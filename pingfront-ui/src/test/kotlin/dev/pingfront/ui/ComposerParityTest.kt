package dev.pingfront.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pingfront.PingFrontInstance
import dev.pingfront.look.ResolvedLook
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer against Google Messages, on the Pixel 8 emulator (the final Android review, 3 Nov 2026,
 * "Composer against Google Messages"). Each test is a difference that was found there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ComposerParityTest {
    @get:Rule val compose = createComposeRule()

    private val instance = PingFrontInstance.detached()
    private val theme = ChatTheme(ResolvedLook.Default, isDark = false)
    private var view: View? = null
    private var focusManager: FocusManager? = null
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Composable
    private fun Host() {
        view = LocalView.current
        focusManager = LocalFocusManager.current
        val state by instance.conversation.state.collectAsState()
        CompositionLocalProvider(
            LocalChatTheme provides theme,
            LocalMaxBubbleWidth provides maxBubbleWidth(412.dp),
            LocalLifecycleOwner provides owner,
        ) {
            ChatMaterialTheme(theme) { Box { Composer(instance, state, ResolvedLook.Default, disabled = false) } }
        }
    }

    private val field get() = compose.onNode(hasSetTextAction())

    private fun lines(count: Int) = (1..count).joinToString("\n") { "Line $it" }

    /**
     * Google Messages grows its field to about a third of the screen (330 dp of 915 on a Pixel 8, 12 lines) before it
     * scrolls inside; the composer stopped at five lines.
     */
    @Test fun theFieldGrowsToAThirdOfTheWindowThenScrolls() {
        compose.setContent { Host() }
        field.performTextReplacement(lines(10))
        val ten = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(ten >= 10 * 24.dp + 2 * FIELD_PADDING_V, "ten lines show in a field of $ten")
        field.performTextReplacement(lines(40))
        val most = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(most <= 915.dp / 3, "forty lines made the field $most tall; it stops at a third of the window and scrolls")
        assertTrue(most >= 915.dp / 3 - 24.dp, "the field stopped at $most, short of a third of the window")
    }

    /**
     * Google Messages keeps the field focused (and the keyboard up) across rotation; the composer lost focus. On the
     * emulator a rotation pauses the activity and clears the field's focus before the state is saved, so that order is
     * played here too.
     */
    @Test fun theFieldKeepsFocusAcrossRotation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        field.performClick()
        field.assertIsFocused()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            focusManager!!.clearFocus()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        field.assertIsFocused()
    }

    /** Google Messages asks the keyboard to capitalize sentences (`TYPE_TEXT_FLAG_CAP_SENTENCES`); the composer did not. */
    @Test fun theKeyboardCapitalizesSentences() {
        compose.setContent { Host() }
        field.performClick()
        compose.waitForIdle()
        val info = EditorInfo()
        compose.runOnIdle { view!!.onCreateInputConnection(info) }
        assertTrue(info.inputType and EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES != 0, "inputType 0x${Integer.toHexString(info.inputType)}")
        assertTrue(info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0, "still multi-line")
    }

    /**
     * On a phone in landscape the keyboard leaves the chat about 82 dp under the sheet's handle. The header took it, and
     * the composer, squeezed to almost nothing, sat under the keyboard with what the person typed. Google Messages hands
     * the text to the keyboard's full-screen editor there; the chat keeps the composer and drops the header instead.
     */
    @Test fun theComposerStaysInViewWhenTheKeyboardLeavesLittleRoom() {
        compose.setContent {
            Box(Modifier.size(915.dp, 82.dp).testTag("room")) { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) }
        }
        val room = compose.onNodeWithTag("room").getBoundsInRoot()
        val composer = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot()
        assertTrue(composer.bottom <= room.bottom, "the composer ends at ${composer.bottom}, under the ${room.bottom} the keyboard leaves")
        assertTrue(composer.top >= room.top, "the composer starts at ${composer.top}, above the room")
        assertTrue(composer.bottom - composer.top >= BUTTON_TARGET, "the field is squeezed to ${composer.bottom - composer.top}")
    }

    /** B1 (audit 2): offline, the banner took that little room too and squashed the field to about 19 dp. */
    @Test fun theComposerStaysWholeWhenOfflineWithLittleRoom() {
        dev.pingfront.internal.debug.Fixtures.setOffline(instance)
        compose.setContent {
            Box(Modifier.size(686.dp, 82.dp).testTag("room")) { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) }
        }
        val room = compose.onNodeWithTag("room").getBoundsInRoot()
        val composer = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot()
        assertTrue(composer.bottom <= room.bottom, "the composer ends at ${composer.bottom}, under the ${room.bottom} the keyboard leaves")
        assertTrue(composer.bottom - composer.top >= BUTTON_TARGET, "the field is squeezed to ${composer.bottom - composer.top}")
    }
}
