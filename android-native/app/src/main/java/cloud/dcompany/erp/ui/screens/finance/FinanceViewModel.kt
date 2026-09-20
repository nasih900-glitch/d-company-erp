package cloud.dcompany.erp.ui.screens.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.FinanceAccess
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity
import cloud.dcompany.erp.core.db.LocalAssetEntity
import cloud.dcompany.erp.core.db.LocalCapitalEntryEntity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.LocalExpenseReceiptChunkEntity
import cloud.dcompany.erp.core.db.LocalExpenseReceiptEntity
import cloud.dcompany.erp.core.db.LocalExpenseReceiptWithExpense
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.observeResolvedOpenShift
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.CostingCoverage
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.core.diagnostics.DiagnosticsRuntime
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import java.time.LocalDate
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
    const val MANUAL_COLLECTIONS = "finance_manual_collections"
    const val TIP_PAYOUTS = "finance_tip_payouts"
    const val TRIAL_BALANCE = "finance_trial_balance"
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

/**
 * Rebuilds Finance's presentation scope from the authenticated profile while
 * proving that it still belongs to the active Room-cache lease. The lease
 * deliberately contains only tenant/location identity; role-derived partner
 * visibility must come from the current profile instead of silently falling
 * back to [FinanceCacheScope]'s restricted default.
 */
