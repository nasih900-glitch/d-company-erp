package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CafeOrderDao {

    @Query("SELECT * FROM cafe_bill_cache ORDER BY openedAt, orderId")
    fun observeActiveBills(): Flow<List<CafeBillCacheEntity>>

    @Query("SELECT * FROM cafe_bill_cache WHERE orderId = :orderId LIMIT 1")
    suspend fun billCacheByOrderId(orderId: String): CafeBillCacheEntity?

    @Query("SELECT * FROM cafe_bill_cache WHERE tableId = :tableId LIMIT 1")
    suspend fun billCacheByTableId(tableId: String): CafeBillCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBillCache(row: CafeBillCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBillCaches(rows: List<CafeBillCacheEntity>)

    @Query("DELETE FROM cafe_bill_cache WHERE orderId NOT IN (:keepIds)")
    suspend fun deleteBillCachesNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceActiveBillCache(rows: List<CafeBillCacheEntity>) {
        upsertBillCaches(rows)
        deleteBillCachesNotIn(rows.map { it.orderId }.ifEmpty { listOf("") })
    }

    @Query("SELECT * FROM local_cafe_bills ORDER BY createdAtMillis, localBillId")
    fun observeLocalBills(): Flow<List<LocalCafeBillEntity>>

    @Query("SELECT * FROM local_cafe_bills WHERE localBillId = :localBillId LIMIT 1")
    suspend fun localBill(localBillId: String): LocalCafeBillEntity?

    @Query("SELECT * FROM local_cafe_bills WHERE tableId = :tableId LIMIT 1")
    suspend fun localBillForTable(tableId: String): LocalCafeBillEntity?

    @Query("SELECT * FROM local_cafe_bills WHERE serverOrderId = :serverOrderId LIMIT 1")
    suspend fun localBillForServerOrder(serverOrderId: String): LocalCafeBillEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLocalBill(row: LocalCafeBillEntity): Long

    @Query("SELECT * FROM local_cafe_actions ORDER BY createdAtMillis, localBillId, sequence")
    fun observeActions(): Flow<List<LocalCafeActionEntity>>

    @Query(
        "SELECT * FROM local_cafe_actions WHERE localBillId = :localBillId " +
            "ORDER BY sequence",
    )
    suspend fun actionsForBill(localBillId: String): List<LocalCafeActionEntity>

    @Query(
        "SELECT * FROM local_cafe_actions WHERE localBillId = :localBillId " +
            "ORDER BY sequence LIMIT 1",
    )
    suspend fun firstAction(localBillId: String): LocalCafeActionEntity?

    @Query(
        "SELECT DISTINCT localBillId FROM local_cafe_actions " +
            "ORDER BY createdAtMillis, localBillId",
    )
    suspend fun billIdsWithActions(): List<String>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM local_cafe_actions WHERE localBillId = :localBillId")
    suspend fun maxSequence(localBillId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAction(row: LocalCafeActionEntity): Long

    /**
     * Creates a new table bill exactly once. Both the unique table index and
     * the action dedupe index guard separate coroutines/rapid taps.
     */
    @Transaction
    suspend fun captureNewBill(
        bill: LocalCafeBillEntity,
        action: LocalCafeActionEntity,
    ): Boolean {
        if (billCacheByTableId(bill.tableId) != null || localBillForTable(bill.tableId) != null) {
            return false
        }
        if (insertLocalBill(bill) == -1L) return false
        val inserted = insertAction(action.copy(localBillId = bill.localBillId, sequence = 1)) != -1L
        if (!inserted) deleteLocalBillIfNoActions(bill.localBillId)
        return inserted
    }

    /**
     * Appends one action to an existing server/local bill. `capturedVersion`
     * belongs only on the first action in a chain; successors consume the
     * authoritative version returned by their predecessor.
     */
    @Transaction
    suspend fun captureAction(
        bill: LocalCafeBillEntity,
        action: LocalCafeActionEntity,
    ): Boolean {
        insertLocalBill(bill)
        val persisted = localBill(bill.localBillId) ?: return false
        if (persisted.tableId != bill.tableId || persisted.shiftId != bill.shiftId) return false
        val existing = actionsForBill(bill.localBillId)
        // Whole-bill void is terminal. Keeping this rule inside the Room
        // transaction closes the rapid-tap race between ViewModel snapshots:
        // no later round/handoff can be appended while DELETE is in flight.
        if (existing.any { it.kind == CafeActionKind.VOID_ORDER }) return false
        if (
            action.kind == CafeActionKind.VOID_ORDER &&
            existing.any {
                it.kind == CafeActionKind.SEND_TO_POS ||
                    it.kind == CafeActionKind.LEGACY_CREATE_AND_SEND
            }
        ) {
            return false
        }
        val nextSequence = maxSequence(bill.localBillId) + 1
        val priorExists = nextSequence > 1
        return insertAction(
            action.copy(
                localBillId = bill.localBillId,
                sequence = nextSequence,
                capturedCheckoutVersion = if (priorExists) null else action.capturedCheckoutVersion,
            ),
        ) != -1L
    }

    @Query(
        "UPDATE local_cafe_actions SET state = :state, lastError = :message " +
            "WHERE actionId = :actionId",
    )
    suspend fun setActionFailure(actionId: String, state: String, message: String)

    @Query(
        "UPDATE local_cafe_actions SET state = 'pending', lastError = NULL " +
            "WHERE actionId = :actionId AND state IN ('conflict', 'rejected')",
    )
    suspend fun retryAction(actionId: String): Int

    @Query(
        "UPDATE local_cafe_actions SET state = 'pending', lastError = NULL, " +
            "capturedCheckoutVersion = :checkoutVersion " +
            "WHERE actionId = :actionId AND state = 'conflict'",
    )
    suspend fun rebaseAndRetryConflict(actionId: String, checkoutVersion: Long): Int

    @Query(
        "UPDATE local_cafe_actions SET lastError = :message " +
            "WHERE actionId = :actionId AND state = 'pending'",
    )
    suspend fun notePendingFailure(actionId: String, message: String)

    @Query("DELETE FROM local_cafe_actions WHERE actionId = :actionId")
    suspend fun deleteAction(actionId: String)

    @Query("DELETE FROM local_cafe_actions WHERE localBillId = :localBillId")
    suspend fun deleteActionsForBill(localBillId: String)

    @Query("SELECT * FROM local_cafe_actions WHERE actionId = :actionId LIMIT 1")
    suspend fun action(actionId: String): LocalCafeActionEntity?

    @Transaction
    suspend fun discardBlockedAction(actionId: String): Boolean {
        val action = action(actionId) ?: return false
        if (action.state !in setOf(CafeActionState.CONFLICT, CafeActionState.REJECTED)) return false
        if (
            action.kind == CafeActionKind.CREATE_ROUND ||
            action.kind == CafeActionKind.LEGACY_CREATE_AND_SEND
        ) {
            // Every successor depends on this bill existing. Keeping append,
            // void, or handoff rows after discarding the failed create would
            // strand them into a noisy one-by-one rejection chain.
            deleteActionsForBill(action.localBillId)
            deleteLocalBill(action.localBillId)
            return true
        }
        deleteAction(actionId)
        val remaining = firstAction(action.localBillId)
        if (remaining == null) {
            deleteLocalBill(action.localBillId)
        } else {
            // Recovery UI performs an authoritative active-bill refresh before
            // discard. Rebase a surviving successor onto that confirmed
            // snapshot; it must not inherit the discarded mutation's version.
            val bill = localBill(action.localBillId)
            val server = bill?.serverOrderId?.let { billCacheByOrderId(it) }
            if (bill != null && server != null) {
                updateConfirmedBill(
                    localBillId = bill.localBillId,
                    serverOrderId = server.orderId,
                    checkoutVersion = server.checkoutVersion,
                    status = server.status,
                )
            }
        }
        return true
    }

    @Query("DELETE FROM local_cafe_bills WHERE localBillId = :localBillId")
    suspend fun deleteLocalBill(localBillId: String)

    @Query(
        "DELETE FROM local_cafe_bills WHERE localBillId = :localBillId " +
            "AND NOT EXISTS (SELECT 1 FROM local_cafe_actions WHERE localBillId = :localBillId)",
    )
    suspend fun deleteLocalBillIfNoActions(localBillId: String)

    @Query(
        "UPDATE local_cafe_bills SET serverOrderId = :serverOrderId, " +
            "confirmedCheckoutVersion = :checkoutVersion, localStatus = :status " +
            "WHERE localBillId = :localBillId",
    )
    suspend fun updateConfirmedBill(
        localBillId: String,
        serverOrderId: String,
        checkoutVersion: Long,
        status: String,
    )

    /** Response cache + chain advancement is one crash-safe commit. */
    @Transaction
    suspend fun confirmAction(
        localBillId: String,
        actionId: String,
        server: CafeBillCacheEntity,
    ) {
        upsertBillCache(server)
        updateConfirmedBill(
            localBillId = localBillId,
            serverOrderId = server.orderId,
            checkoutVersion = server.checkoutVersion,
            status = server.status,
        )
        deleteAction(actionId)
        deleteLocalBillIfNoActions(localBillId)
    }

    @Query("DELETE FROM cafe_bill_cache WHERE orderId = :orderId")
    suspend fun deleteBillCache(orderId: String)

    /**
     * A successful whole-order DELETE has no response body. Remove its active
     * snapshot and advance the terminal action in one crash-safe commit. If a
     * Room failure occurs, the exact action remains and the same idempotency
     * key safely replays the already-completed server command.
     */
    @Transaction
    suspend fun confirmVoidOrderAction(
        localBillId: String,
        actionId: String,
        serverOrderId: String,
    ) {
        val head = firstAction(localBillId)
        check(head != null && head.actionId == actionId && head.kind == CafeActionKind.VOID_ORDER) {
            "Whole-bill void confirmation did not match the ordered action head."
        }
        deleteBillCache(serverOrderId)
        deleteAction(actionId)
        deleteLocalBillIfNoActions(localBillId)
    }

    @Query(
        "SELECT COUNT(*) FROM local_cafe_actions WHERE state IN ('conflict', 'rejected')",
    )
    fun observeBlockedActionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_cafe_actions WHERE state = 'pending'")
    fun observePendingActionCount(): Flow<Int>

    @Query("DELETE FROM local_table_orders WHERE localId = :legacyLocalId")
    suspend fun deleteLegacyOrder(legacyLocalId: String)
}
