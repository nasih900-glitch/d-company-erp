package cloud.dcompany.erp.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHealthMetricsTest {

    @Test
    fun `slow frames use the actual deadline and first draw is excluded`() {
        val builder = HealthCaptureBuilder()
        val oneHundredTwentyHz = 8_333_333L
        builder.frame("gaming", 9_000_000L, oneHundredTwentyHz, false, 2)
        builder.frame("gaming", 8_000_000L, oneHundredTwentyHz, false, 0)
        builder.frame("gaming", 90_000_000L, oneHundredTwentyHz, true, 0)

        val row = capture(builder).frames.single()
        assertEquals("gaming", row.screen)
        assertEquals(2, row.frames)
        assertEquals(1, row.slowFrames)
        assertEquals(1, row.firstDrawFrames)
        assertEquals(2, row.droppedReports)
        assertEquals(listOf(2, 0, 0, 0, 0), row.durationBuckets)
        assertEquals(9L, row.longestFrameMs)
    }

    @Test
    fun `refresh summary is bounded and reports failures without request content`() {
        val builder = HealthCaptureBuilder()
        builder.refresh("gaming", 120, "success")
        builder.refresh("gaming", 3_200, "failure")
        builder.refresh("gaming", 0, "skipped")
        builder.refresh("gaming", 480, "cancelled")

        val row = capture(builder).refreshes.single()
        assertEquals("gaming", row.resource)
        assertEquals(1, row.success)
        assertEquals(1, row.failure)
        assertEquals(1, row.skipped)
        assertEquals(1, row.cancelled)
        assertEquals(3_200L, row.longestRefreshMs)
        assertEquals(listOf(2, 1, 0, 1, 0), row.durationBuckets)
        assertTrue(healthCaptureInsight(capture(builder)).contains("gaming refresh took 3.2s"))
        assertFalse(capture(builder).toString().contains("/api/v1"))
    }

    @Test
    fun `saved summaries stay within one day and only the latest three remain`() {
        val now = 100_000_000L
        val base = capture(HealthCaptureBuilder())
        val records = listOf(
            base.copy(startedAtMillis = now - 1, endedAtMillis = now),
            base.copy(startedAtMillis = now - 2, endedAtMillis = now - 1),
            base.copy(startedAtMillis = now - 3, endedAtMillis = now - 2),
            base.copy(startedAtMillis = now - 4, endedAtMillis = now - 3),
            base.copy(startedAtMillis = now - 90_000_000, endedAtMillis = now - 90_000_000),
            base.copy(startedAtMillis = now + 1, endedAtMillis = now + 1),
        )

        assertEquals(
            listOf(now, now - 1, now - 2),
            recentHealthCaptures(records.reversed(), now).map { it.endedAtMillis },
        )
    }

    private fun capture(builder: HealthCaptureBuilder): AppHealthCapture = builder.build(
        startedAtMillis = 1_000,
        endedAtMillis = 2_000,
        stopReason = "stopped",
        versionName = "3.1.30",
        versionCode = 38,
        androidApiLevel = 35,
    )
}
