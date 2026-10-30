package cloud.dcompany.erp.ui.screens.reports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.BranchScopeMismatchException
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.ReportsAccess
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CostingCoverage
import cloud.dcompany.erp.core.net.ManualBackendOperation
import cloud.dcompany.erp.core.net.ManualBackendRequestAdmission
import cloud.dcompany.erp.ui.screens.finance.TenderCorrectionNotices
import cloud.dcompany.erp.ui.screens.finance.matchesOwnerKey
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val LEGACY_PNL_BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

/** Existing P&L fiscal controls retain their established India-local behavior. */
internal fun businessToday(): LocalDate = LocalDate.now(LEGACY_PNL_BUSINESS_ZONE)

internal fun fiscalYearFor(date: LocalDate): String {
    val startYear = if (date.monthValue >= 4) date.year else date.year - 1
    return fiscalYearOf(startYear)
}

internal fun fiscalYearOf(startYear: Int): String =
    "$startYear-${((startYear + 1) % 100).toString().padStart(2, '0')}"

internal fun fiscalQuarterFor(date: LocalDate): Int = ((date.monthValue - 4 + 12) % 12) / 3 + 1

internal fun fiscalStartYear(fy: String): Int =
    fy.take(4).toIntOrNull() ?: fiscalYearFor(businessToday()).take(4).toInt()

internal fun fiscalQuarterOrdinal(fiscalYear: String, quarter: Int): Long =
    fiscalStartYear(fiscalYear).toLong() * 4L + (quarter.coerceIn(1, 4) - 1)

internal fun shiftFiscalQuarter(fiscalYear: String, quarter: Int, delta: Int): Pair<String, Int> {
    val shifted = fiscalQuarterOrdinal(fiscalYear, quarter) + delta
    val startYear = Math.floorDiv(shifted, 4L).toInt()
    val shiftedQuarter = Math.floorMod(shifted, 4L).toInt() + 1
    return fiscalYearOf(startYear) to shiftedQuarter
}

internal fun canSelectFiscalQuarter(
    fiscalYear: String,
    quarter: Int,
    today: LocalDate = businessToday(),
): Boolean = fiscalQuarterOrdinal(fiscalYear, quarter) <=
    fiscalQuarterOrdinal(fiscalYearFor(today), fiscalQuarterFor(today))

internal enum class ReportsSurface { OVERVIEW, PNL }

internal data class ReportsUiState(
    val access: ReportsAccess = ReportsAccess(),
    val lease: CacheScopeLease? = null,
    val surface: ReportsSurface = ReportsSurface.OVERVIEW,
    val operationalTab: ReportsOperationalTab = ReportsOperationalTab.OVERVIEW,
    val inventory: ReportsInventorySnapshot? = null,
    val inventoryLoading: Boolean = false,
    val inventoryError: String? = null,
    val inventoryFetchedAtMillis: Long? = null,
    val period: ReportPeriod = ReportPeriod.DAILY,
    val onDate: LocalDate,
    val month: YearMonth,
    val fiscalYear: String,
    val quarter: Int,
    val overviewLoading: Boolean = false,
    val overviewError: String? = null,
    val overview: ValidatedReportsOverview? = null,
    val overviewFetchedAtMillis: Long? = null,
    val overviewTimezone: String? = null,
    val overviewUsesServerDefaultDate: Boolean = true,
    val overviewStale: Boolean = false,
    val overviewDenied: Boolean = false,
    val overviewExportBusy: Boolean = false,
    val overviewExportMessage: String? = null,
    val overviewExportFailed: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val report: ReportData? = null,
    val costingCoverage: CostingCoverage? = null,
    val costingLoading: Boolean = false,
    val costingError: String? = null,
    val fetchedAtMillis: Long? = null,
    val pnlStale: Boolean = false,
    val pnlDenied: Boolean = false,
) {
    val selection: ReportSelection
        get() = ReportSelection(period, onDate, month, fiscalYear, quarter)

    val canStepForward: Boolean
        get() {
            val today = reportsSelectionToday(this) ?: return false
            return when (period) {
                ReportPeriod.DAILY -> onDate.isBefore(today)
                ReportPeriod.MONTHLY -> month.isBefore(YearMonth.from(today))
                ReportPeriod.QUARTERLY -> fiscalQuarterOrdinal(fiscalYear, quarter) <
                    fiscalQuarterOrdinal(fiscalYearFor(today), fiscalQuarterFor(today))
                ReportPeriod.YEARLY -> fiscalStartYear(fiscalYear) < fiscalStartYear(fiscalYearFor(today))
            }
        }
}

internal enum class ReportPresentation {
    INITIAL_LOADING, BLOCKING_ERROR, FRESH_EMPTY, STALE_EMPTY, FRESH_CONTENT, STALE_CONTENT,
}

internal fun reportPresentation(state: ReportsUiState): ReportPresentation = when {
    state.report == null && state.error != null -> ReportPresentation.BLOCKING_ERROR
    state.report == null -> ReportPresentation.INITIAL_LOADING
    state.report.hasNothing && state.error != null -> ReportPresentation.STALE_EMPTY
    state.report.hasNothing -> ReportPresentation.FRESH_EMPTY
    state.error != null -> ReportPresentation.STALE_CONTENT
    else -> ReportPresentation.FRESH_CONTENT
}

internal fun reportLoadError(error: Throwable): String = when (error) {
    is BranchScopeMismatchException -> error.message ?: "Could not verify which branch this report belongs to."
    is ReportsContractException -> error.message ?: "The report response could not be verified."
    is ApiException -> error.message?.takeIf(String::isNotBlank) ?: "Could not load the report."
    else -> "Could not refresh the report. Check the connection and try again."
}

