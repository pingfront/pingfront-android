package dev.pingfront

import dev.pingfront.conversation.StoreReceipt
import dev.pingfront.internal.StoreActionLinks
import dev.pingfront.internal.StoreActions
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.model.Card
import dev.pingfront.model.Entitlement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class StoreActionCardTest {
    private fun decode(json: String): Card = WireJson.decodeFromString(Card.serializer(), json)

    @Test fun everyActionDecodes() {
        val wire = mapOf(
            "manage_subscriptions" to Card.StoreAction.Action.ManageSubscriptions,
            "billing_issue" to Card.StoreAction.Action.BillingIssue,
            "request_refund" to Card.StoreAction.Action.RequestRefund,
            "redeem_code" to Card.StoreAction.Action.RedeemCode,
            "restore_purchases" to Card.StoreAction.Action.RestorePurchases,
        )
        for ((name, action) in wire) {
            val card = assertIs<Card.StoreAction>(decode("""{"kind":"store_action","id":"c1","action":"$name","productId":"pro"}"""))
            assertNull(card.title)
            assertEquals(action, card.action)
            assertEquals("pro", card.productId)
            // Round trip: the same wire names come back out.
            assertEquals(card, decode(WireJson.encodeToString(Card.serializer(), card)))
        }
    }

    @Test fun theFullShapeDecodes() {
        val card = assertIs<Card.StoreAction>(
            decode(
                """{"kind":"store_action","id":"c1","action":"billing_issue","title":"Fix it","body":"Retry later.","button":"Fix",""" +
                    """"productId":"pro","subscriptionGroupId":"21345678","transactionId":"2000000412345678","store":"play"}""",
            ),
        )
        assertEquals(
            Card.StoreAction("c1", Card.StoreAction.Action.BillingIssue, "Fix it", "Retry later.", "Fix", "pro", "21345678", "2000000412345678", "play"),
            card,
        )
    }

    @Test fun anUnknownActionDecodesAsUnknownAndAMissingOneAsAnUnknownCard() {
        val card = assertIs<Card.StoreAction>(decode("""{"kind":"store_action","id":"c1","action":"gift_subscription","title":"Gift"}"""))
        assertEquals(Card.StoreAction.Action.Unknown, card.action)
        assertEquals("Gift", card.title, "the card's text is kept for the unknown action")
        assertIs<Card.Unknown>(decode("""{"kind":"store_action","id":"c2"}"""))
    }

    @Test fun playDestinations() {
        val pkg = "dev.pingfront.demo.clockwise"
        assertEquals(
            "https://play.google.com/store/account/subscriptions?sku=pro_yearly&package=$pkg",
            StoreActionLinks.url(Card.StoreAction.Action.BillingIssue, "pro_yearly", pkg),
        )
        assertEquals(
            "https://play.google.com/store/account/subscriptions",
            StoreActionLinks.url(Card.StoreAction.Action.ManageSubscriptions, null, pkg),
            "without a product, the subscription center itself",
        )
        assertEquals("https://play.google.com/store/account/orderhistory", StoreActionLinks.url(Card.StoreAction.Action.RequestRefund, "pro", pkg))
        assertEquals("https://play.google.com/redeem", StoreActionLinks.url(Card.StoreAction.Action.RedeemCode, null, pkg))
        assertNull(StoreActionLinks.url(Card.StoreAction.Action.RestorePurchases, null, pkg))
        assertEquals(
            "https://play.google.com/store/account/subscriptions",
            StoreActionLinks.url(Card.StoreAction.Action.Unknown, null, pkg),
            "an unknown action opens the subscription page",
        )
    }
}

class StoreActionsTest {
    private class Harness(
        var entitlement: Entitlement? = null,
        var store: Entitlement? = null,
        var reachable: Boolean = true,
        val fixer: PaymentFixer? = null,
    ) {
        val opened = mutableListOf<String>()
        val receipts = mutableListOf<Pair<String, StoreReceipt>>()
        val reports = mutableListOf<Triple<String, String?, StoreReceipt>>()
        val actions = StoreActions(
            refresh = { if (reachable) store?.let { StorePurchases(it) } else null },
            fixer = { fixer },
            current = { entitlement },
            setEntitlement = { entitlement = it },
            packageName = { "app.example" },
            open = { opened += it },
            receipt = { id, r -> receipts += id to r },
            report = { c, messageId, r -> reports += Triple(c.id, messageId, r) },
        )
    }

