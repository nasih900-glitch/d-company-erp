package cloud.dcompany.erp.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyInputTest {
    @Test
    fun parsesExactRupeesAndPaiseWithoutFloatingPoint() {
        assertEquals(0L, parseRupeesToMinor("0"))
        assertEquals(10L, parseRupeesToMinor("0.10"))
        assertEquals(12_345L, parseRupeesToMinor("123.45"))
        assertEquals(12_300L, parseRupeesToMinor("123."))
        assertEquals(26_696L, parseRupeesToMinor("266.96"))
        assertEquals(29L, parseRupeesToMinor("0.29"))
        assertEquals(1_250L, parseRupeesToMinor("12,50"))
    }

    @Test
    fun rejectsValuesThatCannotRepresentMoneySafely() {
        assertNull(parseRupeesToMinor(""))
        assertNull(parseRupeesToMinor("1.234"))
        assertNull(parseRupeesToMinor("1..2"))
        assertNull(parseRupeesToMinor("1,250"))
        assertNull(parseRupeesToMinor("12,3.4"))
        assertNull(parseRupeesToMinor("12a50"))
        assertNull(parseRupeesToMinor("-1"))
        assertNull(parseRupeesToMinor("999999999999999999999999"))
    }

    @Test
    fun normalizesOneCommaAsDecimalWithoutDeletingAmbiguousInput() {
        assertEquals("12.50", normalizeRupeeInput("12,50"))
        assertEquals("12.", normalizeRupeeInput("12,"))
        assertEquals("-12.50", normalizeRupeeInput("-12,50", allowNegative = true))
        assertNull(normalizeRupeeInput("-12,50"))
        assertNull(normalizeRupeeInput("1,2,3"))
        assertNull(normalizeRupeeInput("12a50"))
    }

    @Test
    fun formatsEditableMoneyWithoutFloatingPointOrScientificNotation() {
        assertEquals("0", minorToRupeesInput(0L))
        assertEquals("0.05", minorToRupeesInput(5L))
        assertEquals("266.96", minorToRupeesInput(26_696L))
        assertEquals("-0.05", minorToRupeesInput(-5L))
        assertEquals("-1.05", minorToRupeesInput(-105L))
        assertEquals("92233720368547758.07", minorToRupeesInput(Long.MAX_VALUE))
    }
}
