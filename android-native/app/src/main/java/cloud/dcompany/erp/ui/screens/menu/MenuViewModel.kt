package cloud.dcompany.erp.ui.screens.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.MenuAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.auth.PricingLock
import cloud.dcompany.erp.core.db.LocalMenuCategoryEntity
import cloud.dcompany.erp.core.db.LocalMenuItemEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuWriteState
import cloud.dcompany.erp.core.money.minorToRupeesInput
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val LOCAL_PREFIX = "local:"
val ITEM_TYPES = listOf("food", "drink", "dessert", "gaming", "event", "hookah", "streaming")

// ---------------------------------------------------------------- display

data class CategoryRow(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val pendingLocalId: String? = null,
    val rejectedError: String? = null,
    val localWriteId: String? = null,
)

data class ItemRow(
    val id: String,
    val categoryId: String,
    val sku: String,
    val type: String,
    val name: String,
    val description: String?,
    val basePriceMinor: Long,
    val taxRate: Double,
    val hsnCode: String?,
    val priceIncludesTax: Boolean,
    val isAvailable: Boolean,
    val detailsPending: Boolean = false,
    val detailsRejected: String? = null,
    val localWriteId: String? = null,
)

// ------------------------------------------------------------------ editors

/** `id == null` -> new category. `isUnsyncedDraft` also covers a still-local create being re-edited — same reasoning as CustomerEditor in Phase 6. */
data class CategoryEditor(val id: String? = null, val name: String = "", val sortOrder: Int = 0) {
    val isNew: Boolean get() = id == null
    val isUnsyncedDraft: Boolean get() = id == null || id.startsWith(LOCAL_PREFIX)
    val valid: Boolean get() = name.isNotBlank()
}

/** Non-price fields only — always a real, already-synced item id. */
data class ItemDetailsEditor(
    val id: String,
    val categoryId: String,
    val name: String,
    val description: String,
    val isAvailable: Boolean,
) {
    val valid: Boolean get() = name.isNotBlank()
}

/** Price fields only, always against a real, already-synced item id — online-only, never queued. */
data class ItemPricingEditor(
    val id: String,
    val basePriceRupees: String,
    val taxRatePercent: String,
    val hsnCode: String,
    val priceIncludesTax: Boolean,
) {
    val basePriceMinor: Long? get() = parseRupeesToMinor(basePriceRupees)
    val taxRateFraction: Double? get() = taxRatePercent.trim().toDoubleOrNull()?.div(100.0)
    val taxRateValid: Boolean get() = taxRatePercent.isBlank() ||
        (taxRateFraction != null && taxRateFraction!! in 0.0..1.0)
    val valid: Boolean get() = basePriceMinor != null && basePriceMinor!! >= 0 &&
        taxRateValid
}

/** A brand-new item — always online-only (price is required, and pricing can only unlock online). */
data class ItemCreateEditor(
    val categoryId: String,
    val sku: String = "",
    val name: String = "",
    val type: String = ITEM_TYPES.first(),
    val basePriceRupees: String = "",
    val taxRatePercent: String = "",
    val hsnCode: String = "",
    val priceIncludesTax: Boolean = true,
    val description: String = "",
) {
    val basePriceMinor: Long? get() = parseRupeesToMinor(basePriceRupees)
    val taxRateFraction: Double get() = taxRatePercent.trim().toDoubleOrNull()?.div(100.0) ?: 0.0
    val taxRateValid: Boolean get() = taxRatePercent.isBlank() ||
        (taxRatePercent.trim().toDoubleOrNull()?.div(100.0)?.let { it in 0.0..1.0 } == true)
    val valid: Boolean get() = sku.isNotBlank() && name.isNotBlank() &&
        basePriceMinor != null && basePriceMinor!! >= 0 && taxRateValid &&
        !categoryId.startsWith("local:")
}

/**
 * Every piece of UI-only state (never derived from Room) in one flow,
 * updated via `.update { it.copy(...) }` — deliberately not split into many
 * independent flows the way Customers/Analytics do it: this screen has too
 * many distinct dialogs (category editor, item details editor, item
 * pricing editor, item create editor, the pricing-unlock gate) for that to
 * stay under kotlinx.coroutines combine()'s 5-flow typed-overload cap
 * without several levels of Pair/Triple nesting that would be harder to
 * follow than one consolidated state class.
 */
