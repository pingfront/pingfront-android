package dev.pingfront.revenuecat

import android.app.Activity
import dev.pingfront.EntitlementSource
import dev.pingfront.ModuleContext
import dev.pingfront.OfferFailure
import dev.pingfront.OfferHandler
import dev.pingfront.OfferOutcome
import dev.pingfront.OfferRequest
import dev.pingfront.OfferSource
import dev.pingfront.PingFront
import dev.pingfront.PingFrontModule
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * RevenueCat for pingfront (purchases-android). Configure `Purchases` as usual, then:
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") { use(RevenueCat) }
 * ```
 *
 * - Reports every offering as an offer slot (`source: revenuecat`), with `kind` from the offering metadata key
 *   `pingfront_kind` and `available` until the person owns every product in it.
 * - An offer card opens the offering's published paywall (RevenueCat Paywalls), or buys its package; a card naming
 *   a Play offer (`basePlanId`/`offerId`) buys that subscription option.
 * - Keeps the entitlement current from `CustomerInfo`.
 * - Sets the subscriber attribute `pingfront_token` to the person's store account token, so RevenueCat webhooks
 *   find the person even when the app uses its own `appUserID`.
 *
 * Lapses (cancellations, expiries, billing issues) reach pingfront through RevenueCat's webhook, so the adapter
 * does not report them from the device (no double counting).
 */
public object RevenueCat : PingFrontModule("revenuecat") {
    override fun install(context: ModuleContext): Unit = RevenueCatAdapter(PurchasesClient()).install(context)
}

/** The adapter's logic over a [RevenueCatClient]. */
internal class RevenueCatAdapter(private val client: RevenueCatClient) : OfferSource, OfferHandler, EntitlementSource {
    @Volatile internal var activity: () -> Activity? = { null }

    fun install(context: ModuleContext) {
        activity = context::currentActivity
        context.addOfferSource(this)
        context.addEntitlementSource(this)
        context.setOfferHandler(this)
        context.scope.launch { linkIdentity(PingFront.instance.storeAccountTokens()) }
    }

    /** Every store account token (one per person) becomes the `pingfront_token` subscriber attribute. */
    suspend fun linkIdentity(tokens: Flow<String>) {
        tokens.collect { token -> if (client.isConfigured) client.setAttributes(mapOf(ATTRIBUTE to token)) }
    }

    override suspend fun offers(): List<OfferSlot> {
        if (!client.isConfigured) return emptyList()
        return RevenueCatMapping.slots(client.offerings(), client.customer())
    }

    /** The current entitlement, then every customer update; offer availability is refreshed with it. */
    override fun entitlementUpdates(): Flow<Entitlement> = flow {
        if (!client.isConfigured) return@flow
        client.customer()?.let { emit(RevenueCatMapping.entitlement(it)) }
        client.customerUpdates().collect { customer ->
            emit(RevenueCatMapping.entitlement(customer))
            PingFront.setOffers(RevenueCatMapping.slots(client.offerings(), customer))
        }
    }

    override suspend fun handle(request: OfferRequest): OfferOutcome {
        if (!client.isConfigured) return OfferOutcome.Failed(OfferFailure.BillingUnavailable)
        val offering = client.offerings().firstOrNull { it.id == request.offerId } ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        val host = activity() ?: return OfferOutcome.Failed(OfferFailure.NoActivity)
        // A card that names a Play offer buys exactly that subscription option.
        RevenueCatMapping.option(offering, request.storeOffer)?.let { (pkg, option) ->
            return client.purchase(host, offering.id, pkg.id, option)
        }
        if (offering.hasPaywall) return client.presentPaywall(host, offering.id)
        val pkg = offering.packages.firstOrNull() ?: return OfferOutcome.Failed(OfferFailure.ProductNotFound)
        return client.purchase(host, offering.id, pkg.id, null)
    }

    internal companion object {
        /** The subscriber attribute the pingfront server matches webhooks on. */
        const val ATTRIBUTE: String = "pingfront_token"
    }
}
