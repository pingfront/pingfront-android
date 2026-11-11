@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.Engine
import dev.pingfront.internal.EngineSettings
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** `PF-Wrapper`: a bridge's name and version on every request; never sent by a native app. */
class WrapperHeaderTest {
    private suspend fun TestScope.requests(wrapper: String?): ScriptedTransport {
        val transport = ScriptedTransport()
        transport.json("POST sessions", body = Bodies.session())
        transport.json("GET config", body = Bodies.config())
        transport.json("PATCH me", body = """{"person":{"id":"p_1","level":"guest"}}""")
        transport.json("GET thread", body = Bodies.thread(0))
        val engine = Engine(
            PingFrontInstance(),
            AppKey.parse(EngineHarness.KEY).getOrThrow(),
            EngineSettings(ScriptedTransport.BASE_URL, verbose = false, presentsChatForMoments = true, wrapper = wrapper),
            testDependencies(transport),
        )
        engine.start()
        engine.session.token(SessionReason.ChatOpened)
        engine.loadThread()
        advanceUntilIdle()
        engine.stop()
        return transport
    }

    @Test fun aBridgeSendsItsWrapperOnEveryRequest() = runTest {
        val transport = requests("react-native/1.0.0")
        assertTrue(transport.requests.size >= 2)
        transport.requests.forEach { assertEquals("react-native/1.0.0", it.headers["PF-Wrapper"], "${it.method} ${it.url}") }
    }

    @Test fun aNativeAppNeverSendsIt() = runTest {
        val transport = requests(null)
        transport.requests.forEach { assertNull(it.headers["PF-Wrapper"]) }
    }

    @Test fun theConfigurationAcceptsOnlyTheHeadersPattern() {
        val config = PingFrontConfig()
        config.wrapper("flutter", "1.2.0+3")
        assertEquals("flutter/1.2.0+3", config.wrapperHeader)
        config.wrapper("react native", "1.0.0")
        assertEquals("flutter/1.2.0+3", config.wrapperHeader, "a name with a space is ignored")
        assertNull(PingFrontConfig().wrapperHeader)
    }
}
