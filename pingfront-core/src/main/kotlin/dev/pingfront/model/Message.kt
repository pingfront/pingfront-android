@file:UseSerializers(InstantSerializer::class)

package dev.pingfront.model

import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.wire.DecodeLog
import dev.pingfront.internal.wire.InstantSerializer
import dev.pingfront.internal.wire.LossyListSerializer
import dev.pingfront.internal.wire.TolerantEnumSerializer
import dev.pingfront.internal.wire.UNKNOWN_WIRE
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.asJson
import dev.pingfront.internal.wire.string
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val log = Log.of(Log.Category.Decoding)

/** Who wrote a message. */
@Serializable
@ConsistentCopyVisibility
public data class Author internal constructor(
    /** Person, team, AI or system. */
    public val kind: Kind,
    /** Team member id. */
    public val id: String? = null,
    /** Display name. */
    public val name: String? = null,
    /** Avatar image URL. */
    public val avatarUrl: String? = null,
) {
    /** The kind of author. */
    @Serializable(with = KindSerializer::class)
    public enum class Kind {
        /** The person using the app. */
        Person,

        /** A teammate. */
        Team,

        /** The assistant. Its messages are always labelled. */
        Ai,

        /** A system line (receipts). */
        System,

        /** A kind this SDK version does not know; shown like a team message. */
        Unknown,
    }

    internal object KindSerializer : TolerantEnumSerializer<Kind>(
        "AuthorKind",
        mapOf(Kind.Person to "person", Kind.Team to "team", Kind.Ai to "ai", Kind.System to "system", Kind.Unknown to "unknown"),
        Kind.Unknown,
    )
}

/** A structured card inside a message. Unknown kinds decode as [Unknown] and are skipped by the UI. */
@Serializable(with = CardSerializer::class)
public sealed interface Card {
    /** The card id. */
    public val id: String

    /** An offer card: opens the app's offer (paywall or promo) named by [offerId]. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Offer internal constructor(
        override val id: String,
        /** The `OfferSlot.id` to show. */
        public val offerId: String,
        /** Card title. */
        public val title: String,
        /** Optional body. */
        public val body: String? = null,
        /** Button label. */
        public val button: String,
        /** The store offer the button redeems (a Play subscription offer), if the card names one. */
        public val offer: StoreOffer? = null,
    ) : Card

    /** A review card: the native review flow or the store page. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Review internal constructor(
        override val id: String,
        /** Native prompt or store page. */
        public val mode: ReviewMode,
        /** Store page URL. */
        public val storeUrl: String? = null,
        /** Button label. */
        public val button: String,
    ) : Card

    /**
     * A link or deep-link card. With [screen], a saved link to a screen of the app: the SDK hands it to
     * `PingFront.onScreen` when this build registered the screen (`PingFront.registerScreens`), else opens [url].
     */
    @Serializable
    @ConsistentCopyVisibility
    public data class Link internal constructor(
        override val id: String,
        /** Card title. */
        public val title: String,
        /** A web URL or app deep link. With [screen], the fallback for builds that don't register it, or `""` for none. */
        public val url: String,
        /** Button label. */
        public val button: String,
        /** The app screen the link opens, when the team sent a saved link to one. */
        public val screen: LinkScreen? = null,
        /** The saved link the card was made from (set by the server; the SDK needs nothing from it). */
        public val savedLinkId: String? = null,
    ) : Card

    /** A choices card: the person picks one option. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Choices internal constructor(
        override val id: String,
        /** Two to six options. */
        public val options: List<ChoiceOption>,
    ) : Card

    /** A 1–5 rating card. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Rating internal constructor(
        override val id: String,
        /** The question. */
        public val question: String,
        /** Always 5. */
        public val scale: Int = 5,
    ) : Card

    /** A bug report card: opens the report flow. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Report internal constructor(
        override val id: String,
        /** Card title. */
        public val title: String,
        /** Button label. */
        public val button: String,
    ) : Card

    /**
     * A store action card (the protocol, Store action cards): opens the store's own screen for what only the store
     * can do (manage or fix a subscription, refunds, codes) or restores purchases in place. On Android, Google Play.
     */
    @Serializable
    @ConsistentCopyVisibility
    public data class StoreAction internal constructor(
        override val id: String,
        /** What the card does. */
        public val action: Action,
        /** Card title; the SDK's localized title for the action when absent. */
        public val title: String? = null,
        /** Optional body; the SDK's localized explanation when absent. */
        public val body: String? = null,
        /** Button label; the SDK's localized label for the action when absent. */
        public val button: String? = null,
        /** The subscription product it is about (Play: the `sku` of the subscriptions link). */
        public val productId: String? = null,
        /** App Store subscription group (iOS only; ignored on Android). */
        public val subscriptionGroupId: String? = null,
        /** App Store transaction for refunds (iOS only; ignored on Android). */
        public val transactionId: String? = null,
        /** Store hint, `app_store` or `play`; Android ignores it. */
        public val store: String? = null,
    ) : Card {
        /**
         * Store actions. [Unknown] is an action this SDK version does not know: the card shows its text and opens
         * Play's subscriptions page.
         */
        @Serializable(with = StoreActionSerializer::class)
        public enum class Action { ManageSubscriptions, BillingIssue, RequestRefund, RedeemCode, RestorePurchases, Unknown }
    }

    /** A card kind this SDK version does not know. */
    @ConsistentCopyVisibility
    public data class Unknown internal constructor(
        /** The unknown kind. */
        public val kind: String,
        override val id: String,
    ) : Card
}

