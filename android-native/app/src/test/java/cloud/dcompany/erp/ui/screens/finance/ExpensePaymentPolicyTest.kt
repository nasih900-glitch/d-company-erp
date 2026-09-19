package cloud.dcompany.erp.ui.screens.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpensePaymentPolicyTest {

    @Test
    fun `new expenses default to UPI while explicit drawer cash is available`() {
        assertEquals("upi", ExpensePaymentPolicy.DefaultRail)
        assertEquals(
            listOf("cash", "upi", "card", "bank"),
            ExpensePaymentPolicy.Options.map(ExpensePaymentOption::value),
        )
        assertTrue(ExpensePaymentPolicy.Options.any { it.value == "cash" })
    }

    @Test
    fun `cash guidance explains the atomic shift linked paid-out workflow`() {
        assertTrue(
            ExpensePaymentPolicy.CashDrawerGuidance.contains(
                "exact open shift",
            ),
        )
        assertTrue(
            ExpensePaymentPolicy.CashDrawerGuidance.contains(
                "reduces that drawer atomically",
            ),
        )
    }
}
