package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.SyncState
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosDirectSaleFeedbackTest {

    @Test
    fun pendingSaleDoesNotClaimAConfirmedOutcome() {
        assertNull(directSaleOutcomeNotice(order(syncState = SyncState.PENDING)))
    }

    @Test
    fun syncedSaleNamesServerInvoiceAndCanonicalTotal() {
        val message = directSaleOutcomeNotice(
            order(
                syncState = SyncState.SYNCED,
                invoiceNo = "INV-2042",
                serverTotalMinor = 12_345,
            ),
        )!!

        assertTrue(message.contains("confirmed by the server"))
        assertTrue(message.contains("INV-2042"))
        assertTrue(message.contains("₹123.45"))
        assertTrue(message.contains("No further action"))
    }

    @Test
    fun rejectedSaleWarnsAgainstDoubleCollectionAndNamesRecoveryAction() {
        val message = directSaleOutcomeNotice(
            order(
                syncState = SyncState.REJECTED,
                lastError = "Shift was closed",
            ),
        )!!

        assertTrue(message.contains("Shift was closed"))
        assertTrue(message.contains("Do not collect payment again"))
        assertTrue(message.contains("Retry after fix"))
    }

    private fun order(
        syncState: String,
        invoiceNo: String? = null,
        serverTotalMinor: Long? = null,
        lastError: String? = null,
    ) = LocalOrderEntity(
        localId = "sale-1",
        serverOrderId = "server-order-1",
        invoiceNo = invoiceNo,
        shiftId = "shift-1",
        type = "dine_in",
        estimateMinor = 10_000,
        serverTotalMinor = serverTotalMinor,
        paymentMethod = "cash",
        tenderedMinor = 10_000,
        createdAtMillis = 1_725_000_000_000,
        syncState = syncState,
        lastError = lastError,
    )
}
