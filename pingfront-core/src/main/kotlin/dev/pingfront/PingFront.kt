package dev.pingfront

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.pingfront.conversation.Conversation
import dev.pingfront.look.ChatTopic
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.Entitlement
import dev.pingfront.model.OfferSlot
import dev.pingfront.model.PersonSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** An HS256 identity token signed by your backend with the app's `ids_` secret. */
@JvmInline
public value class IdentityToken(
    /** The compact JWT. */
    public val jwt: String,
) {
    override fun toString(): String = "IdentityToken(…)"
}

/**
 * pingfront: an in-app conversation with the people who make the app, and messages they send first ("pings").
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…")
 * PingFront.identify(userId = user.id, name = user.name, email = user.email)
 * PingFront.openChat()
 * ```
 *
 * Nothing touches the network at configure or app start. A session is created lazily on the first
 * chat open, listened moment, push open, bug report or [prepare]. Every call is safe from any thread.
 */
public object PingFront {
    /** The shared state. Internal API for the SDK's own modules. */
    @InternalPingFrontApi
    public val instance: PingFrontInstance = PingFrontInstance()

    /**
     * Configures the SDK with a public app key (or `null` to read `<meta-data android:name="pingfront.app_key">`).
     * Validates the key offline; no network. With the manifest key and `androidx.startup`, no call is needed at all.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, appKey: String? = null, block: PingFrontConfig.() -> Unit = {}) {
        instance.start(context, appKey, PingFrontConfig().apply(block))
    }

    /** Unread team messages. Use it for a badge. */
    public val unreadCount: StateFlow<Int> get() = instance.unreadCount

    /** The resolved chat look (dashboard > code > host app > defaults). */
    public val look: StateFlow<ResolvedLook> get() = instance.look

    /** The person, once a session exists. */
    public val person: StateFlow<PersonSummary?> get() = instance.person

    /** The problem found at configure time, or `null`. Debug builds also show it in the chat. */
    public val configurationProblem: StateFlow<ConfigurationProblem?> get() = instance.configurationProblem

    /** True while the chat is on screen (sheet, activity or embedded). */
    public val isChatVisible: StateFlow<Boolean> get() = instance.isChatVisible

    /**
     * True when the dashboard turned on the launcher badge (`features.appBadge`) in the published config.
     *
     * Off by default, and `false` until a config has loaded: the badge belongs to your app, and pingfront never
     * touches it unless you opt in. The `Push` module reads it to pick the notification channel with badges; a React
     * Native or Flutter app that shows its own notifications reads it to decide whether to set the badge count.
     */
    public val usesAppBadge: Boolean get() = instance.appBadgeEnabled

    /** True when the dashboard marked this device as a test device (it previews draft looks). */
    public val isTestDevice: StateFlow<Boolean> get() = instance.isTestDevice

    /** The thread state and actions, for the built-in chat or your own UI. */
    public val conversation: Conversation get() = instance.conversation

    /** Chat opened and closed, messages, unread changes, offers and problems. */
    public val events: Flow<PingFrontEvent> get() = instance.events

    /**
     * Called on the main thread when the person taps an offer card. Show your paywall for `request.offerId`.
     * When unset, a registered offer module (for example `Billing`) handles it.
     */
    public var onOffer: ((OfferRequest) -> Unit)?
        get() = instance.onOffer
        set(value) {
            instance.onOffer = value
        }

    /**
     * Called on the main thread when the person taps a link: a link card, a link in a message or its preview.
     * Return true when your app opened the URL (for example with your own router); return false, or leave it unset,
     * and the browser opens it (an app's own scheme opens in this app). It may suspend (a bridge waiting for
     * JavaScript or Dart, a router that loads first): the SDK waits for the answer at most 10 seconds, as for
     * [onScreen]. No answer by then cancels the handler and counts as false, as a throw does, and the link opens the
     * default way; a late answer is ignored. A link card reports what really happened: opened, or failed with
     * `cannot_open` when the default way opened nothing either. A newer link tapped in a message replaces one still
     * waiting, and [logout] cancels a wait.
     *
     * ```kotlin
     * PingFront.onLink = { url ->
     *     url.startsWith("myapp://") && router.open(url)
     * }
     * ```
     */
    public var onLink: (suspend (String) -> Boolean)?
        get() = instance.onLink
        set(value) {
            instance.onLink = value
        }

    /**
     * Opens a link the way the chat does when [onLink] declines it (the SDK does this itself after [onLink]): the link
     * card URL rule first (the protocol, "Link card URLs"), then the chat presenter (the browser for http and https,
     * an intent limited to this app's package for its own scheme). False when the rule refuses it or nothing opened
     * it. Call it on the main thread.
     */
    @InternalPingFrontApi
    public fun openLinkByDefault(url: String): Boolean = instance.openLinkByDefault(url)

