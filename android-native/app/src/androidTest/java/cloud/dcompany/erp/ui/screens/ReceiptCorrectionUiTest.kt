package cloud.dcompany.erp.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.dcompany.erp.core.net.*
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Test

class ReceiptCorrectionUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(receipt: CanonicalReceipt) {
        compose.setContent { DCompanyTheme {
            CanonicalReceiptHistoryDialog(listOf(receipt), false, false, null, false, {}, {}, {}, {},
                ReceiptSearchUiState(), onSearch = {}, onLoadMoreSearch = {}, onRefundReceipt = {}, onDismiss = {})
        } }
    }
    @Test fun knownCorrectionShowsNoPayoutPreservesOriginalPaymentAndHidesRefund() {
        show(receiptCorrectionFixture())
        compose.onNodeWithText("CORRECTED · NO PAYOUT").assertIsDisplayed()
        compose.onNodeWithText("QA-CORRECTED").performClick()
        compose.onNodeWithText("Corrected — payment not received; no payout").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Payout").assertIsDisplayed()
        compose.onNodeWithText("Original recorded payments").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open refund for this exact receipt").assertDoesNotExist()
        compose.onNodeWithText("Correct this Cash payment to UPI").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Originally recorded paid"))
        compose.onNodeWithText("Originally recorded paid").assertIsDisplayed()
        compose.onNodeWithText("Receipt correction offset").assertIsDisplayed()
        val paidY = compose.onNodeWithText("Originally recorded paid").fetchSemanticsNode().boundsInRoot.center.y
        compose.onNode(hasTextExactly("80.00") and SemanticsMatcher("recorded paid value on its visible row") {
            kotlin.math.abs(it.boundsInRoot.center.y - paidY) < 2f
        }, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("-80.00").assertIsDisplayed()
        val netY = compose.onNodeWithText("Net collected").fetchSemanticsNode().boundsInRoot.center.y
        compose.onNode(hasTextExactly("0.00") and SemanticsMatcher("effective net value on its visible row") {
            kotlin.math.abs(it.boundsInRoot.center.y - netY) < 2f
        }, useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun unknownCorrectionDoesNotInventNoPayoutOrEnableRefundEvenIfStaleAmountPositive() {
        val source = receiptCorrectionFixture()
        show(source.copy(receiptCorrection = source.receiptCorrection!!.copy(kind = "future_adjustment"), refundableMinor = 8000))
        compose.onNodeWithText("CORRECTION · REVIEW DETAILS").assertIsDisplayed()
        compose.onNodeWithText("QA-CORRECTED").performClick()
        compose.onNodeWithText("No payout conclusion is available from this metadata.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Payout").assertDoesNotExist()
        compose.onNodeWithText("Open refund for this exact receipt").assertDoesNotExist()
    }
}

internal fun receiptCorrectionFixture(): CanonicalReceipt {
    val time = "2026-11-07T12:00:00Z"
    return CanonicalReceipt(orderId = "qa-order", companyId = "qa-company", branchId = "qa-branch", terminalId = "qa-terminal",
        shiftId = "qa-shift", openedBy = "qa-staff", invoiceNo = "QA-CORRECTED", fiscalYear = "2026-27", status = "paid",
        orderType = "dine_in", subtotalMinor = 8000, discountMinor = 0, manualDiscountMinor = 0, pointsRedeemedMinor = 0,
        cgstMinor = 0, sgstMinor = 0, igstMinor = 0, cessMinor = 0, taxMinor = 0, roundOffMinor = 0, tipMinor = 0,
        totalMinor = 8000, paidMinor = 8000, refundedMinor = 0, refundableMinor = 0, netCollectedMinor = 0,
        isReverseCharge = false, openedAt = time, closedAt = time, invoiceIssuedAt = time, createdAt = time, updatedAt = time,
        payments = listOf(CanonicalReceiptPayment("qa-payment", "qa-shift", "upi", 8000, paidAt = time, createdAt = time)),
        correctedMinor = 8000, receiptCorrection = ReceiptCorrectionMetadata("qa-correction", "payment_not_received_full_mistake",
            8000, time, 0, "Corrected — payment not received; no payout"))
}
