package cloud.dcompany.erp.core.checkout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldCheckoutInteractionPolicyTest {

    @Test
    fun listReorderingKeepsOnlyTheExactSelectedOrderIdentity() {
        val before = HeldCheckoutInteractionPolicy.reconcilePreparedSelection(
            selectedOrderId = "table-t1",
            cachedOrderIds = linkedSetOf("table-t1", "ps5-1"),
            locallyConfirmedOrderIds = emptySet(),
            confirmingOrderId = null,
        )
        val afterReorder = HeldCheckoutInteractionPolicy.reconcilePreparedSelection(
            selectedOrderId = "table-t1",
            cachedOrderIds = linkedSetOf("ps5-1", "table-t1"),
            locallyConfirmedOrderIds = emptySet(),
            confirmingOrderId = null,
        )

        assertEquals(PreparedHeldCheckoutAction.KEEP_EXACT_ORDER, before)
        assertEquals(PreparedHeldCheckoutAction.KEEP_EXACT_ORDER, afterReorder)
        assertTrue(
            HeldCheckoutInteractionPolicy.confirmationTargetsPreparedOrder(
                callbackOrderId = "table-t1",
                preparedOrderId = "table-t1",
            ),
        )
        assertFalse(
            HeldCheckoutInteractionPolicy.confirmationTargetsPreparedOrder(
                callbackOrderId = "table-t1",
                preparedOrderId = "ps5-1",
            ),
        )
    }

    @Test
    fun selectedOrderRemovalClosesInsteadOfFallingBackToTheNextListItem() {
        val action = HeldCheckoutInteractionPolicy.reconcilePreparedSelection(
            selectedOrderId = "table-t1",
            cachedOrderIds = setOf("ps5-1"),
            locallyConfirmedOrderIds = emptySet(),
            confirmingOrderId = null,
        )

        assertEquals(PreparedHeldCheckoutAction.CLOSE_AND_RELEASE_CLAIM, action)
    }

    @Test
    fun localConfirmationClosesRemovedDialogWithoutReleasingPaymentClaim() {
        val alreadyInserted = HeldCheckoutInteractionPolicy.reconcilePreparedSelection(
            selectedOrderId = "table-t1",
            // The read cache can still contain T1 for a few milliseconds; the
            // durable local payment identity must take precedence immediately.
            cachedOrderIds = setOf("table-t1", "ps5-1"),
            locallyConfirmedOrderIds = setOf("table-t1"),
            confirmingOrderId = null,
        )
        val insertionInFlight = HeldCheckoutInteractionPolicy.reconcilePreparedSelection(
            selectedOrderId = "table-t1",
            cachedOrderIds = setOf("ps5-1"),
            locallyConfirmedOrderIds = emptySet(),
            confirmingOrderId = "table-t1",
        )

        assertEquals(PreparedHeldCheckoutAction.CLOSE_PAYMENT_OWNS_CLAIM, alreadyInserted)
        assertEquals(PreparedHeldCheckoutAction.CLOSE_PAYMENT_OWNS_CLAIM, insertionInFlight)
    }

    @Test
    fun rapidConcurrentHeldCheckoutCallbacksConsumeExactlyOnce() {
        val gate = OneShotHeldPaymentConfirmation("table-t1")
        assertFalse(gate.tryConsume("ps5-1"))

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts = List(32) {
                pool.submit<Boolean> {
                    start.await()
                    gate.tryConsume("table-t1")
                }
            }
            start.countDown()

            assertEquals(1, attempts.count { it.get() })
            assertTrue(gate.isConsumed)
            assertFalse(gate.tryConsume("table-t1"))
        } finally {
            pool.shutdownNow()
        }
    }
}
