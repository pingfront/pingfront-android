package dev.pingfront.internal

import dev.pingfront.PingFrontException
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.TypingIndicator
import dev.pingfront.internal.messaging.LiveConnection
import dev.pingfront.internal.messaging.UnreadSchedule
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.runtime.Timings
import dev.pingfront.internal.runtime.WakeSignal
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.wire.ClientEvent
import dev.pingfront.internal.wire.ReadRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val log = Log.of(Log.Category.Network)

/**
 * Unread counts without push: polls `GET /unread` while the app is in the foreground, every 20 s for
 * two minutes after the last activity, then every 2 minutes for an hour, then stops until the next activity.
 */
internal class UnreadMonitor(
    private val instance: PingFrontInstance,
    private val client: AuthorizedClient,
    private val session: SessionStore,
    private val time: TimeSource,
    startsInForeground: Boolean = true,
    /** Loads the thread again: an open chat's check for the end of a pause ([PausedRecheck]). */
    private val recheckThread: suspend () -> Unit = {},
    /** `GET /unread` answered: the app is not paused, so messages a pause held can go. */
    private val answered: suspend () -> Unit = {},
) {
    private val wake = WakeSignal()
    private val lastActivity = MutableStateFlow(Duration.ZERO)
    private val push = AtomicBoolean(false)
    private val foreground = AtomicBoolean(startsInForeground)

    @Volatile var paused: Boolean = false

    /** True when push carries unread counts (periodic polling stops; one poll per foreground remains). */
    val pushRegistered: Boolean get() = push.get()

    fun noteActivity() {
        lastActivity.value = time.now()
        wake.signal()
    }

    fun setPushRegistered(value: Boolean) = push.set(value)

    /** Polling happens only while the app is in the foreground (review CQ-M6). */
    fun setForeground(value: Boolean) {
        foreground.set(value)
        if (value) wake.signal()
    }

    /** A new team message arrived: count it, unless the person is looking at it. */
    fun received(onVisible: () -> Unit) {
        if (instance.isChatVisible.value) onVisible() else instance.incrementUnread()
    }

    suspend fun run(): Unit = kotlinx.coroutines.coroutineScope {
        // With the chat open, a paused app is checked through the thread instead (GET /unread is skipped then).
        launch { PausedRecheck.watch(instance.isChatVisible, instance.conversation.state.map { isPaused(it.policy) }, recheckThread) }
        while (true) {
            wake.await()
            while (true) {
                val interval = UnreadSchedule.interval(time.now() - lastActivity.value) ?: break
                delay(interval)
                if (!foreground.get()) break
                // A paused app refuses GET /unread too: nothing is polled until something says it was resumed (a poll
                // on the next foreground, a session start or refresh, a thread load).
                if (skipsPoll()) continue
                poll()
            }
        }
    }

    private fun appPaused(): Boolean = isPaused(instance.conversation.state.value.policy)

    private suspend fun skipsPoll(): Boolean = instance.isChatVisible.value || push.get() || paused || appPaused() || !session.hasInstall()

    /**
     * The app came to the foreground with the chat hidden. With push, the badge still catches up once (review CQ-H3, as
     * iOS does); paused when last asked, one poll finds out whether the app was resumed.
     */
    suspend fun onForeground() {
        if (session.hasInstall() && (push.get() || appPaused())) poll()
    }

    suspend fun poll() {
        try {
            val generation = session.generation()
            val response = client.send(Endpoints.unread(), generation)
            // A logout or erase while polling: the previous person's count never comes back (review CS-2).
            if (session.generation() == generation) instance.setUnread(response.unread)
            // Answered: the app isn't paused (any other reason is the thread's to say, on the next load).
            instance.conversation.clearPaused()
            if (session.generation() == generation) answered()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            // Refused because the app is paused: the policy says so, and polling stops until it is resumed.
            if (e.code == dev.pingfront.ErrorCode.AppPaused) policyRefusal(e)?.let { closed -> instance.conversation.update { it.copy(policy = closed) } }
            log.d("Unread poll failed: ${e.logDescription}")
        }
    }
}

/** Sends `POST /thread/read` shortly after messages appear while the chat is visible. */
internal class ReadReceipts(
    private val instance: PingFrontInstance,
    private val client: AuthorizedClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val job = AtomicReference<Job?>(null)

    fun schedule() {
        job.getAndSet(
            scope.launch {
                delay(Timings.readDelay)
                markRead()
            },
        )?.cancel()
    }

    /** [requireVisible]: false for `Conversation.markRead()`, which a custom UI calls for what it shows. */
    suspend fun markRead(requireVisible: Boolean = true) {
        val upTo = instance.conversation.state.value.messages.lastOrNull()?.seq ?: 0
        if (upTo <= 0 || (requireVisible && !instance.isChatVisible.value)) return
        try {
            val generation = session.generation()
            val unread = client.send(Endpoints.markRead(ReadRequest(upTo)), generation).unread
            // Conversation.markRead() promises 0: everything up to the last message is read now.
            if (session.generation() == generation) instance.setUnread(if (requireVisible) unread else 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            log.i("Read receipt failed: ${e.logDescription}")
        }
    }
}

/** Outgoing typing (throttled to one event per 3 s) and the incoming indicator (cleared after 8 s of silence). */
internal class TypingController(
    private val instance: PingFrontInstance,
    private val live: LiveConnection,
    private val scope: CoroutineScope,
    private val time: TimeSource,
) {
    private val sentAt = AtomicReference<Duration?>(null)
    private val clearJob = AtomicReference<Job?>(null)

    @Volatile var enabled: Boolean = true

    fun setTyping(on: Boolean) {
        if (!enabled) return
        val now = time.now()
        if (on) {
            val sent = sentAt.get()
            if (sent != null && now - sent < Timings.typingThrottle) return
            sentAt.set(now)
        } else if (sentAt.getAndSet(null) == null) {
            return
        }
        scope.launch { live.send(ClientEvent.Typing(on)) }
    }

    /** Typing dots are only for a human teammate: never for the assistant, plays or automated messages. */
    fun received(isAi: Boolean, name: String?, on: Boolean) {
        if (isAi) return
        instance.conversation.update { it.copy(typing = if (on) TypingIndicator(name, isAi) else null) }
        clearJob.getAndSet(
            if (on) {
                scope.launch {
                    delay(Timings.typingTimeout)
                    instance.conversation.update { it.copy(typing = null) }
                }
            } else {
                null
            },
        )?.cancel()
    }

    fun clear() {
        clearJob.getAndSet(null)?.cancel()
        instance.conversation.update { it.copy(typing = null) }
    }
}
