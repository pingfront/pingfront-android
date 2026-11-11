@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.Auth
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.ConfigFetch
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.net.OkHttpTransport
import dev.pingfront.internal.net.TokenProvider
import dev.pingfront.internal.runtime.CooldownBook
import dev.pingfront.internal.wire.CardActionRequest
import dev.pingfront.internal.wire.CreateSessionRequest
import dev.pingfront.internal.wire.CreateUploadRequest
import dev.pingfront.internal.wire.ReadRequest
import dev.pingfront.internal.wire.SendMessageRequest
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.TestDevice
import dev.pingfront.testing.TestTime
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** How [Endpoints] builds requests: encoded paths, auth and idempotency per route, and nothing else builds them. */
class EndpointTableTest {
    @Test fun pathParametersAreEncoded() {
        assertEquals("thread/cards/card%201%2Fx/act", Endpoints.cardAction("card 1/x", CardActionRequest("m", "opened")).path)
    }

    @Test fun authAndIdempotencyPerRoute() {
        assertEquals(Auth.AppKeyOnly, Endpoints.createSession(CreateSessionRequest(TestDevice.device("i"), reason = "prepare")).auth)
        assertEquals(Auth.Bearer, Endpoints.config(draft = true, etag = "x").auth)
        assertEquals(Auth.AppKeyOnly, Endpoints.config(draft = false, etag = "x").auth)
        assertTrue(Endpoints.sendMessage(SendMessageRequest("c", "t")).idempotent)
        assertTrue(!Endpoints.cardAction("c", CardActionRequest("m", "opened")).idempotent)
        assertTrue(Endpoints.thread().threadScoped && Endpoints.markRead(ReadRequest(1)).threadScoped && !Endpoints.unread().threadScoped)
    }

    @Test fun noCodeOutsideTheNetworkPackageBuildsRequests() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first {
            File(it, "pingfront-core").exists() ||
                it.name == "pingfront-core"
        }
        val sources = File(
            if (root.name ==
                "pingfront-core"
            ) {
                root
            } else {
                File(root, "pingfront-core")
            },
            "src/main/kotlin",
        ).walk().filter { it.extension == "kt" }
        val forbidden = Regex(""""(GET|POST|PATCH|PUT|DELETE)"|"/?(thread|sessions|me|uploads|reports|moments|attachments)/[^"]*"""")
        val offenders = sources.filterNot { it.path.contains("/internal/net/") }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (forbidden.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertEquals(emptyList(), offenders)
    }
}

/** The real OkHttp transport and API client against MockWebServer. */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private val tokens = object : TokenProvider {
        var issued = 0
        val invalidated = mutableListOf<String>()

        override suspend fun token(reason: SessionReason): String = "tok-${++issued}"

        override suspend fun invalidate(token: String) {
            invalidated += token
        }
    }

    @BeforeTest fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest fun stop() = server.close()

    private fun kotlinx.coroutines.test.TestScope.client(): AuthorizedClient {
        val api =
            ApiClient(
                server.url("/v1").toString(),
                "pk_test_abcdefghijklmnopqrstuvvd",
                OkHttpTransport(),
                CooldownBook(TestTime(this)),
                Dispatchers.IO,
            )
        return AuthorizedClient(api, tokens)
    }

    /**
     * Audit 5, 2b: after the network came back the first request went out on a pooled connection from the lost
     * network and hung until the 30 s call timeout. A network change evicts the pool.
     */
    @Test fun aNetworkChangeEvictsPooledConnections() = runTest {
        val client = OkHttpTransport.defaultClient()
        val transport = OkHttpTransport(client)
        server.enqueue(json(200, "{}"))
        transport.execute(dev.pingfront.internal.net.HttpRequest("GET", server.url("/v1/ping").toString()))
        assertEquals(1, client.connectionPool.connectionCount(), "the keep-alive connection is pooled")
        transport.networkChanged()
        assertEquals(0, client.connectionPool.connectionCount(), "the next request opens a new connection")
    }

