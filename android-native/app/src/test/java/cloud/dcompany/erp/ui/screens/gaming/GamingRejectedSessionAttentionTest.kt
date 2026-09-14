package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamingRejectedSessionAttentionTest {

    @Test
    fun `start and stop rejections are counted without duplicating payment rejection`() {
        val failedStart = session("start", "station-start", GamingSessionState.START_REJECTED, "start_failed")
        val failedStop = session("stop", "station-stop", GamingSessionState.STOP_REJECTED, "active")
        val failedSend = session("send", "station-send", GamingSessionState.SEND_REJECTED, "ended")
        val pendingStop = session("pending", "station-pending", GamingSessionState.STOP_PENDING, "stopping")
        val state = GamingUiState(sessions = listOf(failedStart, failedStop, failedSend, pendingStop))

        assertEquals(listOf("start", "stop"), state.rejectedSessionsForReview.map(GameSession::id))
        assertEquals(
            2,
            gamingCommandAttentionCount(
                canManageSessions = true,
                terminalBlocked = false,
                focusRequested = false,
                hasRefreshError = false,
                orphanedExtensionCount = 0,
                rejectedSessionCount = state.rejectedSessionsForReview.size,
                needsCancellation = false,
                awaitingPayment = false,
                busy = false,
            ),
        )
    }

    @Test
    fun `review destination is the rejected station and clears with authoritative state`() {
        val rejected = session("stop", "station-stop", GamingSessionState.STOP_REJECTED, "active")
        assertEquals(
            "station-stop",
            rejectedSessionReviewStationId(rejected, listOf("station-other", "station-stop")),
        )
        assertNull(rejectedSessionReviewStationId(rejected, listOf("station-other")))

        val cleared = rejected.copy(localState = GamingSessionState.START_SYNCED)
        val pending = rejected.copy(localState = GamingSessionState.STOP_PENDING)
        assertEquals(emptyList<GameSession>(), GamingUiState(sessions = listOf(cleared, pending)).rejectedSessionsForReview)
        assertNull(rejectedSessionReviewStationId(cleared, listOf("station-stop")))
        assertNull(rejectedSessionReviewStationId(pending, listOf("station-stop")))
    }

    private fun session(id: String, stationId: String, state: String, status: String) = GameSession(
        id = id,
        stationId = stationId,
        shiftId = "shift-1",
        status = status,
        startAt = "2026-09-14T09:00:00Z",
        localState = state,
        amountMinor = 10_000,
    )
}
