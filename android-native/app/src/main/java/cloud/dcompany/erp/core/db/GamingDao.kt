package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GamingDao {

    // ---------------------------------------------------------------- stations
    @Query("SELECT * FROM gaming_stations WHERE isActive = 1 ORDER BY name")
    fun observeStations(): Flow<List<GamingStationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStations(stations: List<GamingStationEntity>)

    @Query("DELETE FROM gaming_stations WHERE id NOT IN (:keepIds)")
    suspend fun deleteStationsNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceStations(stations: List<GamingStationEntity>) {
        upsertStations(stations)
        deleteStationsNotIn(stations.map { it.id }.ifEmpty { listOf("") })
    }

    // ------------------------------------------------------------ session cache
    @Query("SELECT * FROM gaming_session_cache ORDER BY startAtMillis DESC")
    fun observeSessionCache(): Flow<List<GamingSessionCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessionCache(rows: List<GamingSessionCacheEntity>)

    @Query("DELETE FROM gaming_session_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteSessionCacheNotIn(keepIds: List<String>)

    /**
     * Wholesale-replaced like the menu cache — the server is authoritative
     * for every session on every terminal, this tablet never edits these
     * rows locally.
     */
    @Transaction
    suspend fun replaceSessionCache(rows: List<GamingSessionCacheEntity>) {
        upsertSessionCache(rows)
        deleteSessionCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    // --------------------------------------------------------- local sessions
    @Insert
    suspend fun insertLocalSession(session: LocalGamingSessionEntity)

    @Query("SELECT * FROM local_gaming_sessions WHERE localId = :localId")
    suspend fun localSessionById(localId: String): LocalGamingSessionEntity?

    /**
     * A UI-facing session id is either this device's own `localId` (not yet
     * synced) or a real `serverId` (synced, or seen only via the cache
     * because another terminal started it) — the caller doesn't know which,
     * so this checks both.
     */
    @Query("SELECT * FROM local_gaming_sessions WHERE localId = :id OR serverId = :id LIMIT 1")
    suspend fun localSessionByEitherId(id: String): LocalGamingSessionEntity?

    /** Every local session not yet stopped/rejected — merged with the cache at read time. */
    @Query("SELECT * FROM local_gaming_sessions WHERE state != 'stopped' AND state != 'rejected'")
    fun observeActiveLocalSessions(): Flow<List<LocalGamingSessionEntity>>

    @Query("SELECT COUNT(*) FROM local_gaming_sessions WHERE state = 'rejected'")
    fun observeRejectedCount(): Flow<Int>

    @Query(
        "SELECT * FROM local_gaming_sessions WHERE state = 'start_pending' OR state = 'stop_pending' " +
            "ORDER BY startedAtMillis ASC",
    )
    suspend fun pushableSessions(): List<LocalGamingSessionEntity>

    @Query(
        "UPDATE local_gaming_sessions SET serverId = :serverId, status = :status, " +
            "timerEndsAtMillis = :timerEndsAtMillis WHERE localId = :localId",
    )
    suspend fun setSessionStarted(
        localId: String,
        serverId: String,
        status: String,
        timerEndsAtMillis: Long?,
    )

    @Query(
        "UPDATE local_gaming_sessions SET state = :toState WHERE localId = :localId AND state = :fromState",
    )
    suspend fun transitionSessionState(localId: String, fromState: String, toState: String)

    @Query("UPDATE local_gaming_sessions SET state = 'stop_pending' WHERE localId = :localId")
    suspend fun requestSessionStop(localId: String)

    @Query(
        "UPDATE local_gaming_sessions SET state = 'stopped', status = :status, " +
            "endAtMillis = :endAtMillis, billableMinutes = :billableMinutes, amountMinor = :amountMinor " +
            "WHERE localId = :localId",
    )
    suspend fun markSessionStopped(
        localId: String,
        status: String,
        endAtMillis: Long?,
        billableMinutes: Int?,
        amountMinor: Long?,
    )

    @Query("UPDATE local_gaming_sessions SET state = 'rejected', lastError = :error WHERE localId = :localId")
    suspend fun markSessionRejected(localId: String, error: String)
}
