package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.ui.screens.shift.ShiftDetail

internal data class ShiftLifecycleActionIds(
    val open: String,
    val close: String,
)

/**
 * Open and close must retain one causal suffix for the full local lifecycle.
 * The backend uses this relation to distinguish the originating tablet's
 * close from a browser/different-installation close that could strand Room
 * dependants after an ambiguous open response.
 */
internal fun shiftLifecycleActionIds(localId: String): ShiftLifecycleActionIds {
    require(localId.isNotBlank()) { "A local shift identity is required" }
    return ShiftLifecycleActionIds(
        open = "shift-open:$localId",
        close = "shift-close:$localId",
    )
}

/**
 * Call only with the freshly fetched open shift. Server capability is not
 * enough: a pre-0069 shift can remain open across the backend upgrade even
 * though the terminal now advertises captured-opening support. Key presence
 * is not a vintage either, because normal web opens are unkeyed. A missing or
 * wrong-scope shift fails closed; every post-0069 opening requires replay of
 * its durable POST identity instead of a GET heuristic.
 */
internal fun allowsLegacyShiftOpeningMatch(
    liveShift: ShiftDetail?,
    terminalId: String,
    branchId: String,
): Boolean = liveShift != null &&
    liveShift.terminalId == terminalId &&
    liveShift.branchId == branchId &&
    liveShift.status == "open" &&
    liveShift.openingProtocolRevision == null
