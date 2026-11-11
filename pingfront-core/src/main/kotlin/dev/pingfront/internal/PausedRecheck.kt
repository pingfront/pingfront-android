package dev.pingfront.internal

import dev.pingfront.internal.runtime.Timings
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * An open chat notices a pause ending: while the chat is on screen and the app is paused, the thread is loaded again
 * every [Timings.pausedRecheck] (`GET /thread`, whose `policy` says whether the pause ended). The loop stops as soon
 * as the chat hides or the pause ends. Nothing runs while the chat is closed: the unread poll covers a paused app
 * then. It runs in the engine's scope (`UnreadMonitor.run`), so its dispatcher and clock are the injected ones.
 */
internal object PausedRecheck {
    suspend fun watch(visible: Flow<Boolean>, paused: Flow<Boolean>, check: suspend () -> Unit, interval: Duration = Timings.pausedRecheck) {
        combine(visible, paused) { shown, pausedNow -> shown && pausedNow }
            .distinctUntilChanged()
            .collectLatest { watching ->
                while (watching) {
                    delay(interval)
                    check()
                }
            }
    }
}
