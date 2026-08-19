package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {

    @Insert
    suspend fun insert(shift: LocalShiftEntity)

    @Query("SELECT * FROM local_shifts WHERE localId = :localId")
    suspend fun byLocalId(localId: String): LocalShiftEntity?

    /**
     * The shift this tablet is currently on, offline or not — the till bills
     * against this row's `serverShiftId ?: localId`, never a live network call.
     */
    @Query(
        "SELECT * FROM local_shifts WHERE state != 'closed' AND state != 'rejected' " +
            "ORDER BY openedAtMillis DESC LIMIT 1",
    )
    fun observeCurrent(): Flow<LocalShiftEntity?>

    @Query("SELECT * FROM local_shifts WHERE state = 'closed' ORDER BY closedAtMillis DESC LIMIT :limit")
    fun observeHistory(limit: Int = 30): Flow<List<LocalShiftEntity>>

    @Query("SELECT COUNT(*) FROM local_shifts WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    /**
     * Rows with a leg still to send: an open not yet confirmed, or a close
     * requested (which may itself be waiting on that same open — see
     * SyncEngine.pushShiftOne). Oldest first, so a shift closed in a hurry
     * doesn't jump ahead of one still waiting on its open leg.
     */
    @Query(
        "SELECT * FROM local_shifts WHERE state = 'open_pending' OR state = 'close_pending' " +
            "ORDER BY openedAtMillis ASC",
    )
    suspend fun pushable(): List<LocalShiftEntity>

    /** Unconditional — fills the id whether the row is still open_pending or has already moved to close_pending. */
    @Query("UPDATE local_shifts SET serverShiftId = :serverShiftId WHERE localId = :localId")
    suspend fun setServerShiftId(localId: String, serverShiftId: String)

    /** Guarded by `fromState` so a close requested mid-flight is never downgraded back to open_synced. */
    @Query("UPDATE local_shifts SET state = :toState WHERE localId = :localId AND state = :fromState")
    suspend fun transitionState(localId: String, fromState: String, toState: String)

    @Query(
        "UPDATE local_shifts SET state = 'close_pending', countedMinor = :countedMinor, " +
            "closedAtMillis = :closedAtMillis WHERE localId = :localId",
    )
    suspend fun requestClose(localId: String, countedMinor: Long, closedAtMillis: Long)

    @Query(
        "UPDATE local_shifts SET state = 'closed', varianceMinor = :varianceMinor, lastError = NULL " +
            "WHERE localId = :localId",
    )
    suspend fun markClosed(localId: String, varianceMinor: Long)

    @Query("UPDATE local_shifts SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markRejected(localId: String, error: String)
}
