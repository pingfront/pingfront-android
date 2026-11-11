@file:UseSerializers(InstantSerializer::class)

package dev.pingfront.internal.wire

import dev.pingfront.model.ChatLook
import dev.pingfront.model.Entitlement
import dev.pingfront.model.HostLook
import dev.pingfront.model.IdentityProblem
import dev.pingfront.model.Message
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.PersonSummary
import dev.pingfront.model.SendPolicy
import dev.pingfront.model.TeamMember
import dev.pingfront.model.ThreadStatus
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Internal mirrors of the API's OpenAPI schemas for requests and responses.

internal object Limits {
    const val TEXT_CHARS = 4000
    const val IMAGES_PER_MESSAGE = 6
    const val IMAGE_BYTES = 2 * 1024 * 1024
    const val IMAGE_MAX_EDGE = 2400
    const val TRAITS = 30
    const val TRAIT_KEY_CHARS = 64
    const val TRAIT_VALUE_CHARS = 512
    const val USER_ID_CHARS = 255
    const val NAME_CHARS = 200
    const val EMAIL_CHARS = 254
    const val IDENTITY_TOKEN_CHARS = 4096
    const val PAGE_SIZE = 50
    const val PAGE_SIZE_MAX = 200
    const val REPORT_LOG_BYTES = 64 * 1024
    const val MOMENT_PROPS = 20

    /** Limits count Unicode code points on every platform. */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** Cuts [text] to at most [max] code points, never splitting a surrogate pair. */
    fun truncate(text: String, max: Int): String = if (codePoints(text) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
}

/** UTF-8 helpers that never split a character. */
internal object Utf8 {
    private const val CONTINUATION_MASK = 0xC0
    private const val CONTINUATION = 0x80

    /** The last [maxBytes] bytes of [text], starting on a character boundary. */
    fun tail(text: String, maxBytes: Int): String {
        val bytes = text.encodeToByteArray()
        if (bytes.size <= maxBytes) return text
        var start = bytes.size - maxBytes
        // Skip continuation bytes (10xxxxxx) so the cut lands on the first byte of a character.
        while (start < bytes.size && (bytes[start].toInt() and CONTINUATION_MASK) == CONTINUATION) start += 1
        return bytes.copyOfRange(start, bytes.size).decodeToString()
    }
}

internal object ProtocolVersion {
    const val CURRENT = "1.0.0"
}

@Serializable
internal data class Device(
    /** Absent in the partial device sent with refresh and `PATCH /me`. */
    val installId: String? = null,
    val platform: String = "android",
    // Every field is optional so the partial device (`Device.partial()` on the wire) decodes too.
    val sdk: Sdk? = null,
    val app: App? = null,
    val os: String? = null,
    val model: String? = null,
    val locale: String? = null,
    val language: String? = null,
    val timeZone: String? = null,
    /** Sent only with requests the SDK makes anyway; never part of the staged profile (see `ContextReporter`). */
    val context: DeviceContext? = null,
) {
    @Serializable
    data class Sdk(val name: String, val version: String)

    /** [screens]: `AppInfo.screens`, only on the requests `ScreenReporter` picks (the protocol, Saved links). */
    @Serializable
    data class App(val version: String, val build: String, val bundleId: String, val screens: List<WireScreen>? = null)
}

/** `AppScreen` on the wire. */
@Serializable
internal data class WireScreen(val id: String, val title: String? = null)

@Serializable
internal data class Identity(val userId: String, val name: String? = null, val email: String? = null, val token: String? = null)

@Serializable
internal data class PushRegistration(val provider: String, val token: String, val environment: String? = null)

internal enum class SessionReason(val wire: String) {
    ChatOpened("chat_opened"),
    Moment("moment"),
    Prepare("prepare"),
    PushOpened("push_opened"),
    Report("report"),
}

@Serializable
internal data class CreateSessionRequest(
    val device: Device,
    val installSecret: String? = null,
    val identity: Identity? = null,
    val traits: Map<String, String>? = null,
    val entitlement: Entitlement? = null,
    val offers: List<OfferSlot>? = null,
    val hostLook: HostLook? = null,
    val reason: String,
)

@Serializable
internal data class CreateSessionResponse(
    val installId: String? = null,
    val accessToken: String,
    val accessExpiresAt: Instant? = null,
    val accessExpiresIn: Long = 900,
    val installSecret: String,
    val person: PersonSummary,
    val identityProblem: IdentityProblem? = null,
    val testDevice: Boolean = false,
    val storeAccountToken: String? = null,
    /** Which context groups the app collects; absent from older servers (collect every group). */
    val collect: ContextCollection? = null,
)

@Serializable
internal data class InstallCredentials(val installId: String, val installSecret: String)

@Serializable
internal data class RefreshSessionRequest(val installId: String, val installSecret: String, val device: Device? = null)

@Serializable
internal data class RefreshSessionResponse(
    val accessToken: String,
    val accessExpiresAt: Instant? = null,
    val accessExpiresIn: Long = 900,
    val person: PersonSummary,
    val testDevice: Boolean = false,
    val storeAccountToken: String? = null,
    val collect: ContextCollection? = null,
)

/** `PATCH /me`. Only changed fields are present; `push` can be an explicit `null` to unregister. */
internal data class UpdateMeRequest(
    val device: Device? = null,
    val identity: Identity? = null,
    val traits: Map<String, String>? = null,
    val entitlement: Entitlement? = null,
    val offers: List<OfferSlot>? = null,
    val push: PushChange? = null,
    val hostLook: HostLook? = null,
) {
    sealed interface PushChange {
        data class Set(val registration: PushRegistration) : PushChange

        data object Clear : PushChange
    }

    val isEmpty: Boolean get() = device == null && identity == null && traits == null && entitlement == null && offers == null &&
        push == null &&
        hostLook == null

    fun toJson(): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        fun <T> put(key: String, value: T?, serializer: KSerializer<T>) {
            if (value != null) fields[key] = WireJson.encodeToJsonElement(serializer, value)
        }
        put("device", device, Device.serializer())
        put("identity", identity, Identity.serializer())
        traits?.let { map -> fields["traits"] = JsonObject(map.mapValues { JsonPrimitive(it.value) }) }
        put("entitlement", entitlement, Entitlement.serializer())
        offers?.let {
            fields["offers"] =
                WireJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(OfferSlot.serializer()), it)
        }
        put("hostLook", hostLook, HostLook.serializer())
        when (val change = push) {
            is PushChange.Set -> fields["push"] = WireJson.encodeToJsonElement(PushRegistration.serializer(), change.registration)
            PushChange.Clear -> fields["push"] = JsonNull
            null -> Unit
        }
        return JsonObject(fields)
    }
}

