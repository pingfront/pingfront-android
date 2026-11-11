@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.testing.Bodies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review AN-4 (3 Nov 2026): "Delete my data" tapped while the install's first `POST /sessions` was still in flight.
 * The erase read no credentials, so it sent no `DELETE /me` and dropped the create's answer, yet the server had
 * already made the person (with the staged name and email). `eraseMyData()` returned success and that person could
 * never be erased from this device.
 */
class EraseDuringCreateTest {
    @Test fun eraseDuringTheFirstSessionCreateErasesWhatTheCreateMade() = runTest {
        val h = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.transport.on("POST sessions") {
            gate.await()
            HttpResponse(200, emptyMap(), Bodies.session().encodeToByteArray())
        }
        h.transport.json("DELETE me", status = 204, body = "")
        h.instance.identify("user-a", "Ada", "ada@example.com")
        runCurrent()
        val prepare = async { runCatching { h.instance.prepare() } }
        runCurrent()
        val erase = async { runCatching { h.instance.eraseMyData() } }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(erase.await().isSuccess, "eraseMyData failed: ${erase.await().exceptionOrNull()}")
        prepare.await()
        assertEquals(1, h.transport.count("DELETE me"), "the install the create made on the server was never erased")
        assertNull(RecordStore(h.persistence).read(StoredRecords.credentials), "a session was started again after the erase")
        h.stop()
    }
}
