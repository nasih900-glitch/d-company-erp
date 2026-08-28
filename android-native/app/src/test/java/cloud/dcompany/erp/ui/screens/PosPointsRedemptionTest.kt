package cloud.dcompany.erp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PosPointsRedemptionTest {

    @Test
    fun `same versioned absolute set reuses the exact idempotency key`() {
        val first = pointsRedemptionIdempotencyKey(
            localId = "local-1",
            orderId = "order-1",
            checkoutVersion = 7,
            points = 20,
        )
        val retry = pointsRedemptionIdempotencyKey(
            localId = "local-1",
            orderId = "order-1",
            checkoutVersion = 7,
            points = 20,
        )

        assertEquals(first, retry)
    }

    @Test
    fun `a changed absolute set or checkout version cannot collide`() {
        val original = pointsRedemptionIdempotencyKey("local-1", "order-1", 7, 20)

        assertNotEquals(original, pointsRedemptionIdempotencyKey("local-1", "order-1", 7, 10))
        assertNotEquals(original, pointsRedemptionIdempotencyKey("local-1", "order-1", 8, 20))
    }

    @Test
    fun `invalid identity version and points are refused locally`() {
        assertThrows(IllegalArgumentException::class.java) {
            pointsRedemptionIdempotencyKey("", "order-1", 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pointsRedemptionIdempotencyKey("local-1", "order-1", 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pointsRedemptionIdempotencyKey("local-1", "order-1", 1, -1)
        }
    }

    @Test
    fun `points entry rejects malformed and known over-balance values`() {
        assertEquals("Enter a whole number of points.", pointsEntryError("", 10))
        assertEquals("Enter a whole number of points.", pointsEntryError("abc", 10))
        assertEquals("Only 10 points are shown available.", pointsEntryError("20", 10))
        assertEquals(null, pointsEntryError("10", 10))
        assertEquals(null, pointsEntryError("20", null))
    }
}
