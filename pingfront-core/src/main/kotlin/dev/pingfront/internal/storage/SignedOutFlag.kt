package dev.pingfront.internal.storage

import dev.pingfront.internal.runtime.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private val log = Log.of(Log.Category.Storage)

/**
 * Set from the start of a logout, erase or revocation until the next session starts. The push gate reads it without
 * suspending (FCM calls `onMessageReceived` on its own thread and posts the notification before it returns), so a push
 * that cold-starts the app after an offline logout shows nothing for the previous person.
 */
internal interface SignedOutFlag {
    /** Read without suspending. */
    fun isSet(): Boolean

    suspend fun set(on: Boolean)

    /** Tests: memory only. A test that "restarts the process" passes the same instance to the next engine. */
    class Memory : SignedOutFlag {
        @Volatile private var on = false

        override fun isSet(): Boolean = on

        override suspend fun set(on: Boolean) {
            this.on = on
        }
    }
}

/**
 * Production: an empty marker file in the app key's record directory (under `noBackupFilesDir`, never backed up). It
 * holds nothing of the person; its presence is the whole record.
 */
internal class FileSignedOutFlag(private val directory: File, private val io: CoroutineDispatcher) : SignedOutFlag {
    private val marker = File(directory, NAME)

    override fun isSet(): Boolean = marker.exists()

    override suspend fun set(on: Boolean) {
        withContext(io) {
            try {
                if (on) {
                    directory.mkdirs()
                    marker.createNewFile()
                } else if (marker.exists() && !marker.delete()) {
                    throw IOException("not deleted")
                }
            } catch (e: IOException) {
                log.w("The signed-out flag could not be ${if (on) "set" else "cleared"} (${e.javaClass.simpleName}).")
            } catch (e: SecurityException) {
                log.w("The signed-out flag could not be ${if (on) "set" else "cleared"} (${e.javaClass.simpleName}).")
            }
        }
    }

    companion object {
        const val NAME: String = "signed-out"

        /**
         * The gate before `configure` (React Native and Flutter configure from script, later than FCM can wake the app):
         * true when some app key's record directory under [root] is not signed out. No directory means no
         * session ever started here, so there is nobody to notify.
         */
        fun anySignedIn(root: File): Boolean = root.listFiles()?.any { it.isDirectory && !File(it, NAME).exists() } == true
    }
}
