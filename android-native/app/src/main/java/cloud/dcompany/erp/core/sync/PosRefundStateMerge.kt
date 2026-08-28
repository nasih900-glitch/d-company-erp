package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.RefundState

/**
 * Merges a staged local money action with the authoritative refund workflow.
 *
 * Pending local evidence must survive a stale server response, but once the
 * server confirms the corresponding stage it must advance to the accounting
 * state. Keeping `provider_completion_pending` after a successful provider
 * settlement would otherwise resend the idempotent request forever and never
 * allow final accounting to run.
 */
internal fun mergePosRefundState(localState: String, serverState: String): String = when {
    serverState == RefundState.SETTLED || serverState == RefundState.WITHDRAWN -> serverState

    localState in setOf(RefundState.CASH_SETTLE_PENDING, RefundState.CASH_SETTLE_REJECTED) &&
        serverState == RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING -> serverState

    localState in setOf(
        RefundState.PROVIDER_COMPLETION_PENDING,
        RefundState.PROVIDER_COMPLETION_REJECTED,
    ) && serverState == RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING -> serverState

    localState in setOf(
        RefundState.CASH_SETTLE_PENDING,
        RefundState.CASH_SETTLE_REJECTED,
        RefundState.CASH_FINALIZE_REJECTED,
        RefundState.PROVIDER_COMPLETION_PENDING,
        RefundState.PROVIDER_COMPLETION_REJECTED,
        RefundState.PROVIDER_FINALIZE_REJECTED,
        RefundState.WITHDRAWAL_PENDING,
        RefundState.WITHDRAWAL_REJECTED,
    ) -> localState

    localState == RefundState.CASH_HANDOFF_IN_PROGRESS &&
        serverState == RefundState.ACCEPTED_CASH_DUE -> localState

    localState == RefundState.PROVIDER_PAYOUT_IN_PROGRESS &&
        serverState == RefundState.ACCEPTED_PROVIDER_DUE -> localState

    else -> serverState
}

/**
 * Detects the one terminal-state contradiction that cannot be resolved by
 * trusting either device alone: this tablet durably recorded that physical
 * value moved, while another actor made the server request WITHDRAWN.
 *
 * WITHDRAWN remains the server-authoritative workflow state. The separate
 * conflict flag preserves the local payout evidence and blocks replay/shift
 * close until an owner reconciles the drawer/provider and audit history.
 */
internal fun hasPosRefundWithdrawalPayoutConflict(
    localState: String,
    serverState: String,
    settledAtMillis: Long?,
    providerSettledAtMillis: Long?,
    externalReference: String?,
): Boolean {
    if (serverState != RefundState.WITHDRAWN) return false

    return when (localState) {
        RefundState.CASH_SETTLE_PENDING,
        RefundState.CASH_SETTLE_REJECTED,
        RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
        RefundState.CASH_FINALIZE_REJECTED,
        -> settledAtMillis != null

        RefundState.PROVIDER_COMPLETION_PENDING,
        RefundState.PROVIDER_COMPLETION_REJECTED,
        RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
        RefundState.PROVIDER_FINALIZE_REJECTED,
        -> providerSettledAtMillis != null || !externalReference.isNullOrBlank()

        else -> false
    }
}
