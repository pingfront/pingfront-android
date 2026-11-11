package dev.pingfront.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.TeamMember
import java.time.Instant

/** Snapshot and preview overrides. Production leaves everything at its default. */
@Immutable
internal data class ChatPreview(
    /** A fixed "now" for timestamps and the teammate's local time. */
    val now: Instant? = null,
    /** Photos already staged in the composer. */
    val stagedPhotos: List<ByteArray> = emptyList(),
    /** Draft text in the composer. */
    val draft: String? = null,
    /** Start at the top of the thread (load-older and jump-to-latest states). */
    val scrolledToTop: Boolean = false,
    /** Show the jump-to-latest button with this many new messages. */
    val newMessageCount: Int? = null,
    /** Disable animations (snapshots). */
    val still: Boolean = false,
    /** Force debug-build behavior (the setup-problem banner) in previews. */
    val debug: Boolean? = null,
)

internal val LocalChatPreview = staticCompositionLocalOf { ChatPreview() }

/** The assistant's mark: a sparkle on the accent tint (the AI chip next to the name says what it is). */
@Composable
internal fun AiAvatar(size: Dp, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    Box(modifier.size(size).clip(CircleShape).background(theme.accentTint).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.pf_ic_auto_awesome), contentDescription = null, tint = theme.onAccentTint, modifier = Modifier.size(size * AI_MARK))
    }
}

private const val AI_MARK = 0.55f

/** A person's face: their photo, or their initials. */
@Composable
internal fun Avatar(name: String, url: String?, size: Dp, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = if (url != null) rememberChatImage("avatar:$url", url, px) else null
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(theme.accentTint)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(
                monogram(name),
                color = theme.onAccentTint,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize =
                        MaterialTheme.typography.titleMedium.fontSize * (size.value / AVATAR_REFERENCE),
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private const val AVATAR_REFERENCE = 40f

internal fun monogram(name: String): String = name.split(' ').filter {
    it.isNotBlank()
}.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }

/** Up to three overlapping team faces. */
@Composable
internal fun FacesStack(members: List<TeamMember>, size: Dp, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(-size / 3)) {
        members.take(3).forEach { member ->
            Avatar(member.name, member.avatarUrl, size, Modifier.border(BorderStroke(2.dp, theme.background), CircleShape))
        }
    }
}

/** The app icon (from the dashboard URL, or read from the package). */
@Composable
internal fun AppIcon(look: ResolvedLook, size: Dp, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val local: ImageBitmap? = remember(look.appIcon) {
        look.appIcon?.bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val remote = if (local == null && look.appIconUrl != null) rememberChatImage("icon:${look.appIconUrl}", look.appIconUrl, px) else null
    val shape = RoundedCornerShape(size * ICON_CORNER)
    Box(modifier.size(size).clip(shape).background(theme.accent).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val image = local ?: remote
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Text(monogram(look.appName), color = theme.onAccent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private const val ICON_CORNER = 0.24f

/** The "AI" chip above assistant bubbles. Always shown; never configurable. */
@Composable
internal fun AiChip() {
    val theme = LocalChatTheme.current
    Text(
        stringResource(R.string.pf_ai),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = theme.onAccentTint,
        modifier = Modifier
            // A small capsule tinted with the accent, the same shape as iOS.
            .clip(RoundedCornerShape(percent = 50))
            .background(theme.accentTint)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** The pink ping dot. Never text, never animated (the same as iOS). */
@Composable
internal fun PingDot(size: Dp = 8.dp) {
    val color = LocalChatTheme.current.ping
    Canvas(Modifier.size(size)) { drawCircle(color) }
}

/** A setup problem (debug builds) or a notice under the header. */
@Composable
internal fun Banner(message: String, icon: Int, problem: Boolean, title: String? = null, footnote: String? = null, inThread: Boolean = false) {
    val theme = LocalChatTheme.current
    val container = if (problem) theme.stop else theme.teamBubble
    // White or black, whichever reads on the container (the dark-mode problem color is light; review CQ-L17).
    val content = if (problem) {
        if (container.luminance() > LUMINANCE_SPLIT) Color.Black else Color.White
    } else {
        theme.onTeamBubble
    }
    // Under the header a full-width band; in the thread a card in the rows' own margins.
    Surface(
        color = container,
        contentColor = content,
        shape = if (inThread) RoundedCornerShape(theme.cardRadius) else androidx.compose.ui.graphics.RectangleShape,
        modifier = Modifier.fillMaxWidth().padding(vertical = if (inThread) 2.dp else 0.dp),
    ) {
        Row(Modifier.padding(horizontal = if (inThread) 16.dp else theme.gutter, vertical = if (inThread) 12.dp else 8.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp).offset(y = 1.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (footnote != null) Text(footnote, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A small marker on test devices previewing an unpublished look. */
@Composable
internal fun DraftMarker(modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    Text(
        stringResource(R.string.pf_draft_look),
        style = MaterialTheme.typography.labelMedium,
        color = theme.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .border(BorderStroke(1.dp, theme.muted), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Above this relative luminance black text has more contrast than white. */
private const val LUMINANCE_SPLIT = 0.179f
