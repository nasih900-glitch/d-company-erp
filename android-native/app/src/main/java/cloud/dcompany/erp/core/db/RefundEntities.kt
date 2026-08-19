package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Paid orders eligible for a refund, for every terminal — a shared view like
 * [GamingSessionCacheEntity], wholesale-replaced on every pull. `paidMinor` is
 * the original amount collected and never changes; `refundableMinor` is the
 * server's own computation of what's still left after every refund already
 * issued anywhere (paid minus refunded — see backend OrderListItem). This
 * device's own in-flight refunds live in [LocalRefundEntity] until they sync;
 * read-side code (RefundsViewModel) further nets `refundableMinor` against
 * any still-pending local refunds against the same order, so a second
 * offline refund can't be entered for more than is actually left to give
 * back — the server has the final word regardless, but the UI shouldn't
 * invite a mistake it can already see coming.
 */
@Entity(tableName = "refund_order_cache")
data class RefundOrderCacheEntity(
    @PrimaryKey val id: String,
    val invoiceNo: String? = null,
    val status: String,
    val type: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val refundableMinor: Long,
)

object RefundState {
    /** Captured on this tablet, not yet confirmed by the server. */
    const val PENDING = "pending"
    /** Confirmed by the server. */
    const val SYNCED = "synced"
    /** The server gave a definitive refusal. Needs a human. */
    const val REJECTED = "rejected"
}

/**
 * A refund captured on this tablet against an already-existing order. Unlike
 * Shift/Gaming there is no local "create" leg to wait on: `orderId` is always
 * a real server id from the moment this row is inserted — an order can't be
 * created offline in this app (it always comes from [RefundOrderCacheEntity],
 * itself pulled from the server), so there is nothing to resolve before
 * pushing. This is Gaming's stop-only outbox shape (see
 * [LocalGamingSessionEntity]'s doc comment) with the start leg simply absent.
 */
@Entity(tableName = "local_refunds", indices = [Index("state")])
data class LocalRefundEntity(
    @PrimaryKey val localId: String,
    val orderId: String,
    val invoiceNo: String? = null,
    val reasonCode: String,
    val amountMinor: Long,
    val note: String? = null,
    val createdAtMillis: Long,
    val state: String = RefundState.PENDING,
    val settlementMethod: String? = null,
    val lastError: String? = null,
)
