package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.CostingCoverage
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancePresentationPolicyTest {

    @Test
    fun `gaming centre business metrics contain no membership or customer workspace copy`() {
        val metrics = BusinessMetrics(
            periodStart = "2026-08-01",
            periodEnd = "2026-08-29",
            aovMinor = 12_500,
            ordersCount = 8,
            mrrMinor = 2_000,
            arrMinor = 24_000,
            activeMembersCount = 3,
            cacMinor = 500,
            newCustomersCount = 2,
            marketingSpendMinor = 1_000,
            ltvMinor = 50_000,
            customersCount = 4,
            burnRateMinor = 2_500,
        )

        val presented = metrics.presentedMetrics(
            WorkspaceFeatureProfiles.GamingCentre.presentationPolicy(),
        )
        val copy = presented.joinToString(" ") { "${it.label} ${it.detail}" }

        assertEquals(4, presented.size)
        assertFalse(copy.contains("membership", ignoreCase = true))
        assertFalse(copy.contains("customer", ignoreCase = true))
        assertTrue(copy.contains("gaming", ignoreCase = true))
        assertTrue(presented.any { it.value == "₹125.00" })
    }

    @Test
    fun `focused asset category neutralises legacy kitchen equipment without deleting it`() {
        val presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()

        assertEquals("Legacy equipment", assetCategoryLabel("kitchen_equipment", presentation))
        assertEquals("Gaming", assetCategoryLabel("gaming", presentation))
    }

    private val completeCosting = CostingCoverage(
        inventoryItemCount = 1,
        fullyCostedItemCount = 1,
        incompleteItemCount = 0,
        isComplete = true,
    )

    @Test
    fun spendableFundsCopyExcludesEveryProviderClearingRail() {
        assertTrue(SPENDABLE_FUNDS_LABEL.contains("till + bank"))
        assertTrue(SPENDABLE_FUNDS_DETAIL.contains("Cash and Bank"))
        assertTrue(SPENDABLE_FUNDS_DETAIL.contains("Card"))
        assertTrue(SPENDABLE_FUNDS_DETAIL.contains("UPI/QR"))
        assertTrue(SPENDABLE_FUNDS_DETAIL.contains("Wallet"))
        assertTrue(SPENDABLE_FUNDS_DETAIL.contains("settles them to Bank"))
        assertFalse(SPENDABLE_FUNDS_LABEL.contains("UPI"))
        assertTrue(CASH_CONTRACT_UNAVAILABLE.contains("unverified"))
        assertTrue(CASH_CONTRACT_UNAVAILABLE.contains("do not"))
    }

    @Test
    fun loadFailureDistinguishesOfflineFirstLoadFromSavedData() {
        val firstOffline = financeLoadFailureMessage(hasSavedFigures = false, online = false)
        assertTrue(firstOffline.contains("has not loaded"))
        assertTrue(firstOffline.contains("Reconnect"))

        val cachedOffline = financeLoadFailureMessage(hasSavedFigures = true, online = false)
        assertTrue(cachedOffline.contains("Saved figures remain visible"))
        assertTrue(cachedOffline.contains("reconnection"))

        val firstOnlineFailure = financeLoadFailureMessage(hasSavedFigures = false, online = true)
        assertTrue(firstOnlineFailure.contains("Could not load"))
        assertTrue(firstOnlineFailure.contains("access and the connection"))
    }

    @Test
    fun queuedWriteFeedbackIsExplicitOnlineAndOfflineAndPreventsDuplicateEntry() {
        val online = financeWriteQueuedMessage("Expense", online = true)
        assertTrue(online.contains("saved on this tablet"))
        assertTrue(online.contains("Syncing with the server now"))
        assertTrue(online.contains("do not enter it again"))

        val offline = financeWriteQueuedMessage("Capital entry", online = false)
        assertTrue(offline.contains("connection returns"))
        assertTrue(offline.contains("do not enter it again"))
    }

    @Test
    fun costingCompletenessMustBelongToTheSameRefreshAsDisplayedFigures() {
        val current = FinanceUiState(
            lastUpdatedAtMillis = 2_000,
            costingCoverageUpdatedAtMillis = 2_000,
            costingCoverage = completeCosting,
        )
        assertTrue(current.verifiedCostingCoverage?.isComplete == true)

        val stale = current.copy(
            lastUpdatedAtMillis = 3_000,
            costingCoverageUpdatedAtMillis = 2_000,
        )
        assertTrue(stale.verifiedCostingCoverage == null)

        val missingTimestamp = current.copy(costingCoverageUpdatedAtMillis = null)
        assertTrue(missingTimestamp.verifiedCostingCoverage == null)
    }

    @Test
    fun incompleteCostingExplainsTheAccountingDirectionCorrectly() {
        val incomplete = completeCosting.copy(
            fullyCostedItemCount = 0,
            incompleteItemCount = 1,
            isComplete = false,
        )

        assertTrue(incomplete.warningDetail.contains("COGS may be understated"))
        assertTrue(incomplete.warningDetail.contains("profit may be overstated"))
        assertFalse(incomplete.warningDetail.contains("profit may be understated"))
    }

    @Test
    fun collectionAndTipTotalsExcludeVoidedEvidence() {
        fun collection(id: String, method: String, amount: Long, voided: Boolean) =
            ManualCollection(
                id = id,
                companyId = "company",
                branchId = "shop",
                businessDate = "2026-08-28",
                method = method,
                amountMinor = amount,
                sourceKind = "manual_daily",
                sourceRef = "ref-$id",
                idempotencyKey = "key-$id",
                createdBy = "user",
                createdAt = "2026-08-28T12:00:00Z",
                isVoided = voided,
            )
        val totals = manualCollectionTotals(
            listOf(
                collection("cash", "cash", 21_000, false),
                collection("upi", "upi", 162_000, false),
                collection("void", "cash", 99_000, true),
            ),
        )

        assertTrue(totals.totalMinor == 183_000L)
        assertTrue(totals.cashMinor == 21_000L)
        assertTrue(totals.upiMinor == 162_000L)
        assertTrue(totals.activeCount == 2)
        assertTrue(totals.voidedCount == 1)
        assertTrue(
            defaultManualCollectionReference("2026-08-28", "upi") ==
                "Daily collection 2026-08-28 UPI",
        )
    }
}
