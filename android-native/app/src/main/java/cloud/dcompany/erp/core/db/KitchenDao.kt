package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface KitchenDao {

    @Query("SELECT * FROM kitchen_order_cache")
    fun observeOrderCache(): Flow<List<KitchenOrderCacheEntity>>

    @Query("SELECT * FROM kitchen_order_cache WHERE id = :orderId LIMIT 1")
    suspend fun orderCache(orderId: String): KitchenOrderCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderCache(rows: List<KitchenOrderCacheEntity>)

    @Query("DELETE FROM kitchen_order_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteOrderCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceOrderCache(rows: List<KitchenOrderCacheEntity>) {
        upsertOrderCache(rows)
        deleteOrderCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    @Insert
    suspend fun insertAdvance(row: LocalKitchenAdvanceEntity)

    @Query(
        "SELECT * FROM local_kitchen_advances WHERE state = 'pending' ORDER BY requestedAtMillis ASC",
    )
    suspend fun pendingAdvances(): List<LocalKitchenAdvanceEntity>

    /** For the read-side optimistic override — see KitchenViewModel's merge. */
    @Query("SELECT * FROM local_kitchen_advances WHERE state = 'pending'")
    fun observePendingAdvances(): Flow<List<LocalKitchenAdvanceEntity>>

    /**
     * Pending rows remain optimistic; rejected rows remain visible until a
     * person explicitly retries or removes them after checking server truth.
     */
    @Query(
        "SELECT * FROM local_kitchen_advances WHERE state IN ('pending', 'rejected') " +
            "ORDER BY requestedAtMillis ASC",
    )
    fun observeUnresolvedAdvances(): Flow<List<LocalKitchenAdvanceEntity>>

    @Query("DELETE FROM local_kitchen_advances WHERE localId = :localId")
    suspend fun deleteAdvance(localId: String)

    @Query("DELETE FROM local_kitchen_advances WHERE localId = :localId AND state = 'pending'")
    suspend fun deletePendingAdvance(localId: String): Int

    @Query(
        "UPDATE local_kitchen_advances SET state = 'rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'pending'",
    )
    suspend fun markAdvanceRejected(localId: String, error: String): Int

    @Query(
        "UPDATE local_kitchen_advances SET lastError = :error " +
            "WHERE localId = :localId AND state = 'pending'",
    )
    suspend fun keepAdvancePending(localId: String, error: String): Int

    @Query(
        "UPDATE local_kitchen_advances SET state = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'rejected'",
    )
    suspend fun retryRejectedAdvance(localId: String): Int

    @Query("DELETE FROM local_kitchen_advances WHERE localId = :localId AND state = 'rejected'")
    suspend fun discardRejectedAdvance(localId: String): Int

    @Query("SELECT COUNT(*) FROM local_kitchen_advances WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCancellationAck(row: LocalKitchenCancellationAckEntity): Long

    @Query(
        "SELECT * FROM local_kitchen_cancellation_acks WHERE state = 'pending' " +
            "ORDER BY requestedAtMillis, localId",
    )
    suspend fun pendingCancellationAcks(): List<LocalKitchenCancellationAckEntity>

    @Query(
        "SELECT * FROM local_kitchen_cancellation_acks WHERE state IN ('pending', 'rejected') " +
            "ORDER BY requestedAtMillis, localId",
    )
    fun observeCancellationAcks(): Flow<List<LocalKitchenCancellationAckEntity>>

    @Query("DELETE FROM local_kitchen_cancellation_acks WHERE localId = :localId")
    suspend fun deleteCancellationAck(localId: String)

    @Query(
        "UPDATE local_kitchen_cancellation_acks SET lastError = :message " +
            "WHERE localId = :localId AND state = 'pending'",
    )
    suspend fun noteCancellationAckPending(localId: String, message: String)

    @Query(
        "UPDATE local_kitchen_cancellation_acks SET state = 'rejected', lastError = :message " +
            "WHERE localId = :localId AND state = 'pending'",
    )
    suspend fun rejectCancellationAck(localId: String, message: String)

    @Query(
        "UPDATE local_kitchen_cancellation_acks SET state = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'rejected'",
    )
    suspend fun retryCancellationAck(localId: String): Int

    @Query("SELECT COUNT(*) FROM local_kitchen_cancellation_acks WHERE state = 'rejected'")
    fun observeRejectedCancellationAckCount(): Flow<Int>
}
