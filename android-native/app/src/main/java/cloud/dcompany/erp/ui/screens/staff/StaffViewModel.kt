package cloud.dcompany.erp.ui.screens.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.StaffAccess
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.auth.loadPlan
import cloud.dcompany.erp.core.db.LocalStaffEntity
import cloud.dcompany.erp.core.db.StaffCacheEntity
import cloud.dcompany.erp.core.db.StaffWriteState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val ROLES_CACHE_KEY = "staff_roles"

private data class StaffRolesState(
    val rows: List<StaffRole> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Row shown in the staff list — merged cache + any pending/rejected local write. */
data class StaffRow(
    val id: String,
    val email: String,
    val name: String,
    val phone: String?,
    val status: String,
    val roles: List<String>,
    val lastLoginAt: String?,
    val isSelf: Boolean = false,
    val pendingLocalId: String? = null,
    val rejectedError: String? = null,
    val pendingDelete: Boolean = false,
    /**
     * True whenever a queued delete exists for this row, pending OR
     * rejected — unlike [pendingDelete] (display-only, false once rejected
     * so the rejection banner can show through), this tells the UI whether
     * "Cancel removal" should be offered at all, in either state.
     */
    val hasQueuedDelete: Boolean = false,
    val localWriteId: String? = null,
    val localWriteVersion: Long? = null,
    /** Backend masks both protected/co-owner roles as `owner`; only auditAccess may change owner access. */
    val accessChangesLocked: Boolean = false,
    val canDelete: Boolean = true,
)

internal val StaffRow.canDiscardRejectedChange: Boolean
    get() = rejectedError != null && !hasQueuedDelete && localWriteId != null && localWriteVersion != null

/**
 * Draft of the edit dialog. `isSelf` locks role/status, mirroring the web app
 * and the backend's own guard. `originalStatus`/`originalRoleCode` are the
 * values this row actually had when the dialog opened — kept separate from
 * the live-edited `status`/`roleCode` so `saveEdit()` can tell "the admin
 * changed this" from "this is just what the row already said," and only send
 * a field that actually changed (see saveEdit's doc comment for why that
 * matters more here than it looks).
 */
data class StaffEditor(
    val id: String,
    val name: String,
    val phone: String,
    val status: String,
    val roleCode: String,
    val originalStatus: String,
    val originalRoleCode: String,
    val isSelf: Boolean,
    val accessChangesLocked: Boolean,
) {
    val validationError: String?
        get() = when {
            name.trim().isEmpty() -> "Enter the staff member's name."
            name.trim().length > 200 -> "Name must be 200 characters or fewer."
            phone.trim().length > 20 -> "Phone must be 20 characters or fewer."
            status !in setOf("active", "suspended") -> "Choose Active or Suspended status."
            else -> null
        }
    val valid: Boolean get() = validationError == null
}

/** Draft of the "Add staff" two-phase form — request a code, then redeem it. */
data class CreateLoginDraft(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val password: String = "",
    val confirmPassword: String = "",
) {
    val passwordsMatch: Boolean get() = password == confirmPassword
    val validationError: String?
        get() = when {
            name.trim().isEmpty() -> "Enter the staff member's name."
            name.trim().length > 200 -> "Name must be 200 characters or fewer."
            email.trim().length < 3 -> "Enter the staff member's email address."
            email.trim().length > 254 -> "Email must be 254 characters or fewer."
            phone.trim().length > 20 -> "Phone must be 20 characters or fewer."
            password.length < 10 -> "Password must be at least 10 characters."
            password.length > 256 -> "Password must be 256 characters or fewer."
            !passwordsMatch -> "Passwords don't match."
            else -> null
        }
    val requestValid: Boolean
        get() = validationError == null
}

/** Draft of the admin-initiated "Reset password" two-phase form, against an existing user's email. */
data class PasswordResetDraft(
    val forUserId: String,
    val forName: String,
    val email: String,
    val newPassword: String = "",
    val confirmNewPassword: String = "",
) {
    val passwordsMatch: Boolean get() = newPassword == confirmNewPassword
    val validationError: String?
        get() = when {
            newPassword.length < 10 -> "New password must be at least 10 characters."
            newPassword.length > 256 -> "New password must be 256 characters or fewer."
            !passwordsMatch -> "Passwords don't match."
            else -> null
        }
    val newPasswordValid: Boolean get() = validationError == null
}

internal fun resolveAttendanceBranchId(profileBranchId: String?, terminalBranchId: String?): String? =
    profileBranchId?.trim()?.takeIf(String::isNotEmpty)
        ?: terminalBranchId?.trim()?.takeIf(String::isNotEmpty)

/** Every UI-only, ephemeral piece of state consolidated into one flow — same fix as MenuViewModel's EditingState. */
private data class EditingState(
    val editor: StaffEditor? = null,
    val savingEdit: Boolean = false,
    val editError: String? = null,

    val createDraft: CreateLoginDraft? = null,
    val createChallenge: OtpChallenge? = null,
    val createCode: String = "",
    val creating: Boolean = false,
    val createError: String? = null,

    val passwordReset: PasswordResetDraft? = null,
    val passwordResetChallenge: OtpChallenge? = null,
    val passwordResetCode: String = "",
    val resettingPassword: Boolean = false,
    val passwordResetError: String? = null,

    val deleteConfirmFor: StaffRow? = null,

    val clockingInOut: Boolean = false,
    val attendanceError: String? = null,
    val clockedInOverride: Boolean? = null,
    val attendanceUncertain: Boolean = false,

    val notice: String? = null,
)

data class StaffUiState(
    val canReadDirectory: Boolean = false,
    val canManageDirectory: Boolean = false,
    val canUseAttendance: Boolean = false,
    val everSynced: Boolean = false,
    val syncing: Boolean = false,
    val rows: List<StaffRow> = emptyList(),
    val roles: List<StaffRole> = emptyList(),
    val rolesError: String? = null,
    val onShift: List<OnShiftRow> = emptyList(),
    val myUserId: String? = null,
    val myBranchId: String? = null,
    val clockingInOrOut: Boolean = false,
    val attendanceError: String? = null,
    val attendanceUncertain: Boolean = false,
    val clockedInOverride: Boolean? = null,

    val editor: StaffEditor? = null,
    val savingEdit: Boolean = false,
    val editError: String? = null,

    val createDraft: CreateLoginDraft? = null,
    val createChallenge: OtpChallenge? = null,
    val createCode: String = "",
    val creating: Boolean = false,
    val createError: String? = null,

    val passwordReset: PasswordResetDraft? = null,
    val passwordResetChallenge: OtpChallenge? = null,
    val passwordResetCode: String = "",
    val resettingPassword: Boolean = false,
    val passwordResetError: String? = null,

    val deleteConfirmFor: StaffRow? = null,

    val notice: String? = null,
) {
    val loading: Boolean
        get() = canReadDirectory && !everSynced && rows.isEmpty() && syncing
    val couldNotLoad: Boolean
        get() = canReadDirectory && !everSynced && rows.isEmpty() && !syncing
    val clockedIn: Boolean
        get() = clockedInOverride ?: (myUserId != null && onShift.any { it.userId == myUserId })
}

/**
 * Staff members are plain `User` rows (backend/app/models/user.py) — there is
 * no dedicated Staff table. Creating a login and resetting a password are
 * both online-only, two-phase OTP round trips (see StaffApi.kt's doc
 * comment) — never queued, the same online-only reasoning Menu applies to
 * item pricing/creation. Editing (name/phone/status/role) and deleting
 * (soft-delete) *are* offline-queueable, via the same single-outbox-table
 * master-data pattern Customers established, folded onto one
 * [LocalStaffEntity] per staff member (a delete is modelled as a terminal
 * edit via `pendingDelete`, not a second table — see that entity's doc
 * comment).
 */
class StaffViewModel(
    profile: MeResponse,
    private val access: StaffAccess,
) : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<StaffApi>()
    private val loadPlan = access.loadPlan()

    private val pulling = MutableStateFlow(false)
    private val rolesState = MutableStateFlow(StaffRolesState())
    private val myProfile = MutableStateFlow<MeResponse?>(profile)
    private val editing = MutableStateFlow(EditingState())
    private val profileAndAttendanceBranch = combine(
        myProfile,
        appCtx.terminalStore.activeValidatedTerminal,
    ) { currentProfile, terminal ->
        currentProfile to resolveAttendanceBranchId(currentProfile?.branchId, terminal?.branchId)
    }

    val state: StateFlow<StaffUiState> = combine(
        combine(db.staffDao().observeCache(), db.staffDao().observeLocal(), profileAndAttendanceBranch, ::Triple),
        combine(db.syncMetaDao().observe("staff"), pulling, ::Pair),
        db.attendanceDao().observeOnShift(),
        rolesState,
        editing,
    ) { cacheLocalProfile, metaAndSyncing, onShift, rolesState, ed ->
        val (cache, local, profileAndBranch) = cacheLocalProfile
        val (profile, attendanceBranchId) = profileAndBranch
        val (meta, isSyncing) = metaAndSyncing
        StaffUiState(
            canReadDirectory = access.canReadDirectory,
            canManageDirectory = access.canManageDirectory,
            canUseAttendance = access.canUseAttendance,
            everSynced = !access.canReadDirectory || meta != null,
            syncing = isSyncing,
            rows = if (access.canReadDirectory) {
                mergeStaff(cache, local, profile?.userId, profile?.auditAccess == true)
            } else {
                emptyList()
            },
            roles = if (access.canReadDirectory) rolesState.rows else emptyList(),
            rolesError = rolesState.error,
            onShift = if (access.canReadDirectory || access.canUseAttendance) onShift
                .filter { attendanceBranchId == null || it.branchId == attendanceBranchId }
                .map {
                OnShiftRow(
                    id = it.attendanceId,
                    userId = it.userId,
                    userName = it.userName,
                    userEmail = it.userEmail,
                    branchId = it.branchId,
                    branchName = it.branchName,
                    clockInAt = it.clockInAt,
                )
            } else {
                emptyList()
            },
            myUserId = profile?.userId,
            myBranchId = attendanceBranchId,
            editor = ed.editor,
            savingEdit = ed.savingEdit,
            editError = ed.editError,
            createDraft = ed.createDraft,
            createChallenge = ed.createChallenge,
            createCode = ed.createCode,
            creating = ed.creating,
            createError = ed.createError,
            passwordReset = ed.passwordReset,
            passwordResetChallenge = ed.passwordResetChallenge,
            passwordResetCode = ed.passwordResetCode,
            resettingPassword = ed.resettingPassword,
            passwordResetError = ed.passwordResetError,
            deleteConfirmFor = ed.deleteConfirmFor,
            clockingInOrOut = ed.clockingInOut,
            attendanceError = ed.attendanceError,
            attendanceUncertain = ed.attendanceUncertain,
            clockedInOverride = ed.clockedInOverride,
            notice = ed.notice,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        StaffUiState(
            canReadDirectory = access.canReadDirectory,
            canManageDirectory = access.canManageDirectory,
            canUseAttendance = access.canUseAttendance,
        ),
    )

    init {
        retry()
    }

    private fun loadRoles() {
        if (rolesState.value.loading) return
        rolesState.value = rolesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                db.reportSnapshotDao().cached<List<StaffRole>>(ROLES_CACHE_KEY)?.let { (cached, _) ->
                    rolesState.value = rolesState.value.copy(rows = cached)
                }
                lateinit var fresh: List<StaffRole>
                val committed = appCtx.cacheIsolation.fetchAndCommitScoped(
                    fetch = { api.roles() },
                    store = {
                        fresh = it
                        db.reportSnapshotDao().store(ROLES_CACHE_KEY, it)
                    },
                )
                if (!committed) {
                    rolesState.value = rolesState.value.copy(loading = false)
                    return@launch
                }
                rolesState.value = StaffRolesState(rows = fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val hasCache = rolesState.value.rows.isNotEmpty()
                rolesState.value = rolesState.value.copy(
                    loading = false,
                    error = if (hasCache) {
                        "Live roles could not refresh. Cached role choices remain visible."
                    } else {
                        "Role choices could not load. Check the connection and refresh Staff before changing access."
                    },
                )
            }
        }
    }

    // -------------------------------------------------------------- loading

    fun retry() {
        if (loadPlan.loadRoles) loadRoles()
        if (loadPlan.pushManagementOutbox) appCtx.sync.requestSync()
        pulling.value = true
        viewModelScope.launch {
            try {
                if (loadPlan.pullDirectory) appCtx.sync.refresh("staff")
                if (loadPlan.pullAttendance) {
                    when (val result = appCtx.sync.refresh("attendance")) {
                        is ResourceRefreshResult.Refreshed -> editing.update {
                            it.copy(
                                attendanceUncertain = false,
                                clockedInOverride = null,
                                attendanceError = null,
                            )
                        }
                        is ResourceRefreshResult.Failed -> if (editing.value.attendanceUncertain) {
                            editing.update { it.copy(attendanceError = result.userMessage) }
                        }
                        is ResourceRefreshResult.Skipped -> Unit
                    }
                }
            } finally {
                pulling.value = false
            }
        }
    }

    fun dismissNotice() {
        editing.update { it.copy(notice = null) }
    }

    /** Same reasoning as CustomersViewModel.retrySync — a rejected row is parked, not auto-retried. */
    fun retrySync(row: StaffRow) {
        if (!access.canManageDirectory) return
        val localId = row.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.staffDao().getLocal(localId)?.let { existing ->
                        db.staffDao().upsertLocal(
                            existing.copy(
                                state = StaffWriteState.PENDING,
                                lastError = null,
                                version = existing.version + 1,
                            ),
                        )
                        queued = true
                    }
                }
            ) return@launch
            if (queued) appCtx.sync.requestSync()
        }
    }

    /**
     * A rejected row is definitive proof that the server changed nothing, so
     * staff may acknowledge and remove that local attempt. Pending/ambiguous
     * rows deliberately have no discard path: their request may have applied.
     */
    fun discardRejectedChange(row: StaffRow) {
        if (!access.canManageDirectory || !row.canDiscardRejectedChange) return
        val localId = row.localWriteId ?: return
        val expectedVersion = row.localWriteVersion ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var discarded = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    val current = db.staffDao().getLocal(localId)
                    if (current?.state == StaffWriteState.REJECTED) {
                        discarded = db.staffDao().deleteIfVersion(localId, expectedVersion) > 0
                    }
                }
            ) return@launch
            if (discarded) {
                editing.update {
                    it.copy(notice = "Rejected local Staff change discarded. Server data was not changed.")
                }
            }
        }
    }

    // --------------------------------------------------------------- editor

    fun startEdit(row: StaffRow) {
        if (!access.canManageDirectory) return
        val currentRole = row.roles.firstOrNull().orEmpty()
        editing.update {
            it.copy(
                editor = StaffEditor(
                    id = row.id,
                    name = row.name,
                    phone = row.phone.orEmpty(),
                    status = row.status,
                    roleCode = currentRole,
                    originalStatus = row.status,
                    originalRoleCode = currentRole,
                    isSelf = row.isSelf,
                    accessChangesLocked = row.accessChangesLocked,
                ),
                editError = null,
            )
        }
    }

    fun editorChanged(next: StaffEditor) {
        editing.update { it.copy(editor = next) }
    }

    fun cancelEdit() {
        editing.update { it.copy(editor = null, editError = null) }
    }

    /**
     * Always writes locally first — see LocalStaffEntity's doc comment on why
     * this is safe to queue offline.
     *
     * `status`/`roleCode` are only sent when they actually differ from what
     * the dialog opened with — same "only send what changed" rule Customers
     * already applies to phone. It matters more here than it looks: the
     * backend evicts every one of that user's live sessions (bumps
     * `auth_version`) whenever either field is *present* in the PATCH, not
     * just when it changes. Since this is an absolute-PATCH outbox (a retry
     * resends the row's full intended state, not a diff against whatever the
     * server has by then), always including the row's current status/role —
     * even though the backend now also guards this — would still mean a
     * phone-only fix drags along two fields with a real, if now-suppressed,
     * side effect. Omitting them here when unchanged keeps the PATCH honest
     * about intent, not just harmless.
     */
    fun saveEdit() {
        if (!access.canManageDirectory) return
        val ed = editing.value.editor ?: return
        if (editing.value.savingEdit) return
        ed.validationError?.let { message ->
            editing.update { it.copy(editError = message) }
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        editing.update { it.copy(savingEdit = true, editError = null) }
        viewModelScope.launch {
            try {
                val dao = db.staffDao()
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val existingPending = dao.pendingLocalForServerId(ed.id)
                        val local = (existingPending ?: LocalStaffEntity(
                            localId = UUID.randomUUID().toString(),
                            serverId = ed.id,
                            createdAtMillis = System.currentTimeMillis(),
                        )).copy(
                            name = ed.name.trim(),
                            // Empty is intentional: Kotlin null means "omit from PATCH",
                            // while the backend uses an empty string to clear a saved phone.
                            phone = ed.phone.trim(),
                    // Self can't change these — never send them, so a harmless
                    // resubmit of the current value can never trip the
                    // backend's own self-edit guard (router.py's is_self check).
                            status = if (ed.accessChangesLocked || ed.status == ed.originalStatus) null else ed.status,
                            roleCode = if (ed.accessChangesLocked || ed.roleCode == ed.originalRoleCode) {
                                null
                            } else {
                                ed.roleCode.ifBlank { null }
                            },
                    // Always false, not `existingPending?.pendingDelete`: the
                    // only way to reach this save with an existing local row
                    // already attached is either no pending write, or a
                    // *rejected* delete (a still-pending delete hides the
                    // Edit button — see StaffRowCard). Saving an edit is an
                    // explicit signal to keep the row, so a rejected
                    // delete's flag must not survive underneath it — found
                    // via Phase 9's Inventory review, same bug shape, this
                    // row silently requeuing as the same doomed delete
                    // instead of the edit just typed, behind a false "Saved"
                    // notice.
                            pendingDelete = false,
                            state = StaffWriteState.PENDING,
                            lastError = null,
                            version = (existingPending?.version ?: -1) + 1,
                        )
                        dao.upsertLocal(local)
                    }
                ) return@launch
                editing.update {
                    it.copy(editor = null, savingEdit = false, notice = "Saved — will sync when back online.")
                }
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(savingEdit = false, editError = e.message ?: "Could not save this change.")
                }
            }
        }
    }

    // --------------------------------------------------------------- delete

    fun startDelete(row: StaffRow) {
        if (!access.canManageDirectory || !row.canDelete) return
        editing.update { it.copy(deleteConfirmFor = row) }
    }

    fun cancelDelete() {
        editing.update { it.copy(deleteConfirmFor = null) }
    }

    fun confirmDelete() {
        if (!access.canManageDirectory) return
        val row = editing.value.deleteConfirmFor ?: return
        if (!row.canDelete) {
            editing.update {
                it.copy(
                    deleteConfirmFor = null,
                    notice = "Only the protected owner can remove another owner account.",
                )
            }
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        editing.update { it.copy(deleteConfirmFor = null) }
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    val dao = db.staffDao()
                    val existingPending = dao.pendingLocalForServerId(row.id)
                    val local = (existingPending ?: LocalStaffEntity(
                        localId = UUID.randomUUID().toString(),
                        serverId = row.id,
                        createdAtMillis = System.currentTimeMillis(),
                    )).copy(
                        pendingDelete = true,
                        state = StaffWriteState.PENDING,
                        lastError = null,
                        version = (existingPending?.version ?: -1) + 1,
                    )
                    dao.upsertLocal(local)
                }
            ) return@launch
            editing.update { it.copy(notice = "Will remove this login when back online.") }
            appCtx.sync.requestSync()
        }
    }

    /**
     * Abandons a queued delete — the only way back once "Remove" has been
     * confirmed, whether it's still waiting to sync or already came back
     * rejected (e.g. "protected owner cannot be deleted"). If the same local
     * row also carries genuine field edits (an edit made, then Remove tapped
     * before it synced — see LocalStaffEntity's doc comment on why both live
     * on one row), those edits are worth keeping: only clear `pendingDelete`
     * and requeue as a plain edit. A delete-only row has nothing left to
     * keep, so it's dropped outright.
     */
    fun cancelPendingRemoval(row: StaffRow) {
        if (!access.canManageDirectory) return
        val localId = row.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var changed = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    val dao = db.staffDao()
                    val existing = dao.getLocal(localId)
                    if (existing != null) {
                        val hasFieldEdits = existing.name != null || existing.phone != null ||
                            existing.status != null || existing.roleCode != null
                        if (hasFieldEdits) {
                            dao.upsertLocal(
                                existing.copy(
                                    pendingDelete = false,
                                    state = StaffWriteState.PENDING,
                                    lastError = null,
                                    version = existing.version + 1,
                                ),
                            )
                        } else {
                            dao.deleteLocal(localId)
                        }
                        changed = true
                    }
                }
            ) return@launch
            if (!changed) return@launch
            editing.update { it.copy(notice = "Removal cancelled.") }
            appCtx.sync.requestSync()
        }
    }

    // ---------------------------------------------------------- create login

    fun startCreateLogin() {
        if (!access.canManageDirectory) return
        editing.update {
            it.copy(createDraft = CreateLoginDraft(), createChallenge = null, createCode = "", createError = null)
        }
    }

    fun createDraftChanged(next: CreateLoginDraft) {
        editing.update { it.copy(createDraft = next) }
    }

    fun cancelCreateLogin() {
        editing.update {
            it.copy(createDraft = null, createChallenge = null, createCode = "", creating = false, createError = null)
        }
    }

    /** Online-only, direct call — see StaffApi.kt and LocalStaffEntity's doc comments on why this can't be queued. */
    fun requestCreateLogin() {
        if (!access.canManageDirectory) return
        val draft = editing.value.createDraft ?: return
        if (editing.value.creating) return
        draft.validationError?.let { message ->
            editing.update { it.copy(createError = message) }
            return
        }
        if (!appCtx.connectivity.online.value) {
            editing.update {
                it.copy(createError = "Creating a login needs an internet connection to send the approval code.")
            }
            return
        }
        editing.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val challenge = api.requestRegistration(
                    RegistrationRequestBody(
                        email = draft.email.trim(),
                        name = draft.name.trim(),
                        phone = draft.phone.trim().ifBlank { null },
                        password = draft.password,
                    ),
                )
                // Clears any code already typed against a now-superseded
                // challenge — matters for "Resend code" specifically (see
                // CreateLoginDialog), not just the first request.
                editing.update { it.copy(creating = false, createChallenge = challenge, createCode = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(creating = false, createError = (e as? ApiException)?.message ?: "Could not send the code.")
                }
            }
        }
    }

    fun createCodeChanged(code: String) {
        editing.update { it.copy(createCode = code) }
    }

    /**
     * On success the backend returns only `{"message": ...}` — no id (see
     * StaffApi.kt's doc comment) — so this just closes the dialog and
     * re-pulls the list, exactly matching the web app's own behavior
     * (StaffScreen.tsx's AddUserModal `onSuccess()` → `load()`), rather than
     * inventing an id-lookup round trip the backend doesn't support.
     */
    fun confirmCreateLogin() {
        if (!access.canManageDirectory) return
        val challenge = editing.value.createChallenge ?: return
        val code = editing.value.createCode
        if (editing.value.creating) return
        if (code.length != 6) {
            editing.update { it.copy(createError = "Enter the complete 6-digit code.") }
            return
        }
        if (!appCtx.connectivity.online.value) {
            editing.update {
                it.copy(createError = "Reconnect before confirming the approval code. The login has not been created.")
            }
            return
        }
        editing.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                api.confirmRegistration(RegistrationConfirmBody(challenge.challengeId, code))
                editing.update {
                    it.copy(
                        createDraft = null, createChallenge = null, createCode = "", creating = false,
                        notice = "Login created with Staff access. Edit the account to assign another role if needed.",
                    )
                }
                retry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(creating = false, createError = (e as? ApiException)?.message ?: "Could not confirm the code.")
                }
            }
        }
    }

    // ------------------------------------------------------- password reset

    fun startPasswordReset(row: StaffRow) {
        if (!access.canManageDirectory) return
        editing.update {
            it.copy(
                passwordReset = PasswordResetDraft(forUserId = row.id, forName = row.name, email = row.email),
                passwordResetChallenge = null,
                passwordResetCode = "",
                passwordResetError = null,
            )
        }
    }

    fun passwordResetDraftChanged(next: PasswordResetDraft) {
        editing.update { it.copy(passwordReset = next) }
    }

    fun cancelPasswordReset() {
        editing.update {
            it.copy(
                passwordReset = null, passwordResetChallenge = null, passwordResetCode = "",
                resettingPassword = false, passwordResetError = null,
            )
        }
    }

    fun requestPasswordReset() {
        if (!access.canManageDirectory) return
        val draft = editing.value.passwordReset ?: return
        if (editing.value.resettingPassword) return
        if (!appCtx.connectivity.online.value) {
            editing.update {
                it.copy(passwordResetError = "Password reset needs an internet connection to send the approval code.")
            }
            return
        }
        editing.update { it.copy(resettingPassword = true, passwordResetError = null) }
        viewModelScope.launch {
            try {
                val challenge = api.requestPasswordReset(PasswordResetRequestBody(draft.email))
                editing.update {
                    it.copy(resettingPassword = false, passwordResetChallenge = challenge, passwordResetCode = "")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(
                        resettingPassword = false,
                        passwordResetError = (e as? ApiException)?.message ?: "Could not send the code.",
                    )
                }
            }
        }
    }

    fun passwordResetCodeChanged(code: String) {
        editing.update { it.copy(passwordResetCode = code) }
    }

    fun confirmPasswordReset() {
        if (!access.canManageDirectory) return
        val challenge = editing.value.passwordResetChallenge ?: return
        val draft = editing.value.passwordReset ?: return
        val code = editing.value.passwordResetCode
        if (editing.value.resettingPassword) return
        val validationError = when {
            code.length != 6 -> "Enter the complete 6-digit code."
            else -> draft.validationError
        }
        if (validationError != null) {
            editing.update { it.copy(passwordResetError = validationError) }
            return
        }
        if (!appCtx.connectivity.online.value) {
            editing.update {
                it.copy(passwordResetError = "Reconnect before confirming the approval code. The password has not changed.")
            }
            return
        }
        editing.update { it.copy(resettingPassword = true, passwordResetError = null) }
        viewModelScope.launch {
            try {
                api.confirmPasswordReset(PasswordResetConfirmBody(challenge.challengeId, code, draft.newPassword))
                editing.update {
                    it.copy(
                        passwordReset = null, passwordResetChallenge = null, passwordResetCode = "",
                        resettingPassword = false, notice = "Password reset for ${draft.forName}.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(
                        resettingPassword = false,
                        passwordResetError = (e as? ApiException)?.message ?: "Could not confirm the code.",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------- attendance

    /**
     * Direct calls, never queued — see OnShiftEntity's doc comment. Matches
     * the web app's own AttendanceWidget: a failure just surfaces inline,
     * `clockedIn` is re-derived from the roster after a fresh pull rather
     * than assumed from the write's own response.
     */
    fun clockIn() {
        if (!access.canUseAttendance) return
        if (editing.value.clockingInOut) return
        if (editing.value.attendanceUncertain) {
            editing.update {
                it.copy(attendanceError = "Refresh attendance to resolve the previous unknown result before trying again.")
            }
            return
        }
        if (!appCtx.connectivity.online.value) {
            editing.update {
                it.copy(attendanceError = "Clock-in needs an internet connection. Reconnect, then try again.")
            }
            return
        }
        val branchId = resolveAttendanceBranchId(
            myProfile.value?.branchId,
            appCtx.terminalStore.activeValidatedTerminal.value?.branchId,
        )
        if (branchId == null) {
            editing.update {
                it.copy(
                    attendanceError = "No branch is selected. Choose a verified terminal for this branch, then try again.",
                )
            }
            return
        }
        editing.update { it.copy(clockingInOut = true, attendanceError = null) }
        viewModelScope.launch {
            try {
                api.clockIn(ClockInBody(branchId))
                when (val refresh = appCtx.sync.refresh("attendance")) {
                    is ResourceRefreshResult.Refreshed -> editing.update {
                        it.copy(clockedInOverride = null, attendanceUncertain = false, notice = "Clocked in.")
                    }
                    is ResourceRefreshResult.Failed -> editing.update {
                        it.copy(
                            clockedInOverride = true,
                            attendanceUncertain = false,
                            attendanceError = "Clock-in succeeded, but the roster could not refresh: ${refresh.userMessage}",
                        )
                    }
                    is ResourceRefreshResult.Skipped -> editing.update {
                        it.copy(
                            clockedInOverride = true,
                            attendanceUncertain = false,
                            attendanceError = "Clock-in succeeded, but this account could not refresh the roster.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val apiError = e as? ApiException
                editing.update {
                    it.copy(
                        attendanceUncertain = apiError?.isAmbiguous == true,
                        attendanceError = if (apiError?.isAmbiguous == true) {
                            "The clock-in response was lost, so its status is unknown. Refresh attendance before trying again."
                        } else {
                            apiError?.message ?: "Could not clock in. Check the connection and try again."
                        },
                    )
                }
            } finally {
                editing.update { it.copy(clockingInOut = false) }
            }
        }
    }

    fun clockOut() {
        if (!access.canUseAttendance) return
        if (editing.value.clockingInOut) return
        if (editing.value.attendanceUncertain) {
            editing.update {
                it.copy(attendanceError = "Refresh attendance to resolve the previous unknown result before trying again.")
            }
            return
        }
        if (!appCtx.connectivity.online.value) {
            editing.update {
                it.copy(attendanceError = "Clock-out needs an internet connection. Reconnect, then try again.")
            }
            return
        }
        editing.update { it.copy(clockingInOut = true, attendanceError = null) }
        viewModelScope.launch {
            try {
                api.clockOut()
                when (val refresh = appCtx.sync.refresh("attendance")) {
                    is ResourceRefreshResult.Refreshed -> editing.update {
                        it.copy(clockedInOverride = null, attendanceUncertain = false, notice = "Clocked out.")
                    }
                    is ResourceRefreshResult.Failed -> editing.update {
                        it.copy(
                            clockedInOverride = false,
                            attendanceUncertain = false,
                            attendanceError = "Clock-out succeeded, but the roster could not refresh: ${refresh.userMessage}",
                        )
                    }
                    is ResourceRefreshResult.Skipped -> editing.update {
                        it.copy(
                            clockedInOverride = false,
                            attendanceUncertain = false,
                            attendanceError = "Clock-out succeeded, but this account could not refresh the roster.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val apiError = e as? ApiException
                editing.update {
                    it.copy(
                        attendanceUncertain = apiError?.isAmbiguous == true,
                        attendanceError = if (apiError?.isAmbiguous == true) {
                            "The clock-out response was lost, so its status is unknown. Refresh attendance before trying again."
                        } else {
                            apiError?.message ?: "Could not clock out. Check the connection and try again."
                        },
                    )
                }
            } finally {
                editing.update { it.copy(clockingInOut = false) }
            }
        }
    }

    fun dismissAttendanceError() {
        editing.update { it.copy(attendanceError = null) }
    }
}

internal fun mergeStaff(
    cache: List<StaffCacheEntity>,
    local: List<LocalStaffEntity>,
    myUserId: String?,
    callerHasAuditAccess: Boolean,
): List<StaffRow> {
    val localByServerId = local.associateBy { it.serverId }
    val result = ArrayList<StaffRow>(cache.size)
    for (c in cache) {
        val pending = localByServerId[c.id]
        // A definitive refusal changed nothing server-side. Never present its
        // attempted name/status/role as current authority; keep only the error
        // and recovery controls over the authoritative cache row.
        val overlay = pending?.takeUnless { it.state == StaffWriteState.REJECTED }
        val cachedRoles = c.rolesCsv.split(",").filter(String::isNotBlank)
        val roles = overlay?.roleCode?.let { listOf(it) } ?: cachedRoles
        val isSelf = c.id == myUserId
        // Keep owner protections anchored to last confirmed server authority,
        // not an optimistic queued demotion that may still be refused.
        val isOwner = (cachedRoles + roles).any {
            it == "owner" || it == "co_owner" || it == "super_owner"
        }
        result += StaffRow(
            id = c.id,
            email = c.email,
            name = overlay?.name ?: c.name,
            phone = (overlay?.phone ?: c.phone),
            status = overlay?.status ?: c.status,
            roles = roles,
            lastLoginAt = c.lastLoginAt,
            isSelf = isSelf,
            pendingLocalId = pending?.takeIf { it.state != StaffWriteState.REJECTED }?.localId,
            rejectedError = pending?.takeIf { it.state == StaffWriteState.REJECTED }?.lastError,
            // Same REJECTED guard as pendingLocalId above — a rejected delete
            // (e.g. the server refusing "protected owner cannot be deleted")
            // must fall through to the rejectedError banner, not keep
            // claiming forever that removal is still pending. Missing this
            // guard was a real bug: it left a rejected delete permanently
            // stuck showing "Pending removal" with every action button
            // hidden, since StaffRowCard hides the button row while
            // pendingDelete is true.
            pendingDelete = pending?.takeIf { it.state != StaffWriteState.REJECTED }?.pendingDelete ?: false,
            hasQueuedDelete = pending?.pendingDelete ?: false,
            localWriteId = pending?.localId,
            localWriteVersion = pending?.version,
            accessChangesLocked = isSelf || (isOwner && !callerHasAuditAccess),
            canDelete = !isSelf && (!isOwner || callerHasAuditAccess),
        )
    }
    return result.sortedBy { it.name }
}
