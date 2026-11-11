@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.storage.StoredRecords
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review AN-8 (3 Nov 2026): the draft is saved 400 ms after the last keystroke. A logout inside that window removed
 * the stored draft, then the pending save wrote it back, so the next launch put A's half-written message into the
 * next person's composer.
 */
class DraftAfterWipeTest {
    @Test fun aDraftTypedJustBeforeLogoutIsNotWrittenBack() = runTest {
        val h = EngineHarness(this)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.conversation.updateDraft("A's half-written note")
        runCurrent()
        h.instance.logout()
        advanceUntilIdle()
        assertNull(h.persistence.raw(StoredRecords.draft.name), "the draft was written back after logout")
        h.stop()
    }
}