internal class ReportsViewModel : ViewModel() {
    private val app = DCompanyApp.instance
    private val cacheIsolation = app.cacheIsolation
    private val repository = ReportsRepository(
        api = ApiClient.create<ReportsApi>(),
        cache = RoomReportsCacheStore(app.db.reportSnapshotDao()),
    )
    private val inventoryApi = ApiClient.create<ReportsInventoryApi>()
    private val exportApi = ApiClient.create<ReportsOverviewExportApi>()
    private val executor = ReportsLoadExecutor(cacheIsolation)
    private val manualAdmission = ManualBackendRequestAdmission(online = { app.connectivity.online.value })
    private val _state = MutableStateFlow(initialReportsState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()
    val activeLease: StateFlow<CacheScopeLease?> = cacheIsolation.activeLeaseFlow

    private var observedLease: CacheScopeLease? = null
    private var overviewExportJob: Job? = null
    @Volatile private var pendingOverviewExport: ReportsOverviewExportRequest? = null
    private var inventoryJob: Job? = null
    private var inventorySerial = 0L
    private var overviewJob: Job? = null
    private var pnlJob: Job? = null
    private var overviewSerial = 0L
    private var pnlSerial = 0L
    private val overviewCacheAdmission = mutableSetOf<Pair<CacheScopeLease, String>>()
    private val pnlCacheAdmission = mutableSetOf<Pair<CacheScopeLease, String>>()

    init {
        viewModelScope.launch {
            combine(cacheIsolation.activeLeaseFlow, app.connectivity.online) { lease, online -> lease to online }
                .distinctUntilChanged()
                .collect { (lease, online) ->
                    val changed = lease != observedLease
                    observedLease = lease
                    if (changed) {
                        invalidateAllAttempts()
                        overviewCacheAdmission.removeAll { it.first != lease }
                        pnlCacheAdmission.removeAll { it.first != lease }
                        _state.value = _state.value.copy(
                            lease = lease,
                            inventory = null, inventoryLoading = false, inventoryError = null, inventoryFetchedAtMillis = null,
                            overviewLoading = false,
                            overviewError = null,
                            overview = null,
                            overviewFetchedAtMillis = null,
                            overviewTimezone = null,
                            overviewUsesServerDefaultDate = true,
                            overviewStale = false,
                            overviewDenied = false,
                            loading = false,
                            error = null,
                            report = null,
                            costingCoverage = null,
                            costingLoading = false,
                            costingError = null,
                            fetchedAtMillis = null,
                            pnlStale = false,
                            pnlDenied = false,
                        )
                    }
                    if (lease != null && _state.value.access.canReadAny) loadSelected(lease, online)
                }
        }
        viewModelScope.launch {
            TenderCorrectionNotices.changes.collect { notice ->
                val profile = app.shiftCache.profile.value
                val ownerKey = profile?.let { "${it.companyId}|${it.userId}|${it.branchId.orEmpty()}" }
                if (
                    "reports" in notice.resources && notice.matchesOwnerKey(ownerKey) &&
                    _state.value.access.canReadAny
                ) invalidateForTenderCorrection()
            }
        }
    }

    fun updateAccess(access: ReportsAccess) {
        val previous = _state.value.access
        if (previous == access) return
        if (!access.canReadOverview || !access.canExportOverview) cancelOverviewExport()
        val lostOverview = previous.canReadOverview && !access.canReadOverview
        val lostPnl = previous.canReadPnl && !access.canReadPnl
        val lostInventory = !access.canReadOverview || !access.canReadInventory
        if (lostInventory) { inventorySerial += 1; inventoryJob?.cancel() }
        if (lostOverview) {
            overviewSerial += 1
            overviewJob?.cancel()
            overviewCacheAdmission.clear()
        }
        if (lostPnl) {
            pnlSerial += 1
            pnlJob?.cancel()
            pnlCacheAdmission.clear()
        }
        val surface = when {
            _state.value.surface == ReportsSurface.OVERVIEW && access.canReadOverview -> ReportsSurface.OVERVIEW
            _state.value.surface == ReportsSurface.PNL && access.canReadPnl -> ReportsSurface.PNL
            access.canReadOverview -> ReportsSurface.OVERVIEW
            else -> ReportsSurface.PNL
        }
        _state.value = _state.value.copy(
            access = access,
            surface = surface,
            operationalTab = if (lostInventory && _state.value.operationalTab == ReportsOperationalTab.INVENTORY) ReportsOperationalTab.OVERVIEW else _state.value.operationalTab,
            inventory = if (lostInventory) null else _state.value.inventory,
            inventoryLoading = !lostInventory && _state.value.inventoryLoading,
            inventoryError = if (lostInventory) null else _state.value.inventoryError,
            inventoryFetchedAtMillis = if (lostInventory) null else _state.value.inventoryFetchedAtMillis,
            overviewLoading = if (lostOverview) false else _state.value.overviewLoading,
            overviewError = if (lostOverview) null else _state.value.overviewError,
            overview = if (lostOverview) null else _state.value.overview,
            overviewFetchedAtMillis = if (lostOverview) null else _state.value.overviewFetchedAtMillis,
            overviewTimezone = if (lostOverview) null else _state.value.overviewTimezone,
            overviewUsesServerDefaultDate = if (lostOverview) true else _state.value.overviewUsesServerDefaultDate,
            overviewStale = if (lostOverview) false else _state.value.overviewStale,
            overviewDenied = if (lostOverview) false else _state.value.overviewDenied,
            loading = if (lostPnl) false else _state.value.loading,
            error = if (lostPnl) null else _state.value.error,
            report = if (lostPnl) null else _state.value.report,
            costingCoverage = if (lostPnl) null else _state.value.costingCoverage,
            costingLoading = if (lostPnl) false else _state.value.costingLoading,
            costingError = if (lostPnl) null else _state.value.costingError,
            fetchedAtMillis = if (lostPnl) null else _state.value.fetchedAtMillis,
            pnlStale = if (lostPnl) false else _state.value.pnlStale,
            pnlDenied = if (lostPnl) false else _state.value.pnlDenied,
        )
        val lease = cacheIsolation.currentLease()
        if (lease != null && (lostOverview || lostPnl)) {
            viewModelScope.launch {
                cacheIsolation.commitIfCurrent(lease) {
                    if (!_state.value.access.canReadOverview && lostOverview) repository.deleteOverviewScope(lease)
                    if (!_state.value.access.canReadPnl && lostPnl) repository.deletePnlScope(lease)
                }
            }
        }
        if (lease != null && access.canReadAny) loadSelected(lease, app.connectivity.online.value)
    }

    fun beginOverviewExport(access: ReportsAccess): ReportsOverviewExportRequest? {
        updateAccess(access)
        val request = reportsExportRequest(_state.value, cacheIsolation.currentLease()) ?: return null
        pendingOverviewExport = request
        _state.value = _state.value.copy(
            overviewExportBusy = true,
            overviewExportMessage = "Choose where to save the Overview CSV.",
            overviewExportFailed = false,
        )
        return request
    }

    fun cancelOverviewExport(message: String? = null) {
        pendingOverviewExport = null
        overviewExportJob?.cancel()
        overviewExportJob = null
        _state.value = _state.value.copy(
            overviewExportBusy = false,
            overviewExportMessage = message,
            overviewExportFailed = message != null,
        )
    }

    fun saveOverviewExport(requestId: String, uri: Uri?, access: ReportsAccess) {
        updateAccess(access)
        val request = pendingOverviewExport?.takeIf { it.id == requestId } ?: return
        fun current() = reportsExportIsCurrent(
            request, pendingOverviewExport?.id, _state.value, cacheIsolation.currentLease(),
        )
        if (uri == null || !current()) {
            cancelOverviewExport()
            return
        }
        if (uri.scheme != "content") {
            cancelOverviewExport("Choose a document destination to save the CSV.")
            return
        }
        // A repeated activity result must not start a second request or document write.
        if (overviewExportJob?.isActive == true) return
        _state.value = _state.value.copy(overviewExportMessage = "Preparing current server data…")
        overviewExportJob = viewModelScope.launch {
            val result = exportReportsOverviewCsv(
                isCurrent = ::current,
                fetch = {
                    manualAdmission.execute(
                        operation = ManualBackendOperation.MANUAL_READ,
                        normal = {
                            check(current()) { "Export scope changed" }
                            fetchReportsOverviewCsv(exportApi, request)
                        },
                        direct = {
                            check(current()) { "Export scope changed" }
                            fetchReportsOverviewCsv(
                                ApiClient.createManualDirectApi(ReportsOverviewExportApi::class.java), request,
                            )
                        },
                    )
                },
                authorizeWrite = {
                    var authorized = false
                    cacheIsolation.commitIfCurrent(request.lease) { authorized = current() }
                    authorized
                },
                openOutput = { app.contentResolver.openOutputStream(uri, "wt") },
            )
            if (pendingOverviewExport?.id != request.id) return@launch
            val mayPublish = current()
            pendingOverviewExport = null
            if (!mayPublish || result == ReportsCsvExportResult.Stale) {
                _state.value = _state.value.copy(
                    overviewExportBusy = false, overviewExportMessage = null, overviewExportFailed = false,
                )
                return@launch
            }
            _state.value = _state.value.copy(
                overviewExportBusy = false,
                overviewExportFailed = result != ReportsCsvExportResult.Saved,
                overviewExportMessage = when (result) {
                    ReportsCsvExportResult.Saved -> "Saved current server Overview for ${request.periodStart} to ${request.periodEnd}."
                    ReportsCsvExportResult.Denied -> "CSV export access is unavailable. Overview access is being checked again."
                    is ReportsCsvExportResult.Failed -> result.message
                    ReportsCsvExportResult.Stale -> null
                },
            )
            // Export denial can mean only analytics.export was revoked. Reauthorize the read
            // independently; its existing serial/cache guards handle any actual read denial.
            if (result == ReportsCsvExportResult.Denied) {
                loadOverview(request.lease, app.connectivity.online.value, explicitRefresh = true)
            }
        }
    }

    fun selectOperationalTab(tab: ReportsOperationalTab) {
        val before = _state.value
        val next = reportsSelectOperationalTab(before, tab)
        if (next == before) return
        cancelOverviewExport()
        inventorySerial += 1
        inventoryJob?.cancel()
        if (before.surface == ReportsSurface.PNL) { pnlSerial += 1; pnlJob?.cancel() }
        if (tab == ReportsOperationalTab.INVENTORY) { overviewSerial += 1; overviewJob?.cancel() }
        _state.value = next.copy(
            inventoryLoading = false, loading = false, costingLoading = false,
            overviewLoading = if (tab == ReportsOperationalTab.INVENTORY) false else next.overviewLoading,
            overviewExportBusy = false, overviewExportMessage = null, overviewExportFailed = false,
        )
        cacheIsolation.currentLease()?.let { lease ->
            when (reportsOperationalLoadNeeded(_state.value, previous = before)) {
                ReportsOperationalLoad.OVERVIEW -> loadOverview(lease, app.connectivity.online.value)
                ReportsOperationalLoad.INVENTORY -> loadInventory(lease, app.connectivity.online.value)
                ReportsOperationalLoad.NONE -> Unit
            }
        }
    }

    fun selectSurface(surface: ReportsSurface) {
        if (surface == ReportsSurface.OVERVIEW) { selectOperationalTab(ReportsOperationalTab.OVERVIEW); return }
        val allowed = when (surface) {
            ReportsSurface.OVERVIEW -> _state.value.access.canReadOverview
            ReportsSurface.PNL -> _state.value.access.canReadPnl
        }
        if (!allowed || _state.value.surface == surface) return
        inventorySerial += 1
        inventoryJob?.cancel()
        cancelOverviewExport()
        _state.value = when (_state.value.surface) {
            ReportsSurface.OVERVIEW -> {
                overviewSerial += 1
                overviewJob?.cancel()
                _state.value.copy(surface = surface, overviewLoading = false, inventoryLoading = false)
            }
            ReportsSurface.PNL -> {
                pnlSerial += 1
                pnlJob?.cancel()
                _state.value.copy(surface = surface, loading = false, costingLoading = false)
            }
        }
        cacheIsolation.currentLease()?.let { loadSelected(it, app.connectivity.online.value) }
    }

    fun selectPeriod(period: ReportPeriod) {
        if (_state.value.period == period) return
        _state.value = clearReportsPayloads(
            _state.value.copy(period = period, overviewUsesServerDefaultDate = false),
        )
        reloadSelection()
    }

    fun setDate(date: LocalDate) {
        val today = reportsSelectionToday(_state.value)
        val clamped = if (today != null && date.isAfter(today)) today else date
        if (_state.value.onDate == clamped) return
        _state.value = clearReportsPayloads(
            _state.value.copy(onDate = clamped, overviewUsesServerDefaultDate = false),
        )
        reloadSelection()
    }

    fun setMonth(month: YearMonth) {
        val current = reportsSelectionToday(_state.value)?.let { YearMonth.from(it) }
        val clamped = if (current != null && month.isAfter(current)) current else month
        if (_state.value.month == clamped) return
        _state.value = clearReportsPayloads(
            _state.value.copy(month = clamped, overviewUsesServerDefaultDate = false),
        )
        reloadSelection()
    }

    fun setQuarter(quarter: Int) {
        val today = reportsSelectionToday(_state.value)
        if (quarter !in 1..4 || _state.value.quarter == quarter ||
            (today != null && !canSelectFiscalQuarter(_state.value.fiscalYear, quarter, today))
        ) return
        _state.value = clearReportsPayloads(
            _state.value.copy(quarter = quarter, overviewUsesServerDefaultDate = false),
        )
        reloadSelection()
    }

    fun step(delta: Int) {
        if (delta > 0 && !_state.value.canStepForward) return
        val state = _state.value
        when (state.period) {
            ReportPeriod.DAILY -> setDate(state.onDate.plusDays(delta.toLong()))
            ReportPeriod.MONTHLY -> setMonth(state.month.plusMonths(delta.toLong()))
            ReportPeriod.QUARTERLY -> {
                val (fy, quarter) = shiftFiscalQuarter(state.fiscalYear, state.quarter, delta)
                if (!canSelectFiscalQuarter(fy, quarter)) return
                _state.value = clearReportsPayloads(
                    state.copy(fiscalYear = fy, quarter = quarter, overviewUsesServerDefaultDate = false),
                )
                reloadSelection()
            }
            ReportPeriod.YEARLY -> {
                val fy = fiscalYearOf(fiscalStartYear(state.fiscalYear) + delta)
                _state.value = clearReportsPayloads(
                    state.copy(fiscalYear = fy, overviewUsesServerDefaultDate = false),
                )
                reloadSelection()
            }
        }
    }

    fun jumpToCurrent() {
        val state = _state.value
        val today = reportsSelectionToday(state)
        if (today == null) {
            if (state.surface == ReportsSurface.OVERVIEW && state.period == ReportPeriod.DAILY) {
                _state.value = clearReportsPayloads(state.copy(overviewUsesServerDefaultDate = true))
                reloadSelection()
            }
            return
        }
        _state.value = clearReportsPayloads(
            _state.value.copy(
                onDate = today,
                month = YearMonth.from(today),
                fiscalYear = fiscalYearFor(today),
                quarter = fiscalQuarterFor(today),
                overviewUsesServerDefaultDate = false,
            ),
        )
        reloadSelection()
    }

    fun retry() {
        if (_state.value.surface == ReportsSurface.OVERVIEW && _state.value.operationalTab == ReportsOperationalTab.INVENTORY) {
            invalidateAllAttempts()
            cacheIsolation.currentLease()?.let { loadInventory(it, app.connectivity.online.value, explicitRefresh = true) }
            return
        }
        if (_state.value.surface != ReportsSurface.OVERVIEW || !_state.value.access.canReadOverview) {
            reloadSelection()
            return
        }
        invalidateAllAttempts()
        cacheIsolation.currentLease()?.let {
            loadOverview(it, app.connectivity.online.value, explicitRefresh = true)
        }
    }

    private fun reloadSelection() {
        invalidateAllAttempts()
        cacheIsolation.currentLease()?.let { loadSelected(it, app.connectivity.online.value) }
    }

    private fun loadSelected(lease: CacheScopeLease, online: Boolean) {
        when (_state.value.surface) {
            ReportsSurface.OVERVIEW -> if (_state.value.access.canReadOverview) {
                if (_state.value.operationalTab == ReportsOperationalTab.INVENTORY) loadInventory(lease, online)
                else loadOverview(lease, online)
            }
            ReportsSurface.PNL -> if (_state.value.access.canReadPnl) loadPnl(lease, online)
        }
    }

    private fun loadInventory(lease: CacheScopeLease, online: Boolean, explicitRefresh: Boolean = false) {
        if (!_state.value.access.canReadOverview || !_state.value.access.canReadInventory) return
        val serial = ++inventorySerial
        inventoryJob?.cancel()
        if (!online && !explicitRefresh) {
            _state.value = _state.value.copy(inventoryLoading = false, inventoryError =
                if (_state.value.inventory == null) "Connect to load current inventory." else "Offline · showing the last verified inventory snapshot.")
            return
        }
        _state.value = _state.value.copy(inventoryLoading = true, inventoryError = null)
        inventoryJob = viewModelScope.launch {
            fun current() = reportsInventoryAttemptIsCurrent(_state.value, lease, cacheIsolation.currentLease(), serial, inventorySerial)
            val result = fetchReportsInventoryIfCurrent(lease, ::current) {
                val branchId = requireNotNull(lease.scope.branchId)
                if (explicitRefresh) manualAdmission.execute(
                    operation = ManualBackendOperation.MANUAL_READ,
                    normal = { check(current()); inventoryApi.valuation(branchId) },
                    direct = { check(current()); ApiClient.createManualDirectApi(ReportsInventoryApi::class.java).valuation(branchId) },
                ) else inventoryApi.valuation(branchId)
            }
            cacheIsolation.commitIfCurrent(lease) {
                if (!current()) return@commitIfCurrent
                _state.value = when (result) {
                    is ReportsInventoryResult.Fresh -> _state.value.copy(inventory = result.value,
                        inventoryLoading = false, inventoryError = null, inventoryFetchedAtMillis = System.currentTimeMillis())
                    ReportsInventoryResult.Denied -> _state.value.copy(inventory = null,
                        inventoryLoading = false, inventoryError = "Inventory access is unavailable for this account.", inventoryFetchedAtMillis = null)
                    is ReportsInventoryResult.Failed -> _state.value.copy(inventoryLoading = false, inventoryError = result.message)
                    ReportsInventoryResult.Stale -> _state.value
                }
            }
        }
    }

    private fun loadOverview(lease: CacheScopeLease, online: Boolean, explicitRefresh: Boolean = false) {
        if (pendingOverviewExport != null) {
            cancelOverviewExport("The report changed. Export again for the current selection.")
        }
        val serial = ++overviewSerial
        overviewJob?.cancel()
        val selection = _state.value.selection
        val useServerDefaultDate = _state.value.overviewUsesServerDefaultDate &&
            selection.period == ReportPeriod.DAILY
        val requestedCacheKey = reportsOverviewCacheKey(lease, selection)
        _state.value = _state.value.copy(overviewLoading = true, overviewError = null, overviewDenied = false)
        overviewJob = viewModelScope.launch {
            fun current() = reportsOverviewAttemptIsCurrent(
                _state.value, lease, cacheIsolation.currentLease(), selection,
                useServerDefaultDate, serial, overviewSerial,
            )
            val result = executor.execute(
                lease = lease,
                online = online,
                attemptIsCurrent = ::current,
                cacheAdmissionAuthorized = {
                    !useServerDefaultDate && (lease to requestedCacheKey) in overviewCacheAdmission
                },
                loadCached = {
                    if (useServerDefaultDate) null else repository.latestOverview(lease, selection)
                },
                onCached = { cached ->
                    if (current()) _state.value = _state.value.copy(
                        overview = cached.value,
                        overviewFetchedAtMillis = cached.fetchedAtMillis,
                        overviewTimezone = cached.value.timezone,
                        overviewStale = true,
                    )
                },
                fetch = { repository.fetchOverview(lease, selection, useServerDefaultDate) },
                freshFetch = if (explicitRefresh) {
                    { isCurrent ->
                        fetchReportsOverviewForExplicitRefresh(
                            lease, selection, useServerDefaultDate, isCurrent,
                            repository, manualAdmission,
                        ) { ApiClient.createManualDirectApi(ReportsApi::class.java) }
                    }
                } else null,
                store = { overview ->
                    val resolvedSelection = if (useServerDefaultDate) {
                        selection.copy(onDate = LocalDate.parse(overview.periodStart))
                    } else selection
                    repository.storeOverview(lease, resolvedSelection, overview)
                },
                revokeCacheAdmission = {
                    overviewCacheAdmission.removeAll { it.first == lease }
                    if (current()) {
                        _state.value = _state.value.copy(
                            overviewLoading = false,
                            overviewError = "Reports Overview access is unavailable for this account.",
                            overview = null,
                            overviewFetchedAtMillis = null,
                            overviewTimezone = null,
                            overviewUsesServerDefaultDate = true,
                            overviewStale = false,
                            overviewDenied = true,
                        )
                    }
                },
                deleteSurfaceCache = { repository.deleteOverviewScope(lease) },
            )
            if (!current() || result is ReportsLoadResult.Stale) return@launch
            cacheIsolation.commitIfCurrent(lease) {
                if (!current()) return@commitIfCurrent
                _state.value = when (result) {
                    is ReportsLoadResult.Fresh -> {
                        val resolvedDate = if (useServerDefaultDate) {
                            LocalDate.parse(result.value.periodStart)
                        } else _state.value.onDate
                        if (reportsServerDefaultChangesSelection(
                                _state.value,
                                result.value,
                                useServerDefaultDate,
                            )
                        ) {
                            pnlSerial += 1
                            pnlJob?.cancel()
                        }
                        val resolvedSelection = selection.copy(onDate = resolvedDate)
                        val admission = lease to reportsOverviewCacheKey(lease, resolvedSelection)
                        if (result.cacheStored) overviewCacheAdmission += admission
                        else overviewCacheAdmission -= admission
                        reportsFreshOverviewState(
                            state = _state.value,
                            overview = result.value,
                            fetchedAtMillis = result.fetchedAtMillis,
                            useServerDefaultDate = useServerDefaultDate,
                        )
                    }
                    is ReportsLoadResult.Offline -> _state.value.copy(
                        overviewLoading = false,
                        overviewError = if (_state.value.overview != null) {
                            "Offline · showing the last verified Overview for this range."
                        } else "Connect to load Reports Overview.",
                        overviewStale = _state.value.overview != null,
                    )
                    is ReportsLoadResult.Failed -> _state.value.copy(
                        overviewLoading = false,
                        overviewError = result.error,
                        overviewStale = _state.value.overview != null,
                    )
                    is ReportsLoadResult.Denied -> {
                        overviewCacheAdmission.removeAll { it.first == lease }
                        _state.value.copy(
                            overviewLoading = false,
                            overviewError = "Reports Overview access is unavailable for this account.",
                            overview = null,
                            overviewFetchedAtMillis = null,
                            overviewTimezone = null,
                            overviewUsesServerDefaultDate = true,
                            overviewStale = false,
                            overviewDenied = true,
                        )
                    }
                    ReportsLoadResult.Stale -> error("Stale Overview result passed publication guard")
                }
            }
        }
    }

    private fun loadPnl(lease: CacheScopeLease, online: Boolean) {
        val serial = ++pnlSerial
        pnlJob?.cancel()
        val selection = _state.value.selection
        val cacheKey = reportsPnlCacheKey(lease, selection)
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            pnlDenied = false,
            costingLoading = true,
            costingError = null,
        )
        pnlJob = viewModelScope.launch {
            fun current() = serial == pnlSerial && cacheIsolation.currentLease() == lease &&
                _state.value.access.canReadPnl && _state.value.selection == selection
            val result = executor.execute(
                lease = lease,
                online = online,
                attemptIsCurrent = ::current,
                cacheAdmissionAuthorized = { (lease to cacheKey) in pnlCacheAdmission },
                loadCached = { repository.latestPnl(lease, selection) },
                onCached = { cached ->
                    if (current()) _state.value = _state.value.copy(
                        report = cached.value,
                        fetchedAtMillis = cached.fetchedAtMillis,
                        pnlStale = true,
                    )
                },
                fetch = { repository.fetchPnl(lease, selection) },
                store = { repository.storePnl(lease, selection, it) },
                revokeCacheAdmission = {
                    pnlCacheAdmission.removeAll { it.first == lease }
                    if (current()) {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = "Financial report access is unavailable for this account.",
                            report = null,
                            fetchedAtMillis = null,
                            costingCoverage = null,
                            costingLoading = false,
                            costingError = null,
                            pnlStale = false,
                            pnlDenied = true,
                        )
                    }
                },
                deleteSurfaceCache = { repository.deletePnlScope(lease) },
            )
            if (!current() || result is ReportsLoadResult.Stale) return@launch
            val mayLoadCosting = result !is ReportsLoadResult.Denied
            cacheIsolation.commitIfCurrent(lease) {
                if (!current()) return@commitIfCurrent
                _state.value = when (result) {
                    is ReportsLoadResult.Fresh -> {
                        val admission = lease to cacheKey
                        if (result.cacheStored) pnlCacheAdmission += admission else pnlCacheAdmission -= admission
                        _state.value.copy(
                            loading = false,
                            error = null,
                            report = result.value,
                            fetchedAtMillis = result.fetchedAtMillis,
                            pnlStale = false,
                            pnlDenied = false,
                        )
                    }
                    is ReportsLoadResult.Offline -> _state.value.copy(
                        loading = false,
                        error = if (_state.value.report != null) {
                            "Offline · showing the last verified P&L for this period."
                        } else "Connect to load this P&L.",
                        pnlStale = _state.value.report != null,
                    )
                    is ReportsLoadResult.Failed -> _state.value.copy(
                        loading = false,
                        error = result.error,
                        pnlStale = _state.value.report != null,
                    )
                    is ReportsLoadResult.Denied -> {
                        pnlCacheAdmission.removeAll { it.first == lease }
                        _state.value.copy(
                            loading = false,
                            error = "Financial report access is unavailable for this account.",
                            report = null,
                            fetchedAtMillis = null,
                            costingCoverage = null,
                            costingLoading = false,
                            costingError = null,
                            pnlStale = false,
                            pnlDenied = true,
                        )
                    }
                    ReportsLoadResult.Stale -> error("Stale P&L result passed publication guard")
                }
            }
            if (mayLoadCosting && current()) loadCosting(lease, online, serial)
        }
    }

    private suspend fun loadCosting(lease: CacheScopeLease, online: Boolean, serial: Long) {
        fun current() = serial == pnlSerial && cacheIsolation.currentLease() == lease &&
            _state.value.access.canReadPnl
        if (!current()) return
        val cacheKey = reportsCostingCacheKey(lease)
        val cached = if ((lease to cacheKey) in pnlCacheAdmission) repository.latestCosting(lease) else null
        if (!current()) return
        if (cached != null) cacheIsolation.commitIfCurrent(lease) {
            if (current()) _state.value = _state.value.copy(costingCoverage = cached.value)
        }
        if (!online || !current()) {
            cacheIsolation.commitIfCurrent(lease) {
                if (current()) _state.value = _state.value.copy(costingLoading = false)
            }
            return
        }
        try {
            val coverage = repository.fetchCosting(lease)
            if (!current()) return
            cacheIsolation.commitIfCurrent(lease) {
                if (!current()) return@commitIfCurrent
                val stored = repository.storeCosting(lease, coverage)
                val admission = lease to cacheKey
                if (stored) pnlCacheAdmission += admission else pnlCacheAdmission -= admission
                _state.value = _state.value.copy(
                    costingCoverage = coverage,
                    costingLoading = false,
                    costingError = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (!current()) return
            if (error.status == 401 || error.status == 403) {
                cacheIsolation.commitIfCurrent(lease) {
                    if (!current()) return@commitIfCurrent
                    pnlCacheAdmission.removeAll { it.first == lease }
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "Financial report access is unavailable for this account.",
                        report = null,
                        fetchedAtMillis = null,
                        costingCoverage = null,
                        costingLoading = false,
                        costingError = null,
                        pnlStale = false,
                        pnlDenied = true,
                    )
                    repository.deletePnlScope(lease)
                }
            } else publishCostingFailure(lease, ::current)
        } catch (_: Exception) {
            publishCostingFailure(lease, ::current)
        }
    }

    private suspend fun publishCostingFailure(lease: CacheScopeLease, current: () -> Boolean) {
        cacheIsolation.commitIfCurrent(lease) {
            if (current()) _state.value = _state.value.copy(
                costingLoading = false,
                costingError = if (_state.value.costingCoverage == null) {
                    "Inventory costing status could not be verified. COGS and profit may be incomplete."
                } else "Inventory costing status could not refresh. The saved warning is still shown.",
            )
        }
    }

    private fun invalidateForTenderCorrection() {
        val lease = cacheIsolation.currentLease()
        invalidateAllAttempts()
        if (lease != null) {
            overviewCacheAdmission.removeAll { it.first == lease }
            pnlCacheAdmission.removeAll { it.first == lease }
        }
        val selection = _state.value.selection
        val surface = _state.value.surface
        val access = _state.value.access
        _state.value = reportsStateAfterTenderCorrection(_state.value)
        if (lease == null) return
        val expectedOverviewSerial = overviewSerial
        val expectedPnlSerial = pnlSerial
        viewModelScope.launch {
            cacheIsolation.commitIfCurrent(lease) {
                if (access.canReadOverview) repository.deleteOverviewScope(lease)
                if (access.canReadPnl) repository.deletePnlScope(lease)
            }
            val stillCurrent = cacheIsolation.currentLease() == lease &&
                overviewSerial == expectedOverviewSerial && pnlSerial == expectedPnlSerial &&
                _state.value.selection == selection && _state.value.surface == surface
            if (stillCurrent && _state.value.access.canReadAny) {
                loadSelected(lease, app.connectivity.online.value)
            }
        }
    }

    private fun invalidateAllAttempts() {
        inventorySerial += 1
        inventoryJob?.cancel()
        cancelOverviewExport()
        overviewSerial += 1
        pnlSerial += 1
        overviewJob?.cancel()
        pnlJob?.cancel()
    }

}

