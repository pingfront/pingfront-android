package dev.pingfront.internal

import dev.pingfront.conversation.Conversation
import dev.pingfront.internal.messaging.Outbox
import dev.pingfront.model.SendPolicy

/**
 * When the messages a closed policy held go, matching iOS (`Engine.signedIn`, `unreadAnswered`, `reopened`,
 * `policyClosed`). Only a pause or a required sign-in holds a message ([SendPolicy.Reason.holdsMessages]); the outbox
 * keeps it, held, across relaunches, and every answer below lets it go by itself, with the chat open or closed.
 */
internal class HeldMessages(
    private val outbox: Outbox,
    private val conversation: () -> Conversation,
    private val wake: () -> Unit,
) {
    /**
     * A `PATCH /me` left the person verified (a signed identity was accepted): messages held because the app wants a
     * sign-in go. Refused again, a message is simply held again.
     */
    suspend fun signedIn() {
        // The composer the sign-in closed opens again; a pause's hold still waits for its own answer.
        conversation().update {
            if ((it.policy as? SendPolicy.Closed)?.reason == SendPolicy.Reason.VerificationRequired) it.copy(policy = SendPolicy.Open) else it
        }
        if (outbox.retryHeld(SendPolicy.Reason.VerificationRequired)) wake()
    }

    /**
     * `GET /unread` answered: a paused app refuses it, so the app is not paused (any other reason is the thread's to
     * say, on its next load). Messages a pause held go, also those held before a relaunch; messages held for a sign-in
     * wait for a load that allows sending.
     */
    suspend fun unreadAnswered() {
        if (outbox.retryHeld(SendPolicy.Reason.AppPaused)) wake()
    }

    /** A thread load answered with [policy]. */
    suspend fun threadAnswered(policy: SendPolicy) = when (policy) {
        is SendPolicy.Closed -> policyClosed(policy.reason)
        // Every load that allows sending, not only one that reopened it in this process: a message held before a
        // relaunch is persisted held and goes now, first, in order.
        SendPolicy.Open -> {
            outbox.retryHeld()
            wake()
        }
    }

    /** A blocked person is promised nothing: messages a pause or a required sign-in held stop waiting. */
    suspend fun policyClosed(reason: SendPolicy.Reason) {
        if (reason == SendPolicy.Reason.Blocked) outbox.releaseHeld()
    }
}
