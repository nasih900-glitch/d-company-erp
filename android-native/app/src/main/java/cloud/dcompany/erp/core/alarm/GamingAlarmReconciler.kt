package cloud.dcompany.erp.core.alarm

import android.content.Context
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GamingAlarmCandidate(
    val sessionId: String,
    val stationId: String,
    val stationName: String,
    val endsAtMillis: Long,
) {
    val tag: String get() = "gaming-session-$sessionId"

    fun toAlarm(): OperationalAlarmSpec = OperationalAlarmSpec(
        kind = OperationalAlarmKind.GAMING,
        tag = tag,
        triggerAtMillis = endsAtMillis,
        title = "Session over — $stationName",
        body = "The booked time has finished. Open Gaming to stop or extend the session.",
        target = OperationalNotificationTarget.GamingSession(sessionId, stationId),
    )
}

/**
 * Builds the authoritative alarm set from Room.
 *
 * A v28 pending local start is operational work captured at the employee's
 * tap and therefore owns its local deadline before reconnect. A pending stop
 * keeps that deadline until the exact captured end is confirmed. Only a
 * terminal/rejected start without a usable deadline suppresses the alarm.
 */
internal fun gamingAlarmCandidates(
    cache: List<GamingSessionCacheEntity>,
    local: List<LocalGamingSessionEntity>,
    stations: List<GamingStationEntity>,
): List<GamingAlarmCandidate> {
    val stationNames = stations.associate { it.id to it.name }
    val localByServerId = local.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
    val cachedServerIds = cache.mapTo(mutableSetOf()) { it.id }
    val fromCache = cache.asSequence()
        .filter { it.status == "active" }
        .mapNotNull { row ->
            val overlay = localByServerId[row.id]
            val endsAt = when (overlay?.state) {
                null -> row.timerEndsAtMillis
                GamingSessionState.START_SYNCED,
                GamingSessionState.STOP_PENDING,
                GamingSessionState.STOP_REJECTED,
                -> overlay.timerEndsAtMillis ?: row.timerEndsAtMillis
                else -> null
            }
            endsAt?.let {
                GamingAlarmCandidate(
                    sessionId = row.id,
                    stationId = row.stationId,
                    stationName = stationNames[row.stationId] ?: "gaming station",
                    endsAtMillis = it,
                )
            }
        }
    val fromLocal = local.asSequence()
        .filter {
            it.timerEndsAtMillis != null &&
                (
                    it.state == GamingSessionState.START_PENDING ||
                        it.state == GamingSessionState.START_SYNCED ||
                        it.state == GamingSessionState.STOP_PENDING ||
                        it.state == GamingSessionState.STOP_REJECTED
                ) &&
                (it.serverId == null || it.serverId !in cachedServerIds)
        }
        .mapNotNull { row ->
            row.timerEndsAtMillis?.let { endsAt ->
                GamingAlarmCandidate(
                    sessionId = row.serverId ?: row.localId,
                    stationId = row.stationId,
                    stationName = stationNames[row.stationId] ?: "gaming station",
                    endsAtMillis = endsAt,
                )
            }
        }
    return (fromCache + fromLocal).distinctBy { it.tag }.toList()
}

/**
 * Makes AlarmManager match Room exactly and remembers the scheduled tags.
 * The tag set is persisted because AlarmManager cannot enumerate an app's
 * alarms; without it an early stop or server-side stop leaves a ghost alert.
 */
object GamingAlarmReconciler {
    private val mutex = Mutex()

    internal suspend fun currentAlarms(context: Context): List<OperationalAlarmSpec> {
        if (!OperationalAlarmRuntime.hasActiveOwnedScope(context)) return emptyList()
        val lease = DCompanyApp.instance.cacheIsolation.currentLease() ?: return emptyList()
        val dao = DCompanyApp.instance.db.gamingDao()
        val alarms = gamingAlarmCandidates(
            cache = dao.activeSessionCacheForAlarms(),
            local = dao.localSessionOverlaysForAlarms(),
            stations = dao.stationsForAlarms(),
        ).map(GamingAlarmCandidate::toAlarm)
        return alarms.takeIf { DCompanyApp.instance.cacheIsolation.currentLease() == lease }
            ?: emptyList()
    }

    suspend fun reconcile(context: Context) = mutex.withLock {
        val appContext = context.applicationContext
        OperationalAlarmRegistry.reconcile(
            context = appContext,
            kind = OperationalAlarmKind.GAMING,
            desired = currentAlarms(appContext),
        )
    }
}
