package dev.pingfront.conversation

import kotlin.time.Duration

/** The state of the live channel. */
public sealed interface LiveState {
    /** Not connected (chat closed). */
    public data object Stopped : LiveState

    /** Opening the socket. */
    public data object Connecting : LiveState

    /** Receiving events. */
    public data object Live : LiveState

    /** Disconnected; retrying after [retryIn]. */
    @ConsistentCopyVisibility
    public data class Waiting internal constructor(
        /** Time until the next attempt. */
        public val retryIn: Duration,
        /** True when the device looks offline. */
        public val offline: Boolean,
    ) : LiveState
}
