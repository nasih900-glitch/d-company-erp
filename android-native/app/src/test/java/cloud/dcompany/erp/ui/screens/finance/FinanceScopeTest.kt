package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.MeResponse
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

    private data class Row(val branchId: String)
}
