package dev.pingfront

import android.os.Bundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * N12: a Flutter app's manifest carries `flutterEmbedding` as an Integer. Reading every key with `getString` logged a
 * ClassCastException at every configure. Only `dev.pingfront.` keys with the String marker are modules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModuleDiscoveryTest {
    @Test fun foreignAndNonStringMetaDataIsIgnoredQuietly() {
        ShadowLog.clear()
        val meta = Bundle().apply {
            putInt("flutterEmbedding", 2)
            putInt("dev.pingfront.not.a.module", 1)
            putString("com.example.SomeModule", PingFrontInstance.MODULE_MARKER)
            putString("dev.pingfront.ui.ChatUi", PingFrontInstance.MODULE_MARKER)
            putString("pingfront.app_key", "pk_test_x")
            putBoolean("dev.pingfront.flag", true)
        }
        assertEquals(listOf("dev.pingfront.ui.ChatUi"), PingFrontInstance().moduleNames(meta))
        val casts = ShadowLog.getLogs().filter { it.throwable is ClassCastException || "ClassCastException" in it.msg.orEmpty() }
        assertTrue(casts.isEmpty(), "logged: ${casts.map { it.msg }}")
    }
}
