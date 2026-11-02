package cloud.dcompany.erp.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.BranchScopeMismatchException
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.auth.verifyBranchScopedPayload
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.reports.businessToday
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class AnalyticsTab(val label: String) { Today("Today"), Growth("Growth") }

enum class GrowthPeriodOption(val query: String, val label: String) {
    WEEK("wow", "This week vs last"),
    MONTH("mom", "This month vs last"),
    YEAR("yoy", "This year vs last"),
}

data class AnalyticsUiState(
    val tab: AnalyticsTab = AnalyticsTab.Today,
    val scopeLease: CacheScopeLease? = null,
    val canReadToday: Boolean = false,
    val canReadGrowth: Boolean = false,

    val todayLoading: Boolean = true,
    val todayError: String? = null,
    val dashboard: DashboardKpis? = null,
    val dashboardFetchedAtMillis: Long? = null,

    val growthPeriod: GrowthPeriodOption = GrowthPeriodOption.MONTH,
    val growthLoading: Boolean = true,
    val growthError: String? = null,
    val growth: GrowthData? = null,
    val growthFetchedAtMillis: Long? = null,
    val topItems: List<TopItem> = emptyList(),
    val topItemsLoading: Boolean = true,
    val topItemsError: String? = null,
    val topItemsFetchedAtMillis: Long? = null,
)

internal fun canReadAnalyticsDashboard(profile: MeResponse?): Boolean = profile?.takeIf {
    it.effectivePermissions != null
}?.let {
    val permissions = EffectivePermissions.from(it)
    permissions.has(ErpPermission.AnalyticsRead) && permissions.has(ErpPermission.FinanceRead)
} == true

internal fun visibleAnalyticsState(
    state: AnalyticsUiState, profile: MeResponse?, lease: CacheScopeLease?,
): AnalyticsUiState {
    val matches = profile != null && lease != null && state.scopeLease == lease && profile.userId == lease.scope.userId &&
        profile.companyId == lease.scope.companyId && profile.branchId == lease.scope.branchId
    val analytics = matches && profile?.let { EffectivePermissions.from(it).has(ErpPermission.AnalyticsRead) } == true
    if (!analytics) return AnalyticsUiState(todayLoading = false, growthLoading = false, topItemsLoading = false)
    return if (canReadAnalyticsDashboard(profile)) state else state.copy(
        tab = AnalyticsTab.Growth, canReadToday = false, dashboard = null, dashboardFetchedAtMillis = null,
    )
}

internal fun dashboardFailureState(state: AnalyticsUiState, error: Throwable): AnalyticsUiState =
    if (error is ApiException && error.status in setOf(401, 403)) {
        state.copy(dashboard = null, dashboardFetchedAtMillis = null)
    } else state

internal enum class CachedDataPresentation { INITIAL_LOADING, BLOCKING_ERROR, FRESH, STALE }

internal fun cachedDataPresentation(
    hasData: Boolean,
    loading: Boolean,
    error: String?,
): CachedDataPresentation = when {
    !hasData && error != null -> CachedDataPresentation.BLOCKING_ERROR
    !hasData -> CachedDataPresentation.INITIAL_LOADING
    error != null -> CachedDataPresentation.STALE
    else -> CachedDataPresentation.FRESH
}

internal enum class SupplementalListPresentation {
    INITIAL_LOADING,
    BLOCKING_ERROR,
    FRESH_EMPTY,
    STALE_EMPTY,
    FRESH_CONTENT,
    STALE_CONTENT,
}

internal fun supplementalListPresentation(
    hasSnapshot: Boolean,
    isEmpty: Boolean,
    error: String?,
): SupplementalListPresentation = when {
    !hasSnapshot && error != null -> SupplementalListPresentation.BLOCKING_ERROR
    !hasSnapshot -> SupplementalListPresentation.INITIAL_LOADING
    isEmpty && error != null -> SupplementalListPresentation.STALE_EMPTY
    isEmpty -> SupplementalListPresentation.FRESH_EMPTY
    error != null -> SupplementalListPresentation.STALE_CONTENT
    else -> SupplementalListPresentation.FRESH_CONTENT
}

internal fun analyticsLoadError(error: Throwable, fallback: String): String = when (error) {
    is BranchScopeMismatchException -> error.message
        ?: "Could not verify which branch these figures belong to."
    is ApiException -> error.message?.takeIf(String::isNotBlank) ?: fallback
    else -> "$fallback Check the connection and try again."
}

internal fun verifyTopItemBranches(expectedBranchId: String?, rows: List<TopItem>) {
    // An empty list has no row-level branch echo, but it still must not be
    // displayed outside a verified branch scope.
    verifyBranchScopedPayload(expectedBranchId, expectedBranchId, "item ranking")
    rows.forEach {
        verifyBranchScopedPayload(expectedBranchId, it.branchId, "item ranking")
    }
}

