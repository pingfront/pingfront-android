package dev.pingfront.internal.session

import dev.pingfront.ErrorCode
import dev.pingfront.PingFrontException
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.Device
import dev.pingfront.internal.wire.DeviceContext
import dev.pingfront.internal.wire.UpdateMeRequest
import dev.pingfront.internal.wire.UpdateMeResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Log.of(Log.Category.Session)

/** What the engine plugs into the coalescer. */
internal class PatchHandlers(
    /** Sends one `PATCH /me`. */
    val send: suspend (UpdateMeRequest) -> UpdateMeResponse,
    /**
     * Applies a successful response. Called under the coalescer's lock and only when no [PatchCoalescer.reset]
     * (logout, erase, revocation) happened while the request was in flight.
     */
    val applied: suspend (UpdateMeResponse) -> Unit,
    /**
     * Device context that rides on a PATCH going out anyway (never a reason to send one). Called outside the
     * coalescer's lock, only when the patch has something else to say.
     */
    val context: suspend () -> DeviceContext? = { null },
    /** The context of a PATCH reached the server, or the server refused it (it is not sent again either way). */
    val contextDelivered: suspend (DeviceContext) -> Unit = {},
)

/**
 * The app's screens list for a PATCH (the protocol, Saved links): [due] gives it when it or the app version changed
 * since it was delivered, and is called only when the patch has something else to say; [delivered] marks it sent
 * (or refused by the server: not sent again either way). Set by `SessionStore`.
 */
internal class PatchScreens(val due: suspend () -> ScreensReport?, val delivered: suspend (ScreensReport) -> Unit)

/**
 * Coalesces profile changes for 250 ms and sends one `PATCH /me` with only the changed fields.
 * Owns the staged and last-sent profile (guarded by a [Mutex]). Sends nothing until a session
 * exists (no network at launch). One request in flight at a time; [reset] cancels it and discards
 * its response.
 */
