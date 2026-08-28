package cloud.dcompany.erp.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.FinanceAccess
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity
import cloud.dcompany.erp.core.db.LocalAssetEntity
import cloud.dcompany.erp.core.db.LocalCapitalEntryEntity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.CostingCoverage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import java.util.UUID

/** Shared by SyncEngine's serialized writer and this Room-observing screen. */
internal object FinanceSnapshotKeys {
    const val PNL = "finance_pnl"
    const val METRICS = "finance_metrics"
    const val DISTRIBUTABLE = "finance_distributable"
    const val PARTNERS = "finance_partners"
    const val CATEGORIES = "finance_expense_categories"
    const val BRANCHES = "finance_branches"
    const val COSTING = "finance_inventory_costing"
    const val ROW_SCOPE = "finance_row_cache_scope"
}

/** Identity boundary for finance read caches. User id is intentionally not
 * included: two authorized users in the same company/branch may share the
 * same operational data, while a company or assigned-branch change must
 * never reuse it. */
@Serializable
internal data class FinanceCacheScope(
    val companyId: String,
    val branchId: String?,
    val companyWidePartnerFinance: Boolean = false,
) {
    fun key(base: String): String =
        "$base|company=$companyId|branch=${branchId ?: "all"}|" +
            "partner_scope=${if (companyWidePartnerFinance) "company" else "none"}"

    companion object {
        fun from(profile: MeResponse?): FinanceCacheScope? = profile?.let {
            val companyId = it.companyId.trim()
            if (companyId.isEmpty()) null else FinanceCacheScope(
                companyId = companyId,
                branchId = it.branchId?.trim()?.takeIf(String::isNotEmpty),
                companyWidePartnerFinance = it.canViewCompanyWidePartnerFinance(),
            )
        }
    }
}

/** The row caches predate tenant columns, so they are shown only after their
 * durable scope marker is verified. Branch-assigned users additionally see
 * only their branch even if an old/broken backend returned broader data. */
internal fun <T> visibleFinanceRows(
    rows: List<T>,
    scope: FinanceCacheScope?,
    cacheScopeVerified: Boolean,
    branchId: (T) -> String,
): List<T> {
    if (scope == null || !cacheScopeVerified) return emptyList()
    val assignedBranch = scope.branchId ?: return rows
    return rows.filter { branchId(it) == assignedBranch }
}

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
    val online: Boolean = false,
    val lastUpdatedAtMillis: Long? = null,
    val costingCoverageUpdatedAtMillis: Long? = null,
    /** The server's own message, never an HTTP code. */
    val error: String? = null,
    val pl: ProfitAndLoss? = null,
    val metrics: BusinessMetrics? = null,
    val distributable: DistributableProfit? = null,
    val costingCoverage: CostingCoverage? = null,
    val expenses: List<Expense> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val partners: List<Partner> = emptyList(),
    /** Partner ownership/capital has no branch attribution and is intentionally
     * unavailable to a branch-bound role assignment. */
    val companyWidePartnerDataAvailable: Boolean = false,
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
            pl.revenueMinor == 0L && pl.membershipsMinor == 0L &&
            pl.cogsMinor == 0L && pl.expensesMinor == 0L &&
            pl.depreciationMinor == 0L && pl.netProfitMinor == 0L

    /**
     * Costing is safe to apply to the displayed P&L/distribution figures only
     * when both snapshots came from the same successful server refresh. A
     * newer P&L must never inherit an older green "complete" result after the
     * costing endpoint failed.
     */
    val verifiedCostingCoverage: CostingCoverage?
        get() = costingCoverage.takeIf {
            lastUpdatedAtMillis != null &&
                costingCoverageUpdatedAtMillis == lastUpdatedAtMillis
        }

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
 * create-only here. Corrections must use the separate authorised workflow so
 * the original evidence and any reasoned void remain auditable.
 */
class FinanceViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    @Volatile private var access = FinanceAccess()

    private val pl = MutableStateFlow<ProfitAndLoss?>(null)
    private val metrics = MutableStateFlow<BusinessMetrics?>(null)
    private val distributable = MutableStateFlow<DistributableProfit?>(null)
    private val costingCoverage = MutableStateFlow<CostingCoverage?>(null)
    private val lastUpdatedAtMillis = MutableStateFlow<Long?>(null)
    private val costingCoverageUpdatedAtMillis = MutableStateFlow<Long?>(null)
    private val partners = MutableStateFlow<List<Partner>>(emptyList())
    private val branches = MutableStateFlow<List<Branch>>(emptyList())
    private val categoryNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val loading = MutableStateFlow(true)
    private val loadError = MutableStateFlow<String?>(null)
    private val activeScope = MutableStateFlow<FinanceCacheScope?>(null)
    private val rowCacheScopeVerified = MutableStateFlow(false)
    private val financeRefreshError = appCtx.sync.resourceRefreshErrors
        .map { it["finance"] }
        .distinctUntilChanged()

    private val dialog = MutableStateFlow<FinanceDialog?>(null)
    private val busy = MutableStateFlow(false)
    private val formError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private var loadJob: Job? = null

    private data class FormState(
        val dialog: FinanceDialog?,
        val busy: Boolean,
        val formError: String?,
        val notice: String?,
    )

    private data class ReferenceState(
        val partners: List<Partner>,
        val branches: List<Branch>,
        val categoryNames: Map<String, String>,
        val scope: FinanceCacheScope?,
        val rowCacheScopeVerified: Boolean,
    )

    private data class FinanceTotalsState(
        val pl: ProfitAndLoss?,
        val metrics: BusinessMetrics?,
        val distributable: DistributableProfit?,
        val costingCoverage: CostingCoverage?,
        val lastUpdatedAtMillis: Long?,
        val costingCoverageUpdatedAtMillis: Long?,
    )

    val state: StateFlow<FinanceUiState> = combine(
        combine(
            pl,
            metrics,
            distributable,
            costingCoverage,
            combine(lastUpdatedAtMillis, costingCoverageUpdatedAtMillis) { figuresAt, costingAt ->
                figuresAt to costingAt
            },
        ) { p, m, d, coverage, updatedAt ->
            FinanceTotalsState(p, m, d, coverage, updatedAt.first, updatedAt.second)
        },
        combine(
            db.financeDao().observeExpenseCache(),
            db.financeDao().observeLocalExpenses(),
        ) { cache, local -> cache to local },
        combine(
            db.financeDao().observeAssetCache(),
            db.financeDao().observeLocalAssets(),
        ) { cache, local -> cache to local },
        combine(
            partners,
            branches,
            categoryNames,
            activeScope,
            rowCacheScopeVerified,
        ) { p, b, c, scope, verified -> ReferenceState(p, b, c, scope, verified) },
        combine(
            db.financeDao().observeLocalCapitalEntries(),
            combine(
                loading,
                loadError,
                financeRefreshError,
                appCtx.connectivity.online,
            ) { l, localError, refreshError, online ->
                Triple(l, localError ?: refreshError, online)
            },
            combine(dialog, busy, formError, notice) { d, bs, fe, n -> FormState(d, bs, fe, n) },
        ) { capitalEntries, loadingAndError, form -> Triple(capitalEntries, loadingAndError, form) },
    ) { plMetricsDistributable, expenseData, assetData, refData, rest ->
        val p = plMetricsDistributable.pl
        val m = plMetricsDistributable.metrics
        val d = plMetricsDistributable.distributable
        val (expenseCache, localExpenses) = expenseData
        val (assetCache, localAssets) = assetData
        val partnerList = refData.partners
        val branchList = refData.branches
        val catNames = refData.categoryNames
        val scope = refData.scope
        val cacheScopeVerified = refData.rowCacheScopeVerified
        val (capitalEntries, loadingAndError, form) = rest
        val (isLoading, err, isOnline) = loadingAndError

        FinanceUiState(
            loading = isLoading,
            online = isOnline,
            lastUpdatedAtMillis = plMetricsDistributable.lastUpdatedAtMillis,
            costingCoverageUpdatedAtMillis =
                plMetricsDistributable.costingCoverageUpdatedAtMillis,
            error = err,
            pl = p,
            metrics = m,
            distributable = d,
            costingCoverage = plMetricsDistributable.costingCoverage,
            expenses = visibleFinanceRows(
                expenseCache,
                scope,
                cacheScopeVerified,
                ExpenseCacheEntity::branchId,
            ).map { it.toExpense() },
            assets = visibleFinanceRows(
                assetCache,
                scope,
                cacheScopeVerified,
                AssetCacheEntity::branchId,
            ).map { it.toAsset() },
            partners = partnerList,
            companyWidePartnerDataAvailable = scope?.companyWidePartnerFinance == true,
            branches = branchList,
            categoryNames = catNames,
            pendingExpenses = visibleFinanceRows(
                localExpenses,
                scope,
                scope != null,
                LocalExpenseEntity::branchId,
            ).map { it.toPendingRow(catNames) },
            pendingAssets = visibleFinanceRows(
                localAssets,
                scope,
                scope != null,
                LocalAssetEntity::branchId,
            ).map { it.toPendingRow() },
            pendingCapitalEntries = if (scope?.companyWidePartnerFinance != true) {
                emptyList()
            } else {
                capitalEntries.map { it.toPendingRow(partnerList) }
            },
            dialog = form.dialog,
            busy = form.busy,
            formError = form.formError,
            notice = form.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

    init {
        observeSnapshot<ProfitAndLoss>(FinanceSnapshotKeys.PNL) { value, fetchedAt ->
            pl.value = value
            lastUpdatedAtMillis.value = fetchedAt
        }
        observeSnapshot<BusinessMetrics>(FinanceSnapshotKeys.METRICS) { value, _ ->
            metrics.value = value
        }
        observeSnapshot<DistributableProfit>(FinanceSnapshotKeys.DISTRIBUTABLE) { value, _ ->
            distributable.value = value
        }
        observeSnapshot<CostingCoverage>(FinanceSnapshotKeys.COSTING) { value, fetchedAt ->
            costingCoverage.value = value
            costingCoverageUpdatedAtMillis.value = fetchedAt
        }
        observeSnapshot<List<Partner>>(FinanceSnapshotKeys.PARTNERS) { value, _ ->
            partners.value = value.orEmpty()
        }
        observeSnapshot<Map<String, String>>(FinanceSnapshotKeys.CATEGORIES) { value, _ ->
            categoryNames.value = value.orEmpty()
        }
        observeSnapshot<List<Branch>>(FinanceSnapshotKeys.BRANCHES) { value, _ ->
            branches.value = value.orEmpty()
        }
        viewModelScope.launch {
            appCtx.shiftCache.profile.collect { profile ->
                val scope = FinanceCacheScope.from(profile)
                if (scope != activeScope.value) {
                    activeScope.value = scope
                    rowCacheScopeVerified.value = false
                    clearSensitiveReadState()
                }
            }
        }
        load()
    }

    // -------------------------------------------------------------- loading

    fun load() {
        val requestedScope = FinanceCacheScope.from(appCtx.shiftCache.profile.value)
        if (requestedScope == null) {
            loadJob?.cancel()
            activeScope.value = null
            rowCacheScopeVerified.value = false
            clearSensitiveReadState()
            loading.value = false
            loadError.value = "Your signed-in company could not be verified. Sign in again before viewing Finance."
            return
        }
        if (activeScope.value != requestedScope) {
            activeScope.value = requestedScope
            rowCacheScopeVerified.value = false
            clearSensitiveReadState()
        }
        loading.value = true
        loadError.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (appCtx.cacheIsolation.currentLease() == null) {
                failLoadIfCurrent(
                    requestedScope,
                    "Finance is waiting for this account to finish opening. Try again in a moment.",
                )
                return@launch
            }
            val storedRowScope = db.reportSnapshotDao()
                .cached<FinanceCacheScope>(FinanceSnapshotKeys.ROW_SCOPE)
                ?.first
            if (!isCurrentScope(requestedScope)) return@launch
            if (storedRowScope != requestedScope) {
                if (!appCtx.sync.clearFinanceReadCachesForScopeChange(requestedScope)) {
                    failLoadIfCurrent(
                        requestedScope,
                        "Finance could not safely prepare this account's saved figures. Sign in again before continuing.",
                    )
                    return@launch
                }
            }
            if (!isCurrentScope(requestedScope)) return@launch
            rowCacheScopeVerified.value = true

            if (!isCurrentScope(requestedScope)) return@launch
            appCtx.sync.requestSync()

            try {
                appCtx.sync.refresh("finance")
                if (isCurrentScope(requestedScope)) {
                    loading.value = false
                    loadError.value = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrentScope(requestedScope)) {
                    loading.value = false
                    // refresh() contains the diagnostic logging and publishes
                    // its sanitized, resource-scoped error separately.
                    loadError.value = financeLoadFailureMessage(
                        hasSavedFigures = pl.value != null,
                        online = appCtx.connectivity.online.value,
                    )
                }
            }
        }
    }

    /** Room is the sole delivery path for manual and realtime Finance reads. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private inline fun <reified T> observeSnapshot(
        baseKey: String,
        crossinline publish: (T?, Long?) -> Unit,
    ) {
        viewModelScope.launch {
            activeScope.flatMapLatest { scope ->
                if (scope == null) flowOf(null)
                else db.reportSnapshotDao().observe(scope.key(baseKey))
            }.collect { row ->
                val value = withContext(Dispatchers.Default) {
                    row?.let {
                        runCatching { ApiClient.json.decodeFromString<T>(it.jsonBody) }.getOrNull()
                    }
                }
                publish(value, row?.fetchedAtMillis)
            }
        }
    }

    private fun isCurrentScope(scope: FinanceCacheScope): Boolean =
        activeScope.value == scope && FinanceCacheScope.from(appCtx.shiftCache.profile.value) == scope

    private fun failLoadIfCurrent(scope: FinanceCacheScope, message: String) {
        if (!isCurrentScope(scope)) return
        loading.value = false
        loadError.value = message
    }

    private fun clearSensitiveReadState() {
        pl.value = null
        metrics.value = null
        distributable.value = null
        costingCoverage.value = null
        lastUpdatedAtMillis.value = null
        costingCoverageUpdatedAtMillis.value = null
        partners.value = emptyList()
        branches.value = emptyList()
        categoryNames.value = emptyMap()
        loadError.value = null
        dialog.value = null
        formError.value = null
        notice.value = null
        busy.value = false
    }

    // ------------------------------------------------------------- dialogs

    fun updateAccess(next: FinanceAccess) {
        access = next
        val dialogStillAllowed = when (dialog.value) {
            FinanceDialog.ExpenseForm -> next.canRecordExpenses
            FinanceDialog.AssetForm -> next.canManageAssets
            is FinanceDialog.CapitalEntryForm -> next.canRecordPartnerCapital
            null -> true
        }
        if (!dialogStillAllowed) {
            dialog.value = null
            formError.value = null
            notice.value = "Your Finance permission changed. The unsaved form was closed; nothing was queued."
        }
    }

    private fun requireExpenseWrite(): Boolean = authorizeAction(access.canRecordExpenses) {
        notice.value = "You can view Finance, but expense entry is not allowed for this role. Ask an owner or manager."
    }

    private fun requireAssetWrite(): Boolean = authorizeAction(access.canManageAssets) {
        notice.value = "You can view Finance, but asset registration requires the dedicated asset permission. Ask a protected owner."
    }

    private fun requireCapitalWrite(): Boolean = authorizeAction(access.canRecordPartnerCapital) {
        notice.value = "You can view Finance, but partner capital entry requires protected-owner permission."
    }

    fun openExpenseForm() {
        if (!requireExpenseWrite()) return
        if (branches.value.isEmpty() || categoryNames.value.isEmpty()) {
            notice.value =
                "Expense entry is unavailable because branches or expense categories have not loaded. Refresh Finance and try again."
            return
        }
        dialog.value = FinanceDialog.ExpenseForm
    }

    fun openAssetForm() {
        if (!requireAssetWrite()) return
        if (branches.value.isEmpty()) {
            notice.value =
                "Asset entry is unavailable because branches have not loaded. Refresh Finance and try again."
            return
        }
        dialog.value = FinanceDialog.AssetForm
    }

    fun openCapitalEntryForm(partner: Partner) {
        if (!requireCapitalWrite()) return
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
    ) = localMutate(
        allowed = access.canRecordExpenses,
        deniedMessage = "Expense entry is not allowed for this role. Ask an owner or manager.",
    ) {
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
        financeWriteQueuedMessage("Expense", appCtx.connectivity.online.value)
    }

    fun retryExpense(localId: String) {
        if (!requireExpenseWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var retried = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    retried = db.financeDao().retryExpense(localId) == 1
                }) {
                notice.value = "The signed-in account changed. This expense was not retried."
                return@launch
            }
            notice.value = if (retried) {
                "The same saved expense was queued for retry. Do not enter it again."
            } else {
                "This expense is no longer rejected. Refresh Finance to see its current state."
            }
            if (retried) appCtx.sync.requestSync()
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
    ) = localMutate(
        allowed = access.canManageAssets,
        deniedMessage = "Asset registration requires the dedicated asset permission. Ask a protected owner.",
    ) {
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
        financeWriteQueuedMessage("Asset", appCtx.connectivity.online.value)
    }

    fun retryAsset(localId: String) {
        if (!requireAssetWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var retried = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    retried = db.financeDao().retryAsset(localId) == 1
                }) {
                notice.value = "The signed-in account changed. This asset was not retried."
                return@launch
            }
            notice.value = if (retried) {
                "The same saved asset was queued for retry. Do not register it again."
            } else {
                "This asset is no longer rejected. Refresh Finance to see its current state."
            }
            if (retried) appCtx.sync.requestSync()
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
    ) = localMutate(
        allowed = access.canRecordPartnerCapital,
        deniedMessage = "Partner capital entry requires protected-owner permission.",
    ) {
        db.financeDao().insertLocalCapitalEntry(
            LocalCapitalEntryEntity(
                localId = UUID.randomUUID().toString(),
                partnerId = partnerId, type = type, amountMinor = amountMinor,
                effectiveAt = effectiveAt, settlementAccount = settlementAccount,
                sourceRef = sourceRef.trim(), note = note.trim().ifBlank { null },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        financeWriteQueuedMessage("Capital entry", appCtx.connectivity.online.value)
    }

    fun retryCapitalEntry(localId: String) {
        if (!requireCapitalWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var retried = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    retried = db.financeDao().retryCapitalEntry(localId) == 1
                }) {
                notice.value = "The signed-in account changed. This capital entry was not retried."
                return@launch
            }
            notice.value = if (retried) {
                "The same saved capital entry was queued for retry. Do not enter it again."
            } else {
                "This capital entry is no longer rejected. Refresh Finance to see its current state."
            }
            if (retried) appCtx.sync.requestSync()
        }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * One local (Room) write, then the dialog closes — no network wait, since
     * the whole point is that this succeeds instantly offline too. Same shape
     * as InventoryViewModel.localMutate.
     */
    private fun localMutate(
        allowed: Boolean,
        deniedMessage: String,
        block: suspend () -> String,
    ) {
        if (!authorizeAction(allowed) { notice.value = deniedMessage }) return
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                var message = ""
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        message = block()
                    }
                ) {
                    busy.value = false
                    formError.value =
                        "The signed-in account changed before this entry was saved. Nothing was queued."
                    return@launch
                }
                busy.value = false
                dialog.value = null
                formError.value = null
                notice.value = message
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value =
                    "Could not save this entry on the tablet. Nothing was queued. Check storage and try again."
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
