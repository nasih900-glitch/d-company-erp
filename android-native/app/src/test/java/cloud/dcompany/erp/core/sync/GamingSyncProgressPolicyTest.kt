package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.GamingSessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingSyncProgressPolicyTest {
    @Test
    fun `dependency deferred stop and send are not delivery progress`() {
        assertFalse(
            gamingSessionPushResolved(
                phase = GamingSessionPushPhase.STOPS,
                rowStillExists = true,
                currentState = GamingSessionState.STOP_PENDING,
                hasServerId = true,
            ),
        )
        assertFalse(
            gamingSessionPushResolved(
                phase = GamingSessionPushPhase.SENDS,
                rowStillExists = true,
                currentState = GamingSessionState.SEND_PENDING,
                hasServerId = true,
            ),
        )
    }

    @Test
    fun `durably advanced gaming states count as delivery progress`() {
        assertTrue(
            gamingSessionPushResolved(
                phase = GamingSessionPushPhase.STARTS,
                rowStillExists = true,
                currentState = GamingSessionState.START_SYNCED,
                hasServerId = true,
            ),
        )
        assertTrue(
            gamingSessionPushResolved(
                phase = GamingSessionPushPhase.STOPS,
                rowStillExists = true,
                currentState = GamingSessionState.ENDED_UNBILLED,
                hasServerId = true,
            ),
        )
        assertTrue(
            gamingSessionPushResolved(
                phase = GamingSessionPushPhase.SENDS,
                rowStillExists = true,
                currentState = GamingSessionState.SENT,
                hasServerId = true,
            ),
        )
        assertTrue(
            gamingSessionPushResolved(
                phase = GamingSessionPushPhase.SENDS,
                rowStillExists = false,
                currentState = null,
                hasServerId = false,
            ),
        )
    }
}
