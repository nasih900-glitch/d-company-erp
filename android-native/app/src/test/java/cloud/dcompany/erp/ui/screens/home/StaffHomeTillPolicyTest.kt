package cloud.dcompany.erp.ui.screens.home

import cloud.dcompany.erp.ui.TillOperatorDisplay
import cloud.dcompany.erp.ui.TillOperatorRead
import cloud.dcompany.erp.ui.TillOperatorStatus
import org.junit.Assert.*
import org.junit.Test

class StaffHomeTillPolicyTest {
    private val read = TillOperatorRead("shift", "open", "operator", "Running Operator", 1, "recorded")
    private val fresh = TillOperatorDisplay("Signed-in Staff", TillOperatorStatus.VERIFIED, read)

    @Test
    fun `fresh card names running operator independently from signed in staff`() {
        val card = staffHomeTillPresentation(fresh, online = true, canHandover = true)
        assertEquals("Running till: Running Operator", card.operatorLabel)
        assertEquals("Verified · Signed in: Signed-in Staff", card.detail)
        assertTrue(card.canStartHandover)
        assertNull(card.handoverGuidance)
    }

    @Test
    fun `untracked shift never borrows the signed in name`() {
        val card = staffHomeTillPresentation(fresh.copy(verified =
            TillOperatorRead("shift", "open", revision = 0, tracking = "untracked")), true, true)
        assertEquals("Running till: not recorded", card.operatorLabel)
        assertFalse(card.operatorLabel.contains("Signed-in Staff"))
        assertTrue(card.canStartHandover)
        assertTrue(card.detail.startsWith("Handover not yet recorded"))
    }

    @Test
    fun `stale checking unknown and closed facts never offer a handover`() {
        for (status in listOf(TillOperatorStatus.STALE, TillOperatorStatus.CHECKING,
            TillOperatorStatus.UNAVAILABLE, TillOperatorStatus.NO_SHIFT)) {
            val card = staffHomeTillPresentation(fresh.copy(status = status), true, true)
            assertFalse(status.name, card.canStartHandover)
            assertNotNull(status.name, card.handoverGuidance)
        }
        assertEquals("Last verified till: Running Operator",
            staffHomeTillPresentation(fresh.copy(status = TillOperatorStatus.STALE), true, true).operatorLabel)
        assertEquals("No open shop shift",
            staffHomeTillPresentation(fresh.copy(status = TillOperatorStatus.NO_SHIFT), true, true).operatorLabel)
        assertFalse(staffHomeTillPresentation(null, true, true).canStartHandover)
        assertFalse(staffHomeTillPresentation(fresh.copy(verified = read.copy(shiftStatus = "closed")), true, true).canStartHandover)
    }

    @Test
    fun `network and capability gates are independent of displayed name`() {
        val offline = staffHomeTillPresentation(fresh, false, true)
        assertFalse(offline.canStartHandover)
        assertEquals("Connect to the internet before handing over.", offline.handoverGuidance)
        assertFalse(staffHomeTillPresentation(fresh, true, false).canStartHandover)
        assertFalse(staffHomeTillPresentation(fresh.copy(verified = read.copy(shiftId = "")), true, true).canStartHandover)
    }
}
