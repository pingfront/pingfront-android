@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review AN-14 (3 Nov 2026), found while recording the paywall scenario on the emulator: Clockwise reports
 * `paywall_closed` from its plans screen and then leaves it, which cancels the screen's coroutine scope. The moment
 * was cancelled after `GET /moments/listened` and never sent, so the play that answers a closed paywall never ran.
 * Reporting a moment as a screen closes is the main way apps use it.
 */
class MomentOutlivesCallerTest {
    @Test fun aMomentIsSentWhenTheCallingScreenGoesAway() = runTest {
        val h = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.transport.on("GET moments/listened") {
            gate.await()
            HttpResponse(200, emptyMap(), """{"names":["paywall_closed"]}""".encodeToByteArray())
        }
        h.transport.json("POST moments", body = """{"outcome":"scheduled","open":false}""")
        val screen = launch { h.instance.moment("paywall_closed", mapOf("screen" to "plans")) }
        runCurrent()
        screen.cancel() // the plans screen closed, and its scope went with it
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.transport.count("POST moments"), "the moment was dropped with the screen that reported it")
        h.stop()
    }
}
