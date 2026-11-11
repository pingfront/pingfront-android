package dev.pingfront.internal.runtime

import dev.pingfront.LogEntry
import dev.pingfront.LogHandler
import dev.pingfront.LogLevel

/**
 * The SDK's logger. Values that could identify a person or grant access are never logged:
 * request paths and status codes only, never bodies or tokens. Every line also goes to the host's
 * [LogHandler] when one is set.
 */
internal object Log {
    const val TAG: String = "PingFront"

    /** The SDK area a line is about: [LogEntry.category], the same names as on iOS. */
    enum class Category(val raw: String) {
        Setup("setup"),
        Session("session"),
        Network("network"),
        Live("live"),
        Outbox("outbox"),
        Look("look"),
        Storage("storage"),
        Decoding("decoding"),
        Push("push"),
        Billing("billing"),
        SelfCheck("self-check"),
    }

    /** One area's logger: `private val log = Log.of(Log.Category.Outbox)` at the top of a file, then `log.w("…")`. */
    class Area internal constructor(private val category: Category) {
        fun e(message: String) = e(message, category)

        fun always(message: String) = always(message, category)

        fun w(message: String) = w(message, category)

        fun i(message: String) = i(message, category)

        fun d(message: String) = d(message, category)
    }

    fun of(category: Category): Area = Area(category)

    @Volatile var level: LogLevel = LogLevel.Info

    /** The host's handler (`PingFrontConfig.logHandler`). */
    @Volatile var handler: LogHandler? = null

    /** Replaced in JVM tests, where `android.util.Log` is not available. */
    @Volatile var sink: (priority: Int, message: String) -> Unit = { priority, message ->
        try {
            android.util.Log.println(priority, TAG, message)
        } catch (_: RuntimeException) {
            println("$TAG: $message")
        }
    }

    fun e(message: String, category: Category = Category.Setup) {
        if (level >= LogLevel.Error) write(android.util.Log.ERROR, LogLevel.Error, message, category)
    }

    /** Info that is always printed (the self-check line). */
    fun always(message: String, category: Category = Category.SelfCheck) = write(android.util.Log.INFO, LogLevel.Info, message, category)

    fun w(message: String, category: Category = Category.Setup) {
        if (level >= LogLevel.Info) write(android.util.Log.WARN, LogLevel.Info, message, category)
    }

    fun i(message: String, category: Category = Category.Setup) {
        if (level >= LogLevel.Info) write(android.util.Log.INFO, LogLevel.Info, message, category)
    }

    fun d(message: String, category: Category = Category.Setup) {
        if (level >= LogLevel.Debug) write(android.util.Log.DEBUG, LogLevel.Debug, message, category)
    }

    private fun write(priority: Int, entryLevel: LogLevel, message: String, category: Category) {
        sink(priority, message)
        val handler = handler ?: return
        @Suppress("TooGenericExceptionCaught")
        try {
            handler.log(LogEntry(entryLevel, message, category.raw))
        } catch (_: RuntimeException) {
            // A host handler that throws must never break the SDK.
        }
    }
}
