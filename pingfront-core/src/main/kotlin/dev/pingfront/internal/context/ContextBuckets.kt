package dev.pingfront.internal.context

import dev.pingfront.internal.wire.FreeStorage
import dev.pingfront.internal.wire.InstallSource
import dev.pingfront.internal.wire.MemoryPressure
import dev.pingfront.internal.wire.NetworkKind
import dev.pingfront.internal.wire.ScreenClass
import dev.pingfront.internal.wire.TextSize
import dev.pingfront.internal.wire.Thermal
import kotlin.math.roundToInt

/**
 * The buckets of `DeviceContext`, as pure functions of the platform's raw values (JVM-testable). Exact values never
 * leave the device: storage, battery, scale and font size are coarsened here before anything is sent.
 */
internal object ContextBuckets {
    const val PLAY_STORE: String = "com.android.vending"

    /** System installers that mean "installed from an APK file or adb", not a store. */
    private val SIDELOAD_INSTALLERS = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.shell",
    )
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
    private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$")
    private const val INSTALLER_CHARS = 100
    private const val LANGUAGE_TAG_CHARS = 20
    private const val LANGUAGES = 3
    private const val COMPACT_WIDTH_DP = 600
    private const val SCALE_MIN = 1.0
    private const val SCALE_MAX = 4.0
    private const val LARGE_FONT_SCALE = 1.5f
    private const val GB = 1_000_000_000L
    private const val SMALL_STORAGE = 5 * GB
    private const val LARGE_STORAGE = 20 * GB
    private const val BATTERY_STEP = 10
    private const val PERCENT = 100

    // PowerManager.THERMAL_STATUS_* (API 29): NONE 0, LIGHT 1, MODERATE 2, SEVERE 3, CRITICAL 4, EMERGENCY 5, SHUTDOWN 6.
    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3

    /** The installer's package name to a source; the second value is the installer to send (only for another store). */
    fun installSource(installer: String?): Pair<InstallSource, String?> = when {
        installer.isNullOrBlank() || installer in SIDELOAD_INSTALLERS -> InstallSource.Sideloaded to null
        installer == PLAY_STORE -> InstallSource.PlayStore to null
        else -> InstallSource.OtherStore to installer.takeIf { it.length <= INSTALLER_CHARS && PACKAGE_NAME.matches(it) }
    }

    /** Compact under 600 dp of width, else regular. */
    fun screenClass(widthDp: Int): ScreenClass = if (widthDp < COMPACT_WIDTH_DP) ScreenClass.Compact else ScreenClass.Regular

    /** Density rounded to the nearest 0.5, within 1...4. */
    fun scale(density: Float): Double = ((density * 2).roundToInt() / 2.0).coerceIn(SCALE_MIN, SCALE_MAX)

    /** A font scale up to 1.0 is the default, under 1.5 large, 1.5 and above an accessibility size. */
    fun textSize(fontScale: Float): TextSize = when {
        fontScale <= 1.0f -> TextSize.Default
        fontScale < LARGE_FONT_SCALE -> TextSize.Large
        else -> TextSize.Accessibility
    }

    /** The first three preferred languages as BCP 47 tags the contract accepts, without extensions. */
    fun languages(tags: List<String>): List<String> = tags
        .asSequence()
        .filter { it.isNotEmpty() && it != "und" && it.length <= LANGUAGE_TAG_CHARS && LANGUAGE_TAG.matches(it) }
        .distinct()
        .take(LANGUAGES)
        .toList()

    fun freeStorage(bytes: Long): FreeStorage = when {
        bytes < GB -> FreeStorage.Under1Gb
        bytes < SMALL_STORAGE -> FreeStorage.From1To5Gb
        bytes < LARGE_STORAGE -> FreeStorage.From5To20Gb
        else -> FreeStorage.Over20Gb
    }

    /** The battery level (`EXTRA_LEVEL` of `EXTRA_SCALE`) as a percentage rounded down to 10; `null` when unknown. */
    fun battery(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        val percent = (level * PERCENT / scale).coerceIn(0, PERCENT)
        return percent / BATTERY_STEP * BATTERY_STEP
    }

    /** `PowerManager.currentThermalStatus`: none is nominal, light and moderate fair, severe serious, worse critical. */
    fun thermal(status: Int): Thermal = when {
        status <= 0 -> Thermal.Nominal
        status <= THERMAL_MODERATE -> Thermal.Fair
        status == THERMAL_SEVERE -> Thermal.Serious
        else -> Thermal.Critical
    }

    /** From `ActivityManager.MemoryInfo`: the system's low-memory flag is critical, within twice its threshold a warning. */
    fun memoryPressure(lowMemory: Boolean, availableBytes: Long, thresholdBytes: Long): MemoryPressure = when {
        lowMemory -> MemoryPressure.Critical
        thresholdBytes > 0 && availableBytes <= thresholdBytes * 2 -> MemoryPressure.Warning
        else -> MemoryPressure.Normal
    }

    /** The default network's transports: Wi-Fi first (also under a VPN), then cellular, then Ethernet. */
    fun network(connected: Boolean, wifi: Boolean, cellular: Boolean, ethernet: Boolean): NetworkKind = when {
        !connected -> NetworkKind.None
        wifi -> NetworkKind.Wifi
        cellular -> NetworkKind.Cellular
        ethernet -> NetworkKind.Wired
        else -> NetworkKind.Other
    }
}
