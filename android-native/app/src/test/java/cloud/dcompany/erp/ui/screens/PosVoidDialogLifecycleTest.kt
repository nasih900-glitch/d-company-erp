package cloud.dcompany.erp.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosVoidDialogLifecycleTest {
    @Test
    fun `void dialog remains while request is busy even after checkout disappears`() {
        assertFalse(
            shouldDismissPosVoidTarget(
                targetOrderId = "order-1",
                preparedDirectOrderId = null,
                preparedHeldOrderId = null,
                checkoutBusy = true,
            ),
        )
    }

    @Test
    fun `void dialog closes when request completes and checkout is gone`() {
        assertTrue(
            shouldDismissPosVoidTarget(
                targetOrderId = "order-1",
                preparedDirectOrderId = null,
                preparedHeldOrderId = null,
                checkoutBusy = false,
            ),
        )
    }

    @Test
    fun `void dialog remains when the same prepared checkout is still payable`() {
        assertFalse(
            shouldDismissPosVoidTarget(
                targetOrderId = "order-1",
                preparedDirectOrderId = "order-1",
                preparedHeldOrderId = null,
                checkoutBusy = false,
            ),
        )
        assertFalse(
            shouldDismissPosVoidTarget(
                targetOrderId = "order-1",
                preparedDirectOrderId = null,
                preparedHeldOrderId = "order-1",
                checkoutBusy = false,
            ),
        )
    }
}
