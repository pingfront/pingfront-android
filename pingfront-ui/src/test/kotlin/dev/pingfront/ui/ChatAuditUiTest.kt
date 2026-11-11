package dev.pingfront.ui

import android.app.Application
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.pingfront.PingFrontInstance
import dev.pingfront.ReportPresenter
import dev.pingfront.internal.debug.Fixtures
import dev.pingfront.look.ChatTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Behaviour fixes from the UI and UX audit of the SDK (31 Oct 2026) in the chat UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatAuditUiTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** A-02: an open chat sheet (and its topic) survives rotation, dark mode and font changes. */
    @Test fun anOpenChatSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        lateinit var state: PingFrontChatState
        restoration.setContent { state = rememberPingFrontChatState() }
        compose.runOnIdle { state.open(ChatTopic.Bug) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(state.isVisible)
            assertEquals("bug", state.topic?.id)
        }
    }

    /** X-01: "Report a bug" opens the report; it never sends its label. */
    @Test fun theBugTopicOpensTheReport() {
        val instance = PingFrontInstance.detached()
        var opened = 0
        Fixtures.moduleContext(instance, app).setReportPresenter(
            object : ReportPresenter {
                override fun presentReport(prefill: String?) {
                    opened += 1
                }
            },
        )
        topicTapped(ChatTopic.Bug, starter = null, instance = instance)
        assertEquals(1, opened)
        assertTrue(instance.conversation.state.value.pending.isEmpty(), "nothing was sent")
    }

    /** X-01: other topics put a sentence start into the composer; nothing is sent until Send. */
    @Test fun aTopicFillsTheComposerAndSendsNothing() {
        val instance = PingFrontInstance.detached()
        topicTapped(ChatTopic.Billing, "I have a question about billing: ", instance)
        assertEquals("I have a question about billing: ", instance.conversation.composerInsertion.value)
        assertTrue(instance.conversation.state.value.pending.isEmpty())
        // What the person already typed is never overwritten.
        instance.conversation.consumeInsertion()
        instance.conversation.updateDraft("my own words")
        topicTapped(ChatTopic.Idea, "I have an idea: ", instance)
        assertEquals("my own words", instance.conversation.composerInsertion.value)
    }
}
