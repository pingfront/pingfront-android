package dev.pingfront.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFrontInstance
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N5: the embedded chat's insets. Embedded above a host's bottom bar, the chat padded for the whole keyboard although
 * the bar already filled the bottom of the window, leaving an empty band (the bar's height) between the composer and
 * the keyboard. The keyboard is simulated as a bottom inset of [KEYBOARD] measured from the window's bottom edge, as
 * `WindowInsets.ime` reports it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ChatInsetsTest {
    @get:Rule val compose = createComposeRule()

    private val instance = PingFrontInstance.detached()

    /** Full window: the composer ends where the keyboard starts. */
    @Test fun fullWindowTheComposerSitsOnTheKeyboard() {
        compose.setContent {
            Box(Modifier.size(412.dp, 800.dp).testTag("window")) {
                ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize(), windowInsets = WindowInsets(bottom = KEYBOARD))
            }
        }
        val window = compose.onNodeWithTag("window").getBoundsInRoot()
        val composer = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot()
        assertTrue(window.bottom - KEYBOARD - composer.bottom in 0.dp..COMPOSER_MARGIN, "composer ends at ${composer.bottom}")
    }

    /** Above a bottom bar that consumes its padding: the default insets leave no band. */
    @Test fun aboveABottomBarThatConsumesItsPaddingThereIsNoBand() {
        compose.setContent { Host { padding -> Modifier.consumeWindowInsets(padding) } }
        assertFlushOnTheKeyboard()
    }

    /** Above a bottom bar, passing what the bar has not handled: no band either. */
    @Test fun aboveABottomBarPassingWhatIsLeftThereIsNoBand() {
        compose.setContent {
            Host(insets = WindowInsets(bottom = KEYBOARD).exclude(WindowInsets(bottom = BAR))) { Modifier }
        }
        assertFlushOnTheKeyboard()
    }

    /** With the keyboard down, the chat above a bar adds nothing under the composer. */
    @Test fun aboveABottomBarWithTheKeyboardDownThereIsNoGap() {
        compose.setContent { Host(keyboard = 0.dp) { padding -> Modifier.consumeWindowInsets(padding) } }
        val chat = compose.onNodeWithTag("chat").getBoundsInRoot()
        val composer = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot()
        assertTrue(chat.bottom - composer.bottom in 0.dp..COMPOSER_MARGIN, "composer ends at ${composer.bottom}, chat at ${chat.bottom}")
    }

    /** As the whole window: the header starts below the status bar (and nothing above it is drawn under the bar). */
    @Test fun theHeaderClearsTheStatusBar() {
        compose.setContent {
            Box(Modifier.size(412.dp, 800.dp).testTag("window")) {
                ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize(), windowInsets = WindowInsets(top = STATUS_BAR))
            }
        }
        val window = compose.onNodeWithTag("window").getBoundsInRoot()
        val close = compose.onNodeWithContentDescription("Close chat").getBoundsInRoot()
        assertTrue(close.top >= window.top + STATUS_BAR, "the close button starts at ${close.top}, under the ${STATUS_BAR} status bar")
    }

    /** Below a host's own top bar that consumed the status bar: no second status-bar gap above the header. */
    @Test fun aHostThatPadsTheTopIsNotPaddedTwice() {
        compose.setContent {
            Box(Modifier.size(412.dp, 800.dp).testTag("window")) {
                val padding = PaddingValues(top = STATUS_BAR)
                Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().testTag("chat")) {
                    ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize(), windowInsets = WindowInsets(top = STATUS_BAR))
                }
            }
        }
        val chat = compose.onNodeWithTag("chat").getBoundsInRoot()
        val close = compose.onNodeWithContentDescription("Close chat").getBoundsInRoot()
        assertTrue(close.top - chat.top < STATUS_BAR, "a ${close.top - chat.top} gap above the header")
    }

    /** A host column: the chat over a [BAR]-high bottom bar, as a `Scaffold` with a `NavigationBar` lays it out. */
    @androidx.compose.runtime.Composable
    private fun Host(keyboard: Dp = KEYBOARD, insets: WindowInsets? = null, consume: (PaddingValues) -> Modifier) {
        val padding = PaddingValues(bottom = BAR)
        Box(Modifier.size(412.dp, 800.dp).testTag("window")) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().then(consume(padding)).testTag("chat")) {
                    ChatScreen(
                        instance,
                        null,
                        onClose = {},
                        Modifier.fillMaxSize(),
                        windowInsets = insets ?: WindowInsets(bottom = keyboard),
                    )
                }
                Spacer(Modifier.fillMaxWidth().height(BAR))
            }
        }
    }

    private fun assertFlushOnTheKeyboard() {
        val window = compose.onNodeWithTag("window").getBoundsInRoot()
        val composer = compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot()
        val band = window.bottom - KEYBOARD - composer.bottom
        assertTrue(band in 0.dp..COMPOSER_MARGIN, "a $band-high band between the composer and the keyboard")
    }

    private companion object {
        val KEYBOARD = 320.dp
        val BAR = 80.dp
        val STATUS_BAR = 48.dp

        /** The composer's own padding under its field. */
        val COMPOSER_MARGIN = 16.dp
    }
}
