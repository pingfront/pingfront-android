@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review AN-10 (3 Nov 2026): only the staged profile was kept on disk; what the server already had lived in memory.
 * So the first session refresh of every process sent the whole profile again in `PATCH /me` (identity with the email,
 * traits, push, offers, entitlement), although nothing had changed. With push, that refresh is the launch unread check.
 */
class ProfileResendTest {
    @Test fun aNewProcessSendsOnlyWhatChanged() = runTest {
        val persistence = MemoryPersistence()
        val first = EngineHarness(this, ScriptedTransport(), persistence)
        first.instance.identify("user-a", "Ada", "ada@example.com")
        first.instance.setTraits(mapOf("plan" to "pro"))
        runCurrent()
        first.engine.session.token(SessionReason.Prepare)
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val firstPatches = first.transport.count("PATCH me")
        first.stop()

        // The next launch: the install's session is refreshed, nothing was changed.
        val second = EngineHarness(this, ScriptedTransport(), persistence)
        second.transport.json("POST sessions/refresh", body = Bodies.refresh())
        second.engine.session.token(SessionReason.ChatOpened)
        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals(1, second.transport.count("POST sessions/refresh"))
        assertEquals(
            0,
            second.transport.count("PATCH me"),
            "the unchanged profile went out again: ${second.transport.last("PATCH me")?.body?.decodeToString()}",
        )

        // A real change still goes out, and only that change.
        second.instance.setTraits(mapOf("plan" to "team"))
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val patch = second.transport.last("PATCH me")!!.body!!.decodeToString()
        assertTrue(patch.contains("\"plan\":\"team\"") && !patch.contains("ada@example.com"), patch)
        assertEquals(0, firstPatches, "the first process sent everything with POST /sessions")
        second.stop()
    }
}
