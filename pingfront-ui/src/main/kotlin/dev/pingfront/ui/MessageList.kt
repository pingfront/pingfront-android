package dev.pingfront.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pingfront.PingFrontInstance
import dev.pingfront.conversation.ConversationState
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.conversation.TimelineItem
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.model.PingOrigin
import dev.pingfront.model.SendPolicy
import dev.pingfront.model.Timestamps
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** One rendered row: a timeline item, plus the list-level rows around it. */
private sealed interface Row {
    val key: String

    class Item(val item: TimelineItem) : Row {
        // Stable across the pending -> delivered hand-off, so a sent message never re-animates.
        override val key: String = when (val c = item.content) {
            is TimelineItem.Content.Delivered -> c.message.clientId ?: c.message.id
            is TimelineItem.Content.Pending -> c.message.clientId
        }
    }

    class Time(val at: Instant, override val key: String) : Row

    data object Typing : Row {
        override val key: String = "typing"
    }

    data object Older : Row {
        override val key: String = "older"
    }

    /** After the person's first message, before any reply: what happens next (audit X-05). */
    data object Next : Row {
        override val key: String = "next"
    }

    /** The offline, unreachable and setup notices, as a row of the thread at large text or a compact height. */
    data object Notice : Row {
        override val key: String = "notice"
    }
}

/**
 * Where the notices go when they are a row of the thread: before the first message still waiting to send (its id), or
 * last when none waits (`null`). A message the person sends lands below the notice, in view, and its line says what the
 * notice says.
 */
internal fun rowAfterNotice(timeline: List<TimelineItem>): String? = timeline.firstOrNull { it.content is TimelineItem.Content.Pending }?.id

/**
 * "You can close this; the reply waits here" after the person's first message, until the team or the assistant
 * answers. Only once everything the person sent is delivered: it would be untrue while one still waits to send
 * (offline, out of reach, held).
 */
internal fun showsCloseHint(state: ConversationState): Boolean {
    if (state.pending.isNotEmpty() || !state.policy.canSend) return false
    val first = state.messages.indexOfFirst { it.isFromPerson }
    if (first < 0) return false
    return state.messages.drop(first).none {
        it.author.kind == dev.pingfront.model.Author.Kind.Team || it.author.kind == dev.pingfront.model.Author.Kind.Ai
    }
}

