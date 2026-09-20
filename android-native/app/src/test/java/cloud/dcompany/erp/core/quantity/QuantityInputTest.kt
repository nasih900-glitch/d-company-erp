package cloud.dcompany.erp.core.quantity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityInputTest {
    @Test
    fun `plain dot and comma decimals retain their intended magnitude`() {
        assertValid("1000", 1000.0, "1000")
        assertValid("1.000", 1.0, "1.000")
        assertValid("12,50", 12.5, "12.50")
        assertValid("0,005", 0.005, "0.005")
        assertValid("00,125", 0.125, "00.125")
        assertValid("01.000", 1.0, "01.000")
        assertValid("0.1250", 0.125, "0.1250")
        assertValid("0.1", 0.1, "0.1")
        assertValid("0.0001", 0.0001, "0.0001")
        assertValid("1.000000000000000000", 1.0, "1.000000000000000000")
    }

    @Test
    fun `three digit comma grouping is rejected as ambiguous`() {
        listOf("1,000", "-1,000", "01,000", "0001,000", "-01,000", "0012,500").forEach { raw ->
            val result = parseQuantityInput(raw, allowNegative = true)
            assertTrue(result is QuantityInputResult.Invalid)
            assertEquals(AMBIGUOUS_QUANTITY_MESSAGE, result.visibleErrorOrNull())
            assertNull(result.valueOrNull())
        }
    }

    @Test
    fun `malformed scientific and nonfinite values fail closed`() {
        listOf(
            "1,000.50",
            "1..2",
            "1,2,3",
            "12a50",
            "abc.",
            " 12.50 ",
            "NaN",
            "Infinity",
            "-Infinity",
            "1e3",
        ).forEach { raw ->
            assertTrue(raw, parseQuantityInput(raw, allowNegative = true) is QuantityInputResult.Invalid)
        }
        val overLimitWithSeparator = "1".repeat(128) + "."
        assertTrue(parseQuantityInput(overLimitWithSeparator) is QuantityInputResult.Invalid)
    }

    @Test
    fun `incomplete text stays distinguishable from a valid DTO value`() {
        assertTrue(parseQuantityInput("") is QuantityInputResult.Empty)
        listOf("-", ".", ",", "-.", "-,", "12.", "12,").forEach { raw ->
            assertTrue(raw, parseQuantityInput(raw, allowNegative = true) is QuantityInputResult.Incomplete)
            assertNull(parseQuantityInput(raw, allowNegative = true).valueOrNull())
        }
        assertTrue(parseQuantityInput("-12.") is QuantityInputResult.Invalid)
    }

    @Test
    fun `minus sign is accepted only for explicitly signed quantities`() {
        assertTrue(parseQuantityInput("-12,50") is QuantityInputResult.Invalid)
        assertValid("-12,50", -12.5, "-12.50", allowNegative = true)
    }

    @Test
    fun `values changed by the Double wire boundary are rejected`() {
        listOf(
            "9007199254740993",
            "-9007199254740993",
            "999999999999999999999999999999",
            "0.10000000000000001",
            "0,10000000000000001",
            "-0.10000000000000001",
        ).forEach { raw ->
            val result = parseQuantityInput(raw, allowNegative = true)
            assertTrue(raw, result is QuantityInputResult.Invalid)
            assertEquals(raw, QUANTITY_PRECISION_MESSAGE, result.visibleErrorOrNull())
            assertNull(raw, result.valueOrNull())
        }

        assertValid("9007199254740992", 9007199254740992.0, "9007199254740992")
        assertValid(
            "-9007199254740992",
            -9007199254740992.0,
            "-9007199254740992",
            allowNegative = true,
        )
    }

    @Test
    fun `editable formatting is plain precise and exposes nonfinite cache values`() {
        assertEquals("1000", quantityInputText(1000.0))
        assertEquals("0.125", quantityInputText(0.125))
        assertEquals("0.0001", quantityInputText(0.0001))
        assertEquals("12.3456", quantityInputText(12.3456))
        assertEquals("", quantityInputText(0.0, blankZero = true))
        assertEquals("5", percentageInputText(0.05))
        assertEquals("12.5", percentageInputText(0.125))
        assertEquals("NaN", quantityInputText(Double.NaN, blankZero = true))
        assertEquals("Infinity", percentageInputText(Double.POSITIVE_INFINITY))
    }

    private fun assertValid(
        raw: String,
        expectedValue: Double,
        expectedNormalized: String,
        allowNegative: Boolean = false,
    ) {
        val result = parseQuantityInput(raw, allowNegative)
        assertTrue("Expected valid result for $raw but got $result", result is QuantityInputResult.Valid)
        result as QuantityInputResult.Valid
        assertEquals(expectedValue, result.value, 0.0)
        assertEquals(expectedNormalized, result.normalized)
    }
}
