package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local action states are deliberately independent from server task states.
 *
 * An action can be [AMBIGUOUS] while the server task has already advanced.  In
 * that case SyncEngine must reconcile the task before replaying the same
 * idempotency key.  Neither [REJECTED] nor a process crash authorises a second
 * physical collection or payout.
 */
object MembershipMoneyActionState {
    const val PENDING = "pending"
    const val AMBIGUOUS = "ambiguous"
    const val REJECTED = "rejected"
    const val SYNCED = "synced"
    const val LEGACY_RECOVERY_REQUIRED = "legacy_recovery_required"
    const val LEGACY_PROVENANCE_MISSING = "legacy_provenance_missing"

    val unresolved = setOf(
        PENDING,
        AMBIGUOUS,
        REJECTED,
        LEGACY_RECOVERY_REQUIRED,
        LEGACY_PROVENANCE_MISSING,
    )
}

object MembershipPaymentActionKind {
    const val LEGACY_PROBE = "legacy_probe"
    const val PREPARE = "prepare"
    const val BEGIN_CASH = "begin_cash"
    const val BEGIN_PROVIDER = "begin_provider"
    const val COMPLETE = "complete"
    const val FINALIZE = "finalize"
    const val WITHDRAW = "withdraw"
    const val LEGACY_RESOLVE = "legacy_resolve"
}

object MembershipPaymentTaskStatus {
    const val ACCEPTED_PAYMENT_DUE = "accepted_payment_due"
    const val CASH_COLLECTION_IN_PROGRESS = "cash_collection_in_progress"
    const val PROVIDER_ACTION_IN_PROGRESS = "provider_action_in_progress"
    const val PAYMENT_COMPLETED_PENDING_POSTING = "payment_completed_pending_posting"
    const val SETTLED = "settled"
    const val WITHDRAWN = "withdrawn"

    val known = setOf(
        ACCEPTED_PAYMENT_DUE,
        CASH_COLLECTION_IN_PROGRESS,
        PROVIDER_ACTION_IN_PROGRESS,
        PAYMENT_COMPLETED_PENDING_POSTING,
        SETTLED,
        WITHDRAWN,
    )
    val terminal = setOf(SETTLED, WITHDRAWN)
}

/** Server-derived, lossless mirror of MembershipPaymentRequestRead. */
@Entity(
    tableName = "membership_payment_task_cache",
    indices = [
        Index(value = ["clientActionId"], unique = true),
        Index("shiftId"),
        Index("status"),
        Index(value = ["terminalId", "acceptedAt"]),
    ],
)
data class MembershipPaymentTaskCacheEntity(
    @PrimaryKey val id: String,
    /** Scope is supplied by the authenticated cache lease; the response is already scoped. */
    val branchId: String,
    val terminalId: String,
    val customerId: String,
    val tierId: String,
    val shiftId: String,
    val billingCycle: String,
    val paidVia: String,
    val amountMinor: Long,
    val customerName: String?,
    val customerPhone: String,
    val tierCode: String,
    val tierName: String,
    val status: String,
    val acceptedAt: String,
    val preparedBy: String,
    val preparedByName: String?,
    val collectionStartedAt: String?,
    val valueCompletedAt: String?,
    val valueCompletedBy: String?,
    val valueCompletedByName: String?,
    val actionStartedBy: String?,
    val actionStartedByName: String?,
    val actionKind: String?,
    val settledAt: String?,
    val settledBy: String?,
    val settledByName: String?,
    val membershipId: String?,
    val paymentId: String?,
    val receiptNo: String?,
    val externalReference: String?,
    val evidenceOccurredAt: String?,
    val evidenceTimeUntrusted: Boolean,
    val providerEvidenceReconciled: Boolean,
    val customerSpendReconciled: Boolean,
    val resolution: String?,
    val resolvedAt: String?,
    val resolvedBy: String?,
    val resolvedByName: String?,
    val actionStateVerified: Boolean,
    val providerVerificationStatus: String?,
    val providerVerificationReference: String?,
    val providerCheckedAt: String?,
    val cashReturnConfirmed: Boolean,
    val actionTakeoverConfirmed: Boolean,
    val actionTakeoverReason: String?,
    val clientActionId: String,
    val fetchedAtMillis: Long,
)