/**
 * Room-backed via the shared ReportSnapshotEntity cache — same pattern as
 * Reports (see ReportsViewModel). Every read here is a pure aggregate with
 * nothing to queue or merge, so "offline" just means "show the last-fetched
 * snapshot with its own timestamp" rather than a write-outbox.
 */
class AnalyticsViewModel : ViewModel() {

    private val api = ApiClient.create<AnalyticsApi>()
    private val db = DCompanyApp.instance.db
    private val cacheIsolation = DCompanyApp.instance.cacheIsolation

    private val _state = MutableStateFlow(AnalyticsUiState())
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    private var todayJob: Job? = null
    private var growthJob: Job? = null
    private var topItemsJob: Job? = null
    private var todaySerial = 0L
    private var growthSerial = 0L
    private var topItemsSerial = 0L

    private var boundLease: CacheScopeLease? = null
    private var deniedDashboardLease: CacheScopeLease? = null

    init {
        viewModelScope.launch {
            combine(DCompanyApp.instance.shiftCache.profile, cacheIsolation.activeLeaseFlow) { profile, lease ->
                profile to lease
            }.collect { (profile, lease) ->
                val matches = profile != null && lease != null && profile.userId == lease.scope.userId &&
                    profile.companyId == lease.scope.companyId && profile.branchId == lease.scope.branchId
                val todayAllowed = matches && canReadAnalyticsDashboard(profile)
                val growthAllowed = matches && profile?.let {
                    EffectivePermissions.from(it).has(ErpPermission.AnalyticsRead)
                } == true
                if (boundLease == lease && _state.value.canReadToday == todayAllowed &&
                    _state.value.canReadGrowth == growthAllowed) return@collect
                todaySerial++; growthSerial++; topItemsSerial++
                todayJob?.cancel(); growthJob?.cancel(); topItemsJob?.cancel()
                boundLease = lease
                _state.value = AnalyticsUiState(
                    tab = if (todayAllowed) AnalyticsTab.Today else AnalyticsTab.Growth,
                    scopeLease = lease, canReadToday = todayAllowed, canReadGrowth = growthAllowed,
                    todayLoading = false, growthLoading = false, topItemsLoading = false,
                )
                if (!todayAllowed && lease != null) {
                    deniedDashboardLease = lease
                    discardDashboardCache(lease)
                }
                if (todayAllowed) loadToday()
                if (growthAllowed) { loadGrowth(); loadTopItems() }
            }
        }
    }

    fun selectTab(tab: AnalyticsTab) {
        if (tab == AnalyticsTab.Today && !_state.value.canReadToday) return
        if (tab == AnalyticsTab.Growth && !_state.value.canReadGrowth) return
        _state.value = _state.value.copy(tab = tab)
    }

    fun retryToday() = loadToday()

    /**
     * Top items are independent of the growth period on purpose — "this
     * month's top sellers" doesn't change when the Week/Month/Year comparison
     * chip does, so switching that chip only reloads growth(), not a second,
     * identical top-items fetch every tap.
     */
    fun selectGrowthPeriod(option: GrowthPeriodOption) {
        if (_state.value.growthPeriod == option) return
        _state.value = _state.value.copy(growthPeriod = option)
        loadGrowth()
    }

    fun retryGrowth() {
        loadGrowth()
        loadTopItems()
    }

    fun retryTopItems() = loadTopItems()

