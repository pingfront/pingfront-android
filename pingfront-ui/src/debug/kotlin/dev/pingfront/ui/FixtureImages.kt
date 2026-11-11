package dev.pingfront.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * Drawn stand-ins for fixture avatars, photos and the app icon (`fixture://…`), so previews and
 * snapshots never touch the network. Debug builds only.
 */
internal object FixtureImages {
    private val palettes = mapOf(
        "deniz" to intArrayOf(0xFF1B6E66.toInt(), 0xFF7CD3C4.toInt()),
        "mara" to intArrayOf(0xFF8E3B5C.toInt(), 0xFFF2A7C3.toInt()),
        "lea" to intArrayOf(0xFF1F5E3A.toInt(), 0xFF9AD3A8.toInt()),
        "theo" to intArrayOf(0xFF39457E.toInt(), 0xFFA9B5F2.toInt()),
    )

    /** The shared CC0 team portraits (brand/avatars, Open Peeps), copied into the debug resources. */
    private val portraits = mapOf(
        "deniz" to R.drawable.pf_fixture_avatar_deniz,
        "mara" to R.drawable.pf_fixture_avatar_mara,
        "ana" to R.drawable.pf_fixture_avatar_ana,
        "tom" to R.drawable.pf_fixture_avatar_tom,
        "kofi" to R.drawable.pf_fixture_avatar_kofi,
        "lena" to R.drawable.pf_fixture_avatar_lena,
        // People writing in: Notionists line drawings (CC0, brand/avatars), a different style from the team.
        "person-riley" to R.drawable.pf_fixture_avatar_person_riley,
        "person-sam" to R.drawable.pf_fixture_avatar_person_sam,
        "person-jo" to R.drawable.pf_fixture_avatar_person_jo,
    )

    @Volatile private var resources: android.content.res.Resources? = null

    /** Installs the fixtures with the app's resources, so team members show their portraits. */
    fun install(context: android.content.Context) {
        resources = context.applicationContext.resources
        install()
    }

    fun install() {
        ChatImages.fixtures = ::draw
        LinkPreviews.seeded["https://clockwise.app/help"] =
            LinkPreviewData(
                "https://clockwise.app/help",
                "clockwise.app",
                "How widgets refresh",
                "Widgets update every 15 minutes, and right away when you open the app.",
            )
    }

    fun draw(key: String, widthPx: Int): Bitmap? = when {
        key.startsWith("avatar/") -> avatar(key.removePrefix("avatar/"), widthPx.coerceAtLeast(MIN_SIZE))
        key.startsWith("image/") -> photo(key.removePrefix("image/"), widthPx.coerceAtLeast(MIN_SIZE))
        key.startsWith("icon/") -> icon(key.removePrefix("icon/"), widthPx.coerceAtLeast(MIN_SIZE))
        else -> null
    }

    /** Each fixture app's icon: its color and a simple glyph (a clock, a ledger, a wave). */
    private fun icon(app: String, size: Int): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val (dark, light) = when (app) {
            "ledgerly" -> 0xFF1F5E3A.toInt() to 0xFF2E8B57.toInt()
            "driftwood" -> 0xFFB85A3C.toInt() to 0xFFF08A5D.toInt()
            else -> 0xFF00695C.toInt() to 0xFF26A69A.toInt()
        }
        paint.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), light, dark, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.07f
        paint.strokeCap = Paint.Cap.ROUND
        val c = size / 2f
        when (app) {
            "ledgerly" -> {
                canvas.drawRoundRect(RectF(size * 0.27f, size * 0.22f, size * 0.73f, size * 0.78f), size * 0.06f, size * 0.06f, paint)
                listOf(0.38f, 0.5f, 0.62f).forEach { y -> canvas.drawLine(size * 0.36f, size * y, size * 0.64f, size * y, paint) }
            }
            "driftwood" -> {
                val path = android.graphics.Path()
                path.moveTo(size * 0.2f, size * 0.56f)
                path.cubicTo(size * 0.35f, size * 0.36f, size * 0.5f, size * 0.76f, size * 0.65f, size * 0.52f)
                path.cubicTo(size * 0.72f, size * 0.42f, size * 0.78f, size * 0.44f, size * 0.82f, size * 0.48f)
                canvas.drawPath(path, paint)
            }
            else -> {
                canvas.drawCircle(c, c, size * 0.28f, paint)
                canvas.drawLine(c, c, c, size * 0.32f, paint)
                canvas.drawLine(c, c, size * 0.64f, size * 0.58f, paint)
            }
        }
        return bitmap
    }

    /** A team member's portrait from the shared set, else a drawn stand-in. */
    private fun avatar(name: String, size: Int): Bitmap {
        val res = resources
        val id = portraits[name]
        if (res != null && id != null) {
            android.graphics.BitmapFactory.decodeResource(res, id)?.let { return it.scale(size, size) }
        }
        return drawnAvatar(name, size)
    }

    /** A soft portrait: gradient background, a head and shoulders silhouette. */
    private fun drawnAvatar(name: String, size: Int): Bitmap {
        val colors = palettes[name] ?: intArrayOf(0xFF555555.toInt(), 0xFFBBBBBB.toInt())
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors[1], colors[0], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = 0xFFF6E3D3.toInt()
        canvas.drawCircle(size * 0.5f, size * 0.42f, size * 0.2f, paint)
        paint.color = colors[0]
        canvas.drawOval(RectF(size * 0.18f, size * 0.68f, size * 0.82f, size * 1.25f), paint)
        paint.color = 0xFF3B2A22.toInt()
        canvas.drawArc(RectF(size * 0.29f, size * 0.2f, size * 0.71f, size * 0.55f), 180f, 180f, true, paint)
        return bitmap
    }

    /** A phone screenshot-like photo: a widget grid with one glitched tile. */
    private fun photo(key: String, width: Int): Bitmap {
        val height = (width * PHOTO_RATIO).toInt()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF0E4D47.toInt(), 0xFF2A8C80.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        val pad = width * 0.07f
        val tile = (width - pad * 3) / 2
        for (row in 0..1) {
            for (col in 0..1) {
                val left = pad + col * (tile + pad)
                val top = pad * 2 + row * (tile + pad)
                paint.color = if (row == 1 && col == 0) 0xFFE85D75.toInt() else 0xEEFFFFFF.toInt()
                canvas.drawRoundRect(RectF(left, top, left + tile, top + tile), tile * 0.18f, tile * 0.18f, paint)
                paint.color = 0xFF0E4D47.toInt()
                paint.textSize = tile * 0.28f
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(
                    if (row == 1 &&
                        col == 0
                    ) {
                        "--:--"
                    } else {
                        "07:${(row * 2 + col) * 15 % 60}".padEnd(5, '0')
                    },
                    left + tile * 0.1f,
                    top + tile * 0.6f,
                    paint,
                )
            }
        }
        paint.color = 0x55FFFFFF
        canvas.drawRect(0f, height * 0.78f, width.toFloat(), height * 0.8f, paint)
        if (key.isEmpty()) return bitmap
        return bitmap
    }

    private const val MIN_SIZE = 96
    private const val PHOTO_RATIO = 1.2f
}
