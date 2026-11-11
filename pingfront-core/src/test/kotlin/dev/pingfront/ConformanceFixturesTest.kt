package dev.pingfront

import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.CardActionRequest
import dev.pingfront.internal.wire.CreateSessionRequest
import dev.pingfront.internal.wire.CreateSessionResponse
import dev.pingfront.internal.wire.CreateUploadRequest
import dev.pingfront.internal.wire.Device
import dev.pingfront.internal.wire.Identity
import dev.pingfront.internal.wire.ListenedMomentsResponse
import dev.pingfront.internal.wire.MomentRequest
import dev.pingfront.internal.wire.PushRegistration
import dev.pingfront.internal.wire.ReadRequest
import dev.pingfront.internal.wire.RefreshSessionRequest
import dev.pingfront.internal.wire.SendMessageRequest
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.internal.wire.ThreadResponse
import dev.pingfront.internal.wire.UpdateMeResponse
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.WireRules
import dev.pingfront.model.ChatLook
import dev.pingfront.model.Entitlement
import dev.pingfront.model.Message
import dev.pingfront.model.OfferSlot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Runs the protocol's language-neutral conformance fixtures (shared with the Go
 * server, TypeScript and iOS) against the Android models, so drift is caught here too.
 */
class ConformanceFixturesTest {
    private val root = dev.pingfront.testing.ProtocolFixtures.root

