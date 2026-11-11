@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.context.ContextReporter
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.ContextCollection
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.TestTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Review AN-12 and AN-13 (3 Nov 2026): the app's privacy switches for device context. */
class ContextSwitchesTest {
    /**
     * AN-12: with the `connection` group off, the SDK kept reading the connection, Data Saver and Battery Saver on every
     * network change. The one network callback (S2) still says whether the device is online, but the connection
     * itself is never read.
     */
    @Test fun anAppThatTurnedConnectionOffNeverHasItsConnectionRead() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, context = source)
        h.transport.json(
            "POST sessions",
            body = Bodies.session().trimEnd().removeSuffix("}") +
                ""","collect":{"build":true,"display":true,"connection":false,"health":true,"notifications":true}}""",
        )
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        source.change(FakeContextSource.CELLULAR)
        advanceUntilIdle()
        assertEquals(0, source.connectionReads, "the connection was read although the group is off")
        h.stop()
    }

    /**
     * AN-13: a `collect` answer whose write failed (a locked Keystore) stayed in memory only, and the same answer
     * later was never written because nothing had changed in memory. The next process then read the old switches
     * (or every group) and its first session start carried groups the app had turned off.
     */
    @Test fun aCollectAnswerThatCouldNotBeStoredIsStoredByTheNextOne() = runTest {
        val persistence = MemoryPersistence()
        val records = RecordStore(persistence)
        val reporter = ContextReporter(FakeContextSource(), records, TestTime(this), StandardTestDispatcher(testScheduler))
        val noHealth = ContextCollection(build = true, display = true, connection = true, health = false, notifications = true)
        persistence.unavailable = true
        reporter.collectionReceived(noHealth)
        persistence.unavailable = false
        reporter.collectionReceived(noHealth)
        assertEquals(noHealth, records.read(StoredRecords.contextCollection), "the switches were never stored")
    }
}
