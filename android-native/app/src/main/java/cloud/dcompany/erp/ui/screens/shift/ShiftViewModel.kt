package cloud.dcompany.erp.ui.screens.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Indian cash denominations, largest first — the order staff count in. */
val DENOMINATIONS = listOf(500L, 200L, 100L, 50L, 20L, 10L, 5L, 2L, 1L)

data class ShiftUiState(
    val open: LocalShiftEntity? = null,
    val history: List<LocalShiftEntity> = emptyList(),
    val online: Boolean = false,
    val syncing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val closedResult: LocalShiftEntity? = null,
    /**
     * Live, server-computed "cash that should be in the drawer right now" —
     * unlike everything else on this screen, this genuinely cannot be known
     * offline (it depends on every sale attributed to this shift, most of
     * which this one tablet may not have seen). Null means "not available",
     * not zero — closing still works without it; the real variance shows up
     * once the close syncs.
     */
    val expectedMinor: Long? = null,
)

/**
 * Room-backed, offline-first — the UI reads the local shift row, never the
 * network, for the part that has to keep working through a dropped link.
 * Opening or closing writes the row and returns immediately; SyncEngine sends
 * it whenever a connection exists, and the row picks up its real
 * `serverShiftId` and `varianceMinor` once that happens. This is the same
 * guarantee the till already has for orders.
 */
class ShiftViewModel : ViewModel() {

    private val app = DCompanyApp.instance
    private val db = app.db
    private val shiftApi = ApiClient.create<ShiftApi>()

    private var dismissedClosedId: String? = null
    private val expectedMinor = MutableStateFlow<Long?>(null)

    val state: StateFlow<ShiftUiState> = combine(
        db.shiftDao().observeCurrent(),
        db.shiftDao().observeHistory(),
        app.connectivity.online,
        app.sync.syncing,
        expectedMinor,
    ) { current, history, online, syncing, expected ->
        val stillOpen = current?.takeIf { it.state != ShiftState.CLOSE_PENDING }
        val justClosed = history.firstOrNull()?.takeIf { it.localId != dismissedClosedId }
        ShiftUiState(
            open = current,
            history = history,
            online = online,
            syncing = syncing,
            // A close-in-flight still occupies the "current" slot (it's not
            // closed yet), so the close card stays up showing its own state
            // rather than snapping back to "no shift open" mid-sync.
            closedResult = if (stillOpen == null && current == null) justClosed else null,
            expectedMinor = expected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftUiState())

    init {
        app.sync.requestSync()
        // Re-fetch the moment this device's shift actually has a server id to
        // ask about — not just once at screen-open. A shift opened offline
        // starts with none, and without this, "Expected in drawer" would sit
        // on "not available offline" even long after the link (and the open
        // leg's sync) came back, until someone happened to tap Refresh.
        viewModelScope.launch {
            db.shiftDao().observeCurrent()
                .map { it?.serverShiftId }
                .distinctUntilChanged()
                .collect { serverId -> if (serverId != null) refreshExpected(serverId) }
        }
    }

    fun load() {
        app.sync.requestSync()
        state.value.open?.serverShiftId?.let { refreshExpected(it) }
    }

    /** Best-effort only — a failure here (offline) just leaves it unavailable. */
    private fun refreshExpected(serverId: String) {
        viewModelScope.launch {
            try {
                expectedMinor.value = shiftApi.shifts(onlyOpen = true)
                    .firstOrNull { it.id == serverId }?.expectedMinor
            } catch (e: ApiException) {
                // Offline or unreachable — leave whatever was last known.
            }
        }
    }

    fun openShift(floatMinor: Long) {
        if (state.value.busy || state.value.open != null) return
        viewModelScope.launch {
            val localId = UUID.randomUUID().toString()
            db.shiftDao().insert(
                LocalShiftEntity(
                    localId = localId,
                    openingFloatMinor = floatMinor,
                    openedAtMillis = System.currentTimeMillis(),
                    state = ShiftState.OPEN_PENDING,
                ),
            )
            // Billing must not wait for the network round trip — this is the
            // whole reason a shift lives in Room now.
            app.shiftCache.remember(localId)
            expectedMinor.value = null
            app.sync.requestSync()
        }
    }

    fun closeShift(countedMinor: Long) {
        val shift = state.value.open ?: return
        if (state.value.busy) return
        viewModelScope.launch {
            db.shiftDao().requestClose(
                shift.localId,
                countedMinor = countedMinor,
                closedAtMillis = System.currentTimeMillis(),
            )
            // Stop billing against this shift immediately, offline or not —
            // matches the pre-offline behaviour exactly, just no longer
            // gated on a server round trip.
            app.shiftCache.remember(null)
            app.sync.requestSync()
        }
    }

    fun dismissResult() {
        dismissedClosedId = state.value.history.firstOrNull()?.localId
    }
}
