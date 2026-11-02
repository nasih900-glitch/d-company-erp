package cloud.dcompany.erp.ui.screens.staff.time

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
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
        return StaffTimeAuthority(profile.userId, profile.companyId, branch, scope.scope.terminalId,
            staffTimeAuthBinding(session.accessToken, session.refreshToken, verified), staffTimeAccess(verified).canManage,
            current, api)
    }
}

internal class StaffTimeViewModel(profile: MeResponse, private val expectedScope: CacheScopeLease?) : ViewModel() {
    private val app = DCompanyApp.instance
    private val work = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val workScope = CoroutineScope(viewModelScope.coroutineContext + work)
    private val controller = StaffTimeController(profile.userId, profile.companyId, staffTimeAccess(profile),
        AndroidStaffTimeEnvironment(profile, expectedScope),
        AtomicStaffTimePendingStore(File(app.noBackupFilesDir.canonicalFile, "staff-time-intents-v1")))
    val state = controller.state
    init {
        viewModelScope.launch {
            app.cacheIsolation.activeLeaseFlow.collect { scope ->
                if (scope != expectedScope) { work.cancel(); controller.invalidate() }
            }
        }
    }
    fun refresh() { workScope.launch { controller.refresh() } }
    fun person(id: String) { workScope.launch { controller.selectPerson(id) } }
    fun month(value: String) { workScope.launch { controller.selectMonth(value) } }
    fun morePeople() { workScope.launch { controller.loadPeople(true) } }
    fun olderPolicies() { workScope.launch { controller.olderPolicies() } }
    fun clock(out: Boolean) = controller.openClockDraft(out)
    fun correction(entry: TimeAttendance) = controller.openCorrection(entry)
    fun policy() = controller.openPolicy()
    fun edit(transform: (TimeEditDraft) -> TimeEditDraft) = controller.editDraft(transform)
    fun closeDraft() = controller.closeDraft()
    fun confirm() { workScope.launch { controller.confirmDraft() } }
    fun retryExact() { workScope.launch { controller.retryExact() } }
    fun acknowledgeRejected() { workScope.launch { controller.acknowledgeRejected() } }
    fun offline() = controller.markOffline()
    override fun onCleared() { work.cancel(); controller.invalidate(); super.onCleared() }
}
