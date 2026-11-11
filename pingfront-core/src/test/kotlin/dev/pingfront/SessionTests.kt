@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.CooldownBook
import dev.pingfront.internal.session.PatchCoalescer
import dev.pingfront.internal.session.PendingLogouts
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.session.SessionEvent
import dev.pingfront.internal.session.SessionStore
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.Identity
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.TestDevice
import dev.pingfront.testing.TestTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

private class Harness(test: TestScope, val transport: ScriptedTransport = ScriptedTransport(), val persistence: MemoryPersistence = MemoryPersistence()) {
    val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
    val records = RecordStore(persistence)
    val api =
        ApiClient(
            ScriptedTransport.BASE_URL,
            "pk_test_abcdefghijklmnopqrstuvvd",
            transport,
            CooldownBook(TestTime(test)),
            StandardTestDispatcher(test.testScheduler),
        )
    val coalescer = PatchCoalescer(scope, records)
    val session = SessionStore(api, records, TestTime(test), coalescer, scope, TestDevice::device)
    val client = AuthorizedClient(api, session)
    val events = mutableListOf<SessionEvent>()
    val responses = mutableListOf<dev.pingfront.internal.wire.UpdateMeResponse>()

    init {
        scope.launch { session.events.collect { events += it } }
        scope.launch {
            coalescer.setHandlers(
                dev.pingfront.internal.session.PatchHandlers(send = { client.send(Endpoints.updateMe(it)) }, applied = { responses += it }),
            )
        }
    }

    fun body(route: String) = transport.last(route)?.body?.decodeToString()?.let { WireJson.parseToJsonElement(it).jsonObject }

    fun close() = scope.cancel()
}

