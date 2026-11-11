package dev.pingfront.billing

import com.android.billingclient.api.Purchase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review AN-2, the plan-switch path (3 Nov 2026): buying from an offer card asked Play which subscription the person
 * has through `purchases()`, which turns "Play could not be reached" into "no purchases". A subscriber who tapped an
 * offer while Play was disconnected got a second subscription instead of a replacement of the first.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PlanSwitchOutOfReachTest {
    private val monthly = Purchase(
        """{"orderId":"GPA.2","packageName":"dev.pingfront.demo","productId":"pro_monthly","purchaseTime":1,""" +
            """"purchaseState":0,"purchaseToken":"tok2","autoRenewing":true,"acknowledged":true}""",
        "",
    )

    @Test fun aSwitchIsUnknownWhenPlayIsOutOfReach() {
        assertEquals(PlanSwitch.Replacing.Product("pro_monthly"), PlanSwitch.replacing(listOf(monthly), "pro_yearly"))
        assertEquals(PlanSwitch.Replacing.Nothing, PlanSwitch.replacing(emptyList(), "pro_yearly"))
        assertEquals(PlanSwitch.Replacing.Unknown, PlanSwitch.replacing(null, "pro_yearly"), "Play out of reach read as no plan")
    }
}
