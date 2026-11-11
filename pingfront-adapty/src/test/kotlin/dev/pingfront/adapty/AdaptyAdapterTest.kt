package dev.pingfront.adapty

import android.app.Activity
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferRequest
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.StoreOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeAdapty(private val flows: Map<String, AdFlow>, private val profile: AdProfile? = null) : AdaptyClient {
    override var isConfigured: Boolean = true
    val attributes = mutableListOf<Pair<String, String>>()
    val purchases = mutableListOf<AdProduct>()
    val presented = mutableListOf<String>()

    override suspend fun flow(placement: String): AdFlow? = flows[placement]

    override suspend fun profile(): AdProfile? = profile

    override fun profileUpdates(): Flow<AdProfile> = emptyFlow()

    override fun setCustomAttribute(key: String, value: String) {
        attributes += key to value
    }

    override suspend fun purchase(activity: Activity, placement: String, product: AdProduct): OfferOutcome {
        purchases += product
        return OfferOutcome.Purchased
    }

    override suspend fun present(activity: Activity, placement: String): OfferOutcome {
        presented += placement
        return OfferOutcome.Cancelled
    }
}

private val base = AdProduct("pro", "€29.99", "P1Y", "P1W", "yearly", null)
private val promo = AdProduct("pro", "€17.99", "P1Y", null, "yearly", "yearly-40")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdaptyAdapterTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun request(offerId: String, offer: StoreOffer? = null) =
        dev.pingfront.internal.debug.Fixtures.offerRequest(offerId, null, "card_1", "msg_1", offer)

    private fun adapter(client: FakeAdapty) = AdaptyAdapter(client, listOf("main", "winback")).apply { activity = { this@AdaptyAdapterTest.activity } }

    @Test fun placementsBecomeSlots() = runTest {
        val adapty = FakeAdapty(
            mapOf(
                "main" to AdFlow("main", "Pro", null, hasView = true, products = listOf(base)),
                "winback" to AdFlow("winback", "Come back", "winback", hasView = false, products = listOf(promo)),
            ),
            AdProfile(listOf(AdAccessLevel("pro", active = true, lifetime = false, grace = false, trial = false, willRenew = true, expiresAt = null))),
        )
        val slots = adapter(adapty).offers()
        assertEquals(listOf("main", "winback"), slots.map { it.id })
        assertTrue(slots.all { it.source == OfferSlot.Source.Adapty })
        assertEquals(OfferSlot.Kind.Winback, slots[1].kind, "kind from the remote config key pingfront_kind")
        assertFalse(slots[0].available, "the person already has the product")
        assertEquals("P1W", slots[0].products.single().trial)
    }

    @Test fun aFlowWithAViewIsPresentedElseTheProductIsBought() = runTest {
        val adapty = FakeAdapty(
            mapOf(
                "main" to AdFlow("main", "Pro", null, hasView = true, products = listOf(base)),
                "winback" to AdFlow("winback", "Come back", null, hasView = false, products = listOf(base)),
            ),
        )
        assertEquals(OfferOutcome.Cancelled, adapter(adapty).handle(request("main")))
        assertEquals(listOf("main"), adapty.presented)
        assertEquals(OfferOutcome.Purchased, adapter(adapty).handle(request("winback")))
        assertEquals(listOf(base), adapty.purchases)
        assertEquals(OfferOutcome.Failed(OfferFailure.ProductNotFound), adapter(adapty).handle(request("not_configured")))
    }

    @Test fun aCardNamingAPlayOfferBuysTheMatchingProduct() = runTest {
        val adapty = FakeAdapty(mapOf("main" to AdFlow("main", "Pro", null, hasView = true, products = listOf(base, promo))))
        adapter(
            adapty,
        ).handle(request("main", dev.pingfront.internal.debug.Fixtures.storeOffer(StoreOffer.Kind.Promotional, "yearly-40", "pro", "yearly", "yearly-40")))
        assertEquals(listOf(promo), adapty.purchases)
        assertTrue(adapty.presented.isEmpty())
        // An unknown kind only opens the paywall.
        adapter(adapty).handle(request("main", dev.pingfront.internal.debug.Fixtures.storeOffer(StoreOffer.Kind.Unknown, "x", "pro", "yearly", "yearly-40")))
        assertEquals(listOf("main"), adapty.presented)
    }

    @Test fun theStoreAccountTokenBecomesACustomAttribute() = runTest {
        val adapty = FakeAdapty(emptyMap())
        adapter(adapty).linkIdentity(flowOf("token-a"))
        assertEquals(listOf("pingfront_token" to "token-a"), adapty.attributes)
    }

    @Test fun accessLevelsMapToTheEntitlement() {
        val active = AdAccessLevel("pro", active = true, lifetime = false, grace = false, trial = false, willRenew = true, expiresAt = null)
        fun state(level: AdAccessLevel) = AdaptyAdapter.entitlement(AdProfile(listOf(level))).state
        assertEquals(Entitlement.State.Active, state(active))
        assertEquals(Entitlement.State.Grace, state(active.copy(grace = true)))
        assertEquals(Entitlement.State.Cancelled, state(active.copy(willRenew = false)))
        assertEquals(Entitlement.State.Lifetime, state(active.copy(lifetime = true)))
        assertEquals(Entitlement.State.Trial, state(active.copy(trial = true)))
        assertEquals(Entitlement.State.Expired, state(active.copy(active = false)))
        assertEquals(Entitlement.State.None, AdaptyAdapter.entitlement(AdProfile(emptyList())).state)
    }
}
