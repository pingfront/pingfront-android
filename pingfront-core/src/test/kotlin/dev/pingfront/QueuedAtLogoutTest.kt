@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.storage.Records
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review AN-1 (3 Nov 2026): a message the person sent just before logout or erase was still being queued (photos
 * prepared, the outbox written) when the wipe ran. The queueing step then wrote it into the wiped outbox and, with no
 * session left, started a new guest session for it, so the previous person's words went out as the next person.
 */
class QueuedAtLogoutTest {
    @Test fun aMessageStillBeingQueuedAtLogoutIsDroppedAndStartsNoSession() = runTest {
        val persistence = MemoryPersistence()
        val h = EngineHarness(this, persistence = persistence)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.transport.json("POST thread/messages", body = Bodies.sent(1, "00000000-0000-4000-8000-000000000001"))
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val sessions = h.transport.count("POST sessions")
        val gate = CompletableDeferred<Unit>()
        persistence.beforeWrite = { name ->
            if (name == Records.OUTBOX) {
                persistence.beforeWrite = null
                gate.await()
            }
        }
        h.instance.conversation.send("A's note")
        runCurrent() // the message is being written to the outbox
        h.instance.logout()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, h.transport.count("POST thread/messages"), "A's message was sent after logout")
        assertEquals(sessions, h.transport.count("POST sessions"), "a new guest session was started for A's message")
        assertTrue(h.engine.outbox.all().isEmpty(), "the outbox after logout: ${h.engine.outbox.all().map { it.message.text }}")
        h.stop()
    }
}
