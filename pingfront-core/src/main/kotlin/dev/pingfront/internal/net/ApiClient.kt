package dev.pingfront.internal.net

import dev.pingfront.ErrorCode
import dev.pingfront.FieldError
import dev.pingfront.PingFrontException
import dev.pingfront.RateScope
import dev.pingfront.ServerError
import dev.pingfront.internal.runtime.Backoff
import dev.pingfront.internal.runtime.CooldownBook
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.wire.ErrorEnvelope
import dev.pingfront.internal.wire.ProtocolVersion
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

private val log = Log.of(Log.Category.Network)

/** SDK name and version, sent as `PF-SDK` and in `Device.sdk`. */
internal object SdkVersion {
    const val NAME: String = "pingfront-android"
    const val CURRENT: String = dev.pingfront.core.BuildConfig.SDK_VERSION
    const val USER_AGENT: String = "$NAME/$CURRENT"
}

/** Header names, in one place. */
internal object Headers {
    const val APP_KEY = "PF-App-Key"
    const val SDK = "PF-SDK"
    const val WRAPPER = "PF-Wrapper"
    const val PROTOCOL = "PF-Protocol"
    const val THREAD_REV = "PF-Thread-Rev"
    const val AUTHORIZATION = "Authorization"
    const val USER_AGENT = "User-Agent"
    const val ACCEPT = "Accept"
    const val CONTENT_TYPE = "Content-Type"
    const val RETRY_AFTER = "Retry-After"
    const val IF_NONE_MATCH = "If-None-Match"
    const val ETAG = "etag"
    const val WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol"
    const val JSON = "application/json"
}

/** Supplies bearer tokens (the session store). */
internal interface TokenProvider {
    suspend fun token(reason: SessionReason): String

    /** A token for a call that belongs to session [expected] (null: any); a changed session throws instead. */
    suspend fun token(reason: SessionReason, expected: Int?): String = token(reason)

    suspend fun invalidate(token: String)

    /** A bearer route answered 401 `session_revoked` for [token]. */
    suspend fun revoked(token: String) = invalidate(token)

    /** The session generation (changes on logout, erase and revocation). */
    suspend fun generation(): Int = 0
}

/**
 * Bearer requests on top of [ApiClient]: owns getting the token (creating the session lazily) and
 * the one retry after a 401 on every bearer route: `unauthorized` refreshes the token;
 * `session_revoked` (from any route, review finding CQ-M1) runs the revocation path, which retries once
 * through a refresh; a refresh that is refused with `session_revoked` wipes local state and fails the
 * request, and the next request starts a new guest (shared scenario auth-refresh-revoked).
 */
internal class AuthorizedClient(val api: ApiClient, private val tokens: TokenProvider) {
    /**
     * Sends [endpoint] with a bearer token. With [generation], the request belongs to that session: if a
     * logout, erase or revocation happened (the token would be the next person's), it is not sent and
     * [PingFrontException.SessionChanged] is thrown instead.
     */
    suspend fun <R> send(endpoint: Endpoint<R>, generation: Int? = null): R {
        if (endpoint.auth == Auth.AppKeyOnly) return api.send(endpoint)
        val token = bearer(endpoint, generation)
        return try {
            api.send(endpoint, token)
        } catch (e: PingFrontException) {
            when (e.code) {
                ErrorCode.Unauthorized -> tokens.invalidate(token)
                ErrorCode.SessionRevoked -> tokens.revoked(token)
                else -> throw e
            }
            api.send(endpoint, bearer(endpoint, generation))
        }
    }

    private suspend fun bearer(endpoint: Endpoint<*>, generation: Int?): String {
        // The generation is checked before a session is created for this call, and again after (a refresh in flight).
        val token = tokens.token(endpoint.reason, generation)
        if (generation != null && tokens.generation() != generation) throw PingFrontException.SessionChanged()
        return token
    }

    val cooldowns: dev.pingfront.internal.runtime.CooldownBook get() = api.cooldowns

    val threadRev: AtomicReference<String?> get() = api.threadRev

    fun liveUrl(override: String?): String = api.liveUrl(override)

    suspend fun putPresigned(data: ByteArray, url: String, headers: Map<String, String>) = api.putPresigned(data, url, headers)
}

