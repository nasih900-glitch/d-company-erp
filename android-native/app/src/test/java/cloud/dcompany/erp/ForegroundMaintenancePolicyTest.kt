package cloud.dcompany.erp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ForegroundMaintenancePolicyTest {
    @Test
    fun compatibilityCheckRunsOnlyAfterFullIntervalAndHandlesClockReset() {
        assertFalse(
            compatibilityCheckIsDue(
                nowElapsedMillis = 14_999,
                lastCheckElapsedMillis = 10_000,
                intervalMillis = 5_000,
            ),
        )
        assertTrue(
            compatibilityCheckIsDue(
                nowElapsedMillis = 15_000,
                lastCheckElapsedMillis = 10_000,
                intervalMillis = 5_000,
            ),
        )
        assertFalse(
            compatibilityCheckIsDue(
                nowElapsedMillis = 1_000,
                lastCheckElapsedMillis = 10_000,
                intervalMillis = 5_000,
            ),
        )
    }

    @Test
    fun periodicJobStartsOnceAndStopsOnlyAfterLastActivity() {
        val tracker = ForegroundActivityTracker()

        assertTrue(tracker.onStarted())
        assertFalse(tracker.onStarted())
        assertFalse(tracker.onStopped())
        assertTrue(tracker.onStopped())
        assertFalse(tracker.onStopped())
        assertTrue(tracker.onStarted())
    }

    @Test
    fun foregroundReturnWaitsOnlyForRemainingCompatibilityInterval() {
        assertEquals(
            5_000L,
            nextCompatibilityDelayMillis(
                nowElapsedMillis = 15_000L,
                lastCheckElapsedMillis = 10_000L,
                intervalMillis = 10_000L,
            ),
        )
        assertEquals(
            10_000L,
            nextCompatibilityDelayMillis(
                nowElapsedMillis = 20_000L,
                lastCheckElapsedMillis = 10_000L,
                intervalMillis = 10_000L,
            ),
        )
    }

    @Test
    fun reconnectRunsImmediatelyAfterThrottleButRepeatedCallbacksAreConflated() {
        val throttle = CompatibilityRecheckThrottle()
        throttle.recordAttempt(nowElapsedMillis = 10_000L)

        assertFalse(throttle.claimIfDue(nowElapsedMillis = 12_999L, minimumGapMillis = 3_000L))
        assertTrue(throttle.claimIfDue(nowElapsedMillis = 13_000L, minimumGapMillis = 3_000L))
        assertFalse(throttle.claimIfDue(nowElapsedMillis = 13_001L, minimumGapMillis = 3_000L))
        assertEquals(
            899_999L,
            throttle.nextDelayMillis(nowElapsedMillis = 13_001L, intervalMillis = 900_000L),
        )
    }

    @Test
    fun startupOfflineThenFastReconnectIsDelayedRatherThanDropped() {
        val throttle = CompatibilityRecheckThrottle()
        throttle.recordAttempt(nowElapsedMillis = 10_000L)

        // Startup can fail offline almost immediately. A real network recovery
        // one second later is retained for the remaining two seconds instead
        // of falling through to the fifteen-minute foreground interval.
        assertEquals(
            2_000L,
            throttle.delayUntilClaimAllowed(
                nowElapsedMillis = 11_000L,
                minimumGapMillis = 3_000L,
            ),
        )
        assertFalse(throttle.claimIfDue(nowElapsedMillis = 12_999L, minimumGapMillis = 3_000L))
        assertTrue(throttle.claimIfDue(nowElapsedMillis = 13_000L, minimumGapMillis = 3_000L))
    }

    @Test
    fun elapsedClockRollbackStartsANewThrottleWindow() {
        val throttle = CompatibilityRecheckThrottle()
        throttle.recordAttempt(nowElapsedMillis = 10_000L)

        assertFalse(throttle.claimIfDue(nowElapsedMillis = 1_000L, minimumGapMillis = 3_000L))
        assertFalse(throttle.claimIfDue(nowElapsedMillis = 3_999L, minimumGapMillis = 3_000L))
        assertTrue(throttle.claimIfDue(nowElapsedMillis = 4_000L, minimumGapMillis = 3_000L))
    }

    @Test
    fun concurrentReconnectCallbacksAdmitOnlyOneNetworkCheck() {
        val throttle = CompatibilityRecheckThrottle()
        throttle.recordAttempt(nowElapsedMillis = 10_000L)
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val admitted = AtomicInteger(0)

        repeat(8) {
            workers.submit {
                ready.countDown()
                start.await()
                if (throttle.claimIfDue(13_000L, 3_000L)) admitted.incrementAndGet()
            }
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, admitted.get())
    }
}
