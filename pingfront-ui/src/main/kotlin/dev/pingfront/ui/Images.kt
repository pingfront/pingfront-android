package dev.pingfront.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pingfront.WebLinks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Chat images: decoded at display size and kept in an in-memory LRU only (private images never
 * touch the disk). Entries are invalidated per key; the whole cache is cleared on logout and erase.
 */
internal object ChatImages {
    private const val MEMORY_FRACTION = 16
    private const val BYTES_PER_KB = 1024
    private const val TIMEOUT_MS = 15_000

    /** Blocking reads and decodes run on the SDK's injected dispatcher (review AN-20). */
    val io: CoroutineDispatcher get() = dev.pingfront.PingFront.instance.blockingDispatcher()

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / BYTES_PER_KB / MEMORY_FRACTION).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = kb(value)
    }

    private fun kb(value: ImageBitmap): Int = value.width * value.height * 4 / BYTES_PER_KB

    /** One big photo never evicts every avatar and thumbnail: entries over a quarter of the cache are not kept (review CQ-M14). */
    private fun keep(key: String, image: ImageBitmap) {
        if (kb(image) <= cache.maxSize() / LARGEST_SHARE) cache.put(key, image)
    }

    /** Debug builds register drawn fixture images (`fixture://…`) here. */
    @Volatile var fixtures: ((key: String, widthPx: Int) -> Bitmap?)? = null

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** Previews and snapshots: a drawn fixture image right now (no async load), or `null` for other URLs. */
    fun fixtureNow(key: String, url: String?, widthPx: Int): ImageBitmap? {
        if (url == null || !url.startsWith(FIXTURE)) return null
        return fixtures?.invoke(url.removePrefix(FIXTURE), widthPx)?.asImageBitmap()?.also { keep(cacheKey(key, widthPx), it) }
    }

    fun invalidate(key: String) {
        cache.snapshot().keys.filter { it.startsWith("$key@") }.forEach { cache.remove(it) }
    }

    fun clear() = cache.evictAll()

    private fun cacheKey(key: String, widthPx: Int) = "$key@${bucket(widthPx)}"

    private fun bucket(widthPx: Int): Int = (widthPx / BUCKET + 1) * BUCKET

    suspend fun load(key: String, url: String?, widthPx: Int, refresh: (suspend () -> String?)? = null): ImageBitmap? {
        cache.get(cacheKey(key, widthPx))?.let { return it }
        val bitmap = withContext(io) {
            if (url != null && url.startsWith(FIXTURE)) return@withContext fixtures?.invoke(url.removePrefix(FIXTURE), widthPx)
            val bytes = url?.let { fetch(it) } ?: refresh?.invoke()?.let { fetch(it) } ?: return@withContext null
            decode(bytes, widthPx)
        } ?: return null
        return bitmap.asImageBitmap().also { keep(cacheKey(key, widthPx), it) }
    }

    suspend fun decodeLocal(key: String, bytes: ByteArray, widthPx: Int): ImageBitmap? {
        cache.get(cacheKey(key, widthPx))?.let { return it }
        val bitmap = withContext(io) { decode(bytes, widthPx) } ?: return null
        return bitmap.asImageBitmap().also { keep(cacheKey(key, widthPx), it) }
    }

    /** Decodes with `inSampleSize` so the bitmap is about [widthPx] wide, never full size. */
    fun decode(bytes: ByteArray, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Fetches an avatar, icon or attachment. Only http(s) URLs (the server's URLs are not trusted to be web
     * URLs: a `file:` or `jar:` URL used to crash the host app, review CS-9), and at most [MAX_BYTES].
     */
    private fun fetch(url: String): ByteArray? {
        if (!WebLinks.isWeb(url)) return null
        return try {
            val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
            connection.run {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                try {
                    if (responseCode in OK_RANGE) inputStream.use { readCapped(it) } else null
                } finally {
                    disconnect()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readCapped(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
    }

    const val FIXTURE: String = "fixture://"
    private const val LARGEST_SHARE = 4
    private const val BUFFER_BYTES = 16 * 1024
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val BUCKET = 120
    private val OK_RANGE = 200..299
}

/** An image for [key] at [widthPx], loaded once per key and size. */
@Composable
internal fun rememberChatImage(key: String, url: String?, widthPx: Int, refresh: (suspend () -> String?)? = null): ImageBitmap? {
    val still = LocalChatPreview.current.still
    var image by remember(key, widthPx) {
        mutableStateOf(ChatImages.cached("$key@${(widthPx / 120 + 1) * 120}") ?: if (still) ChatImages.fixtureNow(key, url, widthPx) else null)
    }
    LaunchedEffect(key, url, widthPx) {
        if (image == null) image = ChatImages.load(key, url, widthPx, refresh)
    }
    return image
}
