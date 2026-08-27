package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase

data class ShiftCloseBlockerCounts(
    val pendingLocalCount: Int,
    val attentionLocalCount: Int,
    val serverWorkflowCount: Int,
    val unscopedAttentionCount: Int,
) {
    val captureBlockerCount: Int
        get() = attentionLocalCount + serverWorkflowCount + unscopedAttentionCount

    val serverPostBlockerCount: Int
        get() = pendingLocalCount + captureBlockerCount

    fun captureMessage(): String? {
        if (captureBlockerCount == 0) return null
        return "Shift close blocked: $attentionLocalCount saved action(s) need recovery, " +
            "$serverWorkflowCount server-confirmed task(s) still need completion, and " +
            "$unscopedAttentionCount older money record(s) lack exact shift provenance. " +
            "Resolve the warnings in POS, Gaming, Refunds, or Memberships, then count the drawer again."
    }

    fun serverPostMessage(): String? {
        if (serverPostBlockerCount == 0) return null
        return "Shift close was paused before contacting the server: $pendingLocalCount saved " +
            "action(s) still need server confirmation and $captureBlockerCount action(s) need " +
            "staff recovery. Continue the shift, resolve them, then review and retry the drawer count."
    }
}

enum class ShiftCloseCaptureStatus { CAPTURED, BLOCKED, CHANGED }

data class ShiftCloseCaptureResult(
    val status: ShiftCloseCaptureStatus,
    val message: String? = null,
)

/**
 * One DB-authoritative gate for close capture and the final server POST.
 *
 * Pending writes may exist when an offline shift captures its close intent;
 * the ordered sync pass sends those writes first. Rejected/ambiguous rows and
 * unfinished human workflows block capture. Immediately before POST /close,
 * every pending row blocks too. The current shift's own open/close outbox row
 * is deliberately absent from this query, so offline open -> work -> close is
 * supported without treating the close intent as its own blocker.
 */
@Dao
interface ShiftCloseSafetyDao {

    @Query(
        """
        SELECT
          (
            (SELECT COUNT(*) FROM local_orders
              WHERE syncState = 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_table_orders
              WHERE state = 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_cafe_actions a
              JOIN local_cafe_bills b ON b.localBillId = a.localBillId
              WHERE a.state = 'pending' AND
                (b.shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND b.shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_gaming_sessions
              WHERE state IN ('start_pending', 'stop_pending', 'send_pending') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_gaming_package_extensions
              WHERE state = 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_refunds
              WHERE state IN ('request_pending', 'cash_settle_pending', 'withdrawal_pending') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId)
                 OR (:serverShiftId IS NOT NULL AND serverShiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_subscriptions
              WHERE syncState = 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_membership_refunds
              WHERE syncState IN ('request_pending', 'cash_settle_pending', 'withdrawal_pending') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_membership_payment_actions
              WHERE state = 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_membership_refund_actions
              WHERE state = 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_held_order_payments
              WHERE syncState = 'pending' AND terminalId = :terminalId AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_gaming_sessions
              WHERE state IN ('start_pending', 'stop_pending', 'send_pending') AND shiftId IS NULL)
          ) AS pendingLocalCount,
          (
            (SELECT COUNT(*) FROM local_orders
              WHERE syncState NOT IN ('pending', 'synced') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_table_orders
              WHERE state <> 'pending' AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_cafe_actions a
              JOIN local_cafe_bills b ON b.localBillId = a.localBillId
              WHERE a.state <> 'pending' AND
                (b.shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND b.shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_gaming_sessions
              WHERE state NOT IN ('start_pending', 'stop_pending', 'send_pending', 'sent', 'cancelled', 'legacy_resolved') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_gaming_package_extensions
              WHERE state NOT IN ('pending', 'confirmed', 'discarded') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_refunds
              WHERE state NOT IN ('request_pending', 'cash_settle_pending', 'withdrawal_pending',
                                  'settled', 'withdrawn', 'cancelled', 'synced') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId)
                 OR (:serverShiftId IS NOT NULL AND serverShiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_subscriptions
              WHERE syncState NOT IN ('pending', 'synced', 'migrated_v21') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_membership_refunds
              WHERE syncState NOT IN ('request_pending', 'cash_settle_pending', 'withdrawal_pending',
                                      'synced', 'withdrawn', 'migrated_v22') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_membership_payment_actions
              WHERE state NOT IN ('pending', 'synced') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_membership_refund_actions
              WHERE state NOT IN ('pending', 'synced') AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_held_order_payments
              WHERE syncState NOT IN ('pending', 'synced') AND terminalId = :terminalId AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM local_gaming_sessions
              WHERE state NOT IN ('start_pending', 'stop_pending', 'send_pending', 'sent', 'cancelled', 'legacy_resolved') AND
                shiftId IS NULL)
          ) AS attentionLocalCount,
          (
            (SELECT COUNT(*) FROM membership_payment_task_cache
              WHERE status NOT IN ('settled', 'withdrawn') AND terminalId = :terminalId AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM membership_refund_task_cache
              WHERE status NOT IN ('settled', 'withdrawn') AND terminalId = :terminalId AND
                (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) +
            (SELECT COUNT(*) FROM membership_refund_attempt_cache
              WHERE status = 'unresolved' AND terminalId = :terminalId AND
                (sourceShiftId = :localShiftId OR
                 (:serverShiftId IS NOT NULL AND sourceShiftId = :serverShiftId)))
          ) AS serverWorkflowCount,
          (
            (SELECT COUNT(*) FROM local_refunds WHERE state = 'legacy_reconciliation_required') +
            (SELECT COUNT(*) FROM local_membership_payment_actions
              WHERE state <> 'synced' AND (shiftId IS NULL OR trim(shiftId) = '')) +
            (SELECT COUNT(*) FROM local_membership_refund_actions
              WHERE state <> 'synced' AND trim(shiftId) = '') +
            (SELECT COUNT(*) FROM local_held_order_payments
              WHERE syncState <> 'synced' AND (shiftId IS NULL OR terminalId IS NULL)) +
            (SELECT COUNT(*) FROM local_gaming_package_extensions
              WHERE state NOT IN ('confirmed', 'discarded') AND
                (shiftId IS NULL OR trim(shiftId) = ''))
          ) AS unscopedAttentionCount
        """,
    )
    suspend fun blockersForExactShift(
        localShiftId: String,
        serverShiftId: String?,
        terminalId: String,
    ): ShiftCloseBlockerCounts

