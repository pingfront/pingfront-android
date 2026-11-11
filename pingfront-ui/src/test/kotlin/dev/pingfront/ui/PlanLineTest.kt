package dev.pingfront.ui

import dev.pingfront.model.Entitlement
import dev.pingfront.model.Entitlement.State
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The plan status line rules (the protocol, "Plan status"). */
class PlanLineTest {
    private val renews = Instant.parse("2026-11-12T09:00:00Z")
    private val expires = Instant.parse("2026-11-20T09:00:00Z")
    private val grace = Instant.parse("2026-11-04T09:00:00Z")
    private fun e(state: State) = Entitlement(state, "pro", renews, Entitlement.Source.Play, expiresAt = expires, gracePeriodEndsAt = grace)

    @Test fun eachStateUsesItsDate() {
        assertEquals(PlanLine(PlanLine.Kind.Renews, renews), PlanLine.of(e(State.Active)))
        assertEquals(PlanLine(PlanLine.Kind.Renews, renews), PlanLine.of(e(State.Trial)))
        assertEquals(PlanLine(PlanLine.Kind.PaymentIssue, grace), PlanLine.of(e(State.Grace)))
        assertEquals(PlanLine(PlanLine.Kind.PaymentIssue, grace), PlanLine.of(e(State.BillingRetry)))
        assertEquals(PlanLine(PlanLine.Kind.Ends, expires), PlanLine.of(e(State.Cancelled)))
        assertEquals(PlanLine(PlanLine.Kind.Ended, expires), PlanLine.of(e(State.Expired)))
        for (state in listOf(State.None, State.Lifetime, State.Unknown)) assertNull(PlanLine.of(e(state)), "$state shows no line")
    }

    @Test fun withoutADateTheLineStaysUndated() {
        assertEquals(PlanLine(PlanLine.Kind.PaymentIssue, null), PlanLine.of(Entitlement(State.BillingRetry)))
    }

    @Test fun theServersPlanWinsOverTheDevices() {
        val server = e(State.Grace)
        val device = Entitlement(State.Active, "pro", source = Entitlement.Source.Play)
        assertEquals(server, PlanLine.source(server, device))
        assertEquals(device, PlanLine.source(null, device))
        assertEquals(device, PlanLine.source(Entitlement(State.Unknown), device), "an unknown server state falls back")
    }

    /** Entitlement.environment: anything but production (or unsaid) is labelled a test purchase. */
    @Test fun aNonProductionPlanIsATestPurchase() {
        kotlin.test.assertTrue(Entitlement(State.Active, environment = "sandbox").isTestPurchase)
        kotlin.test.assertTrue(Entitlement(State.Active, environment = "xcode").isTestPurchase)
        kotlin.test.assertFalse(Entitlement(State.Active, environment = "production").isTestPurchase)
        kotlin.test.assertFalse(Entitlement(State.Active).isTestPurchase)
    }
}
