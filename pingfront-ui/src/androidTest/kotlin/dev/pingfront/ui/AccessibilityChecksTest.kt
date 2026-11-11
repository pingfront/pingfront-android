package dev.pingfront.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import dev.pingfront.model.ChatLook
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.assertFails
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google's Accessibility Test Framework over the chat, the Android counterpart of the iOS audit: touch targets,
 * text and image contrast on the rendered screen, labels, duplicate descriptions and traversal. Warnings fail
 * too. Each variant checks the welcome screen and a conversation. Runs on a device or emulator
 * (`./gradlew :pingfront-ui:connectedDebugAndroidTest`); the framework skips Compose under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Warnings fail too. Two kinds of contrast estimate are set aside:
     * - a message that has scrolled under the header, which the framework itself marks as partially visible in a
     *   scrolling container and unreliable (it samples the header's colors, not the bubble's);
     * - an estimate taken from a thin script's anti-aliased edges (Arabic at 12 sp: the framework read one edge
     *   shade, #BEBEC3, for a line drawn in #696975), set aside only when the text's own drawn color, the most
     *   frequent color in its bounds after the background, reads at 4.5:1 or more on that background.
     * Everything else on screen is checked.
     */
    private fun strict(): AccessibilityValidator = AccessibilityValidator()
        .setThrowExceptionFor(AccessibilityCheckResultType.WARNING)
        .setSuppressingResultMatcher(estimateOnly)

    /** The screen as drawn just before the current check, and where the compose root sits on screen. */
    private var drawn: Bitmap? = null
    private var origin = IntOffset(0, 0)

    private val estimateOnly = object : TypeSafeMatcher<AccessibilityViewCheckResult>() {
        override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean {
            if (result.sourceCheckClass != TextContrastCheck::class.java) return false
            if (result.getMessage(Locale.ENGLISH).toString().contains("partially visible within a scrollable container")) return true
            val bounds = result.element?.boundsInScreen ?: return false
            val screen = drawn ?: return false
            return drawnTextContrast(screen, bounds.left - origin.x, bounds.top - origin.y, bounds.right - origin.x, bounds.bottom - origin.y) >= MINIMUM
        }

        override fun describeTo(description: Description) {
            description.appendText("a contrast estimate for text scrolled under the header, or one its drawn colors disprove")
        }
    }

    /** Captures the screen, then runs the checks on it. */
    private fun checkNow() {
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().positionOnScreen
        origin = IntOffset(root.x.roundToInt(), root.y.roundToInt())
        drawn = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    /** The test fails its own way on a bad estimate rule: real low-contrast text is not set aside. */
    @Test fun aDrawnLowContrastTextIsStillCaught() {
        compose.enableAccessibilityChecks(strict())
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Text("Barely there", color = Color(0xFFBEBEC3), fontSize = 12.sp)
            }
        }
        assertFails { checkNow() }
    }

    private fun check(
        dark: Boolean = false,
        fontScale: Float = 1f,
        locale: String = "en",
        app: Fixtures.App = Fixtures.Clockwise,
        look: ChatLook = Fixtures.EmptyLook,
    ) {
        FixtureImages.install(compose.activity)
        val instance = PingFrontInstance.detached()
        val kind = mutableStateOf(Fixtures.Kind.Welcome)
        Fixtures.load(instance, kind.value, locale, app, look, false)
        compose.enableAccessibilityChecks(strict())
        compose.setContent {
            Variant(dark, fontScale, locale) {
                key(kind.value) { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) }
            }
        }
        checkNow()

        Fixtures.load(instance, Fixtures.Kind.Conversation, locale, app, look, false)
        kind.value = Fixtures.Kind.Conversation
        checkNow()
    }

    /** The checks really run: a small, unlabeled button is caught. */
    @Test fun theChecksCatchAProblem() {
        compose.enableAccessibilityChecks(strict())
        compose.setContent { Box(Modifier.size(12.dp).clickable {}) }
        assertFails { compose.onRoot().tryPerformAccessibilityChecks() }
    }

    @Test fun light() = check()

    @Test fun dark() = check(dark = true)

    /** The largest font size Android offers (200 %). */
    @Test fun largestFont() = check(fontScale = 2f)

    @Test fun rightToLeft() = check(locale = "ar")

    /** A custom look: Ledgerly's serif, green accent, soft corners and stacked team. */
    @Test fun customLook() = check(app = Fixtures.Ledgerly, look = Fixtures.Ledgerly.look)

    @Test fun customLookDark() = check(dark = true, app = Fixtures.Ledgerly, look = Fixtures.Ledgerly.look)

    /** The two loading states with no thread: offline (banner over the welcome) and the server failing to load. */
    private fun checkState(dark: Boolean, configure: (PingFrontInstance) -> Unit) {
        FixtureImages.install(compose.activity)
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        configure(instance)
        compose.enableAccessibilityChecks(strict())
        compose.setContent { Variant(dark, 1f, "en") { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) } }
        checkNow()
    }

    @Test fun offlineWelcome() = checkState(dark = false) { Fixtures.setLoadFailed(it, offline = true) }

    @Test fun offlineWelcomeDark() = checkState(dark = true) { Fixtures.setLoadFailed(it, offline = true) }

    @Test fun loadFailed() = checkState(dark = false) { Fixtures.setLoadFailed(it, offline = false) }

    @Test fun loadFailedDark() = checkState(dark = true) { Fixtures.setLoadFailed(it, offline = false) }
}

private const val MINIMUM = 4.5

/**
 * The WCAG contrast of the text drawn in [left, top, right, bottom) of [screen]: the most frequent color there is
 * the background, the next most frequent is the text (anti-aliased edge shades are each far rarer).
 */
private fun drawnTextContrast(screen: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Double {
    val counts = HashMap<Int, Int>()
    for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(screen.height)) {
        for (x in left.coerceAtLeast(0) until right.coerceAtMost(screen.width)) {
            val color = screen.getPixel(x, y) or 0xFF000000.toInt()
            counts[color] = (counts[color] ?: 0) + 1
        }
    }
    val ranked = counts.entries.sortedByDescending { it.value }
    if (ranked.size < 2) return 0.0
    return ColorUtils.calculateContrast(ranked[1].key, ranked[0].key)
}

/** The chat as a person with these settings sees it: scheme, text size, language and direction. */
@Composable
private fun Variant(dark: Boolean, fontScale: Float, locale: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val density = LocalDensity.current
    val system = LocalConfiguration.current
    val configuration = remember(system, dark, fontScale, locale) {
        Configuration(system).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
            setLocale(Locale.forLanguageTag(locale))
            setLayoutDirection(Locale.forLanguageTag(locale))
        }
    }
    val context = remember(configuration) {
        val resources = base.createConfigurationContext(configuration).resources
        object : ContextWrapper(base) {
            override fun getResources(): Resources = resources
        } as Context
    }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides configuration,
        LocalDensity provides Density(density.density, fontScale),
        LocalLayoutDirection provides if (locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) { content() }
}