private data class EditingState(
    val selectedCategoryName: String? = null,
    val categoryEditor: CategoryEditor? = null,
    val categorySaving: Boolean = false,
    val categorySaveError: String? = null,
    val itemDetailsEditor: ItemDetailsEditor? = null,
    val itemDetailsSaving: Boolean = false,
    val itemDetailsSaveError: String? = null,
    val itemPricingEditor: ItemPricingEditor? = null,
    val itemPricingSaving: Boolean = false,
    val itemPricingSaveError: String? = null,
    val itemCreateEditor: ItemCreateEditor? = null,
    val itemCreateSaving: Boolean = false,
    val itemCreateSaveError: String? = null,
    val showPricingUnlock: Boolean = false,
    /** What to do once PricingUnlockDialog reports success. */
    val pendingAfterUnlock: (() -> Unit)? = null,
    val loadError: String? = null,
    val notice: String? = null,
)

data class MenuUiState(
    val everSynced: Boolean = false,
    val syncing: Boolean = false,
    val categories: List<CategoryRow> = emptyList(),
    val selectedCategoryName: String? = null,
    val allItems: List<ItemRow> = emptyList(),
    val categoryEditor: CategoryEditor? = null,
    val categorySaving: Boolean = false,
    val categorySaveError: String? = null,
    val itemDetailsEditor: ItemDetailsEditor? = null,
    val itemDetailsSaving: Boolean = false,
    val itemDetailsSaveError: String? = null,
    val itemPricingEditor: ItemPricingEditor? = null,
    val itemPricingSaving: Boolean = false,
    val itemPricingSaveError: String? = null,
    val itemCreateEditor: ItemCreateEditor? = null,
    val itemCreateSaving: Boolean = false,
    val itemCreateSaveError: String? = null,
    val showPricingUnlock: Boolean = false,
    val loadError: String? = null,
    val notice: String? = null,
) {
    val selectedCategory: CategoryRow? get() = categories.firstOrNull { it.name == selectedCategoryName }
    val visibleItems: List<ItemRow>
        get() {
            val cat = selectedCategory ?: return emptyList()
            return allItems.filter { it.categoryId == cat.id }.sortedBy { it.name }
        }
    val loading: Boolean get() = !everSynced && categories.isEmpty() && syncing
    val couldNotLoad: Boolean get() = !everSynced && categories.isEmpty() && !syncing
}

/**
 * Categories: fully offline-capable via the two-table pattern (see
 * LocalMenuCategoryEntity). Items: the read cache is offline, but only
 * DETAILS edits (name/description/category/availability) queue offline —
 * creating an item, and editing its price/tax/HSN, are both always-online
 * synchronous actions gated behind [PricingLock], because a fresh
 * pricing-unlock can only ever happen with a live connection.
 */
class MenuViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<MenuApi>()

    private val editing = MutableStateFlow(EditingState())
    private val pullingMenu = MutableStateFlow(false)
    @Volatile private var access = MenuAccess()

    val state: StateFlow<MenuUiState> = combine(
        combine(db.menuDao().observeCategories(), db.menuWriteDao().observeLocalCategories(), ::Pair),
        combine(db.menuDao().observeAllItems(), db.menuWriteDao().observeLocalItems(), ::Pair),
        combine(db.syncMetaDao().observe("menu"), pullingMenu, ::Pair),
        editing,
    ) { categoriesAndLocal, itemsAndLocal, metaAndPulling, ed ->
        val mergedCategories = mergeCategories(categoriesAndLocal.first, categoriesAndLocal.second)
        val mergedItems = mergeItems(itemsAndLocal.first, itemsAndLocal.second)
        MenuUiState(
            everSynced = metaAndPulling.first != null,
            syncing = metaAndPulling.second,
            categories = mergedCategories,
            selectedCategoryName = ed.selectedCategoryName ?: mergedCategories.firstOrNull()?.name,
            allItems = mergedItems,
            categoryEditor = ed.categoryEditor,
            categorySaving = ed.categorySaving,
            categorySaveError = ed.categorySaveError,
            itemDetailsEditor = ed.itemDetailsEditor,
            itemDetailsSaving = ed.itemDetailsSaving,
            itemDetailsSaveError = ed.itemDetailsSaveError,
            itemPricingEditor = ed.itemPricingEditor,
            itemPricingSaving = ed.itemPricingSaving,
            itemPricingSaveError = ed.itemPricingSaveError,
            itemCreateEditor = ed.itemCreateEditor,
            itemCreateSaving = ed.itemCreateSaving,
            itemCreateSaveError = ed.itemCreateSaveError,
            showPricingUnlock = ed.showPricingUnlock,
            loadError = ed.loadError,
            notice = ed.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MenuUiState())

    init {
        retry()
    }

    fun retry() {
        editing.update { it.copy(loadError = null) }
        pullingMenu.value = true
        viewModelScope.launch {
            try {
                // This screen needs one resource, not the entire outbox
                // pipeline. An unrelated shift/refund/settings failure must
                // never prevent staff from downloading a valid menu.
                appCtx.sync.refresh("menu")
                if (db.syncMetaDao().observe("menu").first() == null) {
                    editing.update {
                        it.copy(
                            loadError = appCtx.sync.lastError.value
                                ?: "The menu could not be downloaded. Check the connection and try again.",
                        )
                    }
                }
            } finally {
                pullingMenu.value = false
            }
        }
    }

    fun selectCategory(name: String) {
        editing.update { it.copy(selectedCategoryName = name) }
    }

    fun dismissNotice() {
        editing.update { it.copy(notice = null) }
    }

    fun updateAccess(next: MenuAccess) {
        access = next
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canManageMenu) {
        editing.update { it.copy(notice = VIEW_ONLY_MESSAGE) }
    }

    // ------------------------------------------------------------ category

    fun startCreateCategory() {
        if (!requireWrite()) return
        editing.update { it.copy(categoryEditor = CategoryEditor(), categorySaveError = null) }
    }

    fun startEditCategory(row: CategoryRow) {
        if (!requireWrite()) return
        editing.update {
            it.copy(
                categoryEditor = CategoryEditor(id = row.id, name = row.name, sortOrder = row.sortOrder),
                categorySaveError = null,
            )
        }
    }

    fun categoryEditorChanged(next: CategoryEditor) {
        editing.update { it.copy(categoryEditor = next) }
    }

    fun cancelCategoryEdit() {
        editing.update { it.copy(categoryEditor = null, categorySaveError = null) }
    }

    fun saveCategory() {
        if (!requireWrite()) return
        val ed = editing.value.categoryEditor ?: return
        if (editing.value.categorySaving || !ed.valid) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        editing.update { it.copy(categorySaving = true, categorySaveError = null) }
        viewModelScope.launch {
            try {
                val dao = db.menuWriteDao()
                val now = System.currentTimeMillis()
                var savedName: String? = null
                var missingDraft = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        savedName = when {
                            ed.isNew -> {
                                val local = LocalMenuCategoryEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = null,
                                    name = ed.name.trim(),
                                    sortOrder = ed.sortOrder,
                                    createdAtMillis = now,
                                )
                                dao.upsertLocalCategory(local)
                                local.name
                            }
                            ed.id!!.startsWith(LOCAL_PREFIX) -> {
                                val localId = ed.id.removePrefix(LOCAL_PREFIX)
                                val existing = dao.getLocalCategory(localId)
                                if (existing == null) {
                                    missingDraft = true
                                    null
                                } else {
                                    val amended = existing.copy(
                                        name = ed.name.trim(),
                                        sortOrder = ed.sortOrder,
                                        state = MenuWriteState.PENDING,
                                        lastError = null,
                                        version = existing.version + 1,
                                    )
                                    dao.upsertLocalCategory(amended)
                                    amended.name
                                }
                            }
                            else -> {
                                val serverId = ed.id
                                val existingPending = dao.pendingCategoryForServerId(serverId)
                                val local = (existingPending ?: LocalMenuCategoryEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = serverId,
                                    createdAtMillis = now,
                                )).copy(
                                    name = ed.name.trim(),
                                    sortOrder = ed.sortOrder,
                                    state = MenuWriteState.PENDING,
                                    lastError = null,
                                    version = (existingPending?.version ?: -1) + 1,
                                )
                                dao.upsertLocalCategory(local)
                                local.name
                            }
                        }
                    }
                ) return@launch
                if (missingDraft) {
                    editing.update {
                        it.copy(
                            categorySaving = false,
                            categorySaveError = "This category's local draft is gone — try again.",
                        )
                    }
                    return@launch
                }
                editing.update {
                    it.copy(
                        categoryEditor = null,
                        categorySaving = false,
                        selectedCategoryName = savedName,
                        notice = "Saved — will sync when back online.",
                    )
                }
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(
                        categorySaving = false,
                        categorySaveError = e.message?.takeIf { m -> m.isNotBlank() } ?: "Could not save this category.",
                    )
                }
            }
        }
    }

    fun retryCategorySync(row: CategoryRow) {
        if (!requireWrite()) return
        val localId = row.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.menuWriteDao().getLocalCategory(localId)?.let {
                        db.menuWriteDao().upsertLocalCategory(
                            it.copy(state = MenuWriteState.PENDING, lastError = null, version = it.version + 1),
                        )
                        queued = true
                    }
                }
            ) return@launch
            if (queued) appCtx.sync.requestSync()
        }
    }

    // ----------------------------------------------------------- item details

    fun startEditItemDetails(row: ItemRow) {
        if (!requireWrite()) return
        editing.update {
            it.copy(
                itemDetailsEditor = ItemDetailsEditor(
                    id = row.id,
                    categoryId = row.categoryId,
                    name = row.name,
                    description = row.description.orEmpty(),
                    isAvailable = row.isAvailable,
                ),
                itemDetailsSaveError = null,
            )
        }
    }

    fun itemDetailsChanged(next: ItemDetailsEditor) {
        editing.update { it.copy(itemDetailsEditor = next) }
    }

    fun cancelItemDetailsEdit() {
        editing.update { it.copy(itemDetailsEditor = null, itemDetailsSaveError = null) }
    }

    fun saveItemDetails() {
        if (!requireWrite()) return
        val ed = editing.value.itemDetailsEditor ?: return
        if (editing.value.itemDetailsSaving || !ed.valid) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        editing.update { it.copy(itemDetailsSaving = true, itemDetailsSaveError = null) }
        viewModelScope.launch {
            try {
                val dao = db.menuWriteDao()
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val existingPending = dao.pendingItemForServerId(ed.id)
                        val local = (existingPending ?: LocalMenuItemEntity(
                            localId = UUID.randomUUID().toString(),
                            serverId = ed.id,
                            createdAtMillis = System.currentTimeMillis(),
                        )).copy(
                            categoryId = ed.categoryId,
                            name = ed.name.trim(),
                            description = ed.description.trim().ifBlank { null },
                            isAvailable = ed.isAvailable,
                            state = MenuWriteState.PENDING,
                            lastError = null,
                            version = (existingPending?.version ?: -1) + 1,
                        )
                        dao.upsertLocalItem(local)
                    }
                ) return@launch
                editing.update {
                    it.copy(
                        itemDetailsEditor = null,
                        itemDetailsSaving = false,
                        notice = "Saved — will sync when back online.",
                    )
                }
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(
                        itemDetailsSaving = false,
                        itemDetailsSaveError = e.message?.takeIf { m -> m.isNotBlank() } ?: "Could not save these details.",
                    )
                }
            }
        }
    }

    fun retryItemDetailsSync(row: ItemRow) {
        if (!requireWrite()) return
        val localId = row.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.menuWriteDao().getLocalItem(localId)?.let {
                        db.menuWriteDao().upsertLocalItem(
                            it.copy(state = MenuWriteState.PENDING, lastError = null, version = it.version + 1),
                        )
                        queued = true
                    }
                }
            ) return@launch
            if (queued) appCtx.sync.requestSync()
        }
    }

    // ------------------------------------------------------- item pricing

    /** Always online-only — see the class doc comment. Shows the unlock
     * dialog first if pricing isn't already unlocked. */
    fun startEditItemPricing(row: ItemRow) {
        if (!requireWrite()) return
        val open: () -> Unit = {
            editing.update {
                it.copy(
                    itemPricingEditor = ItemPricingEditor(
                        id = row.id,
                        basePriceRupees = minorToRupeesInput(row.basePriceMinor),
                        taxRatePercent = formatFractionAsPercent(row.taxRate),
                        hsnCode = row.hsnCode.orEmpty(),
                        priceIncludesTax = row.priceIncludesTax,
                    ),
                    itemPricingSaveError = null,
                )
            }
        }
        if (PricingLock.currentToken(appCtx.tokens.currentPricingSession()) != null) {
            open()
        } else {
            editing.update { it.copy(pendingAfterUnlock = open, showPricingUnlock = true) }
        }
    }

    fun itemPricingChanged(next: ItemPricingEditor) {
        editing.update { it.copy(itemPricingEditor = next) }
    }

    fun cancelItemPricingEdit() {
        editing.update { it.copy(itemPricingEditor = null, itemPricingSaveError = null) }
    }

    fun saveItemPricing() {
        if (!requireWrite()) return
        val ed = editing.value.itemPricingEditor ?: return
        if (editing.value.itemPricingSaving || !ed.valid) return
        editing.update { it.copy(itemPricingSaving = true, itemPricingSaveError = null) }
        viewModelScope.launch {
            try {
                api.updateItemPricing(
                    ed.id,
                    ItemPricingUpdateBody(
                        basePriceMinor = ed.basePriceMinor,
                        taxRate = ed.taxRateFraction,
                        hsnCode = ed.hsnCode.trim().ifBlank { null },
                        priceIncludesTax = ed.priceIncludesTax,
                    ),
                )
                editing.update {
                    it.copy(itemPricingEditor = null, itemPricingSaving = false, notice = "Price updated.")
                }
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(
                        itemPricingSaving = false,
                        itemPricingSaveError = (e as? ApiException)?.message ?: "Could not update pricing.",
                    )
                }
            }
        }
    }

    // -------------------------------------------------------- item create

    /** Always online-only — see the class doc comment. */
    fun startCreateItem(categoryId: String) {
        if (!requireWrite()) return
        val open: () -> Unit = {
            editing.update {
                it.copy(itemCreateEditor = ItemCreateEditor(categoryId = categoryId), itemCreateSaveError = null)
            }
        }
        if (PricingLock.currentToken(appCtx.tokens.currentPricingSession()) != null) {
            open()
        } else {
            editing.update { it.copy(pendingAfterUnlock = open, showPricingUnlock = true) }
        }
    }

    fun itemCreateChanged(next: ItemCreateEditor) {
        editing.update { it.copy(itemCreateEditor = next) }
    }

    fun cancelItemCreate() {
        editing.update { it.copy(itemCreateEditor = null, itemCreateSaveError = null) }
    }

    fun saveItemCreate() {
        if (!requireWrite()) return
        val ed = editing.value.itemCreateEditor ?: return
        if (editing.value.itemCreateSaving || !ed.valid) return
        editing.update { it.copy(itemCreateSaving = true, itemCreateSaveError = null) }
        viewModelScope.launch {
            try {
                api.createItem(
                    ItemCreateBody(
                        categoryId = ed.categoryId,
                        sku = ed.sku.trim(),
                        name = ed.name.trim(),
                        type = ed.type,
                        basePriceMinor = ed.basePriceMinor!!,
                        taxRate = ed.taxRateFraction,
                        hsnCode = ed.hsnCode.trim().ifBlank { null },
                        priceIncludesTax = ed.priceIncludesTax,
                        description = ed.description.trim().ifBlank { null },
                    ),
                    "menu-item-create:${UUID.randomUUID()}",
                )
                editing.update {
                    it.copy(itemCreateEditor = null, itemCreateSaving = false, notice = "Item created.")
                }
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                editing.update {
                    it.copy(
                        itemCreateSaving = false,
                        itemCreateSaveError = (e as? ApiException)?.message ?: "Could not create this item.",
                    )
                }
            }
        }
    }

    // --------------------------------------------------------- pricing lock

    fun dismissPricingUnlock() {
        editing.update { it.copy(showPricingUnlock = false, pendingAfterUnlock = null) }
    }

    fun pricingUnlocked() {
        if (!requireWrite()) return
        val action = editing.value.pendingAfterUnlock
        editing.update { it.copy(showPricingUnlock = false, pendingAfterUnlock = null) }
        action?.invoke()
    }
}