/** The thread, newest at the bottom (a reversed lazy list, so it opens at the latest message). */
@Composable
internal fun MessageList(
    instance: PingFrontInstance,
    state: ConversationState,
    look: ResolvedLook,
    modifier: Modifier = Modifier,
    headerShowsExpectation: Boolean = true,
    notices: (@Composable () -> Unit)? = null,
) {
    val theme = LocalChatTheme.current
    val preview = LocalChatPreview.current
    val animate = !preview.still && animationsEnabled()
    val inThread = notices != null
    val rows = remember(state.timeline, state.typing, state.hasOlder, state.policy, state.pending, inThread) {
        val noticeBefore = if (inThread) rowAfterNotice(state.timeline) else null
        buildList {
            if (state.hasOlder) add(Row.Older)
            state.timeline.forEach { item ->
                if (inThread && item.id == noticeBefore) add(Row.Notice)
                if (item.startsTimeSection) add(Row.Time(item.date, "time-${item.id}"))
                add(Row.Item(item))
            }
            // "The reply waits here" is a promise: not while the person can't send, nor while a message waits to send.
            if (showsCloseHint(state) && state.typing == null) add(Row.Next)
            if (state.typing != null) add(Row.Typing)
            // Nothing waits to send: the notice is the thread's last row, in view where it opens.
            if (inThread && noticeBefore == null) add(Row.Notice)
        }.asReversed()
    }
    val lines = remember(state.pending, state.policy, state.isOffline, state.isServerUnreachable) { PendingLine.shown(state) }
    val lastPersonId = remember(state.messages) { state.messages.lastOrNull { it.isFromPerson }?.id }
    val seen = remember(state.messages, state.teamReadSeq) {
        state.messages.lastOrNull { it.isFromPerson }?.let {
            state.teamReadSeq >=
                it.seq
        }
            ?: false
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (preview.scrolledToTop) (rows.size - 1).coerceAtLeast(0) else 0)
    val scope = rememberCoroutineScope()
    val atTop by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ==
                listState.layoutInfo.totalItemsCount - 1
        }
    }
    val awayFromLatest by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    // A new row at the newest end (a reply, a card, the person's own message) comes into view when the person was at
    // the newest one, as in Google Messages; the list alone keeps its position by key and leaves it below the fold
    // (review AN-15). Read during composition, so it is the position before the new row is laid out.
    val newest = rows.firstOrNull()?.key
    val wasAtNewest = remember(newest) { listState.firstVisibleItemIndex == 0 }
    LaunchedEffect(newest) {
        if (newest != null && wasAtNewest && !preview.still) {
            if (animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
        }
    }
    // A push tap, a chat link or a moment while this chat is on screen: it comes to the latest message instead of a
    // second chat opening over it.
    LaunchedEffect(instance, listState) {
        instance.chatFocusRequests.collect { if (animate) listState.animateScrollToItem(0) else listState.scrollToItem(0) }
    }
    LaunchedEffect(atTop, state.hasOlder) {
        if (atTop && state.hasOlder && !state.isLoadingOlder) instance.conversation.loadOlder()
    }

    val superseded = remember(state.messages) { supersededOffers(state.messages) }
    val askPerson = remember(state.messages, state.policy) { askForPersonTarget(state.messages, state.policy.canSend) }
    Box(modifier) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSupersededOffers provides superseded, LocalAskPersonTarget provides askPerson) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = theme.gutter, vertical = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { it.key }, contentType = { it::class }) { row ->
                    val itemModifier = if (animate) Modifier.animateItem() else Modifier
                    when (row) {
                        is Row.Time -> TimeHeader(row.at, itemModifier)
                        Row.Typing -> TypingRow(state, look, animate, itemModifier.padding(top = theme.groupSpacing))
                        Row.Older -> OlderRow(state.isLoadingOlder, { instance.conversation.loadOlder() }, itemModifier)
                        Row.Next -> NextRow(instance, state, look, headerShowsExpectation, itemModifier.padding(top = theme.groupSpacing))
                        Row.Notice -> Box(itemModifier.padding(top = theme.groupSpacing)) { notices?.invoke() }
                        is Row.Item -> {
                            val top = if (row.item.startsGroup) theme.groupSpacing else theme.rowSpacing
                            Box(itemModifier.padding(top = top)) {
                                MessageRow(
                                    row.item,
                                    instance,
                                    look,
                                    isLastPerson = row.item.id == lastPersonId,
                                    seen = seen,
                                    sending = SendingContext(state, lines),
                                )
                            }
                        }
                    }
                }
            }
        }
        // The thread fades under the header instead of being cut by it.
        Box(
            Modifier.fillMaxWidth().height(FADE).align(Alignment.TopCenter).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(theme.background, theme.background.copy(alpha = 0f))),
            ),
        )
        val newCount = preview.newMessageCount
        AnimatedVisibility(
            visible = (awayFromLatest && !preview.still) || newCount != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            val label = if (newCount != null &&
                newCount > 0
            ) {
                pluralStringResource(R.plurals.pf_new_messages, newCount, newCount)
            } else {
                stringResource(R.string.pf_jump_latest)
            }
            ExtendedFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                icon = { Icon(painterResource(R.drawable.pf_ic_arrow_downward), contentDescription = null) },
                text = { Text(label) },
                containerColor = theme.teamBubble,
                contentColor = theme.text,
            )
        }
    }
}

@Composable
private fun OlderRow(loading: Boolean, load: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            if (LocalChatPreview.current.still) {
                CircularProgressIndicator(progress = {
                    STILL_PROGRESS
                }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.muted)
            } else {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.muted)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pf_loading_earlier), style = MaterialTheme.typography.bodySmall, color = theme.muted)
        } else {
            TextAction(stringResource(R.string.pf_load_earlier), theme.accentText, load)
        }
    }
}

