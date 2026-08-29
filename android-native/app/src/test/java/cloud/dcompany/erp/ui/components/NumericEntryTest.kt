package cloud.dcompany.erp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class NumericEntryTest {

    @Test
    fun `money entry accepts keyboard paste and local decimal separator safely`() {
        assertEquals("", sanitiseMoneyEntry(""))
        assertEquals("0.5", sanitiseMoneyEntry(".5"))
        assertEquals("12.34", sanitiseMoneyEntry("12,34"))
        assertEquals("12.34", sanitiseMoneyEntry("₹ 12a.345"))
        assertEquals("1234", sanitiseMoneyEntry("12.34", allowDecimal = false))
    }

    @Test
    fun `money entry normalizes Arabic Persian and full width digits without clamping`() {
        assertEquals("12.34", sanitiseMoneyEntry("١٢٫٣٤"))
        assertEquals("12.34", sanitiseMoneyEntry("۱۲٫۳۴"))
        assertEquals("12.34", sanitiseMoneyEntry("１２．３４"))
        assertEquals("12.34", sanitiseMoneyEntry("１２，３４"))
        assertEquals("1234.50", sanitiseMoneyEntry("١٬٢٣٤٫٥٠"))
    }

    @Test
    fun `formatted grouping is not mistaken for paise`() {
        assertEquals("1000", sanitiseMoneyEntry("1,000"))
        assertEquals("123456.78", sanitiseMoneyEntry("1,23,456.78"))
        assertEquals("123456", sanitiseMoneyEntry("1,23,456"))
        assertEquals("1234.56", sanitiseMoneyEntry("1,234.56"))
        assertEquals("1234.56", sanitiseMoneyEntry("1.234,56"))
        assertEquals("1234.56", sanitiseMoneyEntry("1,234,56"))
        assertEquals(
            "1234567",
            sanitiseMoneyEntry("1,234,567", maxMinor = 200_000_000L),
        )
        assertEquals("12.34", sanitiseMoneyEntry("12,34"))
    }

    @Test
    fun `Arabic and full width formatted amounts preserve grouping and decimals`() {
        assertEquals("1234.50", sanitiseMoneyEntry("١٬٢٣٤٫٥٠"))
        assertEquals("1234.56", sanitiseMoneyEntry("１，２３４．５６"))
        assertEquals("1234.56", sanitiseMoneyEntry("１．２３４，５６"))
        assertEquals(
            "1234567",
            sanitiseMoneyEntry("１，２３４，５６７", maxMinor = 200_000_000L),
        )
    }

    @Test
    fun `localized single digit never becomes the maximum amount`() {
        assertEquals("1", sanitiseMoneyEntry("١"))
        assertEquals("1", sanitiseMoneyEntry("۱"))
        assertEquals("1", sanitiseMoneyEntry("１"))
    }

    @Test
    fun `money entry visibly clamps values above its safe maximum`() {
        assertEquals("100", sanitiseMoneyEntry("999999999999999999", maxMinor = 10_000L))
        assertEquals("100", sanitiseMoneyEntry("100.01", maxMinor = 10_000L))
        assertEquals("100", applyMoneyKey("100", "9", maxMinor = 10_000L))
    }

    @Test
    fun `touch keypad supports every money editing operation without an IME`() {
        val entered = listOf("1", "2", ".", "3", "4", "5").fold("") { value, key ->
            applyMoneyKey(value, key)
        }

        assertEquals("12.34", entered)
        assertEquals("12.34", applyMoneyKey(entered, "."))
        assertEquals("12.3", applyMoneyKey(entered, "backspace"))
        assertEquals("", applyMoneyKey(entered, "clear"))
        assertEquals("0.", applyMoneyKey("", "."))
    }

    @Test
    fun `whole number entry strips non digits and caps safely`() {
        assertEquals("", sanitiseWholeNumberEntry(""))
        assertEquals("12", sanitiseWholeNumberEntry("00-12 notes"))
        assertEquals("50", sanitiseWholeNumberEntry("999999999999999999", maxValue = 50L))
        assertEquals("50", sanitiseWholeNumberEntry("51", maxValue = 50L))
        assertEquals("123", sanitiseWholeNumberEntry("١۲３"))
    }

    @Test
    fun `stepper increments and decrements from touch with boundary guards`() {
        assertEquals("1", stepWholeNumberEntry("", 1L))
        assertEquals("4", stepWholeNumberEntry("5", -1L))
        assertEquals("0", stepWholeNumberEntry("0", -1L))
        assertEquals("5", stepWholeNumberEntry("5", 1L, maxValue = 5L))
        assertEquals("0", stepWholeNumberEntry("invalid", -1L))
    }
}
