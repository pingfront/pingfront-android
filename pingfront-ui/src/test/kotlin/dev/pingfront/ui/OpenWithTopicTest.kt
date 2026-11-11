package dev.pingfront.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import dev.pingfront.look.ChatTopic
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * X1 (6 Nov 2026), aligned with iOS: `openChat(topic)` and a chat link put only the topic's own prefill into the composer.
 * A preset's sentence start ("Restoring my purchases didn't work: ") belongs to a tap on its chip; opening the chat for
 * a topic never writes a sentence the person did not choose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenWithTopicTest {
    @get:Rule val compose = createComposeRule()

    private fun open(topic: ChatTopic): PingFrontInstance {
        val instance = PingFrontInstance.detached()
        Fixtures.setMessages(
            instance,
            listOf(
                Fixtures.decodeMessage(
                    """{"id":"m1","seq":1,"author":{"kind":"team","name":"Deniz"},"createdAt":"2026-11-03T09:00:00Z",
                       "parts":[{"type":"text","text":"Hi"}]}""",
                ),
            ),
        )
        compose.setContent { Box(Modifier.size(412.dp, 800.dp)) { ChatScreen(instance, topic, onClose = {}, Modifier.fillMaxSize()) } }
        compose.waitForIdle()
        return instance
    }

    @Test fun aPresetTopicOpensWithAnEmptyComposer() {
        assertEquals("", open(ChatTopic.Restore).conversation.draft.value, "no starter on openChat(ChatTopic.Restore)")
    }

    @Test fun aTopicsPrefillGoesIntoTheComposer() {
        val instance = open(ChatTopic("restore", "Restore purchases", prefill = "My purchase isn't showing up. "))
        assertEquals("My purchase isn't showing up. ", instance.conversation.draft.value)
        compose.onNode(hasSetTextAction() and hasText("My purchase isn't showing up.", substring = true)).assertExists()
    }
}
