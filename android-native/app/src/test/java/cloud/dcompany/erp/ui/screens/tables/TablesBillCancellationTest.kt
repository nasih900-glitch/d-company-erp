package cloud.dcompany.erp.ui.screens.tables

import cloud.dcompany.erp.core.sync.CafeBillLineProjection
import cloud.dcompany.erp.core.sync.CafeBillProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TablesBillCancellationTest {

    @Test
    fun `whole bill cancellation trims reason only after operational checks pass`() {
        val decision = validateBillCancellation(
            canCancelItems = true,
            busy = false,
            bill = bill(),
            shiftAvailable = true,
            shiftAuthorized = true,
            shiftAuthorizationMessage = null,
            rawReason = "  Customer left  ",
        )

        assertEquals(BillCancellationDecision.Accepted("Customer left"), decision)
    }

    @Test
    fun `served bill and wrong shift actor are rejected with actionable messages`() {
        val served = validateBillCancellation(
            canCancelItems = true,
            busy = false,
            bill = bill(kitchenStatus = "served"),
            shiftAvailable = true,
            shiftAuthorized = true,
            shiftAuthorizationMessage = null,
            rawReason = "Customer left",
        )
        assertTrue((served as BillCancellationDecision.Rejected).message.contains("already served"))

        val wrongActor = validateBillCancellation(
            canCancelItems = true,
            busy = false,
            bill = bill(),
            shiftAvailable = true,
            shiftAuthorized = false,
            shiftAuthorizationMessage = "Shift opened by Rafi. Only that opener may void it.",
            rawReason = "Customer left",
        )
        assertEquals(
            "Shift opened by Rafi. Only that opener may void it.",
            (wrongActor as BillCancellationDecision.Rejected).message,
        )
    }

    @Test
    fun `blank and oversized audit reasons are rejected`() {
        val blank = decisionFor("   ") as BillCancellationDecision.Rejected
        assertTrue(blank.message.contains("Enter why"))

        val oversized = decisionFor("x".repeat(501)) as BillCancellationDecision.Rejected
        assertTrue(oversized.message.contains("500"))
    }

    private fun decisionFor(reason: String) = validateBillCancellation(
        canCancelItems = true,
        busy = false,
        bill = bill(),
        shiftAvailable = true,
        shiftAuthorized = true,
        shiftAuthorizationMessage = null,
        rawReason = reason,
    )

    private fun bill(kitchenStatus: String = "queued") = CafeBillProjection(
        localBillId = "local-bill",
        serverOrderId = "server-order",
        tableId = "table-1",
        tableCode = "T1",
        status = "open",
        checkoutVersion = 3,
        subtotalMinor = 1_000,
        taxMinor = 0,
        confirmedTotalMinor = 1_000,
        totalMinor = 1_000,
        amountPending = false,
        lines = listOf(
            CafeBillLineProjection(
                stableKey = "line-1",
                serverLineId = "server-line-1",
                clientLineId = "client-line-1",
                menuItemId = "menu-1",
                name = "Coffee",
                qty = 1.0,
                unitPriceMinor = 1_000,
                lineTotalMinor = 1_000,
                note = null,
                roundNo = 1,
                kitchenStatus = kitchenStatus,
                locallyPending = false,
                voided = false,
                voidReason = null,
                kitchenCancellationPending = false,
            ),
        ),
        pendingActionCount = 0,
        blockedActionId = null,
        blockedMessage = null,
    )
}
