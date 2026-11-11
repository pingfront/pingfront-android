@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.conversation.PendingMessage
import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.messaging.OutboxItem
import dev.pingfront.internal.net.HttpRequest
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.net.HttpTransport
import dev.pingfront.internal.net.LiveSocket
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeSocket
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The protocol's shared behaviour scenarios (`scenarios/` in the conformance fixtures), run against the
 * real engine with a fake transport. `given` is set up through the SDK's own paths (stored credentials, a session,
 * a thread load), answered leniently; then the transport turns strict and serves exactly the scenario's steps.
 */
class BehaviourScenariosTest {
    private val root = dev.pingfront.testing.ProtocolFixtures.file("scenarios")

    private fun load(name: String): JsonObject = Json.parseToJsonElement(File(root, "$name.json").readText()).jsonObject

    @Test fun everyScenarioHasADriverHere() {
        val files = root.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(DRIVEN, files, "a new scenario needs its driver in this file")
    }

    @Test fun auth401RefreshRetry() = runTest {
        val s = Run(this, load("auth-401-refresh-retry"))
        s.setUp()
        s.strict { s.engine.loadThread() }
        s.checkRequests()
        assertEquals("at_fresh", s.engine.session.token(SessionReason.ChatOpened), "then.accessToken")
        assertEquals(3, s.transport.served, "the new token is used without another refresh")
        s.stop()
    }

    @Test fun authRefreshRevoked() = runTest {
        val s = Run(this, load("auth-refresh-revoked"))
        s.setUp(holdOutbox = true)
        // The read receipt the open chat sends (ReadReceipts.markRead), without opening the chat's live socket.
        s.strict {
            runCatching {
                s.engine.client.send(
                    dev.pingfront.internal.net.Endpoints.markRead(dev.pingfront.internal.wire.ReadRequest(3)),
                    s.engine.session.generation(),
                )
            }
        }
        s.checkRequests()
        assertNull(s.records.read(StoredRecords.credentials), "then.installSecret: null")
        assertNull(s.records.read(StoredRecords.thread), "then.thread: null")
        assertTrue(s.engine.outbox.all().isEmpty(), "then.outbox: []")
        s.stop()
    }

    @Test fun outboxResend() = runTest {
        val s = Run(this, load("outbox-resend"))
        s.setUp()
        s.strict { s.instance.conversation.send("Is my streak back?") }
        s.checkRequests()
        assertTrue(s.engine.outbox.all().isEmpty(), "then.outbox: []")
        assertEquals(listOf("msg_0", "msg_1"), s.instance.conversation.state.value.messages.map { it.id }, "one bubble")
        s.stop()
    }

    @Test fun cardActionRetry() = runTest {
        val s = Run(this, load("card-action-retry"))
        s.setUp()
        val card = dev.pingfront.model.Card.Choices(
            "card_c1",
            listOf(dev.pingfront.model.ChoiceOption("yes", "Yes please"), dev.pingfront.model.ChoiceOption("no", "No thanks")),
        )
        s.strict { s.engine.act(card, "msg_c1", "yes", null) }
        s.checkRequests()
        assertTrue(s.engine.sender.cardActions.all().isEmpty(), "then.cardActions: []")
        s.stop()
    }

    @Test fun teamActionPending() = runTest {
        val s = Run(this, load("team-action-pending"))
        s.setUp()
        val emitted = mutableListOf<TeamAction>()
        s.engine.scope.launch {
            s.engine.teamActions.actions.collect {
                emitted += it
                it.complete()
            }
        }
        s.strict { s.engine.teamActions.catchUp(s.engine.session.generation()) }
        s.checkRequests()
        assertEquals(listOf("tar_1"), emitted.map { it.id }, "then.emitted: one event, not two")
        s.stop()
    }

    @Test fun threadReset() = runTest {
        val s = Run(this, load("thread-reset"))
        s.setUp()
        s.strict { s.instance.conversation.loadOlder() }
        s.checkRequests()
        val then = s.scenario["then"]!!.jsonObject
        val thread = then["thread"]!!.jsonObject
        assertEquals(thread["messageIds"]!!.jsonArray.map { it.jsonPrimitive.content }, s.instance.conversation.state.value.messages.map { it.id })
        assertEquals(then["draft"]!!.jsonPrimitive.content, s.instance.conversation.draft.value)
        assertTrue(s.engine.outbox.all().isEmpty(), "then.outbox: []")
        s.stop()
    }

