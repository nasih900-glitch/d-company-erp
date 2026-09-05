package cloud.dcompany.erp.ui.screens

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptDateTimeTest {
    @Test
    fun `payment display follows the shop day regardless of the device timezone`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Europe/London", "America/Los_Angeles", "Asia/Kolkata")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(
                    "05 Sep 2026 · 3:11 AM IST",
                    "2026-09-04T21:41:27.410399Z".receiptDateTime(),
                )
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `receipt dates cross midnight exactly at the IST boundary`() {
        assertEquals("04 Sep 2026 · 11:59 PM IST", "2026-09-04T18:29:59Z".receiptDateTime())
        assertEquals("05 Sep 2026 · 12:00 AM IST", "2026-09-04T18:30:00Z".receiptDateTime())
        assertEquals("05 Sep 2026 · 12:00 AM IST", "2026-09-05T00:00:00+05:30".receiptDateTime())
    }

    @Test
    fun `malformed timestamps show a clear unavailable state`() {
        assertEquals("Time unavailable", "".receiptDateTime())
        assertEquals("Time unavailable", "not-a-timestamp".receiptDateTime())
    }
}
