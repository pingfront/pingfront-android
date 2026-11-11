@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.model.SendPolicy
import dev.pingfront.testing.Bodies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** An open chat notices that a paused app was resumed: `GET /thread` every 60 s while visible and paused. */
class PausedRecheckTest {
    private val paused = Bodies.thread(1, Bodies.message(1)).replace(""""policy":{"canSend":true}""", """"policy":{"canSend":false,"reason":"app_paused"}""")

    private fun TestScope.wait(seconds: Int) {
        advanceTimeBy(seconds.seconds)
        runCurrent()
    }

    @Test fun aVisiblePausedChatChecksEveryMinuteUntilThePauseEnds() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = paused)
        // Opening the chat loads the thread, which says the app is paused.
        h.openChat()
        wait(1)
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), h.instance.conversation.state.value.policy)
        val before = h.transport.count("GET thread")
        wait(57)
        assertEquals(before, h.transport.count("GET thread"), "not before a minute")
        wait(3)
        assertEquals(before + 1, h.transport.count("GET thread"), "a minute later")
        wait(60)
        assertEquals(before + 2, h.transport.count("GET thread"), "and every minute")

        // Resumed: the next check opens the chat again, and the checks stop.
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        wait(60)
        assertEquals(SendPolicy.Open, h.instance.conversation.state.value.policy)
        val resumed = h.transport.count("GET thread")
        wait(300)
        assertEquals(resumed, h.transport.count("GET thread"), "no checks once the pause ended")
        h.stop()
    }

    @Test fun aHiddenChatStopsChecking() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = paused)
        h.openChat()
        wait(1)
        h.instance.chatVisibilityChanged(false)
        runCurrent()
        val hidden = h.transport.count("GET thread")
        wait(300)
        assertEquals(hidden, h.transport.count("GET thread"), "a closed chat is not polled for the pause")
        h.stop()
    }
}
