package cloud.dcompany.erp.ui.screens.kitchen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field names copied verbatim from KitchenLineDTO / KitchenOrderDTO in
 * backend/app/api/v1/kitchen/router.py (mirrored in frontend/src/lib/erp-api.ts).
 *
 * No money crosses this endpoint — the kitchen never sees a price — so there is
 * deliberately no amount field here to get wrong.
 */
@Serializable
data class KitchenLine(
    @SerialName("menu_item_id") val menuItemId: String,
    val name: String,
    /** food / drink / dessert — the backend filters everything else out. */
    val type: String,
    /**
     * The server serialises this as `float(line.qty)`, so the wire carries
     * `2.0`, not `2`. Decoding it as Int would throw at runtime on every
     * single ticket. Not money, so a Double is safe here.
     */
    val qty: Double = 0.0,
    val notes: String? = null,
)

@Serializable
data class KitchenOrder(
    val id: String,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    val type: String,
    @SerialName("table_code") val tableCode: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("kitchen_state") val kitchenState: String,
    @SerialName("minutes_waiting") val minutesWaiting: Int = 0,
    val lines: List<KitchenLine> = emptyList(),
) {
    /** What the cook calls this ticket. Table number first — that is how food is walked out. */
    val ticketLabel: String
        get() = when {
            !tableCode.isNullOrBlank() -> "Table $tableCode"
            !invoiceNo.isNullOrBlank() -> invoiceNo
            else -> "Order #${id.take(6)}"
        }

    val whoFor: String get() = customerName?.takeIf { it.isNotBlank() } ?: "Walk-in"

    /** dine_in -> "Dine in", so the tag reads like English on the wall. */
    val typeLabel: String
        get() = type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Request body for PATCH /kitchen/orders/{id}/state (StateUpdate on the server). */
@Serializable
data class KitchenStateUpdate(val state: String)

/**
 * The server enforces this ladder strictly: it refuses a jump (received →
 * ready) and refuses a step backwards, but accepts a repeat of the state a
 * ticket is already in as a no-op. That last property is why re-tapping after
 * a dropped connection is safe, and why this screen needs no idempotency key
 * for the advance — resending the same target state cannot double-apply.
 */
enum class KitchenState(
    val wire: String,
    /** Lane heading. "New" rather than "Received": shorter to read at 3 metres. */
    val label: String,
    val advanceLabel: String?,
) {
    RECEIVED("received", "New", "Start preparing"),
    PREPARING("preparing", "Preparing", "Mark ready"),
    READY("ready", "Ready", "Mark served"),
    SERVED("served", "Served", null);

    val next: KitchenState?
        get() = when (this) {
            RECEIVED -> PREPARING
            PREPARING -> READY
            READY -> SERVED
            SERVED -> null
        }

    companion object {
        /**
         * Null for anything unrecognised. A future backend state must show the
         * ticket as read-only rather than crash a wall display or, worse, put
         * an advance button on it that sends a state the server will refuse.
         */
        fun from(wire: String?): KitchenState? = entries.firstOrNull { it.wire == wire }
    }
}

/** "2×", "1.5×" — never "2.0×". */
fun Double.asQtyPrefix(): String {
    val whole = this == Math.floor(this) && !this.isInfinite()
    return if (whole) toLong().toString() else toString().trimEnd('0').trimEnd('.')
}
