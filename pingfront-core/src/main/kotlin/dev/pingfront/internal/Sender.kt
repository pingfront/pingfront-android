package dev.pingfront.internal

import dev.pingfront.ErrorCode
import dev.pingfront.PingFrontException
import dev.pingfront.RateScope
import dev.pingfront.conversation.Conversation
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.messaging.ImagePreparation
import dev.pingfront.internal.messaging.Outbox
import dev.pingfront.internal.messaging.OutboxItem
import dev.pingfront.internal.messaging.Uploader
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.runtime.WakeSignal
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.storage.Records
import dev.pingfront.internal.wire.CardReply
import dev.pingfront.internal.wire.Limits
import dev.pingfront.internal.wire.SendMessageRequest
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.model.Message
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = Log.of(Log.Category.Outbox)

/** What the [Sender] needs from the engine that owns the thread order and the session events. */
internal interface SenderHost {
    suspend fun noteFailure(error: PingFrontException)

    suspend fun refreshCooldowns()

    suspend fun threadReset()

    /** Merges a just-sent message if [generation] is still the session's; throws [PingFrontException.SessionChanged] otherwise. */
    suspend fun mergeSent(generation: Int, message: Message)

    /** Called after an acknowledged send (activity, thread cache). */
    suspend fun sent()

    /** A send failed with [error]; a refusal that says the person can't send (blocked, paused, verification) closes the policy. */
    suspend fun sendRefused(error: PingFrontException)

    /** False while the system reports no network: nothing is sent until it returns (it wakes the loop). */
    fun systemOnline(): Boolean

    /** Runs [block], logging any failure except cancellation (see `Engine.guarded`). */
    suspend fun guarded(what: String, block: suspend () -> Unit)
}

/**
 * The person's outgoing messages: placeholders while photos are prepared, the persistent outbox, and the
 * delivery loop (uploads, then an idempotent send). Split out of the engine; the engine starts and stops
 * the loop and wakes it.
 */
