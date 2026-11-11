@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Checks for two iOS findings of 3 Nov 2026 on Android. A-13: iOS dropped the device's push token on logout, so the
 * next install never sent it and got no pushes. IO-8: iOS left push marked as registered after logout, so unread
 * polling stopped. On Android the FCM token belongs to the device: it is staged again for the next person and goes
 * out with the next install's first PATCH, also after a restart, so neither happens. These tests hold that.
 */
class PushAfterLogoutTest {
    @Test fun theNextInstallSendsThePushTokenWithItsFirstPatch() = runTest {
        val persistence = MemoryPersistence()
        val h = EngineHarness(this, ScriptedTransport(), persistence)
        h.transport.json("POST me/logout", status = 204, body = "")
        h.instance.setPushToken("fcm-token-1")
        h.engine.session.token(SessionReason.Prepare)
        advanceTimeBy(1_000)
        advanceUntilIdle()
        h.instance.logout()
        advanceUntilIdle()
        h.stop()

        // The app restarts (the token is no longer in memory) and the next person opens the chat.
        val next = EngineHarness(this, ScriptedTransport(), persistence)
        next.transport.json("POST sessions", body = Bodies.session(installId = "0b6f3c1e-8a52-4c1f-9d7e-2f4a6b8c0d1e", token = "tok-9"))
        next.engine.session.token(SessionReason.ChatOpened)
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val patch = next.transport.last("PATCH me")?.body?.decodeToString().orEmpty()
        assertTrue(patch.contains("fcm-token-1"), "the next install's first PATCH did not carry the push token: $patch")
        next.stop()
    }
}
