package dev.pingfront

import dev.pingfront.model.IdentityProblem
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.PersonSummary

/** Events from `PingFront.events`. */
public sealed interface PingFrontEvent {
    /** The chat became visible. */
    public data object ChatOpened : PingFrontEvent

    /** The chat was closed. */
    public data object ChatClosed : PingFrontEvent

    /** A team or assistant message arrived. */
    @ConsistentCopyVisibility
    public data class MessageReceived internal constructor(
        /** A summary safe to show in an in-app banner. */
        public val message: ReceivedMessage,
    ) : PingFrontEvent

    /** The server accepted a message the person wrote (after any queueing and photo uploads). */
    @ConsistentCopyVisibility
    public data class MessageSent internal constructor(
        /** A summary without the text. */
        public val message: SentMessage,
    ) : PingFrontEvent

    /** The person tapped a card (offer, review, link, report, store action) or answered one (choices, rating). */
    @ConsistentCopyVisibility
    public data class CardOpened internal constructor(
        /** The card. */
        public val card: OpenedCard,
    ) : PingFrontEvent

    /** The unread count changed. */
    @ConsistentCopyVisibility
    public data class UnreadChanged internal constructor(
        /** The new count. */
        public val count: Int,
    ) : PingFrontEvent

    /** A session started (lazily, on first use). */
    @ConsistentCopyVisibility
    public data class SessionStarted internal constructor(
        /** The person. */
        public val person: PersonSummary,
    ) : PingFrontEvent

    /** The server reported a problem with the identity token. */
    @ConsistentCopyVisibility
    public data class IdentityProblemReported internal constructor(
        /** The problem. */
        public val problem: IdentityProblem,
    ) : PingFrontEvent

    /** The person tapped an offer card. */
    @ConsistentCopyVisibility
    public data class OfferRequested internal constructor(
        /** The request. */
        public val request: OfferRequest,
    ) : PingFrontEvent

    /** An offer module finished handling an offer card. */
    @ConsistentCopyVisibility
    public data class OfferCompleted internal constructor(
        /** The request. */
        public val request: OfferRequest,
        /** What happened. */
        public val outcome: OfferOutcome,
    ) : PingFrontEvent

    /** The chat look changed (published or draft). */
    @ConsistentCopyVisibility
    public data class LookChanged internal constructor(
        /** The remote revision. */
        public val revision: Int,
        /** True for a draft (test devices). */
        public val draft: Boolean,
    ) : PingFrontEvent

    /** A configuration problem was found. */
    @ConsistentCopyVisibility
    public data class ConfigurationProblemFound internal constructor(
        /** The problem. */
        public val problem: ConfigurationProblem,
    ) : PingFrontEvent

    /** A bug report was sent. */
    public data object ReportSent : PingFrontEvent

    /**
     * The server revoked this install (for example a logout or erase on another device): the SDK cleared its local
     * state, and the next request (the next chat open) starts a new guest. Messages still queued to send (offline,
     * held, failed) are wiped with it and never sent under the new guest.
     */
    public data object SessionRevoked : PingFrontEvent
}

/** A summary of a received message, safe to show in an in-app banner. */
@ConsistentCopyVisibility
public data class ReceivedMessage internal constructor(
    /** Message id. */
    public val id: String,
    /** Author display name. */
    public val authorName: String?,
    /** The text, truncated to 140 characters. */
    public val preview: String,
    /** True when the team reached out before the person wrote. */
    public val isPing: Boolean,
    /** True when written by the assistant. */
    public val isAi: Boolean,
)

/** A summary of a message the person sent, for the host's analytics. It carries no message text. */
@ConsistentCopyVisibility
public data class SentMessage internal constructor(
    /** Message id. */
    public val id: String,
    /** Characters of text (0 for a photo-only message). */
    public val textLength: Int,
    /** Photos attached. */
    public val photoCount: Int,
    /** True when the latest team message before it is a ping (the person is answering a ping). */
    public val repliesToPing: Boolean,
)

/** A card the person tapped or answered. */
@ConsistentCopyVisibility
public data class OpenedCard internal constructor(
    /** What kind of card. */
    public val kind: CardKind,
    /** The card id. */
    public val cardId: String,
    /** The message the card is in. */
    public val messageId: String,
)

/** A card kind, as on the wire (`offer`, `review`, …). Open: kinds added later keep their wire name. */
@JvmInline
public value class CardKind(
    /** The wire name. */
    public val name: String,
) {
    override fun toString(): String = name

    /** Known kinds. */
    public companion object {
        /** An offer card. */
        public val Offer: CardKind = CardKind("offer")

        /** A review ask. */
        public val Review: CardKind = CardKind("review")

        /** A link or deep link. */
        public val Link: CardKind = CardKind("link")

        /** Quick choices. */
        public val Choices: CardKind = CardKind("choices")

        /** A rating (CSAT). */
        public val Rating: CardKind = CardKind("rating")

        /** Report a problem. */
        public val Report: CardKind = CardKind("report")

        /** One of the store's own subscription screens. */
        public val StoreAction: CardKind = CardKind("store_action")

        /** The kind of [card]. */
        public fun of(card: dev.pingfront.model.Card): CardKind = when (card) {
            is dev.pingfront.model.Card.Offer -> Offer
            is dev.pingfront.model.Card.Review -> Review
            is dev.pingfront.model.Card.Link -> Link
            is dev.pingfront.model.Card.Choices -> Choices
            is dev.pingfront.model.Card.Rating -> Rating
            is dev.pingfront.model.Card.Report -> Report
            is dev.pingfront.model.Card.StoreAction -> StoreAction
            is dev.pingfront.model.Card.Unknown -> CardKind(card.kind)
        }
    }
}

