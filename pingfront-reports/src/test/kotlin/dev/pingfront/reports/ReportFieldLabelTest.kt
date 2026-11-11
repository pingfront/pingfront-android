package dev.pingfront.reports

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review AN-25 (3 Nov 2026): on the report screen the text field had neither text nor a description ("What went wrong?"
 * is a separate text node), so TalkBack announced only "Edit box".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportFieldLabelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theReportFieldIsLabelled() {
        compose.setContent { ReportScreen(ReportActivity.Pending(null, null), send = { _, _ -> }, onDone = {}) }
        compose.onNode(hasSetTextAction() and hasContentDescription("What went wrong?")).assertExists()
    }
}
