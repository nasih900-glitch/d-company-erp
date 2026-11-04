package cloud.dcompany.erp.core.money

import cloud.dcompany.erp.core.net.asRupees
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyDisplayTest {
    @Test
    fun `amount display keeps paise sign and Indian grouping without a currency symbol`() {
        assertEquals("0.00", 0L.asRupees())
        assertEquals("0.01", 1L.asRupees())
        assertEquals("-0.01", (-1L).asRupees())
        assertEquals("12,845.00", 1_284_500L.asRupees())
        assertEquals("-12,845.09", (-1_284_509L).asRupees())
        assertEquals("92,23,37,20,36,85,47,758.07", Long.MAX_VALUE.asRupees())
        assertEquals("-92,23,37,20,36,85,47,758.08", Long.MIN_VALUE.asRupees())
    }
}
