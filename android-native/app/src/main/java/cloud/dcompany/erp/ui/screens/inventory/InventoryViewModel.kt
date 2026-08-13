package cloud.dcompany.erp.ui.screens.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

enum class InventoryTab { INGREDIENTS, SUPPLIERS }

/** Which modal is up. Owned by the ViewModel so a rotation does not lose a half-typed GRN. */
sealed interface InventoryDialog {
    data class IngredientForm(val editing: Ingredient?) : InventoryDialog
    data class SupplierForm(val editing: Supplier?) : InventoryDialog
    data object Grn : InventoryDialog
    data class Adjust(val ingredient: Ingredient) : InventoryDialog
    data class ConfirmDeleteIngredient(val ingredient: Ingredient) : InventoryDialog
    data class ConfirmDeleteSupplier(val supplier: Supplier) : InventoryDialog
}

data class InventoryUiState(
    val loading: Boolean = true,
    /** Fatal: nothing could be loaded, the screen shows an error and a Retry. */
    val error: String? = null,
    val ingredients: List<Ingredient> = emptyList(),
    val suppliers: List<Supplier> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val branchId: String? = null,
    val tab: InventoryTab = InventoryTab.INGREDIENTS,
    val selectedId: String? = null,
    val batches: List<Batch> = emptyList(),
    val batchesLoading: Boolean = false,
    val batchesError: String? = null,
    val busy: Boolean = false,
    val dialog: InventoryDialog? = null,
    /** Error shown inside the open dialog, next to the button that caused it. */
    val formError: String? = null,
    val notice: String? = null,
) {
    /** Lowest stock relative to its reorder line first — the order to act in. */
    val sortedIngredients: List<Ingredient>
        get() = ingredients.sortedBy {
            if (it.reorderThreshold > 0) it.currentQty / it.reorderThreshold else 99.0
        }

    val restockPriority: List<Ingredient>
        get() = sortedIngredients.filter { it.reorderThreshold > 0 && it.isLow }.take(6)

    val stockValueMinor: Long get() = ingredients.sumOf { it.stockValueMinor }
    val lowCount: Int get() = ingredients.count { it.isLow }
    val selected: Ingredient? get() = ingredients.firstOrNull { it.id == selectedId }
}

class InventoryViewModel : ViewModel() {

    private val api = ApiClient.create<InventoryApi>()

    private val _state = MutableStateFlow(InventoryUiState())
    val state: StateFlow<InventoryUiState> = _state.asStateFlow()