class SessionStoreTest {
    /**
     * Audit (8 Nov 2026): `wipe()` removed the install before it reset the staged profile, so a token request with no
     * generation (the PATCH flush, a push open) landing in between created a session with the previous person's
     * identity and undid the logout.
     */
    @Test fun aTokenRequestDuringTheWipeNeverCarriesThePreviousIdentity() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session())
        h.coalescer.stage(ProfileChange.SetIdentity(Identity("u1", "Ada")))
        h.session.token(SessionReason.ChatOpened)
        advanceUntilIdle()
        assertEquals("u1", h.body("POST sessions")!!["identity"]!!.jsonObject["userId"]?.toString()?.trim('"'))
        val gate = CompletableDeferred<Unit>()
        h.persistence.beforeRemove = { if (it == "install") gate.await() }
        val wipe = launch { h.session.wipe() }
        runCurrent()
        // The wipe waits on the install's removal: a request without a generation fails instead of creating a session.
        assertFailsWith<PingFrontException.SessionChanged> { h.session.token(SessionReason.Prepare) }
        assertEquals(1, h.transport.count("POST sessions"))
        gate.complete(Unit)
        wipe.join()
        h.persistence.beforeRemove = null
        // After the wipe, the next session is a new guest without the identity.
        h.session.token(SessionReason.ChatOpened)
        assertEquals(2, h.transport.count("POST sessions"))
        assertNull(h.body("POST sessions")!!["identity"])
        assertNull(h.body("POST sessions")!!["installSecret"])
        h.close()
    }

    /**
     * Audit (8 Nov 2026): the server made the install but its write failed, and the credentials were not kept in memory,
     * so every retry minted another install. They are kept for the process and written again with the next refresh.
     */
    @Test fun anInstallWhoseWriteFailedIsKeptAndRefreshedNotMintedAgain() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session(installId = "11111111-2222-4333-8444-555555555555", token = "tok-1"))
        h.transport.json("POST sessions/refresh", body = Bodies.refresh("tok-2"))
        h.persistence.beforeWrite = { if (it == "install") throw java.io.IOException("disk full") }
        assertEquals("tok-1", h.session.token(SessionReason.ChatOpened))
        assertNull(h.records.read(StoredRecords.credentials))
        h.persistence.beforeWrite = null
        h.session.invalidate("tok-1")
        assertEquals("tok-2", h.session.token(SessionReason.ChatOpened))
        assertEquals(1, h.transport.count("POST sessions"), "a second install was created")
        assertEquals(1, h.transport.count("POST sessions/refresh"))
        assertEquals("11111111-2222-4333-8444-555555555555", h.records.read(StoredRecords.credentials)?.installId, "written with the refresh")
        h.close()
    }

    @Test fun nothingHappensUntilATokenIsNeededThenOneCreate() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session(installId = "11111111-2222-4333-8444-555555555555"))
        h.coalescer.stage(ProfileChange.Traits(mapOf("plan" to "pro")))
        advanceUntilIdle()
        assertTrue(h.transport.requests.isEmpty())
        val tokens = List(3) { async { h.session.token(SessionReason.ChatOpened) } }.map { it.await() }
        assertEquals(listOf("tok-1", "tok-1", "tok-1"), tokens)
        assertEquals(1, h.transport.count("POST sessions"))
        val body = h.body("POST sessions")!!
        assertEquals("\"chat_opened\"", body["reason"].toString())
        assertEquals("{\"plan\":\"pro\"}", body["traits"].toString())
        assertNull(body["installSecret"])
        // The server's installId is adopted.
        assertEquals("11111111-2222-4333-8444-555555555555", h.records.read(StoredRecords.credentials)?.installId)
        advanceUntilIdle()
        assertTrue(h.events.first() is SessionEvent.Started)
        h.close()
    }

    @Test fun refreshCarriesDeviceAndTokensFollowAccessExpiresIn() = runTest {
        val h = Harness(this)
        h.records.write(StoredRecords.credentials, InstallCredentials("i-1", "s-1"))
        h.transport.json("POST sessions/refresh", body = Bodies.refresh("tok-a"))
        assertEquals("tok-a", h.session.token(SessionReason.ChatOpened))
        val body = h.body("POST sessions/refresh")!!
        assertEquals("\"i-1\"", body["installId"].toString())
        assertEquals("\"Europe/Berlin\"", body["device"]!!.jsonObject["timeZone"].toString())
        // 900 s token, refreshed 60 s early: still valid at 839 s, refreshed at 841 s.
        advanceTimeBy(839_000)
        h.session.token(SessionReason.ChatOpened)
        assertEquals(1, h.transport.count("POST sessions/refresh"))
        advanceTimeBy(2_000)
        h.session.token(SessionReason.ChatOpened)
        assertEquals(2, h.transport.count("POST sessions/refresh"))
        h.close()
    }

    @Test fun revokedWipesAndStartsANewGuest() = runTest {
        val h = Harness(this)
        h.records.write(StoredRecords.credentials, InstallCredentials("old", "s"))
        h.transport.json("POST sessions/refresh", status = 401, body = Bodies.error("session_revoked"))
        h.transport.json("POST sessions", body = Bodies.session(installId = "22222222-2222-4333-8444-555555555555"))
        // The asking request fails and nothing is created yet (shared scenario auth-refresh-revoked) ...
        assertFailsWith<PingFrontException> { h.session.token(SessionReason.ChatOpened) }
        advanceUntilIdle()
        assertTrue(SessionEvent.Revoked in h.events)
        assertEquals(0, h.transport.count("POST sessions"))
        // ... the next request starts the new guest.
        h.session.token(SessionReason.ChatOpened)
        assertNull(h.body("POST sessions")!!["installSecret"])
        assertEquals("22222222-2222-4333-8444-555555555555", h.records.read(StoredRecords.credentials)?.installId)
        h.close()
    }

    @Test fun unknownSecretReRegistersWithItsSecret() = runTest {
        val h = Harness(this)
        h.records.write(StoredRecords.credentials, InstallCredentials("known", "secret-x"))
        h.transport.json("POST sessions/refresh", status = 404, body = Bodies.error("not_found"))
        h.transport.json("POST sessions", body = Bodies.session(installId = "known"))
        h.session.token(SessionReason.Prepare)
        assertEquals("\"secret-x\"", h.body("POST sessions")!!["installSecret"].toString())
        h.close()
    }

    @Test fun unavailableStorageNeverMintsANewInstall() = runTest {
        val h = Harness(this)
        h.persistence.unavailable = true
        assertFailsWith<PingFrontException.StorageUnavailable> { h.session.token(SessionReason.ChatOpened) }
        assertEquals(0, h.transport.count("POST sessions"))
        assertFalse(h.session.hasInstall())
        h.close()
    }

    @Test fun aCreateThatFinishesAfterLogoutStoresNothing() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.transport.on("POST sessions") {
            gate.await()
            dev.pingfront.internal.net.HttpResponse(200, emptyMap(), Bodies.session().encodeToByteArray())
        }
        // The caller was never cancelled: it gets a PingFrontException, not a CancellationException (review CS-4).
        val pending = async { runCatching { h.session.token(SessionReason.ChatOpened) } }
        runCurrent()
        h.session.wipe()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.await().exceptionOrNull() is PingFrontException.SessionChanged, "got ${pending.await()}")
        assertNull(h.records.read(StoredRecords.credentials))
        assertTrue(h.events.none { it is SessionEvent.Started })
        assertFalse(h.session.isActive())
        h.close()
    }
}

