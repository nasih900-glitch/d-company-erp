package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {

    // ----------------------------------------------------------- read cache
    @Query("SELECT * FROM customer_cache ORDER BY name")
    fun observeCache(): Flow<List<CustomerCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(rows: List<CustomerCacheEntity>)

    @Query("DELETE FROM customer_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteCacheNotIn(keepIds: List<String>)

    /** Wholesale-replaced, same reasoning as [MenuDao.replaceMenu]. */
    @Transaction
    suspend fun replaceCache(rows: List<CustomerCacheEntity>) {
        upsertCache(rows)
        deleteCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
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

    @Query("UPDATE local_customers SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markRejected(localId: String, error: String)

    /** Cache already reflects these once the pull after a push completes — nothing left to keep. */
    @Query("DELETE FROM local_customers WHERE state = 'synced'")
    suspend fun deleteSynced()
}