    private val grnKey = IdempotencyKey()
    private val adjustKey = IdempotencyKey()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // Ingredients are the screen. Suppliers and branches only gate
                // the two write actions, so losing them degrades the screen
                // instead of blanking it — the same call the web spec makes.
                val ingredients = api.ingredients()
                val suppliers = try { api.suppliers() } catch (e: ApiException) { emptyList() }
                val branches = try { api.branches() } catch (e: ApiException) { emptyList() }

                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    ingredients = ingredients,
                    suppliers = suppliers,
                    branches = branches,
                    branchId = resolveBranch(branches),
                    // Drop a selection whose ingredient has since been deleted.
                    selectedId = _state.value.selectedId
                        ?.takeIf { id -> ingredients.any { it.id == id } },
                )
                _state.value.selectedId?.let { loadBatches(it) }
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not load inventory.",
                )
            }
        }
    }

    /**
     * Defaults to the branch this account belongs to, so a GRN on a tablet in
     * one cafe cannot land against another branch's stock just because it
     * happened to sort first.
     */
    private suspend fun resolveBranch(branches: List<Branch>): String? {
        val current = _state.value.branchId
        if (current != null && branches.any { it.id == current }) return current
        val mine = try { ApiClient.api.me().branchId } catch (e: ApiException) { null }
        return branches.firstOrNull { it.id == mine }?.id ?: branches.firstOrNull()?.id
    }

    fun selectTab(tab: InventoryTab) { _state.value = _state.value.copy(tab = tab) }

    fun selectBranch(id: String) { _state.value = _state.value.copy(branchId = id) }

    fun select(id: String?) {
        _state.value = _state.value.copy(
            selectedId = id,
            batches = emptyList(),
            batchesError = null,
        )
        if (id != null) loadBatches(id)
    }

    private fun loadBatches(ingredientId: String) {
        _state.value = _state.value.copy(batchesLoading = true, batchesError = null)
        viewModelScope.launch {
            try {
                val rows = api.batches(ingredientId)
                if (_state.value.selectedId != ingredientId) return@launch
                _state.value = _state.value.copy(batchesLoading = false, batches = rows)
            } catch (e: ApiException) {
                if (_state.value.selectedId != ingredientId) return@launch
                _state.value = _state.value.copy(
                    batchesLoading = false,
                    batchesError = e.message ?: "Could not load batches.",
                )
            }
        }
    }

    fun openDialog(dialog: InventoryDialog) {
        _state.value = _state.value.copy(dialog = dialog, formError = null)
    }

    fun closeDialog() {
        _state.value = _state.value.copy(dialog = null, formError = null)
    }

    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    fun showFormError(message: String) { _state.value = _state.value.copy(formError = message) }

    // ------------------------------------------------------------ ingredients

    fun saveIngredient(
        editing: Ingredient?,
        sku: String,
        name: String,
        baseUnit: String,
        reorderThreshold: Double,
        reorderQty: Double,
    ) = mutate {
        if (editing == null) {
            val created = api.createIngredient(
                IngredientCreate(
                    sku = sku.trim(),
                    name = name.trim(),
                    baseUnit = baseUnit,
                    reorderThreshold = reorderThreshold,
                    reorderQty = reorderQty,
                ),
            )
            "${created.name} added."
        } else {
            val updated = api.updateIngredient(
                editing.id,
                IngredientUpdate(
                    name = name.trim(),
                    baseUnit = baseUnit,
                    reorderThreshold = reorderThreshold,
                    reorderQty = reorderQty,
                ),
            )
            "${updated.name} saved."
        }
    }

    fun deleteIngredient(ingredient: Ingredient) = mutate {
        api.deleteIngredient(ingredient.id)
        if (_state.value.selectedId == ingredient.id) {
            _state.value = _state.value.copy(selectedId = null, batches = emptyList())
        }
        "${ingredient.name} removed. Its batches stay in the audit trail."
    }

    // -------------------------------------------------------------- suppliers

    fun saveSupplier(
        editing: Supplier?,
        name: String,
        contact: String,
        gstin: String,
        paymentTerms: String,
    ) = mutate {
        val body = SupplierBody(
            name = name.trim(),
            contact = contact.trim().ifBlank { null },
            gstin = gstin.trim().ifBlank { null },
            paymentTerms = paymentTerms.trim().ifBlank { null },
        )
        val saved = if (editing == null) api.createSupplier(body)
        else api.updateSupplier(editing.id, body)
        "${saved.name} ${if (editing == null) "added" else "saved"}."
    }

    fun deleteSupplier(supplier: Supplier) = mutate {
        api.deleteSupplier(supplier.id)
        "${supplier.name} removed."
    }

    // -------------------------------------------------------------------- GRN

    fun postGrn(
        supplierId: String,
        branchId: String,
        invoiceNo: String,
        notes: String,
        lines: List<GrnLineBody>,
    ) = mutate {
        val body = GrnBody(
            branchId = branchId,
            supplierId = supplierId,
            supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
            notes = notes.trim().ifBlank { null },
            lines = lines,
        )
        val result = api.postGrn(body, grnKey.forPayload(body))
        grnKey.consumed()
        "Receipt recorded — ${result.batchesCreated} batch(es) added."
    }

    // ------------------------------------------------------------- adjustment

    /**
     * [qty] is exactly what the operator typed. The sign is applied by
     * [adjustmentDelta] and nowhere else, so what the confirmation preview
     * showed them is literally the number that goes to the server.
     */
    fun postAdjustment(
        ingredient: Ingredient,
        branchId: String,
        type: String,
        qty: Double,
        note: String,
    ) = mutate {
        val delta = adjustmentDelta(type, qty)
        val body = AdjustmentBody(
            ingredientId = ingredient.id,
            branchId = branchId,
            qtyDelta = delta,
            type = type,
            note = note.trim().ifBlank { null },
        )
        val result = api.postAdjustment(body, adjustKey.forPayload(body))
        adjustKey.consumed()
        val direction = if (delta < 0) "removed from" else "added to"
        "${abs(delta).asQty()} ${ingredient.baseUnit} $direction ${ingredient.name}. " +
            "Now ${result.remaining.asQty()} ${ingredient.baseUnit}."
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * One write, then a reload. The dialog only closes on success — a failure
     * leaves the operator's typing on screen with the server's own message
     * beside it, so they can fix the input rather than start over.
     */
    private fun mutate(block: suspend () -> String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, formError = null)
        viewModelScope.launch {
            try {
                val message = block()
                _state.value = _state.value.copy(
                    busy = false,
                    dialog = null,
                    formError = null,
                    notice = message,
                )
                load()
            } catch (e: ApiException) {
                _state.value = _state.value.copy(
                    busy = false,
                    formError = e.message ?: "The server refused that. Nothing was saved.",
                )
            }
        }
    }
}

/**
 * An Idempotency-Key that is generated once per attempt and reused on retry.
 *
 * The subtlety is what counts as "the same attempt". Holding one key forever
 * would break the ordinary correct-and-resubmit flow, because the backend
 * hashes the request body alongside the key and answers a changed body with a
 * 409 rather than performing the write. So the key is bound to the payload: an
 * identical resubmission — the retry case, including the dangerous one where
 * the write may already have committed and we never heard back — replays the
 * same key, while an edited payload is a genuinely new attempt and gets a new
 * one. The key is never rotated on failure.
 */
private class IdempotencyKey {
    private var signature: String? = null
    private var key: String = UUID.randomUUID().toString()

    fun forPayload(payload: Any): String {
        val current = payload.toString()
        if (current != signature) {
            signature = current
            key = UUID.randomUUID().toString()
        }
        return key
    }

    /** Call after the server has definitively accepted the write. */
    fun consumed() {
        signature = null
        key = UUID.randomUUID().toString()
    }
}
