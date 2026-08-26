package cloud.dcompany.erp.core.db

sealed interface ShiftCloseCountValidation {
    data class Valid(val countedMinor: Long) : ShiftCloseCountValidation
    data class Invalid(val message: String) : ShiftCloseCountValidation
}

data class ShiftClosePreflightRejection(
    val localId: String,
    val message: String,
)

/**
 * One fail-closed rule for every path that captures or sends a drawer count.
 *
 * Zero is a legitimate empty drawer. Null means an older/corrupt close intent
 * lost its count, while a negative amount can never represent physical cash.
 * Neither invalid state may be repaired by manufacturing a zero.
 */
object ShiftCloseCountPolicy {
    fun validate(countedMinor: Long?): ShiftCloseCountValidation = when {
        countedMinor == null -> ShiftCloseCountValidation.Invalid(
            "This saved close is missing its drawer count and was not sent to the server. " +
                "Choose Continue shift, count the drawer again, then close the shift.",
        )
        countedMinor < 0L -> ShiftCloseCountValidation.Invalid(
            "This saved close has a negative drawer count and was not sent to the server. " +
                "Choose Continue shift, count the drawer again, then close the shift.",
        )
        else -> ShiftCloseCountValidation.Valid(countedMinor)
    }

    /**
     * Runs before the shift-open outbox is allowed to make a network call.
     *
     * A close captured while its offline open is still pending has no server
     * shift id yet, so validating only the close POST queue is too late. Keep
     * ordinary open intents and valid (including zero) close intents moving,
     * but reject every malformed close intent regardless of server-id state.
     */
    fun preflightRejection(row: LocalShiftEntity): ShiftClosePreflightRejection? {
        if (row.state != ShiftState.CLOSE_PENDING && row.state != ShiftState.CLOSE_REJECTED) {
            return null
        }
        val invalid = validate(row.countedMinor) as? ShiftCloseCountValidation.Invalid ?: return null
        return ShiftClosePreflightRejection(row.localId, invalid.message)
    }

    /**
     * Keeps a saved close on the physical terminal that captured it.
     *
     * A null terminal id is retained only for legacy rows whose terminal was
     * not persisted. The server still applies its own scope checks to those
     * rows; a close explicitly owned by a different terminal must never reach
     * the network stage under this tablet's X-Terminal-Id header.
     */
    fun filterForTerminal(
        rows: List<Pair<LocalShiftEntity, Long>>,
        currentTerminalId: String,
    ): List<Pair<LocalShiftEntity, Long>> = rows.filter { (row, _) ->
        row.terminalId == null || row.terminalId == currentTerminalId
    }
}
