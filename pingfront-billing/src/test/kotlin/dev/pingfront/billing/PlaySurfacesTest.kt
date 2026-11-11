package dev.pingfront.billing

import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode
import com.android.billingclient.api.InAppMessageResult.InAppMessageResponseCode
import dev.pingfront.PaymentFix
import dev.pingfront.PlanChange
import dev.pingfront.PricePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Play's native surfaces, as plain logic: in-app payment messages, plan switches and pricing phases. */
class PlaySurfacesTest {
    @Test fun theInAppMessageResultMapsToWhatHappened() {
        assertEquals(PaymentFix.Updated, PaymentFixMapping.fix(InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED, shown = true))
        // Play says NO_ACTION_NEEDED both when it had nothing and when the person closed the sheet.
        assertEquals(PaymentFix.Dismissed, PaymentFixMapping.fix(InAppMessageResponseCode.NO_ACTION_NEEDED, shown = true))
        assertEquals(PaymentFix.NothingToShow, PaymentFixMapping.fix(InAppMessageResponseCode.NO_ACTION_NEEDED, shown = false))
    }

    @Test fun aSwitchReplacesTheOtherSubscriptionOnly() {
        assertEquals("pro_monthly", PlanSwitch.owned(listOf(listOf("pro_monthly")), "pro_yearly"))
        assertNull(PlanSwitch.owned(listOf(listOf("pro_yearly")), "pro_yearly"), "buying what you have is not a switch")
        assertNull(PlanSwitch.owned(emptyList(), "pro_yearly"), "no subscription, nothing to replace")
    }

    @Test fun upgradesHappenNowAndDowngradesAtRenewal() {
        val monthly = PlanSwitch.perYear(3_990_000, "P1M")!!
        val yearly = PlanSwitch.perYear(29_990_000, "P1Y")!!
        val premium = PlanSwitch.perYear(9_990_000, "P1M")!!
        assertEquals(47_880_000, monthly)
        // Monthly to yearly costs less per year: Play defers it to the renewal.
        assertEquals(PlanChange.AtRenewal, PlanSwitch.change(monthly, yearly))
        assertEquals(PlanChange.Now, PlanSwitch.change(monthly, premium))
        assertEquals(PlanChange.Now, PlanSwitch.change(null, yearly), "unknown old price: replace now, prorated")
        assertEquals(ReplacementMode.WITH_TIME_PRORATION, PlanSwitch.replacementMode(PlanChange.Now))
        assertEquals(ReplacementMode.DEFERRED, PlanSwitch.replacementMode(PlanChange.AtRenewal))
        assertEquals(1_000_000, PlanSwitch.perYear(250_000, "P3M"))
        assertNull(PlanSwitch.perYear(1, "monthly"))
    }

    @Test fun everyPricingPhaseIsListedInOrder() {
        val choice = OfferSelection.Choice(
            "yearly",
            "intro",
            "tok",
            listOf(
                OfferSelection.Phase("Free", 0, "P1W", cycles = 1, recurring = false),
                OfferSelection.Phase("€1.99", 1_990_000, "P1M", cycles = 3, recurring = false),
                OfferSelection.Phase("€29.99", 29_990_000, "P1Y"),
            ),
        )
        val price = OfferSelection.price(choice)!!
        assertEquals(
            listOf(PricePhase("Free", "P1W", 1, free = true), PricePhase("€1.99", "P1M", 3, free = false), PricePhase("€29.99", "P1Y", null, free = false)),
            price.phases,
        )
        assertEquals("€1.99", price.price, "the first paid phase is what the person pays now")
    }
}
