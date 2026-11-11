package dev.pingfront.internal.session

import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.wire.Device
import dev.pingfront.internal.wire.Identity
import dev.pingfront.internal.wire.Limits
import dev.pingfront.internal.wire.PushRegistration
import dev.pingfront.internal.wire.UpdateMeRequest
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.WireRules
import dev.pingfront.internal.wire.withoutPaths
import dev.pingfront.model.Entitlement
import dev.pingfront.model.HostLook
import dev.pingfront.model.OfferSlot
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val log = Log.of(Log.Category.Session)

/** Everything the SDK reports about the person. Persisted encrypted, so identify() survives a restart. */
@Serializable
internal data class Profile(
    val device: Device? = null,
    val identity: Identity? = null,
    val traits: Map<String, String> = emptyMap(),
    val entitlement: Entitlement? = null,
    val offers: List<OfferSlot>? = null,
    val push: PushRegistration? = null,
    val hostLook: HostLook? = null,
) {
    /** The `PATCH /me` body that turns [sent] into this: only changed fields, only changed trait keys. */
    fun patch(sent: Profile): UpdateMeRequest {
        val changedTraits = traits.filter { (key, value) -> sent.traits[key] != value }
        return UpdateMeRequest(
            device = device.takeIf { it != sent.device },
            identity = identity.takeIf { it != sent.identity },
            traits = changedTraits.takeIf { it.isNotEmpty() },
            entitlement = entitlement.takeIf { it != sent.entitlement },
            offers = offers.takeIf { it != sent.offers },
            push = when {
                push == sent.push -> null
                push != null -> UpdateMeRequest.PushChange.Set(push)
                else -> UpdateMeRequest.PushChange.Clear
            },
            hostLook = hostLook.takeIf { it != sent.hostLook },
        )
    }

    /**
     * The new "sent" profile after a PATCH that rejected [rejected] fields: those fields count as
     * delivered, so they are dropped from the pending patch instead of being retried.
     */
    fun acceptRejected(staged: Profile, rejected: List<String>): Profile = rejected.fold(this) { sent, path ->
        val top = path.substringBefore('.')
        val sub = path.substringAfter('.', "")
        when (top) {
            "device" -> sent.copy(device = staged.device)
            "identity" -> sent.copy(identity = staged.identity)
            "traits" -> if (sub.isEmpty()) {
                sent.copy(traits = staged.traits)
            } else {
                sent.copy(traits = staged.traits[sub]?.let { sent.traits + (sub to it) } ?: sent.traits)
            }
            "entitlement" -> sent.copy(entitlement = staged.entitlement)
            "offers" -> sent.copy(offers = staged.offers)
            "push" -> sent.copy(push = staged.push)
            "hostLook" -> sent.copy(hostLook = staged.hostLook)
            else -> sent
        }
    }

    /**
     * This profile without the fields that break the contract's rules ([WireRules]), and the paths
     * that were dropped. One bad field (an email the server would refuse, an offer id with a space)
     * must never block the session or the rest of the PATCH (review finding CQ-H1).
     */
    fun sanitized(): Pair<Profile, List<String>> {
        var json = WireJson.encodeToJsonElement(serializer(), this)
        val dropped = mutableListOf<String>()
        repeat(MAX_SANITIZE_PASSES) {
            val problem = WireRules.profileProblems(json).lastOrNull() ?: return decode(json) to dropped
            dropped += problem
            json = json.withoutPaths(listOf(problem))
        }
        return decode(json) to dropped
    }

    /** This profile without the fields at [paths] (the server's `fields[].path`, for example `identity.email`). */
    fun without(paths: List<String>): Profile {
        if (paths.isEmpty()) return this
        return decode(WireJson.encodeToJsonElement(serializer(), this).withoutPaths(paths))
    }

    private fun decode(json: kotlinx.serialization.json.JsonElement): Profile = WireJson.decodeFromJsonElement(serializer(), json)

    fun apply(change: ProfileChange): Profile = when (change) {
        is ProfileChange.SetDevice -> copy(device = change.device)
        is ProfileChange.SetIdentity -> copy(identity = change.identity)
        is ProfileChange.Traits -> copy(traits = ProfileValidation.capTraits(traits, change.traits))
        is ProfileChange.SetEntitlement -> copy(entitlement = change.entitlement)
        is ProfileChange.Offers -> copy(offers = change.offers)
        is ProfileChange.Push -> copy(push = change.registration)
        is ProfileChange.SetHostLook -> copy(hostLook = change.hostLook)
    }
}