/** A screen of the app that a link card opens (the protocol, Saved links). */
@Serializable
@ConsistentCopyVisibility
public data class LinkScreen internal constructor(
    /** The screen id the app registered (`AppScreen.id`). */
    public val id: String,
    /** String parameters for the screen (at most 10), as the team set them. */
    public val params: Map<String, String> = emptyMap(),
) {
    internal companion object {
        fun decodeOrNull(element: kotlinx.serialization.json.JsonElement?): LinkScreen? {
            if (element == null || element is kotlinx.serialization.json.JsonNull) return null
            return try {
                WireJson.decodeFromJsonElement(serializer(), element)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** One option of a choices card. */
@Serializable
@ConsistentCopyVisibility
public data class ChoiceOption internal constructor(
    /** Option id. */
    public val id: String,
    /** Label. */
    public val label: String,
)

/** How a review card asks for a review. */
@Serializable(with = ReviewModeSerializer::class)
public enum class ReviewMode {
    /** The in-app review flow (Play In-App Review on Android). */
    NativePrompt,

    /** The store page. */
    StorePage,

    /** A mode this SDK version does not know yet (the store page is used). */
    Unknown,
}

internal object ReviewModeSerializer : TolerantEnumSerializer<ReviewMode>(
    "ReviewMode",
    mapOf(ReviewMode.NativePrompt to "native_prompt", ReviewMode.StorePage to "store_page", ReviewMode.Unknown to UNKNOWN_WIRE),
    ReviewMode.Unknown,
)

internal object StoreActionSerializer : TolerantEnumSerializer<Card.StoreAction.Action>(
    "StoreAction",
    mapOf(
        Card.StoreAction.Action.ManageSubscriptions to "manage_subscriptions",
        Card.StoreAction.Action.BillingIssue to "billing_issue",
        Card.StoreAction.Action.RequestRefund to "request_refund",
        Card.StoreAction.Action.RedeemCode to "redeem_code",
        Card.StoreAction.Action.RestorePurchases to "restore_purchases",
        Card.StoreAction.Action.Unknown to UNKNOWN_WIRE,
    ),
    Card.StoreAction.Action.Unknown,
)

internal object CardSerializer : KSerializer<Card> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.pingfront.Card")

    override fun deserialize(decoder: Decoder): Card {
        val obj = decoder.asJson().decodeJsonElement().jsonObject
        val kind = obj.string("kind") ?: ""
        val id = obj.string("id") ?: ""
        return try {
            when (kind) {
                // A bad `offer` drops only the store offer: the card still opens the paywall.
                "offer" -> WireJson.decodeFromJsonElement(Card.Offer.serializer(), kotlinx.serialization.json.JsonObject(obj - "offer"))
                    .copy(offer = StoreOffer.decodeOrNull(obj["offer"]))
                "review" -> WireJson.decodeFromJsonElement(Card.Review.serializer(), obj)
                // A bad `screen` drops only the screen: the card still opens its `url`.
                "link" -> WireJson.decodeFromJsonElement(Card.Link.serializer(), kotlinx.serialization.json.JsonObject(obj - "screen"))
                    .copy(screen = LinkScreen.decodeOrNull(obj["screen"]))
                "choices" -> WireJson.decodeFromJsonElement(Card.Choices.serializer(), obj)
                "rating" -> WireJson.decodeFromJsonElement(Card.Rating.serializer(), obj)
                "report" -> WireJson.decodeFromJsonElement(Card.Report.serializer(), obj)
                "store_action" -> WireJson.decodeFromJsonElement(Card.StoreAction.serializer(), obj)
                else -> Card.Unknown(kind, id)
            }
        } catch (_: SerializationException) {
            Card.Unknown(kind, id)
        } catch (_: IllegalArgumentException) {
            Card.Unknown(kind, id)
        }
    }

    override fun serialize(encoder: Encoder, value: Card) {
        val (kind, element) = when (value) {
            is Card.Offer -> "offer" to WireJson.encodeToJsonElement(Card.Offer.serializer(), value)
            is Card.Review -> "review" to WireJson.encodeToJsonElement(Card.Review.serializer(), value)
            is Card.Link -> "link" to WireJson.encodeToJsonElement(Card.Link.serializer(), value)
            is Card.Choices -> "choices" to WireJson.encodeToJsonElement(Card.Choices.serializer(), value)
            is Card.Rating -> "rating" to WireJson.encodeToJsonElement(Card.Rating.serializer(), value)
            is Card.Report -> "report" to WireJson.encodeToJsonElement(Card.Report.serializer(), value)
            is Card.StoreAction -> "store_action" to WireJson.encodeToJsonElement(Card.StoreAction.serializer(), value)
            is Card.Unknown -> value.kind to buildJsonObject { put("id", value.id) }
        }
        encoder.asJson().encodeJsonElement(JsonObject(mapOf("kind" to JsonPrimitive(kind)) + element.jsonObject))
    }
}

/** The final state of a card the person acted on, filled by the server. */
@Serializable
@ConsistentCopyVisibility
public data class CardResult internal constructor(
    /** When the person acted. */
    public val at: Instant,
    /** The chosen option, for choices cards. */
    public val choiceId: String? = null,
    /** The rating, for rating cards. */
    public val rating: Int? = null,
)

/** What a receipt records. */
@Serializable(with = ReceiptActionSerializer::class)
public enum class ReceiptAction {
    /** A trial was extended. */
    TrialExtended,

    /** A promo was granted. */
    PromoGranted,

    /** Purchases were restored. */
    PurchasesRestored,

    /** A bug report was filed. */
    ReportFiled,

    /** Data was exported. */
    DataExported,

    /**
     * The AI teammate handed the conversation to the team ("Deniz will reply"). Nothing was done for the person,
     * so the chat shows it without the green check.
     */
    HandedOff,

    /** Anything else, including actions this SDK version does not know. */
    Custom,
}

internal object ReceiptActionSerializer : TolerantEnumSerializer<ReceiptAction>(
    "ReceiptAction",
    mapOf(
        ReceiptAction.TrialExtended to "trial_extended",
        ReceiptAction.PromoGranted to "promo_granted",
        ReceiptAction.PurchasesRestored to "purchases_restored",
        ReceiptAction.ReportFiled to "report_filed",
        ReceiptAction.DataExported to "data_exported",
        ReceiptAction.HandedOff to "handed_off",
        ReceiptAction.Custom to "custom",
    ),
    ReceiptAction.Custom,
)

/** One part of a message. Unknown part types decode as [Unknown], so new server content never crashes old SDKs. */
@Serializable(with = MessagePartSerializer::class)
public sealed interface MessagePart {
    /** Plain text. */
    @ConsistentCopyVisibility
    public data class Text internal constructor(
        /** The text. */
        public val text: String,
    ) : MessagePart

    /** An image attachment. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Image internal constructor(
        /** Attachment id; an expired [url] is refreshed with `GET /attachments/:id`. */
        public val attachmentId: String,
        /** Pixel width. */
        public val width: Int,
        /** Pixel height. */
        public val height: Int,
        /** Short-lived download URL. */
        public val url: String? = null,
        /**
         * The app's retention deleted the photo: there is no [url], and the chat shows a "Photo removed"
         * placeholder instead of fetching it.
         */
        public val removed: Boolean = false,
    ) : MessagePart

    /** A card, with its result once the person acted. */
    @Serializable
    @ConsistentCopyVisibility
    public data class CardPart internal constructor(
        /** The card. */
        public val card: Card,
        /** Set once the person acted. */
        public val result: CardResult? = null,
    ) : MessagePart

    /** A centered system line recording something done for the person. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Receipt internal constructor(
        /** What was done. */
        public val action: ReceiptAction,
        /** For example "Trial extended to 14 Nov". */
        public val text: String,
    ) : MessagePart

    /** A part type this SDK version does not know. */
    @ConsistentCopyVisibility
    public data class Unknown internal constructor(
        /** The unknown type. */
        public val type: String,
    ) : MessagePart
}

internal object MessagePartSerializer : KSerializer<MessagePart> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("dev.pingfront.MessagePart")

    override fun deserialize(decoder: Decoder): MessagePart {
        val obj = decoder.asJson().decodeJsonElement().jsonObject
        val type = obj.string("type") ?: ""
        return try {
            when (type) {
                "text" -> MessagePart.Text(obj.string("text") ?: throw SerializationException("text"))
                "image" -> WireJson.decodeFromJsonElement(MessagePart.Image.serializer(), obj).let {
                    // A removed photo has no bytes: drop any url so nothing tries to fetch it.
                    if (it.removed && it.url != null) it.copy(url = null) else it
                }
                "card" -> WireJson.decodeFromJsonElement(MessagePart.CardPart.serializer(), obj)
                "receipt" -> WireJson.decodeFromJsonElement(MessagePart.Receipt.serializer(), obj)
                else -> MessagePart.Unknown(type).also {
                    log.d("Unknown message part type \"$type\"; showing the fallback.")
                }
            }
        } catch (e: SerializationException) {
            DecodeLog.dropped("$type part", e)
            MessagePart.Unknown(type)
        } catch (e: IllegalArgumentException) {
            DecodeLog.dropped("$type part", e)
            MessagePart.Unknown(type)
        }
    }

    override fun serialize(encoder: Encoder, value: MessagePart) {
        val (type, body) = when (value) {
            is MessagePart.Text -> "text" to buildJsonObject { put("text", value.text) }
            is MessagePart.Image -> "image" to WireJson.encodeToJsonElement(MessagePart.Image.serializer(), value).jsonObject
            is MessagePart.CardPart -> "card" to WireJson.encodeToJsonElement(MessagePart.CardPart.serializer(), value).jsonObject
            is MessagePart.Receipt -> "receipt" to WireJson.encodeToJsonElement(MessagePart.Receipt.serializer(), value).jsonObject
            is MessagePart.Unknown -> value.type to JsonObject(emptyMap())
        }
        encoder.asJson().encodeJsonElement(JsonObject(mapOf("type" to JsonPrimitive(type)) + body))
    }
}

