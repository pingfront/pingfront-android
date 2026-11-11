package dev.pingfront

import dev.pingfront.internal.Dependencies
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.runtime.SdkDispatchers
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.TestDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Review AN-19 (3 Nov 2026): the sender's placeholders were updated as `value = value + …` from the app's thread (a
 * send) and as `value = value.filterNot …` from the queueing coroutine on another thread. Two of those at once lost an
 * update: a message queued meanwhile kept its "preparing" placeholder forever next to its outbox row, or a new message
 * lost its placeholder. Real threads here, as on a device: many people's taps from several threads while the queue drains.
 */
class PlaceholderRaceTest {
    @Test fun placeholdersSurviveSendsFromSeveralThreads() = runBlocking {
        val transport = ScriptedTransport()
        transport.json("POST sessions", body = Bodies.session())
        transport.json("GET config", status = 404, body = Bodies.error("not_found"))
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        // Nothing is delivered: the outbox keeps every message, so only placeholders and outbox rows are in play.
        transport.offline("POST thread/messages")
        val instance = PingFrontInstance()
        val engine = Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
            Dependencies(
                transport = transport,
                persistence = MemoryPersistence(),
                time = TimeSource.System,
                dispatchers = SdkDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default),
                device = TestDevice::device,
                jitter = { 0.0 },
            ),
        )
        instance.engine = engine
        engine.start()
        engine.session.token(SessionReason.Prepare)
        val threads = 16
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { instance.conversation.send("t$t m$it") }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)
        val settled = withTimeoutOrNull(60_000) {
            while (engine.outbox.all().size < threads * perThread) delay(20)
            delay(500)
            true
        }
        val ghosts = engine.placeholders.value.size
        engine.stop()
        assertEquals(true, settled, "the outbox has ${engine.outbox.all().size} of ${threads * perThread}")
        assertEquals(0, ghosts, "placeholders left behind after every message reached the outbox")
    }
}
