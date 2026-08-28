package cloud.dcompany.erp.ui.screens.refunds

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
}
