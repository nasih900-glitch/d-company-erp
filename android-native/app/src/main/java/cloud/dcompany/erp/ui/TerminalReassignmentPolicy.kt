package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import java.util.concurrent.atomic.AtomicBoolean

internal data class TerminalReassignmentFacts(
    val protectedOwner: Boolean,
    val tokenLineageCurrent: Boolean,
    val online: Boolean,
    val activeTerminalExact: Boolean,
    val currentTerminalStillAvailable: Boolean,
    val unresolvedOutboxCount: Int,
    val localShiftWorkflowCount: Int,
    val localGamingWorkflowCount: Int,
    val serverOpenShiftCount: Int,
    val serverGamingBlockerCount: Int,
    val alternativeTillCount: Int,
)

internal sealed interface TerminalReassignmentDecision {
    data object Allowed : TerminalReassignmentDecision
    data class Blocked(val message: String) : TerminalReassignmentDecision
}

/** One conservative, testable ordering for every till-change precondition. */
internal fun terminalReassignmentDecision(
    facts: TerminalReassignmentFacts,
): TerminalReassignmentDecision = when {
    !facts.protectedOwner -> TerminalReassignmentDecision.Blocked(
        "Only a protected owner can change this tablet's till.",
    )
    !facts.tokenLineageCurrent -> TerminalReassignmentDecision.Blocked(
        "Your sign-in changed while the till was being checked. Sign in again, then retry.",
    )
    !facts.online -> TerminalReassignmentDecision.Blocked(
        "Changing tills requires a live server check. Reconnect this tablet, wait for Sync, then retry.",
    )
    !facts.activeTerminalExact -> TerminalReassignmentDecision.Blocked(
        "This tablet's current till cannot be verified exactly. Reconnect once to refresh its " +
            "branch and till name before changing it.",
    )
    !facts.currentTerminalStillAvailable -> TerminalReassignmentDecision.Blocked(
        "The current till was removed or moved to another branch. No assignment was changed. " +
            "Ask an owner to restore that till or sign out and recover the tablet setup.",
    )
    facts.localShiftWorkflowCount > 0 -> TerminalReassignmentDecision.Blocked(
        "This tablet still has an active or closing shift for the current till. Open Shift, " +
            "finish or recover it, wait for Sync, then try again.",
    )
    facts.localGamingWorkflowCount > 0 -> TerminalReassignmentDecision.Blocked(
        "This tablet still has a gaming session that is active, stopping, or waiting for POS. " +
            "Finish or cancel it in Gaming, then try again.",
    )
    facts.unresolvedOutboxCount > 0 -> TerminalReassignmentDecision.Blocked(
        "This tablet has ${facts.unresolvedOutboxCount} saved change(s) still waiting for Sync or " +
            "staff recovery. Resolve the Sync warnings before changing tills.",
    )
    facts.serverOpenShiftCount > 0 -> TerminalReassignmentDecision.Blocked(
        "The current till still has an open shift on the server. Close that shift and refresh " +
            "Shift before changing this tablet.",
    )
    facts.serverGamingBlockerCount > 0 -> TerminalReassignmentDecision.Blocked(
        "Gaming still has active or stopped-unbilled work in this branch. Finish each session, " +
            "then Send to POS or cancel it with a reason before changing tills.",
    )
    facts.alternativeTillCount == 0 -> TerminalReassignmentDecision.Blocked(
        "No other till is configured for this branch. Create another till in Settings first.",
    )
    else -> TerminalReassignmentDecision.Allowed
}

/** Atomic rather than UI-state-only: two rapid taps cannot launch two guard sequences. */
internal class TerminalReassignmentRequestGate {
    private val running = AtomicBoolean(false)

    fun tryStart(): Boolean = running.compareAndSet(false, true)

    fun finish() {
        running.set(false)
    }
}

internal fun gamingBlocksTillReassignment(status: String, orderId: String?): Boolean =
    status.lowercase() in setOf("active", "paused") ||
        (status.equals("ended", ignoreCase = true) && orderId.isNullOrBlank())

internal fun workspaceLocationLabel(
    branchId: String?,
    branchName: String?,
    requiresTill: Boolean,
    activeTerminal: ValidatedTerminalDisplay?,
): String {
    val normalizedBranch = branchId?.trim()?.takeIf(String::isNotEmpty)
    // branchName is trusted only when the same /auth/me profile carries a
    // non-blank branchId. Never turn a detached/stale name into apparent
    // authority, and do not expose an internal UUID as staff-facing copy.
    val verifiedBranchName = branchName?.trim()?.takeIf(String::isNotEmpty)
        ?.takeIf { normalizedBranch != null }
    val branch = when {
        normalizedBranch == null -> "Branch not assigned"
        verifiedBranchName != null -> verifiedBranchName
        else -> "Assigned branch"
    }
    if (!requiresTill) return "$branch · No till required"
    val exact = activeTerminal?.takeIf { it.branchId == normalizedBranch }
    return if (exact == null) {
        "$branch · Till name pending verification"
    } else {
        "$branch · Till ${exact.terminalName}"
    }
}
