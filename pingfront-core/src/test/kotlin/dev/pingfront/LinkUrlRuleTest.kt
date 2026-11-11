package dev.pingfront

import dev.pingfront.internal.wire.WireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The link card URL rule (the protocol, "Link card URLs"), on the cases the server and every SDK run. */
class LinkUrlRuleTest {
    @Test fun theFixtureCasesPassOrAreRefused() {
        assertEquals(refusedSchemes().toSet(), WebLinks.refusedSchemes)
        val allowed = urls("allowed")
        val refused = urls("refused")
        assertTrue(allowed.isNotEmpty() && refused.isNotEmpty())
        for (url in allowed) assertTrue(WebLinks.isCardLink(url), "allowed: $url")
        for (url in refused) assertFalse(WebLinks.isCardLink(url), "refused: $url")
    }

    @Test fun theCleanedUrlKeepsOnlyWhatABrowserReads() {
        assertEquals("myapp://settings", WebLinks.cleanCardUrl("\u0000 my\tapp://settings\n "))
        assertEquals("myapp", WebLinks.cardUrlScheme(" MyApp://x"))
    }

    companion object {
        private val fixture: JsonObject by lazy {
            WireJson.parseToJsonElement(dev.pingfront.testing.ProtocolFixtures.file("link-urls.json").readText()).jsonObject
        }

        fun urls(kind: String): List<String> = fixture[kind]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content }

        fun refusedSchemes(): List<String> = fixture["refusedSchemes"]!!.jsonArray.map { it.jsonPrimitive.content }
    }
}