    /**
     * Opens a screen of your app from a link card (a saved link the team sent, the protocol, "Saved links"). Called on
     * the main thread with the screen ([dev.pingfront.model.LinkScreen.id]) and its parameters, only for screens this build registered with
     * [registerScreens]. Navigate there and return true; return false when the app can't open it now (the team sees
     * the link failed). Unset, such a card counts as not handled. It may suspend (a bridge waiting for JavaScript or
     * Dart, a router that loads first): the SDK waits for the answer before it reports the tap, at most 10 seconds. No
     * answer by then cancels the handler and counts as false, as on iOS. A link card for a screen this build didn't
     * register opens its `url` instead (through [onLink], then the browser).
     *
     * ```kotlin
     * PingFront.onScreen = { screen ->
     *     navController.navigate(screen.id)   // screen.params: Map<String, String>
     *     true
     * }
     * ```
     */
    public var onScreen: (suspend (dev.pingfront.model.LinkScreen) -> Boolean)?
        get() = instance.onScreen
        set(value) {
            instance.onScreen = value
        }

    /**
     * Registers the screens of your app that the team's saved links can open: each [AppScreen.id] once, at most 200.
     * Call it at launch, before or after [configure]; a later call replaces the list. Nothing is sent for it on its
     * own: the list rides on the session requests the SDK makes anyway. An id that doesn't match
     * `^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$` throws in a debuggable build and is dropped (and logged) in a release build.
     */
    public fun registerScreens(screens: List<AppScreen>): Unit = instance.registerScreens(screens)

    /**
     * The chat's language when your app has its own language picker: a BCP 47 tag such as `"de"` or `"pt-BR"`.
     * `null` (the default) follows the app's and the system's language. The chat UI, the SDK's strings, the
     * team's localized texts and the language the server writes in all follow it. An invalid tag is ignored and logged.
     */
    public var language: String?
        get() = instance.language.value
        set(value) {
            instance.setLanguage(value)
        }

    /**
     * What teammates ask your app to do for the person ("Fix it for them"): extend a trial, grant access, or one of
     * your custom actions. Each arrives once per id, also after the app was closed. Do the work, then call
     * [TeamAction.complete] or [TeamAction.fail].
     *
     * ```kotlin
     * lifecycleScope.launch {
     *     PingFront.teamActions.collect { action ->
     *         when (action.actionId) {
     *             "extend_trial" -> { billing.extendTrial(action.days ?: 7); action.complete() }
     *             else -> action.fail("This app version can't do ${action.name} yet.")
     *         }
     *     }
     * }
     * ```
     */
    public val teamActions: Flow<TeamAction> get() = instance.teamActions

    /** True once [configure] succeeded (false again after a configuration problem disables the SDK). */
    public val isConfigured: StateFlow<Boolean> get() = instance.isConfigured

    /**
     * Checks the setup: sends "Setup check from <app>" to your inbox and waits for the server (at most 20 seconds).
     * Also logs the one line (`PingFront self-check: PASS` or `FAIL <code>: <advice>`). For setup and CI checks.
     */
    public suspend fun selfCheck(): SetupCheckResult = instance.selfCheck()

    /**
     * The per-person token billing modules pass as Play's `obfuscatedAccountId` (and adapters link to vendors), so
     * store webhooks find the person. Null before the first session. Read-only.
     */
    public suspend fun storeAccountToken(): String? = instance.storeAccountToken()