private const val STILL_PROGRESS = 0.7f
private val FADE = 20.dp

/** Centered grouped time ("Today 18:20"). */
@Composable
private fun TimeHeader(at: Instant, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    val now = LocalChatPreview.current.now ?: Instant.now()
    Text(
        timeLabel(at, now),
        style = MaterialTheme.typography.labelMedium,
        color = theme.muted,
        modifier = modifier.fillMaxWidth().padding(top = theme.groupSpacing + 4.dp).semantics { heading() },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
internal fun timeLabel(at: Instant, now: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.pf_today, clock)
        today.minusDays(1) -> stringResource(R.string.pf_yesterday, clock)
        else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)
    }
}

@Composable
private fun MessageRow(item: TimelineItem, instance: PingFrontInstance, look: ResolvedLook, isLastPerson: Boolean, seen: Boolean, sending: SendingContext) {
    when (val content = item.content) {
        is TimelineItem.Content.Delivered -> when (item.side) {
            TimelineItem.Side.System -> Column {
                content.message.parts.filterIsInstance<dev.pingfront.model.MessagePart.Receipt>().forEach { ReceiptLine(it) }
            }
            TimelineItem.Side.Team -> TeamRow(item, content.message, instance, look)
            TimelineItem.Side.Person -> PersonRow(item, content.message, null, instance, if (isLastPerson) seen else null, sending)
        }
        is TimelineItem.Content.Pending -> PersonRow(item, null, content.message, instance, null, sending)
    }
}