    @Query("SELECT * FROM local_shifts WHERE localId = :localId LIMIT 1")
    suspend fun localShift(localId: String): LocalShiftEntity?

    @Query(
        "SELECT * FROM local_shifts WHERE state IN ('close_pending', 'close_rejected') " +
            "ORDER BY openedAtMillis ASC",
    )
    suspend fun closeIntentsForCountPreflight(): List<LocalShiftEntity>

    @Query(
        "SELECT COUNT(*) FROM local_shifts WHERE state IN " +
            "('open_pending', 'open_synced', 'close_pending', 'close_rejected') " +
            "AND (terminalId IS NULL OR terminalId = :terminalId)",
    )
    suspend fun currentShiftCountForTerminal(terminalId: String): Int

    @Query(
        "UPDATE local_shifts SET state = 'close_pending', countedMinor = :countedMinor, " +
            "closedAtMillis = :closedAtMillis, lastError = NULL, closeResultPending = 1 " +
            "WHERE localId = :localId " +
            "AND state IN ('open_pending', 'open_synced')",
    )
    suspend fun markExistingClosePending(
        localId: String,
        countedMinor: Long,
        closedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_shifts SET state = 'close_pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'close_rejected'",
    )
    suspend fun markRetryClosePending(localId: String): Int

    @Query(
        "UPDATE local_shifts SET state = 'close_rejected', lastError = :error " +
            "WHERE localId = :localId AND state IN ('close_pending', 'close_rejected')",
    )
    suspend fun markInvalidCloseCount(localId: String, error: String): Int

