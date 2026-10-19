package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftResultContextTest {
    private val closed = LocalShiftEntity(
        localId = "local-closed-a",
        serverShiftId = "server-closed-a",
        terminalId = "terminal-a",
        branchId = "branch-a",
        openingFloatMinor = 10_000,
        openedAtMillis = 1_000,
        state = ShiftState.CLOSED,
        countedMinor = 10_000,
        closedAtMillis = 2_000,
        varianceMinor = 0,
        closeResultPending = true,
    )
    private val open = ResolvedOpenShift(
        shiftId = "server-open-b",
        source = ShiftSource.SERVER_CACHE,
        openedAtMillis = 3_000,
        openingFloatMinor = 2_500,
        expectedMinor = 2_500,
        openedByUserId = "employee-a",
        openedByName = null,
        openedByEmail = null,
    )

    @Test fun previousHundredRupeeReceiptDoesNotAnnounceNewTwentyFiveRupeeShiftClosed() {
        val visible = visiblePendingShiftCloseResult(closed, true, "terminal-a", null)
        assertSame(closed, visible)
        assertTrue(closed.closeResultPending)
        assertEquals("Previous shift closed — server confirmed", shiftCloseResultTitle(closed, open))
        val message = shiftCloseResultMessage(closed, open)
        assertTrue(message.contains("the previous shift is closed"))
        assertTrue(message.contains("Shift ID: server-closed-a"))
        assertTrue(message.contains("Counted cash: ₹100.00"))
        assertTrue(message.contains("Opened:"))
        assertTrue(message.contains("Closed:"))
        assertTrue(message.contains("Current shift server-open-b remains open"))
        assertFalse(message.contains("Counted cash: ₹25.00"))
        assertEquals(2_500L, open.openingFloatMinor)
        assertEquals(2_500L, open.expectedMinor)
    }

    @Test fun currentClosureAndItsLocalServerAliasesKeepTheirOwnOutcome() {
        assertEquals("Shift closed — server confirmed", shiftCloseResultTitle(closed, null))
        assertFalse(isPreviousShiftCloseResult(closed, open.copy(shiftId = closed.localId)))
        assertFalse(isPreviousShiftCloseResult(closed, open.copy(shiftId = closed.serverShiftId!!)))
        assertFalse(shiftCloseResultMessage(closed).contains("remains open"))
    }

    @Test fun remotePreviousReceiptRemainsDistinctAndPending() {
        val remote = closed.copy(lastError = "Live reconciliation confirmed closure elsewhere.")
        assertEquals("Previous shift closed elsewhere", shiftCloseResultTitle(remote, open))
        val message = shiftCloseResultMessage(remote, open)
        assertTrue(message.contains(remote.lastError!!))
        assertTrue(message.contains("Shift ID: server-closed-a"))
        assertTrue(message.contains("Current shift server-open-b remains open"))
        assertTrue(remote.closeResultPending)
    }

    @Test fun missingCountTimeOrVarianceIsUnknownRatherThanInventedZero() {
        val unknown = closed.copy(countedMinor = null, closedAtMillis = null, varianceMinor = null)
        val message = shiftCloseResultMessage(unknown, open)
        assertTrue(message.contains("Final variance is not available"))
        assertTrue(message.contains("Closed: time unavailable"))
        assertTrue(message.contains("Counted cash: not available"))
        assertFalse(message.contains("balanced exactly"))
        assertFalse(message.contains("₹0.00"))
    }

    @Test fun unloadedOrDifferentTerminalDefersReceiptWithoutAcknowledgingIt() {
        assertNull(visiblePendingShiftCloseResult(closed, false, "terminal-a", null))
        assertNull(visiblePendingShiftCloseResult(closed, true, null, null))
        assertNull(visiblePendingShiftCloseResult(closed, true, "", null))
        assertNull(visiblePendingShiftCloseResult(closed, true, "terminal-b", null))
        assertNull(visiblePendingShiftCloseResult(closed.copy(terminalId = null), true, "terminal-a", null))
        assertTrue(closed.closeResultPending)
    }

    @Test fun onlyClosedUndismissedPendingResultsAreVisible() {
        assertNull(visiblePendingShiftCloseResult(closed.copy(state = ShiftState.CLOSE_PENDING), true, "terminal-a", null))
        assertNull(visiblePendingShiftCloseResult(closed.copy(closeResultPending = false), true, "terminal-a", null))
        assertNull(visiblePendingShiftCloseResult(closed, true, "terminal-a", closed.localId))
        assertSame(closed, visiblePendingShiftCloseResult(closed, true, "terminal-a", "older-result"))
    }

    @Test fun capturedAcknowledgementCannotRetargetToNewerReceipt() {
        val renderedId = closed.localId
        val prior = ShiftUiState(open = open, currentShiftLoaded = true, closedResult = closed)
        assertTrue(canAcknowledgeShiftCloseResult(renderedId, prior, "terminal-a"))
        val newer = closed.copy(localId = "local-closed-b", serverShiftId = "server-closed-b")
        val changed = prior.copy(closedResult = newer)
        val writtenIds = mutableListOf<String>()
        if (canAcknowledgeShiftCloseResult(renderedId, changed, "terminal-a")) writtenIds += renderedId
        assertTrue(writtenIds.isEmpty())
        assertTrue(newer.closeResultPending)
        assertTrue(canAcknowledgeShiftCloseResult(newer.localId, changed, "terminal-a"))
    }

    @Test fun capturedAcknowledgementRefusesLostWorkspaceOrClearedResult() {
        val state = ShiftUiState(open = open, currentShiftLoaded = true, closedResult = closed)
        assertFalse(canAcknowledgeShiftCloseResult(closed.localId, state.copy(currentShiftLoaded = false), "terminal-a"))
        assertFalse(canAcknowledgeShiftCloseResult(closed.localId, state, "terminal-b"))
        assertFalse(canAcknowledgeShiftCloseResult(closed.localId, state, null))
        assertFalse(canAcknowledgeShiftCloseResult(closed.localId, state.copy(closedResult = null), "terminal-a"))
        assertFalse(canAcknowledgeShiftCloseResult(closed.localId, state.copy(closedResult = closed.copy(closeResultPending = false)), "terminal-a"))
    }
}
