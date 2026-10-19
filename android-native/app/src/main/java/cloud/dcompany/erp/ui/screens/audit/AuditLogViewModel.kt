package cloud.dcompany.erp.ui.screens.audit

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.ui.screens.finance.TenderCorrectionNotice
import cloud.dcompany.erp.ui.screens.finance.TenderCorrectionNotices
import cloud.dcompany.erp.ui.screens.finance.matchesOwnerKey
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.staff.StaffApi
import cloud.dcompany.erp.ui.screens.staff.StaffUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Complete tuple is sent with every cursor; no current-page-only filtering. */
data class AuditFilters(
    val view: String = "business",
    val category: String? = null,
    val code: String? = null,
    val outcome: String? = null,
    val actorUserId: String? = null,
    val targetUserId: String? = null,
    val reference: String = "",
    val dateFrom: String = "",
    val dateTo: String = "",
    val query: String = "",
)

data class AuditLogUiState(
    val ownerScopeKey: String? = null,
    val allowed: Boolean = false,
    val locked: Boolean = true,
    val unlocking: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null,
    val unlockError: String? = null,
    val filters: AuditFilters = AuditFilters(),
    val appliedFilters: AuditFilters = AuditFilters(),
    val catalogue: AuditCatalogue? = null,
    val staff: List<StaffUser> = emptyList(),
    val staffError: String? = null,
    val entries: List<AuditEntry> = emptyList(),
    val page: AuditEventsPage? = null,
    val selected: AuditEntry? = null,
    val newEntriesAvailable: Boolean = false,
    val snapshotExpired: Boolean = false,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val viewportReset: Long = 0,
)

