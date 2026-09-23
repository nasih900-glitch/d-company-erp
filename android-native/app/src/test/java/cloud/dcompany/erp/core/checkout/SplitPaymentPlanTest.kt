package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.net.PaymentBundleLegRequest
import cloud.dcompany.erp.core.net.PaymentBundleRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPaymentPlanTest {

    @Test
    fun `exact two-rail plan is canonical and restart decoding preserves cash tender`() {
        val plan = SplitPaymentPolicy.create(
            expectedDueMinor = 12_000,
            legs = listOf(
                SplitPaymentLeg("upi", 7_000),
                SplitPaymentLeg("cash", 5_000, tenderedMinor = 10_000),
            ),
        )

        assertEquals(listOf("cash", "upi"), plan.legs.map { it.method })
        assertEquals(12_000L, plan.totalMinor)
        assertEquals(10_000L, plan.cashTenderedMinor)
        assertEquals(5_000L, plan.cashChangeMinor)

        val persisted = SplitPaymentPolicy.encode(plan)
        val afterRestart = requireNotNull(SplitPaymentPolicy.decodeStoredMethod(persisted))
        assertEquals(plan.legs, afterRestart.legs)
        assertEquals(persisted, SplitPaymentPolicy.encode(afterRestart))
        assertEquals("Cash ₹50 + UPI ₹70", afterRestart.displayLabel())
    }

    @Test
    fun `under allocation over allocation and duplicate rails are rejected`() {
        val under = assertThrows(IllegalArgumentException::class.java) {
            SplitPaymentPolicy.create(
                10_000,
                listOf(SplitPaymentLeg("cash", 4_000, 4_000), SplitPaymentLeg("upi", 5_000)),
            )
        }
        assertEquals("Split amounts must equal the exact amount due.", under.message)

        val over = assertThrows(IllegalArgumentException::class.java) {
            SplitPaymentPolicy.create(
                10_000,
                listOf(SplitPaymentLeg("cash", 6_000, 6_000), SplitPaymentLeg("upi", 5_000)),
            )
        }
        assertEquals("Split amounts must equal the exact amount due.", over.message)

        val duplicate = assertThrows(IllegalArgumentException::class.java) {
            SplitPaymentPolicy.create(
                10_000,
                listOf(SplitPaymentLeg("upi", 4_000), SplitPaymentLeg("UPI", 6_000)),
            )
        }
        assertEquals("Each split payment method can be used only once.", duplicate.message)
    }

    @Test
    fun `cash tender belongs only to cash and must cover its bill allocation`() {
        val short = assertThrows(IllegalArgumentException::class.java) {
            SplitPaymentPolicy.create(
                10_000,
                listOf(SplitPaymentLeg("cash", 6_000, 5_999), SplitPaymentLeg("card", 4_000)),
            )
        }
        assertTrue(short.message.orEmpty().contains("cover the cash part"))

        val nonCashTender = assertThrows(IllegalArgumentException::class.java) {
            SplitPaymentPolicy.create(
                10_000,
                listOf(SplitPaymentLeg("upi", 6_000, 6_000), SplitPaymentLeg("card", 4_000)),
            )
        }
        assertTrue(nonCashTender.message.orEmpty().contains("only for the cash"))
    }

    @Test
    fun `all five backend rails can settle one bill once each`() {
        val plan = SplitPaymentPolicy.create(
            15_000,
            listOf(
                SplitPaymentLeg("wallet", 1_000),
                SplitPaymentLeg("qr", 2_000),
                SplitPaymentLeg("upi", 3_000),
                SplitPaymentLeg("card", 4_000),
                SplitPaymentLeg("cash", 5_000, 5_000),
            ),
        )

        assertEquals(SplitPaymentPolicy.supportedMethods, plan.legs.map { it.method })
        assertEquals(15_000L, plan.totalMinor)
    }

    @Test
    fun `plain single method remains outside split decoding and bad split versions fail closed`() {
        assertNull(SplitPaymentPolicy.decodeStoredMethod("cash"))
        assertThrows(IllegalArgumentException::class.java) {
            SplitPaymentPolicy.decodeStoredMethod("split:v2|cash,5000,5000|upi,5000,")
        }
    }

    @Test
    fun `bundle request contains one complete plan and has no tip field`() {
        val request = PaymentBundleRequest(
            payments = listOf(
                PaymentBundleLegRequest("cash", 5_000, tenderedMinor = 10_000),
                PaymentBundleLegRequest("upi", 7_000),
            ),
            expectedTotalMinor = 12_000,
            expectedDueMinor = 12_000,
        )
        val json = Json.encodeToString(request)

        assertTrue(json.contains("\"payments\""))
        assertTrue(json.contains("\"expected_due_minor\":12000"))
        assertFalse(json.contains("tip"))
    }

    @Test
    fun `retry identity and canonical body remain stable`() {
        val first = SplitPaymentPolicy.create(
            12_000,
            listOf(SplitPaymentLeg("upi", 7_000), SplitPaymentLeg("cash", 5_000, 10_000)),
        )
        val second = SplitPaymentPolicy.create(
            12_000,
            listOf(SplitPaymentLeg("cash", 5_000, 10_000), SplitPaymentLeg("upi", 7_000)),
        )

        assertEquals(SplitPaymentPolicy.encode(first), SplitPaymentPolicy.encode(second))
        assertEquals(
            HeldOrderClaimPolicy.paymentIdempotencyKey("payment-1"),
            HeldOrderClaimPolicy.paymentIdempotencyKey("payment-1"),
        )
    }
}
