package dev.pingfront.internal.wire

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The request rules of the API's OpenAPI contract, checked on the JSON the SDK is about to send.
 * Each check returns the paths of the fields that break a rule, in the server's `fields[].path`
 * form (`identity.email`, `offers.0.id`, `traits.plan`), so the SDK can drop exactly those fields
 * instead of sending a request the server answers with 422 (review finding CQ-H1).
 *
 * The protocol's `invalid` fixtures are the test vectors (`ConformanceFixturesTest`). Limits count
 * Unicode code points, as the contract says.
 */
internal object WireRules {
    private val UUID = Regex(
        "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|" +
            "00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
    )
    private val MOMENT_NAME = Regex("^[a-z0-9][a-z0-9_.-]{0,63}$")
    private val OFFER_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val PRICE = Regex("^\\d+(\\.\\d{1,4})?$")
    private val NON_BLANK = Regex("\\S")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
    private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$")

    // The email rule the server applies (the same shape as zod's `email()`): no leading, trailing or
    // doubled dots in the local part, and a domain with a TLD of at least two letters.
    private val EMAIL = Regex(
        "^(?!\\.)(?!.*\\.\\.)([A-Za-z0-9_'+\\-.]*)[A-Za-z0-9_+-]@([A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$",
    )

    private val PLATFORMS = setOf("ios", "android", "web", "macos")
    private val REASONS = setOf("chat_opened", "moment", "prepare", "push_opened", "report")
    private val ENTITLEMENT_STATES = setOf("none", "trial", "active", "grace", "billing_retry", "cancelled", "expired", "lifetime")
    private val ENTITLEMENT_SOURCES = setOf("storekit", "play", "revenuecat", "superwall", "adapty", "stripe", "app")
    private val OFFER_KINDS = setOf("standard", "discount", "winback")
    private val OFFER_SOURCES = setOf("revenuecat", "superwall", "adapty", "storekit", "play", "app")
    private val PUSH_PROVIDERS = setOf("apns", "fcm", "webpush")
    private val PUSH_ENVIRONMENTS = setOf("sandbox", "production")
    private val FONTS = setOf("system", "rounded", "serif", "custom")
    private val CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/heic", "image/webp")
    private val CARD_ACTIONS = setOf("opened", "chosen", "rated", "dismissed", "failed")
    private val LINK_FAILURES = setOf("not_handled", "unknown_screen", "cannot_open")
    private val SCREEN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$")
    private const val SCREENS_MAX = 200
    private const val SCREEN_TITLE_CHARS = 60
    private val INSTALL_SOURCES = setOf("app_store", "testflight", "development", "play_store", "other_store", "sideloaded", "other")
    private val SCREEN_CLASSES = setOf("compact", "regular")
    private val TEXT_SIZES = setOf("default", "large", "accessibility")
    private val NETWORKS = setOf("wifi", "cellular", "wired", "none", "other")
    private val FREE_STORAGE = setOf("under_1gb", "from_1_to_5gb", "from_5_to_20gb", "over_20gb")
    private val THERMAL = setOf("nominal", "fair", "serious", "critical")
    private val MEMORY_PRESSURE = setOf("normal", "warning", "critical")
    private val AUTHORIZATIONS = setOf("authorized", "provisional", "ephemeral", "denied", "not_determined")

    private const val ID_CHARS = 64
    private const val OFFERS = 20
    private const val PRODUCTS = 10
    private const val INSTALL_SECRET_CHARS = 200
    private const val PUSH_TOKEN_CHARS = 4096
    private const val HOST_ICON_CHARS = 200_000
    private const val PROP_VALUE_CHARS = 256
    private const val SIGNED_TRANSACTION_CHARS = 16_000
    private const val PURCHASE_TOKEN_CHARS = 1024
    private const val TRANSACTION_CHARS = 64
    private const val PRODUCT_ID_CHARS = 100
    private const val ENVIRONMENT_CHARS = 32
    private const val RATING_MAX = 5L
    private const val INSTALLER_CHARS = 100
    private const val LANGUAGES = 3
    private const val LANGUAGE_TAG_CHARS = 20
    private const val SCALE_MIN = 1.0
    private const val SCALE_MAX = 4.0
    private const val BATTERY_MAX = 100L

