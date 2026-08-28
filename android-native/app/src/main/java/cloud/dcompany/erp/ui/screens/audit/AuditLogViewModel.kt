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
    val queryDraft: String = "",
    val appliedQuery: String? = null,
    val entityType: String? = null,
    val action: String? = null,
    val facets: AuditFacets = AuditFacets(),
    val facetsLoading: Boolean = false,
    val facetsError: String? = null,
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
    private var facetsJob: Job? = null

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
                    facetsLoading = true,
                )
                scheduleExpiry(response.expiresIn)
                loadFacets(response.auditToken)
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
        facetsJob?.cancel()
        auditToken = null
        _state.value = AuditLogUiState()
    }

    fun refresh() {
        val token = auditToken ?: return
        if (_state.value.facetsError != null) {
            _state.value = _state.value.copy(facetsLoading = true, facetsError = null)
            loadFacets(token)
        }
        loadFirstPage(clearEntries = false)
    }

    fun selectArea(area: String?) {
        if (_state.value.locked || area == _state.value.area) return
        _state.value = _state.value.copy(area = area, entries = emptyList(), endReached = false)
        loadFirstPage(clearEntries = true)
    }

    fun queryChanged(value: String) {
        _state.value = _state.value.copy(queryDraft = value.take(MAX_QUERY_LENGTH))
    }

    fun applyQuery() {
        if (_state.value.locked) return
        val normalized = _state.value.queryDraft.trim().takeIf(String::isNotEmpty)
        if (normalized == _state.value.appliedQuery) return
        _state.value = _state.value.copy(
            appliedQuery = normalized,
            entries = emptyList(),
            endReached = false,
        )
        loadFirstPage(clearEntries = true)
    }

    fun selectEntityType(value: String?) = selectFacet(entityType = value, action = _state.value.action)

    fun selectAction(value: String?) = selectFacet(entityType = _state.value.entityType, action = value)

    fun clearDetailedFilters() {
        if (_state.value.locked) return
        val current = _state.value
        if (current.queryDraft.isEmpty() && current.appliedQuery == null &&
            current.entityType == null && current.action == null
        ) return
        _state.value = current.copy(
            queryDraft = "",
            appliedQuery = null,
            entityType = null,
            action = null,
            entries = emptyList(),
            endReached = false,
        )
        loadFirstPage(clearEntries = true)
    }

    private fun selectFacet(entityType: String?, action: String?) {
        if (_state.value.locked ||
            (entityType == _state.value.entityType && action == _state.value.action)
        ) return
        _state.value = _state.value.copy(
            entityType = entityType,
            action = action,
            entries = emptyList(),
            endReached = false,
        )
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
                    entityType = snapshot.entityType,
                    action = snapshot.action,
                    query = snapshot.appliedQuery,
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
        val entityType = _state.value.entityType
        val action = _state.value.action
        val query = _state.value.appliedQuery
        loadJob = viewModelScope.launch {
            try {
                val rows = api.list(
                    auditToken = token,
                    limit = PAGE_SIZE,
                    area = area,
                    entityType = entityType,
                    action = action,
                    query = query,
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
        if (apiError?.status == 401 || apiError?.status == 403) {
            expireAuditAccess(
                if (apiError.status == 403) {
                    "Audit access is no longer permitted for this account."
                } else {
                    "Audit access expired. Re-enter your password to continue."
                },
            )
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

    private fun loadFacets(token: String) {
        facetsJob?.cancel()
        facetsJob = viewModelScope.launch {
            try {
                val facets = api.facets(token)
                if (token != auditToken) return@launch
                _state.value = _state.value.copy(
                    facets = facets,
                    facetsLoading = false,
                    facetsError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val apiError = error as? ApiException
                if (apiError?.status == 401 || apiError?.status == 403) {
                    expireAuditAccess(
                        if (apiError.status == 403) {
                            "Audit access is no longer permitted for this account."
                        } else {
                            "Audit access expired. Re-enter your password to continue."
                        },
                    )
                    return@launch
                }
                if (token == auditToken) {
                    _state.value = _state.value.copy(
                        facetsLoading = false,
                        facetsError = apiError?.message
                            ?: "Advanced filters could not load. Area and search filters still work.",
                    )
                }
            }
        }
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
        facetsJob?.cancel()
        auditToken = null
        _state.value = AuditLogUiState(unlockError = message)
    }

    override fun onCleared() {
        auditToken = null
        super.onCleared()
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_QUERY_LENGTH = 200
    }
}
