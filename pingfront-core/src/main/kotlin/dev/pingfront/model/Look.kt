package dev.pingfront.model

import dev.pingfront.internal.wire.DecodeLog
import dev.pingfront.internal.wire.LossyListSerializer
import dev.pingfront.internal.wire.TolerantEnumSerializer
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.asJson
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/** An sRGB color with components in 0..1. Platform-independent, so Core can merge looks and check contrast. */
@Serializable(with = RgbColorSerializer::class)
public class RgbColor(red: Double, green: Double, blue: Double) {
    /** Red, 0..1. */
    public val red: Double = red.coerceIn(0.0, 1.0)

    /** Green, 0..1. */
    public val green: Double = green.coerceIn(0.0, 1.0)

    /** Blue, 0..1. */
    public val blue: Double = blue.coerceIn(0.0, 1.0)

    /** `#RRGGBB`, uppercase. */
    public val hex: String
        get() = "#%02X%02X%02X".format((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

    /** The color as an opaque ARGB int, for Android views and Compose. */
    public val argb: Int
        get() = (0xFF shl 24) or ((red * 255).roundToInt() shl 16) or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt()

    /** WCAG 2.x relative luminance. */
    public val luminance: Double
        get() {
            fun channel(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
        }

    /** Mixes toward [other] by [amount] (0 = this, 1 = other). */
    public fun mixed(other: RgbColor, amount: Double): RgbColor =
        RgbColor(red + (other.red - red) * amount, green + (other.green - green) * amount, blue + (other.blue - blue) * amount)

    override fun equals(other: Any?): Boolean = other is RgbColor && other.hex == hex

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = hex

    /** Constants and parsers. */
    public companion object {
        /** White. */
        public val White: RgbColor = RgbColor(1.0, 1.0, 1.0)

        /** Black. */
        public val Black: RgbColor = RgbColor(0.0, 0.0, 0.0)

        /** Parses `#RRGGBB`, or returns `null`. */
        public fun hex(text: String): RgbColor? {
            val body = text.trim().removePrefix("#")
            if (body.length != HEX_DIGITS) return null
            val value = body.toIntOrNull(16) ?: return null
            return argb(value)
        }

        /** From an ARGB int (alpha is ignored). */
        public fun argb(color: Int): RgbColor =
            RgbColor(((color shr RED_SHIFT) and BYTE) / MAX, ((color shr GREEN_SHIFT) and BYTE) / MAX, (color and BYTE) / MAX)

        /** WCAG contrast ratio between two colors (1..21). */
        public fun contrast(a: RgbColor, b: RgbColor): Double {
            val l1 = a.luminance
            val l2 = b.luminance
            return (maxOf(l1, l2) + FLARE) / (minOf(l1, l2) + FLARE)
        }
    }
}

internal object RgbColorSerializer : KSerializer<RgbColor> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.pingfront.RgbColor", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RgbColor) = encoder.encodeString(value.hex)

    override fun deserialize(decoder: Decoder): RgbColor {
        val text = decoder.decodeString()
        return RgbColor.hex(text) ?: throw SerializationException("Expected #RRGGBB, got $text")
    }
}

/** A color with a light and a dark variant (`PerMode` on the wire). */
@Serializable
public class PingFrontColor(
    /** Light mode value. */
    public val light: RgbColor,
    /** Dark mode value. */
    public val dark: RgbColor,
) {
    /** A copy with the given fields replaced. */
    public fun copy(light: RgbColor = this.light, dark: RgbColor = this.dark): PingFrontColor = PingFrontColor(light, dark)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PingFrontColor) return false
        return light == other.light &&
            dark == other.dark
    }

    override fun hashCode(): Int = java.util.Objects.hash(light, dark)

    override fun toString(): String = "PingFrontColor(light=$light, dark=$dark)"

    /** The value for a mode. */
    public fun value(dark: Boolean): RgbColor = if (dark) this.dark else light

