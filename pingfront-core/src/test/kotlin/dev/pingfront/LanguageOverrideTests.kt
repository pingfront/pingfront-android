package dev.pingfront

import dev.pingfront.internal.runtime.LanguageOverride
import dev.pingfront.internal.runtime.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageOverrideTests {
    @AfterTest fun reset() {
        LanguageOverride.set(null)
    }

    @Test fun acceptsBcp47ShapedTags() {
        assertEquals("pt-BR", LanguageOverride.normalized("pt_BR"))
        assertEquals("de", LanguageOverride.normalized(" de "))
        assertEquals("zh-Hant", LanguageOverride.normalized("zh-Hant"))
        assertEquals("es-419", LanguageOverride.normalized("es-419"))
    }

    @Test fun rejectsAnythingElse() {
        listOf("", "1de", "de;DROP", "äö", "a".repeat(21)).forEach { assertNull(LanguageOverride.normalized(it), it) }
    }

    @Test fun theFacadeKeepsTheLastValidTagAndClearsWithNull() {
        Log.sink = { _, _ -> }
        val instance = PingFrontInstance()
        instance.setLanguage("ko")
        assertEquals("ko", instance.language.value)
        instance.setLanguage("not a tag")
        assertEquals("ko", instance.language.value, "an invalid tag is ignored")
        instance.setLanguage(null)
        assertNull(instance.language.value)
    }
}
