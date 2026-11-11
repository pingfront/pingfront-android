# pingfront for Android

In-app support chat and proactive messages for Android 8.0+ (API 26). Your team talks with people inside
your app, and can reach them first ("pings") at the right moment. Kotlin, coroutines and Flow, Jetpack
Compose with Material 3.

- [Install](#install)
- [Set up in one line (or zero)](#set-up-in-one-line-or-zero)
- [Options](#options)
- [Show the chat](#show-the-chat)
- [Links into the chat](#links-into-the-chat)
- [Identify the person](#identify-the-person)
- [Moments, offers, entitlements](#moments-offers-entitlements)
- [Team actions](#team-actions)
- [Adapters](#adapters)
- [Push](#push)
- [Bug reports](#bug-reports)
- [Unread count and events](#unread-count-and-events)
- [Privacy, logout and erase](#privacy-logout-and-erase)
- [Build your own UI](#build-your-own-ui)
- [For coding agents](#for-coding-agents)
- [Artifacts](#artifacts)
- [Build from source](#build-from-source)

The [Android quickstart](https://pingfront.sh/docs/quickstart-android) walks through setup end to end.
[REFERENCE.md](REFERENCE.md) lists every public symbol, and [CHANGELOG.md](CHANGELOG.md) what each version contains.

## Install

### Requirements

| | Minimum | Why |
| --- | --- | --- |
| Kotlin (your app's compiler) | 2.2.0 | The SDK is compiled for Kotlin 2.2 (`languageVersion` and `apiVersion` 2.2) and asks for `kotlin-stdlib` 2.2.0; with its dependencies the standard library resolves to 2.2.x (2.2.21). A fresh React Native 0.88 or Expo 58 app declares Kotlin 2.2.0 and resolves the Kotlin Gradle plugin to 2.2.10; both read it. |
| Android Gradle Plugin | 9.1 | The oldest checked: Flutter 3.47's template (AGP 9.1.0, Kotlin 2.4.0); React Native 0.88 and Expo 58 use AGP 9.2.1 with Kotlin 2.2.10. |
| `compileSdk` | 36 | The SDK and every library it depends on compile against Android 16 (AndroidX Core 1.18, Compose 1.11, Lifecycle 2.10, OkHttp 5.4), so a default React Native, Expo or Flutter app builds unchanged. |
| `minSdk` | 26 (Android 8.0) | React Native, Expo and Flutter templates start at 24: raise it to 26 (`minSdkVersion = 26`, or `expo-build-properties`). |

Checked on 6 Nov 2026 by building a debug APK of a fresh React Native 0.88.1 app, a fresh Expo SDK 58 app (after
`expo prebuild`) and a fresh Flutter 3.47.5 app against the locally published SDK, changing only `minSdk`.

Published to Maven Central as `sh.pingfront:<module>` (POM, sources and javadoc jars, signed), the namespace of
pingfront.sh. The Kotlin packages stay `dev.pingfront.*`: a package name doesn't have to match the Maven group, and
renaming it would change every import. To build against a checkout instead, see [Build from source](#build-from-source).

```kotlin
// settings.gradle.kts: mavenCentral() and google()
dependencies {
    implementation("sh.pingfront:pingfront:1.0.0")            // core + Compose chat + bug reports
    implementation("sh.pingfront:pingfront-push:1.0.0")       // optional: FCM (Firebase)
    implementation("sh.pingfront:pingfront-billing:1.0.0")    // optional: Google Play Billing + In-App Review
}
```

## Set up in one line (or zero)

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PingFront.configure(this, "pk_live_…")
    }
}
```

Or no code at all: put the key in the manifest and `androidx.startup` configures the SDK at launch.

```xml
<application>
    <meta-data android:name="pingfront.app_key" android:value="pk_live_…" />
</application>
```

A new install touches nothing on the network at launch (an install with a session from an earlier launch may poll
unread). The key is checked offline (CRC-32 check characters). A mistyped
key never fails silently: debug builds show a red banner in the chat with the exact problem, log it with
`Log.e`, and set `PingFront.configurationProblem`; release builds log it and disable the SDK. A session
is created lazily on the first chat open, listened moment, push open, bug report or `prepare()`.

## Options

Every option has a default. Look values set here sit under the look you publish from the dashboard
(dashboard > code > inferred from your app > SDK defaults).

The app icon in the chat is your app's launcher icon (`PackageManager.getApplicationIcon`); without one it is the app's
icon from the dashboard (`appIconUrl`, the icon of its Google Play or App Store listing), and without either, the first
letter of the app's name. The dashboard has no icon upload.

```kotlin
PingFront.configure(this, "pk_live_…") {
    topics(ChatTopic.Billing, ChatTopic.Bug, ChatTopic.Idea)       // up to 6, or ChatTopic(id, label, symbol, prefill)
    appearance {
        accent = PingFrontColor(0xFF00796B)                         // default: your theme's colorPrimary
        corners = Corners.Soft                                      // Round (18 dp), Soft (12), Square (6)
        font = FontStyle.Rounded                                    // System, Rounded, Serif
        headerStyle = HeaderStyle.Team                              // Person, Team, App
        replyExpectation = "Usually replies within a few hours"   // default: "Usually replies within a day"
        useDynamicColor = true                                      // Material You (Android 12+), off by default
    }
    use(Billing(products = listOf("pro_monthly", "pro_yearly")), Push, Reports)
    logLevel = LogLevel.Debug                                       // Off, Error, Info (default), Debug
    logHandler = LogHandler { Timber.tag("pingfront").i(it.message) } // also to your logger; lines carry no personal data
    language = "pt-BR"                                              // your app's own language picker; null follows the system
    baseUrl = "https://api.pingfront.sh/v1"                         // the default; another server only to test against it
    presentsChatForMoments = true
}
```

With Compose colors (`pingfront-ui`): `accent = Color(0xFF00796B).toPingFrontColor()` or
`PingFrontColor(light = Color(…), dark = Color(…))`.

The chat feels like Google Messages: a full-height bottom sheet, a centered face with the name in a
tonal capsule, rounded bubbles that group tightly, Read/Delivered under your last message, the typing
bubble, and a pill composer whose send button appears with text. Text on the accent is picked by contrast.

## Show the chat

```kotlin
// From anywhere (View-system apps too): an SDK-presented bottom sheet.
PingFront.openChat()
PingFront.openChat(ChatTopic.Billing)
PingFront.closeChat()
PingFront.closeChatAndWait()                              // suspends until the SDK's sheet is off screen (at once if none)

// Compose: a sheet you control.
val chat = rememberPingFrontChatState()
Button(onClick = { chat.open(ChatTopic.Bug) }) { Text("Help") }
PingFrontChatSheet(chat)                                  // or PingFrontChatSheet(visible, onDismiss, topic)

// Compose: embedded, for example as a tab.
PingFrontChat(Modifier.fillMaxSize())
```

The embedded chat is edge-to-edge aware the way Compose's own `Scaffold` is: its `windowInsets` parameter defaults to
`PingFrontChatDefaults.windowInsets` (`WindowInsets.safeDrawing`: the system bars, the display cutout and the
keyboard), and it pads only the insets no ancestor has consumed. As the whole window, the header clears the status bar
and the composer rides on the keyboard. Inside your own `Scaffold`, above a bottom bar, consume the Scaffold's
padding and the composer sits flush on the keyboard, with no band under it:

```kotlin
Scaffold(bottomBar = { NavigationBar { … } }) { padding ->
    PingFrontChat(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize())
}
```

In a `Dialog`, draw edge to edge (`DialogProperties(decorFitsSystemWindows = false)`) and give the dialog's container
`safeDrawingPadding()`; a window that is not edge to edge passes `windowInsets = WindowInsets(0)`. When the chat is
the whole window, the status bar draws over it: set the status bar icons for the chat's scheme
(`WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars`).

### Embedded in a View layout

`PingFrontChatView` (in `dev.pingfront.ui`) is the embedded chat as an Android `View`, for screens that are not
Compose: an XML layout, a `Fragment`'s views, a React Native view manager or a Flutter platform view. It owns its
composition, so the app needs neither the Compose compiler nor its own lifecycle owners or recomposer.

```kotlin
val chat = PingFrontChatView(context).apply {
    topic = ChatTopic.Billing                                  // optional; its prefill goes into the composer once
    onClose = { navController.popBackStack() }                 // optional; shows the header's close button
}
container.addView(chat, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
```

```xml
<dev.pingfront.ui.PingFrontChatView
    android:id="@+id/chat"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

| Member | What it does |
| --- | --- |
| `topic: ChatTopic?` | The topic the chat opens with, as `PingFrontChat(topic = …)`. A different topic starts afresh. |
| `onClose: (() -> Unit)?` | The header's close button; `null` (the default) shows none. |
| `insets: PingFrontChatView.Insets` | `SafeDrawing` (the default): in an edge-to-edge window, pads for the part of the system bars, the display cutout and the keyboard that overlaps the view, and not for the space your layout already leaves around it (a toolbar above, a bottom bar below); the composer rides on the keyboard as it animates. `None`: the window is not edge to edge, or your layout keeps the view clear. `Padding(left, top, right, bottom)`: exactly this padding in pixels, which your own layout computed; the keyboard is then yours too. |
| `activityResultRegistryOwner` | Launches the photo picker, the camera and the permission prompt. `null` uses the activity's (any `ComponentActivity`); an activity without one passes its own. |
| `setViewCompositionStrategy(…)`, `disposeComposition()` | The composition is disposed when the view leaves the window or a `RecyclerView` releases it (Compose's default). A host that takes the view out of the window while it should keep its state sets another strategy and disposes it itself. |

The window's lifecycle and saved-state owners are used when it has them (a `ComponentActivity`, `Fragment` or
`ComponentDialog`); in a window without them (`FlutterActivity`) the view keeps its own, and its own recomposer. Give
it an id, as any `ComposeView`, so a draft survives rotation. It counts once toward `PingFront.isChatVisible`, exactly
as `PingFrontChat` does, while the host is started, the view is attached and neither it nor an ancestor is hidden.

`closeChatAndWait()` waits only for the chat the SDK presented; an embedded `PingFrontChat` is yours and stays, so
call it before showing your own screen from an offer or link handler. `PingFront.isChatVisible` is a `StateFlow<Boolean>`. It is `true` only while the chat is on screen (its
lifecycle is at least started), so behind Home it is `false` and notifications show again. Each chat on screen
counts once: a sheet that closes over an embedded `PingFrontChat` leaves the chat visible. While an embedded chat is on
screen, `openChat`, a notification tap, a chat link and a moment that presents the chat focus it (it scrolls to the
latest message and takes the topic's prefill) instead of opening the sheet over it.

The built-in chat marks messages read by itself; a custom chat UI built on `PingFront.conversation` calls
`conversation.markRead()` when the person has seen the latest messages (it does nothing when there is nothing to mark
or while the app is paused, and drops the unread count to 0). `conversation.reload()` is a `suspend` function that
returns once the thread loaded or the load failed; a thread already on screen stays on a failure.
`rememberPingFrontChatState()` keeps an open sheet open across rotation, dark mode and font changes. With the
`PingFrontChatSheet(visible, onDismiss, topic)` overload, keep your flag in `rememberSaveable` or a ViewModel.

Topics never send anything on their own. A tap on a topic on the welcome screen opens its flow or fills the
composer: "Report a bug" opens the report, a topic with a `prefill` uses that text, the other presets put a sentence
start ("I have a question about billing: "), and your own topics put their label ("Shipping: "). `openChat(topic)` and
a chat link put only the topic's `prefill`, as on iOS. Typed text is never replaced. The default labels are verbs:
"Ask about billing", "Report a bug", "Share an idea".

When the server says the person can't send (the app paused in the dashboard, the person blocked, sign-in required),
the composer is replaced by a notice that says so, and nothing promises a reply.

Messages wait in a persistent outbox and go in the order they were written. A message refused because the app is
paused or the app wants a sign-in is held, not failed: one muted line under the last message that waits says "Not sent
yet. It will send when the chat is back." or "Not sent yet. It will send after you sign in.", and the messages behind
it wait for it. A held message goes by itself, first, even with the chat closed and after a relaunch: a pause's hold
after the next unread count or thread load that the server answers, a sign-in's hold once a `PATCH /me` leaves the
person verified. A long press on a held or waiting message offers Delete (TalkBack lists it among the actions), and
deleting the first one lets the rest go. A blocked person is promised nothing: the message fails plainly ("Not
delivered"), and its options offer Delete alone. Other failures open Try again and Delete. When the server revokes the
install (`PingFrontEvent.SessionRevoked`), queued messages are wiped with the rest of the local state and never sent
under the new guest. Known limitation, as on iOS: an app
resumed while the chat stays open keeps the paused notice until the chat is opened again or the app comes back to the
foreground (a paused app gets no live channel to hear of the change).

When the team resets the conversation from the dashboard (the live `thread_reset`), the chat empties in place with no
event: the thread, its revision and the unread count go, the draft and unsent messages stay (the next message starts a
new thread), and the socket stays open. A custom UI reads `PendingMessage.heldFor` (`AppPaused`,
`VerificationRequired`) and `isHeld`, and `ConversationState.isServerUnreachable`, to say the same.

"You're offline" follows the device's network (ConnectivityManager) and requests that could not reach the server,
never the live socket alone. Online with the server out of reach, the notice says "Can't reach the chat right now.
Messages will send when it's back." instead, and a waiting message says "Not sent yet. Trying again soon." At 150 %
font size and up, or a compact window height, the notices are a row of the thread (before the first message waiting to
send, else last) or the welcome's first row, so the header never holds text that can grow without limit.

## Links into the chat

A link your app receives can open the chat or the bug report: an email from your team, a "Message us" QR code,
a "Chat with us in the app" button on your website, a shortcut, or a push from your own provider. A chat link is
any URL whose query has `pingfront=chat` or `pingfront=report`, on your own App Links domain or custom scheme:

```text
https://example.com/help?pingfront=chat
myapp://support?pingfront=chat&topic=billing
myapp://support?pingfront=report
```

`topic` applies only when it matches one of the chat's topics (the dashboard's, else yours, else the defaults);
otherwise it is ignored and the chat still opens. A link never carries text for the composer, and other
parameters are ignored. The rules and the table of accepted and refused URLs are in
[Chat links](https://pingfront.sh/docs/chat-links).

The SDK claims no domain or scheme: keep your intent filters, and hand the intent over.

```xml
<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop">
    <!-- Your custom scheme -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="myapp" />
    </intent-filter>
    <!-- Or your App Links domain (with assetlinks.json on https://example.com) -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="example.com" android:pathPrefix="/help" />
    </intent-filter>
</activity>
```

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
        // Not again after a rotation: the activity is recreated with the same intent.
        if (savedInstanceState == null && !PingFront.handle(intent)) router.open(intent.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!PingFront.handle(intent)) router.open(intent.data)
    }
}

// A URI from anywhere else: your router, a Compose navigation deep link, your own notification's data.
if (PingFront.handle(uri)) return
```

- `handle(intent)` reads only an `ACTION_VIEW` intent's data, never its extras. `handle(uri)` takes any URI.
- It returns `true` only for a chat link it handled; every other URL is yours to route.
- It opens the chat exactly like `openChat()` (the report like `openReport()`), so the session starts when the
  chat appears. `handle` itself sends nothing.
- Before `configure` (your activity ran first), it returns `true` and keeps the link in memory; `configure` opens
  it once. The report waits until your activity is resumed, so its screenshot shows your screen.

Check it on an emulator or a phone with your own scheme or domain:

```sh
adb shell am start -W -a android.intent.action.VIEW -d 'myapp://support?pingfront=chat' your.application.id
```

## Saved links into your screens

The team can send link cards that open a screen of your app (saved links, in the dashboard's Offers, Links tab).
Register the screens this build can open and handle the taps:

```kotlin
PingFront.registerScreens(
    listOf(
        AppScreen("settings.sound", title = "Sound settings"),   // the title is for the team's dashboard only
        AppScreen("plans/yearly"),
    ),
)

PingFront.onScreen = { screen ->                                 // main thread; may suspend
    navController.navigate(screen.id)                            // screen.params: Map<String, String>
    true                                                         // false: the app can't open it now
}
```

- Ids match `^[A-Za-z0-9][A-Za-z0-9_./:-]{0,99}$`. A bad id throws in a debuggable build and is dropped (and logged)
  in a release build; each id counts once, at most 200. A later call replaces the list.
- Registering sends nothing. The list rides on `POST /v1/sessions`, and on a refresh or `PATCH /v1/me` only when it
  or the app version changed since this install last sent it.
- A tap on a card for a registered screen calls `onScreen`, which may suspend: the SDK waits up to 10 seconds for its
  answer, then cancels it and counts the tap as not handled (as on iOS). A screen this build didn't register opens the card's
  `url` instead (through `onLink`, then the browser). The chat reports each tap once, as opened or failed
  (`not_handled` when the handler returns false or none is set, `unknown_screen` without a fallback URL,
  `cannot_open` when nothing opens the URL), so the team sees which links work. The rules are in
  [Saved links](https://pingfront.sh/docs/offers#saved-links).
- Link cards open `http` and `https` URLs with a host in the browser, and the app's own scheme through an `ACTION_VIEW`
  intent limited to the app's package (`cannot_open` when none of its activities handles it). A `javascript:`, `data:`, `vbscript:`, `file:`, `blob:`, `about:`, `content:` or `intent:`
  link is never opened and never reaches `onLink`; another app's scheme is never opened. Review cards open only
  `http` and `https`. The rule is in [Chat links](https://pingfront.sh/docs/chat-links).
- Card reports (taps, picks, ratings) are stored with the install, each with its own `clientId`, and retried until the
  server answers: no answer, 408, 429, 500, 502, 503 and 504 retry with backoff, and at once when the network comes back or the app
  returns to the foreground. The server records a `clientId` once, so a retry never counts twice. A report is dropped
  after 24 hours or 10 attempts, and logout and erase clear the queue.

## Identify the person

```kotlin
PingFront.identify(userId = user.id, name = user.name, email = user.email)   // named
PingFront.identify(IdentityToken(jwtFromYourBackend))                        // verified: one thread across devices
PingFront.setTraits(mapOf("plan" to "pro", "city" to "Izmir"))
```

These calls return immediately. Changes are coalesced for 250 ms and sent as one `PATCH /me` with only
the changed fields, once a session exists. The staged profile is stored encrypted, so it survives a
restart.

## Moments, offers, entitlements

```kotlin
// Report app moments. Only moments a play listens to are sent (the list is cached for 5 minutes).
val result = PingFront.moment("paywall_closed", mapOf("screen" to "plans", "seconds" to 42))
// MomentResult.OpenRequested(presented = true) means the server asked to open the chat, and the SDK did.
// Report it from the screen that is closing: leaving that screen cancels only your wait for the result, never the moment.

// Offers the team can send as cards, and your paywall for them.
PingFront.setOffers(listOf(OfferSlot(id = "yearly_winback", title = "Pro yearly", kind = OfferSlot.Kind.Winback)))
PingFront.onOffer = { request -> showPaywall(request.offerId) }

// Subscription state (automatic with Billing).
PingFront.setEntitlement(Entitlement(Entitlement.State.Trial, productId = "pro_yearly"))
```

`use(Billing(products = …))` reads Play purchases (it never acknowledges or consumes your own), reports the
products as offer slots with Play's prices, buys from offer cards with your `storeAccountToken` as
`obfuscatedAccountId`, detects lapses (cancel, expiry, billing problems) as store moments with Play's
`purchaseToken` and `orderId`, and shows the Play In-App Review flow for review cards.

Play refunds a purchase that is not acknowledged within three days. A purchase started from an offer card runs
on the SDK's own `BillingClient`, so your `PurchasesUpdatedListener` is not called for it, and the SDK acknowledges
it itself once it is paid, as the iOS module finishes the transactions it starts. A pending purchase is acknowledged
when payment completes while the app is running; one that completes after the app was closed shows up in your own
`queryPurchasesAsync` on resume (Google's recommendation), so acknowledge new purchases there too. It never acknowledges a purchase your app started. To grant and acknowledge offer-card purchases yourself,
pass a handler; the SDK then leaves them to you:

```kotlin
use(Billing(products = listOf("pro_monthly")) { purchase -> grant(purchase); acknowledge(purchase) })
```

Setting `PingFront.onOffer` means your app runs the purchase itself, so the SDK starts none and acknowledges none.

A second tap while a purchase sheet is open returns `OfferOutcome.Failed(OfferFailure.InProgress)`.

**Promotional offers.** An offer card can name a store offer (`Card.Offer.offer`, a `StoreOffer` with
`basePlanId` and `offerId` on Play). The card shows the price this person pays, with the regular price struck
through. Billing buys the named offer, or the base plan when Play does not list the offer, which means the person
is not eligible. Your `onOffer` gets it as `OfferRequest.storeOffer`. An offer kind this SDK does not know only
opens your paywall.

**Store action cards** (`Card.StoreAction`, `kind: "store_action"`) open Google Play's own screens: the Play Store
app when it is installed, else the browser. Each card uses the team's `title`, `body` and `button` when sent,
else localized copy for the action, with an icon and an "Opens Google Play" mark.
- `manage_subscriptions`: `https://play.google.com/store/account/subscriptions?sku=…&package=…` (without
  `productId`, the subscriptions list). The plan's status shows under it ("Pro renews 12 Nov").
- `billing_issue`: Play's own in-app message for payment problems first (`showInAppMessages`, transactional).
  When Play has nothing to show, the subscriptions link.
- `request_refund`: Play has no in-app refund sheet, so the order history (`/store/account/orderhistory`).
- `redeem_code`: `https://play.google.com/redeem`.
- `restore_purchases`: re-reads purchases with `queryPurchasesAsync` in place, then shows a receipt line.
- An action this SDK does not know shows the card's text and opens the subscriptions link.

After a trip to Play, purchases are read again when the app returns. If the subscription changed, the
entitlement is updated and the card gets a receipt line at once ("Subscription updated"). The SDK reports it with
`POST /v1/store-actions`, so the team sees it in the thread too, and swaps the local line for the server's message.

**With Billing**, offer cards also show:
- every pricing phase Play will charge ("1 week free, then €1.99 a month for 3 months, then €29.99 a year");
- switches between subscription products: an upgrade replaces the plan now with time proration, and a downgrade
  starts at renewal (`DEFERRED`). The card says which;
- "Waiting for payment" while a purchase is pending (Play's pending purchases).

Play reports products, not base plans, on the device, so switches cover separate products.

**Plan status.** The server's plan (`PersonSummary.entitlement`, built from store events) comes with every
session and `PATCH /v1/me`, and `PingFront.person` carries it. The chat shows one line from it, falling back to
the device's entitlement:
- trial or active: "Pro renews 12 Nov" (`renewsAt`);
- grace or billing retry: "Payment issue: update by 4 Nov" (`gracePeriodEndsAt`), paired with the
  `billing_issue` card;
- cancelled: "Pro ends …" (`expiresAt`);
- expired: "Pro ended …".

Manage cards show any status line; billing-issue cards show only the payment issue.

**Review cards** open Play In-App Review. Play may decide not to show it and does not say so, so the store
listing opens only on a second tap of the same card. `store_page` cards open the listing right away.

## Team actions

A teammate can fix something for the person from the inbox ("Fix it for them"): extend a trial, grant access,
or run one of your custom actions. Your app does the work and confirms it, and the receipt then posts in the
thread.

```kotlin
lifecycleScope.launch {
    PingFront.teamActions.collect { action ->
        when (action.actionId) {
            "extend_trial" -> { myBilling.extendTrial(action.days ?: 7); action.complete() }
            else -> action.fail("This version can't do ${action.name} yet.")   // shown to the team, not the person
        }
    }
}
```

Actions arrive live and through `GET /v1/team-actions` after every session start and reconnect, so an action
sent while the app was closed still arrives. Each arrives once per `id`, also across launches: one you already
answered is not delivered again while its ack waits to be sent. Do the work once per `id`. Acks are stored and retried
until the server has them, across launches. They go out the next time the SDK starts or refreshes a session: when the
chat opens, when your app calls something that needs one, or on a later launch once it makes its first request. An
ack never starts a session by itself, so nothing is sent at launch for it. While the app is paused in the dashboard
acks wait without retrying, and go out with the first session after it is resumed. An expired action (409) or
another person's (404) is dropped. The same
actions also go to your webhook when one is configured.

On pingfront's Free plan, the chat's welcome screen carries our one-colour lockup on its own bar
above the composer: 14 dp, faded, tinted with the chat's muted ink, so it follows the app's palette in
light and dark (`AppConfig.features.branding`, `R.drawable.pf_lockup`). It is artwork, so no font
changes its letters; `R.string.pf_chat_by_pingfront` is its content description. It never appears
inside a conversation.

## Adapters

Using RevenueCat, Superwall or Adapty for paywalls? Add the adapter for it instead of `pingfront-billing`. Each one is
its own artifact, so apps without the vendor never link it, and core has no vendor dependency. Every adapter:

- **reports paywalls:** offer slots with `source` set to the vendor, refreshed when the vendor's data changes;
- **opens them:** an offer card presents the vendor's paywall and reports purchased, cancelled or failed;
- **keeps the entitlement current:** from the vendor's customer state (`source` set to the vendor);
- **links identity:** the person's store account token is set as `pingfront_token` (a RevenueCat subscriber
  attribute, a Superwall user attribute, an Adapty custom attribute), so webhooks find the person;
- **redeems a card's Play offer:** buys the subscription option whose `basePlanId` and `offerId` match, where the
  vendor allows it.

Cancellations, expiries and billing issues reach pingfront through the vendor's webhook, so the adapters don't
report them from the device. None of the three SDKs gives an app a way to set Play's `obfuscatedAccountId` (we found
no use of it in their public API), so with an adapter the person is matched through `pingfront_token`.

**RevenueCat** (`sh.pingfront:pingfront-revenuecat`, purchases-android 11):

```kotlin
Purchases.configure(PurchasesConfiguration.Builder(this, "goog_…").build())
PingFront.configure(this, "pk_live_…") { use(RevenueCat) }
```

Every offering becomes a slot: `title` is the offering's description, `kind` comes from the offering metadata key
`pingfront_kind` (`standard`, `discount`, `winback`), and the slot is `available` until the person owns every
product in it. A card opens the offering's published paywall (RevenueCat Paywalls), else buys its first package.
Entitlement: lifetime when there is no expiry, then trial, then billing issue (`billing_retry`), then
`cancelled` when it won't renew, else `active`.

**Superwall** (`sh.pingfront:pingfront-superwall`, superwall-android 2):

```kotlin
Superwall.configure(this, "pk_…")
PingFront.configure(this, "pk_live_…") { use(Superwall(SuperwallPlacement("campaign_trigger", "Pro yearly"))) }
Superwall.instance.identify(userId)          // the same id you pass to PingFront.identify
```

One slot per placement you list, always available. The adapter never asks Superwall ahead of time, because in
superwall-android 2.9.0 `getPresentationResult` confirms the assignment and records holdouts, just like
`register`. Audiences and holdouts decide when the card is tapped: a card registers the placement, and a holdout or
no audience match comes back as unavailable. Superwall paywalls carry their own offers, so a
card's store offer is ignored.

**Adapty** (`sh.pingfront:pingfront-adapty`, Adapty Android 4):

```kotlin
Adapty.activate(this, AdaptyConfig.Builder("public_live_…").build())
PingFront.configure(this, "pk_live_…") { use(Adapty("main", "winback")) }
Adapty.identify(userId) { }                  // the same id you pass to PingFront.identify
```

One slot per placement, using its flow's name, products and the remote config key `pingfront_kind`. A card shows the
placement's AdaptyUI flow, else buys its product. Built and tested against Adapty 4.x (the current stable release).

## Push

```kotlin
use(Push)          // FCM: forwards the token, updates the unread count, shows notifications, opens the chat on tap
```

A received push updates `PingFront.unreadCount` (from `pf_unread`). A tap opens the app and the chat, also
when the app is alive in the background. Taps go through the SDK's own non-exported activity: the SDK never
reads `pf_*` extras from your launcher activity's intent, because any app can start that one. After logout
or erase, pushes addressed to the previous person are not shown, also when one wakes the app after it was killed
(a signed-out marker is kept with the SDK's records).

`pingfront-push` registers `PingFrontMessagingService`. If your app has its own
`FirebaseMessagingService`, remove ours in your manifest (`tools:node="remove"`) and forward:

```kotlin
override fun onRegistered(token: String) = PingFront.setPushToken(token)   // FCM 26: register() / onRegistered
override fun onMessageReceived(message: RemoteMessage) {
    if (PingFrontPush.handleMessage(this, message.data)) return
    // your own messages
}
```

**Notification icon.** Android draws the status bar icon from its alpha channel only, so a full-colour launcher
icon shows as a solid shape. Give the SDK a monochrome icon (a white silhouette on transparent, as
[developer.android.com](https://developer.android.com/develop/ui/views/notifications) asks) in your manifest:

```xml
<application>
    <meta-data android:name="pingfront.notification_icon" android:resource="@drawable/ic_stat_notification" />
</application>
```

Without it, the SDK uses Firebase's `com.google.firebase.messaging.default_notification_icon` when your app sets
one, and your app's icon otherwise.

Building the notification yourself? Use `PingFrontPush.contentIntent(context, message.data)` as its tap
action, and `PingFront.handlePush(message.data)` for the unread count.

**Another push SDK in the app** (OneSignal, Braze, Expo notifications, or FCM through another library). An app
has one `FirebaseMessagingService` that receives every FCM message, and pingfront sends its own as data messages
(`pf_v = "1"`) to the token you pass to `setPushToken`. Keep one service, yours, and let each SDK take its own
messages:

```kotlin
class AppMessagingService : FirebaseMessagingService() {
    override fun onRegistered(token: String) {
        PingFront.setPushToken(token)
        // and hand the token to your provider, as its docs say
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (PingFrontPush.handleMessage(this, message.data)) return // pingfront's: unread count and notification
        // otherwise your provider's handler, for example Braze's
        // BrazeFirebaseMessagingService.handleBrazeRemoteMessage(this, message), which returns false for others
    }
}
```

- `PingFrontPush.isPingFrontMessage(data)` (or `PingFront.isPingFrontPush(data)` without the Push module) tells
  pingfront's messages apart, so your provider never has to see them.
- If your provider owns the service and doesn't let you replace it, its docs say how other messages are forwarded;
  the pingfront call is the same `PingFrontPush.handleMessage(context, message.data)`.
- Your provider's own pushes can open the chat too: give the notification a chat link as its deep link or URL
  (`myapp://support?pingfront=chat`). Its tap starts your activity with that link, and
  [`PingFront.handle(intent)`](#links-into-the-chat) opens the chat.

Without Firebase, call `PingFront.setPushToken(token)` from your own push provider, or skip push: the SDK
polls `GET /unread` in the foreground only (every 20 s for two minutes after activity, then every two
minutes for an hour). With push, it checks once each time the app comes to the foreground. Launcher badges
are set only when the dashboard turned `appBadge` on; `PingFront.usesAppBadge` says whether it is, for code that
shows pingfront's notifications itself.

## Bug reports

```kotlin
use(Reports)                                   // shake to report, screenshot with markup, log tail, device state
PingFront.openReport()                         // open the flow from a button
PingFront.reportBug("Sync stopped", screenshot = pngBytes)
```

## Unread count and events

```kotlin
val unread by PingFront.unreadCount.collectAsStateWithLifecycle()
BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) { Icon(…) }

lifecycleScope.launch {
    PingFront.events.collect { event ->
        when (event) {
            is PingFrontEvent.MessageReceived -> showBanner(event.message.preview)
            is PingFrontEvent.UnreadChanged -> updateBadge(event.count)
            is PingFrontEvent.MessageSent -> analytics.log("support_message_sent", mapOf("photos" to event.message.photoCount))
            is PingFrontEvent.CardOpened -> analytics.log("support_card_opened", mapOf("kind" to event.card.kind.name))
            is PingFrontEvent.ConfigurationProblemFound -> Log.w("app", event.problem.message)
            else -> Unit
        }
    }
}
```

The full set: `ChatOpened`, `ChatClosed`, `MessageReceived`, `MessageSent` (no text, only its length and photo
count), `CardOpened` (offer, review, link, choices, rating, report, store action), `UnreadChanged`,
`SessionStarted`, `IdentityProblemReported`, `OfferRequested`, `OfferCompleted`, `LookChanged`,
`ConfigurationProblemFound`, `ReportSent` and `SessionRevoked`. Keep an `else` branch: later versions add events.

Set `PingFront.language = "de"` whenever the person picks a language in your app's own picker; the chat, its
right-to-left layout, the team's localized texts and the server's lines follow it. `null` goes back to the
system language. If you ship an app bundle, keep every language installed
(`bundle { language { enableSplit = false } }`), as any app with its own language picker must.

The chat ships in [50 languages](https://pingfront.sh/docs/localization):
Arabic, Bengali, Bulgarian, Catalan, Chinese (Simplified, Traditional and Hong Kong), Croatian, Czech, Danish,
Dutch, English, Finnish, French (France and Canada), German, Greek, Gujarati, Hebrew, Hindi, Hungarian, Indonesian,
Italian, Japanese, Kannada, Korean, Malay, Malayalam, Marathi, Norwegian Bokmål, Odia, Persian, Polish, Portuguese
(Brazil and Portugal), Punjabi, Romanian, Russian, Slovak, Slovenian, Spanish (Spain and Latin America), Swedish,
Tamil, Telugu, Thai, Turkish, Ukrainian, Urdu and Vietnamese.

Also `PingFront.person`, `PingFront.look`, `PingFront.isTestDevice` and `PingFront.configurationProblem`
(all `StateFlow`s).

## Privacy, logout and erase

```kotlin
PingFront.logout()                 // forgets identity, traits and the thread here; tells the server (retried)
PingFront.eraseMyData()            // suspend: DELETE /me, then wipes local data (safe to retry)
val json = PingFront.exportMyData() // suspend: GET /me/export, the person's data as JSON text
PingFront.prepare()                // suspend: create the session now (for example after login)
PingFront.handlePush(data)         // a received FCM data message: applies its unread count; true for pingfront payloads
```

A call that is running when the person logs out, or when their data is erased, throws
`PingFrontException.SessionChanged` and applies nothing. Nothing of the previous person comes back after
logout: not their thread, unread count or profile. While `eraseMyData()` runs, no request can re-create them.

`exportMyData()` returns everything pingfront stores about the person (`GET /me/export`) as the server's
JSON text, a `PersonExport` (`format: pingfront.person.v1`): profile, devices, the thread with every message,
moments, store events and plays, with no secrets, push tokens or team notes. Save or share it as it is; the SDK
shows no UI and keeps no copy. Like `eraseMyData()` it needs a session but no identity. It is limited to 5 an
hour per install: past that it throws `PingFrontException.Server` (`rate_limited`, scope `RateScope.Exports`),
and calls fail fast with `PingFrontException.CoolingDown` until the wait is over.

When the app's retention deletes old photos, their messages stay and the chat shows "Photo removed" where each
photo was (`MessagePart.Image.removed`); nothing is fetched.

Suspend functions (`eraseMyData`, `exportMyData`, `prepare`, `moment`, `reportBug`) and `events` are Kotlin-only. Java callers
use the non-suspending calls (`identify`, `setTraits`, `openChat`, `logout`, …), which are `@JvmStatic`.

Everything is stored under `noBackupFilesDir`, sealed with AES-256-GCM using an Android Keystore key, so
nothing is backed up and nothing is readable in plain text. Chat images are cached in memory only. Two
exceptions: the copy of a photo the person chooses to share, deleted when the share sheet returns, and a
camera capture, deleted once it is staged in the composer; both also go on logout and erase. Link previews are fetched on the device, only for messages a person wrote
and only from public https hosts. No advertising identifiers, no tracking. The SDK runs only in your app's
main process.

### Device context

So the team can see what a person's device looks like when they help them (the dashboard's Context card), the
SDK adds coarse device context (`Device.context`) to requests it **sends anyway**. It never sends a request just for
context, and nothing is read or sent at launch. None of it is ever logged.

| Group | What | When |
| --- | --- | --- |
| `build` | `installSource` (`play_store`, `other_store` with the `installer` package, `sideloaded`), `debuggable` | session start; on change |
| `display` | `screenClass` (compact under 600 dp), `scale` (to 0.5), `darkMode`, `textSize` (`default`, `large`, `accessibility` from font scale 1.5), `screenReader` (TalkBack), `reduceMotion` (animations off), `boldText` (Android 12+), the first 3 `languages` | session start; on change |
| `connection` | `network` (`wifi`, `cellular`, `wired`, `none`, `other`), `constrained` (Data Saver), `expensive` (metered), `lowPower` (Battery Saver) | session start; then on change, at most every 5 minutes |
| `health` | `freeStorage` (`under_1gb`, `from_1_to_5gb`, `from_5_to_20gb`, `over_20gb`), `battery` (rounded down to 10), `charging`, `thermal`, `memoryPressure` | session start only |
| `notifications` | `authorization` (`authorized` or `denied`), `channelEnabled` (pingfront's own channel) | session start; on change |

A session start is `POST /sessions` once the switches are known, or the first request in a process that carries
context: usually its first `POST /sessions/refresh`, and for a new install the request after the first answer. Changes
ride on the next refresh or `PATCH /me`. The SDK registers its own default network callback (WorkManager's retry
constraint has its own), only while a session
exists or the chat is on screen (never at launch for a new install, and it is unregistered on logout). It tells the
chat whether the device is online; the connection itself (with Data Saver and Battery Saver) is read from it only while
the app collects the connection group, and only the latest value is kept.

Each group has a switch in the dashboard (app settings). The SDK keeps the switches from the latest session answer
(`collect`) with the install and sends only the groups that are on. Until an install's first answer it sends no
context at all, so nothing goes out for a group the app turned off: a new install's first `POST /sessions` carries
none, and the first request after the answer carries the allowed groups. Servers that don't send `collect` mean every
group. The SDK uses `ACCESS_NETWORK_STATE`, which it already declares, and
asks for no other permission.

**Crash tool link.** To link a person to your crash reporter from the dashboard, set the trait
`crashReporterUserId` to the id you give that tool:

```kotlin
FirebaseCrashlytics.getInstance().setUserId(user.id)
PingFront.setTraits(mapOf("crashReporterUserId" to user.id))
```

The dashboard builds the link from the crash tool and URL template you set in the app's settings. The SDK does not
depend on any crash tool.

### Play Data safety

What pingfront sends, for your Data safety form: the messages and photos a person sends; the name, email and
user id you pass to `identify`; your traits; purchase state (with Billing or an adapter); the FCM token (with Push);
app activity (moments you report); and device and other ids (the install id, plus the device context above:
app info and performance, such as the device's settings, connection type, battery and storage buckets). It is all
encrypted in transit, used for app functionality and support, and erasable with `eraseMyData()`. The SDK reads no
advertising id and no device location, and nothing it sends is used for advertising. The server
derives a coarse place (country, region, city) from the request address and stores the place, never the address.

## Build your own UI

Depend on `sh.pingfront:pingfront-core` only and use `PingFront.conversation`: `state`
(`StateFlow<ConversationState>`: `messages`, `pending`, `timeline`, `team`, `policy`, `typing`, `live`,
`coolingDown`), `draft`, `composerInsertion`, and actions `send`, `retry`, `discard`, `loadOlder`,
`choose`, `rate`, `open`, `setTyping`, `cooldownRemaining`. `PingFront.look` is the resolved look.

## Types and future versions

Types the SDK hands you to read (`Message`, `PersonSummary`, `Author`, the `Card` and `MessagePart` classes,
`ReceivedMessage`, events and the like) are data classes whose constructor and `copy` are internal, so they can gain
fields in a minor release without breaking your build: you read them, you don't build them. The types you build are
regular classes whose constructors can be overloaded as they grow: `ServerError`, `OfferPrice`, `PricePhase`,
`StorePurchases`, `ReportContext`, `StoreMomentProps`, `ChatTopic`, `PingFrontColor`, `LocalizedText`, `Entitlement`,
`OfferSlot`, `OfferSlot.Product` and `OfferOutcome.Failed`. `EvolvableApiTest` fails on a public data class that exposes
its constructor or `copy`.

## For coding agents

Debug builds understand two launch extras (stripped from minified release builds):

```sh
adb shell am start -n com.example/.MainActivity --ez pingfront_selfcheck true
# logcat (tag PingFront): "PingFront self-check: PASS" or "PingFront self-check: FAIL <code>: <what to do>"
#   codes: key_invalid, key_unknown, network, app_paused, blocked, identity_user_mismatch, rate_limited, timeout
adb shell am start -n com.example/.MainActivity --es pingfront_preview conversation   # or welcome
```

The extras also work when the app configures pingfront later, as React Native and Flutter apps do. The SDK's
initializer tracks activities from process start and keeps the launch intent until `configure` runs.

The same check in code, typed: `val result = PingFront.selfCheck()`. It sends "Setup check from <app>" to your
inbox and returns `SetupCheckResult(passed, code, advice)`, and `result.line` is the one-line form.

For bridges and wrappers:
- `PingFront.isConfigured` is a `StateFlow<Boolean>`;
- `PingFront.onLink = { url -> router.open(url) }` is a `suspend` handler too: it returns true when your app opened
  the URL, and the browser opens it otherwise. The SDK waits at most 10 seconds (no answer, or a throw, counts as
  false), and a link card reports what really happened (`cannot_open` when nothing opened it);
- `PingFront.onScreen` is a `suspend` handler, so a bridge can await its JavaScript or Dart answer; the SDK caps the
  wait at 10 seconds itself, so bridges keep no cap of their own;
- `PingFront.storeAccountToken()` is the read-only token billing passes as Play's `obfuscatedAccountId`.

## Artifacts

| Artifact | Use it when |
| --- | --- |
| `sh.pingfront:pingfront` | Almost always: Core, the Compose chat (registered automatically) and bug reports. |
| `sh.pingfront:pingfront-core` | You build your own UI (no Compose). |
| `sh.pingfront:pingfront-ui` | You assemble modules yourself. |
| `sh.pingfront:pingfront-push` | FCM push (brings Firebase Messaging). |
| `sh.pingfront:pingfront-billing` | Google Play Billing entitlements, offers, lapses and In-App Review. |
| `sh.pingfront:pingfront-reports` | Shake to report (included in the umbrella). |
| `sh.pingfront:pingfront-revenuecat` | Paywalls and entitlements through RevenueCat. |
| `sh.pingfront:pingfront-superwall` | Paywalls and entitlements through Superwall. |
| `sh.pingfront:pingfront-adapty` | Paywalls and entitlements through Adapty. |

## Build from source

You need JDK 17 or newer, the Android SDK with platform 36 (`ANDROID_HOME`, or `sdk.dir` in `local.properties`), and
`bash` and `python3` for the source guards.

```sh
./gradlew build                                              # lint, ktlint, detekt, API dumps, source guards, unit tests
./gradlew publishToMavenLocal -Ppingfront.version=1.0.0      # every module into ~/.m2
```

To use that build in an app, add `mavenLocal()` before `mavenCentral()` in its repositories. The unit tests read the
protocol's conformance fixtures from `fixtures/`, or from the folder the `pingfront.fixtures` Gradle property names.

### Releasing

The pingfront team publishes releases to Maven Central through the Central Publisher API, with plain Gradle:

```sh
./gradlew publishToMavenCentral -Ppingfront.version=1.0.0
```

It publishes every module to `build/central/staging`, signed with the GnuPG agent (`signing.gnupg.keyName` and
`signing.gnupg.passphrase`), with `.md5` and `.sha1` checksums, checks that every file has its signature and
checksums, zips the folder into `build/central/pingfront-android-<version>.zip`, and uploads it with
`publishingType=USER_MANAGED`, authenticated with a Central Portal user token (`mavenCentralUsername` and
`mavenCentralPassword`). All four are Gradle properties in `~/.gradle/gradle.properties`, never in this repository.
Central validates the deployment, which then waits under Deployments at central.sonatype.com until someone presses
Publish. `-PmavenCentralDryRun` does everything but the upload and prints the request it would send; a `-SNAPSHOT`
version is refused. Only these tasks sign: `build` and `publishToMavenLocal` need no key.
