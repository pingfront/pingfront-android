package dev.pingfront.internal

import dev.pingfront.internal.context.ContextSource
import dev.pingfront.internal.net.HttpTransport
import dev.pingfront.internal.runtime.AppLifecycleEvent
import dev.pingfront.internal.runtime.SdkDispatchers
import dev.pingfront.internal.runtime.TimeSource
import dev.pingfront.internal.storage.Persistence
import dev.pingfront.internal.storage.SignedOutFlag
import dev.pingfront.internal.wire.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Schedules a retry of the outbox when the network returns (WorkManager in production). */
internal interface RetryScheduler {
    fun scheduleOutboxRetry()

    fun cancel()

    object None : RetryScheduler {
        override fun scheduleOutboxRetry() = Unit

        override fun cancel() = Unit
    }
}

/** The injectable seams. Tests replace the network, storage, clock, dispatchers and lifecycle. */
internal class Dependencies(
    val transport: HttpTransport,
    val persistence: Persistence,
    val time: TimeSource,
    val dispatchers: SdkDispatchers,
    val device: (installId: String) -> Device,
    val lifecycle: Flow<AppLifecycleEvent> = emptyFlow(),
    val retry: RetryScheduler = RetryScheduler.None,
    val languageTag: () -> String = { java.util.Locale.getDefault().toLanguageTag() },
    val jitter: () -> Double = { kotlin.random.Random.nextDouble(-1.0, 1.0) },
    /**
     * Whether the app counts as foreground before the first lifecycle event. Production: `false`, because
     * `ProcessLifecycleOwner` replays `ON_START` when the app is already started (a process woken by FCM is not).
     */
    val startsInForeground: Boolean = true,
    /** Reads `DeviceContext` (the platform in production). Read only while building a request sent anyway. */
    val context: ContextSource = ContextSource.None,
    /** The persisted signed-out flag the push gate reads (a file in production). */
    val signedOut: SignedOutFlag = SignedOutFlag.Memory(),
)
