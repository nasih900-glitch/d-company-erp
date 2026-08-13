package cloud.dcompany.erp.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Money is always an integer count of paise, never a Double. The backend keeps
 * every amount in minor units precisely so no rounding can creep into a ledger,
 * and a Float here would reintroduce it at the last mile.
 */

@Serializable
data class MenuCategory(
    val id: String,
    val name: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class MenuItem(
    val id: String,
    @SerialName("category_id") val categoryId: String,
    val sku: String,
    val name: String,
    val type: String,
    @SerialName("base_price_minor") val basePriceMinor: Long,
    @SerialName("tax_rate") val taxRate: Double,
    @SerialName("hsn_code") val hsnCode: String? = null,
    @SerialName("price_includes_tax") val priceIncludesTax: Boolean = true,
    @SerialName("is_available") val isAvailable: Boolean = true,
    val description: String? = null,
)

@Serializable
data class Shift(
    val id: String,
    val status: String,
    @SerialName("opening_float_minor") val openingFloatMinor: Long = 0,
    @SerialName("expected_minor") val expectedMinor: Long = 0,
    @SerialName("opened_by_user_id") val openedByUserId: String? = null,
    @SerialName("terminal_id") val terminalId: String? = null,
)

@Serializable
data class OpenShiftRequest(
    @SerialName("opening_float_minor") val openingFloatMinor: Long,
)

@Serializable
data class ShiftOpened(val id: String, val status: String)

@Serializable
data class Terminal(
    val id: String,
    val name: String,
    @SerialName("branch_id") val branchId: String,
)

@Serializable
data class OrderLineRequest(
    @SerialName("menu_item_id") val menuItemId: String,
    val qty: Int,
)

@Serializable
data class CreateOrderRequest(
    val type: String,
    @SerialName("shift_id") val shiftId: String,
    val lines: List<OrderLineRequest>,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
)

@Serializable
data class PaymentRequest(
    val method: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("expected_total_minor") val expectedTotalMinor: Long,
    @SerialName("expected_due_minor") val expectedDueMinor: Long,
    @SerialName("tip_minor") val tipMinor: Long = 0,
)

@Serializable
data class OrderLine(
    val id: String,
    @SerialName("menu_item_id") val menuItemId: String? = null,
    val name: String,
    val qty: Int,
    @SerialName("unit_price_minor") val unitPriceMinor: Long = 0,
    @SerialName("line_total_minor") val lineTotalMinor: Long = 0,
)

@Serializable
data class Order(
    val id: String,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    val status: String,
    val type: String,
    @SerialName("subtotal_minor") val subtotalMinor: Long = 0,
    @SerialName("discount_minor") val discountMinor: Long = 0,
    @SerialName("manual_discount_minor") val manualDiscountMinor: Long = 0,
    @SerialName("points_redeemed_minor") val pointsRedeemedMinor: Long = 0,
    @SerialName("tax_minor") val taxMinor: Long = 0,
    @SerialName("round_off_minor") val roundOffMinor: Long = 0,
    @SerialName("tip_minor") val tipMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("paid_minor") val paidMinor: Long = 0,
    @SerialName("due_minor") val dueMinor: Long = 0,
    val lines: List<OrderLine> = emptyList(),
)

/** ₹ formatting for paise, grouped Indian-style (1,23,456.78). */
fun Long.asRupees(): String {
    val negative = this < 0
    val paise = kotlin.math.abs(this)
    val whole = paise / 100
    val frac = paise % 100
    val s = whole.toString()
    val grouped = if (s.length <= 3) {
        s
    } else {
        // Indian grouping: last three digits, then pairs.
        val head = s.dropLast(3)
        val tail = s.takeLast(3)
        val chunks = mutableListOf<String>()
        var rest = head
        while (rest.length > 2) {
            chunks.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) chunks.add(0, rest)
        chunks.joinToString(",") + "," + tail
    }
    val sign = if (negative) "-" else ""
    return "$sign₹$grouped.${frac.toString().padStart(2, '0')}"
}

/**
 * POST /pos/orders/{id}/payments returns the *Payment*, not the Order.
 * Declaring it as Order made kotlinx throw MissingFieldException for `status`
 * and `type` — and because that is a SerializationException rather than an
 * ApiException it escaped every catch in the sync engine, leaving the sale
 * stranded as "pending" with no error recorded anywhere. The order was created
 * and left unpaid on the server while the till showed nothing wrong.
 */
@Serializable
data class PaymentResult(
    val id: String,
    @SerialName("amount_minor") val amountMinor: Long = 0,
    @SerialName("tip_minor") val tipMinor: Long = 0,
    @SerialName("order_status") val orderStatus: String? = null,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    @SerialName("fiscal_year") val fiscalYear: String? = null,
    @SerialName("invoice_issued_at") val invoiceIssuedAt: String? = null,
)
