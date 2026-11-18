package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.ShiftHistoryMergePolicy
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.ui.screens.shift.ShiftCloseResult
import cloud.dcompany.erp.ui.screens.shift.ShiftDetail
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftAccountingContractTest {
    @Test fun `effective correction totals replace display once while drawer close remains original`() {
        val original = ShiftDetail("shift", "branch", "terminal", "closed", "2026-11-07T08:00:00Z",
            closedAt = "2026-11-07T20:00:00Z", expectedMinor = 50000, countedMinor = 50000, varianceMinor = 0,
            grossCollectionsMinor = 18000, upiCollectionsMinor = 18000, netCollectionsMinor = 18000,
            receiptCorrectionsMinor = 8000, effectiveNetCollectionsMinor = 10000, effectiveUpiCollectionsMinor = 10000)
        val wire = ApiClient.json.decodeFromString<ShiftDetail>(ApiClient.json.encodeToString(ShiftDetail.serializer(), original))
        val cached = wire.toShiftHistoryCache("terminal", 10, 20, 30)
        val row = ShiftHistoryMergePolicy.merge(listOf(cached), emptyList()).single()
        assertEquals(10000L, row.netCollectionsMinor); assertEquals(10000L, row.upiCollectionsMinor)
        assertEquals(18000L, row.grossCollectionsMinor)
        assertEquals(50000L, row.expectedMinor); assertEquals(50000L, row.countedMinor); assertEquals(0L, row.varianceMinor)
        assertEquals(20L, row.closedAtMillis)
        val open = wire.copy(status = "open").toServerOpenShiftCache("terminal", 10, 30)
        assertEquals(10000L, open.netCollectionsMinor); assertEquals(10000L, open.upiCollectionsMinor)
        assertEquals(50000L, open.expectedMinor)
        val absent = original.copy(receiptCorrectionsMinor = null, effectiveNetCollectionsMinor = null, effectiveUpiCollectionsMinor = null)
            .toShiftHistoryCache("terminal", 10, 20, 30)
        assertEquals(18000L, absent.netCollectionsMinor); assertEquals(18000L, absent.upiCollectionsMinor)
        val incomplete = original.copy(effectiveNetCollectionsMinor = null, effectiveUpiCollectionsMinor = null)
            .toShiftHistoryCache("terminal", 10, 20, 30)
        assertNull(incomplete.netCollectionsMinor); assertNull(incomplete.upiCollectionsMinor)
        assertEquals(50000L, incomplete.countedMinor)
    }


    @Test
    fun `close response identifies a different authorised closer without rewriting opener`() {
        val result = ApiClient.json.decodeFromString<ShiftCloseResult>(
            """
            {
              "id": "shift-1",
              "status": "closed",
              "variance_minor": 0,
              "opened_by": "user-rafi",
              "closed_by": "user-sameer",
              "closed_by_was_opener": false
            }
            """.trimIndent(),
        )

        assertEquals("user-rafi", result.openedByUserId)
        assertEquals("user-sameer", result.closedByUserId)
        assertEquals(false, result.closedByWasOpener)
    }

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
              "opened_by_name": "Rafi",
              "closed_by": "user-2",
              "closed_by_name": "Sameer",
              "closed_by_email": "sameer@example.test"
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
        assertEquals("user-2", historyCache.closedByUserId)
        assertEquals("Sameer", historyCache.closedByName)
        assertEquals("sameer@example.test", historyCache.closedByEmail)
        assertNull(historyCache.grossCollectionsMinor)
        assertNull(historyCache.netCollectionsMinor)
        val historyUiRow = ShiftHistoryMergePolicy.merge(listOf(historyCache), emptyList()).single()
        assertNull(historyUiRow.grossCollectionsMinor)
        assertNull(historyUiRow.netCollectionsMinor)
        assertEquals("Sameer", historyUiRow.closedByName)
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
              "opened_by_name": "Rafi",
              "opening_client_platform": "android",
              "opening_client_installation_id": "b7ee1c68-77e2-48e8-82ac-0d4623606ec1"
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
        assertEquals("android", openCache.openingClientPlatform)
        assertEquals(
            "b7ee1c68-77e2-48e8-82ac-0d4623606ec1",
            openCache.openingClientInstallationId,
        )
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
