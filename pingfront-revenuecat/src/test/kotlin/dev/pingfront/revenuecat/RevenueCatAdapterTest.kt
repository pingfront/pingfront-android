package dev.pingfront.revenuecat

import android.app.Activity
import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferRequest
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.StoreOffer
import java.time.Instant
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

/** A stand-in for purchases-android, recording what the adapter asks of it. */
private class FakeRevenueCat(var offerings: List<RcOffering> = emptyList(), var customer: RcCustomer? = null) : RevenueCatClient {
    override var isConfigured: Boolean = true
    val attributes = mutableListOf<Map<String, String>>()
    val purchases = mutableListOf<Triple<String, String, RcOption?>>()
    val paywalls = mutableListOf<String>()

    override suspend fun offerings(): List<RcOffering> = offerings

    override suspend fun customer(): RcCustomer? = customer

    override fun customerUpdates(): Flow<RcCustomer> = emptyFlow()

    override fun setAttributes(attributes: Map<String, String>) {
        this.attributes += attributes
    }

    override suspend fun purchase(activity: Activity, offeringId: String, packageId: String, option: RcOption?): OfferOutcome {
        purchases += Triple(offeringId, packageId, option)
        return OfferOutcome.Purchased
    }

    override suspend fun presentPaywall(activity: Activity, offeringId: String): OfferOutcome {
        paywalls += offeringId
        return OfferOutcome.Cancelled
    }
}

