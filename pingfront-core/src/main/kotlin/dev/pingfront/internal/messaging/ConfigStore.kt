package dev.pingfront.internal.messaging

import dev.pingfront.PingFrontException
import dev.pingfront.internal.net.AuthorizedClient
import dev.pingfront.internal.net.ConfigFetch
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.storage.CachedConfig
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.WireJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

private val log = Log.of(Log.Category.Look)

/**
 * Owns the remote app config: ETag caching (encrypted on disk), at most one refetch per 5 minutes
 * unless forced (a live `config` event), and the draft look for test devices (memory only).
 */
internal class ConfigStore(private val api: AuthorizedClient, private val records: RecordStore, private val time: TimeSource) {
    private val mutex = Mutex()
    private var published: AppConfig? = null
    private var etag: String? = null
    private var lastFetch: Duration? = null
    private var draft: AppConfig? = null
    private var loaded = false

    /** The cached published config (no network). */
    suspend fun cached(): AppConfig? = mutex.withLock {
        loadLocked()
        published
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        try {
            val cached = records.read(StoredRecords.config) ?: return
            published = WireJson.decodeFromString(AppConfig.serializer(), cached.body)
            etag = cached.etag
        } catch (e: PingFrontException.StorageUnavailable) {
            loaded = false
            log.w("The cached look is not readable yet (${e.logDescription}).")
        } catch (_: SerializationException) {
            log.w("The cached look could not be read; the defaults apply until the next fetch.")
        }
    }

    /** The config to render: the draft on test devices, else the published one. */
    suspend fun current(testDevice: Boolean): AppConfig? = mutex.withLock {
        loadLocked()
        if (testDevice) draft ?: published else published
    }

    /** Refetches when the cache is older than 5 minutes, or always when [force]. */
    suspend fun refresh(testDevice: Boolean, force: Boolean = false): AppConfig? {
        val (sendEtag, skip) = mutex.withLock {
            loadLocked()
            val now = time.now()
            val last = lastFetch
            if (!force && last != null && now - last < MINIMUM_INTERVAL) return@withLock null to true
            (if (testDevice) null else etag) to false
        }
        if (skip) return current(testDevice)
        try {
            // Drafts are only served with the test device's bearer token (the endpoint uses bearer auth for drafts).
            val fetch = api.send(Endpoints.config(draft = testDevice, etag = sendEtag))
            if (fetch !is ConfigFetch.Fresh) mutex.withLock { lastFetch = time.now() }
            if (fetch is ConfigFetch.Fresh) {
                val body = fetch.body
                val config = fetch.config
                val newTag = fetch.etag
                mutex.withLock {
                    // Only a successful fetch starts the 5-minute window; a failed one may retry at once (review CQ-L6).
                    lastFetch = time.now()
                    if (config.draft) {
                        draft = config
                    } else {
                        published = config
                        draft = null
                        etag = newTag
                    }
                }
                if (!config.draft) {
                    try {
                        records.write(StoredRecords.config, CachedConfig(newTag, body))
                    } catch (e: PingFrontException.StorageUnavailable) {
                        log.d("The config cache was not written (${e.logDescription}).")
                    }
                }
                log.i("Applied look revision ${config.revision}${if (config.draft) " (draft)" else ""}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PingFrontException) {
            log.i("Config fetch failed: ${e.logDescription}")
        }
        return current(testDevice)
    }

    suspend fun clearDraft() = mutex.withLock { draft = null }

    companion object {
        val MINIMUM_INTERVAL: Duration = 5.minutes
    }
}
