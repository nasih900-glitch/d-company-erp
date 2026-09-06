package cloud.dcompany.erp.ui.components

import cloud.dcompany.erp.core.net.BackendReachability
import cloud.dcompany.erp.core.net.ConnectivityPhase
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
    fun `unknown API is verifying while a proven response is clear`() {
        assertEquals(
            SyncAvailabilityProblem.VERIFYING,
            syncAvailabilityProblem(true, BackendReachability.UNKNOWN),
        )
        assertEquals(
            SyncAvailabilityProblem.NONE,
            syncAvailabilityProblem(true, BackendReachability.REACHABLE),
        )
        assertNull(syncAvailabilityCopy(SyncAvailabilityProblem.NONE))
        assertEquals(
            "Connected to the ERP server",
            syncAvailabilityDialogTitle(SyncAvailabilityProblem.NONE),
        )
    }

    @Test
    fun `degraded dialog titles continue to use their specific recovery copy`() {
        assertEquals(
            syncAvailabilityCopy(SyncAvailabilityProblem.RECOVERING)?.title,
            syncAvailabilityDialogTitle(SyncAvailabilityProblem.RECOVERING),
        )
    }

    @Test
    fun `every coordinator phase has one stable presentation state`() {
        assertEquals(SyncAvailabilityProblem.NO_NETWORK, syncAvailabilityProblem(ConnectivityPhase.NO_NETWORK))
        assertEquals(SyncAvailabilityProblem.VERIFYING, syncAvailabilityProblem(ConnectivityPhase.VERIFYING))
        assertEquals(
            SyncAvailabilityProblem.SERVER_UNREACHABLE,
            syncAvailabilityProblem(ConnectivityPhase.SERVER_UNREACHABLE),
        )
        assertEquals(SyncAvailabilityProblem.RECOVERING, syncAvailabilityProblem(ConnectivityPhase.RECOVERING))
        assertEquals(SyncAvailabilityProblem.NONE, syncAvailabilityProblem(ConnectivityPhase.ONLINE))
    }

    @Test
    fun `recovery copy warns against repeating a payment`() {
        val copy = syncAvailabilityCopy(SyncAvailabilityProblem.RECOVERING)!!

        assertTrue(copy.detail.contains("synchronise", ignoreCase = true))
        assertTrue(copy.detail.contains("do not repeat a payment", ignoreCase = true))
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