internal fun clearReportsPayloads(state: ReportsUiState): ReportsUiState = state.copy(
    inventory = null, inventoryLoading = false, inventoryError = null, inventoryFetchedAtMillis = null,
    overviewLoading = false,
    overviewError = null,
    overview = null,
    overviewFetchedAtMillis = null,
    overviewStale = false,
    overviewDenied = false,
    overviewExportBusy = false,
    overviewExportMessage = null,
    overviewExportFailed = false,
    loading = false,
    error = null,
    report = null,
    costingCoverage = null,
    costingLoading = false,
    costingError = null,
    fetchedAtMillis = null,
    pnlStale = false,
    pnlDenied = false,
)

internal fun reportsStateAfterTenderCorrection(state: ReportsUiState): ReportsUiState =
    clearReportsPayloads(state)

internal fun reportsFreshOverviewState(
    state: ReportsUiState,
    overview: ValidatedReportsOverview,
    fetchedAtMillis: Long,
    useServerDefaultDate: Boolean,
): ReportsUiState {
    val resolvedDate = if (useServerDefaultDate) LocalDate.parse(overview.periodStart) else state.onDate
    val base = if (reportsServerDefaultChangesSelection(state, overview, useServerDefaultDate)) {
        clearReportsPayloads(state)
    } else {
        state
    }
    return base.copy(
        overviewLoading = false,
        overviewError = null,
        overview = overview,
        overviewFetchedAtMillis = fetchedAtMillis,
        overviewTimezone = overview.timezone,
        overviewUsesServerDefaultDate = false,
        onDate = resolvedDate,
        month = if (useServerDefaultDate) YearMonth.from(resolvedDate) else base.month,
        fiscalYear = if (useServerDefaultDate) fiscalYearFor(resolvedDate) else base.fiscalYear,
        quarter = if (useServerDefaultDate) fiscalQuarterFor(resolvedDate) else base.quarter,
        overviewStale = false,
        overviewDenied = false,
    )
}

