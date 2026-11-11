@file:UseSerializers(InstantSerializer::class)

package dev.pingfront.model

import dev.pingfront.internal.wire.InstantSerializer
import dev.pingfront.internal.wire.TolerantEnumSerializer
import dev.pingfront.internal.wire.UNKNOWN_WIRE
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * The person's subscription state: as the app or a store module reports it, or as the server knows it from store
 * events (`PersonSummary.entitlement`, the protocol, "Plan status").
 */
@Serializable
public class Entitlement(
    /** Current state. */
    public val state: State,
    /** The product that grants it. */
    public val productId: String? = null,
    /** The next renewal while it auto-renews (trial, active). */
    public val renewsAt: Instant? = null,
    /** Which system reported it. */
    public val source: Source? = Source.App,
    /** The end of the current period: when access ends (cancelled) or ended (expired). */
    public val expiresAt: Instant? = null,
    /** For grace and billing retry: the day access ends unless the payment is fixed. */
    public val gracePeriodEndsAt: Instant? = null,
    /**
     * The store environment of the event that set this plan: `production` for a real purchase; anything else
     * (`sandbox`, `xcode`, a Play license tester) is a test purchase. `null` when the store did not say.
     */
    public val environment: String? = null,
) {
    /** A copy with the given fields replaced. */
    public fun copy(
        state: State = this.state,
        productId: String? = this.productId,
        renewsAt: Instant? = this.renewsAt,
        source: Source? = this.source,
        expiresAt: Instant? = this.expiresAt,
        gracePeriodEndsAt: Instant? = this.gracePeriodEndsAt,
        environment: String? = this.environment,
    ): Entitlement = Entitlement(state, productId, renewsAt, source, expiresAt, gracePeriodEndsAt, environment)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Entitlement) return false
        return state == other.state &&
            productId == other.productId &&
            renewsAt == other.renewsAt &&
            source == other.source &&
            expiresAt == other.expiresAt &&
            gracePeriodEndsAt == other.gracePeriodEndsAt &&
            environment == other.environment
    }

    override fun hashCode(): Int = java.util.Objects.hash(state, productId, renewsAt, source, expiresAt, gracePeriodEndsAt, environment)

    override fun toString(): String =
        "Entitlement(state=$state, productId=$productId, renewsAt=$renewsAt, source=$source, expiresAt=$expiresAt, gracePeriodEndsAt=$gracePeriodEndsAt, environment=$environment)"

    /** True when the store said this plan came from a test purchase (any environment other than `production`). */
    public val isTestPurchase: Boolean get() = environment != null && !environment.equals("production", ignoreCase = true)

    /** Subscription lifecycle states. */
    @Serializable(with = StateSerializer::class)
    public enum class State {
        None,
        Trial,
        Active,
        Grace,
        BillingRetry,
        Cancelled,
        Expired,
        Lifetime,

        /** A state this SDK version does not know (shows no plan status). */
        Unknown,
    }

    /** Where an entitlement came from. Open: adapters for other vendors pass their own code. */
    @Serializable(with = SourceSerializer::class)
    @JvmInline
    public value class Source(
        /** The wire code, for example `play`. */
        public val code: String,
    ) {
        override fun toString(): String = code

        /** Known sources. */
        public companion object {
            /** Apple StoreKit. */
            public val StoreKit: Source = Source("storekit")

            /** Google Play Billing. */
            public val Play: Source = Source("play")

            /** RevenueCat. */
            public val RevenueCat: Source = Source("revenuecat")

            /** Superwall. */
            public val Superwall: Source = Source("superwall")

            /** Adapty. */
            public val Adapty: Source = Source("adapty")

            /** Stripe. */
            public val Stripe: Source = Source("stripe")

            /** Reported by the app itself. */
            public val App: Source = Source("app")
        }
    }

    internal object StateSerializer : TolerantEnumSerializer<State>(
        "EntitlementState",
        mapOf(
            State.None to "none",
            State.Trial to "trial",
            State.Active to "active",
            State.Grace to "grace",
            State.BillingRetry to "billing_retry",
            State.Cancelled to "cancelled",
            State.Expired to "expired",
            State.Lifetime to "lifetime",
            State.Unknown to UNKNOWN_WIRE,
        ),
        State.Unknown,
    )

    internal object SourceSerializer : kotlinx.serialization.KSerializer<Source> {
        override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
            kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
                "dev.pingfront.EntitlementSource",
                kotlinx.serialization.descriptors.PrimitiveKind.STRING,
            )

        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Source) = encoder.encodeString(value.code)

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Source = Source(decoder.decodeString())
    }
}

