package dev.pingfront.revenuecat

import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.StoreOffer
import java.time.Instant

/** A RevenueCat offering, reduced to what the adapter needs (built from `Offering`; plain data for tests). */
internal data class RcOffering(
    val id: String,
    val description: String,
    /** The offering metadata key `pingfront_kind` (`standard`, `discount`, `winback`). */
    val kind: String?,
    val packages: List<RcPackage>,
    val hasPaywall: Boolean,
)

internal data class RcPackage(val id: String, val productId: String, val price: String, val period: String?, val trial: String?, val options: List<RcOption>)

/** A Play subscription option (base plan and, for an offer, its offer id). */
internal data class RcOption(val basePlanId: String?, val offerId: String?)

/** An active entitlement from `CustomerInfo.entitlements.active`. */
internal data class RcEntitlement(
    val productId: String,
    val trial: Boolean,
    val willRenew: Boolean,
    val billingIssue: Boolean,
    val expiresAt: Instant?,
)

/** The customer: active entitlements and owned product ids. */
internal data class RcCustomer(val active: List<RcEntitlement>, val hadEntitlements: Boolean, val ownedProducts: Set<String>)

/** Pure mapping from RevenueCat data to pingfront's contract (the parity spec, "RevenueCat"). */
internal object RevenueCatMapping {
    private const val TITLE_CHARS = 60
    private const val PRODUCTS = 10
    private val SLOT_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")

    /** One slot per offering; `available` while the person does not own every product in it. */
    fun slots(offerings: List<RcOffering>, customer: RcCustomer?): List<OfferSlot> = offerings
        .filter { SLOT_ID.matches(it.id) }
        .map { offering ->
            val products = offering.packages.take(PRODUCTS)
            val owned = customer?.ownedProducts.orEmpty()
            OfferSlot(
                id = offering.id,
                title = codePoints(offering.description.ifBlank { offering.id }, TITLE_CHARS),
                kind = when (offering.kind) {
                    "discount" -> OfferSlot.Kind.Discount
                    "winback" -> OfferSlot.Kind.Winback
                    else -> OfferSlot.Kind.Standard
                },
                available = products.isEmpty() || !products.all { owns(owned, it.productId) },
                products = products.map { OfferSlot.Product(it.productId, it.price, it.period, it.trial) },
                source = OfferSlot.Source.RevenueCat,
            )
        }

    /** The person's entitlement from the active entitlements (the first one decides). */
    fun entitlement(customer: RcCustomer): Entitlement {
        val e = customer.active.firstOrNull()
            ?: return Entitlement(if (customer.hadEntitlements) Entitlement.State.Expired else Entitlement.State.None, source = Entitlement.Source.RevenueCat)
        val state = when {
            e.expiresAt == null -> Entitlement.State.Lifetime
            e.trial -> Entitlement.State.Trial
            e.billingIssue -> Entitlement.State.BillingRetry
            !e.willRenew -> Entitlement.State.Cancelled
            else -> Entitlement.State.Active
        }
        return Entitlement(state, e.productId, e.expiresAt, Entitlement.Source.RevenueCat)
    }

    /** The package and Play option a card's store offer names (`basePlanId` and `offerId`), if the person can buy it. */
    fun option(offering: RcOffering, offer: StoreOffer?): Pair<RcPackage, RcOption>? {
        if (offer == null || offer.kind == StoreOffer.Kind.Unknown || offer.basePlanId == null) return null
        return offering.packages
            .filter { offer.productId == null || baseProduct(it.productId) == offer.productId }
            .firstNotNullOfOrNull { pkg -> pkg.options.firstOrNull { it.basePlanId == offer.basePlanId && it.offerId == offer.offerId }?.let { pkg to it } }
    }

    /** Play subscription ids come as `product:basePlan`. */
    fun baseProduct(id: String): String = id.substringBefore(':')

    /** Owning `pro:yearly` does not own `pro:monthly`; an owned id without a base plan owns every plan of it. */
    fun owns(owned: Set<String>, productId: String): Boolean = productId in owned || baseProduct(productId) in owned

    private fun codePoints(text: String, max: Int): String =
        if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
}
