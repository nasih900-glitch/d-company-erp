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
        val message = when (stage) {
            TillPreparationStage.OPERATION_DRAIN -> if (unresolved > 0)
                "An earlier action still needs confirmation. Review saved work before handing over."
                else "The tablet is still finishing work. Wait for sync, then retry."
            TillPreparationStage.WORKER_JOIN -> "The tablet could not verify that background work finished. Wait for sync, then retry."
            TillPreparationStage.LOCAL_CHECKOUT -> "Close or finish the open checkout, then retry the handover."
            TillPreparationStage.CHECKOUT_CLEANUP, TillPreparationStage.SERVER_CHECKOUT ->
                "The tablet could not verify that its checkout reservations are clear. Review checkout and retry."
            TillPreparationStage.LOCAL_BEFORE_CLAIM_CLEANUP, TillPreparationStage.LOCAL_BEFORE_SERVER,
            TillPreparationStage.LOCAL_AFTER_SERVER -> if (readiness?.verified == true)
                "Saved work still needs review. Resolve it before handing over the till."
                else "The tablet could not verify its saved work. Keep the current cashier and retry."
            else -> "The tablet could not verify this till for handover. Refresh Shift and retry."
        }
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
        return TillPreparationBlocked(message, evidence)
    }
}

internal class TillPreparationBlocked(message: String, val evidence: String) : IOException(message)
