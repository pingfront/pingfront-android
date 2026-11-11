@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import dev.pingfront.internal.Engine
import dev.pingfront.internal.session.ProfileChange
import dev.pingfront.internal.storage.RecordStore
import dev.pingfront.internal.storage.StoredRecords
import dev.pingfront.internal.wire.InstallCredentials
import dev.pingfront.internal.wire.SessionReason
import dev.pingfront.internal.wire.WireJson
import dev.pingfront.internal.wire.WireRules
import dev.pingfront.look.ChatTopic
import dev.pingfront.model.Card
import dev.pingfront.model.Message
import dev.pingfront.model.MessagePart
import dev.pingfront.testing.Bodies
import dev.pingfront.testing.MemoryPersistence
import dev.pingfront.testing.ScriptedTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun ScriptedTransport.body(route: String): JsonObject? = last(route)?.body?.decodeToString()?.let { WireJson.parseToJsonElement(it).jsonObject }

private fun JsonObject.screens(): JsonArray? = this["device"]?.jsonObject?.get("app")?.jsonObject?.get("screens") as? JsonArray

private val SCREENS = listOf(AppScreen("settings.sound", "Sound settings"), AppScreen("plans/yearly"))

/** the protocol, "Saved links": the screens registry the SDK sends, and what a link card's tap reports. */
class SavedLinksRegistryTest {
    @Test fun registeringChecksIdsKeepsEachOnceAndAtMost200() {
        val instance = PingFrontInstance()
        val many = (0 until 210).map { AppScreen("s$it") }
        instance.registerScreens(listOf(AppScreen("bad id"), AppScreen("a", "  "), AppScreen("a", "again"), AppScreen("-x")) + many)
        val kept = assertNotNull(instance.screens)
        assertEquals(200, kept.size, "at most 200")
        assertEquals(AppScreen("a", null), kept.first(), "the first of a repeated id, a blank title dropped")
        assertTrue(kept.none { it.id == "bad id" || it.id == "-x" }, "a release build drops bad ids")
        assertTrue(instance.isScreenRegistered("s0"))
        assertTrue(!instance.isScreenRegistered("bad id"))
        assertEquals(AppScreen("t", "x".repeat(60)), ScreenRules.clean(listOf(AppScreen("t", "x".repeat(70)))).screens.single())
    }

    @Test fun aDebugBuildFailsLoudlyOnABadId() {
        val instance = PingFrontInstance()
        dev.pingfront.internal.debug.DebugGate.set(true)
        try {
            kotlin.test.assertFailsWith<IllegalArgumentException> { instance.registerScreens(listOf(AppScreen("ok"), AppScreen("not ok"))) }
            instance.registerScreens(listOf(AppScreen("ok")))
            assertEquals(listOf(AppScreen("ok")), instance.screens)
        } finally {
            dev.pingfront.internal.debug.DebugGate.set(false)
        }
    }

