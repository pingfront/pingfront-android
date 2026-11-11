package dev.pingfront.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ApplicationProvider
import dev.pingfront.PingFrontInstance
import dev.pingfront.internal.debug.Fixtures
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A teammate who changes their name and photo in Settings shows the change in the open chat. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
class TeammateProfileUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aChangedNameAndPhotoShowInTheChat() {
        FixtureImages.install(ApplicationProvider.getApplicationContext())
        val instance = PingFrontInstance.detached()
        Fixtures.load(instance, Fixtures.Kind.Welcome, "en", Fixtures.Clockwise, Fixtures.EmptyLook, false)
        val deniz = instance.conversation.state.value.team.first()
        var view: android.view.View? = null
        compose.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            ChatScreen(instance, null, onClose = {}, Modifier.fillMaxSize())
        }
        compose.onNodeWithText(deniz.name).assertExists()
        compose.waitForIdle()
        // The view drawn in software (Robolectric native graphics): Compose 1.11's captureToImage waits for a frame
        // Robolectric never commits.
        val before = compose.runOnIdle { view!!.drawToBitmap() }

        // A new photo only: the welcome face is redrawn.
        Fixtures.setTeam(instance, listOf(Fixtures.teamMember(deniz, avatarUrl = "fixture://avatar/kofi")))
        compose.waitForIdle()
        val after = compose.runOnIdle { view!!.drawToBitmap() }
        assertFalse(before.sameAs(after), "the new photo is drawn")

        // A new name: shown in place of the old one.
        Fixtures.setTeam(instance, listOf(Fixtures.teamMember(deniz, name = "Deniz Kaya", avatarUrl = "fixture://avatar/kofi")))
        compose.onNodeWithText("Deniz Kaya").assertExists()
        compose.onNodeWithText(deniz.name).assertDoesNotExist()
    }
}
