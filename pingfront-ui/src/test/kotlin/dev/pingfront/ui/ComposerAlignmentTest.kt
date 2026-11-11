package dev.pingfront.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFrontInstance
import dev.pingfront.look.ResolvedLook
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The + and send buttons sit on the field's last line (Google Messages): centered on a one-line field and on the
 * last line once it grows, within 0.5 dp, at font scales 1.0, 1.3 and 2.0, left to right and right to left.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ComposerAlignmentTest {
    @get:Rule val compose = createComposeRule()

    private val theme = ChatTheme(ResolvedLook.Default, isDark = false)
    private var fontScale by mutableStateOf(1f)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    @Composable
    private fun Host(instance: PingFrontInstance) {
        val state by instance.conversation.state.collectAsState()
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale),
            LocalLayoutDirection provides direction,
            LocalChatTheme provides theme,
            LocalMaxBubbleWidth provides maxBubbleWidth(412.dp),
        ) {
            ChatMaterialTheme(theme) {
                Box(Modifier.width(412.dp)) { Composer(instance, state, ResolvedLook.Default, disabled = false) }
            }
        }
    }

    private fun centerY(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().let { (it.top + it.bottom) / 2 }

    /** The center of the field's last line, as laid out: above the field's bottom padding. */
    private fun lastLineCenter(lines: Int): Dp {
        val input = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        return compose.onNodeWithTag(ComposerTags.FIELD).getBoundsInRoot().bottom - FIELD_PADDING_V - (input.bottom - input.top) / lines / 2
    }

    private fun near(expected: Dp, actual: Dp, what: String) = assertTrue(abs(expected.value - actual.value) <= 0.5f, "$what: expected $expected, was $actual")

    @Test fun buttonsSitOnTheLastLineAtEveryScaleAndDirection() {
        val instance = PingFrontInstance.detached()
        compose.setContent { Host(instance) }
        for (scale in listOf(1f, 1.3f, 2f)) {
            for (dir in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                fontScale = scale
                direction = dir
                val where = "scale $scale, $dir"
                // Empty and one line: the + is centered on the field.
                compose.onNode(hasSetTextAction()).performTextReplacement("")
                compose.waitForIdle()
                near(centerY(ComposerTags.FIELD), centerY(ComposerTags.ADD), "+ on an empty field ($where)")
                compose.onNode(hasSetTextAction()).performTextReplacement("Hi")
                compose.waitForIdle()
                near(centerY(ComposerTags.FIELD), centerY(ComposerTags.ADD), "+ on a one-line field ($where)")
                near(centerY(ComposerTags.FIELD), centerY(ComposerTags.SEND), "send on a one-line field ($where)")
                // Grown: both on the last line.
                compose.onNode(hasSetTextAction()).performTextReplacement("one\ntwo\nthree")
                compose.waitForIdle()
                near(lastLineCenter(3), centerY(ComposerTags.ADD), "+ on the last line ($where)")
                near(lastLineCenter(3), centerY(ComposerTags.SEND), "send on the last line ($where)")
            }
        }
    }
}
