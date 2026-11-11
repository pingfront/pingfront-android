package dev.pingfront.internal

import dev.pingfront.PingFrontException
import dev.pingfront.TeamAction
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.Limits
import dev.pingfront.internal.wire.QueuedTeamActionAck
import dev.pingfront.internal.wire.TeamActionAck
import dev.pingfront.internal.wire.TeamActionPayload
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private val log = Log.of(Log.Category.Live)

/**
 * Team actions for the host app: from the live event and from `GET /v1/team-actions` (after a session starts and
 * after every reconnect), deduplicated by id. Acks are stored and retried until the server has them; a 404 (not
 * this person's) or 409 (expired) drops one. Answers go out only while a session is active, like the outbox; an
 * action answered in an earlier launch whose answer is still queued is not delivered again.
 */
internal class TeamActions(
    private val client: AuthorizedClient,
    private val records: RecordStore,
    private val scope: CoroutineScope,
    private val jitter: () -> Double,
    /** The session generation: a drain sends for the session it started in, never for the next person's. */
    private val generation: suspend () -> Int,
    /** Whether a session is active. Answers wait for one: sending them never starts a session at launch. */
    private val sessionActive: suspend () -> Boolean,
) {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val acked = ConcurrentHashMap.newKeySet<String>()

    // Late collectors still get the recent ones; acked actions are filtered out.
    private val emitted = MutableSharedFlow<TeamAction>(replay = REPLAY, extraBufferCapacity = REPLAY)
    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Answers whose write failed (a full disk, a locked Keystore): sent from memory, written again (review AN-9). */
    private val unsaved = java.util.concurrent.CopyOnWriteArrayList<QueuedTeamActionAck>()

    val actions: Flow<TeamAction> = emitted.filter { it.id !in acked }

    private val drain = java.util.concurrent.atomic.AtomicReference<Job?>(null)

    fun start() {
        drain.getAndSet(scope.launch { drainLoop() })?.cancel()
    }

    suspend fun received(payload: TeamActionPayload) {
        if (!seen.add(payload.id)) return
        // Answered in an earlier launch and still waiting to be sent: the app already did the work.
        if (answerQueued(payload.id)) {
            acked.add(payload.id)
            return
        }
        // At most once per install: the id is stored before the app sees the action, so an action handed over in an
        // earlier launch is never handed over again, answered or not. Unwritable storage hands nothing over now; the
        // next catch-up tries again (review AN-9).
        val first = try {
            mutex.withLock {
                val delivered = records.read(StoredRecords.teamActionsDelivered).orEmpty()
                if (payload.id in delivered) {
                    false
                } else {
                    records.write(StoredRecords.teamActionsDelivered, (delivered + payload.id).takeLast(DELIVERED_KEPT))
                    true
                }
            }
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("A team action waits: storage is not available (${e.logDescription}).")
            seen.remove(payload.id)
            return
        }
        if (!first) return
        emitted.emit(
            TeamAction(
                payload.id, payload.actionId, payload.name, payload.fields, payload.days, payload.until,
                payload.ackRequired, payload.createdAt, payload.expiresAt,
            ) { ok, message -> ack(payload.id, ok, message) },
        )
    }

    /** Whether an answer for [id] is stored (or held in memory) and not yet delivered. Unreadable storage counts as no answer. */
    private suspend fun answerQueued(id: String): Boolean = unsaved.any { it.id == id } || try {
        mutex.withLock { records.read(StoredRecords.teamActionAcks).orEmpty().any { it.id == id } }
    } catch (e: PingFrontException.StorageUnavailable) {
        log.w("Team action answers could not be read (${e.logDescription}).")
        false
    }

    /** `GET /v1/team-actions`: the ones still pending, oldest first. */
    suspend fun catchUp(generation: Int) {
        val pending = try {
            client.send(Endpoints.pendingTeamActions(), generation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            log.i("Team actions not loaded: ${e.logDescription}")
            // Queued answers still go out with the session this catch-up ran for.
            return wakeUp()
        }
        pending.actions.forEach { received(it) }
        wakeUp()
    }

    fun ack(id: String, ok: Boolean, message: String?) {
        if (!acked.add(id)) return
        val body = TeamActionAck(ok, message?.let { Limits.truncate(it, MESSAGE_CHARS) })
        val answer = QueuedTeamActionAck(id, body)
        scope.launch {
            if (!store(answer)) unsaved += answer
            wakeUp()
        }
    }

    /** Adds [answer] to the stored queue; false when storage refused it. */
    private suspend fun store(answer: QueuedTeamActionAck): Boolean = try {
        mutex.withLock {
            val queue = records.read(StoredRecords.teamActionAcks).orEmpty()
            if (queue.none { it.id == answer.id }) records.write(StoredRecords.teamActionAcks, queue + answer)
        }
        true
    } catch (e: PingFrontException.StorageUnavailable) {
        log.w("A team action answer is kept in memory until storage is available (${e.logDescription}).")
        false
    }

    /** The next answer to send: a stored one, else one held in memory. Unsaved answers are written again first. */
    private suspend fun nextAnswer(): QueuedTeamActionAck? {
        unsaved.toList().forEach { if (store(it)) unsaved.remove(it) }
        val stored = try {
            mutex.withLock { records.read(StoredRecords.teamActionAcks).orEmpty().firstOrNull() }
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("Team action answers could not be read (${e.logDescription}).")
            null
        }
        return stored ?: unsaved.firstOrNull()
    }

    /** [answer] reached the server: off the stored queue and out of memory. */
    private suspend fun answered(answer: QueuedTeamActionAck) {
        unsaved.removeAll { it.id == answer.id }
        try {
            mutex.withLock {
                records.write(StoredRecords.teamActionAcks, records.read(StoredRecords.teamActionAcks).orEmpty().filterNot { it.id == answer.id })
            }
        } catch (e: PingFrontException.StorageUnavailable) {
            // Still stored: it is sent again later, which the server accepts (acks are idempotent per id).
            log.w("A delivered team action answer could not be removed yet (${e.logDescription}).")
        }
    }

    fun wakeUp() {
        wake.trySend(Unit)
    }

    /** Logout or erase: nothing of the person's stays. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun clear() {
        // The drain is stopped first: an answer of the previous person's is never sent after logout (it would create a
        // guest session on a 401), and the next session's drain starts from an empty queue.
        drain.getAndSet(null)?.cancelAndJoin()
        seen.clear()
        acked.clear()
        unsaved.clear()
        emitted.resetReplayCache()
        mutex.withLock {
            records.remove(StoredRecords.teamActionAcks)
            records.remove(StoredRecords.teamActionsDelivered)
        }
        start()
    }

    private suspend fun drainLoop() {
        var attempt = 0
        while (true) {
            wake.receive()
            // Woken again when a session starts or refreshes (Engine) and after each catch-up.
            if (!sessionActive()) continue
            val session = generation()
            while (true) {
                val next = nextAnswer() ?: break
                val delivered = try {
                    client.send(Endpoints.ackTeamAction(next.id, next.ack), session)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PingFrontException) {
                    // A paused app is temporary but not short: the answer is kept, and no retry runs while paused. The
                    // next session start or refresh (wakeUp) sends it. A logout, erase or revocation since the drain
                    // began: the answer waits for the next wake too (the wipe clears the previous person's).
                    if (e is PingFrontException.SessionChanged || e.code == dev.pingfront.ErrorCode.AppPaused) {
                        log.i("Team action ack waits: ${e.logDescription}")
                        // After the resume, a first transient failure backs off from the base delay again.
                        attempt = 0
                        break
                    }
                    // 404: not this person's; 409: expired; any other refusal would never succeed either.
                    if (isFinalRefusal(e)) {
                        log.i("Team action ack dropped: ${e.logDescription}")
                        true
                    } else {
                        log.i("Team action ack failed, retrying: ${e.logDescription}")
                        false
                    }
                }
                if (!delivered) {
                    withTimeoutOrNull(Backoff.Writes.delay(attempt++, jitter())) { wake.receive() }
                    continue
                }
                attempt = 0
                answered(next)
            }
        }
    }

    /** A refusal the ack would never get past by retrying: a 4xx other than 408 and 429. */
    private fun isFinalRefusal(e: PingFrontException): Boolean {
        val status = (e as? PingFrontException.Server)?.error?.status?.toString()?.toIntOrNull() ?: return false
        return status in HTTP_CLIENT_ERRORS && status != HTTP_TIMEOUT && status != HTTP_TOO_MANY
    }

    private companion object {
        const val REPLAY = 50
        const val MESSAGE_CHARS = 200

        /** Delivered ids kept per install; actions expire within days, so older ids never come back. */
        const val DELIVERED_KEPT = 500
        val HTTP_CLIENT_ERRORS = 400..499
        const val HTTP_TIMEOUT = 408
        const val HTTP_TOO_MANY = 429
    }
}
