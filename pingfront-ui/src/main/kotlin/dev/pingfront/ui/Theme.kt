package dev.pingfront.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pingfront.look.Contrast
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.Density
import dev.pingfront.model.FontStyle
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.RgbColor

/** A Compose color as a [PingFrontColor] (the same value in light and dark). */
public fun Color.toPingFrontColor(): PingFrontColor =
    PingFrontColor(RgbColor(red.toDouble(), green.toDouble(), blue.toDouble()), RgbColor(red.toDouble(), green.toDouble(), blue.toDouble()))

/** A [PingFrontColor] from two Compose colors, for `appearance { accent = PingFrontColor(light, dark) }`. */
public fun PingFrontColor(light: Color, dark: Color): PingFrontColor = PingFrontColor(light.toPingFrontColor().light, dark.toPingFrontColor().light)

internal fun RgbColor.compose(): Color = Color(argb)

/**
 * Every design token the chat reads, resolved from the [ResolvedLook] for one color scheme.
 * Views read tokens only: no hard-coded colors, radii or spacing.
 */
@Immutable
internal class ChatTheme(val look: ResolvedLook, val isDark: Boolean) {
    private fun pick(pair: PingFrontColor) = pair.value(isDark).compose()

    val accent: Color = pick(look.accent)
    val onAccent: Color = pick(look.onAccent)
    val background: Color = pick(look.background)
    val teamBubble: Color = pick(look.teamBubble)
    val text: Color = pick(look.text)

    /**
     * Secondary text. It sits on the background and inside team bubbles (the composer's placeholder, card details),
     * so it is nudged until it reads at 4.5:1 on both (the default light muted is 4.46:1 on the default bubble).
     */
    val muted: Color = Contrast.adjustText(
        Contrast.adjustText(look.muted.value(isDark), look.teamBubble.value(isDark), Contrast.MINIMUM),
        look.background.value(isDark),
        Contrast.MINIMUM,
    ).compose()
    val onTeamBubble: Color = Contrast.adjustText(look.text.value(isDark), look.teamBubble.value(isDark), Contrast.MINIMUM).compose()

    /** The accent as readable text or icon color on the background (4.5:1). */
    val accentText: Color = Contrast.adjustText(look.accent.value(isDark), look.background.value(isDark), Contrast.MINIMUM).compose()

    private val tintRgb = look.background.value(isDark).mixed(look.accent.value(isDark), if (isDark) TINT_DARK else TINT_LIGHT)

    /** A soft accent wash for icon headers, monograms and the composer's add button. */
    val accentTint: Color = tintRgb.compose()
    val onAccentTint: Color = Contrast.adjustText(look.accent.value(isDark), tintRgb, Contrast.MINIMUM).compose()

    /** Hairlines. */
    val rule: Color = look.background.value(isDark).mixed(look.text.value(isDark), RULE_MIX).compose()

    /** The pingfront ping: the pink dot on a message the team sent first. Never text. */
    val ping: Color = if (isDark) Color(PING_DARK) else Color(PING_LIGHT)

    /** Success, for receipts. */
    val success: Color = if (isDark) Color(SUCCESS_DARK) else Color(SUCCESS_LIGHT)

    /** Errors. */
    val stop: Color = if (isDark) Color(STOP_DARK) else Color(STOP_LIGHT)

    /** Filled rating stars. */
    val star: Color = Color(STAR)

    val fontFamily: FontFamily = when (look.font) {
        FontStyle.System -> FontFamily.Default
        FontStyle.Serif -> FontFamily.Serif
        FontStyle.Rounded -> Rounded
    }
    val bubbleRadius: Dp = look.bubbleRadius.dp
    val cardRadius: Dp = look.cardRadius.dp

    /** The tight corner between consecutive bubbles from the same sender (Google Messages style). */
    val joinRadius: Dp = if (look.bubbleRadius <= SQUARE_RADIUS) 2.dp else 4.dp
    val compact: Boolean = look.density == Density.Compact

    // Google Messages metrics (founder rule, 31 Oct): 2 dp within a group, 8 dp between groups (6 dp compact).
    val groupSpacing: Dp = if (compact) 6.dp else 8.dp
    val rowSpacing: Dp = 2.dp
    val gutter: Dp = if (compact) 12.dp else 16.dp
    val avatarSize: Dp = 32.dp

    // Bubble padding about 12 by 8 dp around bodyLarge (16 sp, 24 sp line height).
    val bubbleHorizontal: Dp = 12.dp
    val bubbleVertical: Dp = if (compact) 6.dp else 8.dp

