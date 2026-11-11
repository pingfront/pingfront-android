package dev.pingfront.push

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushTest {
    private val v1 = mapOf("pf_v" to "1", "pf_kind" to "message", "pf_app_id" to "app_1", "pf_title" to "Deniz", "pf_body" to "Hi Ada")

    @Test fun recognizesOnlyV1Payloads() {
        assertTrue(PingFrontPush.isPingFrontMessage(v1))
        assertFalse(PingFrontPush.isPingFrontMessage(mapOf("pingfront" to "1")))
        assertFalse(PingFrontPush.isPingFrontMessage(v1 + ("pf_v" to "2")))
    }

    @Test fun otherAppsMessagesAreLeftAlone() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(PingFrontPush.handleMessage(context, mapOf("type" to "promo")))
        assertTrue(PingFrontPush.handleMessage(context, v1))
    }

    /** A monochrome small icon from meta-data wins, then Firebase's default notification icon, then the app's icon. */
    @Test fun theSmallIconComesFromMetaDataFirst() {
        val both = android.os.Bundle().apply {
            putInt("pingfront.notification_icon", 11)
            putInt("com.google.firebase.messaging.default_notification_icon", 22)
        }
        assertEquals(11, PingFrontPush.smallIcon(both, appIcon = 33))
        val firebase = android.os.Bundle().apply { putInt("com.google.firebase.messaging.default_notification_icon", 22) }
        assertEquals(22, PingFrontPush.smallIcon(firebase, appIcon = 33))
        assertEquals(33, PingFrontPush.smallIcon(android.os.Bundle(), appIcon = 33))
        assertEquals(33, PingFrontPush.smallIcon(null, appIcon = 33))
    }

    /** PushContent.sound false reaches Android as pf_sound off; anything else keeps the channel's sound. */
    @Test fun soundOffOnlyWhenTheServerSaysOff() {
        assertTrue(PingFrontPush.soundOff(v1 + ("pf_sound" to "off")))
        assertFalse(PingFrontPush.soundOff(v1))
        assertFalse(PingFrontPush.soundOff(v1 + ("pf_sound" to "loud")))
    }

    /** Review CS-8 / CQ-M3: the tap goes to the SDK's non-exported PushTapActivity, never to the launcher with extras. */
    @Test fun tapsGoToTheSdksOwnActivity() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = org.robolectric.Shadows.shadowOf(PingFrontPush.contentIntent(context, v1)).savedIntent
        assertEquals("dev.pingfront.internal.android.PushTapActivity", intent.component?.className)
        assertEquals("message", intent.getStringExtra("pf_kind"))
        val info = context.packageManager.getActivityInfo(intent.component!!, 0)
        assertFalse(info.exported, "only this app can start it")
    }

    /** Review CQ-H3: a received (not tapped) push never reached `PingFront.unreadCount`. */
    @Test fun aReceivedPushUpdatesTheUnreadCount() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        PingFrontPush.handleMessage(context, v1 + ("pf_unread" to "3"))
        assertEquals(3, dev.pingfront.PingFront.unreadCount.value)
    }
}