    /** Names the person (unverified: one thread per install until verified). Coalesced with other changes for 250 ms. */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, name: String? = null, email: String? = null): Unit = instance.identify(userId, name, email)

    /**
     * Verifies the person with an HS256 token signed by your backend (claims `sub`, `iat`, `exp`, optional
     * `name`/`email`). Verified people get one thread across devices and reinstalls.
     */
    @JvmStatic
    @JvmName("identifyWithToken")
    public fun identify(token: IdentityToken): Unit = instance.identify(token.jwt)

    /** Adds or updates traits (merged; at most 30, keys ≤ 64 and values ≤ 512 characters). */
    @JvmStatic
    public fun setTraits(traits: Map<String, String>): Unit = instance.setTraits(traits)

    /** Reports the person's subscription state (automatic with the `Billing` module). */
    @JvmStatic
    public fun setEntitlement(entitlement: Entitlement): Unit = instance.setEntitlement(entitlement)

    /** Reports the offers the app can show, so the team can send offer cards for them. */
    @JvmStatic
    public fun setOffers(offers: List<OfferSlot>): Unit = instance.setOffers(offers)

    /** Reports an app moment (`paywall_closed`, `trial_cancelled`, …). Only moments a play listens to are sent. */
    public suspend fun moment(name: String, props: Map<String, Any> = emptyMap()): MomentResult = instance.moment(name, props)

    /** Creates the session now instead of lazily (for example right after login). Throws [PingFrontException]. */
    public suspend fun prepare(): Unit = instance.prepare()

    /** Presents the chat (a bottom sheet over the current activity). With a topic, its prefill goes into the composer. */
    @JvmStatic
    @JvmOverloads
    public fun openChat(topic: ChatTopic? = null): Unit = instance.openChat(topic)

    /**
     * Opens the chat from a link your app received: an email from your team, a "Message us" QR code, a website
     * button, a shortcut, or a push from your own provider. A chat link is any URL whose query has
     * `pingfront=chat` or `pingfront=report`, with an optional `topic=<topic id>` that applies only when it matches
     * one of the chat's topics. It works with your own App Links domain or custom scheme; the SDK claims none.
     *
     * Returns `true` when it handled the link: it opened the chat or the bug report (exactly like [openChat] and
     * [openReport]), or, before [configure], kept it and opens it once configure runs. Returns `false` for any
     * other URL, so your own router handles it. It sends nothing itself. See the protocol, "Chat links".
     *
     * ```kotlin
     * if (PingFront.handle(uri)) return
     * ```
     */
    @JvmStatic
    public fun handle(uri: Uri): Boolean = instance.handleLink(uri.toString())

    /**
     * [handle] for an activity's intent: the data of an `ACTION_VIEW` intent (App Links, your custom scheme, a
     * shortcut). Any other action, or no data, returns `false`. Extras are never read.
     *
     * ```kotlin
     * override fun onCreate(savedInstanceState: Bundle?) {
     *     super.onCreate(savedInstanceState)
     *     if (savedInstanceState == null) PingFront.handle(intent)
     * }
     *
     * override fun onNewIntent(intent: Intent) {
     *     super.onNewIntent(intent)
     *     PingFront.handle(intent)
     * }
     * ```
     */
    @JvmStatic
    public fun handle(intent: Intent): Boolean = instance.handleLink(intent)

    /** Closes the SDK-presented chat. Returns at once; [closeChatAndWait] returns once it is gone. */
    @JvmStatic
    public fun closeChat(): Unit = instance.closeChat()

    /**
     * Closes the chat the SDK presented (the sheet `openChat`, a chat link or a notification opened) and returns once
     * it is off screen, at once when none is, as iOS's `await closeChat()` does. An embedded `PingFrontChat` and your
     * own `PingFrontChatSheet` belong to your layout: they are neither closed nor waited for (unlike [isChatVisible],
     * which counts them). Use it before showing your own screen from an
     * offer or link handler, so that screen opens over the app and not under the closing sheet. If the sheet hasn't
     * gone after 2 seconds (a custom presenter that never reports back), it returns anyway.
     */
    public suspend fun closeChatAndWait(): Unit = instance.closeChatAndWait()

    /** Sends a bug report with an optional screenshot (PNG/JPEG bytes). Adds the log tail and device state with the `Reports` module. */
    public suspend fun reportBug(text: String, screenshot: ByteArray? = null): Unit = instance.reportBug(text, screenshot)

    /** Opens the bug report flow (screenshot and markup with `Reports`, a text form otherwise). */
    @JvmStatic
    @JvmOverloads
    public fun openReport(prefill: String? = null): Unit = instance.openReport(prefill)

    /** Forgets identity, traits and the thread on this device and tells the server (retried). The next session is a new guest. */
    @JvmStatic
    public fun logout(): Unit = instance.logout()

    /** Erases the person's data on the server (`DELETE /me`) and on this device. Safe to retry. */
    public suspend fun eraseMyData(): Unit = instance.eraseMyData()

    /**
     * Everything pingfront stores about this person (`GET /me/export`), as the server's JSON text.
     *
     * The result is a `PersonExport` (`format: pingfront.person.v1`): profile, devices, the thread with every
     * message, moments, store events and plays, with no secrets, push tokens or team notes. Save or share it as it
     * is (for example as `pingfront-export.json`); the SDK shows no UI and keeps no copy. Image parts carry
     * short-lived URLs.
     *
     * Needs a session but no identity, so it may start one. Works for a blocked person. Limited to 5 an hour per
     * install: past that it throws [PingFrontException.Server] with code `rate_limited` and scope
     * [RateScope.Exports], and further calls throw [PingFrontException.CoolingDown] until the wait is over.
     */
    public suspend fun exportMyData(): String = instance.exportMyData()

    /** Forwards the FCM registration token (the `Push` module does this for you). Pass `null` to unregister. */
    @JvmStatic
    public fun setPushToken(token: String?): Unit = instance.setPushToken(token)

    /** Handles a received notification's data (`RemoteMessage.data`): applies its unread count. Returns `true` for pingfront pushes. */
    @JvmStatic
    public fun handlePush(data: Map<String, String>): Boolean = instance.handlePush(data)

    /**
     * Handles a tapped notification's data when your own code (or a React Native or Flutter bridge) shows pingfront's
     * notifications: applies its unread count and opens the chat, unless the person it was for logged out since.
     * Returns `true` for pingfront pushes.
     */
    @JvmStatic
    public fun openPush(data: Map<String, String>): Boolean = instance.openPush(data)

    /** True for pingfront push payloads (`pf_v = "1"`). */
    @JvmStatic
    public fun isPingFrontPush(data: Map<String, String>): Boolean = PushPayload.parse(data) != null
}
