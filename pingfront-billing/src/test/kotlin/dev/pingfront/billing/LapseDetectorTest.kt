package dev.pingfront.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class LapseDetectorTest {
    private var now = 0L
    private val detector = LapseDetector { now }
    private fun snap(renewing: Boolean = true, suspended: Boolean = false) = LapseDetector.Snapshot("pro_yearly", "GPA.1", renewing, suspended)

    @Test fun cancelExpiryAndBillingIssueOncePerPurchase() = runTest {
        assertEquals(emptyList(), detector.compare(mapOf("t1" to snap(), "t2" to snap())))
        now += 61_000
        val events = detector.compare(mapOf("t1" to snap(renewing = false), "t2" to snap(suspended = true)))!!
        assertEquals(setOf("subscription_cancelled", "billing_issue"), events.map { it.first }.toSet())
        val cancelled = events.first { it.first == "subscription_cancelled" }.second
        assertEquals("t1", cancelled.purchaseToken)
        assertEquals("GPA.1", cancelled.orderId)
        now += 61_000
        assertEquals(listOf("subscription_expired"), detector.compare(mapOf("t2" to snap(suspended = true)))!!.map { it.first })
        now += 61_000
        detector.compare(mapOf("t1" to snap()))
        now += 61_000
        assertEquals(emptyList(), detector.compare(mapOf("t1" to snap(renewing = false))), "cancel for t1 was already sent")
    }

    @Test fun atMostOnceAMinute() = runTest {
        detector.compare(mapOf("t1" to snap()))
        now += 30_000
        assertNull(detector.compare(mapOf("t1" to snap(renewing = false))))
    }

    /**
     * Review CQ-H2: a real Play purchase token (about 150 characters) was sent as `originalTransactionId`
     * (App Store only, 64 characters at most), so every lapse moment got a 422. Play reports `purchaseToken`
     * and `orderId` (`StoreMomentProps` in openapi.yaml).
     */
    @Test fun lapseMomentsFitStoreMomentProps() = runTest {
        val token = "kmbbnhfgfgdmbngkbmknjbhj.AO-J1Oz" + "x".repeat(140)
        detector.compare(mapOf(token to LapseDetector.Snapshot("pro_monthly", "GPA.3374-1234-5678-90123", true, false)))
        now += 61_000
        val props = detector.compare(mapOf(token to LapseDetector.Snapshot("pro_monthly", "GPA.3374-1234-5678-90123", false, false)))!!.single().second
        assertEquals(token, props.purchaseToken)
        assertEquals("GPA.3374-1234-5678-90123", props.orderId)
        assertNull(props.originalTransactionId, "the purchase token never goes in originalTransactionId")
        assertNull(props.transactionId)
    }

    @Test fun titlesAreCutAtCodePoints() {
        assertEquals("ab", codePoints("ab\uD83D\uDE00", 2))
        assertEquals("ab\uD83D\uDE00", codePoints("ab\uD83D\uDE00c", 3))
    }
}
