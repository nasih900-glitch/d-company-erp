package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    // -------------------------------------------------------- ingredient cache
    @Query("SELECT * FROM ingredient_cache ORDER BY name")
    fun observeIngredientCache(): Flow<List<IngredientCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIngredientCache(rows: List<IngredientCacheEntity>)

    @Query("DELETE FROM ingredient_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteIngredientCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceIngredientCache(rows: List<IngredientCacheEntity>) {
        upsertIngredientCache(rows)
        deleteIngredientCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // -------------------------------------------------- local ingredient writes
    @Query("SELECT * FROM local_ingredients WHERE state != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalIngredients(): Flow<List<LocalIngredientEntity>>

    @Query("SELECT * FROM local_ingredients WHERE localId = :localId")
    suspend fun getLocalIngredient(localId: String): LocalIngredientEntity?

    @Query("SELECT * FROM local_ingredients WHERE serverId = :serverId AND state != 'synced' LIMIT 1")
    suspend fun pendingIngredientForServerId(serverId: String): LocalIngredientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalIngredient(row: LocalIngredientEntity)

    @Query(
        "SELECT * FROM local_ingredients " +
            "WHERE state IN ('pending', 'create_attempted') ORDER BY createdAtMillis ASC",
    )
    suspend fun pushableIngredients(): List<LocalIngredientEntity>

    /**
     * Atomically freezes a create payload before its first network attempt.
     * A concurrent local edit/delete wins only if it commits before this CAS;
     * once claimed, retries must send the unchanged row and stable key.
     */
    @Query(
        "UPDATE local_ingredients SET state = 'create_attempted', lastError = NULL " +
            "WHERE localId = :localId AND serverId IS NULL AND state = 'pending' " +
            "AND version = :expectedVersion",
    )
    suspend fun claimIngredientCreate(localId: String, expectedVersion: Long): Int

    @Query(
        "UPDATE local_ingredients SET sku = :sku, name = :name, baseUnit = :baseUnit, " +
            "reorderThreshold = :reorderThreshold, reorderQty = :reorderQty, " +
            "pendingDelete = 0, state = 'pending', lastError = NULL, version = version + 1 " +
            "WHERE localId = :localId AND serverId IS NULL " +
            "AND state IN ('pending', 'rejected') AND version = :expectedVersion",
    )
    suspend fun updateMutableIngredientCreate(
        localId: String,
        expectedVersion: Long,
        sku: String,
        name: String,
        baseUnit: String,
        reorderThreshold: Double,
        reorderQty: Double,
    ): Int

    @Query(
        "DELETE FROM local_ingredients WHERE localId = :localId AND serverId IS NULL " +
            "AND state IN ('pending', 'rejected')",
    )
    suspend fun deleteMutableIngredientCreate(localId: String): Int

    @Query("SELECT COUNT(*) FROM local_ingredients WHERE state = 'rejected'")
    fun observeRejectedIngredientCount(): Flow<Int>

    @Query("UPDATE local_ingredients SET serverId = :serverId WHERE localId = :localId")
    suspend fun setIngredientServerId(localId: String, serverId: String)

    @Query(
        "UPDATE local_ingredients SET state = 'synced', lastError = NULL " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markIngredientSynced(localId: String, expectedVersion: Long): Int

    /** Mirrors markIngredientSynced's CAS so a stale refusal cannot park a newer edit. */
    @Query(
        "UPDATE local_ingredients SET state = 'rejected', lastError = :error " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markIngredientRejected(localId: String, error: String, expectedVersion: Long): Int

    @Query(
        "UPDATE local_ingredients SET state = 'pending', lastError = NULL, version = version + 1 " +
            "WHERE localId = :localId AND state = 'rejected'",
    )
    suspend fun retryRejectedIngredient(localId: String): Int

    @Query("DELETE FROM local_ingredients WHERE state = 'synced'")
    suspend fun deleteSyncedIngredients()

    @Query("DELETE FROM local_ingredients WHERE localId = :localId")
    suspend fun deleteLocalIngredient(localId: String)

    // ---------------------------------------------------------- supplier cache
    @Query("SELECT * FROM supplier_cache ORDER BY name")
    fun observeSupplierCache(): Flow<List<SupplierCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSupplierCache(rows: List<SupplierCacheEntity>)

    @Query("DELETE FROM supplier_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteSupplierCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceSupplierCache(rows: List<SupplierCacheEntity>) {
        upsertSupplierCache(rows)
        deleteSupplierCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // ---------------------------------------------------- local supplier writes
    @Query("SELECT * FROM local_suppliers WHERE state != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalSuppliers(): Flow<List<LocalSupplierEntity>>

    @Query("SELECT * FROM local_suppliers WHERE localId = :localId")
    suspend fun getLocalSupplier(localId: String): LocalSupplierEntity?

    @Query("SELECT * FROM local_suppliers WHERE serverId = :serverId AND state != 'synced' LIMIT 1")
    suspend fun pendingSupplierForServerId(serverId: String): LocalSupplierEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalSupplier(row: LocalSupplierEntity)

    @Query(
        "SELECT * FROM local_suppliers " +
            "WHERE state IN ('pending', 'create_attempted') ORDER BY createdAtMillis ASC",
    )
    suspend fun pushableSuppliers(): List<LocalSupplierEntity>

    @Query(
        "UPDATE local_suppliers SET state = 'create_attempted', lastError = NULL " +
            "WHERE localId = :localId AND serverId IS NULL AND state = 'pending' " +
            "AND version = :expectedVersion",
    )
    suspend fun claimSupplierCreate(localId: String, expectedVersion: Long): Int

    @Query(
        "UPDATE local_suppliers SET name = :name, contact = :contact, gstin = :gstin, " +
            "paymentTerms = :paymentTerms, pendingDelete = 0, state = 'pending', " +
            "lastError = NULL, version = version + 1 " +
            "WHERE localId = :localId AND serverId IS NULL " +
            "AND state IN ('pending', 'rejected') AND version = :expectedVersion",
    )
    suspend fun updateMutableSupplierCreate(
        localId: String,
        expectedVersion: Long,
        name: String,
        contact: String?,
        gstin: String?,
        paymentTerms: String?,
    ): Int

    @Query(
        "DELETE FROM local_suppliers WHERE localId = :localId AND serverId IS NULL " +
            "AND state IN ('pending', 'rejected')",
    )
    suspend fun deleteMutableSupplierCreate(localId: String): Int

    @Query("SELECT COUNT(*) FROM local_suppliers WHERE state = 'rejected'")
    fun observeRejectedSupplierCount(): Flow<Int>

    @Query("UPDATE local_suppliers SET serverId = :serverId WHERE localId = :localId")
    suspend fun setSupplierServerId(localId: String, serverId: String)

    @Query(
        "UPDATE local_suppliers SET state = 'synced', lastError = NULL " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markSupplierSynced(localId: String, expectedVersion: Long): Int

    /** Mirrors markSupplierSynced's CAS so a stale refusal cannot park a newer edit. */
    @Query(
        "UPDATE local_suppliers SET state = 'rejected', lastError = :error " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markSupplierRejected(localId: String, error: String, expectedVersion: Long): Int

    @Query(
        "UPDATE local_suppliers SET state = 'pending', lastError = NULL, version = version + 1 " +
            "WHERE localId = :localId AND state = 'rejected'",
    )
    suspend fun retryRejectedSupplier(localId: String): Int

    @Query("DELETE FROM local_suppliers WHERE state = 'synced'")
    suspend fun deleteSyncedSuppliers()

    @Query("DELETE FROM local_suppliers WHERE localId = :localId")
    suspend fun deleteLocalSupplier(localId: String)

    // -------------------------------------------------------------- batch cache
    @Query("SELECT * FROM batch_cache WHERE ingredientId = :ingredientId ORDER BY receivedAt ASC")
    fun observeBatchesFor(ingredientId: String): Flow<List<BatchCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatches(rows: List<BatchCacheEntity>)

    @Query("DELETE FROM batch_cache WHERE ingredientId = :ingredientId AND id NOT IN (:keepIds)")
    suspend fun deleteBatchesNotIn(ingredientId: String, keepIds: List<String>)

    @Transaction
    suspend fun replaceBatchesFor(ingredientId: String, rows: List<BatchCacheEntity>) {
        upsertBatches(rows)
        deleteBatchesNotIn(ingredientId, rows.map { it.id }.ifEmpty { listOf("") })
    }

    // -------------------------------------------------------------------- GRN
    @Insert
    suspend fun insertGrn(grn: LocalGrnEntity)

    @Insert
    suspend fun insertGrnLines(lines: List<LocalGrnLineEntity>)

    /** Insert-only capture, same shape as OrderDao.capture(). */
    @Transaction
    suspend fun captureGrn(grn: LocalGrnEntity, lines: List<LocalGrnLineEntity>) {
        insertGrn(grn)
        insertGrnLines(lines)
    }

    @Query("SELECT * FROM local_grns WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableGrns(): List<LocalGrnEntity>

    @Query("SELECT * FROM local_grn_lines WHERE grnLocalId = :localId")
    suspend fun grnLinesFor(localId: String): List<LocalGrnLineEntity>

    @Query("SELECT * FROM local_grns WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalGrns(): Flow<List<LocalGrnEntity>>

    @Query("SELECT COUNT(*) FROM local_grns WHERE syncState = 'rejected'")
    fun observeRejectedGrnCount(): Flow<Int>

    @Query("UPDATE local_grns SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markGrnSynced(localId: String)

    @Query("UPDATE local_grns SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markGrnRejected(localId: String, error: String)

    /** A rejected GRN is parked, not auto-retried — same reasoning as CustomersViewModel.retrySync. */
    @Query("UPDATE local_grns SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryGrn(localId: String)

    // ----------------------------------------------------------------- adjustments
    @Insert
    suspend fun insertAdjustment(row: LocalAdjustmentEntity)

    @Query("SELECT * FROM local_adjustments WHERE syncState = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableAdjustments(): List<LocalAdjustmentEntity>

    @Query("SELECT * FROM local_adjustments WHERE syncState != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalAdjustments(): Flow<List<LocalAdjustmentEntity>>

    @Query("SELECT COUNT(*) FROM local_adjustments WHERE syncState = 'rejected'")
    fun observeRejectedAdjustmentCount(): Flow<Int>

    @Query("UPDATE local_adjustments SET syncState = 'synced', lastError = NULL WHERE localId = :localId")
    suspend fun markAdjustmentSynced(localId: String)

    @Query("UPDATE local_adjustments SET syncState = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markAdjustmentRejected(localId: String, error: String)

    @Query("UPDATE local_adjustments SET syncState = 'pending', lastError = NULL WHERE localId = :localId")
    suspend fun retryAdjustment(localId: String)
}
