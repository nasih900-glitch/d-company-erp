package cloud.dcompany.erp.ui.screens.inventory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Field names are copied verbatim from backend/app/api/v1/inventory/router.py
 * (IngredientRead, SupplierRead, BatchRead, GrnPost, StockAdjustment) and
 * cross-checked against frontend/src/lib/erp-api.ts. A wrong name here fails at
 * runtime, not at compile time, so nothing is guessed.
 *
 * Quantities are genuinely floating point on the backend (`current_qty: float`)
 * because 12.5 ml of syrup is a real measurement. Money never is: every
 * `*_minor` field is an integer count of paise and stays a Long the whole way.
 */

@Serializable
data class Ingredient(
    val id: String,
    val sku: String,
    val name: String,
    @SerialName("base_unit") val baseUnit: String,
    @SerialName("current_qty") val currentQty: Double = 0.0,
    @SerialName("reorder_threshold") val reorderThreshold: Double = 0.0,
    @SerialName("reorder_qty") val reorderQty: Double = 0.0,
    @SerialName("avg_cost_minor") val avgCostMinor: Long = 0,
)

/** Below the reorder line — the same test the web screen uses for its alerts. */
val Ingredient.isLow: Boolean get() = currentQty < reorderThreshold

/**
 * Stock value in paise. The quantity is a Double because it has to be, but the
 * result is rounded back to whole paise immediately so no fractional money can
 * travel any further.
 */
val Ingredient.stockValueMinor: Long get() = (currentQty * avgCostMinor).roundToLong()

@Serializable
data class IngredientCreate(
    val sku: String,
    val name: String,
    @SerialName("base_unit") val baseUnit: String,
    @SerialName("reorder_threshold") val reorderThreshold: Double,
    @SerialName("reorder_qty") val reorderQty: Double,
)

@Serializable
data class IngredientUpdate(
    val name: String,
    @SerialName("base_unit") val baseUnit: String,
    @SerialName("reorder_threshold") val reorderThreshold: Double,
    @SerialName("reorder_qty") val reorderQty: Double,
)

@Serializable
data class Supplier(
    val id: String,
    val name: String,
    val contact: String? = null,
    val gstin: String? = null,
    @SerialName("payment_terms") val paymentTerms: String? = null,
)

/** Create and update take the same shape; the backend treats absent as unchanged. */
@Serializable
data class SupplierBody(
    val name: String,
    val contact: String? = null,
    val gstin: String? = null,
    @SerialName("payment_terms") val paymentTerms: String? = null,
)

@Serializable
data class Batch(
    val id: String,
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("qty_on_hand") val qtyOnHand: Double = 0.0,
    @SerialName("cost_per_unit_minor") val costPerUnitMinor: Long = 0,
    @SerialName("lot_code") val lotCode: String? = null,
)

@Serializable
data class GrnLineBody(
    @SerialName("ingredient_id") val ingredientId: String,
    val qty: Double,
    @SerialName("unit_cost_minor") val unitCostMinor: Long,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("lot_code") val lotCode: String? = null,
)

@Serializable
data class GrnBody(
    @SerialName("branch_id") val branchId: String,
    @SerialName("supplier_id") val supplierId: String,
    @SerialName("supplier_invoice_no") val supplierInvoiceNo: String? = null,
    val notes: String? = null,
    val lines: List<GrnLineBody>,
)

@Serializable
data class GrnResult(
    val ok: Boolean = true,
    @SerialName("batches_created") val batchesCreated: Int = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
)

@Serializable
data class AdjustmentBody(
    @SerialName("ingredient_id") val ingredientId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("qty_delta") val qtyDelta: Double,
    val type: String,
    val note: String? = null,
)

@Serializable
data class AdjustmentResult(
    val id: String,
    val remaining: Double = 0.0,
)

/** Only the fields this screen needs; ignoreUnknownKeys drops the rest. */
@Serializable
data class Branch(
    val id: String,
    val name: String,
    val code: String? = null,
)

// --------------------------------------------------------------- adjustments

const val ADJ_WASTE = "waste"
const val ADJ_DAMAGE = "damage"
const val ADJ_TRANSFER = "transfer"

/** The backend's "adjustment" type is a physical-count correction. */
const val ADJ_COUNT = "adjustment"

/**
 * Types that can only ever *reduce* stock. Kept as an explicit set rather than
 * `type != ADJ_COUNT` so that adding a fifth type later fails loudly in review
 * instead of silently defaulting to "subtract".
 */
private val REDUCING_TYPES = setOf(ADJ_WASTE, ADJ_DAMAGE, ADJ_TRANSFER)

val ADJUSTMENT_TYPES: List<Pair<String, String>> = listOf(
    ADJ_WASTE to "Waste (spoilage / spill)",
    ADJ_DAMAGE to "Damage",
    ADJ_COUNT to "Count correction",
    ADJ_TRANSFER to "Transfer out",
)

/**
 * The sign rule, and the single place it exists.
 *
 * Waste, damage and transfer-out always take stock away, so their field asks
 * for a plain positive amount and it is negated here. A count correction is the
 * exception: the physical count can come up higher *or* lower than the system,
 * so its number is signed by the user and passes through untouched.
 *
 * This mirrors AdjustmentForm in frontend/src/modules/inventory/InventoryScreen.tsx
 * exactly. Getting it wrong does not throw — it just quietly corrupts stock in
 * the opposite direction — so the UI, the preview and the request all call this
 * one function rather than each re-deriving the sign.
 */
fun adjustmentDelta(type: String, qty: Double): Double =
    if (type in REDUCING_TYPES && qty > 0) -qty else qty

/** True when the field should accept a leading minus sign. */
fun allowsNegativeInput(type: String): Boolean = type !in REDUCING_TYPES

// ---------------------------------------------------------------- formatting

/**
 * Quantities, grouped Indian-style and without a pointless ".0" tail. Not money
 * — [cloud.dcompany.erp.core.net.asRupees] stays the only formatter for that.
 */
fun Double.asQty(): String {
    val scaled = (this * 100).roundToLong()
    val negative = scaled < 0
    val magnitude = abs(scaled)
    val whole = magnitude / 100
    val frac = magnitude % 100
    val tail = when {
        frac == 0L -> ""
        frac % 10 == 0L -> ".${frac / 10}"
        else -> ".${frac.toString().padStart(2, '0')}"
    }
    return (if (negative) "-" else "") + groupIndian(whole) + tail
}

private fun groupIndian(value: Long): String {
    val s = value.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    val tail = s.takeLast(3)
    val chunks = mutableListOf<String>()
    var rest = head
    while (rest.length > 2) {
        chunks.add(0, rest.takeLast(2))
        rest = rest.dropLast(2)
    }
    if (rest.isNotEmpty()) chunks.add(0, rest)
    return chunks.joinToString(",") + "," + tail
}

/** "2026-08-12T09:31:00Z" -> "2026-08-12". Batch dates only ever need the day. */
fun String.asDay(): String = if (length >= 10) substring(0, 10) else this
