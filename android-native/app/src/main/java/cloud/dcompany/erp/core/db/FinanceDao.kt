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

    @Query("SELECT * FROM local_expenses WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableExpenses(): List<LocalExpenseEntity>

    @Query("SELECT * FROM local_expenses WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalExpenses(): Flow<List<LocalExpenseEntity>>

    @Query("SELECT COUNT(*) FROM local_expenses WHERE syncState = 'rejected'")
    fun observeRejectedExpenseCount(): Flow<Int>

    @Query("UPDATE local_expenses SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markExpenseSynced(localId: String)

    @Query("UPDATE local_expenses SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markExpenseRejected(localId: String, error: String)

    /** A rejected expense is parked, not auto-retried — same reasoning as CustomersViewModel.retrySync. */
    @Query("UPDATE local_expenses SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryExpense(localId: String)

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

    @Query("UPDATE local_assets SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryAsset(localId: String)

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

    @Query("UPDATE local_capital_entries SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryCapitalEntry(localId: String)
}
