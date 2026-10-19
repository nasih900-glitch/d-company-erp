package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineShiftOpeningStatusTest {
    @Test fun `unresolved startup never claims an opening is available`() {
        assertTrue(inlineShiftOpeningStatus(ShiftUiState()).startsWith("Checking"))
    }

    @Test fun `saved pending open never claims server confirmation`() {
        val saved = ShiftUiState(currentShiftLoaded = true, open = shift(ShiftState.OPEN_PENDING))
        assertTrue(inlineShiftOpeningStatus(saved).contains("Waiting for server confirmation"))
        assertFalse(inlineShiftOpeningStatus(saved).contains("Open shift confirmed"))
        assertTrue(inlineShiftOpeningStatus(saved.copy(offlineGamingSupported = true)).contains("confirmation is still pending"))
    }

    @Test fun `observed confirmed shift is distinct from saved and closing`() {
        assertTrue(inlineShiftOpeningStatus(ShiftUiState(currentShiftLoaded = true,
            open = shift(ShiftState.OPEN_SYNCED, "server-1"))).startsWith("Open shift confirmed"))
        assertTrue(inlineShiftOpeningStatus(ShiftUiState(currentShiftLoaded = true,
            open = shift(ShiftState.CLOSE_PENDING, "server-1"))).contains("saved close"))
        assertTrue(inlineShiftOpeningStatus(ShiftUiState(currentShiftLoaded = true,
            open = shift(ShiftState.CLOSE_REJECTED, "server-1"))).contains("still open"))
    }

    private fun shift(state: String, serverId: String? = null): ResolvedOpenShift = ResolvedOpenShift(
        shiftId = serverId ?: "local-1", source = ShiftSource.LOCAL_OUTBOX,
        local = LocalShiftEntity(localId = "local-1", terminalId = "terminal-1", branchId = "branch-1",
            openingFloatMinor = 0, openedAtMillis = 1, state = state, serverShiftId = serverId),
        openedAtMillis = 1, openingFloatMinor = 0, expectedMinor = null,
        openedByUserId = null, openedByName = null, openedByEmail = null,
    )
}
