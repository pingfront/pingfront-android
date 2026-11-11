package dev.pingfront.ui

import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.ConversationState
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.debug.Fixtures
import dev.pingfront.model.SendPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** X1: held messages carry one muted line per run, as on iOS (`PendingLine.showsLine`, `heldFor`). */
class PendingLinesTest {
    private fun state(vararg rows: Fixtures.PendingRow, policy: SendPolicy = SendPolicy.Open, unreachable: Boolean = false): ConversationState {
        val instance = PingFrontInstance.detached()
        Fixtures.setPending(instance, *rows)
        Fixtures.setPolicy(instance, policy)
        if (unreachable) Fixtures.setServerUnreachable(instance)
        return instance.conversation.state.value
    }

    private val paused = SendPolicy.Reason.AppPaused

    @Test fun aHeldRunShowsOneLineUnderItsLastMessage() {
        val s = state(
            Fixtures.PendingRow("a", "one", 1.0, PendingMessage.State.Failed, "app_paused", 1, paused),
            Fixtures.PendingRow("b", "two", 1.1),
            Fixtures.PendingRow("c", "three", 1.2),
        )
        assertEquals(mapOf("c" to PendingLine.Held(paused)), PendingLine.shown(s), "behind a held message, the rest wait for the same thing")
        assertTrue(s.pending.all { PendingLine.deletable(it, s) }, "Delete is in the menu of each")
    }

    @Test fun aSignInHoldSaysSignIn() {
        val reason = SendPolicy.Reason.VerificationRequired
        val s = state(Fixtures.PendingRow("a", "one", 1.0, PendingMessage.State.Failed, "verification_required", 1, reason))
        val line = PendingLine.shown(s).getValue("a")
        assertEquals(R.string.pf_held_sign_in, line.text())
        assertEquals(R.string.pf_held_chat_back, PendingLine.Held(paused).text())
    }

    @Test fun aPlainFailureEndsARunAndHasNoLine() {
        val s = state(
            Fixtures.PendingRow("a", "one", 1.0, attempts = 1),
            Fixtures.PendingRow("b", "two", 1.1, PendingMessage.State.Failed, "invalid_request", 1),
            Fixtures.PendingRow("c", "three", 1.2, attempts = 1),
        )
        assertEquals(mapOf("a" to PendingLine.Retrying, "c" to PendingLine.Retrying), PendingLine.shown(s))
        assertFalse(PendingLine.deletable(s.pending[1], s), "a plain failure opens Try again and Delete instead")
    }

    @Test fun aBlockedPersonsMessageIsNeverHeld() {
        val s = state(
            Fixtures.PendingRow("a", "one", 1.0, PendingMessage.State.Failed, "blocked", 1),
            policy = dev.pingfront.internal.debug.Fixtures.closed(SendPolicy.Reason.Blocked),
        )
        assertEquals(emptyMap(), PendingLine.shown(s))
        assertEquals(null, PendingLine.heldFor(s.pending.single(), s))
    }

    @Test fun outOfReachAQueuedMessageIsNotSentYet() {
        val s = state(Fixtures.PendingRow("a", "one", 1.0), unreachable = true)
        assertEquals(PendingLine.Retrying, PendingLine.shown(s).getValue("a"))
        assertEquals(R.string.pf_retrying, PendingLine.Retrying.text())
    }

    @Test fun theNoticeGoesBeforeTheFirstPendingRowOrLast() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        assertEquals(null, rowAfterNotice(instance.conversation.state.value.timeline), "nothing pending: last")
        Fixtures.setPending(instance, Fixtures.PendingRow("p1", "one", 30.0), Fixtures.PendingRow("p2", "two", 30.1))
        assertEquals("p1", rowAfterNotice(instance.conversation.state.value.timeline))
        assertTrue(noticesMoveIntoThread(1.5f, androidx.compose.ui.unit.Dp(900f)))
        assertTrue(noticesMoveIntoThread(1f, androidx.compose.ui.unit.Dp(400f)), "compact height")
        assertFalse(noticesMoveIntoThread(1.3f, androidx.compose.ui.unit.Dp(900f)))
    }

    @Test fun theCloseHintWaitsForEverythingToBeDelivered() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        Fixtures.firstMessageOnly(instance)
        assertTrue(showsCloseHint(instance.conversation.state.value))
        Fixtures.setPending(instance, Fixtures.PendingRow("p1", "one", 30.0))
        assertFalse(showsCloseHint(instance.conversation.state.value), "not while a message waits to send")
    }
}
