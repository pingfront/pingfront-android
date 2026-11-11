package dev.pingfront.billing

import com.android.billingclient.api.Purchase
import dev.pingfront.model.Entitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Review AN-2 (3 Nov 2026): the foreground check read "Play could not be reached" (a Play Store update, a service
 * disconnect) as "no purchases". A paying subscriber then got the entitlement None in `PATCH /me` and a
 * `subscription_expired` store moment, which a win-back play answers with a ping, and the real expiry later in that
 * process was never reported because the lapse had already been "sent".
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PlayOutOfReachTest {
    private var now = 0L
    private val lapses = LapseDetector { now }

    private fun subscription(token: String) = Purchase(
        """{"orderId":"GPA.1","packageName":"dev.pingfront.demo","productId":"pro_yearly","purchaseTime":1,""" +
            """"purchaseState":0,"purchaseToken":"$token","autoRenewing":true,"acknowledged":true}""",
        "",
    )

    @Test fun playOutOfReachChangesNothing() = runTest {
        val first = EntitlementCheck.of(listOf(subscription("tok1")), lapses)
        assertEquals(Entitlement.State.Active, first?.entitlement?.state)
        now += 61_000
        val unreachable = EntitlementCheck.of(null, lapses)
        assertNull(
            unreachable,
            "Play out of reach read as no purchases: ${unreachable?.entitlement?.state}, lapses ${unreachable?.lapses?.map { it.first }}",
        )
        now += 61_000
        val expired = EntitlementCheck.of(emptyList(), lapses)
        assertEquals(listOf("subscription_expired"), expired?.lapses?.map { it.first }, "the real expiry is still reported")
        assertEquals(Entitlement.State.None, expired?.entitlement?.state)
    }
}
