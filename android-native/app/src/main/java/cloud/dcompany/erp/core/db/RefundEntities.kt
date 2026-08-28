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
 * device's own unresolved refund request lives in [LocalRefundEntity]. While
 * one exists, the Refunds UI removes that order from the request picker. This
 * is intentionally stricter than subtracting a local amount: a cached balance
 * can be stale across terminals, and a second request must wait until the
 * first has a definitive server outcome and its cash handoff is resolved.
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
    val pendingRefundMinor: Long = 0,
    /** Canonical comma-separated server rails; values themselves never contain commas. */
    val paymentMethodsCsv: String = "",
)

/**
 * Durable, cache-scope-owned obligation to refresh every projection affected
 * by a terminal POS refund. A process restart must not make stale customer,
 * drawer or finance data look current after the money fact was committed.
 */
const val POS_REFUND_EFFECTS_DIRTY_SYNC_KEY = "dirty:pos_refund_effects"

object RefundState {
    /** Captured on this tablet; the server has not accepted any payout yet. */
    const val REQUEST_PENDING = "request_pending"

    /** Server accepted and reserved this cash refund; the drawer must not be touched yet. */
    const val ACCEPTED_CASH_DUE = "accepted_cash_due"

    /** Server accepted and reserved an original-rail refund; the provider app is untouched. */
    const val ACCEPTED_PROVIDER_DUE = "accepted_provider_due"

    /** Server opened the guarded handover window. A restart must not trigger a second payout. */
    const val CASH_HANDOFF_IN_PROGRESS = "cash_handoff_in_progress"

    /** Staff confirmed the physical payout; settlement is waiting for the server. */
    const val CASH_SETTLE_PENDING = "cash_settle_pending"
    const val CASH_SETTLE_REJECTED = "cash_settle_rejected"

    /** Cash handover is server-backed; only append-only accounting finalisation remains. */
    const val CASH_HANDED_OVER_PENDING_ACCOUNTING = "cash_handed_over_pending_accounting"
    const val CASH_FINALIZE_REJECTED = "cash_finalize_rejected"

    /** Server durably recorded that this exact provider payout may now be attempted. */
    const val PROVIDER_PAYOUT_IN_PROGRESS = "provider_payout_in_progress"

    /** Provider value moved once; immutable evidence is durably queued for the server. */
    const val PROVIDER_COMPLETION_PENDING = "provider_completion_pending"
    const val PROVIDER_COMPLETION_REJECTED = "provider_completion_rejected"

    /** Provider completion is server-backed; only accounting materialisation remains. */
    const val PROVIDER_COMPLETED_PENDING_ACCOUNTING = "provider_completed_pending_accounting"
    const val PROVIDER_FINALIZE_REJECTED = "provider_finalize_rejected"

    /** Owner confirmed that no cash left the drawer; the withdrawal is queued. */
    const val WITHDRAWAL_PENDING = "withdrawal_pending"
    const val WITHDRAWAL_REJECTED = "withdrawal_rejected"
    const val WITHDRAWN = "withdrawn"

    /** The backend confirms that the refund and financial effects were posted. */
    const val SETTLED = "settled"

    /** The server gave a definitive refusal; no payout was authorised. */
    const val REQUEST_REJECTED = "request_rejected"

    /** Staff abandoned a definitively refused request. The server recorded no refund. */
    const val CANCELLED = "cancelled"

    /**
     * Pre-v20 rows lack the exact server request, shift, terminal, branch, and
     * actor needed for safe recovery. They remain visible and blocking, but
     * are never reinterpreted or replayed as a new due-cash task.
     */
    const val LEGACY_RECONCILIATION_REQUIRED = "legacy_reconciliation_required"

    /** Old terminal state retained as immutable history, not a v20 server task. */
    const val LEGACY_SETTLED = "synced"

    // Source-compatible aliases while callers move to the explicit names.
    const val PENDING = REQUEST_PENDING
    const val REJECTED = REQUEST_REJECTED
}

/**
 * A refund captured on this tablet against an already-existing order. Unlike
 * Shift/Gaming there is no local "create" leg to wait on: `orderId` is always
 * a real server id from the moment this row is inserted — an order can't be
 * created offline in this app (it always comes from [RefundOrderCacheEntity],
 * itself pulled from the server), so there is nothing to resolve before
 * pushing. A cash request moves through server acceptance, a server-confirmed
 * handover window, physical payout confirmation, and server settlement. Those
 * are deliberately separate durable facts so an app restart cannot pay twice.
 */
@Entity(
    tableName = "local_refunds",
    indices = [
        Index("state"),
        Index("shiftId"),
        Index(value = ["clientActionId"], unique = true),
        Index(value = ["serverRequestId"], unique = true),
    ],
)
data class LocalRefundEntity(
    @PrimaryKey val localId: String,
    val clientActionId: String? = null,
    val orderId: String,
    val invoiceNo: String? = null,
    /** Local or server id captured at intent time; [serverShiftId] is filled after open sync. */
    val shiftId: String? = null,
    val serverShiftId: String? = null,
    val branchId: String? = null,
    val terminalId: String? = null,
    val capturedByUserId: String? = null,
    val reasonCode: String,
    val amountMinor: Long,
    val expectedPaidMinor: Long? = null,
    val expectedRefundableMinor: Long? = null,
    /** "cash" or "original". */
    val mode: String? = null,
    val note: String? = null,
    val externalReference: String? = null,
    val providerSettledAtMillis: Long? = null,
    val createdAtMillis: Long,
    val state: String = RefundState.REQUEST_PENDING,
    /** Server-verified rail: cash/card/upi/qr/wallet. */
    val settlementMethod: String? = null,
    val serverRequestId: String? = null,
    val serverRefundId: String? = null,
    val acceptedAtMillis: Long? = null,
    val acceptedByUserId: String? = null,
    val acceptedByName: String? = null,
    val cashHandoffStartedAtMillis: Long? = null,
    val cashHandoffStartedByUserId: String? = null,
    val cashHandoffStartedByName: String? = null,
    val cashHandedOverAtMillis: Long? = null,
    val cashHandedOverRecordedAtMillis: Long? = null,
    val cashHandedOverByUserId: String? = null,
    val cashHandedOverByName: String? = null,
    val providerPayoutStartedAtMillis: Long? = null,
    val providerPayoutStartedByUserId: String? = null,
    val providerPayoutStartedByName: String? = null,
    val settledAtMillis: Long? = null,
    val providerCompletionRecordedAtMillis: Long? = null,
    val providerCompletedByUserId: String? = null,
    val providerCompletedByName: String? = null,
    val settledByUserId: String? = null,
    val settledByName: String? = null,
    val clientOccurredAtMillis: Long? = null,
    val capturedTimeReconciled: Boolean? = null,
    val providerEvidenceReconciled: Boolean? = null,
    /** Server withdrawal contradicted locally captured physical payout evidence. */
    val payoutConflict: Boolean = false,
    val withdrawalAtMillis: Long? = null,
    val withdrawnByUserId: String? = null,
    val withdrawnByName: String? = null,
    val providerVerificationStatus: String? = null,
    val providerVerificationReference: String? = null,
    val providerVerifiedAtMillis: Long? = null,
    val customerSpendReconciled: Boolean? = null,
    val loyaltyReconciliationState: String? = null,
    val withdrawalReason: String? = null,
    val receiptNo: String? = null,
    val lastError: String? = null,
)
