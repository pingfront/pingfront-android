@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.CardActions
import dev.pingfront.internal.messaging.ConfigStore
import dev.pingfront.internal.messaging.Uploader
import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.model.PersonSummary
import dev.pingfront.model.ReviewMode
import dev.pingfront.model.ThreadStatus
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.RecordingRetryScheduler
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.TestTime
import dev.pingfront.testing.testDependencies
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

/**
 * Regression tests for the findings the reviews found by reading (no repro was attached):
 * the Android conformance and quality review (31 Oct 2026), Medium and Low items.
 */
class ReviewHardeningTest {
    private val installId = "8a1f6f2e-1111-4222-8333-944455556666"

    /** CQ-M7: a logout that could not reach the server is retried by the WorkManager drain, not only by the next session. */
    @Test fun anOfflineLogoutIsRetriedByTheWorker() = runTest {
        val retry = RecordingRetryScheduler()
        val transport = ScriptedTransport().apply {
            json("POST sessions", body = Bodies.session())
            offline("POST me/logout")
        }
        val instance = PingFrontInstance()
        val engine = dev.pingfront.internal.Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            dev.pingfront.internal.EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
            testDependencies(transport, retry = retry),
        )
        instance.engine = engine
        engine.start()
        engine.session.token(SessionReason.Prepare)
        instance.logout()
        advanceUntilIdle()
        assertTrue(retry.scheduled > 0, "the worker was scheduled for the queued logout")
        assertEquals(1, engine.pendingLogouts.all().size)
        transport.json("POST me/logout", status = 204, body = "")
        assertTrue(engine.drainForWorker(), "the worker sent it")
        assertTrue(engine.pendingLogouts.all().isEmpty())
        engine.stop()
    }

    /** CQ-L3: a userId over 255 characters is refused, never truncated into someone else's id. */
    @Test fun aTooLongUserIdIsRefused() = runTest {
        val h = EngineHarness(this)
        h.instance.identify("u".repeat(256), null, null)
        advanceUntilIdle()
        assertNull(h.engine.coalescer.stagedProfile().identity)
        h.instance.identify("u".repeat(255), null, null)
        advanceUntilIdle()
        assertEquals(255, h.engine.coalescer.stagedProfile().identity?.userId?.length)
        h.stop()
    }

    /** CQ-L4: the live handshake carries PF-SDK and PF-Protocol like every HTTP request. */
    @Test fun theLiveHandshakeIdentifiesTheSdk() = runTest {
        val h = EngineHarness(this)
        h.engine.live.start("wss://api.test/v1/live")
        runCurrent()
        val headers = h.transport.socketHeaders.single()
        assertEquals("pingfront-android/${dev.pingfront.internal.net.SdkVersion.CURRENT}", headers["PF-SDK"])
        assertEquals("1.0.0", headers["PF-Protocol"])
        assertEquals(EngineHarness.KEY, headers["PF-App-Key"])
        h.engine.live.stop()
        h.stop()
    }

    /** CQ-L5: a timeout reached the server; the UI must not say "offline". */
    @Test fun aTimeoutIsNotOffline() {
        assertFalse(ApiClient.isOffline(SocketTimeoutException("timeout")))
        assertTrue(ApiClient.isOffline(java.net.UnknownHostException("api.test")))
        assertEquals(120L, ApiClient.UPLOAD_TIMEOUT_S, "uploads get more than the 30 s API call timeout")
    }

    /** CQ-L6: a failed config fetch may be retried at once; only a successful one starts the 5-minute window. */
    @Test fun aFailedConfigFetchDoesNotBlockTheNextOne() = runTest {
        val h = EngineHarness(this)
        h.transport.offline("GET config")
        val store = ConfigStore(h.engine.client, RecordStore(MemoryPersistence()), TestTime(this))
        store.refresh(testDevice = false)
        val afterFailure = h.transport.count("GET config")
        h.transport.json("GET config", body = Bodies.config())
        store.refresh(testDevice = false)
        assertEquals(afterFailure + 1, h.transport.count("GET config"), "the failed fetch did not start the window")
        store.refresh(testDevice = false)
        assertEquals(afterFailure + 1, h.transport.count("GET config"), "after a success, the 5-minute window applies")
        h.stop()
    }

    /** CQ-L7: a device clock that runs behind never keeps an attachment URL cached for more than 5 minutes. */
    @Test fun attachmentUrlsAreCachedForAtMostFiveMinutes() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET attachments/a1", body = """{"url":"https://cdn.test/a1","expiresAt":"2099-01-01T00:00:00Z"}""")
        val uploader = Uploader(h.engine.client, TestTime(this))
        uploader.url("a1")
        advanceTimeBy(4 * 60_000)
        uploader.url("a1")
        assertEquals(1, h.transport.count("GET attachments/a1"))
        advanceTimeBy(2 * 60_000)
        uploader.url("a1")
        assertEquals(2, h.transport.count("GET attachments/a1"))
        h.stop()
    }

    /** CQ-L9: after logout, the device's push token is registered again for the next person. */
    @Test fun thePushTokenIsRegisteredAgainAfterLogout() = runTest {
        val h = EngineHarness(this)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.setPushToken("fcm-token-1")
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        h.instance.logout()
        advanceUntilIdle()
        assertEquals("fcm-token-1", h.engine.coalescer.stagedProfile().push?.token)
        h.stop()
    }

    /** CQ-L10: NaN and infinities have no JSON form and are dropped; finite numbers stay. */
    @Test fun nonFiniteMomentPropsAreDropped() {
        val json = CardActions.encodeProps(mapOf("a" to Double.NaN, "b" to Float.POSITIVE_INFINITY, "c" to 1.5, "d" to 2))
        assertEquals(setOf("c", "d"), json.keys)
        val long = "k".repeat(63) + "😀"
        assertFalse(CardActions.encodeProps(mapOf(long to 1)).keys.single().last().isHighSurrogate(), "keys are cut at code points")
    }

    /** CQ-L1: values this SDK does not know decode to Unknown, never to a known case. */
    @Test fun unknownEnumValuesAreUnknown() {
        val person = WireJson.decodeFromString(PersonSummary.serializer(), """{"id":"p","level":"admin"}""")
        assertEquals(PersonSummary.Level.Unknown, person.level)
        val hello = ServerEvent.decode("""{"type":"hello","lastSeq":1,"readSeq":1,"status":"snoozed"}""") as ServerEvent.Hello
        assertEquals(ThreadStatus.Unknown, hello.status)
        val thread = ServerEvent.decode("""{"type":"thread","status":"archived"}""") as ServerEvent.Thread
        assertEquals(ThreadStatus.Unknown, thread.status)
        assertEquals(ReviewMode.Unknown, WireJson.decodeFromString(ReviewMode.serializer(), "\"in_app_sheet\""))
        assertEquals(ReviewMode.NativePrompt, WireJson.decodeFromString(ReviewMode.serializer(), "\"native_prompt\""))
        // A code the SDK has no name for is kept verbatim, never a crash; verified-only apps send verification_required.
        assertEquals("over_quota", ErrorCode("over_quota").raw)
        assertEquals(ErrorCode.VerificationRequired, ErrorCode("verification_required"))
        // identity_token_* left the contract (identityProblem carries token problems): such a body still decodes, verbatim.
        val removed = ApiClient.serverError(
            401,
            """{"error":{"code":"identity_token_invalid","message":"m","docs":"d"}}""".encodeToByteArray(),
            null,
        )
        assertEquals(401, removed.status)
        assertEquals("identity_token_invalid", removed.code.raw)
    }

    /** CQ-M6: the 20 s / 2 min unread polling stops in the background and resumes in the foreground. */
    @Test fun unreadPollingResumesInTheForeground() = runTest {
        val transport = ScriptedTransport()
        val persistence = MemoryPersistence()
        RecordStore(persistence).write(StoredRecords.credentials, InstallCredentials(installId, "secret-1"))
        transport.json("POST sessions/refresh", body = Bodies.refresh())
        transport.json("GET unread", body = """{"unread":2,"lastSeq":0,"live":false}""")
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        val lifecycle = kotlinx.coroutines.flow.MutableSharedFlow<AppLifecycleEvent>()
        val instance = PingFrontInstance()
        val engine = dev.pingfront.internal.Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            dev.pingfront.internal.EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
            testDependencies(transport, persistence, lifecycle),
        )
        instance.engine = engine
        engine.start()
        runCurrent()
        lifecycle.emit(AppLifecycleEvent.Background)
        advanceTimeBy(30 * 60_000)
        assertEquals(0, transport.count("GET unread"))
        lifecycle.emit(AppLifecycleEvent.Foreground)
        advanceTimeBy(25_000)
        assertTrue(transport.count("GET unread") >= 1)
        assertEquals(2, instance.unreadCount.value)
        engine.stop()
    }

    /** CS-6: a storage failure while sending fails that one message and keeps the send queue alive. */
    @Test fun aStorageErrorWhileQueueingFailsOnlyThatMessage() = runTest {
        // Storage is locked from the start (before the first unlock), so the outbox cannot even be read.
        val disk = MemoryPersistence().apply { unavailable = true }
        val h = EngineHarness(this, persistence = disk)
        h.transport.on("POST thread/messages") {
            val clientId = WireJson.parseToJsonElement(it.body!!.decodeToString()).jsonObject["clientId"]!!.toString().trim('"')
            HttpResponse(200, emptyMap(), Bodies.sent(3, clientId).encodeToByteArray())
        }
        advanceUntilIdle()
        h.instance.conversation.send("first")
        advanceUntilIdle()
        assertEquals(dev.pingfront.conversation.PendingMessage.State.Failed, h.instance.conversation.state.value.pending.single().state)
        disk.unavailable = false
        h.instance.conversation.send("second")
        advanceUntilIdle()
        assertEquals(1, h.transport.count("POST thread/messages"), "the next message still sends")
        h.stop()
    }
}
