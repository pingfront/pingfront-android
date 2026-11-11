package dev.pingfront.conversation

import dev.pingfront.InternalPingFrontApi
import dev.pingfront.RateScope
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.model.Author
import dev.pingfront.model.Card
import dev.pingfront.model.ChoiceOption
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.model.SendPolicy
import dev.pingfront.model.TeamMember
import dev.pingfront.model.ThreadStatus
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Who is typing. */
@ConsistentCopyVisibility
public data class TypingIndicator internal constructor(
    /** The teammate or assistant name. */
    public val name: String?,
    /** True when the assistant is typing. */
    public val isAi: Boolean,
)

/** Loading state of the thread. */
public sealed interface ConversationPhase {
    /** Nothing loaded yet. */
    public data object Idle : ConversationPhase

    /** Loading the latest page. */
    public data object Loading : ConversationPhase

    /** Loaded (possibly from cache while offline). */
    public data object Ready : ConversationPhase

    /** Loading failed and nothing is cached. */
    @ConsistentCopyVisibility
    public data class Failed internal constructor(
        /** True when the device looked offline. */
        public val offline: Boolean,
    ) : ConversationPhase
}

/** One immutable snapshot of the thread. Collect [Conversation.state]. */
@ConsistentCopyVisibility
public data class ConversationState internal constructor(
    /** Loading state. */
    public val phase: ConversationPhase = ConversationPhase.Idle,
    /** Messages in sequence order. */
    public val messages: List<Message> = emptyList(),
    /** Messages the person sent that the server has not acknowledged. */
    public val pending: List<PendingMessage> = emptyList(),
    /** True when older messages can be loaded. */
    public val hasOlder: Boolean = false,
    /** True while older messages are loading. */
    public val isLoadingOlder: Boolean = false,
    /** The team, for the header and welcome. */
    public val team: List<TeamMember> = emptyList(),
    /** The server's reply expectation, already localized. */
    public val replyExpectation: String? = null,
    /** Whether the person can send. */
    public val policy: SendPolicy = SendPolicy.Open,
    /** Open or resolved. */
    public val status: ThreadStatus = ThreadStatus.Open,
    /** Who is typing right now. */
    public val typing: TypingIndicator? = null,
    /** The live channel state. */
    public val live: LiveState = LiveState.Stopped,
    /** Active rate-limit cooldowns: scope to monotonic deadline (read with [Conversation.cooldownRemaining]). */
    internal val cooldownDeadlines: Map<RateScope, Duration> = emptyMap(),
    /** The highest sequence number the team has read. */
    public val teamReadSeq: Int = 0,
    /** The device has no network, or the last request could not reach it (never the socket alone). */
    internal val networkOffline: Boolean = false,
    /** Local receipt lines under store action cards, by card id (shown on this device only, never sent). */
    public val storeReceipts: Map<String, StoreReceipt> = emptyMap(),
    /**
     * Of [networkOffline]: the system reports a network, but the last request could not reach the server. Last, so
     * the public properties keep their `componentN` numbers.
     */
    internal val serverUnreachable: Boolean = false,
) {
    /** The rows the UI renders: messages and pending sends, grouped. */
    public val timeline: List<TimelineItem> by lazy { Timeline.build(messages, pending) }

    /** True when there are no messages and nothing is pending (show the welcome). */
    public val isEmpty: Boolean get() = messages.isEmpty() && pending.isEmpty()

    /** Scopes paused by a rate limit (time left: [Conversation.cooldownRemaining]). */
    public val coolingDown: Set<RateScope> get() = cooldownDeadlines.keys

    /**
     * True when the device is offline: the system reports no usable network, or the last request failed
     * to reach the server. A live socket that is reconnecting on its own is not "offline" (audit A-03).
     */
    public val isOffline: Boolean get() = networkOffline

    /**
     * True when the system reports a network but the last request could not reach the server: its name did not
     * resolve, or the connection was refused or had no route. Part of [isOffline]; the chat says it can't reach the
     * chat instead of "offline". Cleared by the next request that gets through.
     */
    public val isServerUnreachable: Boolean get() = networkOffline && serverUnreachable
}

