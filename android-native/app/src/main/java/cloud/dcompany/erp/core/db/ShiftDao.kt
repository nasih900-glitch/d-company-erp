package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

enum class RejectedOpenRecoveryStatus {
    APPLIED,
    DISCARDED,
    CHANGED,
    WRONG_SCOPE,
    SERVER_SHIFT_PRESENT,
    UNRELATED_SERVER_SHIFT,
    LIVE_VERIFICATION_REQUIRED,
    DEPENDENT_WORK,
}

data class RejectedOpenRecoveryResult(
    val status: RejectedOpenRecoveryStatus,
    val message: String,
)

internal const val REJECTED_OPEN_RACE_TOLERANCE_MILLIS = 5 * 60 * 1_000L

internal data class RejectedOpenTimestampWindow(
    val earliestMillis: Long,
    val latestMillis: Long,
)

/** Saturating bounds avoid overflow at either end of Long's range. */
internal fun rejectedOpenTimestampWindow(
    localOpenedAtMillis: Long,
    raceToleranceMillis: Long = REJECTED_OPEN_RACE_TOLERANCE_MILLIS,
): RejectedOpenTimestampWindow {
    val tolerance = raceToleranceMillis.coerceAtLeast(0L)
    val earliest = if (localOpenedAtMillis < Long.MIN_VALUE + tolerance) {
        Long.MIN_VALUE
    } else {
        localOpenedAtMillis - tolerance
    }
    val latest = if (localOpenedAtMillis > Long.MAX_VALUE - tolerance) {
        Long.MAX_VALUE
    } else {
        localOpenedAtMillis + tolerance
    }
    return RejectedOpenTimestampWindow(earliest, latest)
}

/**
 * A server shift can satisfy a rejected local open only when the immutable
 * opening facts identify the same short request race. The bounded timestamp
 * window tolerates modest clock skew in either direction without allowing an
 * older or later drawer lifecycle to absorb this attempt.
 */
internal fun rejectedOpenServerMatch(
    row: LocalShiftEntity?,
    terminalId: String,
    branchId: String,
    serverOpenedByUserId: String?,
    serverOpeningFloatMinor: Long,
    serverOpenedAtMillis: Long,
    raceToleranceMillis: Long = REJECTED_OPEN_RACE_TOLERANCE_MILLIS,
): RejectedOpenRecoveryResult? {
    // ShiftRead does not expose the POST's Idempotency-Key. Do not invent an
    // operation identity: these are the strongest authoritative immutable
    // opening facts available in the current API contract.
    if (row == null || row.state != ShiftState.OPEN_REJECTED || row.serverShiftId != null) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.CHANGED,
            "This rejected shift attempt changed. Refresh and review it again.",
        )
    }
    if (row.terminalId != terminalId || row.branchId != branchId) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.WRONG_SCOPE,
            "This saved shift attempt does not have the exact current branch and terminal identity, so it was not reconciled.",
        )
    }
    val savedOpener = row.openedByUserId?.takeIf(String::isNotBlank)
    val liveOpener = serverOpenedByUserId?.takeIf(String::isNotBlank)
    if (savedOpener == null || liveOpener == null || savedOpener != liveOpener) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT,
            "The current server shift has a different or unverified opener. The saved attempt and its captured work were not linked.",
        )
    }
    if (row.openingFloatMinor != serverOpeningFloatMinor) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT,
            "The current server shift has a different opening float. The saved attempt and its captured work were not linked.",
        )
    }
    val window = rejectedOpenTimestampWindow(row.openedAtMillis, raceToleranceMillis)
    if (serverOpenedAtMillis !in window.earliestMillis..window.latestMillis) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT,
            "The current server shift opened outside the allowed clock-skew window. The saved attempt and its captured work were not linked.",
        )
    }
    return null
}

