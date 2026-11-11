package dev.pingfront.internal.messaging

import dev.pingfront.PingFrontException
import dev.pingfront.RateScope
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.policyRefusal
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.storage.Persistence
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.CardReply
import dev.pingfront.model.Message
import dev.pingfront.model.SendPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private val log = Log.of(Log.Category.Outbox)

/** One queued send, with the card reply it answers (not part of the public [PendingMessage]). */
@Serializable
internal data class OutboxItem(val message: PendingMessage, val replyToCard: CardReply? = null)

/**
 * The persistent offline send queue. Survives relaunch (encrypted DataStore, never backed up).
 * Delivery is idempotent on `clientId`. The queue is owned by a [Mutex]; network calls happen
 * outside the lock so new messages can be queued while one is sending.
 */
internal class Outbox(private val persistence: Persistence, private val records: RecordStore = RecordStore(persistence)) {
    sealed interface DrainResult {
        data object Idle : DrainResult

        /** A transient failure (offline, 5xx, rate limit): try again after a backoff. */
        data class RetryLater(val error: PingFrontException) : DrainResult
    }

    private val mutex = Mutex()
    private var items: List<OutboxItem> = emptyList()
    private var loaded = false
    private val _changes = MutableStateFlow<List<PendingMessage>>(emptyList())

    /** The queue after every change, for the UI. */
    val changes: StateFlow<List<PendingMessage>> = _changes

    /** Loads the queue. Items left `Sending` by a killed process go back to `Queued`. */
    suspend fun load(): List<PendingMessage> = mutex.withLock {
        loadLocked()
        items.map { it.message }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        // An unreadable outbox is moved aside by the record store, never overwritten with an empty queue.
        val stored = records.read(StoredRecords.outbox) ?: emptyList()
        loaded = true
        items = stored.map { item ->
            if (item.message.state ==
                PendingMessage.State.Sending
            ) {
                item.copy(message = item.message.copy(state = PendingMessage.State.Queued))
            } else {
                item
            }
        }
        _changes.value = items.map { it.message }
    }

    suspend fun all(): List<OutboxItem> = mutex.withLock {
        loadLocked()
        items
    }

    /** Adds a message and stores its photo bytes (encrypted) next to the queue. */
    suspend fun enqueue(item: OutboxItem, photoData: List<ByteArray> = emptyList()) {
        item.message.photos.zip(photoData).forEach { (photo, data) -> blobs { persistence.write(photo.file, data) } }
        mutex.withLock {
            loadLocked()
            items = items + item
            saveLocked()
        }
    }

    suspend fun retry(clientId: String) = mutex.withLock {
        loadLocked()
        items = items.map {
            if (it.message.clientId == clientId) {
                // A person's Try again starts the attempt count again.
                it.copy(message = it.message.copy(state = PendingMessage.State.Queued, failure = null, attempts = 0, heldForCode = null))
            } else {
                it
            }
        }
        saveLocked()
    }

    /**
     * Queues again the items a closed send policy held (only those held for [reason], when given). Returns true when
     * there were any, so the caller wakes the delivery loop.
     */
    suspend fun retryHeld(reason: SendPolicy.Reason? = null): Boolean = mutex.withLock {
        loadLocked()
        var any = false
        items = items.map {
            val held = it.message.heldFor ?: return@map it
            if (reason != null && held != reason) return@map it
            any = true
            it.copy(message = it.message.copy(state = PendingMessage.State.Queued, failure = null, heldForCode = null))
        }
        if (any) saveLocked()
        any
    }

    /**
     * The policy turned to blocked: held items stop waiting and become plain failures (Delete, or Try again once the
     * person can send), so they no longer keep the queue behind them waiting.
     */
    suspend fun releaseHeld() = mutex.withLock {
        loadLocked()
        if (items.none { it.message.isHeld }) return@withLock
        items = items.map { if (it.message.isHeld) it.copy(message = it.message.copy(heldForCode = null)) else it }
        saveLocked()
    }

    suspend fun discard(clientId: String) {
        val removed = mutex.withLock {
            loadLocked()
            val item = items.firstOrNull { it.message.clientId == clientId }
            items = items.filterNot { it.message.clientId == clientId }
            saveLocked()
            item
        }
        removed?.message?.photos?.forEach { blobs { persistence.remove(it.file) } }
    }

    suspend fun clear() {
        val old = mutex.withLock {
            loadLocked()
            val all = items
            items = emptyList()
            saveLocked()
            all
        }
        old.flatMap { it.message.photos }.forEach { blobs { persistence.remove(it.file) } }
    }

    suspend fun photoData(photo: PendingMessage.Photo): ByteArray? = blobs { persistence.read(photo.file) }

    /** Photo blobs go straight to [Persistence]: map its failures like the record store does (review CS-6). */
    private suspend fun <T> blobs(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        throw PingFrontException.StorageUnavailable(e)
    } catch (e: java.security.ProviderException) {
        throw PingFrontException.StorageUnavailable(e)
    }

    suspend fun recordUpload(clientId: String, file: String, attachmentId: String) = mutex.withLock {
        items = items.map { item ->
            if (item.message.clientId != clientId) return@map item
            item.copy(
                message = item.message.copy(
                    photos = item.message.photos.map {
                        if (it.file ==
                            file
                        ) {
                            it.copy(attachmentId = attachmentId)
                        } else {
                            it
                        }
                    },
                ),
            )
        }
        saveLocked()
    }