    /** Preset colors. */
    public companion object {
        /** Google blue: the default accent when the host app has no usable `colorPrimary`. */
        public val Blue: PingFrontColor = PingFrontColor(DefaultBlue.light, DefaultBlue.dark)

        /** Teal. */
        public val Teal: PingFrontColor = PingFrontColor("#00796B", "#4DB6AC")

        /** Green. */
        public val Green: PingFrontColor = PingFrontColor("#146C2E", "#6DD58C")

        /** Purple. */
        public val Purple: PingFrontColor = PingFrontColor("#6750A4", "#D0BCFF")

        /** Orange. */
        public val Orange: PingFrontColor = PingFrontColor("#B3261E", "#FFB4AB")

        /** Pink. */
        public val Pink: PingFrontColor = PingFrontColor("#C8004F", "#FF7AAA")
    }
}

/**
 * A color from two hex strings (`#RRGGBB`). An invalid value is reported (loudly in debug builds) and
 * replaced by [PingFrontColor.Blue]'s value for that mode.
 */
public fun PingFrontColor(light: String, dark: String = light): PingFrontColor {
    fun parse(text: String, fallback: RgbColor): RgbColor = RgbColor.hex(text) ?: fallback.also {
        DecodeLog.invalid("PingFrontColor: \"$text\" is not #RRGGBB; using the default accent instead.")
    }
    return PingFrontColor(parse(light, DefaultBlue.light), parse(dark, DefaultBlue.dark))
}

private object DefaultBlue {
    val light: RgbColor = RgbColor(0x0B / 255.0, 0x57 / 255.0, 0xD0 / 255.0)
    val dark: RgbColor = RgbColor(0xA8 / 255.0, 0xC7 / 255.0, 0xFA / 255.0)
}

/** A color from ARGB values, for example `PingFrontColor(0xFF00897B)`. */
public fun PingFrontColor(light: Long, dark: Long = light): PingFrontColor = PingFrontColor(RgbColor.argb(light.toInt()), RgbColor.argb(dark.toInt()))

/**
 * Text the customer wrote, keyed by BCP-47 language plus a required `"default"`.
 * Lookup: exact tag, then base language, then `"default"`.
 */
@Serializable(with = LocalizedTextSerializer::class)
public class LocalizedText(
    /** Values by language tag. */
    public val values: Map<String, String>,
) {
    /** A copy with the given fields replaced. */
    public fun copy(values: Map<String, String> = this.values): LocalizedText = LocalizedText(values)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalizedText) return false
        return values == other.values
    }

    override fun hashCode(): Int = java.util.Objects.hash(values)

    override fun toString(): String = "LocalizedText(values=$values)"

    /** Resolves for a tag such as `pt-BR`: exact tag (case-insensitive), then base language, then `"default"`. */
    public fun resolve(languageTag: String): String? {
        val tag = languageTag.replace('_', '-')
        values[tag]?.let { return it }
        values.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.let { return it.value }
        val base = tag.substringBefore('-').lowercase()
        values[base]?.let { return it }
        return values["default"]
    }

    /** Factories. */
    public companion object {
        /** The same text for every language. */
        public fun of(text: String): LocalizedText = LocalizedText(mapOf("default" to text))
    }
}

internal object LocalizedTextSerializer : KSerializer<LocalizedText> {
    private val map = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor = map.descriptor

    override fun serialize(encoder: Encoder, value: LocalizedText) = map.serialize(encoder, value.values)

    override fun deserialize(decoder: Decoder): LocalizedText = LocalizedText(map.deserialize(decoder))
}

/** The symbol on a topic chip. */
@Serializable(with = TopicSymbolSerializer::class)
public enum class TopicSymbol { Billing, Bug, Idea, Question, Account, Feedback, Gift, Restore, Privacy, Other }

internal object TopicSymbolSerializer : TolerantEnumSerializer<TopicSymbol>(
    "TopicSymbol",
    TopicSymbol.entries.associateWith { it.name.lowercase() },
    TopicSymbol.Other,
)

/** Light, dark or automatic. */
@Serializable(with = SchemeSerializer::class)
public enum class ColorScheme { Auto, Light, Dark }

/** The font style of the chat. */
@Serializable(with = FontStyleSerializer::class)
public enum class FontStyle { System, Rounded, Serif }

/** Bubble corners: round 18, soft 12, square 6 dp. */
@Serializable(with = CornersSerializer::class)
public enum class Corners { Round, Soft, Square }

