package cloud.dcompany.erp.ui.screens.accesscontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
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
    val notice: String? = null,
    /** An ambiguous PATCH may have committed; no further edits are safe until GET reconciles it. */
    val authorityUnknown: Boolean = false,
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
class AccessControlViewModel(
    private val api: AccessControlApi = ApiClient.create<AccessControlApi>(),
) : ViewModel() {

    private val _state = MutableStateFlow(AccessControlUiState())
    val state: StateFlow<AccessControlUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val dto = api.get()
                _state.value = _state.value.copy(
                    roles = dto.roles,
                    modules = dto.modules,
                    cells = dto.cells,
                    authorityUnknown = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: ApiException) {
                _state.value = if (e.status == 403) {
                    _state.value.copy(
                        error = "Access Control is no longer permitted for this account.",
                        roles = emptyMap(),
                        modules = emptyList(),
                        cells = emptyList(),
                    )
                } else {
                    _state.value.copy(error = e.message ?: "Could not load access control.")
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    error = "Could not load access control. Check the connection and try again.",
                )
            } finally {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun toggle(cell: AccessCell) = setCell(
        cell.roleCode,
        cell.module,
        JsonPrimitive(cell.accessLevel == "blocked"),
    )

    fun resetToDefault(cell: AccessCell) = setCell(cell.roleCode, cell.module, JsonNull)

    fun dismissActionError() { _state.value = _state.value.copy(actionError = null) }

    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    private fun setCell(roleCode: String, module: String, allowed: JsonElement) {
        val key = "$roleCode:$module"
        if (key in _state.value.busyKeys) return
        if (_state.value.authorityUnknown) {
            _state.value = _state.value.copy(
                actionError = "Refresh the live permission matrix before making another change.",
            )
            return
        }
        _state.value = _state.value.copy(
            busyKeys = _state.value.busyKeys + key,
            actionError = null,
            notice = null,
        )
        viewModelScope.launch {
            try {
                val updated = api.update(AccessControlUpdateBody(roleCode, module, allowed))
                _state.value = _state.value.copy(
                    cells = _state.value.cells.map {
                        if (it.roleCode == roleCode && it.module == module) updated else it
                    },
                    notice = accessChangeNotice(updated),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: ApiException) {
                _state.value = if (e.status == 403) {
                    _state.value.copy(
                        roles = emptyMap(),
                        modules = emptyList(),
                        cells = emptyList(),
                        error = "Access Control is no longer permitted for this account.",
                        authorityUnknown = false,
                        actionError = "Access Control permission was removed. No permission was changed.",
                    )
                } else {
                    val feedback = accessMutationFailure(e)
                    _state.value.copy(
                        authorityUnknown = feedback.authorityUnknown,
                        actionError = feedback.message,
                    )
                }
            } catch (failure: Exception) {
                val feedback = accessMutationFailure(failure)
                _state.value = _state.value.copy(
                    authorityUnknown = feedback.authorityUnknown,
                    actionError = feedback.message,
                )
            } finally {
                _state.value = _state.value.copy(busyKeys = _state.value.busyKeys - key)
            }
        }
    }
}

internal data class AccessMutationFailure(
    val authorityUnknown: Boolean,
    val message: String,
)

internal fun accessMutationFailure(failure: Exception): AccessMutationFailure {
    val apiFailure = failure as? ApiException
    val unknown = apiFailure?.isAmbiguous != false
    return AccessMutationFailure(
        authorityUnknown = unknown,
        message = if (unknown) {
            "The server response was lost, so this permission's result is unknown. Refresh before making another change."
        } else {
            apiFailure.message ?: "The server refused this permission change."
        },
    )
}

internal fun accessChangeNotice(cell: AccessCell): String = when {
    !cell.hasExactPermissionEvidence ->
        "Saved, but this server did not return exact permission evidence. Treat this module as partial until the live matrix is refreshed after a backend update."
    cell.accessLevel == "full" -> "Saved: full ${MODULE_LABELS[cell.module] ?: cell.module} access is effective for this role."
    cell.accessLevel == "partial" -> buildString {
        append("Saved: partial ${MODULE_LABELS[cell.module] ?: cell.module} access is effective; ")
        append("${cell.unavailablePermissions.size} permission(s) are currently unavailable")
        if (cell.ceilingLimitedPermissions.isNotEmpty()) {
            append(", including ${cell.ceilingLimitedPermissions.size} that cannot be granted through this matrix")
        }
        append('.')
    }
    else -> "Saved: ${MODULE_LABELS[cell.module] ?: cell.module} access is blocked for this role."
}