/**
 * `String.format("%.2f", ...)` rather than an exact-equality check against
 * a truncated Long — ordinary Double multiplication turns a clean rate like
 * 0.28 into 28.000000000000004, so `percent == percent.toLong().toDouble()`
 * silently fails and displays that raw artifact instead of "28" for a
 * perfectly ordinary GST slab (28% is a real one this app's own item types
 * can be taxed at). Formatting to a fixed 2dp and trimming is exact-enough
 * for a percentage display and immune to this class of drift.
 */
private fun formatFractionAsPercent(fraction: Double): String {
    val formatted = String.format(java.util.Locale.ROOT, "%.2f", fraction * 100)
    return formatted.trimEnd('0').trimEnd('.')
}

// -------------------------------------------------------------------- merge

/**
 * Deliberately does NOT suppress a cache row by name the way
 * CustomersViewModel.mergeCustomers suppresses by phone for a pending
 * create. That works for Customers because phone genuinely is this app's
 * stable identity for a customer — the backend's own upsert-by-phone
 * guarantees a collision really is "the same real entity." A category name
 * carries no such guarantee (nothing enforces uniqueness client-side, and
 * two people can easily type the same name for what they each believe is
 * a new category), and the backend's own fix for this exact phase treats a
 * name collision as a hard, non-mergeable ConflictError, not something to
 * heal into. An earlier version of this function suppressed by name anyway
 * — caught by review before shipping — and the consequence was severe: a
 * pending (or, worse, already-rejected-and-permanently-stuck) local create
 * sharing a name with a real, already-synced category made that real
 * category, and every item under it, silently vanish from this screen
 * entirely. Showing both rows when they happen to collide is the honest
 * behavior: it matches exactly what the server will decide once the
 * pending create actually syncs (a visible, actionable rejection), rather
 * than hiding a real category based on a guess about what the collision
 * means.
 */
