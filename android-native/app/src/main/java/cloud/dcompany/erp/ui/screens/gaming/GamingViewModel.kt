package cloud.dcompany.erp.ui.screens.gaming

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.alarm.AlarmScheduler
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

data class GamingUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stations: List<Station> = emptyList(),
    val sessions: List<GameSession> = emptyList(),
    val busyStationId: String? = null,
    /** Ticks every second so elapsed timers re-render. */
    val nowMillis: Long = System.currentTimeMillis(),
) {
    fun activeFor(stationId: String): GameSession? =
        sessions.firstOrNull { it.stationId == stationId && it.status == "active" }
}

class GamingViewModel(app: Application) : AndroidViewModel(app) {

    private val api = ApiClient.create<GamingApi>()
    private val appCtx = DCompanyApp.instance

    private val _state = MutableStateFlow(GamingUiState())
    val state: StateFlow<GamingUiState> = _state.asStateFlow()

    init {
        load()
        // A local 1s tick drives only the on-screen elapsed clock. It is
        // deliberately NOT what raises the overtime alert — see scheduleAlarm:
        // a coroutine stops with the screen, an AlarmManager alarm does not.
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _state.value = _state.value.copy(nowMillis = System.currentTimeMillis())
            }
        }
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val stations = api.stations().filter { it.isActive }
                val sessions = api.sessions()
                _state.value = _state.value.copy(
                    loading = false, stations = stations, sessions = sessions,
                )
                // Re-arm on every load so an app restart does not lose a
                // pending overtime alert for a session already running.
                sessions.filter { it.status == "active" }.forEach(::scheduleAlarm)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load gaming stations.",
                )
            }
        }
    }

    fun start(station: Station, phone: String?, timerMinutes: Int?) {
        val shift = _state.value.let { appCtx }.shiftCacheIdOrNull()
        if (shift == null) {
            _state.value = _state.value.copy(
                error = "No open shift. Open a shift before starting a session.",
            )
            return
        }
        _state.value = _state.value.copy(busyStationId = station.id, error = null)
        viewModelScope.launch {
            try {
                val session = api.start(
                    SessionStartBody(
                        stationId = station.id,
                        shiftId = shift,
                        customerPhone = phone?.trim()?.takeIf { it.isNotEmpty() },
                        timerMinutes = timerMinutes,
                    ),
                    UUID.randomUUID().toString(),
                )
                scheduleAlarm(session)
                _state.value = _state.value.copy(busyStationId = null)
                load()
            } catch (e: ApiException) {
                _state.value = _state.value.copy(busyStationId = null, error = e.message)
            }
        }
    }

    fun stop(session: GameSession) {
        _state.value = _state.value.copy(busyStationId = session.stationId, error = null)
        viewModelScope.launch {
            try {
                api.stop(session.id, UUID.randomUUID().toString())
                AlarmScheduler.cancel(getApplication(), alarmTag(session))
                _state.value = _state.value.copy(busyStationId = null)
                load()
            } catch (e: ApiException) {
                _state.value = _state.value.copy(busyStationId = null, error = e.message)
            }
        }
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    private fun alarmTag(s: GameSession) = "gaming-session-${s.id}"

    /**
     * Hands the deadline to the OS the moment it is known.
     *
     * This is the concrete thing the WebView build could not do: its timer only
     * ran while the WebView was alive and awake, so a session that ran over
     * while the tablet slept alerted late or not at all — and unbilled minutes
     * are lost money.
     */
    private fun scheduleAlarm(session: GameSession) {
        val endsAt = session.timerEndsAt ?: return
        val at = runCatching { Instant.parse(endsAt).toEpochMilli() }
            .getOrElse { return }
        if (at <= System.currentTimeMillis()) return
        val station = _state.value.stations.firstOrNull { it.id == session.stationId }
        AlarmScheduler.schedule(
            context = getApplication(),
            tag = alarmTag(session),
            atEpochMillis = at,
            title = "Session over — ${station?.name ?: "gaming station"}",
            body = "The booked time has finished. Stop the session or extend it.",
        )
    }
}

/** The open shift cached for offline use; null when none is known. */
private fun DCompanyApp.shiftCacheIdOrNull(): String? =
    kotlinx.coroutines.runBlocking { shiftCache.cachedShiftId() }