/** Why a team or AI message arrived before the person wrote. Shown to the person, never configurable. */
@Serializable
@ConsistentCopyVisibility
public data class PingOrigin internal constructor(
    /** Manual (a teammate wrote it) or play (a rule sent it). */
    public val kind: Kind,
    /** The play that sent it. */
    public val playId: String? = null,
    /** Rendered by the server in the person's language, for example "Sent automatically after you closed Plans". */
    public val explanation: String,
    /** The machine-readable reason. */
    @Serializable(with = LenientReasonSerializer::class)
    public val reason: PingReason? = null,
) {
    /** Who started the conversation. */
    @Serializable(with = KindSerializer::class)
    public enum class Kind {
        /** A teammate wrote first. */
        Manual,

        /** A play (rule) sent it. */
        Play,

        /** A kind this SDK version does not know yet. */
        Unknown,
    }

    internal object KindSerializer : TolerantEnumSerializer<Kind>(
        "PingKind",
        mapOf(Kind.Manual to "manual", Kind.Play to "play", Kind.Unknown to UNKNOWN_WIRE),
        Kind.Unknown,
    )
}

/** Why a ping was sent, as a code SDKs can localize or restyle without parsing text. */
@Serializable
@ConsistentCopyVisibility
public data class PingReason internal constructor(
    /** The reason code. */
    public val code: Code,
    /** Parameters, for example the moment name. */
    public val params: Map<String, String> = emptyMap(),
) {
    /** Reason codes. */
    @Serializable(with = CodeSerializer::class)
    public enum class Code {
        /** A teammate wrote first. */
        Manual,

        /** A play reacted to an app moment. */
        Moment,

        /** A play reacted to a store event. */
        StoreEvent,

        /** A scheduled play. */
        Scheduled,

        /** A code this SDK version does not know. */
        Unknown,
    }

    internal object CodeSerializer : TolerantEnumSerializer<Code>(
        "PingReasonCode",
        mapOf(
            Code.Manual to "manual",
            Code.Moment to "moment",
            Code.StoreEvent to "store_event",
            Code.Scheduled to "scheduled",
            Code.Unknown to "unknown",
        ),
        Code.Unknown,
    )
}

