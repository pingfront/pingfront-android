package dev.pingfront.internal.wire

import dev.pingfront.model.Message
import dev.pingfront.model.ThreadStatus
import dev.pingfront.model.ThreadStatusSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Server events on the live socket (the API's `ServerEvent`). */
internal sealed interface ServerEvent {
    data class Hello(
        val lastSeq: Int,
        val readSeq: Int,
        val rev: Int? = null,
        val teamReadSeq: Int? = null,
        val status: ThreadStatus? = null,
    ) : ServerEvent

    data class NewMessage(val seq: Int, val message: Message) : ServerEvent

    data class MessageUpdated(val seq: Int, val message: Message) : ServerEvent

    data class Typing(val isAi: Boolean, val name: String?, val on: Boolean) : ServerEvent

    data class Read(val upToSeq: Int) : ServerEvent

    data class Thread(val status: ThreadStatus) : ServerEvent

    data object Reset : ServerEvent

    /** The team reset the conversation: every message is gone; the draft and the outbox stay. */
    data class ThreadReset(val threadId: String) : ServerEvent

    data class Config(val revision: Int, val draft: Boolean) : ServerEvent

    data object Reauth : ServerEvent

    /** An event type this SDK version does not know. Ignored. */

    // A team action for the host app. No seq: it never touches the thread or gap repair.
    data class TeamAction(val action: TeamActionPayload) : ServerEvent

    data class Unknown(val type: String) : ServerEvent

    companion object {
        /** Decodes one frame, or returns `null` for malformed JSON. */
        fun decode(text: String): ServerEvent? = try {
            val obj = WireJson.parseToJsonElement(text).jsonObject
            fun int(key: String) = (obj[key] as? JsonPrimitive)?.intOrNull ?: error("missing $key")
            fun message() = WireJson.decodeFromJsonElement(Message.serializer(), obj["message"] ?: error("message"))
            when (val type = obj.string("type") ?: "") {
                "hello" -> Hello(
                    int("lastSeq"),
                    int("readSeq"),
                    // A hello without a thread (sent before the first message) has no revision to keep. The server
                    // leaves threadId out and sends rev 0; revisions start at 1, so 0 is never stored.
                    (obj["rev"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 && obj["threadId"] !is JsonNull },
                    (obj["teamReadSeq"] as? JsonPrimitive)?.intOrNull,
                    obj.string("status")?.let(::status),
                )
                "message" -> NewMessage(int("seq"), message())
                "message_updated" -> MessageUpdated(int("seq"), message())
                "typing" -> {
                    val author = obj["author"] as? JsonObject
                    Typing(author?.string("kind") == "ai", author?.string("name"), (obj["on"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
                "read" -> Read(int("upToSeq"))
                "thread" -> Thread(status(obj.string("status") ?: ""))
                "reset" -> Reset
                "thread_reset" -> ThreadReset(obj.string("threadId") ?: error("missing threadId"))
                "config" -> Config(int("revision"), (obj["draft"] as? JsonPrimitive)?.booleanOrNull ?: false)
                "reauth" -> Reauth
                "team_action" -> TeamAction(WireJson.decodeFromJsonElement(TeamActionPayload.serializer(), obj["action"] ?: error("action")))
                else -> Unknown(type)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Client events on the live socket. */
internal sealed interface ClientEvent {
    data class Typing(val on: Boolean) : ClientEvent

    data object Ping : ClientEvent

    fun encode(): String = when (this) {
        is Typing -> """{"type":"typing","on":$on}"""
        Ping -> """{"type":"ping"}"""
    }
}

internal object LiveProtocol {
    const val SUBPROTOCOL = "pingfront.v1"
}

/** A thread status from a live event; a status this SDK does not know is [ThreadStatus.Unknown], never "open". */
private fun status(raw: String): ThreadStatus = ThreadStatusSerializer.known(raw) ?: ThreadStatus.Unknown
