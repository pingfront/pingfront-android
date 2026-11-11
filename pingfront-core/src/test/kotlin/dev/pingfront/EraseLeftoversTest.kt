@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.MemoryPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Review AN-21 (3 Nov 2026): erase left two things of the person behind: records set aside as unreadable
 * (`*.unreadable-N`, still sealed), and the signed attachment URLs cached in memory.
 */
class EraseLeftoversTest {
    @Test fun eraseClearsSetAsideRecordsAndCachedAttachmentUrls() = runTest {
        val persistence = MemoryPersistence()
        val h = EngineHarness(this, persistence = persistence)
        h.transport.json("DELETE me", status = 204, body = "")
        h.transport.json("GET attachments/att_1", body = """{"url":"https://files.test/att_1?sig=a","expiresAt":"2026-10-30T19:00:00Z"}""")
        h.engine.session.token(SessionReason.Prepare)
        persistence.putRaw("thread-cache", "not sealed")
        persistence.quarantine("thread-cache")
        h.engine.uploader.url("att_1")
        h.instance.eraseMyData()
        advanceUntilIdle()
        assertEquals(emptyList(), persistence.names().filter { "unreadable" in it }, "set-aside records outlived the erase")
        h.engine.uploader.url("att_1")
        assertEquals(2, h.transport.count("GET attachments/att_1"), "the previous person's signed URL was still cached")
        h.stop()
    }
}
