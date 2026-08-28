package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.PosReceiptEntity
import cloud.dcompany.erp.core.net.OrderLine
import cloud.dcompany.erp.core.net.OrderModifierSnapshot
import cloud.dcompany.erp.core.net.OrderVariantSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosReceiptPrintTest {

    @Test
    fun printableReceiptKeepsFinancialAndCustomizationEvidenceAndEscapesText() {
        val lines = Json { encodeDefaults = true; explicitNulls = true }.encodeToString(
            listOf(
                OrderLine(
                    id = "line-1",
                    name = "Coffee <large>",
                    qty = 2.0,
                    lineTotalMinor = 36_000,
                    variantSnapshot = OrderVariantSnapshot("v1", "Large & cold", 0, 0),
                    modifiers = listOf(
                        OrderModifierSnapshot(
                            modifierId = "m1",
                            name = "Extra shot",
                            qty = 2,
                            priceDeltaMinor = 500,
                            lineDeltaMinor = 1_000,
                        ),
                    ),
                    note = "No 'ice'",
                ),
            ),
        )
        val html = posReceiptPrintHtml(receipt(lines))

        assertTrue(html.contains("INV-42"))
        assertTrue(html.contains("Coffee &lt;large&gt;"))
        assertTrue(html.contains("Large &amp; cold"))
        assertTrue(html.contains("Extra shot x2"))
        assertTrue(html.contains("No &#39;ice&#39;"))
        assertTrue(html.contains("₹350.00"))
        assertTrue(html.contains("-₹10.00"))
        assertTrue(html.contains("Cash received"))
        assertTrue(html.contains("₹50.00"))
        assertFalse(html.contains("Coffee <large>"))
    }

    private fun receipt(linesJson: String) = PosReceiptEntity(
        receiptId = "payment-1",
        orderId = "order-1",
        paymentId = "payment-1",
        shiftId = "shift-1",
        sourceKind = "direct_pos",
        sourceLabel = "Main POS",
        customerName = "Guest & Co",
        customerPhone = "1234567",
        orderNote = "Take <away>",
        subtotalMinor = 36_000,
        discountMinor = 1_000,
        taxMinor = 0,
        roundOffMinor = 0,
        totalMinor = 35_000,
        dueBeforePaymentMinor = 35_000,
        method = "cash",
        amountMinor = 35_000,
        billAmountMinor = 35_000,
        tipMinor = 0,
        tenderedMinor = 40_000,
        changeMinor = 5_000,
        refExternal = null,
        paidAt = "2026-08-27T18:00:00Z",
        orderStatus = "paid",
        invoiceNo = "INV-42",
        fiscalYear = "2026-27",
        invoiceIssuedAt = "2026-08-27T18:00:00Z",
        linesJson = linesJson,
        createdAtMillis = 1,
    )
}
