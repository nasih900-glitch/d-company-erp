package cloud.dcompany.erp.ui.screens.analytics

import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class DashboardKpis(
    @Required @SerialName("branch_id") val branchId: String = "",
    val date: String = "",
    @SerialName("revenue_food_minor") val revenueFoodMinor: Long = 0,
    @SerialName("revenue_gaming_minor") val revenueGamingMinor: Long = 0,
    @SerialName("revenue_hookah_minor") val revenueHookahMinor: Long = 0,
    @SerialName("revenue_events_minor") val revenueEventsMinor: Long = 0,
    @SerialName("revenue_memberships_minor") val revenueMembershipsMinor: Long = 0,
    @SerialName("revenue_manual_collections_minor") val revenueManualCollectionsMinor: Long = 0,
    @SerialName("discounts_and_points_redeemed_minor") val discountsAndPointsRedeemedMinor: Long = 0,
    @SerialName("revenue_total_minor") val revenueTotalMinor: Long = 0,
    @SerialName("orders_count") val ordersCount: Int = 0,
    @SerialName("tickets_count") val ticketsCount: Int = 0,
    @SerialName("avg_ticket_minor") val avgTicketMinor: Long = 0,
    @SerialName("inventory_value_minor") val inventoryValueMinor: Long = 0,
    @SerialName("low_stock_items") val lowStockItems: Int = 0,
    @SerialName("open_sessions") val openSessions: Int = 0,
    @Required @SerialName("net_revenue_minor") val netRevenueMinor: Long = 0,
    @Required @SerialName("refunds_issued_minor") val refundsIssuedMinor: Long = 0,
    @Required @SerialName("cogs_minor") val cogsMinor: Long = 0,
    @Required @SerialName("expense_total_minor") val expenseTotalMinor: Long = 0,
    @Required @SerialName("depreciation_minor") val depreciationMinor: Long = 0,
    @Required @SerialName("gross_profit_minor") val grossProfitMinor: Long = 0,
    @SerialName("net_profit_minor") val netProfitMinor: Long = 0,
    @Required
    @SerialName("unissued_paid_orders_count")
    val unissuedPaidOrdersCount: Int = 0,
) {
    val hasActivity: Boolean
        get() = ordersCount > 0 ||
            ticketsCount > 0 ||
            revenueTotalMinor != 0L ||
            netRevenueMinor != 0L ||
            refundsIssuedMinor != 0L ||
            cogsMinor != 0L ||
            expenseTotalMinor != 0L ||
            depreciationMinor != 0L ||
            grossProfitMinor != 0L ||
            netProfitMinor != 0L ||
            unissuedPaidOrdersCount > 0

    /** Revenue-by-stream rows for the mini bar chart, biggest first, zero streams dropped. */
    val revenueStreams: List<Pair<String, Long>>
        get() = listOf(
            "Food" to revenueFoodMinor,
            "Gaming" to revenueGamingMinor,
            "Hookah" to revenueHookahMinor,
            "Events" to revenueEventsMinor,
            "Memberships" to revenueMembershipsMinor,
            "Manual" to revenueManualCollectionsMinor,
        ).filter { it.second > 0 }.sortedByDescending { it.second }

    /**
     * Product-profile grouping for the owner dashboard. Hidden source fields
     * remain part of the graph under a neutral legacy row; no source amount is
     * dropped merely because its operational module is dormant.
     */
    internal fun presentedRevenueStreams(
        presentation: WorkspacePresentationPolicy,
    ): List<Pair<String, Long>> {
        val legacyMinor =
            (if (presentation.showsEvents) 0L else revenueEventsMinor) +
                (if (presentation.showsMemberships) 0L else revenueMembershipsMinor)
        return buildList {
            add(
                (if (presentation.showsRestaurantOperations) "Food" else "Products / snacks / drinks") to
                    revenueFoodMinor,
            )
            add("Gaming" to revenueGamingMinor)
            add((if (presentation.showsRestaurantOperations) "Hookah" else "Shisha") to revenueHookahMinor)
            if (presentation.showsEvents) add("Events" to revenueEventsMinor)
            if (presentation.showsMemberships) add("Memberships" to revenueMembershipsMinor)
            add("Manual collections" to revenueManualCollectionsMinor)
            if (legacyMinor > 0L) add("Legacy/other revenue" to legacyMinor)
        }.filter { it.second > 0L }.sortedByDescending { it.second }
    }

    internal fun hiddenLegacyRevenueMinor(presentation: WorkspacePresentationPolicy): Long =
        (if (presentation.showsEvents) 0L else revenueEventsMinor) +
            (if (presentation.showsMemberships) 0L else revenueMembershipsMinor)
}

@Serializable
data class GrowthPeriod(
    val label: String = "",
    @SerialName("revenue_minor") val revenueMinor: Long = 0,
    @SerialName("refunds_minor") val refundsMinor: Long = 0,
    @SerialName("manual_collections_minor") val manualCollectionsMinor: Long = 0,
    @SerialName("memberships_minor") val membershipsMinor: Long = 0,
    @SerialName("orders_count") val ordersCount: Int = 0,
    @SerialName("avg_ticket_minor") val avgTicketMinor: Long = 0,
)

@Serializable
data class GrowthData(
    @Required @SerialName("branch_id") val branchId: String = "",
    val current: GrowthPeriod = GrowthPeriod(),
    val previous: GrowthPeriod = GrowthPeriod(),
    @Required @SerialName("revenue_delta_pct") val revenueDeltaPct: Double? = null,
    @Required @SerialName("orders_delta_pct") val ordersDeltaPct: Double? = null,
)

@Serializable
data class TopItem(
    @Required @SerialName("branch_id") val branchId: String = "",
    @SerialName("menu_item_id") val menuItemId: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("qty_sold") val qtySold: Double = 0.0,
    @SerialName("revenue_minor") val revenueMinor: Long = 0,
    @Required @SerialName("revenue_basis") val revenueBasis: String = "gross_line",
)

/**
 * Everything here needs only the analytics.read permission a role either has
 * or doesn't — deliberately not mixing in inventory-valuation/recipe-margin/
 * losses (inventory.read, menu.read) the way the web app's Insights tab
 * does, so this screen never partially 403s for a role that can see revenue
 * but not stock.
 */
interface AnalyticsApi {

    @GET("analytics/dashboard")
    suspend fun dashboard(@Query("on_date") onDate: String? = null): DashboardKpis

    /** period is "wow" | "mom" | "yoy" — week/month/year over previous. */
    @GET("insights/growth")
    suspend fun growth(@Query("period") period: String): GrowthData

    @GET("insights/top-items")
    suspend fun topItems(
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
        @Query("limit") limit: Int = 10,
    ): List<TopItem>
}
