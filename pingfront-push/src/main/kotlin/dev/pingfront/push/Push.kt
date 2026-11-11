package dev.pingfront.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.pingfront.ModuleContext
import dev.pingfront.PingFront
import dev.pingfront.PingFrontModule
import dev.pingfront.PushPayload
import dev.pingfront.PushTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Push through Firebase Cloud Messaging: forwards the FCM token (`setPushToken`), updates the unread
 * count, shows pingfront notifications (the `pf_*` v1 data payload) and opens the chat when one is
 * tapped, also when the app is alive in the background. Core never
 * depends on Firebase; without this module, call `PingFront.setPushToken(token)` yourself, or rely
 * on unread polling.
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") { use(Push) }
 * ```
 */
public object Push : PingFrontModule("push") {
    override fun install(context: ModuleContext) {
        context.setPushTokenSource(FcmTokenSource(context.applicationContext))
        PingFrontPush.createChannels(context.applicationContext)
    }
}

/**
 * Registers with FCM (`FirebaseMessaging.register()`); the token arrives in
 * [PingFrontMessagingService.onRegistered]. `null` when Firebase is not set up in the app (no google-services.json).
 */
internal class FcmTokenSource(private val context: Context) : PushTokenSource {
    override suspend fun currentToken(): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        FirebaseMessaging.getInstance().register()
        return withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) { PushTokens.latest.filterNotNull().first() }
    }

    private companion object {
        const val REGISTRATION_TIMEOUT_MS = 10_000L
    }
}

/** The latest token FCM delivered to the service. */
internal object PushTokens {
    val latest: MutableStateFlow<String?> = MutableStateFlow(null)
}

/**
 * The service the module's manifest registers. FCM 26 delivers tokens through `register()` and
 * [onRegistered]; the deprecated `onNewToken` is not used. Apps with their own service call
 * [PingFrontPush] from it instead.
 */
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
public open class PingFrontMessagingService : FirebaseMessagingService() {
    override fun onRegistered(token: String) {
        PushTokens.latest.value = token
        PingFront.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PingFrontPush.handleMessage(this, message.data)
    }
}

/** Helpers for apps that own their `FirebaseMessagingService` and notification code. */
public object PingFrontPush {
    /** The notification channel for team messages. */
    public const val CHANNEL: String = "pingfront_messages"

    /** The same channel with launcher badges, used only when the dashboard turned `appBadge` on. */
    public const val BADGE_CHANNEL: String = "pingfront_messages_badge"

    private const val REQUEST_CODE = 4_217

    /** True for pingfront data messages (`pf_v = "1"`). */
    @JvmStatic
    public fun isPingFrontMessage(data: Map<String, String>): Boolean = PushPayload.parse(data) != null

    /**
     * Handles a received data message: updates `PingFront.unreadCount` from `pf_unread` and shows a
     * notification unless the chat is on screen (the message is already in the thread). Nothing is shown
     * after logout or erase until the next session (the payload was for the previous person). Returns
     * `true` when it was a pingfront message.
     */
    @JvmStatic
    public fun handleMessage(context: Context, data: Map<String, String>): Boolean {
        val payload = PushPayload.parse(data) ?: return false
        PingFront.handlePush(data)
        if (payload.kind == PushPayload.KIND_CONFIG || PingFront.isChatVisible.value || !PingFront.instance.acceptsPush(context)) return true
        showNotification(context, payload, data)
        return true
    }

    /**
     * The tap action for a pingfront notification you build yourself: opens the app and the chat. It
     * targets the SDK's own non-exported activity, so only this app can trigger it with a payload.
     */
    @JvmStatic
    public fun contentIntent(context: Context, data: Map<String, String>): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        PingFront.instance.pushTapIntent(context, data),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = context.getString(R.string.pf_push_channel)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(BADGE_CHANNEL, name, NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
            },
        )
    }

    /**
     * The status bar icon, which Android draws from the icon's alpha only, so it must be a monochrome silhouette
     * (developer.android.com, notifications): `pingfront.notification_icon` meta-data, else Firebase's
     * `com.google.firebase.messaging.default_notification_icon`, else the app's icon (a full-colour launcher icon shows
     * as a solid shape there, so apps set one of the two).
     */
    internal fun smallIcon(context: Context): Int {
        val meta = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return smallIcon(meta, context.applicationInfo.icon)
    }

    /** [smallIcon] from the app's meta-data. */
    internal fun smallIcon(meta: android.os.Bundle?, appIcon: Int): Int =
        ICON_KEYS.firstNotNullOfOrNull { key -> meta?.getInt(key, 0)?.takeIf { it != 0 } } ?: appIcon

    /** The manifest meta-data keys for the notification's small icon, in order. */
    private val ICON_KEYS = listOf(ICON_KEY, FCM_ICON_KEY)

    /** `<meta-data android:name="pingfront.notification_icon" android:resource="@drawable/…" />`. */
    private const val ICON_KEY = "pingfront.notification_icon"

    private const val FCM_ICON_KEY = "com.google.firebase.messaging.default_notification_icon"

    /** `pf_sound = "off"` (FcmData): post without sound. Absent or any other value keeps the channel's sound. */
    internal fun soundOff(data: Map<String, String>): Boolean = data["pf_sound"] == "off"

    private fun showNotification(context: Context, payload: PushPayload, data: Map<String, String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannels(context)
        val pending = contentIntent(context, data)
        // Launcher badges only when the dashboard turned them on: the host app usually owns its badge.
        val badge = PingFront.usesAppBadge
        val title = payload.title ?: context.applicationInfo.loadLabel(context.packageManager).toString()
        val builder = NotificationCompat.Builder(context, if (badge) BADGE_CHANNEL else CHANNEL)
            .setSmallIcon(smallIcon(context))
            .setContentTitle(title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setBadgeIconType(if (badge) NotificationCompat.BADGE_ICON_SMALL else NotificationCompat.BADGE_ICON_NONE)
            // The app turned notification sound off in the dashboard: no sound or vibration, on the same channel.
            .setSilent(soundOff(data))
        if (badge) payload.unread?.let { builder.setNumber(it) }
        try {
            NotificationManagerCompat.from(context).notify(payload.threadId ?: CHANNEL, 0, builder.build())
        } catch (_: SecurityException) {
            // Notifications are off for the app.
        }
    }
}
