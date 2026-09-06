package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.Order
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosHeldOrderSelectionFeedbackTest {

    private val cashier = PosAccess(canCreateAndCollect = true)

    @Test
    fun directBillRecoveryExplainsWhyHeldCardsAreDisabled() {
        val reason = heldOrderSelectionBlockReason(
            PosUiState(
                online = true,
                canCollectPayment = true,
                draftState = SyncState.PREPARING,
            ),
            cashier,
        )

        assertTrue(reason!!.contains("current direct POS bill"))
        assertTrue(reason.contains("before selecting"))
    }

    @Test
    fun offlineStateNamesRequiredRecovery() {
        val reason = heldOrderSelectionBlockReason(
            PosUiState(online = false, canCollectPayment = true),
            cashier,
        )

        assertTrue(reason!!.contains("Reconnect"))
        assertTrue(reason.contains("live server total"))
    }

    @Test
    fun eligibleCashierHasNoBlockingMessage() {
        assertNull(
            heldOrderSelectionBlockReason(
                PosUiState(online = true, canCollectPayment = true),
                cashier,
            ),
        )
    }

    @Test
    fun heldBillReviewBlocksSelectingASecondOrder() {
        val reason = heldOrderSelectionBlockReason(
            PosUiState(
                online = true,
                canCollectPayment = true,
                heldOrderReview = authoritativeHeldOrderReview(
                    order = Order(
                        id = "held-1",
                        status = "held",
                        totalMinor = 10_000L,
                        dueMinor = 10_000L,
                    ),
                    shiftIdAtReview = "shift-1",
                ),
            ),
            cashier,
        )

        assertTrue(reason!!.contains("open held-bill review"))
    }
}
