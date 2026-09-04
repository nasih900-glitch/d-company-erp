package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.CheckoutClaimResult
import cloud.dcompany.erp.core.net.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectOrderPublishPolicyTest {
    private val order = Order(
        id = "order-1",
        status = "open",
        type = "takeaway",
        subtotalMinor = 12_000,
        discountMinor = 1_000,
        totalMinor = 11_000,
        dueMinor = 11_000,
        checkoutVersion = 7,
    )
    private val claim = CheckoutClaimResult(
        claimId = "claim-1",
        orderId = order.id,
        claimToken = "opaque-token",
        expiresAt = "2026-09-03T20:00:00Z",
        orderTotalMinor = order.totalMinor,
        paidMinor = 0,
        dueMinor = order.dueMinor,
        orderVersion = 8,
        claimantUserId = "user-1",
        terminalId = "terminal-1",
    )

    @Test
    fun `publication key is stable and local-order scoped`() {
        assertEquals("direct-publish:local-1", DirectOrderPublishPolicy.idempotencyKey("local-1"))
        assertEquals(
            DirectOrderPublishPolicy.idempotencyKey("local-1"),
            DirectOrderPublishPolicy.idempotencyKey("local-1"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DirectOrderPublishPolicy.idempotencyKey(" ")
        }
    }

    @Test
    fun `response-loss recovery reconstructs only the exact pre-publish version`() {
        assertEquals(
            7,
            DirectOrderPublishPolicy.expectedVersionForReplay(SyncState.PREPARING, 7),
        )
        assertEquals(
            7,
            DirectOrderPublishPolicy.expectedVersionForReplay(
                SyncState.PENDING,
                7,
                hasDurableClaim = false,
            ),
        )
        assertEquals(
            7,
            DirectOrderPublishPolicy.expectedVersionForReplay(
                SyncState.PENDING,
                8,
                hasDurableClaim = true,
            ),
        )
        assertEquals(
            7,
            DirectOrderPublishPolicy.expectedVersionForReplay(SyncState.AWAITING_PAYMENT, 8),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DirectOrderPublishPolicy.expectedVersionForReplay(SyncState.AWAITING_PAYMENT, 1)
        }
        assertThrows(IllegalStateException::class.java) {
            DirectOrderPublishPolicy.expectedVersionForReplay(SyncState.DRAFT, 7)
        }
    }

    @Test
    fun `claim must represent the exact reviewed unpaid bill and one version bump`() {
        assertTrue(DirectOrderPublishPolicy.matchesPricedOrder(order, 7, claim))
        assertTrue(
            DirectOrderPublishPolicy.matchesPricedOrder(
                order.copy(status = "held", checkoutVersion = 8),
                7,
                claim.copy(reused = true),
            ),
        )
        assertFalse(
            DirectOrderPublishPolicy.matchesPricedOrder(order, 7, claim.copy(orderVersion = 9)),
        )
        assertFalse(
            DirectOrderPublishPolicy.matchesPricedOrder(order, 7, claim.copy(dueMinor = 10_999)),
        )
        assertFalse(
            DirectOrderPublishPolicy.matchesPricedOrder(order, 7, claim.copy(claimToken = "")),
        )
        assertFalse(
            DirectOrderPublishPolicy.matchesPricedOrder(
                order.copy(paidMinor = 1),
                7,
                claim.copy(paidMinor = 1),
            ),
        )
    }

    @Test
    fun `new direct settlement requires its durable claim while legacy direct remains compatible`() {
        val legacyDirect = payment(requiresCheckoutClaim = false, claimToken = null)
        val publishedDirect = payment(requiresCheckoutClaim = false, claimToken = "token")
        val held = payment(requiresCheckoutClaim = true, claimToken = null)

        assertFalse(DirectOrderPublishPolicy.paymentNeedsClaim(legacyDirect))
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(publishedDirect))
        assertTrue(DirectOrderPublishPolicy.paymentNeedsClaim(held))
    }

    private fun payment(
        requiresCheckoutClaim: Boolean,
        claimToken: String?,
    ) = LocalHeldOrderPaymentEntity(
        localId = "payment-1",
        targetOrderId = order.id,
        method = "cash",
        amountMinor = order.dueMinor,
        tenderedMinor = order.dueMinor,
        expectedTotalMinor = order.totalMinor,
        expectedDueMinor = order.dueMinor,
        claimToken = claimToken,
        requiresCheckoutClaim = requiresCheckoutClaim,
        createdAtMillis = 1,
    )
}