/** The actions a conversation forwards to the engine. */
internal interface ConversationDriver {
    fun send(text: String?, photos: List<ByteArray>, replyTo: dev.pingfront.internal.wire.CardReply?)

    fun retry(clientId: String)

    fun discard(clientId: String)

    fun loadOlder()

    fun act(card: Card, messageId: String, choiceId: String?, rating: Int?)

    fun setTyping(on: Boolean)

    suspend fun reload()

    fun markRead()

    fun draftChanged(text: String)

    suspend fun photoData(photo: PendingMessage.Photo): ByteArray?

    suspend fun imageUrl(image: MessagePart.Image): String?
}

/**
 * The thread as observable state, plus the actions a chat UI needs. The built-in Compose chat renders
 * it; teams building their own UI use `PingFront.conversation` directly.
 */
public class Conversation internal constructor() {
    private val _state = MutableStateFlow(ConversationState())
    private val _draft = MutableStateFlow("")
    private val _insertion = MutableStateFlow<String?>(null)

    @Volatile internal var driver: ConversationDriver? = null

    @Volatile internal var time: TimeSource = TimeSource.System

    /** The thread. */
    public val state: StateFlow<ConversationState> = _state

    /** The person's unsent text. Persisted (encrypted) between launches. */
    public val draft: StateFlow<String> = _draft

    /** Text to put into the composer once (a topic prefill, `openChat(topic)`). Call [consumeInsertion] after using it. */
    public val composerInsertion: StateFlow<String?> = _insertion

    /** Updates the draft (from the composer). */
    public fun updateDraft(text: String) {
        if (_draft.value == text) return
        _draft.value = text
        driver?.draftChanged(text)
    }

    /** Clears [composerInsertion] once the composer used it. */
    public fun consumeInsertion() {
        _insertion.value = null
    }

    /** Time left on a cooldown, or `null`. */
    public fun cooldownRemaining(scope: RateScope): Duration? {
        val deadline = _state.value.cooldownDeadlines[scope] ?: return null
        val left = deadline - time.now()
        return left.takeIf { it > Duration.ZERO }
    }

