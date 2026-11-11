@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import androidx.work.ListenableWorker
import dev.pingfront.internal.android.OutboxRetryWorker
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.storage.FileSignedOutFlag
import dev.pingfront.internal.storage.SignedOutFlag
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private fun action(id: String): String {
    val times = """"createdAt":"2026-10-31T10:00:00Z","expiresAt":"2026-11-07T10:00:00Z""""
    return """{"id":"$id","actionId":"extend_trial","name":"Extend trial","fields":{},"days":7,"ackRequired":true,$times}"""
}

/** The Android robustness audit of 8 Nov 2026. */
class RobustnessAuditTest {
    /** Queued writes back off to 5 minutes (the protocol, Retrying writes), not the live channel's 30 s. */
    @Test fun aFailingAckBacksOffPastThirtySeconds() = runTest {
        val h = EngineHarness(this)
        val times = mutableListOf<Long>()
        h.transport.on("POST team-actions/ta_1/ack") {
            times += testScheduler.currentTime
            HttpResponse(503, emptyMap(), Bodies.error("unavailable").encodeToByteArray())
        }
        h.engine.session.token(SessionReason.Prepare)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.engine.teamActions.ack("ta_1", ok = true, message = null)
        advanceTimeBy(10.minutes)
        runCurrent()
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue(gaps.max() >= 60_000, "the longest wait between ack attempts was ${gaps.max()} ms")
        assertTrue(gaps.max() <= 5.minutes.inWholeMilliseconds * 6 / 5 + 1_000, "the wait passed the 5-minute cap")
        h.stop()
    }

    /** An ack in flight at logout never starts a guest session nobody asked for (a 401 used to refresh with no generation). */
    @Test fun theAckDrainEndsWithLogout() = runTest {
        val h = EngineHarness(this)
        h.transport.json("POST me/logout", status = 204, body = "")
        val gate = CompletableDeferred<Unit>()
        h.transport.on("POST team-actions/ta_1/ack") {
            gate.await()
            HttpResponse(401, emptyMap(), Bodies.error("unauthorized").encodeToByteArray())
        }
        h.engine.session.token(SessionReason.Prepare)
        advanceTimeBy(1.seconds)
        runCurrent()
        h.engine.teamActions.ack("ta_1", ok = true, message = null)
        runCurrent()
        assertEquals(1, h.transport.count("POST team-actions/ta_1/ack"))
        val sessions = h.transport.count("POST sessions")
        h.instance.logout()
        runCurrent()
        gate.complete(Unit)
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(sessions, h.transport.count("POST sessions"), "an ack from before logout created a session")
        assertEquals(1, h.transport.count("POST team-actions/ta_1/ack"), "the previous person's ack was sent again")
        h.stop()
    }

    /** One team action this SDK cannot decode drops that action, not the whole catch-up. */
    @Test fun aBrokenTeamActionKeepsTheRestOfTheCatchUp() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET team-actions", body = """{"actions":[{"id":"ta_bad","actionId":"x"},${action("ta_2")}]}""")
        val got = mutableListOf<TeamAction>()
        val collector = launch { h.engine.teamActions.actions.take(1).toList(got) }
        h.engine.session.token(SessionReason.Prepare)
        h.engine.teamActions.catchUp(h.engine.session.generation())
        advanceTimeBy(1.seconds)
        runCurrent()
        collector.join()
        assertEquals(listOf("ta_2"), got.map { it.id })
        h.stop()
    }

    /**
     * Logged out offline (the `/me/logout` still queued, so the server keeps the push token), the process killed, and an
     * FCM message cold-starts it: the previous person's notification is not shown.
     */
    @Test fun aPushAfterAnOfflineLogoutAndAColdStartShowsNothing() = runTest {
        val persistence = MemoryPersistence()
        val flag = SignedOutFlag.Memory()
        val h = EngineHarness(this, ScriptedTransport(), persistence, signedOut = flag)
        h.transport.offline("POST me/logout")
        h.engine.session.token(SessionReason.Prepare)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(h.instance.acceptsPush(null))
        h.instance.logout()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(h.instance.acceptsPush(null))
        h.stop()

        // The cold start: a new engine, nothing in memory.
        val next = EngineHarness(this, ScriptedTransport(), persistence, signedOut = flag)
        runCurrent()
        assertFalse(next.instance.acceptsPush(null), "a push for the logged-out person would be shown")
        // The next person's session: pushes are shown again.
        next.engine.session.token(SessionReason.ChatOpened)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(next.instance.acceptsPush(null))
        next.stop()
    }

    /** Before configure (lazy React Native or Flutter), the gate reads the marker files; nobody known means nothing shown. */
    @Test fun theFileFlagSurvivesTheProcessAndGatesBeforeConfigure() = runTest {
        val root = kotlin.io.path.createTempDirectory("pingfront").toFile()
        try {
            assertFalse(FileSignedOutFlag.anySignedIn(root), "no session ever started here")
            val io = StandardTestDispatcher(testScheduler)
            val key = File(root, "0123456789abcdef")
            val flag = FileSignedOutFlag(key, io)
            key.mkdirs()
            assertFalse(flag.isSet())
            assertTrue(FileSignedOutFlag.anySignedIn(root))
            flag.set(true)
            assertTrue(FileSignedOutFlag(key, io).isSet(), "read again as by a new process")
            assertFalse(FileSignedOutFlag.anySignedIn(root))
            flag.set(false)
            assertFalse(flag.isSet())
            assertTrue(FileSignedOutFlag.anySignedIn(root))
            assertFalse(PingFrontInstance().acceptsPush(null), "no engine and no context: nobody is signed in")
        } finally {
            root.deleteRecursively()
        }
    }

    /** A failed erase leaves the person signed in, and the open chat gets its live channel back. */
    @Test fun aFailedEraseReopensTheLiveChannel() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.transport.json("POST sessions/refresh", body = Bodies.refresh())
        h.transport.json("DELETE me", status = 400, body = Bodies.error("bad_request"))
        // A new socket for every upgrade, so a closed one is never handed out again.
        h.transport.socketFactory = { _, _ -> dev.pingfront.testing.FakeSocket() }
        h.instance.chatVisibilityChanged(true)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, h.transport.sockets.size)
        val result = runCatching { h.instance.eraseMyData() }
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(result.isFailure, "the erase was meant to fail")
        assertTrue(h.transport.sockets.first().closed, "the erase closes the socket first")
        assertEquals(2, h.transport.sockets.size, "the live channel stayed closed after the failed erase")
        assertFalse(h.transport.sockets.last().closed)
        h.stop()
    }

    /** A process WorkManager woke before the app configured the SDK: the retry stays scheduled instead of being dropped. */
    @Test fun theOutboxRetryWaitsForConfigureInsteadOfSucceeding() = runTest {
        assertEquals(ListenableWorker.Result.retry(), OutboxRetryWorker.outcome(null))
        val h = EngineHarness(this)
        assertEquals(ListenableWorker.Result.success(), OutboxRetryWorker.outcome(h.engine))
        h.stop()
        advanceUntilIdle()
    }
}
