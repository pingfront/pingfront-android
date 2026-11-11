@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.conversation.LiveState
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeSocket
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

internal class EngineHarness(
    test: TestScope,
    val transport: ScriptedTransport = ScriptedTransport(),
    val persistence: MemoryPersistence = MemoryPersistence(),
    context: dev.pingfront.internal.context.ContextSource = dev.pingfront.internal.context.ContextSource.None,
    signedOut: dev.pingfront.internal.storage.SignedOutFlag = dev.pingfront.internal.storage.SignedOutFlag.Memory(),
) {
    val instance = PingFrontInstance()
    val engine = Engine(
        instance,
        AppKey.parse(KEY).getOrThrow(),
        EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
        test.testDependencies(transport, persistence, context = context, signedOut = signedOut),
    )
    val events = mutableListOf<PingFrontEvent>()

    init {
        transport.json("POST sessions", body = Bodies.session())
        transport.json("GET config", status = 404, body = Bodies.error("not_found"))
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        instance.engine = engine
        engine.start()
        engine.scope.launch { instance.events.collect { events += it } }
    }

    fun openChat(socket: FakeSocket = FakeSocket()): FakeSocket {
        transport.socketFactory = { _, _ -> socket }
        instance.chatVisibilityChanged(true)
        return socket
    }

    fun stop() = engine.stop()

    companion object {
        const val KEY = "pk_test_abcdefghijklmnopqrstuvvd"
    }
}

class EngineLiveTest {
    @Test fun aGapIsRepairedAndRepairedMessagesCountAsReceived() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.transport.json("GET thread/messages", body = Bodies.page(false, Bodies.message(2), Bodies.message(3), Bodies.message(4)))
        h.engine.loadThread()
        h.engine.apply(
            ServerEvent.NewMessage(
                4,
                dev.pingfront.internal.wire.WireJson.decodeFromString(dev.pingfront.model.Message.serializer(), Bodies.message(4)),
            ),
        )
        advanceUntilIdle()
        assertTrue(h.transport.last("GET thread/messages")!!.url.contains("after=1"))
        assertEquals(listOf(1, 2, 3, 4), h.instance.conversation.state.value.messages.map { it.seq })
        assertEquals(4, h.engine.lastSeq())
        assertEquals(3, h.instance.unreadCount.value, "chat closed: each repaired team message counts once")
        assertEquals(3, h.events.filterIsInstance<PingFrontEvent.MessageReceived>().size)
        h.stop()
    }

    @Test fun aFailedRepairKeepsLastSeq() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.transport.offline("GET thread/messages")
        h.engine.loadThread()
        h.engine.apply(
            ServerEvent.NewMessage(
                5,
                dev.pingfront.internal.wire.WireJson.decodeFromString(dev.pingfront.model.Message.serializer(), Bodies.message(5)),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, h.engine.lastSeq())
        assertTrue(h.instance.conversation.state.value.messages.any { it.seq == 5 }, "the message still shows")
        h.stop()
    }

    @Test fun outboxAcksOlderPagesAndReportsNeverMoveLastSeq() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(3, Bodies.message(3)))
        h.engine.loadThread()
        h.transport.json("POST thread/messages", body = Bodies.sent(9, "00000000-0000-4000-8000-000000000001"))
        h.instance.conversation.send("hello")
        advanceUntilIdle()
        assertTrue(h.instance.conversation.state.value.messages.any { it.seq == 9 })
        assertEquals(3, h.engine.lastSeq())
        h.stop()
    }

    @Test fun resetKeepsTheDraftAndPendingRows() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(2, Bodies.message(1), Bodies.message(2)))
        h.engine.loadThread()
        h.instance.conversation.updateDraft("half written")
        h.transport.offline("POST sessions")
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1), rev = 2))
        h.engine.apply(ServerEvent.Reset)
        advanceUntilIdle()
        assertEquals("half written", h.instance.conversation.draft.value)
        assertEquals(listOf(1), h.instance.conversation.state.value.messages.map { it.seq })
        h.stop()
    }

    @Test fun threadResetDropsTheCacheAndSendsTheNewRevision() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(2, Bodies.message(2), rev = 1))
        h.engine.loadThread()
        h.transport.json("POST thread/read", status = 409, body = Bodies.error("thread_reset"))
        val reset = kotlin.runCatching {
            h.engine.client.send(dev.pingfront.internal.net.Endpoints.markRead(dev.pingfront.internal.wire.ReadRequest(2)))
        }
        assertEquals(ErrorCode.ThreadReset, (reset.exceptionOrNull() as? PingFrontException)?.code)
        assertEquals("1", h.transport.last("POST thread/read")?.headers?.get("PF-Thread-Rev"))
        h.instance.conversation.updateDraft("keep me")
        h.transport.json("GET thread", body = Bodies.thread(5, Bodies.message(5), rev = 2))
        h.engine.noteFailure(reset.exceptionOrNull() as PingFrontException)
        runCurrent()
        assertEquals(listOf(5), h.instance.conversation.state.value.messages.map { it.seq })
        assertEquals("2", h.engine.client.threadRev.get())
        assertEquals("keep me", h.instance.conversation.draft.value)
        h.stop()
    }

    @Test fun helloCarriesRevTeamReadSeqAndStatus() = runTest {
        val h = EngineHarness(this)
        h.engine.apply(
            ServerEvent.Hello(lastSeq = 0, readSeq = 0, rev = 4, teamReadSeq = 3, status = dev.pingfront.model.ThreadStatus.Resolved),
        )
        assertEquals("4", h.engine.client.threadRev.get())
        assertEquals(3, h.instance.conversation.state.value.teamReadSeq)
        assertEquals(dev.pingfront.model.ThreadStatus.Resolved, h.instance.conversation.state.value.status)
        h.stop()
    }

    @Test fun aLateHelloWithoutAThreadKeepsTheStoredRevision() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1), rev = 2))
        h.engine.loadThread()
        assertEquals("2", h.engine.client.threadRev.get())
        // The server's real frame before the first message: no threadId at all, rev 0.
        val hello = ServerEvent.decode("""{"type":"hello","rev":0,"status":"open","teamReadSeq":0,"lastSeq":0,"readSeq":0}""") as ServerEvent.Hello
        h.engine.apply(hello)
        assertEquals("2", h.engine.client.threadRev.get())
        val explicit = ServerEvent.decode("""{"type":"hello","threadId":null,"rev":0,"lastSeq":0,"readSeq":0}""") as ServerEvent.Hello
        h.engine.apply(explicit)
        assertEquals("2", h.engine.client.threadRev.get())
        h.stop()
    }

    @Test fun typingClearsItselfAfterEightSeconds() = runTest {
        val h = EngineHarness(this)
        h.engine.apply(ServerEvent.Typing(isAi = false, name = "Deniz", on = true))
        assertEquals("Deniz", h.instance.conversation.state.value.typing?.name)
        advanceTimeBy(8_001)
        runCurrent()
        assertNull(h.instance.conversation.state.value.typing)
        h.stop()
    }
}

