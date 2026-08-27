package cloud.dcompany.erp.core.db

import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.OrderLine
import cloud.dcompany.erp.core.net.OrderModifierSnapshot
import cloud.dcompany.erp.core.net.OrderVariantSnapshot
import cloud.dcompany.erp.core.net.PaymentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PosReceiptMappingTest {
    @Test
    fun paymentReceiptKeepsCanonicalMoneyAndCustomizationSnapshots() {
        val order = Order(
            id = "order-1",
            invoiceNo = "INV-1",
            status = "paid",
            type = "dine_in",
            sourceLabel = "Table 4",
            subtotalMinor = 15_000,
            discountMinor = 1_000,
            taxMinor = 500,
            roundOffMinor = 0,
            totalMinor = 14_500,
            customerName = "Guest",
            lines = listOf(
                OrderLine(
                    id = "line-1",
                    menuItemId = "item-1",
                    variantId = "variant-1",
                    variantSnapshot = OrderVariantSnapshot("variant-1", "Large", 2_000, 2_000),
                    modifiers = listOf(
                        OrderModifierSnapshot(
                            modifierId = "modifier-1",
                            name = "Extra shot",
                            qty = 2,
                            priceDeltaMinor = 500,
                            lineDeltaMinor = 1_000,
                        ),
                    ),
                    name = "Cold coffee",
                    qty = 1.0,
                    unitPriceMinor = 15_000,
                    lineTotalMinor = 15_000,
                    note = "No ice",
                ),
            ),
        )
        val payment = PaymentResult(
            id = "payment-1",
            orderId = order.id,
            shiftId = "shift-1",
            method = "cash",
            amountMinor = 14_500,
            billAmountMinor = 14_500,
            tenderedMinor = 20_000,
            changeMinor = 5_500,
            orderStatus = "paid",
            invoiceNo = "INV-1",
        )

        val receipt = paymentReceipt(order, payment, PosReceiptSource.HELD)

        assertEquals(14_500L, receipt.totalMinor)
        assertEquals(5_500L, receipt.changeMinor)
        assertEquals("Table 4", receipt.sourceLabel)
        val line = receipt.decodedLines().single()
        assertEquals("Large", line.variantSnapshot?.name)
        assertEquals("Extra shot", line.modifiers?.single()?.name)
        assertEquals("No ice", line.note)
        assertNotNull(receipt.linesJson)
    }
}
