package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceScopeTest {

    @Test
    fun snapshotKeysCannotCrossCompanyOrAssignedBranch() {
        val companyAdmin = scope(company = "company-a", branch = null)
        val branchA = scope(company = "company-a", branch = "branch-a")
        val branchB = scope(company = "company-a", branch = "branch-b")
        val otherCompany = scope(company = "company-b", branch = null)

        val keys = listOf(companyAdmin, branchA, branchB, otherCompany)
            .map { it.key("finance_pnl") }

        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(branchA.key("finance_partners"), branchB.key("finance_partners"))
    }

    @Test
    fun companyWidePartnerSnapshotsCannotCrossIntoManagerCacheAtSameBranch() {
        val owner = FinanceCacheScope.from(
            profile("company-a", "branch-a", roles = listOf("owner")),
        )!!
        val manager = FinanceCacheScope.from(
            profile("company-a", "branch-a", roles = listOf("manager")),
        )!!

        assertTrue(owner.companyWidePartnerFinance)
        assertTrue(!manager.companyWidePartnerFinance)
        assertNotEquals(owner.key("finance_partners"), manager.key("finance_partners"))
    }

    @Test
    fun ownerRefreshUsesTheExactScopeObservedByTheFinanceScreen() {
        val profile = profile("company-a", "branch-a", roles = listOf("owner"))
        val observed = FinanceCacheScope.from(profile)!!
        val formerRestrictedDefault = FinanceCacheScope(
            companyId = "company-a",
            branchId = "branch-a",
        )
        val refreshed = financeCacheScopeForLease(
            profile = profile,
            leaseCompanyId = "company-a",
            leaseBranchId = "branch-a",
        )!!

        assertEquals(observed, refreshed)
        assertTrue(refreshed.companyWidePartnerFinance)
        assertNotEquals(
            observed.key(FinanceSnapshotKeys.PNL),
            formerRestrictedDefault.key(FinanceSnapshotKeys.PNL),
        )
        assertEquals(
            observed.key(FinanceSnapshotKeys.PNL),
            refreshed.key(FinanceSnapshotKeys.PNL),
        )
    }

    @Test
    fun financeRefreshRejectsAProfileThatDoesNotMatchTheActiveCacheLease() {
        assertNull(
            financeCacheScopeForLease(
                profile = profile("company-b", "branch-a", roles = listOf("owner")),
                leaseCompanyId = "company-a",
                leaseBranchId = "branch-a",
            ),
        )
    }

    @Test
    fun completedFinanceLoadCanNeverRemainInTheLoadingPresentation() {
        val failed = FinanceUiState(
            loading = false,
            online = true,
            error = financeLoadCompletionError(
                result = ResourceRefreshResult.Refreshed("finance"),
                hasSavedFigures = false,
                online = true,
            ),
        )

        assertEquals(FinancePrimaryContentState.ERROR, failed.primaryContentState)
        assertTrue(!failed.error.isNullOrBlank())
        assertEquals(
            FinancePrimaryContentState.LOADING,
            FinanceUiState(loading = true).primaryContentState,
        )
        assertEquals(
            FinancePrimaryContentState.DATA,
            FinanceUiState(loading = true, pl = emptyProfitAndLoss()).primaryContentState,
        )
    }

    @Test
    fun onlyASummaryCommittedAfterTheCurrentFailureCanClearIt() {
        val currentFailure = FinanceLoadFailure(
            message = "Refresh B failed",
            raisedAtMillis = 3_000,
        )

        assertEquals(
            currentFailure,
            financeLoadFailureAfterSummaryDelivery(
                currentFailure = currentFailure,
                incomingFetchedAtMillis = 2_000,
                hasDecodedSummary = true,
            ),
        )
        assertEquals(
            currentFailure,
            financeLoadFailureAfterSummaryDelivery(
                currentFailure = currentFailure,
                incomingFetchedAtMillis = 3_000,
                hasDecodedSummary = true,
            ),
        )
        assertNull(
            financeLoadFailureAfterSummaryDelivery(
                currentFailure = currentFailure,
                incomingFetchedAtMillis = 3_001,
                hasDecodedSummary = true,
            ),
        )
    }

    @Test
    fun missingOrUndecodableSnapshotCannotHideARealLoadFailure() {
        val currentFailure = FinanceLoadFailure(
            message = "Finance took too long to refresh",
            raisedAtMillis = 1_000,
        )

        assertEquals(
            currentFailure,
            financeLoadFailureAfterSummaryDelivery(
                currentFailure = currentFailure,
                incomingFetchedAtMillis = null,
                hasDecodedSummary = true,
            ),
        )
        assertEquals(
            currentFailure,
            financeLoadFailureAfterSummaryDelivery(
                currentFailure = currentFailure,
                incomingFetchedAtMillis = 2_000,
                hasDecodedSummary = false,
            ),
        )
        assertNull(
            financeLoadFailureAfterSummaryDelivery(
                currentFailure = null,
                incomingFetchedAtMillis = 2_000,
                hasDecodedSummary = true,
            ),
        )
    }

    @Test
    fun rowCacheFailsClosedUntilScopeMarkerIsVerified() {
        val rows = listOf(Row("branch-a"), Row("branch-b"))

        assertTrue(visibleFinanceRows(rows, null, true, Row::branchId).isEmpty())
        assertTrue(
            visibleFinanceRows(rows, scope(branch = "branch-a"), false, Row::branchId).isEmpty(),
        )
    }

    @Test
    fun branchAssignmentFiltersRowsWhileCompanyAdminKeepsHistory() {
        val rows = listOf(Row("branch-a"), Row("deleted-historical-branch"))

        assertEquals(
            listOf(Row("branch-a")),
            visibleFinanceRows(rows, scope(branch = "branch-a"), true, Row::branchId),
        )
        assertEquals(
            rows,
            visibleFinanceRows(rows, scope(branch = null), true, Row::branchId),
        )
    }

    @Test
    fun malformedProfileCannotCreateACacheScope() {
        assertNull(
            FinanceCacheScope.from(
                profile(company = "   ", branch = "branch-a"),
            ),
        )
    }

    @Test
    fun periodWithMembershipIncomeOrDepreciationIsNotCalledIdle() {
        val base = ProfitAndLoss(
            periodStart = "2026-08-01",
            periodEnd = "2026-08-31",
            revenueMinor = 0,
            cogsMinor = 0,
            grossProfitMinor = 0,
            expensesMinor = 0,
            depreciationMinor = 0,
            netProfitMinor = 0,
        )

        assertTrue(FinanceUiState(pl = base).periodIdle)
        assertTrue(!FinanceUiState(pl = base.copy(membershipsMinor = 100)).periodIdle)
        assertTrue(!FinanceUiState(pl = base.copy(depreciationMinor = 100, netProfitMinor = -100)).periodIdle)
    }

    private fun scope(
        company: String = "company-a",
        branch: String? = null,
    ): FinanceCacheScope = FinanceCacheScope.from(profile(company, branch))!!

    private fun profile(
        company: String,
        branch: String?,
        roles: List<String> = listOf("owner"),
    ) = MeResponse(
        userId = "user-a",
        email = "owner@example.com",
        name = "Owner",
        roles = roles,
        companyId = company,
        branchId = branch,
    )

    private fun emptyProfitAndLoss() = ProfitAndLoss(
        periodStart = "2026-08-01",
        periodEnd = "2026-08-31",
        revenueMinor = 0,
        cogsMinor = 0,
        grossProfitMinor = 0,
        expensesMinor = 0,
        depreciationMinor = 0,
        netProfitMinor = 0,
    )

    private data class Row(val branchId: String)
}
