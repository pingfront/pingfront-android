package dev.pingfront.internal.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.pingfront.PingFront

/**
 * Where a pingfront notification tap lands. It is not exported, so only this app's own notifications
 * (through [dev.pingfront.PingFrontInstance.pushTapIntent]) can start it, and its `pf_*` payload can be
 * trusted (review CS-8). It brings the app forward (its existing task, or a cold launch), opens the
 * chat on top and finishes. This also opens the chat when the app is alive in the background, which a
 * plain launcher intent did not (review CQ-M3).
 */
internal class PushTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val extras = intent?.extras
            val data = extras?.keySet().orEmpty()
                .filter { it.startsWith(PUSH_PREFIX) }
                .associateWith { extras?.getString(it).orEmpty() }
            packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launch)
            }
            PingFront.instance.pushTapped(data, this)
        }
        finish()
    }

    private companion object {
        const val PUSH_PREFIX = "pf_"
    }
}
