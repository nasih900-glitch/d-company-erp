package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuWriteDao {

    // ------------------------------------------------------------ categories
    @Query("SELECT * FROM local_menu_categories WHERE state != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalCategories(): Flow<List<LocalMenuCategoryEntity>>

    @Query("SELECT * FROM local_menu_categories WHERE localId = :localId")
    suspend fun getLocalCategory(localId: String): LocalMenuCategoryEntity?

    @Query("SELECT * FROM local_menu_categories WHERE serverId = :serverId AND state != 'synced' LIMIT 1")
    suspend fun pendingCategoryForServerId(serverId: String): LocalMenuCategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalCategory(row: LocalMenuCategoryEntity)

    @Query("SELECT * FROM local_menu_categories WHERE state = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableCategories(): List<LocalMenuCategoryEntity>

    @Query("SELECT COUNT(*) FROM local_menu_categories WHERE state = 'rejected'")
    fun observeRejectedCategoryCount(): Flow<Int>

    @Query("UPDATE local_menu_categories SET serverId = :serverId WHERE localId = :localId")
    suspend fun setCategoryServerId(localId: String, serverId: String)

    @Query(
        "UPDATE local_menu_categories SET state = 'synced', lastError = NULL " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markCategorySynced(localId: String, expectedVersion: Long): Int

    @Query("UPDATE local_menu_categories SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markCategoryRejected(localId: String, error: String)

    @Query("DELETE FROM local_menu_categories WHERE state = 'synced'")
    suspend fun deleteSyncedCategories()

    // ----------------------------------------------------------------- items
    @Query("SELECT * FROM local_menu_items WHERE state != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocalItems(): Flow<List<LocalMenuItemEntity>>

    @Query("SELECT * FROM local_menu_items WHERE serverId = :serverId AND state != 'synced' LIMIT 1")
    suspend fun pendingItemForServerId(serverId: String): LocalMenuItemEntity?

    @Query("SELECT * FROM local_menu_items WHERE localId = :localId")
    suspend fun getLocalItem(localId: String): LocalMenuItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalItem(row: LocalMenuItemEntity)

    @Query("SELECT * FROM local_menu_items WHERE state = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableItems(): List<LocalMenuItemEntity>

    @Query("SELECT COUNT(*) FROM local_menu_items WHERE state = 'rejected'")
    fun observeRejectedItemCount(): Flow<Int>

    /** No setServerId step needed — items only ever edit an already-known serverId, see LocalMenuItemEntity's doc comment. */
    @Query(
        "UPDATE local_menu_items SET state = 'synced', lastError = NULL " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markItemSynced(localId: String, expectedVersion: Long): Int

    @Query("UPDATE local_menu_items SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markItemRejected(localId: String, error: String)

    @Query("DELETE FROM local_menu_items WHERE state = 'synced'")
    suspend fun deleteSyncedItems()
}