    /**
     * Atomically rejects every malformed close intent before SyncEngine is
     * permitted to push shift opens. This deliberately includes unsynced
     * closes whose serverShiftId is still null.
     */
    @Transaction
    suspend fun rejectInvalidCloseIntentsBeforeNetwork(): List<ShiftClosePreflightRejection> {
        val rejections = closeIntentsForCountPreflight()
            .mapNotNull { row -> ShiftCloseCountPolicy.preflightRejection(row) }
        rejections.forEach { rejection ->
            markInvalidCloseCount(rejection.localId, rejection.message)
        }
        return rejections
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdoptedClose(row: LocalShiftEntity)

    @Transaction
    suspend fun captureExistingClose(
        localId: String,
        terminalId: String,
        countedMinor: Long,
        closedAtMillis: Long,
    ): ShiftCloseCaptureResult {
        val row = localShift(localId)
            ?: return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "This shift changed before the drawer count was saved. Refresh and review it again.",
            )
        if (row.state !in setOf(ShiftState.OPEN_PENDING, ShiftState.OPEN_SYNCED)) {
            return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "This shift is no longer ready to close. Refresh and review its current status.",
            )
        }
        if (row.terminalId != null && row.terminalId != terminalId) {
            return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "This shift belongs to another terminal and was not changed.",
            )
        }
        val countValidation = ShiftCloseCountPolicy.validate(countedMinor)
        if (countValidation is ShiftCloseCountValidation.Invalid) {
            return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.BLOCKED,
                countValidation.message,
            )
        }
        blockersForExactShift(row.localId, row.serverShiftId, terminalId).captureMessage()?.let {
            return ShiftCloseCaptureResult(ShiftCloseCaptureStatus.BLOCKED, it)
        }
        return if (markExistingClosePending(localId, countedMinor, closedAtMillis) == 1) {
            ShiftCloseCaptureResult(ShiftCloseCaptureStatus.CAPTURED)
        } else {
            ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "This shift changed before the drawer count was saved. Refresh and review it again.",
            )
        }
    }

    @Transaction
    suspend fun captureAdoptedClose(row: LocalShiftEntity): ShiftCloseCaptureResult {
        val terminalId = row.terminalId
            ?: return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "This shift has no verified terminal and cannot be safely closed.",
            )
        val serverShiftId = row.serverShiftId
            ?: return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "This server shift has no verified reference and cannot be safely closed.",
            )
        val countValidation = ShiftCloseCountPolicy.validate(row.countedMinor)
        if (countValidation is ShiftCloseCountValidation.Invalid) {
            return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.BLOCKED,
                countValidation.message,
            )
        }
        if (currentShiftCountForTerminal(terminalId) != 0) {
            return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.CHANGED,
                "A local shift state appeared before close was saved. Refresh and review it again.",
            )
        }
        blockersForExactShift(row.localId, serverShiftId, terminalId).captureMessage()?.let {
            return ShiftCloseCaptureResult(ShiftCloseCaptureStatus.BLOCKED, it)
        }
        insertAdoptedClose(row)
        return ShiftCloseCaptureResult(ShiftCloseCaptureStatus.CAPTURED)
    }

    @Transaction
    suspend fun retryRejectedClose(
        localId: String,
        terminalId: String,
    ): ShiftCloseCaptureResult {
        val row = localShift(localId)
            ?: return ShiftCloseCaptureResult(ShiftCloseCaptureStatus.CHANGED, "This close no longer exists.")
        if (row.state != ShiftState.CLOSE_REJECTED) {
            return ShiftCloseCaptureResult(ShiftCloseCaptureStatus.CHANGED, "This close is no longer rejected.")
        }
        val countValidation = ShiftCloseCountPolicy.validate(row.countedMinor)
        if (countValidation is ShiftCloseCountValidation.Invalid) {
            markInvalidCloseCount(row.localId, countValidation.message)
            return ShiftCloseCaptureResult(
                ShiftCloseCaptureStatus.BLOCKED,
                countValidation.message,
            )
        }
        blockersForExactShift(row.localId, row.serverShiftId, terminalId).captureMessage()?.let {
            return ShiftCloseCaptureResult(ShiftCloseCaptureStatus.BLOCKED, it)
        }
        return if (markRetryClosePending(localId) == 1) {
            ShiftCloseCaptureResult(ShiftCloseCaptureStatus.CAPTURED)
        } else {
            ShiftCloseCaptureResult(ShiftCloseCaptureStatus.CHANGED, "This close changed before retry.")
        }
    }
}

internal const val SHIFT_CLOSING_WRITE_GUARD = "shift_closing_write_blocked"

internal fun Throwable.shiftClosingMessageOr(fallback: String): String {
    var current: Throwable? = this
    while (current != null) {
        if (current.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD)) {
            return "This shift started closing before the change could be saved. No new work " +
                "was recorded; open or continue the shift, then try again."
        }
        current = current.cause
    }
    return fallback
}

