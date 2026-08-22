package cloud.dcompany.erp.ui.screens.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.BatchCacheEntity
import cloud.dcompany.erp.core.db.IngredientCacheEntity
import cloud.dcompany.erp.core.db.IngredientWriteState
import cloud.dcompany.erp.core.db.LocalAdjustmentEntity
import cloud.dcompany.erp.core.db.LocalGrnEntity
import cloud.dcompany.erp.core.db.LocalGrnLineEntity
import cloud.dcompany.erp.core.db.LocalIngredientEntity
import cloud.dcompany.erp.core.db.LocalSupplierEntity
import cloud.dcompany.erp.core.db.SupplierCacheEntity
import cloud.dcompany.erp.core.db.SupplierWriteState
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToLong

private const val LOCAL_PREFIX = "local:"
private const val BRANCHES_CACHE_KEY = "inventory_branches"

enum class InventoryTab { INGREDIENTS, SUPPLIERS }

/** Which modal is up. Owned by the ViewModel so a rotation does not lose track of which row it was for. */
sealed interface InventoryDialog {
    data class IngredientForm(val editing: IngredientRow?) : InventoryDialog
    data class SupplierForm(val editing: SupplierRow?) : InventoryDialog
    data object Grn : InventoryDialog
    data class Adjust(val ingredient: IngredientRow) : InventoryDialog
    data class ConfirmDeleteIngredient(val ingredient: IngredientRow) : InventoryDialog
    data class ConfirmDeleteSupplier(val supplier: SupplierRow) : InventoryDialog
}

/** Row shown in the ingredients list — merged cache + any pending/rejected local write. */
data class IngredientRow(
    val id: String,
    val sku: String,
    val name: String,
    val baseUnit: String,
    val currentQty: Double = 0.0,
    val reorderThreshold: Double = 0.0,
    val reorderQty: Double = 0.0,
    val avgCostMinor: Long = 0,
    val pendingLocalId: String? = null,
    val rejectedError: String? = null,
    val pendingDelete: Boolean = false,
    val hasQueuedDelete: Boolean = false,
    val localWriteId: String? = null,
) {
    val isLow: Boolean get() = currentQty < reorderThreshold
    val stockValueMinor: Long get() = (currentQty * avgCostMinor).roundToLong()
    /** Not yet a real server id — nothing else can reference this ingredient (a GRN line, a supplier link) until it syncs. */
    val isUnsyncedDraft: Boolean get() = id.startsWith(LOCAL_PREFIX)
}

/** Row shown in the suppliers list — same merge shape as IngredientRow. */
data class SupplierRow(
    val id: String,
    val name: String,
    val contact: String? = null,
    val gstin: String? = null,
    val paymentTerms: String? = null,
    val pendingLocalId: String? = null,
    val rejectedError: String? = null,
    val pendingDelete: Boolean = false,
    val hasQueuedDelete: Boolean = false,
    val localWriteId: String? = null,
) {
    val isUnsyncedDraft: Boolean get() = id.startsWith(LOCAL_PREFIX)
}

/**
 * A queued-but-not-yet-synced stock receipt, with the supplier's name
 * resolved for display. Deliberately doesn't summarize line count/total —
 * that needs the GRN's lines loaded alongside its header, which the
 * `local_grns`-only Flow this is built from doesn't carry; "receipt from
 * {supplier}, queued" is honest with what's cheaply available, rather than
 * a stubbed "0 lines / ₹0" that would misreport a real receipt.
 */
data class PendingGrnRow(
    val localId: String,
    val supplierName: String,
    val rejected: Boolean,
    val error: String? = null,
)

/** A queued-but-not-yet-synced stock adjustment, with the ingredient's name resolved for display. */
data class PendingAdjustmentRow(
    val localId: String,
    val ingredientName: String,
    val unit: String,
    val qtyDelta: Double,
    val rejected: Boolean,
    val error: String? = null,
)

