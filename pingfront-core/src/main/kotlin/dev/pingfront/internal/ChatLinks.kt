package dev.pingfront.internal

import dev.pingfront.WebLinks
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What a URL handed to `PingFront.handle` asks for (the protocol, "Chat links"). */
internal sealed interface ChatLink {
    /** Not a chat link: no `pingfront` parameter, or not a URL that can carry one. The app routes it itself. */
    data object NotALink : ChatLink

    /** It names `pingfront` but is refused: a repeated parameter, an unknown or empty value, a forbidden scheme. */
    data object Refused : ChatLink

    /** Open the chat; [topicId] is the link's `topic`, still to be matched against the chat's topics. */
    data class Chat(val topicId: String?) : ChatLink

    /** Open the bug report flow. */
    data object Report : ChatLink
}

/**
 * The chat link parser, on the URL string, so the rules are the same on every Android version and testable on the
 * JVM. It works on the raw query and percent-decodes names and values itself: `Uri.getQueryParameter` encodes
 * the key before matching, so `p%69ngfront=chat` would be missed.
 */
internal object ChatLinks {
    const val MAX_LENGTH: Int = 2_048
    private const val PARAM = "pingfront"
    private const val TOPIC = "topic"
    private const val TOPIC_MAX = 32
    private val DENIED = WebLinks.refusedSchemes
    private val HIERARCHICAL = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    fun parse(url: String): ChatLink {
        if (url.isEmpty() || url.length > MAX_LENGTH) return ChatLink.NotALink
        val scheme = HIERARCHICAL.find(url)?.groupValues?.get(1) ?: return ChatLink.NotALink
        if (scheme.lowercase() in DENIED) return ChatLink.Refused
        val query = url.substringBefore('#').substringAfter('?', "")
        val values = mutableListOf<String?>()
        val topics = mutableListOf<String?>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('=')) ?: continue
            val value = if ('=' in pair) decode(pair.substringAfter('=')) else null
            when (name) {
                PARAM -> values += value
                TOPIC -> topics += value
            }
        }
        if (values.isEmpty()) return ChatLink.NotALink
        if (values.size > 1) return ChatLink.Refused
        return when (values.single()) {
            "chat" -> ChatLink.Chat(topics.singleOrNull()?.takeIf { it.length in 1..TOPIC_MAX })
            "report" -> ChatLink.Report
            else -> ChatLink.Refused
        }
    }

    /** Strict percent-decoding (`+` stays a plus); `null` for a malformed escape or bytes that aren't UTF-8. */
    private fun decode(raw: String): String? {
        if ('%' !in raw) return raw
        val bytes = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length) return null
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val end = raw.indexOf('%', i).let { if (it < 0) raw.length else it }
                bytes.write(raw.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
