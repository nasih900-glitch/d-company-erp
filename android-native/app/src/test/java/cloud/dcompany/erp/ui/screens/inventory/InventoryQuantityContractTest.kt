package cloud.dcompany.erp.ui.screens.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryQuantityContractTest {
    @Test
    fun `recipe draft validation and DTO conversion use the same quantity parser`() {
        val ambiguousReplacement = RecipeLineDraft("milk", "1,000", "0")
        assertTrue(ambiguousReplacement.error(1).orEmpty().contains("Ambiguous quantity"))
        assertNull(ambiguousReplacement.toBody())

        val corrected = RecipeLineDraft("milk", "1000", "12,50")
        assertNull(corrected.error(1))
        assertEquals(1000.0, corrected.toBody()?.qty ?: error("missing body"), 0.0)
        assertEquals(0.125, corrected.toBody()?.wastagePct ?: error("missing body"), 0.0)

        val explicitDot = RecipeLineDraft("milk", "1.000", "0")
        assertNull(explicitDot.error(1))
        assertEquals(1.0, explicitDot.toBody()?.qty ?: error("missing body"), 0.0)
    }

    @Test
    fun `recipe quantities must be positive and wastage must stay in range`() {
        listOf("", "0", "-1", "NaN", "1e3").forEach { qty ->
            val draft = RecipeLineDraft("milk", qty, "0")
            assertNotNull(qty, draft.error(1))
            assertNull(qty, draft.toBody())
        }
        listOf("-0.1", "100.0001", "Infinity").forEach { wastage ->
            val draft = RecipeLineDraft("milk", "1", wastage)
            assertNotNull(wastage, draft.error(1))
            assertNull(wastage, draft.toBody())
        }
    }

    @Test
    fun `optional reorder values distinguish blank from malformed text`() {
        assertEquals(0.0, optionalQuantityValue("") ?: error("blank should be zero"), 0.0)
        assertEquals(0.0, optionalQuantityValue("0") ?: error("zero should be valid"), 0.0)
        assertEquals(12.5, optionalQuantityValue("12,50") ?: error("comma decimal"), 0.0)
        assertNull(optionalQuantityValue("1,000"))
        assertNull(optionalQuantityValue("bad"))
        assertNull(optionalQuantityValue("-1"))
        assertNull(optionalQuantityValue(" "))
    }

    @Test
    fun `GRN validates normalized precision before creating a body`() {
        val commaDecimal = GrnLineDraft("milk", "12,50", "10.00")
        assertNull(commaDecimal.validationError(1))
        assertEquals("12.50", commaDecimal.normalizedQuantity())
        assertEquals(12.5, commaDecimal.toBody()?.qty ?: error("missing body"), 0.0)
        assertEquals(12_500L, grnLineTotalMinor(commaDecimal.normalizedQuantity().orEmpty(), 1_000L))

        val fourPlaces = GrnLineDraft("milk", "0.1250", "8.00")
        assertNull(fourPlaces.validationError(1))
        assertEquals(100L, grnLineTotalMinor(fourPlaces.normalizedQuantity().orEmpty(), 800L))

        listOf("1,000", "0", "-1", "1.23456", "1.00000", "12345678901", "Infinity", "1e3").forEach { qty ->
            val draft = GrnLineDraft("milk", qty, "1.00")
            assertNotNull(qty, draft.validationError(1))
            assertNull(qty, draft.toBody())
        }
    }

    @Test
    fun `GRN comma decimals retain exact HALF_UP line and receipt totals`() {
        val first = GrnLineDraft("milk", "0,005", "1.00")
        val second = GrnLineDraft("sugar", "0.005", "1.00")
        val normalizedLines = listOf(first, second).map {
            it.normalizedQuantity().orEmpty() to 100L
        }

        assertEquals(1L, grnLineTotalMinor(first.normalizedQuantity().orEmpty(), 100L))
        assertEquals(2L, grnReceiptTotalMinor(normalizedLines))
    }

    @Test
    fun `stored quantities roundtrip through ungrouped editable text`() {
        listOf(1000.0, 0.125, 0.0001, 12.3456).forEach { value ->
            val text = value.asQtyInput()
            val body = GrnLineDraft("milk", text, "1.00").toBody()
            assertEquals(value.toString(), value, body?.qty ?: error("$text did not roundtrip"), 0.0)
        }
        assertEquals("NaN", Double.NaN.asQtyInput())
        assertNull(GrnLineDraft("milk", Double.NaN.asQtyInput(), "1.00").toBody())
    }

    @Test
    fun `precision loss never creates a request body or reuses an earlier value`() {
        val priorRecipe = RecipeLineDraft("milk", "7", "0")
        assertEquals(7.0, priorRecipe.toBody()?.qty ?: error("missing prior body"), 0.0)

        listOf("9007199254740993", "0.10000000000000001").forEach { raw ->
            val replacement = RecipeLineDraft("milk", raw, "0")
            assertTrue(raw, replacement.error(1).orEmpty().contains("more precision"))
            assertNull(raw, replacement.toBody())
            assertNull(raw, optionalQuantityValue(raw))
        }

        val impreciseWastage = RecipeLineDraft("milk", "1", "0.10000000000000001")
        assertTrue(impreciseWastage.error(1).orEmpty().contains("more precision"))
        assertNull(impreciseWastage.toBody())

        val impreciseGrn = GrnLineDraft("milk", "0.10000000000000001", "1.00")
        assertTrue(impreciseGrn.validationError(1).orEmpty().contains("more precision"))
        assertNull(impreciseGrn.normalizedQuantity())
        assertNull(impreciseGrn.toBody())
    }
}
