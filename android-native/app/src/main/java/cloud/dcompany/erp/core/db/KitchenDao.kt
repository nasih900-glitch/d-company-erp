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

    @Query("DELETE FROM local_kitchen_advances WHERE localId = :localId")
    suspend fun deleteAdvance(localId: String)

    @Query(
        "UPDATE local_kitchen_advances SET state = 'rejected', lastError = :error WHERE localId = :localId",
    )
    suspend fun markAdvanceRejected(localId: String, error: String)

    @Query("SELECT COUNT(*) FROM local_kitchen_advances WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>
}
