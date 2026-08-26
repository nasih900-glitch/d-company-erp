package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.net.CheckoutClaimResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldOrderClaimPolicyTest {

    private val order = HeldOrderCacheEntity(
        id = "order-1",
        invoiceNo = null,
        type = "dine_in",
        sourceLabel = "Table 4",
        totalMinor = 12_500,
        paidMinor = 500,
        itemsCount = 2,
        customerName = null,
        createdAt = "2026-08-25T10:00:00Z",
        heldAt = "2026-08-25T10:05:00Z",
        checkoutVersion = 7,
    )
    private val claim = CheckoutClaimResult(
        claimId = "claim-1",
        orderId = order.id,
        claimToken = "opaque-token",
        expiresAt = "2026-08-25T10:10:00Z",
        orderTotalMinor = order.totalMinor,
        paidMinor = order.paidMinor,
        dueMinor = order.dueMinor,
        orderVersion = order.checkoutVersion,
        claimantUserId = "user-1",
        terminalId = "terminal-1",
    )
    private val payment = LocalHeldOrderPaymentEntity(
        localId = "payment-1",
        targetOrderId = order.id,
        method = "upi",
        amountMinor = order.dueMinor,
        tenderedMinor = null,
        expectedTotalMinor = order.totalMinor,
        expectedDueMinor = order.dueMinor,
        claimToken = claim.claimToken,
        claimExpiresAtMillis = 1_000,
        claimOrderVersion = claim.orderVersion,
        createdAtMillis = 1,
        syncState = HeldOrderPaymentState.PENDING,
    )

    @Test
    fun `confirmation requires the exact displayed snapshot including version`() {
        assertTrue(HeldOrderClaimPolicy.matchesDisplayedBill(order, claim))
        assertFalse(
            HeldOrderClaimPolicy.matchesDisplayedBill(
                order,
                claim.copy(orderVersion = claim.orderVersion + 1),
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.matchesDisplayedBill(
                order,
                claim.copy(dueMinor = claim.dueMinor + 100),
            ),
        )
    }

    @Test
    fun `confirmed settlement recovery permits version-only change`() {
        assertTrue(
            HeldOrderClaimPolicy.matchesConfirmedSettlement(
                payment,
                claim.copy(orderVersion = claim.orderVersion + 1),
            ),
        )
    }

    @Test
    fun `confirmed settlement recovery refuses any amount or order change`() {
        assertFalse(
            HeldOrderClaimPolicy.matchesConfirmedSettlement(
                payment,
                claim.copy(dueMinor = claim.dueMinor + 1),
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.matchesConfirmedSettlement(
                payment,
                claim.copy(orderTotalMinor = claim.orderTotalMinor + 1),
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.matchesConfirmedSettlement(
                payment,
                claim.copy(orderId = "order-2"),
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.matchesConfirmedSettlement(
                payment.copy(amountMinor = payment.amountMinor - 1),
                claim,
            ),
        )
    }

    @Test
    fun `claim expiry uses a thirty second confirmation guard`() {
        assertTrue(HeldOrderClaimPolicy.hasConfirmationWindow(31_001, 1_000))
        assertFalse(HeldOrderClaimPolicy.hasConfirmationWindow(31_000, 1_000))
        assertEquals(1_787_652_600_000L, HeldOrderClaimPolicy.claimExpiryMillis(claim))
        assertTrue(HeldOrderClaimPolicy.claimExpiryMillis(claim.copy(expiresAt = "invalid")) == null)
    }

    @Test
    fun `only claim validation errors trigger payment reacquisition`() {
        assertTrue(HeldOrderClaimPolicy.shouldReacquireAfterPaymentError("checkout_claim_required"))
        assertTrue(HeldOrderClaimPolicy.shouldReacquireAfterPaymentError("checkout_claim_expired"))
        assertTrue(HeldOrderClaimPolicy.shouldReacquireAfterPaymentError("checkout_claim_invalid"))
        assertTrue(HeldOrderClaimPolicy.shouldReacquireAfterPaymentError("checkout_claim_stale"))
        assertFalse(HeldOrderClaimPolicy.shouldReacquireAfterPaymentError("business_rule"))
        assertFalse(HeldOrderClaimPolicy.shouldReacquireAfterPaymentError("checkout_claim_conflict"))
        assertEquals("held-payment:payment-1", HeldOrderClaimPolicy.paymentIdempotencyKey("payment-1"))
    }

    @Test
    fun `zero completion accepts only an exact never-paid zero bill`() {
        assertTrue(
            HeldOrderClaimPolicy.isExactZeroTotal(
                totalMinor = 0,
                paidMinor = 0,
                dueMinor = 0,
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.isExactZeroTotal(
                totalMinor = 10_000,
                paidMinor = 10_000,
                dueMinor = 0,
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.isExactZeroTotal(
                totalMinor = 0,
                paidMinor = 1,
                dueMinor = 0,
            ),
        )
        assertFalse(
            HeldOrderClaimPolicy.isExactZeroTotal(
                totalMinor = 0,
                paidMinor = 0,
                dueMinor = 1,
            ),
        )
    }

    @Test
    fun `zero completion retries reuse one key until the bill version changes`() {
        val first = HeldOrderClaimPolicy.zeroFinalizationIdempotencyKey("order-1", 7)

        assertEquals("held-zero:order-1:v7", first)
        assertEquals(first, HeldOrderClaimPolicy.zeroFinalizationIdempotencyKey("order-1", 7))
        assertFalse(first == HeldOrderClaimPolicy.zeroFinalizationIdempotencyKey("order-1", 8))
        assertFalse(first == HeldOrderClaimPolicy.zeroFinalizationIdempotencyKey("order-2", 7))
    }

    @Test
    fun `ambiguous and temporary responses keep confirmed money on the same replay`() {
        assertTrue(HeldOrderClaimPolicy.shouldReplayConfirmedPayment(null, "network_error"))
        assertTrue(HeldOrderClaimPolicy.shouldReplayConfirmedPayment(503, null))
        assertTrue(
            HeldOrderClaimPolicy.shouldReplayConfirmedPayment(409, "idempotency_in_progress"),
        )
        assertTrue(
            HeldOrderClaimPolicy.shouldReplayConfirmedPayment(409, "checkout_claim_conflict"),
        )
        assertFalse(HeldOrderClaimPolicy.shouldReplayConfirmedPayment(409, "checkout_claim_stale"))
        assertFalse(HeldOrderClaimPolicy.shouldReplayConfirmedPayment(422, "business_rule"))
    }
}
