package dev.pingfront.internal.net

import dev.pingfront.RateScope
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.AttachmentUrlResponse
import dev.pingfront.internal.wire.CardActionRequest
import dev.pingfront.internal.wire.CreateReportRequest
import dev.pingfront.internal.wire.CreateSessionRequest
import dev.pingfront.internal.wire.CreateSessionResponse
import dev.pingfront.internal.wire.CreateUploadRequest
import dev.pingfront.internal.wire.CreateUploadResponse
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.ListenedMomentsResponse
import dev.pingfront.internal.wire.MessagesPage
import dev.pingfront.internal.wire.MomentRequest
import dev.pingfront.internal.wire.MomentResponse
import dev.pingfront.internal.wire.PendingTeamActionsResponse
import dev.pingfront.internal.wire.ReadRequest
import dev.pingfront.internal.wire.ReadResponse
import dev.pingfront.internal.wire.RefreshSessionRequest
import dev.pingfront.internal.wire.RefreshSessionResponse
import dev.pingfront.internal.wire.SendMessageRequest
import dev.pingfront.internal.wire.SendMessageResponse
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.StoreActionResultRequest
import dev.pingfront.internal.wire.StoreActionResultResponse
import dev.pingfront.internal.wire.TeamActionAck
import dev.pingfront.internal.wire.ThreadResponse
import dev.pingfront.internal.wire.UnreadResponse
import dev.pingfront.internal.wire.UpdateMeRequest
import dev.pingfront.internal.wire.UpdateMeResponse
import dev.pingfront.internal.wire.WireJson
import java.net.URLEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How a route authenticates. */
internal enum class Auth {
    /** `PF-App-Key` only (sessions, config, logout, erase). */
    AppKeyOnly,

    /** `PF-App-Key` plus `Authorization: Bearer`, with one refresh-and-retry on 401. */
    Bearer,
}

/** A 204 response. */
internal object NoContent

/** `GET /config` with an ETag: either the cached body is still current, or a fresh config. */
internal sealed interface ConfigFetch {
    data object NotModified : ConfigFetch

    class Fresh(val config: AppConfig, val body: String, val etag: String?) : ConfigFetch
}

/** Decodes a response body (run on the IO dispatcher). */
internal fun interface ResponseDecoder<R> {
    fun decode(status: Int, headers: Map<String, String>, body: ByteArray): R
}

/**
 * One API route with everything the client needs: method, encoded path, query, body, auth,
 * whether a retry is safe, the rate-limit scope and the response decoder. Only [Endpoints] builds them.
 */
internal class Endpoint<R> internal constructor(
    /** A stable name for logs (never the URL). */
    val name: String,
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: Body? = null,
    val auth: Auth,
    /** Safe to retry automatically after a network failure or 5xx. */
    val idempotent: Boolean,
    val rateScope: RateScope? = null,
    val acceptsNotModified: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    /** Why a session would be created for a bearer route. */
    val reason: SessionReason = SessionReason.ChatOpened,
    val decoder: ResponseDecoder<R>,
) {
    /** A request body, encoded lazily on the IO dispatcher. */
    class Body(val encode: () -> JsonElement)

    /** `/thread*` routes carry `PF-Thread-Rev`. */
    val threadScoped: Boolean get() = path == "thread" || path.startsWith("thread/")
}

/** One factory per API route. The only place paths are built. */
internal object Endpoints {
    /** The server's page size when a request names none. */
    private const val DEFAULT_PAGE = 50

    private fun <T> json(serializer: KSerializer<T>) = ResponseDecoder { _, _, body -> WireJson.decodeFromString(serializer, body.decodeToString()) }

    private val noContent = ResponseDecoder { _, _, _ -> NoContent }

    private fun <T> body(serializer: KSerializer<T>, value: T) = Endpoint.Body { WireJson.encodeToJsonElement(serializer, value) }

    private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun createSession(request: CreateSessionRequest) = Endpoint(
        "createSession",
        "POST",
        "sessions",
        body = body(CreateSessionRequest.serializer(), request),
        auth = Auth.AppKeyOnly,
        idempotent = false,
        rateScope = RateScope.Sessions,
        decoder = json(CreateSessionResponse.serializer()),
    )

