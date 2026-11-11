package dev.pingfront.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pingfront.ConfigurationProblem
import dev.pingfront.KeyProblem
import dev.pingfront.PingFrontInstance
import dev.pingfront.RateScope
import dev.pingfront.conversation.PendingMessage
import dev.pingfront.conversation.TypingIndicator
import dev.pingfront.internal.debug.Fixtures
import dev.pingfront.look.ChatAppearance
import dev.pingfront.look.DynamicColors
import dev.pingfront.look.HostAppInfo
import dev.pingfront.model.ChatLook
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.SendPolicy
import java.io.File
import java.util.TimeZone
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real chat into `Snapshots/` with Roborazzi (Robolectric native graphics, no emulator) and writes
 * `manifest.json` in the same schema as iOS. Record with `./gradlew :pingfront-ui:recordRoborazziDebug`; `build`
 * verifies against the reference images and fails on a change. A checkout without reference images (they are not
 * published with the source) skips the matrix instead of failing on missing references.
 *
 * Each shot is captured in the settled state: the chat is composed in a Compose UI test and captured only after
 * `waitForIdle()`, so work that starts on first draw (the store's pending purchases, offer prices) has run on the
 * test dispatcher and recomposed. Nothing depends on how fast the machine is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SnapshotMatrixTest {
    private class Device(val slug: String, val name: String, val width: Int, val height: Int, val dpi: Int, val layout: Layout)

    private enum class Layout { Sheet, TwoPane }

    private data class Variant(
        val dark: Boolean = false,
        val fontScale: Float = 1f,
        val locale: String = "en",
        val look: String = "default",
        val dynamic: Boolean = false,
    )

    private val small = Device("small", "Small phone (360×640 dp)", 360, 640, 320, Layout.Sheet)
    private val pixel8 = Device("pixel8", "Pixel 8 (412×915 dp)", 412, 915, 320, Layout.Sheet)
    private val foldable = Device("foldable", "Foldable inner display (673×841 dp)", 673, 841, 320, Layout.Sheet)
    private val landscape = Device("landscape", "Pixel 8 in landscape (915×412 dp)", 915, 412, 320, Layout.Sheet)
    private val tablet = Device("tablet", "Tablet (1280×800 dp, two-pane)", 1280, 800, 240, Layout.TwoPane)

    private val entries = mutableListOf<Map<String, Any>>()
    private val directory = File(System.getProperty("pingfront.snapshots.dir") ?: "build/snapshots")
    private val recording = System.getProperty("roborazzi.test.record") == "true"

    @Test fun matrix() {
        Assume.assumeTrue("No reference images in $directory: record them first", recording || directory.isDirectory)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        directory.mkdirs()
        val base = pixel8
        shot(
            base,
            state = "welcome",
            title = "Welcome with topics",
            notes = "A large centered face, the name, role and reply expectation, and topic chips. The header shows only the close button.",
        )
        shot(
            base,
            state = "conversation",
            title = "Conversation",
            notes = "The end of the thread: choice chips, a 48 dp star row, and a review card with one filled button. Read under the person's last message.",
        )
        shot(
            base,
            state = "conversation-top",
            title = "Conversation, start of the thread",
            notes = "Centered time, the pink ping line (tap for the reason), grouped bubbles with tight corners, " +
                "the AI chip and the hand-off, and a receipt with a green check.",
            preview = ChatPreview(scrolledToTop = true),
        )
        shot(
            base,
            Variant(dark = true),
            "conversation-top",
            "Conversation, start of the thread, dark",
            "The ping line, the AI chip and grouped bubbles in dark mode.",
            preview = ChatPreview(scrolledToTop = true),
        )
        shot(
            base,
            state = "scenario-paywall-ping",
            title = "Scenario: Closed the paywall: a ping and an offer",
            notes = "A play pinged after the person closed Plans twice: the ping line, Deniz's question and the trial offer card.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "paywall-ping") }
        shot(
            base,
            state = "scenario-promo",
            title = "Scenario: Win-back with a Play promotional offer",
            notes = "The card names a Play offer: the discounted price, the regular price struck through.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "promo") }
        shot(
            base,
            state = "offer-replaced",
            title = "A newer offer replaces an older one",
            notes = "Every offer card but the newest collapses to \"Replaced by a newer offer\", " +
                "also when the newer card is a different offer (as on iOS and the web).",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "offer-replaced") }
        shot(
            base,
            state = "scenario-trial-ending",
            title = "Scenario: Trial ending",
            notes = "The ping that also arrives as a push: one more week, as a choices card.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "trial-ending") }
        shot(
            base,
            state = "scenario-billing-problem",
            title = "Scenario: Billing problem",
            notes = "A payment failed: a store action card (billing_issue) opens Play's payment fix (outbound mark).",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "billing-problem") }
        shot(
            base,
            state = "scenario-store-actions",
            title = "Scenario: Store actions",
            notes = "Restore (with its local receipt line), refund, manage and redeem: store action cards, each with its icon.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "store-actions") }
        shot(
            base,
            state = "scenario-plans",
            title = "Scenario: Plans",
            notes = "Play Billing surfaces: the switch and trial offers collapse under the newest offer card, " +
                "a purchase waiting for payment, and the plan's status line.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "plans") }
        shot(
            base,
            state = "scenario-bug-fixed",
            title = "Scenario: Bug report, then fixed in 2.3",
            notes = "The report, the filed receipt, and Deniz's reply.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "bug-fixed") }
        shot(
            base,
            state = "scenario-restore",
            title = "Scenario: Restore",
            notes = "Purchases restored, with a green-check receipt.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "restore") }
        shot(
            base,
            state = "scenario-review",
            title = "Scenario: Review card",
            notes = "In-app review first (Play In-App Review); the store listing opens only on a second tap.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "review") }
        shot(
            base,
            state = "scenario-cancel",
            title = "Scenario: Cancelled: why?",
            notes = "After a cancellation, a choices card asks why.",
            kind = Fixtures.Kind.Welcome,
        ) { dev.pingfront.internal.debug.Scenarios.load(it, "cancel") }
        shot(
            base,
            state = "topic-tapped",
            title = "Welcome after tapping a topic",
            notes = "Audit X-01: the topic put a sentence start into the composer; nothing was sent. Topic labels are verbs (X-08).",
            kind = Fixtures.Kind.Welcome,
        ) { it.conversation.insert("I have a question about billing: ") }
        shot(
            base,
            state = "photo-grid",
            title = "Several photos in one message",
            notes = "Audit X-06: three photos as a two-column grid inside one bubble shape.",
        ) { Fixtures.addPhotoGrid(it) }
        shot(
            base,
            state = "photo-removed",
            title = "Photos removed by retention",
            notes = "A photo the app's retention deleted shows a quiet muted \"Photo removed\" line; " +
                "in a grid the cell keeps its place with the symbol alone. Never fetched.",
        ) { Fixtures.addRemovedPhotos(it) }
        shot(
            base,
            state = "uploading-photos",
            title = "Photos uploading",
            short = true,
            notes = "Audit A-06: 'Uploading 2 photos' with progress, not 'Waiting for connection'.",
        ) { Fixtures.setUploading(it) }
        shot(
            base,
            state = "first-message",
            title = "After the first message",
            notes = "Audit X-05: what happens next, with the reply expectation; the notifications prompt appears when push is on and off.",
        ) { Fixtures.firstMessageOnly(it) }
        shot(
            base,
            state = "staged-photos",
            title = "Composer with two staged photos",
            short = true,
            notes = "Two photos staged above the pill field, each removable; the send button has appeared.",
            preview = ChatPreview(stagedPhotos = listOf(photoBytes(), photoBytes()), draft = "Both screens show the glitch"),
        ) {
            it.conversation.updateDraft("Both screens show the glitch")
        }
        shot(
            base,
            state = "welcome-branding",
            title = "Welcome on the Free plan",
            notes = "features.branding: our one-colour lockup, 14 dp and faded, on its own bar above " +
                "the composer. It takes the chat's muted ink, so it follows the app's palette, and never " +
                "appears inside a conversation.",
            kind = Fixtures.Kind.Welcome,
            configure = { Fixtures.setBranding(it) },
        )
        shot(
            base,
            Variant(dark = true),
            state = "welcome-branding",
            title = "Welcome on the Free plan, dark",
            notes = "The same branding line in dark mode: the lockup follows the look's muted ink, so it " +
                "stays quiet in either appearance.",
            kind = Fixtures.Kind.Welcome,
            configure = { Fixtures.setBranding(it) },
        )
        shot(
            base,
            state = "conversation-branding",
            title = "A conversation on the Free plan",
            notes = "The branding line never appears inside a conversation.",
            short = true,
            configure = { Fixtures.setBranding(it) },
        )
        shot(
            base,
            state = "composer-five-lines",
            title = "Composer grown to five lines",
            short = true,
            notes = "Five lines show in full; the field keeps growing with the text (composer-grown).",
            preview = ChatPreview(draft = FIVE_LINES),
        ) {
            it.conversation.updateDraft(FIVE_LINES)
        }
        shot(
            base,
            state = "composer-grown",
            title = "Composer at its tallest",
            short = true,
            notes = "As in Google Messages, the field grows until it takes a third of the window (about 12 lines on a Pixel 8), " +
                "then scrolls inside the pill; the + and send buttons stay on the last line.",
            preview = ChatPreview(draft = GROWN),
        ) {
            it.conversation.updateDraft(GROWN)
        }
        shot(
            base,
            state = "composer-over-limit",
            title = "Composer over the length limit",
            short = true,
            notes = "Past 4,000 code points the counter turns red and the send button goes away; TalkBack hears how far over it is.",
            preview = ChatPreview(draft = OVER_LIMIT),
        ) {
            it.conversation.updateDraft(OVER_LIMIT)
        }
        shot(
            base,
            state = "sending-failed",
            title = "Sending and failed",
            short = true,
            notes = "One message sending; one not delivered, with the error mark beside it; the mark or the red line opens Try again and Delete.",
        ) { instance ->
            Fixtures.setPending(
                instance,
                Fixtures.PendingRow("c1", "Also, the alarm skipped a minute at midnight.", 19.0, PendingMessage.State.Sending),
                Fixtures.PendingRow("c2", "Is there a family plan?", 19.5, PendingMessage.State.Failed, failure = "invalid_request"),
            )
        }
        shot(
            base,
            state = "offline-queued",
            title = "Offline with a queued message",
            short = true,
            notes = "The offline banner; the queued message waits and sends when the connection returns.",
        ) { instance ->
            Fixtures.setPending(instance, Fixtures.PendingRow("c3", "Sending this from the train.", 19.0, attempts = 1))
            Fixtures.setOffline(instance)
        }
        shot(
            base,
            state = "rate-limited",
            title = "Rate-limited countdown",
            short = true,
            notes = "Only uploads are paused (429 scope uploads); the countdown is exact under five minutes and text still sends.",
        ) { instance ->
            Fixtures.setCooldown(instance, RateScope.Uploads, 41_600)
        }
        shot(
            base,
            state = "blocked",
            title = "Blocked",
            short = true,
            notes = "The composer is replaced by a plain line that says what is going on; a refused message is a plain failure " +
                "(\"Not delivered\"), whose options offer Delete alone.",
        ) { instance ->
            Fixtures.setPending(instance, Fixtures.PendingRow("c5", "Hello?", 19.0, PendingMessage.State.Failed, failure = "blocked", attempts = 1))
            Fixtures.setPolicy(instance, dev.pingfront.internal.debug.Fixtures.closed(SendPolicy.Reason.Blocked))
        }
        shot(
            base,
            state = "app-paused",
            title = "App paused",
            short = true,
            notes = "GET /thread still answers with policy app_paused, so the paused notice shows. A held message and the one " +
                "behind it carry no error mark: one muted line under the last says they send when the chat is back.",
        ) { instance ->
            Fixtures.setPending(
                instance,
                Fixtures.PendingRow("c6", "Is the yearly plan still on sale?", 19.0, PendingMessage.State.Failed, "app_paused", 1, SendPolicy.Reason.AppPaused),
                Fixtures.PendingRow("c7", "Asking for a friend.", 19.2),
            )
            Fixtures.setPolicy(instance, dev.pingfront.internal.debug.Fixtures.closed(SendPolicy.Reason.AppPaused))
        }
        shot(
            base,
            state = "bad-key",
            title = "Bad key, debug banner",
            kind = Fixtures.Kind.Welcome,
            notes = "A mistyped key fails the offline checksum; debug builds show the exact problem in red. Release builds disable the SDK quietly.",
        ) { instance ->
            instance.setPreviewProblem(
                dev.pingfront.internal.debug.Fixtures.badKey(KeyProblem.ChecksumMismatch, KeyProblem.ChecksumMismatch.message),
            )
        }
        shot(
            base, state = "draft-look", title = "Draft look on a test device", short = true, draft = true,
            app = Fixtures.Ledgerly, look = Fixtures.Ledgerly.look, lookName = "ledgerly-draft",
            notes = "A test device previews an unpublished look; the dashed Draft look marker sits in the header.",
        )
        shot(
            base,
            state = "typing",
            title = "Typing indicator",
            short = true,
            notes = "A human teammate is typing (never shown for the assistant or automated messages).",
        ) { instance ->
            Fixtures.setTyping(instance, "Deniz")
        }
        shot(
            base,
            state = "jump-to-latest",
            title = "Jump to latest with new messages",
            notes = "Scrolled up while three new messages arrived.",
            preview = ChatPreview(scrolledToTop = true, newMessageCount = 3),
        )
        shot(
            base,
            state = "load-older",
            title = "Load earlier messages",
            notes = "At the top of a long thread; earlier messages load on scroll.",
            preview = ChatPreview(scrolledToTop = true),
        ) { instance ->
            Fixtures.setLoadingOlder(instance)
        }
        shot(
            base,
            state = "unknown-content",
            title = "Content from a newer server",
            short = true,
            notes = "A part this SDK does not know shows the server's fallback text instead of crashing.",
        ) { instance ->
            Fixtures.addUnknownMessage(instance)
        }
        shot(
            base,
            state = "ai-handoff",
            title = "The AI hands off to the team",
            short = true,
            notes = "The hand-off line is a plain centered system line: no green check, because nothing was done for the person.",
        ) { instance ->
            Fixtures.addHandOff(instance)
        }
        shot(
            base,
            state = "offline-welcome",
            title = "Offline before the thread ever loaded",
            kind = Fixtures.Kind.Welcome,
            notes = "One offline banner and the welcome underneath; topics and the composer work and messages wait in the outbox. " +
                "No failure screen and no retry: the SDK reconnects by itself.",
        ) { instance ->
            Fixtures.setLoadFailed(instance, offline = true)
        }
        shot(
            base,
            state = "load-failed",
            title = "The server failed to load the thread",
            kind = Fixtures.Kind.Welcome,
            notes = "Online, but the server answered with an error: a calm centered state with one action, never with the offline banner.",
        ) { instance ->
            Fixtures.setLoadFailed(instance, offline = false)
        }
        shot(
            base,
            state = "embedded-welcome",
            title = "Embedded welcome, no close button",
            kind = Fixtures.Kind.Welcome,
            embedded = true,
            notes = "PingFrontChat inside the app's own screen: without a close button there is no header row above the welcome.",
        )
        shot(
            landscape,
            state = "welcome",
            title = "Welcome on a phone in landscape",
            notes = "At a compact window height the words and the topics sit side by side, as on iOS, so the topics show without scrolling.",
        )
        shot(
            base,
            state = "server-unreachable",
            title = "Online, but the chat can't be reached",
            short = true,
            notes = "The device has a network but requests don't get through: the banner says so with a cloud glyph, never \"You're offline\", " +
                "and a queued message is \"Not sent yet\", not \"Waiting for connection\".",
        ) { instance ->
            Fixtures.setPending(instance, Fixtures.PendingRow("c4", "Still there?", 19.0, attempts = 1))
            Fixtures.setServerUnreachable(instance)
        }
        shot(
            base,
            state = "verification-required",
            title = "Verification required",
            short = true,
            notes = "The app accepts messages only from signed-in people: the composer says what to do instead, and a held " +
                "message says it sends after the person signs in.",
        ) { instance ->
            Fixtures.setPending(
                instance,
                Fixtures.PendingRow(
                    "c8",
                    "Can I move my data to a new phone?",
                    19.0,
                    PendingMessage.State.Failed,
                    "verification_required",
                    1,
                    SendPolicy.Reason.VerificationRequired,
                ),
            )
            Fixtures.setPolicy(instance, dev.pingfront.internal.debug.Fixtures.closed(SendPolicy.Reason.VerificationRequired))
        }
        shot(
            base,
            Variant(fontScale = 2f),
            state = "unreachable-in-thread",
            title = "Out of reach at the largest font scale",
            short = true,
            notes = "At 150 % font size and up the notice is a row of the thread, before the first message waiting to send, so the " +
                "header holds no text that grows without limit.",
        ) { instance ->
            Fixtures.setPending(instance, Fixtures.PendingRow("c9", "Still there?", 19.0, attempts = 1))
            Fixtures.setServerUnreachable(instance)
        }
        shot(
            base,
            state = "messages-cooldown",
            title = "Sending paused by a rate limit",
            short = true,
            notes = "429 scope messages: an exact countdown under five minutes above the composer.",
        ) { instance ->
            Fixtures.setCooldown(instance, RateScope.Messages, 41_600)
        }

        for (state in listOf("welcome", "conversation")) {
            val name = if (state == "welcome") "Welcome" else "Conversation"
            shot(base, Variant(dark = true), state, "$name, dark", "Dark theme tokens.")
            shot(base, Variant(fontScale = 1.3f), state, "$name, font scale 1.3", "Large text; chrome caps at 1.5x, messages scale fully.")
            shot(
                base,
                Variant(fontScale = 2f),
                state,
                "$name, font scale 2.0",
                "The largest font scale: nothing clips; header and composer cap at 1.5x so the thread keeps room.",
            )
            shot(
                base,
                Variant(locale = "ar"),
                state,
                "$name, Arabic (RTL)",
                "Arabic, right to left: bubbles, corners and chips mirror. Team content is in Arabic, as the team would write it.",
            )
            shot(base, Variant(locale = "de"), state, "$name, German", "German, long strings.")
            shot(base, Variant(locale = "ja"), state, "$name, Japanese", "SDK copy translated; team content as the team would write it.")
            shot(
                base,
                Variant(locale = "fa"),
                state,
                "$name, Persian (RTL)",
                "Persian, right to left: bubbles, corners and chips mirror. Team content is in Persian, as the team would write it.",
            )
            shot(base, Variant(locale = "el"), state, "$name, Greek", "Greek, long strings in another script; formal address.")
            shot(
                base,
                Variant(look = "clockwise"),
                state,
                "$name, look Clockwise",
                "Clockwise: one face (Deniz) in the header, teal accent, the system font, round corners, light.",
                app = Fixtures.Clockwise,
                look = Fixtures.Clockwise.look,
            )
            shot(
                base,
                Variant(look = "ledgerly"),
                state,
                "$name, look Ledgerly",
                "Ledgerly: the team (Ana and Tom stacked) in the header, green accent, serif, soft corners, timestamps on every message, custom topics.",
                app = Fixtures.Ledgerly,
                look = Fixtures.Ledgerly.look,
            )
            shot(
                base,
                Variant(look = "driftwood"),
                state,
                "$name, look Driftwood",
                "Driftwood: the app icon in the header, coral accent, the rounded font, dark, compact, no avatars, an assistant called Guide.",
                app = Fixtures.Driftwood,
                look = Fixtures.Driftwood.look,
            )
            shot(
                base,
                Variant(dynamic = true),
                state,
                "$name, dynamic color",
                "appearance.useDynamicColor = true: Material You colors from the system wallpaper palette.",
            )
            for (device in listOf(small, foldable, tablet)) {
                shot(
                    device,
                    Variant(),
                    state,
                    "$name, ${device.name}",
                    if (device.layout ==
                        Layout.TwoPane
                    ) {
                        "Two-pane: the host app's list on the left, the embedded chat on the right."
                    } else {
                        "The SDK-presented bottom sheet over the host app."
                    },
                )
            }
        }
        if (recording) writeManifest()
    }

    private fun shot(
        device: Device,
        variant: Variant = Variant(),
        state: String,
        title: String,
        notes: String,
        kind: Fixtures.Kind? = null,
        short: Boolean = false,
        draft: Boolean = false,
        app: Fixtures.App = Fixtures.Clockwise,
        look: ChatLook = Fixtures.EmptyLook,
        lookName: String? = null,
        preview: ChatPreview = ChatPreview(),
        embedded: Boolean = false,
        configure: (PingFrontInstance) -> Unit = {},
    ) {
        val rtl = variant.locale in setOf("ar", "fa", "he", "ur")
        val qualifiers = buildString {
            append(if (variant.locale == "en") "en-rUS" else variant.locale)
            if (rtl) append("-ldrtl")
            append("-w${device.width}dp-h${device.height}dp")
            append(if (device.width > device.height) "-land" else "-port")
            append(if (variant.dark) "-night" else "-notnight")
            append("-${device.dpi}dpi")
        }
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(variant.fontScale)
        FixtureImages.install(androidx.test.core.app.ApplicationProvider.getApplicationContext())

        val instance = PingFrontInstance.detached()
        val fixture = kind ?: if (state.startsWith("welcome")) Fixtures.Kind.Welcome else Fixtures.Kind.Conversation
        Fixtures.load(instance, fixture, variant.locale, app, look, draft)
        instance.setPreviewHost(HostAppInfo(app.name, PingFrontColor("#00796B", "#4DB6AC"), null, "1.0 (1)"))
        if (variant.dynamic) {
            instance.setPreviewAppearance(
                ChatAppearance().apply { useDynamicColor = true },
                DynamicColors(
                    PingFrontColor("#8F4C38", "#FFB5A0"),
                    PingFrontColor("#FFF8F6", "#1A110F"),
                    PingFrontColor("#F5DED8", "#3B2D29"),
                ),
            )
        }
        if (short) Fixtures.keepFirst(instance, 5)
        configure(instance)

        val parts = mutableListOf(device.slug, if (variant.dark) "dark" else "light")
        if (variant.fontScale != 1f) parts += "font${variant.fontScale}".replace(".", "")
        if (variant.locale != "en") parts += variant.locale
        if (variant.look != "default") parts += variant.look
        if (variant.dynamic) parts += "dynamic"
        parts += state
        val file = parts.joinToString("-") + ".png"
        val still = preview.copy(now = Fixtures.Base.plusSeconds(20 * 60), still = true, debug = true)
        capture(File(directory, file)) {
            val direction = if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
            CompositionLocalProvider(
                LocalChatPreview provides still,
                androidx.compose.ui.platform.LocalLayoutDirection provides direction,
            ) {
                Frame(device, variant.dark, embedded) {
                    ChatScreen(
                        instance,
                        null,
                        onClose = if (embedded || device.layout == Layout.TwoPane) {
                            null
                        } else {
                            ({})
                        },
                        Modifier.fillMaxSize(),
                    )
                }
            }
        }
        val scheme = if (variant.dark || look.theme.scheme == dev.pingfront.model.ColorScheme.Dark) "dark" else "light"
        entries += linkedMapOf(
            "device" to device.name,
            "direction" to if (rtl) "rtl" else "ltr",
            "file" to file,
            "locale" to variant.locale,
            "look" to (lookName ?: variant.look),
            "notes" to notes,
            "points" to listOf(device.width, device.height),
            "scale" to device.dpi / BASELINE_DPI,
            "scheme" to scheme,
            "state" to state,
            "surface" to "android",
            "textSize" to if (variant.fontScale == 1f) "default" else "fontScale ${variant.fontScale}",
            "title" to title,
        )
    }

    /**
     * Composes [content] in a fresh activity (with the qualifiers just set), waits until Compose is idle (no pending
     * recomposition, effects run, layout done) and captures the root.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun capture(file: File, content: @Composable () -> Unit) = runComposeUiTest {
        setContent(content)
        waitForIdle()
        onRoot().captureRoboImage(file.path, RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.02f)))
    }

    /** The chat as the SDK presents it: a bottom sheet over the dimmed host app, or the detail pane on a tablet. */
    @Composable
    private fun Frame(device: Device, dark: Boolean, embedded: Boolean, content: @Composable () -> Unit) {
        val backdrop = if (dark) Color(0xFF0B0B0D) else Color(0xFFE8ECEB)
        // Embedded: the chat is the app's own screen below the status bar, with no sheet handle or rounded top.
        if (embedded) {
            Box(Modifier.fillMaxSize().background(sheetBackground(dark))) {
                Box(Modifier.fillMaxSize().padding(top = 28.dp)) { content() }
                StatusBar(dark)
            }
            return
        }
        when (device.layout) {
            Layout.Sheet -> Box(Modifier.fillMaxSize().background(backdrop)) {
                HostBackdrop(dark)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM)))
                Column(Modifier.fillMaxSize().padding(top = 36.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                    val sheet = sheetBackground(dark)
                    // At a compact window height the sheet has no drag handle (PingFrontChatSheet).
                    if (device.height >= COMPACT_WINDOW_HEIGHT_DP) {
                        Box(
                            Modifier.fillMaxWidth().background(sheet).padding(top = 10.dp, bottom = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66888888)))
                        }
                    }
                    content()
                }
                StatusBar(dark)
            }
            Layout.TwoPane -> Row(Modifier.fillMaxSize().background(backdrop)) {
                Column(Modifier.width(360.dp).fillMaxHeight().padding(top = 32.dp)) { HostBackdrop(dark) }
                Box(Modifier.fillMaxSize().padding(top = 24.dp)) { content() }
            }
        }
    }

    private val COMPACT_WINDOW_HEIGHT_DP = 480

    private fun sheetBackground(dark: Boolean) = if (dark) Color(0xFF131316) else Color.White

    /** A quiet stand-in for Clockwise behind the sheet. */
    @Composable
    private fun HostBackdrop(dark: Boolean) {
        val ink = if (dark) Color(0xFFE4E2E6) else Color(0xFF1B1B1F)
        val card = if (dark) Color(0xFF1F2A28) else Color.White
        Column(Modifier.padding(horizontal = 20.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Alarms", color = ink, fontSize = 30.sp)
            listOf("06:45  Weekdays", "08:30  Weekend", "13:15  Nap").forEach {
                Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp)) {
                    Text(it, color = ink, fontSize = 20.sp)
                }
            }
        }
    }

    @Composable
    private fun StatusBar(dark: Boolean) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            // System UI demo mode: 9:41, full Wi-Fi, full signal, full battery, no notification icons.
            val ink = if (dark) Color.White else Color.Black
            Text("9:41", color = ink, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.Canvas(Modifier.size(14.dp, 11.dp)) {
                val wifi = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2, size.height)
                    lineTo(0f, size.height * 0.28f)
                    quadraticTo(size.width / 2, -size.height * 0.18f, size.width, size.height * 0.28f)
                    close()
                }
                drawPath(wifi, ink)
            }
            Spacer(Modifier.width(6.dp))
            androidx.compose.foundation.Canvas(Modifier.size(12.dp, 11.dp)) {
                val signal = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(signal, ink)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(18.dp, 9.dp).clip(RoundedCornerShape(2.dp)).background(ink))
        }
    }

    private fun photoBytes(): ByteArray {
        val bitmap = FixtureImages.draw("image/att_widget", 600) ?: error("fixture")
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun writeManifest() {
        fun json(value: Any): String = when (value) {
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is List<*> -> value.joinToString(", ", "[ ", " ]") { json(it ?: "") }
            is Number -> if (value is Float && value % 1f == 0f) value.toInt().toString() else value.toString()
            else -> json(value.toString())
        }
        val body = entries.joinToString(",\n", "[\n", "\n]\n") { entry ->
            entry.entries.joinToString(",\n", "  {\n", "\n  }") { (k, v) -> "    \"$k\" : ${json(v)}" }
        }
        File(directory, "manifest.json").writeText(body)
    }

    private companion object {
        const val SCRIM = 0.32f
        const val BASELINE_DPI = 160f
    }
}

private val OVER_LIMIT: String = "Steps to reproduce: open the widget, wait for midnight. ".repeat(80).take(4012)

private val GROWN: String = "Steps to reproduce, as far as I can tell:\n1. Set an alarm for 23:59.\n2. Lock the phone.\n" +
    "3. Wait for midnight with the widget on the lock screen.\n4. Watch the minute: it jumps from 23:59 to 00:01.\n" +
    "5. Unlock: the app shows 00:00 for a second, then catches up.\nIt happens on both phones, only in 24-hour mode. " +
    "Restarting fixes it until the next night. The alarm itself still rings on time, so it is only the widget. " +
    "I can send a screen recording tonight if that helps, and the phone's bug report too. Thanks for looking into it."

private val FIVE_LINES: String = "The widget skips a minute at midnight on my lock screen. It started after the last update. " +
    "It happens on both phones, and only in 24-hour mode."
