package cloud.dcompany.erp.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStallDetectorTest {
    private val detector = SyncStallDetector(
        stallThresholdMillis = 120_000L,
        repeatIntervalMillis = 600_000L,
    )

    @Test
    fun `offline queue is not reported as a sync stall`() {
        assertNull(detector.evaluate(0L, sample(pending = 3, progress = 1L, online = false)))
        assertNull(detector.evaluate(900_000L, sample(pending = 3, progress = 1L, online = false)))
        assertNull(detector.evaluate(900_001L, sample(pending = 3, progress = 1L, online = true)))
    }

    @Test
    fun `online pending queue reports only after threshold and is rate limited`() {
        assertNull(detector.evaluate(0L, sample(4, 10L, true)))
        assertNull(detector.evaluate(119_999L, sample(4, 10L, true)))
        val first = requireNotNull(detector.evaluate(120_000L, sample(4, 10L, true)))
        assertEquals(DiagnosticDurationBucket.TWO_TO_10M, first.durationBucket)
        assertEquals(4, first.pendingOutboxCount)
        assertNull(detector.evaluate(200_000L, sample(4, 10L, true)))
        assertNotNull(detector.evaluate(720_000L, sample(4, 10L, true)))
    }

    @Test
    fun `delivery progress resets the stall clock`() {
        detector.evaluate(0L, sample(5, 10L, true))
        assertNull(detector.evaluate(100_000L, sample(4, 10L, true)))
        assertNull(detector.evaluate(219_999L, sample(4, 10L, true)))
        assertNotNull(detector.evaluate(220_000L, sample(4, 10L, true)))

        // A newer explicit progress marker also resets even if the count is unchanged.
        assertNull(detector.evaluate(221_000L, sample(4, 11L, true)))
        assertNull(detector.evaluate(340_999L, sample(4, 11L, true)))
        assertNotNull(detector.evaluate(341_000L, sample(4, 11L, true)))
    }

    @Test
    fun `newly queued work does not masquerade as successful progress`() {
        detector.evaluate(0L, sample(1, 10L, true))
        assertNotNull(detector.evaluate(120_000L, sample(8, 10L, true)))
    }

    @Test
    fun `api storm limiter resets across account switch and logout`() {
        val state = ScopedDiagnosticState(
            apiMinimumIntervalMillis = 60_000L,
            stallThresholdMillis = 120_000L,
            stallRepeatIntervalMillis = 600_000L,
        )
        val scopeA = "a".repeat(64)
        val scopeB = "b".repeat(64)

        assertTrue(state.claimApiFailure(scopeA, "same-fingerprint", 1_000L))
        assertFalse(state.claimApiFailure(scopeA, "same-fingerprint", 1_001L))
        assertTrue(state.claimApiFailure(scopeB, "same-fingerprint", 1_001L))
        assertFalse(state.claimApiFailure(scopeB, "same-fingerprint", 1_002L))
        assertTrue(state.observeScope(null))
        assertTrue(state.claimApiFailure(scopeB, "same-fingerprint", 1_003L))
    }

    @Test
    fun `sync stall timer cannot carry between accounts or across logout`() {
        val state = ScopedDiagnosticState(
            apiMinimumIntervalMillis = 60_000L,
            stallThresholdMillis = 120_000L,
            stallRepeatIntervalMillis = 600_000L,
        )
        val scopeA = "a".repeat(64)
        val scopeB = "b".repeat(64)
        val pending = sample(2, 10L, true)

        assertNull(state.evaluateSync(scopeA, 0L, pending))
        assertNotNull(state.evaluateSync(scopeA, 120_000L, pending))
        assertNull(state.evaluateSync(scopeB, 120_000L, pending))
        assertNull(state.evaluateSync(scopeB, 239_999L, pending))
        assertNotNull(state.evaluateSync(scopeB, 240_000L, pending))
        assertTrue(state.observeScope(null))
        assertNull(state.evaluateSync(null, 1_000_000L, pending))
        assertNull(state.evaluateSync(scopeB, 1_000_000L, pending))
        assertNotNull(state.evaluateSync(scopeB, 1_120_000L, pending))
    }

    private fun sample(pending: Int, progress: Long?, online: Boolean) = SyncHealthSample(
        pendingOutboxCount = pending,
        progressMarker = progress,
        online = online,
    )
}
