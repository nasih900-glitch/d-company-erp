package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {

    // -------------------------------------------------------------- company
    @Query("SELECT * FROM company_cache LIMIT 1")
    fun observeCompany(): Flow<CompanyCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompany(row: CompanyCacheEntity)

    @Insert
    suspend fun insertLocalCompanyEdit(row: LocalCompanyEditEntity)

    @Query("DELETE FROM local_company_edits WHERE syncState != 'synced'")
    suspend fun clearPendingCompanyEdits()

    /** Replaces any not-yet-synced edit with this one — editing again before
     * the last edit synced supersedes it rather than queueing a second. */
    @Transaction
    suspend fun replacePendingCompanyEdit(row: LocalCompanyEditEntity) {
        clearPendingCompanyEdits()
        insertLocalCompanyEdit(row)
    }

    @Query("SELECT * FROM local_company_edits WHERE syncState = 'pending' ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun pushableCompanyEdit(): LocalCompanyEditEntity?

    @Query("SELECT * FROM local_company_edits WHERE syncState != 'synced' ORDER BY createdAtMillis DESC LIMIT 1")
    fun observePendingCompanyEdit(): Flow<LocalCompanyEditEntity?>

    @Query("UPDATE local_company_edits SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markCompanyEditSynced(localId: String)

    @Query("UPDATE local_company_edits SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCompanyEditRejected(localId: String, error: String)

    @Query("UPDATE local_company_edits SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryCompanyEdit(localId: String)

    @Query("SELECT COUNT(*) FROM local_company_edits WHERE syncState = 'rejected'")
    fun observeRejectedCompanyEditCount(): Flow<Int>

    // -------------------------------------------------------------- branches
    @Query("SELECT * FROM branch_cache ORDER BY name ASC")
    fun observeBranchCache(): Flow<List<BranchCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBranchCache(rows: List<BranchCacheEntity>)

    @Query("DELETE FROM branch_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteBranchCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceBranchCache(rows: List<BranchCacheEntity>) {
        upsertBranchCache(rows)
        deleteBranchCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    @Insert
    suspend fun insertLocalBranch(row: LocalBranchEntity)

    @Query("SELECT * FROM local_branches WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableBranches(): List<LocalBranchEntity>

    @Query("SELECT * FROM local_branches WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalBranches(): Flow<List<LocalBranchEntity>>

    @Query("SELECT COUNT(*) FROM local_branches WHERE syncState = 'rejected'")
    fun observeRejectedBranchCount(): Flow<Int>

    @Query("UPDATE local_branches SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markBranchSynced(localId: String)

    @Query("UPDATE local_branches SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markBranchRejected(localId: String, error: String)

    @Query("UPDATE local_branches SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryBranch(localId: String)

    // ------------------------------------------------------------- terminals
    @Query("SELECT * FROM terminal_cache ORDER BY name ASC")
    fun observeTerminalCache(): Flow<List<TerminalCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTerminalCache(rows: List<TerminalCacheEntity>)

    @Query("DELETE FROM terminal_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteTerminalCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceTerminalCache(rows: List<TerminalCacheEntity>) {
        upsertTerminalCache(rows)
        deleteTerminalCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    @Insert
    suspend fun insertLocalTerminal(row: LocalTerminalEntity)

    @Query("SELECT * FROM local_terminals WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableTerminals(): List<LocalTerminalEntity>

    @Query("SELECT * FROM local_terminals WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalTerminals(): Flow<List<LocalTerminalEntity>>

    @Query("SELECT COUNT(*) FROM local_terminals WHERE syncState = 'rejected'")
    fun observeRejectedTerminalCount(): Flow<Int>

    @Query("UPDATE local_terminals SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markTerminalSynced(localId: String)

    @Query("UPDATE local_terminals SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markTerminalRejected(localId: String, error: String)

    @Query("UPDATE local_terminals SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryTerminal(localId: String)
}