    @Test fun nothingIsSentAtLaunchAndTheCreateCarriesTheList() = runTest {
        val h = EngineHarness(this)
        h.instance.registerScreens(SCREENS)
        advanceUntilIdle()
        assertTrue(h.transport.requests.isEmpty(), "registering sends nothing")
        h.engine.session.token(SessionReason.Prepare)
        val body = assertNotNull(h.transport.body("POST sessions"))
        val screens = assertNotNull(body.screens())
        assertEquals(listOf("settings.sound", "plans/yearly"), screens.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals("Sound settings", screens[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertNull(screens[1].jsonObject["title"], "an absent title is left out")
        assertEquals(emptyList(), WireRules.problems("CreateSessionRequest", body))
        h.stop()
    }

    @Test fun anAppThatRegistersNoneSendsNoField() = runTest {
        val h = EngineHarness(this)
        h.engine.session.token(SessionReason.Prepare)
        assertNull(h.transport.body("POST sessions")!!.screens())
        h.stop()
    }

    private suspend fun knownInstall(): MemoryPersistence = MemoryPersistence().also {
        RecordStore(it).write(StoredRecords.credentials, InstallCredentials("8a1f6f2e-1111-4222-8333-944455556666", "s"))
    }

    private fun TestScope.patch(h: EngineHarness, trait: String) {
        h.engine.submit(Engine.Command.Stage(ProfileChange.Traits(mapOf("t" to trait))))
        advanceTimeBy(300)
        runCurrent()
    }

    @Test fun aRefreshCarriesTheListOnlyWhenItChanged() = runTest {
        val persistence = knownInstall()
        val first = EngineHarness(this, persistence = persistence)
        first.instance.registerScreens(SCREENS)
        first.transport.json("POST sessions/refresh", body = Bodies.refresh())
        first.engine.session.token(SessionReason.Prepare)
        assertEquals(2, first.transport.body("POST sessions/refresh")!!.screens()?.size, "never delivered by this install")
        advanceUntilIdle()
        first.stop()

        // A new process with the same list: the digest kept with the install says the server has it.
        val second = EngineHarness(this, persistence = persistence)
        second.instance.registerScreens(SCREENS)
        second.transport.json("POST sessions/refresh", body = Bodies.refresh())
        second.engine.session.token(SessionReason.Prepare)
        assertNull(second.transport.body("POST sessions/refresh")!!.screens(), "unchanged: not sent again")
        advanceUntilIdle()

        // A changed list never sends a request of its own; it rides on the next PATCH.
        second.instance.registerScreens(SCREENS + AppScreen("help"))
        advanceUntilIdle()
        assertEquals(0, second.transport.count("PATCH me"))
        patch(second, "1")
        advanceUntilIdle()
        val patched = second.transport.body("PATCH me")!!
        assertEquals(3, patched.screens()?.size)
        assertEquals(emptyList(), WireRules.problems("UpdateMeRequest", patched))
        patch(second, "2")
        advanceUntilIdle()
        assertNull(second.transport.body("PATCH me")!!.screens(), "delivered once")
        second.stop()
    }

    @Test fun logoutForgetsTheDigest() = runTest {
        val persistence = knownInstall()
        RecordStore(persistence).write(StoredRecords.sentScreensDigest, "abc")
        val h = EngineHarness(this, persistence = persistence)
        h.engine.session.wipe()
        assertNull(RecordStore(persistence).read(StoredRecords.sentScreensDigest))
        h.stop()
    }
}

class SavedLinkTapTest {
    @kotlin.test.AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()

    private class Presenter(var opens: Boolean = true) : ChatPresenter {
        val urls = mutableListOf<String>()

        override fun presentChat(topic: ChatTopic?) = Unit

        override fun dismissChat() = Unit

        override fun openUrl(url: String) {
            urls += url
        }

        override fun openLink(url: String): Boolean {
            urls += url
            return opens
        }

        override fun presentSimpleReport(prefill: String?) = Unit
    }

    private fun card(screen: String?, url: String, params: String = """{"tab":"yearly"}"""): Card.Link {
        val screenJson = screen?.let { ""","screen":{"id":"$it","params":$params}""" }.orEmpty()
        val json = """{"kind":"link","id":"l1","title":"Plans","url":"$url","button":"Open","savedLinkId":"sl_1"$screenJson,"future":1}"""
        return WireJson.decodeFromString(Card.serializer(), json) as Card.Link
    }

    private class Setup(val h: EngineHarness, val presenter: Presenter, val message: Message)

    private suspend fun TestScope.setup(): Setup {
        // The handlers run on the main dispatcher.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = EngineHarness(this)
        val presenter = Presenter()
        h.instance.registry.chatPresenter = presenter
        h.instance.registerScreens(SCREENS)
        h.transport.json("GET thread", body = Bodies.thread(1, Bodies.message(1)))
        h.engine.loadThread()
        h.transport.json("POST thread/cards/l1/act", body = "{}")
        return Setup(h, presenter, h.instance.conversation.state.value.messages.first())
    }

    private fun JsonObject.action() = this["action"]!!.jsonPrimitive.content

    private fun JsonObject.failure() = this["failure"]?.jsonPrimitive?.content

    @Test fun theCardDecodesScreenAndSavedLinkId() {
        val card = card("settings.sound", "")
        assertEquals("settings.sound", card.screen?.id)
        assertEquals(mapOf("tab" to "yearly"), card.screen?.params)
        assertEquals("sl_1", card.savedLinkId)
        val plain = """{"type":"card","card":{"kind":"link","id":"l2","title":"t","url":"https://x.com","button":"b"}}"""
        val part = WireJson.decodeFromString(MessagePart.serializer(), plain)
        assertNull(((part as MessagePart.CardPart).card as Card.Link).screen, "a card without screen is as before")
        val badJson = """{"kind":"link","id":"l3","title":"t","url":"https://x.com","button":"b","screen":{"params":7}}"""
        val bad = WireJson.decodeFromString(Card.serializer(), badJson)
        assertEquals("https://x.com", (bad as Card.Link).url, "a bad screen drops only the screen")
        assertNull(bad.screen)
    }

    @Test fun aRegisteredScreenGoesToTheHandlerAndReportsOpened() = runTest {
        val s = setup()
        val seen = mutableListOf<dev.pingfront.model.LinkScreen>()
        s.h.instance.onScreen = {
            seen += it
            true
        }
        s.h.instance.conversation.open(card("settings.sound", "https://example.com/plans"), s.message)
        advanceUntilIdle()
        assertEquals(listOf("settings.sound" to mapOf("tab" to "yearly")), seen.map { it.id to it.params })
        val report = s.h.transport.body("POST thread/cards/l1/act")!!
        assertEquals("opened", report.action())
        assertNull(report.failure())
        assertEquals(emptyList(), WireRules.problems("CardActionRequest", report))
        assertEquals(1, s.h.transport.count("POST thread/cards/l1/act"), "one report per tap")
        assertTrue(s.presenter.urls.isEmpty(), "the fallback url is not opened")
        s.h.stop()
    }

    @Test fun aHandlerThatReturnsFalseOrNoHandlerIsNotHandled() = runTest {
        val s = setup()
        s.h.instance.conversation.open(card("settings.sound", "https://example.com"), s.message)
        advanceUntilIdle()
        assertEquals("not_handled", s.h.transport.body("POST thread/cards/l1/act")!!.failure(), "no handler")
        s.h.instance.onScreen = { false }
        s.h.instance.conversation.open(card("settings.sound", "https://example.com"), s.message)
        advanceUntilIdle()
        val report = s.h.transport.body("POST thread/cards/l1/act")!!
        assertEquals("failed", report.action())
        assertEquals("not_handled", report.failure())
        assertEquals(emptyList(), WireRules.problems("CardActionRequest", report))
        s.h.instance.onScreen = { error("app bug") }
        s.h.instance.conversation.open(card("settings.sound", ""), s.message)
        advanceUntilIdle()
        assertEquals("not_handled", s.h.transport.body("POST thread/cards/l1/act")!!.failure(), "a throwing handler never crashes")
        s.h.stop()
    }

    @Test fun aHandlerThatDoesNotAnswerIn10SecondsIsNotHandledAndCancelled() = runTest {
        val s = setup()
        val never = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var cancelled = false
        s.h.instance.onScreen = {
            try {
                never.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
        s.h.instance.conversation.open(card("settings.sound", "https://example.com"), s.message)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, s.h.transport.count("POST thread/cards/l1/act"), "still waiting before the cap")
        advanceTimeBy(2)
        advanceUntilIdle()
        val report = s.h.transport.body("POST thread/cards/l1/act")!!
        assertEquals("failed", report.action())
        assertEquals("not_handled", report.failure(), "no answer within 10 s counts as not handled")
        assertTrue(cancelled, "the handler is cancelled at the cap")
        never.complete(true)
        advanceUntilIdle()
        assertEquals(1, s.h.transport.count("POST thread/cards/l1/act"), "a late answer reports nothing more")
        assertTrue(s.presenter.urls.isEmpty(), "and opens nothing")

        s.h.instance.onScreen = {
            kotlinx.coroutines.delay(9_000)
            true
        }
        s.h.instance.conversation.open(card("settings.sound", "https://example.com"), s.message)
        advanceUntilIdle()
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action(), "an answer inside the cap counts")
        s.h.stop()
    }

    @Test fun anUnregisteredScreenFallsBackToTheUrlOrIsUnknown() = runTest {
        val s = setup()
        s.h.instance.onScreen = { error("not for unregistered screens") }
        s.h.instance.conversation.open(card("checkout", "https://example.com/checkout"), s.message)
        advanceUntilIdle()
        assertEquals(listOf("https://example.com/checkout"), s.presenter.urls)
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action())

        s.h.instance.conversation.open(card("checkout", ""), s.message)
        advanceUntilIdle()
        assertEquals("unknown_screen", s.h.transport.body("POST thread/cards/l1/act")!!.failure())
        s.h.stop()
    }

    @Test fun theAppsOwnSchemeGoesToOnLinkThenThePresenter() = runTest {
        val s = setup()
        val seen = mutableListOf<String>()
        s.h.instance.onLink = {
            seen += it
            false
        }
        s.h.instance.conversation.open(card(null, " myapp://settings/sound"), s.message)
        advanceUntilIdle()
        assertEquals(listOf("myapp://settings/sound"), seen, "onLink sees the cleaned URL first")
        assertEquals(listOf("myapp://settings/sound"), s.presenter.urls, "then the presenter opens it")
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action())
        s.presenter.opens = false
        s.h.instance.conversation.open(card(null, "myapp://settings/sound"), s.message)
        advanceUntilIdle()
        assertEquals("cannot_open", s.h.transport.body("POST thread/cards/l1/act")!!.failure(), "no activity handles it")
        s.h.stop()
    }

    @Test fun openLinkByDefaultAppliesTheRuleThenThePresenter() = runTest {
        val s = setup()
        assertTrue(s.h.instance.openLinkByDefault(" myapp://settings\n"), "the app's own scheme goes to the presenter")
        assertEquals(listOf("myapp://settings"), s.presenter.urls, "cleaned")
        for (url in LinkUrlRuleTest.urls("refused")) assertFalse(s.h.instance.openLinkByDefault(url), url)
        assertEquals(listOf("myapp://settings"), s.presenter.urls, "a refused URL never reaches the presenter")
        s.presenter.opens = false
        assertFalse(s.h.instance.openLinkByDefault("https://example.com"), "nothing opened it")
        s.h.stop()
    }

    @Test fun aSuspendingOnLinkThatDeclinesReportsTheRealOutcome() = runTest {
        val s = setup()
        s.h.instance.onLink = {
            kotlinx.coroutines.delay(500)
            true
        }
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        advanceUntilIdle()
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action(), "handled after a wait")
        assertTrue(s.presenter.urls.isEmpty(), "nothing else opens it")

        s.h.instance.onLink = {
            kotlinx.coroutines.delay(500)
            false
        }
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        advanceUntilIdle()
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action(), "declined: the browser opened it")
        assertEquals(listOf("https://example.com/help"), s.presenter.urls)

