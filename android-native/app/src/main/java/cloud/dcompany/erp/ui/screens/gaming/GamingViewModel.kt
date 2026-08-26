package cloud.dcompany.erp.ui.screens.gaming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.alarm.GamingAlarmReconciler
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import cloud.dcompany.erp.core.db.observeResolvedOpenShift
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
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
    /** Same Room-derived id PosViewModel uses — null means no shift open. */
    val activeShiftId: String? = null,
) {
    fun activeFor(stationId: String): GameSession? =
        sessions.firstOrNull {
            it.stationId == stationId && (
                it.status in setOf("starting", "start_failed", "active", "paused", "stopping") ||
                    it.isUnbilledEnded()
                )
        }

    val readyForPos: List<GameSession>
        get() = sessions.filter(GameSession::canSendToPos)

    val needsCancellation: List<GameSession>
        get() = sessions.filter { it.canCancelUnbilled() && (it.amountMinor ?: 0L) <= 0L }
}

/** Recovery actions are deliberately derived from the leg-specific state. */
internal fun GameSession.canRetryStart(): Boolean =
    localState == GamingSessionState.START_REJECTED && status == "start_failed"

internal fun GameSession.canRequestStop(): Boolean =
    status in setOf("active", "paused") && localState != GamingSessionState.START_REJECTED

internal fun GameSession.canSendToPos(): Boolean =
    isUnbilledEnded() && (amountMinor ?: 0L) > 0L &&
        localState !in setOf(GamingSessionState.SEND_PENDING, GamingSessionState.SENT)

internal fun GameSession.canCancelUnbilled(): Boolean =
    isUnbilledEnded() &&
        localState !in setOf(GamingSessionState.SEND_PENDING, GamingSessionState.SENT)

private fun GameSession.isUnbilledEnded(): Boolean = status == "ended" && orderId == null

/**
 * Room-backed, offline-first — same shape as PosViewModel/ShiftViewModel.
 * Stations and sessions both read from Room, never the network directly.
 *
 * Sessions are the one resource so far that's simultaneously a shared,
 * cross-terminal read (any tablet can start a session at any station, so
 * this device needs to see what every other terminal is doing) and a local
 * write outbox (this device can safely queue the intent while offline). The two
 * live in separate tables — [GamingSessionCacheEntity] wholesale-replaced
 * from the server like the menu, [LocalGamingSessionEntity] for this
 * device's in-flight actions — and are merged at read time below, never
 * written into each other.
 */
class GamingViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val gamingApi = ApiClient.create<GamingApi>()
    private val resolvedShift = db.shiftDao().observeResolvedOpenShift(
        appCtx.terminalStore.terminalIdFlow,
    )

    private val busyStationId = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val nowMillis = MutableStateFlow(System.currentTimeMillis())
    @Volatile private var access = GamingAccess()

    val state: StateFlow<GamingUiState> = combine(
        db.gamingDao().observeStations(),
        db.gamingDao().observeSessionCache(),
        db.gamingDao().observeActiveLocalSessions(),
        combine(
            combine(busyStationId, error, nowMillis, ::Triple),
            resolvedShift,
            ::Pair,
        ),
        db.syncMetaDao().observe("gaming"),
    ) { stations, cache, local, ui, meta ->
        val (uiTriple, currentShift) = ui
        // Overlay an in-flight local stop/send on the older server cache row;
        // otherwise a successfully stopped session still renders "active"
        // and its ENDED_UNBILLED handoff disappears until another pull.
        val localByServerId = local.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
        val cacheSessions = cache.map { cached ->
            localByServerId[cached.id]?.toGameSession() ?: cached.toGameSession()
        }
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
            busyStationId = uiTriple.first,
            error = uiTriple.second,
            everSynced = meta != null,
            nowMillis = uiTriple.third,
            // Starting/operating a session is shared terminal work; opener
            // ownership gates only POS collection and shift close.
            activeShiftId = currentShift?.shiftId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamingUiState())

    init {
        val recoveryLease = appCtx.cacheIsolation.currentLease()
        viewModelScope.launch {
            // MIGRATION_16_17 handles normal upgrades. This idempotent call
            // also repairs an imported backup that was already stamped with a
            // newer Room version before the user can choose a recovery action.
            if (recoveryLease != null) {
                appCtx.cacheIsolation.commitIfCurrent(recoveryLease) {
                    db.gamingDao().recoverLegacyRejectedSessions()
                }
            }
            appCtx.sync.requestSync()
            appCtx.sync.refresh("gaming")
        }
        // A local 1s tick drives only the on-screen elapsed clock. It is
        // deliberately NOT what raises the overtime alert — see scheduleAlarm:
        // a coroutine stops with the screen, an AlarmManager alarm does not.
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                nowMillis.value = System.currentTimeMillis()
            }
        }
        // Reconciles on every meaningful session change, not just once at
        // start. This both schedules new deadlines and cancels an alarm when
        // another terminal (or a queued local stop) ends the session early.
        viewModelScope.launch {
            state.map { it.sessions.map { s -> Triple(s.id, s.status, s.timerEndsAt) } }
                .distinctUntilChanged()
                .collect { GamingAlarmReconciler.reconcile(appCtx) }
        }
    }

    fun load() {
        appCtx.sync.requestSync()
        viewModelScope.launch { appCtx.sync.refresh("gaming") }
    }

    fun updateAccess(next: GamingAccess) {
        access = next
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canManageSessions) {
        error.value = VIEW_ONLY_MESSAGE
    }

    fun start(station: Station, phone: String?, timerMinutes: Int?) {
        if (!requireWrite()) return
        if (busyStationId.value != null) return
        if (state.value.activeFor(station.id) != null) {
            error.value = "This station already has a session to finish, send to POS, or cancel first."
            return
        }
        val shift = state.value.activeShiftId
        if (shift == null) {
            error.value =
                "No open shift for this tablet's POS terminal. Open or refresh the shift before starting a session."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = station.id
        error.value = null
        viewModelScope.launch {
            try {
                var inserted = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        inserted = db.gamingDao().insertStartIfStationAvailable(
                            LocalGamingSessionEntity(
                                localId = UUID.randomUUID().toString(),
                                stationId = station.id,
                                shiftId = shift,
                                customerPhone = phone?.trim()?.takeIf { it.isNotEmpty() },
                                timerMinutes = timerMinutes,
                                // A pending start is not billable time. The server's
                                // canonical start_at replaces this value only after it
                                // accepts the request; until then staff are told not to
                                // begin play.
                                startedAtMillis = System.currentTimeMillis(),
                                state = GamingSessionState.START_PENDING,
                                status = "starting",
                            ),
                        )
                    }
                ) return@launch
                if (!inserted) {
                    error.value =
                        "This station already has a saved session action. Finish or clear it before starting again."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The session was not saved on this tablet. Check storage and try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun stop(session: GameSession) {
        if (!requireWrite()) return
        if (!session.canRequestStop()) return
        if (busyStationId.value != null) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        viewModelScope.launch {
            try {
                var changed = true
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val existing = dao.localSessionByEitherId(session.id)
                        if (existing != null) {
                            // Already an outbox row for this session (this device started
                            // it, possibly still unsynced) — just flag the stop.
                            changed = dao.requestSessionStop(existing.localId) != 0
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
                                    status = "stopping",
                                    timerMinutes = session.timerMinutes,
                                    timerEndsAtMillis = session.timerEndsAt?.let { value ->
                                        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                                    },
                                ),
                            )
                        }
                    }
                ) return@launch
                if (!changed) {
                    error.value = "This session already changed state. Refresh Gaming before stopping it again."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The stop request was not saved. The session is still running; try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun retryStart(session: GameSession) {
        if (!requireWrite()) return
        if (!session.canRetryStart() || busyStationId.value != null) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        viewModelScope.launch {
            try {
                var moved = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val row = db.gamingDao().localSessionByEitherId(session.id)
                        moved = row != null && db.gamingDao().retryRejectedStart(row.localId) != 0
                    }
                ) return@launch
                if (!moved) {
                    error.value = "This failed start is no longer available. Refresh Gaming."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The start retry could not be saved on this tablet. Try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun discardFailedStart(session: GameSession) {
        if (!requireWrite()) return
        if (!session.canRetryStart() || busyStationId.value != null) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        viewModelScope.launch {
            try {
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val row = db.gamingDao().localSessionByEitherId(session.id)
                        if (row != null) db.gamingDao().discardRejectedStart(row.localId)
                    }
                ) return@launch
            } catch (_: Exception) {
                error.value = "The failed start could not be cleared. Try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    /** Explicit second leg: stopping computes the bill; this creates the POS order. */
    fun sendToPos(session: GameSession) {
        if (!requireWrite()) return
        if (!session.canSendToPos()) return
        if (busyStationId.value != null) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        viewModelScope.launch {
            try {
                var changed = true
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val existing = dao.localSessionByEitherId(session.id)
                        if (existing != null) {
                            changed = dao.requestSessionSend(existing.localId) != 0
                        } else {
                            dao.insertLocalSession(
                                LocalGamingSessionEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = session.id,
                                    stationId = session.stationId,
                                    customerPhone = session.customerPhone,
                                    startedAtMillis = runCatching { Instant.parse(session.startAt).toEpochMilli() }
                                        .getOrDefault(System.currentTimeMillis()),
                                    state = GamingSessionState.SEND_PENDING,
                                    status = session.status,
                                    endAtMillis = session.endAt?.let { value ->
                                        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                                    },
                                    billableMinutes = session.billableMinutes,
                                    amountMinor = session.amountMinor,
                                ),
                            )
                        }
                    }
                ) return@launch
                if (!changed) {
                    error.value = "This session is not ready to send. Refresh Gaming and try again."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The POS handoff was not saved on this tablet. Try Send to POS again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    /**
     * Gives staff an audited escape for mistaken stopped sessions, including the
     * mandatory path for a zero-value session that cannot become a POS order.
     * Cancellation is deliberately online-only: the server records who cancelled
     * it and the required reason, and response-loss replay is naturally safe
     * because cancelled is terminal.
     */
    fun cancelUnbilled(session: GameSession, reason: String) {
        if (!requireWrite()) return
        if (!session.canCancelUnbilled() || busyStationId.value != null) return
        val normalizedReason = reason.trim()
        if (normalizedReason.isEmpty()) {
            error.value = "Enter a reason before cancelling this session."
            return
        }
        if (normalizedReason.length > 500) {
            error.value = "Cancellation reason must be 500 characters or fewer."
            return
        }
        if (!appCtx.connectivity.online.value) {
            error.value =
                "Cancellation needs an internet connection so the reason is recorded. Reconnect, then try again."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return

        busyStationId.value = session.stationId
        error.value = null
        viewModelScope.launch {
            try {
                val dao = db.gamingDao()
                var serverId: String? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val local = dao.localSessionByEitherId(session.id)
                        serverId = local?.serverId ?: session.id.takeIf { local == null }
                    }
                ) return@launch
                if (serverId == null) {
                    error.value =
                        "This session has not been confirmed by the server yet. Refresh Gaming before cancelling it."
                    return@launch
                }
                val cancelled = gamingApi.cancel(
                    id = requireNotNull(serverId),
                    body = SessionCancelBody(normalizedReason),
                )
                appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    dao.upsertAuthoritativeSession(cancelled.toCacheEntity())
                    GamingAlarmReconciler.reconcile(appCtx)
                }
            } catch (e: ApiException) {
                error.value = if (e.isAmbiguous) {
                    "The server response was lost, so cancellation is not confirmed. This session remains blocked " +
                        "from POS and new play. Reconnect and try Cancel again; do not create a replacement session."
                } else {
                    "Cancellation was refused: ${e.message ?: "check the shift, branch, terminal, and session state."}"
                }
            } catch (_: Exception) {
                error.value =
                    "Cancellation could not be recorded. The session remains blocked; refresh and try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun dismissError() { error.value = null }
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

private fun GameSession.toCacheEntity() = GamingSessionCacheEntity(
    id = id,
    stationId = stationId,
    status = status,
    startAtMillis = Instant.parse(startAt).toEpochMilli(),
    endAtMillis = endAt?.let { Instant.parse(it).toEpochMilli() },
    timerMinutes = timerMinutes,
    timerEndsAtMillis = timerEndsAt?.let { Instant.parse(it).toEpochMilli() },
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
    orderId = orderId,
    localState = state,
    lastError = lastError,
)
