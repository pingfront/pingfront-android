@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.wire.SessionReason
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Review AN-11 (3 Nov 2026): ARCHITECTURE promises that `eraseMyData()` always completes. When the engine stopped
 * (a reconfigure, or a React Native reload) while an erase was running or still queued, the caller waited forever.
 */
class EraseCompletesTest {
    @Test fun everyEraseCompletesWhenTheEngineStops() = runTest {
        val h = EngineHarness(this)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        h.transport.on("DELETE me") {
            gate.await()
            HttpResponse(204, emptyMap(), ByteArray(0))
        }
        val first = async { withTimeoutOrNull(60_000) { runCatching { h.instance.eraseMyData() } } }
        val second = async { withTimeoutOrNull(60_000) { runCatching { h.instance.eraseMyData() } } }
        runCurrent()
        h.stop()
        advanceUntilIdle()
        assertNotNull(first.await(), "the running erase never completed")
        assertNotNull(second.await(), "the queued erase never completed")
        val late = async { withTimeoutOrNull(60_000) { runCatching { h.instance.eraseMyData() } } }
        advanceUntilIdle()
        assertNotNull(late.await(), "an erase submitted after stop never completed")
    }
}