/**
 * The only request path: [send] takes a typed [Endpoint]. It owns headers (`PF-App-Key`, `PF-SDK`,
 * `PF-Protocol`, bearer, `PF-Thread-Rev`), the one 401 refresh-and-retry for bearer routes, scoped
 * cooldowns, retries for idempotent routes, and encoding and decoding on the IO dispatcher.
 * Logs carry the endpoint name, status and code only: never URLs, tokens, bodies or server messages.
 */
internal class ApiClient(
    val baseUrl: String,
    private val appKey: String,
    val transport: HttpTransport,
    val cooldowns: CooldownBook,
    private val io: CoroutineDispatcher,
    private val verbose: Boolean = false,
    private val jitter: () -> Double = { 0.0 },
    /** `PF-Wrapper`, sent on every request when a React Native or Flutter bridge configured the SDK. */
    private val wrapper: String? = null,
) {
    /** Told after every request whether the server was reached (`true`) or the device looked offline (`false`). */
    @Volatile var reachability: ((Boolean) -> Unit)? = null

    /** The last thread revision seen (`thread.rev`, `hello.rev` or the `PF-Thread-Rev` header). */
    val threadRev: AtomicReference<String?> = AtomicReference(null)

    /**
     * Sends [endpoint] with [token] (required for bearer routes; see [AuthorizedClient]). Retries
     * idempotent routes after network failures and 5xx; never retries other routes.
     */
    suspend fun <R> send(endpoint: Endpoint<R>, token: String? = null): R {
        endpoint.rateScope?.let { scope -> cooldowns.remaining(scope)?.let { throw PingFrontException.CoolingDown(scope, it) } }
        check(endpoint.auth == Auth.AppKeyOnly || token != null) { "${endpoint.name} needs a bearer token" }
        val body = endpoint.body?.let { b ->
            withContext(io) { WireJson.encodeToString(JsonElement.serializer(), b.encode()).encodeToByteArray() }
        }
        var attempt = 0
        while (true) {
            try {
                return execute(endpoint, body, token)
            } catch (e: PingFrontException) {
                val retryable = endpoint.idempotent && attempt < MAX_RETRIES &&
                    (e is PingFrontException.Network || (e is PingFrontException.Server && e.error.status >= HTTP_SERVER_ERROR))
                if (!retryable) throw e
                delay(Backoff.Writes.delay(attempt, jitter()) / RETRY_BACKOFF_DIVISOR)
                attempt += 1
            }
        }
    }

    /** The headers every request carries. */
    private val fixedHeaders: Map<String, String> = buildMap {
        put(Headers.APP_KEY, appKey)
        put(Headers.SDK, SdkVersion.USER_AGENT)
        put(Headers.PROTOCOL, ProtocolVersion.CURRENT)
        wrapper?.let { put(Headers.WRAPPER, it) }
        put(Headers.USER_AGENT, SdkVersion.USER_AGENT)
        put(Headers.ACCEPT, Headers.JSON)
    }

    private suspend fun <R> execute(endpoint: Endpoint<R>, body: ByteArray?, token: String?): R {
        val headers = buildMap {
            putAll(fixedHeaders)
            if (token != null) put(Headers.AUTHORIZATION, "Bearer $token")
            if (endpoint.threadScoped) threadRev.get()?.let { put(Headers.THREAD_REV, it) }
            putAll(endpoint.headers)
        }
        val request = HttpRequest(
            endpoint.method,
            url(endpoint.path, endpoint.query),
            headers,
            body,
            if (body !=
                null
            ) {
                "application/json"
            } else {
                null
            },
        )
        val response = try {
            transport.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (isOffline(e)) reachability?.invoke(false)
            throw PingFrontException.Network(offline = isOffline(e), cause = e)
        }
        reachability?.invoke(true)
        if (verbose) log.d("${endpoint.name} -> ${response.status}")
        response.header(Headers.THREAD_REV)?.let { threadRev.set(it) }
        val ok = response.status in HTTP_OK || (endpoint.acceptsNotModified && response.status == HTTP_NOT_MODIFIED)
        if (!ok) {
            val error = withContext(io) { serverError(response.status, response.body, response.header(Headers.RETRY_AFTER)) }
            if (response.status == HTTP_TOO_MANY) cooldowns.record(error.scope ?: endpoint.rateScope ?: RateScope.Messages, error.retryAfter)
            val failure = PingFrontException.Server(error)
            log.i("${endpoint.name} failed: ${failure.logDescription}")
            throw failure
        }
        return try {
            withContext(io) { endpoint.decoder.decode(response.status, response.headers, response.body) }
        } catch (e: SerializationException) {
            log.e("${endpoint.name}: the response could not be read.")
            throw PingFrontException.InvalidResponse("Unexpected response from ${endpoint.name}")
        } catch (e: IllegalArgumentException) {
            log.e("${endpoint.name}: the response could not be read.")
            throw PingFrontException.InvalidResponse("Unexpected response from ${endpoint.name}")
        }
    }

    /** `PUT` raw bytes to the presigned URL from `POST /uploads`, with the server's headers exactly. */
    suspend fun putPresigned(data: ByteArray, url: String, headers: Map<String, String>) {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        val response = try {
            transport.execute(HttpRequest("PUT", url, headers, data, contentType, callTimeoutSeconds = UPLOAD_TIMEOUT_S))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The storage host, never the URL (it carries a signature).
            log.d("upload PUT failed: ${e.javaClass.simpleName}")
            throw PingFrontException.Network(offline = isOffline(e), cause = e)
        }
        if (response.status !in HTTP_OK) {
            log.d("upload PUT -> ${response.status}")
            throw PingFrontException.Server(serverError(response.status, response.body, null))
        }
    }

    private fun url(path: String, query: Map<String, String>): String {
        val base = baseUrl.trimEnd('/') + "/" + path
        if (query.isEmpty()) return base
        return base + "?" + query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
    }

    /**
     * The live socket URL: `liveUrl` from the config, or the base URL with a ws scheme. A loopback `liveUrl`
     * (`localhost`) is ignored when the base URL is not loopback: a device can't reach the server's own loopback
     * (a local server seen from the emulator as 10.0.2.2 advertises `ws://localhost`).
     */
    fun liveUrl(override: String?): String {
        if (override != null && !(isLoopback(override) && !isLoopback(url(Endpoints.LIVE_PATH, emptyMap())))) return override
        val http = url(Endpoints.LIVE_PATH, emptyMap())
        return when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> http
        }
    }

    companion object {
        /** True for `localhost`, `127.0.0.0/8` and `::1` hosts. */
        fun isLoopback(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return false
            return host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "::1"
        }

        const val MAX_RETRIES: Int = 2
        private const val HTTP_TOO_MANY = 429
        private val HTTP_OK = 200..299
        const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_SERVER_ERROR = 500
        private const val RETRY_BACKOFF_DIVISOR = 4

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

        /** Photo uploads (up to 2 MiB) may take longer than API calls on slow links. */
        const val UPLOAD_TIMEOUT_S: Long = 120

        /**
         * True when the device appears to be offline. A timeout (`InterruptedIOException`) is not offline:
         * the server was reachable but slow (review CQ-L5), so the UI does not claim "offline".
         */
        fun isOffline(e: IOException): Boolean = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES).any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException
        }

        private const val MAX_CAUSES = 4

        /** Builds a [ServerError] from a body, falling back to the HTTP status when it is not an envelope. */
        fun serverError(status: Int, body: ByteArray, retryAfterHeader: String?): ServerError {
            val header = retryAfterHeader?.toDoubleOrNull()?.let { (it * 1000).toLong().milliseconds }
            val envelope = try {
                WireJson.decodeFromString(ErrorEnvelope.serializer(), body.decodeToString())
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (envelope != null) {
                val e = envelope.error
                return ServerError(
                    status = status,
                    code = ErrorCode(e.code),
                    message = e.message,
                    docs = e.docs?.let { runCatchingUri(it) },
                    retryAfter = e.retryAfter?.let { (it * 1000).toLong().milliseconds } ?: header,
                    scope = RateScope.fromCode(e.scope),
                    fields = e.fields.map { FieldError(it.path, it.message) },
                )
            }
            // Unknown or missing codes are treated by HTTP status class.
            val code = when {
                status == 401 -> ErrorCode.Unauthorized
                status == 404 -> ErrorCode.NotFound
                status == 413 -> ErrorCode.TooLarge
                status == 429 -> ErrorCode.RateLimited
                status >= 500 -> ErrorCode.Unavailable
                else -> ErrorCode.InvalidRequest
            }
            return ServerError(status, code, "HTTP $status", null, header, null)
        }
    }
}

private fun runCatchingUri(text: String): java.net.URI? = try {
    java.net.URI(text)
} catch (_: java.net.URISyntaxException) {
    null
}
