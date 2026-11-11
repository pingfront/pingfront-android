@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.pingfront.testing.Bodies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the React Native and Flutter bridges need from the Android SDK. */
class BridgeApiTests {
    private class Presenter : ChatPresenter {
        val opened = mutableListOf<String>()

        override fun presentChat(topic: dev.pingfront.look.ChatTopic?) = Unit

        override fun dismissChat() = Unit

        override fun openUrl(url: String) {
            opened += url
        }

        override fun presentSimpleReport(prefill: String?) = Unit
    }

    @Test fun onLinkDecidesAndTheBrowserIsTheFallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = EngineHarness(this)
        val presenter = Presenter().also { h.instance.registry.chatPresenter = it }
        val card = dev.pingfront.model.Card.Link("l1", "Guide", "https://example.com/guide", "Read")
        val asked = mutableListOf<String>()
        h.instance.onLink = { url ->
            asked += url
            true
        }
        h.engine.act(card, "m1", null, null)
        advanceUntilIdle()
        assertEquals(listOf("https://example.com/guide"), asked)
        assertTrue(presenter.opened.isEmpty(), "the app handled it")
        h.instance.onLink = { false }
        h.engine.act(card, "m1", null, null)
        advanceUntilIdle()
        assertEquals(listOf("https://example.com/guide"), presenter.opened, "not handled: the browser opens it")
        h.stop()
        Dispatchers.resetMain()
    }

    @Test fun selfCheckReturnsATypedResult() = runTest {
        assertEquals("not_configured", PingFrontInstance().selfCheck().code)
        val h = EngineHarness(this)
        h.transport.json("POST thread/messages", body = Bodies.sent(1, "00000000-0000-4000-8000-000000000001"))
        val result = h.instance.selfCheck()
        assertEquals(SetupCheckResult.Pass, result)
        assertEquals("PingFront self-check: PASS", result.line)
        assertEquals("PingFront self-check: FAIL network: Check it.", SetupCheckResult(false, "network", "Check it.").line)
        h.stop()
    }
}

/** Flutter and React Native configure after the first activity: tracking starts at process start instead. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EarlyActivityTrackingTest {
    @Test fun activitiesAndLaunchExtrasAreSeenBeforeConfigure() {
        val instance = PingFrontInstance()
        assertFalse(instance.isConfigured.value)
        instance.attach(ApplicationProvider.getApplicationContext<Application>())
        val intent = Intent(Intent.ACTION_MAIN).putExtra("pingfront_selfcheck", true)
        val activity = Robolectric.buildActivity(Activity::class.java, intent).setup().get()
        assertSame(activity, instance.activities.current(), "the first chat can open from a real activity")
        assertTrue(instance.heldLaunch()?.getBooleanExtra("pingfront_selfcheck", false) == true, "read when configure runs")
    }
}
