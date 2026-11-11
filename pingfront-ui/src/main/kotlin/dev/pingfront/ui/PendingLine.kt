package dev.pingfront.ui

import dev.pingfront.conversation.ConversationState
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.model.SendPolicy

/**
 * The line under a message that has not been delivered yet, and is not a failure that needs the person ("Not
 * delivered", always under its own message). The same rules as iOS's `PendingLine`.
 */
internal sealed interface PendingLine {
    /** The device has no network: it goes when the connection is back. */
    data object Waiting : PendingLine

    /** On the wire right now, or not tried yet. */
    data object Sending : PendingLine

    /** Photos on the wire: "Uploading 2 photos", with a progress bar. */
    data class Uploading(val photos: Int) : PendingLine

    /** After an attempt the outbox will retry (a rate limit, a backoff, the service out of reach). */
    data object Retrying : PendingLine

    /**
     * Sending is closed for [reason] (the app paused, verification required): nothing retries until it reopens, and
     * then the message goes by itself. Never for a blocked person, who is promised nothing.
     */
    data class Held(val reason: SendPolicy.Reason) : PendingLine

    companion object {
        /**
         * What an undelivered message waits for: its own hold, a closed policy that holds messages, or a held message
         * ahead of it (the queue goes in order). `null` when it waits for nothing of the kind.
         */
        fun heldFor(pending: PendingMessage, state: ConversationState): SendPolicy.Reason? {
            pending.heldFor?.let { return it }
            if (pending.state == PendingMessage.State.Failed) return null
            (state.policy as? SendPolicy.Closed)?.reason?.takeIf { it.holdsMessages }?.let { return it }
            return state.pending.takeWhile { it.clientId != pending.clientId }.lastOrNull { it.isHeld }?.heldFor
        }

        /** The line for one undelivered message; `null` for a failure that needs the person. */
        fun of(pending: PendingMessage, state: ConversationState): PendingLine? {
            if (pending.state == PendingMessage.State.Failed && !pending.isHeld) return null
            heldFor(pending, state)?.let { return Held(it) }
            val unreachable = state.isServerUnreachable
            return when {
                state.isOffline && !unreachable -> Waiting
                unreachable -> Retrying
                pending.state == PendingMessage.State.Sending && pending.photos.isNotEmpty() -> Uploading(pending.photos.size)
                pending.state == PendingMessage.State.Sending || pending.attempts == 0 -> Sending
                else -> Retrying
            }
        }

        /**
         * The line each pending message shows, by client id. Only the last of a run of consecutive messages with the
         * same line shows it, as Google Messages shows a status once, under the last message it applies to; a failure
         * that needs the person ends a run. An upload's line always shows, under its own photos.
         */
        fun shown(state: ConversationState): Map<String, PendingLine> {
            val lines = state.pending.map { of(it, state) }
            val shown = HashMap<String, PendingLine>()
            lines.forEachIndexed { index, line ->
                if (line == null) return@forEachIndexed
                if (line is Uploading || index == lines.lastIndex || lines[index + 1] != line) shown[state.pending[index].clientId] = line
            }
            return shown
        }

        /** Held, or queued behind a held message, and not on the wire: Delete is in its long-press menu. */
        fun deletable(pending: PendingMessage, state: ConversationState): Boolean =
            pending.state != PendingMessage.State.Sending && heldFor(pending, state) != null
    }
}
