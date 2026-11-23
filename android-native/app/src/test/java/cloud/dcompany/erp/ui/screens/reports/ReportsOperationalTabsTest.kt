package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.ReportsAccess
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import org.junit.Assert.*
import org.junit.Test

class ReportsOperationalTabsTest {
    private val branch = "11111111-1111-1111-1111-111111111111"
    private val lease = CacheScopeLease(CacheScope("user", "company", branch, "till"), 1L)
    private fun state() = ReportsUiState(
        access = ReportsAccess(canReadOverview = true, canReadPnl = true, canExportOverview = true, canReadInventory = true),
        lease = lease, onDate = LocalDate.parse("2026-10-27"), month = YearMonth.parse("2026-10"), fiscalYear = "2026-27", quarter = 3,
        overviewTimezone = "Asia/Dubai", overviewUsesServerDefaultDate = false,
        overview = ValidatedReportsOverview(branch, "2026-10-27", "2026-10-27", "Asia/Dubai", 1L,
            0L, 0L, 0L, 0L, 0L, 0L,
            ReportsOverviewSources(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ReportsOverviewPayments(0, 0, 0, 0, 0, 0, 0, 0),
            ReportsOverviewHourProfile.MultiDayUnavailable, emptyList(), emptyList()),
    )
    private fun wire() = ReportsInventoryWire("2026-10-27", branch, listOf(
        ReportsInventoryLine("22222222-2222-2222-2222-222222222222", "TEA", "Tea", "kg", -0.1255, 333, -42, 1.25, true),
    ), -42, 1)

    @Test fun `all operational switches preserve snapshot and dispatch zero overview reads`() {
        var current = state()
        val admitted = current.overview
        var dispatchedReads = 0
        repeat(3) {
            ReportsOperationalTab.entries.filter { it != ReportsOperationalTab.INVENTORY }.forEach { tab ->
                current = reportsSelectOperationalTab(current, tab)
                when (reportsOperationalLoadNeeded(current)) {
                    ReportsOperationalLoad.OVERVIEW -> dispatchedReads++
                    ReportsOperationalLoad.INVENTORY -> fail("Operational switch requested inventory")
                    ReportsOperationalLoad.NONE -> Unit
                }
                assertSame(admitted, current.overview)
            }
        }
        assertEquals(0, dispatchedReads)
        val pending = state().copy(overview = null, overviewLoading = true)
        assertEquals(ReportsOperationalLoad.NONE, reportsOperationalLoadNeeded(reportsSelectOperationalTab(pending, ReportsOperationalTab.SALES)))
        assertEquals(ReportsOperationalLoad.OVERVIEW, reportsOperationalLoadNeeded(pending.copy(overviewLoading = false)))
    }

    @Test fun `returning from independent surfaces rechecks overview without duplicating an active read`() {
        for (previous in listOf(
            state().copy(surface = ReportsSurface.PNL),
            state().copy(operationalTab = ReportsOperationalTab.INVENTORY, inventoryError = "Offline"),
        )) {
            val next = reportsSelectOperationalTab(previous, ReportsOperationalTab.SALES)
            assertSame(previous.overview, next.overview)
            assertEquals(previous.selection, next.selection)
            assertEquals(ReportsOperationalLoad.OVERVIEW, reportsOperationalLoadNeeded(next, previous))
            assertEquals(ReportsOperationalLoad.NONE,
                reportsOperationalLoadNeeded(next.copy(overviewLoading = true), previous))
        }
        val sales = state().copy(operationalTab = ReportsOperationalTab.SALES)
        val payments = reportsSelectOperationalTab(sales, ReportsOperationalTab.PAYMENTS)
        assertEquals(ReportsOperationalLoad.NONE, reportsOperationalLoadNeeded(payments, sales))
        assertEquals(ReportsOperationalLoad.NONE, reportsOperationalLoadNeeded(payments.copy(overviewLoading = true), sales))
        val noAuthority = payments.copy(access = ReportsAccess())
        assertEquals(ReportsOperationalLoad.NONE, reportsOperationalLoadNeeded(noAuthority, state().copy(surface = ReportsSurface.PNL)))
    }

    @Test fun `inventory is independently filtered and has no range or overview export`() {
        val inventory = reportsSelectOperationalTab(state(), ReportsOperationalTab.INVENTORY)
        assertEquals(ReportsOperationalLoad.INVENTORY, reportsOperationalLoadNeeded(inventory))
        assertFalse(reportsSelectionControlsVisible(inventory))
        assertFalse(canExportReportsOverview(inventory, lease))
        assertTrue(canExportReportsOverview(state(), lease))
        val denied = state().copy(access = state().access.copy(canReadInventory = false))
        assertFalse(ReportsOperationalTab.INVENTORY in availableReportsOperationalTabs(denied))
        assertEquals(denied, reportsSelectOperationalTab(denied, ReportsOperationalTab.INVENTORY))
        assertTrue(availableReportsOperationalTabs(state().copy(access = ReportsAccess(canReadPnl = true, canReadInventory = true))).isEmpty())
    }

    @Test fun `inventory read uses selected branch existing endpoint and exact server valuation`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ApiClient.json.encodeToString(wire())))
            val api = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
                .build().create(ReportsInventoryApi::class.java)
            val result = fetchReportsInventoryIfCurrent(lease, { true }) { api.valuation(branch) }
            assertEquals("/api/v1/insights/inventory/valuation?branch_id=$branch", server.takeRequest().path)
            assertEquals(-42L, (result as ReportsInventoryResult.Fresh).value.totalValuationMinor)
            assertEquals(-0.1255, result.value.lines.single().currentQty, 0.0)
        }
    }

    @Test fun `inventory required wire rejects missing fields rather than displaying zeros`() {
        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<ReportsInventoryWire>("""{"as_of":"2026-10-27","branch_id":"$branch"}""")
        }
        val result = validateReportsInventory(wire(), lease)
        assertEquals(-42L, result.totalValuationMinor)
        assertEquals("-0.1255", result.lines.single().currentQty.asReportsStockQuantity())
        assertEquals(333L, result.lines.single().avgCostMinor)
        assertEquals(1, result.lowStockCount)
    }

    @Test fun `inventory contract enforces branch date identities finite quantities exact totals and low stock`() {
        listOf(
            wire().copy(branchId = "other"), wire().copy(asOf = "not-a-date"),
            wire().copy(lines = wire().lines + wire().lines), wire().copy(totalValuationMinor = 0),
            wire().copy(lowStockCount = 0), wire().copy(lines = listOf(wire().lines.single().copy(currentQty = Double.NaN))),
            wire().copy(lines = listOf(wire().lines.single().copy(reorderThreshold = Double.POSITIVE_INFINITY))),
            wire().copy(lines = listOf(wire().lines.single().copy(ingredientId = "invalid"))),
        ).forEach { invalid -> assertThrows(ReportsContractException::class.java) { validateReportsInventory(invalid, lease) } }
        val first = wire().lines.single().copy(valuationMinor = Long.MAX_VALUE)
        val second = first.copy(ingredientId = "33333333-3333-3333-3333-333333333333", valuationMinor = 1)
        assertThrows(ReportsContractException::class.java) {
            validateReportsInventory(wire().copy(lines = listOf(first, second), totalValuationMinor = Long.MIN_VALUE, lowStockCount = 2), lease)
        }
    }

    @Test fun `visible projection removes inventory on revoked permission and every changed authority scope`() {
        val current = reportsSelectOperationalTab(state(), ReportsOperationalTab.INVENTORY).copy(inventory = validateReportsInventory(wire(), lease))
        val revoked = visibleReportsState(current, lease, current.access.copy(canReadInventory = false))
        assertNull(revoked.inventory)
        assertEquals(ReportsOperationalTab.OVERVIEW, revoked.operationalTab)
        listOf(null, lease.copy(generation = 2), lease.copy(scope = lease.scope.copy(userId = "other")),
            lease.copy(scope = lease.scope.copy(companyId = "other")), lease.copy(scope = lease.scope.copy(branchId = "other")),
            lease.copy(scope = lease.scope.copy(terminalId = "other"))).forEach { changed ->
            assertNull(visibleReportsState(current, changed, current.access).inventory)
            assertFalse(reportsInventoryAttemptIsCurrent(current, lease, changed, 1, 1))
        }
        assertFalse(reportsInventoryAttemptIsCurrent(current, lease, lease, 1, 2))
        assertFalse(reportsInventoryAttemptIsCurrent(current.copy(operationalTab = ReportsOperationalTab.SALES), lease, lease, 1, 1))
    }

    @Test fun `inventory denial is distinct from transient failure and cancellation propagates`() = runBlocking {
        for (status in listOf(401, 403)) {
            assertEquals(ReportsInventoryResult.Denied, fetchReportsInventoryIfCurrent(lease, { true }) { throw ApiException("Denied", status) })
        }
        assertTrue(fetchReportsInventoryIfCurrent(lease, { true }) { throw ApiException("Unavailable", 503) } is ReportsInventoryResult.Failed)
        assertThrows(CancellationException::class.java) {
            runBlocking { fetchReportsInventoryIfCurrent(lease, { true }) { throw CancellationException("left view") } }
        }
        Unit
    }

    @Test fun `old inventory success and denial cannot replace newer session data`() = runBlocking {
        for (denial in listOf(false, true)) {
            var current = true
            val release = CompletableDeferred<Unit>()
            val pending = async {
                fetchReportsInventoryIfCurrent(lease, { current }) {
                    release.await()
                    if (denial) throw ApiException("Denied", 403)
                    wire()
                }
            }
            yield()
            current = false
            release.complete(Unit)
            assertEquals(ReportsInventoryResult.Stale, pending.await())
        }
        assertEquals(ReportsInventoryResult.Denied, fetchReportsInventoryIfCurrent(lease, { true }) { throw ApiException("Denied", 403) })
        assertTrue(fetchReportsInventoryIfCurrent(lease, { true }) { wire() } is ReportsInventoryResult.Fresh)
        var requests = 0
        assertEquals(ReportsInventoryResult.Stale, fetchReportsInventoryIfCurrent(lease, { false }) { requests++; wire() })
        assertEquals(0, requests)
    }
}
