package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.net.Terminal
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineGamingShiftCapabilityTest {
    private val local = LocalShiftEntity(
        localId = "local-shift", terminalId = "terminal", branchId = "branch",
        openingFloatMinor = 50_000, openedAtMillis = 1_000,
        openedByUserId = "employee", state = ShiftState.OPEN_PENDING,
    )
    private fun shift(row: LocalShiftEntity = local) = ResolvedOpenShift(
        shiftId = row.localId, source = ShiftSource.LOCAL_OUTBOX, local = row,
        openedAtMillis = row.openedAtMillis, openingFloatMinor = row.openingFloatMinor,
        expectedMinor = null, openedByUserId = row.openedByUserId, openedByName = null, openedByEmail = null,
    )
    private val terminal = ValidatedTerminalDisplay(
        "terminal", "Main", "branch", TerminalPurpose.HYBRID, offlineShiftCaptureSupported = true,
    )

    @Test
    fun `pending shift is usable only with verified matching captured-open capability`() {
        assertTrue(allowsQueuedGamingStart(shift(), terminal))
        assertFalse(allowsQueuedGamingStart(shift(), terminal.copy(offlineShiftCaptureSupported = false)))
        assertFalse(allowsQueuedGamingStart(shift(), null))
        assertFalse(allowsQueuedGamingStart(shift(), terminal.copy(branchId = "other")))
        assertFalse(allowsQueuedGamingStart(shift(), terminal.copy(terminalId = "other")))
        assertFalse(allowsQueuedGamingStart(shift(), terminal.copy(purpose = TerminalPurpose.CAFE_POS)))
        assertFalse(allowsQueuedGamingStart(shift(local.copy(branchId = null)), terminal))
        assertNull(gamingStartShiftBlockMessage("local-shift", false, queuedStartAllowed = true))
        assertTrue(gamingShiftSummary("local-shift", false, false, true).detail.contains("Offline play"))
    }

    @Test
    fun `rejected closed closing and superseded shifts never gain authority from the capability`() {
        listOf(ShiftState.OPEN_REJECTED, ShiftState.CLOSE_PENDING, ShiftState.CLOSED,
            ShiftState.OPEN_SUPERSEDED, ShiftState.OPEN_DISCARDED).forEach { state ->
            assertFalse(state, allowsQueuedGamingStart(shift(local.copy(state = state)), terminal))
        }
        assertFalse(allowsQueuedGamingStart(shift(local.copy(serverShiftId = "server-shift")), terminal))
    }

    @Test
    fun `older terminal responses fail closed while new contract is explicit`() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<Terminal>(
            """{"id":"terminal","name":"Main","branch_id":"branch"}""",
        ).offlineShiftCaptureSupported)
        assertTrue(json.decodeFromString<Terminal>(
            """{"id":"terminal","name":"Main","branch_id":"branch","offline_shift_capture_supported":true}""",
        ).offlineShiftCaptureSupported)
    }
}
