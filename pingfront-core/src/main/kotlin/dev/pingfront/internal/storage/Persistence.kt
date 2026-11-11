package dev.pingfront.internal.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dev.pingfront.internal.runtime.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val log = Log.of(Log.Category.Storage)

/**
 * The storage seam for credentials, the outbox, the draft, the staged profile and caches.
 * Production: one encrypted DataStore per record under `noBackupFilesDir/pingfront` (never backed up);
 * every byte on disk is AES-GCM sealed with a Keystore key. Tests use [MemoryPersistence].
 */
internal interface Persistence {
    suspend fun read(name: String): ByteArray?

    suspend fun write(name: String, data: ByteArray)

    suspend fun remove(name: String)

    /** Moves an unreadable record aside (kept for diagnosis, never overwritten) and clears it. */
    suspend fun quarantine(name: String)

    /** Deletes every record moved aside by [quarantine] (logout and erase: nothing of the person stays). */
    suspend fun removeQuarantined()
}

internal object Records {
    const val CREDENTIALS = "install"
    const val PENDING_LOGOUTS = "pending-logouts"
    const val PROFILE = "staged-profile"
    const val OUTBOX = "outbox"
    const val DRAFT = "draft"
    const val CONFIG = "config"
    const val THREAD = "thread-cache"
    const val HOST_LOOK_VERSION = "host-look-version"
    const val TEAM_ACTION_ACKS = "team-action-acks"
    const val TEAM_ACTIONS_DELIVERED = "team-actions-delivered"
    const val SENT_PROFILE_DIGEST = "sent-profile-digest"
    const val CONTEXT_COLLECTION = "context-collection"
    const val SENT_SCREENS_DIGEST = "sent-screens-digest"
    const val CARD_ACTIONS = "card-actions"

    fun photo(clientId: String, index: Int): String = "photo-$clientId-$index"
}

/** A Keystore `ProviderException` (unchecked) is a transient storage failure, never a crash (review finding CS-7). */
private inline fun <T> guarded(block: () -> T): T = try {
    block()
} catch (e: java.security.ProviderException) {
    throw StorageUnavailableException(e)
}

/** Encrypted DataStore records. One `DataStore<ByteArray>` per record name, created on first use. */
internal class DataStorePersistence(
    private val directory: File,
    private val cipher: SecretCipher,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) : Persistence {
    private val mutex = Mutex()
    private val stores = HashMap<String, DataStore<ByteArray>>()

    private class SealedSerializer(private val cipher: SecretCipher) : Serializer<ByteArray> {
        override val defaultValue: ByteArray = ByteArray(0)

        override suspend fun readFrom(input: InputStream): ByteArray {
            val sealed = input.readBytes()
            if (sealed.isEmpty()) return defaultValue
            return guarded { cipher.decrypt(sealed) } ?: throw CorruptionException("The record could not be decrypted.")
        }

        override suspend fun writeTo(t: ByteArray, output: OutputStream) {
            if (t.isNotEmpty()) output.write(guarded { cipher.encrypt(t) })
        }
    }

    private suspend fun store(name: String): DataStore<ByteArray> = mutex.withLock {
        stores.getOrPut(name) {
            DataStoreFactory.create(
                serializer = SealedSerializer(cipher),
                // A record that cannot be decrypted is moved aside first, then replaced with an empty one.
                corruptionHandler = ReplaceFileCorruptionHandler {
                    moveAside(File(directory, "$name.bin"))
                    ByteArray(0)
                },
                scope = scope,
                produceFile = { File(directory, "$name.bin") },
            )
        }
    }

    // Photos waiting in the outbox are blobs: sealed files written atomically and deleted when sent.
    private fun isBlob(name: String) = name.startsWith("photo-")

    private fun blob(name: String) = File(directory, "$name.blob")

    override suspend fun read(name: String): ByteArray? {
        if (isBlob(name)) return withContext(io) { blob(name).takeIf { it.exists() }?.readBytes()?.let { guarded { cipher.decrypt(it) } } }
        return store(name).data.first().takeIf { it.isNotEmpty() }
    }

    override suspend fun write(name: String, data: ByteArray) {
        if (isBlob(name)) {
            withContext(io) {
                directory.mkdirs()
                val temp = File(directory, "$name.tmp")
                temp.writeBytes(guarded { cipher.encrypt(data) })
                if (!temp.renameTo(blob(name))) {
                    temp.delete()
                    throw java.io.IOException("Could not store $name")
                }
            }
            return
        }
        store(name).updateData { data }
    }

    override suspend fun remove(name: String) {
        if (isBlob(name)) {
            withContext(io) { blob(name).delete() }
            return
        }
        store(name).updateData { ByteArray(0) }
    }

    override suspend fun quarantine(name: String) {
        withContext(io) { moveAside(if (isBlob(name)) blob(name) else File(directory, "$name.bin")) }
        if (!isBlob(name)) store(name).updateData { ByteArray(0) }
    }

    override suspend fun removeQuarantined() {
        withContext(io) { directory.listFiles()?.filter { it.name.contains(".unreadable-") }?.forEach { it.delete() } }
    }

    private fun moveAside(file: File) {
        if (!file.exists()) return
        var index = 1
        while (File(directory, "${file.name}.unreadable-$index").exists()) index += 1
        file.copyTo(File(directory, "${file.name}.unreadable-$index"))
        log.e("A stored record could not be read; it was kept as ${file.name}.unreadable-$index.")
    }
}
