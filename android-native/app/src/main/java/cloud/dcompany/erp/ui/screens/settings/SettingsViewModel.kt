package cloud.dcompany.erp.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class SettingsTab(val label: String) {
    Account("Account"),
    Company("Company"),
    Branches("Branches"),
    Terminals("Terminals"),
}

data class SettingsUiState(
    val tab: SettingsTab = SettingsTab.Account,

    // -- account -----------------------------------------------------------
    val meLoading: Boolean = true,
    val meError: String? = null,
    val me: MeResponse? = null,
    val challenge: OtpChallengeDto? = null,
    val accountBusy: Boolean = false,
    val accountError: String? = null,
    val accountNotice: String? = null,

    // -- company -----------------------------------------------------------
    val companyLoading: Boolean = true,
    val companyError: String? = null,
    val company: CompanyDto? = null,
    val companyForm: CompanyForm = CompanyForm(),
    val companySaving: Boolean = false,
    val companyFormError: String? = null,
    val companyNotice: String? = null,

    // -- branches ----------------------------------------------------------
    val branchesLoading: Boolean = true,
    val branchesError: String? = null,
    val branches: List<BranchDto> = emptyList(),
    val branchForm: BranchForm? = null,
    val branchSaving: Boolean = false,
    val branchFormError: String? = null,
    val branchNotice: String? = null,

    // -- terminals ---------------------------------------------------------
    val terminalsLoading: Boolean = false,
    val terminalsError: String? = null,
    val terminals: List<TerminalDto> = emptyList(),
    val selectedBranchId: String? = null,
    val terminalBusy: Boolean = false,
    val terminalName: String = "",
    val terminalDeviceId: String = "",
    val terminalFormError: String? = null,
    val terminalNotice: String? = null,
) {
    val companyDirty: Boolean
        get() = company?.let { it.toForm() != companyForm } ?: false

    fun branchName(id: String?): String? = branches.firstOrNull { it.id == id }?.name
}

class SettingsViewModel : ViewModel() {