/** An offer the app can show, reported so the team (and plays) can reference it in offer cards. */
@Serializable
public class OfferSlot(
    /** Stable id referenced by offer cards (`[A-Za-z0-9_.-]{1,64}`). */
    public val id: String,
    /** Short title, at most 60 characters. */
    public val title: String,
    /** The flavour of offer. */
    public val kind: Kind = Kind.Standard,
    /** Whether the app can show it right now. */
    public val available: Boolean = true,
    /** Products inside the offer. */
    public val products: List<Product> = emptyList(),
    /** Where the paywall is built: the vendor adapter that reported it, `play`, or `app` (the host's own code). */
    @Serializable(with = SourceSerializer::class)
    public val source: Source? = null,
) {
    /** A copy with the given fields replaced. */
    public fun copy(
        id: String = this.id,
        title: String = this.title,
        kind: Kind = this.kind,
        available: Boolean = this.available,
        products: List<Product> = this.products,
        source: Source? = this.source,
    ): OfferSlot = OfferSlot(id, title, kind, available, products, source)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfferSlot) return false
        return id == other.id &&
            title == other.title &&
            kind == other.kind &&
            available == other.available &&
            products == other.products &&
            source == other.source
    }

    override fun hashCode(): Int = java.util.Objects.hash(id, title, kind, available, products, source)

    override fun toString(): String = "OfferSlot(id=$id, title=$title, kind=$kind, available=$available, products=$products, source=$source)"

    /** Where a paywall is built. Open: a value this SDK version does not know is kept verbatim. */
    @JvmInline
    public value class Source(
        /** The wire code, for example `revenuecat`. */
        public val code: String,
    ) {
        override fun toString(): String = code

        /** Known sources. */
        public companion object {
            /** A RevenueCat offering (the `pingfront-revenuecat` adapter). */
            public val RevenueCat: Source = Source("revenuecat")

            /** A Superwall placement (the `pingfront-superwall` adapter). */
            public val Superwall: Source = Source("superwall")

            /** An Adapty placement (the `pingfront-adapty` adapter). */
            public val Adapty: Source = Source("adapty")

            /** StoreKit products (iOS). */
            public val StoreKit: Source = Source("storekit")

            /** Google Play products (the `pingfront-billing` module). */
            public val Play: Source = Source("play")

            /** The host app's own paywall code. */
            public val App: Source = Source("app")
        }
    }

    internal object SourceSerializer : kotlinx.serialization.KSerializer<Source> {
        override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
            kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("dev.pingfront.OfferSource", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

        override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Source) = encoder.encodeString(value.code)

        override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Source = Source(decoder.decodeString())
    }

    /** Offer flavours. */
    @Serializable(with = KindSerializer::class)
    public enum class Kind { Standard, Discount, Winback }

    /** One purchasable product in the slot. */
    @Serializable
    public class Product(
        /** Store product id. */
        public val id: String,
        /** Localized display price, for example "€4.99". */
        public val price: String,
        /** ISO-8601 period such as "P1M". */
        public val period: String? = null,
        /** ISO-8601 trial period such as "P1W". */
        public val trial: String? = null,
    ) {
        /** A copy with the given fields replaced. */
        public fun copy(id: String = this.id, price: String = this.price, period: String? = this.period, trial: String? = this.trial): Product =
            Product(id, price, period, trial)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Product) return false
            return id == other.id &&
                price == other.price &&
                period == other.period &&
                trial == other.trial
        }

        override fun hashCode(): Int = java.util.Objects.hash(id, price, period, trial)

        override fun toString(): String = "Product(id=$id, price=$price, period=$period, trial=$trial)"
    }

    internal object KindSerializer : TolerantEnumSerializer<Kind>(
        "OfferKind",
        mapOf(Kind.Standard to "standard", Kind.Discount to "discount", Kind.Winback to "winback"),
        Kind.Standard,
    )
}