private val yearly = RcPackage(
    "\$rc_annual",
    "pro:yearly",
    "€29.99",
    "P1Y",
    "P1W",
    listOf(RcOption("yearly", null), RcOption("yearly", "yearly-40")),
)
private val monthly = RcPackage("\$rc_monthly", "pro:monthly", "€3.99", "P1M", null, listOf(RcOption("monthly", null)))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RevenueCatAdapterTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun request(offerId: String, offer: StoreOffer? = null) =
        dev.pingfront.internal.debug.Fixtures.offerRequest(offerId, null, "card_1", "msg_1", offer)

    @Test fun offeringsBecomeSlotsWithSourceKindAndAvailability() = runTest {
        val rc = FakeRevenueCat(
            listOf(
                RcOffering("default", "Pro", null, listOf(yearly, monthly), hasPaywall = true),
                RcOffering("winback_40", "Come back for 40% off", "winback", listOf(yearly), hasPaywall = false),
            ),
            RcCustomer(emptyList(), hadEntitlements = false, ownedProducts = setOf("pro:yearly")),
        )
        val slots = RevenueCatAdapter(rc).offers()
        assertEquals(listOf("default", "winback_40"), slots.map { it.id })
        assertTrue(slots.all { it.source == OfferSlot.Source.RevenueCat })
        assertEquals(OfferSlot.Kind.Winback, slots[1].kind, "kind from the offering metadata pingfront_kind")
        assertEquals(listOf("pro:yearly", "pro:monthly"), slots[0].products.map { it.id })
        assertEquals("P1W", slots[0].products[0].trial)
        assertTrue(slots[0].available, "the monthly product is not owned")
        assertFalse(slots[1].available, "the person owns every product in it")
    }

    @Test fun anUnconfiguredRevenueCatReportsNothing() = runTest {
        val rc = FakeRevenueCat(listOf(RcOffering("default", "Pro", null, listOf(yearly), true))).apply { isConfigured = false }
        assertTrue(RevenueCatAdapter(rc).offers().isEmpty())
        assertEquals(OfferOutcome.Failed(OfferFailure.BillingUnavailable), RevenueCatAdapter(rc).handle(request("default")))
    }

    @Test fun aPublishedPaywallIsPresented() = runTest {
        val rc = FakeRevenueCat(listOf(RcOffering("default", "Pro", null, listOf(yearly), hasPaywall = true)))
        val adapter = RevenueCatAdapter(rc).apply { this.activity = { this@RevenueCatAdapterTest.activity } }
        assertEquals(OfferOutcome.Cancelled, adapter.handle(request("default")))
        assertEquals(listOf("default"), rc.paywalls)
        assertTrue(rc.purchases.isEmpty())
    }

    @Test fun withoutAPaywallTheFirstPackageIsBought() = runTest {
        val rc = FakeRevenueCat(listOf(RcOffering("default", "Pro", null, listOf(monthly, yearly), hasPaywall = false)))
        val adapter = RevenueCatAdapter(rc).apply { this.activity = { this@RevenueCatAdapterTest.activity } }
        assertEquals(OfferOutcome.Purchased, adapter.handle(request("default")))
        assertEquals(Triple("default", "\$rc_monthly", null), rc.purchases.single())
    }

    @Test fun aCardNamingAPlayOfferBuysThatSubscriptionOption() = runTest {
        val rc = FakeRevenueCat(listOf(RcOffering("winback_40", "40% off", "winback", listOf(monthly, yearly), hasPaywall = true)))
        val adapter = RevenueCatAdapter(rc).apply { this.activity = { this@RevenueCatAdapterTest.activity } }
        val offer = dev.pingfront.internal.debug.Fixtures.storeOffer(StoreOffer.Kind.Promotional, "yearly-40", "pro", "yearly", "yearly-40")
        adapter.handle(request("winback_40", offer))
        assertEquals(Triple("winback_40", "\$rc_annual", RcOption("yearly", "yearly-40")), rc.purchases.single())
        assertTrue(rc.paywalls.isEmpty(), "the named offer is bought directly, not through the paywall")
        // An offer the person is not eligible for (not listed) falls back to the paywall.
        adapter.handle(
            request("winback_40", dev.pingfront.internal.debug.Fixtures.storeOffer(offer.kind, offer.id, offer.productId, offer.basePlanId, "yearly-60")),
        )
        assertEquals(listOf("winback_40"), rc.paywalls)
    }

    @Test fun anUnknownOfferingIsNotFound() = runTest {
        val adapter = RevenueCatAdapter(FakeRevenueCat()).apply { this.activity = { this@RevenueCatAdapterTest.activity } }
        assertEquals(OfferOutcome.Failed(OfferFailure.ProductNotFound), adapter.handle(request("nope")))
    }

    @Test fun theStoreAccountTokenBecomesTheSubscriberAttribute() = runTest {
        val rc = FakeRevenueCat()
        RevenueCatAdapter(rc).linkIdentity(flowOf("token-a", "token-b"))
        assertEquals(listOf(mapOf("pingfront_token" to "token-a"), mapOf("pingfront_token" to "token-b")), rc.attributes)
    }

    @Test fun entitlementsMapLikeTheSpec() {
        fun state(e: RcEntitlement) = RevenueCatMapping.entitlement(RcCustomer(listOf(e), true, emptySet())).state
        val base = RcEntitlement("pro:yearly", trial = false, willRenew = true, billingIssue = false, expiresAt = Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals(Entitlement.State.Active, state(base))
        assertEquals(Entitlement.State.Trial, state(base.copy(trial = true)))
        assertEquals(Entitlement.State.Cancelled, state(base.copy(willRenew = false)))
        assertEquals(Entitlement.State.BillingRetry, state(base.copy(billingIssue = true)))
        assertEquals(Entitlement.State.Lifetime, state(base.copy(expiresAt = null)))
        assertEquals(Entitlement.State.None, RevenueCatMapping.entitlement(RcCustomer(emptyList(), false, emptySet())).state)
        assertEquals(Entitlement.State.Expired, RevenueCatMapping.entitlement(RcCustomer(emptyList(), true, emptySet())).state)
        assertEquals(Entitlement.Source.RevenueCat, RevenueCatMapping.entitlement(RcCustomer(listOf(base), true, emptySet())).source)
    }
}
