package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.ShiftCloseBlockerCounts
import cloud.dcompany.erp.core.auth.CacheScopeLease

/** Saved work can forbid closing; its absence can never approve a server close. */
internal fun ShiftUiState.cachedCloseBlockMessage(): String? {
    val counts = closeBlockers ?: return null
    if (counts.captureBlockerCount == 0) return null
    val items = buildList {
        if (counts.serverHeldOrderCount > 0) add(
            "${counts.serverHeldOrderCount} held POS bill${if (counts.serverHeldOrderCount == 1) "" else "s"} saved on this tablet")
        if (counts.serverGamingSessionCount > 0) add(
            "${counts.serverGamingSessionCount} active or unpaid Gaming session${if (counts.serverGamingSessionCount == 1) "" else "s"}")
        if (counts.serverWorkflowCount > 0) add(
            "${counts.serverWorkflowCount} unfinished money task${if (counts.serverWorkflowCount == 1) "" else "s"}")
        if (counts.attentionLocalCount > 0) add(
            "${counts.attentionLocalCount} saved action${if (counts.attentionLocalCount == 1) "" else "s"} needing review")
        if (counts.unscopedAttentionCount > 0) add(
            "${counts.unscopedAttentionCount} older money record${if (counts.unscopedAttentionCount == 1) "" else "s"} needing owner review")
    }
    val next = buildList {
        if (counts.serverHeldOrderCount > 0) add("Review POS and sync.")
        if (counts.serverGamingSessionCount > 0) add("Finish or Void eligible Gaming sessions.")
        if (counts.serverWorkflowCount > 0) add("Finish the money tasks.")
        if (counts.attentionLocalCount + counts.unscopedAttentionCount > 0) add("Review saved actions and sync.")
    }
    return "Close is blocked: ${items.joinToString("; ")}. ${next.joinToString(" ")}"
}

internal fun shiftCloseReviewStatus(state: ShiftUiState, canClose: Boolean): String {
    val blockers = state.closeBlockers
    return when {
        state.rejectedShift != null -> "Saved open needs review"
        state.open?.local?.state == ShiftState.CLOSE_REJECTED -> "Close rejected"
        state.open?.local?.state == ShiftState.CLOSE_PENDING -> "Close waiting for server"
        state.closeOriginMessage != null -> "Close from the opening app"
        state.expectedMinor == null -> "Waiting for verified drawer total"
        !canClose -> "Review only · close permission required"
        !state.canClose -> "Close blocked · review requirements"
        blockers != null && blockers.captureBlockerCount > 0 ->
            "${blockers.captureBlockerCount} saved task${if (blockers.captureBlockerCount == 1) " needs" else "s need"} review"
        blockers == null -> "Saved work not yet checked · review required"
        blockers.pendingLocalCount > 0 ->
            "${blockers.pendingLocalCount} saved action(s) sync before close"
        else -> "Count cash · server review still required"
    }
}

/** A Room emission from an older account, terminal or login generation is never review evidence. */
internal fun scopedCloseReviewCounts(
    counts: ShiftCloseBlockerCounts?, reviewLease: CacheScopeLease?, currentLease: CacheScopeLease?,
    userId: String?, companyId: String?, branchId: String?, terminalId: String?,
): ShiftCloseBlockerCounts? = counts?.takeIf {
    reviewLease != null && reviewLease == currentLease &&
        reviewLease.scope.userId == userId && reviewLease.scope.companyId == companyId &&
        reviewLease.scope.branchId == branchId && reviewLease.scope.terminalId == terminalId
}
