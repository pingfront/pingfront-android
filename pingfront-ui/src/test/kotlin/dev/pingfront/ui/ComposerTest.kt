package dev.pingfront.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.pingfront.PingFrontInstance
import dev.pingfront.look.ResolvedLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The composer's rules, as plain logic. */
class ComposerRulesTest {
    @Test fun countsCodePointsNotUtf16Units() {
        assertEquals(3, ComposerRules.count("a🙂b"))
    }

    @Test fun theCounterShowsNearTheLimitAndSendStopsPastIt() {
        assertNull(ComposerRules.counter(TEXT_LIMIT - 200))
        assertEquals(TEXT_LIMIT - 199, ComposerRules.counter(TEXT_LIMIT - 199))
        assertFalse(ComposerRules.overLimit(TEXT_LIMIT))
        assertTrue(ComposerRules.overLimit(TEXT_LIMIT + 1))
    }

    @Test fun aTopicNeverOverwritesTypedText() {
        assertEquals("I have an idea: ", ComposerRules.insert("", "I have an idea: "))
        assertEquals("I have an idea: ", ComposerRules.insert("  ", "I have an idea: "))
        assertNull(ComposerRules.insert("my own words", "I have an idea: "))
    }

    @Test fun onlyAHardwareEnterWithoutShiftSends() {
        assertTrue(ComposerRules.hardwareSend(isEnter = true, shift = false, physical = true))
        assertFalse(ComposerRules.hardwareSend(isEnter = true, shift = true, physical = true), "Shift+Enter adds a line")
        assertFalse(ComposerRules.hardwareSend(isEnter = true, shift = false, physical = false), "the on-screen keyboard's Enter adds a line")
        assertFalse(ComposerRules.hardwareSend(isEnter = false, shift = false, physical = true))
    }

    /** Shift+Enter's line break is the composer's own, so an on-screen keyboard that sees the key first never decides it. */
    @Test fun aHardwareShiftEnterIsTheComposersLineBreak() {
        assertEquals(ComposerRules.HardwareEnter.NewLine, ComposerRules.hardwareEnter(isEnter = true, shift = true, physical = true, composing = false))
        assertEquals(ComposerRules.HardwareEnter.Send, ComposerRules.hardwareEnter(isEnter = true, shift = false, physical = true, composing = false))
        assertEquals(ComposerRules.HardwareEnter.None, ComposerRules.hardwareEnter(isEnter = true, shift = true, physical = false, composing = false))
        assertEquals(ComposerRules.HardwareEnter.None, ComposerRules.hardwareEnter(isEnter = false, shift = true, physical = true, composing = false))
    }

    /** S1: while a CJK IME composes, a hardware Enter commits the composition: never a send, never a line break. */
    @Test fun aHardwareEnterDuringACompositionIsTheImes() {
        assertEquals(ComposerRules.HardwareEnter.None, ComposerRules.hardwareEnter(isEnter = true, shift = false, physical = true, composing = true))
        assertEquals(ComposerRules.HardwareEnter.None, ComposerRules.hardwareEnter(isEnter = true, shift = true, physical = true, composing = true))
    }
}

/** The composer on real layouts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ComposerUiTest {
    @get:Rule val compose = createComposeRule()

    private val instance = PingFrontInstance.detached()
    private val theme = ChatTheme(ResolvedLook.Default, isDark = false)

    @Composable
    private fun Host() {
        val state by instance.conversation.state.collectAsState()
        CompositionLocalProvider(LocalChatTheme provides theme, LocalMaxBubbleWidth provides maxBubbleWidth(COLUMN)) {
            ChatMaterialTheme(theme) { Box { Composer(instance, state, ResolvedLook.Default, disabled = false) } }
        }
    }

    private val field get() = compose.onNode(hasSetTextAction())

    @Test fun typingUpdatesTheDraftAndARestoredDraftFillsTheField() {
        compose.setContent { Host() }
        field.performTextInput("Hello")
        compose.runOnIdle { assertEquals("Hello", instance.conversation.draft.value) }
        // After process death the draft comes back from disk a moment later: it fills the empty field.
        field.performTextReplacement("")
        compose.runOnIdle { instance.conversation.updateDraft("Saved before the app was killed") }
        compose.onNodeWithText("Saved before the app was killed").assertIsDisplayed()
    }

    @Test fun aTopicStarterNeverReplacesTypedText() {
        compose.setContent { Host() }
        field.performTextInput("my own words")
        compose.runOnIdle { topicTapped(dev.pingfront.look.ChatTopic.Idea, "I have an idea: ", instance) }
        compose.onNodeWithText("my own words").assertIsDisplayed()
        compose.runOnIdle { assertEquals("my own words", instance.conversation.draft.value) }
    }

    @Test fun nearTheLimitACounterShowsAndPastItSendIsGone() {
        compose.setContent { Host() }
        field.performTextReplacement("a".repeat(TEXT_LIMIT - 10))
        compose.onNodeWithText("3,990 / 4,000").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        field.performTextReplacement("🙂".repeat(TEXT_LIMIT + 2))
        compose.onNodeWithContentDescription("2 characters over the limit").assertIsDisplayed()
        compose.onNodeWithText("4,002 / 4,000").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Send")).fetchSemanticsNodes().isEmpty(), "send is hidden over the limit")
    }

    @Test fun theTextSurvivesRotation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        field.performTextInput("Half a sentence")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Half a sentence").assertIsDisplayed()
    }

    private companion object {
        val COLUMN = 412.dp
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