    /** Schemas the SDK decodes, and the decoder for each. Fixtures for other schemas (dashboard, plays, webhooks) are server-side. */
    private val decoders: Map<String, (JsonElement) -> Any?> = mapOf(
        "Message" to decoder(Message.serializer()),
        "ThreadResponse" to decoder(ThreadResponse.serializer()),
        "AppConfig" to decoder(AppConfig.serializer()),
        "ChatLook" to decoder(ChatLook.serializer()),
        "CreateSessionResponse" to decoder(CreateSessionResponse.serializer()),
        "UpdateMeResponse" to decoder(UpdateMeResponse.serializer()),
        "ListenedMomentsResponse" to decoder(ListenedMomentsResponse.serializer()),
        "Entitlement" to decoder(Entitlement.serializer()),
        "OfferSlot" to decoder(OfferSlot.serializer()),
        "StoreOffer" to { json -> dev.pingfront.model.StoreOffer.decodeOrNull(json) ?: error("not decoded") },
        "Device" to decoder(Device.serializer()),
        "AppInfo" to decoder(Device.App.serializer()),
        // `outcome` and `failure` (link cards) are read by nothing on Android yet; they decode as ignored fields.
        "CardResult" to decoder(dev.pingfront.model.CardResult.serializer()),
        "LinkCard" to { json ->
            val card = WireJson.decodeFromJsonElement(dev.pingfront.model.Card.serializer(), json)
            require(card is dev.pingfront.model.Card.Link) { "decoded as $card" }
            card
        },
        "DeviceContext" to decoder(dev.pingfront.internal.wire.DeviceContext.serializer()),
        "ContextCollection" to decoder(dev.pingfront.internal.wire.ContextCollection.serializer()),
        "Identity" to decoder(Identity.serializer()),
        "PushRegistration" to decoder(PushRegistration.serializer()),
        "CreateSessionRequest" to decoder(CreateSessionRequest.serializer()),
        "RefreshSessionRequest" to decoder(RefreshSessionRequest.serializer()),
        "SendMessageRequest" to decoder(SendMessageRequest.serializer()),
        "ReadRequest" to decoder(ReadRequest.serializer()),
        "CardActionRequest" to decoder(CardActionRequest.serializer()),
        "CreateUploadRequest" to decoder(CreateUploadRequest.serializer()),
        "MomentRequest" to decoder(MomentRequest.serializer()),
        "StoreMomentProps" to decoder(StoreMomentProps.serializer()),
        "ServerEvent" to { json -> ServerEvent.decode(json.toString())?.takeIf { it !is ServerEvent.Unknown } ?: error("not decoded") },
        "ErrorBody" to { json ->
            ApiClient.serverError(429, json.toString().encodeToByteArray(), null).also {
                require(it.code.raw == json.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            }
        },
        "PersonSummary" to decoder(dev.pingfront.model.PersonSummary.serializer()),
        "HostLook" to decoder(dev.pingfront.model.HostLook.serializer()),
        "StoreActionCard" to { json ->
            val card = WireJson.decodeFromJsonElement(dev.pingfront.model.Card.serializer(), json)
            require(card is dev.pingfront.model.Card.StoreAction && card.action != dev.pingfront.model.Card.StoreAction.Action.Unknown) { "decoded as $card" }
            card
        },
        "StoreActionResultRequest" to decoder(dev.pingfront.internal.wire.StoreActionResultRequest.serializer()),
        "StoreActionResultResponse" to decoder(dev.pingfront.internal.wire.StoreActionResultResponse.serializer()),
        "TeamActionPayload" to decoder(dev.pingfront.internal.wire.TeamActionPayload.serializer()),
        "TeamActionAck" to decoder(dev.pingfront.internal.wire.TeamActionAck.serializer()),
        // The SDK only writes live client frames: every valid frame must be one the SDK produces, byte for byte in meaning.
        "ClientEvent" to { json ->
            val frames = listOf(
                dev.pingfront.internal.wire.ClientEvent.Typing(true),
                dev.pingfront.internal.wire.ClientEvent.Typing(false),
                dev.pingfront.internal.wire.ClientEvent.Ping,
            )
            frames.firstOrNull { WireJson.parseToJsonElement(it.encode()) == json } ?: error("no SDK frame encodes $json")
        },
        "PersonExport" to { json ->
            dev.pingfront.internal.net.Endpoints.exportMe().decoder.decode(200, emptyMap(), json.toString().encodeToByteArray())
        },
        // Request schemas the SDK builds as JSON (no decoder needed): their fixtures run through WireRules below.
        "UpdateMeRequest" to rulesAccept("UpdateMeRequest"),
        "Traits" to rulesAccept("Traits"),
    )

    private fun rulesAccept(schema: String): (JsonElement) -> Any? = { json ->
        val problems = WireRules.problems(schema, json)
        require(problems != null && problems.isEmpty()) { "WireRules refused $problems" }
        json
    }

    /**
     * Fixture schemas in SDK-facing folders that Android deliberately has no model for, each with its reason.
     * Anything else without a decoder fails [everyValidCaseDecodes]: nothing is skipped silently.
     */
    private val notOnAndroid: Map<String, String> = mapOf(
        "OfferSignatureRequest" to "App Store promotional offers only; Play offers need no signature",
        "OfferSignatureResponse" to "App Store promotional offers only; Play offers need no signature",
        "ApnsPayload" to "iOS push",
        "WebPushPayload" to "web push",
        "PersonWebPushPayload" to "web push to a person's browser, read by the web widget's service worker",
        "FcmData" to "decoded by PushPayload.parse in fcmPayloadsMatchTheFixtures (string map, not JSON)",
        "DashEvent" to "dashboard live socket",
        "DashTranslationEvent" to "dashboard live socket",
        "DashClientEvent" to "frames the dashboard and the team app send on the dashboard live socket",
        "TeamApnsPayload" to "pushes to teammates' iPhones, read by the native team app",
        "TeamPushContent" to "pushes to teammates' iPhones, read by the native team app",
        "LookStateResponse" to "dashboard look editor",
        "SaveDraftRequest" to "dashboard look editor",
    )

    /** Folders whose schemas are server- or dashboard-side only. */
    private val serverOnlyFolders = setOf("api", "dashboard", "plays", "webhooks")

    private fun <T> decoder(serializer: KSerializer<T>): (JsonElement) -> Any? = { WireJson.decodeFromJsonElement(serializer, it) }

    private fun schemaFiles() = root.walk().filter {
        // Files at the top (keys.json, link-urls.json) are rule vectors, not schema fixtures.
        it.isFile && it.extension == "json" && it.parentFile != root && it.parentFile?.name != "scenarios"
    }.toList()

    @Test fun everyValidCaseDecodes() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (file in schemaFiles()) {
            val fixture = WireJson.parseToJsonElement(file.readText()).jsonObject
            val schema = fixture["schema"]!!.jsonPrimitive.content
            val folder = file.parentFile?.name.orEmpty()
            val decode = decoders[schema]
            if (decode == null) {
                if (folder !in serverOnlyFolders && schema !in notOnAndroid) {
                    failures += "$folder/${file.name}: no Android decoder for $schema and no reason in notOnAndroid"
                }
                continue
            }
            for (case in fixture["valid"]?.jsonArray.orEmpty()) {
                checked += 1
                val name = case.jsonObject["name"]?.jsonPrimitive?.content
                try {
                    assertNotNull(decode(case.jsonObject["value"]!!))
                } catch (e: Exception) {
                    failures += "${file.name} › $name: ${e.javaClass.simpleName}: ${e.message?.take(160)}"
                }
            }
        }
        assertTrue(checked > 40, "expected the SDK fixtures to be present ($checked cases)")
        assertEquals(emptyList(), failures)
    }

