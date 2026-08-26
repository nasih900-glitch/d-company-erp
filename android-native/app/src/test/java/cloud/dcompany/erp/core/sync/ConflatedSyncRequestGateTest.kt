package cloud.dcompany.erp.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedSyncRequestGateTest {

    @Test
    fun `burst before a pass starts is covered by one pass`() {
        val gate = ConflatedSyncRequestGate()

        assertTrue(gate.request())
        repeat(20) { assertFalse(gate.request()) }

        assertTrue(gate.claimPass())
        assertFalse(gate.finishPass())
    }

    @Test
    fun `requests during a pass coalesce into exactly one trailing pass`() {
        val gate = ConflatedSyncRequestGate()

        assertTrue(gate.request())
        assertTrue(gate.claimPass())

        repeat(20) { assertFalse(gate.request()) }

        assertTrue(gate.finishPass())
        assertTrue(gate.claimPass())
        assertFalse(gate.finishPass())

        // The worker retired after the quiet trailing pass, so a later request
        // starts a new worker rather than being mistaken for the old burst.
        assertTrue(gate.request())
    }

    @Test
    fun `direct awaited pass absorbs an older queued request`() {
        val gate = ConflatedSyncRequestGate()

        assertTrue(gate.request())
        gate.absorbPendingIntoDirectPass()

        // The already-launched worker observes that the direct pass covered
        // its request, retires, and permits a future request to start afresh.
        assertFalse(gate.claimPass())
        assertTrue(gate.request())
    }

    @Test
    fun `request during a direct awaited pass remains queued for a trailing pass`() {
        val gate = ConflatedSyncRequestGate()

        assertTrue(gate.request())
        gate.absorbPendingIntoDirectPass()

        // The worker is already registered but waiting on SyncEngine's mutex.
        // A new request during the direct pass must remain for that worker.
        assertFalse(gate.request())
        assertTrue(gate.claimPass())
        assertFalse(gate.finishPass())
    }

    @Test
    fun `old cache cohort cannot retire replacement worker after account switch`() {
        val gate = ConflatedSyncRequestGate()

        val oldWorker = requireNotNull(gate.request(cohort = 11L))
        assertTrue(gate.claimPass(oldWorker))

        val replacement = requireNotNull(gate.request(cohort = 12L))

        // Simulates a paused old network pass resuming after the new employee
        // has scheduled their first sync. Its completion must be a no-op.
        assertFalse(gate.finishPass(oldWorker))
        assertTrue(gate.claimPass(replacement))
        assertFalse(gate.finishPass(replacement))
    }

    @Test
    fun `session reset prevents cancelled worker ABA from changing new pending state`() {
        val gate = ConflatedSyncRequestGate()

        val cancelled = requireNotNull(gate.request(cohort = 21L))
        assertTrue(gate.claimPass(cancelled))
        gate.reset()

        val current = requireNotNull(gate.request(cohort = 22L))
        assertFalse(gate.claimPass(cancelled))
        assertFalse(gate.finishPass(cancelled))
        assertTrue(gate.claimPass(current))
        assertFalse(gate.finishPass(current))
    }
}