    private val active = Entitlement(Entitlement.State.Active, "pro", source = Entitlement.Source.Play)
    private val none = Entitlement(Entitlement.State.None, source = Entitlement.Source.Play)
    private fun card(action: Card.StoreAction.Action, product: String? = null) = Card.StoreAction("c1", action, productId = product)

    @Test fun restoreFindsAPurchase() = runTest {
        val h = Harness(entitlement = none, store = active)
        assertTrue(h.actions.act(card(Card.StoreAction.Action.RestorePurchases)))
        assertEquals(listOf("c1" to StoreReceipt.Restored), h.receipts)
        assertEquals(active, h.entitlement)
        assertTrue(h.opened.isEmpty(), "restore stays in the app")
    }

    @Test fun restoreSaysWhenThereIsNothingOrNothingNew() = runTest {
        Harness(entitlement = null, store = none).also {
            it.actions.act(card(Card.StoreAction.Action.RestorePurchases))
            assertEquals(StoreReceipt.NothingToRestore, it.receipts.single().second)
        }
        Harness(entitlement = active, store = active).also {
            it.actions.act(card(Card.StoreAction.Action.RestorePurchases))
            assertEquals(StoreReceipt.UpToDate, it.receipts.single().second)
        }
        Harness(entitlement = active, reachable = false).also {
            it.actions.act(card(Card.StoreAction.Action.RestorePurchases))
            assertEquals(StoreReceipt.Unavailable, it.receipts.single().second)
            assertEquals(active, it.entitlement, "an unreachable store never clears the entitlement")
        }
    }

    @Test fun afterATripToPlayAChangeLeavesAReceipt() = runTest {
        val h = Harness(entitlement = active.copy(state = Entitlement.State.BillingRetry), store = active)
        h.actions.act(card(Card.StoreAction.Action.BillingIssue, "pro"))
        assertEquals(listOf("https://play.google.com/store/account/subscriptions?sku=pro&package=app.example"), h.opened)
        assertTrue(h.receipts.isEmpty(), "nothing until the person comes back")
        h.actions.onForeground()
        assertEquals(listOf("c1" to StoreReceipt.Updated), h.receipts)
        assertEquals(Entitlement.State.Active, h.entitlement?.state)
        h.actions.onForeground()
        assertEquals(1, h.receipts.size, "one check per trip")
    }

    @Test fun noChangeNoReceipt() = runTest {
        val h = Harness(entitlement = active, store = active)
        h.actions.act(card(Card.StoreAction.Action.ManageSubscriptions))
        h.actions.onForeground()
        assertTrue(h.receipts.isEmpty())
        // A foreground without a trip to Play checks nothing.
        Harness(entitlement = none, store = active).also {
            it.actions.onForeground()
            assertTrue(it.receipts.isEmpty())
        }
    }

    @Test fun resultsAreReportedWithTheCardsMessage() = runTest {
        val h = Harness(entitlement = active.copy(state = Entitlement.State.BillingRetry), store = active)
        h.actions.act(card(Card.StoreAction.Action.BillingIssue, "pro"), "m1")
        h.actions.onForeground()
        assertEquals(listOf(Triple<String, String?, StoreReceipt>("c1", "m1", StoreReceipt.Updated)), h.reports.toList())
        Harness(entitlement = active, reachable = false).also {
            it.actions.act(card(Card.StoreAction.Action.RestorePurchases), "m2")
            assertEquals(StoreReceipt.Unavailable, it.receipts.single().second)
            assertTrue(it.reports.isEmpty(), "an unreachable store isn't reported")
        }
    }

    @Test fun wireResultsFollowTheProtocol() {
        assertEquals("payment_updated", StoreActions.wireResult(Card.StoreAction.Action.BillingIssue, StoreReceipt.Updated))
        assertEquals("subscription_updated", StoreActions.wireResult(Card.StoreAction.Action.ManageSubscriptions, StoreReceipt.Updated))
        assertEquals("purchases_restored", StoreActions.wireResult(Card.StoreAction.Action.RestorePurchases, StoreReceipt.Restored))
        assertEquals("nothing_to_restore", StoreActions.wireResult(Card.StoreAction.Action.RestorePurchases, StoreReceipt.NothingToRestore))
        assertEquals("up_to_date", StoreActions.wireResult(Card.StoreAction.Action.RestorePurchases, StoreReceipt.UpToDate))
        assertNull(StoreActions.wireResult(Card.StoreAction.Action.RestorePurchases, StoreReceipt.Unavailable), "an unreachable store isn't reported")
        assertEquals("manage_subscriptions", StoreActions.wireAction(Card.StoreAction.Action.Unknown))
        assertEquals("request_refund", StoreActions.wireAction(Card.StoreAction.Action.RequestRefund))
        val body = WireJson.encodeToString(
            dev.pingfront.internal.wire.StoreActionResultRequest.serializer(),
            dev.pingfront.internal.wire.StoreActionResultRequest("c", "billing_issue", "payment_updated", "c1", "m1"),
        )
        assertEquals("""{"clientId":"c","action":"billing_issue","result":"payment_updated","cardId":"c1","messageId":"m1"}""", body)
        val none = WireJson.decodeFromString(dev.pingfront.internal.wire.StoreActionResultResponse.serializer(), """{"message":null}""")
        assertNull(none.message)
    }

