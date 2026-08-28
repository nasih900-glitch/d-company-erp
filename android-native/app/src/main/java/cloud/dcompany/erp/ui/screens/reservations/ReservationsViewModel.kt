package cloud.dcompany.erp.ui.screens.reservations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.ReservationsAccess
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReservationsDialog {
    data object NewTableReservation : ReservationsDialog
    data object NewGamingBooking : ReservationsDialog
    data class ConfirmTableStatus(
        val reservation: TableReservation,
        val targetStatus: String,
    ) : ReservationsDialog

    data class ConfirmGamingStatus(
        val booking: GamingBooking,
        val targetStatus: String,
    ) : ReservationsDialog
}

data class ReservationsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val online: Boolean = false,
    val selectedTab: ReservationTab = ReservationTab.TABLES,
    val tableReservations: List<TableReservation> = emptyList(),
    val gamingBookings: List<GamingBooking> = emptyList(),
    val tables: List<ReservationTable> = emptyList(),
    val stations: List<ReservationStation> = emptyList(),
    val tableLoadError: String? = null,
    val gamingLoadError: String? = null,
    val dialog: ReservationsDialog? = null,
    val busy: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
) {
    val heldTableCount: Int get() = tableReservations.count { reservationStatusIsPending(it.status) }
    val heldGamingCount: Int get() = gamingBookings.count { reservationStatusIsPending(it.status) }
}

private data class TableLoad(
    val reservations: List<TableReservation>,
    val tables: List<ReservationTable>,
)

private data class GamingLoad(
    val bookings: List<GamingBooking>,
    val stations: List<ReservationStation>,
)

private sealed interface SectionResult<out T> {
    data class Loaded<T>(val value: T) : SectionResult<T>
    data class Failed(val message: String) : SectionResult<Nothing>
    data object Skipped : SectionResult<Nothing>
}

private data class ReservationLoad(
    val tables: SectionResult<TableLoad>,
    val gaming: SectionResult<GamingLoad>,
)

/**
 * Live reservation board. Reads are deliberately session-memory only and
 * writes are online-only: there is no durable server idempotency contract for
 * replaying either reservation resource safely.
 */
class ReservationsViewModel : ViewModel() {
    private val app = DCompanyApp.instance
    private val api = ApiClient.create<ReservationsApi>()
    private val _state = MutableStateFlow(ReservationsUiState())
    val state: StateFlow<ReservationsUiState> = _state.asStateFlow()

    @Volatile private var access = ReservationsAccess()

    init {
        viewModelScope.launch {
            app.connectivity.online.collect { online ->
                _state.update { current ->
                    current.copy(
                        online = online,
                        loading = if (!online && current.loading) false else current.loading,
                    )
                }
                if (online && access.canReadAny) refresh()
            }
        }
    }

    fun updateAccess(next: ReservationsAccess) {
        if (access == next) return
        access = next
        val firstAllowed = when {
            next.canReadTableReservations -> ReservationTab.TABLES
            next.canReadGamingBookings -> ReservationTab.GAMING
            else -> ReservationTab.TABLES
        }
        _state.update { current ->
            val selectedAllowed = when (current.selectedTab) {
                ReservationTab.TABLES -> next.canReadTableReservations
                ReservationTab.GAMING -> next.canReadGamingBookings
            }
            current.copy(
                selectedTab = if (selectedAllowed) current.selectedTab else firstAllowed,
                tableReservations = if (next.canReadTableReservations) current.tableReservations else emptyList(),
                tables = if (next.canReadTableReservations) current.tables else emptyList(),
                gamingBookings = if (next.canReadGamingBookings) current.gamingBookings else emptyList(),
                stations = if (next.canReadGamingBookings) current.stations else emptyList(),
                tableLoadError = null,
                gamingLoadError = null,
                dialog = null,
                formError = null,
            )
        }
        if (_state.value.online && next.canReadAny) refresh()
    }

    fun selectTab(tab: ReservationTab) {
        val allowed = when (tab) {
            ReservationTab.TABLES -> access.canReadTableReservations
            ReservationTab.GAMING -> access.canReadGamingBookings
        }
        if (allowed) _state.update { it.copy(selectedTab = tab) }
    }

