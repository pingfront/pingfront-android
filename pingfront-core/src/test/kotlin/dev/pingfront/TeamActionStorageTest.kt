@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.storage.Records
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private fun action(id: String) = """{"id":"$id","actionId":"extend_trial","name":"Extend trial","days":7,""" +
    """"ackRequired":true,"createdAt":"2026-10-31T10:00:00Z","expiresAt":"2026-11-07T10:00:00Z"}"""

/**
 * Review AN-9 (3 Nov 2026): team-action answers must survive storage errors, so an action never runs twice. Before,
 * an answer whose write failed (full disk, locked Keystore) was lost although it counted as given, one storage error
 * ended the answer loop for the process, and an action delivered once came back after a restart while its answer was
 * not stored.
 */
class TeamActionStorageTest {
    private fun harness(scope: kotlinx.coroutines.test.TestScope, persistence: MemoryPersistence, transport: ScriptedTransport = ScriptedTransport()) =
        EngineHarness(scope, transport, persistence).also {
            it.transport.json("GET team-actions", body = """{"actions":[${action("ta_1")}]}""")
            it.transport.on("POST team-actions/ta_1/ack") { HttpResponse(204, emptyMap(), ByteArray(0)) }
        }

    @Test fun anAnswerWhoseWriteFailedIsStillSent() = runTest {
        val persistence = MemoryPersistence()
        val h = harness(this, persistence)
        h.engine.session.token(SessionReason.ChatOpened)
        advanceUntilIdle()
        val got = mutableListOf<TeamAction>()
        val collector = launch { h.engine.teamActions.actions.take(1).toList(got) }
        h.engine.teamActions.catchUp(h.engine.session.generation())
        advanceUntilIdle()
        collector.join()
        persistence.unavailable = true
        got.single().complete()
        advanceUntilIdle()
        persistence.unavailable = false
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(1, h.transport.count("POST team-actions/ta_1/ack"), "the answer was lost with its write")
        h.stop()
    }

    @Test fun theAnswerLoopOutlivesAStorageError() = runTest {
        val persistence = MemoryPersistence()
        val h = harness(this, persistence)
        h.engine.session.token(SessionReason.ChatOpened)
        advanceUntilIdle()
        val got = mutableListOf<TeamAction>()
        val collector = launch { h.engine.teamActions.actions.take(1).toList(got) }
        h.engine.teamActions.catchUp(h.engine.session.generation())
        advanceUntilIdle()
        collector.join()
        // The queue cannot be read when the loop wakes, then can again.
        persistence.unavailable = true
        h.engine.teamActions.wakeUp()
        advanceUntilIdle()
        persistence.unavailable = false
        got.single().complete()
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(1, h.transport.count("POST team-actions/ta_1/ack"), "the answer loop died on the storage error")
        h.stop()
    }

    @Test fun anActionDeliveredInAnEarlierLaunchIsNotDeliveredAgain() = runTest {
        val persistence = MemoryPersistence()
        val first = harness(this, persistence)
        first.engine.session.token(SessionReason.ChatOpened)
        advanceUntilIdle()
        val got = mutableListOf<TeamAction>()
        val collector = launch { first.engine.teamActions.actions.take(1).toList(got) }
        first.engine.teamActions.catchUp(first.engine.session.generation())
        advanceUntilIdle()
        collector.join()
        // The app started the work and was killed before it answered.
        first.stop()
        val second = harness(this, persistence)
        val again = mutableListOf<TeamAction>()
        val watcher = launch { second.engine.teamActions.actions.collect { again += it } }
        second.engine.teamActions.catchUp(second.engine.session.generation())
        advanceUntilIdle()
        runCurrent()
        watcher.cancel()
        assertEquals(emptyList(), again.map { it.id }, "the app would do ta_1 a second time")
        assertEquals(true, persistence.names().any { it.startsWith(Records.TEAM_ACTIONS_DELIVERED) })
        second.stop()
    }
}
