package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftResultDismissalTest {
    @Test
    fun `new shift result remains visible`() {
        assertTrue(shouldShowShiftResult(itemId = "shift-a", resultPending = true, dismissedId = null))
        assertTrue(shouldShowShiftResult(itemId = "shift-b", resultPending = true, dismissedId = "shift-a"))
    }

    @Test
    fun `dismissed shift result is hidden`() {
        assertFalse(shouldShowShiftResult(itemId = "shift-a", resultPending = true, dismissedId = "shift-a"))
    }

    @Test
    fun `historical closed row without durable pending receipt is hidden on cold start`() {
        assertFalse(shouldShowShiftResult(itemId = "old-shift", resultPending = false, dismissedId = null))
    }

    @Test
    fun `remote reconciliation is not presented as a local close success`() {
        val local = closedShift(lastError = null)
        val remote = closedShift(
            lastError = "Server reconciliation confirmed this shift is no longer open.",
        )

        assertEquals(ShiftCloseResultKind.LOCAL_CONFIRMED, shiftCloseResultKind(local))
        assertEquals(ShiftCloseResultKind.REMOTE_RECONCILED, shiftCloseResultKind(remote))
    }

    private fun closedShift(lastError: String?) = LocalShiftEntity(
        localId = "shift-a",
        terminalId = "terminal-a",
        branchId = "branch-a",
        openingFloatMinor = 5_000,
        openedAtMillis = 1_000,
        state = ShiftState.CLOSED,
        closedAtMillis = 2_000,
        lastError = lastError,
        closeResultPending = true,
    )
}
