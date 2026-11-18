package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.*
import org.junit.Test

class TillCompletionNoticeTest {
    private val me = MeResponse("b", "b@example.test", "Test B", companyId = "company", branchId = "branch")
    private val scope = CacheScope("b", "company", "branch", "terminal")
    private val lease = CacheScopeLease(scope, 2)
    private val login = LoginSessionLease(Any(), 3)
    private val terminal = ValidatedTerminalDisplay("terminal", "Test till", "branch", "hybrid")
    private val notice = TillCompletionNotice(me, lease, login, "shift", 2, false)

    @Test fun noticeRequiresExactPublishedProfileLoginGenerationAndWorkspace() {
        assertTrue(notice.matches(me, lease, terminal, true))
        assertFalse(notice.matches(me.copy(), lease, terminal, true))
        assertFalse(notice.matches(me.copy(userId = "a"), lease, terminal, true))
        assertFalse(notice.matches(me, CacheScopeLease(scope, 3), terminal, true))
        assertFalse(notice.matches(me, lease, terminal.copy(terminalId = "other"), true))
        assertFalse(notice.matches(me, lease, terminal.copy(branchId = "other"), true))
        assertFalse(notice.matches(me, lease, terminal, false))
        assertFalse(notice.matches(null, null, null, false))
    }
    @Test fun newerVerifiedSameShiftRevisionOrActorInvalidatesButStaleOlderCacheDoesNot() {
        fun display(revision: Int, actor: String? = "b", shift: String = "shift", status: TillOperatorStatus = TillOperatorStatus.VERIFIED) =
            TillOperatorDisplay("Test B", status, TillOperatorRead(shift, "open", actor, actor, revision, "recorded"))
        assertFalse(notice.isSupersededBy(null))
        assertFalse(notice.isSupersededBy(display(1, "a")))
        assertFalse(notice.isSupersededBy(display(2)))
        assertFalse(notice.isSupersededBy(display(1, "a", status = TillOperatorStatus.STALE)))
        assertTrue(notice.isSupersededBy(display(3, "a", status = TillOperatorStatus.STALE)))
        assertTrue(notice.isSupersededBy(display(3, "a", status = TillOperatorStatus.CHECKING)))
        assertFalse(notice.isSupersededBy(display(3, "a", shift = "another")))
        assertTrue(notice.isSupersededBy(display(3, "a")))
        assertTrue(notice.isSupersededBy(display(3, "b")))
        assertTrue(notice.isSupersededBy(display(2, "a")))
        val returned = TillCompletionNotice(me, lease, login, "shift", 3, true)
        assertFalse(returned.isSupersededBy(display(2, "a")))
        assertFalse(returned.isSupersededBy(display(3)))
        assertTrue(returned.isSupersededBy(display(4, "a")))
    }

    @Test fun unassignedOwnerBranchStillRequiresTheExactVerifiedTerminalAndCompanyScope() {
        val owner = me.copy(branchId = null)
        val unassignedLease = CacheScopeLease(scope.copy(branchId = null), 2)
        val returned = TillCompletionNotice(owner, unassignedLease, login, "shift", 2, true)
        assertTrue(returned.matches(owner, unassignedLease, terminal, true))
        assertFalse(returned.matches(owner, unassignedLease, terminal.copy(terminalId = "other"), true))
    }
    @Test fun completionUsesAuthenticatedNameAndSeparatesOwnerReturnFromHandoverAndAttendance() {
        assertTrue(notice.message.contains("Test B is now running")); assertTrue(notice.message.contains("same shop shift remains open"))
        assertTrue(notice.message.contains("Attendance is unchanged"))
        val owner = TillCompletionNotice(me, lease, login, "shift", 2, true)
        assertTrue(owner.message.contains("Owner recovery verified")); assertFalse(owner.message.contains("Handover complete"))
    }
}