internal fun reportsServerDefaultChangesSelection(
    state: ReportsUiState,
    overview: ValidatedReportsOverview,
    useServerDefaultDate: Boolean,
): Boolean = useServerDefaultDate && LocalDate.parse(overview.periodStart) != state.onDate

internal fun reportsSelectionToday(state: ReportsUiState): LocalDate? =
    if (state.surface == ReportsSurface.PNL) businessToday()
    else state.overviewTimezone?.let { timezone ->
        runCatching { LocalDate.now(ZoneId.of(timezone)) }.getOrNull()
    }

internal fun reportsOverviewAttemptIsCurrent(
    state: ReportsUiState,
    lease: CacheScopeLease,
    currentLease: CacheScopeLease?,
    selection: ReportSelection,
    useServerDefaultDate: Boolean,
    serial: Long,
    currentSerial: Long,
): Boolean = serial == currentSerial && lease == currentLease &&
    state.surface == ReportsSurface.OVERVIEW && state.access.canReadOverview &&
    state.selection == selection &&
    (state.overviewUsesServerDefaultDate && state.period == ReportPeriod.DAILY) == useServerDefaultDate

/** One user-owned Overview read; a local readiness proof never admits P&L or costing. */
internal suspend fun fetchReportsOverviewForExplicitRefresh(
    lease: CacheScopeLease,
    selection: ReportSelection,
    useServerDefaultDate: Boolean,
    isCurrent: () -> Boolean,
    repository: ReportsRepository,
    admission: ManualBackendRequestAdmission,
    directApi: () -> ReportsApi,
): ValidatedReportsOverview? {
    if (!isCurrent()) return null
    return admission.execute(
        operation = ManualBackendOperation.MANUAL_READ,
        normal = {
            if (!isCurrent()) null else repository.fetchOverview(lease, selection, useServerDefaultDate)
        },
        direct = {
            if (!isCurrent()) null else repository.fetchOverview(lease, selection, useServerDefaultDate, directApi())
        },
    )
}