    /**
     * Sends text and photos (any image bytes; re-encoded as JPEG ≤ 2 MB, ≤ 2400 px). Returns at once;
     * the message shows as pending and is delivered by the outbox, also after a restart.
     */
    public fun send(text: String, photos: List<ByteArray> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && photos.isEmpty()) return
        driver?.send(trimmed.ifEmpty { null }, photos, null)
    }

    /** Sends a failed message again with the same idempotency key (the chat's Try again). */
    public fun retry(message: PendingMessage) {
        driver?.retry(message.clientId)
    }

    /**
     * Removes a message from the queue unsent: a failed one, one a closed policy holds ([PendingMessage.heldFor]), or
     * one queued behind it. Deleting the first held message lets the ones behind it go.
     */
    public fun discard(message: PendingMessage) {
        driver?.discard(message.clientId)
    }

    /** Loads the previous page (call when the top of the list appears). */
    public fun loadOlder() {
        val current = _state.value
        if (!current.hasOlder || current.isLoadingOlder) return
        driver?.loadOlder()
    }

    /** Picks an option on a choices card. */
    public fun choose(option: ChoiceOption, card: Card.Choices, message: Message) {
        driver?.act(card, message.id, option.id, null)
    }

    /** Rates on a rating card (1–5). */
    public fun rate(rating: Int, card: Card.Rating, message: Message) {
        driver?.act(card, message.id, null, rating.coerceIn(1, MAX_RATING))
    }

    /** Opens a card's action (offer, review, link, report). */
    public fun open(card: Card, message: Message) {
        driver?.act(card, message.id, null, null)
    }

    /** Tells the team the person is typing (throttled to one event per 3 s). */
    public fun setTyping(on: Boolean) {
        driver?.setTyping(on)
    }

    /**
     * Reloads the thread, and returns when the reload finished: the thread is loaded, or the load failed. On failure the
     * phase is [ConversationPhase.Failed] only when there is nothing to show; a thread already on screen stays.
     * Cancelling the caller does not cancel the reload.
     */
    public suspend fun reload() {
        driver?.reload()
    }

    /**
     * For a custom chat UI: marks the messages on screen as read (`POST /thread/read` up to the last message's seq) and
     * drops the unread count to 0. Does nothing when there is nothing to mark, or while the app is paused. The built-in
     * chat does this by itself.
     */
    public fun markRead() {
        driver?.markRead()
    }

    /** The JPEG bytes of a photo that is still waiting to be sent. */
    public suspend fun photoData(photo: PendingMessage.Photo): ByteArray? = driver?.photoData(photo)

    /** A fresh URL for an image whose short-lived URL expired. */
    public suspend fun refreshedUrl(image: MessagePart.Image): String? = driver?.imageUrl(image) ?: image.url

    // State updates (engine and previews).

    /** Updates the state atomically (the engine and previews). */
    @InternalPingFrontApi
    public fun update(transform: (ConversationState) -> ConversationState) {
        _state.update(transform)
    }

    /** Puts text into the composer once. */
    @InternalPingFrontApi
    public fun insert(text: String?) {
        _insertion.value = text
    }

    internal fun setDraftSilently(text: String) {
        _draft.value = text
    }

    /** Inserts or replaces messages by id, keeps seq order and drops pending rows they acknowledge. Never moves `lastSeq`. */
    internal fun merge(incoming: List<Message>) {
        if (incoming.isEmpty()) return
        update { current ->
            val byId = LinkedHashMap<String, Message>()
            current.messages.forEach { byId[it.id] = it }
            incoming.forEach { byId[it.id] = it }
            val acknowledged = incoming.mapNotNull { it.clientId }.toSet()
            current.copy(
                messages = byId.values.sortedWith(compareBy<Message> { it.seq }.thenBy { it.createdAt }),
                pending = if (acknowledged.isEmpty()) current.pending else current.pending.filterNot { it.clientId in acknowledged },
            )
        }
    }

    /** Live `reset` and `thread_reset`: drops the messages but keeps the draft and pending rows. */
    internal fun dropMessages() {
        update {
            it.copy(
                messages = emptyList(),
                hasOlder = false,
                isLoadingOlder = false,
                typing = null,
                teamReadSeq = 0,
                phase = ConversationPhase.Loading,
            )
        }
    }

    /** Clears everything (logout, erase). */
    internal fun clear() {
        _state.value = ConversationState()
        _draft.value = ""
        _insertion.value = null
    }
}

/** One row of the timeline with its grouping. */
@ConsistentCopyVisibility
public data class TimelineItem internal constructor(
    /** Stable id (message id or clientId). */
    public val id: String,
    /** The content. */
    public val content: Content,
    /** Which side of the thread the row sits on. */
    public val side: Side,
    /** First row of a group from the same author (show the author name). */
    public val startsGroup: Boolean,
    /** Last row of a group (show the avatar and hand-off line). */
    public val endsGroup: Boolean,
    /** First row of a run of pings (show the ping line). */
    public val startsPing: Boolean,
    /** Written by the assistant (always labelled). */
    public val isAi: Boolean,
    /** The author's display name. */
    public val authorName: String?,
    /** When it was written. */
    public val date: Instant,
    /** First row after a pause of more than 5 minutes (or the first row): show a centered time header. */
    public val startsTimeSection: Boolean,
) {
    /** Which side of the thread a row sits on. */
    public enum class Side {
        /** Start side: team and assistant. */
        Team,

        /** End side: the person. */
        Person,

        /** Centered: receipts and system lines. */
        System,
    }

    /** What a row shows. */
    public sealed interface Content {
        /** A delivered message. */
        @ConsistentCopyVisibility
        public data class Delivered internal constructor(
            /** The message. */
            public val message: Message,
        ) : Content

        /** A message waiting for the server. */
        @ConsistentCopyVisibility
        public data class Pending internal constructor(
            /** The pending message. */
            public val message: PendingMessage,
        ) : Content
    }
}

