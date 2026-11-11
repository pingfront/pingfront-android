package dev.pingfront.internal.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    /** Overrides the client's whole-call timeout (photo uploads get longer than API calls; review CQ-L5). */
    val callTimeoutSeconds: Long? = null,
)

internal class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** One frame (or the end) of a live socket. */
internal sealed interface SocketFrame {
    data class Text(val text: String) : SocketFrame

    /**
     * The socket failed; [status] is the handshake HTTP status when known (for example 401), and [body] the refused
     * handshake's body (the server's error envelope, `{"error":{"code":"app_paused"}}` on a 403).
     */
    class Failed(val status: Int?, val error: Throwable?, val body: ByteArray? = null) : SocketFrame

    /** The server closed the socket; 4001 means the access token expired. */
    data class Closed(val code: Int) : SocketFrame
}

internal interface LiveSocket {
    val frames: ReceiveChannel<SocketFrame>

    fun send(text: String): Boolean

    fun close()
}

/** The network seam. Production uses OkHttp; tests use a scripted stub or MockWebServer. */
internal interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse

    fun openSocket(url: String, protocols: List<String>, headers: Map<String, String>): LiveSocket

    /**
     * The device's network was lost, came back or changed. Pooled connections belong to the old network: kept, the
     * next request goes out on a dead socket and waits for the call timeout (30 s) before it fails over.
     */
    fun networkChanged() {}
}

/** OkHttp with no cookie jar and no cache. Suspends with cancellation (the call is cancelled too). */
internal class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {
    // OkHttp's guidance for a network change: evict the pool, so the next call opens a connection on the new network.
    override fun networkChanged() = client.connectionPool.evictAll()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val body = request.body?.toRequestBody(request.contentType?.toMediaType())
        val okRequest = Request.Builder()
            .url(request.url)
            .headers(request.headers.toHeaders())
            .method(
                request.method,
                body ?: if (request.method in setOf("POST", "PUT", "PATCH", "DELETE")) ByteArray(0).toRequestBody(null) else null,
            )
            .build()
        val call = client.newCall(okRequest)
        request.callTimeoutSeconds?.let { call.timeout().timeout(it, TimeUnit.SECONDS) }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use { r ->
                                HttpResponse(r.code, r.headers.associate { (k, v) -> k.lowercase() to v }, r.body.bytes())
                            }
                        } catch (e: IOException) {
                            continuation.resumeWithException(e)
                            return
                        }
                        continuation.resume(result) { _, _, _ -> }
                    }
                },
            )
        }
    }

    override fun openSocket(url: String, protocols: List<String>, headers: Map<String, String>): LiveSocket {
        val frames = Channel<SocketFrame>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .header(Headers.WEBSOCKET_PROTOCOL, protocols.joinToString(", "))
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.trySend(SocketFrame.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(CLOSE_NORMAL, null)
                    frames.trySend(SocketFrame.Closed(code))
                    frames.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // A refused handshake carries the server's error envelope: read at most 16 KiB of it, so the refusal
                    // says why. The failure frame goes out whatever reading does, so the connection never waits on it.
                    var body: ByteArray? = null
                    try {
                        body = response?.peekBody(MAX_ERROR_BODY)?.bytes()
                    } catch (_: IOException) {
                        body = null
                    } catch (_: IllegalStateException) {
                        body = null
                    } finally {
                        frames.trySend(SocketFrame.Failed(response?.code, t, body))
                        frames.close()
                    }
                }
            },
        )
        return object : LiveSocket {
            override val frames: ReceiveChannel<SocketFrame> = frames

            override fun send(text: String): Boolean = socket.send(text)

            override fun close() {
                socket.close(CLOSE_GOING_AWAY, null)
                frames.close()
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private const val CLOSE_GOING_AWAY = 1001

/** The most of a refused handshake's error body read (the error envelope is a few hundred bytes). */
private const val MAX_ERROR_BODY = 16_384L
private const val CALL_TIMEOUT_S = 30L
private const val CONNECT_TIMEOUT_S = 15L
private const val READ_TIMEOUT_S = 30L

/** OkHttp pings the socket so a dead connection fails fast (with the read timeout). */
private const val PING_INTERVAL_S = 20L
private const val CLOSE_NORMAL = 1000
