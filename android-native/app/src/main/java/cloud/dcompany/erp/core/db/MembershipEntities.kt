package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * Tier create/edit stays web-only — Settings → Memberships already has a
 * working, shipped UI for it, so this screen only ever reads tiers, same
 * "don't rebuild what already exists elsewhere" reasoning that kept Events'
 * ticket-refund path out of scope. Wholesale-replaced read cache, same
 * shape as IngredientCacheEntity.
 */
@Entity(tableName = "membership_tier_cache")
data class MembershipTierCacheEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val monthlyPriceMinor: Long,
    val annualPriceMinor: Long?,
    val foodDiscountPct: Double,
    val gamingDiscountPct: Double,
    val hookahDiscountPct: Double,
    val pointMultiplier: Double,
    val freeGamingMinutesPerWeek: Int,
    val freeHookahPerMonth: Int,
    val priorityBooking: Boolean,
    val description: String?,
    val sortOrder: Int,
)

/**
 * Per-customer cache, wholesale-replaced per parent — same shape as
 * EventTicketCacheEntity/CapitalEntryCacheEntity. Holds at most the single
 * row the backend returns (GET /memberships/customer/{id} only ever
 * returns the current *active* subscription, or none at all).
 */
@Entity(tableName = "customer_membership_cache", indices = [Index("customerId")])
data class CustomerMembershipCacheEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val tierId: String,
    val tierCode: String,
    val tierName: String,
    val billingCycle: String,
    val startsAt: String,
    val expiresAt: String,
    val cancelledAt: String?,
    val revokedAt: String?,
    val autoRenew: Boolean,
    val amountPaidMinor: Long,
    val paymentId: String?,
    val paymentMethod: String?,
    val paymentShiftId: String?,
    val paymentReceiptNo: String?,
    val paymentPaidAt: String?,
    val paymentEvidenceOccurredAt: String? = null,
    @ColumnInfo(defaultValue = "0") val paymentEvidenceTimeUntrusted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val paymentProviderEvidenceReconciled: Boolean = true,
    val refundId: String?,
    val refundStatus: String?,
    val refundAcceptedAt: String?,
    val refundedAt: String?,
    val refundMethod: String?,
    val refundReceiptNo: String?,
    val refundExternalReference: String?,
    val refundEvidenceOccurredAt: String? = null,
    @ColumnInfo(defaultValue = "0") val refundEvidenceTimeUntrusted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val refundProviderEvidenceReconciled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val refundCustomerSpendReconciled: Boolean = true,
    val isActive: Boolean,
)

/** Durable receipt/register history for one customer, including expired and
 * revoked terms. It is separate from the current-active cache so a refund does
 * not erase the receipt staff need to show the customer. */
@Entity(tableName = "customer_membership_history_cache", indices = [Index("customerId")])
data class CustomerMembershipHistoryCacheEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val tierId: String,
    val tierCode: String,
    val tierName: String,
    val billingCycle: String,
    val startsAt: String,
    val expiresAt: String,
    val cancelledAt: String?,
    val revokedAt: String?,
    val autoRenew: Boolean,
    val amountPaidMinor: Long,
    val paymentId: String?,
    val paymentMethod: String?,
    val paymentShiftId: String?,
    val paymentReceiptNo: String?,
    val paymentPaidAt: String?,
    val paymentEvidenceOccurredAt: String? = null,
    @ColumnInfo(defaultValue = "0") val paymentEvidenceTimeUntrusted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val paymentProviderEvidenceReconciled: Boolean = true,
    val refundId: String?,
    val refundStatus: String?,
    val refundAcceptedAt: String?,
    val refundedAt: String?,
    val refundMethod: String?,
    val refundReceiptNo: String?,
    val refundExternalReference: String?,
    val refundEvidenceOccurredAt: String? = null,
    @ColumnInfo(defaultValue = "0") val refundEvidenceTimeUntrusted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val refundProviderEvidenceReconciled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val refundCustomerSpendReconciled: Boolean = true,
    val isActive: Boolean,
)

object MembershipWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * Shape D — insert-only, one row per subscribe action. Mirrors
 * LocalTicketSaleEntity: no serverId-null-vs-set duality since a subscribe
 * is never edited after capture, only ever queued once.
 */
@Entity(tableName = "local_subscriptions")
data class LocalSubscriptionEntity(
    @PrimaryKey val localId: String,
    val customerId: String,
    val tierId: String,
    /** Local shift id while an offline open is pending, otherwise server id.
     * Null only on quarantined pre-v18 rows whose money provenance cannot be
     * reconstructed safely. */
    val shiftId: String?,
    /** Exact price shown and accepted when the action was captured. Null only
     * on quarantined pre-v18 rows. */
    val expectedAmountMinor: Long?,
    val billingCycle: String,
    val paidVia: String,
    val createdAtMillis: Long,
    val syncState: String = MembershipWriteState.PENDING,
    val lastError: String? = null,
)

/**
 * Shape C — no local create leg, always targets a subscription already
 * pulled from the server (same reasoning as LocalCheckInEntity: cancelling
 * a still-unsynced offline subscribe has no server id to target yet, so
 * cancel is only offered against an already-synced subscription row).
 */
@Entity(tableName = "local_membership_cancellations")
data class LocalMembershipCancellationEntity(
    @PrimaryKey val localId: String,
    val customerId: String,
    val subscriptionId: String,
    val createdAtMillis: Long,
    val syncState: String = MembershipWriteState.PENDING,
    val lastError: String? = null,
)

/** Full, append-only financial reversal. It captures the exact shift and
 * amount the protected owner reviewed so retries cannot drift. */
@Entity(tableName = "local_membership_refunds", indices = [Index("subscriptionId"), Index("shiftId")])
data class LocalMembershipRefundEntity(
    @PrimaryKey val localId: String,
    val customerId: String,
    val subscriptionId: String,
    val shiftId: String,
    val expectedAmountMinor: Long,
    val method: String,
    val reason: String,
    /** Provider completion reference for non-cash rails; never invented by ERP. */
    val externalReference: String? = null,
    /** Stable local financial timestamp. Non-cash: provider completion.
     * Cash: physical handover captured after server acceptance. */
    val settledAtMillis: Long? = null,
    /** Populated by the accepted first leg and required by settle-cash. */
    val serverRefundId: String? = null,
    /** Customer-facing refund receipt populated only after settlement. */
    val receiptNo: String? = null,
    val withdrawalReason: String? = null,
    val withdrawalAtMillis: Long? = null,
    val createdAtMillis: Long,
    val syncState: String = MembershipRefundWriteState.REQUEST_PENDING,
    val lastError: String? = null,
)

object MembershipRefundWriteState {
    const val REQUEST_PENDING = "request_pending"
    const val REQUEST_REJECTED = "request_rejected"
    const val ACCEPTED_CASH_DUE = "accepted_cash_due"
    const val CASH_SETTLE_PENDING = "cash_settle_pending"
    const val CASH_SETTLE_REJECTED = "cash_settle_rejected"
    const val WITHDRAWAL_PENDING = "withdrawal_pending"
    const val WITHDRAWAL_REJECTED = "withdrawal_rejected"
    const val WITHDRAWN = "withdrawn"
    const val SYNCED = "synced"
}
