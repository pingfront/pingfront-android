package dev.pingfront.internal.storage

import dev.pingfront.internal.messaging.OutboxItem
import dev.pingfront.internal.session.Profile
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.model.Message
import dev.pingfront.model.TeamMember
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** The last page of the thread, cached for offline opening. */
@Serializable
internal data class CachedThread(val messages: List<Message>, val team: List<TeamMember>, val hasOlder: Boolean)

/** The published config body and its ETag. */
@Serializable
internal data class CachedConfig(val etag: String? = null, val body: String)

/** Every record the SDK stores, with its schema version. Bump a version only with a migration. */
internal object StoredRecords {
    val credentials = RecordStore.Record(Records.CREDENTIALS, InstallCredentials.serializer())
    val pendingLogouts = RecordStore.Record(Records.PENDING_LOGOUTS, ListSerializer(InstallCredentials.serializer()))
    val profile = RecordStore.Record(Records.PROFILE, Profile.serializer())
    val outbox = RecordStore.Record(Records.OUTBOX, ListSerializer(OutboxItem.serializer()))
    val draft = RecordStore.Record(Records.DRAFT, String.serializer())
    val config = RecordStore.Record(Records.CONFIG, CachedConfig.serializer())
    val thread = RecordStore.Record(Records.THREAD, CachedThread.serializer())
    val hostLookVersion = RecordStore.Record(Records.HOST_LOOK_VERSION, String.serializer())
    val teamActionAcks = RecordStore.Record(
        Records.TEAM_ACTION_ACKS,
        ListSerializer(dev.pingfront.internal.wire.QueuedTeamActionAck.serializer()),
    )

    /** A SHA-256 per profile field the server has, so a new process sends only changes (review AN-10). */
    val sentProfileDigest = RecordStore.Record(
        Records.SENT_PROFILE_DIGEST,
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()),
    )

    /** Team action ids already handed to the app, so none is handed over twice (review AN-9). */
    val teamActionsDelivered = RecordStore.Record(Records.TEAM_ACTIONS_DELIVERED, ListSerializer(String.serializer()))

    /** The app's context switches from the latest session answer (an app setting, kept across logout). */
    val contextCollection = RecordStore.Record(Records.CONTEXT_COLLECTION, dev.pingfront.internal.wire.ContextCollection.serializer())

    /** A SHA-256 of the app version and the screens list this install last delivered (the protocol, Saved links). */
    val sentScreensDigest = RecordStore.Record(Records.SENT_SCREENS_DIGEST, String.serializer())

    /** Card action reports waiting for the server, each with its clientId (the protocol, "Retrying writes"). */
    val cardActions = RecordStore.Record(Records.CARD_ACTIONS, ListSerializer(dev.pingfront.internal.messaging.QueuedCardAction.serializer()))
}