/** Auth-scoped memory only. SessionViewModelScope also disposes this on logout. */
class AuditLogViewModel(
    private val api: AuditLogApi = ApiClient.create(),
    private val staffApi: StaffApi = ApiClient.create(),
    private val suppliedScope: CoroutineScope? = null,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val tasks: CoroutineScope get() = suppliedScope ?: viewModelScope
    private val _state = MutableStateFlow(AuditLogUiState())
    val state: StateFlow<AuditLogUiState> = _state.asStateFlow()
    private var identity: String? = null
    private var token: String? = null
    private var grantDeadline: Long? = null
    private var generation = 0L
    private var request: Job? = null
    private var expiry: Job? = null
    private var retained: AuditLogUiState? = null
    private var requestedReference: String? = null
    private var queuedCorrectionCheck = false

    init { tasks.launch { TenderCorrectionNotices.changes.collect { correctionRecorded(it) } } }

    internal fun correctionRecorded(notice: TenderCorrectionNotice) {
        if (!notice.matchesOwnerKey(identity) || !verificationIsCurrent()) return
        queuedCorrectionCheck = true
        drainCorrectionCheck()
    }

    private fun drainCorrectionCheck() {
        if (!queuedCorrectionCheck || busy() || !verificationIsCurrent() || _state.value.page?.activityCursor == null) return
        queuedCorrectionCheck = false
        checkActivity()
    }

    fun bindIdentity(userId: String?, companyId: String?, branchId: String?, allowed: Boolean) {
        val next = if (!userId.isNullOrBlank() && !companyId.isNullOrBlank() && allowed) "$companyId|$userId|${branchId.orEmpty()}" else null
        if (identity == next && _state.value.allowed == allowed) return
        invalidate()
        identity = next
        retained = null
        requestedReference = null
        _state.value = AuditLogUiState(ownerScopeKey = next, allowed = next != null)
    }

    fun unlock(password: String, expectedOwnerScopeKey: String? = identity) {
        val owner = identity ?: return
        if (owner != expectedOwnerScopeKey || !_state.value.allowed || !_state.value.locked || _state.value.unlocking || password.isBlank()) return
        val operation = ++generation
        val requestedAt = elapsedNow()
        request?.cancel()
        _state.value = _state.value.copy(unlocking = true, unlockError = null)
        request = tasks.launch {
            try {
                val grant = api.unlock(AuditUnlockRequest(password))
                if (!current(operation, owner)) return@launch
                token = grant.auditToken
                grantDeadline = requestedAt + grant.expiresIn.coerceIn(1, 600) * 1000L
                if (!grantIsCurrent()) {
                    lockWithMessage("Owner verification expired. Unlock to continue.", keepHistory = true)
                    return@launch
                }
                scheduleExpiry(grant.auditToken, owner)
                val metadata = coroutineScope {
                    val catalogue = async { api.catalogue(grant.auditToken) }
                    val staff = async {
                        try { staffApi.list() to null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { emptyList<StaffUser>() to "Current staff choices could not load. Retained event identities remain available." }
                    }
                    catalogue.await() to staff.await()
                }
                if (!current(operation, owner) || token != grant.auditToken) return@launch
                if (!grantIsCurrent()) {
                    lockWithMessage("Owner verification expired. Unlock to continue.", keepHistory = true)
                    return@launch
                }
                val linked = requestedReference
                val restored = retained ?: _state.value
                _state.value = restored.copy(
                    allowed = true, locked = false, unlocking = false, loading = false,
                    loadingMore = false, checking = false, error = null, unlockError = null,
                    catalogue = metadata.first, staff = metadata.second.first,
                    staffError = metadata.second.second,
                    filters = linked?.let { AuditFilters(reference = it) } ?: restored.filters,
                )
                if (linked != null) { requestedReference = null; find() }
                else if (_state.value.page == null) load(Load.FIRST) else checkActivity()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!current(operation, owner)) return@launch
                token = null
                grantDeadline = null
                expiry?.cancel()
                _state.value = _state.value.copy(locked = true, unlocking = false,
                    loading = false, loadingMore = false, checking = false,
                    unlockError = failure.message ?: "Could not verify the owner. Check the connection.")
            }
        }
    }

    fun lock() = lockWithMessage(null, keepHistory = true)

    private fun lockWithMessage(message: String?, keepHistory: Boolean) {
        val current = _state.value
        if (keepHistory && !current.locked) retained = current.copy(
            loading = false, loadingMore = false, checking = false, unlocking = false,
        ) else if (!keepHistory) retained = null
        invalidate()
        _state.value = AuditLogUiState(ownerScopeKey = identity, allowed = identity != null, unlockError = message,
            filters = current.filters, appliedFilters = current.appliedFilters)
    }

    fun changeFilters(update: (AuditFilters) -> AuditFilters) {
        if (!requireVerification() || busy()) return
        _state.value = _state.value.copy(filters = update(_state.value.filters))
    }

    fun find() {
        if (!requireVerification() || busy()) return
        val filters = _state.value.filters.let {
            it.copy(query = it.query.trim().take(200), reference = it.reference.trim().take(120),
                dateFrom = it.dateFrom.trim(), dateTo = it.dateTo.trim())
        }
        val changed = filters != _state.value.appliedFilters
        _state.value = _state.value.copy(filters = filters, appliedFilters = filters, selected = null,
            entries = if (changed) emptyList() else _state.value.entries,
            page = if (changed) null else _state.value.page,
            newEntriesAvailable = if (changed) false else _state.value.newEntriesAvailable,
            scrollIndex = 0, scrollOffset = 0, viewportReset = _state.value.viewportReset + 1,
            snapshotExpired = if (changed) false else _state.value.snapshotExpired)
        load(Load.FIRST)
    }

    fun reference(value: String) {
        requestedReference = value.take(120)
        applyQueuedReference()
    }

    private fun applyQueuedReference() {
        val reference = requestedReference ?: return
        if (_state.value.locked || busy()) return
        requestedReference = null
        _state.value = _state.value.copy(filters = AuditFilters(reference = reference))
        find()
    }

    fun refresh() {
        if (!requireVerification() || busy()) return
        _state.value = _state.value.copy(scrollIndex = 0, scrollOffset = 0, viewportReset = _state.value.viewportReset + 1)
        load(Load.FIRST)
    }
    fun loadMore() = load(Load.MORE)
    fun checkActivity() = load(Load.CHECK)
    fun rememberViewport(index: Int, offset: Int) {
        if (requireVerification()) _state.value = _state.value.copy(scrollIndex = index.coerceAtLeast(0), scrollOffset = offset.coerceAtLeast(0))
    }
    fun select(entry: AuditEntry) {
        if (requireVerification() && _state.value.entries.any { it == entry }) _state.value = _state.value.copy(selected = entry)
    }
    fun dismissDetails() { _state.value = _state.value.copy(selected = null) }

    private enum class Load { FIRST, MORE, CHECK }
    private fun busy() = _state.value.let { it.loading || it.loadingMore || it.unlocking || it.checking }

    private fun load(mode: Load) {
        val grant = token ?: return
        val owner = identity ?: return
        val before = _state.value
        if (!requireVerification() || busy()) return
        val cursor = when (mode) {
            Load.FIRST -> null
            Load.MORE -> before.page?.takeIf { it.hasMore }?.nextCursor ?: return
            Load.CHECK -> before.page?.activityCursor ?: return
        }
        val operation = ++generation
        val filters = before.appliedFilters
        _state.value = before.copy(loading = mode == Load.FIRST, loadingMore = mode == Load.MORE,
            checking = mode == Load.CHECK, error = if (mode == Load.CHECK) before.error else null)
        request = tasks.launch {
            try {
                val page = api.events(auditToken = grant, limit = 50, cursor = cursor,
                    view = filters.view, code = filters.code, category = filters.category,
                    outcome = filters.outcome, actorUserId = filters.actorUserId,
                    targetUserId = filters.targetUserId,
                    reference = filters.reference.takeIf(String::isNotBlank),
                    dateFrom = filters.dateFrom.takeIf(String::isNotBlank), dateTo = filters.dateTo.takeIf(String::isNotBlank),
                    query = filters.query.takeIf(String::isNotBlank))
                if (!current(operation, owner) || token != grant || !requireVerification()) return@launch
                val latest = _state.value
                _state.value = when (mode) {
                    Load.CHECK -> latest.copy(checking = false, newEntriesAvailable = page.newEntriesAvailable)
                    Load.FIRST -> latest.copy(loading = false, entries = page.entries, page = page,
                        selected = null, newEntriesAvailable = false, snapshotExpired = false)
                    Load.MORE -> latest.copy(loadingMore = false,
                        entries = (latest.entries + page.entries).distinctBy { it.id }, page = page,
                        newEntriesAvailable = latest.newEntriesAvailable || page.newEntriesAvailable)
                }
                applyQueuedReference()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!current(operation, owner) || token != grant || !requireVerification()) return@launch
                val apiFailure = failure as? ApiException
                val expiredSnapshot = apiFailure?.status in setOf(409, 422) &&
                    apiFailure?.message?.contains("Audit history snapshot expired", ignoreCase = true) == true
                when (apiFailure?.status) {
                    401 -> lockWithMessage("Owner verification expired. Unlock to continue.", keepHistory = true)
                    403 -> lockWithMessage("Audit access is no longer allowed.", keepHistory = false)
                    else -> _state.value = _state.value.copy(loading = false, loadingMore = false, checking = false,
                        snapshotExpired = _state.value.snapshotExpired || expiredSnapshot,
                        error = if (expiredSnapshot) "This history snapshot expired. Loaded rows are retained; refresh to start a new snapshot."
                            else failure.message ?: "History could not be checked. Loaded rows are unchanged.")
                }
            } finally {
                if (current(operation, owner)) drainCorrectionCheck()
            }
        }
    }

    /** Pure read-side check also hides protected content before a resume effect can run. */
    fun verificationIsCurrent(): Boolean = !_state.value.locked && grantIsCurrent()

    /** elapsedRealtime includes deep sleep; Handler/coroutine delays alone do not. */
    fun checkVerification() {
        if (token != null && !grantIsCurrent()) {
            lockWithMessage("Owner verification expired. Unlock to continue.", keepHistory = true)
        }
    }

    private fun grantIsCurrent(): Boolean = token != null && grantDeadline?.let { elapsedNow() < it } == true
    private fun requireVerification(): Boolean {
        checkVerification()
        return !_state.value.locked && grantIsCurrent()
    }

    private fun scheduleExpiry(grant: String, owner: String) {
        expiry?.cancel()
        expiry = tasks.launch {
            delay(((grantDeadline ?: elapsedNow()) - elapsedNow()).coerceAtLeast(0))
            if (identity == owner && token == grant) checkVerification()
        }
    }

    private fun current(operation: Long, owner: String) = operation == generation && identity == owner
    private fun invalidate() { queuedCorrectionCheck = false; generation++; request?.cancel(); expiry?.cancel(); token = null; grantDeadline = null }
    override fun onCleared() { invalidate(); retained = null; super.onCleared() }
}