internal fun financeCacheScopeForLease(
    profile: MeResponse?,
    leaseCompanyId: String,
    leaseBranchId: String?,
): FinanceCacheScope? = FinanceCacheScope.from(profile)?.takeIf { scope ->
    scope.companyId == leaseCompanyId && scope.branchId == leaseBranchId
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

/** Snapshot lists are already keyed by company/branch, but retain a second
 * branch check before presentation so a malformed server response cannot
 * expose another shop's financial register. */
internal fun <T> visibleSnapshotFinanceRows(
    rows: List<T>,
    scope: FinanceCacheScope?,
    branchId: (T) -> String,
): List<T> {
    val verifiedScope = scope ?: return emptyList()
    val assignedBranch = verifiedScope.branchId ?: return rows
    return rows.filter { branchId(it) == assignedBranch }
}

/** Which modal is up. Owned by the ViewModel so a rotation does not lose track of which form it was. */
sealed interface FinanceDialog {
    data object ExpenseForm : FinanceDialog
    data object AssetForm : FinanceDialog
    data class CapitalEntryForm(val partner: Partner) : FinanceDialog
    data object ManualCollectionForm : FinanceDialog
    data object TipPayoutForm : FinanceDialog
    data class VoidManualCollection(val row: ManualCollection) : FinanceDialog
    data class VoidTipPayout(val row: TipPayout) : FinanceDialog
    data class DiscardRejectedExpense(val row: PendingExpenseRow) : FinanceDialog
    data class DiscardExpenseReceipt(val row: PendingExpenseReceiptRow) : FinanceDialog
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

data class PendingExpenseReceiptRow(
    val localId: String,
    val expenseLocalId: String,
    val expenseServerId: String?,
    val contentSha256: String,
    val filename: String,
    val waitingForExpense: Boolean,
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

internal data class FinanceUiState(
    val loading: Boolean = true,
    val online: Boolean = false,
    val lastUpdatedAtMillis: Long? = null,
    val costingCoverageUpdatedAtMillis: Long? = null,
    /** The server's own message, never an HTTP code. */
    val error: String? = null,
    val pl: ProfitAndLoss? = null,
    val metrics: BusinessMetrics? = null,
    val distributable: DistributableProfit? = null,
    val allocationUnavailableReason: String? = null,
    val costingCoverage: CostingCoverage? = null,
    val expenses: List<Expense> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val manualCollections: List<ManualCollection> = emptyList(),
    val tipPayouts: List<TipPayout> = emptyList(),
    val trialBalance: TrialBalance? = null,
    val partners: List<Partner> = emptyList(),
    /** Partner ownership/capital has no branch attribution and is intentionally
     * unavailable to a branch-bound role assignment. */
    val companyWidePartnerDataAvailable: Boolean = false,
    val branches: List<Branch> = emptyList(),
    val categoryNames: Map<String, String> = emptyMap(),
    val cashExpenseShifts: List<CashExpenseShiftOption> = emptyList(),
    val pendingExpenses: List<PendingExpenseRow> = emptyList(),
    val pendingExpenseReceipts: List<PendingExpenseReceiptRow> = emptyList(),
    val pendingAssets: List<PendingAssetRow> = emptyList(),
    val pendingCapitalEntries: List<PendingCapitalEntryRow> = emptyList(),
    val dialog: FinanceDialog? = null,
    val busy: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
    /** A live write whose response was not safely resolved. This is a durable
     * exact-request checkpoint, never an offline accounting queue. */
    val pendingOnlineWrite: PendingFinanceOnlineWrite? = null,
) {
    /** True once a load has succeeded; the five report figures always arrive together. */
    val loaded: Boolean get() = pl != null

    val expenseTotalMinor: Long get() =
        expenses.filterNot { it.isVoided || it.isCorrected }.sumOf { it.amountMinor }
    val collectionTotals: ManualCollectionTotals get() =
        manualCollectionTotals(manualCollections)
    val tipPayoutTotalMinor: Long get() = tipPayoutTotal(tipPayouts)
    val tipsPayableMinor: Long? get() = trialBalance?.tipsPayableMinor()

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

    val allocationWarning: String?
        get() = if (!companyWidePartnerDataAvailable || !loaded) null else {
            allocationUnavailableReason ?: FINANCE_ALLOCATION_NOT_VERIFIED.takeIf {
                distributable == null
            }
        }

    fun categoryName(id: String): String = categoryNames[id] ?: "—"
}

internal enum class FinancePrimaryContentState { LOADING, ERROR, DATA }

/** A completed request without a decodable P&L is an error, never a spinner. */
internal val FinanceUiState.primaryContentState: FinancePrimaryContentState
    get() = when {
        loaded -> FinancePrimaryContentState.DATA
        loading -> FinancePrimaryContentState.LOADING
        else -> FinancePrimaryContentState.ERROR
    }

private const val FINANCE_LOAD_TIMEOUT_MILLIS = 75_000L
private const val EXPENSE_RECEIPT_RECONCILIATION_TIMEOUT_MILLIS = 30_000L
private const val FINANCE_SNAPSHOT_DELIVERY_TIMEOUT_MILLIS = 2_000L
private const val FINANCE_LOAD_TIMEOUT_MESSAGE =
    "Finance took too long to finish refreshing. Some figures may already be updated while others still show saved values. Check the connection and try again."

internal fun financeLoadCompletionError(
    result: ResourceRefreshResult,
    hasSavedFigures: Boolean,
    online: Boolean,
): String? = when (result) {
    is ResourceRefreshResult.Refreshed -> if (hasSavedFigures) {
        null
    } else {
        "Finance refreshed, but the financial summary could not be saved on this tablet. Try again; if it continues, ask for support."
    }
    is ResourceRefreshResult.Failed -> result.userMessage
    is ResourceRefreshResult.Skipped -> if (hasSavedFigures) {
        null
    } else {
        financeLoadFailureMessage(hasSavedFigures = false, online = online)
    }
}

/** A local manual-load failure and the wall-clock boundary it established. */
internal data class FinanceLoadFailure(
    val message: String,
    val raisedAtMillis: Long,
)

/**
 * Reconciles a local manual-load failure with Room's authoritative snapshot
 * delivery. Room delivery is asynchronous: a snapshot committed by refresh A
 * can arrive after newer refresh B has already failed. Therefore a merely
 * different timestamp is insufficient. Only a decoded summary committed
 * strictly after the current failure may dismiss it; older/equal deliveries,
 * initial cache emissions and corrupt rows retain the failure.
 *
 * Resource-scoped partial failures remain independently visible through
 * SyncEngine's `resourceRefreshErrors`; this clears only the obsolete local
 * failure left behind by an earlier manual load.
 */
internal fun financeLoadFailureAfterSummaryDelivery(
    currentFailure: FinanceLoadFailure?,
    incomingFetchedAtMillis: Long?,
    hasDecodedSummary: Boolean,
): FinanceLoadFailure? = if (
    currentFailure != null &&
    hasDecodedSummary &&
    incomingFetchedAtMillis != null &&
    incomingFetchedAtMillis > currentFailure.raisedAtMillis
) {
    null
} else {
    currentFailure
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
    private val financeApi = ApiClient.create<FinanceApi>()
    private val onlineWriteExecutor: FinanceOnlineWriteExecutor =
        RetrofitFinanceOnlineWriteExecutor()
    private val writeRecoveryStore: FinanceWriteRecoveryStore =
        SharedPreferencesFinanceWriteRecoveryStore(appCtx)
    @Volatile private var access = FinanceAccess()

    private val pl = MutableStateFlow<ProfitAndLoss?>(null)
    private val metrics = MutableStateFlow<BusinessMetrics?>(null)
    private val allocation = MutableStateFlow<Pair<FinanceAllocationSnapshot?, Long?>>(null to null)
    private val costingCoverage = MutableStateFlow<CostingCoverage?>(null)
    private val lastUpdatedAtMillis = MutableStateFlow<Long?>(null)
    private val costingCoverageUpdatedAtMillis = MutableStateFlow<Long?>(null)
    private val partners = MutableStateFlow<List<Partner>>(emptyList())
    private val branches = MutableStateFlow<List<Branch>>(emptyList())
    private val categoryNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val manualCollections = MutableStateFlow<List<ManualCollection>>(emptyList())
    private val tipPayouts = MutableStateFlow<List<TipPayout>>(emptyList())
    private val trialBalance = MutableStateFlow<TrialBalance?>(null)
    private val pendingOnlineWrite = MutableStateFlow<PendingFinanceOnlineWrite?>(null)
    private val loading = MutableStateFlow(true)
    private val loadFailure = MutableStateFlow<FinanceLoadFailure?>(null)
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
        val allocation: Pair<FinanceAllocationSnapshot?, Long?>,
        val costingCoverage: CostingCoverage?,
        val lastUpdatedAtMillis: Long?,
        val costingCoverageUpdatedAtMillis: Long?,
    )

    private data class OperationalMoneyState(
        val manualCollections: List<ManualCollection>,
        val tipPayouts: List<TipPayout>,
        val trialBalance: TrialBalance?,
        val pendingWrite: PendingFinanceOnlineWrite?,
    )

    private data class ExpenseState(
        val cache: List<ExpenseCacheEntity>,
        val localExpenses: List<LocalExpenseEntity>,
        val localReceipts: List<LocalExpenseReceiptWithExpense>,
        val resolvedShift: ResolvedOpenShift?,
        val cashReservations: List<LocalExpenseEntity>,
    )

    private data class RestState(
        val capitalEntries: List<LocalCapitalEntryEntity>,
        val loadingAndError: Triple<Boolean, String?, Boolean>,
        val form: FormState,
        val operationalMoney: OperationalMoneyState,
    )

    internal val state: StateFlow<FinanceUiState> = combine(
        combine(
            pl,
            metrics,
            allocation,
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
            db.financeDao().observeLocalExpenseReceiptsWithExpense(),
            db.shiftDao().observeResolvedOpenShift(appCtx.terminalStore.terminalIdFlow),
            db.financeDao().observeCashExpenseReservations(),
        ) { cache, local, receipts, resolvedShift, cashReservations ->
            ExpenseState(cache, local, receipts, resolvedShift, cashReservations)
        },
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
                loadFailure,
                financeRefreshError,
                appCtx.connectivity.online,
            ) { l, localFailure, refreshError, online ->
                Triple(l, localFailure?.message ?: refreshError, online)
            },
            combine(dialog, busy, formError, notice) { d, bs, fe, n -> FormState(d, bs, fe, n) },
            combine(
                manualCollections,
                tipPayouts,
                trialBalance,
                pendingOnlineWrite,
            ) { collections, payouts, balance, pending ->
                OperationalMoneyState(collections, payouts, balance, pending)
            },
        ) { capitalEntries, loadingAndError, form, operationalMoney ->
            RestState(capitalEntries, loadingAndError, form, operationalMoney)
        },
    ) { plMetricsDistributable, expenseData, assetData, refData, rest ->
        val p = plMetricsDistributable.pl
        val m = plMetricsDistributable.metrics
        val (allocationSnapshot, allocationFetchedAt) = plMetricsDistributable.allocation
        val expenseCache = expenseData.cache
        val localExpenses = expenseData.localExpenses
        val (assetCache, localAssets) = assetData
        val partnerList = refData.partners
        val branchList = refData.branches
        val catNames = refData.categoryNames
        val scope = refData.scope
        val cacheScopeVerified = refData.rowCacheScopeVerified
        val capitalEntries = rest.capitalEntries
        val loadingAndError = rest.loadingAndError
        val form = rest.form
        val operationalMoney = rest.operationalMoney
        val (isLoading, err, isOnline) = loadingAndError
        val cashShift = cashExpenseShiftOption(
            resolved = expenseData.resolvedShift,
            branchId = scope?.branchId ?: expenseData.resolvedShift?.server?.branchId,
            localCashExpenses = expenseData.cashReservations,
            currentInstallationId = appCtx.updateTelemetry.installation.installationId(),
        )

        FinanceUiState(
            loading = isLoading,
            online = isOnline,
            lastUpdatedAtMillis = plMetricsDistributable.lastUpdatedAtMillis,
            costingCoverageUpdatedAtMillis =
                plMetricsDistributable.costingCoverageUpdatedAtMillis,
            error = err,
            pl = p,
            metrics = m,
            distributable = allocationSnapshot.reportForSummary(
                allocationFetchedAt,
                plMetricsDistributable.lastUpdatedAtMillis,
            ),
            allocationUnavailableReason = allocationSnapshot?.unavailableReason,
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
            manualCollections = visibleSnapshotFinanceRows(
                operationalMoney.manualCollections,
                scope,
                ManualCollection::branchId,
            ),
            tipPayouts = visibleSnapshotFinanceRows(
                operationalMoney.tipPayouts,
                scope,
                TipPayout::branchId,
            ),
            trialBalance = operationalMoney.trialBalance,
            partners = partnerList,
            companyWidePartnerDataAvailable = scope?.companyWidePartnerFinance == true,
            branches = branchList,
            categoryNames = catNames,
            cashExpenseShifts = listOfNotNull(cashShift),
            pendingExpenses = visibleFinanceRows(
                localExpenses,
                scope,
                scope != null,
                LocalExpenseEntity::branchId,
            ).map { it.toPendingRow(catNames) },
            pendingExpenseReceipts = visibleFinanceRows(
                expenseData.localReceipts,
                scope,
                scope != null,
                LocalExpenseReceiptWithExpense::expenseBranchId,
            ).map { it.toPendingRow() },
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
            pendingOnlineWrite = operationalMoney.pendingWrite,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

    init {
        observeSnapshot<ProfitAndLoss>(FinanceSnapshotKeys.PNL) { value, fetchedAt ->
            pl.value = value
            lastUpdatedAtMillis.value = fetchedAt
            loadFailure.value = financeLoadFailureAfterSummaryDelivery(
                currentFailure = loadFailure.value,
                incomingFetchedAtMillis = fetchedAt,
                hasDecodedSummary = value != null,
            )
        }
        observeSnapshot<BusinessMetrics>(FinanceSnapshotKeys.METRICS) { value, _ ->
            metrics.value = value
        }
        observeSnapshot<FinanceAllocationSnapshot>(FinanceSnapshotKeys.DISTRIBUTABLE) { value, fetchedAt ->
            allocation.value = value to fetchedAt
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
        observeSnapshot<List<ManualCollection>>(FinanceSnapshotKeys.MANUAL_COLLECTIONS) { value, _ ->
            manualCollections.value = value.orEmpty()
        }
        observeSnapshot<List<TipPayout>>(FinanceSnapshotKeys.TIP_PAYOUTS) { value, _ ->
            tipPayouts.value = value.orEmpty()
        }
        observeSnapshot<TrialBalance>(FinanceSnapshotKeys.TRIAL_BALANCE) { value, _ ->
            trialBalance.value = value
        }
        viewModelScope.launch {
            appCtx.shiftCache.profile.collect { profile ->
                val scope = FinanceCacheScope.from(profile)
                val scopeChanged = scope != activeScope.value
                if (scopeChanged) {
                    activeScope.value = scope
                    rowCacheScopeVerified.value = false
                    clearSensitiveReadState()
                }
                pendingOnlineWrite.value = profile?.financeWriteScope()?.let(writeRecoveryStore::load)
                // Auth refresh can change branch or protected-owner scope while
                // this ViewModel remains alive. Cancel the obsolete load and
                // start one for the new exact scope instead of leaving the new
                // screen on the old job's loading state forever.
                if (scopeChanged) load()
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
            recordLoadFailure(
                "Your signed-in company could not be verified. Sign in again before viewing Finance.",
            )
            return
        }
        if (activeScope.value != requestedScope) {
            activeScope.value = requestedScope
            rowCacheScopeVerified.value = false
            clearSensitiveReadState()
            pendingOnlineWrite.value = appCtx.shiftCache.profile.value
                ?.financeWriteScope()
                ?.let(writeRecoveryStore::load)
        }
        loading.value = true
        loadFailure.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                withTimeout(FINANCE_LOAD_TIMEOUT_MILLIS) {
                    if (appCtx.cacheIsolation.currentLease() == null) {
                        failLoadIfCurrent(
                            requestedScope,
                            "Finance is waiting for this account to finish opening. Try again in a moment.",
                        )
                        return@withTimeout
                    }
                    val storedRowScope = db.reportSnapshotDao()
                        .cached<FinanceCacheScope>(FinanceSnapshotKeys.ROW_SCOPE)
                        ?.first
                    if (!isCurrentScope(requestedScope)) return@withTimeout
                    if (storedRowScope != requestedScope &&
                        !appCtx.sync.clearFinanceReadCachesForScopeChange(requestedScope)
                    ) {
                        failLoadIfCurrent(
                            requestedScope,
                            "Finance could not safely prepare this account's saved figures. Sign in again before continuing.",
                        )
                        return@withTimeout
                    }
                    if (!isCurrentScope(requestedScope)) return@withTimeout
                    rowCacheScopeVerified.value = true

                    appCtx.sync.requestSync()
                    val result = appCtx.sync.refresh("finance")
                    if (!isCurrentScope(requestedScope)) return@withTimeout

                    // Validate the exact scoped/decodable snapshot rather than
                    // assuming HTTP success implies that Room can present it.
                    val cachedSummary = db.reportSnapshotDao()
                        .cached<ProfitAndLoss>(requestedScope.key(FinanceSnapshotKeys.PNL))
                    if (cachedSummary != null && pl.value == null) {
                        // Do not briefly render a failure between Room's
                        // transaction commit and its observer delivery.
                        withTimeoutOrNull(FINANCE_SNAPSHOT_DELIVERY_TIMEOUT_MILLIS) {
                            pl.filterNotNull().first()
                        }
                    }
                    val hasSavedFigures = pl.value != null
                    loading.value = false
                    recordLoadFailure(
                        financeLoadCompletionError(
                            result = result,
                            hasSavedFigures = hasSavedFigures,
                            online = appCtx.connectivity.online.value,
                        ),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                if (isCurrentScope(requestedScope)) {
                    loading.value = false
                    recordLoadFailure(FINANCE_LOAD_TIMEOUT_MESSAGE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrentScope(requestedScope)) {
                    loading.value = false
                    recordLoadFailure(
                        financeLoadFailureMessage(
                            hasSavedFigures = pl.value != null,
                            online = appCtx.connectivity.online.value,
                        ),
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
        recordLoadFailure(message)
    }

    private fun recordLoadFailure(message: String?) {
        loadFailure.value = message?.let {
            FinanceLoadFailure(message = it, raisedAtMillis = System.currentTimeMillis())
        }
    }

    private fun clearSensitiveReadState() {
        pl.value = null
        metrics.value = null
        allocation.value = null to null
        costingCoverage.value = null
        lastUpdatedAtMillis.value = null
        costingCoverageUpdatedAtMillis.value = null
        partners.value = emptyList()
        branches.value = emptyList()
        categoryNames.value = emptyMap()
        manualCollections.value = emptyList()
        tipPayouts.value = emptyList()
        trialBalance.value = null
        pendingOnlineWrite.value = null
        loadFailure.value = null
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
            FinanceDialog.ManualCollectionForm,
            FinanceDialog.TipPayoutForm,
            is FinanceDialog.VoidManualCollection,
            is FinanceDialog.VoidTipPayout,
            is FinanceDialog.DiscardRejectedExpense,
            is FinanceDialog.DiscardExpenseReceipt,
            -> next.canRecordExpenses
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

    private fun requireOperationalMoneyWrite(): Boolean = authorizeAction(access.canRecordExpenses) {
        notice.value =
            "You can view these registers, but manual collections and tip payouts require Finance write access."
    }

    private fun requireOnlineFinancialWrite(): Boolean {
        if (appCtx.connectivity.online.value) return true
        notice.value =
            "This accounting action is online-only and was not saved. Reconnect, refresh Finance, then try again."
        return false
    }

    private fun requireNoPendingOnlineWrite(): Boolean {
        if (pendingOnlineWrite.value == null) return true
        notice.value =
            "Resolve the exact saved Finance request before starting another manual collection or tip payout."
        return false
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

    fun openManualCollectionForm() {
        if (!requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite()
        ) return
        if (branches.value.isEmpty()) {
            notice.value =
                "No verified shop is available for this collection. Refresh Finance and check shop access."
            return
        }
        dialog.value = FinanceDialog.ManualCollectionForm
        formError.value = null
    }

    fun openTipPayoutForm() {
        if (!requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite()
        ) return
        if (branches.value.isEmpty() || trialBalance.value == null) {
            notice.value =
                "The shop or live Tips Payable balance has not loaded. Refresh Finance before paying staff."
            return
        }
        dialog.value = FinanceDialog.TipPayoutForm
        formError.value = null
    }

    fun openVoidManualCollection(row: ManualCollection) {
        if (row.isVoided || row.isCorrected ||
            (row.method == "cash" && row.sourceShiftStatus != "open") ||
            !requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite()
        ) return
        dialog.value = FinanceDialog.VoidManualCollection(row)
        formError.value = null
    }

    fun openVoidTipPayout(row: TipPayout) {
        if (row.isVoided || row.isCorrected ||
            (row.method == "cash" && row.sourceShiftStatus != "open") ||
            !requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite()
        ) return
        dialog.value = FinanceDialog.VoidTipPayout(row)
        formError.value = null
    }

    fun openDiscardExpenseReceipt(row: PendingExpenseReceiptRow) {
        if (!row.rejected || !requireExpenseWrite()) return
        dialog.value = FinanceDialog.DiscardExpenseReceipt(row)
        formError.value = null
    }

    fun openDiscardRejectedExpense(row: PendingExpenseRow) {
        if (!row.rejected || !requireExpenseWrite()) return
        dialog.value = FinanceDialog.DiscardRejectedExpense(row)
        formError.value = null
    }

    fun closeDialog() {
        if (busy.value) return
        dialog.value = null
        formError.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }

    // -------------------------------- manual collections and tip payouts

    fun createManualCollection(
        branchId: String,
        shiftId: String?,
        businessDate: String,
        method: String,
        amountMinor: Long,
        sourceRef: String,
        note: String,
    ) {
        if (!requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite() || busy.value
        ) return
        val parsedDate = runCatching { LocalDate.parse(businessDate) }.getOrNull()
        val selectedDrawer = state.value.cashExpenseShifts.firstOrNull {
            it.id == shiftId && it.branchId == branchId
        }
        when {
            branches.value.none { it.id == branchId } ->
                formError.value = "Select the verified shop for this collection."
            parsedDate == null -> formError.value = "Select a valid business date."
            parsedDate > financeBusinessToday() ->
                formError.value = "Business date cannot be in the future."
            method !in FINANCE_PAYMENT_METHODS ->
                formError.value = "Select Cash, UPI, Card or Bank transfer."
            amountMinor <= 0 -> formError.value = "Enter an amount greater than ₹0."
            method == "cash" && selectedDrawer == null ->
                formError.value =
                    "Select the verified open shift whose drawer received this cash."
            sourceRef.trim().isEmpty() ->
                formError.value =
                    "Enter a reference that can be matched to the daily sheet or payment evidence."
            else -> {
                val scope = currentWriteScope() ?: return
                executeNewOnlineWrite(
                    pendingManualCollectionCreate(
                        scope,
                        ManualCollectionCreate(
                            branchId = branchId,
                            shiftId = selectedDrawer?.id.takeIf { method == "cash" },
                            businessDate = businessDate,
                            method = method,
                            amountMinor = amountMinor,
                            sourceRef = sourceRef.trim(),
                            note = note.trim().ifBlank { null },
                        ),
                    ),
                )
            }
        }
    }

    fun createTipPayout(
        branchId: String,
        shiftId: String?,
        method: String,
        amountMinor: Long,
        paidAt: String,
        note: String,
    ) {
        if (!requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite() || busy.value
        ) return
        val liveTipsPayable = trialBalance.value?.tipsPayableMinor()
        val selectedDrawer = state.value.cashExpenseShifts.firstOrNull {
            it.id == shiftId && it.branchId == branchId
        }
        when {
            branches.value.none { it.id == branchId } ->
                formError.value = "Select the verified shop for this payout."
            method !in FINANCE_PAYMENT_METHODS ->
                formError.value = "Select Cash, UPI, Card or Bank transfer."
            amountMinor <= 0 -> formError.value = "Enter an amount greater than ₹0."
            method == "cash" && selectedDrawer == null ->
                formError.value =
                    "Select the verified open shift whose drawer paid these tips."
            method == "cash" && amountMinor > (selectedDrawer?.availableMinor ?: 0L) ->
                formError.value =
                    "This payout exceeds the cash available in the selected drawer."
            liveTipsPayable == null ->
                formError.value = "Refresh the live Tips Payable balance before paying staff."
            amountMinor > liveTipsPayable ->
                formError.value =
                    "This exceeds the ${liveTipsPayable.asRupees()} currently owed to staff. Refresh and check the amount."
            note.trim().length < 3 ->
                formError.value =
                    "Enter a note explaining how the payout was split (at least 3 characters)."
            else -> {
                val scope = currentWriteScope() ?: return
                executeNewOnlineWrite(
                    pendingTipPayoutCreate(
                        scope,
                        TipPayoutCreate(
                            branchId = branchId,
                            shiftId = selectedDrawer?.id.takeIf { method == "cash" },
                            amountMinor = amountMinor,
                            method = method,
                            paidAt = paidAt,
                            note = note.trim(),
                        ),
                    ),
                )
            }
        }
    }

    fun voidManualCollection(row: ManualCollection, reason: String) {
        val trimmed = reason.trim()
        if (!requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite() || busy.value
        ) return
        if (trimmed.length < 3) {
            formError.value = "Enter a void reason with at least 3 characters."
            return
        }
        val scope = currentWriteScope() ?: return
        executeNewOnlineWrite(pendingManualCollectionVoid(scope, row.id, trimmed))
    }

    fun voidTipPayout(row: TipPayout, reason: String) {
        val trimmed = reason.trim()
        if (!requireOperationalMoneyWrite() || !requireNoPendingOnlineWrite() ||
            !requireOnlineFinancialWrite() || busy.value
        ) return
        if (trimmed.length < 3) {
            formError.value = "Enter a void reason with at least 3 characters."
            return
        }
        val scope = currentWriteScope() ?: return
        executeNewOnlineWrite(pendingTipPayoutVoid(scope, row.id, trimmed))
    }

    fun retryPendingOnlineWrite() {
        if (!requireOperationalMoneyWrite() || !requireOnlineFinancialWrite() || busy.value) return
        val write = pendingOnlineWrite.value ?: return
        if (currentWriteScope() != write.scope) {
            notice.value =
                "This saved request belongs to another signed-in Finance account. Sign back into that account to resolve it."
            return
        }
        executeOnlineWrite(write, alreadyStored = true)
    }

    private fun currentWriteScope(): FinanceWriteScope? {
        val scope = appCtx.shiftCache.profile.value?.financeWriteScope()
        if (scope == null) {
            formError.value =
                "The signed-in Finance account could not be verified. Sign in again; nothing was sent."
        }
        return scope
    }

    private fun executeNewOnlineWrite(write: PendingFinanceOnlineWrite) {
        executeOnlineWrite(write, alreadyStored = false)
    }

    private fun executeOnlineWrite(write: PendingFinanceOnlineWrite, alreadyStored: Boolean) {
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            val stored = alreadyStored || withContext(Dispatchers.IO) {
                writeRecoveryStore.save(write)
            }
            if (!stored) {
                busy.value = false
                formError.value =
                    "Could not create the duplicate-protection checkpoint, so nothing was sent. Check tablet storage and try again."
                return@launch
            }
            pendingOnlineWrite.value = write
            try {
                val result = onlineWriteExecutor.execute(write)
                val cleared = withContext(Dispatchers.IO) { writeRecoveryStore.clear(write) }
                if (appCtx.shiftCache.profile.value?.financeWriteScope() != write.scope) {
                    busy.value = false
                    dialog.value = null
                    notice.value =
                        "The signed-in account changed while the server processed Finance. Open Finance under the original account to verify the result."
                    return@launch
                }
                if (!cleared) {
                    busy.value = false
                    dialog.value = null
                    notice.value =
                        "The server recorded this action, but the tablet could not clear its safety checkpoint. " +
                            "Do not enter or pay it again; use Retry exact request to reconcile safely."
                    return@launch
                }
                pendingOnlineWrite.value = null
                publishOnlineWriteResult(result)
                busy.value = false
                dialog.value = null
                formError.value = null
                notice.value = when (write.kind) {
                    FinanceOnlineWriteKind.MANUAL_COLLECTION_CREATE ->
                        "Manual collection recorded and included in the server books."
                    FinanceOnlineWriteKind.MANUAL_COLLECTION_VOID ->
                        "Manual collection voided. The original record remains visible for audit."
                    FinanceOnlineWriteKind.TIP_PAYOUT_CREATE ->
                        "Tip payout recorded against Tips Payable."
                    FinanceOnlineWriteKind.TIP_PAYOUT_VOID ->
                        "Tip payout voided. The original record remains visible for audit."
                }
                appCtx.sync.refresh("finance")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val preserve = preserveFinanceWriteForRetry(error)
                if (appCtx.shiftCache.profile.value?.financeWriteScope() != write.scope) {
                    if (!preserve) {
                        withContext(Dispatchers.IO) { writeRecoveryStore.clear(write) }
                    }
                    busy.value = false
                    return@launch
                }
                if (preserve) {
                    dialog.value = null
                    formError.value = null
                    notice.value = financeWriteFailureMessage(error, preserved = true)
                } else {
                    val cleared = withContext(Dispatchers.IO) { writeRecoveryStore.clear(write) }
                    if (cleared) {
                        pendingOnlineWrite.value = null
                        val message = financeWriteFailureMessage(error, preserved = false)
                        if (dialog.value == null) notice.value = message else formError.value = message
                    } else {
                        pendingOnlineWrite.value = write
                        dialog.value = null
                        notice.value =
                            "The server rejected this request, but the tablet could not clear its safety checkpoint. " +
                                "Do not create another entry; reopen Finance after checking tablet storage."
                    }
                }
                busy.value = false
            }
        }
    }

    private fun publishOnlineWriteResult(result: FinanceOnlineWriteResult) {
        when (result) {
            is FinanceOnlineWriteResult.ManualCollectionResult -> {
                manualCollections.value = listOf(result.row) +
                    manualCollections.value.filterNot { it.id == result.row.id }
            }
            is FinanceOnlineWriteResult.TipPayoutResult -> {
                tipPayouts.value = listOf(result.row) +
                    tipPayouts.value.filterNot { it.id == result.row.id }
            }
        }
    }

    // -------------------------------------------------------------- expenses

    internal fun postExpense(
        branchId: String,
        categoryId: String,
        amountMinor: Long,
        paidVia: String,
        paidAt: String,
        vendorName: String,
        invoiceNo: String,
        note: String,
        shiftId: String? = null,
        receipt: ExpenseReceiptDraft? = null,
    ) {
        if (!requireExpenseWrite()) return
        val shiftError = cashExpenseSelectionError(
            paidVia = paidVia,
            branchId = branchId,
            shiftId = shiftId,
            amountMinor = amountMinor,
            options = state.value.cashExpenseShifts,
        )
        if (shiftError != null) {
            formError.value = shiftError
            return
        }
        val capturedShiftId = shiftId?.takeIf { paidVia == "cash" }
        localMutate(
            allowed = access.canRecordExpenses,
            deniedMessage = "Expense entry is not allowed for this role. Ask an owner or manager.",
        ) {
            val expenseLocalId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val receiptLocalId = receipt?.let { UUID.randomUUID().toString() }
            db.financeDao().insertLocalExpenseWithReceipt(
                expense = LocalExpenseEntity(
                    localId = expenseLocalId,
                    branchId = branchId, categoryId = categoryId, supplierId = null,
                    amountMinor = amountMinor, paidVia = paidVia, paidAt = paidAt,
                    vendorName = vendorName.trim().ifBlank { null },
                    invoiceNo = invoiceNo.trim().ifBlank { null },
                    note = note.trim().ifBlank { null },
                    createdAtMillis = now,
                    shiftId = capturedShiftId,
                ),
                receipt = receipt?.let { draft ->
                    require(draft.source in setOf(
                        EXPENSE_RECEIPT_CAMERA_SOURCE,
                        EXPENSE_RECEIPT_GALLERY_SOURCE,
                        EXPENSE_RECEIPT_FILE_SOURCE,
                    )) { "Unsupported receipt source." }
                    LocalExpenseReceiptEntity(
                        localId = requireNotNull(receiptLocalId),
                        expenseLocalId = expenseLocalId,
                        filename = draft.filename,
                        contentType = draft.contentType,
                        source = draft.source,
                        byteSize = draft.content.size,
                        contentSha256 = expenseReceiptSha256(draft.content),
                        createdAtMillis = now,
                    )
                },
                receiptChunks = receipt?.let { draft ->
                    expenseReceiptContentChunks(draft.content).mapIndexed { index, content ->
                        LocalExpenseReceiptChunkEntity(
                            receiptLocalId = requireNotNull(receiptLocalId),
                            chunkIndex = index,
                            content = content,
                        )
                    }
                }.orEmpty(),
            )
            financeWriteQueuedMessage(
                if (receipt == null) "Expense" else "Expense and receipt",
                appCtx.connectivity.online.value,
            )
        }
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

    fun retryExpenseReceipt(localId: String) {
        if (!requireExpenseWrite()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var retried = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    retried = db.financeDao().retryExpenseReceipt(localId) == 1
                }) {
                notice.value = "The signed-in account changed. This receipt was not retried."
                return@launch
            }
            notice.value = if (retried) {
                "The same saved receipt was queued for retry. Do not attach it again."
            } else {
                "This receipt is no longer rejected. Refresh Finance to see its current state."
            }
            if (retried) appCtx.sync.requestSync()
        }
    }

    fun discardRejectedExpense(row: PendingExpenseRow) {
        if (busy.value || !row.rejected || !requireExpenseWrite()) return
        if (!appCtx.connectivity.online.value) {
            formError.value =
                "Reconnect before checking the server. Nothing was removed from this tablet."
            return
        }
        val lease = appCtx.cacheIsolation.currentLease() ?: run {
            formError.value = "This account is still opening. Nothing was removed; try again."
            return
        }
        val profile = appCtx.shiftCache.profile.value ?: run {
            formError.value = "The signed-in account could not be verified. Nothing was removed."
            return
        }
        val capturedOwner = OutboxOwnerIdentity.from(profile)
        if (!expenseReceiptDiscardScopeMatches(
                capturedOwner = capturedOwner,
                currentOwner = capturedOwner,
                durableOutboxOwner = appCtx.outboxOwnerStore.owner(),
                cacheScope = lease.scope,
                hasFinanceWrite = EffectivePermissions.from(profile)
                    .financeAccess().canRecordExpenses,
            )
        ) {
            formError.value =
                "This saved expense belongs to a different account scope. Nothing was removed."
            return
        }

        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                val capturedExpense = db.financeDao().expenseByLocalId(row.localId)
                val capturedReceipts = db.financeDao().expenseReceiptsForExpense(row.localId)
                if (
                    capturedExpense == null || capturedExpense.syncState != SyncState.REJECTED ||
                    capturedExpense.serverId != null ||
                    (lease.scope.branchId != null &&
                        capturedExpense.branchId != lease.scope.branchId) ||
                    capturedReceipts.any {
                        it.expenseLocalId != capturedExpense.localId ||
                            it.serverReceiptId != null ||
                            it.syncState !in setOf(SyncState.PENDING, SyncState.REJECTED)
                    }
                ) {
                    formError.value =
                        "This saved expense changed before the server check. Nothing was removed."
                    return@launch
                }
                val result = withTimeout(EXPENSE_RECEIPT_RECONCILIATION_TIMEOUT_MILLIS) {
                    financeApi.reconcileExpenseAction(
                        actionId = capturedExpense.localId,
                        branchId = capturedExpense.branchId,
                    )
                }
                if (result.idempotencyKey != expenseIdempotencyKey(capturedExpense.localId)) {
                    formError.value =
                        "The server returned a different saved action. Nothing was removed."
                    return@launch
                }
                if (result.state == "in_progress") {
                    formError.value =
                        "The server is still resolving this expense. Nothing was removed; retry shortly."
                    return@launch
                }
                val accepted = result.expense
                if (result.state == "accepted" && (
                        accepted == null || !authoritativeExpenseMatchesLocal(
                            capturedExpense,
                            accepted,
                            capturedOwner.userId,
                        )
                    )
                ) {
                    formError.value =
                        "The server expense does not exactly match this saved request. Nothing was removed."
                    return@launch
                }
                if (result.state !in setOf("accepted", "absent") ||
                    (result.state == "absent" && accepted != null)
                ) {
                    formError.value =
                        "The server could not prove this action's state. Nothing was removed."
                    return@launch
                }

                var outcome = RejectedExpenseActionOutcome.CHANGED
                val committed = appCtx.cacheIsolation.commitIfCurrent(lease) {
                    val currentProfile = appCtx.shiftCache.profile.value
                    val stillAuthorised = currentProfile?.let {
                        EffectivePermissions.from(it).financeAccess().canRecordExpenses
                    } == true
                    if (!expenseReceiptDiscardScopeMatches(
                            capturedOwner = capturedOwner,
                            currentOwner = currentProfile?.let { OutboxOwnerIdentity.from(it) },
                            durableOutboxOwner = appCtx.outboxOwnerStore.owner(),
                            cacheScope = lease.scope,
                            hasFinanceWrite = stillAuthorised && access.canRecordExpenses,
                        )
                    ) {
                        outcome = RejectedExpenseActionOutcome.ACCOUNT_CHANGED
                        return@commitIfCurrent
                    }
                    val currentExpense = db.financeDao().expenseByLocalId(row.localId)
                    val currentReceipts = db.financeDao().expenseReceiptsForExpense(row.localId)
                    if (currentExpense != capturedExpense || currentReceipts != capturedReceipts) {
                        outcome = RejectedExpenseActionOutcome.CHANGED
                        return@commitIfCurrent
                    }
                    outcome = if (accepted != null) {
                        db.financeDao().confirmExpense(
                            capturedExpense.localId,
                            accepted.toFinanceCache(),
                        )
                        RejectedExpenseActionOutcome.SERVER_ALREADY_ACCEPTED
                    } else if (
                        db.financeDao().discardRejectedExpenseAction(
                            capturedExpense,
                            capturedReceipts,
                        )
                    ) {
                        RejectedExpenseActionOutcome.DISCARDED
                    } else {
                        RejectedExpenseActionOutcome.CHANGED
                    }
                }
                if (committed) {
                    when (outcome) {
                        RejectedExpenseActionOutcome.DISCARDED -> {
                            dialog.value = null
                            notice.value =
                                "The server proved this expense action was absent. The exact rejected expense and its saved receipts were removed."
                        }
                        RejectedExpenseActionOutcome.SERVER_ALREADY_ACCEPTED -> {
                            dialog.value = null
                            notice.value =
                                "The server already recorded this exact expense. It was marked synced and its saved receipts will upload normally."
                            appCtx.sync.requestSync()
                        }
                        RejectedExpenseActionOutcome.ACCOUNT_CHANGED ->
                            formError.value =
                                "The account or Finance permission changed. Nothing was removed."
                        RejectedExpenseActionOutcome.CHANGED ->
                            formError.value =
                                "The saved expense changed during the check. Nothing was removed."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (appCtx.cacheIsolation.currentLease() == lease) {
                    formError.value =
                        "The authoritative server check could not be completed. Nothing was removed; update the server, reconnect and try again."
                }
            } finally {
                if (appCtx.cacheIsolation.currentLease() == lease) busy.value = false
            }
        }
    }

    fun discardRejectedExpenseReceipt(row: PendingExpenseReceiptRow) {
        if (busy.value || !row.rejected || !requireExpenseWrite()) return
        if (!appCtx.connectivity.online.value) {
            formError.value =
                "Reconnect before checking the server. Nothing was removed from this tablet."
            return
        }
        val expenseServerId = row.expenseServerId ?: run {
            formError.value =
                "The expense has not been confirmed by the server. Nothing was removed; retry the expense first."
            return
        }
        val lease = appCtx.cacheIsolation.currentLease() ?: run {
            formError.value = "This account is still opening. Nothing was removed; try again in a moment."
            return
        }
        val profile = appCtx.shiftCache.profile.value ?: run {
            formError.value = "The signed-in account could not be verified. Nothing was removed."
            return
        }
        val capturedOwner = OutboxOwnerIdentity.from(profile)
        if (!expenseReceiptDiscardScopeMatches(
                capturedOwner = capturedOwner,
                currentOwner = capturedOwner,
                durableOutboxOwner = appCtx.outboxOwnerStore.owner(),
                cacheScope = lease.scope,
                hasFinanceWrite = EffectivePermissions.from(profile)
                    .financeAccess().canRecordExpenses,
            )
        ) {
            formError.value =
                "This saved receipt belongs to a different account scope. Nothing was removed."
            return
        }

        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                val serverReceipts = withTimeout(EXPENSE_RECEIPT_RECONCILIATION_TIMEOUT_MILLIS) {
                    financeApi.expenseReceipts(expenseServerId)
                }
                when (val reconciliation = reconcileRejectedExpenseReceipt(
                    expenseServerId = expenseServerId,
                    contentSha256 = row.contentSha256,
                    serverReceipts = serverReceipts,
                )) {
                    is ExpenseReceiptReconciliation.Unverifiable -> {
                        if (appCtx.cacheIsolation.currentLease() == lease) {
                            formError.value = reconciliation.message
                        }
                    }
                    else -> {
                        var outcome = ExpenseReceiptDiscardCommitOutcome.CHANGED
                        val committed = appCtx.cacheIsolation.commitIfCurrent(lease) {
                            val currentProfile = appCtx.shiftCache.profile.value
                            val stillAuthorised = currentProfile?.let {
                                EffectivePermissions.from(it).financeAccess().canRecordExpenses
                            } == true
                            if (!expenseReceiptDiscardScopeMatches(
                                    capturedOwner = capturedOwner,
                                    currentOwner = currentProfile?.let { OutboxOwnerIdentity.from(it) },
                                    durableOutboxOwner = appCtx.outboxOwnerStore.owner(),
                                    cacheScope = lease.scope,
                                    hasFinanceWrite = stillAuthorised && access.canRecordExpenses,
                                )
                            ) {
                                outcome = ExpenseReceiptDiscardCommitOutcome.ACCOUNT_CHANGED
                                return@commitIfCurrent
                            }
                            val current = db.financeDao()
                                .expenseReceiptWithExpenseByLocalId(row.localId)
                            val unchanged = current != null &&
                                current.receipt.expenseLocalId == row.expenseLocalId &&
                                current.receipt.contentSha256 == row.contentSha256 &&
                                current.receipt.syncState == SyncState.REJECTED &&
                                current.receipt.serverReceiptId == null &&
                                current.expenseServerId == expenseServerId &&
                                (lease.scope.branchId == null ||
                                    current.expenseBranchId == lease.scope.branchId)
                            if (!unchanged) return@commitIfCurrent

                            outcome = when (reconciliation) {
                                is ExpenseReceiptReconciliation.AlreadyAccepted -> {
                                    db.financeDao().confirmExpenseReceipt(
                                        row.localId,
                                        reconciliation.serverReceiptId,
                                    )
                                    ExpenseReceiptDiscardCommitOutcome.SERVER_ALREADY_ACCEPTED
                                }
                                ExpenseReceiptReconciliation.ConfirmedAbsent -> {
                                    DiagnosticsRuntime
                                        .recordRejectedExpenseReceiptDiscardAuthorised()
                                    val removed = db.financeDao().discardRejectedExpenseReceiptEvidence(
                                        localId = row.localId,
                                        expenseLocalId = row.expenseLocalId,
                                        contentSha256 = row.contentSha256,
                                    ) == 1
                                    if (removed) {
                                        ExpenseReceiptDiscardCommitOutcome.DISCARDED
                                    } else {
                                        ExpenseReceiptDiscardCommitOutcome.CHANGED
                                    }
                                }
                                is ExpenseReceiptReconciliation.Unverifiable ->
                                    ExpenseReceiptDiscardCommitOutcome.CHANGED
                            }
                        }
                        // Scope activation clears this ViewModel's sensitive
                        // state. Publish only while the captured account still
                        // owns the workspace.
                        if (committed) {
                            when (outcome) {
                                ExpenseReceiptDiscardCommitOutcome.DISCARDED -> {
                                    dialog.value = null
                                    notice.value =
                                        "The server confirmed this file was absent. Only the rejected saved receipt was removed; the expense remains recorded."
                                }
                                ExpenseReceiptDiscardCommitOutcome.SERVER_ALREADY_ACCEPTED -> {
                                    dialog.value = null
                                    notice.value =
                                        "The server already had this exact receipt. It was marked synced and the duplicate local bytes were cleared."
                                }
                                ExpenseReceiptDiscardCommitOutcome.ACCOUNT_CHANGED -> {
                                    formError.value =
                                        "The account or Finance permission changed. Nothing was removed."
                                }
                                ExpenseReceiptDiscardCommitOutcome.CHANGED -> {
                                    formError.value =
                                        "This receipt changed while the server was checked. Nothing was removed; refresh Finance and review it again."
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (appCtx.cacheIsolation.currentLease() == lease) {
                    formError.value =
                        "The server receipt check could not be completed. Nothing was removed; reconnect and try again."
                }
            } finally {
                if (appCtx.cacheIsolation.currentLease() == lease) busy.value = false
            }
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

private enum class ExpenseReceiptDiscardCommitOutcome {
    DISCARDED,
    SERVER_ALREADY_ACCEPTED,
    ACCOUNT_CHANGED,
    CHANGED,
}

private enum class RejectedExpenseActionOutcome {
    DISCARDED,
    SERVER_ALREADY_ACCEPTED,
    ACCOUNT_CHANGED,
    CHANGED,
}

private fun ExpenseCacheEntity.toExpense(): Expense = Expense(
    id = id, branchId = branchId, categoryId = categoryId, supplierId = supplierId,
    amountMinor = amountMinor, paidVia = paidVia, paidAt = paidAt,
    vendorName = vendorName, invoiceNo = invoiceNo, note = note,
    receiptCount = receiptCount, receiptStatus = receiptStatus,
    shiftId = shiftId, createdBy = createdBy, isVoided = isVoided,
    sourceShiftStatus = sourceShiftStatus, isCorrected = isCorrected,
    correction = if (isCorrected && correctionReason != null && correctionAt != null) {
        FinanceSourceCorrection(reason = correctionReason, correctedAt = correctionAt)
    } else {
        null
    },
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

private fun LocalExpenseReceiptWithExpense.toPendingRow(): PendingExpenseReceiptRow =
    PendingExpenseReceiptRow(
        localId = receipt.localId,
        expenseLocalId = receipt.expenseLocalId,
        expenseServerId = expenseServerId,
        contentSha256 = receipt.contentSha256,
        filename = receipt.filename,
        waitingForExpense = expenseServerId == null,
        rejected = receipt.syncState == SyncState.REJECTED,
        error = receipt.lastError,
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
