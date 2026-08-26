package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MembershipDao {

    // -------------------------------------------------------------- tier cache
    @Query("SELECT * FROM membership_tier_cache ORDER BY sortOrder ASC, monthlyPriceMinor ASC")
    fun observeTierCache(): Flow<List<MembershipTierCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTierCache(rows: List<MembershipTierCacheEntity>)

    @Query("DELETE FROM membership_tier_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteTierCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceTierCache(rows: List<MembershipTierCacheEntity>) {
        upsertTierCache(rows)
        deleteTierCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // ------------------------------------------------------- customer membership
    @Query("SELECT * FROM customer_membership_cache WHERE customerId = :customerId LIMIT 1")
    fun observeMembershipFor(customerId: String): Flow<CustomerMembershipCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembershipCache(rows: List<CustomerMembershipCacheEntity>)

    @Query("DELETE FROM customer_membership_cache WHERE customerId = :customerId AND id NOT IN (:keepIds)")
    suspend fun deleteMembershipCacheNotIn(customerId: String, keepIds: List<String>)

    /** Replaces this customer's single cached row (or clears it, if [rows] is empty —
     * e.g. their last term expired and nothing active remains). */
    @Transaction
    suspend fun replaceMembershipFor(customerId: String, rows: List<CustomerMembershipCacheEntity>) {
        upsertMembershipCache(rows)
        deleteMembershipCacheNotIn(customerId, rows.map { it.id }.ifEmpty { listOf("") })
    }

    @Query("SELECT * FROM customer_membership_history_cache WHERE customerId = :customerId ORDER BY startsAt DESC")
    fun observeMembershipHistoryFor(customerId: String): Flow<List<CustomerMembershipHistoryCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembershipHistoryCache(rows: List<CustomerMembershipHistoryCacheEntity>)

    @Query("DELETE FROM customer_membership_history_cache WHERE customerId = :customerId AND id NOT IN (:keepIds)")
    suspend fun deleteMembershipHistoryNotIn(customerId: String, keepIds: List<String>)

    @Transaction
    suspend fun replaceMembershipHistoryFor(
        customerId: String,
        rows: List<CustomerMembershipHistoryCacheEntity>,
    ) {
        upsertMembershipHistoryCache(rows)
        deleteMembershipHistoryNotIn(customerId, rows.map { it.id }.ifEmpty { listOf("") })
    }

    // ----------------------------------------------------------- local subscriptions
    @Insert
    suspend fun insertLocalSubscription(row: LocalSubscriptionEntity)

    @Query("SELECT * FROM local_subscriptions WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableSubscriptions(): List<LocalSubscriptionEntity>

    @Query("SELECT * FROM local_subscriptions WHERE localId = :localId LIMIT 1")
    suspend fun subscriptionById(localId: String): LocalSubscriptionEntity?

    @Query("SELECT * FROM local_subscriptions WHERE syncState NOT IN ('synced', 'migrated_v21') ORDER BY createdAtMillis DESC")
    fun observeLocalSubscriptions(): Flow<List<LocalSubscriptionEntity>>

    @Query("SELECT COUNT(*) FROM local_subscriptions WHERE syncState = 'rejected'")
    fun observeRejectedSubscriptionCount(): Flow<Int>

    @Query("UPDATE local_subscriptions SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markSubscriptionSynced(localId: String)

    @Query("UPDATE local_subscriptions SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markSubscriptionRejected(localId: String, error: String)

    @Query("UPDATE local_subscriptions SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retrySubscription(localId: String)

    /** Guards against queueing a second subscribe for the same customer before the
     * first syncs — the backend's own overlap check would reject it anyway, but this
     * avoids a confusing rejected row for a mistake the UI can prevent up front. */
    @Query("SELECT * FROM local_subscriptions WHERE customerId = :customerId AND syncState NOT IN ('synced', 'migrated_v21') LIMIT 1")
    suspend fun pendingSubscriptionForCustomer(customerId: String): LocalSubscriptionEntity?

    // ------------------------------------------------------- local cancellations
    @Insert
    suspend fun insertLocalCancellation(row: LocalMembershipCancellationEntity)

    @Query("SELECT * FROM local_membership_cancellations WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableCancellations(): List<LocalMembershipCancellationEntity>

    @Query("SELECT * FROM local_membership_cancellations WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalCancellations(): Flow<List<LocalMembershipCancellationEntity>>

    @Query("SELECT COUNT(*) FROM local_membership_cancellations WHERE syncState = 'rejected'")
    fun observeRejectedCancellationCount(): Flow<Int>

    @Query("UPDATE local_membership_cancellations SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markCancellationSynced(localId: String)

    @Query("UPDATE local_membership_cancellations SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCancellationRejected(localId: String, error: String)

    @Query("UPDATE local_membership_cancellations SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryCancellation(localId: String)

    @Query("SELECT * FROM local_membership_cancellations WHERE subscriptionId = :subscriptionId AND syncState != 'synced' LIMIT 1")
    suspend fun pendingCancellationForSubscription(subscriptionId: String): LocalMembershipCancellationEntity?

    // ------------------------------------------------------------ local refunds
    @Insert
    suspend fun insertLocalRefund(row: LocalMembershipRefundEntity)

    @Query("SELECT * FROM local_membership_refunds WHERE syncState = 'request_pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableRefundRequests(): List<LocalMembershipRefundEntity>

    @Query("SELECT * FROM local_membership_refunds WHERE localId = :localId LIMIT 1")
    suspend fun refundById(localId: String): LocalMembershipRefundEntity?

    @Query("SELECT * FROM local_membership_refunds WHERE syncState = 'cash_settle_pending' ORDER BY settledAtMillis ASC")
    suspend fun pushableCashRefundSettlements(): List<LocalMembershipRefundEntity>

    @Query("SELECT * FROM local_membership_refunds WHERE syncState = 'withdrawal_pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableRefundWithdrawals(): List<LocalMembershipRefundEntity>

    @Query("SELECT * FROM local_membership_refunds WHERE syncState NOT IN ('synced', 'withdrawn', 'migrated_v22') ORDER BY createdAtMillis DESC")
    fun observeLocalRefunds(): Flow<List<LocalMembershipRefundEntity>>

    @Query("SELECT COUNT(*) FROM local_membership_refunds WHERE syncState IN ('request_rejected', 'cash_settle_rejected', 'withdrawal_rejected')")
    fun observeRejectedRefundCount(): Flow<Int>

    @Query(
        "UPDATE local_membership_refunds SET serverRefundId = :serverRefundId, " +
            "syncState = 'accepted_cash_due', lastError = NULL WHERE localId = :localId",
    )
    suspend fun markRefundAcceptedCashDue(localId: String, serverRefundId: String)

    @Query(
        "UPDATE local_membership_refunds SET serverRefundId = :serverRefundId, " +
            "receiptNo = :receiptNo, syncState = 'synced', lastError = NULL WHERE localId = :localId",
    )
    suspend fun markRefundSettled(localId: String, serverRefundId: String, receiptNo: String?)

    @Query("UPDATE local_membership_refunds SET syncState = 'request_rejected', lastError = :error WHERE localId = :localId")
    suspend fun markRefundRequestRejected(localId: String, error: String)

    @Query("UPDATE local_membership_refunds SET syncState = 'cash_settle_rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCashRefundSettlementRejected(localId: String, error: String)

    @Query(
        "UPDATE local_membership_refunds SET settledAtMillis = :settledAtMillis, " +
            "syncState = 'cash_settle_pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'accepted_cash_due' " +
            "AND serverRefundId IS NOT NULL AND method = 'cash'",
    )
    suspend fun confirmCashRefundHandover(localId: String, settledAtMillis: Long): Int

    @Query(
        "UPDATE local_membership_refunds SET withdrawalReason = :reason, " +
            "withdrawalAtMillis = :withdrawalAtMillis, " +
            "syncState = 'withdrawal_pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'accepted_cash_due' " +
            "AND serverRefundId IS NOT NULL AND method = 'cash'",
    )
    suspend fun requestRefundWithdrawal(
        localId: String,
        reason: String,
        withdrawalAtMillis: Long,
    ): Int

    @Query("UPDATE local_membership_refunds SET syncState = 'withdrawal_rejected', lastError = :error WHERE localId = :localId")
    suspend fun markRefundWithdrawalRejected(localId: String, error: String)

    @Query("UPDATE local_membership_refunds SET syncState = 'withdrawn', lastError = NULL WHERE localId = :localId")
    suspend fun markRefundWithdrawn(localId: String)

    @Query(
        "UPDATE local_membership_refunds SET syncState = CASE " +
            "WHEN syncState = 'request_rejected' THEN 'request_pending' " +
            "WHEN syncState = 'cash_settle_rejected' THEN 'cash_settle_pending' " +
            "WHEN syncState = 'withdrawal_rejected' THEN 'withdrawal_pending' " +
            "ELSE syncState END, lastError = NULL WHERE localId = :localId",
    )
    suspend fun retryRefund(localId: String)

    @Query("SELECT * FROM local_membership_refunds WHERE subscriptionId = :subscriptionId AND syncState NOT IN ('synced', 'withdrawn', 'migrated_v22') LIMIT 1")
    suspend fun pendingRefundForSubscription(subscriptionId: String): LocalMembershipRefundEntity?

    /** Paid membership writes are drawer/shift facts. A queued or rejected row
     * must be resolved before its exact shift can close. */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM local_subscriptions WHERE syncState NOT IN ('synced', 'migrated_v21') " +
            "AND (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))) + " +
            "(SELECT COUNT(*) FROM local_membership_refunds WHERE syncState NOT IN ('synced', 'withdrawn', 'migrated_v22') " +
            "AND (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId)))",
    )
    suspend fun unresolvedMoneyCountForShift(localShiftId: String, serverShiftId: String?): Int
}
