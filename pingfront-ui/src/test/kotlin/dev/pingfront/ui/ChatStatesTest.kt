package dev.pingfront.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.debug.Fixtures
import dev.pingfront.model.SendPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What fills the chat in each loading state (founder review, 6 Nov 2026): offline said the same thing twice (the
 * banner and a failure screen with "Try again") and hid the welcome; an embedded chat showed an empty header row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
class ChatStatesTest {
    @get:Rule val compose = createComposeRule()

    private val offline = "You're offline. Messages will send when you're back online."
    private val couldntLoad = "Couldn't load the conversation"

    private fun show(instance: PingFrontInstance, onClose: (() -> Unit)? = null) {
        compose.setContent { ChatScreen(instance, null, onClose, Modifier.fillMaxSize()) }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /** Offline with nothing cached: one banner, the welcome underneath, the composer usable, no retry. */
    @Test fun offlineShowsOneBannerAndTheWelcome() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setLoadFailed(instance, offline = true)
        show(instance)
        assertEquals(1, count(offline), "the offline fact is said once")
        assertEquals(0, count("Try again"), "offline recovers by itself: no retry button")
        assertEquals(0, count(couldntLoad))
        compose.onNodeWithText("Questions, bugs or ideas", substring = true).assertExists()
        compose.onNodeWithTag(ComposerTags.FIELD).assertExists()
    }

