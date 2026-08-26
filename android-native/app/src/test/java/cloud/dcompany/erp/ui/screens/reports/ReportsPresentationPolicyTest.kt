package cloud.dcompany.erp.ui.screens.reports

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsPresentationPolicyTest {

    private val date = LocalDate.of(2026, 8, 26)

    @Test
    fun `failed refresh never presents a cached empty report as confirmed empty`() {
        val state = state(report = ReportData(label = "26 Aug 2026"), error = "Offline")

        assertEquals(ReportPresentation.STALE_EMPTY, reportPresentation(state))
    }

    @Test
    fun `failed refresh keeps cached report visible but explicitly stale`() {
        val report = ReportData(
            label = "26 Aug 2026",
            ordersCount = 1,
            revenue = ReportRevenue(totalMinor = 12_500),
        )

        assertEquals(
            ReportPresentation.STALE_CONTENT,
            reportPresentation(state(report = report, error = "Timed out")),
        )
    }

    @Test
    fun `first-load failure blocks an empty-looking report and successful empty remains valid`() {
        assertEquals(
            ReportPresentation.BLOCKING_ERROR,
            reportPresentation(state(report = null, error = "Server unavailable")),
        )
        assertEquals(
            ReportPresentation.FRESH_EMPTY,
            reportPresentation(state(report = ReportData(label = "26 Aug 2026"))),
        )
    }

    @Test
    fun `unexpected failures receive an actionable nontechnical message`() {
        val message = reportLoadError(IllegalStateException("raw implementation detail"))

        assertTrue(message.contains("try again", ignoreCase = true))
        assertTrue(!message.contains("implementation detail"))
    }

    @Test
    fun `quarter navigation crosses fiscal years one quarter at a time`() {
        assertEquals("2025-26" to 4, shiftFiscalQuarter("2026-27", 1, -1))
        assertEquals("2027-28" to 1, shiftFiscalQuarter("2026-27", 4, 1))
        assertEquals("2025-26" to 2, shiftFiscalQuarter("2026-27", 2, -4))
    }

    @Test
    fun `future quarter choices are disabled while current and historical quarters remain available`() {
        val today = LocalDate.of(2026, 8, 26) // FY 2026-27, Q2.

        assertTrue(canSelectFiscalQuarter("2026-27", 2, today))
        assertTrue(canSelectFiscalQuarter("2025-26", 4, today))
        assertFalse(canSelectFiscalQuarter("2026-27", 3, today))
        assertFalse(canSelectFiscalQuarter("2027-28", 1, today))
    }

    private fun state(report: ReportData?, error: String? = null) = ReportsUiState(
        onDate = date,
        month = YearMonth.from(date),
        fiscalYear = "2026-27",
        quarter = 2,
        loading = false,
        error = error,
        report = report,
        fetchedAtMillis = report?.let { 1L },
    )
}
