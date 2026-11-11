@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.wire.ServerEvent
import dev.pingfront.testing.Bodies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** A teammate's changed name and photo (Settings, profile) reach the chat without a reinstall. */
class TeammateProfileTest {
    private val before = Bodies.member("m_deniz", "Deniz", "https://cdn.test/deniz-1.jpg")
    private val after = Bodies.member("m_deniz", "Deniz Kaya", "https://cdn.test/deniz-2.jpg")

    @Test fun aReloadedThreadShowsTheNewNameAndPhoto() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(0, team = "[$before]"))
        h.engine.loadThread()
        assertEquals("Deniz", h.instance.conversation.state.value.team.single().name)
        h.transport.json("GET thread", body = Bodies.thread(0, team = "[$after]"))
        h.engine.loadThread()
        val member = h.instance.conversation.state.value.team.single()
        assertEquals("Deniz Kaya", member.name)
        assertEquals("https://cdn.test/deniz-2.jpg", member.avatarUrl)
        h.stop()
    }

    @Test fun aConfigPushUpdatesTheTeammateInAnOpenThread() = runTest {
        val h = EngineHarness(this)
        h.transport.json("GET thread", body = Bodies.thread(0, team = "[$before]"))
        h.engine.loadThread()
        h.transport.json("GET config", body = Bodies.config(revision = 2, team = "[$after]"))
        h.engine.apply(ServerEvent.Config(revision = 2, draft = false))
        advanceUntilIdle()
        val member = h.instance.conversation.state.value.team.single()
        assertEquals("Deniz Kaya", member.name)
        assertEquals("https://cdn.test/deniz-2.jpg", member.avatarUrl)
        h.stop()
    }
}
