@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.wire.SessionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review AN-3 (3 Nov 2026): a call that started before logout (here a moment waiting for the listened list) asked
 * for a token after the wipe. The token request created a new guest session first and only then noticed the
 * session had changed, so the server got a person nobody asked for and the app saw `SessionStarted` after logout.
 */
class SessionAfterLogoutTest {
    @Test fun aCallFromBeforeLogoutNeverStartsTheNextSession() = runTest {
        val h = EngineHarness(this)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val sessions = h.transport.count("POST sessions")
        val gate = CompletableDeferred<Unit>()
        h.transport.on("GET moments/listened") {
            gate.await()
            HttpResponse(200, emptyMap(), """{"names":["signed_out"]}""".encodeToByteArray())
        }
        h.transport.json("POST moments", body = """{"outcome":"ignored","open":false}""")
        val result = async { h.instance.moment("signed_out", emptyMap()) }
        runCurrent()
        h.instance.logout()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(sessions, h.transport.count("POST sessions"), "a session was created after logout for a call from before it")
        assertEquals(0, h.transport.count("POST moments"))
        val failed = assertIs<MomentResult.Failed>(result.await())
        assertIs<PingFrontException.SessionChanged>(failed.error)
        h.stop()
    }
}
