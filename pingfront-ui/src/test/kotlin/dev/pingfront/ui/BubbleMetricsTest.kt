package dev.pingfront.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pingfront.look.ResolvedLook
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's bubble metrics, measured on real layouts so they can't drift from Google Messages (founder rule,
 * 31 Oct): bodyLarge 16 sp with its 24 sp line height, about 12 by 8 dp padding, 20 dp radius, at most 75% of the
 * width, 2 dp within a group and 8 dp between groups, 32 dp avatars, 280 dp cards.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class BubbleMetricsTest {
    @get:Rule val compose = createComposeRule()

    private val theme = ChatTheme(ResolvedLook.Default, isDark = false)

    private fun near(expected: Dp, actual: Dp, what: String) = assertTrue(abs(expected.value - actual.value) <= 1f, "$what: expected $expected, was $actual")

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalChatTheme provides theme, LocalMaxBubbleWidth provides maxBubbleWidth(COLUMN)) {
            ChatMaterialTheme(theme) { Box(Modifier.width(COLUMN)) { content() } }
        }
    }

    @Test fun tokensMatchGoogleMessages() {
        assertEquals(12.dp, theme.bubbleHorizontal)
        assertEquals(8.dp, theme.bubbleVertical)
        assertEquals(20.dp, theme.bubbleRadius)
        assertEquals(2.dp, theme.rowSpacing)
        assertEquals(8.dp, theme.groupSpacing)
        assertEquals(32.dp, theme.avatarSize)
        assertEquals(280.dp, CardWidth)
    }

    @Test fun bodyTextIsBodyLarge() {
        var size: TextUnit = TextUnit.Unspecified
        var line: TextUnit = TextUnit.Unspecified
        compose.setContent {
            ChatMaterialTheme(theme) {
                size = MaterialTheme.typography.bodyLarge.fontSize
                line = MaterialTheme.typography.bodyLarge.lineHeight
            }
        }
        compose.waitForIdle()
        assertEquals(16.sp, size)
        assertEquals(24.sp, line)
    }

    @Test fun aOneLineBubbleIsOneLinePlusPadding() {
        show { Box(Modifier.testTag("b")) { TextBubble("Hi", BubbleSide.Team, joinTop = false, joinBottom = false) } }
        val bounds = compose.onNodeWithTag("b").getBoundsInRoot()
        near(24.dp + 8.dp * 2, bounds.bottom - bounds.top, "one-line bubble height (24 sp line + 2 x 8 dp)")
    }

    @Test fun aLongBubbleStopsAtThreeQuartersOfTheWidth() {
        show {
            Box(Modifier.testTag("b")) {
                TextBubble("A long message that keeps going well past the width of one line on a phone screen.", BubbleSide.Person, false, false)
            }
        }
        val bounds = compose.onNodeWithTag("b").getBoundsInRoot()
        near(COLUMN * 0.75f, bounds.right - bounds.left, "bubble width limit (75% of $COLUMN)")
    }

    @Test fun cardsAre280Wide() {
        show { Box(Modifier.testTag("c")) { CardFrame(null, "Pro yearly", "€29.99 a year", "Widgets and sync.", "See the offer", filled = true) {} } }
        val bounds = compose.onNodeWithTag("c").getBoundsInRoot()
        near(280.dp, bounds.right - bounds.left, "card width")
    }

    @Test fun avatarsAre32() {
        show { Box(Modifier.testTag("a")) { Avatar("Deniz", null, theme.avatarSize) } }
        val bounds = compose.onNodeWithTag("a").getBoundsInRoot()
        near(32.dp, bounds.right - bounds.left, "incoming avatar")
    }

    private companion object {
        val COLUMN = 412.dp
    }
}
