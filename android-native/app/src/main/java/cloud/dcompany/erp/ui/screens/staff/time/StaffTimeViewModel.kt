package cloud.dcompany.erp.ui.screens.staff.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Normal auth/me may refresh the same login; writes retain an immutable bearer and terminal. */
internal class AndroidStaffTimeEnvironment(
    private val profile: MeResponse,
    private val expectedScope: CacheScopeLease?,
    private val app: DCompanyApp = DCompanyApp.instance,
) : StaffTimeEnvironment {
    override fun online() = app.connectivity.online.value
    private fun sameProfile(other: MeResponse): Boolean = profile.userId == other.userId &&
        profile.companyId == other.companyId && profile.branchId == other.branchId &&
        profile.roles.sorted() == other.roles.sorted() &&
        profile.effectivePermissions?.sorted() == other.effectivePermissions?.sorted() &&
        profile.protectedAccess == other.protectedAccess && profile.auditAccess == other.auditAccess
    override suspend fun capture(): StaffTimeAuthority {
        val scope = expectedScope ?: throw StaffTimeAuthorityChanged()
        if (app.cacheIsolation.currentLease() != scope || !online()) throw StaffTimeAuthorityChanged()
        // A safe read uses the existing refresh coordinator, before capturing the write's bearer.
        val verified = ApiClient.api.me()
        if (app.cacheIsolation.currentLease() != scope || !sameProfile(verified) || !staffTimeAccess(verified).canRead) {
            throw StaffTimeAuthorityChanged()
        }
        if (scope.scope.userId != profile.userId || scope.scope.companyId != profile.companyId) throw StaffTimeAuthorityChanged()
        val session = app.tokens.refreshLease() ?: throw StaffTimeAuthorityChanged()
        val terminal = app.terminalStore.activeValidatedTerminal.value
        val branch = scope.scope.branchId ?: terminal?.takeIf { it.terminalId == scope.scope.terminalId }?.branchId
        val current = {
            app.cacheIsolation.currentLease() == scope && app.tokens.isCurrent(session) &&
                (scope.scope.terminalId == null || app.terminalStore.activeValidatedTerminal.value?.terminalId == scope.scope.terminalId)
        }
        if (!current()) throw StaffTimeAuthorityChanged()
        val api = ApiClient.createEphemeralAuthorityApi<StaffTimeApi>(
            accessToken = session.accessToken,
            terminalId = scope.scope.terminalId,
            beforeDispatch = {
                if (!current() || !online()) {
                    throw ApiException("The staff-time workspace changed before dispatch.", code = "staff_authority_changed")
                }
            },
            singleAttempt = true,
        )
        val sharedApi = ApiClient.createEphemeralAuthorityApi<SharedAttendanceApi>(
            accessToken = session.accessToken, terminalId = scope.scope.terminalId,
            singleAttempt = true, beforeDispatch = {
                if (!current() || !online()) throw ApiException("The attendance workspace changed.", code = "staff_authority_changed")
            },
        )
        return StaffTimeAuthority(profile.userId, profile.companyId, branch, scope.scope.terminalId,
            staffTimeAuthBinding(session.accessToken, session.refreshToken, verified), staffTimeAccess(verified).canManage,
            current, api, sharedApi, EffectivePermissions.from(verified).has(ErpPermission.StaffAttendanceWrite))
    }
}

internal class StaffTimeViewModel(profile: MeResponse, private val expectedScope: CacheScopeLease?) : ViewModel() {
    private val app = DCompanyApp.instance
    private val operations = app.handoverOperations.caller(profile, expectedScope)
    private val work = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val workScope = CoroutineScope(viewModelScope.coroutineContext + work)
    private val controller = StaffTimeController(profile.userId, profile.companyId, staffTimeAccess(profile),
        AndroidStaffTimeEnvironment(profile, expectedScope),
        AtomicStaffTimePendingStore(File(app.noBackupFilesDir.canonicalFile, "staff-time-intents-v1")), actorName = profile.name)
    private val shared = SharedAttendanceController(profile.userId, profile.companyId,
        AndroidStaffTimeEnvironment(profile, expectedScope),
        SharedAttendancePendingStore(File(app.noBackupFilesDir.canonicalFile, SHARED_ATTENDANCE_DIRECTORY)))
    val state = controller.state
    val sharedState = shared.state
    init {
        viewModelScope.launch {
            app.cacheIsolation.activeLeaseFlow.collect { scope ->
                if (scope != expectedScope) { work.cancel(); controller.invalidate(); shared.invalidate() }
            }
        }
    }
    private fun attendanceWork(block: suspend () -> Unit) = operations.launch(workScope,
        onRejected = { controller.handoverBlocked(); shared.handoverBlocked() }) {
        block()
        // Both controllers durably retain unknown/denied writes before returning.
        // The two independent pending stores remain handover readiness blockers.
        reconciled()
    }
    fun refresh() { attendanceWork { controller.refresh() } }
    fun refreshShared(more: Boolean = false) { attendanceWork { shared.refresh(more) } }
    fun selectShared(id: String) = shared.select(id)
    fun dismissShared() = shared.dismiss()
    fun confirmShared(password: String, breaks: String) { attendanceWork {
        if (shared.confirm(password, breaks)) { controller.refresh(); shared.refresh() }
    } }
    fun retryShared(password: String) { attendanceWork {
        if (shared.retry(password)) { controller.refresh(); shared.refresh() }
    } }
    fun acknowledgeShared() { attendanceWork { shared.acknowledgeRejected() } }
    fun attendancePeriod(period: String, start: String? = null) { attendanceWork { controller.selectAttendance(period, start) } }
    fun moreAttendance() { attendanceWork { controller.moreAttendance() } }
    fun person(id: String) { attendanceWork { controller.selectPerson(id) } }
    fun month(value: String) { attendanceWork { controller.selectMonth(value) } }
    fun morePeople() { attendanceWork { controller.loadPeople(true) } }
    fun olderPolicies() { attendanceWork { controller.olderPolicies() } }
    fun clock(out: Boolean) = controller.openClockDraft(out)
    fun correction(entry: TimeAttendance) = controller.openCorrection(entry)
    fun policy() = controller.openPolicy()
    fun edit(transform: (TimeEditDraft) -> TimeEditDraft) = controller.editDraft(transform)
    fun closeDraft() = controller.closeDraft()
    fun confirm() { attendanceWork { controller.confirmDraft() } }
    fun retryExact() { attendanceWork { controller.retryExact() } }
    fun acknowledgeRejected() { attendanceWork { controller.acknowledgeRejected() } }
    fun offline() { controller.markOffline(); shared.offline() }
    override fun onCleared() { work.cancel(); controller.invalidate(); shared.invalidate(); super.onCleared() }
}
