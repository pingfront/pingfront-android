package dev.pingfront.internal.session

import dev.pingfront.ErrorCode
import dev.pingfront.PingFrontException
import dev.pingfront.internal.context.ContextReport
import dev.pingfront.internal.context.ContextReporter
import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.net.TokenProvider
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.CreateSessionRequest
import dev.pingfront.internal.wire.Device
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.RefreshSessionRequest
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.model.IdentityProblem
import dev.pingfront.model.PersonSummary
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val log = Log.of(Log.Category.Session)

/** What the session store reports to the engine. */
internal sealed interface SessionEvent {
    /** [generation] is the session generation the create ran in: a handler that sees a newer one ignores it. */
    data class Started(val person: PersonSummary, val testDevice: Boolean, val identityProblem: IdentityProblem?, val generation: Int) : SessionEvent

    data class Refreshed(val person: PersonSummary, val testDevice: Boolean, val generation: Int) : SessionEvent

    /** The server revoked the install secret. The session store forgot the install; the engine wipes the rest. A new guest session follows. */
    data object Revoked : SessionEvent

    /** `POST /sessions` refused the staged identity (409 `identity_user_mismatch`): it was dropped and the session started without it. */
    data object IdentityRejected : SessionEvent
}

/**
 * Owns the install credentials (encrypted with the Keystore key) and the short-lived access token
 * (memory only). Creates sessions lazily and single-flights concurrent token requests.
 */