    /**
     * Review CQ-M16: what the SDK *sends* is checked too. Every `invalid` case of a request schema must be
     * refused by the client-side rules ([WireRules], the checks that drop a field before it is sent), and
     * every `valid` case must pass them. Request schemas are the ones [WireRules.problems] knows.
     */
    @Test fun requestRulesMatchEveryValidAndInvalidCase() {
        val failures = mutableListOf<String>()
        var invalidChecked = 0
        for (file in schemaFiles()) {
            val fixture = WireJson.parseToJsonElement(file.readText()).jsonObject
            val schema = fixture["schema"]!!.jsonPrimitive.content
            for (case in fixture["valid"]?.jsonArray.orEmpty()) {
                val problems = WireRules.problems(schema, case.jsonObject["value"]!!) ?: break
                if (problems.isNotEmpty()) failures += "${file.name} › valid ${case.jsonObject["name"]}: refused $problems"
            }
            for (case in fixture["invalid"]?.jsonArray.orEmpty()) {
                val problems = WireRules.problems(schema, case.jsonObject["value"]!!) ?: break
                invalidChecked += 1
                if (problems.isEmpty()) failures += "${file.name} › invalid ${case.jsonObject["name"]}: accepted"
            }
        }
        assertTrue(invalidChecked > 40, "expected the request fixtures to be present ($invalidChecked cases)")
        assertEquals(emptyList(), failures)
    }

