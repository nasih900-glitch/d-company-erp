package cloud.dcompany.erp.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.room.withTransaction
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.alarm.GamingAlarmReconciler
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.auth.OutboxGateResult
import cloud.dcompany.erp.core.auth.OutboxSafetyGate
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.checkout.HeldOrderClaimPolicy
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.AssetCacheEntity
import cloud.dcompany.erp.core.db.CafeTableEntity
import cloud.dcompany.erp.core.db.CapitalEntryCacheEntity
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.EventCacheEntity
import cloud.dcompany.erp.core.db.EventTicketCacheEntity
import cloud.dcompany.erp.core.db.ExpenseCacheEntity
import cloud.dcompany.erp.core.db.FloorEntity
import cloud.dcompany.erp.core.db.LocalAssetEntity
import cloud.dcompany.erp.core.db.LocalCapitalEntryEntity
import cloud.dcompany.erp.core.db.LocalCheckInEntity
import cloud.dcompany.erp.core.db.CustomerMembershipCacheEntity
import cloud.dcompany.erp.core.db.CustomerMembershipHistoryCacheEntity
import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.LocalMembershipCancellationEntity
import cloud.dcompany.erp.core.db.LocalMembershipRefundEntity
import cloud.dcompany.erp.core.db.LocalMembershipPaymentActionEntity
import cloud.dcompany.erp.core.db.LocalMembershipRefundActionEntity
import cloud.dcompany.erp.core.db.LocalSubscriptionEntity
import cloud.dcompany.erp.core.db.LocalTicketSaleEntity
import cloud.dcompany.erp.core.db.MembershipTierCacheEntity
import cloud.dcompany.erp.core.db.MembershipMoneyActionState
import cloud.dcompany.erp.core.db.MembershipPaymentActionKind
import cloud.dcompany.erp.core.db.MembershipPaymentTaskCacheEntity
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundActionKind
import cloud.dcompany.erp.core.db.MembershipRefundTaskCacheEntity
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.BatchCacheEntity
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.IngredientCacheEntity
import cloud.dcompany.erp.core.db.IngredientWriteState
import cloud.dcompany.erp.core.db.KitchenOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalAdjustmentEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import cloud.dcompany.erp.core.db.LocalGrnEntity
import cloud.dcompany.erp.core.db.LocalGrnLineEntity
import cloud.dcompany.erp.core.db.LocalIngredientEntity
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.db.LocalKitchenCancellationAckEntity
import cloud.dcompany.erp.core.db.LocalCustomerEntity
import cloud.dcompany.erp.core.db.LocalMenuCategoryEntity
import cloud.dcompany.erp.core.db.LocalMenuItemEntity
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.LocalRefundEntity
import cloud.dcompany.erp.core.db.LocalStaffEntity
import cloud.dcompany.erp.core.db.LocalSupplierEntity
import cloud.dcompany.erp.core.db.BranchCacheEntity
import cloud.dcompany.erp.core.db.CompanyCacheEntity
import cloud.dcompany.erp.core.db.LocalBranchEntity
import cloud.dcompany.erp.core.db.LocalCompanyEditEntity
import cloud.dcompany.erp.core.db.LocalTerminalEntity
import cloud.dcompany.erp.core.db.TerminalCacheEntity
import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.OnShiftEntity
import cloud.dcompany.erp.core.db.ReportSnapshotEntity
import cloud.dcompany.erp.core.db.RefundOrderCacheEntity
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.db.RejectedOpenRecoveryResult
import cloud.dcompany.erp.core.db.RejectedOpenRecoveryStatus
import cloud.dcompany.erp.core.db.StaffCacheEntity
import cloud.dcompany.erp.core.db.SupplierCacheEntity
import cloud.dcompany.erp.core.db.SupplierWriteState
import cloud.dcompany.erp.core.db.ShiftActor
import cloud.dcompany.erp.core.db.ShiftCloseCountPolicy
import cloud.dcompany.erp.core.db.ShiftCloseCountValidation
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.SyncMetaEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.BackendReachability
import cloud.dcompany.erp.core.net.backendIsOnline
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import cloud.dcompany.erp.core.net.PaymentRequest
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.core.net.outboxProvenanceHeaders
import cloud.dcompany.erp.ui.screens.customers.CustomerUpdateBody
import cloud.dcompany.erp.ui.screens.customers.CustomerUpsertBody
import cloud.dcompany.erp.ui.screens.customers.CustomersApi
import cloud.dcompany.erp.ui.screens.events.EventsApi
import cloud.dcompany.erp.ui.screens.events.TicketSell
import cloud.dcompany.erp.ui.screens.finance.AssetCreate
import cloud.dcompany.erp.ui.screens.finance.CapitalEntryCreate
import cloud.dcompany.erp.ui.screens.finance.ExpenseCreate
import cloud.dcompany.erp.ui.screens.finance.FinanceApi
import cloud.dcompany.erp.ui.screens.finance.FinanceCacheScope
import cloud.dcompany.erp.ui.screens.finance.FinanceSnapshotKeys
import cloud.dcompany.erp.ui.screens.gaming.GamingApi
import cloud.dcompany.erp.ui.screens.gaming.SessionStartBody
import cloud.dcompany.erp.ui.screens.inventory.AdjustmentBody
import cloud.dcompany.erp.ui.screens.memberships.MembershipsApi
import cloud.dcompany.erp.ui.screens.memberships.CashMembershipRefundSettlementRequest
import cloud.dcompany.erp.ui.screens.memberships.CashMembershipRefundWithdrawalRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentAttemptResolutionRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentCashCollectionRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentFinalizationRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentProviderActionRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentRequestCreate
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentSettlementRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentTask
import cloud.dcompany.erp.ui.screens.memberships.MembershipPaymentWithdrawalRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundAttemptRegistrationRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundAttemptResolutionRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundCashHandoffRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundFinalizationRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundProviderActionRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundResolutionRequest
import cloud.dcompany.erp.ui.screens.memberships.MembershipRefundTask
import cloud.dcompany.erp.ui.screens.memberships.ProviderMembershipRefundSettlementRequest
import cloud.dcompany.erp.ui.screens.memberships.SubscribeRequest
import cloud.dcompany.erp.ui.screens.inventory.GrnBody
import cloud.dcompany.erp.ui.screens.inventory.GrnLineBody
import cloud.dcompany.erp.ui.screens.inventory.IngredientCreate
import cloud.dcompany.erp.ui.screens.inventory.IngredientUpdate
import cloud.dcompany.erp.ui.screens.inventory.InventoryApi
import cloud.dcompany.erp.ui.screens.inventory.SupplierBody
import cloud.dcompany.erp.ui.screens.kitchen.KitchenApi
import cloud.dcompany.erp.ui.screens.kitchen.KitchenStateUpdate
import cloud.dcompany.erp.ui.screens.menu.CategoryCreateBody
import cloud.dcompany.erp.ui.screens.menu.CategoryUpdateBody
import cloud.dcompany.erp.ui.screens.menu.ItemDetailsUpdateBody
import cloud.dcompany.erp.ui.screens.menu.MenuApi
import cloud.dcompany.erp.ui.screens.refunds.PosRefundCashSettlementBody
import cloud.dcompany.erp.ui.screens.refunds.PosRefundHandoffBody
import cloud.dcompany.erp.ui.screens.refunds.PosRefundRequestBody
import cloud.dcompany.erp.ui.screens.refunds.PosRefundRequestResult
import cloud.dcompany.erp.ui.screens.refunds.PosRefundWithdrawalBody
import cloud.dcompany.erp.ui.screens.refunds.RefundsApi
import cloud.dcompany.erp.ui.screens.settings.BranchWriteBody
import cloud.dcompany.erp.ui.screens.settings.CompanyUpdateBody
import cloud.dcompany.erp.ui.screens.settings.SettingsApi
import cloud.dcompany.erp.ui.screens.settings.TerminalCreateBody
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import cloud.dcompany.erp.ui.screens.shift.ShiftCloseBody
import cloud.dcompany.erp.ui.screens.shift.ShiftOpenBody
import cloud.dcompany.erp.ui.screens.staff.StaffApi
import cloud.dcompany.erp.ui.screens.staff.StaffUserUpdateBody
import cloud.dcompany.erp.ui.screens.tables.TablesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val REFRESH_LOG_TAG = "DCompanySync"

private class FinanceReferenceRefreshException(labels: List<String>) : Exception(
    "Finance totals refreshed, but ${labels.joinToString(" and ")} could not be refreshed",
)

/**
 * Watches effective ERP connectivity, not merely "Wi-Fi associated".
 *
 * Android validation proves a general internet route; the backend tracker
 * proves whether the ERP API itself answered. Both are required after a
 * transport failure, which is what lets the UI distinguish cafe Wi-Fi loss
 * from a reachable network whose ERP endpoint is unavailable.
 */
