package dev.pingfront.look

import dev.pingfront.model.LocalizedText
import dev.pingfront.model.TopicSymbol

/**
 * A topic chip on the welcome screen. Use a preset ([Billing], [Bug], [Idea], …) or the constructor.
 * With a [prefill], tapping the chip fills the composer; without one it starts the thread on that topic.
 */
public class ChatTopic(
    /** Stable id (`[a-z0-9_-]{1,32}`). */
    public val id: String,
    /** The label. `null` uses the SDK's built-in, translated label for the preset id. */
    public val label: LocalizedText?,
    /** The chip symbol. */
    public val symbol: TopicSymbol = TopicSymbol.Other,
    /** Text put into the composer when tapped. */
    public val prefill: LocalizedText? = null,
) {
    /** A copy with the given fields replaced. */
    public fun copy(
        id: String = this.id,
        label: LocalizedText? = this.label,
        symbol: TopicSymbol = this.symbol,
        prefill: LocalizedText? = this.prefill,
    ): ChatTopic = ChatTopic(id, label, symbol, prefill)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatTopic) return false
        return id == other.id &&
            label == other.label &&
            symbol == other.symbol &&
            prefill == other.prefill
    }

    override fun hashCode(): Int = java.util.Objects.hash(id, label, symbol, prefill)

    override fun toString(): String = "ChatTopic(id=$id, label=$label, symbol=$symbol, prefill=$prefill)"

    /** A custom topic with a single-language label (localize it yourself with string resources). */
    public constructor(id: String, label: String, symbol: TopicSymbol = TopicSymbol.Other, prefill: String? = null) :
        this(id, LocalizedText.of(label), symbol, prefill?.let { LocalizedText.of(it) })

    /** Presets. */
    public companion object {
        /** "Billing". */
        public val Billing: ChatTopic = ChatTopic("billing", null as LocalizedText?, TopicSymbol.Billing)

        /** "Report a bug". */
        public val Bug: ChatTopic = ChatTopic("bug", null as LocalizedText?, TopicSymbol.Bug)

        /** "Ideas". */
        public val Idea: ChatTopic = ChatTopic("idea", null as LocalizedText?, TopicSymbol.Idea)

        /** "A question". */
        public val Question: ChatTopic = ChatTopic("question", null as LocalizedText?, TopicSymbol.Question)

        /** "My account". */
        public val Account: ChatTopic = ChatTopic("account", null as LocalizedText?, TopicSymbol.Account)

        /** "Feedback". */
        public val Feedback: ChatTopic = ChatTopic("feedback", null as LocalizedText?, TopicSymbol.Feedback)

        /** "Restore purchases". */
        public val Restore: ChatTopic = ChatTopic("restore", null as LocalizedText?, TopicSymbol.Restore)

        /** "A gift". */
        public val Gift: ChatTopic = ChatTopic("gift", null as LocalizedText?, TopicSymbol.Gift)

        /** "Privacy". */
        public val Privacy: ChatTopic = ChatTopic("privacy", null as LocalizedText?, TopicSymbol.Privacy)

        /** The default topics: Billing, Report a bug, Ideas. */
        public val Defaults: List<ChatTopic> = listOf(Billing, Bug, Idea)
    }
}
