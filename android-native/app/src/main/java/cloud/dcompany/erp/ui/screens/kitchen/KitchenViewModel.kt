package cloud.dcompany.erp.ui.screens.kitchen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often the wall screen re-reads the queue. */
const val KITCHEN_POLL_MS = 5_000L

/**
 * After a successful advance every button is briefly dead. A ticket that
 * changes state moves lane, the list reflows under the cook's finger, and a
 * gloved double-tap would otherwise land on whatever slid into that spot —
 * advancing a ticket nobody has started cooking. The server cannot save us
 * here: that second tap is a perfectly legal transition on a different order.
 */
private const val ADVANCE_LOCK_MS = 1_200L

data class KitchenUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val orders: List<KitchenOrder> = emptyList(),
    val includeServed: Boolean = false,
    /** The ticket whose PATCH is in flight. */
    val busyOrderId: String? = null,
    val advanceLockedUntilMillis: Long = 0L,
    val lastSyncedAtMillis: Long? = null,
    /** Ticks every second so "synced 4s ago" and the tap lock stay honest. */
    val nowMillis: Long = System.currentTimeMillis(),
) {
    fun lane(state: KitchenState): List<KitchenOrder> =
        orders.filter { KitchenState.from(it.kitchenState) == state }

    /** Anything the server sent with a state this build does not know about. */
    val unknownState: List<KitchenOrder>
        get() = orders.filter { KitchenState.from(it.kitchenState) == null }

    val newCount: Int get() = lane(KitchenState.RECEIVED).size
    val preparingCount: Int get() = lane(KitchenState.PREPARING).size
    val readyCount: Int get() = lane(KitchenState.READY).size

    val tapsLocked: Boolean get() = busyOrderId != null || nowMillis < advanceLockedUntilMillis

    /** Seconds since the last successful read, or null before the first one. */
    val secondsSinceSync: Long?
        get() = lastSyncedAtMillis?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) }

    /**
     * A KDS that quietly stops updating is dangerous — the cook reads a frozen
     * screen as "no new orders". Two missed polls is enough to say so out loud.
     */
    val stale: Boolean get() = (secondsSinceSync ?: 0) > (KITCHEN_POLL_MS / 1000) * 3
}

class KitchenViewModel : ViewModel() {

    private val api = ApiClient.create<KitchenApi>()

    private val _state = MutableStateFlow(KitchenUiState())
    val state: StateFlow<KitchenUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _state.value = _state.value.copy(nowMillis = System.currentTimeMillis())
            }
        }
    }

    /**
     * Re-reads the queue. Polling calls this every few seconds, so a failure
     * must never blank the board: the last good tickets stay on screen and the
     * error is raised as a banner instead. A cook can still cook from a stale
     * ticket list; they can do nothing with an empty one.
     */
    fun refresh() {
        if (loadJob?.isActive == true) return
        val hadData = _state.value.orders.isNotEmpty()
        if (!hadData) _state.value = _state.value.copy(loading = true)

        loadJob = viewModelScope.launch {
            try {
                val orders = api.queue(includeServed = _state.value.includeServed)
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    orders = orders,
                    lastSyncedAtMillis = System.currentTimeMillis(),
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not reach the server.",
                )
            }
        }
    }

    /** Manual retry from the error state: clears the message first so a repeat failure still reads as new. */
    fun retry() {
        _state.value = _state.value.copy(error = null)
        refresh()
    }

    fun setIncludeServed(include: Boolean) {
        if (include == _state.value.includeServed) return
        // Drop the current tickets: they answer a different question than the
        // one now being asked, and showing them under the new toggle would be
        // a lie until the next poll lands.
        loadJob?.cancel()
        _state.value = _state.value.copy(
            includeServed = include,
            orders = emptyList(),
            error = null,
            loading = true,
        )
        refresh()
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    /**
     * Moves one ticket one rung up the ladder.
     *
     * There is no retry loop and no idempotency key on purpose: the server
     * treats "set the state it is already in" as a no-op, so if the reply is
     * lost the cook simply taps again and the outcome is identical. What it
     * refuses is a skip or a step backwards — which is exactly the protection
     * needed if this screen's view of the ticket is stale.
     */
    fun advance(order: KitchenOrder) {
        val current = KitchenState.from(order.kitchenState) ?: return
        val next = current.next ?: return
        val now = System.currentTimeMillis()
        if (_state.value.busyOrderId != null || now < _state.value.advanceLockedUntilMillis) return

        _state.value = _state.value.copy(busyOrderId = order.id, error = null)
        viewModelScope.launch {
            try {
                val updated = api.setState(order.id, KitchenStateUpdate(next.wire))
                applyAdvance(order.id, updated.kitchenState)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    busyOrderId = null,
                    error = when {
                        // "may or may not have committed" — so say that, and let
                        // the poll below settle it rather than guessing.
                        e.isAmbiguous ->
                            "${e.message} — checking whether it went through."
                        else -> e.message
                    },
                )
                // Either way the truth is on the server, not here.
                refresh()
            }
        }
    }

    /**
     * Applies the state the server just confirmed, without waiting for the next
     * poll — a gloved tap that appears to do nothing for five seconds gets
     * tapped again.
     *
     * Only the state is taken from the response. Its `lines` array also carries
     * lines the queue deliberately hides (already served ones), so splicing the
     * whole DTO in would make finished items reappear on the board.
     */
    private fun applyAdvance(orderId: String, confirmedState: String) {
        val servedNow = KitchenState.from(confirmedState) == KitchenState.SERVED
        val orders = _state.value.orders.mapNotNull { o ->
            when {
                o.id != orderId -> o
                // Served tickets leave the active board entirely, matching what
                // the next queue read will return.
                servedNow && !_state.value.includeServed -> null
                else -> o.copy(kitchenState = confirmedState)
            }
        }
        _state.value = _state.value.copy(
            orders = orders,
            busyOrderId = null,
            advanceLockedUntilMillis = System.currentTimeMillis() + ADVANCE_LOCK_MS,
            nowMillis = System.currentTimeMillis(),
        )
    }
}
