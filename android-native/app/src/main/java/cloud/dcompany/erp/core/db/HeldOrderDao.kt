package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HeldOrderDao {

    @Query("SELECT * FROM held_order_cache ORDER BY COALESCE(heldAt, createdAt) ASC")
    fun observeAll(): Flow<List<HeldOrderCacheEntity>>

    /** One-shot snapshot used by AlarmManager reconciliation and fire-time validation. */
    @Query("SELECT * FROM held_order_cache ORDER BY COALESCE(heldAt, createdAt) ASC")
    suspend fun allForAlarms(): List<HeldOrderCacheEntity>

    @Query("SELECT * FROM held_order_cache WHERE id = :orderId LIMIT 1")
    suspend fun orderForAlarm(orderId: String): HeldOrderCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<HeldOrderCacheEntity>)

    @Query("DELETE FROM held_order_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    /** Insert-then-delete in one transaction so a reader never sees an empty queue mid-refresh. */
    @Transaction
    suspend fun replace(rows: List<HeldOrderCacheEntity>) {
        upsertAll(rows)
        deleteNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    /** A unique targetOrderId makes a rapid double-tap one logical payment. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayment(payment: LocalHeldOrderPaymentEntity): Long

    @Query(
        "SELECT * FROM local_held_order_payments WHERE syncState = 'pending' " +
            "ORDER BY createdAtMillis ASC",
    )
    suspend fun pushablePayments(): List<LocalHeldOrderPaymentEntity>

    /**
     * Money has already been confirmed for every row returned here. Expose the
     * actual settlements—not only counts—so staff can distinguish an automatic
     * pending replay from a definitive refusal that needs manager action.
     */
    @Query(
        "SELECT * FROM local_held_order_payments WHERE syncState != 'synced' " +
            "ORDER BY CASE WHEN syncState = 'rejected' THEN 0 ELSE 1 END, createdAtMillis DESC",
    )
    fun observeUnresolvedPayments(): Flow<List<LocalHeldOrderPaymentEntity>>

    @Query("SELECT * FROM local_held_order_payments WHERE localId = :localId LIMIT 1")
    fun observePayment(localId: String): Flow<LocalHeldOrderPaymentEntity?>

    @Query("SELECT * FROM local_held_order_payments WHERE targetOrderId = :targetOrderId LIMIT 1")
    suspend fun paymentForTarget(targetOrderId: String): LocalHeldOrderPaymentEntity?

    /**
     * Once staff confirmed money locally, this order must never return to the
     * collection queue — not even after a definitive server rejection. A
     * rejected row is a manager reconciliation task, never permission to ask
     * the customer to pay twice.
     */
    @Query("SELECT targetOrderId FROM local_held_order_payments")
    fun observeConfirmedTargetIds(): Flow<List<String>>

    /**
     * A local confirmation is stronger than an older held-order cache row.
     * This includes rejected reconciliation rows: money was already taken and
     * the customer must never be alarmed/asked to pay twice.
     */
    @Query("SELECT targetOrderId FROM local_held_order_payments")
    suspend fun confirmedTargetIdsForAlarms(): List<String>

    @Query("SELECT COUNT(*) FROM local_held_order_payments WHERE syncState = 'pending'")
    fun observePendingPaymentCount(): Flow<Int>

    @Query(
        "UPDATE local_held_order_payments SET claimToken = :token, " +
            "claimExpiresAtMillis = :expiresAtMillis, claimOrderVersion = :orderVersion, " +
            "lastError = NULL WHERE localId = :localId AND syncState = 'pending'",
    )
    suspend fun updatePaymentClaim(
        localId: String,
        token: String,
        expiresAtMillis: Long,
        orderVersion: Long,
    ): Int

    @Query(
        "UPDATE local_held_order_payments SET lastError = :error " +
            "WHERE localId = :localId AND syncState = 'pending'",
    )
    suspend fun notePendingPaymentError(localId: String, error: String)

    @Query(
        "UPDATE local_held_order_payments SET syncState = 'synced', lastError = NULL, " +
            "claimToken = NULL WHERE localId = :localId",
    )
    suspend fun markPaymentSynced(localId: String)

    @Query(
        "UPDATE local_held_order_payments SET syncState = 'rejected', lastError = :error, " +
            "claimToken = NULL WHERE localId = :localId",
    )
    suspend fun markPaymentRejected(localId: String, error: String)

    @Query("SELECT COUNT(*) FROM local_held_order_payments WHERE syncState = 'rejected'")
    fun observeRejectedPaymentCount(): Flow<Int>

    /**
     * Explicit human replay after the refusal has been fixed. The original
     * row/localId is retained because it is the payment idempotency identity;
     * a stale view or double tap cannot transition it twice.
     */
    @Query(
        "UPDATE local_held_order_payments SET syncState = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'rejected'",
    )
    suspend fun retryRejectedPayment(localId: String): Int
}
