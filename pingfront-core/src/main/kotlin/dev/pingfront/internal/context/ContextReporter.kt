package dev.pingfront.internal.context

import dev.pingfront.PingFrontException
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.BuildContext
import dev.pingfront.internal.wire.ConnectionContext
import dev.pingfront.internal.wire.ContextCollection
import dev.pingfront.internal.wire.DeviceContext
import dev.pingfront.internal.wire.DisplayContext
import dev.pingfront.internal.wire.HealthContext
import dev.pingfront.internal.wire.NotificationContext
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.WireRules
import dev.pingfront.internal.wire.withoutPaths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads the platform for each `DeviceContext` group. Every call is cheap (no network, no waiting) and is made
 * only while a request the SDK sends anyway is being built, never at launch. `null` means "unknown here".
 */
internal interface ContextSource {
    fun build(): BuildContext?

    fun display(): DisplayContext?

    fun connection(): ConnectionContext?

    fun health(): HealthContext?

    fun notifications(): NotificationContext?

    /**
     * The default network each time it changes: one default network callback, registered only while collected. The
     * engine collects it once, for both whether the device is online and the connection context (S2).
     */
    fun networkChanges(): Flow<NetworkSample>

    /** No context at all (tests that do not care, and platforms without the readers). */
    object None : ContextSource {
        override fun build(): BuildContext? = null

        override fun display(): DisplayContext? = null

        override fun connection(): ConnectionContext? = null

        override fun health(): HealthContext? = null

        override fun notifications(): NotificationContext? = null

        override fun networkChanges(): Flow<NetworkSample> = emptyFlow()
    }
}

/**
 * One reading of the default network: whether it is usable, and the connection context it gives. The connection
 * (with Data Saver and Battery Saver) is read only when asked, so an app that turned the `connection` group off never
 * has it read (review AN-12).
 */
internal class NetworkSample(
    val online: Boolean,
    val network: Long? = null,
    /** The network has a captive portal (`NET_CAPABILITY_CAPTIVE_PORTAL`): the device is online, the server is not reachable. */
    val captivePortal: Boolean = false,
    private val read: () -> ConnectionContext?,
) {
    fun connection(): ConnectionContext? = read()
}

/** The context for one request, and whether it is a session start (so it counts as such once delivered). */
internal class ContextReport(val context: DeviceContext?, val sessionStart: Boolean)

/**
 * Decides which `DeviceContext` groups ride on the requests the SDK sends anyway (`POST /sessions`,
 * `POST /sessions/refresh`, `PATCH /me`). It never starts a request of its own.
 *
 * - Session start (a create, or the first refresh in this process): every collected group, health included.
 * - Later refreshes and PATCHes: build, display and notifications when they differ from what was delivered;
 *   the connection when it changed and at least [connectionInterval] passed since it was last delivered.
 * - Only the groups the app collects ([ContextCollection], kept with the install); none at all until an install's first
 *   answer, whose next request then counts as the session start (review AN-24).
 *
 * Nothing here is ever logged. Guarded by a [Mutex]; platform reads run on [io].
 */
