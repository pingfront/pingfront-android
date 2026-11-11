package dev.pingfront.internal.runtime

import dev.pingfront.RateScope
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scoped rate-limit cooldowns from a 429's `scope` + `retryAfter`. Only the named scope pauses, so
 * text keeps sending while uploads wait. A value type; [CooldownBook] owns the shared instance.
 */
internal data class Cooldowns(private val until: Map<RateScope, Duration> = emptyMap()) {
    /** Records a 429 at monotonic time [now]. A missing `retryAfter` means 30 s. */
    fun record(scope: RateScope, retryAfter: Duration?, now: Duration): Cooldowns {
        val end = now + (retryAfter ?: 30.seconds)
        return Cooldowns(until + (scope to maxOf(until[scope] ?: Duration.ZERO, end)))
    }

    /** Time left for [scope], or `null` when it is not cooling down. */
    fun remaining(scope: RateScope, now: Duration): Duration? = until[scope]?.takeIf { it > now }?.minus(now)

    /** Scopes cooling down at [now], with their deadlines. */
    fun active(now: Duration): Map<RateScope, Duration> = until.filterValues { it > now }

    companion object {
        /** The exact countdown text (`m:ss`) when under five minutes, else `null` (show a general line instead). */
        fun countdown(remaining: Duration): String? {
            if (remaining >= 300.seconds) return null
            val seconds = maxOf(ceil(remaining.inWholeMilliseconds / 1000.0).toInt(), 0)
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}

/** Shared cooldown state, checked before each request of a scope. */
internal class CooldownBook(private val time: TimeSource) {
    private val mutex = Mutex()
    private var cooldowns = Cooldowns()

    suspend fun record(scope: RateScope, retryAfter: Duration?) = mutex.withLock {
        cooldowns = cooldowns.record(scope, retryAfter, time.now())
    }

    suspend fun remaining(scope: RateScope): Duration? = mutex.withLock { cooldowns.remaining(scope, time.now()) }

    /** Active scopes and their monotonic deadlines. */
    suspend fun deadlines(): Map<RateScope, Duration> = mutex.withLock { cooldowns.active(time.now()) }
}
