package dev.pingfront.internal.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The language the host chose in its own picker (`PingFront.language`): read by device facts and
 * `LocalizedText` lookups, and observed by the chat UI. `null` follows the app's and the system's language.
 */
internal object LanguageOverride {
    private val _tag = MutableStateFlow<String?>(null)
    val tag: StateFlow<String?> = _tag

    private const val MAX_CHARS = 20
    private val pattern = Regex("[A-Za-z][A-Za-z0-9-]*")

    /** A BCP 47 tag the SDK accepts (underscores become hyphens), or `null`. */
    fun normalized(raw: String?): String? {
        val tag = raw?.trim()?.replace('_', '-') ?: return null
        return tag.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS && pattern.matches(it) }
    }

    /** Sets the override; returns false when it did not change. */
    fun set(tag: String?): Boolean {
        if (_tag.value == tag) return false
        _tag.value = tag
        return true
    }
}
