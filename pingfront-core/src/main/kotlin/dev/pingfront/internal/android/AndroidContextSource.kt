package dev.pingfront.internal.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.pingfront.internal.context.ContextBuckets
import dev.pingfront.internal.context.ContextSource
import dev.pingfront.internal.context.NetworkSample
import dev.pingfront.internal.wire.BuildContext
import dev.pingfront.internal.wire.ConnectionContext
import dev.pingfront.internal.wire.DisplayContext
import dev.pingfront.internal.wire.HealthContext
import dev.pingfront.internal.wire.NotificationAuthorization
import dev.pingfront.internal.wire.NotificationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reads `DeviceContext` from the platform. Constructing it reads nothing; every reader is a cheap local
 * call (no network, no waiting) made only while the SDK builds a request it sends anyway. Values are
 * bucketed by [ContextBuckets] and never logged.
 */
internal class AndroidContextSource(
    context: Context,
    /** The push module posts to its badge channel while the dashboard's app badge is on. */
    private val badgeChannel: () -> Boolean,
) : ContextSource {
    private val app = context.applicationContext

    // Where the build came from does not change while the process runs.
    private val build: BuildContext by lazy { readBuild() }

    override fun build(): BuildContext = build

    private fun readBuild(): BuildContext {
        val (source, installer) = ContextBuckets.installSource(installerPackage())
        return BuildContext(
            installSource = source,
            installer = installer,
            debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    private fun installerPackage(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            legacyInstaller()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // getInstallerPackageName is deprecated from API 30, where getInstallSourceInfo above replaces it; below
    // API 30 it is the only way to read the installer.
    @Suppress("DEPRECATION")
    private fun legacyInstaller(): String? = app.packageManager.getInstallerPackageName(app.packageName)

    override fun display(): DisplayContext {
        val configuration = app.resources.configuration
        val accessibility = app.getSystemService(AccessibilityManager::class.java)
        val animatorScale = Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val bold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val adjustment = configuration.fontWeightAdjustment
            adjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED && adjustment > 0
        } else {
            null
        }
        val locales = LocaleList.getDefault()
        val tags = (0 until locales.size()).map { locales.get(it).stripExtensions().toLanguageTag() }
        return DisplayContext(
            screenClass = ContextBuckets.screenClass(configuration.screenWidthDp),
            scale = ContextBuckets.scale(app.resources.displayMetrics.density),
            darkMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
            textSize = ContextBuckets.textSize(configuration.fontScale),
            // TalkBack and other screen readers turn on touch exploration.
            screenReader = accessibility?.isTouchExplorationEnabled,
            reduceMotion = animatorScale == 0f,
            boldText = bold,
            languages = ContextBuckets.languages(tags).takeIf { it.isNotEmpty() },
        )
    }

    override fun connection(): ConnectionContext? {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return null
        return connection(manager, manager.activeNetwork?.let { manager.getNetworkCapabilities(it) })
    }

    private fun connection(manager: ConnectivityManager, capabilities: NetworkCapabilities?): ConnectionContext {
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return ConnectionContext(
            network = ContextBuckets.network(
                connected = connected,
                wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
                ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            ),
            constrained = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            expensive = capabilities?.takeIf { connected }?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) },
            lowPower = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode,
        )
    }

    override fun networkChanges(): Flow<NetworkSample> = callbackFlow {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            close()
            return@callbackFlow
        }
        fun sample(network: Network?, capabilities: NetworkCapabilities?) = NetworkSample(
            online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            network = network?.networkHandle,
            captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        ) { connection(manager, capabilities) }
        // The state when the callback is registered (with no default network, no callback method is called).
        val active = manager.activeNetwork
        trySend(sample(active, active?.let { manager.getNetworkCapabilities(it) }))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(sample(network, networkCapabilities))
            }

            // The default network is gone; a new one, if any, arrives through onCapabilitiesChanged.
            override fun onLost(network: Network) {
                trySend(sample(null, null))
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }

    override fun health(): HealthContext {
        // The sticky battery broadcast: no receiver is registered, the last value is returned.
        val battery: Intent? = ContextCompat.registerReceiver(
            app,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = app.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
            ContextBuckets.thermal(power.currentThermalStatus)
        } else {
            null
        }
        val memory = app.getSystemService(ActivityManager::class.java)?.let { manager ->
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            ContextBuckets.memoryPressure(info.lowMemory, info.availMem, info.threshold)
        }
        return HealthContext(
            freeStorage = ContextBuckets.freeStorage(StatFs(app.filesDir.path).availableBytes),
            battery = battery?.let {
                ContextBuckets.battery(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
            },
            charging = battery?.let { status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL },
            thermal = thermal,
            memoryPressure = memory,
        )
    }

    override fun notifications(): NotificationContext {
        val manager = NotificationManagerCompat.from(app)
        // Android has no "not asked yet" an app can read without an activity, so only authorized or denied.
        val enabled = manager.areNotificationsEnabled()
        val channel = manager.getNotificationChannel(if (badgeChannel()) BADGE_CHANNEL else CHANNEL)
        return NotificationContext(
            authorization = if (enabled) NotificationAuthorization.Authorized else NotificationAuthorization.Denied,
            channelEnabled = channel?.let { it.importance != NotificationManagerCompat.IMPORTANCE_NONE },
        )
    }

    private companion object {
        // The channels pingfront-push creates (PingFrontPush.CHANNEL and BADGE_CHANNEL); core cannot depend on push.
        const val CHANNEL = "pingfront_messages"
        const val BADGE_CHANNEL = "pingfront_messages_badge"
    }
}
