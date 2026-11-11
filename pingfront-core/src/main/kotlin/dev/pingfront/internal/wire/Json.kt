package dev.pingfront.internal.wire

import dev.pingfront.internal.runtime.Log
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val log = Log.of(Log.Category.Decoding)

/** The JSON configuration for the wire protocol. Unknown keys are ignored; absent optionals stay absent. */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
}

/** ISO-8601 instants with `Z` or `±hh:mm` offsets, with or without fractional seconds. */
internal object InstantSerializer : KSerializer<Instant> {
    private val format: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.pingfront.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())
        ?: throw SerializationException("Not an ISO-8601 date")

    fun parse(text: String): Instant? = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: Exception) {
        null
    }

    fun format(value: Instant): String = format.format(value)
}

/**
 * Decodes a list, dropping elements that fail to decode instead of failing the whole list,
 * so old SDKs keep working when the server adds shapes they do not know.
 */
internal class LossyListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            try {
                json.json.decodeFromJsonElement(element, item)
            } catch (e: SerializationException) {
                DecodeLog.dropped("list element of ${element.descriptor.serialName}", e)
                null
            } catch (e: IllegalArgumentException) {
                DecodeLog.dropped("list element of ${element.descriptor.serialName}", e)
                null
            }
        }
    }
}

/** A string-backed wire enum that decodes values it does not know into [fallback]. */
internal open class TolerantEnumSerializer<E : Enum<E>>(
    name: String,
    private val names: Map<E, String>,
    private val fallback: E,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.pingfront.$name", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(names.getValue(value))

    override fun deserialize(decoder: Decoder): E {
        val raw = decoder.decodeString()
        return names.entries.firstOrNull { it.value == raw }?.key ?: fallback
    }

    /** The value for [raw], or `null` when this SDK version does not know it. */
    fun known(raw: String): E? = names.entries.firstOrNull { it.value == raw && it.value != UNKNOWN_WIRE }?.key

    /**
     * A strict variant for fields where "unset" lets another layer decide (the chat look): an unknown
     * value fails, so the field decodes as unset instead of as a default (review CQ-M5).
     */
    val strict: KSerializer<E> get() = StrictEnum(this)

    private class StrictEnum<E : Enum<E>>(private val base: TolerantEnumSerializer<E>) : KSerializer<E> {
        override val descriptor: SerialDescriptor get() = base.descriptor

        override fun serialize(encoder: Encoder, value: E) = base.serialize(encoder, value)

        override fun deserialize(decoder: Decoder): E {
            val raw = decoder.decodeString()
            return base.known(raw) ?: throw SerializationException("unknown value")
        }
    }
}

/** The wire name kept for an enum's `Unknown` case, so a cached value round-trips. */
internal const val UNKNOWN_WIRE: String = "unknown"

/** Tolerant decoding drops what it cannot read; this says so in debug logs (loudly for invalid configuration values). */
internal object DecodeLog {
    fun dropped(what: String, error: Exception) {
        log.d("Dropped an unreadable $what (${error.javaClass.simpleName}).")
    }

    fun invalid(message: String) {
        if (dev.pingfront.internal.debug.DebugGate.isEnabled()) {
            log.e(
                message,
            )
        } else {
            log.d(message)
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun Decoder.asJson(): JsonDecoder = this as? JsonDecoder ?: throw SerializationException("JSON only")

internal fun Encoder.asJson(): JsonEncoder = this as? JsonEncoder ?: throw SerializationException("JSON only")
