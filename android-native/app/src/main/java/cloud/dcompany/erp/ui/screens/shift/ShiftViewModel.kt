package cloud.dcompany.erp.ui.screens.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Indian cash denominations, largest first — the order staff count in. */
val DENOMINATIONS = listOf(500L, 200L, 100L, 50L, 20L, 10L, 5L, 2L, 1L)

data class ShiftUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val open: ShiftDetail? = null,
    val history: List<ShiftDetail> = emptyList(),
    val busy: Boolean = false,
    val closedResult: ShiftCloseResult? = null,
)

class ShiftViewModel : ViewModel() {

    private val api = ApiClient.create<ShiftApi>()
    private val app = DCompanyApp.instance

    private val _state = MutableStateFlow(ShiftUiState())
    val state: StateFlow<ShiftUiState> = _state.asStateFlow()

    /**
     * Generated once per close attempt and reused if the request has to be
     * retried. Regenerating it inside a retry is what turns one drawer count
     * into two.
     */
    private var closeKey: String = UUID.randomUUID().toString()
    private var openKey: String = UUID.randomUUID().toString()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val all = api.shifts(onlyOpen = false)
                val open = all.firstOrNull { it.status == "open" }
                _state.value = _state.value.copy(
                    loading = false,
                    open = open,
                    history = all.filter { it.status != "open" }.take(30),
                )
                // Keep the POS in step: it bills against this id, and caching
                // it is what lets the till keep selling if the link drops.
                app.shiftCache.remember(open?.id)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load shifts.",
                )
            }
        }
    }

    fun openShift(floatMinor: Long) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val shift = api.open(ShiftOpenBody(floatMinor), openKey)
                openKey = UUID.randomUUID().toString()
                app.shiftCache.remember(shift.id)
                _state.value = _state.value.copy(busy = false, open = shift)
                load()
            } catch (e: ApiException) {
                // The key is deliberately NOT rotated on failure: if the shift
                // did open and we simply never heard back, replaying the same
                // key returns that shift instead of opening a second one.
                _state.value = _state.value.copy(busy = false, error = e.message)
            }
        }
    }

    fun closeShift(countedMinor: Long) {
        val shift = _state.value.open ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = api.close(shift.id, ShiftCloseBody(countedMinor), closeKey)
                closeKey = UUID.randomUUID().toString()
                app.shiftCache.remember(null)
                _state.value = _state.value.copy(busy = false, open = null, closedResult = result)
                load()
            } catch (e: ApiException) {
                _state.value = _state.value.copy(busy = false, error = e.message)
            }
        }
    }

    fun dismissResult() { _state.value = _state.value.copy(closedResult = null) }
}