internal class ContextReporter(
    private val source: ContextSource,
    private val records: RecordStore,
    private val time: TimeSource,
    private val io: CoroutineDispatcher,
    private val connectionInterval: Duration = 5.minutes,
) {
    private val mutex = Mutex()

    /** The app's switches; null until an install's first session answer: then no group is sent at all (review AN-24). */
    private var collection: ContextCollection? = null
    private var loaded = false

    /** Whether [collection] is on disk; a failed write is retried with the next answer, even an identical one. */
    private var stored = false

    /** What the server has from this process (cleared on logout, erase and revocation). */
    private var delivered = DeviceContext()
    private var connectionDeliveredAt: Duration? = null
    private var startedInProcess = false

    /** The context of a PATCH that is this process's session start (the first request after a new install's answer). */
    private var patchIsStart = false

    /** The latest connection from the network callback, coalesced until a request carries it. */
    private var latestConnection: ConnectionContext? = null

    /** For `POST /sessions` ([create]) or `POST /sessions/refresh`. */
    suspend fun forSession(create: Boolean): ContextReport {
        val (allowed, start) = mutex.withLock {
            loadLocked()
            collection to (create || !startedInProcess)
        }
        // The switches are not known yet (a new install's first session): no context, and no session start either, so
        // the first request after the answer carries the allowed groups.
        if (allowed == null) return ContextReport(null, sessionStart = false)
        if (start) {
            val context = read { full(allowed) }
            return ContextReport(clean(context), sessionStart = true)
        }
        return ContextReport(changes(allowed), sessionStart = false)
    }

    /** For a `PATCH /me` that is going out anyway: only what changed. */
    suspend fun forPatch(): DeviceContext? {
        val (allowed, start) = mutex.withLock {
            loadLocked()
            collection to !startedInProcess
        }
        if (allowed == null) return null
        if (start) {
            // The first request after a new install's answer: the full set of allowed groups, as a session start.
            val context = clean(read { full(allowed) })
            mutex.withLock { patchIsStart = context != null }
            return context
        }
        return changes(allowed)
    }

    /** [report] reached the server (or the server refused its context: it is not sent again). */
    suspend fun delivered(report: ContextReport) = mutex.withLock {
        if (report.sessionStart || patchIsStart) startedInProcess = true
        patchIsStart = false
        val context = report.context ?: return@withLock
        delivered = delivered.merging(context)
        if (context.connection != null) connectionDeliveredAt = time.now()
    }

    /** `collect` from a session response; absent (an older server) means every group. Kept with the install. */
    suspend fun collectionReceived(value: ContextCollection?) {
        val next = value ?: ContextCollection.All
        val changed = mutex.withLock {
            val changed = !loaded || !stored || collection != next
            collection = next
            loaded = true
            changed
        }
        if (!changed) return
        try {
            records.write(StoredRecords.contextCollection, next)
            mutex.withLock { if (collection == next) stored = true }
        } catch (_: PingFrontException.StorageUnavailable) {
            // Kept in memory; the next answer stores it, even when it is the same (review AN-13).
            mutex.withLock { stored = false }
        }
    }

    /** Whether the app collects the connection group (the network callback runs only then, review AN-12). */
    suspend fun collectsConnection(): Boolean = mutex.withLock {
        loadLocked()
        collection?.connection == true
    }

    /** The network callback saw [connection]. Never sends anything by itself. */
    suspend fun connectionChanged(connection: ConnectionContext) = mutex.withLock { latestConnection = connection }

    /** Logout, erase or revocation: the next session starts over. The app's collection switches stay. */
    suspend fun reset() = mutex.withLock {
        delivered = DeviceContext()
        connectionDeliveredAt = null
        latestConnection = null
        startedInProcess = false
        patchIsStart = false
    }

    private suspend fun changes(allowed: ContextCollection): DeviceContext? {
        val (sent, latest, connectionDue) = mutex.withLock {
            val at = connectionDeliveredAt
            Triple(delivered, latestConnection, at == null || time.now() - at >= connectionInterval)
        }
        val fresh = read {
            DeviceContext(
                build = if (allowed.build) safe { source.build() }?.takeIf { it != sent.build } else null,
                display = if (allowed.display) safe { source.display() }?.takeIf { it != sent.display } else null,
                notifications = if (allowed.notifications) safe { source.notifications() }?.takeIf { it != sent.notifications } else null,
                connection = if (allowed.connection && connectionDue) {
                    (latest ?: safe { source.connection() })?.takeIf { it != sent.connection }
                } else {
                    null
                },
            )
        }
        return clean(fresh)
    }

    private fun full(allowed: ContextCollection): DeviceContext = DeviceContext(
        build = if (allowed.build) safe { source.build() } else null,
        display = if (allowed.display) safe { source.display() } else null,
        connection = if (allowed.connection) safe { source.connection() } else null,
        health = if (allowed.health) safe { source.health() } else null,
        notifications = if (allowed.notifications) safe { source.notifications() } else null,
    )

    private suspend fun read(block: () -> DeviceContext): DeviceContext = withContext(io) { block() }

    /** A platform reader that fails (a missing service, a SecurityException) leaves its group out; it never fails the request. */
    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (@Suppress("TooGenericExceptionCaught") _: RuntimeException) {
        null
    }

    private suspend fun loadLocked() {
        if (loaded) return
        try {
            val saved = records.read(StoredRecords.contextCollection)
            collection = saved
            stored = saved != null
            loaded = true
        } catch (_: PingFrontException.StorageUnavailable) {
            // Not readable yet: no context, as before the first answer; try again next time.
        }
    }

    /** Drops fields the contract would refuse, so context can never fail the request it rides on. */
    private fun clean(context: DeviceContext): DeviceContext? {
        if (context.isEmpty) return null
        val json = WireJson.encodeToJsonElement(DeviceContext.serializer(), context)
        val problems = WireRules.problems("DeviceContext", json).orEmpty()
        val result = if (problems.isEmpty()) context else WireJson.decodeFromJsonElement(DeviceContext.serializer(), json.withoutPaths(problems))
        return result.takeUnless { it.isEmpty }
    }
}
