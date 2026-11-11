@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.conversation.LiveState
import dev.pingfront.internal.messaging.LiveConnection
import dev.pingfront.internal.net.HttpResponse
import dev.pingfront.internal.wire.AppConfig
import dev.pingfront.internal.wire.ClientEvent
import dev.pingfront.look.ChatAppearance
import dev.pingfront.look.ChatTopic
import dev.pingfront.look.Contrast
import dev.pingfront.look.DynamicColors
import dev.pingfront.look.HostAppInfo
import dev.pingfront.look.PngImage
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.ChatLook
import dev.pingfront.model.Corners
import dev.pingfront.model.FontStyle
import dev.pingfront.model.LocalizedText
import dev.pingfront.model.PingFrontColor
import dev.pingfront.model.RgbColor
import dev.pingfront.model.TopicSymbol
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.FakeSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LiveConnectionTest {
    @Test fun stopDuringTheTokenFetchNeverOpensASocket() = runTest {
        val h = EngineHarness(this)
        val gate = CompletableDeferred<Unit>()
        h.transport.on("POST sessions") {
            gate.await()
            HttpResponse(200, emptyMap(), Bodies.session().encodeToByteArray())
        }
        h.engine.live.start("wss://api.test/v1/live")
        runCurrent()
        h.engine.live.stop()
        gate.complete(Unit)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.transport.sockets.isEmpty())
        assertFalse(h.engine.live.isRunning())
        h.stop()
    }

    @Test fun reauthCountsAsAnAttemptAndBacksOff() = runTest {
        val h = EngineHarness(this)
        h.transport.json("POST sessions/refresh", body = Bodies.refresh())
        val sockets = mutableListOf<FakeSocket>()
        h.transport.socketFactory = { _, _ ->
            FakeSocket().also {
                sockets += it
                it.push("""{"type":"reauth"}""")
            }
        }
        h.engine.live.start("wss://api.test/v1/live")
        runCurrent()
        assertEquals(1, sockets.size)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, sockets.size, "no immediate reconnect after reauth")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, sockets.size)
        advanceTimeBy(1_998)
        runCurrent()
        assertEquals(2, sockets.size, "the second wait is 2 s")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, sockets.size)
        h.engine.live.stop()
        h.stop()
    }

    @Test fun close4001RefreshesAndReconnects() = runTest {
        val h = EngineHarness(this)
        h.transport.json("POST sessions/refresh", body = Bodies.refresh("tok-new"))
        val protocols = mutableListOf<List<String>>()
        h.transport.socketFactory = { _, p ->
            FakeSocket().also {
                protocols += p
                if (protocols.size == 1) it.serverClose(4001)
            }
        }
        h.engine.live.start("wss://api.test/v1/live")
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("bearer.tok-new", protocols.last().last())
        h.engine.live.stop()
        h.stop()
    }

    @Test fun aFailedHeartbeatEndsTheConnection() = runTest {
        val h = EngineHarness(this)
        val socket = FakeSocket().apply { sendSucceeds = false }
        h.transport.socketFactory = { _, _ -> socket }
        val states = mutableListOf<LiveState>()
        h.engine.scope.launch { h.engine.live.signals.collect { if (it is LiveConnection.Signal.State) states += it.state } }
        h.engine.live.start("wss://api.test/v1/live")
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(socket.closed)
        assertTrue(socket.sent.contains(ClientEvent.Ping.encode()))
        assertTrue(states.any { it is LiveState.Waiting })
        h.engine.live.stop()
        h.stop()
    }
}

class LookTest {
    private fun remote(look: ChatLook) = AppConfig(revision = 2, appName = "Remote", look = look)

    @Test fun defaultsWhenNothingIsSet() {
        val look = ResolvedLook.Default
        assertEquals(Corners.Round, look.corners)
        assertEquals(20, look.bubbleRadius)
        assertEquals("Assistant", look.aiName)
        assertEquals(ChatTopic.Defaults, look.topics)
    }

    @Test fun precedenceRemoteThenCodeThenHostThenDefaults() {
        val code = ChatAppearance().apply {
            corners = Corners.Soft
            font = FontStyle.Serif
            accent = PingFrontColor("#6750A4")
        }
        val host = HostAppInfo("Host", PingFrontColor("#00796B"), null, "1.0 (1)")
        val remoteLook = ChatLook(theme = ChatLook.Theme(corners = Corners.Square))
        val resolved = ResolvedLook.resolve(remote(remoteLook), code, null, host, null)
        assertEquals(Corners.Square, resolved.corners, "remote wins")
        assertEquals(FontStyle.Serif, resolved.font, "code fills what remote left unset")
        assertEquals("Remote", resolved.appName)
        val hostOnly = ResolvedLook.resolve(null, ChatAppearance(), null, host, null)
        assertEquals(Contrast.accentAdjusted(RgbColor.hex("#00796B")!!), hostOnly.accent.light, "host accent when code sets none")
        assertEquals("Host", hostOnly.appName)
    }

