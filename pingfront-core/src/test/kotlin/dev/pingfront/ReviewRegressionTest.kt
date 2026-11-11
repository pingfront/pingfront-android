@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.pingfront.AppKey
import dev.pingfront.EngineHarness
import dev.pingfront.PingFrontEvent
import dev.pingfront.PingFrontException
import dev.pingfront.PingFrontInstance
import dev.pingfront.PushPayload
import dev.pingfront.conversation.ConversationPhase
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.messaging.OutboxItem
import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.runtime.CooldownBook
import dev.pingfront.internal.session.PatchCoalescer
import dev.pingfront.internal.session.ProfileValidation
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.storage.AesGcm
import dev.pingfront.internal.storage.DataStorePersistence
import dev.pingfront.internal.storage.KeySource
import dev.pingfront.internal.storage.KeystoreCipher
import dev.pingfront.internal.storage.Persistence
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.SecretCipher
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.look.ChatAppearance
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.Corners
import dev.pingfront.model.FontStyle
import dev.pingfront.model.HeaderStyle
import dev.pingfront.model.IdentityProblem
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeSocket
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.TestDevice
import dev.pingfront.testing.TestTime
import dev.pingfront.testing.testDependencies
import java.io.IOException
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Regression tests ported from the reviewers' repros in the Android concurrency and security review and the
 * Android conformance and quality review (both 31 Oct 2026). Each was observed failing against the reviewed
 * code before its fix.
 */

private fun ok(body: String) = HttpResponse(200, emptyMap(), body.encodeToByteArray())

private fun clientIdOf(body: ByteArray?): String = WireJson.parseToJsonElement(body!!.decodeToString()).jsonObject["clientId"]!!.jsonPrimitive.content

/** Echoes the clientId so every send is acknowledged. */
private fun ScriptedTransport.acceptSends() = on("POST thread/messages") { ok(Bodies.sent(7, clientIdOf(it.body))) }

/** An engine with a lifecycle flow and persistence the test controls. */
private class Rig(test: TestScope, val persistence: Persistence = MemoryPersistence()) {
    val transport = ScriptedTransport()
    val lifecycle = MutableSharedFlow<AppLifecycleEvent>()
    val instance = PingFrontInstance()
    val engine: Engine

    init {
        val base = test.testDependencies(transport, MemoryPersistence(), lifecycle)
        val deps = dev.pingfront.internal.Dependencies(
            base.transport, persistence, base.time, base.dispatchers, base.device, base.lifecycle, base.retry, base.languageTag, base.jitter,
        )
        engine = Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
            deps,
        )
        transport.json("POST sessions", body = Bodies.session())
        transport.json("GET config", status = 404, body = Bodies.error("not_found"))
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        instance.engine = engine
        engine.start()
    }
}