/**
 * Durable one-row-per-stage journal for a membership collection.
 *
 * Every value that can affect money is captured before the request is sent.
 * Optional fields are action-specific; they are never filled from a newer
 * tier, shift or account during retry.
 */
@Entity(
    tableName = "local_membership_payment_actions",
    indices = [
        Index(value = ["rootClientActionId", "kind"], unique = true),
        Index(value = ["sourceLegacyLocalId"], unique = true),
        Index("serverRequestId"),
        Index("shiftId"),
        Index("state"),
    ],
)
data class LocalMembershipPaymentActionEntity(
    @PrimaryKey val actionId: String,
    val rootClientActionId: String,
    val serverRequestId: String? = null,
    val sourceLegacyLocalId: String? = null,
    val kind: String,
    val customerId: String,
    val tierId: String,
    /** Local shift id until an offline open resolves, otherwise server shift id. */
    val shiftId: String?,
    val branchId: String?,
    val terminalId: String?,
    val actorUserId: String?,
    val billingCycle: String,
    val paidVia: String,
    val expectedAmountMinor: Long?,
    val occurredAtMillis: Long? = null,
    val externalReference: String? = null,
    val resolution: String? = null,
    val reason: String? = null,
    val actionStateVerified: Boolean = false,
    val providerVerificationStatus: String? = null,
    val providerVerificationReference: String? = null,
    val providerEvidenceOccurredAtMillis: Long? = null,
    val cashReturnConfirmed: Boolean = false,
    val actionTakeoverConfirmed: Boolean = false,
    val actionTakeoverReason: String? = null,
    val createdAtMillis: Long,
    val state: String = MembershipMoneyActionState.PENDING,
    val lastError: String? = null,
)

object MembershipRefundActionKind {
    const val ACCEPT = "accept"
    const val BEGIN_CASH = "begin_cash"
    const val BEGIN_PROVIDER = "begin_provider"
    const val COMPLETE_CASH = "complete_cash"
    const val COMPLETE_PROVIDER = "complete_provider"
    const val FINALIZE = "finalize"
    const val WITHDRAW = "withdraw"
    const val LEGACY_RECONCILE_SERVER = "legacy_reconcile_server"
    const val LEGACY_REGISTER = "legacy_register"
    const val LEGACY_RESOLVE = "legacy_resolve"
}

object MembershipRefundTaskStatus {
    const val ACCEPTED_CASH_DUE = "accepted_cash_due"
    const val ACCEPTED_PROVIDER_DUE = "accepted_provider_due"
    const val CASH_HANDOFF_IN_PROGRESS = "cash_handoff_in_progress"
    const val PROVIDER_ACTION_IN_PROGRESS = "provider_action_in_progress"
    const val PAYOUT_COMPLETED_PENDING_POSTING = "payout_completed_pending_posting"
    const val SETTLED = "settled"
    const val WITHDRAWN = "withdrawn"

    val known = setOf(
        ACCEPTED_CASH_DUE,
        ACCEPTED_PROVIDER_DUE,
        CASH_HANDOFF_IN_PROGRESS,
        PROVIDER_ACTION_IN_PROGRESS,
        PAYOUT_COMPLETED_PENDING_POSTING,
        SETTLED,
        WITHDRAWN,
    )
    val terminal = setOf(SETTLED, WITHDRAWN)
}

