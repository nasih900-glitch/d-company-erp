package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.SyncState

/** Server-proven, same-installation drawer offered explicitly in the cash paid-out form. */
internal data class CashExpenseShiftOption(
    val id: String,
    val branchId: String,
    val terminalId: String,
    val openedBy: String,
    val expectedMinor: Long,
    val queuedMinor: Long,
    val availableMinor: Long,
    val openedAtMillis: Long,
    val verifiedAtMillis: Long,
)

/**
 * Cash may reference only the server identity of the resolved open shift.
 * A local open still waiting for its server receipt is deliberately excluded:
 * rebinding a queued expense to whatever shift appears later could debit the
 * wrong drawer. The opener is presentation evidence, not an ownership gate;
 * finance.write is the authority to use a colleague's open drawer.
 */
internal fun cashExpenseShiftOption(
    resolved: ResolvedOpenShift?,
    branchId: String?,
    localCashExpenses: List<LocalExpenseEntity>,
    currentInstallationId: String?,
): CashExpenseShiftOption? {
    val server = resolved?.server ?: return null
    val expectedMinor = server.expectedMinor ?: return null
    if (
        server.status != "open" ||
        resolved.shiftId != server.serverShiftId ||
        branchId == null ||
        server.branchId != branchId ||
        expectedMinor < 0
    ) return null

    // Finance writes are always captured locally before SyncEngine sends them.
    // A reachability flag therefore cannot make a Web or another tablet's
    // cached shift safe: that workspace can close the drawer between this
    // screen's last pull and the eventual POST. Only the originating Android
    // installation may capture another drawer write. Any authorised user on
    // that same tablet remains allowed. Null upgrade-era origin data fails
    // closed until a live pull refreshes the cache.
    val localInstallation = currentInstallationId
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    if (
        server.openingClientPlatform?.trim()?.lowercase() != "android" ||
        server.openingClientInstallationId?.trim()?.lowercase() != localInstallation
    ) return null

    val queuedMinor = localCashExpenses.asSequence()
        .filter { expense ->
            val drawerSnapshotDoesNotIncludeIt =
                expense.syncState == SyncState.PENDING ||
                    (
                        expense.syncState == SyncState.SYNCED &&
                            expense.createdAtMillis >= server.verifiedAtMillis
                    )
            drawerSnapshotDoesNotIncludeIt &&
                expense.paidVia == "cash" &&
                expense.branchId == server.branchId &&
                expense.shiftId == server.serverShiftId
        }
        .fold(0L) { total, expense ->
            if (Long.MAX_VALUE - total < expense.amountMinor) Long.MAX_VALUE
            else total + expense.amountMinor
        }
    val openedBy = server.openedByName?.trim()?.takeIf(String::isNotEmpty)
        ?: server.openedByEmail?.trim()?.takeIf(String::isNotEmpty)
        ?: "Authorised staff member"

    return CashExpenseShiftOption(
        id = server.serverShiftId,
        branchId = server.branchId,
        terminalId = server.terminalId,
        openedBy = openedBy,
        expectedMinor = expectedMinor,
        queuedMinor = queuedMinor,
        availableMinor = (expectedMinor - queuedMinor.coerceAtMost(expectedMinor)).coerceAtLeast(0),
        openedAtMillis = server.openedAtMillis,
        verifiedAtMillis = server.verifiedAtMillis,
    )
}

internal fun cashExpenseSelectionError(
    paidVia: String,
    branchId: String,
    shiftId: String?,
    amountMinor: Long,
    options: List<CashExpenseShiftOption>,
): String? {
    if (paidVia != "cash") return null
    val selected = options.firstOrNull {
        it.id == shiftId && it.branchId == branchId
    } ?: return "Select a verified open shift for this cash paid-out."
    if (amountMinor > selected.availableMinor) {
        return "This paid-out exceeds the cash available in the selected drawer after saved cash expenses."
    }
    return null
}
