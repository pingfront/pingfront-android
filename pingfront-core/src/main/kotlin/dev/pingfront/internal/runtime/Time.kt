package dev.pingfront.internal.runtime

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Monotonic and wall-clock time, injected so backoff, coalescing, polling and cooldowns are
 * deterministic in tests (a `TestCoroutineScheduler`'s virtual time). Sleeping always uses
 * `delay`, which follows the test scheduler.
 */
internal interface TimeSource {
    /** Monotonic time since an arbitrary origin. */
    fun now(): Duration

    /** Wall-clock time, for message timestamps. */
    fun wall(): Instant

    object System : TimeSource {
        override fun now(): Duration = java.lang.System.nanoTime().nanoseconds

        override fun wall(): Instant = Instant.now()
    }
}

/** The dispatchers the SDK runs on. Tests pass a `StandardTestDispatcher` for both. */
internal class SdkDispatchers(val default: CoroutineDispatcher, val io: CoroutineDispatcher, val main: CoroutineDispatcher) {
    companion object {
        val Production: SdkDispatchers get() = SdkDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Main.immediate)
    }
}

/**
 * Exponential backoff, `min(1s * 2^n, cap)` with ±20% jitter. [Writes] (queued writes: the outbox, acks, `PATCH /me`)
 * caps at 5 minutes (the protocol, Retrying writes); [Live] (the live channel's reconnect) at 30 seconds
 * (the protocol, Live channel).
 */
internal class Backoff private constructor(val cap: Duration) {
    /** The un-jittered delay for attempt [attempt] (0-based). */
    fun nominal(attempt: Int): Duration {
        val exponent = attempt.coerceIn(0, MAX_EXPONENT)
        return minOf(base * (1 shl exponent), cap)
    }

    /** The jittered delay. [unit] is a random value in -1..1 (injected in tests). */
    fun delay(attempt: Int, unit: Double = kotlin.random.Random.nextDouble(-1.0, 1.0)): Duration = nominal(attempt) * (1 + JITTER * unit.coerceIn(-1.0, 1.0))

    companion object {
        val base: Duration = 1.seconds
        const val JITTER: Double = 0.2
        private const val MAX_EXPONENT = 16

        /** Queued writes (the protocol, Retrying writes). */
        val Writes: Backoff = Backoff(5.minutes)

        /** The live channel's reconnect (the protocol, Live channel). */
        val Live: Backoff = Backoff(30.seconds)
    }
}