/** Pure scope/state decision shared by Room recovery code and JVM tests. */
internal fun rejectedOpenRecoveryPrecondition(
    row: LocalShiftEntity?,
    terminalId: String,
    branchId: String,
    serverShiftPresent: Boolean,
): RejectedOpenRecoveryResult? {
    if (row == null || row.state != ShiftState.OPEN_REJECTED || row.serverShiftId != null) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.CHANGED,
            "This rejected shift attempt changed. Refresh and review it again.",
        )
    }
    if (
        (row.terminalId != null && row.terminalId != terminalId) ||
        (row.branchId != null && row.branchId != branchId)
    ) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.WRONG_SCOPE,
            "This saved shift attempt belongs to another branch or terminal and was not changed.",
        )
    }
    if (serverShiftPresent) {
        return RejectedOpenRecoveryResult(
            RejectedOpenRecoveryStatus.SERVER_SHIFT_PRESENT,
            "A current server shift is open on this terminal. Resolve or close it before retrying the saved shift attempt.",
        )
    }
    return null
}

@Dao
interface ShiftDao {

    @Insert
    suspend fun insert(shift: LocalShiftEntity)

    @Query("SELECT * FROM local_shifts WHERE localId = :localId")
    suspend fun byLocalId(localId: String): LocalShiftEntity?

    /**
     * v16 rows are terminal-bound. Null is accepted only for pre-v16 rows,
     * whose original terminal cannot be recovered but whose outbox leg must
     * remain usable after an offline upgrade.
     */
    @Query(
        "SELECT * FROM local_shifts WHERE state IN " +
            "('open_pending', 'open_synced', 'close_pending', 'close_rejected') " +
            "AND (terminalId IS NULL OR terminalId = :terminalId) " +
            "ORDER BY openedAtMillis DESC LIMIT 1",
    )
    fun observeCurrentForTerminal(terminalId: String): Flow<LocalShiftEntity?>

    @Query(
        "SELECT * FROM local_shifts WHERE state IN " +
            "('open_pending', 'open_synced', 'close_pending', 'close_rejected') " +
            "AND (terminalId IS NULL OR terminalId = :terminalId) " +
            "ORDER BY openedAtMillis DESC LIMIT 1",
    )
    suspend fun currentForTerminal(terminalId: String): LocalShiftEntity?

    @Query("SELECT * FROM server_open_shift_cache WHERE terminalId = :terminalId LIMIT 1")
    fun observeServerOpen(terminalId: String): Flow<ServerOpenShiftEntity?>

    @Query("SELECT * FROM server_open_shift_cache WHERE terminalId = :terminalId LIMIT 1")
    suspend fun serverOpen(terminalId: String): ServerOpenShiftEntity?

    @Query("SELECT * FROM local_shifts WHERE state = 'closed' ORDER BY closedAtMillis DESC LIMIT :limit")
    fun observeHistory(limit: Int = 30): Flow<List<LocalShiftEntity>>

    @Query(
        "SELECT * FROM local_shifts WHERE state = 'closed' " +
            "AND (terminalId IS NULL OR terminalId = :terminalId) " +
            "ORDER BY openedAtMillis DESC LIMIT :limit",
    )
    fun observeLocalHistoryForTerminal(
        terminalId: String,
        limit: Int = 200,
    ): Flow<List<LocalShiftEntity>>

    @Query(
        "SELECT * FROM shift_history_cache WHERE terminalId = :terminalId " +
            "AND status = 'closed' ORDER BY openedAtMillis DESC LIMIT :limit",
    )
    fun observeServerHistoryForTerminal(
        terminalId: String,
        limit: Int = 200,
    ): Flow<List<ShiftHistoryCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServerHistory(rows: List<ShiftHistoryCacheEntity>)

    @Query("DELETE FROM shift_history_cache WHERE terminalId = :terminalId")
    suspend fun deleteServerHistoryForTerminal(terminalId: String)

    @Transaction
    suspend fun replaceServerHistoryForTerminal(
        terminalId: String,
        rows: List<ShiftHistoryCacheEntity>,
    ) {
        deleteServerHistoryForTerminal(terminalId)
        upsertServerHistory(rows)
    }

    @Query("SELECT COUNT(*) FROM local_shifts WHERE state IN ('open_rejected', 'close_rejected')")
    fun observeRejectedCount(): Flow<Int>