    /**
     * Sends queued items in order, and is the only place that decides what goes next. A held item ([PendingMessage.heldFor])
     * stops the drain: nothing behind it goes before it, whenever [retryHeld] queues it again. Items that need uploads
     * wait while [uploadsPaused], but text keeps going. A plain failure waits for the person (Try again or Delete) and
     * does not hold up the rest.
     */
    suspend fun drain(uploadsPaused: Boolean, deliver: suspend (OutboxItem) -> Message): DrainResult {
        val passed = mutableSetOf<String>()
        while (true) {
            val next = mutex.withLock {
                loadLocked()
                val index = nextIndex(passed, uploadsPaused) ?: return DrainResult.Idle
                val item = items[index]
                val sending = item.copy(
                    message = item.message.copy(state = PendingMessage.State.Sending, attempts = item.message.attempts + 1),
                )
                items = items.toMutableList().also { it[index] = sending }
                _changes.value = items.map { it.message }
                sending
            }
            val clientId = next.message.clientId
            passed += clientId
            try {
                deliver(next)
                mutex.withLock {
                    items = items.filterNot { it.message.clientId == clientId }
                    saveLocked()
                }
                next.message.photos.forEach { photo ->
                    try {
                        blobs { persistence.remove(photo.file) }
                    } catch (e: PingFrontException.StorageUnavailable) {
                        log.w("A sent photo could not be removed yet (${e.logDescription}).")
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { setState(clientId, PendingMessage.State.Queued, null) }
                throw e
            } catch (@Suppress("SwallowedException") e: PingFrontException.SessionChanged) {
                // Expected: the session changed mid-delivery (revocation). The item waits; the engine decides what to send next.
                setState(clientId, PendingMessage.State.Queued, null)
                return DrainResult.Idle
            } catch (e: PingFrontException) {
                failed(next, e)?.let { return it }
            }
        }
    }

    /**
     * The next item to send, in queue order: `null` when none is left, or when a held item comes first (the items behind
     * it wait for it). Call with [mutex] held.
     */
    private fun nextIndex(passed: Set<String>, uploadsPaused: Boolean): Int? {
        for ((index, item) in items.withIndex()) {
            val message = item.message
            if (message.isHeld) return null
            if (message.clientId in passed || message.state != PendingMessage.State.Queued) continue
            if (uploadsPaused && message.photos.any { it.attachmentId == null }) continue
            return index
        }
        return null
    }

    /** Records a failed delivery. Returns a result to stop the drain with, or `null` to go on with the next item. */
    private suspend fun failed(item: OutboxItem, e: PingFrontException): DrainResult? {
        val clientId = item.message.clientId
        policyRefusal(e)?.let { closed ->
            refused(clientId, closed.reason, describe(e))
            // The person can't send now: every later item would be refused too.
            return DrainResult.Idle
        }
        // After repeated transient failures the message shows as failed with Try again and Delete, instead of
        // waiting forever (audit A-06). Offline never counts: it waits for the connection.
        val givesUp = e.isTransient && !e.isOffline && item.message.attempts >= MAX_ATTEMPTS && e !is PingFrontException.CoolingDown
        if (e.isTransient && !givesUp) {
            setState(clientId, PendingMessage.State.Queued, null)
            return if (e is PingFrontException.CoolingDown && e.scope == RateScope.Uploads) null else DrainResult.RetryLater(e)
        }
        setState(clientId, PendingMessage.State.Failed, describe(e))
        return null
    }

    /**
     * A closed policy refused [clientId]. A pause or a required sign-in holds it: it waits, failed, for sending to
     * reopen ([retryHeld]), and then goes again by itself, first. Blocked promises nothing: this one and the ones queued
     * behind it fail plainly, unsent.
     */
    private suspend fun refused(clientId: String, reason: SendPolicy.Reason, failure: String) = mutex.withLock {
        val at = items.indexOfFirst { it.message.clientId == clientId }
        if (at < 0) return@withLock
        items = items.mapIndexed { index, item ->
            val message = item.message
            when {
                reason.holdsMessages && index == at ->
                    item.copy(message = message.copy(state = PendingMessage.State.Failed, failure = failure, heldForCode = reason.code))
                !reason.holdsMessages && (index == at || (index > at && message.state == PendingMessage.State.Queued)) ->
                    item.copy(message = message.copy(state = PendingMessage.State.Failed, failure = failure))
                else -> item
            }
        }
        saveLocked()
    }

    private suspend fun setState(clientId: String, state: PendingMessage.State, failure: String?) = mutex.withLock {
        items =
            items.map { if (it.message.clientId == clientId) it.copy(message = it.message.copy(state = state, failure = failure)) else it }
        saveLocked()
    }

    private suspend fun saveLocked() {
        _changes.value = items.map { it.message }
        try {
            if (items.isEmpty()) records.remove(StoredRecords.outbox) else records.write(StoredRecords.outbox, items)
        } catch (e: PingFrontException) {
            log.e("Could not save the outbox (${e.logDescription}); the queue is kept in memory.")
        }
    }

    companion object {
        /** Sends that failed this often (not offline, not rate-limited) stop retrying until the person taps Try again. */
        const val MAX_ATTEMPTS: Int = 5

        fun describe(error: PingFrontException): String = when (error) {
            is PingFrontException.Server -> error.error.code.raw
            is PingFrontException.InvalidInput -> error.message ?: "invalid_input"
            else -> error.message ?: error.javaClass.simpleName
        }
    }
}
