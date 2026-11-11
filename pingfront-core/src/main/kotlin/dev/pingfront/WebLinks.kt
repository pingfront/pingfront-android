package dev.pingfront

/**
 * Which URLs from the server the SDK may open or fetch. Message content is written by the app's team,
 * plays and the assistant, so images and review cards are limited to the web, and link cards to the web and the
 * app's own scheme: no `content:`, `file:`, `javascript:`, `intent:` or other apps' deep links (review findings
 * CS-9 and CS-11).
 */
@InternalPingFrontApi
public object WebLinks {
    /**
     * Schemes a link card never carries, compared ignoring case (the protocol, "Link card URLs", and the
     * shared `link-urls.json` test vectors). The server refuses them too; this is defence in depth.
     */
    public val refusedSchemes: Set<String> = setOf("javascript", "data", "vbscript", "file", "blob", "about", "content", "intent")

    private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** True for an absolute `http` or `https` URL with a host. */
    public fun isWeb(url: String): Boolean {
        val uri = try {
            java.net.URI(url.trim())
        } catch (_: java.net.URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()
    }

    /**
     * A link card URL as the rule reads it, the way a browser does: ASCII tab, line feed and carriage return removed
     * anywhere, whitespace and control characters trimmed from both ends.
     */
    public fun cleanCardUrl(url: String): String = url.filterNot { it == '\t' || it == '\n' || it == '\r' }.trim { it.isWhitespace() || it.isISOControl() }

    /** The link card URL's scheme in lowercase, after [cleanCardUrl], or null when it has none. */
    public fun cardUrlScheme(url: String): String? = scheme.find(cleanCardUrl(url))?.groupValues?.get(1)?.lowercase()

    /** Whether a link card may carry the URL: `https`, `http` or the app's own scheme, never [refusedSchemes]. */
    public fun isCardLink(url: String): Boolean = cardUrlScheme(url)?.let { it !in refusedSchemes } ?: false
}
