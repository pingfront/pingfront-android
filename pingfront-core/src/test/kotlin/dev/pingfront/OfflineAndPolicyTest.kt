@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.conversation.ConversationPhase
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.model.SendPolicy
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeNetwork
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

/**
 * Audit round 2 (6 Nov 2026) and N10. Offline, a queued message started a send that hung until its timeout, so it
 * said "Sending…" for about a minute; a thread that failed to load offline never loaded again by itself. And a send
 * the server refused because the person can't send (blocked, paused, verification) failed only that message: the
 * composer stayed open until the next thread load.
 */
class OfflineAndPolicyTest {
    private class Rig(val instance: PingFrontInstance, val engine: Engine, val transport: ScriptedTransport, val net: FakeNetwork) {
        val online get() = net.online
    }

    private fun TestScope.rig(online: Boolean): Rig {
        val net = FakeNetwork(online)
        val transport = ScriptedTransport().apply {
            json("POST sessions", body = Bodies.session())
            json("GET config", status = 404, body = Bodies.error("not_found"))
            json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
            json("GET thread", body = Bodies.thread(0))
            json("GET team-actions", body = """{"actions":[]}""")
            json("POST me/logout", status = 204, body = "")
        }
        val instance = PingFrontInstance()
        val engine = Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, false, true),
            testDependencies(transport, context = net),
        )
        instance.engine = engine
        engine.start()
        return Rig(instance, engine, transport, net)
    }

    private fun ScriptedTransport.echoSends() = on("POST thread/messages") {
        val clientId = WireJson.parseToJsonElement(it.body!!.decodeToString()).jsonObject["clientId"]!!.toString().trim('"')
        HttpResponse(200, emptyMap(), Bodies.sent(1, clientId).encodeToByteArray())
    }

    private fun Rig.stop() {
        instance.chatVisibilityChanged(false)
        engine.stop()
    }

    /** Offline, a queued message is never put on the wire: it stays queued (the UI says "Waiting for connection"). */
    @Test fun offlineNothingIsSentAndTheMessageStaysQueued() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        r.transport.echoSends()
        r.online.value = false
        runCurrent()
        r.instance.conversation.send("Hello from the bus")
        advanceTimeBy(90_000)
        runCurrent()
        val pending = r.instance.conversation.state.value.pending.single()
        assertEquals(PendingMessage.State.Queued, pending.state, "never Sending while offline")
        assertEquals(0, pending.attempts)
        assertEquals(0, r.transport.count("POST thread/messages"))
        assertTrue(r.instance.conversation.state.value.isOffline)
        // The network returns: the outbox drains at once.
        r.online.value = true
        advanceUntilIdle()
        assertEquals(1, r.transport.count("POST thread/messages"))
        assertTrue(r.instance.conversation.state.value.pending.isEmpty())
        r.stop()
    }

    /** A thread that failed to load offline loads again when the network returns, with no tap on anything. */
    @Test fun aThreadThatFailedOfflineLoadsWhenTheNetworkReturns() = runTest {
        val r = rig(online = false)
        r.transport.offline("POST sessions")
        r.instance.chatVisibilityChanged(true)
        runCurrent()
        assertIs<ConversationPhase.Failed>(r.instance.conversation.state.value.phase)
        r.transport.json("POST sessions", body = Bodies.session())
        r.online.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConversationPhase.Ready, r.instance.conversation.state.value.phase)
        r.stop()
    }

    /** A send refused as blocked closes the policy at once; the next thread load with canSend opens it again. */
    @Test fun aRefusedSendClosesThePolicyAndTheServerReopensIt() = runTest {
        for ((code, reason) in listOf(
            "blocked" to SendPolicy.Reason.Blocked,
            "app_paused" to SendPolicy.Reason.AppPaused,
            "verification_required" to SendPolicy.Reason.VerificationRequired,
        )) {
            val r = rig(online = true)
            r.engine.session.token(SessionReason.Prepare)
            r.engine.loadThread()
            advanceUntilIdle()
            r.transport.json("POST thread/messages", status = 403, body = Bodies.error(code))
            r.instance.conversation.send("Hello?")
            advanceUntilIdle()
            val policy = r.instance.conversation.state.value.policy
            assertEquals(SendPolicy.Closed(reason), policy, code)
            assertEquals(PendingMessage.State.Failed, r.instance.conversation.state.value.pending.single().state)
            // A refused send reports a paused app's debug problem as every other refused request does.
            if (code == "app_paused") assertEquals(ConfigurationProblem.Kind.AppPaused, r.instance.configurationProblem.value?.kind)
            // A pause or a required sign-in holds the message; a blocked person's fails plainly (X1, as on iOS).
            assertEquals(code != "blocked", r.instance.conversation.state.value.pending.single().isHeld, code)
            r.transport.echoSends()
            r.engine.loadThread()
            advanceUntilIdle()
            assertEquals(SendPolicy.Open, r.instance.conversation.state.value.policy, "$code: the server says canSend again")
            if (code == "app_paused") assertEquals(null, r.instance.configurationProblem.value, "and it clears with the policy")
            // What was held goes by itself; a plain failure waits for the person.
            assertEquals(code == "blocked", r.instance.conversation.state.value.pending.isNotEmpty(), code)
            r.stop()
        }
    }

    /** Online, but the server can't be reached: that is not "offline", and the system's own offline wins. */
    @Test fun anUnreachableServerIsNotTheDeviceBeingOffline() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        r.transport.offline("GET thread")
        r.engine.loadThread()
        val state = r.instance.conversation.state.value
        assertTrue(state.isOffline)
        assertTrue(state.isServerUnreachable)
        r.online.value = false
        runCurrent()
        assertTrue(r.instance.conversation.state.value.isOffline)
        assertEquals(false, r.instance.conversation.state.value.isServerUnreachable, "the device itself is offline")
        r.stop()
    }

    /**
     * S2 (audit 2): the network callback was registered at launch on every install. Now none at launch, one once a
     * session exists, one (not two) while the chat is also open, and none after logout with the chat closed.
     */
    @Test fun theNetworkCallbackIsRegisteredOnlyWhileNeeded() = runTest {
        val r = rig(online = true)
        runCurrent()
        assertEquals(0, r.net.callbacks, "a new install registers nothing at launch")
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        assertEquals(1, r.net.callbacks, "a session watches the network")
        r.instance.chatVisibilityChanged(true)
        runCurrent()
        assertEquals(1, r.net.callbacks, "the chat shares the one callback")
        r.instance.chatVisibilityChanged(false)
        r.instance.logout()
        advanceUntilIdle()
        assertEquals(0, r.net.callbacks, "gone after logout")
        r.instance.chatVisibilityChanged(true)
        runCurrent()
        assertEquals(1, r.net.callbacks, "the open chat watches it without a session")
        r.stop()
        runCurrent()
        assertEquals(0, r.net.callbacks, "gone with the engine")
    }

    /**
     * Audit 3: every online reading cleared "server unreachable", so the banner flickered with each network callback.
     * Switching networks (mobile data to Wi-Fi) while the server is down keeps it, and wakes the outbox at once.
     */
    @Test fun aNetworkSwitchKeepsUnreachableAndTriesAgainAtOnce() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        r.engine.loadThread()
        advanceUntilIdle()
        r.transport.offline("GET thread")
        r.transport.offline("POST thread/messages")
        r.engine.loadThread()
        assertTrue(r.instance.conversation.state.value.isServerUnreachable)
        r.instance.conversation.send("Still there?")
        // The send's own quick retries end; the outbox then waits its backoff (a second at first).
        fun waiting() = r.instance.conversation.state.value.pending.singleOrNull()?.state == PendingMessage.State.Queued &&
            r.instance.conversation.state.value.pending.single().attempts > 0
        repeat(100) { if (!waiting()) advanceTimeBy(10) }
        assertTrue(waiting(), "the first send failed and waits")
        val tries = r.transport.count("POST thread/messages")
        // Every state the chat sees during the switch, not only the last one.
        val seen = mutableListOf<Boolean>()
        val watch = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            r.instance.conversation.state.collect { seen += it.isServerUnreachable }
        }
        // Another network, the server still down: the banner stays, and the outbox tries again without its backoff.
        r.net.network.value = 2L
        runCurrent()
        watch.cancel()
        assertTrue(seen.all { it }, "the banner dropped during the switch: $seen")
        assertTrue(r.transport.count("POST thread/messages") > tries, "the switch woke the outbox")
        // The server answers again: the message goes, and only then the banner clears.
        r.transport.echoSends()
        r.net.network.value = 3L
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(r.instance.conversation.state.value.pending.isEmpty(), "sent")
        assertEquals(false, r.instance.conversation.state.value.isServerUnreachable)
        r.stop()
    }

    /**
     * Audit 4, B1: a paused app refused the session refresh; only a debug banner said so, the composer stayed open with
     * "Trying again soon", and the chat retried every five seconds (17 refreshes in 76 s). Now the refusal closes the
     * policy, the open chat checks again once a minute (PausedRecheck), and reopening the chat after the app is resumed
     * restores sending and delivers what was queued.
     */
    @Test fun aPausedAppClosesSendingWithoutARetryStormAndReopens() = runTest {
        val r = rig(online = true)
        r.transport.json("POST sessions", status = 403, body = Bodies.error("app_paused"))
        r.instance.chatVisibilityChanged(true)
        runCurrent()
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), r.instance.conversation.state.value.policy)
        assertEquals(ConfigurationProblem.Kind.AppPaused, r.instance.configurationProblem.value?.kind)
        val opens = r.transport.count("POST sessions")
        advanceTimeBy(76_000)
        runCurrent()
        assertEquals(opens + 1, r.transport.count("POST sessions"), "no retry storm while paused: one check a minute")
        // Resumed in the dashboard: the next chat open gets a session, the thread says canSend, the queue drains.
        r.transport.json("POST sessions", body = Bodies.session())
        r.transport.echoSends()
        r.instance.chatVisibilityChanged(false)
        r.transport.socketFactory = { _, _ -> dev.pingfront.testing.FakeSocket() }
        r.instance.chatVisibilityChanged(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(SendPolicy.Open, r.instance.conversation.state.value.policy)
        assertEquals(null, r.instance.configurationProblem.value, "the debug banner goes with the refusal")
        r.instance.conversation.send("Back?")
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(r.instance.conversation.state.value.pending.isEmpty())
        assertEquals(1, r.transport.count("POST thread/messages"))
        r.stop()
    }

    /**
     * Audit 5, 2a: with the app paused, the live channel's 403 was an ordinary failure and the socket was retried every
     * 30 s for as long as the chat stayed open. Now the refusal stops the loop, and the thread load it triggers carries
     * the policy that says why.
     */
    @Test fun aRefusedLiveChannelStopsAndClosesSending() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        var sockets = 0
        // The app is paused after the chat loaded: the upgrade is refused (with no readable body here), and the
        // thread load that follows carries the policy.
        r.transport.socketFactory = { _, _ ->
            sockets += 1
            r.transport.json(
                "GET thread",
                body = """{"thread":null,"messages":[],"hasOlder":false,"team":[],"policy":{"canSend":false,"reason":"app_paused"}}""",
            )
            dev.pingfront.testing.FakeSocket().also { it.fail(403) }
        }
        r.instance.chatVisibilityChanged(true)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, sockets, "one refused upgrade in two minutes, not one every 30 s")
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), r.instance.conversation.state.value.policy)
        assertEquals(dev.pingfront.conversation.LiveState.Stopped, r.instance.conversation.state.value.live)
        r.stop()
    }

    /** 2b: the transport hears of every lost, returned or switched network, and not of a capability change. */
    @Test fun theTransportHearsOfNetworkChanges() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val before = r.transport.networkChanges
        r.net.online.value = false
        runCurrent()
        r.net.online.value = true
        runCurrent()
        r.net.network.value = 7L
        runCurrent()
        assertEquals(before + 3, r.transport.networkChanges)
        r.net.captivePortal.value = false
        runCurrent()
        assertEquals(before + 3, r.transport.networkChanges, "nothing changed")
        r.stop()
    }

    /**
     * Audit 6, S3: a paused foreground started the live socket and scheduled a read receipt, both certain 403s, and the
     * refused upgrade then reloaded the thread again. Now the thread load is the only request: no socket, no read.
     */
    @Test fun aClosedThreadOpensNoSocketAndSendsNoRead() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        var sockets = 0
        r.transport.socketFactory = { _, _ ->
            sockets += 1
            dev.pingfront.testing.FakeSocket()
        }
        r.transport.json(
            "GET thread",
            body = """{"thread":{"id":"t1","status":"open","lastSeq":1,"readSeq":0,"teamReadSeq":0,"unread":1,"rev":1},""" +
                """"messages":[${Bodies.message(1, "team", "sent", null)}],"hasOlder":false,"team":[],""" +
                """"policy":{"canSend":false,"reason":"app_paused"}}""",
        )
        val threads = r.transport.count("GET thread")
        r.instance.chatVisibilityChanged(true)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(threads + 1, r.transport.count("GET thread"), "exactly the thread load")
        assertEquals(0, sockets, "no live socket while sending is closed")
        assertEquals(0, r.transport.count("POST thread/read"), "no read receipt while sending is closed")
        // Resumed: the next load that says canSend opens the live channel, whatever order the foreground came in.
        r.transport.json("GET thread", body = Bodies.thread(0))
        r.engine.loadThread()
        runCurrent()
        assertEquals(1, sockets, "the live channel opens once sending is open again")
        r.stop()
    }

    /**
     * Audit 7: only a paused app is refused the live channel and read receipts; a blocked or unverified person is refused
     * sends only. With the chat open they still get live replies and send reads.
     */
    @Test fun aBlockedPersonKeepsTheLiveChannelAndReadReceipts() = runTest {
        for (reason in listOf("blocked", "verification_required")) {
            val r = rig(online = true)
            r.engine.session.token(SessionReason.Prepare)
            advanceUntilIdle()
            var sockets = 0
            r.transport.socketFactory = { _, _ ->
                sockets += 1
                dev.pingfront.testing.FakeSocket()
            }
            r.transport.json("POST thread/read", status = 204, body = "")
            r.transport.json(
                "GET thread",
                body = """{"thread":{"id":"t1","status":"open","lastSeq":1,"readSeq":0,"teamReadSeq":0,"unread":1,"rev":1},""" +
                    """"messages":[${Bodies.message(1, "team", "sent", null)}],"hasOlder":false,"team":[],""" +
                    """"policy":{"canSend":false,"reason":"$reason"}}""",
            )
            r.instance.chatVisibilityChanged(true)
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(!r.instance.conversation.state.value.policy.canSend, reason)
            assertEquals(1, sockets, "$reason: the live channel opens")
            assertTrue(r.transport.count("POST thread/read") >= 1, "$reason: the read receipt is sent")
            r.stop()
        }
    }

    /**
     * Audit 8: a paused app refuses GET /unread too, and the badge polled it every 20 s to 2 min regardless. Now the
     * refusal closes the policy and polling skips; the next foreground asks once, and an answer resumes polling.
     */
    @Test fun aPausedAppIsNotPolledForUnreadUntilItIsResumed() = runTest {
        val transport = ScriptedTransport()
        val persistence = dev.pingfront.testing.MemoryPersistence()
        dev.pingfront.internal.storage.RecordStore(persistence).write(
            dev.pingfront.internal.storage.StoredRecords.credentials,
            dev.pingfront.internal.wire.InstallCredentials("8a1f6f2e-1111-4222-8333-944455556666", "secret-1"),
        )
        transport.json("POST sessions/refresh", body = Bodies.refresh())
        transport.json("GET unread", status = 403, body = Bodies.error("app_paused"))
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        val lifecycle = kotlinx.coroutines.flow.MutableSharedFlow<AppLifecycleEvent>()
        val instance = PingFrontInstance()
        val engine = Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, false, true),
            testDependencies(transport, persistence, lifecycle),
        )
        instance.engine = engine
        engine.start()
        runCurrent()
        lifecycle.emit(AppLifecycleEvent.Foreground)
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(1, transport.count("GET unread"), "refused once")
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), instance.conversation.state.value.policy)
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, transport.count("GET unread"), "nothing polled while paused")
        // Resumed in the dashboard: the next foreground asks once, the answer reopens, and polling resumes.
        transport.json("GET unread", body = """{"unread":2,"lastSeq":0,"live":false}""")
        lifecycle.emit(AppLifecycleEvent.Background)
        runCurrent()
        lifecycle.emit(AppLifecycleEvent.Foreground)
        runCurrent()
        assertEquals(2, transport.count("GET unread"), "one probe on the foreground")
        assertEquals(SendPolicy.Open, instance.conversation.state.value.policy)
        assertEquals(2, instance.unreadCount.value)
        // Past the first two minutes of activity, the schedule is every 2 min.
        advanceTimeBy(150_000)
        runCurrent()
        assertTrue(transport.count("GET unread") >= 3, "polling resumed")
        engine.stop()
    }

    /** A refused upgrade carries the server's reason in its body: sending closes without another thread load. */
    @Test fun aRefusedUpgradeSaysWhyWithoutReloadingTheThread() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        r.transport.socketFactory = { _, _ -> dev.pingfront.testing.FakeSocket().also { it.fail(403, Bodies.error("app_paused")) } }
        val threads = r.transport.count("GET thread")
        r.instance.chatVisibilityChanged(true)
        // Under a minute: the paused chat's own once-a-minute check (PausedRecheck) hasn't come yet.
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(SendPolicy.Closed(SendPolicy.Reason.AppPaused), r.instance.conversation.state.value.policy)
        assertEquals(threads + 1, r.transport.count("GET thread"), "the chat's own load, no extra one for the refusal")
        r.stop()
    }

    /** Behind a captive portal the system says so: the chat is unreachable at once, and back when the portal is passed. */
    @Test fun aCaptivePortalIsTheChatOutOfReach() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        r.net.captivePortal.value = true
        runCurrent()
        assertTrue(r.instance.conversation.state.value.isServerUnreachable)
        r.net.captivePortal.value = false
        runCurrent()
        assertEquals(false, r.instance.conversation.state.value.isOffline)
        r.stop()
    }

    /** N15: the SDK-presented chat closes and the call returns once it is gone; an embedded chat is not waited for. */
    @Test fun closeChatAndWaitWaitsOnlyForThePresentedChat() = runTest {
        val r = rig(online = true)
        var dismissed = 0
        r.instance.registry.chatPresenter = object : dev.pingfront.ChatPresenter {
            override fun presentChat(topic: dev.pingfront.look.ChatTopic?) = Unit

            override fun dismissChat() {
                dismissed += 1
                // The sheet activity goes off screen a moment later.
                r.engine.scope.launch {
                    kotlinx.coroutines.delay(300)
                    r.instance.presentedChatChanged(false)
                }
            }

            override fun openUrl(url: String) = Unit

            override fun presentSimpleReport(prefill: String?) = Unit
        }
        // Only an embedded chat on screen: the presenter is still told to close (a sheet may sit behind Home), and
        // nothing is waited for.
        r.instance.chatVisibilityChanged(true)
        val start = testScheduler.currentTime
        r.instance.closeChatAndWait()
        assertEquals(start, testScheduler.currentTime, "returns at once")
        assertEquals(1, dismissed)
        // The sheet on screen: it is closed and the call returns when it has gone.
        r.instance.presentedChatChanged(true)
        r.instance.closeChatAndWait()
        assertEquals(2, dismissed)
        assertEquals(300L, testScheduler.currentTime - start)
        assertEquals(false, r.instance.presentedChat.value)
        // Counted: one presented chat stopping while another is on screen leaves it on screen.
        r.instance.presentedChatChanged(true)
        r.instance.presentedChatChanged(true)
        r.instance.presentedChatChanged(false)
        assertEquals(true, r.instance.presentedChat.value)
        r.instance.presentedChatChanged(false)
        assertEquals(false, r.instance.presentedChat.value)
        r.stop()
    }

    /** Other refusals stay with the message: the policy is not touched. */
    @Test fun anOrdinaryRefusalLeavesThePolicyOpen() = runTest {
        val r = rig(online = true)
        r.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        r.transport.json("POST thread/messages", status = 422, body = Bodies.error("invalid_request"))
        r.instance.conversation.send("x")
        advanceUntilIdle()
        assertEquals(SendPolicy.Open, r.instance.conversation.state.value.policy)
        r.stop()
    }
}