    private companion object {
        const val TINT_LIGHT = 0.14
        const val TINT_DARK = 0.26
        const val RULE_MIX = 0.12
        const val SQUARE_RADIUS = 6
        const val PING_LIGHT = 0xFFFF2D78
        const val PING_DARK = 0xFFFF4F8F

        // The pingfront design tokens' `leaf`, the receipt check in both schemes.
        const val SUCCESS_LIGHT = 0xFF30D158
        const val SUCCESS_DARK = 0xFF30D158
        const val STOP_LIGHT = 0xFFB3261E
        const val STOP_DARK = 0xFFFF8A80
        const val STAR = 0xFFF5A300

        private const val MEDIUM = 500
        private const val SEMIBOLD = 600
        private const val BOLD = 700

        /**
         * Nunito (SIL Open Font License), a variable font: the "rounded" font style. Variation settings are still
         * `@ExperimentalTextApi` in Compose 1.11 (stable in 1.12), the documented way to pick a variable font's weight.
         */
        @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
        val Rounded: FontFamily = FontFamily(
            Font(
                R.font.pf_nunito,
                FontWeight.Normal,
                variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Normal.weight)),
            ),
            Font(R.font.pf_nunito, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(MEDIUM))),
            Font(R.font.pf_nunito, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(SEMIBOLD))),
            Font(R.font.pf_nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(BOLD))),
        )
    }
}

internal val LocalChatTheme = staticCompositionLocalOf { ChatTheme(ResolvedLook.Default, isDark = false) }

/** Material 3 colors and type derived from the chat tokens, so Material components inside the chat match the look. */
@Composable
internal fun ChatMaterialTheme(theme: ChatTheme, content: @Composable () -> Unit) {
    val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = theme.accent,
        onPrimary = theme.onAccent,
        primaryContainer = theme.accentTint,
        onPrimaryContainer = theme.onAccentTint,
        secondaryContainer = theme.accentTint,
        onSecondaryContainer = theme.onAccentTint,
        background = theme.background,
        onBackground = theme.text,
        surface = theme.background,
        onSurface = theme.text,
        onSurfaceVariant = theme.muted,
        surfaceContainerLowest = theme.background,
        surfaceContainerLow = theme.background,
        surfaceContainer = theme.teamBubble,
        surfaceContainerHigh = theme.teamBubble,
        surfaceContainerHighest = theme.teamBubble,
        surfaceVariant = theme.teamBubble,
        outline = theme.muted,
        outlineVariant = theme.rule,
        error = theme.stop,
    )
    val type = Typography()
    val family = theme.fontFamily
    val typography = Typography(
        displayLarge = type.displayLarge.copy(fontFamily = family),
        headlineSmall = type.headlineSmall.copy(fontFamily = family),
        titleLarge = type.titleLarge.copy(fontFamily = family),
        titleMedium = type.titleMedium.copy(fontFamily = family),
        titleSmall = type.titleSmall.copy(fontFamily = family),
        bodyLarge = type.bodyLarge.copy(fontFamily = family),
        bodyMedium = type.bodyMedium.copy(fontFamily = family),
        bodySmall = type.bodySmall.copy(fontFamily = family),
        labelLarge = type.labelLarge.copy(fontFamily = family),
        labelMedium = type.labelMedium.copy(fontFamily = family),
        labelSmall = type.labelSmall.copy(fontFamily = family),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/**
 * The chat's Material theme (its colors, dark mode and type) for SDK screens outside the chat, such as the
 * bug report, so they never fall back to Material's baseline scheme (audit A-07).
 */
@dev.pingfront.InternalPingFrontApi
@Composable
public fun PingFrontScreenTheme(content: @Composable () -> Unit) {
    val look by dev.pingfront.PingFront.instance.look.collectAsState()
    val dark = when (look.scheme) {
        dev.pingfront.model.ColorScheme.Auto -> androidx.compose.foundation.isSystemInDarkTheme()
        dev.pingfront.model.ColorScheme.Light -> false
        dev.pingfront.model.ColorScheme.Dark -> true
    }
    val theme = androidx.compose.runtime.remember(look, dark) { ChatTheme(look, dark) }
    PingFrontLanguage { androidx.compose.runtime.CompositionLocalProvider(LocalChatTheme provides theme) { ChatMaterialTheme(theme, content) } }
}

/**
 * pingfront's system lines (receipts, "messaged you first", plan status): tokens.json `font.size.13`,
 * `font.weight.semibold`, `font.tracking.system`. Only these touches are ours; the customer's ChatLook styles the rest.
 */
internal val SystemLineStyle: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle(
    fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
    lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    letterSpacing = androidx.compose.ui.unit.TextUnit(0.005f, androidx.compose.ui.unit.TextUnitType.Em),
)