    /** A request that failed as offline while the network callback has not caught up yet: still not a failure. */
    @Test fun aRequestThatLookedOfflineIsNotAFailureScreen() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setLoadFailed(instance, offline = true, networkOffline = false)
        show(instance)
        assertEquals(0, count("Try again"))
        assertEquals(0, count(couldntLoad))
    }

    /** Online, but the server failed: the centered failure state with its one action, and no offline banner. */
    @Test fun aServerFailureShowsTheFailureStateAlone() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setLoadFailed(instance, offline = false)
        show(instance)
        assertEquals(1, count(couldntLoad))
        assertEquals(1, count("Try again"))
        assertEquals(0, count(offline))
    }

    /** Offline with a cached thread: the thread and one banner. */
    @Test fun offlineWithACachedThreadKeepsTheThread() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        Fixtures.setOffline(instance)
        show(instance)
        assertEquals(1, count(offline))
        assertEquals(0, count("Try again"))
    }

    /** Embedded on the welcome with no close button: no empty header row above the welcome. */
    @Test fun anEmbeddedWelcomeHasNoEmptyHeader() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        val state = instance.conversation.state.value
        val look = instance.look.value
        assertEquals(false, showsHeader(state, onClose = null, look = look))
        assertEquals(true, showsHeader(state, onClose = {}, look = look), "the sheet keeps its close button")
        show(instance)
        assertEquals(0, compose.onAllNodesWithContentDescription("Close chat").fetchSemanticsNodes().size)
    }

    /** A message queued offline says it is waiting; online and queued, it is sending. Never no line at all. */
    @Test fun aQueuedMessageSaysWhatItIsWaitingFor() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setPending(instance, Fixtures.PendingRow("c1", "Hello from the bus", 1.0))
        Fixtures.setOffline(instance)
        show(instance)
        assertEquals(1, count("Waiting for connection"))
        Fixtures.setOffline(instance, offline = false)
        compose.waitForIdle()
        assertEquals(0, count("Waiting for connection"))
        // As on iOS: a message not tried yet is "Sending…".
        assertEquals(1, count("Sending…"), "queued, not tried yet")
    }

    /**
     * Audit round 2: a send under way when the network went said "Sending…" until it timed out (about a minute). Offline
     * wins over the outbox's state.
     */
    @Test fun aSendUnderWayTurnsToWaitingWhenTheNetworkGoes() {
        fun line(state: PendingMessage.State, attempts: Int, offline: Boolean): PendingLine? {
            val holder = PingFrontInstance.detached()
            Fixtures.setPending(holder, Fixtures.PendingRow("c1", "Hi", 1.0, state, attempts = attempts))
            if (offline) Fixtures.setOffline(holder)
            val state = holder.conversation.state.value
            return PendingLine.of(state.pending.single(), state)
        }
        assertEquals(PendingLine.Sending, line(PendingMessage.State.Sending, 1, offline = false))
        assertEquals(PendingLine.Waiting, line(PendingMessage.State.Sending, 1, offline = true))
        assertEquals(PendingLine.Waiting, line(PendingMessage.State.Queued, 1, offline = true))
        assertEquals(PendingLine.Retrying, line(PendingMessage.State.Queued, 1, offline = false))
        // As on iOS: not tried yet is "Sending…"; after an attempt the outbox will retry, it is not sent yet.
        assertEquals(PendingLine.Sending, line(PendingMessage.State.Queued, 0, offline = false))
        assertEquals(null, line(PendingMessage.State.Failed, 1, offline = true), "a failure says Not delivered under itself")
        // On screen: the same row, Sending and then offline.
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setPending(instance, Fixtures.PendingRow("c1", "Hello from the bus", 1.0, PendingMessage.State.Sending, attempts = 1))
        show(instance)
        assertEquals(1, count("Sending…"))
        Fixtures.setOffline(instance)
        compose.waitForIdle()
        assertEquals(0, count("Sending…"))
        assertEquals(1, count("Waiting for connection"))
    }

    /** The device is online but the server can't be reached: the banner says that, never "You're offline". */
    @Test fun anUnreachableServerIsNotCalledOffline() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        Fixtures.setServerUnreachable(instance)
        show(instance)
        assertEquals(0, count(offline))
        assertEquals(1, count("Can't reach the chat right now. Messages will send when it's back."))
    }

    /** Audit 3: online with the server out of reach, a queued message never says "Waiting for connection". */
    @Test fun aQueuedMessageWhileTheServerIsUnreachableIsNotWaitingForAConnection() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setPending(instance, Fixtures.PendingRow("c1", "Still there?", 1.0, attempts = 1))
        Fixtures.setServerUnreachable(instance)
        show(instance)
        assertEquals(0, count("Waiting for connection"))
        assertEquals(1, count("Not sent yet. Trying again soon."))
    }

    /** N13: two messages, the first on the wire and the second behind it: one "Sending…", one "Not sent yet". */
    @Test fun onlyTheMessageOnTheWireSaysSending() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setPending(
            instance,
            Fixtures.PendingRow("c1", "First", 1.0, PendingMessage.State.Sending, attempts = 1),
            Fixtures.PendingRow("c2", "Second", 1.5, attempts = 1),
        )
        show(instance)
        assertEquals(1, count("Sending…"))
        assertEquals(1, count("Not sent yet. Trying again soon."))
    }

    /** The server failed: nothing to write into, so the composer is off, and the failure's title is a heading. */
    @Test fun aServerFailureDisablesTheComposerAndTitlesTheState() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setLoadFailed(instance, offline = false)
        show(instance)
        assertEquals(0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "the field takes no text")
        compose.onNodeWithContentDescription("Add photo or report a bug").assertIsNotEnabled()
        compose.onNode(hasText(couldntLoad) and isHeading()).assertExists()
    }

    /** N10: while the person can't send, nothing promises a reply and a failed message offers Delete only. */
    @Test fun aClosedPolicyPromisesNoReplyAndOffersOnlyDelete() {
        val closed = PingFrontInstance.detached()
        Fixtures.load(closed, Fixtures.Kind.Welcome)
        show(closed)
        assertEquals(1, count("reply here"), "open: the welcome body")
        for (reason in listOf(SendPolicy.Reason.Blocked, SendPolicy.Reason.AppPaused, SendPolicy.Reason.VerificationRequired)) {
            Fixtures.setPolicy(closed, Fixtures.closed(reason))
            compose.waitForIdle()
            assertEquals(0, count("reply here"), "$reason: the welcome body promises a reply")
            assertEquals(0, count("Usually replies"), "$reason: the reply expectation")
            assertEquals(0, count("Ask about billing"), "$reason: topics")
        }
    }

    /** With no reply expectation from the team, the welcome says the SDK's default, as iOS and the web do. */
    @Test fun aTeamWithoutAReplyExpectationGetsTheDefault() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setReplyExpectation(instance, null)
        show(instance)
        assertEquals(1, count("Usually replies within a day"), "the welcome's reply expectation")
        Fixtures.setPolicy(instance, Fixtures.closed(SendPolicy.Reason.Blocked))
        compose.waitForIdle()
        assertEquals(0, count("Usually replies"), "no promise of a reply while sending is closed")
    }

    /** After the first message, the header says the default too. */
    @Test fun theHeaderFallsBackToTheDefaultReplyExpectation() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        Fixtures.firstMessageOnly(instance)
        Fixtures.setReplyExpectation(instance, null)
        show(instance)
        assertEquals(1, count("Usually replies within a day"), "the header's reply expectation")
    }

    /**
     * N10 and X1: a failed message has the error mark and "Not delivered"; the mark opens Try again and Delete while the
     * person may send, and Delete alone while they can't.
     */
    @Test fun aFailedMessageOffersTryAgainOnlyWhileThePersonMaySend() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setPending(instance, Fixtures.PendingRow("c2", "Is there a family plan?", 1.0, PendingMessage.State.Failed, failure = "invalid_request"))
        show(instance)
        // The red line is read once, on the mark beside the bubble.
        assertEquals(1, compose.onAllNodesWithContentDescription("Not delivered").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Not delivered").performClick()
        compose.waitForIdle()
        assertEquals(1, count("Try again"), "open: Try again and Delete")
        assertEquals(1, count("Delete"))
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        Fixtures.setPolicy(instance, Fixtures.closed(SendPolicy.Reason.Blocked))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Not delivered").performClick()
        compose.waitForIdle()
        assertEquals(0, count("Try again"), "closed: Delete only")
        assertEquals(1, count("Delete"))
        assertEquals(1, count("You can't send messages in this chat."))
    }

    /** N10: after the first message, "You'll get the reply here" only while the person can send. */
    @Test fun theReplyLineGoesWhenThePolicyCloses() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        Fixtures.firstMessageOnly(instance)
        show(instance)
        assertEquals(1, count("You can close this; the reply waits here."))
        assertEquals(1, count("Usually replies"), "the header's reply expectation")
        Fixtures.setPolicy(instance, Fixtures.closed(SendPolicy.Reason.AppPaused))
        compose.waitForIdle()
        assertEquals(0, count("You can close this; the reply waits here."))
        // S3 (audit 2): nor does the header promise one.
        assertEquals(0, count("Usually replies"), "the header promises no reply while sending is closed")
    }

    /**
     * Audit 4, B1: a paused app refused the chat's session; the screen showed an open composer, "Not sent yet. Trying
     * again soon." and "You'll get the reply here." Now the refusal is the closed policy: the welcome without a promise,
     * the notice in the composer's place, and a queued message that says it was not delivered, with no retry promised.
     */
    @Test fun aRefusedOpenShowsTheClosedWelcomeNotAFailure() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        Fixtures.setLoadFailed(instance, offline = false)
        Fixtures.setPolicy(instance, Fixtures.closed(SendPolicy.Reason.AppPaused))
        Fixtures.setPending(instance, Fixtures.PendingRow("c1", "Hello?", 1.0))
        assertEquals(ChatBody.Thread, chatBody(instance.conversation.state.value))
        show(instance)
        assertEquals(0, count(couldntLoad))
        assertEquals(0, compose.onAllNodesWithText("Try again").fetchSemanticsNodes().size, "no retry button")
        assertEquals(0, count("Trying again soon"))
        assertEquals(0, count("reply here"))
        assertEquals(1, count("This chat is paused for now."))
        // X1: waiting for the pause to end, not failed: no error mark, one muted line that says when it goes.
        assertEquals(0, count("Not delivered"))
        assertEquals(1, count("Not sent yet. It will send when the chat is back."))
        // With nothing queued, the closed welcome rather than the failure state.
        val empty = PingFrontInstance.detached()
        Fixtures.load(empty, Fixtures.Kind.Welcome)
        Fixtures.setLoadFailed(empty, offline = false)
        Fixtures.setPolicy(empty, Fixtures.closed(SendPolicy.Reason.AppPaused))
        assertEquals(ChatBody.Welcome, chatBody(empty.conversation.state.value))
    }

    @Test fun theBodyFollowsThePhase() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        assertEquals(ChatBody.Welcome, chatBody(instance.conversation.state.value))
        Fixtures.setLoadFailed(instance, offline = false)
        assertEquals(ChatBody.LoadFailed, chatBody(instance.conversation.state.value))
        Fixtures.setLoadFailed(instance, offline = true)
        assertEquals(ChatBody.Welcome, chatBody(instance.conversation.state.value))
        Fixtures.load(instance, Fixtures.Kind.Conversation)
        assertEquals(ChatBody.Thread, chatBody(instance.conversation.state.value))
    }
}
