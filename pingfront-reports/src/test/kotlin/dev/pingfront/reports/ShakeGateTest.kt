@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront.reports

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Review CS-10 / CQ-H5, ported from the reviewer's repro: the shake listener was registered on ON_START and
 * cancelled by the ON_RESUME that always follows, so shaking never opened the report.
 */
class ShakeGateTest {
    /** The events a real LifecycleRegistry dispatches when the process comes to the foreground and goes back. */
    private fun dispatched(vararg states: Lifecycle.State): List<Lifecycle.Event> {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val events = mutableListOf<Lifecycle.Event>()
        owner.registry.addObserver(LifecycleEventObserver { _, event -> events += event })
        states.forEach { owner.registry.currentState = it }
        return events
    }

    @Test fun shakeListenerStaysRegisteredInTheForeground() = runTest {
        val events = MutableSharedFlow<Lifecycle.Event>(replay = 16)
        var listeners = 0
        var shakes = 0
        val shake = callbackFlow<Unit> {
            listeners += 1
            trySend(Unit)
            awaitClose { listeners -= 1 }
        }
        val job = launch { ShakeGate.run(events, shake) { shakes += 1 } }
        dispatched(Lifecycle.State.RESUMED).forEach { events.emit(it) }
        runCurrent()
        assertEquals(1, listeners, "the sensor listener is registered while resumed")
        assertEquals(1, shakes, "and a shake opens the report")
        dispatched(Lifecycle.State.RESUMED, Lifecycle.State.CREATED).drop(3).forEach { events.emit(it) }
        runCurrent()
        assertEquals(0, listeners, "in the background (ON_STOP) the listener is removed")
        job.cancel()
    }
}