data class InventoryUiState(
    val everSynced: Boolean = false,
    val syncing: Boolean = false,
    val ingredients: List<IngredientRow> = emptyList(),
    val suppliers: List<SupplierRow> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val branchId: String? = null,
    val tab: InventoryTab = InventoryTab.INGREDIENTS,
    val selectedSku: String? = null,
    val batches: List<BatchCacheEntity> = emptyList(),
    val batchesLoading: Boolean = false,
    val pendingGrns: List<PendingGrnRow> = emptyList(),
    val pendingAdjustments: List<PendingAdjustmentRow> = emptyList(),
    val busy: Boolean = false,
    val dialog: InventoryDialog? = null,
    /** Error shown inside the open dialog, next to the button that caused it. */
    val formError: String? = null,
    val notice: String? = null,
) {
    val loading: Boolean get() = !everSynced && ingredients.isEmpty() && syncing
    val couldNotLoad: Boolean get() = !everSynced && ingredients.isEmpty() && !syncing

    /** Lowest stock relative to its reorder line first — the order to act in. */
    val sortedIngredients: List<IngredientRow>
        get() = ingredients.sortedBy {
            if (it.reorderThreshold > 0) it.currentQty / it.reorderThreshold else 99.0
        }

    val restockPriority: List<IngredientRow>
        get() = sortedIngredients.filter { it.reorderThreshold > 0 && it.isLow }.take(6)

    val stockValueMinor: Long get() = ingredients.sumOf { it.stockValueMinor }
    val lowCount: Int get() = ingredients.count { it.isLow }
    val selected: IngredientRow? get() = ingredients.firstOrNull { it.sku == selectedSku }

    /** Only ingredients/suppliers that already have a real server id — a GRN or adjustment can't reference a draft that hasn't synced yet. */
    val syncedIngredients: List<IngredientRow> get() = ingredients.filterNot { it.isUnsyncedDraft }
    val syncedSuppliers: List<SupplierRow> get() = suppliers.filterNot { it.isUnsyncedDraft }
}

/**
 * Ingredients and suppliers are master data — offline-queued create/edit/
 * delete via a single outbox row each, same pattern Staff/Customers/Menu
 * already established (delete folded in via `pendingDelete`, same
 * REJECTED-state guard on the display flag Phase 8 had to add after it was
 * missed the first time — see IngredientRow/mergeIngredients below).
 *
 * GRN and adjustments are Shape D: header(+lines for GRN)-only, insert-only,
 * never edited after capture — same pattern as POS orders
 * (OrderDao.capture()/SyncEngine.pushOne). Both are money/stock-correctness
 * sensitive (see backend/app/api/v1/inventory/router.py's now-mandatory
 * Idempotency-Key requirement on /grn and /adjustments) and both can only
 * ever target an already-synced ingredient/supplier — the pickers in
 * GrnDialog/AdjustDialog filter to [InventoryUiState.syncedIngredients]/
 * [syncedSuppliers] rather than resolving a dependency chain, the same
 * sidestep Menu uses for item-create's category picker.
 */
class InventoryViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<InventoryApi>()

    private val pulling = MutableStateFlow(false)
    private val branchesFlow = MutableStateFlow<List<Branch>>(emptyList())
    private val myProfile = MutableStateFlow<MeResponse?>(null)
    private val tab = MutableStateFlow(InventoryTab.INGREDIENTS)
    private val selectedSku = MutableStateFlow<String?>(null)
    private val branchId = MutableStateFlow<String?>(null)
    private val dialog = MutableStateFlow<InventoryDialog?>(null)
    private val busy = MutableStateFlow(false)
    private val formError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<InventoryUiState> = combine(
        combine(
            db.inventoryDao().observeIngredientCache(),
            db.inventoryDao().observeLocalIngredients(),
            db.inventoryDao().observeSupplierCache(),
            db.inventoryDao().observeLocalSuppliers(),
        ) { ic, li, sc, ls -> IngredientsAndSuppliers(ic, li, sc, ls) },
        combine(
            db.syncMetaDao().observe("ingredients"),
            db.syncMetaDao().observe("suppliers"),
            pulling,
        ) { ingMeta, supMeta, syncing -> Triple(ingMeta, supMeta, syncing) },
        combine(branchesFlow, branchId, myProfile) { b, bid, p -> Triple(b, bid, p) },
        combine(
            db.inventoryDao().observeLocalGrns(),
            db.inventoryDao().observeLocalAdjustments(),
        ) { grns, adjustments -> grns to adjustments },
        // busy/formError/notice pre-folded into one flow so this outer
        // combine stays within the 5-flow typed-overload cap alongside
        // tab/selectedSku/dialog — same nested-combine technique used
        // throughout SyncEngine's totalRejectedCount.
        combine(
            tab, selectedSku, dialog,
            combine(busy, formError, notice) { b, fe, n -> Triple(b, fe, n) },
        ) { t, sku, d, action -> EditingState(t, sku, d, action.first, action.second, action.third) },
    ) { data, metaAndSyncing, branchInfo, pendingWrites, ed ->
        val (ingMeta, supMeta, isSyncing) = metaAndSyncing
        val (branches, myBranchId, profile) = branchInfo
        val (grns, adjustments) = pendingWrites
        val ingredientRows = mergeIngredients(data.ingredientCache, data.localIngredients)
        val supplierRows = mergeSuppliers(data.supplierCache, data.localSuppliers)
        InventoryUiState(
            // Both pulls must have completed at least once — ingredients
            // succeeding while suppliers fails on a first run (e.g. the
            // connection drops mid-refresh, since pullInventoryOnDemand()
            // runs them sequentially) must not show a false "No suppliers
            // yet" on an empty cache that was simply never fetched.
            everSynced = ingMeta != null && supMeta != null,
            syncing = isSyncing,
            ingredients = ingredientRows,
            suppliers = supplierRows,
            branches = branches,
            branchId = myBranchId ?: resolveDefaultBranch(branches, profile),
            tab = ed.tab,
            selectedSku = ed.selectedSku,
            batches = emptyList(), // attached by the second combine stage below
            pendingGrns = grns.map { it.toPendingRow(supplierRows) },
            pendingAdjustments = adjustments.map { it.toPendingRow(ingredientRows) },
            busy = ed.busy,
            dialog = ed.dialog,
            formError = ed.formError,
            notice = ed.notice,
        )
    }.let { base ->
        // Batches are a second combine stage keyed off the selected
        // ingredient's *real id*, re-derived from `base` on every emission
        // (via selected?.id) rather than tracked as a separately-mutated
        // field — a separately-tracked id, set only from select()'s own tap
        // handler, went stale if the selected ingredient was a still-local
        // draft that synced while it stayed selected: its id would flip
        // from a "local:" pseudo-id to a real server id, but nothing
        // re-triggered a batches fetch, so the detail panel kept showing
        // "not synced yet" (or an empty list with no explanation) even
        // after the ingredient became fully queryable. distinctUntilChanged
        // still means the Room query only restarts when this ingredient's
        // resolvable id actually changes, not on every unrelated write.
        val selectedIngredientId = base
            .map { it.selected?.takeIf { row -> !row.isUnsyncedDraft }?.id }
            .distinctUntilChanged()
        val batchesFlow = selectedIngredientId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else db.inventoryDao().observeBatchesFor(id)
        }
        combine(base, batchesFlow) { s, batches -> s.copy(batches = batches) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    private data class IngredientsAndSuppliers(
        val ingredientCache: List<IngredientCacheEntity>,
        val localIngredients: List<LocalIngredientEntity>,
        val supplierCache: List<SupplierCacheEntity>,
        val localSuppliers: List<LocalSupplierEntity>,
    )

    private data class EditingState(
        val tab: InventoryTab,
        val selectedSku: String?,
        val dialog: InventoryDialog?,
        val busy: Boolean,
        val formError: String?,
        val notice: String?,
    )

    init {
        loadProfile()
        loadBranches()
        retry()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            myProfile.value = appCtx.shiftCache.cachedProfile()?.let {
                runCatching { ApiClient.json.decodeFromString(MeResponse.serializer(), it) }.getOrNull()
            }
        }
    }

    private fun loadBranches() {
        viewModelScope.launch {
            db.reportSnapshotDao().cached<List<Branch>>(BRANCHES_CACHE_KEY)?.let { (cached, _) ->
                branchesFlow.value = cached
            }
            try {
                val fresh = api.branches()
                branchesFlow.value = fresh
                db.reportSnapshotDao().store(BRANCHES_CACHE_KEY, fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cached branches (if any) stay showing.
            }
        }
    }

    private fun resolveDefaultBranch(branches: List<Branch>, profile: MeResponse?): String? {
        val mine = branches.firstOrNull { it.id == profile?.branchId }
        return (mine ?: branches.firstOrNull())?.id
    }

    // -------------------------------------------------------------- loading

    fun retry() {
        appCtx.sync.requestSync()
        pulling.value = true
        viewModelScope.launch {
            try {
                appCtx.sync.refresh("inventory")
            } finally {
                pulling.value = false
            }
        }
    }

    fun selectTab(t: InventoryTab) { tab.value = t }

    fun selectBranch(id: String) { branchId.value = id }

    fun select(row: IngredientRow?) {
        selectedSku.value = row?.sku
        val realId = row?.takeIf { !it.isUnsyncedDraft }?.id
        if (realId != null) {
            viewModelScope.launch { appCtx.sync.pullBatchesFor(realId) }
        }
    }

    fun openDialog(d: InventoryDialog) { dialog.value = d; formError.value = null }

    fun closeDialog() { dialog.value = null; formError.value = null }

    fun dismissNotice() { notice.value = null }

    fun showFormError(message: String) { formError.value = message }

    // ------------------------------------------------------------ ingredients

    /** Always writes locally first — queued via the outbox, never a direct network call. */
    fun saveIngredient(
        editing: IngredientRow?,
        sku: String,
        name: String,
        baseUnit: String,
        reorderThreshold: Double,
        reorderQty: Double,
    ) = localMutate {
        val dao = db.inventoryDao()
        if (editing == null) {
            dao.upsertLocalIngredient(
                LocalIngredientEntity(
                    localId = UUID.randomUUID().toString(),
                    sku = sku.trim(), name = name.trim(), baseUnit = baseUnit,
                    reorderThreshold = reorderThreshold, reorderQty = reorderQty,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
            "$name added — will sync when back online."
        } else {
            val serverId = editing.id.takeIf { !editing.isUnsyncedDraft }
            val existingPending = editing.localWriteId?.let { dao.getLocalIngredient(it) }
            val local = (existingPending ?: LocalIngredientEntity(
                localId = editing.localWriteId ?: UUID.randomUUID().toString(),
                serverId = serverId,
                createdAtMillis = System.currentTimeMillis(),
            )).copy(
                // Only a still-unsynced draft's SKU is actually editable
                // (the dialog only unlocks the field in that case — see
                // IngredientDialog) — an already-synced ingredient's SKU
                // stays whatever the pending row already had (null, since
                // an edit row never carries one; the backend's own
                // IngredientUpdate has no sku field at all).
                sku = if (editing.isUnsyncedDraft) sku.trim() else null,
                name = name.trim(), baseUnit = baseUnit,
                reorderThreshold = reorderThreshold, reorderQty = reorderQty,
                // The only way to reach this dialog for a row that already
                // carries a local write is either no pending write at all,
                // or a *rejected* delete (a still-pending delete hides the
                // Edit button entirely — see IngredientRowCard). Saving an
                // edit here is an explicit signal to keep the row, not
                // remove it, so this must clear pendingDelete — otherwise a
                // rejected delete's flag survives untouched underneath the
                // edit, the row silently re-queues as the same doomed
                // delete instead of the update just typed, and the "saved"
                // notice below is a lie.
                pendingDelete = false,
                state = IngredientWriteState.PENDING, lastError = null,
                version = (existingPending?.version ?: -1) + 1,
            )
            dao.upsertLocalIngredient(local)
            "$name saved — will sync when back online."
        }
    }

    fun deleteIngredient(ingredient: IngredientRow) = localMutate {
        val dao = db.inventoryDao()
        if (ingredient.isUnsyncedDraft) {
            // Never left this device — nothing to queue, just drop the draft.
            ingredient.localWriteId?.let { dao.deleteLocalIngredient(it) }
        } else {
            val existingPending = ingredient.localWriteId?.let { dao.getLocalIngredient(it) }
            val local = (existingPending ?: LocalIngredientEntity(
                localId = UUID.randomUUID().toString(),
                serverId = ingredient.id,
                createdAtMillis = System.currentTimeMillis(),
            )).copy(
                pendingDelete = true, state = IngredientWriteState.PENDING, lastError = null,
                version = (existingPending?.version ?: -1) + 1,
            )
            dao.upsertLocalIngredient(local)
        }
        if (selectedSku.value == ingredient.sku) selectedSku.value = null
        "${ingredient.name} will be removed when back online. Its batches stay in the audit trail."
    }

    /** Abandons a queued delete, whether still pending or already rejected — mirrors StaffViewModel.cancelPendingRemoval. */
    fun cancelIngredientRemoval(ingredient: IngredientRow) {
        val localId = ingredient.localWriteId ?: return
        viewModelScope.launch {
            val dao = db.inventoryDao()
            val existing = dao.getLocalIngredient(localId) ?: return@launch
            val hasFieldEdits = existing.name != null || existing.baseUnit != null ||
                existing.reorderThreshold != null || existing.reorderQty != null
            if (hasFieldEdits) {
                dao.upsertLocalIngredient(
                    existing.copy(
                        pendingDelete = false, state = IngredientWriteState.PENDING,
                        lastError = null, version = existing.version + 1,
                    ),
                )
            } else {
                dao.deleteLocalIngredient(localId)
            }
            notice.value = "Removal cancelled."
            appCtx.sync.requestSync()
        }
    }

    fun retryIngredientSync(ingredient: IngredientRow) {
        val localId = ingredient.localWriteId ?: return
        viewModelScope.launch {
            val dao = db.inventoryDao()
            dao.getLocalIngredient(localId)?.let { existing ->
                dao.upsertLocalIngredient(
                    existing.copy(
                        state = IngredientWriteState.PENDING, lastError = null,
                        version = existing.version + 1,
                    ),
                )
                appCtx.sync.requestSync()
            }
        }
    }

    // -------------------------------------------------------------- suppliers

    fun saveSupplier(
        editing: SupplierRow?,
        name: String,
        contact: String,
        gstin: String,
        paymentTerms: String,
    ) = localMutate {
        val dao = db.inventoryDao()
        val trimmedContact = contact.trim().ifBlank { null }
        val trimmedGstin = gstin.trim().ifBlank { null }
        val trimmedTerms = paymentTerms.trim().ifBlank { null }
        if (editing == null) {
            dao.upsertLocalSupplier(
                LocalSupplierEntity(
                    localId = UUID.randomUUID().toString(),
                    name = name.trim(), contact = trimmedContact, gstin = trimmedGstin, paymentTerms = trimmedTerms,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
            "$name added — will sync when back online."
        } else {
            val serverId = editing.id.takeIf { !editing.isUnsyncedDraft }
            val existingPending = editing.localWriteId?.let { dao.getLocalSupplier(it) }
            val local = (existingPending ?: LocalSupplierEntity(
                localId = editing.localWriteId ?: UUID.randomUUID().toString(),
                serverId = serverId,
                createdAtMillis = System.currentTimeMillis(),
            )).copy(
                name = name.trim(), contact = trimmedContact, gstin = trimmedGstin, paymentTerms = trimmedTerms,
                // Same reasoning as saveIngredient — the only way to reach
                // this dialog for a row with an existing local write is
                // "no pending write" or "a rejected delete," and saving an
                // edit here means keep the row, so pendingDelete must clear.
                pendingDelete = false,
                state = SupplierWriteState.PENDING, lastError = null,
                version = (existingPending?.version ?: -1) + 1,
            )
            dao.upsertLocalSupplier(local)
            "$name saved — will sync when back online."
        }
    }

    fun deleteSupplier(supplier: SupplierRow) = localMutate {
        val dao = db.inventoryDao()
        if (supplier.isUnsyncedDraft) {
            supplier.localWriteId?.let { dao.deleteLocalSupplier(it) }
        } else {
            val existingPending = supplier.localWriteId?.let { dao.getLocalSupplier(it) }
            val local = (existingPending ?: LocalSupplierEntity(
                localId = UUID.randomUUID().toString(),
                serverId = supplier.id,
                createdAtMillis = System.currentTimeMillis(),
            )).copy(
                pendingDelete = true, state = SupplierWriteState.PENDING, lastError = null,
                version = (existingPending?.version ?: -1) + 1,
            )
            dao.upsertLocalSupplier(local)
        }
        "${supplier.name} will be removed when back online."
    }

    fun cancelSupplierRemoval(supplier: SupplierRow) {
        val localId = supplier.localWriteId ?: return
        viewModelScope.launch {
            val dao = db.inventoryDao()
            val existing = dao.getLocalSupplier(localId) ?: return@launch
            val hasFieldEdits = existing.name != null || existing.contact != null ||
                existing.gstin != null || existing.paymentTerms != null
            if (hasFieldEdits) {
                dao.upsertLocalSupplier(
                    existing.copy(
                        pendingDelete = false, state = SupplierWriteState.PENDING,
                        lastError = null, version = existing.version + 1,
                    ),
                )
            } else {
                dao.deleteLocalSupplier(localId)
            }
            notice.value = "Removal cancelled."
            appCtx.sync.requestSync()
        }
    }

    fun retrySupplierSync(supplier: SupplierRow) {
        val localId = supplier.localWriteId ?: return
        viewModelScope.launch {
            val dao = db.inventoryDao()
            dao.getLocalSupplier(localId)?.let { existing ->
                dao.upsertLocalSupplier(
                    existing.copy(
                        state = SupplierWriteState.PENDING, lastError = null,
                        version = existing.version + 1,
                    ),
                )
                appCtx.sync.requestSync()
            }
        }
    }

    // -------------------------------------------------------------------- GRN

    fun postGrn(
        supplierId: String,
        branchId: String,
        invoiceNo: String,
        notesText: String,
        lines: List<GrnLineBody>,
    ) = localMutate {
        val localId = UUID.randomUUID().toString()
        db.inventoryDao().captureGrn(
            grn = LocalGrnEntity(
                localId = localId, branchId = branchId, supplierId = supplierId,
                supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
                notes = notesText.trim().ifBlank { null },
                createdAtMillis = System.currentTimeMillis(),
            ),
            lines = lines.map {
                LocalGrnLineEntity(
                    grnLocalId = localId, ingredientId = it.ingredientId, qty = it.qty,
                    unitCostMinor = it.unitCostMinor, expiresAt = it.expiresAt, lotCode = it.lotCode,
                )
            },
        )
        "Receipt queued — will sync when back online."
    }

    fun retryGrn(localId: String) {
        viewModelScope.launch {
            db.inventoryDao().retryGrn(localId)
            appCtx.sync.requestSync()
        }
    }

    // ------------------------------------------------------------- adjustment

    /**
     * [qty] is exactly what the operator typed. The sign is applied by
     * [adjustmentDelta] and nowhere else, so what the confirmation preview
     * showed them is literally the number that gets queued.
     */
    fun postAdjustment(
        ingredient: IngredientRow,
        branchId: String,
        type: String,
        qty: Double,
        note: String,
    ) = localMutate {
        val delta = adjustmentDelta(type, qty)
        db.inventoryDao().insertAdjustment(
            LocalAdjustmentEntity(
                localId = UUID.randomUUID().toString(),
                ingredientId = ingredient.id, branchId = branchId,
                qtyDelta = delta, type = type, note = note.trim().ifBlank { null },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        "Adjustment queued — will sync when back online."
    }

    fun retryAdjustment(localId: String) {
        viewModelScope.launch {
            db.inventoryDao().retryAdjustment(localId)
            appCtx.sync.requestSync()
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * One local (Room) write, then the dialog closes — no network wait,
     * since the whole point is that this succeeds instantly offline too.
     * Kept as a suspend-wrapped block (not a plain synchronous call) because
     * Room writes still go through a real coroutine dispatch, and every
     * other action in this app self-guards its own busy flag rather than
     * relying solely on the UI disabling a button.
     */
    private fun localMutate(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                val message = block()
                busy.value = false
                dialog.value = null
                formError.value = null
                notice.value = message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value = "Could not save this locally: ${e.message}"
            }
        }
    }
}

private fun mergeIngredients(
    cache: List<IngredientCacheEntity>,
    local: List<LocalIngredientEntity>,
): List<IngredientRow> {
    val cacheById = cache.associateBy { it.id }
    val overriddenServerIds = local.mapNotNull { it.serverId }.toSet()
    val result = ArrayList<IngredientRow>(cache.size + local.size)
    for (row in local) {
        val matchedCache = row.serverId?.let { cacheById[it] }
        result += IngredientRow(
            id = row.serverId ?: "$LOCAL_PREFIX${row.localId}",
            sku = row.sku ?: matchedCache?.sku ?: "",
            name = row.name ?: matchedCache?.name ?: "",
            baseUnit = row.baseUnit ?: matchedCache?.baseUnit ?: "",
            currentQty = matchedCache?.currentQty ?: 0.0,
            reorderThreshold = row.reorderThreshold ?: matchedCache?.reorderThreshold ?: 0.0,
            reorderQty = row.reorderQty ?: matchedCache?.reorderQty ?: 0.0,
            avgCostMinor = matchedCache?.avgCostMinor ?: 0,
            pendingLocalId = if (row.state != IngredientWriteState.REJECTED) row.localId else null,
            rejectedError = if (row.state == IngredientWriteState.REJECTED) row.lastError else null,
            // Same REJECTED guard Phase 8 had to add to Staff after missing
            // it the first time — a rejected delete must fall through to the
            // rejection banner, not claim forever that it's still pending.
            pendingDelete = if (row.state != IngredientWriteState.REJECTED) row.pendingDelete else false,
            hasQueuedDelete = row.pendingDelete,
            localWriteId = row.localId,
        )
    }
    for (c in cache) {
        if (c.id in overriddenServerIds) continue
        result += IngredientRow(
            id = c.id, sku = c.sku, name = c.name, baseUnit = c.baseUnit,
            currentQty = c.currentQty, reorderThreshold = c.reorderThreshold,
            reorderQty = c.reorderQty, avgCostMinor = c.avgCostMinor,
        )
    }
    return result.sortedBy { it.name }
}

private fun mergeSuppliers(
    cache: List<SupplierCacheEntity>,
    local: List<LocalSupplierEntity>,
): List<SupplierRow> {
    val cacheById = cache.associateBy { it.id }
    val overriddenServerIds = local.mapNotNull { it.serverId }.toSet()
    val result = ArrayList<SupplierRow>(cache.size + local.size)
    for (row in local) {
        val matchedCache = row.serverId?.let { cacheById[it] }
        result += SupplierRow(
            id = row.serverId ?: "$LOCAL_PREFIX${row.localId}",
            name = row.name ?: matchedCache?.name ?: "",
            contact = row.contact ?: matchedCache?.contact,
            gstin = row.gstin ?: matchedCache?.gstin,
            paymentTerms = row.paymentTerms ?: matchedCache?.paymentTerms,
            pendingLocalId = if (row.state != SupplierWriteState.REJECTED) row.localId else null,
            rejectedError = if (row.state == SupplierWriteState.REJECTED) row.lastError else null,
            pendingDelete = if (row.state != SupplierWriteState.REJECTED) row.pendingDelete else false,
            hasQueuedDelete = row.pendingDelete,
            localWriteId = row.localId,
        )
    }
    for (c in cache) {
        if (c.id in overriddenServerIds) continue
        result += SupplierRow(id = c.id, name = c.name, contact = c.contact, gstin = c.gstin, paymentTerms = c.paymentTerms)
    }
    return result.sortedBy { it.name }
}

private fun LocalGrnEntity.toPendingRow(suppliers: List<SupplierRow>): PendingGrnRow = PendingGrnRow(
    localId = localId,
    supplierName = suppliers.firstOrNull { it.id == supplierId }?.name ?: "Unknown supplier",
    rejected = syncState == SyncState.REJECTED,
    error = lastError,
)

private fun LocalAdjustmentEntity.toPendingRow(ingredients: List<IngredientRow>): PendingAdjustmentRow {
    val ingredient = ingredients.firstOrNull { it.id == ingredientId }
    return PendingAdjustmentRow(
        localId = localId,
        ingredientName = ingredient?.name ?: "Unknown ingredient",
        unit = ingredient?.baseUnit ?: "",
        qtyDelta = qtyDelta,
        rejected = syncState == SyncState.REJECTED,
        error = lastError,
    )
}
