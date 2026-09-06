package cloud.dcompany.erp.ui.screens

import java.util.TimeZone
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptDateTimeTest {
    @Test
    fun `gaming clock and shift history share receipt IST regardless of device timezone`() {
        val original = TimeZone.getDefault()
        val wire = "2026-09-04T18:30:00Z"
        try {
            for (zone in listOf("Europe/London", "Asia/Kolkata", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("12:00 AM IST", wire.businessClockTime())
                assertEquals(wire.receiptDateTime(), Instant.parse(wire).toEpochMilli().businessDateTime())
                assertEquals("05 Sep 2026 · 12:00 AM IST", Instant.parse(wire).toEpochMilli().businessDateTime())
            }
        } finally {
            TimeZone.setDefault(original)
        }
        assertEquals("Time unavailable", "invalid".businessClockTime())
    }

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
