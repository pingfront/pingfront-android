package dev.pingfront.look

import dev.pingfront.model.ColorScheme
import dev.pingfront.model.Corners
import dev.pingfront.model.Density
import dev.pingfront.model.FontStyle
import dev.pingfront.model.HeaderStyle
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.Timestamps

/**
 * Look values set in code, inside `PingFront.configure { appearance { … } }`. Every field is optional:
 * unset fields fall through to values inferred from the host app, then SDK defaults. The look
 * published from the dashboard wins over these.
 */
public class ChatAppearance {
    /** The person's bubbles and buttons. Default: the host theme's `colorPrimary`. */
    public var accent: PingFrontColor? = null

    /** The chat background. */
    public var background: PingFrontColor? = null

    /** The team bubble fill. */
    public var teamBubble: PingFrontColor? = null

    /** Force light or dark, or follow the system. */
    public var scheme: ColorScheme? = null

    /** System, rounded or serif. */
    public var font: FontStyle? = null

    /** Bubble corners. */
    public var corners: Corners? = null

    /** Spacing density. */
    public var density: Density? = null

    /** Header style. */
    public var headerStyle: HeaderStyle? = null

    /** Which teammate the person header shows. */
    public var headerMemberId: String? = null

    /** Header title for the team and app styles. */
    public var headerTitle: String? = null

    /** Show the teammate's local time. */
    public var showLocalTime: Boolean? = null

    /** Show the reply expectation line. */
    public var showReplyExpectation: Boolean? = null

    /** Welcome title. */
    public var welcomeTitle: String? = null

    /** Welcome body. */
    public var welcomeBody: String? = null

    /** Show team faces on the welcome screen. */
    public var showWelcomeFaces: Boolean? = null

    /** Composer placeholder. */
    public var composerPlaceholder: String? = null

    /** Allow photos. */
    public var photos: Boolean? = null

    /** Allow bug reports. */
    public var reports: Boolean? = null

    /** Show author names. */
    public var showNames: Boolean? = null

    /** Show team avatars beside messages. */
    public var showAvatars: Boolean? = null

    /** Timestamps per group or under every message. */
    public var timestamps: Timestamps? = null

    /** For example "Usually replies within a few hours". */
    public var replyExpectation: String? = null

    /** The assistant's display name. */
    public var aiName: String? = null

    /**
     * Use Material You dynamic color (Android 12+) for the accent, background and team bubbles
     * instead of the host theme's colors. Off by default. Colors set above, and the dashboard look, still win.
     */
    public var useDynamicColor: Boolean = false

    internal fun snapshot(): ChatAppearance = ChatAppearance().also {
        it.accent = accent
        it.background = background
        it.teamBubble = teamBubble
        it.scheme = scheme
        it.font = font
        it.corners = corners
        it.density = density
        it.headerStyle = headerStyle
        it.headerMemberId = headerMemberId
        it.headerTitle = headerTitle
        it.showLocalTime = showLocalTime
        it.showReplyExpectation = showReplyExpectation
        it.welcomeTitle = welcomeTitle
        it.welcomeBody = welcomeBody
        it.showWelcomeFaces = showWelcomeFaces
        it.composerPlaceholder = composerPlaceholder
        it.photos = photos
        it.reports = reports
        it.showNames = showNames
        it.showAvatars = showAvatars
        it.timestamps = timestamps
        it.replyExpectation = replyExpectation
        it.aiName = aiName
        it.useDynamicColor = useDynamicColor
    }
}
