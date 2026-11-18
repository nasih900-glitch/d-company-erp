package cloud.dcompany.erp.core.handover

import java.io.IOException

internal enum class TillPreparationStage {
    ORIGINAL_AUTHORITY,
    OPERATION_DRAIN,
    WORKER_JOIN,
    LOCAL_BEFORE_CLAIM_CLEANUP,
    LOCAL_CHECKOUT,
    CHECKOUT_CLEANUP,
    LOCAL_BEFORE_SERVER,
    SERVER_PROFILE_BEFORE,
    SERVER_SHIFT,
    SERVER_PROFILE_AFTER,
    LOCAL_AFTER_SERVER,
    SERVER_CHECKOUT,
    DURABLE_SAVE,
}

/** One attempt, finite enum values and counters only. Never retains a claim, identity or exception. */
internal class TillPreparationTrace {
    private var stage = TillPreparationStage.ORIGINAL_AUTHORITY
    private var readiness: LocalHandoverWork? = null
    private var claimCount: Int? = null
    private var drainCounts: Pair<Int, Int>? = null

    fun at(next: TillPreparationStage) {
        stage = next
        readiness = null
        claimCount = null
        drainCounts = null
    }

    fun drain(result: HandoverDrainResult.Blocked) {
        drainCounts = result.active.size to result.unresolved.size
    }

    fun local(work: LocalHandoverWork) {
        readiness = work
    }

    fun claims(next: TillPreparationStage, count: Int) {
        at(next)
        claimCount = count
    }

    fun blocked(originalCurrent: Boolean, state: HandoverCoordinatorState): TillPreparationBlocked {
        val active = drainCounts?.first ?: state.activeOperations.size
        val unresolved = drainCounts?.second ?: state.unresolvedOperations.size
        val blocker = TillPreparationBlocker(stage, originalCurrent, state.storageFault,
            active, unresolved, claimCount, readiness)
        val evidence = buildString {
            append("stage=").append(stage.name)
            append(" current=").append(originalCurrent)
            append(" active=").append(active)
            append(" unresolved=").append(unresolved)
            append(" storage_fault=").append(state.storageFault)
            claimCount?.let { append(" claims=").append(it) }
            readiness?.let {
                append(" local_verified=").append(it.verified)
                append(" pending=").append(it.blockers.sorted().joinToString(",") { kind -> kind.name })
                append(" unverified=").append(it.unverified.sorted().joinToString(",") { kind -> kind.name })
                append(" room=").append(it.roomPendingCount)
                append(" tender=").append(it.tenderReviewCount)
            }
        }
        return TillPreparationBlocked(blocker, evidence)
    }
}

/** Bounded display metadata only; none of these observations authorize a handover. */
internal data class TillPreparationBlocker(
    val stage: TillPreparationStage,
    val originalCurrent: Boolean,
    val storageFault: Boolean,
    val activeCount: Int,
    val unresolvedCount: Int,
    val checkoutCount: Int?,
    val local: LocalHandoverWork?,
) {
    val guidance: String get() {
        val action = when {
            !originalCurrent -> "The signed-in workspace changed. Return to the current cashier’s Shift page and refresh before trying again."
            storageFault -> "Saved handover evidence could not be verified. Ask an Owner to review recovery; do not erase saved work."
            stage == TillPreparationStage.OPERATION_DRAIN && unresolvedCount > 0 ->
                "$unresolvedCount earlier action(s) still need confirmation. Open Status and review unresolved or rejected work before retrying."
            stage == TillPreparationStage.OPERATION_DRAIN ->
                "The tablet is finishing $activeCount active action(s). Wait for Status to finish syncing, then retry."
            stage == TillPreparationStage.WORKER_JOIN ->
                "Background work has not been verified as finished. Check Status, allow sync to finish, then retry."
            stage == TillPreparationStage.LOCAL_CHECKOUT ->
                "An open or preparing checkout is still active" + (checkoutCount?.let { " ($it)" } ?: "") +
                    ". Return to POS and finish or review that checkout before retrying."
            stage == TillPreparationStage.CHECKOUT_CLEANUP || stage == TillPreparationStage.SERVER_CHECKOUT ->
                "Checkout reservations have not been verified as clear. Reconnect, review the existing POS checkout and retry; do not create a replacement bill."
            stage in setOf(TillPreparationStage.LOCAL_BEFORE_CLAIM_CLEANUP,
                TillPreparationStage.LOCAL_BEFORE_SERVER, TillPreparationStage.LOCAL_AFTER_SERVER) -> localGuidance()
            stage == TillPreparationStage.SERVER_SHIFT ->
                "The current open shift or operator could not be verified with the server. Refresh Shift and running till status, then retry."
            stage == TillPreparationStage.DURABLE_SAVE ->
                "The saved handover could not be confirmed. Check the retained handover recovery before trying another action."
            else -> "Server access or the original cashier’s authority could not be verified. Check the connection, refresh Shift and retry."
        }
        return "$action Keep the current cashier until a verified handover completes."
    }

    private fun localGuidance(): String {
        val work = local
        if (work == null || !work.verified) return "Some saved work could not be checked. Check Status and retry online; the number of pending sales is unknown."
        val categories = work.blockers.sorted().map { kind -> when (kind) {
            LocalHandoverWorkKind.ROOM -> "${work.roomPendingCount} saved tablet write(s): review pending or rejected work in Status"
            LocalHandoverWorkKind.STAFF_TIME -> "attendance: review saved clock actions"
            LocalHandoverWorkKind.START_WORK -> "start work: review the saved shift and clock-in request"
            LocalHandoverWorkKind.SHARED_ATTENDANCE -> "shared attendance: review saved staff clock actions"
            LocalHandoverWorkKind.FINANCE -> "finance: ask an Owner to review the saved operation"
            LocalHandoverWorkKind.TENDER -> "payment correction: ask an Owner to review retained payment evidence"
            LocalHandoverWorkKind.REMOTE -> "support: review the retained support operation"
        } }
        return if (categories.isEmpty()) "Saved work has not been verified as clear. Check Status and retry."
            else "Saved work needs attention — ${categories.joinToString("; ")}. Resolve the existing action, then retry. These are saved-work counts, not a sales count."
    }
}

internal class TillPreparationBlocked(val blocker: TillPreparationBlocker, val evidence: String) : IOException(blocker.guidance)
