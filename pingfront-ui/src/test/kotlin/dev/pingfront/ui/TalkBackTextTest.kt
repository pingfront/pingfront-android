package dev.pingfront.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review CQ-M12: TalkBack reads what is on screen, and card messages keep their own text. */
@RunWith(RobolectricTestRunner::class)
// Tall enough that the whole thread, with the close hint under the person's message, is laid out.
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
class TalkBackTextTest {
    @get:Rule val compose = createComposeRule()

    @Test fun teamMessagesReadTheShownTextAndCardsKeepTheirs() {
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome)
        val translated = Fixtures.decodeMessage(
            """{"id":"m1","seq":1,"author":{"kind":"team","id":"mem_1","name":"Deniz"},"createdAt":"2026-10-30T18:00:00Z",""" +
                """"parts":[{"type":"text","text":"Fixed in 2.3.1."}],""" +
                """"translation":{"language":"es","from":"en","parts":[{"type":"text","text":"Arreglado en 2.3.1."}]}}""",
        )
        val cardOnly = Fixtures.decodeMessage(
            """{"id":"m2","seq":2,"author":{"kind":"team","id":"mem_1","name":"Deniz"},"createdAt":"2026-10-30T18:01:00Z",""" +
                """"parts":[{"type":"card","card":{"kind":"link","id":"card_l","title":"Read the guide","url":"https://example.com/help","button":"Open"}}]}""",
        )
        val photoOnly = Fixtures.decodeMessage(
            """{"id":"m3","seq":3,"author":{"kind":"person"},"createdAt":"2026-10-30T18:02:00Z",""" +
                """"parts":[{"type":"image","attachmentId":"att_1","width":10,"height":10,"removed":true}]}""",
        )
        Fixtures.setMessages(instance, listOf(translated, cardOnly, photoOnly))
        compose.setContent {
            CompositionLocalProvider(LocalChatPreview provides ChatPreview(now = Fixtures.Base.plusSeconds(3_600), still = true)) {
                ChatScreen(instance, null, null, Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        // The translation is what is shown, so that is what TalkBack reads.
        compose.onNodeWithContentDescription("Deniz: Arreglado en 2.3.1.", useUnmergedTree = true).assertExists()
        assertTrue(
            compose.onAllNodesWithContentDescription("Deniz: Fixed in 2.3.1.", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "the untranslated text is not read",
        )
        // A card-only message never collapses to "Deniz: " with nothing after it.
        val root = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val descriptions = root.flatMap { node ->
            generateSequence(listOf(node)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten().toList()
        }.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue(descriptions.none { it.trim() == "Deniz:" }, "no empty \"name:\" description: $descriptions")
        compose.onNodeWithText("Read the guide").assertExists()
        // Found on the emulator (1 Nov 2026): the person's photo-only message read as "You: ".
        compose.onNodeWithContentDescription("You: Photo removed", useUnmergedTree = true).assertExists()
        assertTrue(descriptions.none { it.trim() == "You:" }, "no empty \"You:\" description: $descriptions")
    }
}
