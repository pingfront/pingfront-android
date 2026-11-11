@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.conversation.ConversationPhase
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.look.ChatTopic
import dev.pingfront.model.SendPolicy
import dev.pingfront.model.Translation
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeNetwork
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * X1, matching iOS (`Outbox.drain`, `Engine.signedIn`, `unreadAnswered`, `reopened`, `releaseHeld`): only a pause or a
 * required sign-in holds a message; the queue stays in order behind it; it goes by itself when the server says so, even
 * with the chat closed and after a relaunch. A blocked person's messages fail plainly. And the N cards.
 */
class HeldMessagesTest {
    private class Rig(val instance: PingFrontInstance, val engine: Engine, val transport: ScriptedTransport) {
        val pending get() = instance.conversation.state.value.pending
        val policy get() = instance.conversation.state.value.policy
        val sent get() = transport.requests.filter { transport.key(it) == "POST thread/messages" }.map { text(it.body!!) }

        fun stop() {
            instance.chatVisibilityChanged(false)
            engine.stop()
        }
    }

    private fun TestScope.rig(persistence: MemoryPersistence = MemoryPersistence(), transport: ScriptedTransport = ScriptedTransport()): Rig {
        transport.apply {
            json("POST sessions", body = Bodies.session())
            json("POST sessions/refresh", body = Bodies.refresh())
            json("GET config", status = 404, body = Bodies.error("not_found"))
            json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
            json("GET thread", body = Bodies.thread(0))
            json("GET team-actions", body = """{"actions":[]}""")
        }
        val instance = PingFrontInstance()
        val engine = Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, false, true),
            testDependencies(transport, persistence, context = FakeNetwork(true)),
        )
        instance.engine = engine
        engine.start()
        return Rig(instance, engine, transport)
    }

    private fun ScriptedTransport.echoSends() = on("POST thread/messages") {
        val clientId = WireJson.parseToJsonElement(it.body!!.decodeToString()).jsonObject["clientId"]!!.jsonPrimitive.content
        HttpResponse(200, emptyMap(), Bodies.sent(1, clientId).encodeToByteArray())
    }

    private suspend fun TestScope.started(r: Rig) {
        r.engine.session.token(SessionReason.Prepare)
        r.engine.loadThread()
        advanceUntilIdle()
    }

    private suspend fun TestScope.holdTwo(r: Rig, code: String) {
        r.transport.json("POST thread/messages", status = 403, body = Bodies.error(code))
        r.instance.conversation.send("one")
        r.instance.conversation.send("two")
        advanceUntilIdle()
    }

    @Test fun aPauseHoldsTheHeadAndTheQueueWaitsBehindIt() = runTest {
        val r = rig()
        started(r)
        holdTwo(r, "app_paused")
        assertEquals(listOf("one"), r.sent, "one refusal: the second is never tried out of order")
        val (head, behind) = r.pending
        assertEquals(PendingMessage.State.Failed, head.state)
        assertEquals(SendPolicy.Reason.AppPaused, head.heldFor)
        assertEquals(PendingMessage.State.Queued, behind.state)
        assertNull(behind.heldFor)
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), r.policy)
        // The next drain (a wake, a reconnect) does not jump the held head.
        r.transport.echoSends()
        r.engine.drainForWorker()
        advanceUntilIdle()
        assertEquals(listOf("one"), r.sent)
        // GET /unread answered, with the chat closed: the pause is over, and both go, in order.
        r.transport.json("GET unread", body = """{"unread":0,"lastSeq":0,"live":false}""")
        r.engine.unread.poll()
        advanceUntilIdle()
        assertEquals(listOf("one", "one", "two"), r.sent)
        assertTrue(r.pending.isEmpty())
        r.stop()
    }

    @Test fun aHoldSurvivesARelaunchAndGoesOnTheNextThreadLoad() = runTest {
        val persistence = MemoryPersistence()
        val first = rig(persistence)
        started(first)
        holdTwo(first, "app_paused")
        first.stop()
        advanceUntilIdle()
        val transport = ScriptedTransport().apply { echoSends() }
        val second = rig(persistence, transport)
        second.engine.outbox.load()
        advanceUntilIdle()
        assertEquals(SendPolicy.Reason.AppPaused, second.pending.first().heldFor, "persisted held")
        started(second)
        assertEquals(listOf("one", "two"), second.sent, "a load that allows sending sends what was held, in order")
        assertTrue(second.pending.isEmpty())
        second.stop()
    }

    @Test fun aSignInHoldGoesAfterAPatchLeavesThePersonVerified() = runTest {
        val r = rig()
        started(r)
        holdTwo(r, "verification_required")
        assertEquals(SendPolicy.Reason.VerificationRequired, r.pending.first().heldFor)
        r.transport.echoSends()
        // An unread answer says only that the app isn't paused: a sign-in's hold still waits.
        r.transport.json("GET unread", body = """{"unread":0,"lastSeq":0,"live":false}""")
        r.engine.unread.poll()
        advanceUntilIdle()
        assertEquals(listOf("one"), r.sent)
        r.transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"verified"}}""")
        r.engine.coalescer.stage(ProfileChange.Traits(mapOf("plan" to "pro")))
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals(SendPolicy.Open, r.policy, "the composer the sign-in closed opens again")
        assertEquals(listOf("one", "one", "two"), r.sent)
        assertTrue(r.pending.isEmpty())
        r.stop()
    }

    @Test fun blockedFailsPlainlyAndReleasesWhatWasHeld() = runTest {
        val r = rig()
        started(r)
        holdTwo(r, "blocked")
        assertEquals(listOf("one"), r.sent)
        assertTrue(r.pending.all { it.state == PendingMessage.State.Failed && !it.isHeld }, "nothing promised: plain failures")
        r.stop()

        val held = rig()
        started(held)
        holdTwo(held, "app_paused")
        assertTrue(held.pending.first().isHeld)
        // The thread now says the person is blocked: the held message stops waiting.
        held.transport.json(
            "GET thread",
            body = Bodies.thread(0).replace("\"policy\":{\"canSend\":true}", "\"policy\":{\"canSend\":false,\"reason\":\"blocked\"}"),
        )
        held.engine.loadThread()
        advanceUntilIdle()
        assertFalse(held.pending.first().isHeld)
        assertEquals(PendingMessage.State.Failed, held.pending.first().state)
        held.stop()
    }

    @Test fun deletingTheHeldHeadLetsTheRestGo() = runTest {
        val r = rig()
        started(r)
        holdTwo(r, "app_paused")
        r.transport.echoSends()
        r.instance.conversation.discard(r.pending.first())
        advanceUntilIdle()
        assertEquals(listOf("one", "two"), r.sent)
        assertTrue(r.pending.isEmpty())
        r.stop()
    }

    @Test fun tryAgainClearsAHold() = runTest {
        val r = rig()
        started(r)
        holdTwo(r, "app_paused")
        r.transport.echoSends()
        r.instance.conversation.retry(r.pending.first())
        advanceUntilIdle()
        assertEquals(listOf("one", "one", "two"), r.sent)
        r.stop()
    }

    /** N3: each chat on screen counts once; opened and closed fire for the first to appear and the last to go. */
    @Test fun visibilityCountsPresenters() = runTest {
        val instance = PingFrontInstance.detached()
        val events = mutableListOf<PingFrontEvent>()
        val job = launch { instance.events.collect { events += it } }
        runCurrent()
        val sheet = Any()
        val embedded = Any()
        instance.presenterVisibilityChanged(embedded, true, embedded = true)
        instance.presenterVisibilityChanged(sheet, true, embedded = false)
        instance.presenterVisibilityChanged(sheet, true, embedded = false)
        instance.presenterVisibilityChanged(sheet, false, embedded = false)
        runCurrent()
        assertTrue(instance.isChatVisible.value, "the sheet closed over the embedded chat")
        instance.presenterVisibilityChanged(embedded, false, embedded = true)
        runCurrent()
        assertFalse(instance.isChatVisible.value)
        assertEquals(listOf<PingFrontEvent>(PingFrontEvent.ChatOpened, PingFrontEvent.ChatClosed), events)
        job.cancel()
    }

    /** N7: with an embedded chat on screen, openChat focuses it and applies the prefill instead of presenting. */
    @Test fun openChatFocusesAnEmbeddedChat() = runTest {
        val instance = PingFrontInstance.detached()
        var presented = 0
        instance.registry.chatPresenter = object : ChatPresenter {
            override fun presentChat(topic: ChatTopic?) {
                presented++
            }

            override fun dismissChat() = Unit

            override fun openUrl(url: String) = Unit

            override fun presentSimpleReport(prefill: String?) = Unit
        }
        val focus = mutableListOf<Unit>()
        val job = launch { instance.chatFocusRequests.collect { focus += it } }
        runCurrent()
        instance.openChat(null)
        assertEquals(1, presented)
        instance.presenterVisibilityChanged(this, true, embedded = true)
        instance.openChat(ChatTopic.Billing)
        runCurrent()
        assertEquals(1, presented, "no sheet over the embedded chat")
        assertEquals(1, focus.size)
        job.cancel()
    }

    /** N2: markRead marks what is on screen and drops unread to 0; nothing while paused or with nothing to mark. */
    @Test fun markReadForCustomUis() = runTest {
        val r = rig()
        r.transport.json("GET thread", body = Bodies.thread(2, Bodies.message(1), Bodies.message(2), unread = 2))
        r.transport.json("POST thread/read", body = """{"readSeq":2,"unread":1}""")
        r.engine.session.token(SessionReason.Prepare)
        r.instance.conversation.markRead()
        advanceUntilIdle()
        assertEquals(0, r.transport.count("POST thread/read"), "nothing to mark yet")
        r.engine.loadThread()
        advanceUntilIdle()
        assertEquals(2, r.instance.unreadCount.value)
        r.instance.conversation.markRead()
        advanceUntilIdle()
        assertEquals(1, r.transport.count("POST thread/read"))
        assertTrue(r.transport.last("POST thread/read")!!.body!!.decodeToString().contains("2"))
        assertEquals(0, r.instance.unreadCount.value)
        r.instance.conversation.update { it.copy(policy = SendPolicy.Closed(SendPolicy.Reason.AppPaused)) }
        r.instance.conversation.markRead()
        advanceUntilIdle()
        assertEquals(1, r.transport.count("POST thread/read"), "a paused app refuses reads")
        r.stop()
    }

    /** N14: reload suspends until the reload finished; a failure keeps a thread already on screen. */
    @Test fun reloadReturnsWhenTheReloadFinished() = runTest {
        val r = rig()
        r.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        r.instance.conversation.reload()
        assertEquals(ConversationPhase.Ready, r.instance.conversation.state.value.phase)
        assertEquals(1, r.instance.conversation.state.value.messages.size)
        r.transport.json("GET thread", status = 500, body = Bodies.error("internal"))
        r.instance.conversation.reload()
        assertEquals(ConversationPhase.Ready, r.instance.conversation.state.value.phase, "the thread on screen stays")
        assertEquals(1, r.instance.conversation.state.value.messages.size)
        r.stop()
    }

    /** N8: every log line names its area, as on iOS; translations keep the language they came from. */
    @Test fun logEntriesHaveACategoryAndTranslationsKeepFrom() {
        val entries = mutableListOf<LogEntry>()
        val before = Log.handler
        Log.handler = LogHandler { entries += it }
        try {
            Log.of(Log.Category.Outbox).w("A test line.")
        } finally {
            Log.handler = before
        }
        assertEquals("outbox", entries.single().category)
        val translation = WireJson.decodeFromString<Translation>("""{"language":"de","from":"en","parts":[{"type":"text","text":"Hallo"}]}""")
        assertEquals("en", translation.from)
        assertEquals(listOf("Hallo"), translation.texts)
        assertTrue(WireJson.encodeToString(Translation.serializer(), translation).contains("\"from\":\"en\""))
    }

    private companion object {
        fun text(body: ByteArray): String = WireJson.parseToJsonElement(body.decodeToString()).jsonObject["text"]!!.jsonPrimitive.content
    }
}
