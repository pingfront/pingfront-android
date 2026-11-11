package dev.pingfront.billing

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Play refunds a purchase that is not acknowledged within three days
 * (https://developer.android.com/google/play/billing/integrate). A purchase from an offer card reaches only the SDK's
 * own listener, so the SDK acknowledges it, and only it: never a purchase the host app started.
 */
class AcknowledgementTest {
    private fun candidate(token: String, paid: Boolean = true, acknowledged: Boolean = false) = Acknowledgement.Candidate(token, paid, acknowledged)

    @Test fun acknowledgesOnlyPaidUnacknowledgedPurchasesTheSdkStarted() {
        val purchases = listOf(
            candidate("sdk-paid"),
            candidate("sdk-pending", paid = false),
            candidate("sdk-done", acknowledged = true),
            candidate("host-paid"),
        )
        assertEquals(setOf("sdk-paid"), Acknowledgement.due(purchases, started = setOf("sdk-paid", "sdk-pending", "sdk-done")))
    }

    @Test fun aPendingPurchaseIsAcknowledgedOnceItIsPaid() {
        val started = setOf("sdk-pending")
        assertEquals(emptySet(), Acknowledgement.due(listOf(candidate("sdk-pending", paid = false)), started))
        assertEquals(setOf("sdk-pending"), Acknowledgement.due(listOf(candidate("sdk-pending")), started))
    }

    @Test fun nothingWhenTheSdkStartedNothing() {
        assertEquals(emptySet(), Acknowledgement.due(listOf(candidate("host-paid"), candidate("host-other")), started = emptySet()))
    }
}
