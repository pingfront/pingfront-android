package dev.pingfront.ui

import androidx.test.core.app.ApplicationProvider
import dev.pingfront.PingFront
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Links in text bubbles go through the app's onLink first, then a browsable intent (web links only). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkRoutingTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @AfterTest fun reset() {
        PingFront.onLink = null
    }

    @Test fun onLinkWinsWhenItHandlesTheLink() {
        val instance = PingFront.instance
        val seen = mutableListOf<String>()
        instance.onLink = {
            seen += it
            true
        }
        ChatLinkHandler(instance, app).openUri("https://example.com/help")
        assertEquals(listOf("https://example.com/help"), seen)
        assertNull(shadowOf(app).nextStartedActivity, "nothing else opens")
    }

    @Test fun otherwiseAWebLinkOpensAndOtherSchemesNever() {
        val instance = PingFront.instance
        instance.onLink = { false }
        ChatLinkHandler(instance, app).openUri("javascript:alert(1)")
        assertNull(shadowOf(app).nextStartedActivity)
        ChatLinkHandler(instance, app).openUri("https://example.com")
        assertEquals("https://example.com", shadowOf(app).nextStartedActivity?.dataString)
    }
}
