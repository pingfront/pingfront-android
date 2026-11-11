package dev.pingfront.internal.storage

import dev.pingfront.PingFrontException
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.wire.WireJson
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private val log = Log.of(Log.Category.Storage)

/**
 * Versioned records on top of [Persistence]: every record is `{"v": <schema>, "data": …}`.
 * - Older versions are migrated step by step.
 * - A record that cannot be read (or was written by a newer SDK) is moved aside, never overwritten,
 *   and the failure is logged.
 * - "Nothing stored" (`null`) and "storage not available yet" ([PingFrontException.StorageUnavailable])
 *   are different outcomes.
 */
internal class RecordStore(private val persistence: Persistence) {
    /** A record type: its name, current schema version, serializer and migrations from older versions. */
    class Record<T>(
        val name: String,
        val serializer: KSerializer<T>,
        val version: Int = 1,
        /** `migrations[n]` turns version `n` data into version `n + 1`. */
        val migrations: Map<Int, (JsonElement) -> JsonElement> = emptyMap(),
    )

    suspend fun <T> read(record: Record<T>): T? {
        val bytes = storage { persistence.read(record.name) } ?: return null
        return try {
            val envelope = WireJson.parseToJsonElement(bytes.decodeToString()).jsonObject
            val version = (envelope[VERSION] as? JsonPrimitive)?.intOrNull ?: throw SerializationException("no version")
            if (version > record.version) throw SerializationException("written by a newer SDK (v$version)")
            var data = envelope[DATA] ?: throw SerializationException("no data")
            for (from in version until record.version) {
                val step = record.migrations[from] ?: throw SerializationException("no migration from v$from")
                data = step(data)
            }
            WireJson.decodeFromJsonElement(record.serializer, data)
        } catch (e: SerializationException) {
            unreadable(record, e)
        } catch (e: IllegalArgumentException) {
            unreadable(record, e)
        }
    }

    private suspend fun <T> unreadable(record: Record<T>, error: Exception): T? {
        log.e("The ${record.name} record could not be read (${error.javaClass.simpleName}); it was moved aside.")
        storage { persistence.quarantine(record.name) }
        return null
    }

    suspend fun <T> write(record: Record<T>, value: T) {
        val envelope = JsonObject(
            mapOf(
                VERSION to JsonPrimitive(record.version),
                DATA to WireJson.encodeToJsonElement(record.serializer, value),
            ),
        )
        storage { persistence.write(record.name, envelope.toString().encodeToByteArray()) }
    }

    suspend fun remove(record: Record<*>) {
        storage { persistence.remove(record.name) }
    }

    /**
     * Every storage failure becomes [PingFrontException.StorageUnavailable]: a locked Keystore, a full
     * disk (`IOException` from DataStore), and the `ProviderException` AndroidKeyStore throws (review
     * findings CS-6 and CS-7). Nothing escapes as an unchecked exception that would end an engine loop.
     */
    private suspend fun <T> storage(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw PingFrontException.StorageUnavailable(e)
    } catch (e: java.security.ProviderException) {
        throw PingFrontException.StorageUnavailable(e)
    } catch (e: java.security.GeneralSecurityException) {
        throw PingFrontException.StorageUnavailable(e)
    }

    private companion object {
        const val VERSION = "v"
        const val DATA = "data"
    }
}
