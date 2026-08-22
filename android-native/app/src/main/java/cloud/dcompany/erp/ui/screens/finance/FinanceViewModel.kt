package cloud.dcompany.erp.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity
import cloud.dcompany.erp.core.db.LocalAssetEntity
import cloud.dcompany.erp.core.db.LocalCapitalEntryEntity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

private const val PL_CACHE_KEY = "finance_pnl"
private const val METRICS_CACHE_KEY = "finance_metrics"
private const val DISTRIBUTABLE_CACHE_KEY = "finance_distributable"
private const val PARTNERS_CACHE_KEY = "finance_partners"
private const val CATEGORIES_CACHE_KEY = "finance_expense_categories"
private const val BRANCHES_CACHE_KEY = "finance_branches"

/** Which modal is up. Owned by the ViewModel so a rotation does not lose track of which form it was. */
sealed interface FinanceDialog {
    data object ExpenseForm : FinanceDialog
    data object AssetForm : FinanceDialog
    data class CapitalEntryForm(val partner: Partner) : FinanceDialog
}

/** A queued-but-not-yet-synced expense, with its category name resolved for display. */
data class PendingExpenseRow(
    val localId: String,
    val amountMinor: Long,
    val categoryName: String,
    val vendorName: String?,
    val rejected: Boolean,
    val error: String? = null,
)

data class PendingAssetRow(
    val localId: String,
    val name: String,
    val purchaseMinor: Long,
    val rejected: Boolean,
    val error: String? = null,
)

/** A queued-but-not-yet-synced capital movement, with the partner's name resolved for display. */
data class PendingCapitalEntryRow(
    val localId: String,
    val partnerName: String,
    val type: String,
    val amountMinor: Long,
    val rejected: Boolean,
    val error: String? = null,
)

data class FinanceUiState(
    val loading: Boolean = true,
    /** The server's own message, never an HTTP code. */
    val error: String? = null,
    val pl: ProfitAndLoss? = null,
    val metrics: BusinessMetrics? = null,
    val distributable: DistributableProfit? = null,
    val expenses: List<Expense> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val partners: List<Partner> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val pendingExpenses: List<PendingExpenseRow> = emptyList(),
    val pendingAssets: List<PendingAssetRow> = emptyList(),
    val pendingCapitalEntries: List<PendingCapitalEntryRow> = emptyList(),
    val dialog: FinanceDialog? = null,
    val busy: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
) {
    /** True once a load has succeeded; the five report figures always arrive together. */
    val loaded: Boolean get() = pl != null

    val expenseTotalMinor: Long get() = expenses.sumOf { it.amountMinor }

    /**
     * Nothing booked this period. Distinguished from "not loaded yet" so the
     * screen can say *why* it is showing zeros instead of leaving an owner to
     * guess whether the tablet failed or the month genuinely started quiet.
     */
    val periodIdle: Boolean
        get() = pl != null &&
            pl.revenueMinor == 0L && pl.cogsMinor == 0L && pl.expensesMinor == 0L

    fun categoryName(id: String): String = categoryNames[id] ?: "—"
}

/**
 * Finance's read side deliberately mixes two caching strategies. Expenses
 * and assets are genuine offline-created outbox resources (Shape D — see
 * FinanceEntities.kt's class doc), so they use the two-table (cache + local
 * outbox) Room pattern SyncEngine reads and writes, same as Inventory's GRN/
 * adjustments. P&L, metrics, distributable, partners, expense categories and
 * branches are pure reference/aggregate reads with no offline-write concept
 * at all, so they reuse the generic ReportSnapshotEntity stale-while-
 * revalidate cache — the exact same mechanism InventoryViewModel already
 * uses for its own branch picker — instead of a bespoke table per read-only
 * shape.
 *
 * Recording an expense, a partner capital movement, or an asset is
 * create-only here: expense edit/delete, capital-entry void, and any change
 * at all to an asset stay in the web ERP, where that evidence-reference
 * discipline (and, for a void, a written reason) actually lives.
 */
class FinanceViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<FinanceApi>()

    private val pl = MutableStateFlow<ProfitAndLoss?>(null)
    private val metrics = MutableStateFlow<BusinessMetrics?>(null)
    private val distributable = MutableStateFlow<DistributableProfit?>(null)
    private val partners = MutableStateFlow<List<Partner>>(emptyList())
    private val branches = MutableStateFlow<List<Branch>>(emptyList())
    private val categoryNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val loading = MutableStateFlow(true)
    private val loadError = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<FinanceDialog?>(null)
    private val busy = MutableStateFlow(false)
    private val formError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private data class FormState(
        val dialog: FinanceDialog?,
        val busy: Boolean,
        val formError: String?,
        val notice: String?,
    )

    val state: StateFlow<FinanceUiState> = combine(
        combine(pl, metrics, distributable) { p, m, d -> Triple(p, m, d) },
        combine(
            db.financeDao().observeExpenseCache(),
            db.financeDao().observeLocalExpenses(),
        ) { cache, local -> cache to local },
        combine(
            db.financeDao().observeAssetCache(),
            db.financeDao().observeLocalAssets(),
        ) { cache, local -> cache to local },
        combine(partners, branches, categoryNames) { p, b, c -> Triple(p, b, c) },
        combine(
            db.financeDao().observeLocalCapitalEntries(),
            combine(loading, loadError) { l, e -> l to e },
            combine(dialog, busy, formError, notice) { d, bs, fe, n -> FormState(d, bs, fe, n) },
        ) { capitalEntries, loadingAndError, form -> Triple(capitalEntries, loadingAndError, form) },
    ) { plMetricsDistributable, expenseData, assetData, refData, rest ->
        val (p, m, d) = plMetricsDistributable
        val (expenseCache, localExpenses) = expenseData
        val (assetCache, localAssets) = assetData
        val (partnerList, branchList, catNames) = refData
        val (capitalEntries, loadingAndError, form) = rest
        val (isLoading, err) = loadingAndError

        FinanceUiState(
            loading = isLoading,
            error = err,
            pl = p,
            metrics = m,
            distributable = d,
            expenses = expenseCache.map { it.toExpense() },
            assets = assetCache.map { it.toAsset() },
            partners = partnerList,
            branches = branchList,
            categoryNames = catNames,
            pendingExpenses = localExpenses.map { it.toPendingRow(catNames) },
            pendingAssets = localAssets.map { it.toPendingRow() },
            pendingCapitalEntries = capitalEntries.map { it.toPendingRow(partnerList) },
            dialog = form.dialog,
            busy = form.busy,
            formError = form.formError,
            notice = form.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

    init {
        load()
    }

    // -------------------------------------------------------------- loading

    fun load() {
        loading.value = true
        loadError.value = null
        appCtx.sync.requestSync()
        viewModelScope.launch {
            // Cached-first, same "stale but labelled" idiom InventoryViewModel
            // uses for branches — shown immediately, replaced the instant a
            // fresh fetch succeeds.
            db.reportSnapshotDao().cached<ProfitAndLoss>(PL_CACHE_KEY)?.let { (c, _) -> pl.value = c }
            db.reportSnapshotDao().cached<BusinessMetrics>(METRICS_CACHE_KEY)?.let { (c, _) -> metrics.value = c }
            db.reportSnapshotDao().cached<DistributableProfit>(DISTRIBUTABLE_CACHE_KEY)
                ?.let { (c, _) -> distributable.value = c }
            db.reportSnapshotDao().cached<List<Partner>>(PARTNERS_CACHE_KEY)?.let { (c, _) -> partners.value = c }
            db.reportSnapshotDao().cached<Map<String, String>>(CATEGORIES_CACHE_KEY)
                ?.let { (c, _) -> categoryNames.value = c }
            db.reportSnapshotDao().cached<List<Branch>>(BRANCHES_CACHE_KEY)?.let { (c, _) -> branches.value = c }

            try {
                // Expenses/assets refresh through the same on-demand pull
                // SyncEngine already exposes (populates Room, observed
                // reactively above) — run alongside, not before, the direct
                // reads below so a slow report fetch doesn't delay it.
                val financeRefresh = async {
                    try {
                        appCtx.sync.refresh("finance")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best effort — a failed background refresh must not
                        // fail the whole screen load.
                    }
                }
                coroutineScope {
                    val plDeferred = async { api.profitAndLoss() }
                    val metricsDeferred = async { api.metrics() }
                    val distributableDeferred = async { api.distributable() }
                    val partnersDeferred = async { api.partners() }
                    val branchesDeferred = async { api.branches() }
                    val categoriesDeferred = async {
                        try {
                            api.expenseCategories()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }

                    val freshPl = plDeferred.await()
                    val freshMetrics = metricsDeferred.await()
                    val freshDistributable = distributableDeferred.await()
                    val freshPartners = partnersDeferred.await()
                    val freshBranches = branchesDeferred.await()
                    val freshCategoryNames = categoriesDeferred.await().associate { it.id to it.name }

                    pl.value = freshPl
                    metrics.value = freshMetrics
                    distributable.value = freshDistributable
                    partners.value = freshPartners
                    branches.value = freshBranches
                    categoryNames.value = freshCategoryNames

                    db.reportSnapshotDao().store(PL_CACHE_KEY, freshPl)
                    db.reportSnapshotDao().store(METRICS_CACHE_KEY, freshMetrics)
                    db.reportSnapshotDao().store(DISTRIBUTABLE_CACHE_KEY, freshDistributable)
                    db.reportSnapshotDao().store(PARTNERS_CACHE_KEY, freshPartners)
                    db.reportSnapshotDao().store(CATEGORIES_CACHE_KEY, freshCategoryNames)
                    db.reportSnapshotDao().store(BRANCHES_CACHE_KEY, freshBranches)
                }
                financeRefresh.await()
                loading.value = false
                loadError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Keep whatever was already on screen: stale-but-labelled beats
                // an empty screen when the wifi drops mid-refresh.
                loading.value = false
                loadError.value = e.message
            } catch (e: Exception) {
                loading.value = false
                loadError.value = "Could not read the finance figures: ${e.message ?: "unexpected error"}"
            }
        }
    }

    // ------------------------------------------------------------- dialogs

    fun openExpenseForm() {
        dialog.value = FinanceDialog.ExpenseForm
    }

    fun openAssetForm() {
        dialog.value = FinanceDialog.AssetForm
    }

    fun openCapitalEntryForm(partner: Partner) {
        dialog.value = FinanceDialog.CapitalEntryForm(partner)
    }

    fun closeDialog() {
        dialog.value = null
        formError.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }

    // -------------------------------------------------------------- expenses

    fun postExpense(
        branchId: String,
        categoryId: String,
        amountMinor: Long,
        paidVia: String,
        paidAt: String,
        vendorName: String,
        invoiceNo: String,
        note: String,
    ) = localMutate {
        db.financeDao().insertLocalExpense(
            LocalExpenseEntity(
                localId = UUID.randomUUID().toString(),
                branchId = branchId, categoryId = categoryId, supplierId = null,
                amountMinor = amountMinor, paidVia = paidVia, paidAt = paidAt,
                vendorName = vendorName.trim().ifBlank { null },
                invoiceNo = invoiceNo.trim().ifBlank { null },
                note = note.trim().ifBlank { null },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        "Expense queued — will sync when back online."
    }

    fun retryExpense(localId: String) {
        viewModelScope.launch {
            db.financeDao().retryExpense(localId)
            appCtx.sync.requestSync()
        }
    }

    // ---------------------------------------------------------------- assets

    fun postAsset(
        branchId: String,
        name: String,
        type: String,
        purchaseMinor: Long,
        purchaseDate: String,
        usefulLifeMonths: Int,
        salvageMinor: Long,
        notesText: String,
    ) = localMutate {
        db.financeDao().insertLocalAsset(
            LocalAssetEntity(
                localId = UUID.randomUUID().toString(),
                branchId = branchId, name = name.trim(), type = type,
                purchaseMinor = purchaseMinor, purchaseDate = purchaseDate,
                usefulLifeMonths = usefulLifeMonths, salvageMinor = salvageMinor,
                notes = notesText.trim().ifBlank { null },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        "Asset queued — will sync when back online."
    }

    fun retryAsset(localId: String) {
        viewModelScope.launch {
            db.financeDao().retryAsset(localId)
            appCtx.sync.requestSync()
        }
    }

    // -------------------------------------------------------- capital entries

    fun postCapitalEntry(
        partnerId: String,
        type: String,
        amountMinor: Long,
        effectiveAt: String,
        settlementAccount: String,
        sourceRef: String,
        note: String,
    ) = localMutate {
        db.financeDao().insertLocalCapitalEntry(
            LocalCapitalEntryEntity(
                localId = UUID.randomUUID().toString(),
                partnerId = partnerId, type = type, amountMinor = amountMinor,
                effectiveAt = effectiveAt, settlementAccount = settlementAccount,
                sourceRef = sourceRef.trim(), note = note.trim().ifBlank { null },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        "Capital entry queued — will sync when back online."
    }

    fun retryCapitalEntry(localId: String) {
        viewModelScope.launch {
            db.financeDao().retryCapitalEntry(localId)
            appCtx.sync.requestSync()
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * One local (Room) write, then the dialog closes — no network wait, since
     * the whole point is that this succeeds instantly offline too. Same shape
     * as InventoryViewModel.localMutate.
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

private fun ExpenseCacheEntity.toExpense(): Expense = Expense(
    id = id, branchId = branchId, categoryId = categoryId, supplierId = supplierId,
    amountMinor = amountMinor, paidVia = paidVia, paidAt = paidAt,
    vendorName = vendorName, invoiceNo = invoiceNo, note = note,
)

private fun AssetCacheEntity.toAsset(): Asset = Asset(
    id = id, branchId = branchId, name = name, type = type,
    purchaseMinor = purchaseMinor, purchaseDate = purchaseDate,
    usefulLifeMonths = usefulLifeMonths, salvageMinor = salvageMinor,
    depreciationMethod = depreciationMethod, notes = notes,
    accumulatedDepreciationMinor = accumulatedDepreciationMinor, bookValueMinor = bookValueMinor,
)

private fun LocalExpenseEntity.toPendingRow(categoryNames: Map<String, String>): PendingExpenseRow =
    PendingExpenseRow(
        localId = localId,
        amountMinor = amountMinor,
        categoryName = categoryNames[categoryId] ?: "—",
        vendorName = vendorName,
        rejected = syncState == SyncState.REJECTED,
        error = lastError,
    )

private fun LocalAssetEntity.toPendingRow(): PendingAssetRow = PendingAssetRow(
    localId = localId,
    name = name,
    purchaseMinor = purchaseMinor,
    rejected = syncState == SyncState.REJECTED,
    error = lastError,
)

private fun LocalCapitalEntryEntity.toPendingRow(partners: List<Partner>): PendingCapitalEntryRow =
    PendingCapitalEntryRow(
        localId = localId,
        partnerName = partners.firstOrNull { it.id == partnerId }?.name ?: "Unknown partner",
        type = type,
        amountMinor = amountMinor,
        rejected = syncState == SyncState.REJECTED,
        error = lastError,
    )
