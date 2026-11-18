package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.StoredSession
import cloud.dcompany.erp.core.handover.AcceptedHandover
import cloud.dcompany.erp.core.handover.HandoverAction
import cloud.dcompany.erp.core.handover.HandoverAuthority
import cloud.dcompany.erp.core.handover.HandoverIntent
import cloud.dcompany.erp.core.handover.HandoverScope
import cloud.dcompany.erp.core.handover.HandoverStage
import cloud.dcompany.erp.core.handover.OriginalOperationBinding
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.*
import org.junit.Test

class TillTakeoverPresentationTest {
    private val profile = MeResponse("staff-a", "a@example.test", "Staff A", companyId = "company", branchId = "branch")
    private val authority = HandoverAuthority(HandoverScope("staff-a", "company", "branch", "terminal"), 1)
    private val ui = TillTakeoverUi("shift", revision = 0)
    private fun original(current: () -> Boolean = { true }) = OriginalOperationBinding(authority,
        CacheScopeLease(CacheScope("staff-a", "company", "branch", "terminal"), 1),
        SessionRefreshLease(StoredSession("test-access", "test-refresh"), 1), current)

    @Test
    fun `old actor selection cannot render or invoke guarded callbacks as incoming actor`() {
        val binding = original()
        assertTrue(mayPresentOrdinaryTillTakeover(ui, ui, profile, profile, binding))
        val incoming = profile.copy(userId = "staff-b", name = "Staff B")
        assertFalse(mayPresentOrdinaryTillTakeover(ui, ui, profile, incoming, binding))
        assertFalse(mayPresentOrdinaryTillTakeover(ui, ui, profile, profile.copy(), binding))
        assertFalse(mayPresentOrdinaryTillTakeover(ui, ui.copy(), profile, profile, binding))
        var callbacks = 0
        if (mayPresentOrdinaryTillTakeover(ui, ui, profile, incoming, binding)) callbacks++
        assertEquals(0, callbacks)
    }

    @Test
    fun `generation terminal or lineage revocation hides original dialog and retained recovery stays separate`() {
        var current = true
        val binding = original { current }
        assertTrue(mayPresentOrdinaryTillTakeover(ui, ui, profile, profile, binding))
        current = false
        assertFalse(mayPresentOrdinaryTillTakeover(ui, ui, profile, profile, binding))
        assertFalse(mayPresentOrdinaryTillTakeover(ui, ui, profile, profile, null))
        assertFalse(mayPresentOrdinaryTillTakeover(ui, null, profile, profile, original()))
        val retained = ui.copy(intent = HandoverIntent(HandoverAction("action", authority, "shift", "staff-b", 0)))
        assertFalse(mayPresentOrdinaryTillTakeover(retained, retained, profile, profile, original()))
    }

    @Test
    fun `automatic sign-in requires the exact fresh accepted action as durable recovery evidence`() {
        fun id(lastDigit: Int) = "00000000-0000-0000-0000-00000000000$lastDigit"
        val origin = HandoverAuthority(HandoverScope(id(1), id(2), id(3), id(4)), 1)
        val action = HandoverAction(id(5), origin, id(6), id(7), 4)
        val receipt = AcceptedHandover(id(8), action.key, id(2), id(3), id(4), id(6), id(1), id(7), 4, 5)
        val accepted = HandoverIntent(action, HandoverStage.ACCEPTED, receipt, priorMutationUncertainty = false)

        assertTrue(matchesFreshTillHandoverAcceptance(action, accepted, accepted.copy()))
        assertFalse(matchesFreshTillHandoverAcceptance(action, accepted, null))
        assertFalse(matchesFreshTillHandoverAcceptance(action.copy(key = id(9)), accepted, accepted))
        val unresolved = HandoverIntent(action, HandoverStage.SENT_UNKNOWN)
        assertFalse(matchesFreshTillHandoverAcceptance(action, unresolved, unresolved))
        val wrongRecipient = accepted.copy(receipt = receipt.copy(incomingUserId = id(9)))
        assertFalse(matchesFreshTillHandoverAcceptance(action, wrongRecipient, wrongRecipient))
        assertFalse(matchesFreshTillHandoverAcceptance(action, accepted, accepted.copy(receipt = receipt.copy(receiptId = id(9)))))
    }
}
