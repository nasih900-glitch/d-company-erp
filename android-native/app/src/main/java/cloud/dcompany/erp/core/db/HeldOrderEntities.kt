package cloud.dcompany.erp.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object HeldOrderPaymentState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * Read-only cache of orders waiting at the till for payment — a table's
 * order sent via "Send to POS", or any other order held instead of paid
 * immediately. Wholesale-replaced from the server like the menu; nothing
 * writes into this table directly.
 *
 * This was the actual cause of "orders aren't coming to POS": nothing in the
 * native app ever pulled or displayed this list at all, even though the
 * backend and the web app both already treat it as a real queue.
 */
@Entity(tableName = "held_order_cache")
data class HeldOrderCacheEntity(
    @PrimaryKey val id: String,
    val invoiceNo: String?,
    val type: String,
    /** "Table 4", a gaming station name, or null — see backend's _source_label. */
    val sourceLabel: String?,
    val totalMinor: Long,
    val paidMinor: Long,
    val itemsCount: Int,
    val customerName: String?,
    val createdAt: String,
    val heldAt: String?,
    /** Database-maintained server version of checkout-relevant bill state. */
    @ColumnInfo(defaultValue = "1") val checkoutVersion: Long = 1,
) {
    val dueMinor: Long get() = (totalMinor - paidMinor).coerceAtLeast(0)
}

/**
 * Shape C — this device's in-flight payment against an order that's already
 * real on the server (it only appears here after being pulled into
 * [HeldOrderCacheEntity]), so unlike a POS cart sale there's no create leg,
 * just the payment. `localId` is also the idempotency key.
 */
@Entity(
    tableName = "local_held_order_payments",
    indices = [
        Index("syncState"),
        Index(value = ["targetOrderId"], unique = true),
        Index("shiftId"),
        Index("terminalId"),
    ],
)
data class LocalHeldOrderPaymentEntity(
    @PrimaryKey val localId: String,
    val targetOrderId: String,
    val method: String,
    /** Exact bill balance collected; never the cash handed over. */
    val amountMinor: Long,
    /** Cash handed over, used only for cash change/audit. */
    val tenderedMinor: Long?,
    val expectedTotalMinor: Long,
    val expectedDueMinor: Long,
    /** Short-lived bearer lease. App-private Room is required for crash-safe replay. */
    val claimToken: String? = null,
    val claimExpiresAtMillis: Long? = null,
    /** Server checkout version represented by [claimToken]. */
    val claimOrderVersion: Long? = null,
    /**
     * Historical discriminator retained for receipt/source compatibility.
     * New direct bills also carry a claimToken after atomic publication; sync
     * therefore treats either this flag or a durable token as claim-required.
     */
    @ColumnInfo(defaultValue = "1") val requiresCheckoutClaim: Boolean = true,
    /** Exact drawer shift accepted at claim time. Null only for pre-v23 rows. */
    val shiftId: String? = null,
    /** Exact physical terminal that accepted payment. Null only for pre-v23 rows. */
    val terminalId: String? = null,
    val createdAtMillis: Long,
    val syncState: String = HeldOrderPaymentState.PENDING,
    val lastError: String? = null,
)