@Serializable
internal data class RejectedField(val field: String, val code: String = "", val message: String = "")

@Serializable
internal data class UpdateMeResponse(
    val person: PersonSummary,
    val identityProblem: IdentityProblem? = null,
    val rejected: List<RejectedField> = emptyList(),
)

@Serializable
internal data class ThreadInfo(
    val id: String,
    val status: ThreadStatus = ThreadStatus.Open,
    val lastSeq: Int = 0,
    val readSeq: Int = 0,
    val teamReadSeq: Int = 0,
    val unread: Int = 0,
    val rev: Int? = null,
)

@Serializable
internal data class ThreadResponse(
    val thread: ThreadInfo? = null,
    @Serializable(with = MessageListSerializer::class)
    val messages: List<Message> = emptyList(),
    val hasOlder: Boolean = false,
    @Serializable(with = TeamListSerializer::class)
    val team: List<TeamMember> = emptyList(),
    val replyExpectation: String? = null,
    val policy: SendPolicy = SendPolicy.Open,
)

internal object MessageListSerializer : KSerializer<List<Message>> by LossyListSerializer(Message.serializer())

internal object TeamListSerializer : KSerializer<List<TeamMember>> by LossyListSerializer(TeamMember.serializer())

@Serializable
internal data class MessagesPage(
    @Serializable(with = MessageListSerializer::class)
    val messages: List<Message> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
internal data class CardReply(val messageId: String, val cardId: String, val choiceId: String? = null, val rating: Int? = null)

@Serializable
internal data class SendMessageRequest(
    val clientId: String,
    val text: String? = null,
    val attachmentIds: List<String>? = null,
    val replyToCard: CardReply? = null,
)

@Serializable
internal data class SendMessageResponse(val message: Message, val threadId: String)

@Serializable
internal data class ReadRequest(val upToSeq: Int)

@Serializable
internal data class ReadResponse(val readSeq: Int, val unread: Int)

@Serializable
internal data class CardActionRequest(
    val messageId: String,
    val action: String,
    val choiceId: String? = null,
    val rating: Int? = null,
    /** `LinkFailure` with `action: failed` (link cards only). */
    val failure: String? = null,
    /** One per report, kept across its retries: the server records a repeat once (the protocol, "Retrying writes"). */
    val clientId: String? = null,
)

/** `POST /store-actions`: what a store action did on the device, for the team's receipt line. */
@Serializable
internal data class StoreActionResultRequest(
    val clientId: String,
    val action: String,
    val result: String,
    val cardId: String? = null,
    val messageId: String? = null,
    val productName: String? = null,
)

/** The receipt the server wrote, or null when it wrote none. */
@Serializable
internal data class StoreActionResultResponse(val message: Message? = null)

@Serializable
internal data class UnreadResponse(val unread: Int, val lastSeq: Int = 0, val live: Boolean = false)

@Serializable
internal data class CreateUploadRequest(
    val requestId: String,
    val contentType: String,
    val bytes: Int,
    val width: Int? = null,
    val height: Int? = null,
    val sha256: String,
)

@Serializable
internal data class CreateUploadResponse(
    val attachmentId: String,
    val putUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Instant,
)

@Serializable
internal data class AttachmentUrlResponse(val url: String, val expiresAt: Instant)

@Serializable
internal data class CreateReportRequest(
    val clientId: String,
    val text: String,
    val screenshotIds: List<String> = emptyList(),
    val logTail: String? = null,
    val state: Map<String, String>? = null,
)

@Serializable
internal data class MomentRequest(
    val name: String,
    val props: JsonObject? = null,
    val store: dev.pingfront.StoreMomentProps? = null,
    val occurredAt: Instant? = null,
    val clientId: String,
)

@Serializable
internal data class MomentResponse(val outcome: String = "ignored", val open: Boolean = false)

@Serializable
internal data class ListenedMomentsResponse(val names: List<String> = emptyList(), val storeEvents: List<String> = emptyList())

/** `GET /config`: the public app configuration with the chat look. */
@Serializable
internal data class AppConfig(
    val schema: Int = 1,
    val revision: Int = 0,
    val draft: Boolean = false,
    val appName: String,
    val appIconUrl: String? = null,
    val look: ChatLook = ChatLook(),
    @Serializable(with = TeamListSerializer::class)
    val team: List<TeamMember> = emptyList(),
    val features: Features = Features(),
    val liveUrl: String? = null,
) {
    @Serializable
    data class Features(
        val photos: Boolean = true,
        val reports: Boolean = true,
        val translation: Boolean = false,
        val ai: Boolean = false,
        val appBadge: Boolean = false,
        /** The org is on pingfront's Free plan: the chat's welcome screen shows the "runs on pingfront" line. */
        val branding: Boolean = false,
    )
}

/** The error envelope: `{ "error": { code, message, docs, retryAfter?, scope? } }`. */
@Serializable
internal data class ErrorEnvelope(val error: Body) {
    @Serializable
    data class Body(
        val code: String,
        val message: String = "",
        val docs: String? = null,
        val retryAfter: Double? = null,
        val scope: String? = null,
        val fields: List<Field> = emptyList(),
    )

    @Serializable
    data class Field(val path: String, val message: String = "")
}

/** A team action the host app has to do (`TeamActionPayload`): live event, `GET /v1/team-actions`. */
@Serializable
internal data class TeamActionPayload(
    val id: String,
    val actionId: String,
    val name: String,
    val fields: Map<String, String> = emptyMap(),
    val days: Int? = null,
    val until: Instant? = null,
    val ackRequired: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/** Lossy: one action this SDK cannot decode is dropped, never the whole catch-up. */
internal object TeamActionListSerializer : KSerializer<List<TeamActionPayload>> by LossyListSerializer(TeamActionPayload.serializer())

@Serializable
internal data class PendingTeamActionsResponse(
    @Serializable(with = TeamActionListSerializer::class)
    val actions: List<TeamActionPayload>,
)

/** `POST /v1/team-actions/{id}/ack`; also the stored, not yet sent ack. */
@Serializable
internal data class TeamActionAck(val ok: Boolean, val message: String? = null)

@Serializable
internal data class QueuedTeamActionAck(val id: String, val ack: TeamActionAck)