/**
 * A SHA-256 per profile field, kept with the install, of what the server already has: a new process compares
 * against it and sends only what changed (review AN-10). No profile value is kept beyond its digest.
 */
internal object ProfileDigest {
    private val TOP = listOf("device", "identity", "entitlement", "offers", "push", "hostLook")

    /** The digest of [profile]'s fields; [keys] limits it to those top-level fields (`traits` covers every trait). */
    fun of(profile: Profile, keys: Set<String>? = null): Map<String, String> {
        val json = WireJson.encodeToJsonElement(Profile.serializer(), profile) as kotlinx.serialization.json.JsonObject
        val out = linkedMapOf<String, String>()
        for (key in TOP) if (keys == null || key in keys) out[key] = hash(json[key]?.toString() ?: "null")
        if (keys == null || "traits" in keys) profile.traits.forEach { (k, v) -> out["traits.$k"] = hash(v) }
        return out
    }

    /** [patch] without the fields whose staged value the server already has according to [digest]. */
    fun withoutDelivered(patch: UpdateMeRequest, staged: Profile, digest: Map<String, String>): UpdateMeRequest {
        if (digest.isEmpty()) return patch
        val now = of(staged)
        fun has(key: String) = digest[key] != null && digest[key] == now[key]
        return patch.copy(
            device = patch.device.takeUnless { has("device") },
            identity = patch.identity.takeUnless { has("identity") },
            traits = patch.traits?.filterKeys { !has("traits.$it") }?.takeIf { it.isNotEmpty() },
            entitlement = patch.entitlement.takeUnless { has("entitlement") },
            offers = patch.offers.takeUnless { has("offers") },
            push = patch.push.takeUnless { has("push") },
            hostLook = patch.hostLook.takeUnless { has("hostLook") },
        )
    }

    private fun hash(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

/** One change to the staged profile. */
internal sealed interface ProfileChange {
    data class SetDevice(val device: Device) : ProfileChange

    data class SetIdentity(val identity: Identity?) : ProfileChange

    data class Traits(val traits: Map<String, String>) : ProfileChange

    data class SetEntitlement(val entitlement: Entitlement?) : ProfileChange

    data class Offers(val offers: List<OfferSlot>) : ProfileChange

    data class Push(val registration: PushRegistration?) : ProfileChange

    data class SetHostLook(val hostLook: HostLook) : ProfileChange
}

/** Client-side checks that keep one bad value from failing a whole PATCH. */
internal object ProfileValidation {
    /** The server's email rule (see [WireRules]): `jo@example.c` and `jo@example..com` are refused. */
    fun isEmail(value: String): Boolean = WireRules.isEmail(value)

    /** Merges [added] into [existing], keeping at most 30 keys (existing keys always update). */
    fun capTraits(existing: Map<String, String>, added: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap(existing)
        for ((key, value) in added) {
            if (key in result || result.size < Limits.TRAITS) {
                result[key] = value
            } else {
                log.e("setTraits: \"$key\" was dropped. A person has at most ${Limits.TRAITS} traits.")
            }
        }
        return result
    }

    /** Drops traits over the limits and returns the problems found. */
    fun clean(traits: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val result = linkedMapOf<String, String>()
        val problems = mutableListOf<String>()
        for ((key, value) in traits.toSortedMap()) {
            when {
                key.isEmpty() || Limits.codePoints(key) > Limits.TRAIT_KEY_CHARS ->
                    problems +=
                        "Trait key \"${Limits.truncate(key, KEY_PREVIEW_CHARS)}\" must be 1–64 characters."
                Limits.codePoints(value) > Limits.TRAIT_VALUE_CHARS -> problems += "Trait \"$key\" is longer than 512 characters."
                else -> result[key] = value
            }
        }
        return result to problems
    }
}

/** Reads the claims of an HS256 identity token. The signature is verified by the server, never here. */
internal object IdentityToken {
    data class Claims(val sub: String, val name: String?, val email: String?)

    fun claims(token: String): Claims? {
        val segments = token.split('.')
        if (segments.size != 3) return null
        return try {
            val json = Base64.getUrlDecoder().decode(segments[1].trimEnd('=')).decodeToString()
            val obj: JsonObject = WireJson.parseToJsonElement(json).jsonObject
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
            Claims(text("sub") ?: return null, text("name"), text("email"))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private const val KEY_PREVIEW_CHARS = 20
private const val MAX_SANITIZE_PASSES = 64