    /** One scenario run: the engine, its stores and the two-phase transport. */
    private class Run(val test: TestScope, val scenario: JsonObject) {
        val given = scenario["given"]!!.jsonObject
        val persistence = MemoryPersistence()
        val records = RecordStore(persistence)
        val transport = ScenarioTransport(scenario["steps"]!!.jsonArray.map { it.jsonObject })
        val instance = PingFrontInstance()
        lateinit var engine: Engine

        suspend fun setUp(chatVisible: Boolean = false, holdOutbox: Boolean = false) {
            val lenient = transport.lenient
            val token = given["accessToken"]?.jsonPrimitive?.content ?: "at_ok"
            val installId = given["installId"]?.jsonPrimitive?.content
            val secret = given["installSecret"]?.jsonPrimitive?.content
            if (installId != null && secret != null) records.write(StoredRecords.credentials, InstallCredentials(installId, secret))
            given["draft"]?.jsonPrimitive?.content?.let { records.write(StoredRecords.draft, it) }
            val outbox = given["outbox"]?.jsonArray.orEmpty().map {
                val item = it.jsonObject
                OutboxItem(
                    PendingMessage(
                        item["clientId"]!!.jsonPrimitive.content,
                        item["text"]?.jsonPrimitive?.content,
                        createdAt = Instant.parse("2026-11-02T11:59:00Z"),
                    ),
                )
            }
            if (outbox.isNotEmpty()) records.write(StoredRecords.outbox, outbox)
            lenient.json("POST sessions", body = Bodies.session(installId = installId ?: "0b6f3c1e-8a52-4c1f-9d7e-2f4a6b8c0d1e", token = token))
            lenient.json("POST sessions/refresh", body = Bodies.refresh(token))
            lenient.json("GET config", body = Bodies.config())
            lenient.json("PATCH me", body = """{"person":{"id":"per_1","level":"guest"}}""")
            lenient.json("GET team-actions", body = """{"actions":[]}""")
            // The outbox stays queued through set-up: its first attempt never leaves the device.
            // With [holdOutbox], a rate limit keeps it waiting for an hour, so the scenario's action goes first.
            if (holdOutbox) {
                lenient.json("POST thread/messages", status = 429, body = Bodies.error("rate_limited"), headers = mapOf("Retry-After" to "3600"))
            } else {
                lenient.offline("POST thread/messages")
            }
            val thread = given["thread"]?.takeIf { it !is JsonNull }?.jsonObject
            if (thread != null) {
                val rev = thread["rev"]!!.jsonPrimitive.int
                val messages = thread["messages"]?.jsonArray.orEmpty().map { it.jsonObject }
                val last = messages.maxOfOrNull { it["seq"]!!.jsonPrimitive.int } ?: 0
                val bodies = messages.map { m ->
                    """{"id":"${m["id"]!!.jsonPrimitive.content}","seq":${m["seq"]!!.jsonPrimitive.int},"author":{"kind":"team","name":"Deniz"},""" +
                        """"parts":[{"type":"text","text":"hi"}],"createdAt":"2026-11-01T09:00:00Z"}"""
                }
                lenient.json(
                    "GET thread",
                    body = """{"thread":{"id":"${thread["id"]!!.jsonPrimitive.content}","status":"open","lastSeq":$last,"readSeq":0,""" +
                        """"teamReadSeq":0,"unread":0,"rev":$rev},"messages":[${bodies.joinToString(
                            ",",
                        )}],"hasOlder":true,"team":[],"policy":{"canSend":true}}""",
                    headers = mapOf("PF-Thread-Rev" to "$rev"),
                )
            } else {
                lenient.json("GET thread", body = """{"thread":null,"messages":[],"hasOlder":false,"team":[],"policy":{"canSend":true}}""")
            }
            engine = Engine(
                instance,
                AppKey.parse(EngineHarness.KEY).getOrThrow(),
                EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true),
                test.testDependencies(transport, persistence),
            )
            instance.engine = engine
            engine.start()
            test.advanceTimeBy(1_000)
            engine.session.token(SessionReason.ChatOpened)
            if (thread != null) engine.loadThread()
            if (chatVisible) instance.chatVisibilityChanged(true)
            test.runCurrent()
            assertTrue(engine.session.isActive(), "set-up made a session")
        }

        /** Serves only the scenario's steps from here on, runs [action] and lets the SDK go idle. */
        suspend fun strict(action: suspend () -> Unit) {
            transport.strict = true
            action()
            // Long enough for a resend after a backoff, shorter than the 20 s unread poll the scenarios exclude.
            test.advanceTimeBy(15_000)
            test.runCurrent()
        }

