package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CheckoutClaimResult
import cloud.dcompany.erp.core.net.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    fun `absolute expiry remains available for durable settlement metadata`() {
        assertEquals(1_787_652_600_000L, HeldOrderClaimPolicy.claimExpiryMillis(claim))
        assertTrue(HeldOrderClaimPolicy.claimExpiryMillis(claim.copy(expiresAt = "invalid")) == null)
    }

    @Test
    fun `confirmation measures from request start without using tablet wall time`() {
        val requestStarted = 42_000L
        val proof = requireNotNull(CheckoutClaimWindow.from(
            claim.copy(serverTime = "2026-08-25T10:09:00Z"),
            requestStarted,
        ))
        // The server grants 60 seconds. Request transit and processing spend
        // the same budget; an exactly 30 second remainder must fail closed.
        assertTrue(proof.hasConfirmationWindow(claim.claimToken, requestStarted + 29_999))
        assertFalse(proof.hasConfirmationWindow(claim.claimToken, requestStarted + 30_000))
        assertFalse(proof.hasConfirmationWindow(claim.claimToken, requestStarted + 61_000))
        assertFalse(proof.hasConfirmationWindow(claim.claimToken, requestStarted - 1))
        assertFalse(proof.hasConfirmationWindow("rotated-token", requestStarted))
        // Neither the very large absolute server epoch nor a much smaller
        // elapsed clock is compared to the other's time base.
        assertTrue(proof.hasConfirmationWindow(claim.claimToken, requestStarted))
        assertFalse(proof.hasConfirmationWindow(claim.claimToken, Long.MAX_VALUE))
    }

    @Test
    fun `clock proof requires a fresh positive lifetime within the server lease limit`() {
        for (serverTime in listOf(null, "malformed", claim.expiresAt, "2026-08-25T09:59:59.999Z")) {
            assertEquals(null, CheckoutClaimWindow.from(claim.copy(serverTime = serverTime), 0L))
        }
        assertEquals(null, CheckoutClaimWindow.from(
            claim.copy(serverTime = "2026-08-25T10:10:01Z"), 0L,
        ))
        assertEquals(null, CheckoutClaimWindow.from(
            claim.copy(serverTime = "2026-08-25T10:00:00Z"), -1L,
        ))
        assertEquals(null, CheckoutClaimWindow.from(
            claim.copy(serverTime = "2026-08-25T10:00:00Z", expiresAt = "invalid"), 0L,
        ))
        assertTrue(requireNotNull(CheckoutClaimWindow.from(
            claim.copy(serverTime = "2026-08-25T10:00:00Z"), 0L,
        )).hasConfirmationWindow(claim.claimToken, 0L))
    }

    @Test
    fun `Room alone cannot make a positive direct bill collectable after restart`() {
        val fresh = requireNotNull(CheckoutClaimWindow.from(
            claim.copy(serverTime = "2026-08-25T10:09:00Z"), 1_000L,
        ))
        assertTrue(DirectOrderPublishPolicy.canPresentPreparedCheckout(
            order.totalMinor, order.dueMinor, claim.claimToken, fresh, 2_000L,
        ))
        assertFalse(DirectOrderPublishPolicy.canPresentPreparedCheckout(
            order.totalMinor, order.dueMinor, claim.claimToken, null, 2_000L,
        ))
        assertFalse(DirectOrderPublishPolicy.canPresentPreparedCheckout(
            order.totalMinor, order.dueMinor, "other-token", fresh, 2_000L,
        ))
        assertFalse(DirectOrderPublishPolicy.canPresentPreparedCheckout(
            order.totalMinor, order.dueMinor, claim.claimToken, fresh, 31_000L,
        ))
        // Exact-zero recovery is still available after restart.
        assertTrue(DirectOrderPublishPolicy.canPresentPreparedCheckout(
            0L, 0L, claim.claimToken, null, 2_000L,
        ))
    }

    @Test
    fun `rejected direct payment retains its need for a fresh claim`() {
        val cleared = payment.copy(requiresCheckoutClaim = false, claimToken = null)
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(cleared))
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(cleared.copy(claimOrderVersion = null)))
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(cleared.copy(claimExpiresAtMillis = null)))
        val legacy = cleared.copy(claimOrderVersion = null, claimExpiresAtMillis = null)
        assertFalse(DirectOrderPublishPolicy.paymentNeedsClaim(legacy))
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(legacy.copy(claimToken = claim.claimToken)))
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(legacy.copy(requiresCheckoutClaim = true)))
    }

    @Test
    fun `tokenless exact replay requires terminal status of the same canonical bill`() {
        val paid = Order(id = order.id, status = "paid")
        assertTrue(HeldOrderClaimPolicy.canReplayWithoutClaim(order.id, paid))
        assertTrue(HeldOrderClaimPolicy.canReplayWithoutClaim(order.id, paid.copy(status = "refunded")))
        assertFalse(HeldOrderClaimPolicy.canReplayWithoutClaim(order.id, paid.copy(id = "another")))
        for (status in listOf("open", "held", "void", "")) {
            assertFalse(HeldOrderClaimPolicy.canReplayWithoutClaim(order.id, paid.copy(status = status)))
        }
    }

    @Test
    fun `a definitive order GET failure after an accepted POST must stay ambiguous`() = runBlocking {
        val denied = ApiException("Order receipt read refused", status = 403)
        val result = runCatching { HeldOrderClaimPolicy.readOrderAfterAcceptedPayment { throw denied } }
        val pending = result.exceptionOrNull()
        assertTrue(pending is IllegalStateException)
        assertTrue(pending?.cause === denied)
        val cancellation = CancellationException("Checkout recovery cancelled")
        val cancelled = runCatching {
            HeldOrderClaimPolicy.readOrderAfterAcceptedPayment { throw cancellation }
        }
        assertTrue(cancelled.exceptionOrNull() === cancellation)
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