    /**
     * The most recent refused open/close attempt, so the screen has
     * something to show — previously a rejected row was excluded from both
     * `observeCurrent` and `observeHistory`, so a refused attempt vanished
     * with zero feedback: the screen just reset to "no shift open" as if
     * nothing had happened.
     */
    @Query(
        "SELECT * FROM local_shifts WHERE state = 'open_rejected' " +
            "AND (terminalId IS NULL OR terminalId = :terminalId) " +
            "ORDER BY openedAtMillis DESC LIMIT 1",
    )
    fun observeLatestRejectedForTerminal(terminalId: String): Flow<LocalShiftEntity?>

    /**
     * Rows with a leg still to send: an open not yet confirmed, or a close
     * requested (which may itself be waiting on that same open — see
     * SyncEngine.pushShiftOpen). Oldest first, so a shift closed in a hurry
     * doesn't jump ahead of one still waiting on its open leg.
     */
    @Query(
        "SELECT * FROM local_shifts WHERE serverShiftId IS NULL " +
            "AND state IN ('open_pending', 'close_pending') ORDER BY openedAtMillis ASC",
    )
    suspend fun pushableOpens(): List<LocalShiftEntity>

    @Query(
        "SELECT * FROM local_shifts WHERE serverShiftId IS NOT NULL " +
            "AND state = 'close_pending' ORDER BY openedAtMillis ASC",
    )
    suspend fun pushableCloses(): List<LocalShiftEntity>

    /** Unconditional — fills the id whether the row is still open_pending or has already moved to close_pending. */
    @Query("UPDATE local_shifts SET serverShiftId = :serverShiftId WHERE localId = :localId")
    suspend fun setServerShiftId(localId: String, serverShiftId: String)

    /** Guarded by `fromState` so a close requested mid-flight is never downgraded back to open_synced. */
    @Query("UPDATE local_shifts SET state = :toState WHERE localId = :localId AND state = :fromState")
    suspend fun transitionState(localId: String, fromState: String, toState: String)

    @Query(
        "UPDATE local_shifts SET state = 'close_pending', countedMinor = :countedMinor, " +
            "closedAtMillis = :closedAtMillis, closeResultPending = 1 WHERE localId = :localId " +
            "AND state IN ('open_pending', 'open_synced')",
    )
    suspend fun requestClose(localId: String, countedMinor: Long, closedAtMillis: Long): Int

    @Query(
        "UPDATE local_shifts SET state = 'closed', varianceMinor = :varianceMinor, lastError = NULL " +
            "WHERE localId = :localId",
    )
    suspend fun markClosed(localId: String, varianceMinor: Long)

    @Query("UPDATE local_shifts SET state = 'open_rejected', lastError = :error WHERE localId = :localId")
    suspend fun markOpenRejected(localId: String, error: String)