internal class SessionStore(
    private val api: ApiClient,
    private val records: RecordStore,
    private val time: TimeSource,
    private val profile: PatchCoalescer,
    private val scope: CoroutineScope,
    private val device: (installId: String) -> Device,
    /** Device context for session requests; `null` sends none (tests that do not care). */
    private val context: ContextReporter? = null,
    /** The app's registered screens for session requests; `null` sends none (tests that do not care). */
    private val screens: ScreenReporter? = null,
) : TokenProvider {

    init {
        // The screens list also rides on a PATCH that goes out anyway, when it or the app version changed.
        profile.screens = screens?.let { reporter -> PatchScreens({ reporter.forUpdate(device("").app) }, reporter::delivered) }
    }

    private val mutex = Mutex()
    private var credentials: InstallCredentials? = null
    private var loaded = false
    private var accessToken: String? = null
    private var validUntil: Duration = Duration.ZERO
    private var inflight: Deferred<Result<String>>? = null
    private var hostLookPending: dev.pingfront.model.HostLook? = null
    private var person: PersonSummary? = null
    private var testDevice = false
    private var storeAccountToken: String? = null

    /** The install in [credentials] could not be written when it was created: written again with the next refresh. */
    private var credentialsUnsaved = false

    /**
     * Bumped by [wipe], [beginErase] and revocation. A create or refresh that started in an older generation is
     * discarded. Written under [mutex]; volatile so [generationNow] can read it without suspending.
     */
    @Volatile private var generation = 0

    /** True from [beginErase] until the wipe: every token request fails fast, so nothing re-registers the person. */
    private var erasing = false

    /**
     * True while [wipe] runs: every token request fails fast until the staged profile and the install are gone, so a
     * caller without a generation (a PATCH flush, a push open) never creates a session with the previous person's
     * identity in the middle of a logout.
     */
    private var wiping = false

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)

    /** Session lifecycle events. */
    val events: SharedFlow<SessionEvent> = _events

    suspend fun isTestDevice(): Boolean = mutex.withLock { testDevice }

    /** The per-person UUID billing modules pass as `obfuscatedAccountId`. */
    suspend fun storeAccountToken(): String? = mutex.withLock { storeAccountToken }

    /** The current generation (changes on logout, erase and revocation). */
    override suspend fun generation(): Int = mutex.withLock { generation }

    /** The current generation without suspending: for calls from the app's thread (a send), compared later. */
    fun generationNow(): Int = generation

    suspend fun isActive(): Boolean = mutex.withLock { accessToken != null }

    /** True when this install has a session from an earlier launch (no network). False while storage is unavailable. */
    suspend fun hasInstall(): Boolean = try {
        installCredentials() != null
    } catch (_: PingFrontException.StorageUnavailable) {
        false
    }

    suspend fun installCredentials(): InstallCredentials? = mutex.withLock {
        loadLocked()
        credentials
    }

    /** A valid access token, creating or refreshing the session if needed. Throws [PingFrontException]. */
    override suspend fun token(reason: SessionReason): String = token(reason, null)

    /**
     * Like [token], for a call that belongs to session [expected]: after a logout, erase or revocation it throws
     * [PingFrontException.SessionChanged] before anything is created, so a call from before the wipe never starts
     * the next person's session (review AN-3).
     */
    override suspend fun token(reason: SessionReason, expected: Int?): String {
        val pending = mutex.withLock {
            if (erasing || wiping || (expected != null && expected != generation)) throw PingFrontException.SessionChanged()
            val token = accessToken
            if (token != null && time.now() < validUntil) return token
            // A finished request whose callers all went away is never reused (its token may be invalidated by now).
            inflight?.takeUnless { it.isCompleted } ?: scope.async { establish(reason, generation) }.also { inflight = it }
        }
        val result = try {
            pending.await()
        } catch (e: CancellationException) {
            // The shared request was cancelled by a logout, erase or revocation, not this caller:
            // report that as a PingFrontException instead of cancelling a caller that is still active.
            currentCoroutineContext().ensureActive()
            throw PingFrontException.SessionChanged()
        } finally {
            withContext(NonCancellable) { mutex.withLock { if (inflight === pending && pending.isCompleted) inflight = null } }
        }
        return result.getOrThrow()
    }

    /** Drops the access token after a 401 so the next call refreshes. */
    override suspend fun invalidate(token: String) {
        mutex.withLock { if (accessToken == token) accessToken = null }
    }

    /**
     * A bearer route answered 401 `session_revoked` for [token] (for example the person erased their data on
     * another device). The next token request refreshes, which reports the revocation and starts over.
     */
    override suspend fun revoked(token: String) = invalidate(token)

    /**
     * Erase begins: no token request may re-register the person while `DELETE /me` is in flight. A create
     * already in flight is allowed to finish first, because the server may have made the person already: its
     * install is then known and erased too (review AN-4). Returns the install to erase, null when there is none.
     * The staged profile is wiped before `DELETE /me`, so a failed erase restores nothing.
     */
    suspend fun beginErase(): InstallCredentials? {
        val pending = mutex.withLock {
            erasing = true
            inflight?.takeUnless { it.isCompleted }
        }
        if (pending != null) {
            try {
                pending.await()
            } catch (e: CancellationException) {
                // The create was cancelled (stop): this erase goes on unless it was cancelled itself.
                currentCoroutineContext().ensureActive()
            }
        }
        val existing = try {
            installCredentials()
        } catch (e: PingFrontException.StorageUnavailable) {
            mutex.withLock { erasing = false }
            throw e
        }
        mutex.withLock {
            generation += 1
            inflight?.cancel()
            inflight = null
            accessToken = null
        }
        profile.reset()
        return existing
    }

    /** Erase failed: sessions work again (the install still exists on the server, the profile stays wiped). */
    suspend fun endErase() = mutex.withLock { erasing = false }

    /** Forgets the install (logout, erase). The next session is a new guest. */
    suspend fun wipe() {
        mutex.withLock {
            wiping = true
            generation += 1
            inflight?.cancel()
            inflight = null
            accessToken = null
        }
        try {
            // The staged profile (the person's identity) goes before anything else can suspend for long.
            profile.reset()
            clearLocal()
            mutex.withLock { erasing = false }
        } finally {
            withContext(NonCancellable) { mutex.withLock { wiping = false } }
        }
    }

    /** The host look to send with the next session create (once per app version). */
    suspend fun setPendingHostLook(look: dev.pingfront.model.HostLook?) = mutex.withLock { hostLookPending = look }

    private suspend fun clearLocal() {
        mutex.withLock {
            generation += 1
            accessToken = null
            person = null
            testDevice = false
            credentials = null
            credentialsUnsaved = false
            loaded = true
        }
        mutex.withLock { storeAccountToken = null }
        // A new install sends its screens with its first session.
        screens?.reset()
        try {
            records.remove(StoredRecords.credentials)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The install record could not be removed yet (${e.logDescription}).")
        }
    }

    /** Throws [PingFrontException.StorageUnavailable] (and stays unloaded) when the Keystore cannot be used yet. */
    private suspend fun loadLocked() {
        if (loaded) return
        credentials = records.read(StoredRecords.credentials)
        loaded = true
    }

    private fun stale(): Result<String> = Result.failure(PingFrontException.SessionChanged())

    private suspend fun establish(reason: SessionReason, started: Int): Result<String> {
        // Unavailable storage is not "no install": wait for it instead of minting a new install.
        val existing = try {
            installCredentials()
        } catch (e: PingFrontException.StorageUnavailable) {
            return Result.failure(e)
        }
        if (existing != null) {
            saveUnsaved(existing, started)
            try {
                // Refresh carries the device facts, so time zone, locale and versions stay current.
                val partialDevice = device(existing.installId).copy(installId = null)
                // Device context rides on the refresh (the full set when it starts this process's session).
                val report = context?.forSession(create = false)
                // The screens list rides along only when it or the app version changed (the protocol, Saved links).
                val screensReport = screens?.forUpdate(partialDevice.app)
                val sentDevice = partialDevice.copy(context = report?.context, app = screensReport?.app ?: partialDevice.app)
                val request = RefreshSessionRequest(existing.installId, existing.installSecret, sentDevice)
                val response = api.send(Endpoints.refreshSession(request))
                val current = mutex.withLock {
                    if (generation != started) return@withLock false
                    store(response.accessToken, response.accessExpiresIn)
                    person = response.person
                    testDevice = response.testDevice
                    storeAccountToken = response.storeAccountToken ?: storeAccountToken
                    true
                }
                if (!current) return stale()
                contextAnswered(report, response.collect)
                screensReport?.let { screens?.delivered(it) }
                profile.markDeviceSent(partialDevice)
                _events.emit(SessionEvent.Refreshed(response.person, response.testDevice, started))
                profile.activate()
                return Result.success(response.accessToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                when (e.code) {
                    ErrorCode.SessionRevoked -> {
                        // Wipe and stop: the request that asked fails, and the next one (the next chat open) starts a
                        // new guest. Nothing is retried under the old install (shared scenario auth-refresh-revoked).
                        log.i("Install secret revoked; local state cleared, the next request starts a new guest.")
                        if (mutex.withLock { generation != started }) return stale()
                        profile.reset()
                        clearLocal()
                        // Reset here, so the next guest's create counts as the session start.
                        context?.reset()
                        _events.emit(SessionEvent.Revoked)
                        return Result.failure(e)
                    }
                    // The secret is unknown to the server (for example a reset dev database). Create a new one.
                    ErrorCode.Unauthorized, ErrorCode.NotFound -> Unit
                    else -> return Result.failure(e)
                }
            }
        }
        return create(reason, existing, started)
    }

    private suspend fun create(reason: SessionReason, known: InstallCredentials?, started: Int): Result<String> {
        val first = createOnce(reason, known, started, withContext = true, withScreens = true)
        val error = first.exceptionOrNull() as? PingFrontException.Server ?: return first
        // A profile field must never block the session: drop what the server named and try once more.
        val paths = if (error.error.status == HTTP_UNPROCESSABLE) error.error.fields.map { it.path } else emptyList()
        val dropped = when {
            error.code == ErrorCode.IdentityUserMismatch -> listOf("identity")
            else -> paths.filter { it.substringBefore('.') in PROFILE_FIELDS }
        }
        // Device context never blocks the session either: refused, the retry goes without it.
        val contextRefused = paths.any { it.startsWith(CONTEXT_PATH) }
        // Nor does the screens list: refused, it is not sent again until it or the app version changes.
        val screensRefused = paths.any { it.startsWith(SCREENS_PATH) }
        if (screensRefused) screens?.forCreate(device("").app)?.let { screens.delivered(it) }
        if ((dropped.isEmpty() && !contextRefused && !screensRefused) || mutex.withLock { generation != started }) return first
        if (dropped.isNotEmpty()) {
            log.e("POST /sessions refused ${dropped.joinToString()}; starting the session without ${if (dropped.size == 1) "it" else "them"}.")
            profile.dropFields(dropped)
        }
        if (error.code == ErrorCode.IdentityUserMismatch) _events.emit(SessionEvent.IdentityRejected)
        return createOnce(reason, known, started, withContext = !contextRefused, withScreens = !screensRefused)
    }

    private suspend fun createOnce(
        reason: SessionReason,
        known: InstallCredentials?,
        started: Int,
        withContext: Boolean,
        withScreens: Boolean,
    ): Result<String> {
        val installId = known?.installId ?: UUID.randomUUID().toString()
        var staged = profile.stagedProfile()
        mutex.withLock { hostLookPending }?.let { staged = staged.copy(hostLook = it) }
        val fullDevice = device(installId)
        val report = if (withContext) context?.forSession(create = true) else ContextReport(null, sessionStart = true)
        val screensReport = if (withScreens) screens?.forCreate(fullDevice.app) else null
        val request = CreateSessionRequest(
            device = fullDevice.copy(context = report?.context, app = screensReport?.app ?: fullDevice.app),
            // Re-registering a known installId needs its secret; without it the server mints a new id.
            installSecret = known?.installSecret,
            identity = staged.identity,
            traits = staged.traits.takeIf { it.isNotEmpty() },
            entitlement = staged.entitlement,
            offers = staged.offers,
            hostLook = staged.hostLook,
            reason = reason.wire,
        )
        return try {
            val response = api.send(Endpoints.createSession(request))
            // Adopt the id the server registered (a fresh one when the old id could not be reused).
            val newCredentials = InstallCredentials(response.installId ?: installId, response.installSecret)
            // A logout or erase while the request was in flight wins: store nothing, announce nothing.
            if (mutex.withLock { generation != started }) return stale()
            // The server made the install: it is kept in memory for this process even when it cannot be written, so a
            // retry refreshes it instead of minting another install. The write is tried again with the next refresh.
            val saved = try {
                records.write(StoredRecords.credentials, newCredentials)
                true
            } catch (e: PingFrontException.StorageUnavailable) {
                log.w("The install could not be stored yet (${e.logDescription}); it is kept in memory and stored later.")
                false
            }
            val current = mutex.withLock {
                if (generation != started) return@withLock false
                credentials = newCredentials
                loaded = true
                credentialsUnsaved = !saved
                store(response.accessToken, response.accessExpiresIn)
                person = response.person
                testDevice = response.testDevice
                storeAccountToken = response.storeAccountToken
                hostLookPending = null
                true
            }
            if (!current) {
                records.remove(StoredRecords.credentials)
                return stale()
            }
            contextAnswered(report, response.collect)
            screensReport?.let { screens?.delivered(it) }
            // The push token is not part of POST /sessions; leave it unsent so the PATCH carries it.
            profile.markSent(staged.copy(push = null, device = fullDevice.copy(installId = null)))
            profile.activate()
            _events.emit(SessionEvent.Started(response.person, response.testDevice, response.identityProblem, started))
            log.i("Session created (${response.person.level.name.lowercase()})")
            Result.success(response.accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            Result.failure(e)
        }
    }

    /**
     * Writes [existing] again when its first write failed, unless a logout, erase or revocation came first. Written
     * under the lock (a rare recovery path), so a wipe's removal can never be overtaken by this write.
     */
    private suspend fun saveUnsaved(existing: InstallCredentials, started: Int) {
        try {
            mutex.withLock {
                if (!credentialsUnsaved || credentials != existing || generation != started) return
                records.write(StoredRecords.credentials, existing)
                credentialsUnsaved = false
            }
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The install still could not be stored (${e.logDescription}).")
        }
    }

    /** The session request carrying [report] was answered: its context counts as delivered, and [collect] applies from now on. */
    private suspend fun contextAnswered(report: ContextReport?, collect: dev.pingfront.internal.wire.ContextCollection?) {
        val reporter = context ?: return
        reporter.collectionReceived(collect)
        report?.let { reporter.delivered(it) }
    }

    private companion object {
        const val HTTP_UNPROCESSABLE = 422
        const val CONTEXT_PATH = "device.context"
        const val SCREENS_PATH = "device.app.screens"
        val PROFILE_FIELDS = setOf("identity", "traits", "entitlement", "offers", "hostLook")
    }

    private fun store(token: String, expiresInSeconds: Long) {
        accessToken = token
        // Scheduled from the server's `accessExpiresIn`, never from the device clock; refresh a minute
        // early (or halfway, for short tokens).
        val lifetime = expiresInSeconds.coerceAtLeast(1).seconds
        validUntil = time.now() + lifetime - minOf(60.seconds, lifetime / 2)
    }
}
