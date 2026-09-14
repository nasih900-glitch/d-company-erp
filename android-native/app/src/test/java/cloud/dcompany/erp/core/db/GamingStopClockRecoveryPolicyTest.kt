package cloud.dcompany.erp.core.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingStopClockRecoveryPolicyTest {

    @Test
    fun `only definitive future-time stop rejection permits recapture`() {
        assertTrue(
            isCorrectableGamingStopClockRejection(
                GamingSessionState.STOP_REJECTED,
                GAMING_STOP_FUTURE_TIME_REJECTION,
            ),
        )

        assertFalse(
            isCorrectableGamingStopClockRejection(
                GamingSessionState.STOP_PENDING,
                GAMING_STOP_FUTURE_TIME_REJECTION,
            ),
        )
        assertFalse(
            isCorrectableGamingStopClockRejection(
                GamingSessionState.STOP_REJECTED,
                "Could not reach the server. Check the connection and try again.",
            ),
        )
        assertFalse(
            isCorrectableGamingStopClockRejection(
                GamingSessionState.STOP_REJECTED,
                "Session stop time cannot be before the session started.",
            ),
        )
    }
}
