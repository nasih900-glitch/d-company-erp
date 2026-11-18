package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.OutboxGateResult
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.handover.HandoverCoordinatorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal const val CASHIER_SHIFT_SIGN_OUT_BLOCKED = "Close shop shift or use Change cashier before signing out."
internal const val CASHIER_SHIFT_SIGN_OUT_UNVERIFIED = "The shop shift could not be verified for this account and till. Refresh Shift and try signing out again."

internal fun hasCashierDuty(profile: MeResponse): Boolean {
    val permissions = EffectivePermissions.from(profile)
    return permissions.has(ErpPermission.PosWrite) &&
        permissions.hasAny(ErpPermission.PosShiftOpen, ErpPermission.PosShiftClose)
}

/** Local metadata may be forgotten only when the coordinator proves no exit was started. */
internal fun mayForgetUnstartedLogoutMetadata(hasPendingExit: Boolean, state: HandoverCoordinatorState): Boolean =
    !hasPendingExit && state.workspaceExitKey == null && !state.storageFault

/** Both retry kinds fence original authority before any logout intent or cancellation effects. */
internal inline fun <T> withCurrentLogoutIntent(
    deactivated: Boolean,
    authorityCurrent: () -> Boolean,
    shiftGuard: CashierSignOutGuard?,
    begin: () -> T,
): T? {
    if (!authorityCurrent() || (!deactivated && shiftGuard?.current() == false)) return null
    return begin()
}

/** Captured synchronously before a fresh voluntary sign-out can suspend. */
internal class CashierSignOutScope private constructor(
    private val signedIn: AuthState.SignedIn,
    val login: SessionRefreshLease,
    private val lease: CacheScopeLease,
    private val generation: Long,
    val terminalId: String,
    private val terminalBranchId: String,
) {
    fun isCurrent(
        state: AuthState, currentLogin: SessionRefreshLease?, currentLease: CacheScopeLease?,
        currentGeneration: Long, terminal: ValidatedTerminalDisplay?, originalAccessAvailable: Boolean,
    ): Boolean = state === signedIn && currentLogin?.session?.lineage === login.session.lineage &&
        originalAccessAvailable && currentLease == lease && currentGeneration == generation &&
        terminal?.terminalId == terminalId && terminal?.branchId == terminalBranchId

    companion object {
        fun capture(
            signedIn: AuthState.SignedIn, login: SessionRefreshLease?, lease: CacheScopeLease?,
            generation: Long, terminal: ValidatedTerminalDisplay?,
        ): CashierSignOutScope? {
            if (login == null || lease == null || terminal == null) return null
            val profile = signedIn.me
            val scope = lease.scope
            if (scope.userId != profile.userId || scope.companyId != profile.companyId ||
                scope.branchId != profile.branchId || scope.terminalId != terminal.terminalId ||
                terminal.terminalId.isBlank() || terminal.branchId.isBlank() || lease.generation != generation ||
                (scope.branchId != null && scope.branchId != terminal.branchId)
            ) return null
            return CashierSignOutScope(signedIn, login, lease, generation, terminal.terminalId, terminal.branchId)
        }
    }
}

/** The same read runs before exit intent and again under the final cache-deactivation gate. */
internal class CashierSignOutGuard(
    private val isCurrent: () -> Boolean,
    private val readManagementShift: suspend () -> ResolvedOpenShift?,
    private val timeoutMillis: Long = 3_000,
) {
    private data class Observation(val shift: ResolvedOpenShift?)
    fun current(): Boolean = isCurrent()

    suspend fun check(): OutboxGateResult {
        if (!isCurrent()) return OutboxGateResult.Blocked(CASHIER_SHIFT_SIGN_OUT_UNVERIFIED)
        val observed = try {
            withTimeoutOrNull(timeoutMillis) { Observation(readManagementShift()) }
                ?: return OutboxGateResult.Blocked(CASHIER_SHIFT_SIGN_OUT_UNVERIFIED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return OutboxGateResult.Blocked(CASHIER_SHIFT_SIGN_OUT_UNVERIFIED)
        }
        if (!isCurrent()) return OutboxGateResult.Blocked(CASHIER_SHIFT_SIGN_OUT_UNVERIFIED)
        return if (observed.shift != null) OutboxGateResult.Blocked(CASHIER_SHIFT_SIGN_OUT_BLOCKED)
        else OutboxGateResult.Allowed
    }
}