/** Spacing density. */
@Serializable(with = DensitySerializer::class)
public enum class Density { Comfortable, Compact }

/** Header style: one face, stacked team faces, or the app icon. */
@Serializable(with = HeaderStyleSerializer::class)
public enum class HeaderStyle { Person, Team, App }

/** Timestamps per group or under every message. */
@Serializable(with = TimestampsSerializer::class)
public enum class Timestamps { Grouped, Always }

internal object SchemeSerializer : TolerantEnumSerializer<ColorScheme>(
    "Scheme",
    ColorScheme.entries.associateWith {
        it.name.lowercase()
    },
    ColorScheme.Auto,
)

internal object FontStyleSerializer : TolerantEnumSerializer<FontStyle>(
    "FontStyle",
    FontStyle.entries.associateWith {
        it.name.lowercase()
    },
    FontStyle.System,
)

internal object CornersSerializer : TolerantEnumSerializer<Corners>(
    "Corners",
    Corners.entries.associateWith {
        it.name.lowercase()
    },
    Corners.Round,
)

internal object DensitySerializer : TolerantEnumSerializer<Density>(
    "Density",
    Density.entries.associateWith {
        it.name.lowercase()
    },
    Density.Comfortable,
)

internal object HeaderStyleSerializer : TolerantEnumSerializer<HeaderStyle>(
    "HeaderStyle",
    HeaderStyle.entries.associateWith {
        it.name.lowercase()
    },
    HeaderStyle.Person,
)

internal object TimestampsSerializer : TolerantEnumSerializer<Timestamps>(
    "Timestamps",
    Timestamps.entries.associateWith {
        it.name.lowercase()
    },
    Timestamps.Grouped,
)

/**
 * The chat look as configured in the dashboard (the API's `ChatLook`). Fields keep "unset" as `null`
 * so values set in code can fill them; `ResolvedLook` applies defaults.
 */
@Serializable(with = ChatLookSerializer::class)
@ConsistentCopyVisibility
public data class ChatLook internal constructor(
    /** Colors and type. */
    public val theme: Theme = Theme(),
    /** Header options. */
    public val header: Header = Header(),
    /** Welcome (empty state) options. */
    public val welcome: Welcome = Welcome(),
    /** Up to six topics. An empty list counts as unset. */
    public val topics: List<Topic> = emptyList(),
    /** Composer options. */
    public val composer: Composer = Composer(),
    /** Message list options. */
    public val messages: Messages = Messages(),
    /** For example "Usually replies within a few hours". */
    public val replyExpectation: LocalizedText? = null,
    /** The assistant's display name. AI replies are always labelled. */
    public val aiName: String? = null,
) {
    /** Colors and type. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Theme internal constructor(
        public val accent: PingFrontColor? = null,
        public val background: PingFrontColor? = null,
        public val teamBubble: PingFrontColor? = null,
        public val scheme: ColorScheme? = null,
        public val font: FontStyle? = null,
        public val corners: Corners? = null,
        public val density: Density? = null,
    )

    /** Header options. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Header internal constructor(
        public val style: HeaderStyle? = null,
        public val memberId: String? = null,
        public val title: LocalizedText? = null,
        public val showLocalTime: Boolean? = null,
        public val showReplyExpectation: Boolean? = null,
    )

    /** Welcome options. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Welcome internal constructor(
        public val title: LocalizedText? = null,
        public val body: LocalizedText? = null,
        public val showFaces: Boolean? = null,
    )

    /** A topic chip. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Topic internal constructor(
        public val id: String,
        public val label: LocalizedText,
        public val symbol: TopicSymbol,
        public val prefill: LocalizedText? = null,
    )

    /** Composer options. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Composer internal constructor(
        public val placeholder: LocalizedText? = null,
        public val photos: Boolean? = null,
        public val reports: Boolean? = null,
    )

    /** Message list options. */
    @Serializable
    @ConsistentCopyVisibility
    public data class Messages internal constructor(
        public val showNames: Boolean? = null,
        public val showAvatars: Boolean? = null,
        public val timestamps: Timestamps? = null,
    )
}

