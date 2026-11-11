package dev.pingfront.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.pingfront.PingFrontException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review AN-7 (3 Nov 2026): a bug report that could not be sent (offline, a cooldown) closed the form anyway, "the
 * chat shows the failure", but nothing showed it: the person's words were gone and they believed the report was sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aReportThatFailsKeepsTheFormAndSaysSo() {
        var closed = false
        var attempts = 0
        compose.setContent {
            SimpleReport("The alarm skipped a minute", send = {
                attempts += 1
                throw PingFrontException.Network(offline = true)
            }) { closed = true }
        }
        compose.onNodeWithText("Send report").performClick()
        compose.waitForIdle()
        assertFalse(closed, "the form closed although the report was not sent")
        compose.onNodeWithText("Not delivered").assertExists()
        compose.onNodeWithText("The alarm skipped a minute").assertExists()
        compose.onNodeWithText("Send report").performClick()
        compose.waitForIdle()
        assertEquals(2, attempts, "Send report tries again")
    }
}
