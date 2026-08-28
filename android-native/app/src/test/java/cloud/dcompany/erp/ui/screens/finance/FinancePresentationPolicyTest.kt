package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.CostingCoverage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancePresentationPolicyTest {

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
}
