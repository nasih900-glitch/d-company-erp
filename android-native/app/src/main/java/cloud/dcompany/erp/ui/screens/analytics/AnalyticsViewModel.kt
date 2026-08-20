package cloud.dcompany.erp.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.reports.businessToday
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AnalyticsTab(val label: String) { Today("Today"), Growth("Growth") }

enum class GrowthPeriodOption(val query: String, val label: String) {
    WEEK("wow", "This week vs last"),
    MONTH("mom", "This month vs last"),
    YEAR("yoy", "This year vs last"),
}

data class AnalyticsUiState(
    val tab: AnalyticsTab = AnalyticsTab.Today,

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
)

/**
 * Room-backed via the shared ReportSnapshotEntity cache — same pattern as
 * Reports (see ReportsViewModel). Every read here is a pure aggregate with
 * nothing to queue or merge, so "offline" just means "show the last-fetched
 * snapshot with its own timestamp" rather than a write-outbox.
 */
class AnalyticsViewModel : ViewModel() {

    private val api = ApiClient.create<AnalyticsApi>()
    private val db = DCompanyApp.instance.db

    private val _state = MutableStateFlow(AnalyticsUiState())
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    private var todayJob: Job? = null
    private var growthJob: Job? = null
    private var topItemsJob: Job? = null

    init {
        loadToday()
        loadGrowth()
        loadTopItems()
    }

    fun selectTab(tab: AnalyticsTab) { _state.value = _state.value.copy(tab = tab) }

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

    private fun loadToday() {
        todayJob?.cancel()
        val today = businessToday()
        val key = "dashboard:$today"
        _state.value = _state.value.copy(todayLoading = true, todayError = null)
        todayJob = viewModelScope.launch {
            db.reportSnapshotDao().cached<DashboardKpis>(key)?.let { (cachedValue, fetchedAt) ->
                _state.value = _state.value.copy(dashboard = cachedValue, dashboardFetchedAtMillis = fetchedAt)
            }
            try {
                val dashboard = api.dashboard(today.toString())
                val now = System.currentTimeMillis()
                db.reportSnapshotDao().store(key, dashboard)
                _state.value = _state.value.copy(
                    todayLoading = false,
                    dashboard = dashboard,
                    dashboardFetchedAtMillis = now,
                    todayError = null,
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    todayLoading = false,
                    todayError = if (_state.value.dashboard == null) {
                        e.message ?: "Could not load today's numbers."
                    } else null,
                )
            }
        }
    }

    private fun loadGrowth() {
        growthJob?.cancel()
        val s = _state.value
        val today = businessToday()
        val key = "growth:${s.growthPeriod.query}:$today"
        // Cleared immediately, same reasoning as ReportsViewModel.load(): a
        // period switch must not go on showing the PREVIOUS period's
        // comparison under the newly-selected chip while this period's own
        // cache is read.
        _state.value = s.copy(growthLoading = true, growthError = null, growth = null, growthFetchedAtMillis = null)
        growthJob = viewModelScope.launch {
            db.reportSnapshotDao().cached<GrowthData>(key)?.let { (cachedValue, fetchedAt) ->
                _state.value = _state.value.copy(growth = cachedValue, growthFetchedAtMillis = fetchedAt)
            }
            try {
                val growth = api.growth(s.growthPeriod.query)
                val now = System.currentTimeMillis()
                db.reportSnapshotDao().store(key, growth)
                _state.value = _state.value.copy(
                    growthLoading = false, growth = growth, growthFetchedAtMillis = now, growthError = null,
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    growthLoading = false,
                    growthError = if (_state.value.growth == null) (e.message ?: "Could not load growth.") else null,
                )
            }
        }
    }

    /**
     * Independent of loadGrowth() on purpose (see selectGrowthPeriod) — a
     * failure fetching top items must never discard an already-successful
     * growth fetch, and vice versa, which sharing one try/catch used to do.
     */
    private fun loadTopItems() {
        topItemsJob?.cancel()
        val today = businessToday()
        val from = today.withDayOfMonth(1)
        val key = "top-items:$from:$today:10"
        topItemsJob = viewModelScope.launch {
            db.reportSnapshotDao().cached<List<TopItem>>(key)?.let { (cachedValue, _) ->
                _state.value = _state.value.copy(topItems = cachedValue)
            }
            try {
                val topItems = api.topItems(from.toString(), today.toString(), 10)
                db.reportSnapshotDao().store(key, topItems)
                _state.value = _state.value.copy(topItems = topItems)
            } catch (e: ApiException) {
                // No dedicated error slot for this list — it's a supplement
                // to the growth comparison, not something worth blocking the
                // whole tab's retry/error state over. A stale or empty list
                // degrades gracefully; growthError already covers the
                // primary comparison this tab exists to show.
            }
        }
    }
}