class ReviewRegressionTest {
    // F1. A session change during delivery throws a CancellationException out of Outbox.drain and the
    // outbox loop ends for good; later messages are never sent.
    @Test fun outboxLoopSurvivesASessionChangeDuringDelivery() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        val uploadGate = CompletableDeferred<Unit>()
        h.transport.on("POST uploads") {
            uploadGate.await()
            ok("""{"attachmentId":"a1","putUrl":"https://up.test/x","headers":{},"expiresAt":"2026-10-30T19:00:00Z"}""")
        }
        h.transport.on("PUT https://up.test/x") { HttpResponse(200, emptyMap(), ByteArray(0)) }
        h.transport.acceptSends()
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        h.engine.outbox.enqueue(
            OutboxItem(
                PendingMessage("c1", "with photo", listOf(PendingMessage.Photo("photo-c1-0", 1, 1, 1, "ab")), Instant.EPOCH),
            ),
            listOf(byteArrayOf(1)),
        )
        h.engine.retry("c1") // wakes the drain; the upload is now in flight
        runCurrent()
        // Meanwhile another caller refreshes and the server says the install secret was revoked.
        h.engine.session.invalidate("tok-1")
        h.transport.json("POST sessions/refresh", status = 401, body = Bodies.error("session_revoked"))
        h.transport.json("POST sessions", body = Bodies.session(installId = "99999999-2222-4333-8444-555555555555", token = "tok-2"))
        // The revocation fails this caller (shared scenario auth-refresh-revoked); the next one is the new guest.
        runCatching { h.engine.session.token(SessionReason.ChatOpened) }
        h.engine.session.token(SessionReason.ChatOpened)
        runCurrent()
        uploadGate.complete(Unit)
        advanceUntilIdle()
        // The person keeps typing under the new guest session.
        h.instance.conversation.send("typed after the session changed")
        advanceUntilIdle()
        assertTrue(
            h.transport.count("POST thread/messages") >= 1,
            "outbox loop is dead: POST thread/messages count=${h.transport.count("POST thread/messages")}, " +
                "pending=${h.instance.conversation.state.value.pending.map { it.clientId to it.state }}",
        )
        h.stop()
    }

    // F2a. A GET /thread that was in flight when logout ran repopulates the conversation and the disk cache.
    @Test fun aThreadLoadThatFinishesAfterLogoutIsDiscarded() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        h.transport.on("GET thread") {
            gate.await()
            ok(Bodies.thread(2, Bodies.message(1, text = "previous person's private thread"), Bodies.message(2), unread = 2))
        }
        h.transport.json("POST me/logout", status = 204, body = "")
        h.engine.scope.launch { h.engine.loadThread() }
        runCurrent()
        h.instance.logout()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val cached = RecordStore(h.persistence).read(StoredRecords.thread)
        assertTrue(
            h.instance.conversation.state.value.messages.isEmpty() && cached == null && h.instance.unreadCount.value == 0,
            "after logout: messages=${h.instance.conversation.state.value.messages.map { it.plainText }}, " +
                "thread cache on disk=${cached?.messages?.size}, unread=${h.instance.unreadCount.value}",
        )
        h.stop()
    }

    // F2b. A PATCH /me that was in flight when logout ran restores PingFront.person to the logged-out person.
    @Test fun aPatchThatFinishesAfterLogoutDoesNotRestoreThePerson() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        h.transport.on("PATCH me") {
            gate.await()
            ok("""{"person":{"id":"p_1","level":"named","name":"Ada Lovelace"}}""")
        }
        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.identify("user-a", "Ada Lovelace", "ada@example.com")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, h.transport.count("PATCH me"))
        h.instance.logout()
        runCurrent()
        assertNull(h.instance.person.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(h.instance.person.value, "PingFront.person after logout: ${h.instance.person.value}")
        h.stop()
    }

    // F3. A logout while a session create is in flight makes prepare() throw CancellationException
    // into a caller that was never cancelled (the contract says PingFrontException).
    @Test fun prepareInterruptedByLogoutThrowsPingFrontException() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.transport.on("POST sessions") {
            gate.await()
            ok(Bodies.session())
        }
        val outcome = async {
            try {
                h.instance.prepare()
                "ok"
            } catch (_: PingFrontException) {
                "PingFrontException"
            } catch (e: CancellationException) {
                "CancellationException(\"${e.message}\") while the caller was active=${coroutineContext.isActive}"
            }
        }
        runCurrent()
        h.instance.logout()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("PingFrontException", outcome.await())
        h.stop()
    }

    // F4. The app goes to the background while the chat is loading: the socket still opens afterwards
    // and stays open in the background.
    @Test fun noSocketOpensAfterTheAppWentToTheBackground() = runTest(timeout = 20.seconds) {
        val r = Rig(this)
        val gate = CompletableDeferred<Unit>()
        r.transport.on("GET thread") {
            gate.await()
            ok(Bodies.thread(0))
        }
        runCurrent()
        val socket = FakeSocket()
        r.transport.socketFactory = { _, _ -> socket }
        r.instance.chatVisibilityChanged(true)
        runCurrent()
        r.lifecycle.emit(AppLifecycleEvent.Background)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(
            r.transport.sockets.isEmpty() || socket.closed,
            "in the background: sockets opened=${r.transport.sockets.size}, closed=${socket.closed}",
        )
        r.engine.stop()
    }

    // F5. One plain IOException from storage (a full disk) kills the command consumer: later identify and
    // logout calls are dropped and eraseMyData() never returns.
    @Test fun aStorageErrorDoesNotKillTheCommandLoop() = runTest(timeout = 20.seconds) {
        val disk = DiskFullPersistence()
        val r = Rig(this, disk)
        runCurrent()
        disk.full = true
        r.instance.identify("user-a", "Ada", null)
        runCurrent()
        disk.full = false
        val erase = async { withTimeoutOrNull(60_000) { r.instance.eraseMyData().let { "returned" } } }
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals("returned", erase.await(), "eraseMyData() did not return within 60 s (virtual)")
        r.engine.stop()
    }

    // F6a. Keystore failures surface as java.security.ProviderException (a RuntimeException). Nothing in the
    // storage stack maps it, so prepare() throws a raw ProviderException instead of PingFrontException.
    @Test fun aKeystoreProviderExceptionIsMappedToStorageUnavailable() = runTest(timeout = 20.seconds) {
        val dir = kotlin.io.path.createTempDirectory("pf-review").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // An existing sealed record, as on any device that already has an install.
        java.io.File(dir, "install.bin").writeBytes(ByteArray(64) { 1 })
        val persistence = DataStorePersistence(dir, ProviderFailingCipher(), scope, Dispatchers.IO)
        val records = RecordStore(persistence)
        val outcome = try {
            records.read(StoredRecords.credentials)
            "read returned"
        } catch (_: PingFrontException.StorageUnavailable) {
            "StorageUnavailable"
        } catch (e: RuntimeException) {
            "unmapped ${e.javaClass.name}: ${e.message}"
        }
        scope.cancel()
        dir.deleteRecursively()
        assertEquals("StorageUnavailable", outcome)
    }

    // CS-7b. A key that can no longer be used (the InvalidKeyException family, which includes
    // KeyPermanentlyInvalidatedException) was reported as "unavailable" forever. Now the key is replaced, the
    // records sealed with it are moved aside, and a new install starts. Runs the real KeystoreCipher (with a
    // software key source), DataStorePersistence and RecordStore over an existing sealed install record.
    @Test fun aPermanentlyBrokenKeyEventuallyStartsAFreshInstall() = runTest(timeout = 20.seconds) {
        val dir = kotlin.io.path.createTempDirectory("pf-keys").toFile()
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val keys = BrokenThenFreshKeys()
        try {
            // The install record was written with the original key.
            val writer = DataStorePersistence(dir, KeystoreCipher(FixedKey(keys.original)), io, Dispatchers.IO)
            RecordStore(writer).write(StoredRecords.credentials, InstallCredentials(installId, "secret-1"))
        } finally {
            io.cancel()
        }
        // After an OS update the Keystore key can no longer initialise a cipher.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val persistence = DataStorePersistence(dir, KeystoreCipher(keys), scope, Dispatchers.IO)
        val records = RecordStore(persistence)
        val transport = ScriptedTransport().apply { json("POST sessions", body = Bodies.session(installId = "99999999-2222-4333-8444-555555555555")) }
        val api = ApiClient(ScriptedTransport.BASE_URL, EngineHarness.KEY, transport, CooldownBook(TestTime(this)), StandardTestDispatcher(testScheduler))
        val session = SessionStore(api, records, TestTime(this), PatchCoalescer(scope, records), scope, TestDevice::device)
        val token = kotlinx.coroutines.withContext(Dispatchers.IO) { session.token(SessionReason.ChatOpened) }
        scope.cancel()
        val asideFiles = dir.listFiles().orEmpty().map { it.name }.filter { "unreadable" in it }
        dir.deleteRecursively()
        assertEquals("tok-1", token)
        assertEquals(1, keys.deleted, "the invalidated key was deleted and replaced")
        assertEquals(1, transport.count("POST sessions"), "a new install started")
        assertNull(WireJson.parseToJsonElement(transport.last("POST sessions")!!.body!!.decodeToString()).jsonObject["installSecret"])
        assertTrue(asideFiles.isNotEmpty(), "the unreadable record was moved aside, not overwritten")
    }

    // CS-7b, the transient side: a locked Keystore (IllegalStateException) and a provider failure stay "unavailable" and never mint an install.
    @Test fun aLockedKeystoreIsTransient() {
        val locked = KeystoreCipher(object : KeySource {
            override fun load(): javax.crypto.SecretKey = throw IllegalStateException("Keystore locked")
            override fun delete() = Unit
        })
        assertTrue(runCatching { locked.decrypt(ByteArray(40)) }.exceptionOrNull() is dev.pingfront.internal.storage.StorageUnavailableException)
        val provider = KeystoreCipher(object : KeySource {
            override fun load(): javax.crypto.SecretKey = throw java.security.ProviderException("Keystore key generation failed")
            override fun delete() = Unit
        })
        assertTrue(runCatching { provider.encrypt(ByteArray(4)) }.exceptionOrNull() is dev.pingfront.internal.storage.StorageUnavailableException)
    }

    // CS-8. Push payloads were trusted from any intent. Launch intents are no longer read at all (FacadeTest
    // covers the forged intent on Robolectric); FCM data only updates the badge, and only a tap on the SDK's own
    // notification (the non-exported PushTapActivity) opens the chat or starts a session.
    @Test fun aReceivedPushNeverOpensTheChatOrStartsASession() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        val handled = h.instance.handlePush(mapOf("pf_v" to "1", "pf_kind" to "message", "pf_app_id" to "app_1", "pf_unread" to "4"))
        advanceUntilIdle()
        assertTrue(handled)
        assertEquals(4, h.instance.unreadCount.value)
        assertEquals(0, h.transport.count("POST sessions"), "no network for a received push")
        assertTrue(!h.instance.isChatVisible.value)
        h.stop()
    }

    // CQ-M7. After logout, pushes addressed to the previous person are not applied or shown.
    @Test fun pushesForThePreviousPersonAreDroppedAfterLogout() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.logout()
        advanceUntilIdle()
        assertTrue(!h.instance.acceptsPush())
        h.instance.handlePush(mapOf("pf_v" to "1", "pf_kind" to "message", "pf_app_id" to "app_1", "pf_unread" to "7"))
        assertEquals(0, h.instance.unreadCount.value)
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        assertTrue(h.instance.acceptsPush(), "the next session accepts pushes again")
        h.stop()
    }

    // F9. A token request whose only caller was cancelled leaves a completed Deferred in `inflight`; the next
    // token() after a 401 reuses it and returns the token that was just invalidated.
    @Test fun aStaleSingleFlightResultIsNotReused() = runTest(timeout = 20.seconds) {
        val transport = ScriptedTransport()
        val gate = CompletableDeferred<Unit>()
        transport.on("POST sessions") {
            gate.await()
            ok(Bodies.session())
        }
        transport.json("POST sessions/refresh", body = Bodies.refresh("tok-2"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val records = RecordStore(MemoryPersistence())
        val api = ApiClient(ScriptedTransport.BASE_URL, EngineHarness.KEY, transport, CooldownBook(TestTime(this)), StandardTestDispatcher(testScheduler))
        val session = SessionStore(api, records, TestTime(this), PatchCoalescer(scope, records), scope, TestDevice::device)
        val caller = launch { session.token(SessionReason.ChatOpened) }
        runCurrent()
        caller.cancel() // for example a ViewModel scope that went away during prepare()
        gate.complete(Unit)
        runCurrent()
        session.invalidate("tok-1") // the server answered 401 to tok-1
        val next = session.token(SessionReason.ChatOpened)
        scope.cancel()
        assertEquals("tok-2", next, "token() after invalidate returned $next (refreshes=${transport.count("POST sessions/refresh")})")
    }

    // F10. eraseMyData() leaves the session usable while DELETE /me is in flight. A concurrent token request
    // that finds the install gone re-registers it with the staged identity, so the server recreates the
    // person (name and email) that is being erased.
    @Test fun nothingReRegistersThePersonWhileEraseIsInFlight() = runTest(timeout = 20.seconds) {
        val h = EngineHarness(this)
        h.instance.identify("user-a", "Ada", "ada@example.com")
        runCurrent()
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val created = h.transport.count("POST sessions")
        val gate = CompletableDeferred<Unit>()
        h.transport.on("DELETE me") {
            gate.await()
            HttpResponse(204, emptyMap(), ByteArray(0))
        }
        h.transport.json("POST sessions/refresh", status = 404, body = Bodies.error("not_found"))
        h.engine.session.invalidate("tok-1")
        val erase = async { h.instance.eraseMyData() }
        runCurrent()
        h.engine.scope.launch { h.engine.unread.poll() } // the 20 s unread poll, or any bearer call
        runCurrent()
        gate.complete(Unit)
        erase.await()
        advanceUntilIdle()
        val extra = h.transport.requests.filter { h.transport.key(it) == "POST sessions" }.drop(created)
        assertTrue(
            extra.isEmpty(),
            "POST /sessions sent during erase: ${extra.map { WireJson.parseToJsonElement(it.body!!.decodeToString()).jsonObject["identity"] }}",
        )
        h.stop()
    }

    private val installId = "8a1f6f2e-1111-4222-8333-944455556666"

    // CQ-M1: 401 session_revoked on a bearer route (another device erased the person, E5 in recorded.txt).
    // Ported with one change: the new guest session gets tok-2 (the repro scripted tok-1 for both sessions,
    // so "tok-1 is never reused" could not be told apart from "the new session's token is used").
    @Test fun sessionRevokedOnABearerRouteStartsOver() = runTest {
        val h = EngineHarness(this)
        h.transport.on("GET thread") {
            if (it.headers["Authorization"] == "Bearer tok-1") {
                HttpResponse(401, emptyMap(), Bodies.error("session_revoked").encodeToByteArray())
            } else {
                ok(Bodies.thread(0))
            }
        }
        h.transport.json("POST sessions/refresh", status = 401, body = Bodies.error("session_revoked"))
        h.engine.session.token(SessionReason.ChatOpened)
        h.transport.json("POST sessions", body = Bodies.session(installId = "99999999-2222-4333-8444-555555555555", token = "tok-2"))
        h.engine.loadThread()
        h.engine.loadThread()
        h.engine.loadThread()
        advanceUntilIdle()
        val bearers = h.transport.requests.filter { h.transport.key(it) == "GET thread" }.map { it.headers["Authorization"] }
        assertTrue(h.events.any { it is PingFrontEvent.SessionRevoked }, "a revoked session must wipe local state and start over")
        assertEquals(1, bearers.count { it == "Bearer tok-1" }, "the revoked token must not be reused: $bearers")
        assertEquals(ConversationPhase.Ready, h.instance.conversation.state.value.phase, "the retry under the new session succeeded")
        h.stop()
    }

    // CQ-M2: PATCH /me answers 200 with rejected[] identity_user_mismatch (E4), never 409.
    @Test fun identityUserMismatchInRejectedIsReported() = runTest {
        val h = EngineHarness(this)
        h.transport.json(
            "PATCH me",
            body = """{"person":{"id":"p_1","level":"verified"},"rejected":[{"field":"identity","code":"identity_user_mismatch","message":"x"}]}""",
        )
        h.engine.session.token(SessionReason.Prepare)
        h.instance.identify("user-b", null, null)
        advanceUntilIdle()
        println("C2 PATCH count: ${h.transport.count("PATCH me")}; events: ${h.events.map { it::class.simpleName }}")
        assertTrue(
            h.events.any { it is PingFrontEvent.IdentityProblemReported && it.problem == IdentityProblem.UserMismatch },
            "identity_user_mismatch in rejected[] must surface as IdentityProblemReported(UserMismatch)",
        )
        h.stop()
    }

    // CQ-H1: an email the SDK used to accept but the server rejects (E1) blocked POST /sessions forever.
    @Test fun aFieldThe422NamesIsDroppedSoTheSessionCanStart() = runTest {
        // The client check now matches the server's rule, so this email is never staged at all.
        assertFalse(ProfileValidation.isEmail("jo@example.c"))
        assertFalse(ProfileValidation.isEmail("jo@example..com"))
        assertTrue(ProfileValidation.isEmail("jo@example.com"))
        val h = EngineHarness(this)
        // And when the server refuses a field the client accepted, that field is dropped and the session starts.
        h.transport.on("POST sessions") { request ->
            if ("ada@example.com" in request.body!!.decodeToString()) {
                HttpResponse(
                    422,
                    emptyMap(),
                    Bodies.error("invalid_request", extra = ""","fields":[{"path":"identity.email","message":"Invalid email address"}]""").encodeToByteArray(),
                )
            } else {
                ok(Bodies.session())
            }
        }
        h.instance.identify("u-1", "Ada", "ada@example.com")
        advanceUntilIdle()
        assertEquals("tok-1", h.engine.session.token(SessionReason.ChatOpened))
        advanceUntilIdle()
        val bodies = h.transport.requests.filter { h.transport.key(it) == "POST sessions" }.map { it.body!!.decodeToString() }
        assertEquals(2, bodies.size, "one retry without the field")
        assertFalse("ada@example.com" in bodies.last(), "after a 422 naming identity.email, the next attempt must drop that field")
        assertTrue("\"u-1\"" in bodies.last(), "the rest of the identity still goes out")
        h.stop()
    }

    // CQ-H1: an offer id with a space (the OfferSlot fixture "rejects an id with spaces") never reaches the server.
    @Test fun invalidOffersAreDroppedBeforeTheyAreSent() = runTest {
        val h = EngineHarness(this)
        h.instance.setOffers(
            listOf(
                dev.pingfront.model.OfferSlot("pro yearly", "Pro"),
                dev.pingfront.model.OfferSlot("pro_monthly", "Pro"),
            ),
        )
        advanceUntilIdle()
        h.engine.session.token(SessionReason.Prepare)
        val offers = WireJson.parseToJsonElement(h.transport.last("POST sessions")!!.body!!.decodeToString()).jsonObject["offers"].toString()
        assertFalse("pro yearly" in offers)
        assertTrue("pro_monthly" in offers)
        h.stop()
    }

    // Contract: POST /sessions answers 409 identity_user_mismatch when a known install sends another user's identity.
    @Test fun aSessionCreateIdentityMismatchStartsTheSessionWithoutIt() = runTest {
        val h = EngineHarness(this)
        h.transport.on("POST sessions") { request ->
            if ("user-b" in request.body!!.decodeToString()) {
                HttpResponse(409, emptyMap(), Bodies.error("identity_user_mismatch").encodeToByteArray())
            } else {
                ok(Bodies.session())
            }
        }
        h.instance.identify("user-b", null, null)
        advanceUntilIdle()
        assertEquals("tok-1", h.engine.session.token(SessionReason.Prepare))
        advanceUntilIdle()
        assertEquals(2, h.transport.count("POST sessions"))
        assertTrue(h.events.any { it is PingFrontEvent.IdentityProblemReported && it.problem == IdentityProblem.UserMismatch })
        h.stop()
    }

    // C4: PingFront.isTestDevice after a refresh (every launch after the first) stays false.
    @Test fun isTestDeviceFollowsTheRefreshResponse() = runTest {
        val persistence = MemoryPersistence()
        RecordStore(persistence).write(StoredRecords.credentials, InstallCredentials(installId, "secret-1"))
        val h = EngineHarness(this, persistence = persistence)
        h.transport.json(
            "POST sessions/refresh",
            body = """{"accessToken":"tok-2","accessExpiresAt":"2026-10-30T18:15:00Z","accessExpiresIn":900,
                "person":{"id":"p_1","level":"guest"},"testDevice":true,"storeAccountToken":"0b0c3f5e-7777-4888-9999-aaaabbbbcccc"}""",
        )
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        assertEquals(0, h.transport.count("POST sessions"))
        assertTrue(h.engine.session.isTestDevice(), "the session store knows")
        assertTrue(h.instance.isTestDevice.value, "PingFront.isTestDevice must be true after a refresh that says testDevice")
        h.stop()
    }

    // C5: an unknown remote enum value overrides the value set in code.
    @Test fun unknownRemoteEnumValuesFallThroughToCode() {
        val config = WireJson.decodeFromString(
            AppConfig.serializer(),
            """{"schema":1,"revision":3,"draft":false,"appName":"Clockwise",
               "look":{"theme":{"corners":"pill","font":"mono"},"header":{"style":"carousel"},"welcome":{},"topics":[],"composer":{},"messages":{}},
               "team":[],"features":{"photos":true,"reports":true,"translation":false,"ai":false},"liveUrl":"wss://api.test/v1/live"}""",
        )
        val code = ChatAppearance().apply {
            corners = Corners.Square
            font = FontStyle.Serif
            headerStyle = HeaderStyle.Team
        }
        val look = ResolvedLook.resolve(config, code, null, null, null)
        println("C5 resolved: corners=${look.corners} font=${look.font} header=${look.headerStyle}")
        assertEquals(Corners.Square, look.corners)
        assertEquals(FontStyle.Serif, look.font)
        assertEquals(HeaderStyle.Team, look.headerStyle)
    }

    // C6: pf_v 1 with a kind this SDK does not know is still pingfront's payload.
    @Test fun unknownPushKindIsStillAPingFrontPayload() {
        assertNotNull(PushPayload.parse(mapOf("pf_v" to "1", "pf_kind" to "survey", "pf_app_id" to "app_1")))
    }

    // C7: UTF-16 take() splits a surrogate pair (push title, MessageReceived preview).
    @Test fun pushTitleNeverEndsInALoneSurrogate() {
        val title = "a".repeat(99) + "😀"
        val payload = PushPayload.parse(mapOf("pf_v" to "1", "pf_kind" to "message", "pf_app_id" to "a", "pf_title" to title))!!
        assertFalse(payload.title!!.last().isHighSurrogate(), "title ends in a lone high surrogate")
    }

    // C8: unread polling must stop when the app goes to the background.
    @Test fun unreadPollingStopsInTheBackground() = runTest {
        val transport = ScriptedTransport()
        val persistence = MemoryPersistence()
        RecordStore(persistence).write(StoredRecords.credentials, InstallCredentials(installId, "secret-1"))
        transport.json("POST sessions/refresh", body = Bodies.refresh())
        transport.json("GET unread", body = """{"unread":0,"lastSeq":0,"live":false}""")
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        val lifecycle = MutableSharedFlow<AppLifecycleEvent>()
        val instance = PingFrontInstance()
        val engine = Engine(
            instance,
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
            testDependencies(transport, persistence, lifecycle),
        )
        instance.engine = engine
        engine.start()
        runCurrent()
        lifecycle.emit(AppLifecycleEvent.Foreground)
        runCurrent()
        lifecycle.emit(AppLifecycleEvent.Background)
        runCurrent()
        val before = transport.count("GET unread")
        advanceTimeBy(30.minutes)
        val after = transport.count("GET unread")
        println("C8 GET /unread while backgrounded for 30 min: ${after - before} (session refreshes: ${transport.count("POST sessions/refresh")})")
        assertEquals(before, after, "no GET /unread while the app is in the background")
        engine.stop()
    }
}

/** Memory storage whose writes fail with ENOSPC while [full], as DataStore does on a full disk. */
private class DiskFullPersistence : Persistence {
    private val inner = MemoryPersistence()

    @Volatile var full = false

    override suspend fun read(name: String): ByteArray? = inner.read(name)

    override suspend fun write(name: String, data: ByteArray) {
        if (full) throw IOException("write failed: ENOSPC (No space left on device)")
        inner.write(name, data)
    }

    override suspend fun remove(name: String) = inner.remove(name)

    override suspend fun quarantine(name: String) = inner.quarantine(name)

    override suspend fun removeQuarantined() = inner.removeQuarantined()
}

/** What KeystoreCipher lets through when AndroidKeyStore fails: KeyGenerator.generateKey and keystore
 * cipher operations throw java.security.ProviderException, which KeystoreCipher.usableKey does not catch. */
private class ProviderFailingCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = throw java.security.ProviderException("Keystore key generation failed")

    override fun decrypt(sealed: ByteArray): ByteArray? = throw java.security.ProviderException("Keystore operation failed")
}

/** A key source whose key was invalidated: it fails to initialise a cipher until it is deleted, then yields a fresh key. */
private class BrokenThenFreshKeys : KeySource {
    val original: javax.crypto.SecretKey = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    var deleted = 0

    override fun load(): javax.crypto.SecretKey =
        if (deleted == 0) SecretKeySpec(ByteArray(7), "AES") else javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun delete() {
        deleted += 1
    }
}

private class FixedKey(private val key: javax.crypto.SecretKey) : KeySource {
    override fun load(): javax.crypto.SecretKey = key

    override fun delete() = Unit
}
