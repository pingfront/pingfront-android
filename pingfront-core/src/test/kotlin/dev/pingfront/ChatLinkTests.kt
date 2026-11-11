@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pingfront

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.pingfront.internal.ChatLink
import dev.pingfront.internal.ChatLinks
import dev.pingfront.look.ChatTopic
import dev.pingfront.testing.ScriptedTransport
import dev.pingfront.testing.testDependencies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The case table in the protocol, "Chat links": the parser on the URL string, without Android. */
class ChatLinkParserTest {
    private val chat = ChatLink.Chat(null)

    private fun check(expected: ChatLink, vararg urls: String) {
        for (url in urls) assertEquals(expected, ChatLinks.parse(url), url)
    }

    @Test fun chatLinksOnTheAppsDomainOrSchemeOpenTheChat() = check(
        chat,
        "https://example.com/help?pingfront=chat",
        "myapp://support?pingfront=chat",
        "HTTPS://example.com/?utm_source=mail&pingfront=chat",
        "https://example.com/?pingfront=chat#section",
        "myapp:///?pingfront=chat",
    )

    @Test fun namesAndValuesArePercentDecoded() = check(chat, "https://example.com/?pingfront=ch%61t", "https://example.com/?p%69ngfront=chat")

    @Test fun reportOpensTheReportAndIgnoresTopics() = check(
        ChatLink.Report,
        "myapp://support?pingfront=report",
        "https://example.com/?pingfront=report&topic=billing",
    )

    @Test fun aTopicIsPassedOnOnlyWhenItAppearsOnce() {
        check(ChatLink.Chat("billing"), "https://example.com/?pingfront=chat&topic=billing", "myapp://x?topic=billing&pingfront=chat")
        // Matching against the chat's topics happens when the link is applied; these never get that far.
        check(
            chat,
            "https://example.com/?pingfront=chat&topic=billing&topic=bug",
            "https://example.com/?pingfront=chat&topic=",
            "https://example.com/?pingfront=chat&topic",
            "https://example.com/?pingfront=chat&topic=${"a".repeat(40)}",
            "https://example.com/?pingfront=chat&topic=%zz",
        )
        // Unknown or wrongly cased topics are carried, and refused by the look match (see the facade tests).
        check(ChatLink.Chat("BILLING"), "https://example.com/?pingfront=chat&topic=BILLING")
    }

    @Test fun textNeverReachesTheComposer() = check(
        chat,
        "https://example.com/?pingfront=chat&text=Cancel%20my%20plan",
        "https://example.com/?pingfront=chat&message=hi&prefill=hi",
    )

    @Test fun unknownEmptyOrRepeatedValuesAreRefused() = check(
        ChatLink.Refused,
        "https://example.com/?pingfront=Chat",
        "https://example.com/?pingfront=",
        "https://example.com/?pingfront",
        "https://example.com/?pingfront=open",
        "https://example.com/?pingfront=chat%00",
        "https://example.com/?pingfront=chat%",
        "https://example.com/?pingfront=chat&pingfront=chat",
        "https://example.com/?pingfront=chat&pingfront=report",
        "https://example.com/?pingfront=chat+",
    )

    @Test fun onlyTheQueryCounts() = check(
        ChatLink.NotALink,
        "https://example.com/#pingfront=chat",
        "https://example.com/?a=1#?pingfront=chat",
        "https://example.com/pingfront=chat",
        "https://pingfront.example.com/chat",
        "https://example.com/?Pingfront=chat",
        "https://example.com/?utm_source=mail",
    )

    @Test fun hostileSchemesAreRefused() {
        check(
            ChatLink.Refused,
            "JavaScript://x?pingfront=chat",
            "javascript://%0aalert(1)?pingfront=chat",
            "data://text/html,?pingfront=chat",
            "file:///sdcard/x?pingfront=chat",
            "content://evil/x?pingfront=chat",
            "intent://x?pingfront=chat",
            "vbscript://x?pingfront=chat",
            "blob://x?pingfront=chat",
            "about://blank?pingfront=chat",
        )
        // Not hierarchical, so not a chat link at all.
        check(
            ChatLink.NotALink,
            "javascript:alert(1)//?pingfront=chat",
            "data:text/html,?pingfront=chat",
            "myapp:support?pingfront=chat",
            "/help?pingfront=chat",
            "?pingfront=chat",
            "",
            "pingfront=chat",
        )
    }

    @Test fun urlsLongerThan2048CharactersAreNotLinks() {
        val head = "https://example.com/?pingfront=chat&pad="
        val longest = head + "x".repeat(ChatLinks.MAX_LENGTH - head.length)
        assertEquals(chat, ChatLinks.parse(longest))
        assertEquals(ChatLink.NotALink, ChatLinks.parse(longest + "x"))
        assertEquals(ChatLink.NotALink, ChatLinks.parse("https://example.com/?pingfront=chat&topic=" + "b".repeat(100_000)))
    }
}

