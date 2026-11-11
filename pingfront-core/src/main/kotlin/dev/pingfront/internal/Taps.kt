package dev.pingfront.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren

/**
 * What the person tapped and the SDK still waits on: card actions, and links waiting for the app's `onLink` or
 * `onScreen`. A child of the engine's scope, so stopping the engine cancels them; logout, erase and a revoked session
 * cancel them too ([cancelAll]), so no tap outlives the person who made it, as on iOS.
 */
internal class Taps(engineScope: CoroutineScope) {
    private val jobs = SupervisorJob(engineScope.coroutineContext[Job])

    val scope: CoroutineScope = CoroutineScope(engineScope.coroutineContext + jobs)

    fun cancelAll() = jobs.cancelChildren()
}
