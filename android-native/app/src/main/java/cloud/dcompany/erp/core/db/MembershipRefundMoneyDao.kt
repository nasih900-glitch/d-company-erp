package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MembershipRefundMoneyDao {

    @Query(
        "SELECT * FROM membership_refund_task_cache " +
            "WHERE status NOT IN ('settled', 'withdrawn') ORDER BY acceptedAt ASC",
    )
    fun observeUnresolvedTasks(): Flow<List<MembershipRefundTaskCacheEntity>>

    @Query(
        "SELECT * FROM membership_refund_task_cache " +
            "WHERE status NOT IN ('settled', 'withdrawn') ORDER BY acceptedAt ASC",
    )
    suspend fun unresolvedTasks(): List<MembershipRefundTaskCacheEntity>

    @Query("SELECT * FROM membership_refund_task_cache WHERE id = :id LIMIT 1")
    suspend fun taskById(id: String): MembershipRefundTaskCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(rows: List<MembershipRefundTaskCacheEntity>)

    @Query("DELETE FROM membership_refund_task_cache WHERE terminalId = :terminalId")
    suspend fun deleteTasksForTerminal(terminalId: String)

    @Transaction
    suspend fun replaceTasksForTerminal(
        terminalId: String,
        rows: List<MembershipRefundTaskCacheEntity>,
    ) {
        deleteTasksForTerminal(terminalId)
        upsertTasks(rows)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAction(row: LocalMembershipRefundActionEntity): Long

    @Query("SELECT * FROM local_membership_refund_actions WHERE actionId = :id LIMIT 1")
    suspend fun actionById(id: String): LocalMembershipRefundActionEntity?

    /**
     * A v20 refund may already have moved cash/provider value without the v22
     * begin/completion journal.  While that exact server refund is being
     * reconciled, normal begin/complete/withdraw controls must stay locked;
     * otherwise a restart can invite staff to pay the same refund twice.
     */
    @Query(
        "SELECT * FROM local_membership_refund_actions " +
            "WHERE serverRefundId = :refundId AND kind = 'legacy_reconcile_server' " +
            "AND state <> 'synced' LIMIT 1",
    )
    suspend fun unresolvedLegacyActionForServerRefund(
        refundId: String,
    ): LocalMembershipRefundActionEntity?

    @Query(
        "SELECT * FROM local_membership_refund_actions " +
            "WHERE rootClientActionId = :root AND kind = :kind LIMIT 1",
    )
    suspend fun actionForStage(root: String, kind: String): LocalMembershipRefundActionEntity?

    @Query(
        "SELECT * FROM local_membership_refund_actions " +
            "WHERE state IN ('pending', 'ambiguous') ORDER BY createdAtMillis ASC",
    )
    suspend fun pushableActions(): List<LocalMembershipRefundActionEntity>

    @Query(
        "SELECT * FROM local_membership_refund_actions " +
            "WHERE state <> 'synced' ORDER BY createdAtMillis DESC",
    )
    fun observeUnresolvedActions(): Flow<List<LocalMembershipRefundActionEntity>>

    @Query(
        "SELECT COUNT(*) FROM local_membership_refund_actions " +
            "WHERE membershipId = :membershipId AND state <> 'synced'",
    )
    suspend fun unresolvedActionCountForMembership(membershipId: String): Int

    @Query(
        "SELECT COUNT(*) FROM membership_refund_task_cache " +
            "WHERE membershipId = :membershipId AND status NOT IN ('settled', 'withdrawn')",
    )
    suspend fun unresolvedTaskCountForMembership(membershipId: String): Int

    @Query(
        "UPDATE local_membership_refund_actions SET serverRefundId = COALESCE(serverRefundId, :serverId), " +
            "state = 'synced', lastError = NULL WHERE actionId = :id AND state <> 'synced'",
    )
    suspend fun markSynced(id: String, serverId: String?): Int

    @Query(
        "UPDATE local_membership_refund_actions SET serverRefundId = COALESCE(serverRefundId, :serverId), " +
            "state = 'ambiguous', lastError = :error WHERE actionId = :id AND state <> 'synced'",
    )
    suspend fun markAmbiguous(id: String, serverId: String?, error: String): Int

    @Query(
        "UPDATE local_membership_refund_actions SET state = 'rejected', lastError = :error " +
            "WHERE actionId = :id AND state <> 'synced'",
    )
    suspend fun markRejected(id: String, error: String): Int

    @Query(
        "UPDATE local_membership_refund_actions SET state = 'legacy_recovery_required', " +
            "lastError = :error WHERE actionId = :id AND state <> 'synced'",
    )
    suspend fun requireLegacyRecovery(id: String, error: String): Int

    @Query(
        "UPDATE local_membership_refund_actions SET state = 'pending', lastError = NULL " +
            "WHERE actionId = :id AND state IN ('rejected', 'ambiguous')",
    )
    suspend fun retryAction(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttempts(rows: List<MembershipRefundAttemptCacheEntity>)

    @Query("DELETE FROM membership_refund_attempt_cache WHERE terminalId = :terminalId")
    suspend fun deleteAttemptsForTerminal(terminalId: String)

    @Transaction
    suspend fun replaceAttemptsForTerminal(
        terminalId: String,
        rows: List<MembershipRefundAttemptCacheEntity>,
    ) {
        deleteAttemptsForTerminal(terminalId)
        upsertAttempts(rows)
    }

    @Query(
        "SELECT * FROM membership_refund_attempt_cache WHERE status = 'unresolved' " +
            "ORDER BY registeredAt ASC",
    )
    fun observeUnresolvedAttempts(): Flow<List<MembershipRefundAttemptCacheEntity>>

    @Query(
        "SELECT * FROM membership_refund_attempt_cache " +
            "WHERE originalClientActionId = :actionId LIMIT 1",
    )
    suspend fun attemptByOriginalAction(actionId: String): MembershipRefundAttemptCacheEntity?

    @Query(
        "SELECT COUNT(*) FROM local_membership_refund_actions WHERE state <> 'synced' AND " +
            "(shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))",
    )
    suspend fun unresolvedActionCountForShift(localShiftId: String, serverShiftId: String?): Int

    @Query(
        "SELECT COUNT(*) FROM membership_refund_task_cache " +
            "WHERE status NOT IN ('settled', 'withdrawn') AND " +
            "(shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))",
    )
    suspend fun unresolvedTaskCountForShift(localShiftId: String, serverShiftId: String?): Int

    @Query(
        "SELECT COUNT(*) FROM membership_refund_attempt_cache WHERE status = 'unresolved' AND " +
            "(sourceShiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND sourceShiftId = :serverShiftId))",
    )
    suspend fun unresolvedAttemptCountForShift(localShiftId: String, serverShiftId: String?): Int

    @Query("SELECT COUNT(*) FROM local_membership_refund_actions WHERE state <> 'synced' AND shiftId = ''")
    suspend fun globalUnknownShiftCount(): Int
}
