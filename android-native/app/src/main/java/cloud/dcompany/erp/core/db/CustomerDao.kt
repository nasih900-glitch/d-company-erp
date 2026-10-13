package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex

/** Serializes every server customer projection request through commit. A
 * program revision orders rules, not two balance responses within one
 * revision, so request overlap must be prevented rather than timestamp-guessed. */
object CustomerProjectionRefreshGate {
    val mutex = Mutex()
}

internal fun mergeCustomerProjection(
    existing: CustomerCacheEntity?,
    incoming: CustomerCacheEntity,
): CustomerCacheEntity {
    val incomingRevision = incoming.loyaltyProgramRevision
    val existingRevision = existing?.loyaltyProgramRevision
    return if (
        existing != null && existingRevision != null &&
        (incomingRevision == null || incomingRevision < existingRevision)
    ) {
        incoming.copy(
            loyaltyPoints = existing.loyaltyPoints,
            loyaltyProgramRevision = existingRevision,
            loyaltyPointValueMinor = existing.loyaltyPointValueMinor,
            lifetimeGamingPointsEarned = existing.lifetimeGamingPointsEarned,
            gamingRank = existing.gamingRank,
            gamingRankFloor = existing.gamingRankFloor,
            nextGamingRank = existing.nextGamingRank,
            nextGamingRankFloor = existing.nextGamingRankFloor,
            pointsToNextGamingRank = existing.pointsToNextGamingRank,
        )
    } else incoming
}

@Dao
interface CustomerDao {

    @Query("SELECT * FROM customer_directory_state WHERE companyId = :companyId LIMIT 1")
    suspend fun directoryState(companyId: String): CustomerDirectoryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDirectoryState(state: CustomerDirectoryStateEntity)

    // ----------------------------------------------------------- read cache
    @Query("SELECT * FROM customer_cache ORDER BY name")
    fun observeCache(): Flow<List<CustomerCacheEntity>>

    @Query("SELECT * FROM customer_cache WHERE id = :id LIMIT 1")
    suspend fun getCache(id: String): CustomerCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCacheRows(rows: List<CustomerCacheEntity>)

    /** Unknown/older clients may refresh identity fields, but cannot erase or
     * downgrade a newer revision's point economics. Same-revision requests are
     * ordered by [CustomerProjectionRefreshGate]. */
    @Transaction
    suspend fun upsertCache(rows: List<CustomerCacheEntity>) {
        replaceCacheRows(rows.map { incoming -> mergeCustomerProjection(getCache(incoming.id), incoming) })
    }

    @Query("DELETE FROM customer_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteCacheNotIn(keepIds: List<String>)

    /** Wholesale-replaced, same reasoning as [MenuDao.replaceMenu]. */
    @Transaction
    suspend fun replaceCache(rows: List<CustomerCacheEntity>) {
        upsertCache(rows)
        deleteCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // ------------------------------------------------------ purchase history
    @Query(
        "SELECT * FROM customer_order_history_cache " +
            "WHERE customerId = :customerId ORDER BY createdAt DESC",
    )
    suspend fun history(customerId: String): List<CustomerOrderHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(rows: List<CustomerOrderHistoryEntity>)

    @Query(
        "DELETE FROM customer_order_history_cache " +
            "WHERE customerId = :customerId AND id NOT IN (:keepIds)",
    )
    suspend fun deleteHistoryNotIn(customerId: String, keepIds: List<String>)

    /** Replace only the selected customer's bounded history, never another profile's cache. */
    @Transaction
    suspend fun replaceHistory(customerId: String, rows: List<CustomerOrderHistoryEntity>) {
        upsertHistory(rows)
        deleteHistoryNotIn(customerId, rows.map { it.id }.ifEmpty { listOf("") })
    }

    // -------------------------------------------------------- local writes
    /** Pending or rejected only — a synced row is deleted, see [deleteSynced]. */
    @Query("SELECT * FROM local_customers WHERE state != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocal(): Flow<List<LocalCustomerEntity>>

    @Query("SELECT * FROM local_customers WHERE localId = :localId")
    suspend fun getLocal(localId: String): LocalCustomerEntity?

    /** The still-unsynced edit already queued against this server customer, if any. */
    @Query("SELECT * FROM local_customers WHERE serverId = :serverId AND state != 'synced' LIMIT 1")
    suspend fun pendingLocalForServerId(serverId: String): LocalCustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocal(row: LocalCustomerEntity)

    @Query("SELECT * FROM local_customers WHERE state = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushable(): List<LocalCustomerEntity>

    @Query("SELECT COUNT(*) FROM local_customers WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    /**
     * Recorded as soon as the create/update response names it — independent
     * of [markSynced] and before the cache pull that follows, so a customer
     * this device just created is never briefly absent from both the local
     * override and the cache (see SyncEngine.pushCustomerOne's ordering).
     */
    @Query("UPDATE local_customers SET serverId = :serverId WHERE localId = :localId")
    suspend fun setServerId(localId: String, serverId: String)

    /**
     * Only applies if [expectedVersion] still matches — the row's version
     * bumps on every save() amend (see LocalCustomerEntity's doc comment), so
     * a value that no longer matches means a newer edit landed while this
     * particular network call was in flight. Returns the number of rows
     * actually updated (0 or 1) so the caller can tell whether it actually
     * applied — Room supports a plain `Int` return on a `@Query` UPDATE for
     * exactly this.
     */
    @Query(
        "UPDATE local_customers SET state = 'synced', lastError = NULL " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markSynced(localId: String, expectedVersion: Long): Int

    /** A refusal for an older in-flight snapshot must not park a newer edit. */
    @Query(
        "UPDATE local_customers SET state = 'rejected', lastError = :error " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markRejected(localId: String, error: String, expectedVersion: Long): Int

    /** Cache already reflects these once the pull after a push completes — nothing left to keep. */
    @Query("DELETE FROM local_customers WHERE state = 'synced'")
    suspend fun deleteSynced()
}
