package cloud.dcompany.erp.ui.components

import cloud.dcompany.erp.core.net.BackendReachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAvailabilityBannerTest {

    @Test
    fun `no validated network shows offline state`() {
        assertEquals(
            SyncAvailabilityProblem.NO_NETWORK,
            syncAvailabilityProblem(
                networkValidated = false,
                backendReachability = BackendReachability.REACHABLE,
            ),
        )
    }

    @Test
    fun `validated wifi with failed API shows server state`() {
        assertEquals(
            SyncAvailabilityProblem.SERVER_UNREACHABLE,
            syncAvailabilityProblem(
                networkValidated = true,
                backendReachability = BackendReachability.UNREACHABLE,
            ),
        )
    }

    @Test
    fun `unknown or reachable API stays clear while network is validated`() {
        assertEquals(
            SyncAvailabilityProblem.NONE,
            syncAvailabilityProblem(true, BackendReachability.UNKNOWN),
        )
        assertEquals(
            SyncAvailabilityProblem.NONE,
            syncAvailabilityProblem(true, BackendReachability.REACHABLE),
        )
        assertNull(syncAvailabilityCopy(SyncAvailabilityProblem.NONE))
    }

    @Test
    fun `degraded copy explains local save automatic sync and stale data`() {
        val copy = syncAvailabilityCopy(SyncAvailabilityProblem.SERVER_UNREACHABLE)!!

        assertTrue(copy.detail.contains("saved data", ignoreCase = true))
        assertTrue(copy.detail.contains("saved locally", ignoreCase = true))
        assertTrue(copy.detail.contains("waiting to sync", ignoreCase = true))
        assertTrue(copy.detail.contains("automatically", ignoreCase = true))
    }
}
