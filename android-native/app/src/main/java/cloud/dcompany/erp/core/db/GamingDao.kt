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

    @Query("SELECT * FROM gaming_session_cache WHERE status = 'active'")
    suspend fun activeSessionCacheForAlarms(): List<GamingSessionCacheEntity>

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
        reconcileLocalSessions(rows)
        deleteSessionCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    /** Applies one authoritative mutation response without replacing the full cache. */
    @Transaction
    suspend fun upsertAuthoritativeSession(row: GamingSessionCacheEntity) {
        upsertSessionCache(listOf(row))
        reconcileLocalSessions(listOf(row))
    }

    // --------------------------------------------------------- local sessions
    @Insert
    suspend fun insertLocalSession(session: LocalGamingSessionEntity)

    @Query(
        "SELECT COUNT(*) FROM local_gaming_sessions WHERE stationId = :stationId " +
            "AND state NOT IN ('sent', 'cancelled')",
    )
    suspend fun unresolvedLocalSessionCount(stationId: String): Int

    /** Prevents a rapid second Start from creating two local lifecycle rows. */
    @Transaction
    suspend fun insertStartIfStationAvailable(session: LocalGamingSessionEntity): Boolean {
        if (unresolvedLocalSessionCount(session.stationId) > 0) return false
        insertLocalSession(session)
        return true
    }

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

    /** Local lifecycle overlays, including ended sessions still waiting for POS. */
    @Query("SELECT * FROM local_gaming_sessions WHERE state NOT IN ('sent', 'cancelled')")
    fun observeActiveLocalSessions(): Flow<List<LocalGamingSessionEntity>>

    /**
     * Includes pending/rejected stops as overlays because the server session
     * remains active and billable until stop is confirmed; the alarm policy
     * therefore keeps their existing deadline armed.
     */
    @Query("SELECT * FROM local_gaming_sessions WHERE state NOT IN ('sent', 'cancelled')")
    suspend fun localSessionOverlaysForAlarms(): List<LocalGamingSessionEntity>

    @Query(
        "SELECT * FROM local_gaming_sessions WHERE serverId IS NOT NULL " +
            "AND state NOT IN ('sent', 'cancelled')",
    )
    suspend fun localSessionsForServerReconciliation(): List<LocalGamingSessionEntity>

    @Query(
        "UPDATE local_gaming_sessions SET state = :state, status = :status, " +
            "endAtMillis = :endAtMillis, billableMinutes = :billableMinutes, " +
            "amountMinor = :amountMinor, orderId = :orderId, lastError = NULL " +
            "WHERE localId = :localId",
    )
    suspend fun applyServerReconciliation(
        localId: String,
        state: String,
        status: String,
        endAtMillis: Long?,
        billableMinutes: Int?,
        amountMinor: Long?,
        orderId: String?,
    )

    /**
     * A stop/cancel/send completed on another terminal (or its response was
     * lost locally). Let that terminal server outcome replace the stale local
     * overlay so the station and its AlarmManager deadline recover together.
     */
    suspend fun reconcileLocalSessions(rows: List<GamingSessionCacheEntity>) {
        val localByServerId = localSessionsForServerReconciliation()
            .mapNotNull { local -> local.serverId?.let { it to local } }
            .toMap()
        rows.forEach { server ->
            val local = localByServerId[server.id] ?: return@forEach
            val state = when (
                gamingServerReconciliation(local.state, server.status, server.orderId)
            ) {
                GamingServerReconciliation.NONE -> return@forEach
                GamingServerReconciliation.START_SYNCED -> GamingSessionState.START_SYNCED
                GamingServerReconciliation.ENDED_UNBILLED -> GamingSessionState.ENDED_UNBILLED
                GamingServerReconciliation.SENT -> GamingSessionState.SENT
                GamingServerReconciliation.CANCELLED -> GamingSessionState.CANCELLED
            }
            applyServerReconciliation(
                localId = local.localId,
                state = state,
                status = server.status,
                endAtMillis = server.endAtMillis,
                billableMinutes = server.billableMinutes,
                amountMinor = server.amountMinor,
                orderId = server.orderId,
            )
        }
    }

    @Query("SELECT * FROM gaming_stations")
    suspend fun stationsForAlarms(): List<GamingStationEntity>

    @Query("SELECT COUNT(*) FROM local_gaming_sessions WHERE state LIKE '%_rejected'")
    fun observeRejectedCount(): Flow<Int>

    /**
     * Runtime safety net for a restored/pre-release database already marked
     * as the current Room version. Normal upgrades are repaired by
     * MIGRATION_16_17; this keeps an imported backup from remaining stranded.
     */
    @Query(RECOVER_LEGACY_GAMING_REJECTIONS_SQL)
    suspend fun recoverLegacyRejectedSessions(): Int

    @Query(
        "SELECT * FROM local_gaming_sessions WHERE state IN ('start_pending', 'stop_pending', 'send_pending') " +
            "ORDER BY startedAtMillis ASC",
    )
    suspend fun pushableSessions(): List<LocalGamingSessionEntity>

    @Query(
        "UPDATE local_gaming_sessions SET serverId = :serverId, status = :status, " +
            "startedAtMillis = :startedAtMillis, timerEndsAtMillis = :timerEndsAtMillis, " +
            "state = CASE WHEN state = 'start_pending' THEN 'start_synced' ELSE state END, " +
            "lastError = NULL WHERE localId = :localId",
    )
    suspend fun setSessionStarted(
        localId: String,
        serverId: String,
        status: String,
        startedAtMillis: Long,
        timerEndsAtMillis: Long?,
    )

    @Query(
        "UPDATE local_gaming_sessions SET state = :toState WHERE localId = :localId AND state = :fromState",
    )
    suspend fun transitionSessionState(localId: String, fromState: String, toState: String)

    @Query(
        "UPDATE local_gaming_sessions SET state = 'stop_pending', status = 'stopping', lastError = NULL " +
            "WHERE localId = :localId AND state IN ('start_synced', 'stop_rejected')",
    )
    suspend fun requestSessionStop(localId: String): Int

    @Query(
        "UPDATE local_gaming_sessions SET state = 'ended_unbilled', status = :status, " +
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

    @Query(
        "UPDATE local_gaming_sessions SET state = 'send_pending', lastError = NULL " +
            "WHERE localId = :localId AND state IN ('ended_unbilled', 'send_rejected')",
    )
    suspend fun requestSessionSend(localId: String): Int

    @Query(
        "UPDATE local_gaming_sessions SET state = 'sent', orderId = :orderId, " +
            "amountMinor = :amountMinor, lastError = NULL WHERE localId = :localId",
    )
    suspend fun markSessionSent(localId: String, orderId: String, amountMinor: Long)

    @Query(
        "UPDATE local_gaming_sessions SET state = :state, lastError = :error, " +
            "status = CASE " +
            "WHEN :state = 'start_rejected' THEN 'start_failed' " +
            "WHEN :state = 'stop_rejected' THEN 'active' " +
            "ELSE status END WHERE localId = :localId",
    )
    suspend fun markSessionRejected(localId: String, state: String, error: String)

    @Query(
        "UPDATE local_gaming_sessions SET state = 'start_pending', status = 'starting', lastError = NULL " +
            "WHERE localId = :localId AND state = 'start_rejected'",
    )
    suspend fun retryRejectedStart(localId: String): Int

    @Query("DELETE FROM local_gaming_sessions WHERE localId = :localId AND state = 'start_rejected'")
    suspend fun discardRejectedStart(localId: String): Int
}