internal object Timeline {
    /** Rows from the same author within this window form a group. */
    val groupWindow: Duration = 5.minutes

    fun build(messages: List<Message>, pending: List<PendingMessage>): List<TimelineItem> {
        val items = ArrayList<TimelineItem>(messages.size + pending.size)
        for (message in messages) {
            val side = when (message.author.kind) {
                Author.Kind.Person -> TimelineItem.Side.Person
                Author.Kind.System -> TimelineItem.Side.System
                else -> if (message.parts.isNotEmpty() &&
                    message.parts.all { it is MessagePart.Receipt }
                ) {
                    TimelineItem.Side.System
                } else {
                    TimelineItem.Side.Team
                }
            }
            items += TimelineItem(
                message.id, TimelineItem.Content.Delivered(message), side, startsGroup = true, endsGroup = true, startsPing = false,
                isAi = message.author.kind == Author.Kind.Ai, authorName = message.author.name, date = message.createdAt, startsTimeSection = false,
            )
        }
        for (message in pending) {
            items += TimelineItem(
                message.clientId, TimelineItem.Content.Pending(message), TimelineItem.Side.Person, startsGroup = true, endsGroup = true,
                startsPing = false, isAi = false, authorName = null, date = message.createdAt, startsTimeSection = false,
            )
        }
        return items.mapIndexed { index, item ->
            val previous = items.getOrNull(index - 1)
            val next = items.getOrNull(index + 1)
            val current = (item.content as? TimelineItem.Content.Delivered)?.message
            val startsPing = current?.ping?.let { ping ->
                val prior = (previous?.content as? TimelineItem.Content.Delivered)?.message
                prior?.ping == null || prior.author != current.author || prior.ping.playId != ping.playId
            } ?: false
            item.copy(
                startsTimeSection = previous?.let { gap(it.date, item.date) > groupWindow } ?: true,
                startsGroup = !(previous?.let { sameGroup(it, item) } ?: false),
                endsGroup = !(next?.let { sameGroup(item, it) } ?: false),
                startsPing = startsPing,
            )
        }
    }

    private fun gap(a: Instant, b: Instant): Duration = kotlin.time.Duration.parse("${b.toEpochMilli() - a.toEpochMilli()}ms")

    private fun authorKey(item: TimelineItem): String = when (val content = item.content) {
        is TimelineItem.Content.Delivered -> "${content.message.author.kind}:${content.message.author.id ?: content.message.author.name ?: ""}"
        is TimelineItem.Content.Pending -> "${Author.Kind.Person}:"
    }

    private fun sameGroup(a: TimelineItem, b: TimelineItem): Boolean {
        if (a.side != b.side || a.side == TimelineItem.Side.System) return false
        val aKey = authorKey(a)
        val bKey = authorKey(b)
        // Pending rows join the person's group whatever id the delivered rows carry.
        val sameAuthor = aKey == bKey || (a.side == TimelineItem.Side.Person && b.side == TimelineItem.Side.Person)
        if (!sameAuthor) return false
        // A new ping run always starts a new group so its line sits above it.
        val bPing = (b.content as? TimelineItem.Content.Delivered)?.message?.ping
        if (bPing != null) {
            val aPing = (a.content as? TimelineItem.Content.Delivered)?.message?.ping
            if (aPing == null || aPing.playId != bPing.playId) return false
        }
        val gap = gap(a.date, b.date)
        return gap >= Duration.ZERO && gap <= groupWindow
    }
}

private const val MAX_RATING = 5
