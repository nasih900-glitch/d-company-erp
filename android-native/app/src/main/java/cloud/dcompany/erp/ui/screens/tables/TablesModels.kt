package cloud.dcompany.erp.ui.screens.tables

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models copied field-for-field from the FastAPI schemas — guessing a
 * name here fails at runtime, not at compile time:
 *   backend/app/api/v1/tables/router.py  → FloorRead, TableRead, TableCreate,
 *                                          TableUpdate, TableStatusUpdate
 *   backend/app/api/v1/pos/router.py     → OrderRead, OrderLineRead,
 *                                          OrderListItem, OrderCreate,
 *                                          OrderLinesAppend, VoidOrderRequest
 *
 * Money is always an integer count of paise (`*_minor`), never a Double.
 */

@Serializable
data class Floor(
    val id: String,
    @SerialName("branch_id") val branchId: String,
    val name: String,
)

/**
 * Named CafeTable, not Table, so nothing in a Compose file can confuse it
 * with a layout container.
 */
@Serializable
data class CafeTable(
    val id: String,
    @SerialName("floor_id") val floorId: String,
    val code: String,
    val seats: Int = 0,
    val shape: String = "rect",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val status: String = "available",
)

@Serializable
data class TableCreateBody(
    val code: String,
    val seats: Int,
    val shape: String,
    // Omitted when null — the backend auto-resolves the floor when the branch
    // has only one.
    @SerialName("floor_id") val floorId: String? = null,
)

@Serializable
data class TableUpdateBody(
    val code: String? = null,
    val seats: Int? = null,
    val shape: String? = null,
)

@Serializable
data class TableStatusBody(val status: String)

/** ShiftRead, trimmed to what this screen needs. */
@Serializable
data class OpenShift(
    val id: String,
    val status: String,
    @SerialName("terminal_id") val terminalId: String? = null,
)

/**
 * OrderLineRead. `qty` really is a float on the wire — an Int here would
 * fail to parse the `2.0` the server sends.
 */
@Serializable
data class TableOrderLine(
    val id: String = "",
    @SerialName("client_line_id") val clientLineId: String? = null,
    @SerialName("menu_item_id") val menuItemId: String? = null,
    val name: String = "",
    val qty: Double = 0.0,
    @SerialName("unit_price_minor") val unitPriceMinor: Long = 0,
    @SerialName("line_total_minor") val lineTotalMinor: Long = 0,
    val note: String? = null,
    @SerialName("kitchen_status") val kitchenStatus: String = "queued",
    @SerialName("kitchen_released_at") val kitchenReleasedAt: String? = null,
    @SerialName("kitchen_round_no") val kitchenRoundNo: Int? = null,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("voided_by") val voidedBy: String? = null,
    @SerialName("void_reason") val voidReason: String? = null,
    @SerialName("kitchen_void_acknowledged_at")
    val kitchenVoidAcknowledgedAt: String? = null,
) {
    /** "2" rather than "2.0"; a half portion still reads as "0.5". */
    val qtyLabel: String
        get() = if (qty == qty.toLong().toDouble()) qty.toLong().toString() else qty.toString()
}

@Serializable
data class TableOrder(
    val id: String,
    val status: String,
    val type: String = "dine_in",
    @SerialName("table_id") val tableId: String? = null,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("subtotal_minor") val subtotalMinor: Long = 0,
    @SerialName("tax_minor") val taxMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("opened_at") val openedAt: String = "",
    @SerialName("held_at") val heldAt: String? = null,
    @SerialName("checkout_version") val checkoutVersion: Long = 1,
    val lines: List<TableOrderLine> = emptyList(),
    @SerialName("voided_lines") val voidedLines: List<TableOrderLine> = emptyList(),
)

/** OrderListItem — the slim row returned by GET /pos/orders. */
@Serializable
data class TableOrderSummary(
    val id: String,
    val status: String,
    @SerialName("table_id") val tableId: String? = null,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("items_count") val itemsCount: Int = 0,
)

@Serializable
data class OrderLineBody(
    @SerialName("client_line_id") val clientLineId: String,
    @SerialName("menu_item_id") val menuItemId: String,
    val qty: Int,
    val note: String? = null,
)

@Serializable
data class TableOrderCreateBody(
    val type: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("table_id") val tableId: String,
    val lines: List<OrderLineBody>,
)

@Serializable
data class OrderLinesAppendBody(
    @SerialName("expected_checkout_version") val expectedCheckoutVersion: Long,
    val lines: List<OrderLineBody>,
)

@Serializable
data class SendToPosBody(
    @SerialName("expected_checkout_version") val expectedCheckoutVersion: Long,
)

@Serializable
data class VoidOrderLineBody(
    @SerialName("expected_checkout_version") val expectedCheckoutVersion: Long,
    val reason: String,
)

/** DELETE /pos/orders/{id} returns 204; the reason is its complete body. */
@Serializable
data class VoidOrderBody(
    val reason: String,
)