    fun refreshSession(request: RefreshSessionRequest) = Endpoint(
        "refreshSession",
        "POST",
        "sessions/refresh",
        body = body(RefreshSessionRequest.serializer(), request),
        auth = Auth.AppKeyOnly,
        idempotent = true,
        rateScope = RateScope.Sessions,
        decoder = json(RefreshSessionResponse.serializer()),
    )

    fun updateMe(request: UpdateMeRequest) = Endpoint(
        "updateMe",
        "PATCH",
        "me",
        body = Endpoint.Body { request.toJson() },
        auth = Auth.Bearer,
        idempotent = true,
        reason = SessionReason.Prepare,
        decoder = json(UpdateMeResponse.serializer()),
    )

    fun logout(credentials: InstallCredentials) = Endpoint(
        "logout",
        "POST",
        "me/logout",
        body = body(InstallCredentials.serializer(), credentials),
        auth = Auth.AppKeyOnly,
        idempotent = true,
        decoder = noContent,
    )

    fun eraseMe(credentials: InstallCredentials) = Endpoint(
        "eraseMe",
        "DELETE",
        "me",
        body = body(InstallCredentials.serializer(), credentials),
        auth = Auth.AppKeyOnly,
        idempotent = true,
        decoder = noContent,
    )

    /**
     * `GET /me/export`: the person's `PersonExport`, returned as the server's JSON text (handed to the host app
     * unchanged). Only a JSON object naming its `format` is accepted. An explicit host call, like `prepare()`.
     */
    fun exportMe() = Endpoint(
        "exportMe",
        "GET",
        "me/export",
        auth = Auth.Bearer,
        idempotent = true,
        rateScope = RateScope.Exports,
        reason = SessionReason.Prepare,
        decoder = ResponseDecoder { _, _, body ->
            val text = body.decodeToString()
            val format = (WireJson.parseToJsonElement(text) as? JsonObject)?.get("format") as? JsonPrimitive
            if (format == null || !format.isString) throw SerializationException("not a PersonExport")
            text
        },
    )

    /** Published config (key only, ETag), or the draft for a test device (bearer). */
    fun config(draft: Boolean, etag: String?) = Endpoint(
        "config", "GET", "config",
        query = if (draft) mapOf("draft" to "1") else emptyMap(),
        auth = if (draft) Auth.Bearer else Auth.AppKeyOnly,
        idempotent = true,
        acceptsNotModified = true,
        headers = if (!draft && etag != null) mapOf(Headers.IF_NONE_MATCH to etag) else emptyMap(),
        decoder = ResponseDecoder { status, headers, body ->
            if (status == ApiClient.HTTP_NOT_MODIFIED) {
                ConfigFetch.NotModified
            } else {
                val text = body.decodeToString()
                ConfigFetch.Fresh(WireJson.decodeFromString(AppConfig.serializer(), text), text, headers[Headers.ETAG])
            }
        },
    )

    fun thread() = Endpoint("thread", "GET", "thread", auth = Auth.Bearer, idempotent = true, decoder = json(ThreadResponse.serializer()))

    fun messagesBefore(seq: Int, limit: Int) = Endpoint(
        "messagesBefore",
        "GET",
        "thread/messages",
        // The server's default page is 50: only another size is sent (the shared thread-reset scenario).
        query = if (limit == DEFAULT_PAGE) mapOf("before" to seq.toString()) else mapOf("before" to seq.toString(), "limit" to limit.toString()),
        auth = Auth.Bearer,
        idempotent = true,
        decoder = json(MessagesPage.serializer()),
    )

    fun messagesAfter(seq: Int, limit: Int) = Endpoint(
        "messagesAfter",
        "GET",
        "thread/messages",
        query = mapOf("after" to seq.toString(), "limit" to limit.toString()),
        auth = Auth.Bearer,
        idempotent = true,
        decoder = json(MessagesPage.serializer()),
    )

