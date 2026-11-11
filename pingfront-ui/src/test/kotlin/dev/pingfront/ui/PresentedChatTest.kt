package dev.pingfront.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.pingfront.PingFront
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit 5, item 3: which activities count as "the presented chat" for `closeChatAndWait()`. The plain report form is
 * not the chat, and a sheet behind Home or the photo picker is still closed.
 */
@OptIn(dev.pingfront.InternalPingFrontApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresentedChatTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun chat(report: Boolean) =
        Robolectric.buildActivity(PingFrontChatActivity::class.java, PingFrontChatActivity.intent(app, null, report = report, prefill = null))

    @AfterTest fun reset() {
        // Leave the shared instance as found: nothing presented.
        while (PingFront.instance.presentedChat.value) PingFront.instance.presentedChatChanged(false)
    }

    @Test fun theReportFormIsNotThePresentedChat() {
        val report = chat(report = true).create().start().resume()
        assertEquals(false, PingFront.instance.presentedChat.value, "the report form doesn't count")
        report.pause().stop().destroy()
    }

    @Test fun aReportOverTheSheetDoesNotClearTheSheet() {
        val sheet = chat(report = false).create().start().resume()
        assertEquals(true, PingFront.instance.presentedChat.value)
        val report = chat(report = true).create().start().resume()
        report.pause().stop().destroy()
        assertEquals(true, PingFront.instance.presentedChat.value, "the sheet is still on screen")
        sheet.pause().stop().destroy()
        assertEquals(false, PingFront.instance.presentedChat.value)
    }

    @Test fun aSheetBehindHomeIsClosedAtOnce() {
        val sheet = chat(report = false).create().start().resume().pause().stop()
        assertEquals(false, PingFront.instance.presentedChat.value, "not on screen behind Home")
        ActivityChatPresenter(app).dismissChat()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(sheet.get().isFinishing, "closed although it isn't drawn")
        sheet.destroy()
    }
}
