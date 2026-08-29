package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.CanonicalReceiptPayment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalReceiptPresentationTest {
    @Test
    fun `receipt summary names every distinct payment actor`() {
        val summary = receiptPaymentSummary(
            listOf(
                payment(id = "cash-1", method = "cash", actor = "Rafi"),
                payment(id = "upi-1", method = "upi", actor = "Sameer"),
                payment(id = "cash-2", method = "cash", actor = "Rafi"),
            ),
        )

        assertEquals("CASH + UPI · recorded by Rafi, Sameer", summary)
    }

    @Test
    fun `receipt without payments has no misleading biller summary`() {
        assertNull(receiptPaymentSummary(emptyList()))
    }

    private fun payment(
        id: String,
        method: String,
        actor: String?,
    ) = CanonicalReceiptPayment(
        id = id,
        shiftId = "shift-1",
        method = method,
        amountMinor = 1_000,
        paidAt = "2026-08-29T10:00:00Z",
        recordedBy = actor?.let { "user-$it" },
        recordedByName = actor,
        createdAt = "2026-08-29T10:00:00Z",
    )
}
