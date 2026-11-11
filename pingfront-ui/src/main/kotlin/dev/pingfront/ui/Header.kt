package dev.pingfront.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pingfront.conversation.ConversationState
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.Author
import dev.pingfront.model.HeaderStyle
import dev.pingfront.model.TeamMember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/** The teammate the person header shows: the configured one, else the latest team author, else the first. */
internal fun headerMember(look: ResolvedLook, state: ConversationState): TeamMember? {
    val team = state.team.ifEmpty { look.team }
    look.headerMemberId?.let { id -> team.firstOrNull { it.id == id }?.let { return it } }
    val lastAuthor = state.messages.lastOrNull { it.author.kind == Author.Kind.Team }?.author?.id
    return team.firstOrNull { it.id == lastAuthor } ?: team.firstOrNull()
}

@Composable
internal fun headerTitle(look: ResolvedLook, member: TeamMember?): String = when (look.headerStyle) {
    HeaderStyle.Person -> member?.name ?: look.headerTitle.text() ?: look.appName
    HeaderStyle.Team -> look.headerTitle.text() ?: stringResource(R.string.pf_team_title, look.appName)
    HeaderStyle.App -> look.headerTitle.text() ?: look.appName
}

/**
 * The header's status lines: the reply expectation, then "It's 11:20 PM for Deniz" in the locale's time style,
 * refreshed every minute. The time never appears without its sentence (audit X-02).
 */
@Composable
internal fun statusLines(look: ResolvedLook, state: ConversationState, member: TeamMember?): List<String> {
    // No promise of a reply while the person can't send (the notice in the composer's place says why).
    val expectation = if (state.policy.canSend) replyExpectation(look, state)?.trimEnd('.') else null
    val preview = LocalChatPreview.current
    var now by remember { mutableStateOf(preview.now ?: Instant.now()) }
    if (preview.now == null) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(MINUTE_MS)
                now = Instant.now()
            }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val zone = member?.timeZone?.takeIf { look.showLocalTime && look.headerStyle == HeaderStyle.Person }?.let { runCatchingZone(it) }
    val time = zone?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(it).format(now) }
    val name = member?.name
    val local = if (time != null && name != null) stringResource(R.string.pf_local_time, time, name) else null
    return listOfNotNull(expectation, local)
}

private const val MINUTE_MS = 60_000L

private fun runCatchingZone(id: String): ZoneId? = try {
    ZoneId.of(id)
} catch (_: java.time.DateTimeException) {
    null
}

/**
 * Who you are talking to, the Google Messages / Material 3 way (audit A-04): a close button as the
 * navigation icon, a 40 dp face (team faces or the app icon), the name in titleMedium and the status lines
 * in bodySmall, start-aligned. The large centered face belongs to the welcome screen only.
 */
@Composable
internal fun ChatHeader(look: ResolvedLook, state: ConversationState, onClose: (() -> Unit)?) {
    val theme = LocalChatTheme.current
    val member = headerMember(look, state)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = theme.gutter, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.pf_ic_close), contentDescription = stringResource(R.string.pf_close_chat), tint = theme.text)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        if (!state.isEmpty) {
            Spacer(Modifier.width(4.dp))
            when (look.headerStyle) {
                // No teammate known yet: the app's icon, as in the welcome, not an initial of the app's name.
                HeaderStyle.Person -> if (member != null) Avatar(member.name, member.avatarUrl, 40.dp) else AppIcon(look, 40.dp)
                HeaderStyle.Team -> FacesStack(state.team.ifEmpty { look.team }, 32.dp)
                HeaderStyle.App -> AppIcon(look, 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    headerTitle(look, member),
                    style = MaterialTheme.typography.titleMedium,
                    color = theme.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                statusLines(look, state, member).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = theme.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (look.isDraft) DraftMarker(Modifier.padding(start = 8.dp))
    }
}