    /** Idempotent on `clientId`: a retry returns the original message. */
    fun sendMessage(request: SendMessageRequest, reason: SessionReason = SessionReason.ChatOpened) = Endpoint(
        "sendMessage", "POST", "thread/messages", body = body(SendMessageRequest.serializer(), request),
        auth = Auth.Bearer, idempotent = true, rateScope = RateScope.Messages, reason = reason,
        decoder = json(
            SendMessageResponse.serializer(),
        ),
    )

    fun markRead(request: ReadRequest) = Endpoint(
        "markRead",
        "POST",
        "thread/read",
        body = body(ReadRequest.serializer(), request),
        auth = Auth.Bearer,
        idempotent = true,
        decoder = json(ReadResponse.serializer()),
    )

    fun cardAction(cardId: String, request: CardActionRequest) = Endpoint(
        "cardAction",
        "POST",
        "thread/cards/${segment(cardId)}/act",
        body = body(CardActionRequest.serializer(), request),
        auth = Auth.Bearer,
        idempotent = false,
        decoder = noContent,
    )

    fun reportStoreAction(request: StoreActionResultRequest) = Endpoint(
        "reportStoreAction",
        "POST",
        "store-actions",
        body = body(StoreActionResultRequest.serializer(), request),
        auth = Auth.Bearer,
        // Once per clientId on the server: a retry gets the same receipt.
        idempotent = true,
        decoder = json(StoreActionResultResponse.serializer()),
    )

    fun pendingTeamActions() = Endpoint(
        "pendingTeamActions",
        "GET",
        "team-actions",
        auth = Auth.Bearer,
        idempotent = true,
        reason = SessionReason.Prepare,
        decoder = json(PendingTeamActionsResponse.serializer()),
    )

    fun ackTeamAction(id: String, ack: TeamActionAck) = Endpoint(
        "ackTeamAction",
        "POST",
        "team-actions/${segment(id)}/ack",
        body = body(TeamActionAck.serializer(), ack),
        auth = Auth.Bearer,
        // Repeats are no-ops on the server (204).
        idempotent = true,
        reason = SessionReason.Prepare,
        decoder = noContent,
    )

    fun unread() = Endpoint(
        "unread",
        "GET",
        "unread",
        auth = Auth.Bearer,
        idempotent = true,
        reason = SessionReason.Prepare,
        decoder = json(UnreadResponse.serializer()),
    )

    /** Idempotent on `requestId`. */
    fun createUpload(request: CreateUploadRequest, reason: SessionReason = SessionReason.ChatOpened) = Endpoint(
        "createUpload", "POST", "uploads", body = body(CreateUploadRequest.serializer(), request),
        auth = Auth.Bearer, idempotent = true, rateScope = RateScope.Uploads, reason = reason,
        decoder = json(
            CreateUploadResponse.serializer(),
        ),
    )

    fun attachment(id: String) = Endpoint(
        "attachment",
        "GET",
        "attachments/${segment(id)}",
        auth = Auth.Bearer,
        idempotent = true,
        decoder = json(AttachmentUrlResponse.serializer()),
    )

    fun createReport(request: CreateReportRequest) = Endpoint(
        "createReport", "POST", "reports", body = body(CreateReportRequest.serializer(), request),
        auth = Auth.Bearer, idempotent = true, rateScope = RateScope.Messages, reason = SessionReason.Report,
        decoder = json(
            SendMessageResponse.serializer(),
        ),
    )

    /** Key only first; the client retries with a bearer if the server asks for one. */
    fun listenedMoments(auth: Auth = Auth.AppKeyOnly) = Endpoint(
        "listenedMoments",
        "GET",
        "moments/listened",
        auth = auth,
        idempotent = true,
        rateScope = RateScope.Moments,
        reason = SessionReason.Moment,
        decoder = json(ListenedMomentsResponse.serializer()),
    )

    fun moment(request: MomentRequest) = Endpoint(
        "moment", "POST", "moments", body = body(MomentRequest.serializer(), request),
        auth = Auth.Bearer, idempotent = true, rateScope = RateScope.Moments, reason = SessionReason.Moment,
        decoder = json(
            MomentResponse.serializer(),
        ),
    )

    /** The live socket path (the URL is built by the client: `liveUrl` from config, or the base URL with ws). */
    const val LIVE_PATH: String = "live"
}
