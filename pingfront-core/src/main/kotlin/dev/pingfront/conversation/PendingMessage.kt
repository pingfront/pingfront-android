@file:UseSerializers(InstantSerializer::class)

package dev.pingfront.conversation

import dev.pingfront.internal.wire.InstantSerializer
import dev.pingfront.model.SendPolicy
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** A message the person wrote that the server has not acknowledged yet. */
@Serializable
@ConsistentCopyVisibility
public data class PendingMessage internal constructor(
    /** The idempotency key. A retry reuses it, so the server never stores a message twice. */
    public val clientId: String,
    /** Text, if any. */
    public val text: String? = null,
    /** Photos, if any. */
    public val photos: List<Photo> = emptyList(),
    /** When the person tapped Send. */
    public val createdAt: Instant,
    /** Delivery attempts so far. */
    public val attempts: Int = 0,
    /** Current state. */
    public val state: State = State.Queued,
    /** Why it failed, in plain words. */
    public val failure: String? = null,
    /** The wire code of [heldFor], persisted with the queue so a hold survives a relaunch. */
    @SerialName("heldFor") internal val heldForCode: String? = null,
) {
    /**
     * Why a closed policy refused it, when that refusal holds it: [SendPolicy.Reason.AppPaused] or
     * [SendPolicy.Reason.VerificationRequired]. A held message is [State.Failed] but needs nothing from the person: it
     * goes again by itself, first, when sending reopens, and the messages behind it wait for it. A blocked person's
     * message is never held. `null` for every other message.
     */
    public val heldFor: SendPolicy.Reason? get() = heldForCode?.let(SendPolicy::Reason)

    /** True when a closed policy holds it (see [heldFor]). */
    public val isHeld: Boolean get() = heldForCode != null

    /** Delivery state. */
    public enum class State {
        /** Waiting to be sent (also while offline or rate limited). */
        Queued,

        /** Being uploaded or sent right now. */
        Sending,

        /** The server rejected it; the person can retry or discard. */
        Failed,
    }

    /** A photo staged for upload. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Photo internal constructor(
        /** Record name in the encrypted outbox store. */
        internal val file: String,
        /** Pixel width. */
        public val width: Int,
        /** Pixel height. */
        public val height: Int,
        /** Byte count. */
        public val bytes: Int,
        /** Hex SHA-256 of the JPEG. */
        public val sha256: String,
        /** Set once uploaded, so a retry never uploads twice. */
        public val attachmentId: String? = null,
    )
}