/** A summary of the person this install belongs to. */
@Serializable
@ConsistentCopyVisibility
public data class PersonSummary internal constructor(
    /** Server id of the person. */
    public val id: String,
    /** Guest, named or verified. */
    public val level: Level,
    /** Display name, if known. */
    public val name: String? = null,
    /** The person's plan as the server knows it from store events; absent until one was recorded. */
    public val entitlement: Entitlement? = null,
) {
    /** Identity levels. */
    @Serializable(with = LevelSerializer::class)
    public enum class Level {
        /** Anonymous. */
        Guest,

        /** Identified by the app, not verified. */
        Named,

        /** Verified with an identity token: one thread across devices. */
        Verified,

        /** A level this SDK version does not know yet (never mistaken for a known one). */
        Unknown,
    }

    internal object LevelSerializer : TolerantEnumSerializer<Level>(
        "PersonLevel",
        mapOf(Level.Guest to "guest", Level.Named to "named", Level.Verified to "verified", Level.Unknown to UNKNOWN_WIRE),
        Level.Unknown,
    )
}

/** A problem with the identity token, reported by the server without failing the request. */
@Serializable(with = IdentityProblemSerializer::class)
public enum class IdentityProblem {
    /** The signature did not verify. */
    TokenInvalid,

    /** The token expired. */
    TokenExpired,

    /** The token belongs to a different user id. */
    UserMismatch,

    /** A problem this SDK version does not know. */
    Unknown,
}

internal object IdentityProblemSerializer : TolerantEnumSerializer<IdentityProblem>(
    "IdentityProblem",
    mapOf(
        IdentityProblem.TokenInvalid to "token_invalid",
        IdentityProblem.TokenExpired to "token_expired",
        IdentityProblem.UserMismatch to "user_mismatch",
        IdentityProblem.Unknown to "unknown",
    ),
    IdentityProblem.Unknown,
)

/** Whether the person can send. */
@Serializable(with = SendPolicySerializer::class)
public sealed interface SendPolicy {
    /** Sending is allowed. */
    public data object Open : SendPolicy

    /** Sending is off, for [reason]. */
    @ConsistentCopyVisibility
    public data class Closed internal constructor(
        /** Why. */
        public val reason: Reason,
    ) : SendPolicy

    /** Why sending is off. Open: unknown reasons keep their code. */
    @JvmInline
    public value class Reason(
        /** The wire code. */
        public val code: String,
    ) {
        override fun toString(): String = code

        /**
         * True when a message refused for this reason waits and goes again by itself when sending reopens: a pause or
         * a required sign-in ends without the person writing again. A blocked person is promised nothing.
         */
        @dev.pingfront.InternalPingFrontApi
        public val holdsMessages: Boolean get() = this == AppPaused || this == VerificationRequired

        /** Known reasons. */
        public companion object {
            /** The team blocked messages from this person. */
            public val Blocked: Reason = Reason("blocked")

            /** The app is paused in the dashboard. */
            public val AppPaused: Reason = Reason("app_paused")

            /** The app accepts messages only from verified people: the person must sign in to the host app. */
            public val VerificationRequired: Reason = Reason("verification_required")
        }
    }

    /** True when the composer is enabled. */
    public val canSend: Boolean get() = this is Open
}

internal object SendPolicySerializer : kotlinx.serialization.KSerializer<SendPolicy> {
    @Serializable
    private data class Wire(val canSend: Boolean = true, val reason: String? = null)

    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor = Wire.serializer().descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: SendPolicy) = Wire.serializer().serialize(
        encoder,
        when (value) {
            SendPolicy.Open -> Wire(true)
            is SendPolicy.Closed -> Wire(false, value.reason.code)
        },
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): SendPolicy {
        val wire = Wire.serializer().deserialize(decoder)
        return if (wire.canSend) SendPolicy.Open else SendPolicy.Closed(SendPolicy.Reason(wire.reason ?: "unknown"))
    }
}

/** Open or resolved. [Unknown] is a status this SDK version does not know yet (shown like open). */
@Serializable(with = ThreadStatusSerializer::class)
public enum class ThreadStatus { Open, Resolved, Unknown }

internal object ThreadStatusSerializer : TolerantEnumSerializer<ThreadStatus>(
    "ThreadStatus",
    mapOf(ThreadStatus.Open to "open", ThreadStatus.Resolved to "resolved", ThreadStatus.Unknown to UNKNOWN_WIRE),
    ThreadStatus.Unknown,
)

/** A member of the app's team, shown in the header and welcome. */
@Serializable
@ConsistentCopyVisibility
public data class TeamMember internal constructor(
    /** Member id. */
    public val id: String,
    /** Display name. */
    public val name: String,
    /** For example "Maker of Clockwise". */
    public val role: String? = null,
    /** Avatar URL. */
    public val avatarUrl: String? = null,
    /** IANA time zone, used for "21:40 for Deniz". */
    public val timeZone: String? = null,
)