/** Install on both migration and every open so a brand-new v23 database gets the same guards. */
internal fun installShiftClosingWriteGuards(db: SupportSQLiteDatabase) {
    data class GuardedTable(
        val table: String,
        val shiftExpression: String,
        val pendingCondition: String,
    )

    val guardedTables = listOf(
        GuardedTable(
            "local_orders",
            "NEW.shiftId",
            "NEW.syncState IN ('draft', 'preparing', 'awaiting_payment', 'pending')",
        ),
        GuardedTable("local_table_orders", "NEW.shiftId", "NEW.state = 'pending'"),
        GuardedTable(
            "local_gaming_sessions",
            "NEW.shiftId",
            "NEW.state IN ('start_pending', 'stop_pending', 'send_pending')",
        ),
        GuardedTable(
            "local_gaming_package_extensions",
            "NEW.shiftId",
            "NEW.state = 'pending'",
        ),
        GuardedTable(
            "local_refunds",
            "NEW.shiftId",
            "NEW.state IN ('request_pending', 'cash_settle_pending', 'withdrawal_pending')",
        ),
        GuardedTable("local_subscriptions", "NEW.shiftId", "NEW.syncState = 'pending'"),
        GuardedTable(
            "local_membership_refunds",
            "NEW.shiftId",
            "NEW.syncState IN ('request_pending', 'cash_settle_pending', 'withdrawal_pending')",
        ),
        GuardedTable(
            "local_membership_payment_actions",
            "NEW.shiftId",
            "NEW.state = 'pending'",
        ),
        GuardedTable(
            "local_membership_refund_actions",
            "NEW.shiftId",
            "NEW.state = 'pending'",
        ),
        GuardedTable(
            "local_held_order_payments",
            "NEW.shiftId",
            "NEW.syncState = 'pending'",
        ),
    )
    for ((table, shiftExpression, pendingCondition) in guardedTables) {
        // This installer also runs from older stepwise migrations. Newer
        // guarded outbox tables may not exist until a later migration.
        val tableExists = db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }
        if (!tableExists) continue
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `guard_${table}_while_shift_closing`
            BEFORE INSERT ON `$table`
            WHEN $shiftExpression IS NOT NULL AND ($pendingCondition) AND EXISTS (
                SELECT 1 FROM `local_shifts` s
                WHERE s.`state` IN ('close_pending', 'closed')
                  AND (s.`localId` = $shiftExpression OR s.`serverShiftId` = $shiftExpression)
            )
            BEGIN
                SELECT RAISE(ABORT, '$SHIFT_CLOSING_WRITE_GUARD');
            END
            """.trimIndent(),
        )
        // A stale screen can also turn an existing lifecycle row into a new
        // stop/send/settle/retry action after close was captured. Sync updates
        // from an already-pending state remain allowed so offline work can
        // drain before POST /close; only a transition *into* a pending action
        // is refused.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `guard_${table}_update_while_shift_closing`
            BEFORE UPDATE ON `$table`
            WHEN $shiftExpression IS NOT NULL AND ($pendingCondition) AND NOT (
                ${pendingCondition.replace("NEW.", "OLD.")}
            ) AND EXISTS (
                SELECT 1 FROM `local_shifts` s
                WHERE s.`state` IN ('close_pending', 'closed')
                  AND (s.`localId` = $shiftExpression OR s.`serverShiftId` = $shiftExpression)
            )
            BEGIN
                SELECT RAISE(ABORT, '$SHIFT_CLOSING_WRITE_GUARD');
            END
            """.trimIndent(),
        )
    }

    // v24 cafe actions carry their shift through the local bill header. Keep
    // this conditional because MIGRATION_22_23 invokes this installer before
    // the v24 tables exist when an older database upgrades through both steps.
    val hasCafeActions = db.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'local_cafe_actions' LIMIT 1",
    ).use { it.moveToFirst() }
    if (hasCafeActions) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `guard_local_cafe_actions_while_shift_closing`
            BEFORE INSERT ON `local_cafe_actions`
            WHEN NEW.`state` = 'pending' AND EXISTS (
                SELECT 1
                  FROM `local_cafe_bills` b
                  JOIN `local_shifts` s
                    ON s.`localId` = b.`shiftId` OR s.`serverShiftId` = b.`shiftId`
                 WHERE b.`localBillId` = NEW.`localBillId`
                   AND s.`state` IN ('close_pending', 'closed')
            )
            BEGIN
                SELECT RAISE(ABORT, '$SHIFT_CLOSING_WRITE_GUARD');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS `guard_local_cafe_actions_update_while_shift_closing`
            BEFORE UPDATE ON `local_cafe_actions`
            WHEN NEW.`state` = 'pending' AND OLD.`state` <> 'pending' AND EXISTS (
                SELECT 1
                  FROM `local_cafe_bills` b
                  JOIN `local_shifts` s
                    ON s.`localId` = b.`shiftId` OR s.`serverShiftId` = b.`shiftId`
                 WHERE b.`localBillId` = NEW.`localBillId`
                   AND s.`state` IN ('close_pending', 'closed')
            )
            BEGIN
                SELECT RAISE(ABORT, '$SHIFT_CLOSING_WRITE_GUARD');
            END
            """.trimIndent(),
        )
    }
}

val SHIFT_CLOSING_WRITE_GUARD_CALLBACK = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        installShiftClosingWriteGuards(db)
    }
}
