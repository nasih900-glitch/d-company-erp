package cloud.dcompany.erp.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSilenceTrackerTest {

    @Test
    fun `backward wall clock change cannot delay silence recovery`() {
        var elapsedRealtimeMillis = 10_000L
        var wallClockMillis = 1_000_000L
        val tracker = RealtimeSilenceTracker { elapsedRealtimeMillis }
        tracker.recordMessage()

        wallClockMillis -= 120_000L
        elapsedRealtimeMillis += 50_001L

        assertTrue(wallClockMillis < 1_000_000L)
        assertFalse(
            "The former wall-clock subtraction misses a stale socket after a clock rollback",
            wallClockMillis - 1_000_000L > 50_000L,
        )
        assertTrue(tracker.hasExceeded(50_000L))
    }

    @Test
    fun `new message resets monotonic silence window`() {
        var elapsedRealtimeMillis = 10_000L
        val tracker = RealtimeSilenceTracker { elapsedRealtimeMillis }
        tracker.recordMessage()
        elapsedRealtimeMillis += 50_001L
        assertTrue(tracker.hasExceeded(50_000L))

        tracker.recordMessage()
        assertFalse(tracker.hasExceeded(50_000L))
        elapsedRealtimeMillis += 50_000L
        assertFalse(tracker.hasExceeded(50_000L))
        elapsedRealtimeMillis += 1L
        assertTrue(tracker.hasExceeded(50_000L))
    }
}
