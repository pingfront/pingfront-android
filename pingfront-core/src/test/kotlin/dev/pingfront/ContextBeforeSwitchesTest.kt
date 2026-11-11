@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.Engine
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.testing.Bodies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Review AN-24 (3 Nov 2026): device context must never go out for a group the app's privacy switches turn off, also
 * before the first session answer (now part of the SDK's privacy promise). A new install's first `POST /sessions` sent every
 * group, because the switches (`collect`) only arrive with that answer; the server dropped the ones turned off.
 */
class ContextBeforeSwitchesTest {
    private fun JsonObject.context(): JsonObject? = this["device"]?.jsonObject?.get("context")?.jsonObject

    @Test fun aNewInstallSendsNoContextUntilItsSwitchesAreKnown() = runTest {
        val source = FakeContextSource()
        val h = EngineHarness(this, context = source)
        h.transport.json(
            "POST sessions",
            body = Bodies.session().trimEnd().removeSuffix("}") +
                ""","collect":{"build":true,"display":true,"connection":false,"health":true,"notifications":true}}""",
        )
        h.engine.session.token(SessionReason.Prepare)
        advanceUntilIdle()
        val create = WireJson.parseToJsonElement(h.transport.last("POST sessions")!!.body!!.decodeToString()).jsonObject
        assertNull(create.context(), "context before the app's switches were known: ${create.context()}")

        // The next request that goes out anyway carries the allowed groups, as the session start, and never connection.
        h.engine.submit(Engine.Command.Stage(ProfileChange.Traits(mapOf("plan" to "pro"))))
        advanceTimeBy(300)
        runCurrent()
        val patch = WireJson.parseToJsonElement(h.transport.last("PATCH me")!!.body!!.decodeToString()).jsonObject
        val context = assertNotNull(patch.context(), "the first request after the answer carries the allowed groups")
        assertEquals(setOf("build", "display", "health", "notifications"), context.keys)
        h.stop()
    }
}