internal class PatchCoalescer(
    private val scope: CoroutineScope,
    private val records: RecordStore,
    private val window: Duration = 250.milliseconds,
) {
    private val mutex = Mutex()
    private var staged = Profile()
    private var sent = Profile()

    /** What the server has, as field digests kept with the install (review AN-10). */
    private var sentDigest: Map<String, String> = emptyMap()
    private var loaded = false
    private var active = false
    private var flushJob: Job? = null
    private var inFlightJob: Job? = null
    private var inFlight = false
    private var failures = 0
    private var handlers: PatchHandlers? = null

    /** The screens list that rides on a PATCH going out anyway; `null` sends none. */
    @Volatile var screens: PatchScreens? = null

    /** Bumped by [reset]. A response from an older epoch is discarded. */
    private var epoch = 0

    private val _rejections = MutableSharedFlow<PingFrontException>(extraBufferCapacity = 8)

    /** PATCHes that failed (for configuration problems such as a paused app). */
    val rejections: SharedFlow<PingFrontException> = _rejections

    suspend fun setHandlers(handlers: PatchHandlers) = mutex.withLock { this.handlers = handlers }

    /** Stages a change. Fields the contract would refuse are dropped here. If a session is active, a flush follows the window. */
    suspend fun stage(change: ProfileChange) {
        val snapshot = mutex.withLock {
            loadLocked()
            val (clean, dropped) = staged.apply(change).sanitized()
            if (dropped.isNotEmpty()) log.e("Profile fields not sent because they break the contract's rules: ${dropped.joinToString()}")
            staged = clean
            scheduleLocked(window)
            staged
        }
        persist(snapshot)
    }

    /** Drops fields the server refused on `POST /sessions` (422 `fields[]`, or `identity` on 409 `identity_user_mismatch`). */
    suspend fun dropFields(paths: List<String>) {
        val snapshot = mutex.withLock {
            loadLocked()
            staged = staged.without(paths)
            sent = sent.without(paths)
            staged
        }
        persist(snapshot)
    }

    /** The staged profile, sent whole with `POST /sessions`. */
    suspend fun stagedProfile(): Profile = mutex.withLock {
        loadLocked()
        staged
    }

    /** Marks [profile] as delivered (it went out with the session request). */
    suspend fun markSent(profile: Profile) {
        val digest = mutex.withLock {
            loadLocked()
            sent = profile
            sentDigest = ProfileDigest.of(profile)
            sentDigest
        }
        persistDigest(digest)
    }

    /** Marks the device facts as delivered (they went out with a refresh). */
    suspend fun markDeviceSent(device: dev.pingfront.internal.wire.Device) {
        val digest = mutex.withLock {
            loadLocked()
            sent = sent.copy(device = device)
            sentDigest = sentDigest + ProfileDigest.of(sent, setOf("device"))
            sentDigest
        }
        persistDigest(digest)
    }

    /** A session now exists: send anything staged that the server has not seen. */
    suspend fun activate() = mutex.withLock {
        active = true
        scheduleLocked(window)
    }

    /** Logout, erase or revocation: forget everything staged and sent, cancel the PATCH in flight and ignore its response. */
    suspend fun reset() {
        mutex.withLock {
            epoch += 1
            flushJob?.cancel()
            flushJob = null
            inFlightJob?.cancel()
            inFlightJob = null
            inFlight = false
            staged = Profile()
            sent = Profile()
            sentDigest = emptyMap()
            active = false
            failures = 0
            loaded = true
        }
        try {
            records.remove(StoredRecords.profile)
            records.remove(StoredRecords.sentProfileDigest)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The staged profile could not be removed (${e.logDescription}).")
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        try {
            // Profiles staged by an older SDK are checked against today's rules too.
            records.read(StoredRecords.profile)?.let { staged = it.sanitized().first }
            records.read(StoredRecords.sentProfileDigest)?.let { sentDigest = it + sentDigest }
        } catch (e: PingFrontException.StorageUnavailable) {
            // Not readable yet: keep what this run staged and try again on the next change.
            loaded = false
            log.w("The staged profile is not readable yet (${e.logDescription}).")
        }
    }

    private suspend fun persist(profile: Profile) {
        try {
            records.write(StoredRecords.profile, profile)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The staged profile could not be saved (${e.logDescription}); it is kept in memory.")
        }
    }

    private suspend fun persistDigest(digest: Map<String, String>) {
        try {
            records.write(StoredRecords.sentProfileDigest, digest)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The sent profile could not be recorded (${e.logDescription}); the next process may send it again.")
        }
    }

    private fun scheduleLocked(delay: Duration) {
        if (!active || flushJob != null || inFlight) return
        flushJob = scope.launch {
            delay(delay)
            flush()
        }
    }

    private class Work(val snapshot: Profile, val patch: UpdateMeRequest, val handlers: PatchHandlers, val epoch: Int)

    /** [patch] with the device context that rides on it (the partial device gains `context`). */
    private fun withContext(patch: UpdateMeRequest, context: DeviceContext?): UpdateMeRequest =
        if (context == null) patch else patch.copy(device = (patch.device ?: Device()).copy(context = context))

    /** [patch] with the screens list that rides on it (the partial device gains the whole `app`). */
    private fun withScreens(patch: UpdateMeRequest, screens: ScreensReport?): UpdateMeRequest =
        if (screens == null) patch else patch.copy(device = (patch.device ?: Device()).copy(app = screens.app))

    private suspend fun flush() {
        val work = mutex.withLock {
            flushJob = null
            val current = handlers
            if (!active || current == null) return
            loadLocked()
            // What an earlier process already delivered is not sent again (review AN-10).
            val patch = ProfileDigest.withoutDelivered(staged.patch(sent), staged, sentDigest)
            if (patch.isEmpty) {
                sent = staged
                return
            }
            inFlight = true
            inFlightJob = currentCoroutineContext()[Job]
            Work(staged, patch, current, epoch)
        }
        var context: DeviceContext? = null
        var screensReport: ScreensReport? = null
        var delivered: Map<String, String>? = null
        try {
            context = work.handlers.context()
            screensReport = screens?.due?.invoke()
            val response = work.handlers.send(withScreens(withContext(work.patch, context), screensReport))
            context?.let { work.handlers.contextDelivered(it) }
            screensReport?.let { screens?.delivered?.invoke(it) }
            mutex.withLock {
                // A logout, erase or revocation while the PATCH was in flight wins: apply nothing.
                if (epoch != work.epoch) return
                inFlight = false
                inFlightJob = null
                sent = work.snapshot
                sentDigest = sentDigest + ProfileDigest.of(work.snapshot)
                delivered = sentDigest
                failures = 0
                applyRejectedLocked(response)
                work.handlers.applied(response)
                // Changes staged while the request was in flight go out in the next window.
                if (!staged.patch(sent).isEmpty) scheduleLocked(window)
            }
            delivered?.let { persistDigest(it) }
        } catch (e: CancellationException) {
            // reset() already cleared the in-flight state; stop() discards everything.
            throw e
        } catch (e: PingFrontException) {
            // Context the server refused (a 422 naming `device.context`) is dropped like any refused field.
            val refused = (e as? PingFrontException.Server)?.error?.fields.orEmpty().any { it.path.startsWith(CONTEXT_PATH) }
            if (refused) context?.let { work.handlers.contextDelivered(it) }
            val screensRefused = (e as? PingFrontException.Server)?.error?.fields.orEmpty().any { it.path.startsWith(SCREENS_PATH) }
            if (screensRefused) screensReport?.let { screens?.delivered?.invoke(it) }
            failed(work, e)
        }
    }

    private companion object {
        const val CONTEXT_PATH = "device.context"
        const val SCREENS_PATH = "device.app.screens"
    }

    /** `rejected[]` entries are final: the fields stay unsent (never retried), and the identity is dropped on a user mismatch. */
    private fun applyRejectedLocked(response: UpdateMeResponse) {
        if (response.rejected.isEmpty()) return
        log.e("PATCH /me rejected: " + response.rejected.joinToString { "${it.field} (${it.code})" })
        if (response.rejected.any { it.code == ErrorCode.IdentityUserMismatch.raw }) {
            staged = staged.copy(identity = null)
            sent = sent.copy(identity = null)
        }
    }

    private suspend fun failed(work: Work, e: PingFrontException) {
        log.w("PATCH /me failed: ${e.logDescription}")
        mutex.withLock {
            if (epoch != work.epoch) return
            inFlight = false
            inFlightJob = null
            failures += 1
            val fields = (e as? PingFrontException.Server)?.error?.fields.orEmpty()
            when {
                e.isTransient -> scheduleLocked(Backoff.Writes.delay(failures - 1))
                // A 422 names the bad fields: drop them from the pending patch and send the rest.
                fields.isNotEmpty() -> {
                    sent = sent.acceptRejected(work.snapshot, fields.map { it.path })
                    // Refused fields count as delivered in later processes too (review AN-10).
                    sentDigest = sentDigest + ProfileDigest.of(work.snapshot, fields.map { it.path.substringBefore('.') }.toSet())
                    scheduleLocked(window)
                }
            }
        }
        _rejections.tryEmit(e)
    }
}