internal fun visibleReportsState(
    state: ReportsUiState,
    currentLease: CacheScopeLease?,
    access: ReportsAccess,
): ReportsUiState {
    val exactLease = currentLease != null && state.lease == currentLease
    val surface = when {
        state.surface == ReportsSurface.OVERVIEW && access.canReadOverview -> ReportsSurface.OVERVIEW
        state.surface == ReportsSurface.PNL && access.canReadPnl -> ReportsSurface.PNL
        access.canReadOverview -> ReportsSurface.OVERVIEW
        else -> ReportsSurface.PNL
    }
    return state.copy(
        access = access,
        lease = currentLease,
        surface = surface,
        operationalTab = if (state.operationalTab == ReportsOperationalTab.INVENTORY && !access.canReadInventory) ReportsOperationalTab.OVERVIEW else state.operationalTab,
        inventory = state.inventory.takeIf { exactLease && access.canReadOverview && access.canReadInventory },
        inventoryLoading = state.inventoryLoading && exactLease && access.canReadOverview && access.canReadInventory,
        inventoryError = state.inventoryError.takeIf { exactLease && access.canReadOverview && access.canReadInventory },
        inventoryFetchedAtMillis = state.inventoryFetchedAtMillis.takeIf { exactLease && access.canReadOverview && access.canReadInventory },
        overview = state.overview.takeIf { exactLease && access.canReadOverview },
        overviewFetchedAtMillis = state.overviewFetchedAtMillis.takeIf { exactLease && access.canReadOverview },
        overviewTimezone = state.overviewTimezone.takeIf { exactLease && access.canReadOverview },
        overviewUsesServerDefaultDate = state.overviewUsesServerDefaultDate || !exactLease || !access.canReadOverview,
        overviewLoading = state.overviewLoading && exactLease && access.canReadOverview,
        overviewError = state.overviewError.takeIf { exactLease && access.canReadOverview },
        overviewStale = state.overviewStale && exactLease && access.canReadOverview,
        overviewDenied = state.overviewDenied && exactLease && access.canReadOverview,
        overviewExportBusy = state.overviewExportBusy && exactLease && access.canReadOverview && access.canExportOverview,
        overviewExportMessage = state.overviewExportMessage.takeIf { exactLease && access.canReadOverview && access.canExportOverview },
        overviewExportFailed = state.overviewExportFailed && exactLease && access.canReadOverview && access.canExportOverview,
        report = state.report.takeIf { exactLease && access.canReadPnl },
        fetchedAtMillis = state.fetchedAtMillis.takeIf { exactLease && access.canReadPnl },
        costingCoverage = state.costingCoverage.takeIf { exactLease && access.canReadPnl },
        loading = state.loading && exactLease && access.canReadPnl,
        error = state.error.takeIf { exactLease && access.canReadPnl },
        costingLoading = state.costingLoading && exactLease && access.canReadPnl,
        costingError = state.costingError.takeIf { exactLease && access.canReadPnl },
        pnlStale = state.pnlStale && exactLease && access.canReadPnl,
        pnlDenied = state.pnlDenied && exactLease && access.canReadPnl,
    )
}

private fun initialReportsState(): ReportsUiState = businessToday().let { today ->
    ReportsUiState(
        onDate = today,
        month = YearMonth.from(today),
        fiscalYear = fiscalYearFor(today),
        quarter = fiscalQuarterFor(today),
    )
}
