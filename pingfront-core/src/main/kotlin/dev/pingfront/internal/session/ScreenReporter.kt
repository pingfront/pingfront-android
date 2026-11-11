package dev.pingfront.internal.session

import dev.pingfront.AppScreen
import dev.pingfront.PingFrontException
import dev.pingfront.internal.runtime.Log
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.Device
import dev.pingfront.internal.wire.WireScreen
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Log.of(Log.Category.Session)

/** The screens list for one request, and the digest that marks it delivered once the server answered. */
internal class ScreensReport(val app: Device.App, val digest: String)

/**
 * Decides when the app's registered screens (`AppInfo.screens`, the protocol, "Saved links") ride on a request the
 * SDK sends anyway. It never starts a request of its own.
 *
 * - `POST /sessions`: whenever the app registered screens.
 * - A refresh or `PATCH /me`: only when the list or the app version changed since this install last sent it (a
 *   digest kept with the install).
 *
 * The list is app metadata, never logged. Guarded by a [Mutex].
 */
internal class ScreenReporter(
    private val records: RecordStore,
    /** The screens the app registered, already checked (`ScreenRules`); `null` until it registers any. */
    private val screens: () -> List<AppScreen>?,
) {
    private val mutex = Mutex()
    private var delivered: String? = null
    private var loaded = false

    /** For `POST /sessions`: the list whenever the app registered one. */
    fun forCreate(app: Device.App?): ScreensReport? {
        val base = app ?: return null
        val list = screens() ?: return null
        return report(base, list)
    }

    /** For a refresh or `PATCH /me`: the list only when it, or the app version, changed since it was last delivered. */
    suspend fun forUpdate(app: Device.App?): ScreensReport? {
        val base = app ?: return null
        val list = screens() ?: return null
        val report = report(base, list)
        return mutex.withLock {
            loadLocked()
            report.takeIf { it.digest != delivered }
        }
    }

    /** The server answered the request that carried [report] (or refused the list): it is not sent again. */
    suspend fun delivered(report: ScreensReport) {
        mutex.withLock {
            delivered = report.digest
            loaded = true
        }
        try {
            records.write(StoredRecords.sentScreensDigest, report.digest)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The sent screens could not be recorded (${e.logDescription}); the next process may send them again.")
        }
    }

    /** Logout, erase or revocation: a new install sends the list with its first session. */
    suspend fun reset() {
        mutex.withLock {
            delivered = null
            loaded = true
        }
        try {
            records.remove(StoredRecords.sentScreensDigest)
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The sent screens could not be forgotten (${e.logDescription}).")
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        try {
            delivered = records.read(StoredRecords.sentScreensDigest)
            loaded = true
        } catch (e: PingFrontException.StorageUnavailable) {
            log.w("The sent screens are not readable yet (${e.logDescription}).")
        }
    }

    private fun report(base: Device.App, list: List<AppScreen>): ScreensReport {
        val wire = list.map { WireScreen(it.id, it.title) }
        return ScreensReport(base.copy(screens = wire), digest(base.version, wire))
    }

    private fun digest(version: String, list: List<WireScreen>): String {
        val text = buildString {
            append(version)
            for (screen in list) append('\n').append(screen.id).append('\t').append(screen.title.orEmpty())
        }
        return MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