private fun mergeCategories(
    cache: List<MenuCategoryEntity>,
    local: List<LocalMenuCategoryEntity>,
): List<CategoryRow> {
    val cacheById = cache.associateBy { it.id }
    val overriddenServerIds = local.mapNotNull { it.serverId }.toSet()
    val result = ArrayList<CategoryRow>(cache.size + local.size)
    for (row in local) {
        val matchedCache = row.serverId?.let { cacheById[it] }
        result += CategoryRow(
            id = row.serverId ?: "$LOCAL_PREFIX${row.localId}",
            name = row.name ?: matchedCache?.name ?: "",
            sortOrder = row.sortOrder ?: matchedCache?.sortOrder ?: 0,
            pendingLocalId = if (row.state != MenuWriteState.REJECTED) row.localId else null,
            rejectedError = if (row.state == MenuWriteState.REJECTED) row.lastError else null,
            localWriteId = row.localId,
        )
    }
    for (c in cache) {
        if (c.id in overriddenServerIds) continue
        result += CategoryRow(id = c.id, name = c.name, sortOrder = c.sortOrder)
    }
    return result.sortedWith(compareBy({ it.sortOrder }, { it.name }))
}

private fun mergeItems(
    cache: List<MenuItemEntity>,
    local: List<LocalMenuItemEntity>,
): List<ItemRow> {
    val cacheById = cache.associateBy { it.id }
    val overriddenIds = local.map { it.serverId }.toSet()
    val result = ArrayList<ItemRow>(cache.size)
    for (row in local) {
        val c = cacheById[row.serverId] ?: continue // the item vanished from cache entirely — nothing sane to show
        result += ItemRow(
            id = c.id,
            categoryId = row.categoryId ?: c.categoryId,
            sku = c.sku,
            type = c.type,
            name = row.name ?: c.name,
            description = row.description ?: c.description,
            basePriceMinor = c.basePriceMinor,
            taxRate = c.taxRate,
            hsnCode = c.hsnCode,
            priceIncludesTax = c.priceIncludesTax,
            isAvailable = row.isAvailable ?: c.isAvailable,
            detailsPending = row.state != MenuWriteState.REJECTED,
            detailsRejected = if (row.state == MenuWriteState.REJECTED) row.lastError else null,
            localWriteId = row.localId,
        )
    }
    for (c in cache) {
        if (c.id in overriddenIds) continue
        result += ItemRow(
            id = c.id, categoryId = c.categoryId, sku = c.sku, type = c.type, name = c.name,
            description = c.description, basePriceMinor = c.basePriceMinor, taxRate = c.taxRate,
            hsnCode = c.hsnCode, priceIncludesTax = c.priceIncludesTax, isAvailable = c.isAvailable,
        )
    }
    return result
}
