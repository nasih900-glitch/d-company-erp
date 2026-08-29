package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.ShiftHistoryMergePolicy
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftAccountingContractTest {

    @Test
    fun `legacy response keeps unavailable accounting totals null through caches and UI model`() {
        val detail = ApiClient.json.decodeFromString<ShiftDetail>(
            """
            {
              "id": "shift-legacy",
              "branch_id": "branch-1",
              "terminal_id": "terminal-1",
              "status": "open",
              "opened_at": "2026-08-25T08:00:00Z",
              "opening_float_minor": 50000,
              "expected_minor": 90000,
              "pos_sales_minor": 83600,
              "total_sales_minor": 83600,
              "opened_by": "user-1",
              "opened_by_name": "Rafi"
            }
            """.trimIndent(),
        )

        assertNull(detail.membershipSalesMinor)
        assertNull(detail.grossCollectionsMinor)
        assertNull(detail.settledPosRefundsMinor)
        assertNull(detail.settledMembershipRefundsMinor)
        assertNull(detail.totalRefundsMinor)
        assertNull(detail.netCollectionsMinor)

        val openCache = detail.toServerOpenShiftCache(
            terminalId = "terminal-1",
            openedAtMillis = 1_000,
            verifiedAtMillis = 2_000,
        )
        assertEquals(83_600L, openCache.posCollectionsMinor)
        assertNull(openCache.grossCollectionsMinor)
        assertNull(openCache.netCollectionsMinor)
        val resolved = requireNotNull(ShiftResolutionPolicy.resolve(local = null, server = openCache))
        assertNull(resolved.accountingBreakdownOrNull())

        val historyCache = detail.toShiftHistoryCache(
            terminalId = "terminal-1",
            openedAtMillis = 1_000,
            closedAtMillis = null,
            fetchedAtMillis = 2_000,
        )
        assertEquals(83_600L, historyCache.posSalesMinor)
        assertNull(historyCache.grossCollectionsMinor)
        assertNull(historyCache.netCollectionsMinor)
        val historyUiRow = ShiftHistoryMergePolicy.merge(listOf(historyCache), emptyList()).single()
        assertNull(historyUiRow.grossCollectionsMinor)
        assertNull(historyUiRow.netCollectionsMinor)
    }

    @Test
    fun `complete response keeps exact accounting breakdown available`() {
        val detail = ApiClient.json.decodeFromString<ShiftDetail>(
            """
            {
              "id": "shift-current",
              "branch_id": "branch-1",
              "terminal_id": "terminal-1",
              "status": "open",
              "opened_at": "2026-08-25T08:00:00Z",
              "opening_float_minor": 50000,
              "expected_minor": 90000,
              "pos_sales_minor": 83600,
              "membership_sales_minor": 10000,
              "gross_collections_minor": 93600,
              "cash_collections_minor": 40000,
              "card_collections_minor": 23600,
              "upi_collections_minor": 30000,
              "other_collections_minor": 0,
              "settled_pos_refunds_minor": 3600,
              "settled_membership_refunds_minor": 1000,
              "total_refunds_minor": 4600,
              "net_collections_minor": 89000,
              "total_sales_minor": 83600,
              "opened_by": "user-1",
              "opened_by_name": "Rafi"
            }
            """.trimIndent(),
        )
        val openCache = detail.toServerOpenShiftCache(
            terminalId = "terminal-1",
            openedAtMillis = 1_000,
            verifiedAtMillis = 2_000,
        )
        val resolved = requireNotNull(ShiftResolutionPolicy.resolve(local = null, server = openCache))
        val accounting = resolved.accountingBreakdownOrNull()

        assertNotNull(accounting)
        assertEquals(83_600L, accounting!!.posCollectionsMinor)
        assertEquals(10_000L, accounting.membershipCollectionsMinor)
        assertEquals(93_600L, accounting.grossCollectionsMinor)
        assertEquals(40_000L, accounting.cashCollectionsMinor)
        assertEquals(23_600L, accounting.cardCollectionsMinor)
        assertEquals(30_000L, accounting.upiCollectionsMinor)
        assertEquals(0L, accounting.otherCollectionsMinor)
        assertEquals(3_600L, accounting.settledPosRefundsMinor)
        assertEquals(1_000L, accounting.settledMembershipRefundsMinor)
        assertEquals(4_600L, accounting.totalRefundsMinor)
        assertEquals(89_000L, accounting.netCollectionsMinor)

        val historyCache = detail.toShiftHistoryCache(
            terminalId = "terminal-1",
            openedAtMillis = 1_000,
            closedAtMillis = null,
            fetchedAtMillis = 2_000,
        )
        assertEquals(93_600L, historyCache.grossCollectionsMinor)
        assertEquals(40_000L, historyCache.cashCollectionsMinor)
        assertEquals(23_600L, historyCache.cardCollectionsMinor)
        assertEquals(30_000L, historyCache.upiCollectionsMinor)
        assertEquals(0L, historyCache.otherCollectionsMinor)
        assertEquals(89_000L, historyCache.netCollectionsMinor)
    }

    @Test
    fun `partial response cannot render a mixed inferred breakdown`() {
        val partial = ShiftDetail(
            id = "shift-partial",
            branchId = "branch-1",
            terminalId = "terminal-1",
            status = "open",
            openedAt = "2026-08-25T08:00:00Z",
            posSalesMinor = 83_600,
            membershipSalesMinor = 10_000,
            grossCollectionsMinor = 93_600,
            // Refund fields are absent on this hypothetical mixed deployment.
        )
        val resolved = requireNotNull(
            ShiftResolutionPolicy.resolve(
                local = null,
                server = partial.toServerOpenShiftCache(
                    terminalId = "terminal-1",
                    openedAtMillis = 1_000,
                    verifiedAtMillis = 2_000,
                ),
            ),
        )

        assertNull(resolved.accountingBreakdownOrNull())
    }
}
