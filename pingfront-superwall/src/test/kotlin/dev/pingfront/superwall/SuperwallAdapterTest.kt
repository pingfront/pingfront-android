package dev.pingfront.superwall

import dev.pingfront.OfferFailure
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferRequest
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.StoreOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/** A stand-in for superwall-android: an experiment whose assignments the test can watch. */
private class FakeSuperwall(private val result: SwPresentation = SwPresentation.Purchased) : SuperwallClient {
    override var isConfigured: Boolean = true
    val registrations = mutableListOf<String>()
    val attributes = mutableListOf<Map<String, Any>>()

    override suspend fun register(placement: String): SwPresentation {
        // Registering records an assignment and can use up a holdout in Superwall.
        registrations += placement
        return result
    }

    override fun statusUpdates(): Flow<SwStatus> = emptyFlow()

    override fun setUserAttributes(attributes: Map<String, Any>) {
        this.attributes += attributes
    }
}

class SuperwallAdapterTest {
    private val placements = listOf(SuperwallPlacement("campaign_trigger", "Pro yearly"), SuperwallPlacement("winback", "Come back", OfferSlot.Kind.Winback))

    private fun request(offerId: String, offer: StoreOffer? = null) =
        dev.pingfront.internal.debug.Fixtures.offerRequest(offerId, null, "card_1", "msg_1", offer)

    /**
     * Reporting slots must leave the experiment untouched. In superwall-android 2.9.0 both register and
     * getPresentationResult confirm assignments and record holdouts, so the client has no "would present" call and
     * every slot is available; audiences and holdouts decide at tap time.
     */
    @Test fun reportingSlotsNeverTouchesTheExperiment() = runTest {
        val sw = FakeSuperwall()
        val slots = SuperwallAdapter(sw, placements).offers()
        assertTrue(sw.registrations.isEmpty(), "reporting slots registered a placement")
        assertTrue(slots.all { it.available })
        assertTrue(slots.all { it.source == OfferSlot.Source.Superwall })
        assertEquals(OfferSlot.Kind.Winback, slots[1].kind)
        assertEquals("Pro yearly", slots[0].title)
    }

    @Test fun aCardRegistersThePlacementAndMapsTheOutcome() = runTest {
        suspend fun outcome(result: SwPresentation) = SuperwallAdapter(FakeSuperwall(result), placements).handle(request("campaign_trigger"))
        assertEquals(OfferOutcome.Purchased, outcome(SwPresentation.Purchased))
        assertEquals(OfferOutcome.Purchased, outcome(SwPresentation.Restored))
        assertEquals(OfferOutcome.Cancelled, outcome(SwPresentation.Declined))
        assertEquals(OfferOutcome.Unavailable, outcome(SwPresentation.Skipped))
        assertEquals(OfferOutcome.Failed(OfferFailure.StoreError), outcome(SwPresentation.Failed))
    }

    @Test fun aStoreOfferIsIgnoredAndUnknownPlacementsAreNotRegistered() = runTest {
        val sw = FakeSuperwall()
        val adapter = SuperwallAdapter(sw, placements)
        adapter.handle(
            request("campaign_trigger", dev.pingfront.internal.debug.Fixtures.storeOffer(StoreOffer.Kind.Promotional, "x", "pro", "yearly", "yearly-40")),
        )
        assertEquals(listOf("campaign_trigger"), sw.registrations, "Superwall paywalls carry their own offers")
        assertEquals(OfferOutcome.Failed(OfferFailure.ProductNotFound), adapter.handle(request("other")))
        assertEquals(1, sw.registrations.size)
    }

    @Test fun theStoreAccountTokenBecomesAUserAttribute() = runTest {
        val sw = FakeSuperwall()
        SuperwallAdapter(sw, placements).linkIdentity(flowOf("token-a"))
        assertEquals(listOf(mapOf<String, Any>("pingfront_token" to "token-a")), sw.attributes)
    }

    @Test fun subscriptionStatusMapsToTheEntitlement() {
        val base = SwEntitlement("pro", lifetime = false, grace = false, trial = false, willRenew = true, expiresAt = null)
        fun state(e: SwEntitlement) = SuperwallAdapter.entitlement(SwStatus(listOf(e)))?.state
        assertEquals(Entitlement.State.Active, state(base))
        assertEquals(Entitlement.State.Trial, state(base.copy(trial = true)))
        assertEquals(Entitlement.State.Grace, state(base.copy(grace = true)))
        assertEquals(Entitlement.State.Cancelled, state(base.copy(willRenew = false)))
        assertEquals(Entitlement.State.Lifetime, state(base.copy(lifetime = true)))
        assertEquals(Entitlement.State.None, SuperwallAdapter.entitlement(SwStatus(emptyList()))?.state)
        assertNull(SuperwallAdapter.entitlement(SwStatus(null)), "unknown status reports nothing")
        assertEquals(Entitlement.Source.Superwall, SuperwallAdapter.entitlement(SwStatus(listOf(base)))?.source)
    }
}
