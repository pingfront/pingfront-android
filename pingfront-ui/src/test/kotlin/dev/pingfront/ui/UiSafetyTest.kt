package dev.pingfront.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Ported from the review repros (the Android concurrency and security review, 31 Oct 2026, findings 9 and 11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiSafetyTest {
    /** Finding 9: a non-http(s) avatar, icon or image URL from the server crashed the host app (`ClassCastException`). */
    @Test fun aNonHttpImageUrlDoesNotThrow() = runTest {
        val file = java.io.File.createTempFile("avatar", ".png")
        val result = runCatching { ChatImages.load("avatar:x", file.toURI().toString(), 96) }
        file.delete()
        assertTrue(result.isSuccess, "ChatImages.load(file:…) threw ${result.exceptionOrNull()}")
        assertNull(result.getOrNull(), "only http(s) images are fetched")
    }

    /** Finding 11: link and review cards opened any scheme with ACTION_VIEW, without CATEGORY_BROWSABLE. */
    @Test fun linkCardsOpenOnlyBrowsableWebLinks() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val presenter = ActivityChatPresenter(app)
        val opened = listOf(
            "javascript:alert(document.cookie)",
            "content://com.example.provider/private",
            "file:///data/data/x/secret",
            "intent://scan/#Intent;scheme=zxing;end",
            "https://example.com/",
            "http://example.com/",
        ).map { url ->
            presenter.openUrl(url)
            val intent = shadowOf(app).nextStartedActivity
            url to intent?.let { "${it.action} ${it.data} browsable=${it.hasCategory(Intent.CATEGORY_BROWSABLE)}" }
        }
        val bad = opened.filter { (url, intent) ->
            if (url.startsWith("http")) intent == null || !intent.endsWith("browsable=true") else intent != null
        }
        assertTrue(bad.isEmpty(), "opened: $bad")
    }

    private fun viewFilter(scheme: String) = IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        addDataScheme(scheme)
    }

    /** The link card URL rule: the app's own scheme opens in the app, through an intent limited to its package. */
    @Test fun aLinkCardOpensTheAppsOwnSchemeAndNoOtherApps() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val packages = shadowOf(app.packageManager)
        val own = ComponentName(app.packageName, "dev.pingfront.ui.test.DeepLinkActivity")
        packages.addActivityIfNotPresent(own)
        packages.addIntentFilterForActivity(own, viewFilter("myapp"))
        val other = ComponentName("com.example.other", "com.example.other.Main")
        packages.addActivityIfNotPresent(other)
        packages.addIntentFilterForActivity(other, viewFilter("otherapp"))
        // startActivity throws ActivityNotFoundException when nothing in reach handles the intent, as on a device.
        shadowOf(app).checkActivities(true)
        val presenter = ActivityChatPresenter(app)

        assertTrue(presenter.openLink(" myapp://settings/sound"), "the app's own scheme opens")
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("myapp://settings/sound", intent.dataString)
        assertEquals(app.packageName, intent.`package`, "limited to the app's own package")
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))

        assertFalse(presenter.openLink("otherapp://x"), "another app's scheme never opens (cannot_open)")
        assertFalse(presenter.openLink("unhandled://x"), "nothing handles it: cannot_open")
        assertNull(shadowOf(app).nextStartedActivity)
    }

    /** A link tapped in a message is checked by the rule before the app's onLink (and so a bridge) ever sees it. */
    @Test fun aChatLinkNeverHandsARefusedUrlToOnLink() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val instance = dev.pingfront.PingFrontInstance.detached()
        val seen = mutableListOf<String>()
        instance.onLink = {
            seen += it
            true
        }
        val handler = ChatLinkHandler(instance, app)
        for (url in refusedUrls()) handler.openUri(url)
        assertEquals(emptyList(), seen)
        assertNull(shadowOf(app).nextStartedActivity)
        handler.openUri("myapp://settings")
        assertEquals(listOf("myapp://settings"), seen)
    }

    private fun refusedUrls(): List<String> {
        val refused = JSONObject(dev.pingfront.testing.ProtocolFixtures.file("link-urls.json").readText()).getJSONArray("refused")
        return (0 until refused.length()).map { refused.getJSONObject(it).getString("url") }
    }

    /** The link card URL rule's refused URLs (`link-urls.json` in the protocol fixtures) never start an activity. */
    @Test fun aLinkCardNeverOpensARefusedUrl() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val presenter = ActivityChatPresenter(app)
        val refused = JSONObject(dev.pingfront.testing.ProtocolFixtures.file("link-urls.json").readText()).getJSONArray("refused")
        for (i in 0 until refused.length()) {
            val url = refused.getJSONObject(i).getString("url")
            assertFalse(presenter.openLink(url), url)
            assertNull(shadowOf(app).nextStartedActivity, url)
        }
    }
}
