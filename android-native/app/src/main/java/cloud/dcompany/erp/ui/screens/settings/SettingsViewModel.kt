package cloud.dcompany.erp.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.BranchCacheEntity
import cloud.dcompany.erp.core.db.CompanyCacheEntity
import cloud.dcompany.erp.core.db.LocalBranchEntity
import cloud.dcompany.erp.core.db.LocalCompanyEditEntity
import cloud.dcompany.erp.core.db.LocalTerminalEntity
import cloud.dcompany.erp.core.db.SettingsWriteState
import cloud.dcompany.erp.core.db.TerminalCacheEntity
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

enum class SettingsTab(val label: String) {
    Account("Account"),
    Company("Company"),
    Branches("Branches"),
    Terminals("Terminals"),
}

data class PendingBranchRow(
    val localId: String,
    val name: String,
    val rejected: Boolean,
    val error: String?,
)

data class PendingTerminalRow(
    val localId: String,
    val branchId: String,
    val branchName: String,
    val name: String,
    val rejected: Boolean,
    val error: String?,
)

data class SettingsUiState(
    val tab: SettingsTab = SettingsTab.Account,

    // -- account (online-only — see SettingsApi's class doc) ----------------
    val meLoading: Boolean = true,
    val meError: String? = null,
    val me: MeResponse? = null,
    val challenge: OtpChallengeDto? = null,
    val accountBusy: Boolean = false,
    val accountError: String? = null,
    val accountNotice: String? = null,

    // -- company (cache + Shape C pending edit) ------------------------------
    val companyLoading: Boolean = true,
    val companyRefreshing: Boolean = false,
    val companyError: String? = null,
    val company: CompanyDto? = null,
    val companyForm: CompanyForm = CompanyForm(),
    val companySaving: Boolean = false,
    val companyFormError: String? = null,
    val companyNotice: String? = null,
    val companyPending: Boolean = false,
    val companyPendingLocalId: String? = null,
    val companyRejectedError: String? = null,

    // -- branches (cache + Shape D create-only outbox) -----------------------
    val branchesLoading: Boolean = true,
    val branchesRefreshing: Boolean = false,
    val branchesError: String? = null,
    val branches: List<BranchDto> = emptyList(),
    val pendingBranches: List<PendingBranchRow> = emptyList(),
    val branchForm: BranchForm? = null,
    val branchSaving: Boolean = false,
    val branchFormError: String? = null,
    val branchNotice: String? = null,

    // -- terminals (cache + Shape D create-only outbox) -----------------------
    val terminalsLoading: Boolean = false,
    val terminalsError: String? = null,
    val allTerminals: List<TerminalDto> = emptyList(),
    val pendingTerminals: List<PendingTerminalRow> = emptyList(),
    val selectedBranchId: String? = null,
    val terminalBusy: Boolean = false,
    val terminalName: String = "",
    val terminalDeviceId: String = "",
    val terminalFormError: String? = null,
    val terminalActionError: String? = null,
    val terminalNotice: String? = null,
) {
    val companyDirty: Boolean
        get() = company?.let { it.toForm() != companyForm } ?: false

    fun branchName(id: String?): String? = branches.firstOrNull { it.id == id }?.name

    /** Only already-synced branches ever appear in [branches] — a locally
     * pending, not-yet-synced branch has no real server id yet, so a new
     * terminal can't safely be filed under one (dependency-sidestep, same
     * pattern as Memberships' customer_cache-only picker). */
    val terminals: List<TerminalDto>
        get() = allTerminals.filter { it.branchId == selectedBranchId }

    val branchFormDirty: Boolean
        get() {
            val form = branchForm ?: return false
            val original = form.id
                ?.let { id -> branches.firstOrNull { it.id == id }?.toForm() }
                ?: BranchForm()
            return form != original
        }
}

internal enum class SettingsReadPresentation {
    INITIAL_LOADING,
    BLOCKING_ERROR,
    FRESH,
    REFRESHING,
    STALE,
}