internal class ConnectivityObserver(
    context: Context,
    scope: CoroutineScope,
    backendReachability: StateFlow<BackendReachability>,
) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _networkValidated = MutableStateFlow(false)
    val networkValidated: StateFlow<Boolean> = _networkValidated.asStateFlow()
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var onRegained: (() -> Unit)? = null
    @Volatile private var latestBackendReachability = BackendReachability.UNKNOWN

    init {
        scope.launch {
            backendReachability.collect { backend ->
                latestBackendReachability = backend
                updateEffectiveOnline(
                    backendIsOnline(
                        networkValidated = _networkValidated.value,
                        backendReachability = backend,
                    ),
                )
            }
        }
    }

    fun start(onBackOnline: () -> Unit) {
        onRegained = onBackOnline
        updateNetworkValidation(currentlyValidated())
        // registerDefaultNetworkCallback, NOT a capability-filtered request.
        // The filtered form only reports networks that already match, so in the
        // trial run toggling airplane mode never produced a callback: the
        // banner stayed hidden and the queue never drained. The default
        // callback reports every transition, and onCapabilitiesChanged is what
        // actually fires when a link becomes validated.
        manager?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) = refresh()
            },
        )
    }

    private fun refresh() {
        updateNetworkValidation(currentlyValidated())
    }

    private fun updateNetworkValidation(nowValidated: Boolean) {
        val networkWasUnavailable = !_networkValidated.value
        _networkValidated.value = nowValidated
        updateEffectiveOnline(
            backendIsOnline(
                networkValidated = nowValidated,
                backendReachability = latestBackendReachability,
            ),
        )
        // If internet returned while the last API probe was unreachable, the
        // effective state intentionally remains offline. Trigger one probe so
        // a successful HTTP response can prove recovery and clear the banner.
        if (
            nowValidated && networkWasUnavailable &&
            latestBackendReachability == BackendReachability.UNREACHABLE
        ) {
            onRegained?.invoke()
        }
    }

    private fun updateEffectiveOnline(nowOnline: Boolean) {
        val wasOffline = !_online.value
        _online.value = nowOnline
        if (nowOnline && wasOffline) onRegained?.invoke()
    }

    private fun currentlyValidated(): Boolean {
        val active = manager?.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Thread-safe state machine for conflating fire-and-forget sync requests.
 *
 * One worker owns a sequence of passes. Requests made before a pass begins are
 * covered by that pass; any number made while it is running become exactly one
 * trailing pass. The worker retires atomically only after observing no pending
 * request, so a request at the pass/idle boundary cannot be stranded.
 */
internal class ConflatedSyncRequestGate {
    private var workerRunning = false
    private var requestPending = false

    /** Returns true only for the caller responsible for starting the worker. */
    @Synchronized
    fun request(): Boolean {
        requestPending = true
        if (workerRunning) return false
        workerRunning = true
        return true
    }

    /** Called under SyncEngine's pass mutex immediately before a worker pass. */
    @Synchronized
    fun claimPass(): Boolean {
        if (!requestPending) {
            workerRunning = false
            return false
        }
        requestPending = false
        return true
    }

    /** Returns true when at least one request arrived during the preceding pass. */
    @Synchronized
    fun finishPass(): Boolean {
        if (requestPending) return true
        workerRunning = false
        return false
    }

    /** A direct, awaited pass starting now also covers every older queued request. */
    @Synchronized
    fun absorbPendingIntoDirectPass() {
        requestPending = false
    }
}

/**
 * Keeps a pull failure at the resource boundary that initiated it. A realtime
 * collector is process-long; letting a converter, mapping, or Room exception
 * escape from one refresh would cancel that collector and silently stop every
 * later cross-device update until the app restarted.
 *
 * Coroutine cancellation is control flow, not a refresh failure, and must
 * always propagate to the owning scope.
 */
sealed interface ResourceRefreshResult {
    val resource: String

    data class Refreshed(override val resource: String) : ResourceRefreshResult
    data class Skipped(override val resource: String) : ResourceRefreshResult
    data class Failed(
        override val resource: String,
        val userMessage: String,
    ) : ResourceRefreshResult
}

/**
 * Same-resource fetch-and-commit and confirmed-write projections are ordered
 * even when screen polling, realtime and an outbox drain touch one cache at
 * once. Different resources remain independent, so a slow finance read cannot
 * freeze the KDS.
 */
internal class ResourceRefreshSerialiser {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> run(resource: String, block: suspend () -> T): T {
        val key = canonicalRefreshResource(resource)
        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock { block() }
    }

    /** Acquire a resource set once, in a stable order, to prevent inversion. */
    suspend fun <T> runAll(
        resources: Collection<String>,
        block: suspend () -> T,
    ): T {
        val keys = canonicalResourceLockOrder(resources)

        suspend fun acquire(index: Int): T = if (index >= keys.size) {
            block()
        } else {
            run(keys[index]) { acquire(index + 1) }
        }
        return acquire(0)
    }
}

internal fun canonicalResourceLockOrder(resources: Collection<String>): List<String> = resources
    .map(::canonicalRefreshResource)
    .distinct()
    .sorted()

/** The observable, resource-scoped read status consumed by feature ViewModels. */
internal class ResourceRefreshFeedbackStore {
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    fun record(result: ResourceRefreshResult) {
        _errors.update { current ->
            when (result) {
                is ResourceRefreshResult.Failed ->
                    current + (result.resource to result.userMessage)
                is ResourceRefreshResult.Refreshed,
                is ResourceRefreshResult.Skipped,
                -> current - result.resource
            }
        }
    }
}

internal suspend fun runResourceRefresh(
    resource: String,
    pull: suspend () -> Unit,
    logFailure: (resource: String, failure: Throwable) -> Unit,
): ResourceRefreshResult {
    val key = canonicalRefreshResource(resource)
    return try {
        pull()
        ResourceRefreshResult.Refreshed(key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logFailure(key, failure)
        ResourceRefreshResult.Failed(
            resource = key,
            userMessage = resourceRefreshFailureMessage(key, failure),
        )
    }
}

internal fun canonicalRefreshResource(resource: String): String = resource
    .trim()
    .lowercase(Locale.ROOT)
    .ifEmpty { "requested" }

internal fun resourceRefreshFailureMessage(resource: String, failure: Throwable): String {
    val label = canonicalRefreshResource(resource)
        .replace('_', ' ')
    val retryGuidance =
        "Saved data is still available. Try again; if it continues, ask a manager for help."
    val serverReason = when (failure) {
        is ApiException -> failure.message
        is FinanceReferenceRefreshException -> failure.message
        else -> null
    }
        ?.trim()
        ?.trimEnd('.')
        ?.takeIf(String::isNotEmpty)
    return if (serverReason == null) {
        "Could not refresh $label data on this tablet. $retryGuidance"
    } else {
        "Could not refresh $label data: $serverReason. $retryGuidance"
    }
}

enum class RejectedShiftOpenVerificationStatus {
    CLEARED,
    SERVER_SHIFT_RECONCILED,
    BLOCKED,
    FAILED,
}

data class RejectedShiftOpenVerificationResult(
    val status: RejectedShiftOpenVerificationStatus,
    val message: String,
) {
    val succeeded: Boolean
        get() = status == RejectedShiftOpenVerificationStatus.CLEARED ||
            status == RejectedShiftOpenVerificationStatus.SERVER_SHIFT_RECONCILED
}

private fun RejectedOpenRecoveryResult.toVerificationResult(): RejectedShiftOpenVerificationResult =
    RejectedShiftOpenVerificationResult(
        status = when (status) {
            RejectedOpenRecoveryStatus.APPLIED -> RejectedShiftOpenVerificationStatus.CLEARED
            RejectedOpenRecoveryStatus.DISCARDED -> RejectedShiftOpenVerificationStatus.CLEARED
            RejectedOpenRecoveryStatus.CHANGED,
            RejectedOpenRecoveryStatus.WRONG_SCOPE,
            RejectedOpenRecoveryStatus.SERVER_SHIFT_PRESENT,
            RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT,
            RejectedOpenRecoveryStatus.LIVE_VERIFICATION_REQUIRED,
            RejectedOpenRecoveryStatus.DEPENDENT_WORK -> RejectedShiftOpenVerificationStatus.BLOCKED
        },
        message = message,
    )

/**
 * Pulls reference data down and drains captured sales up.
 *
 * Ordering matters: sales are pushed *before* the menu is refreshed, so a
 * queued sale is priced against the menu it was actually taken on rather than
 * against a menu that changed while the tablet was offline.
 */
class SyncEngine(
    private val db: ErpDatabase,
    private val scope: CoroutineScope,
    private val outboxSafety: OutboxSafetyGate,
    private val cacheIsolation: CacheIsolationCoordinator,
) {

    private val shiftApi = ApiClient.create<ShiftApi>()
    private val gamingApi = ApiClient.create<GamingApi>()
    private val kitchenApi = ApiClient.create<KitchenApi>()
    private val tablesApi = ApiClient.create<TablesApi>()
    private val cafeOrderSync = CafeOrderSyncCoordinator(db, db.cafeOrderDao(), tablesApi)
    private val refundsApi = ApiClient.create<RefundsApi>()
    private val customersApi = ApiClient.create<CustomersApi>()
    private val menuApi = ApiClient.create<MenuApi>()
    private val staffApi = ApiClient.create<StaffApi>()
    private val inventoryApi = ApiClient.create<InventoryApi>()
    private val financeApi = ApiClient.create<FinanceApi>()
    private val eventsApi = ApiClient.create<EventsApi>()
    private val membershipsApi = ApiClient.create<MembershipsApi>()
    private val settingsApi = ApiClient.create<SettingsApi>()
    private val mutex = Mutex()
    private val syncRequests = ConflatedSyncRequestGate()
    private val resourceRefreshSerialiser = ResourceRefreshSerialiser()
    private val resourceRefreshFeedback = ResourceRefreshFeedbackStore()
    private data class ActiveBatchTarget(
        val ingredientId: String,
        val lease: CacheScopeLease,
    )

    private val activeBatchTarget = AtomicReference<ActiveBatchTarget?>(null)
    /**
     * Set when a write may have reached the server but no definitive response
     * came back. A close must never overtake that unknown write: if it did,
     * the retry could later be refused because the shift had already closed.
     * Access is serialized by [mutex].
     */
    private var passHadAmbiguousFailure = false

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Screen-facing pull failures. A kitchen failure cannot masquerade as a Tables error. */
    val resourceRefreshErrors: StateFlow<Map<String, String>> = resourceRefreshFeedback.errors

    /*
     * Shift history has its own read status.  The global sync error is useful
     * for the app-wide banner, but must never be presented as though a shift
     * history refresh failed: an inventory/menu/refund write can fail during
     * the same pass while the history cache is perfectly healthy.
     */
    private val _shiftHistoryRefreshing = MutableStateFlow(false)
    val shiftHistoryRefreshing: StateFlow<Boolean> = _shiftHistoryRefreshing.asStateFlow()

    private val _shiftHistoryError = MutableStateFlow<String?>(null)
    val shiftHistoryError: StateFlow<String?> = _shiftHistoryError.asStateFlow()

    val pendingCount = db.orderDao().observePendingCount()
    val rejectedCount = db.orderDao().observeRejectedCount()

    /**
     * Every outbox's rejected count combined, so a rejected row on a resource
     * nobody currently has open (e.g. a shift, once other resources join this
     * later) is never invisible. A *pending* row isn't included the same way —
     * it will sync on its own given time and doesn't need cross-screen
     * attention the way a stuck, rejected one does. Grows as more resources
     * gain an outbox — see the plan this phase started from.
     */
    val totalRejectedCount = combine(
        rejectedCount,
        db.shiftDao().observeRejectedCount(),
        db.gamingDao().observeRejectedCount(),
        combine(
            db.kitchenDao().observeRejectedCount(),
            db.kitchenDao().observeRejectedCancellationAckCount(),
        ) { advances, acknowledgements -> advances + acknowledgements },
        // Kotlin's combine() only has typed overloads up to 5 flows — the
        // rest are pre-summed in a nested 5-arg combine (itself at the
        // typed-overload cap) rather than adding more top-level arguments,
        // or piling into a bigger tuple, which would just move the same
        // limit one level down (see the same fix in TablesViewModel.state).
        combine(
            combine(
                db.tablesDao().observeRejectedCount(),
                db.cafeOrderDao().observeBlockedActionCount(),
            ) { legacy, cafe -> legacy + cafe },
            db.refundDao().observeRejectedCount(),
            db.customerDao().observeRejectedCount(),
            db.menuWriteDao().observeRejectedCategoryCount(),
            // One more resource than fits this inner combine's own 5-arg cap
            // — nested one level deeper rather than restructuring the levels
            // above, which already have real, unrelated meaning (orders vs.
            // shifts vs. gaming vs. kitchen). Same pre-sum technique, just
            // applied again as the resource count grows past 10.
            combine(
                db.menuWriteDao().observeRejectedItemCount(),
                db.staffDao().observeRejectedCount(),
                // Same trick a third time: 4 more inventory sources than fit
                // this combine's own remaining slots, pre-summed one level
                // deeper again.
                combine(
                    db.inventoryDao().observeRejectedIngredientCount(),
                    db.inventoryDao().observeRejectedSupplierCount(),
                    db.inventoryDao().observeRejectedGrnCount(),
                    db.inventoryDao().observeRejectedAdjustmentCount(),
                ) { ingredients, suppliers, grns, adjustments ->
                    ingredients + suppliers + grns + adjustments
                },
                // Fourth application of the same pre-sum trick: 3 new Finance
                // sources (expense/asset/capital-entry) than fit this
                // combine's own remaining slots.
                combine(
                    db.financeDao().observeRejectedExpenseCount(),
                    db.financeDao().observeRejectedAssetCount(),
                    db.financeDao().observeRejectedCapitalEntryCount(),
                ) { expenses, assets, capitalEntries -> expenses + assets + capitalEntries },
                // Fifth application: 2 Events sources (ticket sale/check-in)
                // filled level 3's last remaining slot — so the 2 new
                // Memberships sources (subscribe/cancel) nest one level
                // deeper again, inside this same slot, rather than
                // widening level 3 past its 5-arg cap. Settings' 3 sources
                // (company edit/branch/terminal) join as a fourth argument
                // at this same nested level — still within its own 5-cap.
                combine(
                    db.eventDao().observeRejectedTicketSaleCount(),
                    db.eventDao().observeRejectedCheckInCount(),
                    combine(
                        db.membershipDao().observeRejectedSubscriptionCount(),
                        db.membershipDao().observeRejectedCancellationCount(),
                        db.membershipDao().observeRejectedRefundCount(),
                    ) { subscriptions, cancellations, refunds ->
                        subscriptions + cancellations + refunds
                    },
                    combine(
                        db.settingsDao().observeRejectedCompanyEditCount(),
                        db.settingsDao().observeRejectedBranchCount(),
                        db.settingsDao().observeRejectedTerminalCount(),
                    ) { companyEdit, branches, terminals -> companyEdit + branches + terminals },
                    // Last free slot at this nesting level — a rejected
                    // held-order payment ("real cash already left the
                    // drawer" the same way a rejected refund is, see
                    // RefundDao's own doc comment) needs to be just as
                    // visible as everything else here.
                    db.heldOrderDao().observeRejectedPaymentCount(),
                ) { ticketSales, checkIns, memberships, settings, heldPayments ->
                    ticketSales + checkIns + memberships + settings + heldPayments
                },
            ) { menuItems, staff, inventory, finance, events ->
                menuItems + staff + inventory + finance + events
            },
        ) { tables, refunds, customers, menuCategories, rest ->
            tables + refunds + customers + menuCategories + rest
        },
    ) { orders, shifts, gaming, kitchen, rest ->
        orders + shifts + gaming + kitchen + rest
    }

    /**
     * Pull-only resources, fetched when a screen using them opens or a
     * realtime "changed" event names them — not on every sync(), which would
     * otherwise fan every reconnect out into GETs for screens nobody has
     * open. Push (the outbox drain) is always unconditional in sync(); this
     * map is purely the read side.
     */
    private val onDemandPulls: Map<String, suspend () -> Unit> = mapOf(
        "shifts" to { pullOpenShift(); pullShiftHistory() },
        "gaming" to ::pullGamingData,
        "kitchen" to ::pullKitchenData,
        "tables" to ::pullTablesData,
        // POS and Menu need a narrow read path on first open. Routing this
        // through the broad outbox drain means an unrelated earlier failure
        // can leave a perfectly reachable menu blank with no way to fetch it.
        "menu" to ::pullMenu,
        // "orders" is the same realtime resource POS/Kitchen already broadcast
        // on (backend _PATH_RESOURCE_MAP maps /pos/orders, including the
        // nested /refunds route, to "orders") — reusing it means another
        // terminal issuing a refund or taking a payment already wakes this
        // pull, no new backend resource needed. Held orders piggyback on the
        // same trigger for the same reason — a table sending an order to POS
        // hits this same path, so the till's queue updates the instant it does.
        "orders" to { pullRefundableOrders(); pullHeldOrders() },
        "customers" to ::pullCustomers,
        "staff" to ::pullStaff,
        "attendance" to ::pullOnShift,
        // Backend's realtime resource map deliberately does not split
        // inventory into ingredients/suppliers/grn/adjustments — everything
        // under /inventory broadcasts as one combined "inventory" resource,
        // so one write anywhere in the module refreshes both caches here.
        "inventory" to ::pullInventoryOnDemand,
        // Same reasoning for finance: /finance/* broadcasts as one combined
        // "finance" resource. Capital entries are per-partner and demand-
        // loaded on selection instead (see pullCapitalEntriesFor), same
        // shape as pullBatchesFor.
        "finance" to ::pullFinanceOnDemand,
        // /events/* broadcasts as one combined "events" resource too.
        // Per-event ticket lists are demand-loaded on selection instead
        // (see pullTicketsFor), same shape as pullBatchesFor.
        "events" to ::pullEventsData,
        // /memberships/* broadcasts as one combined "memberships" resource.
        // Per-customer membership status is demand-loaded on selection
        // instead (see pullMembershipFor), same shape as pullBatchesFor.
        "memberships" to {
            pullTiers()
            pullMembershipPaymentTasksBestEffort()
            pullMembershipRefundStateBestEffort()
        },
        // /settings/* broadcasts as one combined "settings" resource —
        // company profile, branches, and terminals all refresh together.
        "settings" to ::pullSettingsData,
    )

    /**
     * A screen-open or realtime pull is a failure boundary: expected API
     * failures and unexpected local conversion/Room failures both preserve
     * the cached screen and publish an actionable resource-specific notice.
     * Only coroutine cancellation escapes. This is especially important for
     * realtime callers, whose process-long collector must survive one bad
     * resource and continue receiving later events.
    */
    suspend fun refresh(resource: String): ResourceRefreshResult {
        val key = canonicalRefreshResource(resource)
        return withResourceSerialisation(key) { refreshAlreadyLocked(key) }
    }

    /**
     * Execute a complete fetch-and-commit or confirmed-write projection while
     * holding the canonical resource lock. Callers must acquire the broad sync
     * mutex first (when they need it), then this lock, and must never re-enter
     * this same resource through [refresh] from inside [block].
     */
    private suspend fun <T> withResourceSerialisation(
        resource: String,
        block: suspend () -> T,
    ): T = resourceRefreshSerialiser.run(resource, block)

    /**
     * Cross-resource writes acquire distinct canonical locks in sorted order.
     * This makes the order independent of call-site argument order and rules
     * out gaming/orders or tables/orders lock inversion.
     */
    private suspend fun <T> withResourceSerialisations(
        vararg resources: String,
        block: suspend () -> T,
    ): T = resourceRefreshSerialiser.runAll(resources.asList(), block)

    /** Performs one refresh after its canonical resource lock is already held. */
    private suspend fun refreshAlreadyLocked(key: String): ResourceRefreshResult {
        val pull = onDemandPulls[key]
        val result = if (pull == null || !currentResourceAccess().canPull(key)) {
            ResourceRefreshResult.Skipped(key)
        } else {
            return runAndRecordRefreshAlreadyLocked(key, pull)
        }
        resourceRefreshFeedback.record(result)
        return result
    }

    /** Canonical [key] lock is already held; cancellation remains control flow. */
    private suspend fun runAndRecordRefreshAlreadyLocked(
        key: String,
        pull: suspend () -> Unit,
    ): ResourceRefreshResult {
        val result = runResourceRefresh(
            resource = key,
            pull = pull,
            logFailure = { failedResource, failure ->
                Log.e(
                    REFRESH_LOG_TAG,
                    "Resource refresh failed: $failedResource",
                    failure,
                )
            },
        )
        resourceRefreshFeedback.record(result)
        if (result is ResourceRefreshResult.Failed) {
            // Compatibility for the existing app-wide sync banner. New
            // feature feedback must use resourceRefreshErrors instead.
            _lastError.value = result.userMessage
        }
        return result
    }

    /**
     * Login must not clear the terminal's replaceable shift authority cache
     * while an older shift GET is still able to commit. Clear and repopulate
     * it as one ordered shift-resource operation.
     */
    suspend fun refreshShiftAuthorityAtLogin(): ResourceRefreshResult =
        withResourceSerialisation("shifts") {
            val terminalId = DCompanyApp.instance.terminalStore.terminalId()
                ?: return@withResourceSerialisation ResourceRefreshResult.Skipped("shifts")
            val lease = cacheIsolation.currentLease()
                ?: return@withResourceSerialisation ResourceRefreshResult.Skipped("shifts")
            if (!commitToCurrentScope(lease) { db.shiftDao().deleteServerOpen(terminalId) }) {
                return@withResourceSerialisation ResourceRefreshResult.Skipped("shifts")
            }
            refreshAlreadyLocked("shifts")
        }

    /** Narrow settings refreshes used after direct online writes. */
    suspend fun refreshSettingsCompany() = withResourceSerialisation("settings") {
        pullCompany()
    }

    suspend fun refreshSettingsBranches() = withResourceSerialisation("settings") {
        pullBranches()
    }

    suspend fun refreshSettingsTerminals() = withResourceSerialisation("settings") {
        pullTerminals()
    }

    /**
     * Finance row caches predate tenant columns. Their scope-change purge must
     * be ordered with finance refresh/push commits. Purge and durable marker
     * update are one scoped Room transaction, so no refresh can land between
     * them. Repeating after a crash is safe and preferable to exposing rows
     * under an unverified scope.
     */
    internal suspend fun clearFinanceReadCachesForScopeChange(scope: FinanceCacheScope): Boolean {
        return withResourceSerialisation("finance") {
            val lease = cacheIsolation.currentLease()
                ?: return@withResourceSerialisation false
            if (
                lease.scope.companyId != scope.companyId ||
                lease.scope.branchId != scope.branchId
            ) return@withResourceSerialisation false
            commitToCurrentScope(lease) {
                db.withTransaction {
                    db.financeDao().clearReadCachesForScopeChange()
                    db.reportSnapshotDao().put(
                        ReportSnapshotEntity(
                            key = FinanceSnapshotKeys.ROW_SCOPE,
                            jsonBody = ApiClient.json.encodeToString(scope),
                            fetchedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Refreshes every cache affected by a realtime write. Keep this separate
     * from [refresh] so opening one screen remains a narrow, single-resource
     * pull while cross-screen invalidation is applied only to realtime events.
     */
    suspend fun refreshRealtime(changedResource: String) {
        for (resource in RealtimeRefreshPolicy.resourcesFor(changedResource)) {
            // refresh() contains its own expected-offline handling, so failure
            // of one dependent pull does not suppress the remaining pulls.
            refresh(resource)
        }
    }

    /** Every on-demand resource — used after a realtime reconnect-after-gap. */
    suspend fun refreshAllOnDemand() {
        for (resource in onDemandPulls.keys) {
            refresh(resource)
        }
    }

    fun requestSync() {
        if (syncRequests.request()) {
            scope.launch { drainRequestedSyncs() }
        }
    }

    /**
     * KDS mutations have no POS/shift dependency. Drain only the kitchen
     * outbox and refresh its authoritative queue, so a kitchen-only account
     * never enters the broad sync pass just because a cook advanced a ticket.
     */
    fun requestKitchenSync() {
        scope.launch {
            mutex.withLock { runKitchenSyncPass() }
        }
    }

    /**
     * Runs one awaited pass for the two flows that need completion semantics.
     * It waits behind an active pass instead of being dropped, and absorbs any
     * older fire-and-forget request that this pass is about to satisfy.
     */
    suspend fun sync() {
        mutex.withLock {
            syncRequests.absorbPendingIntoDirectPass()
            runSyncPass()
        }
    }

    /**
     * Resolve a definitively rejected shift open without deleting evidence.
     *
     * A normal retry is a local CAS handled by ShiftDao and reuses the exact
     * localId/idempotency key. Clearing the attempt is intentionally stricter:
     * this method owns a fresh authenticated GET, validates branch/terminal
     * scope, and mutates Room only in the same cache lease. A matching live
     * opening is linked only after immutable opening facts agree. An unrelated
     * live opening, or no live opening, may clear only an empty attempt; the
     * DAO refuses any discard while captured records use the stable identity.
     */
    suspend fun verifyAndClearRejectedShiftOpen(
        localId: String,
    ): RejectedShiftOpenVerificationResult = mutex.withLock {
        withResourceSerialisation("shifts") {
            verifyAndClearRejectedShiftOpenAlreadyLocked(localId)
        }
    }

    /** Broad sync mutex and canonical shifts-resource lock are already held. */
    private suspend fun verifyAndClearRejectedShiftOpenAlreadyLocked(
        localId: String,
    ): RejectedShiftOpenVerificationResult {
        _syncing.value = true
        _lastError.value = null
        return try {
            val safety = try {
                outboxSafety.canSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "The saved-work owner could not be verified. The rejected shift attempt was not changed.",
                )
            }
            if (safety is OutboxGateResult.Blocked) {
                return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    safety.message,
                )
            }
            if (!currentResourceAccess().canPull("shifts")) {
                return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "This account cannot verify server shifts. Ask an authorised shift user to recover this attempt.",
                )
            }
            val lease = cacheIsolation.currentLease()
                ?: return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "The signed-in account is still being verified. Wait a moment and try again.",
                )
            val terminalId = DCompanyApp.instance.terminalStore.terminalId()
                ?: return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "This tablet has no verified POS terminal. The rejected shift attempt was not changed.",
                )
            val branchId = DCompanyApp.instance.shiftCache.profile.value?.branchId
                ?: return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "This account has no verified branch. The rejected shift attempt was not changed.",
                )
            val local = db.shiftDao().byLocalId(localId)
            val precondition = cloud.dcompany.erp.core.db.rejectedOpenRecoveryPrecondition(
                row = local,
                terminalId = terminalId,
                branchId = branchId,
                // This operation exists specifically to replace possibly
                // stale cache state with a fresh live read below.
                serverShiftPresent = false,
            )
            if (precondition != null) {
                return precondition.toVerificationResult()
            }

            val rows = shiftApi.shifts(onlyOpen = true)
            if (rows.size > 1) {
                return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "The server returned multiple open shifts for this terminal. Nothing was cleared; an owner must reconcile the terminal.",
                )
            }
            val detail = rows.firstOrNull()
            if (
                detail != null && (
                    detail.terminalId != terminalId ||
                        detail.branchId != branchId ||
                        detail.status != "open"
                )
            ) {
                return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "The live server shift did not match this branch and terminal. Nothing was cleared.",
                )
            }
            val verifiedAt = System.currentTimeMillis()
            if (detail != null) {
                val openedAt = runCatching { Instant.parse(detail.openedAt).toEpochMilli() }.getOrNull()
                    ?: return RejectedShiftOpenVerificationResult(
                        RejectedShiftOpenVerificationStatus.BLOCKED,
                        "The live server shift had an invalid opening time. Nothing was cleared.",
                    )
                val cached = detail.toServerOpenShiftCache(
                    terminalId = terminalId,
                    openedAtMillis = openedAt,
                    verifiedAtMillis = verifiedAt,
                )
                var reconciliation: RejectedOpenRecoveryResult? = null
                if (!commitToCurrentScope(lease) {
                    db.withTransaction {
                        db.shiftDao().reconcileServerOpen(terminalId, cached, verifiedAt)
                        db.syncMetaDao().put(SyncMetaEntity("shifts", verifiedAt))
                        reconciliation = db.shiftDao().resolveRejectedOpenAgainstVerifiedServer(
                            localId = localId,
                            terminalId = terminalId,
                            branchId = branchId,
                            serverShiftId = cached.serverShiftId,
                            serverOpenedByUserId = cached.openedByUserId,
                            serverOpeningFloatMinor = cached.openingFloatMinor,
                            serverOpenedAtMillis = cached.openedAtMillis,
                            verifiedAtMillis = verifiedAt,
                        )
                    }
                }) {
                    return RejectedShiftOpenVerificationResult(
                        RejectedShiftOpenVerificationStatus.BLOCKED,
                        "The account changed during verification. Sign in and review the shift again.",
                    )
                }
                val selectedResult = reconciliation ?: RejectedOpenRecoveryResult(
                    RejectedOpenRecoveryStatus.CHANGED,
                    "The rejected shift attempt changed during reconciliation. Refresh and review it again.",
                )
                return when (selectedResult.status) {
                    RejectedOpenRecoveryStatus.APPLIED -> RejectedShiftOpenVerificationResult(
                        RejectedShiftOpenVerificationStatus.SERVER_SHIFT_RECONCILED,
                        selectedResult.message,
                    )
                    else -> selectedResult.toVerificationResult()
                }
            }

            var localResult: RejectedOpenRecoveryResult? = null
            if (!commitToCurrentScope(lease) {
                    db.withTransaction {
                        db.shiftDao().reconcileServerOpen(terminalId, null, verifiedAt)
                        db.syncMetaDao().put(SyncMetaEntity("shifts", verifiedAt))
                        localResult = db.shiftDao().discardVerifiedRejectedOpen(
                            localId = localId,
                            terminalId = terminalId,
                            branchId = branchId,
                            verifiedAtMillis = verifiedAt,
                        )
                    }
                }
            ) {
                return RejectedShiftOpenVerificationResult(
                    RejectedShiftOpenVerificationStatus.BLOCKED,
                    "The account changed during verification. Sign in and review the shift again.",
                )
            }
            return (localResult ?: RejectedOpenRecoveryResult(
                RejectedOpenRecoveryStatus.CHANGED,
                "The rejected shift attempt changed during verification. Refresh and review it again.",
            )).toVerificationResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = if (failure is ApiException) {
                failure.message ?: "The live server check failed. Nothing was cleared."
            } else {
                "The live server check failed on this tablet. Nothing was cleared; try again."
            }
            _lastError.value = message
            RejectedShiftOpenVerificationResult(
                RejectedShiftOpenVerificationStatus.FAILED,
                message,
            )
        } finally {
            _syncing.value = false
        }
    }

    /**
     * Opens the server-side cash handover window and waits for its durable
     * response. This leg is intentionally not an offline fire-and-forget
     * action: staff may touch the drawer only after the returned server state
     * is persisted as `cash_handoff_in_progress` in Room.
     */
    suspend fun beginPosRefundCashHandoff(localId: String): PosRefundRequestResult = mutex.withLock {
        withResourceSerialisation("orders") {
            val scopeLease = cacheIsolation.currentLease()
                ?: error("The active account scope is unavailable. Reopen the workspace before touching cash.")
            when (val safety = outboxSafety.canSync()) {
                is OutboxGateResult.Blocked -> error(safety.message)
                OutboxGateResult.Allowed -> Unit
            }
            val row = requireNotNull(db.refundDao().refundById(localId)) {
                "This refund task is no longer stored on this tablet. Refresh before touching cash."
            }
            require(row.state == RefundState.ACCEPTED_CASH_DUE) {
                if (row.state == RefundState.CASH_HANDOFF_IN_PROGRESS) {
                    "The cash handover is already open. Verify the customer and drawer; do not pay twice."
                } else {
                    "This refund is not ready to begin a cash handover. Refresh its server status first."
                }
            }
            val serverRequestId = requireNotNull(row.serverRequestId) {
                "The accepted refund is missing its server reference. Refresh before touching cash."
            }
            val serverShiftId = requireExactRefundShift(row, includeClosingIntent = false)
            val actionId = "pos-refund-handoff:${row.localId}"
            val result = refundsApi.beginCashHandoff(
                id = serverRequestId,
                body = PosRefundHandoffBody(
                    shiftId = serverShiftId,
                    expectedAmountMinor = row.amountMinor,
                ),
                key = actionId,
                provenance = outboxProvenanceHeaders(System.currentTimeMillis(), actionId),
            )
            applyPosRefundServerResult(result, row, scopeLease)
            result
        }
    }

    /**
     * One coroutine drains the conflated request state. The loop continues
     * only when a request arrived during the pass that just completed; there
     * is no recursion, polling, or spin while idle.
     */
    private suspend fun drainRequestedSyncs() {
        while (true) {
            var claimed = false
            var failure: Exception? = null
            try {
                mutex.withLock {
                    claimed = syncRequests.claimPass()
                    if (claimed) runSyncPass()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failure = e
            }

            if (!claimed) return
            failure?.let {
                _lastError.value = it.message ?: "Sync failed because of an unexpected app error."
            }
            if (!syncRequests.finishPass()) return
        }
    }

    /**
     * KDS mutation pass used by saved advances/acknowledgements; call only
     * while holding [mutex]. The write side remains kitchen-only. Its narrow
     * read reconciliation also refreshes Tables because both screens project
     * the same OrderLine.kitchen_status through different Room caches.
     */
    private suspend fun runKitchenSyncPass() {
        try {
            val safety = try {
                outboxSafety.canSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val message =
                    "Sync is locked because the app could not verify who owns the saved work. " +
                        "Keep this tablet offline and ask a manager or support technician for help."
                outboxSafety.publishNotice(message)
                _lastError.value = message
                return
            }
            if (safety is OutboxGateResult.Blocked) {
                _lastError.value = safety.message
                return
            }
            _syncing.value = true
            _lastError.value = null
            passHadAmbiguousFailure = false
            withResourceSerialisation("kitchen") {
                pushKitchenAdvances()
                if (!passHadAmbiguousFailure) pushKitchenCancellationAcks()
            }
            // Always try the authoritative reads, including after an
            // ambiguous response: the server may have committed before the
            // connection dropped. refresh() is permission-gated and treats
            // routine offline failure as cache-preserving, so a kitchen-only
            // account never gains Tables access and no broad sync is started.
            for (resource in RealtimeRefreshPolicy.resourcesFor("kitchen")) {
                refresh(resource)
            }
        } catch (e: ApiException) {
            _lastError.value = e.message
        } finally {
            _syncing.value = false
        }
    }

    /** The actual serialized network/Room pass. Call only while holding [mutex]. */
    private suspend fun runSyncPass() {
        try {
            val resourceAccess = currentResourceAccess()
            val safety = try {
                outboxSafety.canSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val message =
                    "Sync is locked because the app could not verify who owns the saved work. " +
                        "Keep this tablet offline and ask a manager or support technician for help."
                outboxSafety.publishNotice(message)
                _lastError.value = message
                return
            }
            if (safety is OutboxGateResult.Blocked) {
                _lastError.value = safety.message
                return
            }
            _syncing.value = true
            _lastError.value = null
            passHadAmbiguousFailure = false
            // Shifts before orders: an order captured against a shift that
            // hasn't synced yet has nothing to attach to until the shift's
            // open leg resolves (see pushPendingOrders).
            withResourceSerialisation("shifts") {
                // This must be the first shift operation in the pass. A close
                // captured before its offline open has a server id is also in
                // pushableOpens(); reject a missing/negative saved count locally
                // before that open (or any later close) can reach the server.
                val invalidCloseIntents =
                    db.shiftCloseSafetyDao().rejectInvalidCloseIntentsBeforeNetwork()
                if (invalidCloseIntents.isNotEmpty()) {
                    _lastError.value = invalidCloseIntents.first().message
                }
                pushShiftOpens()
                // Pull before dependents as well as after an attempted close.
                // If this tablet's offline open lost a race, reconciliation
                // links Tables/Gaming rows to the real terminal shift in this
                // same pass. A read failure must not block unrelated outboxes.
                if (resourceAccess.canPull("shifts")) pullOpenShiftBestEffort()
            }
            withResourceSerialisation("orders") {
                pushPendingOrders()
                // A held-order payment always targets an order that's already
                // real on the server. Keep its confirmed local state and its
                // replacement queue pull in the same order-resource critical
                // section as manual/realtime GETs.
                pushHeldOrderPayments()
                pushRefunds()
            }
            // These outboxes also update the same Room projections their
            // screen/realtime pulls replace. Share the resource locks so an
            // older in-flight GET cannot land after a newer confirmed write.
            withResourceSerialisations("gaming", "orders") {
                val changedHeldQueue = pushGamingSessions()
                if (changedHeldQueue && resourceAccess.canPull("orders")) {
                    pullHeldOrdersBestEffort()
                }
            }
            withResourceSerialisation("kitchen") {
                pushKitchenAdvances()
                pushKitchenCancellationAcks()
            }
            withResourceSerialisations("tables", "orders") {
                val changedHeldQueue = pushCafeActions()
                if (changedHeldQueue && resourceAccess.canPull("orders")) {
                    pullHeldOrdersBestEffort()
                }
            }
            withResourceSerialisation("customers") { pushCustomers() }
            withResourceSerialisation("staff") { pushStaff() }
            // Ingredients/suppliers before GRN/adjustments: both depend on an
            // already-synced ingredient/supplier server id (the GRN/adjust
            // dialogs' own pickers exclude anything still local-only, so this
            // ordering isn't required for correctness, just gives a
            // same-pass chance for a just-created ingredient to be usable by
            // a GRN queued in the same batch of offline work).
            withResourceSerialisation("inventory") {
                pushIngredients()
                pushSuppliers()
                pushGrns()
                pushAdjustments()
            }
            // No cross-resource dependency here (a capital entry's partner
            // is never itself created offline, and an expense's branch/
            // category/supplier pickers only ever offer already-synced
            // options, same dependency-sidestep as GRN/adjustments above) —
            // placed here purely to keep all outbox pushes grouped before
            // the menu pull.
            withResourceSerialisation("finance") {
                val hadFinanceWork =
                    db.financeDao().pushableExpenses().isNotEmpty() ||
                        db.financeDao().pushableAssets().isNotEmpty() ||
                        db.financeDao().pushableCapitalEntries().isNotEmpty()
                pushExpenses()
                pushAssets()
                pushCapitalEntries()
                if (hadFinanceWork && resourceAccess.canPull("finance")) {
                    // Keep the pass alive if only the follow-up read fails;
                    // every confirmed POST remains replay-safe in its outbox.
                    runAndRecordRefreshAlreadyLocked("finance") {
                        val missingReferences = pullFinanceSnapshots()
                        if (missingReferences.isNotEmpty()) {
                            throw FinanceReferenceRefreshException(missingReferences)
                        }
                    }
                }
            }
            // Sales before check-ins: the tickets UI only ever offers
            // check-in for an already-synced ticket (dependency-sidestep,
            // same as GRN/adjustments only offering synced ingredients), so
            // in practice a check-in row's ticketId is never itself
            // pending — this ordering just matches the general "push a
            // dependency before its dependent" discipline everywhere else.
            withResourceSerialisation("events") {
                pushTicketSales()
                pushCheckIns()
            }
            // Membership collection is a server-reserved, multi-stage money
            // workflow. Reconcile before replay, then push one stable local
            // action per stage; never use the retired direct subscribe path.
            withResourceSerialisation("memberships") {
                pullMembershipPaymentTasksBestEffort()
                reconcileMembershipPaymentActionsFromCache()
                ensureMembershipPaymentFinalizations()
                pushMembershipPaymentActions()
                pushCancellations()
                pullMembershipRefundStateBestEffort()
                reconcileMembershipRefundActionsFromCache()
                ensureMembershipRefundFinalizations()
                pushMembershipRefundActions()
            }
            // Branches before terminals: a new terminal only ever targets an
            // already-synced branch id (dependency-sidestep — the branch
            // picker only ever shows branch_cache rows), so a branch created
            // in this same drain must land first. Company edit has no
            // dependency either way.
            withResourceSerialisation("settings") {
                pushCompanyEdit()
                pushBranches()
                pushTerminals()
            }
            // Right before the menu pull, same reasoning the pull's own
            // class doc already gives for running last: this device's own
            // just-synced category/item edits, and any other device's,
            // should already be reflected the instant pullMenu() runs.
            withResourceSerialisation("menu") {
                pushMenuCategories()
                pushMenuItems()
                if (resourceAccess.canPull("menu")) {
                    pullMenu()
                }
            }
            // A close is the final write in a pass. It must not overtake a
            // sale, table handoff, session or any other operation belonging
            // to the shift. If an earlier response was ambiguous, leave the
            // close queued for the next pass so idempotent recovery happens
            // before the till can close.
            withResourceSerialisation("shifts") {
                val closeAttempted = if (!passHadAmbiguousFailure) pushShiftCloses() else false
                if (closeAttempted && resourceAccess.canPull("shifts")) {
                    pullOpenShiftBestEffort()
                }
            }
        } catch (e: ApiException) {
            _lastError.value = e.message
        } finally {
            _syncing.value = false
        }
    }

    private fun currentResourceAccess(): SyncResourceAccess = SyncResourceAccess.from(
        DCompanyApp.instance.shiftCache.profile.value,
    )

    /**
     * Capture the active account/branch/terminal generation before issuing a
     * read, then serialize its final Room mutation with scope transitions. A
     * response sent under A is silently discarded if B has activated meanwhile.
     */
    private suspend fun commitToCurrentScope(
        lease: CacheScopeLease,
        store: suspend () -> Unit,
    ): Boolean = cacheIsolation.commitIfCurrent(lease, store)

    /**
     * One unit of outbox work per row: `push` either fully succeeds or throws.
     * A non-`ApiException` is a bug on our side (e.g. a DTO mismatch) — reject
     * visibly rather than let it crash the app mid-sync, same reasoning as the
     * order push below. An ambiguous `ApiException` (no answer, or the server
     * is mid-flight) stops the whole drain so a bad link isn't hammered
     * further; a definitive refusal is parked for a human, since retrying
     * cannot change the answer.
     *
     * Returns false if the drain stopped early on an ambiguous failure.
     */
    private suspend fun <T> drainOutbox(
        rows: List<T>,
        markRejected: suspend (T, String) -> Unit,
        push: suspend (T) -> Unit,
    ): Boolean {
        for (row in rows) {
            try {
                push(row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _lastError.value = e.message
                if (e !is ApiException) {
                    markRejected(row, "Could not sync this (app error): ${e.message}")
                    continue
                }
                if (e.mustPreserveOutbox) {
                    passHadAmbiguousFailure = true
                    // A 426 is a definitive app-wide compatibility block,
                    // not a row-specific refusal.  Leave this row pending and
                    // stop the pass immediately instead of sending every
                    // other outbox row into the same gate.
                    if (e.status == 426) throw e
                    return false
                }
                markRejected(row, e.message ?: "Server refused this.")
            }
        }
        return true
    }

    private suspend fun pushShiftOpens() {
        val dao = db.shiftDao()
        val terminalId = DCompanyApp.instance.terminalStore.terminalId()
        if (terminalId == null) {
            if (dao.pushableOpens().isNotEmpty()) {
                _lastError.value =
                    "A saved shift open is waiting, but this tablet has no verified POS terminal. Sign in online before syncing it."
            }
            return
        }
        val rows = dao.pushableOpens()
        val eligible = rows.filter { it.terminalId == null || it.terminalId == terminalId }
        if (eligible.size != rows.size) {
            // Never post a v16 outbox leg under a different X-Terminal-Id.
            // It stays pending for explicit terminal recovery.
            _lastError.value =
                "A saved shift belongs to another terminal and was not sent. Ask an owner to restore that terminal assignment."
        }
        drainOutbox(
            rows = eligible,
            markRejected = { row, msg -> dao.markOpenRejected(row.localId, msg) },
            push = ::pushShiftOpen,
        )
    }

    /**
     * Cache the one server-open shift scoped by X-Terminal-Id.
     *
     * Malformed/multiple rows invalidate the replaceable cache. The backend
     * enforces one open shift per terminal, so retaining a formerly valid row
     * would incorrectly leave money actions enabled after an authority error.
     */
    private suspend fun pullOpenShift() {
        val lease = cacheIsolation.currentLease() ?: return
        val terminalId = DCompanyApp.instance.terminalStore.terminalId() ?: return
        val rows = shiftApi.shifts(onlyOpen = true)
        if (rows.size > 1) {
            if (commitToCurrentScope(lease) { db.shiftDao().deleteServerOpen(terminalId) }) {
                _lastError.value =
                    "The server returned multiple open shifts for this terminal. POS money actions are locked until an owner reconciles them."
            }
            return
        }
        val detail = rows.firstOrNull()
        val profileBranchId = DCompanyApp.instance.shiftCache.profile.value?.branchId
        if (
            detail != null && (
                detail.terminalId != terminalId ||
                    profileBranchId == null ||
                    detail.branchId != profileBranchId ||
                    detail.status != "open"
            )
        ) {
            if (commitToCurrentScope(lease) { db.shiftDao().deleteServerOpen(terminalId) }) {
                _lastError.value =
                    "The server shift did not match this signed-in branch and terminal. POS money actions are locked."
            }
            return
        }
        val observedAt = System.currentTimeMillis()
        val cached = if (detail == null) {
            null
        } else {
            val openedAt = runCatching { Instant.parse(detail.openedAt).toEpochMilli() }.getOrNull()
            if (openedAt == null) {
                if (commitToCurrentScope(lease) { db.shiftDao().deleteServerOpen(terminalId) }) {
                    _lastError.value =
                        "The server shift opening time was invalid. POS money actions are locked."
                }
                return
            }
            detail.toServerOpenShiftCache(
                terminalId = terminalId,
                openedAtMillis = openedAt,
                verifiedAtMillis = observedAt,
            )
        }
        commitToCurrentScope(lease) {
            db.withTransaction {
                db.shiftDao().reconcileServerOpen(terminalId, cached, observedAt)
                db.syncMetaDao().put(SyncMetaEntity("shifts", observedAt))
            }
        }
    }

    /**
     * Refresh the last 200 shifts for this exact logical terminal. This read
     * is intentionally separate from [pullOpenShift]: a capped history list
     * must never decide which shift is currently authoritative.
     */
    private suspend fun pullShiftHistory() {
        val lease = cacheIsolation.currentLease() ?: return
        val terminalId = DCompanyApp.instance.terminalStore.terminalId() ?: return
        val branchId = DCompanyApp.instance.shiftCache.profile.value?.branchId ?: return
        _shiftHistoryRefreshing.value = true
        try {
            val rows = shiftApi.shifts(onlyOpen = false, limit = 200)
            if (rows.any { it.terminalId != terminalId || it.branchId != branchId }) {
                _shiftHistoryError.value =
                    "The downloaded history belonged to a different branch or terminal. Saved history was kept."
                return
            }
            val fetchedAt = System.currentTimeMillis()
            val mapped = rows.map { detail ->
                val openedAt = runCatching { Instant.parse(detail.openedAt).toEpochMilli() }.getOrNull()
                val closedAt = detail.closedAt?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                }
                if (openedAt == null || (detail.closedAt != null && closedAt == null)) {
                    _shiftHistoryError.value =
                        "The downloaded history contained an invalid time. Saved history was kept."
                    return
                }
                detail.toShiftHistoryCache(
                    terminalId = terminalId,
                    openedAtMillis = openedAt,
                    closedAtMillis = closedAt,
                    fetchedAtMillis = fetchedAt,
                )
            }
            if (commitToCurrentScope(lease) {
                    db.withTransaction {
                        db.shiftDao().replaceServerHistoryForTerminal(terminalId, mapped)
                        db.syncMetaDao().put(SyncMetaEntity("shift_history", fetchedAt))
                    }
                }
            ) {
                _shiftHistoryError.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _shiftHistoryError.value = e.message?.takeIf { it.isNotBlank() }
                ?: "Could not refresh shift history. Saved history was kept."
            throw e
        } finally {
            _shiftHistoryRefreshing.value = false
        }
    }

    private suspend fun pullOpenShiftBestEffort() {
        try {
            pullOpenShift()
            pullShiftHistory()
        } catch (e: ApiException) {
            _lastError.value = e.message
        }
    }

    /**
     * Resolve only the open leg here. A close requested while offline remains
     * `close_pending`; [pushShiftCloses] finds it at the end of this same pass
     * after every dependent outbox has had its chance to drain.
     */
    private suspend fun pushShiftOpen(row: LocalShiftEntity) {
        val dao = db.shiftDao()
        val opened = shiftApi.open(
            ShiftOpenBody(row.openingFloatMinor),
            "shift-open:${row.localId}",
            outboxProvenanceHeaders(row.openedAtMillis, "shift-open:${row.localId}"),
        )
        dao.setServerShiftId(row.localId, opened.id)
        // Guarded transition: if staff requested close while this call was in
        // flight, retain close_pending rather than reopening the local row.
        dao.transitionState(row.localId, fromState = ShiftState.OPEN_PENDING, toState = ShiftState.OPEN_SYNCED)
    }

    private suspend fun pushShiftCloses(): Boolean {
        val dao = db.shiftDao()
        val rows = dao.pushableCloses()
        val countEligible = mutableListOf<Pair<LocalShiftEntity, Long>>()
        for (row in rows) {
            when (val validation = ShiftCloseCountPolicy.validate(row.countedMinor)) {
                is ShiftCloseCountValidation.Valid -> {
                    countEligible += row to validation.countedMinor
                }
                is ShiftCloseCountValidation.Invalid -> {
                    // Never manufacture zero for an older/corrupt close row.
                    // Keep the server shift open and move the local intent to
                    // the visible recovery state before any network call.
                    db.shiftCloseSafetyDao().markInvalidCloseCount(
                        row.localId,
                        validation.message,
                    )
                    _lastError.value = validation.message
                }
            }
        }
        val terminalId = DCompanyApp.instance.terminalStore.terminalId()
        if (terminalId == null) {
            if (countEligible.isNotEmpty()) {
                _lastError.value =
                    "A saved shift close is waiting, but this tablet has no verified POS terminal. Sign in online before syncing it."
            }
            return false
        }
        val terminalEligible = ShiftCloseCountPolicy.filterForTerminal(countEligible, terminalId)
        val legacyPosRefunds = db.refundDao().legacyReconciliationCount()
        val unscopedMembershipMoney =
            db.membershipPaymentDao().globalUnknownShiftCount() +
                db.membershipRefundMoneyDao().globalUnknownShiftCount()
        val eligible = terminalEligible.filter { (row, _) ->
            val legacyMembershipUnresolved = db.membershipDao().unresolvedMoneyCountForShift(
                row.localId,
                row.serverShiftId,
            )
            val paymentUnresolved =
                db.membershipPaymentDao().unresolvedActionCountForShift(row.localId, row.serverShiftId) +
                    db.membershipPaymentDao().unresolvedTaskCountForShift(row.localId, row.serverShiftId)
            val refundUnresolved =
                db.membershipRefundMoneyDao().unresolvedActionCountForShift(row.localId, row.serverShiftId) +
                    db.membershipRefundMoneyDao().unresolvedTaskCountForShift(row.localId, row.serverShiftId) +
                    db.membershipRefundMoneyDao().unresolvedAttemptCountForShift(row.localId, row.serverShiftId)
            val posRefundUnresolved = db.refundDao().unresolvedMoneyCountForShift(
                row.localId,
                row.serverShiftId,
            )
            when {
                legacyPosRefunds > 0 || unscopedMembershipMoney > 0 -> {
                    _lastError.value =
                        "Shift close is waiting: ${legacyPosRefunds + unscopedMembershipMoney} money record(s) from an older app " +
                            "lack a trustworthy shift or physical-action state. Do not move value again; " +
                            "a protected owner must reconcile server and drawer records."
                    false
                }
                legacyMembershipUnresolved + paymentUnresolved + refundUnresolved + posRefundUnresolved > 0 -> {
                    _lastError.value =
                        "Shift close is waiting: $posRefundUnresolved POS refund action(s) and " +
                            "${legacyMembershipUnresolved + paymentUnresolved + refundUnresolved} membership payment/refund action(s) for this " +
                            "exact shift still need attention. Resolve them before counting cash."
                    false
                }
                else -> true
            }
        }
        if (eligible.size != rows.size) {
            if (terminalEligible.size != countEligible.size) {
                _lastError.value =
                    "A saved shift close belongs to another terminal and was not sent. Ask an owner to restore that terminal assignment."
            }
        }
        var attemptedServerClose = false
        for ((row, countedMinor) in eligible) {
            // This is deliberately the last read before the network call. The
            // v23 SQLite write guard is already active while close_pending,
            // so no stale screen can insert a new exact-shift money action in
            // the query -> POST window. Pending rows captured before close are
            // allowed to drain earlier in this pass, but cannot be overtaken.
            val finalBlocker = db.shiftCloseSafetyDao().blockersForExactShift(
                localShiftId = row.localId,
                serverShiftId = row.serverShiftId,
                terminalId = terminalId,
            ).serverPostMessage()
            if (finalBlocker != null) {
                _lastError.value = finalBlocker
                // Do not strand the UI in close_pending. A pending write can
                // legitimately turn into a server-side workflow (for example
                // a table handoff or an ended gaming bill) while this pass is
                // draining. Reopen operational recovery explicitly; retrying
                // the close later re-runs the same atomic gate.
                dao.markCloseRejected(row.localId, finalBlocker)
                continue
            }
            try {
                val serverShiftId = requireNotNull(row.serverShiftId) {
                    "A close cannot sync before its shift open is confirmed."
                }
                attemptedServerClose = true
                val result = shiftApi.close(
                    serverShiftId,
                    ShiftCloseBody(countedMinor),
                    "shift-close:${row.localId}",
                    outboxProvenanceHeaders(row.closedAtMillis, "shift-close:${row.localId}"),
                )
                dao.markClosed(row.localId, result.varianceMinor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _lastError.value = e.message
                if (e is ApiException && e.mustPreserveOutbox) {
                    passHadAmbiguousFailure = true
                    if (e.status == 426) throw e
                    return attemptedServerClose
                }
                dao.markCloseRejected(
                    row.localId,
                    if (e is ApiException) {
                        e.message ?: "Server refused this shift close."
                    } else {
                        "Could not sync this shift close (app error): ${e.message}"
                    },
                )
            }
        }
        return attemptedServerClose
    }

    /**
     * Same dependency-resolution need as orders: a session started against a
     * shift that was itself opened offline carries that shift's `localId`
     * until it resolves. Sessions whose shift hasn't synced yet are left
     * untouched, not rejected — the next sync() call picks them up once
     * pushShiftOpens() (which runs first) has resolved it.
     *
     * Drain gaming actions while the gaming and orders locks are already held.
     * Returns true only when a confirmed send-to-POS response changed the held
     * queue; the caller then refreshes orders before releasing either lock.
     */
    private suspend fun pushGamingSessions(): Boolean {
        val dao = db.gamingDao()
        // A restored database can already carry the current Room version yet
        // still contain a pre-v17 generic rejection. Recover it before reading
        // the queue so the row becomes visible to the correct human retry path.
        // This is still a local_* mutation: serialize it with scope changes so
        // an old sync pass cannot rewrite A's recovery state after sign-out or
        // after B has replaced the workspace.
        val recoveryLease = cacheIsolation.currentLease() ?: return false
        if (!commitToCurrentScope(recoveryLease) { dao.recoverLegacyRejectedSessions() }) return false
        val shiftDao = db.shiftDao()
        val ready = dao.pushableSessions().filter { row ->
            // Only a still-unsynced start leg has a shift to wait on — a
            // stop-only row (serverId already set) or a genuinely shiftless
            // row is always ready.
            if (row.serverId != null || row.shiftId == null) return@filter true
            val localShift = shiftDao.byLocalId(row.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        var changedHeldQueue = false
        drainOutbox(
            rows = ready,
            markRejected = { row, msg ->
                val current = dao.localSessionById(row.localId)
                val rejectedState = when {
                    current?.serverId == null -> GamingSessionState.START_REJECTED
                    current.state == GamingSessionState.SEND_PENDING -> GamingSessionState.SEND_REJECTED
                    else -> GamingSessionState.STOP_REJECTED
                }
                dao.markSessionRejected(row.localId, rejectedState, msg)
            },
            push = { row ->
                if (pushGamingSessionOne(row)) changedHeldQueue = true
            },
        )
        return changedHeldQueue
    }

    /**
     * Start, then stop if requested — same one-row-both-legs shape and same
     * reasoning as pushShiftOne: a stop requested before the start has
     * synced is already `state = stop_pending` on this row, so the check
     * below just finds it waiting once a real serverId exists.
     */
    private suspend fun pushGamingSessionOne(row: LocalGamingSessionEntity): Boolean {
        val dao = db.gamingDao()
        var serverId = row.serverId
        if (serverId == null) {
            // Guaranteed non-null here by GamingViewModel.start() — the only
            // call site that ever inserts a start_pending row. A violation
            // surfaces as a caught, rejected "app error" via drainOutbox
            // rather than crashing the sync.
            val shiftId = row.shiftId!!
            val resolvedShiftId = db.shiftDao().byLocalId(shiftId)?.serverShiftId ?: shiftId
            val started = gamingApi.start(
                SessionStartBody(
                    stationId = row.stationId,
                    shiftId = resolvedShiftId,
                    customerPhone = row.customerPhone,
                    timerMinutes = row.timerMinutes,
                ),
                "gaming-session-start:${row.localId}",
                outboxProvenanceHeaders(row.startedAtMillis, "gaming-session-start:${row.localId}"),
            )
            serverId = started.id
            dao.setSessionStarted(
                localId = row.localId,
                serverId = serverId,
                status = started.status,
                startedAtMillis = Instant.parse(started.startAt).toEpochMilli(),
                timerEndsAtMillis = started.timerEndsAt?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                },
            )
            dao.transitionSessionState(
                row.localId,
                fromState = GamingSessionState.START_PENDING,
                toState = GamingSessionState.START_SYNCED,
            )
        }
        val current = dao.localSessionById(row.localId) ?: return false
        if (current.state == GamingSessionState.STOP_PENDING) {
            val stopped = gamingApi.stop(
                serverId,
                "gaming-session-stop:${row.localId}",
                outboxProvenanceHeaders(null, "gaming-session-stop:${row.localId}"),
            )
            dao.markSessionStopped(
                row.localId,
                stopped.status,
                stopped.endAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                stopped.billableMinutes,
                stopped.amountMinor,
            )
            return false
        }
        if (current.state == GamingSessionState.SEND_PENDING) {
            val sent = gamingApi.sendToPos(
                serverId,
                outboxProvenanceHeaders(null, "gaming-session-send:${row.localId}"),
            )
            dao.markSessionSent(row.localId, sent.orderId, sent.amountMinor)
            // The successful handoff changes both shared views. Both resource
            // locks are already held, so these raw pulls do not re-enter the
            // non-reentrant serialiser.
            pullGamingData()
            return true
        }
        return false
    }

    /**
     * Stations + every session on every terminal — a shared floor view, so
     * this always pulls the whole company's sessions, not just this
     * device's. On-demand only (see onDemandPulls): a screen open or a
     * realtime "gaming" event triggers it, not every sync().
     */
    private suspend fun pullGamingData() {
        val lease = cacheIsolation.currentLease() ?: return
        val stations = gamingApi.stations()
        val sessions = gamingApi.sessions()
        commitToCurrentScope(lease) {
            db.withTransaction {
                db.gamingDao().replaceStations(
                    stations.map {
                        GamingStationEntity(
                            id = it.id,
                            code = it.code,
                            name = it.name,
                            type = it.type,
                            ratePerHourMinor = it.ratePerHourMinor,
                            isActive = it.isActive,
                        )
                    },
                )
                db.gamingDao().replaceSessionCache(
                    sessions.map {
                        GamingSessionCacheEntity(
                            id = it.id,
                            stationId = it.stationId,
                            status = it.status,
                            startAtMillis = runCatching { Instant.parse(it.startAt).toEpochMilli() }
                                .getOrDefault(System.currentTimeMillis()),
                            endAtMillis = it.endAt?.let { s ->
                                runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                            },
                            timerMinutes = it.timerMinutes,
                            timerEndsAtMillis = it.timerEndsAt?.let { s ->
                                runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                            },
                            billableMinutes = it.billableMinutes,
                            amountMinor = it.amountMinor,
                            customerName = it.customerName,
                            customerPhone = it.customerPhone,
                            orderId = it.orderId,
                        )
                    },
                )
                db.syncMetaDao().put(SyncMetaEntity("gaming", System.currentTimeMillis()))
            }
            // Gaming may be closed while a realtime event reports a stop from
            // another terminal. Keep alarm reconciliation under the same scope
            // lease so A cannot schedule a notification after B activates.
            GamingAlarmReconciler.reconcile(DCompanyApp.instance)
        }
    }

    /**
     * Reconcile each saved advance against current server truth. A second KDS
     * may already have moved the ticket further, in which case blindly
     * classifying the backend's backwards-transition refusal as a rejection
     * strands harmless work and account-locks this tablet. The active queue is
     * authoritative and unpaginated: at/after target, or absent because the
     * ticket is no longer active, satisfies the older local intent.
     */
    private suspend fun pushKitchenAdvances() {
        val dao = db.kitchenDao()
        val reconciler = KitchenAdvanceReconciler(
            setState = { row ->
                kitchenApi.setState(
                    row.orderId,
                    KitchenStateUpdate(row.targetState),
                    outboxProvenanceHeaders(
                        row.requestedAtMillis,
                        "kitchen-advance:${row.localId}",
                    ),
                )
            },
            activeQueue = { kitchenApi.queue(includeServed = false) },
        )

        for (row in dao.pendingAdvances()) {
            val decision = reconciler.reconcile(row)
            val message = decision.message
                ?: "This saved kitchen update needs review before it can be removed."
            when (decision.disposition) {
                KitchenAdvanceDisposition.SATISFIED -> dao.deletePendingAdvance(row.localId)
                KitchenAdvanceDisposition.NEEDS_ATTENTION -> {
                    dao.markAdvanceRejected(row.localId, message)
                    _lastError.value = message
                }
                KitchenAdvanceDisposition.KEEP_PENDING -> {
                    dao.keepAdvancePending(row.localId, message)
                    _lastError.value = message
                    passHadAmbiguousFailure = true
                    // Match generic outbox semantics: do not hammer the same
                    // failed connection or let shift close overtake an
                    // uncertain write in this pass.
                    return
                }
            }
        }
    }

    private suspend fun pullKitchenData() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { kitchenApi.queue(includeServed = false) },
            store = { orders ->
                db.withTransaction {
                    db.kitchenDao().replaceOrderCache(
                        orders.map {
                            KitchenOrderCacheEntity(
                                id = it.id,
                                invoiceNo = it.invoiceNo,
                                type = it.type,
                                tableCode = it.tableCode,
                                customerName = it.customerName,
                                openedAt = it.openedAt,
                                kitchenState = it.kitchenState,
                                minutesWaiting = it.minutesWaiting,
                                lines = it.lines,
                                pendingCancellations = it.pendingCancellations,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("kitchen", System.currentTimeMillis()))
                }
            },
        )
    }

    private suspend fun pushKitchenCancellationAcks() {
        val dao = db.kitchenDao()
        for (row in dao.pendingCancellationAcks()) {
            try {
                val cached = dao.orderCache(row.orderId)
                if (cached != null && cached.pendingCancellations.none { it.lineId == row.lineId }) {
                    // Another KDS already acknowledged it. Server truth has
                    // satisfied this local intent, so no replay is needed.
                    dao.deleteCancellationAck(row.localId)
                    continue
                }
                kitchenApi.acknowledgeCancellation(
                    orderId = row.orderId,
                    lineId = row.lineId,
                    key = "kitchen-cancel-ack:${row.localId}",
                    provenance = outboxProvenanceHeaders(
                        row.requestedAtMillis,
                        "kitchen-cancel-ack:${row.localId}",
                    ),
                )
                dao.deleteCancellationAck(row.localId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val message = e.message ?: "Kitchen cancellation acknowledgement was not confirmed."
                _lastError.value = message
                if (e !is ApiException || e.mustPreserveOutbox) {
                    dao.noteCancellationAckPending(row.localId, message)
                    passHadAmbiguousFailure = true
                    if (e is ApiException && e.status == 426) throw e
                    return
                }
                dao.rejectCancellationAck(row.localId, message)
            }
        }
    }

    /**
     * Drain table actions while the tables and orders locks are already held.
     * The caller uses the returned flag to reconcile POS without re-entering
     * either non-reentrant lock.
     */
    private suspend fun pushCafeActions(): Boolean {
        val result = cafeOrderSync.push()
        result.lastError?.let { _lastError.value = it }
        if (result.stoppedOnAmbiguousFailure) {
            passHadAmbiguousFailure = true
        }
        return result.changedHeldQueue
    }

    /**
     * Recovery needs a throwing, positively committed active-bill read rather
     * than [refresh]'s cache-preserving failure result. It still participates
     * in the canonical tables queue so an older floor/bill GET cannot land
     * after this evidence is used for a destructive discard or conflict rebase.
     */
    suspend fun refreshCafeBillsForRecovery(): Boolean {
        return withResourceSerialisation("tables") {
            val lease = cacheIsolation.currentLease()
                ?: return@withResourceSerialisation false
            val rows = cafeOrderSync.fetchActiveBills()
            commitToCurrentScope(lease) {
                db.cafeOrderDao().replaceActiveBillCache(rows)
            }
        }
    }

    private suspend fun pullTablesData() {
        val lease = cacheIsolation.currentLease() ?: return
        val floors = tablesApi.floors()
        val tables = tablesApi.tables()
        val activeBills = cafeOrderSync.fetchActiveBills()
        commitToCurrentScope(lease) {
            db.withTransaction {
                db.tablesDao().replaceFloors(
                    floors.map { FloorEntity(id = it.id, branchId = it.branchId, name = it.name) },
                )
                db.tablesDao().replaceTables(
                    tables.map {
                        CafeTableEntity(
                            id = it.id,
                            floorId = it.floorId,
                            code = it.code,
                            seats = it.seats,
                            shape = it.shape,
                            x = it.x,
                            y = it.y,
                            status = it.status,
                        )
                    },
                )
                db.cafeOrderDao().replaceActiveBillCache(activeBills)
                db.syncMetaDao().put(SyncMetaEntity("tables", System.currentTimeMillis()))
            }
        }
    }

    /**
     * POS refunds are a server-authoritative state machine:
     * request/reserve -> begin handover -> physical confirmation -> settle,
     * or an owner withdrawal when no cash was given. Every queued leg is
     * bound to the exact shift captured by the UI and pushed before close.
     */
    private suspend fun pushRefunds() {
        val dao = db.refundDao()
        val profile = DCompanyApp.instance.shiftCache.profile.value
        if (profile == null || !EffectivePermissions.from(profile).has(ErpPermission.PosRefund)) {
            if (dao.reconcilableRefunds().isNotEmpty() || dao.legacyReconciliationCount() > 0) {
                _lastError.value =
                    "POS refund work is saved on this tablet, but this employee no longer has refund access. " +
                        "A protected owner must sign in and resolve it before the shift can close."
            }
            return
        }
        val requests = mutableListOf<LocalRefundEntity>()
        for (row in dao.pushableRefundRequests()) {
            if (refundShiftCanResolve(row)) requests += row
        }
        if (!drainOutbox(
                rows = requests,
                markRejected = { row, msg -> dao.markRequestRejected(row.localId, msg) },
                push = ::pushRefundRequestOne,
            )
        ) return

        val settlements = mutableListOf<LocalRefundEntity>()
        for (row in dao.pushableCashSettlements()) {
            if (refundShiftCanResolve(row)) settlements += row
        }
        if (!drainOutbox(
                rows = settlements,
                markRejected = { row, msg -> dao.markCashSettlementRejected(row.localId, msg) },
                push = ::pushRefundCashSettlementOne,
            )
        ) return

        val withdrawals = mutableListOf<LocalRefundEntity>()
        for (row in dao.pushableWithdrawals()) {
            if (refundShiftCanResolve(row)) withdrawals += row
        }
        if (!drainOutbox(
                rows = withdrawals,
                markRejected = { row, msg -> dao.markWithdrawalRejected(row.localId, msg) },
                push = ::pushRefundWithdrawalOne,
            )
        ) return

        // Recover response-loss and same-terminal tasks after reinstall before
        // considering a queued shift close. A failed pull leaves every local
        // state intact and the close gate below remains fail-closed.
        reconcilePosRefundRequests()
    }

    private suspend fun refundShiftCanResolve(row: LocalRefundEntity): Boolean {
        val capturedShiftId = row.shiftId ?: return false
        val localShift = db.shiftDao().byLocalId(capturedShiftId)
        return localShift == null || localShift.serverShiftId != null
    }

    private suspend fun pushRefundRequestOne(row: LocalRefundEntity) {
        val scopeLease = cacheIsolation.currentLease()
            ?: error("The active account scope changed before this refund could be sent.")
        val clientActionId = requireNotNull(row.clientActionId) {
            "Refund action identity is missing; owner reconciliation is required."
        }
        val expectedPaid = requireNotNull(row.expectedPaidMinor) {
            "The captured paid total is missing. Refresh and create a new request only after reconciliation."
        }
        val expectedRefundable = requireNotNull(row.expectedRefundableMinor) {
            "The captured refundable balance is missing. Refresh before retrying."
        }
        val mode = requireNotNull(row.mode) { "Refund method was not captured safely." }
        val serverShiftId = requireExactRefundShift(row, includeClosingIntent = true)
        val result = refundsApi.requestRefund(
            body = PosRefundRequestBody(
                orderId = row.orderId,
                shiftId = serverShiftId,
                reasonCode = row.reasonCode,
                amountMinor = row.amountMinor,
                expectedPaidMinor = expectedPaid,
                expectedRefundableMinor = expectedRefundable,
                mode = mode,
                clientActionId = clientActionId,
                externalReference = row.externalReference,
                providerSettledAt = row.providerSettledAtMillis?.let {
                    Instant.ofEpochMilli(it).toString()
                },
                note = row.note,
            ),
            key = "pr:${row.localId}",
            // Backend verifies that this header exactly matches the body.
            provenance = outboxProvenanceHeaders(row.createdAtMillis, clientActionId),
        )
        applyPosRefundServerResult(result, row, scopeLease)
    }

    private suspend fun pushRefundCashSettlementOne(row: LocalRefundEntity) {
        val scopeLease = cacheIsolation.currentLease()
            ?: error("The active account scope changed before this cash settlement could be sent.")
        val requestId = requireNotNull(row.serverRequestId) {
            "Cash handover is missing its server request. Refresh before retrying; do not pay twice."
        }
        val settledAt = requireNotNull(row.settledAtMillis) {
            "Physical handover time is missing. Ask an owner to reconcile this refund."
        }
        val serverShiftId = requireExactRefundShift(row, includeClosingIntent = true)
        val actionId = "ps:${row.localId}"
        val result = refundsApi.settleCash(
            id = requestId,
            body = PosRefundCashSettlementBody(
                shiftId = serverShiftId,
                expectedAmountMinor = row.amountMinor,
                settledAt = Instant.ofEpochMilli(settledAt).toString(),
            ),
            key = actionId,
            provenance = outboxProvenanceHeaders(settledAt, actionId),
        )
        applyPosRefundServerResult(result, row, scopeLease)
    }

    private suspend fun pushRefundWithdrawalOne(row: LocalRefundEntity) {
        val scopeLease = cacheIsolation.currentLease()
            ?: error("The active account scope changed before this withdrawal could be sent.")
        val requestId = requireNotNull(row.serverRequestId) {
            "Cash refund withdrawal is missing its server request. Refresh before retrying."
        }
        val reason = row.withdrawalReason?.trim().orEmpty()
        require(reason.length >= 3) { "Enter why no cash was handed over." }
        val withdrawnAt = requireNotNull(row.withdrawalAtMillis) {
            "Cash refund withdrawal time is missing. Ask an owner to reconcile this task."
        }
        val serverShiftId = requireExactRefundShift(row, includeClosingIntent = true)
        val actionId = "pw:${row.localId}"
        val result = refundsApi.withdrawCash(
            id = requestId,
            body = PosRefundWithdrawalBody(
                shiftId = serverShiftId,
                expectedAmountMinor = row.amountMinor,
                reason = reason,
                withdrawnAt = Instant.ofEpochMilli(withdrawnAt).toString(),
            ),
            key = actionId,
            provenance = outboxProvenanceHeaders(withdrawnAt, actionId),
        )
        applyPosRefundServerResult(result, row, scopeLease)
    }

    /** Resolves only the shift captured on the row; never substitutes the first open shift. */
    private suspend fun requireExactRefundShift(
        row: LocalRefundEntity,
        includeClosingIntent: Boolean,
    ): String {
        val app = DCompanyApp.instance
        val terminalId = app.terminalStore.terminalId()
            ?: error("This tablet has no verified POS terminal. Reconnect before refunding money.")
        require(row.terminalId == terminalId) {
            "This refund belongs to another terminal. Restore that terminal assignment; do not touch cash."
        }
        val profile = app.shiftCache.profile.value
            ?: error("The signed-in employee could not be verified. Reconnect before refunding money.")
        val branchId = profile.branchId
            ?: error("This employee has no branch assignment. An owner must correct access first.")
        require(row.branchId == branchId) {
            "This refund belongs to another branch. Switch back to the original branch; do not touch cash."
        }
        val resolved = ShiftResolutionPolicy.resolve(
            db.shiftDao().currentForTerminal(terminalId),
            db.shiftDao().serverOpen(terminalId),
            includeClosingIntent = includeClosingIntent,
        ) ?: error("The captured POS shift is not open on this terminal. Do not touch cash.")
        val actor = ShiftActor(profile.userId, profile.protectedAccess)
        require(resolved.canManageMoney(actor)) {
            resolved.moneyAccessMessage(actor)
                ?: "Only the shift opener or a protected owner may refund money from this drawer."
        }
        val resolvedBranch = resolved.server?.branchId ?: resolved.local?.branchId
        require(resolvedBranch == branchId) {
            "The open shift does not match this employee's branch. Refresh the shift before refunding."
        }
        val serverShiftId = resolved.server?.serverShiftId ?: resolved.local?.serverShiftId
            ?: error("The captured shift is still waiting for server confirmation.")
        val validShiftIds = setOfNotNull(
            resolved.shiftId,
            resolved.local?.localId,
            resolved.local?.serverShiftId,
            resolved.server?.serverShiftId,
        )
        require(row.shiftId in validShiftIds || row.serverShiftId in validShiftIds) {
            "A different shift is open now. Resolve this refund against its original shift; do not pay from this drawer."
        }
        return serverShiftId
    }

    private suspend fun reconcilePosRefundRequests() {
        val dao = db.refundDao()
        // First recover each local action, including a non-cash request that
        // settled on the server before the response was lost.
        for (row in dao.reconcilableRefunds()) {
            val actionId = row.clientActionId ?: continue
            val scopeLease = cacheIsolation.currentLease() ?: return
            val result = refundsApi.refundRequests(
                unresolved = false,
                clientActionId = actionId,
                limit = 2,
            ).singleOrNull() ?: continue
            applyPosRefundServerResult(result, row, scopeLease)
        }

        // Then adopt any unresolved same-terminal task missing after reinstall.
        val terminalId = DCompanyApp.instance.terminalStore.terminalId() ?: return
        val resolved = ShiftResolutionPolicy.resolve(
            db.shiftDao().currentForTerminal(terminalId),
            db.shiftDao().serverOpen(terminalId),
            includeClosingIntent = true,
        ) ?: return
        val serverShiftId = resolved.server?.serverShiftId ?: resolved.local?.serverShiftId ?: return
        val scopeLease = cacheIsolation.currentLease() ?: return
        val unresolved = refundsApi.refundRequests(unresolved = true, shiftId = serverShiftId)
        for (result in unresolved) {
            val existing = dao.refundByClientActionId(result.clientActionId)
                ?: dao.refundByServerRequestId(result.id)
            if (existing != null) {
                applyPosRefundServerResult(result, existing, scopeLease)
            } else {
                adoptPosRefundServerResult(result, scopeLease)
            }
        }
    }

    private suspend fun adoptPosRefundServerResult(
        result: PosRefundRequestResult,
        scopeLease: CacheScopeLease,
    ) {
        require(result.status in setOf(
            RefundState.ACCEPTED_CASH_DUE,
            RefundState.CASH_HANDOFF_IN_PROGRESS,
        )) { "Only unresolved server refund work may be adopted." }
        val terminalId = DCompanyApp.instance.terminalStore.terminalId()
        val branchId = DCompanyApp.instance.shiftCache.profile.value?.branchId
        require(result.terminalId == terminalId && result.branchId == branchId) {
            "Server refund recovery returned another branch or terminal; nothing local was changed."
        }
        val acceptedAt = Instant.parse(result.acceptedAt).toEpochMilli()
        val row = LocalRefundEntity(
            localId = "server-refund:${result.id}",
            clientActionId = result.clientActionId,
            orderId = result.orderId,
            shiftId = result.shiftId,
            serverShiftId = result.shiftId,
            branchId = result.branchId,
            terminalId = result.terminalId,
            capturedByUserId = null,
            reasonCode = result.reasonCode,
            amountMinor = result.amountMinor,
            mode = result.mode,
            note = result.note,
            externalReference = result.externalReference,
            createdAtMillis = acceptedAt,
            state = result.status,
            settlementMethod = result.settlementMethod,
            serverRequestId = result.id,
            serverRefundId = result.refundId,
            acceptedAtMillis = acceptedAt,
            cashHandoffStartedAtMillis = result.handoffStartedAt?.let {
                Instant.parse(it).toEpochMilli()
            },
            settledAtMillis = result.settledAt?.let { Instant.parse(it).toEpochMilli() },
            withdrawalAtMillis = result.withdrawnAt?.let { Instant.parse(it).toEpochMilli() },
            receiptNo = result.receiptNo,
        )
        commitToCurrentScope(scopeLease) {
            db.refundDao().insertRecoveredRefund(row)
        }
    }

    private suspend fun applyPosRefundServerResult(
        result: PosRefundRequestResult,
        local: LocalRefundEntity,
        scopeLease: CacheScopeLease,
    ) {
        require(result.clientActionId == local.clientActionId) {
            "Server refund identity did not match the saved action. Owner reconciliation is required."
        }
        require(
            result.orderId == local.orderId && result.amountMinor == local.amountMinor &&
                (local.mode == null || result.mode == local.mode),
        ) { "Server refund details differ from the saved request. Owner reconciliation is required." }
        require(result.branchId == local.branchId && result.terminalId == local.terminalId) {
            "Server refund belongs to another branch or terminal. Nothing local was changed."
        }
        val knownServerShift = local.serverShiftId
            ?: local.shiftId?.let { db.shiftDao().byLocalId(it)?.serverShiftId }
            ?: local.shiftId
        require(result.shiftId == knownServerShift) {
            "Server refund belongs to a different shift. Nothing local was changed."
        }
        val serverState = when (result.status) {
            RefundState.ACCEPTED_CASH_DUE,
            RefundState.CASH_HANDOFF_IN_PROGRESS,
            RefundState.SETTLED,
            RefundState.WITHDRAWN,
            -> result.status
            else -> error("Unknown server refund state '${result.status}'. Update the app before continuing.")
        }
        val mergedState = when {
            serverState == RefundState.SETTLED || serverState == RefundState.WITHDRAWN -> serverState
            local.state in setOf(
                RefundState.CASH_SETTLE_PENDING,
                RefundState.CASH_SETTLE_REJECTED,
                RefundState.WITHDRAWAL_PENDING,
                RefundState.WITHDRAWAL_REJECTED,
            ) -> local.state
            local.state == RefundState.CASH_HANDOFF_IN_PROGRESS &&
                serverState == RefundState.ACCEPTED_CASH_DUE -> local.state
            else -> serverState
        }
        val warning = if (
            serverState == RefundState.SETTLED && result.customerSpendReconciled == false
        ) {
            "Refund settled, but this legacy order had no stable customer link. Owner LTV reconciliation is required."
        } else {
            null
        }
        var changed = 0
        if (!commitToCurrentScope(scopeLease) {
                changed = db.refundDao().applyServerState(
                    localId = local.localId,
                    state = mergedState,
                    serverRequestId = result.id,
                    serverRefundId = result.refundId,
                    serverShiftId = result.shiftId,
                    branchId = result.branchId,
                    terminalId = result.terminalId,
                    settlementMethod = result.settlementMethod,
                    acceptedAtMillis = Instant.parse(result.acceptedAt).toEpochMilli(),
                    cashHandoffStartedAtMillis = result.handoffStartedAt?.let {
                        Instant.parse(it).toEpochMilli()
                    },
                    settledAtMillis = result.settledAt?.let { Instant.parse(it).toEpochMilli() },
                    withdrawalAtMillis = result.withdrawnAt?.let { Instant.parse(it).toEpochMilli() },
                    externalReference = result.externalReference,
                    receiptNo = result.receiptNo,
                    lastError = warning,
                )
            }
        ) return
        check(changed == 1) {
            "Refund task changed locally before the authoritative server state could be saved."
        }
    }

    /**
     * Paid orders eligible for a refund, for every terminal — a shared view
     * like Gaming's sessions. On-demand only (see onDemandPulls): opening
     * Refunds, an "orders" realtime event, or this device's own refund
     * being accepted (see pushRefundOne) triggers it, not every sync().
     *
     * Passing status=paid explicitly (rather than the unfiltered call the
     * pre-offline screen made) matters for two reasons: it skips the
     * backend's default same-day date window, so an order paid yesterday is
     * still refundable today, and it's what makes the backend compute and
     * return refundable_minor at all (see OrderListItem/list_orders) —
     * unfiltered list_orders calls exist elsewhere in this app for today's
     * order history and don't need that figure.
     */
    private suspend fun pullRefundableOrders() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { refundsApi.orders(status = "paid") },
            store = { orders ->
                db.withTransaction {
                    db.refundDao().replaceOrderCache(
                        orders.map {
                            RefundOrderCacheEntity(
                                id = it.id,
                                invoiceNo = it.invoiceNo,
                                status = it.status,
                                type = it.type,
                                totalMinor = it.totalMinor,
                                paidMinor = it.paidMinor,
                                refundableMinor = it.refundableMinor,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("orders", System.currentTimeMillis()))
                }
            },
        )
    }

    /**
     * Orders waiting at the till — a table's "Send to POS", or anything else
     * held instead of paid on the spot. Only `held` work enters this shared
     * claim flow. A direct counter POS order remains on the existing
     * create-and-pay path and intentionally never acquires a checkout claim.
     */
    private suspend fun pullHeldOrders() {
        val safeLimit = 500
        cacheIsolation.fetchAndCommitScoped(
            fetch = { ApiClient.api.orders(status = listOf("held"), limit = safeLimit) },
            store = { orders ->
                if (orders.size >= safeLimit) {
                    // The endpoint has no pagination/completeness header. At
                    // the hard cap, replacing would silently delete unseen
                    // financial work from the local queue and its alarms.
                    _lastError.value = "Held orders exceed this app's safe $safeLimit-order cache. " +
                        "No queue rows were replaced; bill older held orders or update the app."
                    return@fetchAndCommitScoped
                }
                db.heldOrderDao().replace(
                    orders.map {
                        HeldOrderCacheEntity(
                            id = it.id,
                            invoiceNo = it.invoiceNo,
                            type = it.type,
                            sourceLabel = it.sourceLabel,
                            totalMinor = it.totalMinor,
                            paidMinor = it.paidMinor,
                            itemsCount = it.itemsCount,
                            customerName = it.customerName,
                            createdAt = it.createdAt,
                            heldAt = it.heldAt,
                            checkoutVersion = it.checkoutVersion,
                        )
                    },
                )
            },
        )
    }

    /** Call only while the canonical orders-resource lock is already held. */
    private suspend fun pullHeldOrdersBestEffort() {
        try {
            pullHeldOrders()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(REFRESH_LOG_TAG, "Held-order reconciliation failed", failure)
            _lastError.value = resourceRefreshFailureMessage("orders", failure)
        }
    }

    private suspend fun pushHeldOrderPayments() {
        val dao = db.heldOrderDao()
        for (row in dao.pushablePayments()) {
            try {
                pushHeldOrderPaymentOne(row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _lastError.value = e.message
                if (e !is ApiException) {
                    // A converter/Room failure can occur after the server
                    // committed but before this device durably observed it.
                    // That is ambiguous money state, never a reason to park
                    // the row or let staff collect again. Keep replaying the
                    // same idempotency key after the app problem is resolved.
                    dao.notePendingPaymentError(
                        row.localId,
                        "Could not verify this confirmed payment yet (app error): ${e.message}",
                    )
                    passHadAmbiguousFailure = true
                    return
                }
                // A dropped response must remain pending so the exact same
                // idempotency key replays. A different cashier's active lease
                // is also temporary: wait rather than turning already-taken
                // money into a false permanent rejection.
                if (
                    e.mustPreserveOutbox ||
                    HeldOrderClaimPolicy.shouldReplayConfirmedPayment(e.status, e.code)
                ) {
                    dao.notePendingPaymentError(
                        row.localId,
                        e.message ?: "Waiting to reconcile this confirmed payment.",
                    )
                    passHadAmbiguousFailure = true
                    if (e.status == 426) throw e
                    return
                }
                dao.markPaymentRejected(
                    row.localId,
                    e.message ?: "Server refused this confirmed payment; manager review required.",
                )
            }
        }
    }

    private suspend fun pushHeldOrderPaymentOne(row: LocalHeldOrderPaymentEntity) {
        var token = row.claimToken
        var reacquisitions = 0
        while (true) {
            if (token == null) {
                token = acquireMatchingClaimForConfirmedPayment(row)
            }
            try {
                ApiClient.api.recordPayment(
                    row.targetOrderId,
                    PaymentRequest(
                        method = row.method,
                        amountMinor = row.amountMinor,
                        tenderedMinor = row.tenderedMinor,
                        expectedTotalMinor = row.expectedTotalMinor,
                        expectedDueMinor = row.expectedDueMinor,
                    ),
                    idempotencyKey = HeldOrderClaimPolicy.paymentIdempotencyKey(row.localId),
                    checkoutClaimToken = token,
                    provenance = outboxProvenanceHeaders(
                        row.createdAtMillis,
                        HeldOrderClaimPolicy.paymentIdempotencyKey(row.localId),
                    ),
                )
                db.heldOrderDao().markPaymentSynced(row.localId)
                // The order this just paid is no longer held — drop it from
                // the queue now rather than waiting for realtime.
                pullHeldOrders()
                return
            } catch (e: ApiException) {
                if (
                    !HeldOrderClaimPolicy.shouldReacquireAfterPaymentError(e.code) ||
                    reacquisitions >= 2
                ) {
                    throw e
                }
                // Always tried the stored token + original idempotency key
                // first. If a previous ambiguous response actually committed,
                // the backend replays it before claim validation. Only a
                // definitive claim error reaches this safe reacquisition.
                token = acquireMatchingClaimForConfirmedPayment(row)
                reacquisitions += 1
            }
        }
    }

    private suspend fun acquireMatchingClaimForConfirmedPayment(
        row: LocalHeldOrderPaymentEntity,
    ): String {
        val claim = ApiClient.api.acquireCheckoutClaim(
            row.targetOrderId,
            outboxProvenanceHeaders(row.createdAtMillis, "held-payment-claim:${row.localId}"),
        )
        val expiresAtMillis = HeldOrderClaimPolicy.claimExpiryMillis(claim)
        if (
            expiresAtMillis == null ||
            !HeldOrderClaimPolicy.matchesConfirmedSettlement(row, claim)
        ) {
            try {
                ApiClient.api.releaseCheckoutClaim(
                    row.targetOrderId,
                    claim.claimToken,
                    outboxProvenanceHeaders(row.createdAtMillis, "held-payment-claim-release:${row.localId}"),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(REFRESH_LOG_TAG, "Mismatched checkout claim release failed", failure)
            }
            throw ApiException(
                "The live bill changed after payment was confirmed locally. Do not collect " +
                    "again; a manager must reconcile this saved payment.",
                status = 409,
                code = "checkout_claim_settlement_changed",
            )
        }
        val updated = db.heldOrderDao().updatePaymentClaim(
            localId = row.localId,
            token = claim.claimToken,
            expiresAtMillis = expiresAtMillis,
            orderVersion = claim.orderVersion,
        )
        check(updated == 1) {
            "Confirmed held-order payment disappeared before its claim could be saved."
        }
        return claim.claimToken
    }

    /**
     * No shift or any other id to resolve first — see [LocalCustomerEntity]'s
     * doc comment for why a customer write never has a dependency chain the
     * way orders/gaming/tables do.
     */
    private suspend fun pushCustomers() {
        val dao = db.customerDao()
        drainOutbox(
            rows = dao.pushable(),
            markRejected = { row, msg ->
                dao.markRejected(
                    localId = row.localId,
                    error = msg,
                    expectedVersion = row.version,
                )
            },
            push = ::pushCustomerOne,
        )
    }

    /**
     * Order matters here, more than in most push-one functions:
     *
     * 1. `setServerId` lands the moment the response names it — independent
     *    of `state`, so a brand-new customer never has a window where it's
     *    absent from both the local override (still `state = pending`,
     *    still visible) AND the cache (not pulled yet). Without this
     *    separation, marking the row `synced` immediately would remove it
     *    from `observeLocal()` before the cache pull below had a chance to
     *    repopulate it — a guaranteed, every-single-create "the customer I
     *    just added vanished" flash. Landing `serverId` first also means
     *    `mergeCustomers` starts resolving this row against the real cache
     *    row (once pulled) by id rather than a since-superseded phone match.
     * 2. `pullCustomers()` runs before `markSynced` — if this GET fails (a
     *    dropped connection right after a successful write is a realistic
     *    cafe-wifi moment), the row is simply left `pending` with its real
     *    `serverId` already recorded; the next sync() retries it as a PATCH
     *    (absolute-set, so re-sending the same values is harmless) instead
     *    of leaving a `synced`-but-never-deleted, invisible orphan row that
     *    only some unrelated later push would happen to clean up.
     * 3. `markSynced` only applies if `version` still matches what was just
     *    pushed (see LocalCustomerEntity's doc comment) — if a newer edit
     *    landed on this row while this network call was in flight, this is
     *    a no-op and the row stays pending with the newer content, so the
     *    next sync() actually sends it instead of it being silently
     *    discarded when this stale write's response arrives.
     * 4. `deleteSynced()` only runs once this row is confirmed to actually
     *    be the one that got marked synced (step 3 applied) — deleting
     *    unconditionally here would remove a row a concurrent newer edit
     *    just legitimately re-armed back to pending.
     */
    private suspend fun pushCustomerOne(row: LocalCustomerEntity) {
        val dao = db.customerDao()
        val server = if (row.serverId == null) {
            customersApi.upsert(
                CustomerUpsertBody(
                    phone = row.phone!!,
                    name = row.name,
                    email = row.email,
                    birthday = row.birthday,
                    notes = row.notes,
                ),
                outboxProvenanceHeaders(row.createdAtMillis, "customer-upsert:${row.localId}"),
            )
        } else {
            customersApi.update(
                row.serverId,
                CustomerUpdateBody(
                    name = row.name,
                    phone = row.phone,
                    email = row.email,
                    birthday = row.birthday,
                    notes = row.notes,
                ),
                outboxProvenanceHeaders(row.createdAtMillis, "customer-update:${row.localId}"),
            )
        }
        dao.setServerId(row.localId, server.id)
        pullCustomers()
        val applied = dao.markSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSynced()
    }

    /**
     * The full customer list, wholesale-replaced — same shape as pullMenu.
     * On-demand only (see onDemandPulls): opening Customers, a "customers"
     * realtime event, or this device's own write finishing (see
     * pushCustomerOne) triggers it, not every sync().
     */
    private suspend fun pullCustomers() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { customersApi.list(limit = 500) },
            store = { customers ->
                db.withTransaction {
                    db.customerDao().replaceCache(
                        customers.map {
                            CustomerCacheEntity(
                                id = it.id,
                                name = it.name,
                                phone = it.phone,
                                email = it.email,
                                birthday = it.birthday,
                                visitCount = it.visitCount,
                                totalSpentMinor = it.totalSpentMinor,
                                loyaltyPoints = it.loyaltyPoints,
                                lifetimeGamingPointsEarned = it.lifetimeGamingPointsEarned,
                                gamingRank = it.gamingRank,
                                gamingRankFloor = it.gamingRankFloor,
                                nextGamingRank = it.nextGamingRank,
                                nextGamingRankFloor = it.nextGamingRankFloor,
                                pointsToNextGamingRank = it.pointsToNextGamingRank,
                                lastVisitAt = it.lastVisitAt,
                                notes = it.notes,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("customers", System.currentTimeMillis()))
                }
            },
        )
    }

    private suspend fun pushStaff() {
        val dao = db.staffDao()
        drainOutbox(
            rows = dao.pushable(),
            markRejected = { row, msg ->
                dao.markRejected(
                    localId = row.localId,
                    error = msg,
                    expectedVersion = row.version,
                )
            },
            push = ::pushStaffOne,
        )
    }

    /**
     * `serverId` is always already set here — see [LocalStaffEntity]'s doc
     * comment on why a staff write is never a create. `pendingDelete` wins
     * over any field edits also sitting on the row: a delete is a terminal
     * state, there is nothing left to PATCH after it.
     *
     * The delete branch's `status != 404` check is the one real subtlety:
     * `DELETE /staff/users/{id}` 404s on an already-deleted user rather than
     * silently no-op'ing (backend/app/api/v1/staff/router.py delete_user), so
     * a dropped connection right after a successful delete would otherwise
     * make a clean retry look like a failure — see the entity's doc comment
     * for the full reasoning. Same pull-then-CAS-markSynced-then-
     * conditional-delete ordering as pushCustomerOne otherwise, for the same
     * reasons: `pullStaff()` before `markSynced` so a failed pull just
     * leaves the row pending for a harmless retry instead of a synced-but-
     * orphaned row; `markSynced`'s version check so a newer edit that landed
     * while this call was in flight is not silently discarded.
     */
    private suspend fun pushStaffOne(row: LocalStaffEntity) {
        val dao = db.staffDao()
        if (row.pendingDelete) {
            try {
                staffApi.delete(
                    row.serverId,
                    outboxProvenanceHeaders(row.createdAtMillis, "staff-delete:${row.localId}"),
                )
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        } else {
            staffApi.update(
                row.serverId,
                StaffUserUpdateBody(
                    name = row.name,
                    phone = row.phone,
                    status = row.status,
                    roleCode = row.roleCode,
                ),
                outboxProvenanceHeaders(row.createdAtMillis, "staff-update:${row.localId}"),
            )
        }
        pullStaff()
        val applied = dao.markSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSynced()
    }

    /**
     * The full staff list, wholesale-replaced — same shape as pullCustomers.
     * On-demand only (see onDemandPulls): opening Staff, a "staff" realtime
     * event, or this device's own write finishing (see pushStaffOne)
     * triggers it, not every sync().
     */
    private suspend fun pullStaff() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { staffApi.list() },
            store = { users ->
                db.withTransaction {
                    db.staffDao().replaceCache(
                        users.map {
                            StaffCacheEntity(
                                id = it.id,
                                email = it.email,
                                name = it.name,
                                phone = it.phone,
                                status = it.status,
                                rolesCsv = it.roles.joinToString(","),
                                lastLoginAt = it.lastLoginAt,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("staff", System.currentTimeMillis()))
                }
            },
        )
    }

    /**
     * Who's currently clocked in — read-only, no outbox (see [OnShiftEntity]'s
     * doc comment on why clock-in/out can never be queued). On-demand only:
     * opening Staff, an "attendance" realtime event, or this device's own
     * clock-in/clock-out finishing (see StaffViewModel.clockIn/clockOut)
     * triggers it.
     */
    private suspend fun pullOnShift() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { staffApi.onShift() },
            store = { rows ->
                db.attendanceDao().replaceOnShift(
                    rows.map {
                        OnShiftEntity(
                            attendanceId = it.id,
                            userId = it.userId,
                            userName = it.userName,
                            userEmail = it.userEmail,
                            branchId = it.branchId,
                            branchName = it.branchName,
                            clockInAt = it.clockInAt,
                        )
                    },
                )
            },
        )
    }

    // ---------------------------------------------------------- ingredients

    private suspend fun pushIngredients() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableIngredients(),
            markRejected = { row, msg ->
                dao.markIngredientRejected(
                    localId = row.localId,
                    error = msg,
                    expectedVersion = row.version,
                )
            },
            push = ::pushIngredientOne,
        )
    }

    /**
     * Unlike Staff, an ingredient genuinely can be created offline — there's
     * no live-round-trip block on it — so, matching Customers/Menu-category,
     * `serverId == null` means this row is still an unsynced create. Delete
     * is folded in via `pendingDelete`, same as Staff, including the same
     * 404-tolerance on the delete branch (see LocalIngredientEntity's doc
     * comment) — except here a delete can also target a row that was queued
     * and then removed before it ever reached the server at all
     * (`serverId == null`), which needs no network call, just dropping the
     * local row outright.
     */
    private suspend fun pushIngredientOne(row: LocalIngredientEntity) {
        val dao = db.inventoryDao()
        if (row.pendingDelete) {
            val serverId = row.serverId
            if (serverId == null) {
                dao.deleteLocalIngredient(row.localId)
                return
            }
            try {
                inventoryApi.deleteIngredient(
                    serverId,
                    outboxProvenanceHeaders(row.createdAtMillis, "ingredient-delete:${row.localId}"),
                )
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        } else if (row.serverId == null) {
            if (
                row.state != IngredientWriteState.CREATE_ATTEMPTED &&
                dao.claimIngredientCreate(row.localId, row.version) == 0
            ) {
                // A local edit/delete won the CAS immediately before this
                // attempt. The next pass will read that newer row; never send
                // this stale payload under its stable idempotency key.
                return
            }
            val actionId = InventoryCreateReplayPolicy.ingredientActionId(row.localId)
            val created = inventoryApi.createIngredient(
                body = IngredientCreate(
                    sku = row.sku.orEmpty(),
                    name = row.name.orEmpty(),
                    baseUnit = row.baseUnit.orEmpty(),
                    reorderThreshold = row.reorderThreshold ?: 0.0,
                    reorderQty = row.reorderQty ?: 0.0,
                ),
                key = actionId,
                provenance = outboxProvenanceHeaders(row.createdAtMillis, actionId),
            )
            dao.setIngredientServerId(row.localId, created.id)
        } else {
            inventoryApi.updateIngredient(
                row.serverId,
                IngredientUpdate(
                    name = row.name.orEmpty(),
                    baseUnit = row.baseUnit.orEmpty(),
                    reorderThreshold = row.reorderThreshold ?: 0.0,
                    reorderQty = row.reorderQty ?: 0.0,
                ),
                outboxProvenanceHeaders(row.createdAtMillis, "ingredient-update:${row.localId}"),
            )
        }
        pullIngredients()
        val applied = dao.markIngredientSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedIngredients()
    }

    private suspend fun pullIngredients() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { inventoryApi.ingredients() },
            store = { rows ->
                db.withTransaction {
                    db.inventoryDao().replaceIngredientCache(
                        rows.map {
                            IngredientCacheEntity(
                                id = it.id, sku = it.sku, name = it.name, baseUnit = it.baseUnit,
                                currentQty = it.currentQty, reorderThreshold = it.reorderThreshold,
                                reorderQty = it.reorderQty, avgCostMinor = it.avgCostMinor,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("ingredients", System.currentTimeMillis()))
                }
            },
        )
    }

    // ------------------------------------------------------------ suppliers

    private suspend fun pushSuppliers() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableSuppliers(),
            markRejected = { row, msg ->
                dao.markSupplierRejected(
                    localId = row.localId,
                    error = msg,
                    expectedVersion = row.version,
                )
            },
            push = ::pushSupplierOne,
        )
    }

    /** Same shape as pushIngredientOne — see its doc comment. */
    private suspend fun pushSupplierOne(row: LocalSupplierEntity) {
        val dao = db.inventoryDao()
        if (row.pendingDelete) {
            val serverId = row.serverId
            if (serverId == null) {
                dao.deleteLocalSupplier(row.localId)
                return
            }
            try {
                inventoryApi.deleteSupplier(
                    serverId,
                    outboxProvenanceHeaders(row.createdAtMillis, "supplier-delete:${row.localId}"),
                )
            } catch (e: ApiException) {
                if (e.status != 404) throw e
            }
        } else if (row.serverId == null) {
            if (
                row.state != SupplierWriteState.CREATE_ATTEMPTED &&
                dao.claimSupplierCreate(row.localId, row.version) == 0
            ) {
                return
            }
            val actionId = InventoryCreateReplayPolicy.supplierActionId(row.localId)
            val created = inventoryApi.createSupplier(
                body = SupplierBody(
                    name = row.name.orEmpty(), contact = row.contact,
                    gstin = row.gstin, paymentTerms = row.paymentTerms,
                ),
                key = actionId,
                provenance = outboxProvenanceHeaders(row.createdAtMillis, actionId),
            )
            dao.setSupplierServerId(row.localId, created.id)
        } else {
            inventoryApi.updateSupplier(
                row.serverId,
                SupplierBody(
                    name = row.name.orEmpty(), contact = row.contact,
                    gstin = row.gstin, paymentTerms = row.paymentTerms,
                ),
                outboxProvenanceHeaders(row.createdAtMillis, "supplier-update:${row.localId}"),
            )
        }
        pullSuppliers()
        val applied = dao.markSupplierSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedSuppliers()
    }

    private suspend fun pullSuppliers() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { inventoryApi.suppliers() },
            store = { rows ->
                db.withTransaction {
                    db.inventoryDao().replaceSupplierCache(
                        rows.map {
                            SupplierCacheEntity(
                                id = it.id, name = it.name, contact = it.contact,
                                gstin = it.gstin, paymentTerms = it.paymentTerms,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("suppliers", System.currentTimeMillis()))
                }
            },
        )
    }

    private suspend fun pullInventoryOnDemand() {
        pullIngredients()
        pullSuppliers()
        // Batch rows are demand-scoped. Refresh the active detail projection
        // on realtime/manual inventory invalidation instead of leaving a
        // selected FIFO quantity stale after another terminal changes stock.
        val currentLease = cacheIsolation.currentLease()
        val target = activeBatchTarget.get()
        if (target != null && target.lease == currentLease) {
            pullBatchesForAlreadyLocked(target.ingredientId)
        } else if (target != null) {
            activeBatchTarget.compareAndSet(target, null)
        }
    }

    /**
     * FIFO batches for one ingredient — demand-loaded on selection, not part
     * of [onDemandPulls] (that map is keyed by realtime *resource* name, this
     * is keyed by which ingredient a screen currently has open). Called
     * directly by InventoryViewModel.select().
     */
    suspend fun pullBatchesFor(ingredientId: String): ResourceRefreshResult {
        val requestedId = ingredientId.trim()
        if (requestedId.isEmpty()) return ResourceRefreshResult.Skipped("inventory")
        return withResourceSerialisation("inventory") {
            if (!currentResourceAccess().canPull("inventory")) {
                ResourceRefreshResult.Skipped("inventory").also(resourceRefreshFeedback::record)
            } else {
                val lease = cacheIsolation.currentLease()
                    ?: return@withResourceSerialisation ResourceRefreshResult.Skipped("inventory")
                        .also(resourceRefreshFeedback::record)
                activeBatchTarget.set(ActiveBatchTarget(requestedId, lease))
                runAndRecordRefreshAlreadyLocked("inventory") {
                    pullBatchesForAlreadyLocked(requestedId)
                }
            }
        }
    }

    /**
     * Stop realtime/manual inventory refreshes from retaining a detail request
     * after that detail closes. The expected id prevents an older ViewModel's
     * onCleared callback from erasing a newer screen's selection.
     */
    fun clearActiveBatchTarget(expectedIngredientId: String?) {
        while (true) {
            val current = activeBatchTarget.get() ?: return
            if (expectedIngredientId != null && current.ingredientId != expectedIngredientId) return
            if (activeBatchTarget.compareAndSet(current, null)) return
        }
    }

    /** Canonical inventory-resource lock is already held. */
    private suspend fun pullBatchesForAlreadyLocked(ingredientId: String) {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { inventoryApi.batches(ingredientId) },
            store = { rows ->
                db.inventoryDao().replaceBatchesFor(
                    ingredientId,
                    rows.map {
                        BatchCacheEntity(
                            id = it.id, ingredientId = it.ingredientId, receivedAt = it.receivedAt,
                            expiresAt = it.expiresAt, qtyOnHand = it.qtyOnHand,
                            costPerUnitMinor = it.costPerUnitMinor, lotCode = it.lotCode,
                        )
                    },
                )
            },
        )
    }

    // -------------------------------------------------------------------- GRN

    private suspend fun pushGrns() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableGrns(),
            markRejected = { row, msg -> dao.markGrnRejected(row.localId, msg) },
            push = ::pushGrnOne,
        )
    }

    /**
     * Shape D, single call (unlike pushOne for orders, there's no second
     * chained payment call — a GRN is complete in one POST). Insert-only, so
     * unlike ingredients/suppliers there is no version-CAS on markGrnSynced:
     * nothing can amend an already-captured GRN row out from under an
     * in-flight push the way an edit can for master data.
     */
    private suspend fun pushGrnOne(grn: LocalGrnEntity) {
        val dao = db.inventoryDao()
        val lines = dao.grnLinesFor(grn.localId)
        inventoryApi.postGrn(
            GrnBody(
                branchId = grn.branchId,
                supplierId = grn.supplierId,
                supplierInvoiceNo = grn.supplierInvoiceNo,
                notes = grn.notes,
                lines = lines.map {
                    GrnLineBody(
                        ingredientId = it.ingredientId, qty = it.qty,
                        unitCostMinor = it.unitCostMinor,
                        expiresAt = it.expiresAt, lotCode = it.lotCode,
                    )
                },
            ),
            key = "grn:${grn.localId}",
            provenance = outboxProvenanceHeaders(grn.createdAtMillis, "grn:${grn.localId}"),
        )
        // The GRN itself rewrote current_qty/avg_cost_minor server-side —
        // reflect that immediately rather than waiting for the next
        // unrelated pull.
        pullIngredients()
        lines.map(LocalGrnLineEntity::ingredientId).distinct().forEach {
            pullBatchesForAlreadyLocked(it)
        }
        dao.markGrnSynced(grn.localId)
    }

    // ----------------------------------------------------------- adjustments

    private suspend fun pushAdjustments() {
        val dao = db.inventoryDao()
        drainOutbox(
            rows = dao.pushableAdjustments(),
            markRejected = { row, msg -> dao.markAdjustmentRejected(row.localId, msg) },
            push = ::pushAdjustmentOne,
        )
    }

    /** Same insert-only reasoning as pushGrnOne — no version-CAS needed. */
    private suspend fun pushAdjustmentOne(row: LocalAdjustmentEntity) {
        inventoryApi.postAdjustment(
            AdjustmentBody(
                ingredientId = row.ingredientId, branchId = row.branchId,
                qtyDelta = row.qtyDelta, type = row.type, note = row.note,
            ),
            key = "adjustment:${row.localId}",
            provenance = outboxProvenanceHeaders(row.createdAtMillis, "adjustment:${row.localId}"),
        )
        pullIngredients()
        pullBatchesForAlreadyLocked(row.ingredientId)
        db.inventoryDao().markAdjustmentSynced(row.localId)
    }

    // ---------------------------------------------------------------- expenses

    private suspend fun pushExpenses() {
        val dao = db.financeDao()
        drainOutbox(
            rows = dao.pushableExpenses(),
            markRejected = { row, msg -> dao.markExpenseRejected(row.localId, msg) },
            push = ::pushExpenseOne,
        )
    }

    /** Insert-only, same reasoning as pushGrnOne/pushAdjustmentOne above — no
     * version-CAS needed: expense edit/delete stay web-only, so nothing can
     * amend this row out from under an in-flight push. */
    private suspend fun pushExpenseOne(row: LocalExpenseEntity) {
        financeApi.createExpense(
            ExpenseCreate(
                branchId = row.branchId, categoryId = row.categoryId, supplierId = row.supplierId,
                amountMinor = row.amountMinor, paidVia = row.paidVia, paidAt = row.paidAt,
                vendorName = row.vendorName, invoiceNo = row.invoiceNo, note = row.note,
            ),
            key = "expense:${row.localId}",
            provenance = outboxProvenanceHeaders(row.createdAtMillis, "expense:${row.localId}"),
        )
        pullExpenses()
        db.financeDao().markExpenseSynced(row.localId)
    }

    private suspend fun pullExpenses() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { financeApi.expenses() },
            store = { rows ->
                db.withTransaction {
                    db.financeDao().replaceExpenseCache(
                        rows.map {
                            ExpenseCacheEntity(
                                id = it.id, branchId = it.branchId, categoryId = it.categoryId,
                                supplierId = it.supplierId, amountMinor = it.amountMinor,
                                paidVia = it.paidVia, paidAt = it.paidAt,
                                vendorName = it.vendorName, invoiceNo = it.invoiceNo, note = it.note,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("expenses", System.currentTimeMillis()))
                }
            },
        )
    }

    // ------------------------------------------------------------------- assets

    private suspend fun pushAssets() {
        val dao = db.financeDao()
        drainOutbox(
            rows = dao.pushableAssets(),
            markRejected = { row, msg -> dao.markAssetRejected(row.localId, msg) },
            push = ::pushAssetOne,
        )
    }

    /** Same insert-only reasoning as pushExpenseOne — assets have no backend
     * edit/delete endpoint at all, so there is nothing to guard against. */
    private suspend fun pushAssetOne(row: LocalAssetEntity) {
        financeApi.createAsset(
            AssetCreate(
                branchId = row.branchId, name = row.name, type = row.type,
                purchaseMinor = row.purchaseMinor, purchaseDate = row.purchaseDate,
                usefulLifeMonths = row.usefulLifeMonths, salvageMinor = row.salvageMinor,
                notes = row.notes,
            ),
            key = "asset:${row.localId}",
            provenance = outboxProvenanceHeaders(row.createdAtMillis, "asset:${row.localId}"),
        )
        pullAssets()
        db.financeDao().markAssetSynced(row.localId)
    }

    private suspend fun pullAssets() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { financeApi.assets() },
            store = { rows ->
                db.withTransaction {
                    db.financeDao().replaceAssetCache(
                        rows.map {
                            AssetCacheEntity(
                                id = it.id, branchId = it.branchId, name = it.name, type = it.type,
                                purchaseMinor = it.purchaseMinor, purchaseDate = it.purchaseDate,
                                usefulLifeMonths = it.usefulLifeMonths, salvageMinor = it.salvageMinor,
                                depreciationMethod = it.depreciationMethod, notes = it.notes,
                                accumulatedDepreciationMinor = it.accumulatedDepreciationMinor,
                                bookValueMinor = it.bookValueMinor,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("assets", System.currentTimeMillis()))
                }
            },
        )
    }

    // ---------------------------------------------------------- capital entries

    private suspend fun pushCapitalEntries() {
        val dao = db.financeDao()
        drainOutbox(
            rows = dao.pushableCapitalEntries(),
            markRejected = { row, msg -> dao.markCapitalEntryRejected(row.localId, msg) },
            push = ::pushCapitalEntryOne,
        )
    }

    /** Same insert-only reasoning — a capital entry is void-only after
     * creation, and void stays online-only (direct write, not queued). */
    private suspend fun pushCapitalEntryOne(row: LocalCapitalEntryEntity) {
        financeApi.createCapitalEntry(
            CapitalEntryCreate(
                partnerId = row.partnerId, type = row.type, amountMinor = row.amountMinor,
                effectiveAt = row.effectiveAt, settlementAccount = row.settlementAccount,
                sourceRef = row.sourceRef, note = row.note,
            ),
            key = "capital-entry:${row.localId}",
            provenance = outboxProvenanceHeaders(row.createdAtMillis, "capital-entry:${row.localId}"),
        )
        pullCapitalEntriesForAlreadyLocked(row.partnerId)
        db.financeDao().markCapitalEntrySynced(row.localId)
    }

    /**
     * Per-partner, on-demand pull — called directly by FinanceViewModel on
     * partner selection, not part of [onDemandPulls] (that map is keyed by
     * realtime *resource* name, this is keyed by which partner's capital
     * history a screen currently has open). Same shape as pullBatchesFor.
     */
    suspend fun pullCapitalEntriesFor(partnerId: String) {
        withResourceSerialisation("finance") {
            pullCapitalEntriesForAlreadyLocked(partnerId)
        }
    }

    /** Canonical finance-resource lock is already held. */
    private suspend fun pullCapitalEntriesForAlreadyLocked(partnerId: String) {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { financeApi.capitalEntries(partnerId) },
            store = { rows ->
                db.financeDao().replaceCapitalEntriesFor(
                    partnerId,
                    rows.map {
                        CapitalEntryCacheEntity(
                            id = it.id, partnerId = it.partnerId, type = it.type,
                            amountMinor = it.amountMinor, effectiveAt = it.effectiveAt,
                            settlementAccount = it.settlementAccount, sourceRef = it.sourceRef,
                            note = it.note, createdByName = it.createdByName, createdAt = it.createdAt,
                            voidedAt = it.voidedAt, voidReason = it.voidReason, isVoided = it.isVoided,
                        )
                    },
                )
            },
        )
    }

    private suspend fun pullFinanceOnDemand() {
        // Operational totals are the primary Finance read. Fetch them first so
        // an expense-list failure cannot leave a successfully reachable P&L stale.
        val missingReferences = pullFinanceSnapshots()
        pullExpenses()
        pullAssets()
        if (missingReferences.isNotEmpty()) {
            // P&L/metrics/rows were committed above, but this refresh must stay
            // visibly partial because the Android write forms require these
            // reference lists and cannot treat an unfetched list as empty.
            throw FinanceReferenceRefreshException(missingReferences)
        }
    }

    /**
     * Aggregate/reference Finance snapshots share the same lock as finance
     * outbox confirmation and realtime refresh. One older P&L GET therefore
     * cannot commit after a newer expense, asset, or capital write.
     */
    private suspend fun pullFinanceSnapshots(): List<String> {
        val lease = cacheIsolation.currentLease() ?: return emptyList()
        val cacheScope = FinanceCacheScope(
            companyId = lease.scope.companyId,
            branchId = lease.scope.branchId,
        )
        return coroutineScope {
            val plDeferred = async { financeApi.profitAndLoss() }
            val metricsDeferred = async { financeApi.metrics() }
            val distributableDeferred = async { financeApi.distributable() }
            val partnersDeferred = async { financeApi.partners() }
            val branchesDeferred = async {
                optionalFinanceReference("branches") { financeApi.branches() }
            }
            val categoriesDeferred = async {
                optionalFinanceReference("expense categories") { financeApi.expenseCategories() }
            }

            val freshPl = plDeferred.await()
            val freshMetrics = metricsDeferred.await()
            val freshDistributable = distributableDeferred.await()
            val freshPartners = partnersDeferred.await()
            val freshBranches = branchesDeferred.await()
            val freshCategories = categoriesDeferred.await()
            val fetchedAt = System.currentTimeMillis()

            commitToCurrentScope(lease) {
                db.withTransaction {
                    val snapshots = db.reportSnapshotDao()
                    snapshots.put(
                        ReportSnapshotEntity(
                            cacheScope.key(FinanceSnapshotKeys.PNL),
                            ApiClient.json.encodeToString(freshPl),
                            fetchedAt,
                        ),
                    )
                    snapshots.put(
                        ReportSnapshotEntity(
                            cacheScope.key(FinanceSnapshotKeys.METRICS),
                            ApiClient.json.encodeToString(freshMetrics),
                            fetchedAt,
                        ),
                    )
                    snapshots.put(
                        ReportSnapshotEntity(
                            cacheScope.key(FinanceSnapshotKeys.DISTRIBUTABLE),
                            ApiClient.json.encodeToString(freshDistributable),
                            fetchedAt,
                        ),
                    )
                    snapshots.put(
                        ReportSnapshotEntity(
                            cacheScope.key(FinanceSnapshotKeys.PARTNERS),
                            ApiClient.json.encodeToString(freshPartners),
                            fetchedAt,
                        ),
                    )
                    freshBranches.value?.let {
                        snapshots.put(
                            ReportSnapshotEntity(
                                cacheScope.key(FinanceSnapshotKeys.BRANCHES),
                                ApiClient.json.encodeToString(it),
                                fetchedAt,
                            ),
                        )
                    }
                    freshCategories.value?.associate { it.id to it.name }?.let {
                        snapshots.put(
                            ReportSnapshotEntity(
                                cacheScope.key(FinanceSnapshotKeys.CATEGORIES),
                                ApiClient.json.encodeToString(it),
                                fetchedAt,
                            ),
                        )
                    }
                }
            }
            return@coroutineScope listOfNotNull(
                "branches".takeIf { freshBranches.failure != null },
                "expense categories".takeIf { freshCategories.failure != null },
            )
        }
    }

    private data class FinanceReferenceResult<T>(
        val value: T?,
        val failure: Exception?,
    )

    private suspend fun <T> optionalFinanceReference(
        label: String,
        fetch: suspend () -> T,
    ): FinanceReferenceResult<T> = try {
        FinanceReferenceResult(fetch(), null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Log.w(REFRESH_LOG_TAG, "Optional Finance reference refresh failed: $label", failure)
        FinanceReferenceResult(null, failure)
    }

    // -------------------------------------------------------------- events

    private suspend fun pullEventsData() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { eventsApi.listAll() },
            store = { rows ->
                db.withTransaction {
                    db.eventDao().replaceEventCache(
                        rows.map {
                            EventCacheEntity(
                                id = it.id, name = it.name, description = it.description,
                                eventType = it.eventType, screen = it.screen, startsAt = it.startsAt,
                                endsAt = it.endsAt, capacity = it.capacity, sold = it.sold,
                                remaining = it.remaining,
                                baseTicketPriceMinor = it.baseTicketPriceMinor,
                                sacCode = it.sacCode, taxRate = it.taxRate, status = it.status,
                                posterUrl = it.posterUrl,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("events", System.currentTimeMillis()))
                }
            },
        )
    }

    /**
     * Per-event ticket list — demand-loaded on selection, not part of
     * [onDemandPulls] (that map is keyed by realtime *resource* name, this
     * is keyed by which event a screen currently has open). Called
     * directly by EventsViewModel.selectEvent().
     */
    suspend fun pullTicketsFor(eventId: String) {
        withResourceSerialisation("events") {
            pullTicketsForAlreadyLocked(eventId)
        }
    }

    /** Canonical events-resource lock is already held. */
    private suspend fun pullTicketsForAlreadyLocked(eventId: String) {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { eventsApi.listTickets(eventId) },
            store = { rows ->
                db.eventDao().replaceTicketsFor(
                    eventId,
                    rows.map {
                        EventTicketCacheEntity(
                            id = it.id, eventId = it.eventId, ticketNo = it.ticketNo,
                            eventName = it.eventName, customerName = it.customerName,
                            customerPhone = it.customerPhone, seat = it.seat,
                            pricePaidMinor = it.pricePaidMinor, status = it.status,
                            checkedInAt = it.checkedInAt,
                        )
                    },
                )
            },
        )
    }

    private suspend fun pushTicketSales() {
        val dao = db.eventDao()
        drainOutbox(
            rows = dao.pushableTicketSales(),
            markRejected = { row, msg -> dao.markTicketSaleRejected(row.localId, msg) },
            push = ::pushTicketSaleOne,
        )
    }

    /**
     * Insert-only, same reasoning as pushGrnOne/pushAdjustmentOne — no
     * version-CAS needed: a ticket sale is never edited, only ever queued
     * once. Refreshes the event (sold/remaining) and this event's ticket
     * list immediately, same as GRN refreshing ingredients after posting.
     */
    private suspend fun pushTicketSaleOne(row: LocalTicketSaleEntity) {
        eventsApi.sellTickets(
            row.eventId,
            TicketSell(
                customerName = row.customerName, customerPhone = row.customerPhone,
                seat = row.seat, qty = row.qty, note = row.note,
            ),
            key = "ticket-sale:${row.localId}",
            provenance = outboxProvenanceHeaders(row.createdAtMillis, "ticket-sale:${row.localId}"),
        )
        pullEventsData()
        pullTicketsForAlreadyLocked(row.eventId)
        db.eventDao().markTicketSaleSynced(row.localId)
    }

    private suspend fun pushCheckIns() {
        val dao = db.eventDao()
        drainOutbox(
            rows = dao.pushableCheckIns(),
            markRejected = { row, msg -> dao.markCheckInRejected(row.localId, msg) },
            push = ::pushCheckInOne,
        )
    }

    /** Shape C — targets an existing server ticket id, no CAS needed since
     * a check-in is a one-way transition the server itself already guards
     * (re-checking-in an already-checked-in ticket is a clean 4xx, not a
     * silent double-effect). */
    private suspend fun pushCheckInOne(row: LocalCheckInEntity) {
        eventsApi.checkIn(
            row.eventId,
            row.ticketId,
            outboxProvenanceHeaders(row.createdAtMillis, "ticket-check-in:${row.localId}"),
        )
        pullTicketsForAlreadyLocked(row.eventId)
        db.eventDao().markCheckInSynced(row.localId)
    }

    // --------------------------------------------------------- memberships

    private suspend fun pullTiers() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { membershipsApi.listTiers() },
            store = { rows ->
                db.withTransaction {
                    db.membershipDao().replaceTierCache(
                        rows.map {
                            MembershipTierCacheEntity(
                                id = it.id, code = it.code, name = it.name,
                                monthlyPriceMinor = it.monthlyPriceMinor,
                                annualPriceMinor = it.annualPriceMinor,
                                foodDiscountPct = it.foodDiscountPct,
                                gamingDiscountPct = it.gamingDiscountPct,
                                hookahDiscountPct = it.hookahDiscountPct,
                                pointMultiplier = it.pointMultiplier,
                                freeGamingMinutesPerWeek = it.freeGamingMinutesPerWeek,
                                freeHookahPerMonth = it.freeHookahPerMonth,
                                priorityBooking = it.priorityBooking,
                                description = it.description, sortOrder = it.sortOrder,
                            )
                        },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("memberships", System.currentTimeMillis()))
                }
            },
        )
    }

    /**
     * Per-customer active-membership lookup — demand-loaded on selection,
     * not part of [onDemandPulls] (that map is keyed by realtime *resource*
     * name, this is keyed by which customer a screen currently has open).
     * Called directly by MembershipsViewModel.selectCustomer(). A null
     * response (no active membership) clears this customer's cache row
     * rather than leaving a stale one showing.
     */
    suspend fun pullMembershipFor(customerId: String) {
        withResourceSerialisation("memberships") {
            pullMembershipForAlreadyLocked(customerId)
        }
    }

    /** Canonical memberships-resource lock is already held. */
    private suspend fun pullMembershipForAlreadyLocked(customerId: String) {
        val lease = cacheIsolation.currentLease() ?: return
        val sub = membershipsApi.getCustomerSubscription(customerId)
        val history = membershipsApi.getCustomerMembershipHistory(customerId)
        commitToCurrentScope(lease) {
            db.withTransaction {
                db.membershipDao().replaceMembershipFor(
                    customerId,
                    listOfNotNull(sub).map {
                        CustomerMembershipCacheEntity(
                            id = it.id, customerId = it.customerId, tierId = it.tierId,
                            tierCode = it.tierCode, tierName = it.tierName,
                            billingCycle = it.billingCycle, startsAt = it.startsAt,
                            expiresAt = it.expiresAt, cancelledAt = it.cancelledAt,
                            revokedAt = it.revokedAt, autoRenew = it.autoRenew,
                            amountPaidMinor = it.amountPaidMinor,
                            paymentId = it.paymentId, paymentMethod = it.paymentMethod,
                            paymentShiftId = it.paymentShiftId,
                            paymentReceiptNo = it.paymentReceiptNo, paymentPaidAt = it.paymentPaidAt,
                            paymentEvidenceOccurredAt = it.paymentEvidenceOccurredAt,
                            paymentEvidenceTimeUntrusted = it.paymentEvidenceTimeUntrusted,
                            paymentProviderEvidenceReconciled = it.paymentProviderEvidenceReconciled,
                            refundId = it.refundId, refundStatus = it.refundStatus,
                            refundAcceptedAt = it.refundAcceptedAt, refundedAt = it.refundedAt,
                            refundMethod = it.refundMethod, refundReceiptNo = it.refundReceiptNo,
                            refundExternalReference = it.refundExternalReference,
                            refundEvidenceOccurredAt = it.refundEvidenceOccurredAt,
                            refundEvidenceTimeUntrusted = it.refundEvidenceTimeUntrusted,
                            refundProviderEvidenceReconciled = it.refundProviderEvidenceReconciled,
                            refundCustomerSpendReconciled = it.refundCustomerSpendReconciled,
                            isActive = it.isActive,
                        )
                    },
                )
                db.membershipDao().replaceMembershipHistoryFor(
                    customerId,
                    history.map {
                        CustomerMembershipHistoryCacheEntity(
                            id = it.id, customerId = it.customerId, tierId = it.tierId,
                            tierCode = it.tierCode, tierName = it.tierName,
                            billingCycle = it.billingCycle, startsAt = it.startsAt,
                            expiresAt = it.expiresAt, cancelledAt = it.cancelledAt,
                            revokedAt = it.revokedAt, autoRenew = it.autoRenew,
                            amountPaidMinor = it.amountPaidMinor,
                            paymentId = it.paymentId, paymentMethod = it.paymentMethod,
                            paymentShiftId = it.paymentShiftId,
                            paymentReceiptNo = it.paymentReceiptNo,
                            paymentPaidAt = it.paymentPaidAt,
                            paymentEvidenceOccurredAt = it.paymentEvidenceOccurredAt,
                            paymentEvidenceTimeUntrusted = it.paymentEvidenceTimeUntrusted,
                            paymentProviderEvidenceReconciled = it.paymentProviderEvidenceReconciled,
                            refundId = it.refundId, refundStatus = it.refundStatus,
                            refundAcceptedAt = it.refundAcceptedAt, refundedAt = it.refundedAt,
                            refundMethod = it.refundMethod, refundReceiptNo = it.refundReceiptNo,
                            refundExternalReference = it.refundExternalReference,
                            refundEvidenceOccurredAt = it.refundEvidenceOccurredAt,
                            refundEvidenceTimeUntrusted = it.refundEvidenceTimeUntrusted,
                            refundProviderEvidenceReconciled = it.refundProviderEvidenceReconciled,
                            refundCustomerSpendReconciled = it.refundCustomerSpendReconciled,
                            isActive = it.isActive,
                        )
                    },
                )
            }
        }
    }

    /** Best-effort projection refresh that still preserves coroutine control flow. */
    private suspend fun pullMembershipForBestEffortAlreadyLocked(customerId: String) {
        try {
            pullMembershipForAlreadyLocked(customerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The confirmed money/action state is already durable. Realtime or
            // the next screen refresh can retry this replaceable projection.
            Log.e(REFRESH_LOG_TAG, "Membership projection reconciliation failed", failure)
            _lastError.value = resourceRefreshFailureMessage("memberships", failure)
        }
    }

    private fun canAccessMembershipMoney(): Boolean {
        val profile = DCompanyApp.instance.shiftCache.profile.value ?: return false
        return profile.protectedAccess &&
            EffectivePermissions.from(profile).has(ErpPermission.AdminSystem)
    }

    /**
     * A wholesale task-cache replacement is safe only when the backend says
     * the result is complete. Dropping row 201 would otherwise make shift
     * close/sign-out believe an unresolved physical-money task disappeared.
     */
    private fun <T> requireCompleteTaskList(
        response: retrofit2.Response<List<T>>,
        limit: Int,
        resource: String,
    ): List<T> {
        if (!response.isSuccessful) {
            error("Could not download $resource (HTTP ${response.code()}).")
        }
        val rows = response.body() ?: error("The server returned an empty $resource response.")
        val incomplete = try {
            taskListIsIncomplete(response.headers()["X-Result-Truncated"], rows.size, limit)
        } catch (_: IllegalArgumentException) {
            error("The server returned an invalid completeness marker for $resource.")
        }
        if (incomplete) {
            error(
                "$resource exceeds this app's safe $limit-row cache. No rows were replaced; " +
                    "resolve older tasks or update the app before closing the shift.",
            )
        }
        return rows
    }

    /**
     * Pull recent canonical tasks before replaying any stage. A decoding or
     * transport failure leaves the prior cache intact and prevents a queued
     * close from overtaking an unknown money state.
     */
    private suspend fun pullMembershipPaymentTasksBestEffort(): Boolean {
        if (!canAccessMembershipMoney()) return false
        return try {
            val lease = cacheIsolation.currentLease() ?: return false
            val terminalId = lease.scope.terminalId ?: return false
            val branchId = lease.scope.branchId ?: return false
            val tasks = requireCompleteTaskList(
                membershipsApi.paymentRequests(unresolved = true, limit = 200),
                limit = 200,
                resource = "membership payment tasks",
            )
            if (tasks.any { it.status !in MembershipPaymentTaskStatus.known }) {
                _lastError.value =
                    "Membership payment tasks contain a status this app does not understand. Update the app before touching money."
                passHadAmbiguousFailure = true
                return false
            }
            val fetchedAt = System.currentTimeMillis()
            val rows = tasks.map { it.toCache(branchId, terminalId, fetchedAt) }
            commitToCurrentScope(lease) {
                db.withTransaction {
                    db.membershipPaymentDao().replaceTasksForTerminal(terminalId, rows)
                    db.syncMetaDao().put(SyncMetaEntity("membership_payment_tasks", fetchedAt))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Could not reconcile membership payment tasks."
            // A missing/truncated canonical list is itself an unknown money
            // state, even when this device has no local action to replay.
            passHadAmbiguousFailure = true
            false
        }
    }

    private suspend fun reconcileMembershipPaymentActionsFromCache() {
        val dao = db.membershipPaymentDao()
        dao.pushableActions().forEach { action ->
            val task = action.serverRequestId?.let { dao.taskById(it) }
                ?: dao.taskByClientActionId(action.rootClientActionId)
            if (task != null && paymentActionSatisfied(action, task)) {
                dao.markSynced(action.actionId, task.id)
            }
        }
    }

    private suspend fun ensureMembershipPaymentFinalizations() {
        val dao = db.membershipPaymentDao()
        val profile = DCompanyApp.instance.shiftCache.profile.value ?: return
        dao.unresolvedTasks()
            .filter { it.status == MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING }
            .forEach { task ->
                val actionId = "membership-payment-finalize:${task.id}"
                dao.insertAction(
                    LocalMembershipPaymentActionEntity(
                        actionId = actionId,
                        rootClientActionId = task.clientActionId,
                        serverRequestId = task.id,
                        kind = MembershipPaymentActionKind.FINALIZE,
                        customerId = task.customerId,
                        tierId = task.tierId,
                        shiftId = task.shiftId,
                        branchId = task.branchId,
                        terminalId = task.terminalId,
                        actorUserId = profile.userId,
                        billingCycle = task.billingCycle,
                        paidVia = task.paidVia,
                        expectedAmountMinor = task.amountMinor,
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
    }

    private suspend fun pushMembershipPaymentActions() {
        if (!canAccessMembershipMoney()) return
        val dao = db.membershipPaymentDao()
        for (action in dao.pushableActions()) {
            val existing = action.serverRequestId?.let { dao.taskById(it) }
                ?: dao.taskByClientActionId(action.rootClientActionId)
            if (existing != null && paymentActionSatisfied(action, existing)) {
                dao.markSynced(action.actionId, existing.id)
                continue
            }
            try {
                pushMembershipPaymentActionOne(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val recovered = reconcileMembershipPaymentActionAfterFailure(action)
                if (recovered) continue

                val message = failure.message ?: "Membership payment state could not be verified."
                when {
                    action.kind == MembershipPaymentActionKind.LEGACY_PROBE &&
                        failure is ApiException && !failure.mustPreserveOutbox -> {
                        dao.requireLegacyRecovery(
                            action.actionId,
                            "The old direct payment did not post. Verify whether money moved; do not collect again. $message",
                        )
                    }
                    action.kind in setOf(
                        MembershipPaymentActionKind.COMPLETE,
                        MembershipPaymentActionKind.FINALIZE,
                    ) || failure !is ApiException || failure.mustPreserveOutbox -> {
                        dao.markAmbiguous(action.actionId, action.serverRequestId, message)
                        passHadAmbiguousFailure = true
                        if (failure is ApiException && failure.status == 426) throw failure
                        return
                    }
                    else -> dao.markRejected(action.actionId, message)
                }
            }
        }
        // A successful completion inserts its deterministic FINALIZE action.
        // Drain that newly-created accounting leg in the same serialized pass
        // so a healthy connection does not strand a paid customer waiting for
        // an unrelated future sync trigger.
        ensureMembershipPaymentFinalizations()
        for (action in dao.pushableActions().filter { it.kind == MembershipPaymentActionKind.FINALIZE }) {
            try {
                pushMembershipPaymentActionOne(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!reconcileMembershipPaymentActionAfterFailure(action)) {
                    dao.markAmbiguous(
                        action.actionId,
                        action.serverRequestId,
                        failure.message ?: "Membership accounting finalization could not be verified.",
                    )
                    passHadAmbiguousFailure = true
                    if (failure is ApiException && failure.status == 426) throw failure
                    return
                }
            }
        }
    }

    private suspend fun pushMembershipPaymentActionOne(
        action: LocalMembershipPaymentActionEntity,
    ) {
        val lease = cacheIsolation.currentLease()
            ?: error("The active account scope changed before membership payment sync.")
        val amount = action.expectedAmountMinor
            ?: error("This legacy payment has no captured amount and cannot be sent.")
        val shiftId = resolveMembershipShift(action.shiftId) ?: return
        val occurredAt = action.occurredAtMillis ?: action.createdAtMillis
        val provenance = outboxProvenanceHeaders(occurredAt, action.actionId)
        val result: MembershipPaymentTask? = when (action.kind) {
            MembershipPaymentActionKind.LEGACY_PROBE -> {
                membershipsApi.subscribe(
                    SubscribeRequest(
                        customerId = action.customerId,
                        tierId = action.tierId,
                        shiftId = shiftId,
                        expectedAmountMinor = amount,
                        collectedAt = Instant.ofEpochMilli(occurredAt).toString(),
                        billingCycle = action.billingCycle,
                        paidVia = action.paidVia,
                    ),
                    key = action.rootClientActionId,
                    provenance = outboxProvenanceHeaders(occurredAt, action.rootClientActionId),
                )
                if (!commitToCurrentScope(lease) {
                        db.membershipPaymentDao().markSynced(action.actionId, null)
                    }
                ) return
                pullMembershipForBestEffortAlreadyLocked(action.customerId)
                return
            }
            MembershipPaymentActionKind.PREPARE -> membershipsApi.preparePayment(
                MembershipPaymentRequestCreate(
                    customerId = action.customerId,
                    tierId = action.tierId,
                    shiftId = shiftId,
                    expectedAmountMinor = amount,
                    billingCycle = action.billingCycle,
                    paidVia = action.paidVia,
                    clientActionId = action.rootClientActionId,
                ),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipPaymentActionKind.BEGIN_CASH -> membershipsApi.beginCashCollection(
                id = requireNotNull(action.serverRequestId),
                body = MembershipPaymentCashCollectionRequest(shiftId, amount),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipPaymentActionKind.BEGIN_PROVIDER -> membershipsApi.beginProviderAction(
                id = requireNotNull(action.serverRequestId),
                body = MembershipPaymentProviderActionRequest(shiftId, amount),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipPaymentActionKind.COMPLETE -> membershipsApi.settlePayment(
                id = requireNotNull(action.serverRequestId),
                body = MembershipPaymentSettlementRequest(
                    shiftId = shiftId,
                    expectedAmountMinor = amount,
                    collectedAt = Instant.ofEpochMilli(occurredAt).toString(),
                    externalReference = action.externalReference,
                    actionTakeoverConfirmed = action.actionTakeoverConfirmed,
                    actionTakeoverReason = action.actionTakeoverReason,
                ),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipPaymentActionKind.FINALIZE -> membershipsApi.finalizePayment(
                id = requireNotNull(action.serverRequestId),
                body = MembershipPaymentFinalizationRequest(shiftId, amount),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipPaymentActionKind.WITHDRAW -> membershipsApi.withdrawPayment(
                id = requireNotNull(action.serverRequestId),
                body = MembershipPaymentWithdrawalRequest(
                    shiftId = shiftId,
                    expectedAmountMinor = amount,
                    resolution = requireNotNull(action.resolution),
                    reason = requireNotNull(action.reason),
                    externalReference = action.externalReference,
                    actionStateVerified = action.actionStateVerified,
                    providerVerificationStatus = action.providerVerificationStatus,
                    providerVerificationReference = action.providerVerificationReference,
                    providerEvidenceOccurredAt = action.providerEvidenceOccurredAtMillis?.let {
                        Instant.ofEpochMilli(it).toString()
                    },
                    cashReturnConfirmed = action.cashReturnConfirmed,
                    actionTakeoverConfirmed = action.actionTakeoverConfirmed,
                    actionTakeoverReason = action.actionTakeoverReason,
                ),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipPaymentActionKind.LEGACY_RESOLVE -> {
                membershipsApi.resolveLegacyPaymentAttempt(
                    MembershipPaymentAttemptResolutionRequest(
                        originalClientActionId = action.rootClientActionId,
                        customerId = action.customerId,
                        tierId = action.tierId,
                        shiftId = shiftId,
                        expectedAmountMinor = amount,
                        paidVia = action.paidVia,
                        resolution = requireNotNull(action.resolution),
                        reason = requireNotNull(action.reason),
                        externalReference = action.externalReference,
                        providerVerificationStatus = action.providerVerificationStatus,
                        providerEvidenceOccurredAt = action.providerEvidenceOccurredAtMillis?.let {
                            Instant.ofEpochMilli(it).toString()
                        },
                        cashReturnConfirmed = action.cashReturnConfirmed,
                    ),
                    key = action.actionId,
                    provenance = provenance,
                )
                if (!commitToCurrentScope(lease) {
                        db.membershipPaymentDao().markSynced(action.actionId, null)
                    }
                ) return
                return
            }
            else -> error("Unknown membership payment action ${action.kind}; update the app.")
        }
        requireNotNull(result)
        validateMembershipPaymentTask(action, result, shiftId, amount)
        val now = System.currentTimeMillis()
        val branchId = lease.scope.branchId ?: error("Membership payment branch scope is missing.")
        val terminalId = lease.scope.terminalId ?: error("Membership payment terminal scope is missing.")
        if (!commitToCurrentScope(lease) {
                db.withTransaction {
                    db.membershipPaymentDao().upsertTasks(
                        listOf(result.toCache(branchId, terminalId, now)),
                    )
                    db.membershipPaymentDao().markSynced(action.actionId, result.id)
                }
            }
        ) return
        if (result.status == MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING) {
            ensureMembershipPaymentFinalizations()
        }
        if (result.status == MembershipPaymentTaskStatus.SETTLED) {
            pullMembershipForBestEffortAlreadyLocked(action.customerId)
        }
    }

    private suspend fun resolveMembershipShift(capturedShiftId: String?): String? {
        val value = capturedShiftId
            ?: error("This membership payment has no verified source shift and is quarantined.")
        val local = db.shiftDao().byLocalId(value) ?: return value
        if (local.serverShiftId == null) return null
        return local.serverShiftId
    }

    private fun validateMembershipPaymentTask(
        action: LocalMembershipPaymentActionEntity,
        task: MembershipPaymentTask,
        shiftId: String,
        amount: Long,
    ) {
        require(task.status in MembershipPaymentTaskStatus.known) {
            "Server returned unknown membership payment status ${task.status}; update the app."
        }
        require(task.shiftId == shiftId && task.amountMinor == amount) {
            "Server membership payment did not match the captured shift or amount."
        }
        require(task.customerId == action.customerId && task.tierId == action.tierId) {
            "Server membership payment did not match the captured customer or tier."
        }
        require(task.paidVia == action.paidVia && task.clientActionId == action.rootClientActionId) {
            "Server membership payment did not match the captured rail or action identity."
        }
    }

    private fun paymentActionSatisfied(
        action: LocalMembershipPaymentActionEntity,
        task: MembershipPaymentTaskCacheEntity,
    ): Boolean = paymentStageSatisfied(action.kind, task.status)

    private suspend fun reconcileMembershipPaymentActionAfterFailure(
        action: LocalMembershipPaymentActionEntity,
    ): Boolean {
        if (action.kind in setOf(
                MembershipPaymentActionKind.LEGACY_PROBE,
                MembershipPaymentActionKind.LEGACY_RESOLVE,
            )
        ) return false
        return try {
            val rows = requireCompleteTaskList(
                membershipsApi.paymentRequests(
                    unresolved = false,
                    shiftId = resolveMembershipShift(action.shiftId),
                    clientActionId = action.rootClientActionId,
                    requestId = action.serverRequestId,
                    limit = 200,
                ),
                limit = 200,
                resource = "membership payment reconciliation",
            )
            val task = rows.firstOrNull {
                it.id == action.serverRequestId || it.clientActionId == action.rootClientActionId
            } ?: return false
            if (task.status !in MembershipPaymentTaskStatus.known) return false
            val lease = cacheIsolation.currentLease() ?: return false
            val branchId = lease.scope.branchId ?: return false
            val terminalId = lease.scope.terminalId ?: return false
            val cached = task.toCache(branchId, terminalId, System.currentTimeMillis())
            if (!commitToCurrentScope(lease) {
                    db.withTransaction {
                        db.membershipPaymentDao().upsertTasks(listOf(cached))
                        if (paymentActionSatisfied(action, cached)) {
                            db.membershipPaymentDao().markSynced(action.actionId, task.id)
                        }
                    }
                }
            ) return false
            paymentActionSatisfied(action, cached)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun pullMembershipRefundStateBestEffort(): Boolean {
        if (!canAccessMembershipMoney()) return false
        return try {
            val lease = cacheIsolation.currentLease() ?: return false
            val terminalId = lease.scope.terminalId ?: return false
            val branchId = lease.scope.branchId ?: return false
            val tasks = requireCompleteTaskList(
                membershipsApi.refundTasks(unresolved = true, limit = 200),
                limit = 200,
                resource = "membership refund tasks",
            )
            val attempts = requireCompleteTaskList(
                membershipsApi.refundAttempts(unresolved = true, limit = 200),
                limit = 200,
                resource = "legacy membership refund recovery tasks",
            )
            if (tasks.any { it.status !in MembershipRefundTaskStatus.known }) {
                error("Membership refund tasks contain a status this app does not understand. Update the app before moving money.")
            }
            if (attempts.any { it.status !in setOf("unresolved", "resolved") }) {
                error("Legacy refund recovery contains an unknown status. Update the app before resolving it.")
            }
            val fetchedAt = System.currentTimeMillis()
            commitToCurrentScope(lease) {
                db.withTransaction {
                    db.membershipRefundMoneyDao().replaceTasksForTerminal(
                        terminalId,
                        tasks.map { it.toCache(branchId, terminalId, fetchedAt) },
                    )
                    db.membershipRefundMoneyDao().replaceAttemptsForTerminal(
                        terminalId,
                        attempts.map { it.toCache(branchId, terminalId, fetchedAt) },
                    )
                    db.syncMetaDao().put(SyncMetaEntity("membership_refund_tasks", fetchedAt))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Could not reconcile membership refund tasks."
            passHadAmbiguousFailure = true
            false
        }
    }

    private suspend fun reconcileMembershipRefundActionsFromCache() {
        val dao = db.membershipRefundMoneyDao()
        dao.pushableActions().forEach { action ->
            when (action.kind) {
                MembershipRefundActionKind.LEGACY_REGISTER -> {
                    if (dao.attemptByOriginalAction(action.rootClientActionId) != null) {
                        dao.markSynced(action.actionId, action.serverRefundId)
                    }
                }
                MembershipRefundActionKind.LEGACY_RECONCILE_SERVER -> {
                    action.serverRefundId?.let { id -> dao.taskById(id) }?.let { task ->
                        adoptOrQuarantineLegacyServerRefund(action, task)
                    }
                }
                else -> action.serverRefundId?.let { dao.taskById(it) }?.let { task ->
                    if (refundActionSatisfied(action, task)) {
                        dao.markSynced(action.actionId, task.id)
                    }
                }
            }
        }
    }

    private suspend fun adoptOrQuarantineLegacyServerRefund(
        action: LocalMembershipRefundActionEntity,
        task: MembershipRefundTaskCacheEntity,
    ) {
        val dao = db.membershipRefundMoneyDao()
        val legacyValueMayHaveMoved = action.occurredAtMillis != null && (
            action.paidVia != "cash" || action.cashHandoverConfirmed
        )
        when {
            task.status in MembershipRefundTaskStatus.terminal ->
                dao.markSynced(action.actionId, task.id)
            legacyValueMayHaveMoved -> dao.requireLegacyRecovery(
                action.actionId,
                "The server refund exists but the older app may have moved value without the new begin/completion journal. " +
                    "Do not pay again or withdraw it as unpaid; verify drawer/provider evidence and use protected recovery.",
            )
            else -> dao.markSynced(action.actionId, task.id)
        }
    }

    private suspend fun ensureMembershipRefundFinalizations() {
        val dao = db.membershipRefundMoneyDao()
        val profile = DCompanyApp.instance.shiftCache.profile.value ?: return
        dao.unresolvedTasks()
            .filter { it.status == MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING }
            .forEach { task ->
                dao.insertAction(
                    LocalMembershipRefundActionEntity(
                        actionId = "membership-refund-finalize:${task.id}",
                        rootClientActionId = "membership-refund-task:${task.id}",
                        serverRefundId = task.id,
                        kind = MembershipRefundActionKind.FINALIZE,
                        customerId = task.customerId.orEmpty(),
                        membershipId = task.membershipId,
                        paymentId = task.paymentId,
                        shiftId = task.shiftId,
                        branchId = task.branchId,
                        terminalId = task.terminalId,
                        actorUserId = profile.userId,
                        paidVia = task.method,
                        expectedAmountMinor = task.amountMinor,
                        reason = task.reason,
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
    }

    private suspend fun pushMembershipRefundActions() {
        if (!canAccessMembershipMoney()) return
        val dao = db.membershipRefundMoneyDao()
        for (action in dao.pushableActions()) {
            val task = action.serverRefundId?.let { dao.taskById(it) }
            if (task != null && action.kind != MembershipRefundActionKind.LEGACY_RECONCILE_SERVER &&
                refundActionSatisfied(action, task)
            ) {
                dao.markSynced(action.actionId, task.id)
                continue
            }
            try {
                pushMembershipRefundActionOne(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (reconcileMembershipRefundActionAfterFailure(action)) continue
                val message = failure.message ?: "Membership refund state could not be verified."
                val valueEvidenceStage = action.kind in setOf(
                    MembershipRefundActionKind.COMPLETE_CASH,
                    MembershipRefundActionKind.COMPLETE_PROVIDER,
                    MembershipRefundActionKind.FINALIZE,
                    MembershipRefundActionKind.WITHDRAW,
                    MembershipRefundActionKind.LEGACY_RESOLVE,
                )
                if (valueEvidenceStage || failure !is ApiException || failure.mustPreserveOutbox) {
                    dao.markAmbiguous(action.actionId, action.serverRefundId, message)
                    passHadAmbiguousFailure = true
                    if (failure is ApiException && failure.status == 426) throw failure
                    return
                }
                if (action.kind == MembershipRefundActionKind.LEGACY_RECONCILE_SERVER) {
                    dao.requireLegacyRecovery(
                        action.actionId,
                        "The older server refund could not be adopted safely. Do not repeat a payout. $message",
                    )
                } else {
                    dao.markRejected(action.actionId, message)
                }
            }
        }
        ensureMembershipRefundFinalizations()
        for (action in dao.pushableActions().filter { it.kind == MembershipRefundActionKind.FINALIZE }) {
            try {
                pushMembershipRefundActionOne(action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!reconcileMembershipRefundActionAfterFailure(action)) {
                    dao.markAmbiguous(
                        action.actionId,
                        action.serverRefundId,
                        failure.message ?: "Membership refund accounting finalization could not be verified.",
                    )
                    passHadAmbiguousFailure = true
                    return
                }
            }
        }
    }

    private suspend fun pushMembershipRefundActionOne(action: LocalMembershipRefundActionEntity) {
        val lease = cacheIsolation.currentLease()
            ?: error("The active account scope changed before membership refund sync.")
        val shiftId = resolveMembershipShift(action.shiftId) ?: return
        val occurredAt = action.occurredAtMillis ?: action.createdAtMillis
        val provenance = outboxProvenanceHeaders(occurredAt, action.actionId)
        when (action.kind) {
            MembershipRefundActionKind.LEGACY_RECONCILE_SERVER -> {
                val serverId = requireNotNull(action.serverRefundId)
                val rows = requireCompleteTaskList(
                    membershipsApi.refundTasks(unresolved = false, refundId = serverId, limit = 200),
                    200,
                    "legacy membership refund reconciliation",
                )
                val task = rows.singleOrNull()
                    ?: error("The saved server refund could not be found. It remains quarantined.")
                validateMembershipRefundTask(action, task, shiftId)
                val branchId = lease.scope.branchId ?: error("Membership refund branch scope is missing.")
                val terminalId = lease.scope.terminalId ?: error("Membership refund terminal scope is missing.")
                val cached = task.toCache(branchId, terminalId, System.currentTimeMillis())
                if (!commitToCurrentScope(lease) {
                        db.membershipRefundMoneyDao().upsertTasks(listOf(cached))
                        adoptOrQuarantineLegacyServerRefund(action, cached)
                    }
                ) return
                return
            }
            MembershipRefundActionKind.LEGACY_REGISTER -> {
                val paymentId = requireNotNull(action.paymentId) {
                    "This legacy refund has no verified payment id and cannot be registered automatically."
                }
                val result = membershipsApi.registerRefundAttempt(
                    MembershipRefundAttemptRegistrationRequest(
                        originalClientActionId = action.rootClientActionId,
                        customerId = action.customerId,
                        membershipId = action.membershipId,
                        paymentId = paymentId,
                        sourceShiftId = shiftId,
                        expectedAmountMinor = action.expectedAmountMinor,
                        paidVia = action.paidVia,
                        capturedAt = Instant.ofEpochMilli(occurredAt).toString(),
                    ),
                    key = action.actionId,
                    provenance = provenance,
                )
                val branchId = lease.scope.branchId ?: error("Membership refund branch scope is missing.")
                val terminalId = lease.scope.terminalId ?: error("Membership refund terminal scope is missing.")
                if (!commitToCurrentScope(lease) {
                        db.withTransaction {
                            db.membershipRefundMoneyDao().upsertAttempts(
                                listOf(result.toCache(branchId, terminalId, System.currentTimeMillis())),
                            )
                            db.membershipRefundMoneyDao().markSynced(action.actionId, null)
                        }
                    }
                ) return
                return
            }
            MembershipRefundActionKind.LEGACY_RESOLVE -> {
                val paymentId = requireNotNull(action.paymentId)
                membershipsApi.resolveRefundAttempt(
                    MembershipRefundAttemptResolutionRequest(
                        originalClientActionId = action.rootClientActionId,
                        customerId = action.customerId,
                        membershipId = action.membershipId,
                        paymentId = paymentId,
                        sourceShiftId = shiftId,
                        reconciliationShiftId = action.reconciliationShiftId?.let {
                            resolveMembershipShift(it)
                        },
                        expectedAmountMinor = action.expectedAmountMinor,
                        paidVia = action.paidVia,
                        outcome = requireNotNull(action.resolution),
                        reason = action.reason,
                        providerStatus = action.providerVerificationStatus,
                        verificationReference = action.providerVerificationReference,
                        evidenceOccurredAt = action.providerEvidenceOccurredAtMillis?.let {
                            Instant.ofEpochMilli(it).toString()
                        },
                        cashHandoverConfirmed = action.cashHandoverConfirmed,
                    ),
                    key = action.actionId,
                    provenance = provenance,
                )
                if (!commitToCurrentScope(lease) {
                        db.membershipRefundMoneyDao().markSynced(action.actionId, null)
                    }
                ) return
                pullMembershipForBestEffortAlreadyLocked(action.customerId)
                return
            }
        }

        val result: MembershipRefundTask = when (action.kind) {
            MembershipRefundActionKind.ACCEPT -> membershipsApi.refund(
                subscriptionId = action.membershipId,
                body = MembershipRefundRequest(
                    shiftId = shiftId,
                    expectedAmountMinor = action.expectedAmountMinor,
                    method = action.paidVia,
                    reason = action.reason,
                    settledAt = null,
                    externalReference = null,
                ),
                key = action.actionId,
                provenance = provenance,
            )
            MembershipRefundActionKind.BEGIN_CASH -> membershipsApi.beginRefundCashHandoff(
                requireNotNull(action.serverRefundId),
                MembershipRefundCashHandoffRequest(shiftId, action.expectedAmountMinor),
                action.actionId,
                provenance,
            )
            MembershipRefundActionKind.BEGIN_PROVIDER -> membershipsApi.beginRefundProviderAction(
                requireNotNull(action.serverRefundId),
                MembershipRefundProviderActionRequest(shiftId, action.expectedAmountMinor),
                action.actionId,
                provenance,
            )
            MembershipRefundActionKind.COMPLETE_CASH -> membershipsApi.settleCashRefund(
                requireNotNull(action.serverRefundId),
                CashMembershipRefundSettlementRequest(
                    shiftId = shiftId,
                    expectedAmountMinor = action.expectedAmountMinor,
                    settledAt = Instant.ofEpochMilli(occurredAt).toString(),
                    cashHandedOver = true,
                    actionTakeoverConfirmed = action.actionTakeoverConfirmed,
                    actionTakeoverReason = action.actionTakeoverReason,
                ),
                action.actionId,
                provenance,
            )
            MembershipRefundActionKind.COMPLETE_PROVIDER -> membershipsApi.settleProviderRefund(
                requireNotNull(action.serverRefundId),
                ProviderMembershipRefundSettlementRequest(
                    shiftId = shiftId,
                    expectedAmountMinor = action.expectedAmountMinor,
                    settledAt = Instant.ofEpochMilli(occurredAt).toString(),
                    externalReference = requireNotNull(action.externalReference),
                    actionTakeoverConfirmed = action.actionTakeoverConfirmed,
                    actionTakeoverReason = action.actionTakeoverReason,
                ),
                action.actionId,
                provenance,
            )
            MembershipRefundActionKind.FINALIZE -> membershipsApi.finalizeRefund(
                requireNotNull(action.serverRefundId),
                MembershipRefundFinalizationRequest(shiftId, action.expectedAmountMinor),
                action.actionId,
                provenance,
            )
            MembershipRefundActionKind.WITHDRAW -> membershipsApi.resolveRefund(
                requireNotNull(action.serverRefundId),
                MembershipRefundResolutionRequest(
                    shiftId = shiftId,
                    expectedAmountMinor = action.expectedAmountMinor,
                    resolution = requireNotNull(action.resolution),
                    reason = action.reason,
                    externalReference = action.externalReference,
                    actionStateVerified = action.actionStateVerified,
                    providerVerificationStatus = action.providerVerificationStatus,
                    providerVerificationReference = action.providerVerificationReference,
                    providerEvidenceOccurredAt = action.providerEvidenceOccurredAtMillis?.let {
                        Instant.ofEpochMilli(it).toString()
                    },
                    cashReturnConfirmed = action.cashReturnConfirmed,
                    actionTakeoverConfirmed = action.actionTakeoverConfirmed,
                    actionTakeoverReason = action.actionTakeoverReason,
                ),
                action.actionId,
                provenance,
            )
            else -> error("Unknown membership refund action ${action.kind}; update the app.")
        }
        validateMembershipRefundTask(action, result, shiftId)
        val branchId = lease.scope.branchId ?: error("Membership refund branch scope is missing.")
        val terminalId = lease.scope.terminalId ?: error("Membership refund terminal scope is missing.")
        if (!commitToCurrentScope(lease) {
                db.withTransaction {
                    db.membershipRefundMoneyDao().upsertTasks(
                        listOf(result.toCache(branchId, terminalId, System.currentTimeMillis())),
                    )
                    db.membershipRefundMoneyDao().markSynced(action.actionId, result.id)
                }
            }
        ) return
        if (result.status == MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING) {
            ensureMembershipRefundFinalizations()
        }
        if (result.status in MembershipRefundTaskStatus.terminal) {
            pullMembershipForBestEffortAlreadyLocked(action.customerId)
        }
    }

    private fun validateMembershipRefundTask(
        action: LocalMembershipRefundActionEntity,
        task: MembershipRefundTask,
        shiftId: String,
    ) {
        require(task.status in MembershipRefundTaskStatus.known) {
            "Server returned unknown membership refund status ${task.status}; update the app."
        }
        require(task.shiftId == shiftId && task.amountMinor == action.expectedAmountMinor) {
            "Server membership refund did not match the captured shift or amount."
        }
        require(task.membershipId == action.membershipId && task.method == action.paidVia) {
            "Server membership refund did not match the captured membership or rail."
        }
        action.paymentId?.let { expected ->
            require(task.paymentId == expected) {
                "Server membership refund did not match the captured payment."
            }
        }
    }

    private fun refundActionSatisfied(
        action: LocalMembershipRefundActionEntity,
        task: MembershipRefundTaskCacheEntity,
    ): Boolean = refundStageSatisfied(action.kind, task.status)

    private suspend fun reconcileMembershipRefundActionAfterFailure(
        action: LocalMembershipRefundActionEntity,
    ): Boolean {
        return try {
            when (action.kind) {
                MembershipRefundActionKind.LEGACY_REGISTER -> {
                    val rows = requireCompleteTaskList(
                        membershipsApi.refundAttempts(
                            unresolved = false,
                            originalClientActionId = action.rootClientActionId,
                            limit = 200,
                        ),
                        200,
                        "legacy membership refund recovery reconciliation",
                    )
                    if (rows.isEmpty()) return false
                    val lease = cacheIsolation.currentLease() ?: return false
                    val branchId = lease.scope.branchId ?: return false
                    val terminalId = lease.scope.terminalId ?: return false
                    if (!commitToCurrentScope(lease) {
                            db.withTransaction {
                                db.membershipRefundMoneyDao().upsertAttempts(
                                    rows.map { it.toCache(branchId, terminalId, System.currentTimeMillis()) },
                                )
                                db.membershipRefundMoneyDao().markSynced(action.actionId, null)
                            }
                        }
                    ) return false
                    true
                }
                MembershipRefundActionKind.LEGACY_RESOLVE -> false
                else -> {
                    val rows = requireCompleteTaskList(
                        membershipsApi.refundTasks(
                            unresolved = false,
                            shiftId = resolveMembershipShift(action.shiftId),
                            refundId = action.serverRefundId,
                            clientActionId = action.rootClientActionId.takeIf {
                                action.kind == MembershipRefundActionKind.ACCEPT
                            },
                            limit = 200,
                        ),
                        200,
                        "membership refund reconciliation",
                    )
                    val task = rows.firstOrNull {
                        it.id == action.serverRefundId || action.kind == MembershipRefundActionKind.ACCEPT
                    } ?: return false
                    val lease = cacheIsolation.currentLease() ?: return false
                    val branchId = lease.scope.branchId ?: return false
                    val terminalId = lease.scope.terminalId ?: return false
                    val cached = task.toCache(branchId, terminalId, System.currentTimeMillis())
                    if (!commitToCurrentScope(lease) {
                            db.withTransaction {
                                db.membershipRefundMoneyDao().upsertTasks(listOf(cached))
                                if (action.kind == MembershipRefundActionKind.LEGACY_RECONCILE_SERVER) {
                                    adoptOrQuarantineLegacyServerRefund(action, cached)
                                } else if (refundActionSatisfied(action, cached)) {
                                    db.membershipRefundMoneyDao().markSynced(action.actionId, task.id)
                                }
                            }
                        }
                    ) return false
                    action.kind == MembershipRefundActionKind.LEGACY_RECONCILE_SERVER ||
                        refundActionSatisfied(action, cached)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun pushSubscriptions() {
        val dao = db.membershipDao()
        val shiftDao = db.shiftDao()
        val ready = dao.pushableSubscriptions().filter { row ->
            val capturedShiftId = row.shiftId ?: return@filter true
            val localShift = shiftDao.byLocalId(capturedShiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markSubscriptionRejected(row.localId, msg) },
            push = ::pushSubscriptionOne,
        )
    }

    /** Insert-only, same reasoning as pushTicketSaleOne — no version-CAS
     * needed: a subscribe is never edited, only ever queued once. */
    private suspend fun pushSubscriptionOne(row: LocalSubscriptionEntity) {
        val capturedShiftId = row.shiftId
            ?: error(
                "This saved subscription predates shift-bound membership accounting. " +
                    "It was not sent because its original drawer cannot be verified; " +
                    "ask an owner to reconcile it and create a new subscription."
            )
        val expectedAmount = row.expectedAmountMinor
            ?: error(
                "This saved subscription predates price-snapshot accounting. " +
                    "It was not sent because the amount collected cannot be verified; " +
                    "ask an owner to reconcile it and create a new subscription."
            )
        val resolvedShiftId =
            db.shiftDao().byLocalId(capturedShiftId)?.serverShiftId ?: capturedShiftId
        membershipsApi.subscribe(
            SubscribeRequest(
                customerId = row.customerId, tierId = row.tierId,
                shiftId = resolvedShiftId,
                expectedAmountMinor = expectedAmount,
                collectedAt = java.time.Instant.ofEpochMilli(row.createdAtMillis).toString(),
                billingCycle = row.billingCycle, paidVia = row.paidVia,
            ),
            key = "membership-subscribe:${row.localId}",
            provenance = outboxProvenanceHeaders(
                row.createdAtMillis,
                "membership-subscribe:${row.localId}",
            ),
        )
        pullMembershipForAlreadyLocked(row.customerId)
        db.membershipDao().markSubscriptionSynced(row.localId)
    }

    private suspend fun pushCancellations() {
        val dao = db.membershipDao()
        drainOutbox(
            rows = dao.pushableCancellations(),
            markRejected = { row, msg -> dao.markCancellationRejected(row.localId, msg) },
            push = ::pushCancellationOne,
        )
    }

    /** Shape C — targets an existing server subscription id. Cancellation is
     * a one-way desired state and the backend treats an already-cancelled or
     * already-nonrenewing term as convergent success. The stable key still
     * makes response-loss replay return the original result and audit identity. */
    private suspend fun pushCancellationOne(row: LocalMembershipCancellationEntity) {
        membershipsApi.cancel(
            row.subscriptionId,
            "membership-cancel:${row.localId}",
            outboxProvenanceHeaders(row.createdAtMillis, "membership-cancel:${row.localId}"),
        )
        pullMembershipForAlreadyLocked(row.customerId)
        db.membershipDao().markCancellationSynced(row.localId)
    }

    private suspend fun pushMembershipRefunds() {
        val dao = db.membershipDao()
        val shiftDao = db.shiftDao()
        val ready = dao.pushableRefundRequests().filter { row ->
            val localShift = shiftDao.byLocalId(row.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markRefundRequestRejected(row.localId, msg) },
            push = ::pushMembershipRefundOne,
        )
    }

    private suspend fun pushMembershipRefundOne(row: LocalMembershipRefundEntity) {
        val resolvedShiftId = db.shiftDao().byLocalId(row.shiftId)?.serverShiftId ?: row.shiftId
        val result = membershipsApi.refund(
            row.subscriptionId,
            MembershipRefundRequest(
                shiftId = resolvedShiftId,
                expectedAmountMinor = row.expectedAmountMinor,
                method = row.method,
                reason = row.reason,
                settledAt = row.settledAtMillis?.let {
                    java.time.Instant.ofEpochMilli(it).toString()
                },
                externalReference = row.externalReference,
            ),
            key = "membership-refund:${row.localId}",
            provenance = outboxProvenanceHeaders(
                row.settledAtMillis ?: row.createdAtMillis,
                "membership-refund:${row.localId}",
            ),
        )
        pullMembershipForAlreadyLocked(row.customerId)
        if (result.status == "accepted_cash_due") {
            db.membershipDao().markRefundAcceptedCashDue(row.localId, result.id)
        } else {
            db.membershipDao().markRefundSettled(row.localId, result.id, result.receiptNo)
        }
    }

    private suspend fun pushMembershipCashRefundSettlements() {
        val dao = db.membershipDao()
        val ready = dao.pushableCashRefundSettlements().filter { row ->
            val localShift = db.shiftDao().byLocalId(row.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg ->
                dao.markCashRefundSettlementRejected(row.localId, msg)
            },
            push = ::pushMembershipCashRefundSettlementOne,
        )
    }

    private suspend fun pushMembershipCashRefundSettlementOne(
        row: LocalMembershipRefundEntity,
    ) {
        val refundId = row.serverRefundId
            ?: error("Accepted cash refund is missing its server reference; refresh before retrying.")
        val settledAtMillis = row.settledAtMillis
            ?: error("Cash refund handover time is missing; confirm the handover again.")
        val resolvedShiftId = db.shiftDao().byLocalId(row.shiftId)?.serverShiftId ?: row.shiftId
        val actionId = "membership-refund-cash-settle:${row.localId}"
        val result = membershipsApi.settleCashRefund(
            refundId = refundId,
            body = CashMembershipRefundSettlementRequest(
                shiftId = resolvedShiftId,
                expectedAmountMinor = row.expectedAmountMinor,
                settledAt = java.time.Instant.ofEpochMilli(settledAtMillis).toString(),
                cashHandedOver = true,
            ),
            key = actionId,
            provenance = outboxProvenanceHeaders(settledAtMillis, actionId),
        )
        pullMembershipForAlreadyLocked(row.customerId)
        db.membershipDao().markRefundSettled(row.localId, result.id, result.receiptNo)
    }

    private suspend fun pushMembershipRefundWithdrawals() {
        val dao = db.membershipDao()
        val ready = dao.pushableRefundWithdrawals().filter { row ->
            val localShift = db.shiftDao().byLocalId(row.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markRefundWithdrawalRejected(row.localId, msg) },
            push = ::pushMembershipRefundWithdrawalOne,
        )
    }

    private suspend fun pushMembershipRefundWithdrawalOne(
        row: LocalMembershipRefundEntity,
    ) {
        val refundId = row.serverRefundId
            ?: error("Accepted cash refund is missing its server reference; refresh before retrying.")
        val reason = row.withdrawalReason?.trim().orEmpty()
        val withdrawalAtMillis = row.withdrawalAtMillis
            ?: error("Cash refund withdrawal is missing its captured audit time.")
        require(reason.length >= 3) {
            "Enter why no cash was handed over before withdrawing this refund."
        }
        val resolvedShiftId = db.shiftDao().byLocalId(row.shiftId)?.serverShiftId ?: row.shiftId
        val actionId = "membership-refund-withdraw:${row.localId}"
        membershipsApi.withdrawCashRefund(
            refundId = refundId,
            body = CashMembershipRefundWithdrawalRequest(
                shiftId = resolvedShiftId,
                cashNotHandedOver = true,
                reason = reason,
            ),
            key = actionId,
            provenance = outboxProvenanceHeaders(withdrawalAtMillis, actionId),
        )
        pullMembershipForAlreadyLocked(row.customerId)
        db.membershipDao().markRefundWithdrawn(row.localId)
    }

    // ------------------------------------------------------------ settings

    /** One combined pull for company/branches/terminals — mirrors the
     * backend's own settings routes all broadcasting as a single realtime
     * resource (see onDemandPulls). */
    private suspend fun pullSettingsData() {
        pullCompany()
        pullBranches()
        pullTerminals()
    }

    private suspend fun pullCompany() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { settingsApi.company() },
            store = { c ->
                db.settingsDao().upsertCompany(
                    CompanyCacheEntity(
                        id = c.id, name = c.name, legalName = c.legalName, currency = c.currency,
                        timezone = c.timezone, country = c.country, gstin = c.gstin, pan = c.pan,
                        gstRegistrationType = c.gstRegistrationType, isComposition = c.isComposition,
                        eInvoicingEnabled = c.eInvoicingEnabled,
                        fiscalYearStartMonth = c.fiscalYearStartMonth, upiVpa = c.upiVpa,
                    ),
                )
            },
        )
    }

    private suspend fun pullBranches() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { settingsApi.branches() },
            store = { rows ->
                db.settingsDao().replaceBranchCache(
                    rows.map {
                        BranchCacheEntity(
                            id = it.id, name = it.name, code = it.code, address = it.address,
                            timezone = it.timezone, opensAt = it.opensAt, closesAt = it.closesAt,
                            stateCode = it.stateCode, fssaiLicenseNo = it.fssaiLicenseNo,
                            tradeLicenseNo = it.tradeLicenseNo, branchGstin = it.branchGstin,
                        )
                    },
                )
            },
        )
    }

    private suspend fun pullTerminals() {
        cacheIsolation.fetchAndCommitScoped(
            fetch = { settingsApi.terminals() },
            store = { rows ->
                db.settingsDao().replaceTerminalCache(
                    rows.map {
                        TerminalCacheEntity(
                            id = it.id, branchId = it.branchId, name = it.name,
                            deviceId = it.deviceId, lastSeenAt = it.lastSeenAt,
                        )
                    },
                )
            },
        )
    }

    /** Shape C — no Idempotency-Key needed, "set fields to X" is naturally
     * safe to retry (see SettingsApi's class doc). */
    private suspend fun pushCompanyEdit() {
        val dao = db.settingsDao()
        drainOutbox(
            rows = listOfNotNull(dao.pushableCompanyEdit()),
            markRejected = { row, msg -> dao.markCompanyEditRejected(row.localId, msg) },
            push = ::pushCompanyEditOne,
        )
    }

    private suspend fun pushCompanyEditOne(row: LocalCompanyEditEntity) {
        settingsApi.updateCompany(
            CompanyUpdateBody(
                name = row.name, legalName = row.legalName, timezone = row.timezone,
                gstin = row.gstin, pan = row.pan, gstRegistrationType = row.gstRegistrationType,
                isComposition = row.isComposition, eInvoicingEnabled = row.eInvoicingEnabled,
                upiVpa = row.upiVpa,
            ),
            "settings-company:${row.localId}",
            outboxProvenanceHeaders(row.createdAtMillis, "settings-company:${row.localId}"),
        )
        pullCompany()
        db.settingsDao().markCompanyEditSynced(row.localId)
    }

    /** Shape D — mandatory Idempotency-Key, no natural key a duplicate
     * retry could safely collide against (see SettingsApi's class doc). */
    private suspend fun pushBranches() {
        val dao = db.settingsDao()
        drainOutbox(
            rows = dao.pushableBranches(),
            markRejected = { row, msg -> dao.markBranchRejected(row.localId, msg) },
            push = ::pushBranchOne,
        )
    }

    private suspend fun pushBranchOne(row: LocalBranchEntity) {
        settingsApi.createBranch(
            BranchWriteBody(
                name = row.name, code = row.code, address = row.address, timezone = row.timezone,
                opensAt = row.opensAt, closesAt = row.closesAt, stateCode = row.stateCode,
                fssaiLicenseNo = row.fssaiLicenseNo, tradeLicenseNo = row.tradeLicenseNo,
                branchGstin = row.branchGstin,
            ),
            "settings-branch:${row.localId}",
            outboxProvenanceHeaders(row.createdAtMillis, "settings-branch:${row.localId}"),
        )
        pullBranches()
        db.settingsDao().markBranchSynced(row.localId)
    }

    private suspend fun pushTerminals() {
        val dao = db.settingsDao()
        drainOutbox(
            rows = dao.pushableTerminals(),
            markRejected = { row, msg -> dao.markTerminalRejected(row.localId, msg) },
            push = ::pushTerminalOne,
        )
    }

    private suspend fun pushTerminalOne(row: LocalTerminalEntity) {
        settingsApi.createTerminal(
            TerminalCreateBody(branchId = row.branchId, name = row.name, deviceId = row.deviceId),
            "settings-terminal:${row.localId}",
            outboxProvenanceHeaders(row.createdAtMillis, "settings-terminal:${row.localId}"),
        )
        pullTerminals()
        db.settingsDao().markTerminalSynced(row.localId)
    }

    private suspend fun pullMenu() {
        val lease = cacheIsolation.currentLease() ?: return
        val categories = ApiClient.api.menuCategories()
        val items = ApiClient.api.menuItems()
        commitToCurrentScope(lease) {
            db.withTransaction {
                db.menuDao().replaceMenu(
                    items = items.map {
                        MenuItemEntity(
                            id = it.id,
                            categoryId = it.categoryId,
                            sku = it.sku,
                            name = it.name,
                            type = it.type,
                            basePriceMinor = it.basePriceMinor,
                            taxRate = it.taxRate,
                            hsnCode = it.hsnCode,
                            priceIncludesTax = it.priceIncludesTax,
                            isAvailable = it.isAvailable,
                            description = it.description,
                        )
                    },
                    categories = categories.map {
                        MenuCategoryEntity(id = it.id, name = it.name, sortOrder = it.sortOrder)
                    },
                )
                db.syncMetaDao().put(SyncMetaEntity("menu", System.currentTimeMillis()))
            }
        }
    }

    /**
     * Categories have no price fields, so unlike items they're fully
     * offline-capable — same shape and same ordering rationale as
     * pushCustomerOne (see its doc comment): setServerId lands independent
     * of state so a just-created category is never briefly absent from both
     * the local override and the cache; the cache pull runs before
     * markSynced so a dropped connection right after a successful write
     * leaves the row pending-with-known-serverId for a clean retry instead
     * of an invisible orphan; markSynced only applies if version still
     * matches what was actually pushed, so a re-edit landing while this
     * exact push was in flight isn't silently discarded.
     */
    private suspend fun pushMenuCategories() {
        val dao = db.menuWriteDao()
        drainOutbox(
            rows = dao.pushableCategories(),
            markRejected = { row, msg -> dao.markCategoryRejected(row.localId, msg) },
            push = ::pushMenuCategoryOne,
        )
    }

    private suspend fun pushMenuCategoryOne(row: LocalMenuCategoryEntity) {
        val dao = db.menuWriteDao()
        val server = if (row.serverId == null) {
            menuApi.createCategory(
                CategoryCreateBody(name = row.name!!, sortOrder = row.sortOrder ?: 0),
                "menu-category:${row.localId}",
                outboxProvenanceHeaders(row.createdAtMillis, "menu-category-create:${row.localId}"),
            )
        } else {
            menuApi.updateCategory(
                row.serverId,
                CategoryUpdateBody(name = row.name, sortOrder = row.sortOrder),
                outboxProvenanceHeaders(row.createdAtMillis, "menu-category-update:${row.localId}"),
            )
        }
        dao.setCategoryServerId(row.localId, server.id)
        pullMenu()
        val applied = dao.markCategorySynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedCategories()
    }

    /**
     * No id-resolution, no create leg — an item's `serverId` is always
     * already known (see [LocalMenuItemEntity]'s doc comment), so this is
     * the simplest push in the whole engine: one PATCH, then refresh.
     */
    private suspend fun pushMenuItems() {
        val dao = db.menuWriteDao()
        drainOutbox(
            rows = dao.pushableItems(),
            markRejected = { row, msg -> dao.markItemRejected(row.localId, msg) },
            push = ::pushMenuItemOne,
        )
    }

    private suspend fun pushMenuItemOne(row: LocalMenuItemEntity) {
        val dao = db.menuWriteDao()
        menuApi.updateItemDetails(
            row.serverId,
            ItemDetailsUpdateBody(
                categoryId = row.categoryId,
                name = row.name,
                description = row.description,
                isAvailable = row.isAvailable,
            ),
            outboxProvenanceHeaders(row.createdAtMillis, "menu-item-update:${row.localId}"),
        )
        pullMenu()
        val applied = dao.markItemSynced(row.localId, expectedVersion = row.version)
        if (applied > 0) dao.deleteSyncedItems()
    }

    /**
     * An order captured while its shift was still `open_pending` carries that
     * shift's `localId`, which the server has never heard of — pushShiftOpens()
     * runs first, but a shift can itself be waiting on an ambiguous retry, so
     * this still has to check rather than assume the shift resolved. Orders
     * held back this way are simply left untouched (not rejected, not an
     * error): the next sync() call retries them once their shift catches up.
     */
    private suspend fun pushPendingOrders() {
        val dao = db.orderDao()
        val shiftDao = db.shiftDao()
        val ready = dao.byState(SyncState.PENDING).filter { order ->
            val localShift = shiftDao.byLocalId(order.shiftId)
            localShift == null || localShift.serverShiftId != null
        }
        drainOutbox(
            rows = ready,
            markRejected = { row, msg -> dao.markRejected(row.localId, msg) },
            push = ::pushOne,
        )
    }

    private suspend fun pushOne(order: LocalOrderEntity) {
        val dao = db.orderDao()
        val lines = dao.linesFor(order.localId)
        // If order.shiftId is a local shift's id, resolve it to the real
        // server id (guaranteed non-null here — pushPendingOrders only lets
        // resolved orders through). Otherwise it was already a real server
        // shift id (the common case: shift opened while online).
        val resolvedShiftId = db.shiftDao().byLocalId(order.shiftId)?.serverShiftId ?: order.shiftId

        // Deterministic keys derived from the local id: replaying this whole
        // function after a crash reuses them and the server returns the stored
        // response rather than creating a second order or a second payment.
        val created = ApiClient.api.createOrder(
            CreateOrderRequest(
                type = order.type,
                shiftId = resolvedShiftId,
                lines = lines.map { OrderLineRequest(it.menuItemId, it.qty) },
                customerName = order.customerName,
                customerPhone = order.customerPhone,
            ),
            idempotencyKey = "order:${order.localId}",
            provenance = outboxProvenanceHeaders(order.createdAtMillis, "order:${order.localId}"),
        )

        // The server has now priced it, and that price may differ from the
        // offline estimate the customer actually paid against — a membership
        // discount, or a price edited while this tablet was offline.
        //
        // Auto-paying the server's figure would silently record money that was
        // never collected (or drop money that was), and the shift's cash count
        // would be wrong with nothing to show why. A partial payment is not an
        // escape either: the backend refuses one unless split payments are
        // enabled. So a mismatch is parked for a human, with both numbers in
        // the message. The order stays open and unpaid on the server, which is
        // the honest state.
        if (created.dueMinor != order.estimateMinor) {
            dao.markRejected(
                order.localId,
                "Price changed while offline: collected ${order.estimateMinor.asRupees()}, " +
                    "server bill is ${created.dueMinor.asRupees()}. " +
                    "Order ${created.id.take(8)} is open and unpaid — settle the difference.",
            )
            return
        }

        val paid = ApiClient.api.recordPayment(
            created.id,
            PaymentRequest(
                method = order.paymentMethod,
                amountMinor = created.dueMinor,
                tenderedMinor = order.tenderedMinor.takeIf { order.paymentMethod == "cash" },
                expectedTotalMinor = created.totalMinor,
                expectedDueMinor = created.dueMinor,
                tipMinor = order.tipMinor,
            ),
            idempotencyKey = "payment:${order.localId}",
            provenance = outboxProvenanceHeaders(order.createdAtMillis, "payment:${order.localId}"),
        )

        dao.markSynced(
            localId = order.localId,
            // The order id, not the payment id — paid.id identifies the Payment.
            serverId = created.id,
            invoiceNo = paid.invoiceNo,
            totalMinor = created.totalMinor,
        )
    }
}
