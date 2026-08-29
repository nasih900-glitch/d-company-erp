package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CostingCoverage
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsPresentationPolicyTest {

    private val date = LocalDate.of(2026, 8, 26)

    @Test
    fun `gaming centre report preserves hidden-module money under one neutral row`() {
        val revenue = ReportRevenue(
            foodMinor = 1_000,
            gamingMinor = 2_000,
            hookahMinor = 3_000,
            eventTicketsMinor = 4_000,
            membershipsMinor = 5_000,
            deliveryAggregatorMinor = 6_000,
            otherMinor = 7_000,
            manualCollectionsMinor = 8_000,
        )
        val presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()
        val rows = revenue.presentedSources(presentation)

        assertEquals(36_000L, rows.sumOf { it.amountMinor })
        assertEquals(22_000L, rows.single { it.label == "Legacy/other revenue" }.amountMinor)
        assertEquals(22_000L, revenue.hiddenLegacySourceMinor(presentation))
        assertFalse(rows.any { it.label.contains("membership", ignoreCase = true) })
        assertFalse(rows.any { it.label.contains("event", ignoreCase = true) })
        assertFalse(rows.any { it.label.contains("delivery", ignoreCase = true) })
    }

    @Test
    fun `gaming centre hides zero legacy sources and event ticket metric`() {
        val presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()
        val rows = ReportRevenue(gamingMinor = 400).presentedSources(presentation)
        val metrics = reportSecondaryMetrics(ReportData(ticketsCount = 9), presentation)

        assertFalse(rows.any { it.label == "Legacy/other revenue" })
        assertFalse(metrics.any { it.label == "Tickets" })
    }

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

    @Test
    fun `secondary report metrics preserve tickets and source-backed financial ratios`() {
        val metrics = reportSecondaryMetrics(
            ReportData(
                ticketsCount = 7,
                netRevenueMinor = 20_000,
                netProfitMinor = 5_000,
                cogsMinor = 4_000,
                expenseTotalMinor = 3_000,
                depreciationMinor = 1_000,
            ),
        )

        assertEquals(listOf("Tickets", "Profit margin", "Cost ratio"), metrics.map { it.label })
        assertEquals(listOf("7", "25.0%", "40.0%"), metrics.map { it.value })
        assertEquals(
            listOf(UiTone.Neutral, UiTone.Success, UiTone.Success),
            metrics.map { it.tone },
        )
    }

    @Test
    fun `secondary ratios handle a zero revenue denominator without fake percentages`() {
        val metrics = reportSecondaryMetrics(
            ReportData(netRevenueMinor = 0, netProfitMinor = -500, expenseTotalMinor = 500),
        )

        assertEquals("0.0%", metrics[1].value)
        assertEquals(UiTone.Danger, metrics[1].tone)
        assertEquals("0.0%", metrics[2].value)
        assertEquals(UiTone.Neutral, metrics[2].tone)
    }

    @Test
    fun `refund and cost only periods are not presented as no activity`() {
        assertFalse(ReportData(refundsIssuedMinor = 500).hasNothing)
        assertFalse(ReportData(cogsMinor = 500).hasNothing)
        assertFalse(ReportData(expenseTotalMinor = 500).hasNothing)
    }

    @Test
    fun `report wire contract requires branch scope and invoice integrity count`() {
        val decoded = ApiClient.json.decodeFromString<ReportData>(
            """{"branch_id":"branch-a","unissued_paid_orders_count":0}""",
        )
        assertEquals("branch-a", decoded.branchId)

        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<ReportData>(
                """{"unissued_paid_orders_count":0}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<ReportData>(
                """{"branch_id":"branch-a"}""",
            )
        }
    }

    @Test
    fun `costing coverage wire contract requires its selected branch`() {
        val decoded = ApiClient.json.decodeFromString<CostingCoverage>(
            """{"branch_id":"branch-a"}""",
        )
        assertEquals("branch-a", decoded.branchId)

        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<CostingCoverage>("{}")
        }
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