@Composable
private fun TeamRow(item: TimelineItem, message: Message, instance: PingFrontInstance, look: ResolvedLook) {
    val theme = LocalChatTheme.current
    val name = if (item.isAi) aiDisplayName(look, message.author.name) else message.author.name ?: stringResource(R.string.pf_the_team)
    val avatarColumn = if (look.showAvatars) theme.avatarSize + 8.dp else 0.dp
    var showOriginal by remember { mutableStateOf(false) }
    val texts = if (message.translation != null && !showOriginal) message.translation?.texts else null
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = false) { }) {
        val ping = message.ping
        if (item.startsPing && ping != null) PingLine(ping, name)
        if (item.startsGroup && (look.showNames || item.isAi)) {
            Row(Modifier.padding(start = avatarColumn + 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.labelMedium, color = theme.muted)
                if (item.isAi) {
                    Spacer(Modifier.width(6.dp))
                    AiChip()
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (look.showAvatars) {
                when {
                    !item.endsGroup -> Spacer(Modifier.width(theme.avatarSize))
                    // The assistant is not a person: a sparkle mark, never initials (audit A-05).
                    item.isAi -> AiAvatar(theme.avatarSize)
                    else -> Avatar(name, message.author.avatarUrl, theme.avatarSize)
                }
                Spacer(Modifier.width(8.dp))
            }
            // TalkBack reads what is on screen (the translation when shown) in a localized "name: text" form.
            // Messages with cards or receipts keep their children's own descriptions (review CQ-M12).
            val shown = (texts ?: message.parts.filterIsInstance<MessagePart.Text>().map { it.text }).joinToString(" ")
            val textOnly = message.parts.all { it is MessagePart.Text }
            val description = stringResource(R.string.pf_message_from, name, shown.ifEmpty { message.fallback.orEmpty() })
            Box(if (textOnly) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier) {
                Parts(message, BubbleSide.Team, texts, joinTop = !item.startsGroup, joinBottom = !item.endsGroup, instance = instance)
            }
        }
        if (message.translation != null) {
            Box(Modifier.padding(start = avatarColumn)) {
                TextAction(stringResource(if (showOriginal) R.string.pf_show_translation else R.string.pf_show_original), theme.muted) {
                    showOriginal =
                        !showOriginal
                }
            }
        }
        if (look.timestamps == Timestamps.Always) {
            Text(
                timeLabel(message.createdAt, LocalChatPreview.current.now ?: Instant.now()),
                style = MaterialTheme.typography.labelSmall,
                color = theme.muted,
                modifier = Modifier.padding(start = avatarColumn + 12.dp, top = 2.dp),
            )
        }
        if (item.isAi && message.id == LocalAskPersonTarget.current) {
            val ask = stringResource(R.string.pf_ask_person_message)
            Box(Modifier.padding(start = avatarColumn)) {
                TextAction(stringResource(R.string.pf_ask_person), theme.accentText) { instance.conversation.send(ask) }
            }
        }
    }
}

/** The assistant's message that offers "Ask for a person", or null (see [askForPersonTarget]). */
private val LocalAskPersonTarget = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * "Ask for a person" goes under the assistant's latest message only, and only while asking still means something: not
 * once the assistant handed the thread off or a teammate wrote after it, and not when the person can't send. Under
 * every AI message it repeated itself, and after "Deniz will reply" it contradicted the hand-off.
 */
internal fun askForPersonTarget(messages: List<Message>, canSend: Boolean): String? {
    if (!canSend) return null
    val last = messages.indexOfLast { it.author.kind == dev.pingfront.model.Author.Kind.Ai }
    if (last < 0) return null
    val handedOver = messages.drop(last + 1).any { message ->
        message.author.kind == dev.pingfront.model.Author.Kind.Team ||
            message.parts.any { it is MessagePart.Receipt && it.action == dev.pingfront.model.ReceiptAction.HandedOff }
    }
    return if (handedOver) null else messages[last].id
}

/** Above a message the team sent first: the pink dot, "Deniz messaged you first", tap for why. */
@Composable
private fun PingLine(ping: PingOrigin, author: String) {
    val theme = LocalChatTheme.current
    var explaining by remember { mutableStateOf(false) }
    val label = stringResource(R.string.pf_messaged_you_first, author)
    val why = stringResource(R.string.pf_why_message)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = why) { explaining = true }
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PingDot()
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.merge(SystemLineStyle), color = theme.muted)
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(R.drawable.pf_ic_info), contentDescription = null, tint = theme.muted, modifier = Modifier.size(16.dp))
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(why) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ping.kind == PingOrigin.Kind.Manual) stringResource(R.string.pf_wrote_first, author) else ping.explanation)
                    Text(stringResource(R.string.pf_reply_if_you_like), color = theme.muted)
                }
            },
        )
    }
}