    @Test fun anUnknownActionOpensTheSubscriptionPage() = runTest {
        val h = Harness()
        assertTrue(h.actions.act(card(Card.StoreAction.Action.Unknown)))
        assertEquals(listOf("https://play.google.com/store/account/subscriptions"), h.opened)
    }

    @Test fun aBillingIssueShowsPlaysOwnMessageFirst() = runTest {
        for ((fix, opened, receipt) in listOf(
            Triple(PaymentFix.Updated, false, StoreReceipt.Updated),
            Triple(PaymentFix.Dismissed, false, null),
            Triple(PaymentFix.NothingToShow, true, null),
        )) {
            val h = Harness(entitlement = active.copy(state = Entitlement.State.BillingRetry), store = active, fixer = PaymentFixer { fix })
            h.actions.act(card(Card.StoreAction.Action.BillingIssue, "pro"))
            assertEquals(opened, h.opened.isNotEmpty(), "$fix: the subscription page only when Play had nothing to show")
            assertEquals(receipt, h.receipts.singleOrNull()?.second, "$fix")
        }
    }
}

class PlanStatusWireTest {
    @Test fun theServersPlanDecodesWithItsDates() {
        val person = WireJson.decodeFromString(
            dev.pingfront.model.PersonSummary.serializer(),
            """{"id":"p_1","level":"named","entitlement":{"state":"grace","productId":"pro","gracePeriodEndsAt":"2026-11-04T09:00:00Z",""" +
                """"expiresAt":"2026-11-04T09:00:00Z","source":"play"}}""",
        )
        val e = person.entitlement!!
        assertEquals(Entitlement.State.Grace, e.state)
        assertEquals(java.time.Instant.parse("2026-11-04T09:00:00Z"), e.gracePeriodEndsAt)
        assertEquals(java.time.Instant.parse("2026-11-04T09:00:00Z"), e.expiresAt)
        assertEquals(Entitlement.Source.Play, e.source)
    }

    @Test fun anUnknownStateDecodesAsUnknownAndNoPlanIsNull() {
        val person = WireJson.decodeFromString(
            dev.pingfront.model.PersonSummary.serializer(),
            """{"id":"p_1","level":"guest","entitlement":{"state":"paused"}}""",
        )
        assertEquals(Entitlement.State.Unknown, person.entitlement?.state)
        assertNull(WireJson.decodeFromString(dev.pingfront.model.PersonSummary.serializer(), """{"id":"p_1","level":"guest"}""").entitlement)
    }
}

/** Found on the emulator end to end: a local server advertises `ws://localhost`, which the device can't reach. */
class LiveUrlTest {
    private fun api(base: String, scope: kotlinx.coroutines.test.TestScope) = dev.pingfront.internal.net.ApiClient(
        base,
        "pk",
        dev.pingfront.testing.ScriptedTransport(),
        dev.pingfront.internal.runtime.CooldownBook(dev.pingfront.testing.TestTime(scope)),
        kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler),
    )

    @Test fun aLoopbackLiveUrlIsIgnoredWhenTheBaseUrlIsNot() = runTest {
        val device = api("http://10.0.2.2:8807/v1", this)
        assertEquals("ws://10.0.2.2:8807/v1/live", device.liveUrl("ws://localhost:8807/v1/live"))
        assertEquals("ws://10.0.2.2:8807/v1/live", device.liveUrl("ws://127.0.0.1:8807/v1/live"))
        assertEquals("wss://live.example.com/v1/live", device.liveUrl("wss://live.example.com/v1/live"), "a real host is used as given")
        val local = api("http://localhost:8807/v1", this)
        assertEquals("ws://localhost:9000/v1/live", local.liveUrl("ws://localhost:9000/v1/live"), "loopback to loopback is fine")
    }
}
