package cloud.dcompany.erp.ui.screens.gaming

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.alarm.AlarmScheduler
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

data class GamingUiState(
    val stations: List<Station> = emptyList(),
    val sessions: List<GameSession> = emptyList(),
    val busyStationId: String? = null,
    val error: String? = null,
    /** A gaming pull has completed at least once on this device. */
    val everSynced: Boolean = false,
    /** Ticks every second so elapsed timers re-render. */
    val nowMillis: Long = System.currentTimeMillis(),
) {
    fun activeFor(stationId: String): GameSession? =
        sessions.firstOrNull { it.stationId == stationId && it.status == "active" }
}

/**
 * Room-backed, offline-first — same shape as PosViewModel/ShiftViewModel.
 * Stations and sessions both read from Room, never the network directly.
 *
 * Sessions are the one resource so far that's simultaneously a shared,
 * cross-terminal read (any tablet can start a session at any station, so
 * this device needs to see what every other terminal is doing) and a local
 * write outbox (this device's own start/stop must work offline). The two
 * live in separate tables — [GamingSessionCacheEntity] wholesale-replaced
 * from the server like the menu, [LocalGamingSessionEntity] for this
 * device's in-flight actions — and are merged at read time below, never
 * written into each other.
 */
class GamingViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db

    private val busyStationId = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val nowMillis = MutableStateFlow(System.currentTimeMillis())

    val state: StateFlow<GamingUiState> = combine(
        db.gamingDao().observeStations(),
        db.gamingDao().observeSessionCache(),
        db.gamingDao().observeActiveLocalSessions(),
        combine(busyStationId, error, nowMillis, ::Triple),
        db.syncMetaDao().observe("gaming"),
    ) { stations, cache, local, ui, meta ->
        val cacheSessions = cache.map { it.toGameSession() }
        val cachedServerIds = cache.map { it.id }.toSet()
        // A local row already visible via the cache (its action synced and a
        // pull landed) would otherwise show twice — only this device's still
        // -pending or not-yet-pulled sessions belong here on top of it.
        val localOnly = local
            .filter { it.serverId == null || it.serverId !in cachedServerIds }
            .map { it.toGameSession() }
        GamingUiState(
            stations = stations.map { it.toStation() },
            sessions = cacheSessions + localOnly,
            busyStationId = ui.first,
            error = ui.second,
            everSynced = meta != null,
            nowMillis = ui.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamingUiState())

    init {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("gaming") }
        // A local 1s tick drives only the on-screen elapsed clock. It is
        // deliberately NOT what raises the overtime alert — see scheduleAlarm:
        // a coroutine stops with the screen, an AlarmManager alarm does not.
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                nowMillis.value = System.currentTimeMillis()
            }
        }
        // Re-arms on every emission, not just once at start — a session that
        // syncs while the screen is open picks up a real timerEndsAt for the
        // first time right then, and needs its alarm scheduled at that
        // moment, not only at app launch.
        viewModelScope.launch {
            state.map { it.sessions.filter { s -> s.status == "active" } }
                .distinctUntilChanged()
                .collect { active -> active.forEach(::scheduleAlarm) }
        }
    }

    fun load() {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("gaming") }
    }

    fun start(station: Station, phone: String?, timerMinutes: Int?) {
        val shift = appCtx.shiftCacheIdOrNull()
        if (shift == null) {
            error.value = "No open shift. Open a shift before starting a session."
            return
        }
        busyStationId.value = station.id
        error.value = null
        viewModelScope.launch {
            db.gamingDao().insertLocalSession(
                LocalGamingSessionEntity(
                    localId = UUID.randomUUID().toString(),
                    stationId = station.id,
                    shiftId = shift,
                    customerPhone = phone?.trim()?.takeIf { it.isNotEmpty() },
                    timerMinutes = timerMinutes,
                    startedAtMillis = System.currentTimeMillis(),
                    state = GamingSessionState.START_PENDING,
                    status = "active",
                ),
            )
            busyStationId.value = null
            appCtx.sync.requestSync()
        }
    }

    fun stop(session: GameSession) {
        busyStationId.value = session.stationId
        error.value = null
        viewModelScope.launch {
            val dao = db.gamingDao()
            val existing = dao.localSessionByEitherId(session.id)
            if (existing != null) {
                // Already an outbox row for this session (this device started
                // it, possibly still unsynced) — just flag the stop.
                dao.requestSessionStop(existing.localId)
            } else {
                // A session this device only ever saw via the cache (started
                // on another terminal). serverId is already known, so
                // pushGamingSessionOne skips straight to the stop leg.
                dao.insertLocalSession(
                    LocalGamingSessionEntity(
                        localId = UUID.randomUUID().toString(),
                        serverId = session.id,
                        stationId = session.stationId,
                        startedAtMillis = runCatching { Instant.parse(session.startAt).toEpochMilli() }
                            .getOrDefault(System.currentTimeMillis()),
                        state = GamingSessionState.STOP_PENDING,
                        status = session.status,
                    ),
                )
            }
            AlarmScheduler.cancel(getApplication(), alarmTag(session.id))
            busyStationId.value = null
            appCtx.sync.requestSync()
        }
    }

    fun dismissError() { error.value = null }

    private fun alarmTag(sessionId: String) = "gaming-session-$sessionId"

    /**
     * Hands the deadline to the OS the moment it is known.
     *
     * This is the concrete thing the WebView build could not do: its timer
     * only ran while the WebView was alive and awake, so a session that ran
     * over while the tablet slept alerted late or not at all — and unbilled
     * minutes are lost money.
     */
    private fun scheduleAlarm(session: GameSession) {
        val endsAt = session.timerEndsAt ?: return
        val at = runCatching { Instant.parse(endsAt).toEpochMilli() }.getOrElse { return }
        if (at <= System.currentTimeMillis()) return
        val station = state.value.stations.firstOrNull { it.id == session.stationId }
        AlarmScheduler.schedule(
            context = getApplication(),
            tag = alarmTag(session.id),
            atEpochMillis = at,
            title = "Session over — ${station?.name ?: "gaming station"}",
            body = "The booked time has finished. Stop the session or extend it.",
        )
    }
}

private fun GamingStationEntity.toStation() = Station(
    id = id,
    code = code,
    name = name,
    type = type,
    ratePerHourMinor = ratePerHourMinor,
    isActive = isActive,
)

private fun GamingSessionCacheEntity.toGameSession() = GameSession(
    id = id,
    stationId = stationId,
    status = status,
    startAt = Instant.ofEpochMilli(startAtMillis).toString(),
    endAt = endAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    timerMinutes = timerMinutes,
    timerEndsAt = timerEndsAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    billableMinutes = billableMinutes,
    amountMinor = amountMinor,
    customerName = customerName,
    customerPhone = customerPhone,
    orderId = orderId,
)

private fun LocalGamingSessionEntity.toGameSession() = GameSession(
    id = serverId ?: localId,
    stationId = stationId,
    status = status,
    startAt = Instant.ofEpochMilli(startedAtMillis).toString(),
    endAt = endAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    timerMinutes = timerMinutes,
    timerEndsAt = timerEndsAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    billableMinutes = billableMinutes,
    amountMinor = amountMinor,
    customerName = null,
    customerPhone = customerPhone,
    orderId = null,
)

/** The open shift cached for offline use; null when none is known. */
private fun DCompanyApp.shiftCacheIdOrNull(): String? =
    runBlocking { shiftCache.cachedShiftId() }
