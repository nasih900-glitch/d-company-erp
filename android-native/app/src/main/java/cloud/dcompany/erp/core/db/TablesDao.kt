package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TablesDao {

    @Query("SELECT * FROM cafe_floors ORDER BY name")
    fun observeFloors(): Flow<List<FloorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFloors(floors: List<FloorEntity>)

    @Query("DELETE FROM cafe_floors WHERE id NOT IN (:keepIds)")
    suspend fun deleteFloorsNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceFloors(floors: List<FloorEntity>) {
        upsertFloors(floors)
        deleteFloorsNotIn(floors.map { it.id }.ifEmpty { listOf("") })
    }

    @Query("SELECT * FROM cafe_tables ORDER BY code")
    fun observeTables(): Flow<List<CafeTableEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTables(tables: List<CafeTableEntity>)

    @Query("DELETE FROM cafe_tables WHERE id NOT IN (:keepIds)")
    suspend fun deleteTablesNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceTables(tables: List<CafeTableEntity>) {
        upsertTables(tables)
        deleteTablesNotIn(tables.map { it.id }.ifEmpty { listOf("") })
    }

    @Insert
    suspend fun insertLocalOrder(order: LocalTableOrderEntity)

    @Query("SELECT * FROM local_table_orders WHERE localId = :localId")
    suspend fun localOrderById(localId: String): LocalTableOrderEntity?

    @Query("SELECT * FROM local_table_orders WHERE state = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pendingOrders(): List<LocalTableOrderEntity>

    @Query("UPDATE local_table_orders SET orderId = :orderId WHERE localId = :localId")
    suspend fun setOrderId(localId: String, orderId: String)

    @Query("DELETE FROM local_table_orders WHERE localId = :localId")
    suspend fun deleteLocalOrder(localId: String)

    @Query(
        "UPDATE local_table_orders SET state = 'rejected', lastError = :error WHERE localId = :localId",
    )
    suspend fun markOrderRejected(localId: String, error: String)

    @Query("SELECT COUNT(*) FROM local_table_orders WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    /** For a "sending…" indicator on the table that's mid-flight — see TablesViewModel. */
    @Query("SELECT * FROM local_table_orders WHERE state = 'pending'")
    fun observePendingOrders(): Flow<List<LocalTableOrderEntity>>
}