    /** Checks [value] against the request schema [schema]; `null` when the SDK does not send that schema. */
    fun problems(schema: String, value: JsonElement): List<String>? {
        val out = Problems()
        when (schema) {
            "CreateSessionRequest" -> createSession(value, out)
            "RefreshSessionRequest" -> refreshSession(value, out)
            "UpdateMeRequest" -> updateMe(value, "", out)
            "Device" -> device(value, "", out, full = true)
            "DeviceContext" -> deviceContext(value, "", out)
            "Identity" -> identity(value, "", out)
            "Traits" -> traits(value, "", out)
            "Entitlement" -> entitlement(value, "", out)
            "OfferSlot" -> offerSlot(value, "", out)
            "PushRegistration" -> push(value, "", out)
            "HostLook" -> hostLook(value, "", out)
            "MomentRequest" -> moment(value, out)
            "StoreMomentProps" -> store(value, "", out)
            "SendMessageRequest" -> sendMessage(value, out)
            "ReadRequest" -> obj(value, "", out) { o -> int(o, "upToSeq", out, required = true, min = 0) }
            "CreateUploadRequest" -> upload(value, out)
            "CardActionRequest" -> cardAction(value, out)
            else -> return null
        }
        return out.paths
    }

    /** Problems in a staged profile (the fields of `PATCH /me`; the device is the partial device). */
    fun profileProblems(profile: JsonElement): List<String> = Problems().also { updateMe(profile, "", it) }.paths

    fun isEmail(value: String): Boolean = Limits.codePoints(value) <= Limits.EMAIL_CHARS && EMAIL.matches(value)

    // Schemas

    private fun createSession(value: JsonElement, out: Problems) = obj(value, "", out) { o ->
        device(present(o, "device") ?: return@obj out.add("device"), "device", out, full = true)
        string(o, "installSecret", out, max = INSTALL_SECRET_CHARS)
        present(o, "identity")?.let { identity(it, "identity", out) }
        present(o, "traits")?.let { traits(it, "traits", out) }
        present(o, "entitlement")?.let { entitlement(it, "entitlement", out) }
        present(o, "offers")?.let { offers(it, "offers", out) }
        present(o, "hostLook")?.let { hostLook(it, "hostLook", out) }
        enum(o, "reason", REASONS, out, required = true)
    }

    private fun refreshSession(value: JsonElement, out: Problems) = obj(value, "", out) { o ->
        pattern(o, "installId", UUID, out, required = true)
        string(o, "installSecret", out, required = true)
        present(o, "device")?.let { device(it, "device", out, full = false) }
    }

    private fun updateMe(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        present(o, "device")?.let { device(it, join(path, "device"), out, full = false) }
        present(o, "identity")?.let { identity(it, join(path, "identity"), out) }
        present(o, "traits")?.let { traits(it, join(path, "traits"), out) }
        present(o, "entitlement")?.let { entitlement(it, join(path, "entitlement"), out) }
        present(o, "offers")?.let { offers(it, join(path, "offers"), out) }
        present(o, "push")?.let { push(it, join(path, "push"), out) }
        present(o, "hostLook")?.let { hostLook(it, join(path, "hostLook"), out) }
    }

