package dev.pingfront.internal.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Timings shared across components (each component's own timing stays next to its logic). */
internal object Timings {
    /** While the chat is on screen and the app is paused, the thread is loaded again this often (`PausedRecheck`). */
    val pausedRecheck: Duration = 60.seconds

    /** Read receipts wait this long after messages appear. */
    val readDelay: Duration = 500.milliseconds

    /** The draft is saved after typing pauses. */
    val draftDebounce: Duration = 400.milliseconds

    /** A typing indicator clears itself when the server goes quiet. */
    val typingTimeout: Duration = 8.seconds

    /** Typing events go out at most this often. */
    val typingThrottle: Duration = 3.seconds

    /** The live heartbeat. */
    val heartbeat: Duration = 30.seconds

    /** Opening the chat retries after a failed session this often while visible. */
    val openRetry: Duration = 5.seconds

    /** The self-check gives the server this long. */
    val selfCheckTimeout: Duration = 20.seconds
}