class EngineLifecycleTest {
    @Test fun noNetworkUntilSomethingNeedsIt() = runTest {
        val h = EngineHarness(this)
        h.instance.identify("u1", "Ada", "ada@example.com")
        advanceUntilIdle()
        assertTrue(h.transport.requests.isEmpty())
        h.stop()
    }

    @Test fun sendsAreQueuedInOrderAndDelivered() = runTest {
        val h = EngineHarness(this)
        val order = mutableListOf<String>()
        h.transport.on("POST thread/messages") { request ->
            val text = Regex("\"text\":\"([^\"]+)\"").find(request.body?.decodeToString().orEmpty())!!.groupValues[1]
            val clientId = Regex("\"clientId\":\"([^\"]+)\"").find(request.body?.decodeToString().orEmpty())!!.groupValues[1]
            order += text
            HttpResponse(200, emptyMap(), Bodies.sent(order.size, clientId).encodeToByteArray())
        }
        listOf("one", "two", "three").forEach { h.instance.conversation.send(it) }
        advanceUntilIdle()
        assertEquals(listOf("one", "two", "three"), order)
        assertTrue(h.instance.conversation.state.value.pending.isEmpty())
        h.stop()
    }

    @Test fun logoutCancelsTheDrainAndNothingIsSentAsTheNextPerson() = runTest {
        val h = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.transport.on("POST uploads") {
            gate.await()
            HttpResponse(
                200,
                emptyMap(),
                """{"attachmentId":"a1","putUrl":"https://up/x","headers":{},"expiresAt":"2026-10-30T19:00:00Z"}""".encodeToByteArray(),
            )
        }
        h.transport.json("POST thread/messages", body = Bodies.sent(1, "00000000-0000-4000-8000-000000000001"))
        h.transport.json("POST me/logout", status = 204, body = "")
        h.engine.session.token(dev.pingfront.internal.wire.SessionReason.Prepare)
        h.engine.outbox.enqueue(
            dev.pingfront.internal.messaging.OutboxItem(
                dev.pingfront.conversation.PendingMessage(
                    "c1",
                    "with photo",
                    listOf(dev.pingfront.conversation.PendingMessage.Photo("photo-c1-0", 1, 1, 1, "ab")),
                    java.time.Instant.EPOCH,
                ),
            ),
            listOf(byteArrayOf(1)),
        )
        h.engine.coalescer.activate()
        h.engine.session.token(dev.pingfront.internal.wire.SessionReason.Prepare)
        h.instance.logout()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, h.transport.count("POST thread/messages"))
        assertTrue(h.engine.outbox.all().isEmpty())
        assertEquals(1, h.transport.count("POST me/logout"))
        h.stop()
    }

    @Test fun eraseIsIdempotent() = runTest {
        val h = EngineHarness(this)
        h.engine.session.token(dev.pingfront.internal.wire.SessionReason.Prepare)
        h.transport.json("DELETE me", status = 401, body = Bodies.error("session_revoked"))
        h.instance.eraseMyData()
        assertNull(dev.pingfront.internal.storage.RecordStore(h.persistence).read(StoredRecords.credentials))
        h.instance.eraseMyData()
        h.stop()
    }

    @Test fun exportMyDataStartsTheSessionAndReturnsTheJson() = runTest {
        val h = EngineHarness(this)
        val body = """{"format":"pingfront.person.v1","exportedAt":"2026-10-31T10:00:00Z","app":{"id":"app_1","name":"Clockwise"},"person":{}}"""
        h.transport.json("GET me/export", body = body)
        assertEquals(body, h.instance.exportMyData())
        assertEquals(1, h.transport.count("POST sessions"))
        assertTrue(h.transport.last("GET me/export")!!.headers["Authorization"]!!.startsWith("Bearer "))
        h.stop()
    }

    @Test fun exportMyDataRateLimitedThenCoolsDown() = runTest {
        val h = EngineHarness(this)
        h.transport.json(
            "GET me/export",
            status = 429,
            body = Bodies.error("rate_limited", extra = ""","retryAfter":600,"scope":"exports""""),
        )
        val limited = kotlin.test.assertFailsWith<PingFrontException.Server> { h.instance.exportMyData() }
        assertEquals(ErrorCode.RateLimited, limited.error.code)
        assertEquals(RateScope.Exports, limited.error.scope)
        val cooling = kotlin.test.assertFailsWith<PingFrontException.CoolingDown> { h.instance.exportMyData() }
        assertEquals(RateScope.Exports, cooling.scope)
        assertEquals(1, h.transport.count("GET me/export"))
        h.stop()
    }

    @Test fun exportMyDataNeedsConfigure() = runTest {
        kotlin.test.assertFailsWith<PingFrontException.NotConfigured> { PingFrontInstance().exportMyData() }
    }

    @Test fun liveSocketUsesSubprotocolsAndReachesLive() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(0))
        var protocols: List<String> = emptyList()
        val socket = FakeSocket()
        h.transport.socketFactory = { _, p ->
            protocols = p
            socket
        }
        h.instance.chatVisibilityChanged(true)
        runCurrent()
        socket.push("""{"type":"hello","lastSeq":0,"readSeq":0}""")
        runCurrent()
        assertEquals(listOf("pingfront.v1", "bearer.tok-1"), protocols)
        assertEquals(LiveState.Live, h.instance.conversation.state.value.live)
        h.instance.chatVisibilityChanged(false)
        runCurrent()
        assertTrue(socket.closed)
        h.stop()
    }

    @Test fun eventsFlowCarriesUnreadChanges() = runTest {
        val h = EngineHarness(this)
        val collected = mutableListOf<PingFrontEvent>()
        val job = launch { h.instance.events.toList(collected) }
        runCurrent()
        h.instance.setUnread(2)
        runCurrent()
        assertTrue(PingFrontEvent.UnreadChanged(2) in collected)
        assertFalse(h.instance.isChatVisible.value)
        job.cancel()
        h.stop()
    }
}
