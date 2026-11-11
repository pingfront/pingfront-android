# Changelog

## 1.0.0

- First release, on Maven Central as `sh.pingfront:*`. Android 8.0 (API 26) or later, Kotlin 2.2 or later in your
  app, `compileSdk` 36.
- The in-app chat in Jetpack Compose with Material 3, familiar the Google Messages way: an SDK-presented sheet
  (`PingFront.openChat()`), a Compose sheet you control (`PingFrontChatSheet`), an embedded `PingFrontChat`, or
  `PingFrontChatView` in a View layout.
- Setup in one line (`PingFront.configure`) or none (the key in the manifest), with the app key checked offline. A
  new install sends nothing at launch; its session starts with the first chat open, listened moment, push open,
  bug report or `prepare()`.
- Identity (`identify`, `identify(token)`), traits, moments, entitlements and offer slots.
- `sh.pingfront:pingfront` brings the core, the chat and `pingfront-reports` (shake to report a bug). Optional:
  `pingfront-push` (Firebase Cloud Messaging) and `pingfront-billing` (Google Play Billing and In-App Review).
- Adapters for RevenueCat, Superwall and Adapty: `pingfront-revenuecat`, `pingfront-superwall`, `pingfront-adapty`.
- Chat links (`PingFront.handle`), saved links into your screens (`registerScreens`, `onScreen`), unread count,
  events and team actions.
- `pingfront-core` alone for a chat UI of your own (`PingFront.conversation`).
- 50 languages with right-to-left layout, large text, foldables and tablets.
- Everything the SDK stores is sealed with an Android Keystore key; `logout()` and `eraseMyData()`.
