@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import androidx.test.core.app.ApplicationProvider
import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review AN-5 (3 Nov 2026): `PingFront.teamActions` switched engines on `isConfigured`, a StateFlow. A second
 * `configure` on the main thread (a reconfigure, a React Native reload) set it false and then true before the
 * collector ran, so the conflated flow never changed and the collector stayed on the stopped engine: every later
 * team action was missed and expired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TeamActionsReconfigureTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun config() = PingFrontConfig().apply { baseUrl = ScriptedTransport.BASE_URL }

    @Test fun aCollectorKeepsReceivingTeamActionsAfterAReconfigure() = runTest {
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, config(), testDependencies(ScriptedTransport()))
        val got = mutableListOf<String>()
        val collector = launch { instance.teamActions.take(1).collect { got += it.id } }
        runCurrent()
        instance.start(context, EngineHarness.KEY, config(), testDependencies(ScriptedTransport()))
        runCurrent()
        val action = ServerEvent.decode(
            """{"type":"team_action","action":{"id":"ta_9","actionId":"extend_trial","name":"Extend trial","days":7,""" +
                """"ackRequired":true,"createdAt":"2026-10-31T10:00:00Z","expiresAt":"2026-11-07T10:00:00Z"}}""",
        ) as ServerEvent.TeamAction
        instance.engine!!.teamActions.received(action.action)
        advanceUntilIdle()
        collector.cancel()
        assertEquals(listOf("ta_9"), got, "the collector missed the action after the reconfigure")
        instance.teardown()
    }
}
