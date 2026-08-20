package cloud.dcompany.erp.ui.screens.accesscontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** role_code -> human label, matching the web app's own module names. */
val MODULE_LABELS = mapOf(
    "pos" to "POS",
    "tables" to "Tables",
    "menu" to "Menu",
    "inventory" to "Inventory",
    "gaming" to "Gaming",
    "finance" to "Finance",
    "ocr" to "OCR",
    "staff" to "Staff",
    "insights_reports" to "Insights/Reports",
)

data class AccessControlUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val roles: Map<String, String> = emptyMap(),
    val modules: List<String> = emptyList(),
    val cells: List<AccessCell> = emptyList(),
    /**
     * "roleCode:module" keys currently in flight. A set, not a single
     * nullable key: each cell's PATCH is an independent write against its
     * own (role, module) row, so unlike a single shared resource there's
     * nothing to serialize across different cells — only the SAME cell
     * needs protecting from a double-tap landing on top of its own
     * in-flight request. Matches the web app, which has no cross-cell lock
     * at all (see AccessControlTab.tsx's single busyKey being purely
     * cosmetic there too).
     */
    val busyKeys: Set<String> = emptySet(),
    val actionError: String? = null,
) {
    fun cellFor(roleCode: String, module: String): AccessCell? =
        cells.firstOrNull { it.roleCode == roleCode && it.module == module }
}

/**
 * Owner-only, online-only (per the project's plan: rare, security-sensitive,
 * must apply against live current rules, never a stale offline copy). Every
 * toggle is its own immediate PATCH with no save step, mirroring the web
 * app's AccessControlTab exactly — including the reset-to-default-via-
 * null-override affordance.
 */
class AccessControlViewModel : ViewModel() {

    private val api = ApiClient.create<AccessControlApi>()

    private val _state = MutableStateFlow(AccessControlUiState())
    val state: StateFlow<AccessControlUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val dto = api.get()
                _state.value = _state.value.copy(
                    loading = false,
                    roles = dto.roles,
                    modules = dto.modules,
                    cells = dto.cells,
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load access control.",
                )
            }
        }
    }

    fun toggle(cell: AccessCell) = setCell(cell.roleCode, cell.module, JsonPrimitive(!cell.allowed))

    fun resetToDefault(cell: AccessCell) = setCell(cell.roleCode, cell.module, JsonNull)

    fun dismissActionError() { _state.value = _state.value.copy(actionError = null) }

    private fun setCell(roleCode: String, module: String, allowed: JsonElement) {
        val key = "$roleCode:$module"
        if (key in _state.value.busyKeys) return
        _state.value = _state.value.copy(busyKeys = _state.value.busyKeys + key, actionError = null)
        viewModelScope.launch {
            try {
                val updated = api.update(AccessControlUpdateBody(roleCode, module, allowed))
                _state.value = _state.value.copy(
                    busyKeys = _state.value.busyKeys - key,
                    cells = _state.value.cells.map {
                        if (it.roleCode == roleCode && it.module == module) updated else it
                    },
                )
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    busyKeys = _state.value.busyKeys - key,
                    actionError = e.message ?: "Could not update this permission.",
                )
            }
        }
    }
}
