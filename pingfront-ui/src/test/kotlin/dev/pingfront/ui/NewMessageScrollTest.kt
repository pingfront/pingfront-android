package dev.pingfront.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review AN-15 (3 Nov 2026), found while recording the review scenario on the emulator: with a thread longer than the
 * screen, a message that arrived while the person was reading the newest one landed below the fold. The list kept its
 * position by key, so only the new message's time header peeked in, and no "new messages" button showed. Google
 * Messages brings the new message into view when you are at the newest one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NewMessageScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun message(seq: Int, text: String, minute: Int) = Fixtures.decodeMessage(
        """{"id":"m$seq","seq":$seq,"author":{"kind":"team","name":"Deniz"},"createdAt":"2026-11-03T09:${minute.toString().padStart(2, '0')}:00Z",
           "parts":[{"type":"text","text":"$text"}]}""",
    )

    @Test fun aMessageThatArrivesAtTheNewestEndComesIntoView() {
        val instance = PingFrontInstance.detached()
        val thread = (1..20).map { message(it, "Earlier message number $it, long enough to wrap onto a second line here.", 0) }
        Fixtures.setMessages(instance, thread)
        compose.setContent { Box(Modifier.size(412.dp, 600.dp)) { ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("Earlier message number 20", substring = true).assertIsDisplayed()
        compose.runOnIdle { Fixtures.setMessages(instance, thread + message(21, "If Clockwise wakes you up well, a rating helps.", 52)) }
        compose.onNodeWithText("If Clockwise wakes you up well, a rating helps.").assertIsDisplayed()
    }
}
