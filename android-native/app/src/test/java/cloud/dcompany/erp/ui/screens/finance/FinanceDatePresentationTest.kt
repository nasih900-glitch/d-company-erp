package cloud.dcompany.erp.ui.screens.finance

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceDatePresentationTest {
    @Test
    fun `ledger date agrees with the IST report day on a London device`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals("5 Sept 2026", "2026-09-04T21:41:27.410399Z".asDay())
            assertEquals("5 Sept", "2026-09-04T21:41:27.410399Z".asDayShort())
            assertEquals("4 Sept 2026", "2026-09-04T18:29:59Z".asDay())
            assertEquals("5 Sept 2026", "2026-09-04T18:30:00Z".asDay())
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `explicit accounting dates are never shifted and bad values remain visible`() {
        assertEquals("4 Sept 2026", "2026-09-04".asDay())
        assertEquals("4 Sept 2026", "2026-09-04T21:41:27".asDay())
        assertEquals("invalid date", "invalid date".asDay())
    }
}
