package cloud.dcompany.erp.core.sync

import androidx.work.ExistingWorkPolicy
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup
import cloud.dcompany.erp.core.net.BackendReachability
import cloud.dcompany.erp.core.net.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncWorkerPolicyTest {
    @Test
    fun `background recovery confirms only the exact single hybrid workspace`() {
        val hybrid = terminal("hybrid", TerminalPurpose.HYBRID)

        assertEquals(
            "hybrid",
            confirmedBackgroundHybridTerminalId(
                branchId = "branch-1",
                availableTerminals = listOf(hybrid),
                savedTerminalId = "hybrid",
            ),
        )
        assertEquals(
            null,
            confirmedBackgroundHybridTerminalId(
                branchId = "branch-1",
                availableTerminals = listOf(hybrid),
                savedTerminalId = "stale-terminal",
            ),
        )
    }

    @Test
    fun `background recovery blocks split active terminal topology`() {
        assertEquals(
            null,
            confirmedBackgroundHybridTerminalId(
                branchId = "branch-1",
                availableTerminals = listOf(
                    terminal("hybrid", TerminalPurpose.HYBRID),
                    terminal("gaming", TerminalPurpose.GAMING),
                ),
                savedTerminalId = "hybrid",
            ),
        )
    }

    @Test
    fun `new durable request replaces rather than loses or chains an older handoff`() {
        assertEquals(ExistingWorkPolicy.REPLACE, DURABLE_SYNC_EXISTING_WORK_POLICY)
    }

    @Test
    fun `pushable and ambiguous states retry after process recovery`() {
        val groups = listOf(
            group("pos_orders", "pending"),
            group("refunds", "provider_completed_pending_accounting"),
            group("membership_payment_actions", "ambiguous"),
            group("ingredients", "create_attempted"),
            group("suppliers", "create_attempted"),
        )

        assertTrue(hasBackgroundRetryableWork(groups))
        assertTrue(shouldRetryBackgroundSync(groups))
    }

    @Test
    fun `human decision and rejected states do not spin in background`() {
        val groups = listOf(
            group("pos_orders", "draft"),
            group("refunds", "accepted_cash_due"),
            group("gaming_sessions", "ended_unbilled"),
            group("customers", "rejected"),
            group("cafe_actions", "conflict"),
            group("refunds", "provider_payout_in_progress"),
            group("refunds", "legacy_reconciliation_required"),
            group("pos_orders", "awaiting_payment"),
        )

        assertFalse(hasBackgroundRetryableWork(groups))
        assertFalse(shouldRetryBackgroundSync(groups))
    }

    @Test
    fun `pushable work remains retryable beyond the old fixed attempt cap`() {
        val pending = listOf(group("shifts", "open_pending"))

        assertTrue(shouldRetryBackgroundSync(pending))
    }

    @Test
    fun `validated reconnect probes a backend still marked unreachable`() {
        assertTrue(
            shouldProbeBackendOnValidatedReconnect(
                wasValidated = false,
                nowValidated = true,
                backendReachability = BackendReachability.UNREACHABLE,
            ),
        )
        assertFalse(
            shouldProbeBackendOnValidatedReconnect(
                wasValidated = true,
                nowValidated = true,
                backendReachability = BackendReachability.UNREACHABLE,
            ),
        )
        assertFalse(
            shouldProbeBackendOnValidatedReconnect(
                wasValidated = false,
                nowValidated = true,
                backendReachability = BackendReachability.REACHABLE,
            ),
        )
    }

    @Test
    fun `compatibility reconnect skips the initial snapshot and repeated capabilities`() {
        assertFalse(
            shouldNotifyValidatedReconnect(
                wasValidated = false,
                nowValidated = true,
                notificationsEnabled = false,
            ),
        )
        assertTrue(
            shouldNotifyValidatedReconnect(
                wasValidated = false,
                nowValidated = true,
                notificationsEnabled = true,
            ),
        )
        assertFalse(
            shouldNotifyValidatedReconnect(
                wasValidated = true,
                nowValidated = true,
                notificationsEnabled = true,
            ),
        )
        assertFalse(
            shouldNotifyValidatedReconnect(
                wasValidated = true,
                nowValidated = false,
                notificationsEnabled = true,
            ),
        )
    }

    private fun group(resource: String, state: String) = UnresolvedOutboxGroup(
        resource = resource,
        state = state,
        count = 1,
    )

    private fun terminal(id: String, purpose: String) = Terminal(
        id = id,
        name = "Workspace $id",
        branchId = "branch-1",
        purpose = purpose,
    )
}
