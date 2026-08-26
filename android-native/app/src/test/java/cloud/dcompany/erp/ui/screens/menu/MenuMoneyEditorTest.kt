package cloud.dcompany.erp.ui.screens.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuMoneyEditorTest {
    @Test
    fun pricingEditorParsesPaiseExactlyAndRejectsOverPrecision() {
        val valid = ItemPricingEditor("item", "266.96", "5", "", true)
        assertEquals(26_696L, valid.basePriceMinor)
        assertTrue(valid.valid)

        val invalid = valid.copy(basePriceRupees = "266.999")
        assertNull(invalid.basePriceMinor)
        assertFalse(invalid.valid)
    }

    @Test
    fun itemCreateKeepsPercentageValidationSeparateFromMoneyParsing() {
        val base = ItemCreateEditor(
            categoryId = "category",
            sku = "COFFEE",
            name = "Coffee",
            basePriceRupees = "0.29",
            taxRatePercent = "101",
        )
        assertEquals(29L, base.basePriceMinor)
        assertFalse(base.taxRateValid)
        assertFalse(base.valid)
        assertTrue(base.copy(taxRatePercent = "18").valid)
    }
}
