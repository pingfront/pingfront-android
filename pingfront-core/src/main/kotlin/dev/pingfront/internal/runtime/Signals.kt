package dev.pingfront.internal.runtime

import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A level-triggered wake-up: [notify] wakes the current waiter, or the next one if nobody waits.
 * A conflated channel, so there is no lock to hold.
 */
internal class WakeSignal {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    fun signal() {
        channel.trySend(Unit)
    }

    suspend fun await() {
        channel.receive()
    }

    /** Waits until notified or [timeout] passes. */
    suspend fun await(timeout: Duration) {
        withTimeoutOrNull(timeout) { channel.receive() }
    }
}

/** App lifecycle transitions. */
internal enum class AppLifecycleEvent { Foreground, Background }
