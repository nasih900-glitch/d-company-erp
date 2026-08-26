package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MembershipPaymentDao {

    @Query(
        "SELECT * FROM membership_payment_task_cache " +
            "WHERE status NOT IN ('settled', 'withdrawn') ORDER BY acceptedAt ASC",
    )
    fun observeUnresolvedTasks(): Flow<List<MembershipPaymentTaskCacheEntity>>

    @Query(
        "SELECT * FROM membership_payment_task_cache " +
            "WHERE status NOT IN ('settled', 'withdrawn') ORDER BY acceptedAt ASC",
    )
    suspend fun unresolvedTasks(): List<MembershipPaymentTaskCacheEntity>

    @Query("SELECT * FROM membership_payment_task_cache WHERE id = :id LIMIT 1")
    suspend fun taskById(id: String): MembershipPaymentTaskCacheEntity?

    @Query(
        "SELECT * FROM membership_payment_task_cache " +
            "WHERE clientActionId = :clientActionId LIMIT 1",
    )
    suspend fun taskByClientActionId(clientActionId: String): MembershipPaymentTaskCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(rows: List<MembershipPaymentTaskCacheEntity>)

    @Query("DELETE FROM membership_payment_task_cache WHERE terminalId = :terminalId")
    suspend fun deleteTasksForTerminal(terminalId: String)

    @Transaction
    suspend fun replaceTasksForTerminal(
        terminalId: String,
        rows: List<MembershipPaymentTaskCacheEntity>,
    ) {
        deleteTasksForTerminal(terminalId)
        upsertTasks(rows)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAction(row: LocalMembershipPaymentActionEntity): Long

    @Query("SELECT * FROM local_membership_payment_actions WHERE actionId = :actionId LIMIT 1")
    suspend fun actionById(actionId: String): LocalMembershipPaymentActionEntity?

    @Query(
        "SELECT * FROM local_membership_payment_actions " +
            "WHERE rootClientActionId = :rootClientActionId AND kind = :kind LIMIT 1",
    )
    suspend fun actionForStage(
        rootClientActionId: String,
        kind: String,
    ): LocalMembershipPaymentActionEntity?

    @Query(
        "SELECT COUNT(*) FROM local_membership_payment_actions " +
            "WHERE customerId = :customerId AND state <> 'synced'",
    )
    suspend fun unresolvedActionCountForCustomer(customerId: String): Int

    @Query(
        "SELECT COUNT(*) FROM membership_payment_task_cache " +
            "WHERE customerId = :customerId AND status NOT IN ('settled', 'withdrawn')",
    )
    suspend fun unresolvedTaskCountForCustomer(customerId: String): Int

    @Query(
        "SELECT * FROM local_membership_payment_actions " +
            "WHERE state IN ('pending', 'ambiguous') ORDER BY createdAtMillis ASC",
    )
    suspend fun pushableActions(): List<LocalMembershipPaymentActionEntity>

    @Query(
        "SELECT * FROM local_membership_payment_actions " +
            "WHERE state <> 'synced' ORDER BY createdAtMillis DESC",
    )
    fun observeUnresolvedActions(): Flow<List<LocalMembershipPaymentActionEntity>>

    @Query(
        "SELECT COUNT(*) FROM local_membership_payment_actions " +
            "WHERE state IN ('rejected', 'legacy_recovery_required', 'legacy_provenance_missing')",
    )
    fun observeAttentionCount(): Flow<Int>

    @Query(
        "UPDATE local_membership_payment_actions SET serverRequestId = :serverRequestId, " +
            "state = 'synced', lastError = NULL WHERE actionId = :actionId " +
            "AND state IN ('pending', 'ambiguous', 'rejected')",
    )
    suspend fun markSynced(actionId: String, serverRequestId: String?): Int

    @Query(
        "UPDATE local_membership_payment_actions SET serverRequestId = COALESCE(serverRequestId, :serverRequestId), " +
            "state = 'ambiguous', lastError = :error WHERE actionId = :actionId AND state <> 'synced'",
    )
    suspend fun markAmbiguous(actionId: String, serverRequestId: String?, error: String): Int

    @Query(
        "UPDATE local_membership_payment_actions SET state = 'rejected', lastError = :error " +
            "WHERE actionId = :actionId AND state <> 'synced'",
    )
    suspend fun markRejected(actionId: String, error: String): Int

    @Query(
        "UPDATE local_membership_payment_actions SET state = 'legacy_recovery_required', " +
            "lastError = :error WHERE actionId = :actionId AND state <> 'synced'",
    )
    suspend fun requireLegacyRecovery(actionId: String, error: String): Int

    @Query(
        "UPDATE local_membership_payment_actions SET state = 'pending', lastError = NULL " +
            "WHERE actionId = :actionId AND state IN ('rejected', 'ambiguous')",
    )
    suspend fun retryAction(actionId: String): Int

    @Query(
        "SELECT COUNT(*) FROM local_membership_payment_actions " +
            "WHERE state <> 'synced' AND shiftId IS NULL",
    )
    suspend fun globalUnknownShiftCount(): Int

    @Query(
        "SELECT COUNT(*) FROM local_membership_payment_actions " +
            "WHERE state <> 'synced' AND " +
            "(shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))",
    )
    suspend fun unresolvedActionCountForShift(localShiftId: String, serverShiftId: String?): Int

    @Query(
        "SELECT COUNT(*) FROM membership_payment_task_cache " +
            "WHERE status NOT IN ('settled', 'withdrawn') AND " +
            "(shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))",
    )
    suspend fun unresolvedTaskCountForShift(localShiftId: String, serverShiftId: String?): Int
}
