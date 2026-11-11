package dev.pingfront.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.ConversationState
import dev.pingfront.look.ChatTopic
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.HeaderStyle

/**
 * The empty state: a large face, the name, the reply expectation and the topic chips. At a compact window height (a
 * phone in landscape) the words and the topics sit side by side, as on iOS, so the topics show without scrolling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Welcome(
    instance: PingFrontInstance,
    state: ConversationState,
    look: ResolvedLook,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    condensed: Boolean = false,
    notices: (@Composable () -> Unit)? = null,
) {
    val theme = LocalChatTheme.current
    // Free plan only, and only on this empty screen: never inside a conversation.
    val branding = instance.showsBranding.collectAsState().value
    // A topic only fills the composer: with sending off (blocked, paused, the SDK disabled) it would be a dead end.
    val showsTopics = look.topics.isNotEmpty() && state.policy.canSend && !disabled
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minHeight = maxHeight * MIN_HEIGHT_FRACTION
            if (condensed) {
                // The notices, when they are content, are the welcome's first row and scroll with it.
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minHeight).padding(vertical = 8.dp)) {
                    notices?.let { Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { it() } }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WelcomeWords(state, look, disabled, condensed = true, Modifier.weight(1f))
                        if (showsTopics) {
                            FlowRow(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                look.topics.forEach { topic -> TopicChip(topic, instance) }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minHeight)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    notices?.let { Box(Modifier.padding(bottom = 16.dp)) { it() } }
                    WelcomeWords(state, look, disabled, condensed = false)
                    if (showsTopics) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.widthIn(max = 440.dp).padding(top = 24.dp),
                        ) {
                            look.topics.forEach { topic -> TopicChip(topic, instance) }
                        }
                    }
                }
            }
        }
        // Pingfront's own one-colour lockup on its own bar above the composer, in the chat's muted colour: it never
        // scrolls with the welcome, never carries pink into a look that may not match, and never appears in a
        // conversation.
        if (branding) {
            Image(
                painter = painterResource(R.drawable.pf_lockup),
                contentDescription = stringResource(R.string.pf_chat_by_pingfront),
                colorFilter = ColorFilter.tint(theme.muted),
                contentScale = ContentScale.Fit,
                alpha = BRANDING_ALPHA,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp).height(BRANDING_HEIGHT),
            )
        }
    }
}

/** The face, the name, the reply expectation and the welcome text: centred, or start-aligned beside the topics. */
@Composable
private fun WelcomeWords(state: ConversationState, look: ResolvedLook, disabled: Boolean, condensed: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = if (condensed) Alignment.Start else Alignment.CenterHorizontally) {
        WelcomeWordsContent(state, look, disabled, condensed)
    }
}

@Composable
private fun ColumnScope.WelcomeWordsContent(state: ConversationState, look: ResolvedLook, disabled: Boolean, condensed: Boolean) {
    val theme = LocalChatTheme.current
    val member = headerMember(look, state)
    val align = if (condensed) TextAlign.Start else TextAlign.Center
    // Condensed (a phone in landscape), the words and the topics share one short row: the face would take it all.
    if (look.showWelcomeFaces && !condensed) {
        when (look.headerStyle) {
            HeaderStyle.App -> AppIcon(look, 88.dp)
            HeaderStyle.Team -> FacesStack(state.team.ifEmpty { look.team }, 72.dp)
            // Without a teammate yet (a new install before the config), the app's icon, as on iOS: never a letter tile.
            HeaderStyle.Person -> if (member != null) Avatar(member.name, member.avatarUrl, 96.dp) else AppIcon(look, 88.dp)
        }
    }
    val customTitle = look.welcomeTitle.text()
    Text(
        customTitle ?: headerTitle(look, member),
        style = if (condensed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = theme.text,
        textAlign = align,
        modifier = Modifier.padding(top = if (condensed) 0.dp else 14.dp).semantics { heading() },
    )
    // A person who can't send gets no promise of a reply: no reply expectation and no "reply here" body (the notice in
    // the composer's place says why), as on the web widget.
    val open = state.policy.canSend && !disabled
    val expectation = if (open) replyExpectation(look, state) else null
    val subtitle = if (look.headerStyle == HeaderStyle.Person && member?.role != null && customTitle == null) {
        listOfNotNull(member.role, expectation).joinToString("\n")
    } else {
        expectation
    }
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = theme.muted, textAlign = align, modifier = Modifier.padding(top = 4.dp))
    }
    // Condensed, the short row holds who replies and how soon beside the topics; the longer welcome text waits for the
    // taller window.
    if (open && !condensed) {
        Text(
            look.welcomeBody.text() ?: stringResource(R.string.pf_welcome_body, look.appName),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.muted,
            textAlign = align,
            modifier = Modifier.widthIn(max = 360.dp).padding(top = 12.dp),
        )
    }
}

private const val MIN_HEIGHT_FRACTION = 0.9f

/** The lockup's in-app size, 14 dp (brand/logo/source/inapp.mjs). */
private val BRANDING_HEIGHT = 14.dp

/** Faded so it reads as a footnote, in either appearance, and never disturbs the welcome. */
private const val BRANDING_ALPHA = 0.65f
private const val ICON_SP = 18

/** What a topic tap does; nothing is ever sent from here. */
internal fun topicTapped(topic: ChatTopic, starter: String?, instance: PingFrontInstance) {
    if (topic.id == ChatTopic.Bug.id && topic.prefill == null) {
        instance.openReport(null)
    } else {
        // Never overwrite what the person already typed: then the tap only focuses the composer.
        val draft = instance.conversation.draft.value
        instance.conversation.insert(if (draft.isBlank()) starter ?: draft else draft)
    }
}

/**
 * A topic opens its flow and never sends anything by itself (audit X-01): "Report a bug" opens the report;
 * the others put a sentence start (or the customer's prefill) into the composer and focus it.
 */
@Composable
private fun TopicChip(topic: ChatTopic, instance: PingFrontInstance) {
    val theme = LocalChatTheme.current
    val label = topic.displayLabel()
    val starter = topic.starter()
    val iconSize = with(androidx.compose.ui.platform.LocalDensity.current) { ICON_SP.sp.toDp() }
    AssistChip(
        onClick = { topicTapped(topic, starter, instance) },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = {
            Icon(
                painterResource(topic.symbol.icon),
                contentDescription = null,
                tint = theme.accentText,
                // Scales with the font size like the label (audit A-08).
                modifier = Modifier.size(iconSize),
            )
        },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(containerColor = theme.background, labelColor = theme.text),
        modifier = Modifier.heightIn(min = 48.dp),
    )
}