/** Server-derived, lossless mirror of MembershipRefundRead. */
@Entity(
    tableName = "membership_refund_task_cache",
    indices = [
        Index("membershipId"),
        Index("paymentId"),
        Index("shiftId"),
        Index("status"),
        Index(value = ["terminalId", "acceptedAt"]),
    ],
)
data class MembershipRefundTaskCacheEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val terminalId: String,
    val membershipId: String,
    val paymentId: String,
    val shiftId: String,
    val method: String,
    val amountMinor: Long,
    val acceptedAt: String,
    val status: String,
    val handoffStartedAt: String?,
    val payoutCompletedAt: String?,
    val payoutCompletedBy: String?,
    val payoutCompletedByName: String?,
    val acceptedBy: String?,
    val acceptedByName: String?,
    val actionStartedBy: String?,
    val actionStartedByName: String?,
    val actionKind: String?,
    val settledAt: String?,
    val settledBy: String?,
    val settledByName: String?,
    val reason: String,
    val externalReference: String?,
    val receiptNo: String?,
    val entitlementRestored: Boolean,
    val customerId: String?,
    val customerName: String?,
    val customerPhone: String?,
    val tierName: String?,
    val originalPaymentReceiptNo: String?,
    val resolution: String?,
    val resolutionReason: String?,
    val resolvedAt: String?,
    val resolvedBy: String?,
    val resolvedByName: String?,
    val evidenceOccurredAt: String?,
    val evidenceTimeUntrusted: Boolean,
    val providerEvidenceReconciled: Boolean,
    val customerSpendReconciled: Boolean,
    val actionStateVerified: Boolean,
    val providerVerificationStatus: String?,
    val providerVerificationReference: String?,
    val providerCheckedAt: String?,
    val cashReturnConfirmed: Boolean,
    val actionTakeoverConfirmed: Boolean,
    val actionTakeoverReason: String?,
    val fetchedAtMillis: Long,
)

@Entity(
    tableName = "local_membership_refund_actions",
    indices = [
        Index(value = ["rootClientActionId", "kind"], unique = true),
        Index(value = ["sourceLegacyLocalId"], unique = true),
        Index("serverRefundId"),
        Index("membershipId"),
        Index("paymentId"),
        Index("shiftId"),
        Index("state"),
    ],
)
data class LocalMembershipRefundActionEntity(
    @PrimaryKey val actionId: String,
    val rootClientActionId: String,
    val serverRefundId: String? = null,
    val sourceLegacyLocalId: String? = null,
    val kind: String,
    val customerId: String,
    val membershipId: String,
    val paymentId: String?,
    val shiftId: String,
    val branchId: String?,
    val terminalId: String?,
    val actorUserId: String?,
    val paidVia: String,
    val expectedAmountMinor: Long,
    val reason: String,
    val occurredAtMillis: Long? = null,
    val externalReference: String? = null,
    val resolution: String? = null,
    val reconciliationShiftId: String? = null,
    val providerVerificationStatus: String? = null,
    val providerVerificationReference: String? = null,
    val providerEvidenceOccurredAtMillis: Long? = null,
    val cashHandoverConfirmed: Boolean = false,
    val actionStateVerified: Boolean = false,
    val cashReturnConfirmed: Boolean = false,
    val actionTakeoverConfirmed: Boolean = false,
    val actionTakeoverReason: String? = null,
    val createdAtMillis: Long,
    val state: String = MembershipMoneyActionState.PENDING,
    val lastError: String? = null,
)

/** Server-visible quarantine for pre-reservation refund attempts. */
@Entity(
    tableName = "membership_refund_attempt_cache",
    indices = [
        Index(value = ["originalClientActionId"], unique = true),
        Index("sourceShiftId"),
        Index("status"),
        Index("terminalId"),
    ],
)
data class MembershipRefundAttemptCacheEntity(
    @PrimaryKey val id: String,
    val branchId: String,
    val terminalId: String,
    val originalClientActionId: String,
    val customerId: String,
    val membershipId: String,
    val paymentId: String,
    val sourceShiftId: String,
    val expectedAmountMinor: Long,
    val paidVia: String,
    val capturedAt: String,
    val capturedTimeUntrusted: Boolean,
    val registeredAt: String,
    val registeredBy: String,
    val registeredByName: String?,
    val status: String,
    val resolutionId: String?,
    val fetchedAtMillis: Long,
)
