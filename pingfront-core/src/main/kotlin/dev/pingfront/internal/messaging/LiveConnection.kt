package dev.pingfront.internal.messaging

import dev.pingfront.PingFrontException
import dev.pingfront.RateScope
import dev.pingfront.conversation.LiveState
import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.SocketFrame
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.wire.ClientEvent
import dev.pingfront.internal.wire.LiveProtocol
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.internal.wire.SessionReason
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Log.of(Log.Category.Live)

/**
 * Owns the WebSocket while the chat is visible: subprotocols `pingfront.v1` + `bearer.<token>`,
 * a heartbeat every 30 s, `reauth` handling and reconnect backoff `min(1s·2ⁿ, 30s)` ±20%.
 */
internal class LiveConnection(
    private val api: ApiClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
    private val appKey: String,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble(-1.0, 1.0) },
    startsInForeground: Boolean = true,
) {
    sealed interface Signal {
        data class State(val state: LiveState) : Signal

        data class Event(val event: ServerEvent) : Signal

        /**
         * The server refused the live channel (403): it does so only while the app is paused (a blocked or unverified
         * person keeps the channel). The loop has stopped; it starts again with the next chat open, app foreground or
         * thread load. [error] is the refusal (from the session refresh, or the refused upgrade's error envelope); null
         * only when the 403 came without a readable body, and then the engine reloads the thread, whose policy says why.
         */
        data class Refused(val error: dev.pingfront.PingFrontException?) : Signal
    }

    private sealed interface Ending {
        data class Failed(val status: Int?, val offline: Boolean, val refusal: dev.pingfront.PingFrontException? = null) : Ending

        data object Reauth : Ending
    }

    private val mutex = Mutex()
    private var job: Job? = null
    private var socket: dev.pingfront.internal.net.LiveSocket? = null
    private var attempt = 0

    /** Bumped by every start and stop; events from an older run are dropped. */
    private var generation = 0

    /** False while the app is in the background: no socket opens then (review CS-5). Guarded by [mutex]. */
    private var foreground = startsInForeground
    private val channel = Channel<Signal>(Channel.UNLIMITED)

    /** States and events, in order. Collected by one engine loop. */
    val signals: Flow<Signal> = channel.receiveAsFlow()

    suspend fun isRunning(): Boolean = mutex.withLock { job?.isActive == true }

    suspend fun start(url: String) = mutex.withLock {
        if (!foreground) return@withLock log.d("Live not started: the app is in the background")
        if (job?.isActive == true) return@withLock
        attempt = 0
        generation += 1
        val tag = generation
        job = scope.launch { run(url, tag) }
    }

    suspend fun stop() {
        val old = mutex.withLock {
            generation += 1
            val current = job
            job = null
            socket?.close()
            socket = null
            current
        }
        old?.cancel()
        channel.trySend(Signal.State(LiveState.Stopped))
    }

    /**
     * The app went to the background or came back. In the background the socket closes and [start] does
     * nothing, so a chat that finishes loading after the person pressed Home never opens one.
     */
    suspend fun setForeground(value: Boolean) {
        mutex.withLock { foreground = value }
        if (!value) stop()
    }

    /** Restarts immediately (for example after coming back to the foreground). */
    suspend fun reconnectNow(url: String) {
        stop()
        start(url)
    }

    suspend fun send(event: ClientEvent) {
        mutex.withLock { socket }?.send(event.encode())
    }

    private suspend fun emit(tag: Int, signal: Signal) {
        mutex.withLock { if (tag == generation) channel.trySend(signal) }
    }

    private suspend fun run(url: String, tag: Int) {
        while (currentCoroutineContextActive()) {
            api.cooldowns.remaining(RateScope.Live)?.let { wait ->
                emit(tag, Signal.State(LiveState.Waiting(wait, offline = false)))
                delay(wait)
            }
            emit(tag, Signal.State(LiveState.Connecting))
            var offline = false
            try {
                val token = session.token(SessionReason.ChatOpened)
                // stop() may have run while the token was fetched: never open a socket for a stale run.
                if (!currentCoroutineContextActive() || mutex.withLock { tag != generation || !foreground }) return
                val ending = connect(url, token, tag)
                log.d("Live closed: ${if (ending is Ending.Failed) "status ${ending.status}, offline ${ending.offline}" else "reauth"}")
                when (ending) {
                    // Counted as an attempt and backed off below, so a server that keeps asking cannot cause a storm.
                    Ending.Reauth -> session.invalidate(token)
                    is Ending.Failed -> {
                        // A refusal won't change by retrying every 30 s (audit 5): stop until something reopens.
                        if (ending.status == HTTP_FORBIDDEN) {
                            emit(tag, Signal.Refused(ending.refusal))
                            return
                        }
                        if (ending.status == HTTP_UNAUTHORIZED) session.invalidate(token)
                        if (ending.status == HTTP_TOO_MANY) api.cooldowns.record(RateScope.Live, null)
                        offline = ending.offline
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                if (dev.pingfront.internal.policyRefusal(e) != null) {
                    emit(tag, Signal.Refused(e))
                    return
                }
                offline = e.isOffline
                log.i("Live token failed: ${e.logDescription}")
            }
            val wait = mutex.withLock { Backoff.Live.delay(attempt++, jitter()) }
            emit(tag, Signal.State(LiveState.Waiting(wait, offline)))
            delay(wait)
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    private suspend fun connect(url: String, token: String, tag: Int): Ending {
        val live = api.transport.openSocket(
            url,
            listOf(LiveProtocol.SUBPROTOCOL, "bearer.$token"),
            // The same identifying headers as every HTTP request (review CQ-L4).
            mapOf(
                dev.pingfront.internal.net.Headers.APP_KEY to appKey,
                dev.pingfront.internal.net.Headers.SDK to dev.pingfront.internal.net.SdkVersion.USER_AGENT,
                dev.pingfront.internal.net.Headers.PROTOCOL to dev.pingfront.internal.wire.ProtocolVersion.CURRENT,
            ),
        )
        val current = mutex.withLock {
            if (tag != generation) return@withLock false
            socket = live
            true
        }
        if (!current) {
            live.close()
            return Ending.Failed(null, false)
        }
        try {
            return coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(dev.pingfront.internal.runtime.Timings.heartbeat)
                        // A failed heartbeat ends the connection; the run loop reconnects with backoff.
                        if (!live.send(ClientEvent.Ping.encode())) live.close()
                    }
                }
                try {
                    for (frame in live.frames) {
                        when (frame) {
                            is SocketFrame.Text -> {
                                val event = ServerEvent.decode(frame.text) ?: continue
                                if (event == ServerEvent.Reauth) return@coroutineScope Ending.Reauth
                                received(event, tag)
                            }
                            is SocketFrame.Failed -> {
                                val offline = (frame.error as? IOException)?.let { ApiClient.isOffline(it) } ?: false
                                val status = frame.status
                                val body = frame.body
                                val refusal = if (status != null && body != null && body.isNotEmpty()) {
                                    dev.pingfront.PingFrontException.Server(ApiClient.serverError(status, body, null))
                                } else {
                                    null
                                }
                                return@coroutineScope Ending.Failed(status, offline, refusal)
                            }
                            // 4001: the access token lapsed. Refresh and reconnect (with backoff, like reauth).
                            is SocketFrame.Closed -> return@coroutineScope if (frame.code ==
                                CLOSE_TOKEN_EXPIRED
                            ) {
                                Ending.Reauth
                            } else {
                                Ending.Failed(null, false)
                            }
                        }
                    }
                    Ending.Failed(null, false)
                } finally {
                    heartbeat.cancel()
                }
            }
        } finally {
            live.close()
            mutex.withLock { if (socket === live) socket = null }
        }
    }

    private suspend fun received(event: ServerEvent, tag: Int) {
        mutex.withLock {
            if (tag != generation) return
            if (event is ServerEvent.Hello) {
                attempt = 0
                channel.trySend(Signal.State(LiveState.Live))
            }
            channel.trySend(Signal.Event(event))
        }
    }
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY = 429

/** The server closes with 4001 when the access token lapsed. */
private const val CLOSE_TOKEN_EXPIRED = 4001
