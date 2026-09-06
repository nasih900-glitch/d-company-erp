package cloud.dcompany.erp.ui.screens.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpensePaymentPolicyTest {

    @Test
    fun `new expenses default to UPI and never offer till cash`() {
        assertEquals("upi", ExpensePaymentPolicy.DefaultRail)
        assertEquals(
            listOf("upi", "card", "bank"),
            ExpensePaymentPolicy.Options.map(ExpensePaymentOption::value),
        )
        assertFalse(ExpensePaymentPolicy.Options.any { it.value == "cash" })
    }

    @Test
    fun `cash limitation explains the shift linked paid-out workflow`() {
        assertTrue(
            ExpensePaymentPolicy.CashDrawerGuidance.contains(
                "not linked to the open shift drawer",
            ),
        )
        assertTrue(
            ExpensePaymentPolicy.CashDrawerGuidance.contains(
                "future shift-linked drawer workflow",
            ),
        )
    }
}