    /** `valid[].expect`: after decoding, defaults are filled as the contract says (compared field by field, timestamps as instants). */
    @Test fun decodedValuesMatchExpect() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (file in schemaFiles()) {
            val fixture = WireJson.parseToJsonElement(file.readText()).jsonObject
            val serializer = encoders[fixture["schema"]!!.jsonPrimitive.content] ?: continue
            for (case in fixture["valid"]?.jsonArray.orEmpty()) {
                val expect = case.jsonObject["expect"] ?: continue
                checked += 1
                val actual = serializer(case.jsonObject["value"]!!)
                if (normalize(actual) != normalize(expect)) failures += "${file.name} › ${case.jsonObject["name"]}: $actual"
            }
        }
        assertTrue(checked >= 4, "expected expect cases ($checked)")
        assertEquals(emptyList(), failures)
    }

    /** Request bodies the SDK sends from its models: every valid case survives decode and encode unchanged, so no field is lost. */
    @Test fun sentRequestsKeepEveryValidField() {
        val sent = mapOf(
            "StoreActionResultRequest" to roundTrip(dev.pingfront.internal.wire.StoreActionResultRequest.serializer()),
            "TeamActionAck" to roundTrip(dev.pingfront.internal.wire.TeamActionAck.serializer()),
            "CardActionRequest" to roundTrip(CardActionRequest.serializer()),
            "ReadRequest" to roundTrip(ReadRequest.serializer()),
            "CreateUploadRequest" to roundTrip(CreateUploadRequest.serializer()),
        )
        val failures = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (file in schemaFiles()) {
            val fixture = WireJson.parseToJsonElement(file.readText()).jsonObject
            val schema = fixture["schema"]!!.jsonPrimitive.content
            val encode = sent[schema] ?: continue
            seen += schema
            for (case in fixture["valid"]?.jsonArray.orEmpty()) {
                val value = case.jsonObject["value"]!!
                val actual = encode(value)
                if (normalize(actual) != normalize(value)) failures += "${file.name} › ${case.jsonObject["name"]}: $actual"
            }
        }
        assertEquals(sent.keys, seen, "every sent schema has a fixture")
        assertEquals(emptyList(), failures)
    }

    /** Decode with the SDK's model, then encode again. */
    private val encoders: Map<String, (JsonElement) -> JsonElement> = mapOf(
        "CreateSessionResponse" to roundTrip(CreateSessionResponse.serializer()),
        "UpdateMeResponse" to roundTrip(UpdateMeResponse.serializer()),
        "OfferSlot" to roundTrip(OfferSlot.serializer()),
        "CreateSessionRequest" to roundTrip(CreateSessionRequest.serializer()),
    )

    private fun <T> roundTrip(serializer: KSerializer<T>): (JsonElement) -> JsonElement =
        { WireJson.encodeToJsonElement(serializer, WireJson.decodeFromJsonElement(serializer, it)) }

    private fun normalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterValues { it !is kotlinx.serialization.json.JsonNull }.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(value.map(::normalize))
        is JsonPrimitive -> if (value.isString) {
            try {
                JsonPrimitive(java.time.OffsetDateTime.parse(value.content).toInstant().toString())
            } catch (_: java.time.format.DateTimeParseException) {
                value
            }
        } else {
            value
        }
    }

    /** `PersonExport` (what `exportMyData()` returns): its messages decode, and a photo retention deleted reads as removed. */
    @Test fun removedImagePartsFromThePersonExportFixture() {
        val fixture = WireJson.parseToJsonElement(File(root, "sdk/PersonExport.json").readText()).jsonObject
        val images = fixture["valid"]!!.jsonArray.flatMap { case ->
            val thread = case.jsonObject["value"]!!.jsonObject["person"]!!.jsonObject["thread"] as? JsonObject
            thread?.get("messages")?.jsonArray.orEmpty().map { WireJson.decodeFromJsonElement(Message.serializer(), it) }
        }.flatMap { it.parts }.filterIsInstance<dev.pingfront.model.MessagePart.Image>()
        val removed = images.filter { it.removed }
        assertTrue(removed.isNotEmpty(), "the PersonExport fixture carries a removed image part")
        removed.forEach {
            assertNull(it.url)
            assertEquals("att_1", it.attachmentId)
        }
        // A url next to removed: true is dropped, so nothing tries to fetch it; present photos keep theirs.
        fun part(json: String) = WireJson.decodeFromString(
            Message.serializer(),
            """{"id":"m","seq":1,"author":{"kind":"team"},"createdAt":"2026-10-30T18:00:00Z","parts":[$json]}""",
        ).parts.single() as dev.pingfront.model.MessagePart.Image
        val both = part("""{"type":"image","attachmentId":"a","width":1,"height":1,"url":"https://f.test/a","removed":true}""")
        assertTrue(both.removed)
        assertNull(both.url)
        val present = part("""{"type":"image","attachmentId":"a","width":1,"height":1,"url":"https://f.test/a"}""")
        assertTrue(!present.removed && present.url != null)
        // The thread cache keeps the flag (encode, then decode).
        val message = dev.pingfront.model.Message(
            id = "m",
            seq = 1,
            author = dev.pingfront.model.Author(dev.pingfront.model.Author.Kind.Team),
            parts = listOf(dev.pingfront.model.MessagePart.Image("a", 1, 1, removed = true)),
            createdAt = java.time.Instant.parse("2026-10-30T18:00:00Z"),
        )
        val cached = WireJson.decodeFromString(Message.serializer(), WireJson.encodeToString(Message.serializer(), message))
        assertTrue((cached.parts.single() as dev.pingfront.model.MessagePart.Image).removed)
    }

    @Test fun fcmPayloadsMatchTheFixtures() {
        val fixture = WireJson.parseToJsonElement(File(root, "push/FcmData.json").readText()).jsonObject
        fun asStrings(value: JsonElement): Map<String, String>? {
            val obj = value as? JsonObject ?: return null
            if (obj.values.any { it !is JsonPrimitive || !it.isString }) return null
            return obj.mapValues { it.value.jsonPrimitive.content }
        }
        for (case in fixture["valid"]!!.jsonArray) {
            val data = asStrings(case.jsonObject["value"]!!)
            assertNotNull(data?.let { PushPayload.parse(it) }, case.jsonObject["name"].toString())
        }
        for (case in fixture["invalid"]!!.jsonArray) {
            val data = asStrings(case.jsonObject["value"]!!)
            assertNull(data?.let { PushPayload.parse(it) }, case.jsonObject["name"].toString())
        }
    }

    @Test fun keysMatchTheFixtures() {
        val keys = WireJson.parseToJsonElement(File(root, "keys.json").readText()).jsonObject
        assertEquals(KeyCheck.BASE62, keys["alphabet"]!!.jsonPrimitive.content)
        keys["crc32"]!!.jsonArray.forEach {
            assertEquals(it.jsonObject["expected"]!!.jsonPrimitive.long, KeyCheck.crc32(it.jsonObject["input"]!!.jsonPrimitive.content))
        }
        keys["checkChars"]!!.jsonArray.forEach {
            assertEquals(it.jsonObject["expected"]!!.jsonPrimitive.content, KeyCheck.checkChars(it.jsonObject["input"]!!.jsonPrimitive.content))
        }
        keys["make"]!!.jsonArray.forEach { case ->
            val o = case.jsonObject
            val body = (o["randomBytes"] as JsonArray).joinToString("") { KeyCheck.BASE62[it.jsonPrimitive.int % 62].toString() }
            val head = "${o["kind"]!!.jsonPrimitive.content}_${o["mode"]!!.jsonPrimitive.content}_$body"
            assertEquals(o["expected"]!!.jsonPrimitive.content, head + KeyCheck.checkChars(head), o["name"].toString())
        }
        keys["valid"]!!.jsonArray.forEach { case ->
            val o = case.jsonObject
            assertTrue(KeyCheck.check(o["key"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content) is KeyCheck.Outcome.Valid, o["name"].toString())
        }
        keys["invalid"]!!.jsonArray.forEach { case ->
            val o = case.jsonObject
            val outcome = KeyCheck.check(o["key"]!!.jsonPrimitive.content, o["kind"]?.jsonPrimitive?.content ?: "pk")
            assertEquals(o["problem"]!!.jsonPrimitive.content, (outcome as? KeyCheck.Outcome.Invalid)?.problem?.code, o["name"].toString())
        }
    }
}
