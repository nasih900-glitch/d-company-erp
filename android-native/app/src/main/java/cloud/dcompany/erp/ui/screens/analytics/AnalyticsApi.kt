package cloud.dcompany.erp.ui.screens.analytics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

@Serializable
data class DashboardKpis(
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
    @SerialName("net_profit_minor") val netProfitMinor: Long = 0,
) {
    val hasActivity: Boolean get() = ordersCount > 0 || ticketsCount > 0 || revenueTotalMinor > 0

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
    val current: GrowthPeriod = GrowthPeriod(),
    val previous: GrowthPeriod = GrowthPeriod(),
    @SerialName("revenue_delta_pct") val revenueDeltaPct: Double = 0.0,
    @SerialName("orders_delta_pct") val ordersDeltaPct: Double = 0.0,
)

@Serializable
data class TopItem(
    @SerialName("menu_item_id") val menuItemId: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("qty_sold") val qtySold: Double = 0.0,
    @SerialName("revenue_minor") val revenueMinor: Long = 0,
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
