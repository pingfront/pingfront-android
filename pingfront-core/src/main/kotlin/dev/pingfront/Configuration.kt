package dev.pingfront

import dev.pingfront.look.ChatAppearance
import dev.pingfront.look.ChatTopic

/** How much the SDK logs. */
public enum class LogLevel {
    /** Nothing, not even errors. */
    Off,

    /** Errors only. */
    Error,

    /** Problems and notable events (default). */
    Info,

    /** Everything, including request paths (never bodies or tokens). */
    Debug,
}

/** One SDK log line, as passed to [LogHandler]. The message never contains ids, names, URLs, tokens or user input. */
@ConsistentCopyVisibility
public data class LogEntry internal constructor(
    /** [LogLevel.Error], [LogLevel.Info] or [LogLevel.Debug]. */
    public val level: LogLevel,
    /** The line. */
    public val message: String,
    /**
     * The SDK area, the same names as on iOS: `setup`, `session`, `network`, `live`, `outbox`, `look`, `storage`,
     * `decoding`, `push`, `billing` or `self-check`. New areas may be added.
     */
    public val category: String = "setup",
)

/** Receives every SDK log line at or above `logLevel`, on any thread. Keep it fast; hand off to your logger. */
public fun interface LogHandler {
    /** Called for each line. */
    public fun log(entry: LogEntry)
}

/** Marks the configuration DSL, so nested blocks cannot reach outer receivers by accident. */
@DslMarker
public annotation class PingFrontDsl

/**
 * Options for `PingFront.configure`. Every option has a default.
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") {
 *     topics(ChatTopic.Billing, ChatTopic.Bug, ChatTopic.Idea)
 *     appearance {
 *         accent = PingFrontColor(0xFF00796B)
 *         corners = Corners.Soft
 *     }
 *     use(Billing, Push, Reports)
 *     logLevel = LogLevel.Debug
 * }
 * ```
 */
@PingFrontDsl
public class PingFrontConfig {
    internal var topicList: List<ChatTopic>? = null
    internal val moduleList = mutableListOf<PingFrontModule>()

    /** Look values set in code. The dashboard look wins; unset values come from the host app, then defaults. */
    public val appearance: ChatAppearance = ChatAppearance()

    /** How much the SDK logs. Default [LogLevel.Info]. */
    public var logLevel: LogLevel = LogLevel.Info

    /**
     * Routes SDK log lines into your own logger, in addition to Logcat:
     * `logHandler = LogHandler { Timber.tag("pingfront").i(it.message) }`. Lines carry no personal data. Default `null`.
     */
    public var logHandler: LogHandler? = null

    /**
     * The chat's language for apps with their own language picker: a BCP 47 tag such as `"de"` or `"pt-BR"`.
     * Default `null`: follow the app's and the system's language. Change it later with `PingFront.language`.
     */
    public var language: String? = null

    /** The API base URL. Keep the default; another URL only points the SDK at a server you test against. */
    public var baseUrl: String = "https://api.pingfront.sh/v1"

    internal var wrapperHeader: String? = null
        private set

    /**
     * For the React Native and Flutter bridges only: the bridge's name and version, sent as `PF-Wrapper`
     * (`react-native/1.0.0`) on every request. Native apps never call it. A value outside the header's pattern
     * (`name` 1 to 40 of `A-Z a-z 0-9 . _ -`, `version` 1 to 20 of those or `+`) is ignored and logged.
     */
    @InternalPingFrontApi
    public fun wrapper(name: String, version: String) {
        val value = "$name/$version"
        if (WRAPPER_PATTERN.matches(value)) {
            wrapperHeader = value
        } else {
            dev.pingfront.internal.runtime.Log.w("Ignored a wrapper that PF-Wrapper does not allow.")
        }
    }

    /** Present the chat when the server answers a moment with `open`. Default `true`. */
    public var presentsChatForMoments: Boolean = true

    /** Topic chips on the welcome screen (up to six). Default: Billing, Report a bug, Ideas. */
    public fun topics(vararg topics: ChatTopic) {
        topicList = topics.toList()
    }

    /** Topic chips on the welcome screen (up to six). */
    public fun topics(topics: List<ChatTopic>) {
        topicList = topics
    }

    /** Sets look values in code. */
    public fun appearance(block: ChatAppearance.() -> Unit) {
        appearance.block()
    }

    /** Adds optional modules, for example `use(Billing, Push, Reports)`. Adding the same module twice has no effect. */
    public fun use(vararg modules: PingFrontModule) {
        for (module in modules) if (moduleList.none { it.id == module.id }) moduleList += module
    }

    /** The modules added with [use], in order. */
    public val modules: List<PingFrontModule> get() = moduleList.toList()

    internal var dependencies: Any? = null
}

/** A configuration problem. In debug builds the chat shows it in a red banner and logs it with `Log.e`. */
@ConsistentCopyVisibility
public data class ConfigurationProblem internal constructor(
    /** What kind of problem. */
    public val kind: Kind,
    /** The exact problem and what to do, in plain words. */
    public val message: String,
    /** True when the SDK disabled itself because of it. */
    public val disablesSdk: Boolean,
) {
    /** What kind of problem. */
    public sealed interface Kind {
        /** The app key failed the offline check. */
        @ConsistentCopyVisibility
        public data class InvalidKey internal constructor(
            /** The problem. */
            public val problem: KeyProblem,
        ) : Kind

        /** No key was passed and `pingfront.app_key` is not in the manifest. */
        public data object MissingKey : Kind

        /** The server does not know the key (found at the first request). */
        public data object UnknownKey : Kind

        /** The app is paused in the dashboard. */
        public data object AppPaused : Kind

        /** Another option is wrong. */
        public data object Option : Kind
    }
}

private val WRAPPER_PATTERN = Regex("^[A-Za-z0-9._-]{1,40}/[A-Za-z0-9.+_-]{1,20}$")