internal class Sender(
    private val host: SenderHost,
    private val scope: CoroutineScope,
    private val conversation: () -> Conversation,
    private val outbox: Outbox,
    private val session: SessionStore,
    private val client: AuthorizedClient,
    private val uploader: Uploader,
    private val time: TimeSource,
    private val deps: Dependencies,
    private val noteActivity: () -> Unit,
) {
    /** One send, tied to the session [generation] it was made in (a logout or erase while it is queued drops it). */
    private class SendJob(val placeholder: PendingMessage, val photos: List<ByteArray>, val replyTo: CardReply?, val generation: Int)

    private val wakeSignal = WakeSignal()

    /** Card action reports, queued and retried with their own rules (the protocol, "Retrying writes"). */
    val cardActions = dev.pingfront.internal.messaging.CardActionQueue(
        dev.pingfront.internal.storage.RecordStore(deps.persistence),
        client,
        session,
        time,
        deps.jitter,
        threadReset = { host.threadReset() },
        hasMessage = { id -> conversation().state.value.messages.any { it.id == id } },
        foreground = deps.lifecycle,
    )
    private val cardLoop = AtomicReference<Job?>(null)
    private val jobs = Channel<SendJob>(Channel.UNLIMITED)
    private val loopJob = AtomicReference<Job?>(null)
    private val _placeholders = MutableStateFlow<List<PendingMessage>>(emptyList())

    /** Messages being prepared (photos re-encoded) before they reach the outbox. */
    val placeholders: StateFlow<List<PendingMessage>> = _placeholders

    /** Starts the queueing loop (one job at a time, in order). */
    fun start() {
        scope.launch {
            for (job in jobs) {
                try {
                    enqueue(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PingFrontException) {
                    // Storage refused the message (a full disk, a locked Keystore): show it as failed, keep the loop (review CS-6).
                    log.e("A message could not be queued: ${e.logDescription}")
                    _placeholders.update { list ->
                        list.map {
                            if (it.clientId == job.placeholder.clientId) it.copy(state = PendingMessage.State.Failed, failure = Outbox.describe(e)) else it
                        }
                    }
                }
            }
        }
        startLoop()
    }

    fun close() = jobs.close()

    /** Drains the outbox now; with [retryCards] (the network came back, the live channel reconnected) the queued card actions go at once too. */
    fun wake(retryCards: Boolean = false) {
        wakeSignal.signal()
        if (retryCards) cardActions.wake()
    }

    /** (Re)starts the delivery loop. */
    fun startLoop() {
        loopJob.getAndSet(scope.launch { loop() })?.cancel()
        cardLoop.getAndSet(scope.launch { cardActions.run() })?.cancel()
    }

    /** Cancels the delivery loop (logout and erase), so nothing queued before it is sent as the next person. */
    fun stopLoop() {
        loopJob.getAndSet(null)?.cancel()
        cardLoop.getAndSet(null)?.cancel()
    }

    /** Logout and erase: the messages being prepared and the queued card action reports go. */
    suspend fun clearLocal() {
        _placeholders.value = emptyList()
        cardActions.clear()
    }

    fun send(text: String?, photos: List<ByteArray>, replyTo: CardReply?) {
        noteActivity()
        val placeholder =
            PendingMessage(clientId = UUID.randomUUID().toString(), text = text?.let { Limits.truncate(it, Limits.TEXT_CHARS) }, createdAt = time.wall())
        _placeholders.update { list -> list + placeholder }
        if (replyTo == null) conversation().updateDraft("")
        jobs.trySend(SendJob(placeholder, photos, replyTo, session.generationNow()))
    }

    /** Sends are prepared and queued one at a time, in the order the person sent them. */
    private suspend fun enqueue(job: SendJob) {
        val clientId = job.placeholder.clientId
        val prepared = mutableListOf<PendingMessage.Photo>()
        val bytes = mutableListOf<ByteArray>()
        withContext(deps.dispatchers.default) {
            job.photos.take(Limits.IMAGES_PER_MESSAGE).forEachIndexed { index, data ->
                try {
                    val image = ImagePreparation.prepare(data)
                    prepared += PendingMessage.Photo(Records.photo(clientId, index), image.width, image.height, image.data.size, image.sha256)
                    bytes += image.data
                } catch (e: PingFrontException) {
                    log.e("Photo skipped: ${e.logDescription}")
                }
            }
        }
        if (job.placeholder.text == null && prepared.isEmpty() && job.replyTo == null) {
            _placeholders.update { list -> list.filterNot { it.clientId == clientId } }
            return
        }
        // A logout or erase ran while the photos were prepared: the message belongs to the previous person. It is
        // dropped, never queued for the next one (review AN-1). Checked again after the write, which a wipe can pass.
        if (session.generation() != job.generation) return dropStale(clientId)
        outbox.enqueue(OutboxItem(job.placeholder.copy(photos = prepared), job.replyTo), bytes)
        if (session.generation() != job.generation) {
            outbox.discard(clientId)
            return dropStale(clientId)
        }
        _placeholders.update { list -> list.filterNot { it.clientId == clientId } }
        if (!session.isActive()) {
            scope.launch {
                try {
                    if (session.generation() == job.generation) session.token(SessionReason.ChatOpened)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: PingFrontException.SessionChanged) {
                    // Logged out meanwhile: the message went with the wipe, and no session starts for it.
                } catch (e: PingFrontException) {
                    host.noteFailure(e)
                    if (e.isOffline) deps.retry.scheduleOutboxRetry()
                }
            }
        }
        wake()
    }

    private fun dropStale(clientId: String) {
        log.i("A message sent before logout or erase was dropped.")
        _placeholders.update { list -> list.filterNot { it.clientId == clientId } }
    }

    fun retry(clientId: String) {
        scope.launch {
            host.guarded("retry") {
                outbox.retry(clientId)
                wake()
            }
        }
    }

    fun discard(clientId: String) {
        _placeholders.update { list -> list.filterNot { it.clientId == clientId } }
        scope.launch {
            host.guarded("discard") {
                outbox.discard(clientId)
                // A deleted held head no longer keeps the messages behind it waiting.
                wake()
            }
        }
    }

    /**
     * Sends queued messages whenever woken. Survives every failure except real cancellation (logout,
     * stop): a session change mid-delivery puts the item back and the loop waits for the next wake
     * (review CS-1).
     */
    private suspend fun loop() {
        var attempt = 0
        while (true) {
            wakeSignal.await()
            try {
                if (!session.isActive() || !host.systemOnline()) continue
                attempt = drainUntilIdle(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                log.w("Outbox drain failed: ${e.logDescription}")
                wakeSignal.await(Backoff.Writes.delay(attempt++, deps.jitter()))
                wake()
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // A bug or an unexpected platform failure: logged and survived, never a dead loop.
                log.e("Outbox drain failed unexpectedly (${e.javaClass.simpleName}).")
                wakeSignal.await(Backoff.Writes.delay(attempt++, deps.jitter()))
                wake()
            }
        }
    }

    /** Drains until nothing transient is left. Returns the backoff attempt to continue from. */
    private suspend fun drainUntilIdle(start: Int): Int {
        var attempt = start
        while (true) {
            val result = drainOnce()
            if (result !is Outbox.DrainResult.RetryLater) return 0
            val error = result.error
            host.noteFailure(error)
            if (error.isOffline) deps.retry.scheduleOutboxRetry()
            // The network went away mid-drain: wait for it to return (it wakes the loop) instead of backing off.
            if (!host.systemOnline()) return 0
            val wait = (error as? PingFrontException.CoolingDown)?.remaining ?: Backoff.Writes.delay(attempt++, deps.jitter())
            wakeSignal.await(wait)
        }
    }

    /** One pass over the queue (also the WorkManager retry). */
    suspend fun drainOnce(): Outbox.DrainResult {
        val uploadsPaused = client.cooldowns.remaining(RateScope.Uploads) != null
        val result = outbox.drain(uploadsPaused) { deliver(it) }
        host.refreshCooldowns()
        return result
    }

    /** Uploads missing photos, then sends with the same clientId (idempotent). Aborts if the session changed (logout). */
    suspend fun deliver(item: OutboxItem): Message {
        val generation = session.generation()
        val attachmentIds = mutableListOf<String>()
        for (photo in item.message.photos) {
            photo.attachmentId?.let {
                attachmentIds += it
                continue
            }
            val data = outbox.photoData(photo) ?: throw PingFrontException.InvalidInput("The photo is no longer on this device.")
            val requestId = UUID.nameUUIDFromBytes("${item.message.clientId}/${photo.file}".encodeToByteArray()).toString()
            val id = uploader.upload(data, photo.width, photo.height, photo.sha256, requestId, generation = generation)
            outbox.recordUpload(item.message.clientId, photo.file, id)
            attachmentIds += id
        }
        val request = SendMessageRequest(item.message.clientId, item.message.text, attachmentIds.ifEmpty { null }, item.replyToCard)
        suspend fun post(): Message {
            // A logout, erase or revocation while uploading: never send this person's message under the next
            // session, and never report it delivered into the next person's thread. Not a cancellation:
            // the drain puts the item back and the loop lives on (review CS-1).
            val message = client.send(Endpoints.sendMessage(request), generation).message
            host.mergeSent(generation, message)
            return message
        }
        val message = try {
            post()
        } catch (e: PingFrontException) {
            if (e.code != ErrorCode.ThreadReset) {
                host.sendRefused(e)
                throw e
            }
            host.threadReset()
            post()
        }
        host.sent()
        return message
    }
}
