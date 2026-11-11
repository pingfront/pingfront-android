package dev.pingfront.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext

/** Finds web links in message text. */
internal object Links {
    private val pattern = Regex("""\bhttps?://[^\s<>"]+[^\s<>".,;:!?)\]]""", RegexOption.IGNORE_CASE)

    fun find(text: String): List<IntRange> = pattern.findAll(text).map { it.range }.toList()

    fun first(text: String): String? = pattern.find(text)?.value?.takeIf { it.startsWith("https://", ignoreCase = true) }

    /** The text with tappable, underlined links in [color]. */
    fun annotate(text: String, color: Color): AnnotatedString {
        val ranges = find(text)
        if (ranges.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
            ranges.forEach { addLink(LinkAnnotation.Url(text.substring(it), style), it.first, it.last + 1) }
        }
    }
}

/** What a link preview shows. */
@Immutable
internal data class LinkPreviewData(val url: String, val site: String, val title: String?, val description: String?)

/**
 * Link previews, fetched on the device only (never through a server), for https links. At most 256 KB
 * of the page is read; results are kept in memory and cleared on logout. Hardened (review CQ-M10):
 * - only for messages a person wrote (a teammate or the person), never for the assistant or plays, so an
 *   automated message cannot make every device fetch a third-party URL;
 * - the host must resolve to public addresses only (no loopback, link-local, private or multicast);
 * - redirects are not followed; no cookies, no cache.
 */
internal object LinkPreviews {
    private const val MAX_BYTES = 256 * 1024
    private const val IPV6_BYTES = 16
    private const val ULA_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC
    private const val TIMEOUT_MS = 8_000
    private const val MAX_TEXT = 160
    private val cache = ConcurrentHashMap<String, LinkPreviewData>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** Debug builds pre-seed previews for fixture links (snapshots never touch the network). */
    val seeded: MutableMap<String, LinkPreviewData> = ConcurrentHashMap()

    fun cached(url: String): LinkPreviewData? = seeded[url] ?: cache[url]

    fun clear() {
        cache.clear()
        failed.clear()
    }

    suspend fun load(url: String): LinkPreviewData? {
        cached(url)?.let { return it }
        if (url in failed) return null
        val data = withContext(ChatImages.io) { fetch(url) }
        if (data == null) failed += url else cache[url] = data
        return data
    }

    /** Messages whose links may be previewed: written by a person, not by the assistant or a play. */
    fun allowedFor(message: dev.pingfront.model.Message): Boolean =
        message.author.kind != dev.pingfront.model.Author.Kind.Ai && message.ping?.kind != dev.pingfront.model.PingOrigin.Kind.Play

    private fun isPublic(address: java.net.InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return false
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        // IPv6 unique local addresses (fc00::/7) are private too.
        val bytes = address.address
        return !(bytes.size == IPV6_BYTES && (bytes[0].toInt() and ULA_MASK) == ULA_PREFIX)
    }

    private fun fetch(url: String): LinkPreviewData? = try {
        fetchOrThrow(url)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }

    private fun fetchOrThrow(url: String): LinkPreviewData? {
        val uri = URI(url)
        val host = uri.host ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        if (!java.net.InetAddress.getAllByName(host).all(::isPublic)) return null
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "text/html")
        return try {
            if (connection.responseCode !in OK_RANGE || connection.contentType?.contains("html") != true) return null
            val bytes = connection.inputStream.use { input -> input.readNBytesCompat(MAX_BYTES) }
            parse(url, host.removePrefix("www."), bytes.decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    fun parse(url: String, site: String, html: String): LinkPreviewData? {
        fun meta(name: String): String? {
            val tag = Regex("""<meta[^>]+(?:property|name)=["']$name["'][^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
            return Regex(
                """content=["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ).find(tag)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(MAX_TEXT)
        }
        val title =
            meta("og:title")
                ?: Regex(
                    """<title[^>]*>([^<]*)</title>""",
                    RegexOption.IGNORE_CASE,
                ).find(html)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(MAX_TEXT)
        if (title.isNullOrBlank()) return null
        return LinkPreviewData(url, meta("og:site_name") ?: site, title, meta("og:description") ?: meta("description"))
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val BUFFER = 8 * 1024
    private val OK_RANGE = 200..299
}

/**
 * Every link tapped in the chat (text bubbles and link previews): the link card URL rule, then the app's `onLink`,
 * then a browsable intent through the chat presenter (web links only).
 */
internal class ChatLinkHandler(
    private val instance: dev.pingfront.PingFrontInstance,
    private val context: android.content.Context,
) : androidx.compose.ui.platform.UriHandler {
    override fun openUri(uri: String) {
        // `UriHandler.openUri` can't wait, and `onLink` may suspend: the SDK applies the link card URL rule (the protocol,
        // "Link card URLs"; a refused link never reaches onLink or a bridge that forwards it) and waits for the answer
        // in a coroutine it owns, which logout and stop cancel.
        instance.openChatLink(uri) { ActivityChatPresenter(context).openUrl(it) }
    }
}

/** A compact preview under a bubble that contains a link. */
@Composable
internal fun LinkPreview(url: String) {
    val theme = LocalChatTheme.current
    var data by remember(url) { mutableStateOf(LinkPreviews.cached(url)) }
    LaunchedEffect(url) { if (data == null) data = LinkPreviews.load(url) }
    val preview = data ?: return
    val uri = LocalUriHandler.current
    Column(
        Modifier
            .widthIn(max = LocalMaxBubbleWidth.current)
            .clip(RoundedCornerShape(theme.cardRadius))
            .background(theme.teamBubble)
            .clickable { uri.openUri(preview.url) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(preview.site, style = MaterialTheme.typography.labelMedium, color = theme.muted, maxLines = 1)
        preview.title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = theme.onTeamBubble,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        preview.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = theme.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
