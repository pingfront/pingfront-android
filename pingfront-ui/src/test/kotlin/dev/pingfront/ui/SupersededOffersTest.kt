package dev.pingfront.ui

import dev.pingfront.internal.debug.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/** Offer cards: every one but the newest collapses to "Replaced by a newer offer", whatever its offer (as on iOS and the web). */
class SupersededOffersTest {
    private fun message(seq: Int, vararg cards: Pair<String, String>) = Fixtures.decodeMessage(
        """{"id":"m$seq","seq":$seq,"author":{"kind":"team","name":"Deniz"},"createdAt":"2026-10-30T18:00:00Z","parts":[""" +
            cards.joinToString(",") { (id, offer) ->
                """{"type":"card","card":{"kind":"offer","id":"$id","offerId":"$offer","title":"t","button":"b"}}"""
            } + "]}",
    )

    @Test fun aDifferentNewerOfferReplacesAnOlderOne() {
        val thread = listOf(message(1, "c1" to "yearly_winback"), message(2, "c2" to "monthly_promo"))
        assertEquals(setOf("c1"), supersededOffers(thread))
    }

    @Test fun onlyTheNewestCardStandsAcrossAndWithinMessages() {
        val thread = listOf(message(1, "c1" to "a", "c2" to "b"), message(2, "c3" to "a"))
        assertEquals(setOf("c1", "c2"), supersededOffers(thread))
        assertEquals(emptySet(), supersededOffers(listOf(message(1, "only" to "a"))))
        assertEquals(emptySet(), supersededOffers(emptyList()))
    }
}