    @Query("UPDATE local_shifts SET state = 'close_rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCloseRejected(localId: String, error: String)

    @Query("UPDATE local_shifts SET state = 'close_pending', lastError = NULL WHERE localId = :localId AND state = 'close_rejected'")
    suspend fun retryClose(localId: String): Int

    @Query(
        "UPDATE local_shifts SET state = CASE WHEN serverShiftId IS NULL " +
            "THEN 'open_pending' ELSE 'open_synced' END, " +
            "countedMinor = NULL, closedAtMillis = NULL, " +
            "lastError = NULL, closeResultPending = 0 " +
            "WHERE localId = :localId AND state = 'close_rejected'",
    )
    suspend fun cancelRejectedClose(localId: String): Int

    @Query(
        "UPDATE local_shifts SET closeResultPending = 0 " +
            "WHERE localId = :localId AND state = 'closed' AND closeResultPending = 1",
    )
    suspend fun acknowledgeCloseResult(localId: String): Int

    @Query(
        "UPDATE local_shifts SET state = 'open_pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'open_rejected' AND serverShiftId IS NULL",
    )
    suspend fun markRejectedOpenPending(localId: String): Int

    /**
     * Counts every local record that names this local shift identity, not just
     * unresolved rows. A rejected open cannot be cleared while any sale,
     * session, table bill or money workflow may still depend on its stable id.
     */
    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM local_orders WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_table_orders WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_cafe_bills WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_gaming_sessions WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_refunds WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_subscriptions WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_membership_refunds WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_membership_payment_actions WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_membership_refund_actions WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM local_held_order_payments WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM membership_payment_task_cache WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM membership_refund_task_cache WHERE shiftId = :localShiftId) +
          (SELECT COUNT(*) FROM membership_refund_attempt_cache WHERE sourceShiftId = :localShiftId)
        """,
    )
    suspend fun exactDependentRecordCount(localShiftId: String): Int

    @Query("SELECT lastSyncMillis FROM sync_meta WHERE `key` = 'shifts' LIMIT 1")
    suspend fun lastLiveShiftVerificationMillis(): Long?

    @Query(
        "UPDATE local_shifts SET state = 'open_discarded', " +
            "lastError = CASE WHEN lastError IS NULL OR trim(lastError) = '' THEN :verificationNote " +
            "ELSE lastError || ' ' || :verificationNote END WHERE localId = :localId " +
            "AND state = 'open_rejected' AND serverShiftId IS NULL",
    )
    suspend fun markRejectedOpenDiscarded(localId: String, verificationNote: String): Int

    @Transaction
    suspend fun retryRejectedOpen(
        localId: String,
        terminalId: String,
        branchId: String,
    ): RejectedOpenRecoveryResult {
        val row = byLocalId(localId)
        rejectedOpenRecoveryPrecondition(
            row = row,
            terminalId = terminalId,
            branchId = branchId,
            serverShiftPresent = serverOpen(terminalId) != null,
        )?.let { return it }
        return if (markRejectedOpenPending(localId) == 1) {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.APPLIED,
                "The same saved shift attempt is queued again with its original identity.",
            )
        } else {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.CHANGED,
                "This rejected shift attempt changed before retry. Refresh and review it again.",
            )
        }
    }

    /**
     * Called only inside SyncEngine's scoped transaction immediately after a
     * successful live GET returned no open shift. The replaceable server cache
     * is rechecked here and the durable outbox row is preserved as discarded.
     */
    @Transaction
    suspend fun discardVerifiedRejectedOpen(
        localId: String,
        terminalId: String,
        branchId: String,
        verifiedAtMillis: Long,
    ): RejectedOpenRecoveryResult {
        val row = byLocalId(localId)
        rejectedOpenRecoveryPrecondition(
            row = row,
            terminalId = terminalId,
            branchId = branchId,
            serverShiftPresent = serverOpen(terminalId) != null,
        )?.let { return it }
        if (lastLiveShiftVerificationMillis() != verifiedAtMillis) {
            return RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.LIVE_VERIFICATION_REQUIRED,
                "A fresh live server check has not been recorded. The rejected shift attempt was not changed.",
            )
        }
        val dependentCount = exactDependentRecordCount(localId)
        if (dependentCount != 0) {
            return RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.DEPENDENT_WORK,
                "This shift attempt still has $dependentCount captured record(s). It was not removed; retry the original shift so that work can reconcile.",
            )
        }
        val note =
            "Cleared after a live server check at $verifiedAtMillis confirmed no open shift on this terminal."
        return if (markRejectedOpenDiscarded(localId, note) == 1) {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.APPLIED,
                "Live verification confirmed that no shift exists on this terminal. The rejected attempt was cleared safely.",
            )
        } else {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.CHANGED,
                "This rejected shift attempt changed during verification. Refresh and review it again.",
            )
        }
    }

    /**
     * A pre-v16 pending open is itself part of the ownership-gated outbox.
     * Once that gate has authenticated its owner, fill only missing metadata;
     * the unresolved state, idempotency key and close intent are untouched.
     */
    @Query(
        "UPDATE local_shifts SET openedByUserId = :userId, openedByName = :name, " +
            "openedByEmail = :email, terminalId = COALESCE(terminalId, :terminalId), " +
            "branchId = COALESCE(branchId, :branchId) " +
            "WHERE state = 'open_pending' AND openedByUserId IS NULL",
    )
    suspend fun attributeLegacyPendingOpen(
        userId: String,
        name: String,
        email: String,
        terminalId: String?,
        branchId: String?,
    ): Int

    @Query(
        "UPDATE local_shifts SET serverShiftId = :serverShiftId, state = 'open_superseded', " +
            "lastError = CASE WHEN lastError IS NULL OR trim(lastError) = '' THEN :note " +
            "ELSE lastError || ' ' || :note END " +
            "WHERE localId = :localId AND state = 'open_rejected' AND serverShiftId IS NULL " +
            "AND terminalId = :terminalId AND branchId = :branchId " +
            "AND openedByUserId = :serverOpenedByUserId " +
            "AND openingFloatMinor = :serverOpeningFloatMinor " +
            "AND openedAtMillis = :expectedLocalOpenedAtMillis " +
            "AND :serverOpenedAtMillis BETWEEN :earliestServerOpenedAtMillis AND :latestServerOpenedAtMillis",
    )
    suspend fun linkSelectedRejectedOpenIfCausal(
        localId: String,
        terminalId: String,
        branchId: String,
        expectedLocalOpenedAtMillis: Long,
        serverShiftId: String,
        serverOpenedByUserId: String,
        serverOpeningFloatMinor: Long,
        serverOpenedAtMillis: Long,
        earliestServerOpenedAtMillis: Long,
        latestServerOpenedAtMillis: Long,
        note: String,
    ): Int

    @Transaction
    suspend fun reconcileSelectedRejectedOpen(
        localId: String,
        terminalId: String,
        branchId: String,
        serverShiftId: String,
        serverOpenedByUserId: String?,
        serverOpeningFloatMinor: Long,
        serverOpenedAtMillis: Long,
        raceToleranceMillis: Long = REJECTED_OPEN_RACE_TOLERANCE_MILLIS,
    ): RejectedOpenRecoveryResult {
        val row = byLocalId(localId)
        rejectedOpenServerMatch(
            row = row,
            terminalId = terminalId,
            branchId = branchId,
            serverOpenedByUserId = serverOpenedByUserId,
            serverOpeningFloatMinor = serverOpeningFloatMinor,
            serverOpenedAtMillis = serverOpenedAtMillis,
            raceToleranceMillis = raceToleranceMillis,
        )?.let { return it }
        val selected = requireNotNull(row)
        val expectedOpenedAt = selected.openedAtMillis
        val verifiedOpener = requireNotNull(serverOpenedByUserId?.takeIf(String::isNotBlank))
        val window = rejectedOpenTimestampWindow(expectedOpenedAt, raceToleranceMillis)
        val note =
            "Explicit recovery linked this attempt to the live shift after exact branch, terminal, opener, opening-float, and bounded opening-time validation."
        return if (
            linkSelectedRejectedOpenIfCausal(
                localId = localId,
                terminalId = terminalId,
                branchId = branchId,
                expectedLocalOpenedAtMillis = expectedOpenedAt,
                serverShiftId = serverShiftId,
                serverOpenedByUserId = verifiedOpener,
                serverOpeningFloatMinor = serverOpeningFloatMinor,
                serverOpenedAtMillis = serverOpenedAtMillis,
                earliestServerOpenedAtMillis = window.earliestMillis,
                latestServerOpenedAtMillis = window.latestMillis,
                note = note,
            ) == 1
        ) {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.APPLIED,
                "The saved attempt was reconciled to the shift that was already open during the same opening race.",
            )
        } else {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.CHANGED,
                "This rejected shift attempt changed during reconciliation. Refresh and review it again.",
            )
        }
    }

    /**
     * Resolve one explicitly selected rejected attempt against the exact live
     * server snapshot recorded by SyncEngine. A matching opening race is
     * linked. A different drawer lifecycle is discarded only when no captured
     * record references the rejected local identity; otherwise it remains
     * blocked for staff recovery.
     */
    @Transaction
    suspend fun resolveRejectedOpenAgainstVerifiedServer(
        localId: String,
        terminalId: String,
        branchId: String,
        serverShiftId: String,
        serverOpenedByUserId: String?,
        serverOpeningFloatMinor: Long,
        serverOpenedAtMillis: Long,
        verifiedAtMillis: Long,
        raceToleranceMillis: Long = REJECTED_OPEN_RACE_TOLERANCE_MILLIS,
    ): RejectedOpenRecoveryResult {
        val cached = serverOpen(terminalId)
        if (
            lastLiveShiftVerificationMillis() != verifiedAtMillis ||
            cached == null ||
            cached.verifiedAtMillis != verifiedAtMillis ||
            cached.serverShiftId != serverShiftId ||
            cached.terminalId != terminalId ||
            cached.branchId != branchId ||
            cached.status != "open" ||
            cached.openedByUserId != serverOpenedByUserId ||
            cached.openingFloatMinor != serverOpeningFloatMinor ||
            cached.openedAtMillis != serverOpenedAtMillis
        ) {
            return RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.LIVE_VERIFICATION_REQUIRED,
                "The current live shift snapshot changed during recovery. The saved attempt was not changed.",
            )
        }

        val reconciliation = reconcileSelectedRejectedOpen(
            localId = localId,
            terminalId = terminalId,
            branchId = branchId,
            serverShiftId = serverShiftId,
            serverOpenedByUserId = serverOpenedByUserId,
            serverOpeningFloatMinor = serverOpeningFloatMinor,
            serverOpenedAtMillis = serverOpenedAtMillis,
            raceToleranceMillis = raceToleranceMillis,
        )
        if (reconciliation.status != RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT) {
            return reconciliation
        }

        val dependentCount = exactDependentRecordCount(localId)
        if (dependentCount != 0) {
            return RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.DEPENDENT_WORK,
                "The current live shift is a different drawer lifecycle, and the saved attempt still has $dependentCount captured record(s). It remains blocked. Resolve or close the current shift before retrying the saved attempt.",
            )
        }
        val note =
            "Cleared after live verification at $verifiedAtMillis found a different current shift and confirmed that no captured record referenced this saved attempt."
        return if (markRejectedOpenDiscarded(localId, note) == 1) {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.DISCARDED,
                "Live verification found a different current shift and no captured work for this older attempt. Only the empty saved attempt was cleared; the current shift was not changed.",
            )
        } else {
            RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.CHANGED,
                "This rejected shift attempt changed during verification. Refresh and review it again.",
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServerOpen(shift: ServerOpenShiftEntity)

    @Query("DELETE FROM server_open_shift_cache WHERE terminalId = :terminalId")
    suspend fun deleteServerOpen(terminalId: String)

    @Query(
        "UPDATE local_shifts SET state = 'closed', closedAtMillis = :closedAtMillis, " +
            "lastError = :reason, closeResultPending = 1 WHERE localId = :localId " +
            "AND state IN ('open_synced', 'close_rejected')",
    )
    suspend fun markRemotelyClosed(localId: String, closedAtMillis: Long, reason: String): Int

    /**
     * Replace only the read cache and reconcile local states that have no
     * unresolved write leg. OPEN_PENDING/CLOSE_PENDING are intentionally never
     * rewritten by a pull: their stable idempotent operation still has to be
     * replayed before the server result is known.
     */
    @Transaction
    suspend fun reconcileServerOpen(
        terminalId: String,
        server: ServerOpenShiftEntity?,
        observedAtMillis: Long,
    ) {
        val local = currentForTerminal(terminalId)
        if (server == null) {
            deleteServerOpen(terminalId)
            if (
                local?.serverShiftId != null &&
                local.state in setOf(ShiftState.OPEN_SYNCED, ShiftState.CLOSE_REJECTED)
            ) {
                markRemotelyClosed(
                    local.localId,
                    observedAtMillis,
                    "Server reconciliation confirmed this shift is no longer open.",
                )
            }
            return
        }

        upsertServerOpen(server)
        if (
            local?.serverShiftId != null &&
            local.serverShiftId != server.serverShiftId &&
            local.state in setOf(ShiftState.OPEN_SYNCED, ShiftState.CLOSE_REJECTED)
        ) {
            markRemotelyClosed(
                local.localId,
                observedAtMillis,
                "Another server shift is now open on this terminal; this local shift was closed elsewhere.",
            )
        }
    }
}
