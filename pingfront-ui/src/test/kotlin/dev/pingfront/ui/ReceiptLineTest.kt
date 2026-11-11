package dev.pingfront.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.pingfront.model.MessagePart
import dev.pingfront.model.ReceiptAction
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Receipts carry the green check only when something was done for the person. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceiptLineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHandOffLineHasNoCheckAndOtherReceiptsDo() {
        compose.setContent {
            ReceiptLine(dev.pingfront.internal.debug.Fixtures.receipt(ReceiptAction.HandedOff, "Deniz will reply"))
            ReceiptLine(dev.pingfront.internal.debug.Fixtures.receipt(ReceiptAction.TrialExtended, "Trial extended by 7 days"))
            ReceiptLine(dev.pingfront.internal.debug.Fixtures.receipt(ReceiptAction.Custom, "Refund sent"))
        }
        compose.onNodeWithText("Deniz will reply").assertExists()
        assertEquals(2, compose.onAllNodesWithTag(RECEIPT_CHECK_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
