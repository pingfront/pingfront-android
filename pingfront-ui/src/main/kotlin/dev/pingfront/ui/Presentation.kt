package dev.pingfront.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.pingfront.ChatPresenter
import dev.pingfront.ModuleContext
import dev.pingfront.PingFront
import dev.pingfront.PingFrontException
import dev.pingfront.PingFrontModule
import dev.pingfront.WebLinks
import dev.pingfront.look.ChatTopic
import dev.pingfront.model.LocalizedText
import dev.pingfront.model.TopicSymbol
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * The chat UI module. Registered automatically through the merged manifest when `pingfront-ui`
 * (or the `pingfront` umbrella) is on the classpath; no `use(...)` needed.
 */
public object ChatUi : PingFrontModule("chat_ui") {
    override fun install(context: ModuleContext) {
        context.setChatPresenter(ActivityChatPresenter(context.applicationContext))
        if (dev.pingfront.internal.debug.DebugGateAccess.isDebug()) installFixtures(context.applicationContext)
        PingFront.instance.addWipeListener { wipe(context.applicationContext) }
    }

    /** Logout and erase: everything the chat UI keeps for the person, in memory and on disk. */
    internal fun wipe(context: Context) {
        ChatImages.clear()
        LinkPreviews.clear()
        // The shared photo and a camera capture are the only chat images that touch the disk; they go with the
        // person (reviews CQ-M13 and AN-6).
        PhotoShare.clearNow(context)
        CameraFiles.clearNow(context)
    }
}

/** Debug builds: drawn fixture images for the preview launch extra (the class exists in debug sources only). */
private fun installFixtures(context: Context) {
    try {
        val fixtures = Class.forName("dev.pingfront.ui.FixtureImages")
        fixtures.getMethod("install", Context::class.java).invoke(fixtures.getField("INSTANCE").get(null), context)
    } catch (_: ReflectiveOperationException) {
        // A release build of the SDK: no fixtures.
    }
}

/** Presents the chat as a bottom sheet over the current activity, from any context (View-system apps too). */
internal class ActivityChatPresenter(private val context: Context) : ChatPresenter {
    override fun presentChat(topic: ChatTopic?) {
        // One chat at a time: a second open (a push tap, a moment) never stacks another sheet (review CQ-L16).
        if (PingFrontChatActivity.isShowing()) return
        start(PingFrontChatActivity.intent(context, topic, report = false, prefill = null))
    }

    override fun dismissChat() {
        // closeChat() may be called from any thread (a bridge's, a coroutine's): the activity is dismissed on its own.
        PingFrontChatActivity.current?.get()?.let { activity -> activity.runOnUiThread { activity.dismiss() } }
    }

    /**
     * Opens a link from a message or a review card: web links only, as a browsable intent, so a link can never reach
     * `content:`, `file:`, `javascript:` or an exported activity that did not opt in to links (review CS-11, CQ-M11).
     */
    override fun openUrl(url: String) {
        if (WebLinks.isWeb(url)) openLink(url)
    }

