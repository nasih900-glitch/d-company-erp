package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {

    // ---------------------------------------------------------- expense cache
    @Query("SELECT * FROM expense_cache ORDER BY paidAt DESC")
    fun observeExpenseCache(): Flow<List<ExpenseCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExpenseCache(rows: List<ExpenseCacheEntity>)

    @Query("DELETE FROM expense_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteExpenseCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceExpenseCache(rows: List<ExpenseCacheEntity>) {
        upsertExpenseCache(rows)
        deleteExpenseCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // --------------------------------------------------------- local expenses
    @Insert
    suspend fun insertLocalExpense(row: LocalExpenseEntity)

    @Insert
    suspend fun insertLocalExpenseReceipt(row: LocalExpenseReceiptEntity)

    @Insert
    suspend fun insertLocalExpenseReceiptChunks(rows: List<LocalExpenseReceiptChunkEntity>)

    @Transaction
    suspend fun insertLocalExpenseWithReceipt(
        expense: LocalExpenseEntity,
        receipt: LocalExpenseReceiptEntity?,
        receiptChunks: List<LocalExpenseReceiptChunkEntity> = emptyList(),
    ) {
        insertLocalExpense(expense)
        if (receipt != null) {
            require(receiptChunks.isNotEmpty()) { "Receipt metadata requires payload chunks." }
            require(receiptChunks.all { it.receiptLocalId == receipt.localId }) {
                "Receipt chunks must belong to the captured receipt."
            }
            insertLocalExpenseReceipt(receipt)
            insertLocalExpenseReceiptChunks(receiptChunks)
        } else {
            require(receiptChunks.isEmpty()) { "Receipt chunks require receipt metadata." }
        }
    }

    @Query("SELECT * FROM local_expenses WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableExpenses(): List<LocalExpenseEntity>

    @Query("SELECT * FROM local_expenses WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalExpenses(): Flow<List<LocalExpenseEntity>>

    /** Synced cash rows remain a temporary local drawer reservation until a
     * newer server shift snapshot proves its expected cash includes them. */
    @Query(
        "SELECT * FROM local_expenses WHERE paidVia = 'cash' AND shiftId IS NOT NULL " +
            "ORDER BY createdAtMillis ASC",
    )
    fun observeCashExpenseReservations(): Flow<List<LocalExpenseEntity>>

    @Query("SELECT COUNT(*) FROM local_expenses WHERE syncState = 'rejected'")
    fun observeRejectedExpenseCount(): Flow<Int>

    @Query(
        "SELECT r.* FROM local_expense_receipts r " +
            "INNER JOIN local_expenses e ON e.localId = r.expenseLocalId " +
            "WHERE r.syncState = 'pending' AND e.serverId IS NOT NULL " +
            "ORDER BY r.createdAtMillis ASC",
    )
    suspend fun pushableExpenseReceipts(): List<LocalExpenseReceiptEntity>

    @Query("SELECT * FROM local_expense_receipts WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalExpenseReceipts(): Flow<List<LocalExpenseReceiptEntity>>

    @Query(
        "SELECT r.*, e.branchId AS expenseBranchId, e.serverId AS expenseServerId " +
            "FROM local_expense_receipts r " +
            "INNER JOIN local_expenses e ON e.localId = r.expenseLocalId " +
            "WHERE r.syncState != 'synced' ORDER BY r.createdAtMillis DESC",
    )
    fun observeLocalExpenseReceiptsWithExpense(): Flow<List<LocalExpenseReceiptWithExpense>>

    @Query("SELECT COUNT(*) FROM local_expense_receipts WHERE syncState = 'rejected'")
    fun observeRejectedExpenseReceiptCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_expense_receipts WHERE syncState = 'pending'")
    suspend fun pendingExpenseReceiptCount(): Int

    @Query("SELECT * FROM local_expenses WHERE localId = :localId LIMIT 1")
    suspend fun expenseByLocalId(localId: String): LocalExpenseEntity?

    @Query("SELECT * FROM local_expense_receipts WHERE localId = :localId LIMIT 1")
    suspend fun expenseReceiptByLocalId(localId: String): LocalExpenseReceiptEntity?

    @Query(
        "SELECT * FROM local_expense_receipts WHERE expenseLocalId = :expenseLocalId " +
            "ORDER BY localId ASC",
    )
    suspend fun expenseReceiptsForExpense(
        expenseLocalId: String,
    ): List<LocalExpenseReceiptEntity>

    @Query(
        "SELECT r.*, e.branchId AS expenseBranchId, e.serverId AS expenseServerId " +
            "FROM local_expense_receipts r " +
            "INNER JOIN local_expenses e ON e.localId = r.expenseLocalId " +
            "WHERE r.localId = :localId LIMIT 1",
    )
    suspend fun expenseReceiptWithExpenseByLocalId(
        localId: String,
    ): LocalExpenseReceiptWithExpense?

    @Query(
        "SELECT * FROM local_expense_receipt_chunks WHERE receiptLocalId = :receiptLocalId " +
            "ORDER BY chunkIndex ASC",
    )
    suspend fun expenseReceiptChunks(
        receiptLocalId: String,
    ): List<LocalExpenseReceiptChunkEntity>

    @Query(
        "UPDATE local_expenses SET serverId = :serverId, syncState = 'synced', lastError = NULL " +
            "WHERE localId = :localId",
    )
    suspend fun markExpenseSynced(localId: String, serverId: String)

    @Query("UPDATE local_expenses SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markExpenseRejected(localId: String, error: String)

    @Query("UPDATE local_expenses SET lastError = :error WHERE localId = :localId AND syncState = 'pending'")
    suspend fun notePendingExpenseError(localId: String, error: String)

    @Query(
        "UPDATE local_expense_receipts SET syncState = 'synced', serverReceiptId = :serverReceiptId, " +
            "lastError = NULL WHERE localId = :localId",
    )
    suspend fun markExpenseReceiptSynced(localId: String, serverReceiptId: String)

    @Query("DELETE FROM local_expense_receipt_chunks WHERE receiptLocalId = :receiptLocalId")
    suspend fun deleteExpenseReceiptChunks(receiptLocalId: String)

    @Transaction
    suspend fun confirmExpenseReceipt(localId: String, serverReceiptId: String) {
        markExpenseReceiptSynced(localId, serverReceiptId)
        deleteExpenseReceiptChunks(localId)
    }

    @Query(
        "UPDATE local_expense_receipts SET syncState = 'rejected', lastError = :error " +
            "WHERE localId = :localId",
    )
    suspend fun markExpenseReceiptRejected(localId: String, error: String)

    @Query(
        "UPDATE local_expense_receipts SET lastError = :error " +
            "WHERE localId = :localId AND syncState = 'pending'",
    )
    suspend fun notePendingExpenseReceiptError(localId: String, error: String)

    @Transaction
    suspend fun confirmExpense(localId: String, receipt: ExpenseCacheEntity) {
        upsertExpenseCache(listOf(receipt))
        markExpenseSynced(localId, receipt.id)
    }

    /** A rejected expense is parked, not auto-retried — same reasoning as CustomersViewModel.retrySync. */
    @Query(
        "UPDATE local_expenses SET syncState = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'rejected'",
    )
    suspend fun retryExpense(localId: String): Int

    @Query(
        "UPDATE local_expense_receipts SET syncState = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'rejected'",
    )
    suspend fun retryExpenseReceipt(localId: String): Int

    /**
     * Final compare-and-delete after an online authoritative receipt-list check.
     * The exact immutable identities make a concurrent retry or account action
     * fail closed. Foreign-key cascade removes only this receipt's chunks.
     */
    @Query(
        "DELETE FROM local_expense_receipts WHERE localId = :localId " +
            "AND expenseLocalId = :expenseLocalId AND contentSha256 = :contentSha256 " +
            "AND syncState = 'rejected' AND serverReceiptId IS NULL",
    )
    suspend fun discardRejectedExpenseReceiptEvidence(
        localId: String,
        expenseLocalId: String,
        contentSha256: String,
    ): Int

    @Query(
        "DELETE FROM local_expenses WHERE localId = :localId " +
            "AND syncState = 'rejected' AND serverId IS NULL",
    )
    suspend fun deleteExactRejectedExpenseParent(localId: String): Int

    /**
     * Room-transaction CAS for a server-proven-absent expense action. Exact
     * parent and receipt metadata are re-read immediately before deletion;
     * FK cascades remove only those receipts and their chunks.
     */
    @Transaction
    suspend fun discardRejectedExpenseAction(
        capturedExpense: LocalExpenseEntity,
        capturedReceipts: List<LocalExpenseReceiptEntity>,
    ): Boolean {
        val currentExpense = expenseByLocalId(capturedExpense.localId)
        val currentReceipts = expenseReceiptsForExpense(capturedExpense.localId)
        if (currentExpense != capturedExpense || currentReceipts != capturedReceipts) return false
        if (
            currentExpense.serverId != null || currentExpense.syncState != SyncState.REJECTED ||
            currentReceipts.any {
                it.serverReceiptId != null ||
                    it.syncState !in setOf(SyncState.PENDING, SyncState.REJECTED)
            }
        ) return false
        return deleteExactRejectedExpenseParent(capturedExpense.localId) == 1
    }

    // ------------------------------------------------------------ asset cache
    @Query("SELECT * FROM asset_cache ORDER BY purchaseDate DESC")
    fun observeAssetCache(): Flow<List<AssetCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssetCache(rows: List<AssetCacheEntity>)

    @Query("DELETE FROM asset_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteAssetCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceAssetCache(rows: List<AssetCacheEntity>) {
        upsertAssetCache(rows)
        deleteAssetCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // ----------------------------------------------------------- local assets
    @Insert
    suspend fun insertLocalAsset(row: LocalAssetEntity)

    @Query("SELECT * FROM local_assets WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableAssets(): List<LocalAssetEntity>

    @Query("SELECT * FROM local_assets WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalAssets(): Flow<List<LocalAssetEntity>>

    @Query("SELECT COUNT(*) FROM local_assets WHERE syncState = 'rejected'")
    fun observeRejectedAssetCount(): Flow<Int>

    @Query("UPDATE local_assets SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markAssetSynced(localId: String)

    @Query("UPDATE local_assets SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markAssetRejected(localId: String, error: String)

    @Query("UPDATE local_assets SET lastError = :error WHERE localId = :localId AND syncState = 'pending'")
    suspend fun notePendingAssetError(localId: String, error: String)

    @Transaction
    suspend fun confirmAsset(localId: String, receipt: AssetCacheEntity) {
        upsertAssetCache(listOf(receipt))
        markAssetSynced(localId)
    }

    @Query(
        "UPDATE local_assets SET syncState = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'rejected'",
    )
    suspend fun retryAsset(localId: String): Int

    // ---------------------------------------------------- capital entry cache
    @Query("SELECT * FROM capital_entry_cache WHERE partnerId = :partnerId ORDER BY effectiveAt DESC")
    fun observeCapitalEntriesFor(partnerId: String): Flow<List<CapitalEntryCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapitalEntryCache(rows: List<CapitalEntryCacheEntity>)

    @Query("DELETE FROM capital_entry_cache WHERE partnerId = :partnerId AND id NOT IN (:keepIds)")
    suspend fun deleteCapitalEntryCacheNotIn(partnerId: String, keepIds: List<String>)

    @Transaction
    suspend fun replaceCapitalEntriesFor(partnerId: String, rows: List<CapitalEntryCacheEntity>) {
        upsertCapitalEntryCache(rows)
        deleteCapitalEntryCacheNotIn(partnerId, rows.map { it.id }.ifEmpty { listOf("") })
    }

    @Query("DELETE FROM expense_cache")
    suspend fun clearExpenseCache()

    @Query("DELETE FROM asset_cache")
    suspend fun clearAssetCache()

    @Query("DELETE FROM capital_entry_cache")
    suspend fun clearCapitalEntryCache()

    /** Read caches have no tenant columns. Clear them atomically before a
     * different company/branch scope is allowed to observe this shared Room
     * database; local outboxes are intentionally left untouched and remain
     * protected by OutboxSafetyGate. */
    @Transaction
    suspend fun clearReadCachesForScopeChange() {
        clearExpenseCache()
        clearAssetCache()
        clearCapitalEntryCache()
    }

    // ------------------------------------------------------ local capital entries
    @Insert
    suspend fun insertLocalCapitalEntry(row: LocalCapitalEntryEntity)

    @Query("SELECT * FROM local_capital_entries WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableCapitalEntries(): List<LocalCapitalEntryEntity>

    @Query("SELECT * FROM local_capital_entries WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalCapitalEntries(): Flow<List<LocalCapitalEntryEntity>>

    @Query("SELECT COUNT(*) FROM local_capital_entries WHERE syncState = 'rejected'")
    fun observeRejectedCapitalEntryCount(): Flow<Int>

    @Query("UPDATE local_capital_entries SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markCapitalEntrySynced(localId: String)

    @Query("UPDATE local_capital_entries SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCapitalEntryRejected(localId: String, error: String)

    @Query("UPDATE local_capital_entries SET lastError = :error WHERE localId = :localId AND syncState = 'pending'")
    suspend fun notePendingCapitalEntryError(localId: String, error: String)

    @Transaction
    suspend fun confirmCapitalEntry(localId: String, receipt: CapitalEntryCacheEntity) {
        upsertCapitalEntryCache(listOf(receipt))
        markCapitalEntrySynced(localId)
    }

    @Query(
        "UPDATE local_capital_entries SET syncState = 'pending', lastError = NULL " +
            "WHERE localId = :localId AND syncState = 'rejected'",
    )
    suspend fun retryCapitalEntry(localId: String): Int
}
