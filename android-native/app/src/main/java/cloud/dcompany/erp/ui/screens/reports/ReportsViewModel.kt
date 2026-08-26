package cloud.dcompany.erp.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The cafe's books run on Indian Standard Time regardless of what the tablet's
 * clock is set to. Deriving "today" from the device would let a mis-set tablet
 * ask for tomorrow's takings and be told, truthfully, that there were none.
 */
private val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

internal fun businessToday(): LocalDate = LocalDate.now(BUSINESS_ZONE)

/** Indian fiscal year: April to March. 12 Aug 2026 -> "2026-27". */
internal fun fiscalYearFor(date: LocalDate): String {
    val startYear = if (date.monthValue >= 4) date.year else date.year - 1
    return fiscalYearOf(startYear)
}

internal fun fiscalYearOf(startYear: Int): String =
    "$startYear-${((startYear + 1) % 100).toString().padStart(2, '0')}"

/** Q1 = Apr-Jun … Q4 = Jan-Mar. */
internal fun fiscalQuarterFor(date: LocalDate): Int = ((date.monthValue - 4 + 12) % 12) / 3 + 1

/** "2026-27" -> 2026. Falls back to the current fiscal year if the text is junk. */
internal fun fiscalStartYear(fy: String): Int =
    fy.take(4).toIntOrNull() ?: fiscalYearFor(businessToday()).take(4).toInt()

internal fun fiscalQuarterOrdinal(fiscalYear: String, quarter: Int): Long =
    fiscalStartYear(fiscalYear).toLong() * 4L + (quarter.coerceIn(1, 4) - 1)

internal fun shiftFiscalQuarter(
    fiscalYear: String,
    quarter: Int,
    delta: Int,
): Pair<String, Int> {
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

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.DAILY,
    val onDate: LocalDate,
    val month: YearMonth,
    val fiscalYear: String,
    val quarter: Int,
    val loading: Boolean = true,
    val error: String? = null,
    val report: ReportData? = null,
    /** When `report` was last actually fetched from the server — null for a report that's never synced. */
    val fetchedAtMillis: Long? = null,
) {
    /**
     * Nothing is ever recorded ahead of time, so stepping past the present is
     * only ever a way to land on an empty report and wonder why.
     */
    val canStepForward: Boolean
        get() {
            val today = businessToday()
            return when (period) {
                ReportPeriod.DAILY -> onDate.isBefore(today)
                ReportPeriod.MONTHLY -> month.isBefore(YearMonth.from(today))
                ReportPeriod.QUARTERLY -> fiscalQuarterOrdinal(fiscalYear, quarter) <
                    fiscalQuarterOrdinal(fiscalYearFor(today), fiscalQuarterFor(today))
                ReportPeriod.YEARLY ->
                    fiscalStartYear(fiscalYear) < fiscalStartYear(fiscalYearFor(today))
            }
        }
}

internal enum class ReportPresentation {
    INITIAL_LOADING,
    BLOCKING_ERROR,
    FRESH_EMPTY,
    STALE_EMPTY,
    FRESH_CONTENT,
    STALE_CONTENT,
}

/** Pure render policy: a failed refresh can never masquerade as a confirmed
 * empty period merely because an older empty snapshot exists. */
internal fun reportPresentation(state: ReportsUiState): ReportPresentation = when {
    state.report == null && state.error != null -> ReportPresentation.BLOCKING_ERROR
    state.report == null -> ReportPresentation.INITIAL_LOADING
    state.report.hasNothing && state.error != null -> ReportPresentation.STALE_EMPTY
    state.report.hasNothing -> ReportPresentation.FRESH_EMPTY
    state.error != null -> ReportPresentation.STALE_CONTENT
    else -> ReportPresentation.FRESH_CONTENT
}

internal fun reportLoadError(error: Throwable): String = when (error) {
    is ApiException -> error.message?.takeIf(String::isNotBlank) ?: "Could not load the report."
    else -> "Could not refresh the report. Check the connection and try again."
}

/** The exact key this selection is cached under — see core/db/ReportSnapshots.kt. */
private fun cacheKey(s: ReportsUiState): String = when (s.period) {
    ReportPeriod.DAILY -> "daily:${s.onDate}"
    ReportPeriod.MONTHLY -> "monthly:${s.month}"
    ReportPeriod.QUARTERLY -> "quarterly:${s.fiscalYear}:Q${s.quarter}"
    ReportPeriod.YEARLY -> "yearly:${s.fiscalYear}"
}

/**
 * Room-backed via the shared [cloud.dcompany.erp.core.db.ReportSnapshotEntity]
 * cache, not a per-row entity — a P&L has nothing to queue or merge, it's one
 * JSON blob per (period type + resolved date/month/fy/quarter) key. Every
 * load() seeds instantly from that key's cache (if any) before the network
 * call resolves, and a cached report for the CURRENT key is never mislabeled
 * the way the old "drop everything on error" behavior was guarding against —
 * the key already encodes the exact period, so a stale cached figure stays on
 * screen (with its own fetchedAtMillis) instead of being replaced by an error
 * banner when offline.
 */
