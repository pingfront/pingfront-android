package dev.pingfront.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audit A-01: tapping a topic on the welcome screen puts its sentence start into the composer, visibly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
class TopicTapUiTest {
    @get:Rule val compose = createComposeRule()

    private fun welcome(): PingFrontInstance {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome, "en", Fixtures.Clockwise, Fixtures.EmptyLook, false)
        return instance
    }

    @Test fun tappingATopicChipFillsTheComposerAndSendsNothing() {
        val instance = welcome()
        compose.setContent { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) }
        compose.onNodeWithText("Ask about billing").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("I have a question about billing: ")
        assertTrue(instance.conversation.state.value.pending.isEmpty(), "nothing was sent")
    }

    @Test fun aStarterWaitingBeforeTheChatAppearsShowsInTheFirstFrame() {
        val instance = welcome()
        instance.conversation.insert("I have a question about billing: ")
        compose.mainClock.autoAdvance = false
        compose.setContent { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) }
        compose.onNode(hasSetTextAction()).assertTextEquals("I have a question about billing: ")
    }
}