        // Declined, and the default way opens nothing either: the real outcome, never opened.
        s.presenter.opens = false
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        advanceUntilIdle()
        val report = s.h.transport.body("POST thread/cards/l1/act")!!
        assertEquals("failed", report.action())
        assertEquals("cannot_open", report.failure())
        assertEquals(emptyList(), WireRules.problems("CardActionRequest", report))
        s.h.stop()
    }

    @Test fun anOnLinkThatThrowsCountsAsDeclined() = runTest {
        val s = setup()
        s.h.instance.onLink = { error("app bug") }
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        advanceUntilIdle()
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action(), "the browser opened it")
        assertEquals(listOf("https://example.com/help"), s.presenter.urls)
        s.h.instance.onLink = { throw kotlinx.coroutines.CancellationException("the bridge gave up") }
        s.presenter.opens = false
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        advanceUntilIdle()
        assertEquals("cannot_open", s.h.transport.body("POST thread/cards/l1/act")!!.failure(), "a handler's own cancellation is a decline")
        s.h.stop()
    }

    @Test fun anOnLinkThatDoesNotAnswerIn10SecondsIsDeclinedAndCancelled() = runTest {
        val s = setup()
        val never = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var cancelled = 0
        s.h.instance.onLink = {
            try {
                never.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
        }
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, s.h.transport.count("POST thread/cards/l1/act"), "still waiting before the cap")
        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action(), "declined at the cap: the browser opened it")
        assertEquals(listOf("https://example.com/help"), s.presenter.urls)
        assertEquals(1, cancelled, "the handler is cancelled at the cap")

        // The same cap for a link in a message.
        val opened = mutableListOf<String>()
        s.h.instance.openChatLink("https://example.com/text") { opened += it }
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf("https://example.com/text"), opened)
        assertEquals(2, cancelled)
        never.complete(true)
        advanceUntilIdle()
        assertEquals(1, s.h.transport.count("POST thread/cards/l1/act"), "a late answer reports nothing more")
        s.h.stop()
    }

    @Test fun aNewerLinkInAMessageReplacesOneStillWaiting() = runTest {
        val s = setup()
        val first = kotlinx.coroutines.CompletableDeferred<Boolean>()
        s.h.instance.onLink = { if (it == "https://a.example") first.await() else false }
        val opened = mutableListOf<String>()
        s.h.instance.openChatLink("https://a.example") { opened += it }
        runCurrent()
        s.h.instance.openChatLink("https://b.example") { opened += it }
        first.complete(false)
        advanceUntilIdle()
        assertEquals(listOf("https://b.example"), opened)
        s.h.stop()
    }

    @Test fun logoutCancelsAnOnLinkStillDeciding() = runTest {
        val s = setup()
        s.h.transport.json("POST me/logout", status = 204, body = "")
        val never = kotlinx.coroutines.CompletableDeferred<Boolean>()
        var cancelled = 0
        s.h.instance.onLink = {
            try {
                never.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
        }
        val opened = mutableListOf<String>()
        s.h.instance.conversation.open(card(null, "https://example.com/help"), s.message)
        s.h.instance.openChatLink("https://example.com/text") { opened += it }
        advanceTimeBy(1_000)
        runCurrent()
        s.h.instance.logout()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, cancelled, "both waits are cancelled")
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(opened.isEmpty() && s.presenter.urls.isEmpty(), "a cancelled wait never opens the link")
        assertEquals(0, s.h.transport.count("POST thread/cards/l1/act"), "the tap belongs to the previous person")
        s.h.stop()
    }

    @Test fun aUrlNothingOpensIsCannotOpen() = runTest {
        val s = setup()
        s.presenter.opens = false
        s.h.instance.conversation.open(card(null, "https://example.com"), s.message)
        advanceUntilIdle()
        assertEquals("cannot_open", s.h.transport.body("POST thread/cards/l1/act")!!.failure())
        s.presenter.opens = true
        s.h.instance.onLink = { true }
        s.h.instance.conversation.open(card(null, "https://example.com"), s.message)
        advanceUntilIdle()
        assertEquals("opened", s.h.transport.body("POST thread/cards/l1/act")!!.action(), "onLink opened it")
        s.h.stop()
    }
}