/** The outcome of `PingFront.moment`. */
public sealed interface MomentResult {
    /** No play listens to the moment, so nothing was sent. */
    public data object NotListened : MomentResult

    /** Sent; no play reacted. */
    public data object Ignored : MomentResult

    /** A play scheduled a ping. */
    public data object Scheduled : MomentResult

    /** A ping was delivered now. */
    public data object Delivered : MomentResult

    /** The person is in a holdout group for this play. */
    public data object HeldOut : MomentResult

    /** The server asked to open the chat; [presented] is `false` when `presentsChatForMoments` is off. */
    @ConsistentCopyVisibility
    public data class OpenRequested internal constructor(
        /** True when the SDK presented the chat. */
        public val presented: Boolean,
    ) : MomentResult

    /** The name is invalid (`[a-z0-9][a-z0-9_.-]{0,63}`). */
    public data object InvalidName : MomentResult

    /** Sending failed. */
    @ConsistentCopyVisibility
    public data class Failed internal constructor(
        /** Why. */
        public val error: PingFrontException,
    ) : MomentResult
}

/** An offer card the person tapped. */
@ConsistentCopyVisibility
public data class OfferRequest internal constructor(
    /** The `OfferSlot.id` from the card. */
    public val offerId: String,
    /** The matching slot from `setOffers` or an offer source, if any. */
    public val slot: OfferSlot?,
    /** The card id. */
    public val cardId: String,
    /** The message id. */
    public val messageId: String,
    /** The store offer the card names (for example a Play subscription offer), so the host can buy it too. */
    public val storeOffer: dev.pingfront.model.StoreOffer? = null,
)

/** What happened when an offer was shown. */
public sealed interface OfferOutcome {
    /** The person bought it. */
    public data object Purchased : OfferOutcome

    /** The purchase waits for approval or payment (for example a family member's approval). */
    public data object Pending : OfferOutcome

    /** The person closed the offer. */
    public data object Cancelled : OfferOutcome

    /** The offer is not available. */
    public data object Unavailable : OfferOutcome

    /** Something failed. */
    public class Failed(
        /** Why. */
        public val failure: OfferFailure,
    ) : OfferOutcome {
        /** A copy with the given fields replaced. */
        public fun copy(failure: OfferFailure = this.failure): Failed = Failed(failure)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Failed) return false
            return failure == other.failure
        }

        override fun hashCode(): Int = java.util.Objects.hash(failure)

        override fun toString(): String = "Failed(failure=$failure)"
    }
}

/** Why an offer failed. Open: store modules add their own codes. */
@JvmInline
public value class OfferFailure(
    /** A stable code, for example `billing_unavailable`. */
    public val code: String,
) {
    override fun toString(): String = code

    /** Known failures. */
    public companion object {
        /** The store is not available on this device. */
        public val BillingUnavailable: OfferFailure = OfferFailure("billing_unavailable")

        /** No product matches the offer. */
        public val ProductNotFound: OfferFailure = OfferFailure("product_not_found")

        /** There is no activity to show the purchase flow from. */
        public val NoActivity: OfferFailure = OfferFailure("no_activity")

        /** The store reported an error. */
        public val StoreError: OfferFailure = OfferFailure("store_error")

        /** A purchase sheet is already open (a second tap while the first purchase runs). */
        public val InProgress: OfferFailure = OfferFailure("in_progress")
    }
}

/**
 * A store event detected on the device (`StoreMomentProps`), reported by the billing module or an adapter.
 * Fields per store (see the protocol): the App Store sends [transactionId], [originalTransactionId] and
 * [signedTransaction]; Google Play sends [purchaseToken] and [orderId]. A Play purchase token never goes in
 * [originalTransactionId].
 */
@kotlinx.serialization.Serializable
public class StoreMomentProps(
    /** The store product id. */
    public val productId: String,
    /** App Store only: the transaction id. */
    public val transactionId: String? = null,
    /** App Store only: the original transaction id. */
    public val originalTransactionId: String? = null,
    /** Decimal price in [currency], for example "4.99". */
    public val price: String? = null,
    /** ISO 4217 currency code. */
    public val currency: String? = null,
    /** App Store only: a verified signed transaction. */
    public val signedTransaction: String? = null,
    /** Google Play only: the purchase token (up to 1024 characters). */
    public val purchaseToken: String? = null,
    /** Google Play only: the order id (`GPA.…`), the key that dedupes against provider events. */
    public val orderId: String? = null,
) {
    /** A copy with the given fields replaced. */
    public fun copy(
        productId: String = this.productId,
        transactionId: String? = this.transactionId,
        originalTransactionId: String? = this.originalTransactionId,
        price: String? = this.price,
        currency: String? = this.currency,
        signedTransaction: String? = this.signedTransaction,
        purchaseToken: String? = this.purchaseToken,
        orderId: String? = this.orderId,
    ): StoreMomentProps = StoreMomentProps(productId, transactionId, originalTransactionId, price, currency, signedTransaction, purchaseToken, orderId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoreMomentProps) return false
        return productId == other.productId &&
            transactionId == other.transactionId &&
            originalTransactionId == other.originalTransactionId &&
            price == other.price &&
            currency == other.currency &&
            signedTransaction == other.signedTransaction &&
            purchaseToken == other.purchaseToken &&
            orderId == other.orderId
    }

    override fun hashCode(): Int =
        java.util.Objects.hash(productId, transactionId, originalTransactionId, price, currency, signedTransaction, purchaseToken, orderId)

    override fun toString(): String =
        "StoreMomentProps(productId=$productId, transactionId=$transactionId, originalTransactionId=$originalTransactionId, price=$price, currency=$currency, signedTransaction=$signedTransaction, purchaseToken=$purchaseToken, orderId=$orderId)"
}
