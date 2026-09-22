package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.PaymentBundleLegResult
import cloud.dcompany.erp.core.net.PaymentBundleResult
import org.junit.Assert.assertThrows
import org.junit.Test

class SplitPaymentSettlementPolicyTest {
    private val plan = SplitPaymentPolicy.create(
        12_000,
        listOf(SplitPaymentLeg("cash", 5_000, 10_000), SplitPaymentLeg("upi", 7_000)),
    )
    private val row = LocalHeldOrderPaymentEntity(
        localId = "payment-1",
        targetOrderId = "order-1",
        method = SplitPaymentPolicy.encode(plan),
        amountMinor = 12_000,
        tenderedMinor = null,
        expectedTotalMinor = 12_000,
        expectedDueMinor = 12_000,
        claimToken = "claim-1",
        claimExpiresAtMillis = 2_000,
        claimOrderVersion = 7,
        shiftId = "shift-1",
        terminalId = "terminal-1",
        createdAtMillis = 1_000,
    )
    private val order = Order(
        id = "order-1",
        invoiceNo = "INV-1",
        fiscalYear = "2026-27",
        invoiceIssuedAt = "2026-09-21T12:00:00Z",
        status = "paid",
        type = "gaming",
        totalMinor = 12_000,
        paidMinor = 12_000,
        dueMinor = 0,
    )
    private val result = PaymentBundleResult(
        orderId = "order-1",
        shiftId = "shift-1",
        payments = listOf(
            PaymentBundleLegResult(
                id = "cash-payment",
                method = "cash",
                amountMinor = 5_000,
                tenderedMinor = 10_000,
                changeMinor = 5_000,
                paidAt = "2026-09-21T12:00:00Z",
            ),
            PaymentBundleLegResult(
                id = "upi-payment",
                method = "upi",
                amountMinor = 7_000,
                paidAt = "2026-09-21T12:00:00Z",
            ),
        ),
        totalAmountMinor = 12_000,
        paymentBreakdownMinor = mapOf(
            "cash" to 5_000,
            "card" to 0,
            "upi" to 7_000,
            "qr" to 0,
            "wallet" to 0,
        ),
        orderStatus = "paid",
        invoiceNo = "INV-1",
        fiscalYear = "2026-27",
        invoiceIssuedAt = "2026-09-21T12:00:00Z",
    )

    @Test
    fun `complete paid invoice response passes the durable resolution gate`() {
        requireAuthoritativeSplitSettlement(row, plan, result, order)
    }

    @Test
    fun `non-paid or invoice-incomplete result never resolves the durable row`() {
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(orderStatus = "held"),
                order,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(invoiceIssuedAt = null),
                order,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result,
                order.copy(status = "held", paidMinor = 0, dueMinor = 12_000),
            )
        }
    }

    @Test
    fun `rail and accounting mismatches never resolve the durable row`() {
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(
                    payments = result.payments.map {
                        if (it.method == "upi") it.copy(amountMinor = 6_999) else it
                    },
                ),
                order,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(
                    payments = listOf(
                        result.payments.first(),
                        result.payments.first().copy(id = "second-cash-payment"),
                    ),
                ),
                order,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(
                    paymentBreakdownMinor = result.paymentBreakdownMinor + ("upi" to 6_999),
                ),
                order,
            )
        }
    }

    @Test
    fun `all rails must share the authoritative invoice settlement time`() {
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(
                    payments = result.payments.map {
                        if (it.method == "upi") {
                            it.copy(paidAt = "2026-09-21T12:00:01Z")
                        } else {
                            it
                        }
                    },
                ),
                order,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(invoiceIssuedAt = "2026-09-21T12:00:01Z"),
                order,
            )
        }
    }

    @Test
    fun `bundle and final order must have the same complete invoice identity`() {
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result.copy(fiscalYear = "2027-28"),
                order,
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result,
                order.copy(fiscalYear = "2027-28"),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result,
                order.copy(invoiceIssuedAt = "2026-09-21T12:00:01Z"),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireAuthoritativeSplitSettlement(
                row,
                plan,
                result,
                order.copy(invoiceIssuedAt = null),
            )
        }
    }
}
