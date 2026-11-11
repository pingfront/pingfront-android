package dev.pingfront.internal.wire

import kotlinx.serialization.Serializable

// Mirrors of `DeviceContext` and its groups, and `ContextCollection` (the API's OpenAPI
// contract, the protocol, "Person context"). Every field is optional; enums decode values this SDK does not know
// to `Unknown` and the SDK never sends `Unknown` (WireRules drops it).

/** What the SDK knows about the device right now, in coarse buckets. Sent only with requests the SDK makes anyway. */
@Serializable
internal data class DeviceContext(
    val build: BuildContext? = null,
    val display: DisplayContext? = null,
    val connection: ConnectionContext? = null,
    val health: HealthContext? = null,
    val notifications: NotificationContext? = null,
) {
    val isEmpty: Boolean get() = build == null && display == null && connection == null && health == null && notifications == null

    /** The groups of [other] that are present replace the groups here. */
    fun merging(other: DeviceContext): DeviceContext = DeviceContext(
        build = other.build ?: build,
        display = other.display ?: display,
        connection = other.connection ?: connection,
        health = other.health ?: health,
        notifications = other.notifications ?: notifications,
    )
}

@Serializable
internal data class BuildContext(
    val installSource: InstallSource? = null,
    /** The installer's package name, only with [InstallSource.OtherStore]. */
    val installer: String? = null,
    val debuggable: Boolean? = null,
)

@Serializable
internal data class DisplayContext(
    val screenClass: ScreenClass? = null,
    val scale: Double? = null,
    val darkMode: Boolean? = null,
    val textSize: TextSize? = null,
    val screenReader: Boolean? = null,
    val reduceMotion: Boolean? = null,
    val boldText: Boolean? = null,
    val languages: List<String>? = null,
)

@Serializable
internal data class ConnectionContext(
    val network: NetworkKind? = null,
    val constrained: Boolean? = null,
    val expensive: Boolean? = null,
    val lowPower: Boolean? = null,
)

@Serializable
internal data class HealthContext(
    val freeStorage: FreeStorage? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val thermal: Thermal? = null,
    val memoryPressure: MemoryPressure? = null,
)

@Serializable
internal data class NotificationContext(
    val authorization: NotificationAuthorization? = null,
    /** pingfront's own notification channel is enabled (absent while the channel does not exist). */
    val channelEnabled: Boolean? = null,
)

/**
 * Which groups the app collects (`CreateSessionResponse.collect`, `RefreshSessionResponse.collect`). The contract
 * requires every flag; a missing one decodes as collected, the same as an absent `collect` from an older server.
 */
@Serializable
internal data class ContextCollection(
    val build: Boolean = true,
    val display: Boolean = true,
    val connection: Boolean = true,
    val health: Boolean = true,
    val notifications: Boolean = true,
) {
    companion object {
        /** From servers that do not send `collect` (before an install's first answer nothing is sent). */
        val All: ContextCollection = ContextCollection()
    }
}

@Serializable(with = InstallSource.Serializer::class)
internal enum class InstallSource {
    AppStore,
    TestFlight,
    Development,
    PlayStore,
    OtherStore,
    Sideloaded,
    Other,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<InstallSource>(
        "InstallSource",
        mapOf(
            AppStore to "app_store",
            TestFlight to "testflight",
            Development to "development",
            PlayStore to "play_store",
            OtherStore to "other_store",
            Sideloaded to "sideloaded",
            Other to "other",
            Unknown to UNKNOWN_WIRE,
        ),
        Unknown,
    )
}

@Serializable(with = ScreenClass.Serializer::class)
internal enum class ScreenClass {
    Compact,
    Regular,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<ScreenClass>(
        "ScreenClass",
        mapOf(Compact to "compact", Regular to "regular", Unknown to UNKNOWN_WIRE),
        Unknown,
    )
}

@Serializable(with = TextSize.Serializer::class)
internal enum class TextSize {
    Default,
    Large,
    Accessibility,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<TextSize>(
        "TextSize",
        mapOf(Default to "default", Large to "large", Accessibility to "accessibility", Unknown to UNKNOWN_WIRE),
        Unknown,
    )
}

@Serializable(with = NetworkKind.Serializer::class)
internal enum class NetworkKind {
    Wifi,
    Cellular,
    Wired,
    None,
    Other,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<NetworkKind>(
        "NetworkKind",
        mapOf(Wifi to "wifi", Cellular to "cellular", Wired to "wired", None to "none", Other to "other", Unknown to UNKNOWN_WIRE),
        Unknown,
    )
}

@Serializable(with = FreeStorage.Serializer::class)
internal enum class FreeStorage {
    Under1Gb,
    From1To5Gb,
    From5To20Gb,
    Over20Gb,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<FreeStorage>(
        "FreeStorage",
        mapOf(
            Under1Gb to "under_1gb",
            From1To5Gb to "from_1_to_5gb",
            From5To20Gb to "from_5_to_20gb",
            Over20Gb to "over_20gb",
            Unknown to UNKNOWN_WIRE,
        ),
        Unknown,
    )
}

@Serializable(with = Thermal.Serializer::class)
internal enum class Thermal {
    Nominal,
    Fair,
    Serious,
    Critical,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<Thermal>(
        "Thermal",
        mapOf(Nominal to "nominal", Fair to "fair", Serious to "serious", Critical to "critical", Unknown to UNKNOWN_WIRE),
        Unknown,
    )
}

@Serializable(with = MemoryPressure.Serializer::class)
internal enum class MemoryPressure {
    Normal,
    Warning,
    Critical,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<MemoryPressure>(
        "MemoryPressure",
        mapOf(Normal to "normal", Warning to "warning", Critical to "critical", Unknown to UNKNOWN_WIRE),
        Unknown,
    )
}

@Serializable(with = NotificationAuthorization.Serializer::class)
internal enum class NotificationAuthorization {
    Authorized,
    Provisional,
    Ephemeral,
    Denied,
    NotDetermined,
    Unknown,
    ;

    object Serializer : TolerantEnumSerializer<NotificationAuthorization>(
        "NotificationAuthorization",
        mapOf(
            Authorized to "authorized",
            Provisional to "provisional",
            Ephemeral to "ephemeral",
            Denied to "denied",
            NotDetermined to "not_determined",
            Unknown to UNKNOWN_WIRE,
        ),
        Unknown,
    )
}
