package cloud.dcompany.erp.ui.screens.inventory

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CostingCoverage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InventoryAccountingContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `batch retains branch identity and exact FIFO valuation`() {
        val batch = json.decodeFromString<Batch>(
            """{
              "id":"batch-1","ingredient_id":"milk","branch_id":"main",
              "received_at":"2026-08-27T09:00:00Z","qty_on_hand":8,
              "cost_per_unit_minor":500,"lot_code":"LOT-1"
            }""",
        )
        val valuation = json.decodeFromString<InventoryValuation>(
            """{
              "as_of":"2026-08-27","branch_id":"main",
              "lines":[{"ingredient_id":"milk","sku":"MILK","name":"Milk",
                "base_unit":"unit","current_qty":8,"avg_cost_minor":350,
                "valuation_minor":4000,"reorder_threshold":2,"is_low_stock":false}],
              "total_valuation_minor":4000,"low_stock_count":0
            }""",
        )

        assertEquals("main", batch.branchId)
        assertEquals("main", valuation.branchId)
        // Deliberately proves the UI uses the authoritative batch sum rather
        // than the wrong 8 * 350 = 2800 approximation.
        assertEquals(4_000L, valuation.lines.single().valuationMinor)
        assertEquals(4_000L, valuation.totalValuationMinor)
    }

    @Test
    fun `offline GRN wire freezes received time and supplier document total`() {
        val body = GrnBody(
            branchId = "main",
            supplierId = "supplier-1",
            supplierInvoiceNo = "INV-44",
            supplierInvoiceAmountMinor = 12_345,
            receivedAt = "2026-08-27T09:15:00Z",
            notes = "morning delivery",
            lines = listOf(GrnLineBody("milk", 2.5, 4_000, lotCode = "LOT-44")),
        )
        val encoded = json.encodeToString(body)

        assertTrue(encoded.contains("\"supplier_invoice_amount_minor\":12345"))
        assertTrue(encoded.contains("\"received_at\":\"2026-08-27T09:15:00Z\""))
        assertTrue(encoded.contains("\"unit_cost_minor\":4000"))
        assertTrue(encoded.contains("\"lot_code\":\"LOT-44\""))
    }

    @Test
    fun `GRN values round each line HALF_UP before summing`() {
        val first = grnLineTotalMinor("0.005", 100)
        val second = grnLineTotalMinor("0.005", 100)

        assertEquals(1L, first)
        assertEquals(1L, second)
        assertEquals(2L, listOf(first, second).sumOf { it ?: 0L })
        assertEquals(
            2L,
            grnReceiptTotalMinor(listOf("0.005" to 100L, "0.005" to 100L)),
        )
        assertEquals(123L, grnLineTotalMinor("1.2345", 100))
        assertEquals(1L, grnLineTotalMinor(0.0001, 5_000L))
        assertEquals(
            2L,
            grnWireReceiptTotalMinor(
                listOf(
                    GrnLineBody("milk", 0.005, 100),
                    GrnLineBody("sugar", 0.005, 100),
                ),
            ),
        )
        assertFalse(isSupportedGrnQuantity("1.23456"))
        assertNull(grnLineTotalMinor("1.23456", 100))
        assertNull(
            grnReceiptTotalMinor(
                listOf("1" to Long.MAX_VALUE, "1" to 1L),
            ),
        )
    }

    @Test
    fun `single sided transfer cannot enter the Android adjustment outbox`() {
        assertFalse(ADJUSTMENT_TYPES.any { it.first == "transfer" })
        assertFalse(isSupportedAdjustmentType("transfer"))
        assertTrue(TRANSFER_UNAVAILABLE_MESSAGE.contains("source and destination"))
        try {
            adjustmentDelta("transfer", 4.0)
            fail("unsupported transfer should fail closed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("source and destination"))
        }
    }

    @Test
    fun `recipe percent is serialized as fraction and yield is explicit`() {
        val body = RecipeCreateBody(
            menuItemId = "latte",
            name = "Latte recipe",
            yieldQty = 2.0,
            lines = listOf(RecipeLineBody("milk", 400.0, 0.05)),
        )
        val encoded = ApiClient.json.encodeToString(body)

        assertTrue(encoded.contains("\"yield_qty\":2.0"))
        assertTrue(encoded.contains("\"wastage_pct\":0.05"))
    }

    @Test
    fun `costing warning never presents incomplete COGS as reliable`() {
        val incomplete = CostingCoverage(
            inventoryItemCount = 6,
            fullyCostedItemCount = 4,
            incompleteItemCount = 2,
            missingRecipeCount = 1,
            emptyRecipeCount = 1,
            isComplete = false,
        )
        assertFalse(incomplete.isComplete)
        assertEquals("2 menu items are not fully costed", incomplete.warningTitle)
        assertTrue(incomplete.warningDetail.contains("may be understated"))

        val unknownValue = InventoryUiState(
            ingredients = listOf(
                IngredientRow("milk", "MILK", "Milk", "ml", valuationMinor = null),
            ),
        )
        assertNull(unknownValue.stockValueMinor)
    }
}
