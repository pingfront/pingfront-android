@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.look.ChatTopic
import dev.pingfront.model.Card
import dev.pingfront.model.ChoiceOption
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val ACT = "POST thread/cards/c1/act"

/** K2: card action reports are queued and retried safely (the protocol, "Retrying writes"). */
class CardActionQueueTest {
    private val choices = Card.Choices("c1", listOf(ChoiceOption("yes", "Yes"), ChoiceOption("no", "No")))

    @AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun EngineHarness.bodies() = transport.requests.filter { it.method == "POST" && it.url.endsWith("/thread/cards/c1/act") }
        .map { WireJson.parseToJsonElement(it.body!!.decodeToString()).jsonObject }

    private fun EngineHarness.pick() = engine.act(choices, "m1", "yes", null)

    /** A harness whose session is up and whose start-up requests are done, so they don't wake the queue. */
    private suspend fun TestScope.started(persistence: MemoryPersistence = MemoryPersistence()): EngineHarness {
        val h = EngineHarness(this, persistence = persistence)
        h.engine.session.token(dev.pingfront.internal.wire.SessionReason.Prepare)
        wait(1_000)
        return h
    }

    private fun EngineHarness.answer(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
        transport.on(ACT) { HttpResponse(status, headers, body.encodeToByteArray()) }

    @Test fun aLostAnswerIsRetriedWithTheSameClientIdAndBody() = runTest {
        val h = started()
        h.transport.offline(ACT)
        h.pick()
        wait(100)
        assertEquals(1, h.transport.count(ACT))
        h.answer(204)
        // Backoff before retry 0: 1 s ±20% (the injected jitter is 0).
        wait(800)
        assertEquals(1, h.transport.count(ACT), "not before the backoff")
        wait(400)
        assertEquals(2, h.transport.count(ACT))
        val (first, second) = h.bodies()
        assertEquals(first, second, "same clientId and body")
        assertTrue(first["clientId"]!!.jsonPrimitive.content.matches(Regex("[0-9a-f-]{36}")))
        assertEquals("chosen", first["action"]!!.jsonPrimitive.content)
        assertTrue(h.engine.sender.cardActions.all().isEmpty(), "settled")
        wait(600_000)
        assertEquals(2, h.transport.count(ACT), "nothing after the 204")
        h.stop()
    }

    @Test fun theBackoffDoublesAndTheReportGoesAfterTenAttempts() = runTest {
        val h = started()
        h.answer(503, Bodies.error("unavailable"))
        h.pick()
        wait(100)
        val times = mutableListOf(testScheduler.currentTime)
        repeat(1_000) {
            wait(1_000)
            if (h.transport.count(ACT) > times.size) times += testScheduler.currentTime
        }
        // min(1 s · 2ⁿ, 5 min) between attempts (the injected jitter is 0), and no 11th attempt.
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 64, 128, 256), times.zipWithNext { a, b -> (b - a + 500) / 1_000 })
        assertEquals(10, h.transport.count(ACT))
        assertTrue(h.engine.sender.cardActions.all().isEmpty(), "dropped after about 10 attempts")
        h.stop()
    }

    @Test fun aRateLimitWaitsAtLeastRetryAfter() = runTest {
        val h = started()
        h.answer(429, Bodies.error("rate_limited"), mapOf("retry-after" to "120"))
        h.pick()
        wait(100)
        h.answer(204)
        wait(110_000)
        assertEquals(1, h.transport.count(ACT), "Retry-After is longer than the backoff")
        wait(15_000)
        assertEquals(2, h.transport.count(ACT))
        h.stop()
    }

    @Test fun otherClientErrorsAreNeverRetried() = runTest {
        for (status in listOf(400, 403, 404, 410, 422)) {
            val h = EngineHarness(this)
            h.answer(status, Bodies.error("invalid_request"))
            h.pick()
            wait(600_000)
            assertEquals(1, h.transport.count(ACT), "$status is final")
            assertTrue(h.engine.sender.cardActions.all().isEmpty())
            h.stop()
        }
    }

    @Test fun aThreadResetReloadsAndRetriesOnceWhileTheMessageIsThere() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.answer(409, Bodies.error("thread_reset"))
        h.engine.act(choices, "m1", "yes", null)
        wait(100)
        // The reload found m1, so the report went again at once; a second reset drops it.
        assertEquals(2, h.transport.count(ACT))
        assertTrue(h.transport.count("GET thread") >= 1)
        wait(600_000)
        assertEquals(2, h.transport.count(ACT), "retried once")
        assertTrue(h.engine.sender.cardActions.all().isEmpty())

        // A message the reload no longer has: dropped without a retry.
        h.engine.act(Card.Choices("c2", choices.options), "m_gone", "yes", null)
        h.transport.on("POST thread/cards/c2/act") { HttpResponse(409, emptyMap(), Bodies.error("thread_reset").encodeToByteArray()) }
        wait(600_000)
        assertEquals(1, h.transport.count("POST thread/cards/c2/act"))
        h.stop()
    }

    @Test fun theQueueSurvivesARestartWithItsClientId() = runTest {
        val persistence = MemoryPersistence()
        val first = EngineHarness(this, persistence = persistence)
        first.transport.offline(ACT)
        first.pick()
        wait(100)
        val clientId = first.bodies().single()["clientId"]!!.jsonPrimitive.content
        first.stop()
        assertEquals(clientId, RecordStore(persistence).read(StoredRecords.cardActions)!!.single().request.clientId, "persisted")

        val second = EngineHarness(this, persistence = persistence)
        second.answer(204)
        wait(100)
        assertEquals(clientId, second.bodies().single()["clientId"]!!.jsonPrimitive.content, "resent unchanged")
        assertTrue(RecordStore(persistence).read(StoredRecords.cardActions).isNullOrEmpty())
        second.stop()
    }

    @Test fun aDayOldOrTenTimesTriedReportIsDropped() = runTest {
        val h = EngineHarness(this)
        h.transport.offline(ACT)
        h.pick()
        wait(25 * 3_600_000L)
        assertTrue(h.engine.sender.cardActions.all().isEmpty(), "dropped")
        assertTrue(h.transport.count(ACT) <= 10, "about 10 attempts at most: ${h.transport.count(ACT)}")
        h.stop()
    }

    @Test fun logoutClearsTheQueue() = runTest {
        val persistence = MemoryPersistence()
        val h = EngineHarness(this, persistence = persistence)
        h.transport.offline(ACT)
        h.pick()
        wait(100)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.logout()
        wait(100)
        assertTrue(h.engine.sender.cardActions.all().isEmpty())
        assertNull(RecordStore(persistence).read(StoredRecords.cardActions))
        val tries = h.transport.count(ACT)
        h.answer(204)
        wait(600_000)
        assertEquals(tries, h.transport.count(ACT), "nothing is sent for the previous person")
        h.stop()
    }

    @Test fun aLinkWithADangerousSchemeIsNeverOpenedAndReportsCannotOpen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = EngineHarness(this)
        val opened = mutableListOf<String>()
        h.instance.registry.chatPresenter = object : ChatPresenter {
            override fun presentChat(topic: ChatTopic?) = Unit

            override fun dismissChat() = Unit

            override fun openUrl(url: String) {
                opened += url
            }

            override fun presentSimpleReport(prefill: String?) = Unit
        }
        h.instance.onLink = {
            opened += it
            true
        }
        h.answer(204)
        // Every URL the link card URL rule refuses (`link-urls.json` in the protocol fixtures).
        for (url in LinkUrlRuleTest.urls("refused")) {
            h.engine.act(Card.Link("c1", "t", url, "Open"), "m1", null, null)
            wait(100)
            assertEquals("cannot_open", h.bodies().last()["failure"]!!.jsonPrimitive.content, url)
        }
        assertTrue(opened.isEmpty(), "neither onLink nor the browser saw them: $opened")
        h.stop()
    }
}
