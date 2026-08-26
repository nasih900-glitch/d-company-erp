package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.MembershipPaymentActionKind
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundActionKind
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus

internal fun taskListIsIncomplete(
    truncatedHeader: String?,
    rowCount: Int,
    limit: Int,
): Boolean {
    require(limit > 0)
    val marker = truncatedHeader?.trim()?.lowercase()
    require(marker in setOf(null, "true", "false"))
    return marker == "true" || (marker == null && rowCount >= limit)
}

internal fun paymentStageSatisfied(kind: String, status: String): Boolean = when (kind) {
    MembershipPaymentActionKind.PREPARE -> status in MembershipPaymentTaskStatus.known
    MembershipPaymentActionKind.BEGIN_CASH -> status in setOf(
        MembershipPaymentTaskStatus.CASH_COLLECTION_IN_PROGRESS,
        MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING,
        MembershipPaymentTaskStatus.SETTLED,
        MembershipPaymentTaskStatus.WITHDRAWN,
    )
    MembershipPaymentActionKind.BEGIN_PROVIDER -> status in setOf(
        MembershipPaymentTaskStatus.PROVIDER_ACTION_IN_PROGRESS,
        MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING,
        MembershipPaymentTaskStatus.SETTLED,
        MembershipPaymentTaskStatus.WITHDRAWN,
    )
    MembershipPaymentActionKind.COMPLETE -> status in setOf(
        MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING,
        MembershipPaymentTaskStatus.SETTLED,
    )
    MembershipPaymentActionKind.FINALIZE -> status == MembershipPaymentTaskStatus.SETTLED
    MembershipPaymentActionKind.WITHDRAW -> status == MembershipPaymentTaskStatus.WITHDRAWN
    else -> false
}

internal fun refundStageSatisfied(kind: String, status: String): Boolean = when (kind) {
    MembershipRefundActionKind.ACCEPT -> status in MembershipRefundTaskStatus.known
    MembershipRefundActionKind.BEGIN_CASH -> status in setOf(
        MembershipRefundTaskStatus.CASH_HANDOFF_IN_PROGRESS,
        MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING,
        MembershipRefundTaskStatus.SETTLED,
        MembershipRefundTaskStatus.WITHDRAWN,
    )
    MembershipRefundActionKind.BEGIN_PROVIDER -> status in setOf(
        MembershipRefundTaskStatus.PROVIDER_ACTION_IN_PROGRESS,
        MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING,
        MembershipRefundTaskStatus.SETTLED,
        MembershipRefundTaskStatus.WITHDRAWN,
    )
    MembershipRefundActionKind.COMPLETE_CASH,
    MembershipRefundActionKind.COMPLETE_PROVIDER,
    -> status in setOf(
        MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING,
        MembershipRefundTaskStatus.SETTLED,
    )
    MembershipRefundActionKind.FINALIZE -> status == MembershipRefundTaskStatus.SETTLED
    MembershipRefundActionKind.WITHDRAW -> status == MembershipRefundTaskStatus.WITHDRAWN
    else -> false
}
