package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.CheckoutClaimResult
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.OrderLine
import cloud.dcompany.erp.core.net.OrderModifierSnapshot
import cloud.dcompany.erp.core.net.OrderVariantSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PosHeldOrderReviewTest {

    @Test
    fun `authoritative held order maps item detail and every settlement reduction`() {
        val review = authoritativeHeldOrderReview(
            order = heldOrder(),
            shiftIdAtReview = "shift-1",
            fallbackSourceLabel = "PS5 Station 2",
        )

        assertEquals("order-1", review.orderId)
        assertEquals("shift-1", review.shiftIdAtReview)
        assertEquals("PS5 Station 2", review.sourceLabel)
        assertEquals(20_000L, review.subtotalMinor)
        assertEquals(500L, review.lineOrMembershipDiscountMinor)
        assertEquals(2_000L, review.manualDiscountMinor)
        assertEquals(1_000L, review.pointsRedeemedMinor)
        assertEquals(16_500L, review.totalMinor)
        assertEquals(16_500L, review.dueMinor)
        assertEquals(19_500L, review.maxManualDiscountMinor)
        assertEquals("One hour session", review.lines.single().name)
        assertEquals("Dual mode", review.lines.single().variantName)
        assertEquals(listOf("Extra controller ×2"), review.lines.single().modifiers)
        assertEquals("Controller 2", review.lines.single().note)
    }

    @Test
    fun `review rejects a non-held or internally inconsistent bill`() {
        assertThrows(IllegalArgumentException::class.java) {
            authoritativeHeldOrderReview(
                heldOrder().copy(status = "open"),
                shiftIdAtReview = "shift-1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            authoritativeHeldOrderReview(
                heldOrder().copy(dueMinor = 16_499L),
                shiftIdAtReview = "shift-1",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            authoritativeHeldOrderReview(
                heldOrder().copy(discountMinor = 2_999L),
                shiftIdAtReview = "shift-1",
            )
        }
    }

    @Test
    fun `discount mutation is an absolute optimistic set with a stable retry key`() {
        val review = authoritativeHeldOrderReview(heldOrder(), "shift-1")
        val first = heldOrderDiscountMutation(review, 2_500L)
        val retry = heldOrderDiscountMutation(review, 2_500L)

        assertEquals(first, retry)
        assertEquals(2_500L, first.body.manualDiscountMinor)
        assertEquals(7L, first.body.expectedCheckoutVersion)
        assertNotEquals(first.idempotencyKey, heldOrderDiscountMutation(review, 0L).idempotencyKey)
        assertNotEquals(
            first.idempotencyKey,
            heldOrderDiscountMutation(review.copy(checkoutVersion = 8L), 2_500L).idempotencyKey,
        )
        assertThrows(IllegalArgumentException::class.java) {
            heldOrderDiscountMutation(review, review.maxManualDiscountMinor + 1L)
        }
    }

    @Test
    fun `claim must match reviewed order version and exact money snapshot`() {
        val review = authoritativeHeldOrderReview(heldOrder(), "shift-1")
        val claim = claim()

        assertTrue(heldClaimMatchesReview(review, claim))
        assertFalse(heldClaimMatchesReview(review, claim.copy(orderVersion = 8L)))
        assertFalse(heldClaimMatchesReview(review, claim.copy(orderTotalMinor = 16_400L)))
        assertFalse(heldClaimMatchesReview(review, claim.copy(dueMinor = 16_400L)))
        assertFalse(heldClaimMatchesReview(review, claim.copy(orderId = "other-order")))
    }

    @Test
    fun `rupee discount entry supports apply clear decimals and reviewed ceiling`() {
        assertNull(heldDiscountEntryError("125.50", 19_500L))
        assertNull(heldDiscountEntryError("", 19_500L))
        assertTrue(heldDiscountEntryError("12.345", 19_500L)!!.contains("valid rupee"))
        assertTrue(heldDiscountEntryError("195.01", 19_500L)!!.contains("cannot exceed"))
    }

    private fun heldOrder() = Order(
        id = "order-1",
        invoiceNo = "INV-101",
        status = "held",
        type = "gaming",
        sourceLabel = null,
        subtotalMinor = 20_000L,
        discountMinor = 3_500L,
        manualDiscountMinor = 2_000L,
        pointsRedeemedMinor = 1_000L,
        pointsRedeemed = 100,
        taxMinor = 0L,
        roundOffMinor = 0L,
        tipMinor = 0L,
        totalMinor = 16_500L,
        paidMinor = 0L,
        dueMinor = 16_500L,
        checkoutVersion = 7L,
        lines = listOf(
            OrderLine(
                id = "line-1",
                name = "One hour session",
                qty = 1.0,
                unitPriceMinor = 20_000L,
                lineTotalMinor = 19_500L,
                discountMinor = 500L,
                variantSnapshot = OrderVariantSnapshot(
                    variantId = "dual",
                    name = "Dual mode",
                ),
                modifiers = listOf(
                    OrderModifierSnapshot(
                        modifierId = "controller",
                        name = "Extra controller",
                        qty = 2,
                    ),
                ),
                note = "Controller 2",
            ),
        ),
    )

    private fun claim() = CheckoutClaimResult(
        claimId = "claim-1",
        orderId = "order-1",
        claimToken = "token-1",
        expiresAt = "2099-01-01T00:00:00Z",
        orderTotalMinor = 16_500L,
        paidMinor = 0L,
        dueMinor = 16_500L,
        orderVersion = 7L,
        claimantUserId = "user-1",
        terminalId = "terminal-1",
    )
}
