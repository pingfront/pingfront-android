package dev.pingfront.internal.messaging

import dev.pingfront.ErrorCode
import dev.pingfront.PingFrontException
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.runtime.WakeSignal
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.CardActionRequest
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

private val log = Log.of(Log.Category.Network)

/** One card action report waiting for the server (the protocol, "Retrying writes"). Its request keeps its `clientId`. */
@Serializable
internal data class QueuedCardAction(
    val cardId: String,
    val request: CardActionRequest,
    /** Wall clock, epoch milliseconds: a report older than a day is dropped, also in a later process. */
    val queuedAt: Long,
    val attempts: Int = 0,
    /** A 409 `thread_reset` already reloaded the thread for it once. */
    val resetRetried: Boolean = false,
)

/**
 * Card action reports (`POST /v1/thread/cards/{cardId}/act`), queued and retried until an answer settles them, in
 * order. Each report keeps the `clientId` it got when it was made, so a retry the server already recorded is a no-op.
 *
 * - Retried: no answer, 408, 429 (at least `retryAfter`), 500, 502, 503, 504, after `min(1 s · 2ⁿ, 5 min)` ±20%
 *   jitter, or at once on [wake] (the network came back, the app returned to the foreground).
 * - 401: the client's own refresh-and-retry runs once; a 401 after it drops the report.
 * - 409 `thread_reset`: the thread is reloaded, and the report goes once more if its message is still there.
 * - Any other 4xx drops it; so do 24 hours or [MAX_ATTEMPTS] attempts.
 *
 * Persisted with the install ([StoredRecords.cardActions]), cleared on logout and erase ([clear]).
 */
