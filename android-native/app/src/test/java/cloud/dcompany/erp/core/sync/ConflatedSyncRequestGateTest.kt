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
}
