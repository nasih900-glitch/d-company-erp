package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface GamingDao {

    // ---------------------------------------------------------------- stations
    // Disabled stations remain visible because they may still own an active or
    // unpaid session. Hiding them would hide a financial obligation as well.
    @Query("SELECT * FROM gaming_stations ORDER BY name")
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

    // ------------------------------------------------------------- packages
    @Query("SELECT * FROM gaming_package_cache ORDER BY stationType, variant, durationMinutes")
    fun observePackages(): Flow<List<GamingPackageCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPackages(packages: List<GamingPackageCacheEntity>)

    @Query("DELETE FROM gaming_package_cache WHERE id NOT IN (:keepIds)")
    suspend fun deletePackagesNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replacePackages(packages: List<GamingPackageCacheEntity>) {
        upsertPackages(packages)
        deletePackagesNotIn(packages.map { it.id }.ifEmpty { listOf("") })
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
            "AND state NOT IN ('sent', 'cancelled', 'legacy_resolved')",
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
    @Query("SELECT * FROM local_gaming_sessions WHERE state NOT IN ('sent', 'cancelled', 'legacy_resolved')")
    fun observeActiveLocalSessions(): Flow<List<LocalGamingSessionEntity>>

    /**
     * Includes pending/rejected stops as overlays because the server session
     * remains active and billable until stop is confirmed; the alarm policy
     * therefore keeps their existing deadline armed.
     */
    @Query("SELECT * FROM local_gaming_sessions WHERE state NOT IN ('sent', 'cancelled', 'legacy_resolved')")
    suspend fun localSessionOverlaysForAlarms(): List<LocalGamingSessionEntity>

    @Query(
        "SELECT * FROM local_gaming_sessions WHERE serverId IS NOT NULL " +
            "AND state NOT IN ('sent', 'cancelled', 'legacy_resolved')",
    )
    suspend fun localSessionsForServerReconciliation(): List<LocalGamingSessionEntity>

    @Query(
        "UPDATE local_gaming_sessions SET state = :state, stationId = :stationId, " +
            "shiftId = :shiftId, customerPhone = :customerPhone, timerMinutes = :timerMinutes, " +
            "startedAtMillis = :startedAtMillis, status = :status, endAtMillis = :endAtMillis, " +
            "timerEndsAtMillis = :timerEndsAtMillis, billableMinutes = :billableMinutes, " +
            "amountMinor = :amountMinor, ratePerHourMinor = :ratePerHourMinor, " +
            "packageId = :packageId, billingMode = :billingMode, " +
            "packagePriceMinor = :packagePriceMinor, " +
            "packageDurationMinutes = :packageDurationMinutes, packageVariant = :packageVariant, " +
            "packageStationTypeSnapshot = :packageStationTypeSnapshot, " +
            "extraControllers = :extraControllers, orderId = :orderId, " +
            "lastError = CASE WHEN :clearLastError THEN NULL ELSE lastError END " +
            "WHERE localId = :localId",
    )
    suspend fun applyAuthoritativeSessionSnapshot(
        localId: String,
        state: String,
        stationId: String,
        shiftId: String?,
        customerPhone: String?,
        timerMinutes: Int?,
        startedAtMillis: Long,
        status: String,
        endAtMillis: Long?,
        timerEndsAtMillis: Long?,
        billableMinutes: Int?,
        amountMinor: Long?,
        ratePerHourMinor: Long?,
        packageId: String?,
        billingMode: String?,
        packagePriceMinor: Long?,
        packageDurationMinutes: Int?,
        packageVariant: String?,
        packageStationTypeSnapshot: String?,
        extraControllers: Int,
        orderId: String?,
        clearLastError: Boolean,
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
            val reconciliation = gamingServerReconciliation(
                local.state,
                server.status,
                server.orderId,
            )
            val state = when (reconciliation) {
                GamingServerReconciliation.NONE -> local.state
                GamingServerReconciliation.START_SYNCED -> GamingSessionState.START_SYNCED
                GamingServerReconciliation.ENDED_UNBILLED -> GamingSessionState.ENDED_UNBILLED
                GamingServerReconciliation.SENT -> GamingSessionState.SENT
                GamingServerReconciliation.CANCELLED -> GamingSessionState.CANCELLED
            }
            val retainedLegacyBillingReview =
                local.state == GamingSessionState.START_REJECTED &&
                    local.legacyResolutionAttemptState == GamingLegacyResolutionAttemptState.RESOLVED &&
                    local.legacyResolutionReceiptId != null
            // endAtMillis doubles as the captured stop command until that leg
            // is confirmed. An active server snapshot must refresh every
            // other field without erasing the exact offline tap timestamp.
            val resolvedEndAtMillis = if (retainedLegacyBillingReview) {
                local.endAtMillis
            } else if (
                reconciliation == GamingServerReconciliation.NONE &&
                local.state in setOf(
                    GamingSessionState.STOP_PENDING,
                    GamingSessionState.STOP_REJECTED,
                ) &&
                server.status in setOf("active", "paused")
            ) {
                local.endAtMillis
            } else {
                server.endAtMillis
            }
            applyAuthoritativeSessionSnapshot(
                localId = local.localId,
                state = state,
                stationId = server.stationId,
                shiftId = server.shiftId,
                customerPhone = server.customerPhone,
                timerMinutes = server.timerMinutes,
                startedAtMillis = server.startAtMillis,
                status = if (retainedLegacyBillingReview) local.status else server.status,
                endAtMillis = resolvedEndAtMillis,
                timerEndsAtMillis = server.timerEndsAtMillis,
                billableMinutes = server.billableMinutes,
                amountMinor = server.amountMinor,
                ratePerHourMinor = server.ratePerHourMinor,
                packageId = server.packageId,
                billingMode = server.billingMode,
                packagePriceMinor = server.packagePriceMinorSnapshot,
                packageDurationMinutes = server.packageDurationMinutesSnapshot,
                packageVariant = server.packageVariantSnapshot,
                packageStationTypeSnapshot = server.packageStationTypeSnapshot,
                extraControllers = server.extraControllers,
                orderId = server.orderId,
                clearLastError = reconciliation != GamingServerReconciliation.NONE,
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

    /** Runtime safety net for a current-version DB restored without tap-time package facts. */
    @Query(
        "UPDATE local_gaming_sessions SET state = 'start_rejected', status = 'start_failed', " +
            "legacyOriginalCapturedStartAtMillis = " +
            "COALESCE(legacyOriginalCapturedStartAtMillis, startedAtMillis), " +
            "legacyOriginalCapturedStopAtMillis = " +
            "COALESCE(legacyOriginalCapturedStopAtMillis, endAtMillis), " +
            "lastError = :reviewError WHERE serverId IS NULL AND packageId IS NOT NULL " +
            "AND (packagePriceMinor IS NULL OR packageDurationMinutes IS NULL OR packageVariant IS NULL) " +
            "AND state IN ('start_pending', 'stop_pending', 'start_rejected')",
    )
    suspend fun quarantineUnverifiableLegacyPackageStarts(
        reviewError: String = LEGACY_PACKAGE_START_REVIEW_ERROR,
    ): Int

    @Query(
        "SELECT * FROM local_gaming_sessions WHERE state IN ('start_pending', 'stop_pending', 'send_pending') " +
            "ORDER BY startedAtMillis ASC",
    )
    suspend fun pushableSessions(): List<LocalGamingSessionEntity>

    @Query(
        "UPDATE local_gaming_sessions SET serverId = :serverId, " +
            "status = CASE WHEN state = 'stop_pending' THEN 'stopping' ELSE :status END, shiftId = :shiftId, " +
            "startedAtMillis = :startedAtMillis, timerMinutes = :timerMinutes, " +
            "timerEndsAtMillis = :timerEndsAtMillis, amountMinor = :amountMinor, " +
            "ratePerHourMinor = :ratePerHourMinor, packageId = :packageId, billingMode = :billingMode, " +
            "packagePriceMinor = :packagePriceMinor, packageDurationMinutes = :packageDurationMinutes, " +
            "packageVariant = :packageVariant, packageStationTypeSnapshot = :packageStationTypeSnapshot, " +
            "extraControllers = :extraControllers, " +
            "state = CASE WHEN state = 'start_pending' THEN 'start_synced' ELSE state END, " +
            "lastError = NULL WHERE localId = :localId",
    )
    suspend fun setSessionStarted(
        localId: String,
        serverId: String,
        status: String,
        shiftId: String?,
        startedAtMillis: Long,
        timerMinutes: Int?,
        timerEndsAtMillis: Long?,
        amountMinor: Long?,
        ratePerHourMinor: Long?,
        packageId: String?,
        billingMode: String?,
        packagePriceMinor: Long?,
        packageDurationMinutes: Int?,
        packageVariant: String?,
        packageStationTypeSnapshot: String?,
        extraControllers: Int,
    )

    /** Applies a direct online transfer/extension response to both read layers. */
    @Query(
        "UPDATE local_gaming_sessions SET stationId = :stationId, shiftId = :shiftId, status = :status, " +
            "timerMinutes = :timerMinutes, timerEndsAtMillis = :timerEndsAtMillis, " +
            "amountMinor = :amountMinor, ratePerHourMinor = :ratePerHourMinor, packageId = :packageId, " +
            "billingMode = :billingMode, packagePriceMinor = :packagePriceMinor, " +
            "packageDurationMinutes = :packageDurationMinutes, packageVariant = :packageVariant, " +
            "packageStationTypeSnapshot = :packageStationTypeSnapshot, " +
            "extraControllers = :extraControllers, lastError = NULL WHERE serverId = :serverId " +
            "AND state = 'start_synced'",
    )
    suspend fun updateRunningLocalSnapshot(
        serverId: String,
        stationId: String,
        shiftId: String?,
        status: String,
        timerMinutes: Int?,
        timerEndsAtMillis: Long?,
        amountMinor: Long?,
        ratePerHourMinor: Long?,
        packageId: String?,
        billingMode: String?,
        packagePriceMinor: Long?,
        packageDurationMinutes: Int?,
        packageVariant: String?,
        packageStationTypeSnapshot: String?,
        extraControllers: Int,
    )

    @Query(
        "UPDATE local_gaming_sessions SET state = :toState WHERE localId = :localId AND state = :fromState",
    )
    suspend fun transitionSessionState(localId: String, fromState: String, toState: String)

    @Query(
        "UPDATE local_gaming_sessions SET state = 'stop_pending', status = 'stopping', " +
            "endAtMillis = CASE WHEN state = 'stop_rejected' THEN endAtMillis ELSE :stoppedAtMillis END, " +
            "lastError = NULL " +
            "WHERE localId = :localId AND state IN ('start_pending', 'start_synced', 'stop_rejected')",
    )
    suspend fun requestSessionStop(localId: String, stoppedAtMillis: Long): Int

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

    /** Persist the confirmed POS handoff before any best-effort board refresh. */
    @Query(
        "UPDATE gaming_session_cache SET orderId = :orderId, amountMinor = :amountMinor " +
            "WHERE id = :serverId",
    )
    suspend fun markCachedSessionSent(serverId: String, orderId: String, amountMinor: Long)

    @Query(
        "UPDATE local_gaming_sessions SET state = :state, lastError = :error, " +
            "legacyOriginalCapturedStartAtMillis = CASE WHEN :state = 'start_rejected' " +
            "THEN COALESCE(legacyOriginalCapturedStartAtMillis, startedAtMillis) " +
            "ELSE legacyOriginalCapturedStartAtMillis END, " +
            "legacyOriginalCapturedStopAtMillis = CASE WHEN :state = 'start_rejected' " +
            "THEN COALESCE(legacyOriginalCapturedStopAtMillis, endAtMillis) " +
            "ELSE legacyOriginalCapturedStopAtMillis END, " +
            "status = CASE " +
            "WHEN :state = 'start_rejected' THEN 'start_failed' " +
            "WHEN :state = 'stop_rejected' THEN 'active' " +
            "ELSE status END WHERE localId = :localId",
    )
    suspend fun markSessionRejected(localId: String, state: String, error: String)

    // -------------------------- rejected start evidence reconciliation

    /** Capture or replace only a definitively rejected protected-owner attempt. */
    @Query(
        "UPDATE local_gaming_sessions SET legacyResolution = :resolution, " +
            "legacyResolutionReason = trim(:reason), " +
            "legacyResolutionReferenceOrderId = :referenceOrderId, " +
            "legacyResolutionAttemptState = 'pending', legacyResolutionError = NULL, " +
            "legacyResolutionCapturedAtMillis = :capturedAtMillis, " +
            "legacyOriginalCapturedStartAtMillis = " +
            "COALESCE(legacyOriginalCapturedStartAtMillis, startedAtMillis), " +
            "legacyOriginalCapturedStopAtMillis = " +
            "COALESCE(legacyOriginalCapturedStopAtMillis, endAtMillis), " +
            "legacyResolvedAtMillis = NULL, legacyResolvedByUserId = :actorUserId, " +
            "legacyResolutionReceiptId = NULL " +
            "WHERE localId = :localId AND serverId IS NULL AND state = 'start_rejected' " +
            "AND (legacyResolutionAttemptState IS NULL OR legacyResolutionAttemptState = 'rejected') " +
            "AND :resolution IN " +
            "('manual_bill_recorded', 'confirmed_no_play', 'server_session_recovered') " +
            "AND length(trim(:reason)) BETWEEN 3 AND 500 " +
            "AND length(trim(:actorUserId)) > 0 AND :capturedAtMillis > 0 " +
            "AND ((:resolution = 'manual_bill_recorded' AND :referenceOrderId IS NOT NULL " +
            "AND length(trim(:referenceOrderId)) > 0) OR " +
            "(:resolution IN ('confirmed_no_play', 'server_session_recovered') " +
            "AND :referenceOrderId IS NULL))",
    )
    suspend fun captureLegacyPackageResolution(
        localId: String,
        resolution: String,
        reason: String,
        referenceOrderId: String?,
        actorUserId: String,
        capturedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_gaming_sessions SET legacyResolutionAttemptState = :attemptState, " +
            "legacyResolutionError = :error WHERE localId = :localId " +
            "AND serverId IS NULL AND state = 'start_rejected' " +
            "AND legacyResolutionAttemptState IN ('pending', 'ambiguous')",
    )
    suspend fun markLegacyPackageResolutionAttempt(
        localId: String,
        attemptState: String,
        error: String,
    ): Int

    /**
     * The server audit receipt is authoritative. Every captured request field
     * participates in this CAS so a stale UI response cannot resolve a newer
     * human decision.
     */
    @Query(
        "UPDATE local_gaming_sessions SET state = 'legacy_resolved', status = 'resolved', " +
            "legacyResolutionAttemptState = 'resolved', legacyResolutionError = NULL, " +
            "legacyResolvedAtMillis = :resolvedAtMillis, " +
            "legacyResolutionReceiptId = :receiptId " +
            "WHERE localId = :localId AND serverId IS NULL AND state = 'start_rejected' " +
            "AND legacyResolution = :resolution AND legacyResolutionReason = :reason " +
            "AND legacyResolvedByUserId = :actorUserId " +
            "AND ((legacyResolutionReferenceOrderId IS NULL AND :referenceOrderId IS NULL) OR " +
            "legacyResolutionReferenceOrderId = :referenceOrderId) " +
            "AND legacyResolutionAttemptState IN ('pending', 'ambiguous') " +
            "AND legacyResolutionReceiptId IS NULL AND :receiptId > 0 AND :resolvedAtMillis > 0",
    )
    suspend fun confirmLegacyPackageResolution(
        localId: String,
        resolution: String,
        reason: String,
        referenceOrderId: String?,
        actorUserId: String,
        receiptId: Long,
        resolvedAtMillis: Long,
    ): Int

    /**
     * A response-lost Start can be proven by the server after migration has
     * quarantined the local row. If that row also captured Stop while the
     * server session remains active, advance the SAME local action so the
     * original `gaming-session-stop:{localId}` key replay once. A v27 server
     * start used receipt time, so its authoritative start can be later than
     * the locally captured Stop; only this proven package-recovery path clamps
     * replay to max(captured Stop, authoritative Start). The server receipt
     * retains the original captured Stop as audit evidence.
     */
    @Query(
        "UPDATE local_gaming_sessions SET serverId = :serverId, stationId = :stationId, " +
            "shiftId = :shiftId, customerPhone = :customerPhone, timerMinutes = :timerMinutes, " +
            "legacyOriginalCapturedStartAtMillis = " +
            "COALESCE(legacyOriginalCapturedStartAtMillis, startedAtMillis), " +
            "legacyOriginalCapturedStopAtMillis = " +
            "COALESCE(legacyOriginalCapturedStopAtMillis, endAtMillis), " +
            "startedAtMillis = :startedAtMillis, state = 'stop_pending', status = 'stopping', " +
            "endAtMillis = CASE WHEN endAtMillis < :startedAtMillis AND " +
            "(:billingMode = 'package' OR (:billingMode = 'hourly' " +
            "AND :referenceOrderId IS NOT NULL AND :orderId = :referenceOrderId)) " +
            "THEN :startedAtMillis ELSE endAtMillis END, " +
            "timerEndsAtMillis = :timerEndsAtMillis, billableMinutes = :billableMinutes, " +
            "amountMinor = :amountMinor, ratePerHourMinor = :ratePerHourMinor, " +
            // Preserve the original package identity if the catalogue row was
            // retired and the authoritative FK is now null. The cache row
            // still carries the exact server DTO independently.
            "packageId = COALESCE(:packageId, packageId), billingMode = :billingMode, " +
            "packagePriceMinor = :packagePriceMinor, " +
            "packageDurationMinutes = :packageDurationMinutes, packageVariant = :packageVariant, " +
            "packageStationTypeSnapshot = :packageStationTypeSnapshot, " +
            "extraControllers = :extraControllers, orderId = :orderId, lastError = NULL, " +
            "legacyResolutionAttemptState = 'resolved', legacyResolutionError = NULL, " +
            "legacyResolvedAtMillis = :resolvedAtMillis, legacyResolutionReceiptId = :receiptId " +
            "WHERE localId = :localId AND serverId IS NULL AND state = 'start_rejected' " +
            "AND endAtMillis IS NOT NULL AND :authoritativeStatus IN ('active', 'paused') " +
            "AND ((packageId IS NOT NULL AND :billingMode = 'package') OR " +
            "(packageId IS NULL AND :billingMode = 'hourly' AND :packageId IS NULL " +
            "AND :ratePerHourMinor IS NOT NULL AND ratePerHourMinor = :ratePerHourMinor " +
            "AND (endAtMillis >= :startedAtMillis OR " +
            "(:referenceOrderId IS NOT NULL AND :orderId = :referenceOrderId)))) " +
            "AND legacyResolution = :resolution AND legacyResolutionReason = :reason " +
            "AND legacyResolvedByUserId = :actorUserId " +
            "AND ((legacyResolutionReferenceOrderId IS NULL AND :referenceOrderId IS NULL) OR " +
            "legacyResolutionReferenceOrderId = :referenceOrderId) " +
            "AND legacyResolutionAttemptState IN ('pending', 'ambiguous') " +
            "AND legacyResolutionReceiptId IS NULL AND :receiptId > 0 AND :resolvedAtMillis > 0",
    )
    suspend fun adoptRecoveredLegacyStartForPendingStop(
        localId: String,
        resolution: String,
        reason: String,
        referenceOrderId: String?,
        actorUserId: String,
        receiptId: Long,
        resolvedAtMillis: Long,
        serverId: String,
        stationId: String,
        shiftId: String?,
        customerPhone: String?,
        timerMinutes: Int?,
        startedAtMillis: Long,
        authoritativeStatus: String,
        timerEndsAtMillis: Long?,
        billableMinutes: Int?,
        amountMinor: Long?,
        ratePerHourMinor: Long?,
        packageId: String?,
        billingMode: String?,
        packagePriceMinor: Long?,
        packageDurationMinutes: Int?,
        packageVariant: String?,
        packageStationTypeSnapshot: String?,
        extraControllers: Int,
        orderId: String?,
    ): Int

    /**
     * Persist a real recovery receipt and authoritative running snapshot while
     * deliberately keeping the local safety blocker. This is used when an old
     * hourly Stop predates the server receipt-time Start (or another billing
     * invariant is ambiguous): ordinary Stop/Send must remain unavailable.
     */
    @Query(
        "UPDATE local_gaming_sessions SET serverId = :serverId, stationId = :stationId, " +
            "shiftId = :shiftId, customerPhone = :customerPhone, timerMinutes = :timerMinutes, " +
            "legacyOriginalCapturedStartAtMillis = " +
            "COALESCE(legacyOriginalCapturedStartAtMillis, startedAtMillis), " +
            "legacyOriginalCapturedStopAtMillis = " +
            "COALESCE(legacyOriginalCapturedStopAtMillis, endAtMillis), " +
            "startedAtMillis = :startedAtMillis, state = 'start_rejected', status = 'start_failed', " +
            "timerEndsAtMillis = :timerEndsAtMillis, billableMinutes = :billableMinutes, " +
            "amountMinor = :amountMinor, ratePerHourMinor = :ratePerHourMinor, " +
            "packageId = COALESCE(:packageId, packageId), billingMode = :billingMode, " +
            "packagePriceMinor = :packagePriceMinor, " +
            "packageDurationMinutes = :packageDurationMinutes, packageVariant = :packageVariant, " +
            "packageStationTypeSnapshot = :packageStationTypeSnapshot, " +
            "extraControllers = :extraControllers, orderId = :orderId, lastError = :reviewError, " +
            "legacyResolutionAttemptState = 'resolved', legacyResolutionError = :reviewError, " +
            "legacyResolvedAtMillis = :resolvedAtMillis, legacyResolutionReceiptId = :receiptId " +
            "WHERE localId = :localId AND serverId IS NULL AND state = 'start_rejected' " +
            "AND legacyResolution = :resolution AND legacyResolutionReason = :reason " +
            "AND legacyResolvedByUserId = :actorUserId " +
            "AND ((legacyResolutionReferenceOrderId IS NULL AND :referenceOrderId IS NULL) OR " +
            "legacyResolutionReferenceOrderId = :referenceOrderId) " +
            "AND legacyResolutionAttemptState IN ('pending', 'ambiguous') " +
            "AND legacyResolutionReceiptId IS NULL AND :receiptId > 0 AND :resolvedAtMillis > 0",
    )
    suspend fun retainRecoveredLegacyBillingReview(
        localId: String,
        resolution: String,
        reason: String,
        referenceOrderId: String?,
        actorUserId: String,
        receiptId: Long,
        resolvedAtMillis: Long,
        reviewError: String,
        serverId: String,
        stationId: String,
        shiftId: String?,
        customerPhone: String?,
        timerMinutes: Int?,
        startedAtMillis: Long,
        timerEndsAtMillis: Long?,
        billableMinutes: Int?,
        amountMinor: Long?,
        ratePerHourMinor: Long?,
        packageId: String?,
        billingMode: String?,
        packagePriceMinor: Long?,
        packageDurationMinutes: Int?,
        packageVariant: String?,
        packageStationTypeSnapshot: String?,
        extraControllers: Int,
        orderId: String?,
    ): Int

    /**
     * Store the owner receipt and authoritative shared-board row together. A
     * crash can therefore expose neither a cleared blocker without its server
     * session nor a recovered server session while the local proof remains in
     * an indeterminate state.
     */
    @Transaction
    suspend fun confirmRecoveredLegacyServerSession(
        localId: String,
        capturedResolution: String,
        reason: String,
        referenceOrderId: String?,
        actorUserId: String,
        receiptId: Long,
        resolvedAtMillis: Long,
        authoritative: GamingSessionCacheEntity,
        disposition: RecoveredLegacyServerDisposition,
        billingReviewError: String? = null,
    ): Boolean {
        if (!authoritative.hasSafeRecoveredLegacyBillingEvidence()) return false
        if (authoritative.status !in setOf("active", "paused", "ended", "cancelled")) return false
        val retained = localSessionById(localId) ?: return false
        if (
            authoritative.billingMode == "hourly" &&
            retained.ratePerHourMinor != authoritative.ratePerHourMinor &&
            disposition != RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW
        ) return false
        if (
            disposition == RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP &&
            authoritative.status !in setOf("active", "paused")
        ) return false
        if (
            disposition == RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP &&
            retained.endAtMillis == null
        ) return false
        if (
            disposition == RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP &&
            authoritative.billingMode == "hourly" &&
            requireNotNull(retained.endAtMillis) < authoritative.startAtMillis &&
            (referenceOrderId == null || authoritative.orderId != referenceOrderId)
        ) return false
        if (
            disposition == RecoveredLegacyServerDisposition.RESOLVE_LOCAL &&
            authoritative.status in setOf("active", "paused") && retained.endAtMillis != null
        ) return false
        val changed = when (disposition) {
            RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP ->
                adoptRecoveredLegacyStartForPendingStop(
                localId = localId,
                resolution = capturedResolution,
                reason = reason,
                referenceOrderId = referenceOrderId,
                actorUserId = actorUserId,
                receiptId = receiptId,
                resolvedAtMillis = resolvedAtMillis,
                serverId = authoritative.id,
                stationId = authoritative.stationId,
                shiftId = authoritative.shiftId,
                customerPhone = authoritative.customerPhone,
                timerMinutes = authoritative.timerMinutes,
                startedAtMillis = authoritative.startAtMillis,
                authoritativeStatus = authoritative.status,
                timerEndsAtMillis = authoritative.timerEndsAtMillis,
                billableMinutes = authoritative.billableMinutes,
                amountMinor = authoritative.amountMinor,
                ratePerHourMinor = authoritative.ratePerHourMinor,
                packageId = authoritative.packageId,
                billingMode = authoritative.billingMode,
                packagePriceMinor = authoritative.packagePriceMinorSnapshot,
                packageDurationMinutes = authoritative.packageDurationMinutesSnapshot,
                packageVariant = authoritative.packageVariantSnapshot,
                packageStationTypeSnapshot = authoritative.packageStationTypeSnapshot,
                extraControllers = authoritative.extraControllers,
                orderId = authoritative.orderId,
            )
            RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW ->
                retainRecoveredLegacyBillingReview(
                    localId = localId,
                    resolution = capturedResolution,
                    reason = reason,
                    referenceOrderId = referenceOrderId,
                    actorUserId = actorUserId,
                    receiptId = receiptId,
                    resolvedAtMillis = resolvedAtMillis,
                    reviewError = billingReviewError?.trim()?.takeIf(String::isNotEmpty)
                        ?: return false,
                    serverId = authoritative.id,
                    stationId = authoritative.stationId,
                    shiftId = authoritative.shiftId,
                    customerPhone = authoritative.customerPhone,
                    timerMinutes = authoritative.timerMinutes,
                    startedAtMillis = authoritative.startAtMillis,
                    timerEndsAtMillis = authoritative.timerEndsAtMillis,
                    billableMinutes = authoritative.billableMinutes,
                    amountMinor = authoritative.amountMinor,
                    ratePerHourMinor = authoritative.ratePerHourMinor,
                    packageId = authoritative.packageId,
                    billingMode = authoritative.billingMode,
                    packagePriceMinor = authoritative.packagePriceMinorSnapshot,
                    packageDurationMinutes = authoritative.packageDurationMinutesSnapshot,
                    packageVariant = authoritative.packageVariantSnapshot,
                    packageStationTypeSnapshot = authoritative.packageStationTypeSnapshot,
                    extraControllers = authoritative.extraControllers,
                    orderId = authoritative.orderId,
                )
            RecoveredLegacyServerDisposition.RESOLVE_LOCAL -> confirmLegacyPackageResolution(
                localId = localId,
                resolution = capturedResolution,
                reason = reason,
                referenceOrderId = referenceOrderId,
                actorUserId = actorUserId,
                receiptId = receiptId,
                resolvedAtMillis = resolvedAtMillis,
            )
        }
        if (changed != 1) return false
        upsertSessionCache(listOf(authoritative))
        return true
    }

    // ---------------------------------------------- paid package extensions

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPackageExtensionAction(action: LocalGamingPackageExtensionEntity)

    @Query(
        "SELECT COUNT(*) FROM local_gaming_package_extensions " +
            "WHERE serverSessionId = :serverSessionId AND state NOT IN ('confirmed', 'discarded')",
    )
    suspend fun unresolvedPackageExtensionCount(serverSessionId: String): Int

    /**
     * Captures exactly one unresolved financial extension per server session.
     * The immutable UUID is never upserted, so an accidental key collision
     * cannot rewrite the amount/package snapshot of an earlier request.
     */
    @Transaction
    suspend fun capturePackageExtension(action: LocalGamingPackageExtensionEntity): Boolean {
        require(action.state == GamingPackageExtensionState.PENDING)
        require(runCatching { UUID.fromString(action.actionId) }.isSuccess)
        require(action.serverSessionId.isNotBlank())
        require(!action.shiftId.isNullOrBlank())
        require(action.packageId.isNotBlank())
        require(action.expectedPackagePriceMinor > 0L)
        require(action.expectedPackageDurationMinutes > 0)
        require(action.expectedPackageVariant.isNotBlank())
        require(action.expectedSessionTimerMinutes >= 0)
        require(action.expectedSessionAmountMinor >= 0L)
        require(action.createdAtMillis > 0L)
        require(action.lastError == null)
        require(action.resolvedAtMillis == null)
        require(action.resolutionReason == null)
        if (unresolvedPackageExtensionCount(action.serverSessionId) > 0) return false
        insertPackageExtensionAction(action)
        return true
    }

    @Query("SELECT * FROM local_gaming_package_extensions WHERE actionId = :actionId LIMIT 1")
    suspend fun packageExtensionAction(actionId: String): LocalGamingPackageExtensionEntity?

    /** Ambiguous requests replay with their original action UUID/idempotency key. */
    @Query(
        "SELECT * FROM local_gaming_package_extensions " +
            "WHERE state IN ('pending', 'ambiguous') ORDER BY createdAtMillis ASC, actionId ASC",
    )
    suspend fun packageExtensionsForSync(): List<LocalGamingPackageExtensionEntity>

    @Query(
        "SELECT * FROM local_gaming_package_extensions " +
            "WHERE state NOT IN ('confirmed', 'discarded') " +
            "ORDER BY createdAtMillis ASC, actionId ASC",
    )
    fun observeUnresolvedPackageExtensions(): Flow<List<LocalGamingPackageExtensionEntity>>

    @Query(
        "UPDATE local_gaming_package_extensions SET state = 'ambiguous', lastError = :error " +
            "WHERE actionId = :actionId AND state IN ('pending', 'ambiguous', 'rejected')",
    )
    suspend fun markPackageExtensionAmbiguous(actionId: String, error: String): Int

    @Query(
        "UPDATE local_gaming_package_extensions SET state = 'confirmed', lastError = NULL " +
            "WHERE actionId = :actionId AND state IN ('pending', 'ambiguous', 'rejected')",
    )
    suspend fun markPackageExtensionConfirmed(actionId: String): Int

    @Query(
        "UPDATE local_gaming_package_extensions SET state = 'rejected', lastError = :error " +
            "WHERE actionId = :actionId AND state IN ('pending', 'ambiguous')",
    )
    suspend fun markPackageExtensionRejected(actionId: String, error: String): Int

    @Query(
        "UPDATE local_gaming_package_extensions SET state = 'discarded', " +
            "resolvedAtMillis = :resolvedAtMillis, resolutionReason = trim(:reason) " +
            "WHERE actionId = :actionId AND state = 'rejected' " +
            "AND :resolvedAtMillis > 0 AND length(trim(:reason)) BETWEEN 3 AND 500",
    )
    suspend fun markRejectedPackageExtensionDiscarded(
        actionId: String,
        reason: String,
        resolvedAtMillis: Long,
    ): Int

    /**
     * Retains deterministic-refusal evidence after a deliberate staff
     * acknowledgement. Pending/ambiguous money writes cannot pass the CAS.
     */
    @Transaction
    suspend fun discardRejectedPackageExtension(
        actionId: String,
        reason: String,
        resolvedAtMillis: Long,
    ): Int {
        val normalizedReason = reason.trim()
        require(normalizedReason.length in 3..500)
        require(resolvedAtMillis > 0L)
        return markRejectedPackageExtensionDiscarded(
            actionId = actionId,
            reason = normalizedReason,
            resolvedAtMillis = resolvedAtMillis,
        )
    }
}

/**
 * A quarantined pre-v28 package Start may only adopt a server row whose
 * locked amount and package discriminator are complete enough to stop/send
 * safely. A retired package legitimately has all four catalogue snapshots
 * absent; a partially populated snapshot is corruption and remains blocked.
 */
internal fun GamingSessionCacheEntity.hasSafeRecoveredLegacyBillingEvidence(): Boolean {
    if (billingMode == "hourly") {
        return packageId == null && ratePerHourMinor != null && ratePerHourMinor >= 0L &&
            packagePriceMinorSnapshot == null && packageDurationMinutesSnapshot == null &&
            packageVariantSnapshot == null && packageStationTypeSnapshot == null
    }
    if (billingMode != "package" || amountMinor == null || amountMinor < 0L) return false
    val allSnapshotsMissing = packagePriceMinorSnapshot == null &&
        packageDurationMinutesSnapshot == null && packageVariantSnapshot == null &&
        packageStationTypeSnapshot == null
    val allSnapshotsComplete = packagePriceMinorSnapshot != null && packagePriceMinorSnapshot >= 0L &&
        packageDurationMinutesSnapshot != null && packageDurationMinutesSnapshot > 0 &&
        !packageVariantSnapshot.isNullOrBlank() && !packageStationTypeSnapshot.isNullOrBlank()
    return allSnapshotsMissing || allSnapshotsComplete
}