class PatchCoalescerTest {
    @Test fun oneMergedPatchAfter250msWithChangedFieldsOnly() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session())
        h.transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"named"}}""")
        h.coalescer.stage(ProfileChange.Traits(mapOf("a" to "1")))
        h.session.token(SessionReason.Prepare)
        h.coalescer.stage(ProfileChange.SetIdentity(Identity("u1", "Ada")))
        h.coalescer.stage(ProfileChange.Traits(mapOf("b" to "2")))
        advanceTimeBy(249.milliseconds)
        runCurrent()
        assertEquals(0, h.transport.count("PATCH me"))
        advanceTimeBy(2.milliseconds)
        advanceUntilIdle()
        assertEquals(1, h.transport.count("PATCH me"))
        val body = h.body("PATCH me")!!
        assertEquals("{\"b\":\"2\"}", body["traits"].toString())
        assertEquals("\"u1\"", body["identity"]!!.jsonObject["userId"].toString())
        h.coalescer.stage(ProfileChange.Traits(mapOf("b" to "2")))
        advanceUntilIdle()
        assertEquals(1, h.transport.count("PATCH me"), "identical values send nothing")
        h.close()
    }

    @Test fun neverTwoInFlightAndOrderIsKept() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session())
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        h.transport.on("PATCH me") {
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            gate.await()
            inFlight -= 1
            dev.pingfront.internal.net.HttpResponse(200, emptyMap(), """{"person":{"id":"p_1","level":"guest"}}""".encodeToByteArray())
        }
        h.session.token(SessionReason.Prepare)
        h.coalescer.stage(ProfileChange.Traits(mapOf("a" to "1")))
        advanceTimeBy(300)
        h.coalescer.stage(ProfileChange.Traits(mapOf("a" to "2")))
        advanceTimeBy(1_000)
        assertEquals(1, h.transport.count("PATCH me"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, h.transport.count("PATCH me"))
        assertEquals(1, maxInFlight)
        assertEquals("{\"a\":\"2\"}", h.body("PATCH me")!!["traits"].toString())
        h.close()
    }

    /** Review CQ-M2: PATCH /me reports a verified user switch as 200 with `rejected[]`, never as a 409. */
    @Test fun aVerifiedUserSwitchInRejectedDropsTheIdentity() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session())
        h.transport.json(
            "PATCH me",
            body = """{"person":{"id":"p_1","level":"verified"},"rejected":[{"field":"identity","code":"identity_user_mismatch","message":"x"}]}""",
        )
        h.session.token(SessionReason.Prepare)
        h.coalescer.stage(ProfileChange.SetIdentity(Identity("someone-else")))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, h.responses.size, "the response is applied (the rest of the PATCH still counts)")
        h.coalescer.stage(ProfileChange.Traits(mapOf("t" to "1")))
        h.transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"verified"},"rejected":[]}""")
        advanceUntilIdle()
        val next = h.body("PATCH me")!!
        assertNull(next["identity"], "the mismatched identity is never resent")
        assertNull(h.coalescer.stagedProfile().identity, "and is not sent with the next POST /sessions either")
        h.close()
    }

    @Test fun rejectedFieldsAreDroppedNotRetried() = runTest {
        val h = Harness(this)
        h.transport.json("POST sessions", body = Bodies.session())
        h.transport.json(
            "PATCH me",
            status = 422,
            body = Bodies.error("invalid_request", extra = ""","fields":[{"path":"identity.email","message":"bad"}]"""),
        )
        h.session.token(SessionReason.Prepare)
        h.coalescer.stage(ProfileChange.SetIdentity(Identity("u", email = "x@y.z")))
        h.coalescer.stage(ProfileChange.Traits(mapOf("t" to "1")))
        advanceTimeBy(300)
        runCurrent()
        h.transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"},"rejected":[]}""")
        advanceUntilIdle()
        val retry = h.body("PATCH me")!!
        assertNull(retry["identity"])
        assertEquals("{\"t\":\"1\"}", retry["traits"].toString())
        h.close()
    }

    @Test fun traitsAreCappedAtThirty() = runTest {
        val h = Harness(this)
        h.coalescer.stage(ProfileChange.Traits((1..40).associate { "k$it" to "v" }))
        assertEquals(30, h.coalescer.stagedProfile().traits.size)
        h.coalescer.stage(ProfileChange.Traits(mapOf("k1" to "updated", "new" to "x")))
        assertEquals("updated", h.coalescer.stagedProfile().traits["k1"])
        assertNull(h.coalescer.stagedProfile().traits["new"])
        h.close()
    }
}

class PendingLogoutsTest {
    @Test fun oneOwnerRemovesByInstallId() = runTest {
        val persistence = MemoryPersistence()
        val logouts = PendingLogouts(RecordStore(persistence))
        logouts.add(InstallCredentials("a", "1"))
        logouts.add(InstallCredentials("b", "2"))
        logouts.add(InstallCredentials("a", "1"))
        logouts.remove("a")
        assertEquals(listOf("b"), logouts.all().map { it.installId })
        val transport = ScriptedTransport().apply { offline("POST me/logout") }
        val api =
            ApiClient(ScriptedTransport.BASE_URL, "pk", transport, CooldownBook(TestTime(this)), StandardTestDispatcher(testScheduler))
        logouts.drain(api)
        assertEquals(listOf("b"), logouts.all().map { it.installId }, "transient failures stay queued")
        transport.json("POST me/logout", status = 204, body = "")
        logouts.drain(api)
        assertTrue(logouts.all().isEmpty())
    }
}
