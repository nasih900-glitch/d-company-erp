package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** Required fields deliberately avoid the general Inventory screen's legacy defaults. */
@Serializable
internal data class ReportsInventoryWire(
    @SerialName("as_of") val asOf: String,
    @SerialName("branch_id") val branchId: String,
    val lines: List<ReportsInventoryLine>,
    @SerialName("total_valuation_minor") val totalValuationMinor: Long,
    @SerialName("low_stock_count") val lowStockCount: Int,
)

@Serializable
internal data class ReportsInventoryLine(
    @SerialName("ingredient_id") val ingredientId: String,
    val sku: String,
    val name: String,
    @SerialName("base_unit") val baseUnit: String,
    @SerialName("current_qty") val currentQty: Double,
    @SerialName("avg_cost_minor") val avgCostMinor: Long,
    @SerialName("valuation_minor") val valuationMinor: Long,
    @SerialName("reorder_threshold") val reorderThreshold: Double,
    @SerialName("is_low_stock") val isLowStock: Boolean,
)

internal data class ReportsInventorySnapshot(
    val asOf: LocalDate,
    val branchId: String,
    val lines: List<ReportsInventoryLine>,
    val totalValuationMinor: Long,
    val lowStockCount: Int,
)

internal interface ReportsInventoryApi {
    @GET("insights/inventory/valuation")
    suspend fun valuation(@Query("branch_id") branchId: String): ReportsInventoryWire
}

internal fun validateReportsInventory(wire: ReportsInventoryWire, lease: CacheScopeLease): ReportsInventorySnapshot {
    fun valid(condition: Boolean) {
        if (!condition) throw ReportsContractException("The current inventory response could not be verified.")
    }
    valid(wire.branchId == lease.scope.branchId)
    val date = runCatching { LocalDate.parse(wire.asOf) }.getOrNull()
    valid(date != null)
    valid(wire.lines.map { it.ingredientId }.distinct().size == wire.lines.size)
    wire.lines.forEach {
        valid(runCatching { UUID.fromString(it.ingredientId).toString() == it.ingredientId }.getOrDefault(false))
        valid(it.name.isNotBlank() && it.baseUnit.isNotBlank())
        valid(it.currentQty.isFinite() && it.reorderThreshold.isFinite())
    }
    // Negative shortage layers and signed valuation are legitimate; do not clamp them.
    val total = runCatching { wire.lines.fold(0L) { sum, line -> Math.addExact(sum, line.valuationMinor) } }.getOrNull()
    valid(total != null && total == wire.totalValuationMinor)
    valid(wire.lowStockCount == wire.lines.count { it.isLowStock })
    return ReportsInventorySnapshot(date!!, wire.branchId, wire.lines, wire.totalValuationMinor, wire.lowStockCount)
}

internal sealed interface ReportsInventoryResult {
    data class Fresh(val value: ReportsInventorySnapshot) : ReportsInventoryResult
    data class Failed(val message: String) : ReportsInventoryResult
    data object Denied : ReportsInventoryResult
    data object Stale : ReportsInventoryResult
}

/** No persisted stock cache; both success and denial are bound to this exact attempt. */
internal suspend fun fetchReportsInventoryIfCurrent(
    lease: CacheScopeLease,
    isCurrent: () -> Boolean,
    fetch: suspend () -> ReportsInventoryWire,
): ReportsInventoryResult {
    if (!isCurrent()) return ReportsInventoryResult.Stale
    return try {
        val wire = fetch()
        if (!isCurrent()) ReportsInventoryResult.Stale
        else ReportsInventoryResult.Fresh(validateReportsInventory(wire, lease))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        when {
            !isCurrent() -> ReportsInventoryResult.Stale
            error is ApiException && error.status in listOf(401, 403) -> ReportsInventoryResult.Denied
            else -> ReportsInventoryResult.Failed(reportLoadError(error))
        }
    }
}

internal fun Double.asReportsStockQuantity(): String = java.math.BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