/** Decodes every section on its own, so one broken section never breaks the whole look. */
internal object ChatLookSerializer : KSerializer<ChatLook> {
    @Serializable
    private data class Wire(
        val theme: ChatLook.Theme? = null,
        val header: ChatLook.Header? = null,
        val welcome: ChatLook.Welcome? = null,
        val topics: List<ChatLook.Topic>? = null,
        val composer: ChatLook.Composer? = null,
        val messages: ChatLook.Messages? = null,
        val replyExpectation: LocalizedText? = null,
        val aiName: String? = null,
    )

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ChatLook) = Wire.serializer().serialize(
        encoder,
        Wire(value.theme, value.header, value.welcome, value.topics, value.composer, value.messages, value.replyExpectation, value.aiName),
    )

    override fun deserialize(decoder: Decoder): ChatLook {
        val obj = decoder.asJson().decodeJsonElement() as? JsonObject ?: return ChatLook()
        val theme = obj.section("theme")
        val header = obj.section("header")
        val welcome = obj.section("welcome")
        val composer = obj.section("composer")
        val messages = obj.section("messages")
        return ChatLook(
            theme = ChatLook.Theme(
                accent = theme.field("theme.accent", PingFrontColor.serializer()),
                background = theme.field("theme.background", PingFrontColor.serializer()),
                teamBubble = theme.field("theme.teamBubble", PingFrontColor.serializer()),
                scheme = theme.field("theme.scheme", SchemeSerializer.strict),
                font = theme.field("theme.font", FontStyleSerializer.strict),
                corners = theme.field("theme.corners", CornersSerializer.strict),
                density = theme.field("theme.density", DensitySerializer.strict),
            ),
            header = ChatLook.Header(
                style = header.field("header.style", HeaderStyleSerializer.strict),
                memberId = header.field("header.memberId", String.serializer()),
                title = header.field("header.title", LocalizedTextSerializer),
                showLocalTime = header.field("header.showLocalTime", Boolean.serializer()),
                showReplyExpectation = header.field("header.showReplyExpectation", Boolean.serializer()),
            ),
            welcome = ChatLook.Welcome(
                title = welcome.field("welcome.title", LocalizedTextSerializer),
                body = welcome.field("welcome.body", LocalizedTextSerializer),
                showFaces = welcome.field("welcome.showFaces", Boolean.serializer()),
            ),
            topics = (obj.field("topics", LossyListSerializer(ChatLook.Topic.serializer())) ?: emptyList()).take(MAX_TOPICS),
            composer = ChatLook.Composer(
                placeholder = composer.field("composer.placeholder", LocalizedTextSerializer),
                photos = composer.field("composer.photos", Boolean.serializer()),
                reports = composer.field("composer.reports", Boolean.serializer()),
            ),
            messages = ChatLook.Messages(
                showNames = messages.field("messages.showNames", Boolean.serializer()),
                showAvatars = messages.field("messages.showAvatars", Boolean.serializer()),
                timestamps = messages.field("messages.timestamps", TimestampsSerializer.strict),
            ),
            replyExpectation = obj.field("replyExpectation", LocalizedTextSerializer),
            aiName = obj.field("aiName", String.serializer()),
        )
    }

    private const val MAX_TOPICS = 6

    private fun JsonObject.section(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    /**
     * Decodes one field; a bad value drops only that field (loudly in debug builds). Enum fields use the
     * strict serializers: a value a newer dashboard added decodes as unset, so the app's code value applies.
     */
    private fun <T> JsonObject.field(path: String, serializer: KSerializer<T>): T? {
        val element = this[path.substringAfterLast('.')] ?: return null
        if (element is kotlinx.serialization.json.JsonNull) return null
        return try {
            WireJson.decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            DecodeLog.dropped("look field $path", e)
            null
        } catch (e: IllegalArgumentException) {
            DecodeLog.dropped("look field $path", e)
            null
        }
    }
}

/** What the SDK reports about the host app, so the dashboard's look editor starts from the real app. */
@Serializable
internal data class HostLook(
    val appName: String,
    val accent: PingFrontColor? = null,
    val background: PingFrontColor? = null,
    val font: String? = null,
    val iconPng: String? = null,
)

private const val HEX_DIGITS = 6
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE = 0xFF
private const val MAX = 255.0

/** WCAG flare term in the contrast ratio. */
private const val FLARE = 0.05