    /**
     * Opens a link card's URL as a browsable `ACTION_VIEW` intent: a web link in the browser, the app's own scheme
     * in the app's own activity (the intent is limited to this package, so another app's scheme never opens). False
     * when no activity handles it. The link card URL rule refuses `javascript:`, `content:`, `intent:` and the rest
     * (the protocol, "Link card URLs").
     *
     * https://developer.android.com/training/basics/intents/sending
     */
    override fun openLink(url: String): Boolean {
        val cleaned = WebLinks.cleanCardUrl(url)
        if (!WebLinks.isCardLink(cleaned)) return false
        val scheme = WebLinks.cardUrlScheme(cleaned)
        val web = scheme == "https" || scheme == "http"
        if (web && !WebLinks.isWeb(cleaned)) return false
        val intent = Intent(Intent.ACTION_VIEW, cleaned.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!web) intent.setPackage(context.packageName)
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            // No app handles the link; nothing to open.
            false
        } catch (_: SecurityException) {
            // The handler refused; nothing to open.
            false
        }
    }

    override fun presentSimpleReport(prefill: String?) = start(PingFrontChatActivity.intent(context, null, report = true, prefill = prefill))

    private fun start(intent: Intent) {
        val activity = PingFront.instance.currentActivityForUi()
        if (activity != null) activity.startActivity(intent) else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Hosts the SDK-presented chat: a translucent, edge-to-edge activity with a full-height Material bottom
 * sheet (drag or predictive back to dismiss). `PingFront.openChat()` starts it.
 */
public class PingFrontChatActivity : ComponentActivity() {
    private var dismissRequest by mutableStateOf(false)

    /** The plain report form, not the chat: it is never "the presented chat" closeChat() closes or waits for. */
    private val reportMode: Boolean get() = intent.getBooleanExtra(EXTRA_REPORT, false)

    /** Counted in [PingFront.instance]'s presented chats (between onStart and onStop). */
    private var counted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!reportMode) current = WeakReference(this)
        val topic = intent.getStringExtra(EXTRA_TOPIC)?.let {
            ChatTopic(it, null as LocalizedText?, TopicSymbol.Other, intent.getStringExtra(EXTRA_PREFILL)?.let(LocalizedText::of))
        }
        val report = reportMode
        setContent {
            if (report) {
                PingFrontLanguage { SimpleReport(intent.getStringExtra(EXTRA_PREFILL)) { finish() } }
            } else {
                // Side effects (finishing) run in effects, never during composition (review CQ-L16).
                LaunchedEffect(dismissRequest) { if (dismissRequest) finish() }
                PingFrontChatSheet(visible = !dismissRequest, onDismiss = { finish() }, topic = topic)
            }
        }
    }

    internal fun dismiss() {
        // Stopped (behind Home or another app) the sheet isn't drawn, so its hide animation (the effect above) would
        // wait for the person to come back: the activity finishes at once instead. Behind the photo picker it is still
        // started and animates as usual.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) dismissRequest = true else finish()
    }

    // On screen while started: `PingFront.closeChatAndWait()` waits for every presented chat to stop.
    override fun onStart() {
        super.onStart()
        if (!reportMode && !counted) {
            counted = true
            PingFront.instance.presentedChatChanged(true)
        }
    }

    override fun onStop() {
        if (counted) {
            counted = false
            PingFront.instance.presentedChatChanged(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        // Deprecated from API 34 for overrideActivityTransition, which API 26 to 33 don't have; this call
        // still works on every version from minSdk 26 up, so one call covers them all.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    internal companion object {
        private const val EXTRA_TOPIC = "dev.pingfront.topic"
        private const val EXTRA_PREFILL = "dev.pingfront.prefill"
        private const val EXTRA_REPORT = "dev.pingfront.report"

        @Volatile var current: WeakReference<PingFrontChatActivity>? = null

        fun isShowing(): Boolean = current?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

        fun intent(context: Context, topic: ChatTopic?, report: Boolean, prefill: String?): Intent = Intent(context, PingFrontChatActivity::class.java).apply {
            topic?.let {
                putExtra(EXTRA_TOPIC, it.id)
                it.prefill?.resolve(java.util.Locale.getDefault().toLanguageTag())?.let { text -> putExtra(EXTRA_PREFILL, text) }
            }
            if (report) putExtra(EXTRA_REPORT, true)
            if (report && prefill != null) putExtra(EXTRA_PREFILL, prefill)
        }
    }
}

/** The plain text bug report used when the Reports module is not installed. */
@androidx.compose.runtime.Composable
internal fun SimpleReport(prefill: String?, send: suspend (String) -> Unit = { PingFront.reportBug(it) }, done: () -> Unit) {
    var text by remember { mutableStateOf(prefill.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = done,
        title = { Text(stringResource(R.string.pf_what_went_wrong)) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = {
                    text = limitCodePoints(it)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                if (failed) ReportNotDelivered()
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    failed = false
                    scope.launch {
                        // A report that did not go out keeps the form and its text, and says so (review AN-7).
                        if (sendReport { send(text) }) done() else failed = true
                        sending = false
                    }
                },
            ) { Text(stringResource(R.string.pf_send_report)) }
        },
        dismissButton = { TextButton(onClick = done) { Text(stringResource(R.string.pf_cancel)) } },
    )
}

/** Sends a bug report: true when it went out (the form closes), false when it did not (the form stays, review AN-7). */
@dev.pingfront.InternalPingFrontApi
public suspend fun sendReport(send: suspend () -> Unit): Boolean = try {
    send()
    true
} catch (_: PingFrontException) {
    false
}

/** "Not delivered" under a report that could not be sent; Send tries again. Announced by TalkBack. */
@dev.pingfront.InternalPingFrontApi
@androidx.compose.runtime.Composable
public fun ReportNotDelivered(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.pf_not_delivered),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
        modifier = modifier.padding(top = 8.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    )
}
