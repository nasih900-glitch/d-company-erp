package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay

/** Terminal-global Owners may have no profile branch; derive it only from that exact active terminal. */
internal fun effectiveOperationScope(cached: CacheScope, terminal: ValidatedTerminalDisplay?): HandoverScope {
    if (cached.terminalId == null) return HandoverScope(cached.userId, cached.companyId, cached.branchId, null).also { it.validate() }
    require(terminal != null && terminal.terminalId == cached.terminalId) { "The original validated terminal changed." }
    require(cached.branchId == null || cached.branchId == terminal.branchId) { "The original branch changed." }
    return HandoverScope(cached.userId, cached.companyId, cached.branchId ?: terminal.branchId, cached.terminalId).also { it.validate() }
}

/** Captured once by the originating caller, never reconstructed from the latest cashier in a late callback. */
internal class OriginalOperationBinding(
    val authority: HandoverAuthority,
    val cacheLease: CacheScopeLease,
    val loginLease: SessionRefreshLease,
    val isCurrent: () -> Boolean,
) {
    override fun toString() = "OriginalOperationBinding(<redacted>)"
}
