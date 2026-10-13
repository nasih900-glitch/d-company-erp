package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.net.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundRailPolicyTest {

    @Test
    fun `single provider defaults to the exact original rail`() {
        val policy = refundRailPolicy(listOf("upi"))

        assertEquals(RefundRailKind.SINGLE_PROVIDER, policy.kind)
        assertEquals("original", policy.defaultMode)
        assertTrue(policy.allows("original"))
        assertTrue(policy.allows("cash"))
        assertEquals("UPI", refundRailSummary(policy.methods))
    }

    @Test
    fun `original cash routes through guarded cash workflow`() {
        val policy = refundRailPolicy(listOf("cash"))

        assertEquals(RefundRailKind.CASH, policy.kind)
        assertEquals("cash", policy.defaultMode)
        assertTrue(policy.allows("cash"))
        assertFalse(policy.allows("original"))
    }

    @Test
    fun `mixed payment permits only explicit cash`() {
        val policy = refundRailPolicy(listOf("card", "upi"))

        assertEquals(RefundRailKind.MIXED, policy.kind)
        assertTrue(policy.allows("cash"))
        assertFalse(policy.allows("original"))
        assertEquals("Mixed: Card + UPI", refundRailSummary(policy.methods))
    }

    @Test
    fun `missing or unknown evidence fails closed`() {
        for (methods in listOf(emptyList(), listOf("bank_transfer"))) {
            val policy = refundRailPolicy(methods)
            assertEquals(RefundRailKind.UNKNOWN, policy.kind)
            assertFalse(policy.requestReady)
            assertFalse(policy.allows("cash"))
            assertFalse(policy.allows("original"))
        }
    }

    @Test
    fun `refund capture requires the same selected authoritative snapshot`() {
        val verified = Order(
            id = "order-1",
            checkoutVersion = 7,
            paidMinor = 29_000,
            refundableMinor = 29_000,
            pendingRefundMinor = 0,
            paymentMethods = listOf("cash", "upi"),
        )

        assertTrue(refundOrderSnapshotMatches(verified, verified.copy()))
        assertFalse(refundOrderSnapshotMatches(null, verified))
        assertFalse(refundOrderSnapshotMatches(verified.copy(checkoutVersion = 8), verified))
        assertFalse(refundOrderSnapshotMatches(verified.copy(pendingRefundMinor = 1_000), verified))
        assertFalse(refundOrderSnapshotMatches(verified.copy(refundableMinor = 28_000), verified))
        assertFalse(refundOrderSnapshotMatches(verified.copy(paymentMethods = listOf("upi")), verified))
    }

    @Test
    fun `stale exact receipt A cannot replace a newer B request`() {
        assertFalse(
            exactReceiptResponseIsCurrent(
                requestGeneration = 1,
                requestOrderId = "order-a",
                responseOrderId = "order-a",
                currentGeneration = 2,
                currentOrderId = "order-b",
            )
        )
        assertFalse(
            exactReceiptResponseIsCurrent(
                requestGeneration = 2,
                requestOrderId = "order-b",
                responseOrderId = "order-a",
                currentGeneration = 2,
                currentOrderId = "order-b",
            )
        )
        assertTrue(
            exactReceiptResponseIsCurrent(
                requestGeneration = 2,
                requestOrderId = "order-b",
                responseOrderId = "order-b",
                currentGeneration = 2,
                currentOrderId = "order-b",
            )
        )
    }
}