internal object LenientReasonSerializer : LenientSerializer<PingReason>(PingReason.serializer())

/** A machine translation of a message's text parts. */
@Serializable(with = TranslationSerializer::class)
@ConsistentCopyVisibility
public data class Translation internal constructor(
    /** Target language tag. */
    public val language: String,
    /** Translated text parts. */
    public val texts: List<String>,
    /** The language the message was written in (a language tag), when the server knows it. */
    public val from: String? = null,
)

internal object TranslationSerializer : KSerializer<Translation> {
    @Serializable
    private class Wire(
        val language: String,
        @Serializable(with = TextListSerializer::class) val parts: List<String>,
        val from: String? = null,
    )

    private object TextListSerializer : KSerializer<List<String>> {
        private val lossy = LossyListSerializer(MessagePartSerializer)
        override val descriptor: SerialDescriptor = lossy.descriptor

        override fun serialize(encoder: Encoder, value: List<String>) = lossy.serialize(encoder, value.map { MessagePart.Text(it) })

        override fun deserialize(decoder: Decoder): List<String> = lossy.deserialize(decoder).filterIsInstance<MessagePart.Text>().map {
            it.text
        }
    }

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Translation) = Wire.serializer().serialize(encoder, Wire(value.language, value.texts, value.from))

    override fun deserialize(decoder: Decoder): Translation = Wire.serializer().deserialize(decoder).let {
        Translation(it.language, it.parts, it.from)
    }
}

