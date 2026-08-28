package cloud.dcompany.erp.ui.screens.gaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingPosTargetDialogPolicyTest {
    private val target = PosTargetShift(
        shiftId = "cafe-shift",
        terminalId = "cafe-terminal",
        terminalName = "Cafe counter",
        openedBy = "staff-1",
        openedByName = "Shameer",
        openedAt = "2026-08-28T12:00:00Z",
    )

    @Test
    fun `dialog has no implicit target and enables only an exact visible shift`() {
        assertFalse(canConfirmPosTargetSelection(null, listOf(target)))
        assertFalse(canConfirmPosTargetSelection("unknown-shift", listOf(target)))
        assertTrue(canConfirmPosTargetSelection("cafe-shift", listOf(target)))
    }

    @Test
    fun `destination row identifies both terminal and shift opener`() {
        val supportingText = posTargetShiftSupportingText(target)

        assertTrue(target.terminalName.contains("Cafe"))
        assertTrue(supportingText.contains("Opened by Shameer"))
    }
}