class ReportsViewModel : ViewModel() {

    private val api = ApiClient.create<ReportsApi>()
    private val db = DCompanyApp.instance.db
    private val cacheIsolation = DCompanyApp.instance.cacheIsolation

    private val _state = MutableStateFlow(
        businessToday().let { today ->
            ReportsUiState(
                onDate = today,
                month = YearMonth.from(today),
                fiscalYear = fiscalYearFor(today),
                quarter = fiscalQuarterFor(today),
            )
        }
    )
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    /**
     * Only one report may be in flight. Without this, tapping Yearly then
     * Daily can leave the slower yearly response painting itself over the
     * daily numbers under a "Daily P&L" heading — a wrong figure presented
     * as the right one.
     */
    private var inFlight: Job? = null
    private var requestSerial: Long = 0

    init { load() }

    fun selectPeriod(period: ReportPeriod) {
        if (_state.value.period == period) return
        _state.value = _state.value.copy(period = period)
        load()
    }

    fun setDate(date: LocalDate) {
        val today = businessToday()
        val clamped = if (date.isAfter(today)) today else date
        if (_state.value.onDate == clamped) return
        _state.value = _state.value.copy(onDate = clamped)
        load()
    }

    fun setMonth(month: YearMonth) {
        val current = YearMonth.from(businessToday())
        val clamped = if (month.isAfter(current)) current else month
        if (_state.value.month == clamped) return
        _state.value = _state.value.copy(month = clamped)
        load()
    }

    fun setQuarter(quarter: Int) {
        if (
            quarter !in 1..4 ||
            _state.value.quarter == quarter ||
            !canSelectFiscalQuarter(_state.value.fiscalYear, quarter)
        ) return
        _state.value = _state.value.copy(quarter = quarter)
        load()
    }

    /** One step back or forward in whatever unit the current period uses. */
    fun step(delta: Int) {
        if (delta > 0 && !_state.value.canStepForward) return
        val s = _state.value
        when (s.period) {
            ReportPeriod.DAILY -> setDate(s.onDate.plusDays(delta.toLong()))
            ReportPeriod.MONTHLY -> setMonth(s.month.plusMonths(delta.toLong()))
            ReportPeriod.QUARTERLY -> {
                val (fy, quarter) = shiftFiscalQuarter(s.fiscalYear, s.quarter, delta)
                if (!canSelectFiscalQuarter(fy, quarter)) return
                _state.value = s.copy(fiscalYear = fy, quarter = quarter)
                load()
            }
            ReportPeriod.YEARLY -> {
                val fy = fiscalYearOf(fiscalStartYear(s.fiscalYear) + delta)
                _state.value = s.copy(fiscalYear = fy)
                load()
            }
        }
    }

    fun jumpToCurrent() {
        val today = businessToday()
        _state.value = _state.value.copy(
            onDate = today,
            month = YearMonth.from(today),
            fiscalYear = fiscalYearFor(today),
            quarter = fiscalQuarterFor(today),
        )
        load()
    }

    fun retry() = load()

    private fun load() {
        val requestId = ++requestSerial
        inFlight?.cancel()
        val s = _state.value
        val key = cacheKey(s)
        // Cleared immediately, not left showing the PREVIOUS period's figures
        // under the new heading while this period's own cache is read — that
        // read is a fast local lookup, not worth a flash of wrong numbers to
        // save.
        _state.value = s.copy(loading = true, error = null, report = null, fetchedAtMillis = null)
        inFlight = viewModelScope.launch {
            try {
                db.reportSnapshotDao().cached<ReportData>(key)?.let { (cachedReport, fetchedAt) ->
                    if (requestId == requestSerial) {
                        _state.value = _state.value.copy(
                            report = cachedReport,
                            fetchedAtMillis = fetchedAt,
                        )
                    }
                }
                lateinit var report: ReportData
                val committed = cacheIsolation.fetchAndCommitScoped(
                    fetch = {
                        when (s.period) {
                            ReportPeriod.DAILY -> api.daily(s.onDate.toString())
                            // YearMonth.toString() is exactly the "2026-08" the
                            // backend validates yyyy_mm against.
                            ReportPeriod.MONTHLY -> api.monthly(s.month.toString())
                            ReportPeriod.QUARTERLY -> api.quarterly(s.fiscalYear, s.quarter)
                            ReportPeriod.YEARLY -> api.yearly(s.fiscalYear)
                        }
                    },
                    store = {
                        report = it
                        db.reportSnapshotDao().store(key, it)
                    },
                )
                if (!committed || requestId != requestSerial) return@launch
                val now = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    report = report, fetchedAtMillis = now, error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestId == requestSerial) {
                    _state.value = _state.value.copy(error = reportLoadError(error))
                }
            } finally {
                if (requestId == requestSerial) {
                    _state.value = _state.value.copy(loading = false)
                }
            }
        }
    }
}