    private fun device(value: JsonElement, path: String, out: Problems, full: Boolean) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        fun at(key: String) = join(p ?: "", key)
        pattern(o, "installId", UUID, out, required = full, path = p)
        enum(o, "platform", PLATFORMS, out, required = full, path = p)
        val sdk = present(o, "sdk")
        if (sdk == null) {
            if (full) out.add(at("sdk"))
        } else {
            obj(sdk, at("sdk"), out) { s ->
                string(s, "name", out, required = true, max = 40, path = at("sdk"))
                string(s, "version", out, required = true, max = 20, path = at("sdk"))
            }
        }
        val app = present(o, "app")
        if (app == null) {
            if (full) out.add(at("app"))
        } else {
            obj(app, at("app"), out) { a ->
                string(a, "version", out, required = true, max = 40, path = at("app"))
                string(a, "build", out, required = true, max = 40, path = at("app"))
                string(a, "bundleId", out, required = true, max = 200, path = at("app"))
                present(a, "screens")?.let { screens(it, join(at("app"), "screens"), out) }
            }
        }
        string(o, "os", out, required = full, max = 40, path = p)
        string(o, "model", out, required = full, max = 60, path = p)
        string(o, "locale", out, required = full, max = 40, path = p)
        string(o, "language", out, required = full, max = 20, path = p)
        string(o, "timeZone", out, required = full, max = 60, path = p)
        present(o, "context")?.let { deviceContext(it, at("context"), out) }
    }

    private fun screens(value: JsonElement, path: String, out: Problems) {
        val list = value as? JsonArray ?: return out.add(path)
        if (list.size > SCREENS_MAX) return out.add(path)
        list.forEachIndexed { index, item ->
            val at = join(path, index.toString())
            obj(item, at, out) { s ->
                pattern(s, "id", SCREEN_ID, out, required = true, path = at)
                string(s, "title", out, min = 1, max = SCREEN_TITLE_CHARS, path = at)
            }
        }
    }

    private fun deviceContext(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        fun group(key: String, block: (JsonObject, String) -> Unit) {
            val v = present(o, key) ?: return
            val at = join(path, key)
            obj(v, at, out) { block(it, at) }
        }
        group("build") { g, at ->
            enum(g, "installSource", INSTALL_SOURCES, out, path = at)
            pattern(g, "installer", PACKAGE_NAME, out, path = at)
            string(g, "installer", out, max = INSTALLER_CHARS, path = at)
            bool(g, "debuggable", out, path = at)
        }
        group("display") { g, at ->
            enum(g, "screenClass", SCREEN_CLASSES, out, path = at)
            number(g, "scale", out, min = SCALE_MIN, max = SCALE_MAX, path = at)
            bool(g, "darkMode", out, path = at)
            enum(g, "textSize", TEXT_SIZES, out, path = at)
            for (key in listOf("screenReader", "reduceMotion", "boldText")) bool(g, key, out, path = at)
            present(g, "languages")?.let { languages ->
                val list = languages as? JsonArray
                val ok = list != null && list.size <= LANGUAGES && list.all {
                    val tag = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    tag != null && tag.length <= LANGUAGE_TAG_CHARS && LANGUAGE_TAG.matches(tag)
                }
                if (!ok) out.add(join(at, "languages"))
            }
        }
        group("connection") { g, at ->
            enum(g, "network", NETWORKS, out, path = at)
            for (key in listOf("constrained", "expensive", "lowPower")) bool(g, key, out, path = at)
        }
        group("health") { g, at ->
            enum(g, "freeStorage", FREE_STORAGE, out, path = at)
            int(g, "battery", out, min = 0, max = BATTERY_MAX, path = at)
            bool(g, "charging", out, path = at)
            enum(g, "thermal", THERMAL, out, path = at)
            enum(g, "memoryPressure", MEMORY_PRESSURE, out, path = at)
        }
        group("notifications") { g, at ->
            enum(g, "authorization", AUTHORIZATIONS, out, path = at)
            bool(g, "channelEnabled", out, path = at)
        }
    }

    private fun identity(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        string(o, "userId", out, required = true, min = 1, max = Limits.USER_ID_CHARS, path = p)
        string(o, "name", out, max = Limits.NAME_CHARS, path = p)
        val email = o["email"]
        if (email != null && email !is JsonNull) {
            val text = (email as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text == null || !isEmail(text)) out.add(join(path, "email"))
        }
        string(o, "token", out, max = Limits.IDENTITY_TOKEN_CHARS, path = p)
    }

    private fun traits(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        if (o.size > Limits.TRAITS) out.add(path)
        for ((key, v) in o) {
            val keyOk = key.isNotEmpty() && Limits.codePoints(key) <= ID_CHARS
            val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!keyOk || text == null || Limits.codePoints(text) > Limits.TRAIT_VALUE_CHARS) out.add(join(path, key))
        }
    }

    private fun entitlement(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        enum(o, "state", ENTITLEMENT_STATES, out, required = true, path = p)
        string(o, "productId", out, max = PRODUCT_ID_CHARS, path = p)
        timestamp(o, "renewsAt", out, path = p)
        timestamp(o, "expiresAt", out, path = p)
        timestamp(o, "gracePeriodEndsAt", out, path = p)
        enum(o, "source", ENTITLEMENT_SOURCES, out, path = p)
        string(o, "environment", out, max = ENVIRONMENT_CHARS, path = p)
    }

    private fun offers(value: JsonElement, path: String, out: Problems) {
        val list = value as? JsonArray ?: return out.add(path)
        if (list.size > OFFERS) out.add(path)
        list.forEachIndexed { index, slot -> offerSlot(slot, join(path, index.toString()), out) }
    }

    private fun offerSlot(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        pattern(o, "id", OFFER_ID, out, required = true, path = p)
        string(o, "title", out, required = true, max = 60, path = p)
        enum(o, "kind", OFFER_KINDS, out, required = true, path = p)
        if ((o["available"] as? JsonPrimitive)?.booleanOrNull == null) out.add(join(path, "available"))
        enum(o, "source", OFFER_SOURCES, out, path = p)
        val products = present(o, "products") ?: return@obj
        val list = products as? JsonArray ?: return@obj out.add(join(path, "products"))
        if (list.size > PRODUCTS) out.add(join(path, "products"))
        list.forEachIndexed { index, product ->
            val at = join(join(path, "products"), index.toString())
            obj(product, at, out) { po ->
                string(po, "id", out, required = true, max = PRODUCT_ID_CHARS, path = at)
                string(po, "price", out, required = true, max = 40, path = at)
                string(po, "period", out, max = 12, path = at)
                string(po, "trial", out, max = 12, path = at)
            }
        }
    }

    private fun push(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        enum(o, "provider", PUSH_PROVIDERS, out, required = true, path = p)
        string(o, "token", out, required = true, min = 1, max = PUSH_TOKEN_CHARS, path = p)
        enum(o, "environment", PUSH_ENVIRONMENTS, out, path = p)
    }

    private fun hostLook(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        string(o, "appName", out, required = true, max = 100, path = p)
        for (key in listOf("accent", "background")) {
            val mode = present(o, key) ?: continue
            obj(mode, join(path, key), out) { m ->
                pattern(m, "light", HEX, out, required = true, path = join(path, key))
                pattern(m, "dark", HEX, out, required = true, path = join(path, key))
            }
        }
        enum(o, "font", FONTS, out, path = p)
        string(o, "iconPng", out, max = HOST_ICON_CHARS, path = p)
    }

    private fun moment(value: JsonElement, out: Problems) = obj(value, "", out) { o ->
        pattern(o, "name", MOMENT_NAME, out, required = true)
        pattern(o, "clientId", UUID, out, required = true)
        timestamp(o, "occurredAt", out)
        present(o, "props")?.let { props ->
            obj(props, "props", out) { po ->
                if (po.size > Limits.MOMENT_PROPS) out.add("props")
                for ((key, v) in po) {
                    val primitive = v as? JsonPrimitive
                    val ok = Limits.codePoints(key) <= ID_CHARS && primitive != null && primitive !is JsonNull &&
                        (
                            if (primitive.isString) {
                                Limits.codePoints(primitive.content) <= PROP_VALUE_CHARS
                            } else {
                                primitive.booleanOrNull != null || primitive.doubleOrNull?.isFinite() == true
                            }
                            )
                    if (!ok) out.add(join("props", key))
                }
            }
        }
        present(o, "store")?.let { store(it, "store", out) }
    }

    private fun store(value: JsonElement, path: String, out: Problems) = obj(value, path, out) { o ->
        val p = path.ifEmpty { null }
        string(o, "productId", out, required = true, max = PRODUCT_ID_CHARS, path = p)
        string(o, "transactionId", out, max = TRANSACTION_CHARS, path = p)
        string(o, "originalTransactionId", out, max = TRANSACTION_CHARS, path = p)
        string(o, "purchaseToken", out, min = 1, max = PURCHASE_TOKEN_CHARS, path = p)
        string(o, "orderId", out, min = 1, max = TRANSACTION_CHARS, path = p)
        pattern(o, "price", PRICE, out, path = p)
        string(o, "currency", out, min = 3, max = 3, path = p)
        string(o, "signedTransaction", out, max = SIGNED_TRANSACTION_CHARS, path = p)
    }

    private fun sendMessage(value: JsonElement, out: Problems) = obj(value, "", out) { o ->
        pattern(o, "clientId", UUID, out, required = true)
        string(o, "text", out, max = Limits.TEXT_CHARS)
        val attachments = present(o, "attachmentIds")?.let { it as? JsonArray ?: return@obj out.add("attachmentIds") }
        if (attachments != null) {
            if (attachments.size > Limits.IMAGES_PER_MESSAGE) out.add("attachmentIds")
            attachments.forEachIndexed { index, id -> if (!isId(id)) out.add("attachmentIds.$index") }
        }
        present(o, "replyToCard")?.let { reply ->
            obj(reply, "replyToCard", out) { r ->
                id(r, "messageId", out, required = true, path = "replyToCard")
                id(r, "cardId", out, required = true, path = "replyToCard")
                id(r, "choiceId", out, path = "replyToCard")
                int(r, "rating", out, min = 1, max = RATING_MAX, path = "replyToCard")
            }
        }
        val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val hasContent = (text != null && NON_BLANK.containsMatchIn(text)) || !attachments.isNullOrEmpty() || present(o, "replyToCard") != null
        if (!hasContent) out.add("text")
    }

    private fun upload(value: JsonElement, out: Problems) = obj(value, "", out) { o ->
        pattern(o, "requestId", UUID, out, required = true)
        enum(o, "contentType", CONTENT_TYPES, out, required = true)
        int(o, "bytes", out, required = true, min = 1, max = Limits.IMAGE_BYTES.toLong())
        int(o, "width", out, min = 1)
        int(o, "height", out, min = 1)
        pattern(o, "sha256", SHA256, out, required = true)
    }

    private fun cardAction(value: JsonElement, out: Problems) = obj(value, "", out) { o ->
        id(o, "messageId", out, required = true)
        enum(o, "action", CARD_ACTIONS, out, required = true)
        id(o, "choiceId", out)
        int(o, "rating", out, min = 1, max = RATING_MAX)
        enum(o, "failure", LINK_FAILURES, out)
        pattern(o, "clientId", UUID, out)
    }

    // Primitive checks

    private class Problems {
        val paths = mutableListOf<String>()

        fun add(path: String) {
            if (path !in paths) paths += path
        }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private inline fun obj(value: JsonElement, path: String, out: Problems, block: (JsonObject) -> Unit) {
        val o = value as? JsonObject ?: return out.add(path)
        block(o)
    }

    private fun present(o: JsonObject, key: String): JsonElement? = o[key]?.takeUnless { it is JsonNull }

    private fun string(o: JsonObject, key: String, out: Problems, required: Boolean = false, min: Int = 0, max: Int = Int.MAX_VALUE, path: String? = null) {
        val at = join(path ?: "", key)
        val v = present(o, key) ?: return if (required) out.add(at) else Unit
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out.add(at)
        val length = Limits.codePoints(text)
        if (length < min || length > max) out.add(at)
    }

    private fun pattern(o: JsonObject, key: String, regex: Regex, out: Problems, required: Boolean = false, path: String? = null) {
        val at = join(path ?: "", key)
        val v = present(o, key) ?: return if (required) out.add(at) else Unit
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null || !regex.matches(text)) out.add(at)
    }

    private fun enum(o: JsonObject, key: String, allowed: Set<String>, out: Problems, required: Boolean = false, path: String? = null) {
        val at = join(path ?: "", key)
        val v = present(o, key) ?: return if (required) out.add(at) else Unit
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null || text !in allowed) out.add(at)
    }

    private fun int(
        o: JsonObject,
        key: String,
        out: Problems,
        required: Boolean = false,
        min: Long = Long.MIN_VALUE,
        max: Long = Long.MAX_VALUE,
        path: String? = null,
    ) {
        val at = join(path ?: "", key)
        val v = present(o, key) ?: return if (required) out.add(at) else Unit
        val number = (v as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (number == null || number < min || number > max) out.add(at)
    }

    private fun number(o: JsonObject, key: String, out: Problems, min: Double, max: Double, path: String? = null) {
        val at = join(path ?: "", key)
        val v = present(o, key) ?: return
        val number = (v as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        if (number == null || number < min || number > max) out.add(at)
    }

    private fun bool(o: JsonObject, key: String, out: Problems, path: String? = null) {
        val v = present(o, key) ?: return
        if ((v as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) out.add(join(path ?: "", key))
    }

    private fun isId(v: JsonElement): Boolean {
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return text.isNotEmpty() && Limits.codePoints(text) <= ID_CHARS
    }

    private fun id(o: JsonObject, key: String, out: Problems, required: Boolean = false, path: String? = null) =
        string(o, key, out, required = required, min = 1, max = ID_CHARS, path = path)

    private fun timestamp(o: JsonObject, key: String, out: Problems, path: String? = null) {
        val at = join(path ?: "", key)
        val v = present(o, key) ?: return
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out.add(at)
        try {
            OffsetDateTime.parse(text)
        } catch (_: DateTimeParseException) {
            out.add(at)
        }
    }
}

/**
 * Removes the fields at [paths] from [value] (`a.b`, `offers.2`). When a path names a field that is
 * already absent, its parent is removed instead, so a missing required field drops the whole object.
 */
internal fun JsonElement.withoutPaths(paths: List<String>): JsonElement = paths.fold(this) { current, path ->
    remove(current, path.split('.'))
}

private fun remove(value: JsonElement, keys: List<String>): JsonElement {
    if (keys.isEmpty()) return value
    val head = keys.first()
    return when (value) {
        is JsonObject -> {
            val child = value[head] ?: return value
            if (keys.size == 1) {
                JsonObject(value - head)
            } else {
                val next = remove(child, keys.drop(1))
                // The deeper field was already gone: drop this one instead.
                if (next == child) JsonObject(value - head) else JsonObject(value + (head to next))
            }
        }
        is JsonArray -> {
            val index = head.toIntOrNull() ?: return value
            if (index !in value.indices) return value
            if (keys.size == 1) {
                JsonArray(value.filterIndexed { i, _ -> i != index })
            } else {
                val next = remove(value[index], keys.drop(1))
                if (next == value[index]) {
                    JsonArray(value.filterIndexed { i, _ -> i != index })
                } else {
                    JsonArray(value.mapIndexed { i, e -> if (i == index) next else e })
                }
            }
        }
        else -> value
    }
}
