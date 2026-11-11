package dev.pingfront.internal.messaging

import dev.pingfront.internal.runtime.TimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Caches `GET /moments/listened` for 5 minutes so only listened moments are sent. */
internal class MomentGate(private val time: TimeSource) {
    private val mutex = Mutex()
    private var names: Set<String> = emptySet()
    private var storeEvents: Set<String> = emptySet()
    private var fetchedAt: Duration? = null

    suspend fun isFresh(): Boolean = mutex.withLock { fetchedAt?.let { time.now() - it < TTL } ?: false }

    suspend fun contains(name: String): Boolean = mutex.withLock { name in names }

    suspend fun containsStoreEvent(event: String): Boolean = mutex.withLock { event in storeEvents }

    suspend fun update(listened: List<String>, store: List<String> = emptyList()) = mutex.withLock {
        names = listened.toSet()
        storeEvents = store.toSet()
        fetchedAt = time.now()
    }

    companion object {
        val TTL: Duration = 5.minutes
        private val pattern = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")

        fun isValid(name: String): Boolean = pattern.matches(name)
    }
}

/**
 * Unread polling without push: every 20 s for 2 minutes after the last activity,
 * then every 2 minutes for an hour, then stop until the next foreground.
 */
internal object UnreadSchedule {
    fun interval(sinceActivity: Duration): Duration? = when {
        sinceActivity < FAST_WINDOW -> FAST_INTERVAL
        sinceActivity < FAST_WINDOW + SLOW_WINDOW -> SLOW_INTERVAL
        else -> null
    }
}

/** Tracks the last applied thread sequence number and detects gaps in live events. */
internal data class SeqTracker(val lastSeq: Int = 0) {
    sealed interface Decision {
        /** The next event in order: apply it. */
        data object Apply : Decision

        /** Already seen: apply idempotently (updates) or ignore. */
        data object Duplicate : Decision

        /** Events were missed: repair with `GET /thread/messages?after=<after>`, then apply. */
        data class Gap(val after: Int) : Decision
    }

    fun decide(seq: Int): Decision = when {
        seq <= lastSeq -> Decision.Duplicate
        seq == lastSeq + 1 -> Decision.Apply
        else -> Decision.Gap(lastSeq)
    }

    fun advance(seq: Int): SeqTracker = SeqTracker(maxOf(lastSeq, seq))
}

private val FAST_WINDOW = 2.minutes
private val FAST_INTERVAL = 20.seconds
private val SLOW_WINDOW = 1.hours
private val SLOW_INTERVAL = 2.minutes