@Composable
private fun PersonRow(item: TimelineItem, message: Message?, pending: PendingMessage?, instance: PingFrontInstance, seen: Boolean?, sending: SendingContext) {
    val theme = LocalChatTheme.current
    val text = message?.plainText ?: pending?.text.orEmpty()
    // Photos are named too, so a photo-only message never reads as an empty "You:".
    val photo = stringResource(R.string.pf_photo)
    val removed = stringResource(R.string.pf_photo_removed)
    val photos = message?.parts?.filterIsInstance<MessagePart.Image>()?.map { if (it.removed) removed else photo }
        ?: List(pending?.photos?.size ?: 0) { photo }
    val you = stringResource(R.string.pf_you, (photos + listOfNotNull(text.ifEmpty { null })).joinToString(", "))
    // A failure that needs the person: the error mark and the red line open one Try again / Delete dialog. A held
    // message goes by itself, so it gets no mark.
    val failed = pending?.state == PendingMessage.State.Failed && !pending.isHeld
    var options by remember { mutableStateOf(false) }
    // Held, or queued behind a held message: Delete is in the bubble's long-press menu and among TalkBack's actions.
    val deletable = pending != null && PendingLine.deletable(pending, sending.state)
    var menu by remember { mutableStateOf(false) }
    val delete = stringResource(R.string.pf_delete)
    val tryAgain = stringResource(R.string.pf_try_again)
    val canRetry = sending.state.policy.canSend
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = you
                if (pending != null && (failed || deletable)) {
                    customActions = buildList {
                        if (failed && canRetry) {
                            add(
                                CustomAccessibilityAction(tryAgain) {
                                    instance.conversation.retry(pending)
                                    true
                                },
                            )
                        }
                        add(
                            CustomAccessibilityAction(delete) {
                                instance.conversation.discard(pending)
                                true
                            },
                        )
                    }
                }
            },
        ) {
            if (failed) {
                val notDelivered = stringResource(R.string.pf_not_delivered)
                // Like Google Messages: the error mark beside the bubble, a 48 dp target.
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(role = Role.Button) { options = true }
                        .semantics { contentDescription = notDelivered },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.pf_ic_error), contentDescription = null, tint = theme.stop, modifier = Modifier.size(22.dp))
                }
            }
            if (message != null) {
                Parts(message, BubbleSide.Person, null, joinTop = !item.startsGroup, joinBottom = !item.endsGroup, instance = instance)
            } else if (pending != null) {
                Box {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(theme.rowSpacing),
                        modifier = if (deletable) {
                            Modifier.pointerInput(pending.clientId) {
                                detectTapGestures(onLongPress = {
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    menu = true
                                })
                            }
                        } else {
                            Modifier
                        },
                    ) {
                        PendingParts(item, pending, instance)
                    }
                    DropdownMenu(expanded = menu && deletable, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(delete) },
                            leadingIcon = { Icon(painterResource(R.drawable.pf_ic_delete), contentDescription = null) },
                            onClick = {
                                menu = false
                                instance.conversation.discard(pending)
                            },
                        )
                    }
                }
            }
        }
        StatusLine(pending, seen, sending, onFailure = { options = true })
        if (options && pending != null) {
            // Material's form of iOS's confirmation dialog. Try again only where sending again can work: while the person
            // can't send (the notice in the composer's place says why), the dialog offers Delete alone.
            AlertDialog(
                onDismissRequest = { options = false },
                title = { Text(stringResource(R.string.pf_not_delivered)) },
                confirmButton = {
                    TextButton(onClick = {
                        options = false
                        if (canRetry) instance.conversation.retry(pending) else instance.conversation.discard(pending)
                    }) { Text(if (canRetry) tryAgain else delete) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        options = false
                        if (canRetry) instance.conversation.discard(pending)
                    }) { Text(if (canRetry) delete else stringResource(R.string.pf_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun PendingParts(item: TimelineItem, pending: PendingMessage, instance: PingFrontInstance) {
    if (pending.photos.size >= 2) {
        PhotoGrid(pending.photos.size, BubbleSide.Person, !item.startsGroup, !item.endsGroup || pending.text != null) {
            PendingPhotoBubble(pending.photos[it], joinTop = true, joinBottom = true, instance = instance, cell = true)
        }
    } else {
        pending.photos.forEachIndexed { i, photo ->
            PendingPhotoBubble(
                photo,
                !item.startsGroup || i > 0,
                !item.endsGroup || i < pending.photos.size - 1 || pending.text != null,
                instance,
            )
        }
    }
    pending.text?.let {
        TextBubble(
            it,
            BubbleSide.Person,
            joinTop = !item.startsGroup || pending.photos.isNotEmpty(),
            joinBottom = !item.endsGroup,
        )
    }
}

/** What the status lines need from the conversation, read once for the list instead of once per row. */
internal class SendingContext(val state: ConversationState, val lines: Map<String, PendingLine>)

@Composable
private fun StatusLine(pending: PendingMessage?, seen: Boolean?, sending: SendingContext, onFailure: () -> Unit) {
    val theme = LocalChatTheme.current
    val style = MaterialTheme.typography.labelSmall
    val line = Modifier.padding(top = 2.dp, end = 4.dp)
    when {
        pending == null -> if (seen != null) {
            Text(stringResource(if (seen) R.string.pf_read else R.string.pf_delivered), style = style, color = theme.muted, modifier = line)
        }
        // The red line says what happened; it and the mark beside the bubble open the one dialog. TalkBack hears it
        // once, on the mark, and finds Try again and Delete among the row's actions.
        pending.state == PendingMessage.State.Failed && !pending.isHeld -> Text(
            stringResource(R.string.pf_not_delivered),
            style = style,
            color = theme.stop,
            modifier = line.clickable(onClick = onFailure).clearAndSetSemantics { },
        )
        else -> PendingStatus(sending.lines[pending.clientId])
    }
}

/** The muted line under an undelivered message, when it is the last of its run (see [PendingLine.shown]). */
@Composable
private fun PendingStatus(shown: PendingLine?) {
    val theme = LocalChatTheme.current
    val style = MaterialTheme.typography.labelSmall
    val line = Modifier.padding(top = 2.dp, end = 4.dp)
    when (shown) {
        null -> Unit
        is PendingLine.Uploading -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 4.dp, end = 4.dp)) {
            Text(pluralStringResource(R.plurals.pf_uploading_photos, shown.photos, shown.photos), style = style, color = theme.muted)
            if (LocalChatPreview.current.still) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { PREVIEW_PROGRESS },
                    modifier = Modifier.width(120.dp).padding(top = 4.dp),
                    color = theme.accent,
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.width(120.dp).padding(top = 4.dp), color = theme.accent)
            }
        }
        else -> Text(
            stringResource(shown.text()),
            style = style,
            color = theme.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = line,
        )
    }
}

/** The words of a pending line (not [PendingLine.Uploading], which is a plural). */
internal fun PendingLine.text(): Int = when (this) {
    PendingLine.Waiting -> R.string.pf_waiting_connection
    PendingLine.Sending -> R.string.pf_sending
    PendingLine.Retrying, is PendingLine.Uploading -> R.string.pf_retrying
    is PendingLine.Held -> if (reason == SendPolicy.Reason.VerificationRequired) R.string.pf_held_sign_in else R.string.pf_held_chat_back
}

@Composable
private fun TypingRow(state: ConversationState, look: ResolvedLook, animate: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalChatTheme.current
    val typing = state.typing ?: return
    val name = if (typing.isAi) aiDisplayName(look, typing.name) else typing.name ?: stringResource(R.string.pf_the_team)
    val label = stringResource(R.string.pf_typing, name)
    // A polite live region: TalkBack says "Deniz is typing" without moving focus (review CQ-L17).
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = label
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.Bottom,
    ) {
        if (look.showAvatars) {
            Avatar(name, state.team.firstOrNull { it.name == typing.name }?.avatarUrl, theme.avatarSize)
            Spacer(Modifier.width(8.dp))
        }
        TypingDots(animate)
    }
}

/** False when the person turned animations off (Settings > Accessibility > Remove animations). */
@Composable
internal fun animationsEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) >
            0f
    }
}

private const val PREVIEW_PROGRESS = 0.4f

/**
 * After the first message: says where the reply arrives, with the reply expectation, and, when the app uses
 * push and notifications are off, offers to turn them on (the app's own notification permission; audit X-05).
 */
@Composable
private fun NextRow(
    instance: PingFrontInstance,
    state: ConversationState,
    look: ResolvedLook,
    headerShowsExpectation: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalChatTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // The header already says how soon the team replies; the line repeats it only when the header is hidden.
    val expectation = if (!headerShowsExpectation) replyExpectation(look, state)?.trimEnd('.') else null
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> allowed = granted }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (expectation != null) stringResource(R.string.pf_after_first, expectation) else stringResource(R.string.pf_after_first_plain),
            style = MaterialTheme.typography.bodySmall,
            color = theme.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (instance.pushEnabled && !allowed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.compose.material3.OutlinedButton(
                onClick = { request.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.pf_turn_on_notifications)) }
        }
    }
}

private fun notificationsAllowed(context: android.content.Context): Boolean = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
    android.content.pm.PackageManager.PERMISSION_GRANTED
