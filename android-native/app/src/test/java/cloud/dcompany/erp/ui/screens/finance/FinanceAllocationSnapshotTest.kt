package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FinanceAllocationSnapshotTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val reconciliation = "Partner ownership shares total 0%, not 100%. " +
        "Owner reconciliation is required before profit or distribution allocations are authoritative."

    @Test
    fun `invalid ownership does not cancel sibling finance reads`() = runBlocking {
        coroutineScope {
            val allocation = async {
                fetchFinanceAllocation { throw invalidOwnership() }
            }
            val pnl = async { yield(); summary() }
            val ledger = async { yield(); 76_700L to 76_700L }
            assertNull(allocation.await().report)
            assertEquals(reconciliation, allocation.await().unavailableReason)
            assertEquals(37_700L, pnl.await().revenueMinor)
            assertEquals(ledger.await().first, ledger.await().second)
        }
    }

    @Test
    fun `unavailable cache replaces valid allocation and survives restart without zero shares`() = runBlocking {
        val key = FinanceCacheScope("company", "shop", true).key(FinanceSnapshotKeys.DISTRIBUTABLE)
        val cache = mutableMapOf(key to json.encodeToString(FinanceAllocationSnapshot(report = report())))
        val unavailable = fetchFinanceAllocation { throw invalidOwnership() }
        cache[key] = json.encodeToString(unavailable)

        val restarted = json.decodeFromString<FinanceAllocationSnapshot>(cache.getValue(key))
        assertNull(restarted.report)
        assertEquals(reconciliation, restarted.unavailableReason)
        assertTrue(!cache.getValue(key).contains("safe_to_distribute_minor"))

        val state = FinanceUiState(
            loading = false,
            pl = summary(),
            companyWidePartnerDataAvailable = true,
            distributable = restarted.report,
            allocationUnavailableReason = restarted.unavailableReason,
        )
        assertEquals(FinancePrimaryContentState.DATA, state.primaryContentState)
        assertEquals(37_700L, state.pl?.revenueMinor)
        assertEquals(reconciliation, state.allocationWarning)
    }

    @Test
    fun `successful reconciliation replaces unavailable snapshot with server report`() = runBlocking {
        val fresh = fetchFinanceAllocation { report() }
        val restarted = json.decodeFromString<FinanceAllocationSnapshot>(json.encodeToString(fresh))
        assertEquals(report(), restarted.report)
        assertNull(restarted.unavailableReason)
        assertNull(
            FinanceUiState(
                loading = false,
                pl = summary(),
                companyWidePartnerDataAvailable = true,
                distributable = restarted.report,
            ).allocationWarning,
        )
    }

    @Test
    fun `auth scope server unrelated validation decoder and cancellation failures propagate`() = runBlocking {
        val failures = listOf(
            ApiException(reconciliation, 401, "business_rule"),
            ApiException(reconciliation, 403, "business_rule"),
            ApiException(reconciliation, 500, "business_rule"),
            ApiException(reconciliation, 422, "validation_error"),
            ApiException("Branch scope does not match", 422, "business_rule"),
            IllegalStateException("Scope changed"),
            IllegalArgumentException("Invalid response"),
            IOException("Connection interrupted"),
            CancellationException("Cancelled"),
        )
        failures.forEach { expected ->
            try {
                fetchFinanceAllocation { throw expected }
                fail("Expected ${expected.javaClass.simpleName} to propagate")
            } catch (actual: Exception) {
                assertSame(expected, actual)
            }
        }
    }

    @Test
    fun `older allocation cannot look current beside a newer summary`() {
        val snapshot = FinanceAllocationSnapshot(report = report())
        assertEquals(report(), snapshot.reportForSummary(100, 100))
        assertNull(snapshot.reportForSummary(100, 200))
        assertNull(snapshot.reportForSummary(null, 100))
        assertNull(snapshot.reportForSummary(100, null))
        assertNull(FinanceAllocationSnapshot(unavailableReason = reconciliation).reportForSummary(100, 100))
    }

    @Test
    fun `legacy bare cache and contradictory snapshots fail closed`() {
        listOf(
            json.encodeToString(report()),
            "{}",
        ).forEach { body ->
            try {
                json.decodeFromString<FinanceAllocationSnapshot>(body)
                fail("Unverified legacy allocation was treated as current")
            } catch (_: IllegalArgumentException) {
                // No old report is reused without a successful server refresh.
            }
        }
        try {
            FinanceAllocationSnapshot(report(), reconciliation)
            fail("A report cannot remain available alongside its invalidation")
        } catch (_: IllegalArgumentException) {
            // The cache has exactly one authoritative state.
        }
        assertEquals(
            FINANCE_ALLOCATION_NOT_VERIFIED,
            FinanceUiState(pl = summary(), companyWidePartnerDataAvailable = true).allocationWarning,
        )
        assertNull(FinanceUiState(pl = summary()).allocationWarning)
    }

    private fun invalidOwnership() = ApiException(reconciliation, 422, "business_rule")

    private fun summary() = ProfitAndLoss(
        periodStart = "2026-09-01", periodEnd = "2026-09-05",
        revenueMinor = 37_700, cogsMinor = 0, grossProfitMinor = 37_700,
        expensesMinor = 0, depreciationMinor = 0, netProfitMinor = 37_700,
    )

    private fun report() = DistributableProfit(
        asOf = "2026-09-05", lifetimeNetProfitMinor = 37_700,
        lifetimeDepreciationMinor = 0, lifetimeWithdrawnMinor = 0,
        reserveMonths = 6, avgMonthlyCostMinor = 0, reserveMinor = 0,
        liquidCashMinor = 70_000, profitBasedCapacityMinor = 37_700,
        cashBasedCapacityMinor = 70_000, safeToDistributeMinor = 37_700,
    )
}
