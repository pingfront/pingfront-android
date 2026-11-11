@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.runtime.Log
import dev.pingfront.model.Card
import dev.pingfront.model.Message
import dev.pingfront.testing.Bodies
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class EventStreamTests {
    @AfterTest fun resetLog() {
        Log.handler = null
        Log.level = LogLevel.Info
    }

    @Test fun messageSentFiresOnceWithoutTheText() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.engine.loadThread()
        h.transport.json("POST thread/messages", body = Bodies.sent(9, "00000000-0000-4000-8000-000000000001"))
        h.instance.conversation.send("hello")
        advanceUntilIdle()
        val sent = h.events.filterIsInstance<PingFrontEvent.MessageSent>()
        assertEquals(listOf(SentMessage("m9", textLength = 4, photoCount = 0, repliesToPing = false)), sent.map { it.message })
        h.stop()
    }

    @Test fun cardOpenedCarriesTheKindAndUnknownCardsEmitNothing() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.engine.loadThread()
        h.transport.json("POST thread/cards/l1/act", body = "{}")
        val message: Message = h.instance.conversation.state.value.messages.first()
        h.instance.conversation.open(Card.Unknown("poll", "u1"), message)
        h.instance.conversation.open(Card.Link("l1", "Docs", "https://example.com", "Open"), message)
        advanceUntilIdle()
        val opened = h.events.filterIsInstance<PingFrontEvent.CardOpened>().map { it.card }
        assertEquals(listOf(OpenedCard(CardKind.Link, "l1", "m1")), opened)
        assertEquals("poll", CardKind.of(Card.Unknown("poll", "u2")).name)
        h.stop()
    }

    @Test fun theHandlerGetsLinesAtOrAboveTheLevelAndCannotBreakTheSdk() {
        val seen = mutableListOf<LogEntry>()
        Log.sink = { _, _ -> }
        Log.level = LogLevel.Info
        Log.handler = LogHandler { seen += it }
        Log.e("Session failed: 401")
        Log.i("Live: connected")
        Log.d("GET /thread 200")
        assertEquals(listOf(LogEntry(LogLevel.Error, "Session failed: 401"), LogEntry(LogLevel.Info, "Live: connected")), seen)
        Log.handler = LogHandler { error("host bug") }
        Log.e("still fine")
        assertTrue(true, "a throwing handler is swallowed")
    }
}
