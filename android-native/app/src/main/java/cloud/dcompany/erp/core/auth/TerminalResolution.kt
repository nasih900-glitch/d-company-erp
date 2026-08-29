package cloud.dcompany.erp.core.auth

import cloud.dcompany.erp.core.net.Terminal

sealed interface TerminalResolution {
    data object NotRequired : TerminalResolution

    data class Resolved(
        val terminal: Terminal,
        val shouldRemember: Boolean,
    ) : TerminalResolution

    data class SelectionRequired(val terminals: List<Terminal>) : TerminalResolution

    data class Blocked(val message: String) : TerminalResolution
}

/**
 * Resolve the physical till without ever falling back to the first terminal.
 *
 * Unsynced local work makes a stale/missing assignment non-recoverable in-app:
 * choosing another till could submit the old till's money under the new scope.
 */
internal fun resolveTerminalAssignment(
    requiresPosTerminal: Boolean,
    branchId: String?,
    availableTerminals: List<Terminal>,
    cachedTerminalId: String?,
    hasUnresolvedLocalWork: Boolean,
    singleHybridOnly: Boolean = false,
): TerminalResolution {
    if (!requiresPosTerminal) return TerminalResolution.NotRequired

    val normalizedBranchId = branchId?.trim().orEmpty()
    if (normalizedBranchId.isEmpty()) {
        return TerminalResolution.Blocked(
            "This account has no branch assignment. Ask a manager to assign one before opening the workspace.",
        )
    }

    val branchTerminals = availableTerminals
        .asSequence()
        .filter { terminal ->
            terminal.id.isNotBlank() && terminal.branchId.trim() == normalizedBranchId
        }
        .distinctBy(Terminal::id)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Terminal::name).thenBy(Terminal::id))
        .toList()

    val normalizedCachedId = cachedTerminalId?.trim()?.takeIf(String::isNotEmpty)
    if (
        singleHybridOnly &&
        (branchTerminals.size != 1 || branchTerminals.singleOrNull()?.purpose != TerminalPurpose.HYBRID)
    ) {
        val correction = when {
            branchTerminals.isEmpty() ->
                "No Hybrid Gaming + POS workspace is configured for this shop."
            branchTerminals.size > 1 ->
                "More than one active workspace is configured for this shop."
            else ->
                "This shop's active workspace is not configured for both Gaming and POS."
        }
        val savedWork = if (hasUnresolvedLocalWork) {
            " Saved work was kept under its original internal terminal; do not clear app data."
        } else {
            ""
        }
        return TerminalResolution.Blocked(
            "$correction Ask an owner to keep exactly one active Hybrid workspace, then verify again.$savedWork",
        )
    }

    val eligibleTerminals = branchTerminals
    eligibleTerminals.firstOrNull { it.id == normalizedCachedId }?.let {
        return TerminalResolution.Resolved(terminal = it, shouldRemember = false)
    }

    if (hasUnresolvedLocalWork) {
        return TerminalResolution.Blocked(
            "This tablet has saved work for its previous till. Reconnect with that till and finish Sync " +
                "before changing the assignment.",
        )
    }

    return when (eligibleTerminals.size) {
        0 -> TerminalResolution.Blocked(
            "No POS terminal is configured for this branch. Ask an owner to create one.",
        )
        1 -> TerminalResolution.Resolved(
            terminal = eligibleTerminals.single(),
            shouldRemember = normalizedCachedId != eligibleTerminals.single().id,
        )
        else -> TerminalResolution.SelectionRequired(eligibleTerminals)
    }
}

/**
 * Keep the crash/failure order explicit: the new durable cache scope must be
 * committed before TerminalStore is changed. If activation refuses, the old
 * till assignment remains available for recovery.
 */
internal suspend fun activateAndRememberTerminal(
    terminalId: String,
    shouldRemember: Boolean,
    activate: suspend (String) -> Unit,
    remember: suspend (String) -> Unit,
) {
    activate(terminalId)
    if (shouldRemember) remember(terminalId)
}
