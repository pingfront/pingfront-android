@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeSocket
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Behaviour fixes from the UI and UX audit of the SDK (31 Oct 2026) that live in core. */
class ChatAuditTest {
    /**
     * A-03: "You're offline" showed while messages were delivered, because it was derived from the live socket.
     * Offline now follows the system's network and failed requests; a reconnecting socket alone is not offline.
     */
    @Test fun offlineFollowsTheNetworkNotTheSocket() = runTest {
        val net = dev.pingfront.testing.FakeNetwork(true)
        val online = net.online
        val transport = ScriptedTransport().apply {
            json("POST sessions", body = Bodies.session())
            json("GET config", status = 404, body = Bodies.error("not_found"))
            json("GET thread", body = Bodies.thread(0))
        }
        val instance = PingFrontInstance()
        val deps = testDependencies(transport, context = net)
        val engine = Engine(instance, AppKey.parse(EngineHarness.KEY).getOrThrow(), EngineSettings(ScriptedTransport.BASE_URL, false, true), deps)
        instance.engine = engine
        engine.start()
        try {
            check(instance, engine, transport, online)
        } finally {
            instance.chatVisibilityChanged(false)
            engine.stop()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.check(
        instance: PingFrontInstance,
        engine: Engine,
        transport: ScriptedTransport,
        online: MutableStateFlow<Boolean>,
    ) {
        engine.session.token(SessionReason.ChatOpened)
        // The socket keeps failing as if offline, but REST works: no "offline".
        transport.socketFactory = { _, _ -> FakeSocket().also { it.fail() } }
        instance.chatVisibilityChanged(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(instance.conversation.state.value.isOffline, "a reconnecting socket is not offline")
        online.value = false
        runCurrent()
        assertTrue(instance.conversation.state.value.isOffline, "the system lost the network")
        online.value = true
        runCurrent()
        assertFalse(instance.conversation.state.value.isOffline)
        // A request that cannot reach the server says offline until the next one succeeds.
        transport.offline("GET thread")
        engine.loadThread()
        assertTrue(instance.conversation.state.value.isOffline)
        transport.json("GET thread", body = Bodies.thread(0))
        engine.loadThread()
        assertFalse(instance.conversation.state.value.isOffline)
    }

    /** Promotional offers: the card's store offer reaches the host's offer handler. */
    @Test fun theOfferCardsStoreOfferReachesTheHost() = runTest {
        // Card actions hop to the main thread for the host's callbacks.
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val h = EngineHarness(this)
        val requests = mutableListOf<OfferRequest>()
        h.instance.onOffer = { requests += it }
        val offer = dev.pingfront.model.StoreOffer(dev.pingfront.model.StoreOffer.Kind.Promotional, "yearly-40", "pro", "yearly", "yearly-40")
        h.engine.act(dev.pingfront.model.Card.Offer("c1", "yearly_winback", "40% off", null, "Get it", offer), "m1", null, null)
        advanceUntilIdle()
        assertEquals(offer, requests.single().storeOffer)
        // An unknown kind only opens the paywall: no store offer reaches the host or the billing module.
        h.engine.act(
            dev.pingfront.model.Card.Offer("c2", "yearly_winback", "Gift", null, "Get it", offer.copy(kind = dev.pingfront.model.StoreOffer.Kind.Unknown)),
            "m2",
            null,
            null,
        )
        advanceUntilIdle()
        assertEquals(null, requests.last().storeOffer)
        assertEquals("yearly_winback", requests.last().offerId)
        h.stop()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /**
     * Found on the emulator (1 Nov 2026): with no Play account, tapping an offer did nothing visible. A purchase that
     * fails because Google Play can't be reached marks the card, as store actions do; a later attempt clears it.
     */
    @Test fun anOfferThatCannotReachPlaySaysSo() = runTest {
        val h = EngineHarness(this)
        var outcome: OfferOutcome = OfferOutcome.Failed(OfferFailure.BillingUnavailable)
        h.instance.registry.offerHandler = object : OfferHandler {
            override suspend fun handle(request: OfferRequest): OfferOutcome = outcome
        }
        val card = dev.pingfront.model.Card.Offer("c1", "yearly_winback", "40% off", null, "Get it")
        h.engine.act(card, "m1", null, null)
        advanceUntilIdle()
        assertEquals(dev.pingfront.conversation.StoreReceipt.Unavailable, h.instance.conversation.state.value.storeReceipts["c1"])
        outcome = OfferOutcome.Cancelled
        h.engine.act(card, "m1", null, null)
        advanceUntilIdle()
        assertEquals(null, h.instance.conversation.state.value.storeReceipts["c1"])
        h.stop()
    }

    /** Review: Play In-App Review first; Play may not show it, so the store listing opens only on a second tap. */
    @Test fun aReviewCardOpensTheStoreOnlyOnASecondTap() = runTest {
        // Card actions hop to the main thread for the host's callbacks.
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val h = EngineHarness(this)
        var reviews = 0
        val opened = mutableListOf<String>()
        h.instance.registry.reviewRequester = object : ReviewRequester {
            override suspend fun requestReview(): Boolean {
                reviews += 1
                return true
            }
        }
        h.instance.registry.chatPresenter = object : ChatPresenter {
            override fun presentChat(topic: dev.pingfront.look.ChatTopic?) = Unit

            override fun dismissChat() = Unit

            override fun openUrl(url: String) {
                opened += url
            }

            override fun presentSimpleReport(prefill: String?) = Unit
        }
        val card = dev.pingfront.model.Card.Review("r1", dev.pingfront.model.ReviewMode.NativePrompt, "https://play.google.com/store/apps/details?id=x", "Rate")
        h.engine.act(card, "m1", null, null)
        advanceUntilIdle()
        assertEquals(1, reviews)
        assertTrue(opened.isEmpty(), "the first tap stays in the app")
        h.engine.act(card, "m1", null, null)
        advanceUntilIdle()
        assertEquals(1, reviews)
        assertEquals(listOf("https://play.google.com/store/apps/details?id=x"), opened)
        h.stop()
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
