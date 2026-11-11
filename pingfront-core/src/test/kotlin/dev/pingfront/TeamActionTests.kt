@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.QueuedTeamActionAck
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.TeamActionAck
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private fun payload(id: String, actionId: String = "extend_trial") =
    """{"id":"$id","actionId":"$actionId","name":"Extend trial","fields":{"note":"sorry"},"days":7,""" +
        """"ackRequired":true,"createdAt":"2026-10-31T10:00:00Z","expiresAt":"2026-11-07T10:00:00Z"}"""

class TeamActionTests {
    @Test fun theLiveEventDecodesWithoutASeqAndUnknownTypesStayUnknown() {
        val event = assertIs<ServerEvent.TeamAction>(ServerEvent.decode("""{"type":"team_action","action":${payload("ta_1")}}"""))
        assertEquals("extend_trial", event.action.actionId)
        assertEquals(7, event.action.days)
        assertEquals(mapOf("note" to "sorry"), event.action.fields)
        assertIs<ServerEvent.Unknown>(ServerEvent.decode("""{"type":"team_poll"}"""))
        assertNull(ServerEvent.decode("""{"type":"team_action","action":{"id":"x"}}"""), "a broken action is dropped, never a crash")
    }

    @Test fun catchUpAndLiveAreDeduplicatedById() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET team-actions", body = """{"actions":[${payload("ta_1")},${payload("ta_2", "grant_access")}]}""")
        val got = mutableListOf<TeamAction>()
        val collector = launch { h.engine.teamActions.actions.take(2).toList(got) }
        h.engine.teamActions.catchUp(h.engine.session.generation())
        // The same action live, and the catch-up after a reconnect: still once each.
        h.engine.teamActions.received(
            ServerEvent.decode("""{"type":"team_action","action":${payload("ta_1")}}""").let {
                (it as ServerEvent.TeamAction).action
            },
        )
        h.engine.teamActions.catchUp(h.engine.session.generation())
        advanceUntilIdle()
        collector.join()
        assertEquals(listOf("ta_1", "ta_2"), got.map { it.id })
        h.stop()
    }

    @Test fun anActionAnsweredInAnEarlierLaunchIsNotDeliveredAgain() = runTest {
        // The app completed ta_1 before it was killed; the ack is still queued (the server answered 503 so far).
        val persistence = MemoryPersistence()
        RecordStore(persistence).write(StoredRecords.teamActionAcks, listOf(QueuedTeamActionAck("ta_1", TeamActionAck(ok = true))))
        val transport = ScriptedTransport()
        transport.on("POST team-actions/ta_1/ack") { HttpResponse(503, emptyMap(), ByteArray(0)) }
        transport.json("GET team-actions", body = """{"actions":[${payload("ta_1")},${payload("ta_2")}]}""")
        val h = EngineHarness(this, transport, persistence)
        val got = mutableListOf<TeamAction>()
        val collector = launch { h.engine.teamActions.actions.collect { got += it } }
        try {
            // The next launch's catch-up still lists ta_1 as pending: the server has no answer yet.
            h.engine.teamActions.catchUp(h.engine.session.generation())
            advanceTimeBy(1_000)
            runCurrent()
        } finally {
            // The ack keeps failing: stop the engine so its retry loop ends with the test.
            collector.cancel()
            h.stop()
        }
        assertEquals(listOf("ta_2"), got.map { it.id }, "ta_1 was already done: the app must not do it twice")
    }

    @Test fun aQueuedAnswerWaitsForASessionAndNeverStartsOneAtLaunch() = runTest {
        // An install from an earlier launch, with an answer the server does not have yet.
        val persistence = MemoryPersistence()
        val records = RecordStore(persistence)
        records.write(StoredRecords.credentials, InstallCredentials("0b6f3c1e-8a52-4c1f-9d7e-2f4a6b8c0d1e", "is_test"))
        records.write(StoredRecords.teamActionAcks, listOf(QueuedTeamActionAck("ta_1", TeamActionAck(ok = true))))
        val transport = ScriptedTransport()
        transport.json("POST sessions/refresh", body = Bodies.refresh())
        transport.json("GET team-actions", body = """{"actions":[]}""")
        transport.on("POST team-actions/ta_1/ack") { HttpResponse(204, emptyMap(), ByteArray(0)) }
        val h = EngineHarness(this, transport, persistence)
        try {
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, transport.count("POST sessions/refresh"), "the launch starts no session for a queued answer")
            assertEquals(0, transport.count("POST team-actions/ta_1/ack"))
            // The next session (here: the chat opens) sends it.
            h.engine.session.token(SessionReason.ChatOpened)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, transport.count("POST team-actions/ta_1/ack"), "sent once a session exists")
        } finally {
            h.stop()
        }
    }

    @Test fun completeAndFailAckOnceAndAreRetriedUntilTheServerHasThem() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET team-actions", body = """{"actions":[${payload("ta_1")},${payload("ta_2")}]}""")
        var failures = 1
        h.transport.on("POST team-actions/ta_1/ack") {
            if (failures-- > 0) HttpResponse(503, emptyMap(), ByteArray(0)) else HttpResponse(204, emptyMap(), ByteArray(0))
        }
        h.transport.json("POST team-actions/ta_2/ack", status = 409, body = """{"error":{"code":"conflict","message":"expired"}}""")
        val got = mutableListOf<TeamAction>()
        val collector = launch { h.engine.teamActions.actions.take(2).toList(got) }
        h.engine.teamActions.catchUp(h.engine.session.generation())
        advanceUntilIdle()
        collector.join()
        got[0].complete()
        got[0].complete()
        got[1].fail("Billing is down")
        advanceUntilIdle()
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(2, h.transport.count("POST team-actions/ta_1/ack"), "one retry after the 503, and the repeat tap sends nothing")
        assertTrue(h.transport.last("POST team-actions/ta_1/ack")!!.body!!.decodeToString().contains("\"ok\":true"))
        val failed = h.transport.last("POST team-actions/ta_2/ack")!!.body!!.decodeToString()
        assertTrue(failed.contains("\"ok\":false") && failed.contains("Billing is down"))
        assertEquals(1, h.transport.count("POST team-actions/ta_2/ack"), "409 (expired): dropped, never retried")
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertEquals(2, h.transport.count("POST team-actions/ta_1/ack"), "delivered: never sent again")
        h.stop()
    }

    /**
     * Audit 6 to 8: a paused app (403 app_paused) is temporary, so the answer is kept; no retry runs while paused (it
     * retried every ~30 s, in the background too); it survives the process; and the next session sends it, here a real
     * session refresh in the next launch.
     */
    @Test fun anAnswerRefusedWhileTheAppIsPausedIsKeptAndSentByTheNextSession() = runTest {
        val persistence = MemoryPersistence()
        val paused = ScriptedTransport()
        paused.json("POST sessions", body = Bodies.session())
        paused.json("GET team-actions", body = """{"actions":[${payload("ta_1")}]}""")
        paused.on("POST team-actions/ta_1/ack") {
            HttpResponse(403, emptyMap(), """{"error":{"code":"app_paused","message":"paused"}}""".encodeToByteArray())
        }
        val first = EngineHarness(this, paused, persistence)
        first.engine.session.token(SessionReason.Prepare)
        val got = mutableListOf<TeamAction>()
        val collector = launch { first.engine.teamActions.actions.take(1).toList(got) }
        first.engine.teamActions.catchUp(first.engine.session.generation())
        advanceUntilIdle()
        collector.join()
        got[0].complete()
        advanceUntilIdle()
        assertEquals(1, paused.count("POST team-actions/ta_1/ack"), "refused once")
        advanceTimeBy(10 * 60_000)
        advanceUntilIdle()
        assertEquals(1, paused.count("POST team-actions/ta_1/ack"), "no requests while paused")
        first.stop()
        assertEquals(
            listOf("ta_1"),
            RecordStore(persistence).read(StoredRecords.teamActionAcks)?.map { it.id },
            "the answer is stored, not dropped",
        )

        // The next launch, the app resumed: the answer is still queued, and the session refresh sends it.
        val resumed = ScriptedTransport()
        resumed.json("POST sessions", body = Bodies.session())
        resumed.json("POST sessions/refresh", body = Bodies.refresh())
        resumed.json("GET team-actions", body = """{"actions":[]}""")
        resumed.on("POST team-actions/ta_1/ack") { HttpResponse(204, emptyMap(), ByteArray(0)) }
        val second = EngineHarness(this, resumed, persistence)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, resumed.count("POST team-actions/ta_1/ack"), "nothing at launch")
        second.engine.session.token(SessionReason.ChatOpened)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, resumed.count("POST team-actions/ta_1/ack"), "kept across the process and sent by the next session")
        second.stop()
    }
}

class BrandingTest {
    @Test fun brandingFollowsTheConfigAndIsOffWhenMissing() {
        val instance = PingFrontInstance()
        dev.pingfront.internal.debug.Fixtures.load(instance, dev.pingfront.internal.debug.Fixtures.Kind.Welcome)
        assertEquals(false, instance.showsBranding.value, "missing means false")
        dev.pingfront.internal.debug.Fixtures.setBranding(instance)
        assertEquals(true, instance.showsBranding.value)
        dev.pingfront.internal.debug.Fixtures.setBranding(instance, on = false)
        assertEquals(false, instance.showsBranding.value)
        val config = dev.pingfront.internal.wire.WireJson.decodeFromString(
            dev.pingfront.internal.wire.AppConfig.serializer(),
            """{"revision":1,"appName":"A","features":{"branding":true}}""",
        )
        assertTrue(config.features.branding)
    }
}
