package cloud.dcompany.erp.ui.screens.analytics

import cloud.dcompany.erp.core.auth.BranchScopeMismatchException
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsPresentationPolicyTest {

    @Test
    fun `gaming centre groups dormant revenue without dropping a paise`() {
        val dashboard = DashboardKpis(
            revenueFoodMinor = 1_100,
            revenueGamingMinor = 2_200,
            revenueHookahMinor = 3_300,
            revenueEventsMinor = 4_400,
            revenueMembershipsMinor = 5_500,
            revenueManualCollectionsMinor = 6_600,
        )
        val rows = dashboard.presentedRevenueStreams(
            WorkspaceFeatureProfiles.GamingCentre.presentationPolicy(),
        )

        assertEquals(23_100L, rows.sumOf { it.second })
        assertEquals(9_900L, rows.single { it.first == "Legacy/other revenue" }.second)
        assertTrue(rows.any { it.first == "Products / snacks / drinks" })
        assertTrue(rows.any { it.first == "Shisha" })
        assertFalse(rows.any { it.first.contains("membership", ignoreCase = true) })
        assertFalse(rows.any { it.first.contains("event", ignoreCase = true) })
    }

    @Test
    fun `gaming centre omits an empty legacy revenue row`() {
        val rows = DashboardKpis(revenueGamingMinor = 500).presentedRevenueStreams(
            WorkspaceFeatureProfiles.GamingCentre.presentationPolicy(),
        )

        assertEquals(listOf("Gaming" to 500L), rows)
    }

    @Test
    fun `cached dashboard is labelled stale after refresh failure`() {
        assertEquals(
            CachedDataPresentation.STALE,
            cachedDataPresentation(hasData = true, loading = false, error = "Offline"),
        )
        assertEquals(
            CachedDataPresentation.BLOCKING_ERROR,
            cachedDataPresentation(hasData = false, loading = false, error = "Offline"),
        )
    }

    @Test
    fun `top items cannot show a false empty state before a successful snapshot`() {
        assertEquals(
            SupplementalListPresentation.INITIAL_LOADING,
            supplementalListPresentation(hasSnapshot = false, isEmpty = true, error = null),
        )
        assertEquals(
            SupplementalListPresentation.BLOCKING_ERROR,
            supplementalListPresentation(hasSnapshot = false, isEmpty = true, error = "Offline"),
        )
    }

    @Test
    fun `saved empty and populated top items are labelled stale after failure`() {
        assertEquals(
            SupplementalListPresentation.STALE_EMPTY,
            supplementalListPresentation(hasSnapshot = true, isEmpty = true, error = "Offline"),
        )
        assertEquals(
            SupplementalListPresentation.STALE_CONTENT,
            supplementalListPresentation(hasSnapshot = true, isEmpty = false, error = "Offline"),
        )
        assertEquals(
            SupplementalListPresentation.FRESH_EMPTY,
            supplementalListPresentation(hasSnapshot = true, isEmpty = true, error = null),
        )
    }

    @Test
    fun `unexpected analytics failures are actionable and hide technical details`() {
        val message = analyticsLoadError(
            IllegalArgumentException("serializer internals"),
            "Could not load top items.",
        )

        assertTrue(message.contains("try again", ignoreCase = true))
        assertTrue(!message.contains("serializer internals"))
    }

    @Test
    fun `refund and cost only dashboards still contain business activity`() {
        assertTrue(DashboardKpis(refundsIssuedMinor = 500).hasActivity)
        assertTrue(DashboardKpis(cogsMinor = 500).hasActivity)
        assertTrue(DashboardKpis(expenseTotalMinor = 500).hasActivity)
        assertFalse(DashboardKpis().hasActivity)
    }

    @Test
    fun `top item scope fails closed even when the response is empty`() {
        assertThrows(BranchScopeMismatchException::class.java) {
            verifyTopItemBranches(null, emptyList())
        }
        assertThrows(BranchScopeMismatchException::class.java) {
            verifyTopItemBranches(
                "branch-a",
                listOf(TopItem(branchId = "branch-b", revenueBasis = "gross_line")),
            )
        }
    }

    @Test
    fun `analytics wire contract requires branch and accounting fields`() {
        val dashboard = ApiClient.json.decodeFromString<DashboardKpis>(
            """
            {
              "branch_id":"branch-a",
              "net_revenue_minor":0,
              "refunds_issued_minor":0,
              "cogs_minor":0,
              "expense_total_minor":0,
              "depreciation_minor":0,
              "gross_profit_minor":0,
              "unissued_paid_orders_count":0
            }
            """.trimIndent(),
        )
        assertEquals("branch-a", dashboard.branchId)

        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<DashboardKpis>(
                """{"branch_id":"branch-a"}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<GrowthData>(
                """{"revenue_delta_pct":null,"orders_delta_pct":null}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            ApiClient.json.decodeFromString<TopItem>(
                """{"branch_id":"branch-a"}""",
            )
        }
    }
}
