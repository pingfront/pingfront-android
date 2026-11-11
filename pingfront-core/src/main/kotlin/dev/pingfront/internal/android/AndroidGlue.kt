package dev.pingfront.internal.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.eventFlow
import dev.pingfront.internal.net.SdkVersion
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.runtime.LanguageOverride
import dev.pingfront.internal.wire.Device
import dev.pingfront.internal.wire.Limits
import dev.pingfront.look.DynamicColors
import dev.pingfront.look.HostAppInfo
import dev.pingfront.look.PngImage
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.RgbColor
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.TimeZone
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/** Tracks the resumed activity (for presenters, billing, review and screenshots) and the first launch intent. */
internal class ActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var resumed: WeakReference<Activity>? = null

    @Volatile private var registered = false

    @Volatile var onCreated: ((Intent) -> Unit)? = null

    /** Activities created and not yet destroyed (main thread). */
    @Volatile private var alive = 0

    private val afterResume = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun current(): Activity? = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /**
     * Runs [action] now when an activity is resumed (or none exists at all), else once the next one resumes: a chat
     * link arrives in `onCreate` or `onNewIntent`, before its activity is resumed, and the report flow needs a
     * resumed activity to capture. The last waiting action wins.
     */
    fun whenResumed(action: () -> Unit) {
        if (current() != null || alive == 0) return action()
        afterResume.set(action)
        // The activity may have resumed between the check and the set: run what is left exactly once.
        if (current() != null) afterResume.getAndSet(null)?.invoke()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        alive += 1
        if (savedInstanceState == null) onCreated?.invoke(activity.intent ?: return)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        afterResume.getAndSet(null)?.invoke()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        alive = (alive - 1).coerceAtLeast(0)
    }
}

internal object DeviceInfo {
    /** True in the app's main process (the SDK's storage is single-process). */
    fun isMainProcess(context: Context): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            manager?.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        return name == null || name == context.packageName
    }

    fun provider(context: Context): (String) -> Device {
        val app = context.applicationContext
        val info = try {
            app.packageManager.getPackageInfo(app.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val version = Limits.truncate(info?.versionName ?: "0", VERSION_CHARS)
        val build = Limits.truncate((info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L).toString(), VERSION_CHARS)
        return { installId ->
            Device(
                installId = installId.ifEmpty { null },
                sdk = Device.Sdk(SdkVersion.NAME, SdkVersion.CURRENT),
                app = Device.App(version, build, Limits.truncate(app.packageName, BUNDLE_CHARS)),
                os = Limits.truncate("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", SHORT_CHARS),
                model = Limits.truncate("${Build.MANUFACTURER} ${Build.MODEL}", MODEL_CHARS),
                locale = Limits.truncate(LanguageOverride.tag.value ?: java.util.Locale.getDefault().toLanguageTag(), SHORT_CHARS),
                language = Limits.truncate(languageTag(), LANGUAGE_CHARS),
                timeZone = Limits.truncate(TimeZone.getDefault().id, MODEL_CHARS),
            )
        }
    }

    fun languageTag(): String = LanguageOverride.tag.value ?: LocaleList.getDefault().get(0)?.toLanguageTag() ?: "en"

    fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (_: PackageManager.NameNotFoundException) {
        "0"
    }

    fun isDebuggable(context: Context): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

/** Reads the host app's name, theme primary color (light and dark) and icon. No network, cheap enough for a background thread. */
internal object HostInference {
    fun infer(context: Context): HostAppInfo {
        val app = context.applicationContext
        val name = app.applicationInfo.loadLabel(app.packageManager).toString()
        val light = primary(app, night = false)
        val dark = primary(app, night = true)
        val accent = if (light != null) PingFrontColor(light, dark ?: light) else null
        return HostAppInfo(name, accent, icon(app), DeviceInfo.appVersion(app))
    }

    // The host app's own `colorPrimary` attribute (AppCompat/MDC) can only be found by name: the SDK has no R class for it.
    @SuppressLint("DiscouragedApi")
    private fun primary(context: Context, night: Boolean): RgbColor? {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode =
                (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        val themed = ContextThemeWrapper(context.createConfigurationContext(configuration), context.applicationInfo.theme)
        val appAttr = context.resources.getIdentifier("colorPrimary", "attr", context.packageName)
        for (attr in listOfNotNull(appAttr.takeIf { it != 0 }, android.R.attr.colorPrimary, android.R.attr.colorAccent)) {
            val value = TypedValue()
            if (!themed.theme.resolveAttribute(attr, value, true)) continue
            val color = if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                value.data
            } else if (value.resourceId != 0) {
                try {
                    themed.getColor(value.resourceId)
                } catch (_: android.content.res.Resources.NotFoundException) {
                    continue
                }
            } else {
                continue
            }
            val rgb = RgbColor.argb(color)
            if (isUsable(rgb)) return rgb
        }
        return null
    }

    /** Framework greys (the default action bar color) are not an accent. */
    private fun isUsable(color: RgbColor): Boolean {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        return max - min > MIN_CHROMA && max > MIN_BRIGHTNESS
    }

    private fun icon(context: Context): PngImage? = try {
        val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
        val bitmap = createBitmap(ICON_PX, ICON_PX)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        PngImage(out.toByteArray())
    } catch (_: RuntimeException) {
        null
    }
}

/** Material You colors (Android 12+): primary, surface and a tinted container for team bubbles. */
internal object SystemDynamicColors {
    fun read(context: Context): DynamicColors? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        fun c(id: Int) = RgbColor.argb(context.getColor(id))
        return DynamicColors(
            accent = PingFrontColor(c(android.R.color.system_accent1_600), c(android.R.color.system_accent1_200)),
            background = PingFrontColor(c(android.R.color.system_neutral1_10), c(android.R.color.system_neutral1_900)),
            teamBubble = PingFrontColor(c(android.R.color.system_neutral2_100), c(android.R.color.system_neutral2_800)),
        )
    }
}

/** Foreground and background transitions from `ProcessLifecycleOwner` (collected on the main thread by `eventFlow`). */
internal fun processLifecycle(): Flow<AppLifecycleEvent> = ProcessLifecycleOwner.get().lifecycle.eventFlow.mapNotNull {
    when (it) {
        Lifecycle.Event.ON_START -> AppLifecycleEvent.Foreground
        Lifecycle.Event.ON_STOP -> AppLifecycleEvent.Background
        else -> null
    }
}

private const val BUNDLE_CHARS = 200
private const val VERSION_CHARS = 40
private const val MODEL_CHARS = 60
private const val SHORT_CHARS = 40
private const val LANGUAGE_CHARS = 20
private const val MIN_CHROMA = 0.12
private const val MIN_BRIGHTNESS = 0.15
private const val ICON_PX = 128
private const val PNG_QUALITY = 100