    private suspend fun discardDashboardCache(lease: CacheScopeLease) {
        try {
            cacheIsolation.commitIfCurrent(lease) { db.reportSnapshotDao().deleteWithPrefix("dashboard:") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Memory remains empty and this lease cannot read cached financial data again.
        }
    }

    private fun loadToday() {
        if (!_state.value.canReadToday) return
        val lease = boundLease ?: return
        val requestId = ++todaySerial
        todayJob?.cancel()
        val today = businessToday()
        val key = "dashboard:v2:${lease.scope.branchId}:$today"
        fun current() = requestId == todaySerial && boundLease == lease &&
            cacheIsolation.currentLease() == lease && _state.value.canReadToday
        _state.value = _state.value.copy(todayLoading = true, todayError = null)
        todayJob = viewModelScope.launch {
            try {
                verifyBranchScopedPayload(lease.scope.branchId, lease.scope.branchId, "analytics dashboard")
                if (deniedDashboardLease != lease) {
                    db.reportSnapshotDao().cached<DashboardKpis>(key)?.let { (saved, fetchedAt) ->
                        if (current() && saved.branchId == lease.scope.branchId) {
                            _state.value = _state.value.copy(dashboard = saved, dashboardFetchedAtMillis = fetchedAt)
                        }
                    }
                }
                val dashboard = api.dashboard(today.toString())
                verifyBranchScopedPayload(lease.scope.branchId, dashboard.branchId, "analytics dashboard")
                if (!current()) return@launch
                cacheIsolation.commitIfCurrent(lease) {
                    if (current()) db.reportSnapshotDao().store(key, dashboard)
                }
                if (!current()) return@launch
                deniedDashboardLease = null
                _state.value = _state.value.copy(
                    dashboard = dashboard, dashboardFetchedAtMillis = System.currentTimeMillis(), todayError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current()) {
                    _state.value = dashboardFailureState(_state.value, error).copy(
                        todayError = analyticsLoadError(error, "Could not load today's numbers."),
                    )
                    if (error is ApiException && error.status in setOf(401, 403)) {
                        deniedDashboardLease = lease
                        discardDashboardCache(lease)
                    }
                }
            } finally {
                if (current()) _state.value = _state.value.copy(todayLoading = false)
            }
        }
    }

    private fun loadGrowth() {
        if (!_state.value.canReadGrowth) return
        val requestId = ++growthSerial
        growthJob?.cancel()
        val s = _state.value
        val today = businessToday()
        val key = "growth:${s.growthPeriod.query}:$today"
        val expectedBranchId = cacheIsolation.currentLease()?.scope?.branchId
        // Cleared immediately, same reasoning as ReportsViewModel.load(): a
        // period switch must not go on showing the PREVIOUS period's
        // comparison under the newly-selected chip while this period's own
        // cache is read.
        _state.value = s.copy(growthLoading = true, growthError = null, growth = null, growthFetchedAtMillis = null)
        growthJob = viewModelScope.launch {
            try {
                verifyBranchScopedPayload(
                    expectedBranchId,
                    expectedBranchId,
                    "growth comparison",
                )
                db.reportSnapshotDao().cached<GrowthData>(key)?.let { (cachedValue, fetchedAt) ->
                    val belongsToActiveBranch = runCatching {
                        verifyBranchScopedPayload(
                            expectedBranchId,
                            cachedValue.branchId,
                            "saved growth comparison",
                        )
                    }.isSuccess
                    if (belongsToActiveBranch && requestId == growthSerial) {
                        _state.value = _state.value.copy(
                            growth = cachedValue,
                            growthFetchedAtMillis = fetchedAt,
                        )
                    }
                }
                lateinit var growth: GrowthData
                val committed = cacheIsolation.fetchAndCommitScoped(
                    fetch = {
                        api.growth(s.growthPeriod.query).also {
                            verifyBranchScopedPayload(
                                expectedBranchId,
                                it.branchId,
                                "growth comparison",
                            )
                        }
                    },
                    store = {
                        growth = it
                        db.reportSnapshotDao().store(key, it)
                    },
                )
                if (!committed || requestId != growthSerial) {
                    return@launch
                }
                val now = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    growth = growth, growthFetchedAtMillis = now, growthError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestId == growthSerial) {
                    _state.value = _state.value.copy(
                        growthError = analyticsLoadError(error, "Could not load growth."),
                    )
                }
            } finally {
                if (requestId == growthSerial) {
                    _state.value = _state.value.copy(growthLoading = false)
                }
            }
        }
    }

    /**
     * Independent of loadGrowth() on purpose (see selectGrowthPeriod) — a
     * failure fetching top items must never discard an already-successful
     * growth fetch, and vice versa, which sharing one try/catch used to do.
     */
    private fun loadTopItems() {
        if (!_state.value.canReadGrowth) return
        val requestId = ++topItemsSerial
        topItemsJob?.cancel()
        val today = businessToday()
        val from = today.withDayOfMonth(1)
        val key = "top-items:$from:$today:10"
        val expectedBranchId = cacheIsolation.currentLease()?.scope?.branchId
        _state.value = _state.value.copy(topItemsLoading = true, topItemsError = null)
        topItemsJob = viewModelScope.launch {
            try {
                verifyTopItemBranches(expectedBranchId, emptyList())
                db.reportSnapshotDao().cached<List<TopItem>>(key)?.let { (cachedValue, fetchedAt) ->
                    val belongsToActiveBranch = runCatching {
                        verifyTopItemBranches(expectedBranchId, cachedValue)
                    }.isSuccess
                    if (belongsToActiveBranch && requestId == topItemsSerial) {
                        _state.value = _state.value.copy(
                            topItems = cachedValue,
                            topItemsFetchedAtMillis = fetchedAt,
                        )
                    }
                }
                lateinit var topItems: List<TopItem>
                val committed = cacheIsolation.fetchAndCommitScoped(
                    fetch = {
                        api.topItems(from.toString(), today.toString(), 10).also {
                            verifyTopItemBranches(expectedBranchId, it)
                        }
                    },
                    store = {
                        topItems = it
                        db.reportSnapshotDao().store(key, it)
                    },
                )
                if (!committed || requestId != topItemsSerial) {
                    return@launch
                }
                _state.value = _state.value.copy(
                    topItems = topItems,
                    topItemsFetchedAtMillis = System.currentTimeMillis(),
                    topItemsError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestId == topItemsSerial) {
                    _state.value = _state.value.copy(
                        topItemsError = analyticsLoadError(error, "Could not load top items."),
                    )
                }
            } finally {
                if (requestId == topItemsSerial) {
                    _state.value = _state.value.copy(topItemsLoading = false)
                }
            }
        }
    }
}