/** A message in the thread. */
@Serializable
@ConsistentCopyVisibility
public data class Message internal constructor(
    /** Server id. */
    public val id: String,
    /** Position in the thread. */
    public val seq: Int,
    /** The sender's idempotency key (person messages sent from an SDK). */
    public val clientId: String? = null,
    /** Who wrote it. */
    public val author: Author,
    /** Text, images, cards and receipts. */
    @Serializable(with = PartListSerializer::class)
    public val parts: List<MessagePart>,
    /** When the server accepted it. */
    public val createdAt: Instant,
    /** Set when the team reached out before the person wrote. */
    @Serializable(with = LenientPingSerializer::class)
    public val ping: PingOrigin? = null,
    /** A plain-text rendering of the whole message, shown for parts and cards this SDK version does not know. */
    public val fallback: String? = null,
    /** Machine translation, when enabled. */
    @Serializable(with = LenientTranslationSerializer::class)
    public val translation: Translation? = null,
) {
    /** The plain text of all text parts, joined by newlines. */
    public val plainText: String get() = parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }

    /** True for messages written by the person on this or another device. */
    public val isFromPerson: Boolean get() = author.kind == Author.Kind.Person
}

internal object PartListSerializer : KSerializer<List<MessagePart>> by LossyListSerializer(MessagePartSerializer)

/** Decodes an optional object, turning a malformed value into `null` instead of failing the message. */
internal open class LenientSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.descriptor

    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.asJson().encodeJsonElement(kotlinx.serialization.json.JsonNull) else inner.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T? {
        val element = decoder.asJson().decodeJsonElement()
        return try {
            WireJson.decodeFromJsonElement(inner, element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal object LenientPingSerializer : LenientSerializer<PingOrigin>(PingOrigin.serializer())

internal object LenientTranslationSerializer : LenientSerializer<Translation>(TranslationSerializer)

/**
 * The store offer an offer card redeems (`StoreOffer`). On Google Play, [basePlanId] and [offerId] name the
 * subscription offer; the billing module buys it when the person is eligible and falls back to the base plan.
 */
@Serializable
@ConsistentCopyVisibility
public data class StoreOffer internal constructor(
    /** The offer's kind (promotional, win-back, code, intro). */
    @Serializable(with = KindSerializer::class)
    public val kind: Kind,
    /** The offer's id. */
    public val id: String,
    /** The product the offer applies to (the Play subscription id). */
    public val productId: String? = null,
    /** Google Play: the base plan. */
    public val basePlanId: String? = null,
    /** Google Play: the offer within the base plan. */
    public val offerId: String? = null,
) {
    /** Offer kinds. [Unknown] is a kind this SDK version does not know (the card then just opens the paywall). */
    public enum class Kind { Promotional, Winback, Code, Intro, Unknown }

    internal object KindSerializer : TolerantEnumSerializer<Kind>(
        "StoreOfferKind",
        mapOf(Kind.Promotional to "promotional", Kind.Winback to "winback", Kind.Code to "code", Kind.Intro to "intro", Kind.Unknown to UNKNOWN_WIRE),
        Kind.Unknown,
    )

    internal companion object {
        fun decodeOrNull(element: kotlinx.serialization.json.JsonElement?): StoreOffer? {
            if (element == null || element is kotlinx.serialization.json.JsonNull) return null
            return try {
                WireJson.decodeFromJsonElement(serializer(), element)
            } catch (e: SerializationException) {
                DecodeLog.dropped("store offer", e)
                null
            } catch (e: IllegalArgumentException) {
                DecodeLog.dropped("store offer", e)
                null
            }
        }
    }
}