    fun refresh() {
        if (!access.canReadAny) {
            _state.update { it.copy(loading = false, refreshing = false) }
            return
        }
        if (!_state.value.online) {
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    notice = reservationOfflineMessage(),
                )
            }
            return
        }
        if (_state.value.refreshing) return

        val initialLoad = _state.value.tableReservations.isEmpty() &&
            _state.value.gamingBookings.isEmpty()
        _state.update {
            it.copy(
                loading = initialLoad,
                refreshing = true,
                tableLoadError = null,
                gamingLoadError = null,
            )
        }
        val requestedAccess = access
        viewModelScope.launch {
            try {
                val committed = app.cacheIsolation.fetchAndCommitScoped(
                    fetch = { loadReservationSections(requestedAccess) },
                    store = { loaded -> commitLoadedSections(loaded) },
                )
                if (!committed) {
                    _state.update {
                        it.copy(
                            notice = "The active shop or account changed while loading. Open Reservations again to refresh the correct shop.",
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = reservationLoadError(error)
                _state.update {
                    it.copy(
                        tableLoadError = if (requestedAccess.canReadTableReservations) message else null,
                        gamingLoadError = if (requestedAccess.canReadGamingBookings) message else null,
                    )
                }
            } finally {
                _state.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    private suspend fun loadReservationSections(requested: ReservationsAccess): ReservationLoad =
        coroutineScope {
            val tableLoad = async {
                if (!requested.canReadTableReservations) {
                    SectionResult.Skipped
                } else {
                    loadSection {
                        TableLoad(
                            reservations = api.tableReservations(),
                            tables = api.tables().sortedBy(ReservationTable::code),
                        )
                    }
                }
            }
            val gamingLoad = async {
                if (!requested.canReadGamingBookings) {
                    SectionResult.Skipped
                } else {
                    loadSection {
                        GamingLoad(
                            bookings = api.gamingBookings(),
                            stations = api.stations()
                                .filter(ReservationStation::isActive)
                                .sortedBy(ReservationStation::code),
                        )
                    }
                }
            }
            ReservationLoad(tableLoad.await(), gamingLoad.await())
        }

    private suspend fun <T> loadSection(block: suspend () -> T): SectionResult<T> = try {
        SectionResult.Loaded(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        SectionResult.Failed(reservationLoadError(error))
    }

    private fun commitLoadedSections(loaded: ReservationLoad) {
        _state.update { current ->
            var next = current
            when (val tableResult = loaded.tables) {
                is SectionResult.Loaded -> next = next.copy(
                    tableReservations = tableResult.value.reservations,
                    tables = tableResult.value.tables,
                    tableLoadError = null,
                )
                is SectionResult.Failed -> next = next.copy(tableLoadError = tableResult.message)
                SectionResult.Skipped -> Unit
            }
            when (val gamingResult = loaded.gaming) {
                is SectionResult.Loaded -> next = next.copy(
                    gamingBookings = gamingResult.value.bookings,
                    stations = gamingResult.value.stations,
                    gamingLoadError = null,
                )
                is SectionResult.Failed -> next = next.copy(gamingLoadError = gamingResult.message)
                SectionResult.Skipped -> Unit
            }
            next
        }
    }

    fun openCreate() {
        val target = _state.value.selectedTab
        val allowed = when (target) {
            ReservationTab.TABLES -> access.canManageTableReservations
            ReservationTab.GAMING -> access.canManageGamingBookings
        }
        if (!allowed) {
            _state.update { it.copy(notice = "View only — ask a manager to create this booking.") }
            return
        }
        if (!requireOnlineMutation()) return
        _state.update {
            it.copy(
                dialog = when (target) {
                    ReservationTab.TABLES -> ReservationsDialog.NewTableReservation
                    ReservationTab.GAMING -> ReservationsDialog.NewGamingBooking
                },
                formError = null,
            )
        }
    }

    fun confirmTableStatus(reservation: TableReservation, targetStatus: String) {
        if (!access.canManageTableReservations) {
            _state.update { it.copy(notice = "View only — ask a manager to update this reservation.") }
            return
        }
        if (!reservationStatusIsPending(reservation.status) || targetStatus !in TABLE_RESERVATION_TARGETS) return
        if (!requireOnlineMutation()) return
        _state.update {
            it.copy(
                dialog = ReservationsDialog.ConfirmTableStatus(reservation, targetStatus),
                formError = null,
            )
        }
    }

    fun confirmGamingStatus(booking: GamingBooking, targetStatus: String) {
        if (!access.canManageGamingBookings) {
            _state.update { it.copy(notice = "View only — ask a manager to update this booking.") }
            return
        }
        if (!reservationStatusIsPending(booking.status) || targetStatus !in GAMING_BOOKING_TARGETS) return
        if (!requireOnlineMutation()) return
        _state.update {
            it.copy(
                dialog = ReservationsDialog.ConfirmGamingStatus(booking, targetStatus),
                formError = null,
            )
        }
    }

    fun createTableReservation(body: TableReservationCreate) {
        if (!access.canManageTableReservations || !requireOnlineMutation() || _state.value.busy) return
        performMutation(
            action = "save this table reservation",
            request = { api.createTableReservation(body) },
            onSuccess = {
                _state.update {
                    it.copy(dialog = null, formError = null, notice = "Table reservation saved.")
                }
                refresh()
            },
        )
    }

    fun createGamingBooking(body: GamingBookingCreate) {
        if (!access.canManageGamingBookings || !requireOnlineMutation() || _state.value.busy) return
        performMutation(
            action = "save this gaming booking",
            request = { api.createGamingBooking(body) },
            onSuccess = {
                _state.update {
                    it.copy(dialog = null, formError = null, notice = "Gaming booking saved.")
                }
                refresh()
            },
        )
    }

    fun updateTableStatus(reservationId: String, targetStatus: String) {
        if (!access.canManageTableReservations || targetStatus !in TABLE_RESERVATION_TARGETS ||
            !requireOnlineMutation() || _state.value.busy
        ) return
        performMutation(
            action = "update this table reservation",
            request = {
                api.updateTableReservationStatus(reservationId, ReservationStatusUpdate(targetStatus))
            },
            onSuccess = { updated ->
                _state.update { current ->
                    current.copy(
                        tableReservations = current.tableReservations.map {
                            if (it.id == updated.id) updated else it
                        },
                        dialog = null,
                        formError = null,
                        notice = "Reservation marked ${reservationStatusLabel(updated.status).lowercase()}.",
                    )
                }
            },
        )
    }

    fun updateGamingStatus(bookingId: String, targetStatus: String) {
        if (!access.canManageGamingBookings || targetStatus !in GAMING_BOOKING_TARGETS ||
            !requireOnlineMutation() || _state.value.busy
        ) return
        performMutation(
            action = "update this gaming booking",
            request = {
                api.updateGamingBookingStatus(bookingId, ReservationStatusUpdate(targetStatus))
            },
            onSuccess = { updated ->
                _state.update { current ->
                    current.copy(
                        gamingBookings = current.gamingBookings.map {
                            if (it.id == updated.id) updated else it
                        },
                        dialog = null,
                        formError = null,
                        notice = "Booking marked ${reservationStatusLabel(updated.status).lowercase()}.",
                    )
                }
            },
        )
    }

    private fun <T> performMutation(
        action: String,
        request: suspend () -> T,
        onSuccess: (T) -> Unit,
    ) {
        _state.update { it.copy(busy = true, formError = null) }
        viewModelScope.launch {
            try {
                val result = request()
                onSuccess(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(formError = reservationMutationError(error, action)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun requireOnlineMutation(): Boolean {
        if (_state.value.online) return true
        _state.update { it.copy(notice = reservationOfflineMessage()) }
        return false
    }

    fun closeDialog() {
        if (!_state.value.busy) _state.update { it.copy(dialog = null, formError = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }
}

internal fun reservationOfflineMessage(): String =
    "Reservations are online-only and were not saved. Reconnect, refresh the live board, then try again."

internal fun reservationLoadError(error: Throwable): String = when (error) {
    is ApiException -> error.message?.takeIf(String::isNotBlank)
        ?: "Could not load reservations. Check the connection and try again."
    else -> "Could not load reservations. Check the connection and try again."
}

internal fun reservationMutationError(error: Throwable, action: String): String = when {
    error is ApiException && error.status == null ->
        "Could not confirm whether the server completed the request. Refresh the live board before trying to $action again."
    error is ApiException && !error.message.isNullOrBlank() -> error.message!!
    else -> "Could not $action. Nothing was queued offline; refresh and try again."
}
