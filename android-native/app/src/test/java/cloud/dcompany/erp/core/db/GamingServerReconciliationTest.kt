package cloud.dcompany.erp.core.db

import org.junit.Assert.assertEquals
import org.junit.Test

class GamingServerReconciliationTest {

    @Test
    fun `server confirmed start repairs a crash-window start pending row`() {
        listOf("active", "paused").forEach { serverStatus ->
            assertEquals(
                GamingServerReconciliation.START_SYNCED,
                gamingServerReconciliation(
                    GamingSessionState.START_PENDING,
                    serverStatus = serverStatus,
                    serverOrderId = null,
                ),
            )
        }
    }

    @Test
    fun `remote stop advances active or failed stop legs to ended unbilled`() {
        listOf(
            GamingSessionState.START_SYNCED,
            GamingSessionState.START_PENDING,
            GamingSessionState.STOP_PENDING,
            GamingSessionState.STOP_REJECTED,
        ).forEach { localState ->
            assertEquals(
                GamingServerReconciliation.ENDED_UNBILLED,
                gamingServerReconciliation(localState, serverStatus = "ended", serverOrderId = null),
            )
        }
    }

    @Test
    fun `ended snapshot does not falsely claim a pending POS handoff succeeded`() {
        assertEquals(
            GamingServerReconciliation.NONE,
            gamingServerReconciliation(
                GamingSessionState.SEND_PENDING,
                serverStatus = "ended",
                serverOrderId = null,
            ),
        )
    }

    @Test
    fun `server order id resolves every stale local leg as sent`() {
        listOf(
            GamingSessionState.START_SYNCED,
            GamingSessionState.STOP_PENDING,
            GamingSessionState.ENDED_UNBILLED,
            GamingSessionState.SEND_PENDING,
            GamingSessionState.SEND_REJECTED,
        ).forEach { localState ->
            assertEquals(
                GamingServerReconciliation.SENT,
                gamingServerReconciliation(localState, serverStatus = "ended", serverOrderId = "order-1"),
            )
        }
    }

    @Test
    fun `server cancellation clears active ended and rejected local overlays`() {
        listOf(
            GamingSessionState.START_SYNCED,
            GamingSessionState.STOP_REJECTED,
            GamingSessionState.ENDED_UNBILLED,
            GamingSessionState.SEND_REJECTED,
        ).forEach { localState ->
            assertEquals(
                GamingServerReconciliation.CANCELLED,
                gamingServerReconciliation(localState, serverStatus = "cancelled", serverOrderId = null),
            )
        }
    }

    @Test
    fun `ordinary active server snapshot leaves local recovery leg unchanged`() {
        assertEquals(
            GamingServerReconciliation.NONE,
            gamingServerReconciliation(
                GamingSessionState.STOP_REJECTED,
                serverStatus = "active",
                serverOrderId = null,
            ),
        )
    }
}
