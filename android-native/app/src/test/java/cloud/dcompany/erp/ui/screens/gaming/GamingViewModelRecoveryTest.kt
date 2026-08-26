package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingViewModelRecoveryTest {

    @Test
    fun `authenticated feature owner can construct gaming view model without application extras`() {
        val constructor = GamingViewModel::class.java.getDeclaredConstructor()

        assertEquals(0, constructor.parameterCount)
    }

    @Test
    fun recoveredLegsExposeOnlyTheirSafeAction() {
        val failedStart = session(
            id = "start",
            status = "start_failed",
            localState = GamingSessionState.START_REJECTED,
        )
        val failedStop = session(
            id = "stop",
            status = "active",
            localState = GamingSessionState.STOP_REJECTED,
        )
        val failedSend = session(
            id = "send",
            status = "ended",
            localState = GamingSessionState.SEND_REJECTED,
        )
        val sendPending = session(
            id = "send-pending",
            status = "ended",
            localState = GamingSessionState.SEND_PENDING,
        )
        val zeroValue = session(
            id = "zero",
            status = "ended",
            localState = GamingSessionState.SEND_REJECTED,
            amountMinor = 0,
        )
        val paused = session(
            id = "paused",
            status = "paused",
            localState = GamingSessionState.START_SYNCED,
        )

        assertTrue(failedStart.canRetryStart())
        assertFalse(failedStart.canRequestStop())
        assertFalse(failedStart.canSendToPos())

        assertFalse(failedStop.canRetryStart())
        assertTrue(failedStop.canRequestStop())
        assertFalse(failedStop.canSendToPos())

        assertFalse(failedSend.canRetryStart())
        assertFalse(failedSend.canRequestStop())
        assertTrue(failedSend.canSendToPos())
        assertFalse(sendPending.canSendToPos())
        assertFalse(zeroValue.canSendToPos())
        assertTrue(zeroValue.canCancelUnbilled())
        assertTrue(failedSend.canCancelUnbilled())
        assertFalse(sendPending.canCancelUnbilled())
        assertTrue(paused.canRequestStop())

        val ui = GamingUiState(
            sessions = listOf(failedStart, failedStop, failedSend, sendPending, zeroValue, paused),
        )
        assertEquals("start", ui.activeFor("station-start")?.id)
        assertEquals("stop", ui.activeFor("station-stop")?.id)
        assertEquals("send", ui.activeFor("station-send")?.id)
        assertEquals("zero", ui.activeFor("station-zero")?.id)
        assertEquals("paused", ui.activeFor("station-paused")?.id)
        assertEquals(listOf("send"), ui.readyForPos.map { it.id })
        assertEquals(listOf("zero"), ui.needsCancellation.map { it.id })
    }

    private fun session(
        id: String,
        status: String,
        localState: String,
        amountMinor: Long = 37_500,
    ) = GameSession(
        id = id,
        stationId = "station-$id",
        status = status,
        startAt = "2026-08-25T10:00:00Z",
        endAt = if (status == "ended") "2026-08-25T10:15:00Z" else null,
        billableMinutes = if (status == "ended") 15 else null,
        amountMinor = if (status == "ended") amountMinor else null,
        localState = localState,
        lastError = "legacy failure",
    )
}
