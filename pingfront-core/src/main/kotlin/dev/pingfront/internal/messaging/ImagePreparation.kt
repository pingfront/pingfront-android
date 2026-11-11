package dev.pingfront.internal.messaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import dev.pingfront.PingFrontException
import dev.pingfront.internal.wire.Limits
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A JPEG ready to upload: at most 2 MB and 2400 px on the long edge. */
internal class PreparedImage(val data: ByteArray, val width: Int, val height: Int, val sha256: String)

internal object ImagePreparation {
    /**
     * Re-encodes any decodable image (JPEG, PNG, HEIC, WebP) as an upright JPEG within the limits.
     * CPU work: call it on a background dispatcher.
     */
    suspend fun prepare(input: ByteArray, maxEdge: Int = Limits.IMAGE_MAX_EDGE, maxBytes: Int = Limits.IMAGE_BYTES): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PingFrontException.InvalidInput("This file is not an image.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw PingFrontException.InvalidInput("This image could not be read.")
        val upright = rotate(decoded, orientation(input))
        var edge = minOf(maxEdge, max(upright.width, upright.height))
        // Small images are encoded as they are; large ones shrink until they fit (never below 320 px).
        val floor = minOf(320, edge)
        while (edge >= floor) {
            currentCoroutineContext().ensureActive()
            val scaled = scale(upright, edge)
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (bytes.size <= maxBytes) return PreparedImage(bytes, scaled.width, scaled.height, sha256(bytes))
            }
            if (edge == floor) break
            edge = maxOf(edge * 3 / 4, floor)
        }
        throw PingFrontException.InvalidInput("This image is too large to send.")
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun scale(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val factor = edge.toDouble() / longest
        return Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * factor).toInt()), max(1, (bitmap.height * factor).toInt()), true)
    }

    private fun orientation(input: ByteArray): Int = try {
        ExifInterface(input.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: java.io.IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(QUARTER)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(HALF)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(THREE_QUARTERS)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

private val QUALITIES = intArrayOf(85, 72, 60, 48)
private const val QUARTER = 90f
private const val HALF = 180f
private const val THREE_QUARTERS = 270f
