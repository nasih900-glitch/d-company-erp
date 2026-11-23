package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.LoginSessionLease
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.net.MeResponse

/** Process-only acknowledgement, published after accepted recovery and incoming cache publication. */
internal class TillCompletionNotice(
    val profile: MeResponse,
    val lease: CacheScopeLease,
    val login: LoginSessionLease,
    val shiftId: String,
    val operatorRevision: Int,
    val ownerReturned: Boolean,
) {
    val message: String get() = if (ownerReturned)
        "Owner recovery verified. ${profile.name} is running the till again. The same shop shift remains open. Attendance is unchanged."
        else "Handover complete. ${profile.name} is now running the till. The same shop shift remains open. Attendance is unchanged."

    fun isSupersededBy(display: TillOperatorDisplay?): Boolean {
        val read = display?.verified ?: return false
        // A failed refresh does not erase a previously verified, newer operator revision.
        if (read.shiftId != shiftId || read.revision < operatorRevision) return false
        return read.revision > operatorRevision || read.operatorUserId != profile.userId ||
            read.shiftStatus != "open" || read.tracking != "recorded"
    }

    fun matches(profile: MeResponse?, lease: CacheScopeLease?, terminal: ValidatedTerminalDisplay?, loginCurrent: Boolean): Boolean =
        this.profile === profile && this.lease == lease && loginCurrent &&
            terminal != null && terminal.terminalId == this.lease.scope.terminalId &&
            (this.lease.scope.branchId == null || terminal.branchId == this.lease.scope.branchId)
}
