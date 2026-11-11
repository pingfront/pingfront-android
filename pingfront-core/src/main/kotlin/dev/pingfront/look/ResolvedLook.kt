package dev.pingfront.look

import dev.pingfront.InternalPingFrontApi
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.model.ChatLook
import dev.pingfront.model.ColorScheme
import dev.pingfront.model.Corners
import dev.pingfront.model.Density
import dev.pingfront.model.FontStyle
import dev.pingfront.model.HeaderStyle
import dev.pingfront.model.HostLook
import dev.pingfront.model.LocalizedText
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.RgbColor
import dev.pingfront.model.TeamMember
import dev.pingfront.model.Timestamps
import java.util.Base64

/** PNG bytes with value equality (for the app icon). */
public class PngImage(
    /** The encoded PNG. */
    public val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is PngImage && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/** Look values inferred from the host app (registered by the UI module). */
@InternalPingFrontApi
public data class HostAppInfo(
    val appName: String,
    val accent: PingFrontColor?,
    val icon: PngImage?,
    val appVersion: String,
) {
    internal val hostLook: HostLook get() = HostLook(
        appName = appName,
        accent = accent,
        iconPng = icon?.let {
            Base64.getEncoder().encodeToString(it.bytes)
        },
    )
}

/** Material You colors read from the system (Android 12+), used when `useDynamicColor` is on. */
@InternalPingFrontApi
public data class DynamicColors(val accent: PingFrontColor, val background: PingFrontColor, val teamBubble: PingFrontColor)

/**
 * The single resolved look the chat renders. Precedence for every field: the published remote look
 * (on test devices, the draft), then values set in code, then values inferred from the host app,
 * then SDK defaults. Colors are adjusted toward WCAG AA when a configured value would make text unreadable.
 */
@ConsistentCopyVisibility
public data class ResolvedLook internal constructor(
    /** The app name. */
    public val appName: String,
    /** The app icon URL from the dashboard. */
    public val appIconUrl: String?,
    /** The app icon read from the package. */
    public val appIcon: PngImage?,
    /** The person's bubbles and buttons (contrast-adjusted). */
    public val accent: PingFrontColor,
    /** Text on the accent, picked by contrast (never configured). */
    public val onAccent: PingFrontColor,
    /** The chat background. */
    public val background: PingFrontColor,
    /** The team bubble fill. */
    public val teamBubble: PingFrontColor,
    /** Body text. */
    public val text: PingFrontColor,
    /** Secondary text. */
    public val muted: PingFrontColor,
    /** Light, dark or automatic. */
    public val scheme: ColorScheme,
    /** Font style. */
    public val font: FontStyle,
    /** Bubble corners. */
    public val corners: Corners,
    /** Spacing density. */
    public val density: Density,
    /** Header style. */
    public val headerStyle: HeaderStyle,
    /** The teammate the person header shows. */
    public val headerMemberId: String?,
    /** Header title for the team and app styles. */
    public val headerTitle: LocalizedText?,
    /** Show the teammate's local time. */
    public val showLocalTime: Boolean,
    /** Show the reply expectation. */
    public val showReplyExpectation: Boolean,
    /** Welcome title (`null`: built-in). */
    public val welcomeTitle: LocalizedText?,
    /** Welcome body (`null`: built-in). */
    public val welcomeBody: LocalizedText?,
    /** Show team faces on the welcome screen. */
    public val showWelcomeFaces: Boolean,
    /** Topic chips, at most six. */
    public val topics: List<ChatTopic>,
    /** Composer placeholder (`null`: built-in). */
    public val composerPlaceholder: LocalizedText?,
    /** Photos allowed. */
    public val photos: Boolean,
    /** Bug reports allowed. */
    public val reports: Boolean,
    /** Show author names. */
    public val showNames: Boolean,
    /** Show team avatars beside messages. */
    public val showAvatars: Boolean,
    /** Timestamps per group or per message. */
    public val timestamps: Timestamps,
    /** The reply expectation. */
    public val replyExpectation: LocalizedText?,
    /** The assistant's display name. */
    public val aiName: String,
    /** The team from the config. */
    public val team: List<TeamMember>,
    /** The look is an unpublished draft (test devices only). */
    public val isDraft: Boolean,
    /** Remote revision, 0 when none. */
    public val revision: Int,
) {
    /** Bubble radius in dp: 18, 12 or 6. */
    public val bubbleRadius: Int get() = when (corners) {
        Corners.Round -> 20
        Corners.Soft -> 12
        Corners.Square -> 6
    }

    /** Card radius in dp: 16, 12 or 6. */
    public val cardRadius: Int get() = when (corners) {
        Corners.Round -> 16
        Corners.Soft -> 12
        Corners.Square -> 6
    }

    /** SDK defaults: Material neutrals, Google blue when the host has no usable primary color. */
    internal object Defaults {
        val background = PingFrontColor("#FFFFFF", "#131316")
        val teamBubble = PingFrontColor("#ECEEF3", "#2A2C32")
        val text = PingFrontColor("#1B1B1F", "#E4E2E6")

        // The pingfront design tokens' `color.palette.muted`; the customer's ChatLook wins.
        val muted = PingFrontColor("#6C6C78", "#9C9CA8")
        val accent = PingFrontColor.Blue
    }

    /** The default look and the merge. */
    @InternalPingFrontApi
    public companion object {
        /** The look with nothing configured. */
        public val Default: ResolvedLook = resolve(null, ChatAppearance(), null, null, null)

        /** Merges the layers. [remote] is the published (or, on test devices, draft) config. */
        internal fun resolve(
            remote: AppConfig?,
            code: ChatAppearance,
            codeTopics: List<ChatTopic>?,
            host: HostAppInfo?,
            dynamic: DynamicColors?,
        ): ResolvedLook {
            val look = remote?.look ?: ChatLook()
            val theme = look.theme
            val useDynamic = dynamic.takeIf { code.useDynamicColor }
            val remoteTopics = look.topics.map { ChatTopic(it.id, it.label, it.symbol, it.prefill) }
            val features = remote?.features ?: AppConfig.Features()
            val accent = layer(theme.accent, code.accent, useDynamic?.accent, host?.accent, default = Defaults.accent)
            return ResolvedLook(
                appName = layer(remote?.appName, host?.appName, default = ""),
                appIconUrl = remote?.appIconUrl,
                appIcon = host?.icon,
                accent = accent,
                onAccent = accent,
                background = layer(theme.background, code.background, useDynamic?.background, default = Defaults.background),
                teamBubble = layer(theme.teamBubble, code.teamBubble, useDynamic?.teamBubble, default = Defaults.teamBubble),
                text = Defaults.text,
                muted = Defaults.muted,
                scheme = layer(theme.scheme, code.scheme, default = ColorScheme.Auto),
                font = layer(theme.font, code.font, default = FontStyle.System),
                corners = layer(theme.corners, code.corners, default = Corners.Round),
                density = layer(theme.density, code.density, default = Density.Comfortable),
                headerStyle = layer(look.header.style, code.headerStyle, default = HeaderStyle.Person),
                headerMemberId = layer(look.header.memberId, code.headerMemberId),
                headerTitle = layer(look.header.title, code.headerTitle?.let(LocalizedText::of)),
                showLocalTime = layer(look.header.showLocalTime, code.showLocalTime, default = true),
                showReplyExpectation = layer(look.header.showReplyExpectation, code.showReplyExpectation, default = true),
                welcomeTitle = layer(look.welcome.title, code.welcomeTitle?.let(LocalizedText::of)),
                welcomeBody = layer(look.welcome.body, code.welcomeBody?.let(LocalizedText::of)),
                showWelcomeFaces = layer(look.welcome.showFaces, code.showWelcomeFaces, default = true),
                topics = remoteTopics.ifEmpty { codeTopics ?: ChatTopic.Defaults }.take(MAX_TOPICS),
                composerPlaceholder = layer(look.composer.placeholder, code.composerPlaceholder?.let(LocalizedText::of)),
                photos = features.photos && layer(look.composer.photos, code.photos, default = true),
                reports = features.reports && layer(look.composer.reports, code.reports, default = true),
                showNames = layer(look.messages.showNames, code.showNames, default = true),
                showAvatars = layer(look.messages.showAvatars, code.showAvatars, default = true),
                timestamps = layer(look.messages.timestamps, code.timestamps, default = Timestamps.Grouped),
                replyExpectation = layer(look.replyExpectation, code.replyExpectation?.let(LocalizedText::of)),
                aiName = layer(look.aiName, code.aiName, default = DEFAULT_AI_NAME),
                team = remote?.team ?: emptyList(),
                isDraft = remote?.draft ?: false,
                revision = remote?.revision ?: 0,
            ).withContrast()
        }

        private const val DEFAULT_AI_NAME = "Assistant"
    }

    /** Picks on-accent text by contrast and nudges surfaces toward AA (4.5:1) for body text. */
    private fun withContrast(): ResolvedLook {
        fun fix(pair: PingFrontColor, against: PingFrontColor) =
            PingFrontColor(Contrast.adjust(pair.light, against.light), Contrast.adjust(pair.dark, against.dark))
        val bg = fix(background, text)
        val bubble = fix(teamBubble, text)
        val adjusted = PingFrontColor(Contrast.accentAdjusted(accent.light), Contrast.accentAdjusted(accent.dark))
        return copy(
            background = bg,
            teamBubble = bubble,
            accent = adjusted,
            onAccent = PingFrontColor(Contrast.bestText(adjusted.light), Contrast.bestText(adjusted.dark)),
            muted = PingFrontColor(
                Contrast.adjustText(muted.light, bg.light, Contrast.MINIMUM),
                Contrast.adjustText(muted.dark, bg.dark, Contrast.MINIMUM),
            ),
        )
    }
}

/** WCAG contrast helpers. */
@InternalPingFrontApi
public object Contrast {
    /** AA for body text. */
    public const val MINIMUM: Double = 4.5

    /** White text is preferred on an accent when it reaches this ratio (the accent is then deepened to AA). */
    public const val PREFER_WHITE_ABOVE: Double = 3.0

    private const val MAX_STEPS = 40
    private const val STEP = 0.025

    /** Dark text used on light accents. */
    public val DarkText: RgbColor = RgbColor.Black

    /** Light text used on dark accents. */
    public val LightText: RgbColor = RgbColor.White

    /** White when it reaches 3:1, otherwise whichever of black or white contrasts more. */
    public fun bestText(background: RgbColor): RgbColor {
        if (RgbColor.contrast(LightText, background) >= PREFER_WHITE_ABOVE) return LightText
        return if (RgbColor.contrast(DarkText, background) >= RgbColor.contrast(LightText, background)) DarkText else LightText
    }

    /** Moves an accent away from its text color until the text reaches 4.5:1. */
    public fun accentAdjusted(accent: RgbColor): RgbColor {
        val text = bestText(accent)
        val target = if (text == LightText) RgbColor.Black else RgbColor.White
        var color = accent
        var step = 0
        while (RgbColor.contrast(text, color) < MINIMUM && step < MAX_STEPS) {
            step += 1
            color = accent.mixed(target, step * STEP)
        }
        return color
    }

    /** Moves a surface away from [text] until text on it reaches 4.5:1. */
    public fun adjust(surface: RgbColor, text: RgbColor): RgbColor {
        if (RgbColor.contrast(surface, text) >= MINIMUM) return surface
        val target = if (text.luminance > 0.5) RgbColor.Black else RgbColor.White
        var color = surface
        var step = 0
        while (RgbColor.contrast(color, text) < MINIMUM && step < MAX_STEPS) {
            step += 1
            color = surface.mixed(target, step * STEP)
        }
        return color
    }

    /** Moves text away from its background until it reaches [minimum]. */
    public fun adjustText(text: RgbColor, background: RgbColor, minimum: Double): RgbColor {
        if (RgbColor.contrast(text, background) >= minimum) return text
        val target = if (RgbColor.contrast(RgbColor.Black, background) >=
            RgbColor.contrast(RgbColor.White, background)
        ) {
            RgbColor.Black
        } else {
            RgbColor.White
        }
        var color = text
        var step = 0
        while (RgbColor.contrast(color, background) < minimum && step < MAX_STEPS) {
            step += 1
            color = text.mixed(target, step * STEP)
        }
        return color
    }
}

private const val MAX_TOPICS = 6

/** The first set value, by precedence (remote, then code, then host), else [default]. */
private fun <T : Any> layer(vararg values: T?, default: T): T = values.firstOrNull { it != null } ?: default

/** The first set value, or `null`. */
private fun <T : Any> layer(vararg values: T?): T? = values.firstOrNull { it != null }
