package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StaffDao {

    // ----------------------------------------------------------- read cache
    @Query("SELECT * FROM staff_cache ORDER BY name")
    fun observeCache(): Flow<List<StaffCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(rows: List<StaffCacheEntity>)

    @Query("DELETE FROM staff_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteCacheNotIn(keepIds: List<String>)

    /** Wholesale-replaced, same reasoning as [CustomerDao.replaceCache]. */
    @Transaction
    suspend fun replaceCache(rows: List<StaffCacheEntity>) {
        upsertCache(rows)
        deleteCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // -------------------------------------------------------- local writes
    /** Pending or rejected only — a synced row is deleted, see [deleteSynced]. */
    @Query("SELECT * FROM local_staff WHERE state != 'synced' ORDER BY createdAtMillis DESC")
    fun observeLocal(): Flow<List<LocalStaffEntity>>

    @Query("SELECT * FROM local_staff WHERE localId = :localId")
    suspend fun getLocal(localId: String): LocalStaffEntity?

    /** The still-unsynced write already queued against this server staff member, if any. */
    @Query("SELECT * FROM local_staff WHERE serverId = :serverId AND state != 'synced' LIMIT 1")
    suspend fun pendingLocalForServerId(serverId: String): LocalStaffEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocal(row: LocalStaffEntity)

    @Query("SELECT * FROM local_staff WHERE state = 'pending' ORDER BY createdAtMillis ASC")
    suspend fun pushable(): List<LocalStaffEntity>

    @Query("SELECT COUNT(*) FROM local_staff WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    /** See [LocalCustomerEntity]'s doc comment on the equivalent `markSynced` for the version-CAS reasoning. */
    @Query(
        "UPDATE local_staff SET state = 'synced', lastError = NULL " +
            "WHERE localId = :localId AND version = :expectedVersion",
    )
    suspend fun markSynced(localId: String, expectedVersion: Long): Int

    @Query("UPDATE local_staff SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markRejected(localId: String, error: String)

    /** Cache already reflects these once the pull after a push completes — nothing left to keep. */
    @Query("DELETE FROM local_staff WHERE state = 'synced'")
    suspend fun deleteSynced()

    /** Abandons a not-yet-applied local write outright — see StaffViewModel.cancelPendingRemoval. */
    @Query("DELETE FROM local_staff WHERE localId = :localId")
    suspend fun deleteLocal(localId: String)
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM on_shift_cache ORDER BY clockInAt ASC")
    fun observeOnShift(): Flow<List<OnShiftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOnShift(rows: List<OnShiftEntity>)

    @Query("DELETE FROM on_shift_cache WHERE attendanceId NOT IN (:keepIds)")
    suspend fun deleteOnShiftNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceOnShift(rows: List<OnShiftEntity>) {
        upsertOnShift(rows)
        deleteOnShiftNotIn(rows.map { it.attendanceId }.ifEmpty { listOf("") })
    }
}