/** `PingFront.handle`: what opens, when, and that nothing is sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatLinkFacadeTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private class Recorder : ChatPresenter {
        val chats = mutableListOf<ChatTopic?>()
        var reports = 0

        override fun presentChat(topic: ChatTopic?) {
            chats += topic
        }

        override fun dismissChat() = Unit

        override fun openUrl(url: String) = Unit

        override fun presentSimpleReport(prefill: String?) {
            reports += 1
        }
    }

    private class RecorderModule(val recorder: Recorder) : PingFrontModule("test-chat-ui") {
        override fun install(context: ModuleContext) = context.setChatPresenter(recorder)
    }

    private fun config(recorder: Recorder, vararg topics: ChatTopic) = PingFrontConfig().apply {
        baseUrl = ScriptedTransport.BASE_URL
        use(RecorderModule(recorder))
        if (topics.isNotEmpty()) topics(*topics)
    }

    @Test fun afterConfigureALinkOpensTheChatAndSendsNothing() = runTest {
        val transport = ScriptedTransport()
        val recorder = Recorder()
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, config(recorder), testDependencies(transport))
        advanceUntilIdle()
        assertTrue(instance.handleLink("https://example.com/help?pingfront=chat"))
        assertTrue(instance.handleLink("myapp://support?pingfront=chat&topic=billing"))
        assertTrue(instance.handleLink("myapp://support?pingfront=chat&topic=nope"))
        assertTrue(instance.handleLink("myapp://support?pingfront=chat&topic=BILLING"))
        assertTrue(instance.handleLink("myapp://support?pingfront=report&topic=billing"))
        assertEquals(listOf(null, ChatTopic.Billing, null, null), recorder.chats)
        assertEquals(1, recorder.reports)
        for (url in listOf("https://example.com/?pingfront=nope", "javascript:alert(1)//?pingfront=chat", "https://example.com/", "")) {
            assertFalse(instance.handleLink(url), url)
        }
        assertEquals(4, recorder.chats.size)
        advanceUntilIdle()
        assertTrue(transport.requests.isEmpty(), "handling links sends nothing: ${transport.requests.map(transport::key)}")
        instance.teardown()
    }

    @Test fun topicsMatchTheChatsOwnTopics() = runTest {
        val recorder = Recorder()
        val alarms = ChatTopic("alarms", "Alarms")
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, config(recorder, alarms), testDependencies())
        assertTrue(instance.handleLink("myapp://x?pingfront=chat&topic=alarms"))
        assertTrue(instance.handleLink("myapp://x?pingfront=chat&topic=billing"), "a default topic the app replaced is ignored")
        assertEquals(listOf(alarms, null), recorder.chats)
        instance.teardown()
    }

    @Test fun beforeConfigureTheLastLinkIsKeptAndAppliedOnce() = runTest {
        val transport = ScriptedTransport()
        val recorder = Recorder()
        val instance = PingFrontInstance()
        assertTrue(instance.handleLink("myapp://x?pingfront=report"))
        assertTrue(instance.handleLink("myapp://x?pingfront=chat&topic=bug"))
        assertFalse(instance.handleLink("myapp://x?pingfront=nope"), "a refused link never replaces the kept one")
        assertTrue(recorder.chats.isEmpty())
        instance.start(context, EngineHarness.KEY, config(recorder), testDependencies(transport))
        assertEquals(listOf<ChatTopic?>(ChatTopic.Bug), recorder.chats, "the last link, with its topic checked at configure")
        assertEquals(0, recorder.reports)
        instance.start(context, EngineHarness.KEY, config(recorder), testDependencies(transport))
        assertEquals(1, recorder.chats.size, "a second configure does not apply it again")
        advanceUntilIdle()
        assertTrue(transport.requests.isEmpty(), "nothing was sent")
        instance.teardown()
    }

    @Test fun aKeptLinkStillOpensWhenTheKeyIsWrong() = runTest {
        val recorder = Recorder()
        val instance = PingFrontInstance()
        assertTrue(instance.handleLink("https://example.com/?pingfront=chat"))
        instance.start(context, "pk_live_0123456789ABCDEFGHIJKLNz", config(recorder), testDependencies())
        assertEquals(listOf<ChatTopic?>(null), recorder.chats, "the chat shows the configuration problem")
        instance.teardown()
    }

    /** A link arrives in onCreate or onNewIntent; the report's screenshot waits for the activity to resume. */
    @Test fun aReportLinkWaitsForItsActivityToResume() = runTest {
        val recorder = Recorder()
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, config(recorder), testDependencies())
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create()
        assertTrue(instance.handleLink("myapp://x?pingfront=report"))
        assertEquals(0, recorder.reports, "not before the activity is on screen")
        activity.start().resume()
        assertEquals(1, recorder.reports)
        activity.pause()
        activity.resume()
        assertEquals(1, recorder.reports, "once")
        instance.teardown()
    }

    @Test fun intentsCountOnlyForViewWithData() = runTest {
        val recorder = Recorder()
        val instance = PingFrontInstance()
        instance.start(context, EngineHarness.KEY, config(recorder), testDependencies())
        val link = Uri.parse("myapp://support?pingfront=chat")
        assertTrue(instance.handleLink(Intent(Intent.ACTION_VIEW, link)))
        assertFalse(instance.handleLink(Intent(Intent.ACTION_SEND, link)))
        assertFalse(instance.handleLink(Intent(Intent.ACTION_VIEW)))
        assertFalse(instance.handleLink(Intent(Intent.ACTION_MAIN).putExtra("pingfront", "chat")), "extras are never read")
        assertFalse(instance.handleLink(Intent(Intent.ACTION_VIEW, Uri.parse("myapp://support?pingfront=nope"))))
        assertEquals(1, recorder.chats.size)
        instance.teardown()
    }
}
