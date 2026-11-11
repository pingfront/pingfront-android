package dev.pingfront.internal

import dev.pingfront.internal.storage.SignedOutFlag

/**
 * Whether a received push may be shown: not after logout, erase or revocation until the next session starts, also when
 * the app was killed in between ([flag] is persisted, and read without suspending on FCM's thread).
 */
internal class PushGate(private val flag: SignedOutFlag) {
    @Volatile private var signedOut = false

    fun accepts(): Boolean = !signedOut && !flag.isSet()

    /** A session started or refreshed: the person is signed in again, in memory and on disk. */
    suspend fun signedIn() {
        signedOut = false
        if (flag.isSet()) flag.set(false)
    }

    /** Logout, erase or revocation: set before anything is wiped. */
    suspend fun signedOut() {
        signedOut = true
        flag.set(true)
    }
}
