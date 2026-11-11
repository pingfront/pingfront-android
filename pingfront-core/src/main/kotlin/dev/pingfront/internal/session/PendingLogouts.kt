package dev.pingfront.internal.session

import dev.pingfront.PingFrontException
import dev.pingfront.internal.net.ApiClient
import dev.pingfront.internal.net.Endpoints
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.InstallCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Log.of(Log.Category.Session)

/**
 * The one owner of logouts the server has not confirmed yet (`POST /me/logout` without a bearer,
 * retried until accepted). Entries are added and removed by `installId`; the list is never overwritten.
 */
internal class PendingLogouts(private val records: RecordStore) {
    private val mutex = Mutex()

    private suspend fun readLocked(): List<InstallCredentials> = records.read(StoredRecords.pendingLogouts) ?: emptyList()

    private suspend fun writeLocked(list: List<InstallCredentials>) {
        if (list.isEmpty()) records.remove(StoredRecords.pendingLogouts) else records.write(StoredRecords.pendingLogouts, list)
    }

    suspend fun add(credentials: InstallCredentials) = mutex.withLock {
        val list = readLocked()
        if (list.none { it.installId == credentials.installId }) writeLocked(list + credentials)
    }

    suspend fun remove(installId: String) = mutex.withLock {
        writeLocked(readLocked().filterNot { it.installId == installId })
    }

    suspend fun all(): List<InstallCredentials> = mutex.withLock { readLocked() }

    /** Sends every pending logout. Transient failures stay queued; accepted or rejected ones are removed. */
    suspend fun drain(api: ApiClient) {
        for (credentials in all()) {
            try {
                api.send(Endpoints.logout(credentials))
                remove(credentials.installId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PingFrontException) {
                if (!e.isTransient) {
                    log.i("Logout was not accepted: ${e.logDescription}")
                    remove(credentials.installId)
                }
            }
        }
    }
}
