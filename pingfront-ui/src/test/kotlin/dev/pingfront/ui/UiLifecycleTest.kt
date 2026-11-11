package dev.pingfront.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Review findings in the chat UI that were found by reading (the Android conformance and quality review, 31 Oct 2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiLifecycleTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** CQ-H4: a chat behind Home is not "visible" (pushes show, unread counts, polling runs); back in front it is again. */
    @Test fun theChatIsVisibleOnlyWhileStarted() {
        val instance = PingFrontInstance.detached()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { ChatScreen(instance, null, onClose = null) }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(instance.isChatVisible.value, "visible while on screen")
        controller.pause().stop()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(instance.isChatVisible.value, "not visible after Home")
        controller.start().resume()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(instance.isChatVisible.value, "visible again in front")
        controller.pause().stop().destroy()
        assertFalse(instance.isChatVisible.value)
    }

    /** CQ-L16: a second open (a push tap, a moment) never stacks another chat activity. */
    @Test fun openingTwiceDoesNotStackChats() {
        val presenter = ActivityChatPresenter(app)
        presenter.presentChat(null)
        val first = shadowOf(app).nextStartedActivity
        assertEquals(PingFrontChatActivity::class.java.name, first?.component?.className)
        val running = Robolectric.buildActivity(PingFrontChatActivity::class.java, first).create().start().resume()
        presenter.presentChat(null)
        assertNull(shadowOf(app).nextStartedActivity, "no second chat on top of the first")
        running.pause().stop().destroy()
    }

    /** CQ-M10: link previews only for messages a person wrote, never for the assistant or a play. */
    @Test fun previewsOnlyForMessagesFromPeople() {
        fun message(author: String, ping: String = "") = Fixtures.decodeMessage(
            """{"id":"m","seq":1,"author":{"kind":"$author","name":"x"},"createdAt":"2026-10-30T18:00:00Z"$ping,
               "parts":[{"type":"text","text":"see https://example.com"}]}""",
        )
        assertTrue(LinkPreviews.allowedFor(message("team")))
        assertTrue(LinkPreviews.allowedFor(message("person")))
        assertFalse(LinkPreviews.allowedFor(message("ai")))
        assertFalse(LinkPreviews.allowedFor(message("team", ""","ping":{"kind":"play","explanation":"x"}""")))
    }

    /**
     * Review AN-6 (3 Nov 2026): a camera capture (the full-size original, with the camera app's EXIF and GPS) stayed in
     * `cacheDir/pingfront-camera` after logout and erase; only the next capture removed it.
     */
    @Test fun aCameraCaptureIsDeletedOnWipe() {
        val file = File(File(app.cacheDir, "pingfront-camera").apply { mkdirs() }, "photo-1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ChatUi.wipe(app)
        assertFalse(file.exists(), "the camera capture outlived the wipe")
    }

    /** CQ-M13: the shared photo (the one chat image on disk) goes with the person on logout and erase. */
    @Test fun theSharedPhotoIsDeletedOnWipe() {
        val file = File(File(app.cacheDir, "pingfront-share").apply { mkdirs() }, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        PhotoShare.clearNow(app)
        assertFalse(file.exists())
    }
}
