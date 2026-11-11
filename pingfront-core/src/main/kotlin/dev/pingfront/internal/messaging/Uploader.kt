package dev.pingfront.internal.messaging

import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.wire.CreateUploadRequest
import dev.pingfront.internal.wire.SessionReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uploads photos (`POST /uploads`, then `PUT putUrl` with the returned headers exactly) and resolves
 * short-lived attachment URLs. Owns the attachment URL cache.
 */
internal class Uploader(private val api: AuthorizedClient, private val time: TimeSource) {
    private val mutex = Mutex()
    private val urls = HashMap<String, Pair<String, Duration>>()

    /** Uploads one JPEG and returns its attachment id. */
    suspend fun upload(
        data: ByteArray,
        width: Int,
        height: Int,
        sha256: String,
        requestId: String,
        reason: SessionReason = SessionReason.ChatOpened,
        contentType: String = "image/jpeg",
        generation: Int? = null,
    ): String {
        // The same requestId returns the same attachment, so a retry after a lost response creates nothing new.
        val request = CreateUploadRequest(requestId, contentType, data.size, width.takeIf { it > 0 }, height.takeIf { it > 0 }, sha256)
        val slot = api.send(Endpoints.createUpload(request, reason), generation)
        api.putPresigned(data, slot.putUrl, slot.headers)
        return slot.attachmentId
    }

    /** Logout and erase: the previous person's signed URLs go too (review AN-21). */
    suspend fun clear() = mutex.withLock { urls.clear() }

    /** A fresh URL for an attachment, refetched when the cached one is about to expire. */
    suspend fun url(attachmentId: String): String {
        mutex.withLock { urls[attachmentId] }?.let { (url, until) -> if (time.now() < until) return url }
        val response = api.send(Endpoints.attachment(attachmentId))
        // The device clock may be wrong, so the server's expiry only shortens a short monotonic lifetime
        // (review CQ-L7): a clock that runs behind can never keep an expired URL cached for long.
        val byServer = (response.expiresAt.toEpochMilli() - time.wall().toEpochMilli() - EXPIRY_MARGIN_MS).milliseconds
        val left = byServer.coerceIn(MIN_CACHE, MAX_CACHE)
        mutex.withLock { urls[attachmentId] = response.url to time.now() + left }
        return response.url
    }
}

private const val EXPIRY_MARGIN_MS = 30_000L
private val MIN_CACHE = kotlin.time.Duration.ZERO
private val MAX_CACHE = 5.minutes
