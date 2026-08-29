package cloud.dcompany.erp.core.net

import cloud.dcompany.erp.core.auth.TerminalPurpose
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
data class MenuVariant(
    val id: String,
    val name: String,
    @SerialName("price_delta_minor") val priceDeltaMinor: Long,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class MenuModifier(
    val id: String,
    @SerialName("modifier_group_id") val modifierGroupId: String,
    val name: String,
    @SerialName("price_delta_minor") val priceDeltaMinor: Long,
    @SerialName("max_quantity") val maxQuantity: Int = 1,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class MenuModifierGroup(
    val id: String,
    val name: String,
    @SerialName("min_select") val minSelect: Int = 0,
    @SerialName("max_select") val maxSelect: Int = 1,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    val options: List<MenuModifier> = emptyList(),
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
    val variants: List<MenuVariant> = emptyList(),
    @SerialName("modifier_groups") val modifierGroups: List<MenuModifierGroup> = emptyList(),
)

@Serializable
data class Terminal(
    val id: String,
    val name: String,
    @SerialName("branch_id") val branchId: String,
    /** Absent only on a pre-purpose server; migration-compatible local behavior is hybrid. */
    val purpose: String = TerminalPurpose.HYBRID,
)

@Serializable
data class OrderLineRequest(
    @SerialName("client_line_id") val clientLineId: String? = null,
    @SerialName("menu_item_id") val menuItemId: String,
    val qty: Int,
    @SerialName("variant_id") val variantId: String? = null,
    val modifiers: List<ModifierSelectionRequest> = emptyList(),
    val note: String? = null,
)

@Serializable
data class ModifierSelectionRequest(
    @SerialName("modifier_id") val modifierId: String,
    val qty: Int = 1,
)

@Serializable
data class OrderModifierSnapshot(
    @SerialName("modifier_id") val modifierId: String,
    @SerialName("modifier_group_id") val modifierGroupId: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val name: String = "",
    val qty: Int = 1,
    @SerialName("price_delta_minor") val priceDeltaMinor: Long = 0,
    @SerialName("per_item_delta_minor") val perItemDeltaMinor: Long = 0,
    @SerialName("line_delta_minor") val lineDeltaMinor: Long = 0,
)

@Serializable
data class OrderVariantSnapshot(
    @SerialName("variant_id") val variantId: String,
    val name: String = "",
    @SerialName("price_delta_minor") val priceDeltaMinor: Long = 0,
    @SerialName("line_delta_minor") val lineDeltaMinor: Long = 0,
)

@Serializable
data class CreateOrderRequest(
    val type: String,
    @SerialName("shift_id") val shiftId: String,
    val lines: List<OrderLineRequest>,
    @SerialName("table_id") val tableId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    val notes: String? = null,
)

@Serializable
data class PaymentRequest(
    val method: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("tendered_minor") val tenderedMinor: Long? = null,
    @SerialName("ref_external") val refExternal: String? = null,
    @SerialName("expected_order_total_minor") val expectedTotalMinor: Long,
    @SerialName("expected_due_minor") val expectedDueMinor: Long,
    @SerialName("tip_minor") val tipMinor: Long = 0,
)

@Serializable
data class OrderCustomerUpdateRequest(
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("expected_checkout_version") val expectedCheckoutVersion: Long,
)

@Serializable
data class OrderDiscountUpdateRequest(
    @SerialName("manual_discount_minor") val manualDiscountMinor: Long,
    @SerialName("expected_checkout_version") val expectedCheckoutVersion: Long,
)

@Serializable
data class OrderPointsRedemptionUpdateRequest(
    /** Absolute spend, never a delta. The backend owns the conversion and balance check. */
    val points: Int,
    @SerialName("expected_checkout_version") val expectedCheckoutVersion: Long,
)

@Serializable
data class VoidOrderRequest(val reason: String)

@Serializable
data class CheckoutClaimResult(
    @SerialName("claim_id") val claimId: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("claim_token") val claimToken: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("order_total_minor") val orderTotalMinor: Long,
    @SerialName("paid_minor") val paidMinor: Long,
    @SerialName("due_minor") val dueMinor: Long,
    @SerialName("order_version") val orderVersion: Long,
    @SerialName("claimant_user_id") val claimantUserId: String,
    @SerialName("terminal_id") val terminalId: String,
    val reused: Boolean = false,
)

/**
 * Every field is optional. An order line has NO `id` in the payload, and `qty`
 * comes back as a float ("qty": 1.0) — declaring them as a required String and
 * an Int made kotlinx throw mid-sync, and because nothing caught it the whole
 * app crashed while sending a captured sale. A wire model on a till should
 * degrade, never take the process down.
 */
@Serializable
data class OrderLine(
    val id: String? = null,
    @SerialName("client_line_id") val clientLineId: String? = null,
    @SerialName("menu_item_id") val menuItemId: String? = null,
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("variant_snapshot") val variantSnapshot: OrderVariantSnapshot? = null,
    val modifiers: List<OrderModifierSnapshot>? = null,
    val name: String = "",
    val sku: String? = null,
    val qty: Double = 0.0,
    @SerialName("unit_price_minor") val unitPriceMinor: Long = 0,
    @SerialName("line_total_minor") val lineTotalMinor: Long = 0,
    @SerialName("taxable_value_minor") val taxableValueMinor: Long = 0,
    @SerialName("discount_minor") val discountMinor: Long = 0,
    @SerialName("cgst_minor") val cgstMinor: Long = 0,
    @SerialName("sgst_minor") val sgstMinor: Long = 0,
    @SerialName("igst_minor") val igstMinor: Long = 0,
    val note: String? = null,
)

@Serializable
data class Order(
    val id: String,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    // Defaulted for the same reason as OrderLine: an unexpected payload must
    // surface as a handled error, not kill the process mid-sale.
    val status: String = "",
    val type: String = "",
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("subtotal_minor") val subtotalMinor: Long = 0,
    @SerialName("discount_minor") val discountMinor: Long = 0,
    @SerialName("manual_discount_minor") val manualDiscountMinor: Long = 0,
    @SerialName("points_redeemed_minor") val pointsRedeemedMinor: Long = 0,
    @SerialName("points_redeemed") val pointsRedeemed: Int = 0,
    @SerialName("tax_minor") val taxMinor: Long = 0,
    @SerialName("round_off_minor") val roundOffMinor: Long = 0,
    @SerialName("tip_minor") val tipMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("paid_minor") val paidMinor: Long = 0,
    @SerialName("due_minor") val dueMinor: Long = 0,
    // Only populated by GET /pos/orders (the list endpoint) for the Refunds
    // screen — paidMinor minus every refund already issued anywhere, so a
    // second partial refund can't be entered for more than is actually left.
    // Defaults to 0 for every other consumer of this shared model, which is
    // the safe failure mode (blocks a refund rather than risking an over-refund).
    @SerialName("refundable_minor") val refundableMinor: Long = 0,
    // Authoritative server reservation and payment-rail evidence used by the
    // Refunds screen. An empty paymentMethods list is intentionally treated as
    // unavailable evidence; the client must not guess an original payout rail.
    @SerialName("pending_refund_minor") val pendingRefundMinor: Long = 0,
    @SerialName("payment_methods") val paymentMethods: List<String> = emptyList(),
    val lines: List<OrderLine> = emptyList(),
    @SerialName("checkout_version") val checkoutVersion: Long = 1,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    val notes: String? = null,
)

/** Slim row from `GET /pos/orders` — the held-orders queue and Refunds' lookup both use this. */
@Serializable
data class OrderListItem(
    val id: String,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    val type: String = "",
    val status: String = "",
    @SerialName("table_id") val tableId: String? = null,
    // "Table 4", a gaming station name, or null — pre-formatted server-side,
    // see backend's _source_label, so this client never needs the join.
    @SerialName("source_label") val sourceLabel: String? = null,
    @SerialName("total_minor") val totalMinor: Long = 0,
    @SerialName("paid_minor") val paidMinor: Long = 0,
    @SerialName("items_count") val itemsCount: Int = 0,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("held_at") val heldAt: String? = null,
    @SerialName("checkout_version") val checkoutVersion: Long = 1,
) {
    val dueMinor: Long get() = (totalMinor - paidMinor).coerceAtLeast(0)
}

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
    @SerialName("order_id") val orderId: String = "",
    @SerialName("shift_id") val shiftId: String = "",
    val method: String = "",
    @SerialName("amount_minor") val amountMinor: Long = 0,
    @SerialName("bill_amount_minor") val billAmountMinor: Long = 0,
    @SerialName("tip_minor") val tipMinor: Long = 0,
    @SerialName("tendered_minor") val tenderedMinor: Long? = null,
    @SerialName("change_minor") val changeMinor: Long? = null,
    @SerialName("ref_external") val refExternal: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("order_status") val orderStatus: String? = null,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    @SerialName("fiscal_year") val fiscalYear: String? = null,
    @SerialName("invoice_issued_at") val invoiceIssuedAt: String? = null,
)

/** Response from POST /pos/orders/{id}/finalize-zero. */
@Serializable
data class ZeroTotalFinalizationResult(
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_minor") val amountMinor: Long = 0,
    @SerialName("order_status") val orderStatus: String,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    @SerialName("fiscal_year") val fiscalYear: String? = null,
    @SerialName("invoice_issued_at") val invoiceIssuedAt: String? = null,
)
