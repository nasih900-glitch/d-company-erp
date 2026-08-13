package cloud.dcompany.erp.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
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

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.DAILY,
    val onDate: LocalDate,
    val month: YearMonth,
    val fiscalYear: String,
    val quarter: Int,
    val loading: Boolean = true,
    val error: String? = null,
    val report: ReportData? = null,
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
                ReportPeriod.QUARTERLY, ReportPeriod.YEARLY ->
                    fiscalStartYear(fiscalYear) < fiscalStartYear(fiscalYearFor(today))
            }
        }
}

class ReportsViewModel : ViewModel() {

    private val api = ApiClient.create<ReportsApi>()

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
        if (quarter !in 1..4 || _state.value.quarter == quarter) return
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
            ReportPeriod.QUARTERLY, ReportPeriod.YEARLY -> {
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
        inFlight?.cancel()
        val s = _state.value
        _state.value = s.copy(loading = true, error = null)
        inFlight = viewModelScope.launch {
            try {
                val report = when (s.period) {
                    ReportPeriod.DAILY -> api.daily(s.onDate.toString())
                    // YearMonth.toString() is exactly the "2026-08" the
                    // backend validates yyyy_mm against.
                    ReportPeriod.MONTHLY -> api.monthly(s.month.toString())
                    ReportPeriod.QUARTERLY -> api.quarterly(s.fiscalYear, s.quarter)
                    ReportPeriod.YEARLY -> api.yearly(s.fiscalYear)
                }
                _state.value = _state.value.copy(loading = false, report = report, error = null)
            } catch (e: ApiException) {
                // The stale report is dropped rather than left on screen under
                // an error banner: a P&L labelled with one period but holding
                // another period's money is worse than no P&L.
                _state.value = _state.value.copy(
                    loading = false,
                    report = null,
                    error = e.message ?: "Could not load the report.",
                )
            }
        }
    }
}