internal class CardActionQueue(
    private val records: RecordStore,
    private val client: AuthorizedClient,
    private val session: SessionStore,
    private val time: TimeSource,
    private val jitter: () -> Double,
    /** Reloads the thread after a 409 `thread_reset`. */
    private val threadReset: suspend () -> Unit,
    /** True when the thread still holds the message (checked after a reset). */
    private val hasMessage: (String) -> Boolean,
    /** The app's lifecycle: a return to the foreground retries at once. */
    private val foreground: kotlinx.coroutines.flow.Flow<dev.pingfront.internal.runtime.AppLifecycleEvent> = kotlinx.coroutines.flow.emptyFlow(),
) {
    private val mutex = Mutex()
    private val wakeSignal = WakeSignal()
    private var items: List<QueuedCardAction> = emptyList()
    private var loaded = false

    /** When the head may be tried again (monotonic); [wake] clears it. Not persisted: a new process tries at once. */
    @Volatile private var notBefore: Duration = Duration.ZERO

    /** Queues a report and tries it now. */
    suspend fun enqueue(cardId: String, request: CardActionRequest) {
        mutex.withLock {
            loadLocked()
            // A head that is backing off keeps its backoff: the new report waits behind it, in order.
            if (items.isEmpty()) notBefore = Duration.ZERO
            items = items + QueuedCardAction(cardId, request, time.wall().toEpochMilli())
            persistLocked()
        }
        wakeSignal.signal()
    }

    /** Try the head now (a new report, the network came back, the app returned to the foreground). */
    fun wake() {
        notBefore = Duration.ZERO
        wakeSignal.signal()
    }

    /** The queued reports, for tests. */
    suspend fun all(): List<QueuedCardAction> = mutex.withLock {
        loadLocked()
        items
    }

    /** Logout and erase: nothing queued for the previous person is ever sent. */
    suspend fun clear() {
        mutex.withLock {
            items = emptyList()
            loaded = true
            notBefore = Duration.ZERO
        }
        try {
            records.remove(StoredRecords.cardActions)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("Queued card actions could not be removed (${e.logDescription}).")
        }
    }

    /** Drains the queue until cancelled (the sender's loop owns this job). */
    suspend fun run(): Unit = kotlinx.coroutines.coroutineScope {
        launch { foreground.collect { if (it == dev.pingfront.internal.runtime.AppLifecycleEvent.Foreground) wake() } }
        while (true) {
            val head = next()
            if (head == null) {
                wakeSignal.await()
                continue
            }
            val wait = notBefore - time.now()
            if (wait > Duration.ZERO) {
                wakeSignal.await(wait)
                continue
            }
            attempt(head)
        }
    }

    /** The head, after dropping what aged out. */
    private suspend fun next(): QueuedCardAction? = mutex.withLock {
        loadLocked()
        val now = time.wall().toEpochMilli()
        val fresh = items.filter { now - it.queuedAt <= MAX_AGE.inWholeMilliseconds && it.attempts < MAX_ATTEMPTS }
        if (fresh.size != items.size) {
            log.i("Dropped ${items.size - fresh.size} card action report(s) that waited too long.")
            items = fresh
            persistLocked()
        }
        items.firstOrNull()
    }

    private suspend fun attempt(head: QueuedCardAction) {
        val outcome = try {
            client.send(Endpoints.cardAction(head.cardId, head.request), session.generation())
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            outcome(head, e)
        }
        when (outcome) {
            Outcome.Done -> remove(head)
            is Outcome.Drop -> {
                log.i("Card action report dropped: ${outcome.reason}")
                remove(head)
            }
            is Outcome.Retry -> {
                val next = update(head) { it.copy(attempts = it.attempts + 1) }
                notBefore = time.now() + maxOf(backoff((next?.attempts ?: 1) - 1), outcome.atLeast)
            }
            Outcome.AfterReset -> update(head) { it.copy(resetRetried = true) }
        }
    }

    private sealed interface Outcome {
        data object Done : Outcome

        data class Drop(val reason: String) : Outcome

        data class Retry(val atLeast: Duration) : Outcome

        /** Reloaded after `thread_reset`: the message is still there, so the report goes once more at once. */
        data object AfterReset : Outcome
    }

    private suspend fun outcome(head: QueuedCardAction, e: PingFrontException): Outcome = when (e) {
        is PingFrontException.Network, is PingFrontException.StorageUnavailable -> Outcome.Retry(Duration.ZERO)
        is PingFrontException.CoolingDown -> Outcome.Retry(e.remaining)
        is PingFrontException.Server -> when {
            e.error.status in RETRIED_STATUSES -> Outcome.Retry(e.error.retryAfter ?: Duration.ZERO)
            e.error.code == ErrorCode.ThreadReset && !head.resetRetried -> {
                threadReset()
                if (hasMessage(head.request.messageId)) Outcome.AfterReset else Outcome.Drop("its message is gone after a thread reset")
            }
            else -> Outcome.Drop(e.logDescription)
        }
        else -> Outcome.Drop(e.logDescription)
    }

    /** `min(1 s · 2ⁿ, 5 min)` with ±20% jitter. */
    private fun backoff(attempt: Int): Duration {
        val nominal = minOf(1.seconds * 2.0.pow(attempt.coerceIn(0, MAX_EXPONENT)), MAX_BACKOFF)
        return nominal * (1 + JITTER * jitter().coerceIn(-1.0, 1.0))
    }

    private suspend fun remove(head: QueuedCardAction) = mutex.withLock {
        items = items.filterNot { it.request.clientId == head.request.clientId && it.cardId == head.cardId }
        notBefore = Duration.ZERO
        persistLocked()
    }

    private suspend fun update(head: QueuedCardAction, change: (QueuedCardAction) -> QueuedCardAction): QueuedCardAction? = mutex.withLock {
        var updated: QueuedCardAction? = null
        items = items.map {
            if (it.request.clientId == head.request.clientId && it.cardId == head.cardId) change(it).also { next -> updated = next } else it
        }
        persistLocked()
        updated
    }

    private suspend fun loadLocked() {
        if (loaded) return
        try {
            // Reports queued by an earlier process: they keep their clientId and age.
            items = records.read(StoredRecords.cardActions).orEmpty() + items
            loaded = true
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("Queued card actions are not readable yet (${e.logDescription}).")
        }
    }

    private suspend fun persistLocked() {
        try {
            records.write(StoredRecords.cardActions, items)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("Queued card actions could not be saved (${e.logDescription}); they are kept in memory.")
        }
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 10
        val MAX_AGE: Duration = 24.hours
        val MAX_BACKOFF: Duration = 5.minutes
        private const val MAX_EXPONENT = 16
        private const val JITTER = 0.2
        private val RETRIED_STATUSES = setOf(408, 429, 500, 502, 503, 504)
    }
}
