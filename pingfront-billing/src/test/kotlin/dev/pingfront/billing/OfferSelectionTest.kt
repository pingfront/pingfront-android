package dev.pingfront.billing

import dev.pingfront.model.StoreOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Promotional offers on Play: the card's named offer, the base-plan fallback, and the discounted price. */
class OfferSelectionTest {
    private fun phase(price: String, micros: Long, period: String = "P1Y") = OfferSelection.Phase(price, micros, period)

    private val basePlan = OfferSelection.Choice("yearly", null, "tok-base", listOf(phase("€29.99", 29_990_000)))
    private val promo = OfferSelection.Choice("yearly", "yearly-40", "tok-promo", listOf(phase("€17.99", 17_990_000), phase("€29.99", 29_990_000)))
    private val monthly = OfferSelection.Choice("monthly", null, "tok-monthly", listOf(phase("€3.99", 3_990_000, "P1M")))
    private val named = dev.pingfront.internal.debug.Fixtures.storeOffer(StoreOffer.Kind.Promotional, "yearly-40", "pro", "yearly", "yearly-40")

    @Test fun theNamedOfferIsBought() {
        assertEquals("tok-promo", OfferSelection.pick(listOf(monthly, basePlan, promo), named)?.token)
    }

    /** Play lists only offers the person is eligible for: a missing offer means "not eligible", so the base plan is bought. */
    @Test fun withoutTheOfferTheBasePlanIsBought() {
        assertEquals("tok-base", OfferSelection.pick(listOf(monthly, basePlan), named)?.token)
        assertEquals("tok-monthly", OfferSelection.pick(listOf(monthly, basePlan), null)?.token, "no named offer: the first base plan")
    }

    @Test fun theDiscountIsShownWithTheRegularPrice() {
        val price = OfferSelection.price(promo)!!
        assertEquals("€17.99", price.price)
        assertEquals("€29.99", price.regularPrice)
        assertNull(OfferSelection.price(basePlan)!!.regularPrice, "no discount, nothing struck through")
    }

    @Test fun aFreeTrialPhaseIsNotTheDiscountedPrice() {
        val trial = OfferSelection.Choice("yearly", "trial", "tok-trial", listOf(phase("Free", 0, "P1W"), phase("€29.99", 29_990_000)))
        assertEquals("€29.99", OfferSelection.price(trial)!!.price)
        assertNull(OfferSelection.price(trial)!!.regularPrice)
    }
}
