@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.look.ChatTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Review AN-18 (3 Nov 2026): the React Native and Flutter bridges handled a tapped notification with `handlePush`
 * plus `openChat()`. The SDK's own tap path refuses a notification addressed to the person who logged out since, but
 * the bridges' path did not: tapping the previous person's notification opened the chat and started a new guest.
 * `PingFront.openPush` is the one tap path, and the bridges call it.
 */
class PushOpenedTest {
    private val payload = mapOf("pf_v" to "1", "pf_kind" to "message", "pf_app_id" to "app_1", "pf_unread" to "3")

    private class Recorder : ChatPresenter {
        var presented = 0

        override fun presentChat(topic: ChatTopic?) {
            presented += 1
        }

        override fun dismissChat() = Unit

        override fun openUrl(url: String) = Unit

        override fun presentSimpleReport(prefill: String?) = Unit
    }

    @Test fun aTapOpensTheChatButNotForThePersonWhoLoggedOut() = runTest {
        val h = EngineHarness(this)
        val recorder = Recorder()
        h.instance.registry.chatPresenter = recorder
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        assertTrue(h.instance.openPush(payload))
        assertEquals(1, recorder.presented, "a tap opens the chat")

        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.logout()
        advanceUntilIdle()
        val sessions = h.transport.count("POST sessions")
        assertTrue(h.instance.openPush(payload), "still a pingfront payload")
        advanceUntilIdle()
        assertEquals(1, recorder.presented, "the previous person's notification opened the chat")
        assertEquals(sessions, h.transport.count("POST sessions"), "a session was started for it")
        assertEquals(false, h.instance.openPush(mapOf("from" to "another sdk")))
        h.stop()
    }
}
