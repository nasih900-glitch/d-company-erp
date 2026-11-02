package cloud.dcompany.erp.ui.screens.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET

@Serializable
internal data class HomeMetricWire(
    @SerialName("current") val current: Long,
    @SerialName("previous") val previous: Long,
    // JsonElement is deliberate: ApiClient uses explicitNulls=false, so a
    // nullable Kotlin property would silently accept an omitted contract key.
    @SerialName("delta_pct") val deltaPct: JsonElement,
    @SerialName("comparison_status") val comparisonStatus: String,
)

@Serializable
internal data class HomeCustomersWire(
    @SerialName("current") val current: Long,
    @SerialName("scope") val scope: String,
    @SerialName("comparison_status") val comparisonStatus: String,
)

@Serializable
internal data class HomeGamingWire(
    @SerialName("active_station_capacity") val activeStationCapacity: Long,
    @SerialName("open_sessions") val openSessions: Long,
)

@Serializable
internal data class HomeHourPointWire(
    @SerialName("hour") val hour: Int,
    @SerialName("revenue_minor") val revenueMinor: Long,
    @SerialName("orders_count") val ordersCount: Long,
)

@Serializable
internal data class HomeLocalHourProfileWire(
    @SerialName("timezone") val timezone: String,
    @SerialName("business_date") val businessDate: String,
    @SerialName("day_length_minutes") val dayLengthMinutes: Int,
    @SerialName("basis") val basis: String,
    @SerialName("reconciles_to_daily_gross") val reconcilesToDailyGross: Boolean,
    @SerialName("total_revenue_minor") val totalRevenueMinor: Long,
    @SerialName("total_orders_count") val totalOrdersCount: Long,
    @SerialName("points") val points: List<HomeHourPointWire>,
)

@Serializable
internal data class HomeActivityWire(
    @SerialName("kind") val kind: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("display_reference") val displayReference: String,
    @SerialName("title") val title: String,
    @SerialName("amount_minor") val amountMinor: JsonElement,
    @SerialName("scope") val scope: String,
)

@Serializable
internal data class HomeActivityCapabilitiesWire(
    @SerialName("pos") val pos: String,
    @SerialName("gaming") val gaming: String,
)

@Serializable
internal data class HomeReadModelWire(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("branch_id") val branchId: String,
    @SerialName("terminal_id") val terminalId: JsonElement,
    @SerialName("business_date") val businessDate: String,
    @SerialName("timezone") val timezone: String,
    @SerialName("snapshot_at") val snapshotAt: String,
    @SerialName("revenue") val revenue: HomeMetricWire,
    @SerialName("orders") val orders: HomeMetricWire,
    @SerialName("customers") val customers: HomeCustomersWire,
    @SerialName("gaming") val gaming: HomeGamingWire,
    @SerialName("local_hour_profile") val localHourProfile: HomeLocalHourProfileWire,
    @SerialName("recent_activity") val recentActivity: List<HomeActivityWire>,
    @SerialName("activity_capabilities") val activityCapabilities: HomeActivityCapabilitiesWire,
)

internal interface HomeApi {
    @GET("analytics/home")
    suspend fun home(): HomeReadModelWire
}