internal fun settingsReadPresentation(
    hasData: Boolean,
    loading: Boolean,
    error: String?,
    emptyIsValid: Boolean = false,
): SettingsReadPresentation = when {
    !hasData && loading -> SettingsReadPresentation.INITIAL_LOADING
    !hasData && error != null -> SettingsReadPresentation.BLOCKING_ERROR
    !hasData && !emptyIsValid -> SettingsReadPresentation.INITIAL_LOADING
    error != null -> SettingsReadPresentation.STALE
    loading -> SettingsReadPresentation.REFRESHING
    else -> SettingsReadPresentation.FRESH
}

/**
 * Settings — lowest-frequency screen in this rebuild. Company profile edits
 * and new Branch/Terminal creation are real offline outbox writes; editing
 * an existing branch, deleting a terminal, and the Account tab all stay
 * online-only, per SettingsApi's class doc.
 */
class SettingsViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<SettingsApi>()
    private val keys = IdempotencyKeys()
    private val cacheIsolation = appCtx.cacheIsolation

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadMe()
        observeCompanyCache()
        observeBranchesCache()
        observeTerminalsCache()
    }

    fun selectTab(tab: SettingsTab) {
        _state.value = _state.value.copy(tab = tab)
        when (tab) {
            SettingsTab.Account -> Unit
            SettingsTab.Company -> loadCompany()
            SettingsTab.Branches -> loadBranches()
            SettingsTab.Terminals -> {
                loadBranches()
                loadTerminalsCache()
            }
        }
    }

    // ================================================================ account
    // Unchanged from before this phase — inherently online-only (see
    // SettingsApi's class doc): the OTP code is a live round-trip to the
    // business security mailbox, nothing here can be queued offline.

    fun loadMe() {
        _state.value = _state.value.copy(meLoading = true, meError = null)
        viewModelScope.launch {
            try {
                val me = ApiClient.api.me()
                _state.value = _state.value.copy(
                    me = me,
                    selectedBranchId = _state.value.selectedBranchId ?: me.branchId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    meError = error.settingsReadable("Could not refresh your account."),
                )
            } finally {
                _state.value = _state.value.copy(meLoading = false)
            }
        }
    }

    fun requestPasswordCode() {
        if (_state.value.accountBusy) return
        val email = _state.value.me?.email
        if (email.isNullOrBlank()) {
            _state.value = _state.value.copy(
                accountError = "Your login email is unavailable, so no code can be sent.",
            )
            return
        }
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                val challenge = api.requestPasswordReset(PasswordResetRequestBody(email))
                _state.value = _state.value.copy(challenge = challenge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    accountError = error.settingsReadable("Could not send the approval code."),
                )
            } finally {
                _state.value = _state.value.copy(accountBusy = false)
            }
        }
    }

    fun confirmPasswordChange(code: String, newPassword: String, confirmPassword: String) {
        if (_state.value.accountBusy) return
        val challenge = _state.value.challenge ?: return
        if (newPassword.length < 10) {
            _state.value = _state.value.copy(
                accountError = "Password must be at least 10 characters.",
            )
            return
        }
        if (newPassword != confirmPassword) {
            _state.value = _state.value.copy(accountError = "New passwords do not match.")
            return
        }
        if (!Regex("^\\d{6}$").matches(code)) {
            _state.value = _state.value.copy(accountError = "Enter the 6-digit approval code.")
            return
        }
        _state.value = _state.value.copy(accountBusy = true, accountError = null)
        viewModelScope.launch {
            try {
                val result = api.confirmPasswordReset(
                    PasswordResetConfirmBody(
                        challengeId = challenge.challengeId,
                        code = code,
                        newPassword = newPassword,
                    ),
                )
                _state.value = _state.value.copy(
                    challenge = null,
                    accountNotice = result.message.ifBlank { "Password updated." },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    accountError = error.settingsReadable("Could not change the password."),
                )
            } finally {
                _state.value = _state.value.copy(accountBusy = false)
            }
        }
    }

    fun cancelPasswordChange() {
        _state.value = _state.value.copy(challenge = null, accountError = null)
    }

    fun dismissAccountFeedback() {
        _state.value = _state.value.copy(accountError = null, accountNotice = null)
    }

    // ================================================================ company

    private fun observeCompanyCache() {
        viewModelScope.launch {
            combine(
                db.settingsDao().observeCompany(),
                db.settingsDao().observePendingCompanyEdit(),
            ) { cached, pending -> cached to pending }.collect { (cached, pending) ->
                val base = cached?.toDto()
                val effective = pending?.overlayOnto(base) ?: base
                val seedForm = _state.value.companyLoading && effective != null
                _state.value = _state.value.copy(
                    companyLoading = if (effective != null) false else _state.value.companyLoading,
                    company = effective ?: _state.value.company,
                    companyForm = if (seedForm) effective!!.toForm() else _state.value.companyForm,
                    companyPending = pending != null && pending.syncState != SettingsWriteState.SYNCED,
                    companyPendingLocalId = pending
                        ?.takeIf { it.syncState != SettingsWriteState.SYNCED }
                        ?.localId,
                    companyRejectedError = pending
                        ?.takeIf { it.syncState == SettingsWriteState.REJECTED }?.lastError,
                )
            }
        }
    }

    fun loadCompany() {
        _state.value = _state.value.copy(
            companyLoading = _state.value.company == null,
            companyRefreshing = _state.value.company != null,
            companyError = null,
        )
        viewModelScope.launch {
            try {
                refreshCompanyCache()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    companyError = error.settingsReadable("Could not refresh company settings."),
                )
            } finally {
                _state.value = _state.value.copy(
                    companyLoading = false,
                    companyRefreshing = false,
                )
            }
        }
    }

    fun editCompany(block: (CompanyForm) -> CompanyForm) {
        _state.value = _state.value.copy(
            companyForm = block(_state.value.companyForm),
            companyFormError = null,
            companyNotice = null,
        )
    }

    fun resetCompanyEdits() {
        val company = _state.value.company ?: return
        _state.value = _state.value.copy(
            companyForm = company.toForm(),
            companyFormError = null,
            companyNotice = null,
        )
    }

    /** Shape C: queues the edit locally and replays it via PATCH when back
     * online. No Idempotency-Key is needed — "set fields to X" is naturally
     * safe to retry, same reasoning as Events' check-in and Memberships'
     * cancel. A fresh edit before the last one syncs replaces it rather than
     * queueing a second (see SettingsDao.replacePendingCompanyEdit). */
    fun saveCompany() {
        if (_state.value.companySaving) return
        val form = _state.value.companyForm
        form.validate()?.let { message ->
            _state.value = _state.value.copy(companyFormError = message)
            return
        }
        val scopeLease = cacheIsolation.currentLease() ?: return
        _state.value = _state.value.copy(
            companySaving = true, companyFormError = null, companyNotice = null,
        )
        viewModelScope.launch {
            try {
                if (!cacheIsolation.commitIfCurrent(scopeLease) {
                        db.settingsDao().replacePendingCompanyEdit(
                            LocalCompanyEditEntity(
                                localId = UUID.randomUUID().toString(),
                                name = form.name.trim(),
                                legalName = form.legalName.trim().ifBlank { null },
                                timezone = form.timezone.trim(),
                                gstin = form.gstin.trim().uppercase().ifBlank { null },
                                pan = form.pan.trim().uppercase().ifBlank { null },
                                gstRegistrationType = form.gstRegistrationType,
                                isComposition = form.isComposition,
                                eInvoicingEnabled = form.eInvoicingEnabled,
                                upiVpa = form.upiVpa.trim(),
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                ) return@launch
                _state.value = _state.value.copy(
                    companyNotice = "Change queued — will sync when back online.",
                )
                clearNoticeLater { it.copy(companyNotice = null) }
                appCtx.sync.requestSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    companyFormError = error.settingsReadable(
                        "Could not save this company change on the tablet.",
                    ),
                )
            } finally {
                _state.value = _state.value.copy(companySaving = false)
            }
        }
    }

    fun retryCompanyEdit() {
        val localId = _state.value.companyPendingLocalId ?: return
        val scopeLease = cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!cacheIsolation.commitIfCurrent(scopeLease) {
                    queued = db.settingsDao().retryCompanyEdit(localId) == 1
                }
            ) return@launch
            _state.value = _state.value.copy(
                companyNotice = if (queued) {
                    "Retry queued for the same company change. It will sync when online."
                } else {
                    "That company change is no longer rejected. Its current status is shown above."
                },
            )
            if (queued) appCtx.sync.requestSync()
        }
    }

    fun discardRejectedCompanyEdit() {
        val localId = _state.value.companyPendingLocalId ?: return
        val scopeLease = cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var discarded = false
                var cached: CompanyDto? = null
                if (!cacheIsolation.commitIfCurrent(scopeLease) {
                        discarded = db.settingsDao().discardRejectedCompanyEdit(localId) == 1
                        if (discarded) cached = db.settingsDao().company()?.toDto()
                    }
                ) return@launch
                if (!discarded) {
                    _state.value = _state.value.copy(
                        companyNotice =
                            "That company change is no longer rejected, so it was not discarded.",
                    )
                    return@launch
                }
                _state.value = _state.value.copy(
                    company = cached,
                    companyForm = cached?.toForm() ?: CompanyForm(),
                    companyNotice =
                        "Failed local company change discarded. Settings will refresh from the server when online.",
                )
                try {
                    refreshCompanyCache()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.value = _state.value.copy(
                        companyError = error.settingsReadable(
                            "The failed local change was discarded, but current company settings could not be refreshed.",
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    companyNotice = error.settingsReadable(
                        "Could not discard the failed company change from this tablet.",
                    ),
                )
            }
        }
    }

    fun dismissCompanyNotice() {
        _state.value = _state.value.copy(companyNotice = null)
    }

    // =============================================================== branches

    private fun observeBranchesCache() {
        viewModelScope.launch {
            combine(
                db.settingsDao().observeBranchCache(),
                db.settingsDao().observeLocalBranches(),
            ) { cache, local -> cache to local }.collect { (cache, local) ->
                val branches = cache.map { it.toDto() }
                _state.value = _state.value.copy(
                    branchesLoading = false,
                    branches = branches,
                    pendingBranches = local.map { it.toPendingRow() },
                    selectedBranchId = _state.value.selectedBranchId
                        ?.takeIf { id -> branches.any { it.id == id } }
                        ?: branches.firstOrNull()?.id,
                )
            }
        }
    }

    fun loadBranches() {
        _state.value = _state.value.copy(
            branchesLoading = _state.value.branches.isEmpty(),
            branchesRefreshing = _state.value.branches.isNotEmpty(),
            branchesError = null,
        )
        viewModelScope.launch {
            try {
                refreshBranchesCache()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    branchesError = error.settingsReadable("Could not refresh branches."),
                )
            } finally {
                _state.value = _state.value.copy(
                    branchesLoading = false,
                    branchesRefreshing = false,
                )
            }
        }
    }

    fun newBranch() {
        _state.value = _state.value.copy(
            branchForm = BranchForm(), branchFormError = null, branchNotice = null,
        )
    }

    /** Editing an existing branch stays online-only (see SettingsApi's
     * class doc) — this form is only ever opened for a new branch now. */
    fun editBranch(branch: BranchDto) {
        _state.value = _state.value.copy(
            branchForm = branch.toForm(), branchFormError = null, branchNotice = null,
        )
    }

    fun updateBranchForm(block: (BranchForm) -> BranchForm) {
        val current = _state.value.branchForm ?: return
        _state.value = _state.value.copy(
            branchForm = block(current), branchFormError = null,
        )
    }

    fun closeBranchForm() {
        _state.value = _state.value.copy(branchForm = null, branchFormError = null)
    }

    fun saveBranch() {
        if (_state.value.branchSaving) return
        val form = _state.value.branchForm ?: return
        form.validate()?.let { message ->
            _state.value = _state.value.copy(branchFormError = message)
            return
        }
        _state.value = _state.value.copy(branchSaving = true, branchFormError = null)
        if (form.isNew) {
            val scopeLease = cacheIsolation.currentLease() ?: run {
                _state.value = _state.value.copy(branchSaving = false)
                return
            }
            viewModelScope.launch {
                try {
                    if (!cacheIsolation.commitIfCurrent(scopeLease) {
                            db.settingsDao().insertLocalBranch(
                                LocalBranchEntity(
                                    localId = UUID.randomUUID().toString(),
                                    name = form.name.trim(),
                                    code = form.code.trim().uppercase().ifBlank { null },
                                    address = form.address.trim().ifBlank { null },
                                    timezone = form.timezone.trim().ifBlank { null },
                                    opensAt = form.opensAt.trim().ifBlank { null },
                                    closesAt = form.closesAt.trim().ifBlank { null },
                                    stateCode = form.stateCode.trim().ifBlank { null },
                                    fssaiLicenseNo = form.fssaiLicenseNo.trim().ifBlank { null },
                                    tradeLicenseNo = form.tradeLicenseNo.trim().ifBlank { null },
                                    branchGstin = form.branchGstin.trim().uppercase().ifBlank { null },
                                    createdAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    ) return@launch
                    _state.value = _state.value.copy(
                        branchForm = null,
                        branchNotice = "Branch \"${form.name.trim()}\" queued — will sync when back online.",
                    )
                    clearNoticeLater { it.copy(branchNotice = null) }
                    appCtx.sync.requestSync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.value = _state.value.copy(
                        branchFormError = error.settingsReadable(
                            "Could not save this branch on the tablet.",
                        ),
                    )
                } finally {
                    _state.value = _state.value.copy(branchSaving = false)
                }
            }
            return
        }
        // Editing an existing branch — unchanged, direct online call.
        val body = form.toBody()
        val operation = "branch:${form.id}"
        val key = keys.keyFor(operation, body)
        viewModelScope.launch {
            try {
                val saved = api.updateBranch(form.id!!, body, key)
                keys.done(operation)
                _state.value = _state.value.copy(
                    branchForm = null,
                    branchNotice = "Branch \"${saved.name}\" saved.",
                )
                loadBranches()
                clearNoticeLater { it.copy(branchNotice = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    branchFormError = error.settingsReadable("Could not save this branch."),
                )
            } finally {
                _state.value = _state.value.copy(branchSaving = false)
            }
        }
    }

    fun retryBranch(localId: String) {
        val scopeLease = cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!cacheIsolation.commitIfCurrent(scopeLease) {
                    queued = db.settingsDao().retryBranch(localId) == 1
                }
            ) return@launch
            _state.value = _state.value.copy(
                branchNotice = if (queued) {
                    "Retry queued for the same branch. It will sync when online."
                } else {
                    "That branch is no longer rejected. Its current status is shown above."
                },
            )
            if (queued) appCtx.sync.requestSync()
        }
    }

    fun discardRejectedBranch(localId: String) {
        val scopeLease = cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var discarded = false
                if (!cacheIsolation.commitIfCurrent(scopeLease) {
                        discarded = db.settingsDao().discardRejectedBranch(localId) == 1
                    }
                ) return@launch
                _state.value = _state.value.copy(
                    branchNotice = if (discarded) {
                        "Failed local branch discarded. Settings will refresh from the server when online."
                    } else {
                        "That branch is no longer rejected, so it was not discarded."
                    },
                )
                if (discarded) {
                    try {
                        refreshBranchesCache()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _state.value = _state.value.copy(
                            branchesError = error.settingsReadable(
                                "The failed local branch was discarded, but branches could not be refreshed.",
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    branchNotice = error.settingsReadable("Could not discard the failed branch."),
                )
            }
        }
    }

    fun dismissBranchNotice() {
        _state.value = _state.value.copy(branchNotice = null)
    }

    // ============================================================== terminals

    private fun observeTerminalsCache() {
        viewModelScope.launch {
            combine(
                db.settingsDao().observeTerminalCache(),
                db.settingsDao().observeLocalTerminals(),
            ) { cache, local -> cache to local }.collect { (cache, local) ->
                val branches = _state.value.branches
                _state.value = _state.value.copy(
                    terminalsLoading = false,
                    allTerminals = cache.map { it.toDto() },
                    pendingTerminals = local.map { it.toPendingRow(branches) },
                )
            }
        }
    }

    fun loadTerminalsCache() {
        _state.value = _state.value.copy(terminalsLoading = true, terminalsError = null)
        viewModelScope.launch {
            try {
                refreshTerminalsCache()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    terminalsError = error.settingsReadable("Could not refresh terminals."),
                )
            } finally {
                _state.value = _state.value.copy(terminalsLoading = false)
            }
        }
    }

    fun selectBranch(id: String) {
        _state.value = _state.value.copy(selectedBranchId = id)
    }

    fun setTerminalName(value: String) {
        _state.value = _state.value.copy(terminalName = value, terminalFormError = null)
    }

    fun setTerminalDeviceId(value: String) {
        _state.value = _state.value.copy(terminalDeviceId = value, terminalFormError = null)
    }

    fun addTerminal() {
        if (_state.value.terminalBusy) return
        val branchId = _state.value.selectedBranchId ?: run {
            _state.value = _state.value.copy(
                terminalFormError = "Select or create a branch before adding a terminal.",
            )
            return
        }
        val name = _state.value.terminalName.trim()
        if (name.isEmpty()) {
            _state.value = _state.value.copy(terminalFormError = "Give the till a name.")
            return
        }
        if (name.length > 100) {
            _state.value = _state.value.copy(
                terminalFormError = "Till name must be 100 characters or fewer.",
            )
            return
        }
        val deviceId = _state.value.terminalDeviceId.trim().ifBlank { null }
        val deviceIdError = terminalDeviceIdError(_state.value.terminalDeviceId)
        if (deviceIdError != null) {
            _state.value = _state.value.copy(
                terminalFormError = deviceIdError,
            )
            return
        }
        val scopeLease = cacheIsolation.currentLease() ?: return
        _state.value = _state.value.copy(
            terminalBusy = true,
            terminalFormError = null,
            terminalActionError = null,
        )
        viewModelScope.launch {
            try {
                if (!cacheIsolation.commitIfCurrent(scopeLease) {
                        db.settingsDao().insertLocalTerminal(
                            LocalTerminalEntity(
                                localId = UUID.randomUUID().toString(),
                                branchId = branchId,
                                name = name,
                                deviceId = deviceId,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                ) return@launch
                _state.value = _state.value.copy(
                    terminalName = "",
                    terminalDeviceId = "",
                    terminalNotice = "Till \"$name\" queued — will sync when back online.",
                )
                clearNoticeLater { it.copy(terminalNotice = null) }
                appCtx.sync.requestSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    terminalFormError = error.settingsReadable(
                        "Could not save this terminal on the tablet.",
                    ),
                )
            } finally {
                _state.value = _state.value.copy(terminalBusy = false)
            }
        }
    }

    fun retryTerminal(localId: String) {
        val scopeLease = cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!cacheIsolation.commitIfCurrent(scopeLease) {
                    queued = db.settingsDao().retryTerminal(localId) == 1
                }
            ) return@launch
            _state.value = _state.value.copy(
                terminalNotice = if (queued) {
                    "Retry queued for the same terminal. It will sync when online."
                } else {
                    "That terminal is no longer rejected. Its current status is shown above."
                },
            )
            if (queued) appCtx.sync.requestSync()
        }
    }

    fun discardRejectedTerminal(localId: String) {
        val scopeLease = cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            try {
                var discarded = false
                if (!cacheIsolation.commitIfCurrent(scopeLease) {
                        discarded = db.settingsDao().discardRejectedTerminal(localId) == 1
                    }
                ) return@launch
                _state.value = _state.value.copy(
                    terminalNotice = if (discarded) {
                        "Failed local terminal discarded. Settings will refresh from the server when online."
                    } else {
                        "That terminal is no longer rejected, so it was not discarded."
                    },
                )
                if (discarded) {
                    try {
                        refreshTerminalsCache()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _state.value = _state.value.copy(
                            terminalsError = error.settingsReadable(
                                "The failed local terminal was discarded, but terminals could not be refreshed.",
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    terminalNotice = error.settingsReadable("Could not discard the failed terminal."),
                )
            }
        }
    }

    /** Deleting a terminal stays online-only (see SettingsApi's class doc)
     * — unchanged from before this phase. */
    fun deleteTerminal(terminal: TerminalDto) {
        if (_state.value.terminalBusy) return
        val operation = "terminal:delete:${terminal.id}"
        val key = keys.keyFor(operation, terminal.id)
        _state.value = _state.value.copy(
            terminalBusy = true,
            terminalActionError = null,
        )
        viewModelScope.launch {
            try {
                api.deleteTerminal(terminal.id, key)
                keys.done(operation)
                _state.value = _state.value.copy(
                    terminalNotice = "Till \"${terminal.name}\" removed.",
                )
                loadTerminalsCache()
                clearNoticeLater { it.copy(terminalNotice = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 409 here is the useful case: the backend refuses to delete a
                // till that shifts or orders point at, because that history is
                // what a GST audit reads.
                _state.value = _state.value.copy(
                    terminalActionError = error.settingsReadable("Could not delete this terminal."),
                )
            } finally {
                _state.value = _state.value.copy(terminalBusy = false)
            }
        }
    }

    fun dismissTerminalFeedback() {
        _state.value = _state.value.copy(
            terminalFormError = null,
            terminalActionError = null,
            terminalNotice = null,
        )
    }

    // ================================================================ helpers

    /** Settings needs the outcome, unlike the broad background refresh API
     * which deliberately absorbs expected offline failures. These narrow
     * pulls retain the same cache-scope lease before committing Room rows. */
    private suspend fun refreshCompanyCache() {
        appCtx.sync.refreshSettingsCompany()
    }

    private suspend fun refreshBranchesCache() {
        appCtx.sync.refreshSettingsBranches()
    }

    private suspend fun refreshTerminalsCache() {
        appCtx.sync.refreshSettingsTerminals()
    }

    private fun clearNoticeLater(block: (SettingsUiState) -> SettingsUiState) {
        viewModelScope.launch {
            delay(3_000)
            _state.value = block(_state.value)
        }
    }
}

private fun CompanyCacheEntity.toDto(): CompanyDto = CompanyDto(
    id = id, name = name, legalName = legalName, currency = currency, timezone = timezone,
    country = country, gstin = gstin, pan = pan, gstRegistrationType = gstRegistrationType,
    isComposition = isComposition, eInvoicingEnabled = eInvoicingEnabled,
    fiscalYearStartMonth = fiscalYearStartMonth,
    googleSheetsWebhookUrl = null, upiVpa = upiVpa,
    paymentProvider = null, paymentKeyId = null, paymentSecretSet = false,
)

/** Overlays a queued-but-unsynced edit onto the last-known company row, so
 * the operator sees their own change reflected immediately rather than only
 * after it syncs. Fields this screen never edits (currency, payment
 * provider, etc.) pass through from [base] untouched. */
private fun LocalCompanyEditEntity.overlayOnto(base: CompanyDto?): CompanyDto {
    val fallback = base ?: CompanyDto(
        id = "", name = name, legalName = legalName, currency = "INR", timezone = timezone,
        country = null, gstin = gstin, pan = pan, gstRegistrationType = gstRegistrationType,
        isComposition = isComposition, eInvoicingEnabled = eInvoicingEnabled,
        fiscalYearStartMonth = 4, googleSheetsWebhookUrl = null, upiVpa = upiVpa,
        paymentProvider = null, paymentKeyId = null, paymentSecretSet = false,
    )
    return fallback.copy(
        name = name, legalName = legalName, timezone = timezone, gstin = gstin, pan = pan,
        gstRegistrationType = gstRegistrationType, isComposition = isComposition,
        eInvoicingEnabled = eInvoicingEnabled, upiVpa = upiVpa,
    )
}

private fun BranchCacheEntity.toDto(): BranchDto = BranchDto(
    id = id, name = name, code = code, address = address, timezone = timezone,
    opensAt = opensAt, closesAt = closesAt, stateCode = stateCode,
    fssaiLicenseNo = fssaiLicenseNo, tradeLicenseNo = tradeLicenseNo, branchGstin = branchGstin,
)

private fun LocalBranchEntity.toPendingRow() = PendingBranchRow(
    localId = localId, name = name,
    rejected = syncState == SettingsWriteState.REJECTED, error = lastError,
)

private fun TerminalCacheEntity.toDto(): TerminalDto = TerminalDto(
    id = id, branchId = branchId, name = name, deviceId = deviceId, lastSeenAt = lastSeenAt,
)

private fun LocalTerminalEntity.toPendingRow(branches: List<BranchDto>) = PendingTerminalRow(
    localId = localId,
    branchId = branchId,
    branchName = branches.firstOrNull { it.id == branchId }?.name ?: "Unknown branch",
    name = name,
    rejected = syncState == SettingsWriteState.REJECTED, error = lastError,
)

/**
 * The server's own words, never "HTTP 422".
 *
 * One gap is worth naming: the shared ErrorInterceptor can only decode the
 * backend's `{"error":{...}}` envelope, and FastAPI's *request validation*
 * failures (a bad timezone, a 14-character GSTIN) are returned in its own
 * `{"detail":[...]}` shape instead, which arrives here as the generic
 * "Request failed (HTTP 422)." That is why every field the backend validates
 * strictly is also validated in SettingsModels.kt with the backend's exact
 * wording — the operator sees "timezone must be a valid IANA name like
 * Asia/Kolkata" on the field, not a status code. If one still gets through,
 * the note below says which values the server is capable of rejecting rather
 * than pretending the request simply failed.
 */
private fun ApiException.readable(): String {
    val server = message?.takeIf { it.isNotBlank() } ?: "The request failed."
    return when {
        status == 422 && code == null ->
            "$server The server rejected one of these values — timezone (must be an " +
                "IANA name like Asia/Kolkata), GSTIN (15 characters), PAN (10 " +
                "characters), UPI ID (name@bank), FSSAI (14 digits) or opening hours " +
                "(HH:MM)."
        isAmbiguous ->
            "$server It is not known whether the change was applied. Tap save again — " +
                "the same request is replayed, so it cannot be applied twice."
        status == 403 ->
            "$server Company and branch settings need an owner or manager login."
        else -> server
    }
}

internal fun Throwable.settingsReadable(fallback: String): String =
    (this as? ApiException)?.readable()
        ?: fallback

/**
 * One key per logical attempt, reused across retries of that same attempt.
 *
 * The backend hashes method + path + body alongside the key, so replaying a key
 * with an edited payload is a 409, not a silent overwrite. That is why the
 * fingerprint is part of the record: retrying an unchanged save reuses the key
 * (the point of idempotency), while a save the operator has since edited gets a
 * fresh one. Keys are never minted inside the retry itself.
 */
private class IdempotencyKeys {

    private val issued = mutableMapOf<String, Pair<Int, String>>()

    fun keyFor(operation: String, payload: Any): String {
        val fingerprint = payload.hashCode()
        issued[operation]?.let { (previous, key) ->
            if (previous == fingerprint) return key
        }
        val fresh = UUID.randomUUID().toString()
        issued[operation] = fingerprint to fresh
        return fresh
    }

    /** Call once the write is known to have landed. */
    fun done(operation: String) {
        issued.remove(operation)
    }
}
