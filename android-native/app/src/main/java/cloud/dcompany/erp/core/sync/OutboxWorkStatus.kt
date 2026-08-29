package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup

/**
 * App-wide, truthful summary of locally durable work.
 *
 * `retryableCount` is safe for automatic WorkManager replay. `actionRequiredCount`
 * is deliberately excluded from background retry (definitive refusals,
 * conflicts, ended gaming sessions awaiting POS, payout decisions, and legacy
 * reconciliation). POS drafts are durable but are neither a failed Sync nor a
 * reason to alarm the operator, so they remain a separate saved-work count.
 */
data class OutboxWorkStatus(
    val retryableCount: Int = 0,
    val actionRequiredCount: Int = 0,
    val savedDraftCount: Int = 0,
) {
    val totalCount: Int = retryableCount + actionRequiredCount + savedDraftCount
    val isClear: Boolean = totalCount == 0
}

internal fun summarizeOutboxWork(
    groups: List<UnresolvedOutboxGroup>,
): OutboxWorkStatus {
    var retryable = 0
    var actionRequired = 0
    var savedDrafts = 0
    for (group in groups) {
        if (group.count <= 0) continue
        when {
            isBackgroundRetryableGroup(group) -> retryable += group.count
            group.resource == "pos_orders" && group.state in SAVED_POS_DRAFT_STATES ->
                savedDrafts += group.count
            else -> actionRequired += group.count
        }
    }
    return OutboxWorkStatus(
        retryableCount = retryable,
        actionRequiredCount = actionRequired,
        savedDraftCount = savedDrafts,
    )
}

internal fun isBackgroundRetryableGroup(group: UnresolvedOutboxGroup): Boolean =
    group.count > 0 && isBackgroundRetryableState(group.state)

internal fun isBackgroundRetryableState(state: String): Boolean =
    state == "pending" ||
        state == "ambiguous" ||
        state == "create_attempted" ||
        state.endsWith("_pending") ||
        "_pending_" in state

internal fun outboxWorkVisibleLabel(
    status: OutboxWorkStatus,
    syncing: Boolean,
    showDetail: Boolean,
): String? {
    if (status.isClear && !syncing) return null
    if (!showDetail) return status.totalCount.takeIf { it > 0 }?.toString() ?: "Sync"

    return buildList {
        if (status.actionRequiredCount > 0) add("${status.actionRequiredCount} review")
        if (status.retryableCount > 0) {
            add(
                if (syncing) "Syncing ${status.retryableCount}"
                else "${status.retryableCount} waiting",
            )
        } else if (syncing) {
            add("Syncing")
        }
        if (status.savedDraftCount > 0) add("${status.savedDraftCount} saved")
    }.joinToString(" · ")
}

private val SAVED_POS_DRAFT_STATES = setOf(
    "draft",
    "preparing",
    "awaiting_payment",
)