    /** Audit 7: a refused live upgrade (403 with the error envelope) reaches the engine with its body, through real OkHttp. */
    @Test fun aRefusedUpgradeCarriesItsErrorBody() = runTest {
        server.enqueue(json(403, """{"error":{"code":"app_paused","message":"This app is paused."}}"""))
        val socket = OkHttpTransport().openSocket(server.url("/v1/live").toString().replace("http", "ws"), listOf("pingfront.v1"), emptyMap())
        // Real sockets: wait in real time, not the test's virtual time.
        val frame = kotlinx.coroutines.withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeout(10_000) { socket.frames.receive() } }
        frame as dev.pingfront.internal.net.SocketFrame.Failed
        assertEquals(403, frame.status)
        val error = ApiClient.serverError(frame.status!!, frame.body!!, null)
        assertEquals(dev.pingfront.ErrorCode.AppPaused, error.code)
    }

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build()

    @Test fun headersOnEveryRequestAndThreadRevOnThreadRoutes() = runTest {
        server.enqueue(
            json(
                200,
                """{"thread":{"id":"t","status":"open","lastSeq":0,"readSeq":0,"teamReadSeq":0,"unread":0,"rev":7},"messages":[],"hasOlder":false,"team":[],"policy":{"canSend":true}}""",
                "PF-Thread-Rev" to "7",
            ),
        )
        server.enqueue(json(200, """{"unread":0,"lastSeq":0,"live":false}"""))
        server.enqueue(json(200, """{"readSeq":1,"unread":0}"""))
        val client = client()
        client.send(Endpoints.thread())
        client.send(Endpoints.unread())
        client.send(Endpoints.markRead(ReadRequest(1)))
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("pk_test_abcdefghijklmnopqrstuvvd", first.headers["PF-App-Key"])
        assertTrue(first.headers["PF-SDK"]!!.startsWith("pingfront-android/"))
        assertEquals("1.0.0", first.headers["PF-Protocol"])
        assertEquals("Bearer tok-1", first.headers["Authorization"])
        assertNull(first.headers["PF-Thread-Rev"])
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.headers["PF-Thread-Rev"])
        assertEquals("7", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["PF-Thread-Rev"])
    }

    @Test fun unauthorizedRefreshesAndRetriesOnce() = runTest {
        server.enqueue(json(401, """{"error":{"code":"unauthorized","message":"expired"}}"""))
        server.enqueue(json(200, """{"unread":3,"lastSeq":4,"live":false}"""))
        assertEquals(3, client().send(Endpoints.unread()).unread)
        assertEquals(listOf("tok-1"), tokens.invalidated)
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer tok-2", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test fun idempotentRoutesRetryServerErrorsOthersDoNot() = runTest {
        server.enqueue(json(503, """{"error":{"code":"unavailable","message":"x"}}"""))
        server.enqueue(json(200, """{"unread":0,"lastSeq":0,"live":false}"""))
        client().send(Endpoints.unread())
        assertEquals(2, server.requestCount)
        server.enqueue(json(503, """{"error":{"code":"unavailable","message":"x"}}"""))
        assertFailsWith<PingFrontException.Server> { client().send(Endpoints.cardAction("c", CardActionRequest("m", "opened"))) }
        assertEquals(3, server.requestCount)
    }

    @Test fun rateLimitPausesOnlyItsScope() = runTest {
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"x","retryAfter":30,"scope":"uploads"}}"""))
        val client = client()
        assertFailsWith<PingFrontException.Server> {
            client.send(Endpoints.createUpload(CreateUploadRequest("r", "image/jpeg", 1, sha256 = "0".repeat(64))))
        }
        val cooling =
            assertFailsWith<PingFrontException.CoolingDown> {
                client.send(Endpoints.createUpload(CreateUploadRequest("r", "image/jpeg", 1, sha256 = "0".repeat(64))))
            }
        assertEquals(RateScope.Uploads, cooling.scope)
        server.enqueue(json(200, """{"unread":0,"lastSeq":0,"live":false}"""))
        client.send(Endpoints.unread())
    }

    @Test fun exportReturnsTheServersJsonUnchanged() = runTest {
        val body = """{"format":"pingfront.person.v1","exportedAt":"2026-10-31T10:00:00Z","app":{"id":"app_1","name":"Clockwise"},"person":{}}"""
        server.enqueue(json(200, body))
        assertEquals(body, client().send(Endpoints.exportMe()))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/me/export", request.url.encodedPath)
        assertEquals("Bearer tok-1", request.headers["Authorization"])
        assertEquals(SessionReason.Prepare, Endpoints.exportMe().reason)
    }

    @Test fun exportRefusesABodyThatIsNotAnExport() = runTest {
        server.enqueue(json(200, "[1,2]"))
        assertFailsWith<PingFrontException.InvalidResponse> { client().send(Endpoints.exportMe()) }
    }

    @Test fun exportRateLimitPausesOnlyExports() = runTest {
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"x","retryAfter":600,"scope":"exports"}}"""))
        val client = client()
        val limited = assertFailsWith<PingFrontException.Server> { client.send(Endpoints.exportMe()) }
        assertEquals(ErrorCode.RateLimited, limited.error.code)
        assertEquals(RateScope.Exports, limited.error.scope)
        val cooling = assertFailsWith<PingFrontException.CoolingDown> { client.send(Endpoints.exportMe()) }
        assertEquals(RateScope.Exports, cooling.scope)
        assertEquals(1, server.requestCount)
        server.enqueue(json(200, """{"unread":0,"lastSeq":0,"live":false}"""))
        client.send(Endpoints.unread())
    }

    @Test fun configHonorsNotModified() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())
        assertIs<ConfigFetch.NotModified>(client().send(Endpoints.config(draft = false, etag = "\"abc\"")))
        assertEquals("\"abc\"", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["If-None-Match"])
    }

    @Test fun presignedPutSendsTheServersHeadersExactly() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        client().putPresigned(
            byteArrayOf(1, 2, 3),
            server.url("/put").toString(),
            mapOf("Content-Type" to "image/jpeg", "x-amz-meta" to "v"),
        )
        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", put.method)
        assertEquals("v", put.headers["x-amz-meta"])
        assertNull(put.headers["PF-App-Key"])
        assertEquals(3L, put.bodySize)
    }
}
