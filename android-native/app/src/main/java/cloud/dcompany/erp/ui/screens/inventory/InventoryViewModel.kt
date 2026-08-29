package cloud.dcompany.erp.ui.screens.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.InventoryAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.db.BatchCacheEntity
import cloud.dcompany.erp.core.db.IngredientCacheEntity
import cloud.dcompany.erp.core.db.IngredientWriteState
import cloud.dcompany.erp.core.db.LocalAdjustmentEntity
import cloud.dcompany.erp.core.db.LocalGrnEntity
import cloud.dcompany.erp.core.db.LocalGrnLineEntity
import cloud.dcompany.erp.core.db.LocalIngredientEntity
import cloud.dcompany.erp.core.db.LocalSupplierEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.SupplierCacheEntity
import cloud.dcompany.erp.core.db.SupplierWriteState
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.Instant

private const val LOCAL_PREFIX = "local:"
private const val BRANCHES_CACHE_KEY = "inventory_branches"

private class CreateConfirmationPending(message: String) : Exception(message)

enum class InventoryTab { INGREDIENTS, SUPPLIERS, RECIPES }

/** Which modal is up. Owned by the ViewModel so a rotation does not lose track of which row it was for. */
sealed interface InventoryDialog {
    data class IngredientForm(val editing: IngredientRow?) : InventoryDialog
    data class SupplierForm(val editing: SupplierRow?) : InventoryDialog
    data object Grn : InventoryDialog
    data class Adjust(val ingredient: IngredientRow) : InventoryDialog
    data class ConfirmDeleteIngredient(val ingredient: IngredientRow) : InventoryDialog
    data class ConfirmDeleteSupplier(val supplier: SupplierRow) : InventoryDialog
    data class RecipeCreate(val menuItem: MenuItemEntity) : InventoryDialog
    data class RecipeLineForm(val recipe: Recipe, val editing: RecipeLine?) : InventoryDialog
    data class ConfirmDeleteRecipe(val recipe: Recipe) : InventoryDialog
    data class ConfirmDeleteRecipeLine(val recipe: Recipe, val line: RecipeLine) : InventoryDialog
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
    /** Exact sum of remaining FIFO batches; null means the server could not verify it. */
    val valuationMinor: Long? = null,
    val projectionBranchId: String? = null,
    val pendingLocalId: String? = null,
    val rejectedError: String? = null,
    val pendingDelete: Boolean = false,
    val hasQueuedDelete: Boolean = false,
    val localWriteId: String? = null,
    val createConfirmationPending: Boolean = false,
) {
    val isLow: Boolean get() = currentQty < reorderThreshold
    val stockValueMinor: Long? get() = valuationMinor
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
    val createConfirmationPending: Boolean = false,
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
    val ingredientsLoaded: Boolean = false,
    val suppliersLoaded: Boolean = false,
    val syncing: Boolean = false,
    val refreshError: String? = null,
    val ingredients: List<IngredientRow> = emptyList(),
    val suppliers: List<SupplierRow> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val branchesLoaded: Boolean = false,
    val branchesError: String? = null,
    val branchId: String? = null,
    val tab: InventoryTab = InventoryTab.INGREDIENTS,
    val selectedSku: String? = null,
    val batches: List<BatchCacheEntity> = emptyList(),
    val batchesLoading: Boolean = false,
    val batchesError: String? = null,
    val pendingGrns: List<PendingGrnRow> = emptyList(),
    val pendingAdjustments: List<PendingAdjustmentRow> = emptyList(),
    val recipeMenuItems: List<MenuItemEntity> = emptyList(),
    val selectedRecipeMenuItemId: String? = null,
    val recipes: List<Recipe> = emptyList(),
    val recipesLoading: Boolean = false,
    val recipesError: String? = null,
    val busy: Boolean = false,
    val dialog: InventoryDialog? = null,
    /** Error shown inside the open dialog, next to the button that caused it. */
    val formError: String? = null,
    val notice: String? = null,
) {
    val loading: Boolean
        get() = !ingredientsLoaded && !suppliersLoaded &&
            ingredients.isEmpty() && suppliers.isEmpty() && syncing
    val couldNotLoad: Boolean
        get() = !ingredientsLoaded && !suppliersLoaded &&
            ingredients.isEmpty() && suppliers.isEmpty() && !syncing
    val ingredientsUnavailable: Boolean
        get() = !ingredientsLoaded && ingredients.isEmpty() && !syncing
    val suppliersUnavailable: Boolean
        get() = !suppliersLoaded && suppliers.isEmpty() && !syncing

    /** Lowest stock relative to its reorder line first — the order to act in. */
    val sortedIngredients: List<IngredientRow>
        get() = ingredients.sortedBy {
            if (it.reorderThreshold > 0) it.currentQty / it.reorderThreshold else 99.0
        }

    val restockPriority: List<IngredientRow>
        get() = sortedIngredients.filter { it.reorderThreshold > 0 && it.isLow }.take(6)

    val stockValueMinor: Long?
        get() {
            val persisted = ingredients.filterNot(IngredientRow::isUnsyncedDraft)
            if (persisted.any { it.stockValueMinor == null }) return null
            return persisted.sumOf { it.stockValueMinor ?: 0L }
        }
    val lowCount: Int get() = ingredients.count { it.isLow }
    val selected: IngredientRow? get() = ingredients.firstOrNull { it.sku == selectedSku }

    /** Only ingredients/suppliers that already have a real server id — a GRN or adjustment can't reference a draft that hasn't synced yet. */
    val syncedIngredients: List<IngredientRow>
        get() = ingredients.filterNot { it.isUnsyncedDraft || it.hasQueuedDelete }
    val syncedSuppliers: List<SupplierRow>
        get() = suppliers.filterNot { it.isUnsyncedDraft || it.hasQueuedDelete }
    val selectedRecipeMenuItem: MenuItemEntity?
        get() = recipeMenuItems.firstOrNull { it.id == selectedRecipeMenuItemId }
    val activeRecipe: Recipe? get() = recipes.firstOrNull { it.isActive }
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
    private val branchesLoaded = MutableStateFlow(false)
    private val branchesError = MutableStateFlow<String?>(null)
    private val myProfile = MutableStateFlow<MeResponse?>(null)
    private val tab = MutableStateFlow(InventoryTab.INGREDIENTS)
    private val selectedSku = MutableStateFlow<String?>(null)
    private val branchId = MutableStateFlow<String?>(null)
    private val dialog = MutableStateFlow<InventoryDialog?>(null)
    private val busy = MutableStateFlow(false)
    private val formError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val batchesLoading = MutableStateFlow(false)
    private val batchesError = MutableStateFlow<String?>(null)
    private val selectedRecipeMenuItemId = MutableStateFlow<String?>(null)
    private val recipes = MutableStateFlow<List<Recipe>>(emptyList())
    private val recipesLoading = MutableStateFlow(false)
    private val recipesError = MutableStateFlow<String?>(null)
    private val inventoryRefreshError = appCtx.sync.resourceRefreshErrors
        .map { it["inventory"] }
        .distinctUntilChanged()
    private val batchRequests = BatchSelectionRequestGuard()
    private var batchLoadJob: Job? = null
    private var recipeLoadJob: Job? = null
    private var batchTargetKey: Pair<String, String?>? = null
    @Volatile private var access = InventoryAccess()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ingredientProjectionMeta = combine(branchId, myProfile, branchesFlow) {
            selected, profile, branches ->
        selected ?: resolveDefaultBranch(branches, profile)
    }.distinctUntilChanged().flatMapLatest { selectedBranch ->
        db.syncMetaDao().observe("ingredients:${selectedBranch ?: "all"}")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<InventoryUiState> = combine(
        combine(
            db.inventoryDao().observeIngredientCache(),
            db.inventoryDao().observeLocalIngredients(),
            db.inventoryDao().observeSupplierCache(),
            db.inventoryDao().observeLocalSuppliers(),
        ) { ic, li, sc, ls -> IngredientsAndSuppliers(ic, li, sc, ls) },
        combine(
            ingredientProjectionMeta,
            db.syncMetaDao().observe("suppliers"),
            pulling,
            inventoryRefreshError,
        ) { ingMeta, supMeta, syncing, refreshError ->
            InventoryLoadState(
                ingredientsLoaded = ingMeta != null,
                suppliersLoaded = supMeta != null,
                syncing = syncing,
                refreshError = refreshError,
            )
        },
        combine(branchesFlow, branchesLoaded, branchesError, branchId, myProfile) {
                branches, loaded, error, selectedBranchId, profile ->
            BranchState(branches, loaded, error, selectedBranchId, profile)
        },
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
        val branches = branchInfo.branches
        val (grns, adjustments) = pendingWrites
        val selectedBranch = branchInfo.selectedBranchId
            ?: resolveDefaultBranch(branches, branchInfo.profile)
        val scopedIngredientCache = data.ingredientCache.filter {
            it.projectionBranchId == selectedBranch
        }
        val ingredientRows = mergeIngredients(scopedIngredientCache, data.localIngredients)
        val supplierRows = mergeSuppliers(data.supplierCache, data.localSuppliers)
        InventoryUiState(
            // Both pulls must have completed at least once — ingredients
            // succeeding while suppliers fails on a first run (e.g. the
            // connection drops mid-refresh, since pullInventoryOnDemand()
            // runs them sequentially) must not show a false "No suppliers
            // yet" on an empty cache that was simply never fetched.
            everSynced = metaAndSyncing.ingredientsLoaded && metaAndSyncing.suppliersLoaded,
            ingredientsLoaded = metaAndSyncing.ingredientsLoaded,
            suppliersLoaded = metaAndSyncing.suppliersLoaded,
            syncing = metaAndSyncing.syncing,
            refreshError = metaAndSyncing.refreshError,
            ingredients = ingredientRows,
            suppliers = supplierRows,
            branches = branches,
            branchesLoaded = branchInfo.loaded,
            branchesError = branchInfo.error,
            branchId = selectedBranch,
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
    }.flowOn(Dispatchers.Default).let { base ->
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
        val selectedBatchProjection = base
            .map { it.selected?.takeIf { row -> !row.isUnsyncedDraft }?.id to it.branchId }
            .distinctUntilChanged()
        val batchesFlow = selectedBatchProjection.flatMapLatest { (id, selectedBranch) ->
            if (id == null) flowOf(emptyList())
            else db.inventoryDao().observeBatchesFor(id, selectedBranch)
        }
        combine(base, batchesFlow, batchesLoading, batchesError) { s, batches, loading, error ->
            s.copy(
                batches = batches,
                batchesLoading = loading,
                batchesError = error,
            )
        }
    }.let { stockState ->
        combine(
            stockState,
            db.menuDao().observeAllItems(),
            selectedRecipeMenuItemId,
            recipes,
            combine(recipesLoading, recipesError) { loading, error -> loading to error },
        ) { s, menuItems, selectedMenuItemId, recipeRows, recipeLoad ->
            s.copy(
                recipeMenuItems = menuItems.filter {
                    it.type.lowercase() in setOf("food", "drink", "dessert", "hookah")
                },
                selectedRecipeMenuItemId = selectedMenuItemId,
                recipes = recipeRows,
                recipesLoading = recipeLoad.first,
                recipesError = recipeLoad.second,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InventoryUiState())

    private data class IngredientsAndSuppliers(
        val ingredientCache: List<IngredientCacheEntity>,
        val localIngredients: List<LocalIngredientEntity>,
        val supplierCache: List<SupplierCacheEntity>,
        val localSuppliers: List<LocalSupplierEntity>,
    )

    private data class InventoryLoadState(
        val ingredientsLoaded: Boolean,
        val suppliersLoaded: Boolean,
        val syncing: Boolean,
        val refreshError: String?,
    )

    private data class BranchState(
        val branches: List<Branch>,
        val loaded: Boolean,
        val error: String?,
        val selectedBranchId: String?,
        val profile: MeResponse?,
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
        retry()
        viewModelScope.launch {
            state.map {
                it.selected?.takeIf { row -> !row.isUnsyncedDraft }?.id to it.branchId
            }
                .distinctUntilChanged()
                .collect { (ingredientId, selectedBranch) ->
                    updateBatchTarget(ingredientId, selectedBranch)
                }
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            myProfile.value = appCtx.shiftCache.cachedProfile()?.let {
                runCatching { ApiClient.json.decodeFromString(MeResponse.serializer(), it) }.getOrNull()
            }
            ensureSelectedBranch(branchesFlow.value)
        }
    }

    private fun loadBranches() {
        viewModelScope.launch {
            db.reportSnapshotDao().cached<List<Branch>>(BRANCHES_CACHE_KEY)?.let { (cached, _) ->
                branchesFlow.value = cached
                branchesLoaded.value = true
                ensureSelectedBranch(cached)
            }
            try {
                lateinit var fresh: List<Branch>
                val committed = appCtx.cacheIsolation.fetchAndCommitScoped(
                    fetch = { api.branches() },
                    store = {
                        fresh = it
                        db.reportSnapshotDao().store(BRANCHES_CACHE_KEY, it)
                    },
                )
                if (!committed) return@launch
                branchesFlow.value = fresh
                branchesLoaded.value = true
                ensureSelectedBranch(fresh)
                branchesError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cached branches (if any) stay showing, but a first-load empty
                // picker must never masquerade as a real empty branch list.
                branchesError.value = if (branchesLoaded.value) {
                    "Could not refresh branches. Saved branches are still shown; try Refresh again."
                } else {
                    "Could not load branches. Stock receipts and adjustments need a branch; check the connection and try Refresh again."
                }
            }
        }
    }

    private fun ensureSelectedBranch(branches: List<Branch>) {
        val current = branchId.value
        if (current != null && branches.any { it.id == current }) return
        val selected = resolveDefaultBranch(branches, myProfile.value) ?: return
        selectBranch(selected)
    }

    private fun resolveDefaultBranch(branches: List<Branch>, profile: MeResponse?): String? {
        val mine = branches.firstOrNull { it.id == profile?.branchId }
        return (mine ?: branches.firstOrNull())?.id
    }

    // -------------------------------------------------------------- loading

    fun retry() {
        loadBranches()
        appCtx.sync.requestSync()
        pulling.value = true
        viewModelScope.launch {
            try {
                val selectedBranch = branchId.value
                if (selectedBranch == null) appCtx.sync.refresh("inventory")
                else appCtx.sync.pullInventoryForBranch(selectedBranch)
            } finally {
                pulling.value = false
            }
        }
    }

    fun selectTab(t: InventoryTab) {
        if (t == InventoryTab.RECIPES && !access.canManageCosting) {
            notice.value = "Recipe costing is protected-owner only on Android. Use the web ERP for manager configuration."
            return
        }
        tab.value = t
        if (t == InventoryTab.RECIPES && selectedRecipeMenuItemId.value == null) {
            state.value.recipeMenuItems.firstOrNull()?.let(::selectRecipeMenuItem)
        }
    }

    fun selectBranch(id: String) {
        val selected = id.trim()
        if (selected.isEmpty() || branchId.value == selected) return
        if (branchesLoaded.value && branchesFlow.value.none { it.id == selected }) {
            notice.value = "That branch is not available to this account. Refresh branches and try again."
            return
        }
        branchId.value = selected
        batchesError.value = null
        selectedSku.value = null
        pulling.value = true
        viewModelScope.launch {
            try {
                appCtx.sync.pullInventoryForBranch(selected)
            } finally {
                pulling.value = false
            }
        }
    }

    fun select(row: IngredientRow?) {
        selectedSku.value = row?.sku
        updateBatchTarget(row?.takeIf { !it.isUnsyncedDraft }?.id, branchId.value)
    }

    fun retryBatches() {
        val id = state.value.selected?.takeIf { !it.isUnsyncedDraft }?.id ?: return
        updateBatchTarget(id, state.value.branchId, force = true)
    }

    private fun updateBatchTarget(
        ingredientId: String?,
        selectedBranchId: String?,
        force: Boolean = false,
    ) {
        val nextTarget = ingredientId?.let { it to selectedBranchId }
        if (!force && nextTarget == batchTargetKey) return
        val previousTarget = batchTargetKey
        batchTargetKey = nextTarget
        batchLoadJob?.cancel()
        if (previousTarget != null && previousTarget != nextTarget) {
            appCtx.sync.clearActiveBatchTarget(previousTarget.first, previousTarget.second)
        }
        val request = batchRequests.begin(ingredientId, selectedBranchId)
        batchesError.value = null
        if (ingredientId == null) {
            batchesLoading.value = false
            return
        }

        batchesLoading.value = true
        batchLoadJob = viewModelScope.launch {
            try {
                val result = appCtx.sync.pullBatchesFor(ingredientId, selectedBranchId)
                if (!batchRequests.isCurrent(request)) return@launch
                batchesError.value = when (result) {
                    is ResourceRefreshResult.Failed -> result.userMessage
                    is ResourceRefreshResult.Skipped ->
                        "Batches are unavailable for this account. Ask a manager to check Inventory access."
                    is ResourceRefreshResult.Refreshed -> null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (batchRequests.isCurrent(request)) batchesLoading.value = false
            }
        }
    }

    override fun onCleared() {
        batchLoadJob?.cancel()
        recipeLoadJob?.cancel()
        appCtx.sync.clearActiveBatchTarget(batchTargetKey?.first, batchTargetKey?.second)
        super.onCleared()
    }

    fun updateAccess(next: InventoryAccess) {
        access = next
        if (!next.canManageCosting && tab.value == InventoryTab.RECIPES) {
            tab.value = InventoryTab.INGREDIENTS
            selectedRecipeMenuItemId.value = null
            recipes.value = emptyList()
            dialog.value = null
        }
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canManageInventory) {
        notice.value = VIEW_ONLY_MESSAGE
    }

    private fun requireCostingWrite(): Boolean = authorizeAction(access.canManageCosting) {
        notice.value =
            "Recipe costing is protected-owner only on Android. A manager can still configure it in the web ERP."
    }

    fun openDialog(d: InventoryDialog) {
        val costingDialog = d is InventoryDialog.RecipeCreate ||
            d is InventoryDialog.RecipeLineForm ||
            d is InventoryDialog.ConfirmDeleteRecipe ||
            d is InventoryDialog.ConfirmDeleteRecipeLine
        if (costingDialog) {
            if (!requireCostingWrite()) return
        } else if (!requireWrite()) return
        when {
            d is InventoryDialog.IngredientForm && d.editing == null && !state.value.ingredientsLoaded -> {
                notice.value =
                    "Ingredients have not loaded yet. Refresh before creating one so an existing ingredient is not duplicated."
                return
            }
            d is InventoryDialog.SupplierForm && d.editing == null && !state.value.suppliersLoaded -> {
                notice.value =
                    "Suppliers have not loaded yet. Refresh before creating one so an existing supplier is not duplicated."
                return
            }
        }
        dialog.value = d
        formError.value = null
    }

    fun closeDialog() { dialog.value = null; formError.value = null }

    fun dismissNotice() { notice.value = null }

    fun showFormError(message: String) { formError.value = message }

    // --------------------------------------------------------------- recipes

    fun selectRecipeMenuItem(item: MenuItemEntity) {
        if (!requireCostingWrite()) return
        if (selectedRecipeMenuItemId.value == item.id && recipes.value.isNotEmpty()) return
        selectedRecipeMenuItemId.value = item.id
        loadRecipes(item.id)
    }

    fun retryRecipes() {
        val menuItemId = selectedRecipeMenuItemId.value ?: return
        loadRecipes(menuItemId)
    }

    private fun loadRecipes(menuItemId: String) {
        recipeLoadJob?.cancel()
        recipesLoading.value = true
        recipesError.value = null
        recipeLoadJob = viewModelScope.launch {
            try {
                lateinit var fresh: List<Recipe>
                val committed = appCtx.cacheIsolation.fetchAndCommitScoped(
                    fetch = { api.recipes(menuItemId) },
                    store = { fresh = it },
                )
                if (committed && selectedRecipeMenuItemId.value == menuItemId) {
                    recipes.value = fresh
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (selectedRecipeMenuItemId.value == menuItemId) {
                    recipesError.value =
                        "Could not load this recipe. Recipe changes need a live connection; no stock link was changed."
                }
            } finally {
                if (selectedRecipeMenuItemId.value == menuItemId) recipesLoading.value = false
            }
        }
    }

    fun createRecipe(
        menuItem: MenuItemEntity,
        name: String,
        yieldQty: Double,
        lines: List<RecipeLineBody>,
    ) = costingMutate(menuItem.id, "Recipe linked to ${menuItem.name}") {
        api.createRecipe(
            RecipeCreateBody(
                menuItemId = menuItem.id,
                name = name.trim(),
                yieldQty = yieldQty,
                lines = lines,
            ),
        )
    }

    fun saveRecipeLine(recipe: Recipe, editing: RecipeLine?, body: RecipeLineBody) =
        costingMutate(recipe.menuItemId, "Recipe line saved") {
            if (editing == null) api.addRecipeLine(recipe.id, body)
            else api.updateRecipeLine(
                recipe.id,
                editing.id,
                RecipeLineUpdateBody(body.ingredientId, body.qty, body.wastagePct),
            )
        }

    fun deleteRecipe(recipe: Recipe) = costingMutate(recipe.menuItemId, "Recipe deactivated") {
        api.deleteRecipe(recipe.id)
    }

    fun deleteRecipeLine(recipe: Recipe, line: RecipeLine) =
        costingMutate(recipe.menuItemId, "Recipe line removed") {
            api.deleteRecipeLine(recipe.id, line.id)
        }

    private fun costingMutate(
        menuItemId: String,
        successMessage: String,
        request: suspend () -> Unit,
    ) {
        if (!requireCostingWrite() || busy.value) return
        if (appCtx.cacheIsolation.currentLease() == null) {
            formError.value = "Sign in again before changing recipe costing."
            return
        }
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                request()
                dialog.value = null
                notice.value = successMessage
                loadRecipes(menuItemId)
                appCtx.sync.requestSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                formError.value =
                    "Could not save the recipe. Check the connection and values, then try again. Nothing was queued offline."
            } finally {
                busy.value = false
            }
        }
    }

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
            queuedNotice("$name added")
        } else if (editing.isUnsyncedDraft) {
            val localId = editing.localWriteId
                ?: throw CreateConfirmationPending("This ingredient draft is no longer available. Refresh and try again.")
            val existing = dao.getLocalIngredient(localId)
                ?: throw CreateConfirmationPending("This ingredient draft changed. Refresh and try again.")
            val changed = dao.updateMutableIngredientCreate(
                localId = localId,
                expectedVersion = existing.version,
                sku = sku.trim(),
                name = name.trim(),
                baseUnit = baseUnit,
                reorderThreshold = reorderThreshold,
                reorderQty = reorderQty,
            )
            if (changed == 0) {
                throw CreateConfirmationPending(
                    "This ingredient may already have reached the server. Its original details are locked until confirmation to prevent a duplicate; refresh and try again.",
                )
            }
            queuedNotice("$name saved")
        } else {
            val serverId = editing.id
            val existingPending = editing.localWriteId?.let { dao.getLocalIngredient(it) }
            val local = (existingPending ?: LocalIngredientEntity(
                localId = editing.localWriteId ?: UUID.randomUUID().toString(),
                serverId = serverId,
                createdAtMillis = System.currentTimeMillis(),
            )).copy(
                // IngredientUpdate has no SKU field; server-backed edits keep
                // the immutable SKU only in the read cache.
                sku = null,
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
            queuedNotice("$name saved")
        }
    }

    fun deleteIngredient(ingredient: IngredientRow) = localMutate {
        val dao = db.inventoryDao()
        if (ingredient.isUnsyncedDraft) {
            val localId = ingredient.localWriteId
                ?: throw CreateConfirmationPending("This ingredient draft is no longer available. Refresh and try again.")
            if (dao.deleteMutableIngredientCreate(localId) == 0) {
                throw CreateConfirmationPending(
                    "This ingredient may already have reached the server. It cannot be removed until confirmation, which prevents it from reappearing or being created twice.",
                )
            }
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
        queuedNotice(
            "${ingredient.name} removal saved",
            "Its batches stay in the audit trail.",
        )
    }

    /** Abandons a queued delete, whether still pending or already rejected — mirrors StaffViewModel.cancelPendingRemoval. */
    fun cancelIngredientRemoval(ingredient: IngredientRow) {
        if (!requireWrite()) return
        val localId = ingredient.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var changed = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    val dao = db.inventoryDao()
                    val existing = dao.getLocalIngredient(localId)
                    if (existing != null) {
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
                        changed = true
                    }
                }
            ) return@launch
            if (!changed) return@launch
            notice.value = "Removal cancelled."
            appCtx.sync.requestSync()
        }
    }

    fun retryIngredientSync(ingredient: IngredientRow) {
        if (!requireWrite()) return
        val localId = ingredient.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    queued = db.inventoryDao().retryRejectedIngredient(localId) > 0
                }
            ) return@launch
            if (queued) appCtx.sync.requestSync()
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
            queuedNotice("$name added")
        } else if (editing.isUnsyncedDraft) {
            val localId = editing.localWriteId
                ?: throw CreateConfirmationPending("This supplier draft is no longer available. Refresh and try again.")
            val existing = dao.getLocalSupplier(localId)
                ?: throw CreateConfirmationPending("This supplier draft changed. Refresh and try again.")
            val changed = dao.updateMutableSupplierCreate(
                localId = localId,
                expectedVersion = existing.version,
                name = name.trim(),
                contact = trimmedContact,
                gstin = trimmedGstin,
                paymentTerms = trimmedTerms,
            )
            if (changed == 0) {
                throw CreateConfirmationPending(
                    "This supplier may already have reached the server. Its original details are locked until confirmation to prevent a duplicate; refresh and try again.",
                )
            }
            queuedNotice("$name saved")
        } else {
            val serverId = editing.id
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
            queuedNotice("$name saved")
        }
    }

    fun deleteSupplier(supplier: SupplierRow) = localMutate {
        val dao = db.inventoryDao()
        if (supplier.isUnsyncedDraft) {
            val localId = supplier.localWriteId
                ?: throw CreateConfirmationPending("This supplier draft is no longer available. Refresh and try again.")
            if (dao.deleteMutableSupplierCreate(localId) == 0) {
                throw CreateConfirmationPending(
                    "This supplier may already have reached the server. It cannot be removed until confirmation, which prevents it from reappearing or being created twice.",
                )
            }
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
        queuedNotice("${supplier.name} removal saved")
    }

    fun cancelSupplierRemoval(supplier: SupplierRow) {
        if (!requireWrite()) return
        val localId = supplier.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var changed = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    val dao = db.inventoryDao()
                    val existing = dao.getLocalSupplier(localId)
                    if (existing != null) {
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
                        changed = true
                    }
                }
            ) return@launch
            if (!changed) return@launch
            notice.value = "Removal cancelled."
            appCtx.sync.requestSync()
        }
    }

    fun retrySupplierSync(supplier: SupplierRow) {
        if (!requireWrite()) return
        val localId = supplier.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    queued = db.inventoryDao().retryRejectedSupplier(localId) > 0
                }
            ) return@launch
            if (queued) appCtx.sync.requestSync()
        }
    }

    // -------------------------------------------------------------------- GRN

    fun postGrn(
        supplierId: String,
        branchId: String,
        invoiceNo: String,
        supplierInvoiceAmountMinor: Long?,
        notesText: String,
        lines: List<GrnLineBody>,
    ) {
        if (!requireWrite()) return
        val current = state.value
        val computedTotal = grnWireReceiptTotalMinor(lines)
        when {
            branchId != current.branchId || current.branches.none { it.id == branchId } ->
                showFormError("The selected branch changed or is unavailable. Close this receipt and try again.")
            current.syncedSuppliers.none { it.id == supplierId } ->
                showFormError("The selected supplier is no longer available. Refresh Inventory and try again.")
            lines.isEmpty() -> showFormError("Add at least one receipt line.")
            lines.any { line -> current.syncedIngredients.none { it.id == line.ingredientId } } ->
                showFormError("A receipt ingredient is no longer available. Refresh Inventory and try again.")
            computedTotal == null ->
                showFormError("Check every quantity and unit cost. This receipt could not be valued safely.")
            invoiceNo.isNotBlank() && supplierInvoiceAmountMinor == null ->
                showFormError("Enter the supplier invoice total, or clear the invoice number.")
            invoiceNo.isBlank() && supplierInvoiceAmountMinor != null ->
                showFormError("Enter the supplier invoice number, or clear the invoice total.")
            supplierInvoiceAmountMinor != null && supplierInvoiceAmountMinor != computedTotal ->
                showFormError("The supplier invoice total no longer matches the capitalised line total.")
            else -> localMutate {
                val localId = UUID.randomUUID().toString()
                db.inventoryDao().captureGrn(
                    grn = LocalGrnEntity(
                        localId = localId, branchId = branchId, supplierId = supplierId,
                        supplierInvoiceNo = invoiceNo.trim().ifBlank { null },
                        supplierInvoiceAmountMinor = supplierInvoiceAmountMinor,
                        // This absolute instant is frozen before any network attempt.
                        // Retrying tomorrow must never move yesterday's receipt into
                        // a different accounting day. Back-dating remains a deliberate
                        // web-ERP workflow where company timezone can be reviewed.
                        receivedAt = Instant.now().toString(),
                        notes = notesText.trim().ifBlank { null },
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                    lines = lines.map {
                        LocalGrnLineEntity(
                            grnLocalId = localId, ingredientId = it.ingredientId,
                            qty = it.qty, unitCostMinor = it.unitCostMinor,
                            expiresAt = it.expiresAt, lotCode = it.lotCode,
                        )
                    },
                )
                queuedNotice("Stock receipt saved")
            }
        }
    }

    fun retryGrn(localId: String) {
        if (!requireWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.inventoryDao().retryGrn(localId)
                }
            ) return@launch
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
    ) {
        if (!requireWrite()) return
        if (!isSupportedAdjustmentType(type)) {
            showFormError(TRANSFER_UNAVAILABLE_MESSAGE)
            return
        }
        val current = state.value
        when {
            branchId != current.branchId || current.branches.none { it.id == branchId } -> {
                showFormError("The selected branch changed or is unavailable. Close this adjustment and try again.")
                return
            }
            !qty.isFinite() || qty == 0.0 -> {
                showFormError("Enter a non-zero adjustment quantity.")
                return
            }
            current.syncedIngredients.none { it.id == ingredient.id } -> {
                showFormError("This ingredient is no longer available. Refresh Inventory and try again.")
                return
            }
        }
        localMutate {
            val delta = adjustmentDelta(type, qty)
            db.inventoryDao().insertAdjustment(
                LocalAdjustmentEntity(
                    localId = UUID.randomUUID().toString(),
                    ingredientId = ingredient.id, branchId = branchId,
                    qtyDelta = delta, type = type, note = note.trim().ifBlank { null },
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
            queuedNotice("Stock adjustment saved")
        }
    }

    fun retryAdjustment(localId: String) {
        if (!requireWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.inventoryDao().retryAdjustment(localId)
                }
            ) return@launch
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
        if (!requireWrite()) return
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: run {
            formError.value = "Sign in again before saving this inventory change."
            return
        }
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                var message = ""
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        message = block()
                    }
                ) return@launch
                busy.value = false
                dialog.value = null
                formError.value = null
                notice.value = message
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CreateConfirmationPending) {
                busy.value = false
                formError.value = e.message
            } catch (e: Exception) {
                busy.value = false
                formError.value = "Could not save this locally: ${e.message}"
            }
        }
    }

    /**
     * Local-first writes are safe both online and offline, but the feedback
     * must not tell an online cashier to wait for a connection they already
     * have. The authoritative outcome is still shown by the pending/rejected
     * row after SyncEngine processes the outbox.
     */
    private fun queuedNotice(action: String, detail: String? = null): String {
        val delivery = if (appCtx.connectivity.online.value) {
            "Confirming with the server now."
        } else {
            "Saved on this tablet. It will sync when the connection returns."
        }
        return listOfNotNull("$action.", detail, delivery).joinToString(" ")
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
            valuationMinor = matchedCache?.valuationMinor,
            projectionBranchId = matchedCache?.projectionBranchId,
            pendingLocalId = if (row.state != IngredientWriteState.REJECTED) row.localId else null,
            rejectedError = if (row.state == IngredientWriteState.REJECTED) row.lastError else null,
            // Same REJECTED guard Phase 8 had to add to Staff after missing
            // it the first time — a rejected delete must fall through to the
            // rejection banner, not claim forever that it's still pending.
            pendingDelete = if (row.state != IngredientWriteState.REJECTED) row.pendingDelete else false,
            hasQueuedDelete = row.pendingDelete,
            localWriteId = row.localId,
            createConfirmationPending =
                row.serverId == null && row.state == IngredientWriteState.CREATE_ATTEMPTED,
        )
    }
    for (c in cache) {
        if (c.id in overriddenServerIds) continue
        result += IngredientRow(
            id = c.id, sku = c.sku, name = c.name, baseUnit = c.baseUnit,
            currentQty = c.currentQty, reorderThreshold = c.reorderThreshold,
            reorderQty = c.reorderQty, avgCostMinor = c.avgCostMinor,
            valuationMinor = c.valuationMinor, projectionBranchId = c.projectionBranchId,
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
            createConfirmationPending =
                row.serverId == null && row.state == SupplierWriteState.CREATE_ATTEMPTED,
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
