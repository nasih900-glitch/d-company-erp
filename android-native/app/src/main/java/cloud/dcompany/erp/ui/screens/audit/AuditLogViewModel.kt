package cloud.dcompany.erp.ui.screens.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuditArea(val value: String?, val label: String)

val AUDIT_AREAS = listOf(
    AuditArea(null, "All"),
    AuditArea("login", "Login"),
    AuditArea("pos", "POS & payments"),
    AuditArea("operations", "Operations"),
    AuditArea("finance", "Finance"),
    AuditArea("staff", "Staff"),
    AuditArea("inventory", "Inventory"),
    AuditArea("system", "System"),
)

data class AuditLogUiState(
    val locked: Boolean = true,
    val unlocking: Boolean = false,
    val unlockError: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val entries: List<AuditEntry> = emptyList(),
    val area: String? = null,
    val endReached: Boolean = false,
    val selected: AuditEntry? = null,
)

/**
 * Protected-owner audit reader. The short-lived unlock token is deliberately
 * memory-only and belongs to this authenticated ViewModel store, which is
 * destroyed on logout/account switch by SessionViewModelScope.
 */
class AuditLogViewModel(
    private val api: AuditLogApi = ApiClient.create<AuditLogApi>(),
) : ViewModel() {

    private val _state = MutableStateFlow(AuditLogUiState())
    val state: StateFlow<AuditLogUiState> = _state.asStateFlow()

    private var auditToken: String? = null
    private var expiryJob: Job? = null
    private var loadJob: Job? = null

    fun unlock(password: String) {
        if (_state.value.unlocking) return
        if (password.isBlank()) {
            _state.value = _state.value.copy(
                unlockError = "Enter your account password to continue.",
            )
            return
        }
        _state.value = _state.value.copy(unlocking = true, unlockError = null)
        viewModelScope.launch {
            try {
                val response = api.unlock(AuditUnlockRequest(password))
                auditToken = response.auditToken
                _state.value = _state.value.copy(
                    locked = false,
                    unlocking = false,
                    unlockError = null,
                    entries = emptyList(),
                    error = null,
                    endReached = false,
                )
                scheduleExpiry(response.expiresIn)
                loadFirstPage(clearEntries = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    unlocking = false,
                    unlockError = (e as? ApiException)?.message
                        ?: "Could not unlock the Audit Log. Check the connection and try again.",
                )
            }
        }
    }

    fun lock() {
        expiryJob?.cancel()
        loadJob?.cancel()
        auditToken = null
        _state.value = AuditLogUiState()
    }

    fun refresh() = loadFirstPage(clearEntries = false)

    fun selectArea(area: String?) {
        if (_state.value.locked || area == _state.value.area) return
        _state.value = _state.value.copy(area = area, entries = emptyList(), endReached = false)
        loadFirstPage(clearEntries = true)
    }

    fun loadMore() {
        val snapshot = _state.value
        val token = auditToken ?: return
        if (snapshot.loading || snapshot.loadingMore || snapshot.endReached) return
        val beforeId = snapshot.entries.lastOrNull()?.id ?: return
        _state.value = snapshot.copy(loadingMore = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val rows = api.list(
                    auditToken = token,
                    limit = PAGE_SIZE,
                    beforeId = beforeId,
                    area = snapshot.area,
                )
                if (token != auditToken) return@launch
                _state.value = _state.value.copy(
                    loadingMore = false,
                    entries = _state.value.entries + rows,
                    endReached = rows.size < PAGE_SIZE,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleLoadFailure(e, loadingMore = true)
            }
        }
    }

    fun select(entry: AuditEntry) {
        _state.value = _state.value.copy(selected = entry)
    }

    fun dismissDetails() {
        _state.value = _state.value.copy(selected = null)
    }

    private fun loadFirstPage(clearEntries: Boolean) {
        val token = auditToken ?: return
        loadJob?.cancel()
        val snapshot = _state.value
        _state.value = snapshot.copy(
            loading = true,
            loadingMore = false,
            error = null,
            entries = if (clearEntries) emptyList() else snapshot.entries,
            endReached = false,
        )
        val area = _state.value.area
        loadJob = viewModelScope.launch {
            try {
                val rows = api.list(
                    auditToken = token,
                    limit = PAGE_SIZE,
                    area = area,
                )
                if (token != auditToken) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    entries = rows,
                    endReached = rows.size < PAGE_SIZE,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleLoadFailure(e, loadingMore = false)
            }
        }
    }

    private fun handleLoadFailure(error: Exception, loadingMore: Boolean) {
        val apiError = error as? ApiException
        if (apiError?.status == 401) {
            expireAuditAccess("Audit access expired. Re-enter your password to continue.")
            return
        }
        _state.value = _state.value.copy(
            loading = false,
            loadingMore = false,
            error = apiError?.message ?: if (loadingMore) {
                "Could not load older audit entries. Try again."
            } else {
                "Could not load the Audit Log. Check the connection and try again."
            },
        )
    }

    private fun scheduleExpiry(expiresInSeconds: Int) {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            delay(expiresInSeconds.coerceAtLeast(1) * 1_000L)
            expireAuditAccess("Audit access expired. Re-enter your password to continue.")
        }
    }

    private fun expireAuditAccess(message: String) {
        loadJob?.cancel()
        expiryJob?.cancel()
        auditToken = null
        _state.value = AuditLogUiState(unlockError = message)
    }

    override fun onCleared() {
        auditToken = null
        super.onCleared()
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
