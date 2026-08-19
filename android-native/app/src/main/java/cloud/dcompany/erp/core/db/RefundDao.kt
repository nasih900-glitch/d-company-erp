package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RefundDao {

    // ------------------------------------------------------------ order cache
    @Query("SELECT * FROM refund_order_cache WHERE status = 'paid' ORDER BY invoiceNo DESC")
    fun observeRefundableOrders(): Flow<List<RefundOrderCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderCache(rows: List<RefundOrderCacheEntity>)

    @Query("DELETE FROM refund_order_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteOrderCacheNotIn(keepIds: List<String>)

    /**
     * Wholesale-replaced like the gaming session cache — the server is
     * authoritative for every order's paid/refunded status, this tablet
     * never edits these rows locally.
     */
    @Transaction
    suspend fun replaceOrderCache(rows: List<RefundOrderCacheEntity>) {
        upsertOrderCache(rows)
        deleteOrderCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // --------------------------------------------------------- local refunds
    @Insert
    suspend fun insertLocalRefund(refund: LocalRefundEntity)

    /** Still-unsynced refunds, netted against their order's cached refundableMinor at read time. */
    @Query("SELECT * FROM local_refunds WHERE state = 'pending' ORDER BY createdAtMillis DESC")
    fun observePendingLocalRefunds(): Flow<List<LocalRefundEntity>>

    @Query("SELECT COUNT(*) FROM local_refunds WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    /**
     * Real cash already left the drawer on the strength of this app's own
     * "cannot be undone" confirmation before the server had a say — a
     * refund it later refuses needs to be seen, not just counted. Refunds is
     * the one resource in this app where that's true today.
     */
    @Query("SELECT * FROM local_refunds WHERE state = 'rejected' ORDER BY createdAtMillis DESC")
    fun observeRejectedRefunds(): Flow<List<LocalRefundEntity>>

    @Query("SELECT * FROM local_refunds WHERE state = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableRefunds(): List<LocalRefundEntity>

    @Query(
        "UPDATE local_refunds SET state = 'synced', settlementMethod = :settlementMethod " +
            "WHERE localId = :localId",
    )
    suspend fun markRefundSynced(localId: String, settlementMethod: String?)

    @Query("UPDATE local_refunds SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markRefundRejected(localId: String, error: String)
}