    private val api = ApiClient.create<SettingsApi>()
    private val keys = IdempotencyKeys()

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadMe()
        loadCompany()
        loadBranches()
    }

    fun selectTab(tab: SettingsTab) {
        _state.value = _state.value.copy(tab = tab)
        if (tab == SettingsTab.Terminals && _state.value.terminals.isEmpty()) {
            loadTerminals()
        }
    }

    // ================================================================ account

    fun loadMe() {
        _state.value = _state.value.copy(meLoading = true, meError = null)
        viewModelScope.launch {
            try {
                val me = ApiClient.api.me()
                _state.value = _state.value.copy(
                    meLoading = false,
                    me = me,
                    // A staff member scoped to one branch should land on that
                    // branch's tills, not on whichever happens to be first.
                    selectedBranchId = _state.value.selectedBranchId ?: me.branchId,
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(meLoading = false, meError = e.readable())
            }
        }
    }

    /**
     * Step one of the change: ask for a code. The destination is the business
     * security mailbox, which the response tells us (masked) so the screen can
     * say where to look instead of leaving staff guessing.
     */
    fun requestPasswordCode() {
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
                _state.value = _state.value.copy(accountBusy = false, challenge = challenge)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(accountBusy = false, accountError = e.readable())
            }
        }
    }

    fun confirmPasswordChange(code: String, newPassword: String, confirmPassword: String) {
        val challenge = _state.value.challenge ?: return
        // Checked here as well as by the server: a mismatch caught locally
        // costs nothing, whereas burning the one-time code on a typo means
        // asking the mailbox holder for another one.
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
                    accountBusy = false,
                    challenge = null,
                    accountNotice = result.message.ifBlank { "Password updated." },
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(accountBusy = false, accountError = e.readable())
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

    fun loadCompany() {
        _state.value = _state.value.copy(companyLoading = true, companyError = null)
        viewModelScope.launch {
            try {
                val company = api.company()
                _state.value = _state.value.copy(
                    companyLoading = false,
                    company = company,
                    companyForm = company.toForm(),
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    companyLoading = false,
                    companyError = e.readable(),
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

    fun saveCompany() {
        val form = _state.value.companyForm
        form.validate()?.let { message ->
            _state.value = _state.value.copy(companyFormError = message)
            return
        }
        val body = form.toBody()
        val key = keys.keyFor("company", body)
        _state.value = _state.value.copy(
            companySaving = true, companyFormError = null, companyNotice = null,
        )
        viewModelScope.launch {
            try {
                val updated = api.updateCompany(body, key)
                keys.done("company")
                _state.value = _state.value.copy(
                    companySaving = false,
                    company = updated,
                    companyForm = updated.toForm(),
                    companyNotice = "Company profile saved.",
                )
                clearNoticeLater { it.copy(companyNotice = null) }
            } catch (e: ApiException) {
                // The key is deliberately kept: tapping Save again replays the
                // same attempt rather than issuing a second write.
                _state.value = _state.value.copy(
                    companySaving = false,
                    companyFormError = e.readable(),
                )
            }
        }
    }

    // =============================================================== branches

    fun loadBranches() {
        _state.value = _state.value.copy(branchesLoading = true, branchesError = null)
        viewModelScope.launch {
            try {
                val branches = api.branches()
                _state.value = _state.value.copy(
                    branchesLoading = false,
                    branches = branches,
                    selectedBranchId = _state.value.selectedBranchId
                        ?.takeIf { id -> branches.any { it.id == id } }
                        ?: branches.firstOrNull()?.id,
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    branchesLoading = false,
                    branchesError = e.readable(),
                )
            }
        }
    }

    fun newBranch() {
        _state.value = _state.value.copy(
            branchForm = BranchForm(), branchFormError = null, branchNotice = null,
        )
    }

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
        val form = _state.value.branchForm ?: return
        form.validate()?.let { message ->
            _state.value = _state.value.copy(branchFormError = message)
            return
        }
        val body = form.toBody()
        val operation = "branch:${form.id ?: "new"}"
        val key = keys.keyFor(operation, body)
        _state.value = _state.value.copy(branchSaving = true, branchFormError = null)
        viewModelScope.launch {
            try {
                val saved = if (form.isNew) {
                    api.createBranch(body, key)
                } else {
                    api.updateBranch(form.id!!, body, key)
                }
                keys.done(operation)
                _state.value = _state.value.copy(
                    branchSaving = false,
                    branchForm = null,
                    branchNotice = if (form.isNew) {
                        "Branch \"${saved.name}\" created."
                    } else {
                        "Branch \"${saved.name}\" saved."
                    },
                )
                loadBranches()
                clearNoticeLater { it.copy(branchNotice = null) }
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    branchSaving = false, branchFormError = e.readable(),
                )
            }
        }
    }

    fun dismissBranchNotice() {
        _state.value = _state.value.copy(branchNotice = null)
    }

    // ============================================================== terminals

    fun selectBranch(id: String) {
        if (_state.value.selectedBranchId == id) return
        _state.value = _state.value.copy(selectedBranchId = id, terminals = emptyList())
        loadTerminals()
    }

    fun loadTerminals() {
        val branchId = _state.value.selectedBranchId
        if (branchId == null) {
            _state.value = _state.value.copy(terminalsLoading = false, terminals = emptyList())
            return
        }
        _state.value = _state.value.copy(terminalsLoading = true, terminalsError = null)
        viewModelScope.launch {
            try {
                val rows = api.terminals(branchId)
                _state.value = _state.value.copy(terminalsLoading = false, terminals = rows)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    terminalsLoading = false, terminalsError = e.readable(),
                )
            }
        }
    }

    fun setTerminalName(value: String) {
        _state.value = _state.value.copy(terminalName = value, terminalFormError = null)
    }

    fun setTerminalDeviceId(value: String) {
        _state.value = _state.value.copy(terminalDeviceId = value, terminalFormError = null)
    }

    fun addTerminal() {
        val branchId = _state.value.selectedBranchId ?: return
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
        val body = TerminalCreateBody(
            branchId = branchId,
            name = name,
            deviceId = _state.value.terminalDeviceId.trim().ifBlank { null },
        )
        val key = keys.keyFor("terminal:new", body)
        _state.value = _state.value.copy(terminalBusy = true, terminalFormError = null)
        viewModelScope.launch {
            try {
                val created = api.createTerminal(body, key)
                keys.done("terminal:new")
                _state.value = _state.value.copy(
                    terminalBusy = false,
                    terminalName = "",
                    terminalDeviceId = "",
                    terminalNotice = "Till \"${created.name}\" registered.",
                )
                loadTerminals()
                clearNoticeLater { it.copy(terminalNotice = null) }
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    terminalBusy = false, terminalFormError = e.readable(),
                )
            }
        }
    }

    fun deleteTerminal(terminal: TerminalDto) {
        val operation = "terminal:delete:${terminal.id}"
        val key = keys.keyFor(operation, terminal.id)
        _state.value = _state.value.copy(terminalBusy = true, terminalFormError = null)
        viewModelScope.launch {
            try {
                api.deleteTerminal(terminal.id, key)
                keys.done(operation)
                _state.value = _state.value.copy(
                    terminalBusy = false,
                    terminalNotice = "Till \"${terminal.name}\" removed.",
                )
                loadTerminals()
                clearNoticeLater { it.copy(terminalNotice = null) }
            } catch (e: ApiException) {
                // 409 here is the useful case: the backend refuses to delete a
                // till that shifts or orders point at, because that history is
                // what a GST audit reads.
                _state.value = _state.value.copy(
                    terminalBusy = false, terminalFormError = e.readable(),
                )
            }
        }
    }

    fun dismissTerminalFeedback() {
        _state.value = _state.value.copy(terminalFormError = null, terminalNotice = null)
    }

    // ================================================================ helpers

    private fun clearNoticeLater(block: (SettingsUiState) -> SettingsUiState) {
        viewModelScope.launch {
            delay(3_000)
            _state.value = block(_state.value)
        }
    }
}

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
