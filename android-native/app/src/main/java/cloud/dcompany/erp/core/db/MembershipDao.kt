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

    // ----------------------------------------------------------- local subscriptions
    @Insert
    suspend fun insertLocalSubscription(row: LocalSubscriptionEntity)

    @Query("SELECT * FROM local_subscriptions WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableSubscriptions(): List<LocalSubscriptionEntity>

    @Query("SELECT * FROM local_subscriptions WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
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
    @Query("SELECT * FROM local_subscriptions WHERE customerId = :customerId AND syncState != 'synced' LIMIT 1")
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
}