        fun checkRequests() {
            transport.failure?.let { fail(it) }
            val then = scenario["then"]!!.jsonObject
            assertEquals(transport.steps.size, transport.served, "every step was served")
            assertEquals(then["requests"]!!.jsonPrimitive.int, transport.served, "then.requests")
        }

        fun stop() = engine.stop()
    }

    /** Lenient while `given` is set up; then each request must be the next scripted step. */
    private class ScenarioTransport(val steps: List<JsonObject>) : HttpTransport {
        val lenient = ScriptedTransport()
        var strict = false
        var served = 0
        var failure: String? = null
        private val bound = HashMap<String, String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            if (!strict) return lenient.execute(request)
            val path = request.url.removePrefix("https://api.test")
            val step = steps.getOrNull(served)
            if (step == null) return refuse("an unscripted request after the last step: ${request.method} $path")
            val expected = step["request"]!!.jsonObject
            val name = step["name"]!!.jsonPrimitive.content
            val operation = expected["operation"]!!.jsonPrimitive.content
            if (request.method != operation.substringBefore(' ') || path != expected["path"]!!.jsonPrimitive.content) {
                return refuse("step \"$name\": expected $operation ${expected["path"]}, got ${request.method} $path")
            }
            val headers = request.headers.mapKeys { it.key.lowercase() }
            expected["headers"]?.jsonObject?.forEach { (key, value) ->
                if (headers[key.lowercase()] != value.jsonPrimitive.content) return refuse("step \"$name\": header $key was ${headers[key.lowercase()]}")
            }
            expected["absentHeaders"]?.jsonArray?.forEach {
                if (headers.containsKey(
                        it.jsonPrimitive.content.lowercase(),
                    )
                ) {
                    return refuse("step \"$name\": header ${it.jsonPrimitive.content} must be absent")
                }
            }
            val body = request.body?.decodeToString()?.let(Json::parseToJsonElement)
            val wanted = expected["body"]
            if (wanted != null && (body == null || !matches(wanted, body))) return refuse("step \"$name\": body was $body, expected $wanted")
            if (wanted == null && body != null) return refuse("step \"$name\": expected no body, got $body")
            served += 1
            val response = step["response"]!!.jsonObject
            when (response["transport"]?.jsonPrimitive?.content) {
                "lost" -> throw java.net.SocketTimeoutException("scenario: the answer was lost")
                "offline" -> throw java.io.IOException("offline", java.net.UnknownHostException())
            }
            val status = response["status"]!!.jsonPrimitive.int
            val out = response["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty().mapKeys { it.key.lowercase() }
            return HttpResponse(status, out, (response["body"]?.toString() ?: "").encodeToByteArray())
        }

        private fun refuse(message: String): HttpResponse {
            if (failure == null) failure = message
            return HttpResponse(599, emptyMap(), """{"error":{"code":"scenario","message":"$message"}}""".encodeToByteArray())
        }

        /**
         * Deep equality, with two allowances: a `clientId` the SDK generated itself (the scenario's literal stands
         * for it, as in the Go replay) binds on first sight, and `device` (device context, sent on requests the SDK
         * makes anyway when the app's privacy switches allow) may be added to a scripted body.
         */
        private fun matches(wanted: JsonElement, actual: JsonElement, key: String? = null): Boolean = when (wanted) {
            is JsonObject ->
                actual is JsonObject &&
                    (actual.keys - "device").let { it == wanted.keys } &&
                    wanted.all { (k, v) -> matches(v, actual[k]!!, k) }
            is JsonArray -> actual is JsonArray && actual.size == wanted.size && wanted.indices.all { matches(wanted[it], actual[it]) }
            is JsonPrimitive -> if (key == "clientId" && actual is JsonPrimitive) {
                bound.getOrPut(wanted.content) { actual.content } == actual.content
            } else {
                wanted == actual || (wanted.intOrNull != null && wanted.intOrNull == (actual as? JsonPrimitive)?.intOrNull)
            }
        }

        override fun openSocket(url: String, protocols: List<String>, headers: Map<String, String>): LiveSocket = FakeSocket()
    }

    private companion object {
        val DRIVEN = setOf("auth-401-refresh-retry", "auth-refresh-revoked", "card-action-retry", "outbox-resend", "team-action-pending", "thread-reset")
    }
}