    @Test fun appIconIsTheLauncherIconWithOrWithoutAStoreIcon() {
        // The launcher icon read from PackageManager (HostAppInfo.icon) is the app's icon in the welcome and the
        // header; the dashboard's store icon URL stays beside it as the fallback when the package has none.
        val launcher = PngImage(byteArrayOf(1, 2, 3))
        val host = HostAppInfo("Host", null, launcher, "1.0 (1)")
        val noStore = ResolvedLook.resolve(remote(ChatLook()), ChatAppearance(), null, host, null)
        assertEquals(launcher, noStore.appIcon)
        assertEquals(null, noStore.appIconUrl)
        val store = remote(ChatLook()).copy(appIconUrl = "https://is1-ssl.mzstatic.com/x/512x512bb.png")
        val withStore = ResolvedLook.resolve(store, ChatAppearance(), null, host, null)
        assertEquals(launcher, withStore.appIcon)
        assertEquals("https://is1-ssl.mzstatic.com/x/512x512bb.png", withStore.appIconUrl)
    }

    @Test fun dynamicColorIsOptInAndBelowExplicitCode() {
        val dynamic = DynamicColors(PingFrontColor("#8F4C38"), PingFrontColor("#FFF8F6", "#1A110F"), PingFrontColor("#F5DED8", "#3B2D29"))
        assertFalse(
            ResolvedLook.resolve(null, ChatAppearance(), null, null, dynamic).accent.light ==
                Contrast.accentAdjusted(RgbColor.hex("#8F4C38")!!),
        )
        val on = ResolvedLook.resolve(null, ChatAppearance().apply { useDynamicColor = true }, null, null, dynamic)
        assertEquals(Contrast.accentAdjusted(RgbColor.hex("#8F4C38")!!), on.accent.light)
    }

    @Test fun emptyRemoteTopicsFallBackAndFeatureFlagsAndToggles() {
        val codeTopics = listOf(ChatTopic.Gift)
        assertEquals(codeTopics, ResolvedLook.resolve(remote(ChatLook()), ChatAppearance(), codeTopics, null, null).topics)
        val photosOff = remote(ChatLook()).copy(features = AppConfig.Features(photos = false))
        assertFalse(ResolvedLook.resolve(photosOff, ChatAppearance().apply { photos = true }, null, null, null).photos)
        val sevenTopics = (1..7).map { ChatTopic("t$it", "T$it") }
        assertEquals(6, ResolvedLook.resolve(null, ChatAppearance(), sevenTopics, null, null).topics.size)
    }

    @Test fun localizedFallback() {
        val text = LocalizedText(mapOf("default" to "Hi", "pt" to "Olá", "pt-BR" to "Oi", "de" to "Hallo"))
        assertEquals("Oi", text.resolve("pt-BR"))
        assertEquals("Oi", text.resolve("pt_br"))
        assertEquals("Olá", text.resolve("pt-PT"))
        assertEquals("Hallo", text.resolve("de-AT"))
        assertEquals("Hi", text.resolve("ja"))
    }

    @Test fun contrastPicking() {
        assertEquals(RgbColor.Black, Contrast.bestText(RgbColor.hex("#FFCE3A")!!))
        assertEquals(RgbColor.White, Contrast.bestText(RgbColor.hex("#3550E6")!!))
        val hostile = listOf("#FFFF00", "#00FF00", "#00FFFF", "#FF00FF", "#FFE066", "#777777", "#FF8A3D", "#4DB6AC", "#C0C0C0", "#0000FF")
        for (hex in hostile) {
            val look = ResolvedLook.resolve(
                remote(
                    ChatLook(
                        theme = ChatLook.Theme(
                            accent = PingFrontColor(hex),
                            background = PingFrontColor(hex),
                            teamBubble = PingFrontColor(hex),
                        ),
                    ),
                ),
                ChatAppearance(),
                null,
                null,
                null,
            )
            for (dark in listOf(false, true)) {
                assertTrue(RgbColor.contrast(look.onAccent.value(dark), look.accent.value(dark)) >= 4.5, "$hex accent dark=$dark")
                assertTrue(RgbColor.contrast(look.text.value(dark), look.background.value(dark)) >= 4.5, "$hex background dark=$dark")
                assertTrue(RgbColor.contrast(look.text.value(dark), look.teamBubble.value(dark)) >= 4.5, "$hex bubble dark=$dark")
            }
        }
    }

    @Test fun topicPresetsIncludeGift() = assertEquals(TopicSymbol.Gift, ChatTopic.Gift.symbol)
}
