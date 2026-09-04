package cloud.dcompany.erp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.alarm.overdueHeldOrderIds
import cloud.dcompany.erp.core.alarm.overdueHeldOrderFingerprint
import cloud.dcompany.erp.core.alarm.shouldShowOverdueHeldOrderBanner
import cloud.dcompany.erp.core.checkout.CheckoutClientInstancePolicy
import cloud.dcompany.erp.core.checkout.CheckoutClientInstanceUnavailableException
import cloud.dcompany.erp.core.checkout.DirectOrderPublishPolicy
import cloud.dcompany.erp.core.checkout.HeldCheckoutInteractionPolicy
import cloud.dcompany.erp.core.checkout.HeldOrderClaimPolicy
import cloud.dcompany.erp.core.checkout.PreparedHeldCheckoutAction
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.CanonicalReceiptSyncStateEntity
import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.db.LocalOrderEntity
import cloud.dcompany.erp.core.db.LocalOrderWithLines
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuModifierGroupEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.db.PosReceiptSource
import cloud.dcompany.erp.core.db.PosReceiptEntity
import cloud.dcompany.erp.core.db.RECEIPT_HISTORY_PAGE_SIZE
import cloud.dcompany.erp.core.db.ShiftActor
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.zeroTotalReceipt
import cloud.dcompany.erp.core.db.decodedReceipt
import cloud.dcompany.erp.core.db.observeResolvedOpenShift
import cloud.dcompany.erp.core.db.shiftClosingMessageOr
import cloud.dcompany.erp.core.errors.RecoveryRisk
import cloud.dcompany.erp.core.errors.recoveryGuidance
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CheckoutClaimResult
import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.ModifierSelectionRequest
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.OrderDiscountUpdateRequest
import cloud.dcompany.erp.core.net.OrderLineRequest
import cloud.dcompany.erp.core.net.OrderPointsRedemptionUpdateRequest
import cloud.dcompany.erp.core.net.PublishDirectCheckoutClaimRequest
import cloud.dcompany.erp.core.net.CanonicalReceipt
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Direct tablet carts are counter sales; table and gaming workflows retain their own server types. */
internal const val DIRECT_COUNTER_SALE_ORDER_TYPE = "takeaway"

data class PreparedHeldCheckout(
    val orderId: String,
    /** Prevents a claim prepared under one drawer shift being paid after a shift rollover. */
    val shiftIdAtClaim: String,
    val sourceLabel: String?,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val manualDiscountMinor: Long,
    val pointsRedeemedMinor: Long,
    val pointsRedeemed: Int,
    val taxMinor: Long,
    val roundOffMinor: Long,
    val tipMinor: Long,
    val totalMinor: Long,
    val paidMinor: Long,
    val dueMinor: Long,
    val claimToken: String,
    val claimExpiresAtMillis: Long,
    val claimOrderVersion: Long,
)

/** Full server bill shown before a short-lived checkout claim is acquired. */
data class HeldOrderReview(
    val orderId: String,
    val shiftIdAtReview: String,
    val sourceLabel: String?,
    val invoiceNo: String?,
    val type: String,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val manualDiscountMinor: Long,
    val pointsRedeemedMinor: Long,
    val pointsRedeemed: Int,
    val taxMinor: Long,
    val roundOffMinor: Long,
    val tipMinor: Long,
    val totalMinor: Long,
    val paidMinor: Long,
    val dueMinor: Long,
    val checkoutVersion: Long,
    val maxManualDiscountMinor: Long,
    val lines: List<HeldOrderReviewLine>,
) {
    val lineOrMembershipDiscountMinor: Long
        get() = (discountMinor - manualDiscountMinor - pointsRedeemedMinor).coerceAtLeast(0L)
}

data class HeldOrderReviewLine(
    val identity: String,
    val name: String,
    val quantity: Double,
    val unitPriceMinor: Long,
    val discountMinor: Long,
    val lineTotalMinor: Long,
    val variantName: String?,
    val modifiers: List<String>,
    val note: String?,
)

internal data class HeldOrderDiscountMutation(
    val body: OrderDiscountUpdateRequest,
    val idempotencyKey: String,
)

internal fun authoritativeHeldOrderReview(
    order: Order,
    shiftIdAtReview: String,
    fallbackSourceLabel: String? = null,
): HeldOrderReview {
    require(order.id.isNotBlank()) { "The server returned a bill without an identity." }
    require(shiftIdAtReview.isNotBlank()) { "The open shift could not be identified." }
    require(order.status == "held") { "This order is no longer held for POS payment." }
    require(order.checkoutVersion >= 1L) { "The server returned an invalid bill version." }
    require(
        listOf(
            order.subtotalMinor,
            order.discountMinor,
            order.manualDiscountMinor,
            order.pointsRedeemedMinor,
            order.taxMinor,
            order.tipMinor,
            order.totalMinor,
            order.paidMinor,
            order.dueMinor,
        ).all { it >= 0L },
    ) { "The server returned an invalid negative bill amount." }
    require(order.pointsRedeemed >= 0) { "The server returned invalid redeemed points." }
    val manualAndPoints = Math.addExact(order.manualDiscountMinor, order.pointsRedeemedMinor)
    require(manualAndPoints <= order.discountMinor) {
        "The server returned an inconsistent discount breakdown."
    }
    require(order.dueMinor == (order.totalMinor - order.paidMinor).coerceAtLeast(0L)) {
        "The server returned an inconsistent payable balance."
    }
    val maxManualDiscountMinor = Math.addExact(order.totalMinor, manualAndPoints)
    val lines = order.lines.mapIndexed { index, line ->
        require(line.quantityIsValid()) { "The server returned an invalid item quantity." }
        require(
            line.unitPriceMinor >= 0L && line.discountMinor >= 0L && line.lineTotalMinor >= 0L,
        ) { "The server returned an invalid item amount." }
        HeldOrderReviewLine(
            identity = line.id ?: line.clientLineId ?: "${order.id}:$index",
            name = line.name.trim().ifEmpty { "Item ${index + 1}" },
            quantity = line.qty,
            unitPriceMinor = line.unitPriceMinor,
            discountMinor = line.discountMinor,
            lineTotalMinor = line.lineTotalMinor,
            variantName = line.variantSnapshot?.name?.trim()?.takeIf(String::isNotEmpty),
            modifiers = line.modifiers.orEmpty().mapNotNull { modifier ->
                modifier.name.trim().takeIf(String::isNotEmpty)?.let { name ->
                    if (modifier.qty > 1) "$name ×${modifier.qty}" else name
                }
            },
            note = line.note?.trim()?.takeIf(String::isNotEmpty),
        )
    }
    return HeldOrderReview(
        orderId = order.id,
        shiftIdAtReview = shiftIdAtReview,
        sourceLabel = order.sourceLabel?.trim()?.takeIf(String::isNotEmpty)
            ?: fallbackSourceLabel?.trim()?.takeIf(String::isNotEmpty),
        invoiceNo = order.invoiceNo?.trim()?.takeIf(String::isNotEmpty),
        type = order.type,
        subtotalMinor = order.subtotalMinor,
        discountMinor = order.discountMinor,
        manualDiscountMinor = order.manualDiscountMinor,
        pointsRedeemedMinor = order.pointsRedeemedMinor,
        pointsRedeemed = order.pointsRedeemed,
        taxMinor = order.taxMinor,
        roundOffMinor = order.roundOffMinor,
        tipMinor = order.tipMinor,
        totalMinor = order.totalMinor,
        paidMinor = order.paidMinor,
        dueMinor = order.dueMinor,
        checkoutVersion = order.checkoutVersion,
        maxManualDiscountMinor = maxManualDiscountMinor,
        lines = lines,
    )
}

private fun cloud.dcompany.erp.core.net.OrderLine.quantityIsValid(): Boolean =
    qty.isFinite() && qty > 0.0

internal fun heldOrderDiscountMutation(
    review: HeldOrderReview,
    manualDiscountMinor: Long,
): HeldOrderDiscountMutation {
    require(manualDiscountMinor in 0L..review.maxManualDiscountMinor) {
        "Discount must be within the reviewed bill total."
    }
    return HeldOrderDiscountMutation(
        body = OrderDiscountUpdateRequest(
            manualDiscountMinor = manualDiscountMinor,
            expectedCheckoutVersion = review.checkoutVersion,
        ),
        idempotencyKey = "held-order-discount:${review.orderId}:${review.checkoutVersion}:$manualDiscountMinor",
    )
}

internal fun heldClaimMatchesReview(
    review: HeldOrderReview,
    claim: CheckoutClaimResult,
): Boolean = claim.orderId == review.orderId &&
    claim.orderVersion == review.checkoutVersion &&
    claim.orderTotalMinor == review.totalMinor &&
    claim.paidMinor == review.paidMinor &&
    claim.dueMinor == review.dueMinor

internal fun subscriptionClock(
    periodMillis: Long,
    nowMillis: () -> Long = System::currentTimeMillis,
): Flow<Long> {
    require(periodMillis > 0L) { "Clock period must be positive" }
    return flow {
        emit(nowMillis())
        while (currentCoroutineContext().isActive) {
            delay(periodMillis)
            emit(nowMillis())
        }
    }
}

data class PreparedDirectCheckout(
    val localId: String,
    /** Exact editable-cart revision that produced this authoritative bill. */
    val revision: Long,
    val orderId: String,
    val shiftIdAtClaim: String,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val pointsRedeemedMinor: Long,
    val pointsRedeemed: Int,
    val taxMinor: Long,
    val roundOffMinor: Long,
    val totalMinor: Long,
    val dueMinor: Long,
    val claimToken: String,
    val claimExpiresAtMillis: Long,
    val claimOrderVersion: Long,
)

/**
 * Canonical direct bill that remains editable on the server. Customer,
 * discount and points decisions happen here; payment controls are withheld
 * until atomic publication returns a claim and that claim is durable in Room.
 */
data class DirectCheckoutReview(
    val localId: String,
    val revision: Long,
    val orderId: String,
    val shiftId: String,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val pointsRedeemedMinor: Long,
    val pointsRedeemed: Int,
    val taxMinor: Long,
    val roundOffMinor: Long,
    val totalMinor: Long,
    val dueMinor: Long,
    val checkoutVersion: Long,
)

/**
 * Immutable payment confirmation rendered to the cashier. Passing this back
 * to the ViewModel prevents a late cart edit or recomposition from changing
 * the amount between confirmation and durable capture.
 */
data class DirectPaymentConfirmation(
    val localId: String,
    val revision: Long,
    val dueMinor: Long,
)

data class RejectedDirectSale(
    /** The original idempotency identity; retry must never mint a replacement. */
    val localId: String,
    /** What the cashier actually collected against the tablet estimate. */
    val amountMinor: Long,
    val paymentMethod: String,
    val createdAtMillis: Long,
    val error: String,
)

data class HeldPaymentStatus(
    val localId: String,
    val targetOrderId: String,
    val sourceLabel: String?,
    val amountMinor: Long,
    val paymentMethod: String,
    val createdAtMillis: Long,
    val state: String,
    val error: String?,
)

private data class DirectQueueSnapshot(
    val pendingCount: Int,
    val rejected: List<LocalOrderEntity>,
)

private data class HeldQueueSnapshot(
    val orders: List<HeldOrderCacheEntity>,
    val confirmedTargetIds: List<String>,
    val unresolvedPayments: List<LocalHeldOrderPaymentEntity>,
    val nowMillis: Long,
    val focusedOrderId: String?,
    val bannerMute: HeldBannerMute,
)

private data class HeldBannerMute(
    val overdueFingerprint: String? = null,
    val untilMillis: Long = 0L,
)

private data class RetrySnapshot(
    val directSaleIds: Set<String>,
    val heldPaymentIds: Set<String>,
)

private data class CheckoutSnapshot(
    val busy: Boolean,
    val preparingOrderId: String?,
    val review: HeldOrderReview?,
    val prepared: PreparedHeldCheckout?,
    val heldSelectionBlocked: Boolean,
)

private data class PosMenuSnapshot(
    val items: List<MenuItemEntity>,
    val categories: List<MenuCategoryEntity>,
    val variants: List<MenuVariantEntity>,
    val modifierGroups: List<MenuModifierGroupEntity>,
    val modifiers: List<MenuModifierEntity>,
    val draft: LocalOrderWithLines?,
    val customers: List<CustomerCacheEntity>,
)

/**
 * Receipt lists are replaceable UI projections, not payment/outbox workers.
 * The session ViewModel survives route changes, so eager sharing would keep
 * Room queries, sync-error combination and canonical JSON decoding alive for
 * the rest of the login after the cashier leaves POS. Keep a short grace
 * period for transient recomposition, then stop until POS is visible again.
 * Durable receipt rows remain in Room and are replayed on the next collection.
 */
private val receiptUiSharing = SharingStarted.WhileSubscribed(5_000)

data class PosUiState(
    /** Full available catalogue retained for durable draft/retry reconstruction. */
    val categories: List<MenuCategoryEntity> = emptyList(),
    val items: List<MenuItemEntity> = emptyList(),
    /** Release-profile subset offered for a brand-new counter sale. */
    val operationalCategories: List<MenuCategoryEntity> = emptyList(),
    val operationalItems: List<MenuItemEntity> = emptyList(),
    val variants: List<MenuVariantEntity> = emptyList(),
    val modifierGroups: List<MenuModifierGroupEntity> = emptyList(),
    val modifiers: List<MenuModifierEntity> = emptyList(),
    val selectedCategoryId: String? = null,
    val cart: List<CartLine> = emptyList(),
    val online: Boolean = false,
    val pendingCount: Int = 0,
    val rejectedCount: Int = 0,
    /** Direct POS sales have an explicit, idempotent human-retry path below. */
    val rejectedDirectSales: List<RejectedDirectSale> = emptyList(),
    /** Held-order settlement failures are separate and must not use direct-sale replay. */
    val heldRejectedCount: Int = 0,
    val retryingRejectedSaleIds: Set<String> = emptySet(),
    /** Money already taken for Tables/Gaming orders, awaiting or needing reconciliation. */
    val heldPaymentStatuses: List<HeldPaymentStatus> = emptyList(),
    val retryingHeldPaymentIds: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val checkoutBusy: Boolean = false,
    val preparingHeldOrderId: String? = null,
    /** Authoritative server bill visible before acquiring a checkout claim. */
    val heldOrderReview: HeldOrderReview? = null,
    val preparedHeldCheckout: PreparedHeldCheckout? = null,
    /** Exact server-priced direct sale; this—not the cached estimate—may be collected online. */
    val preparedDirectCheckout: PreparedDirectCheckout? = null,
    /** Editable canonical review shown before the direct bill is published. */
    val directCheckoutReview: DirectCheckoutReview? = null,
    /** Absorbs the second pointer-up after a held-order settlement confirmation. */
    val heldSelectionBlocked: Boolean = false,
    val notice: String? = null,
    val menuEmpty: Boolean = false,
    /** A sync has completed at least once on this device. */
    val everSynced: Boolean = false,
    /**
     * `serverShiftId` once the shift's open leg has synced, its `localId`
     * while still offline-pending — either way, what an order should be
     * captured against. Null once a close has been requested, even before
     * that close reaches the server: billing must stop the instant staff
     * say "close", not whenever the network happens to confirm it.
     */
    val activeShiftId: String? = null,
    /** Direct collection is opener-owned; protected owners may override. */
    val canCollectPayment: Boolean = false,
    /** Non-null when a server/local shift exists but this profile cannot collect it. */
    val shiftAccessMessage: String? = null,
    /**
     * Orders waiting to be paid at this till — a table's "Send to POS", or
     * anything else held instead of paid immediately. Filters out every row
     * this device has already confirmed payment for, whether pending, synced,
     * or rejected. A failed reconciliation is an owner task; it must never
     * make staff ask the customer to pay again.
     */
    val heldOrders: List<HeldOrderCacheEntity> = emptyList(),
    /** Exact authoritative heldAt+15m queue; never derived from cache arrival time. */
    val overdueHeldOrderIds: List<String> = emptyList(),
    val showOverdueBanner: Boolean = false,
    val overdueBannerMutedUntilMillis: Long = 0L,
    /** Notification/View focus is visual only and never starts a checkout claim. */
    val focusedHeldOrderId: String? = null,
    /** The draft is stored in Room, so process death and navigation cannot erase an order. */
    val draftState: String? = null,
    val draftLocalId: String? = null,
    val draftRevision: Long? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    /** Last synced display balance only; the backend authorises every redemption. */
    val customerLoyaltyPoints: Int? = null,
    val orderNote: String? = null,
    val manualDiscountMinor: Long = 0,
) {
    val visibleItems: List<MenuItemEntity>
        get() = if (selectedCategoryId == null) operationalItems
            else operationalItems.filter { it.categoryId == selectedCategoryId }

    /**
     * Cart-side estimate only. The server does the canonical pricing — tax,
     * membership discounts, rounding — and that figure is what actually gets
     * charged once the sale syncs.
     */
    val estimateMinor: Long get() = cart.sumOf { it.lineTotalMinor }
    val estimatedDueMinor: Long get() = (estimateMinor - manualDiscountMinor).coerceAtLeast(0L)
    val cartCount: Int get() = cart.sumOf { it.qty }
    val draftEditable: Boolean get() = draftState == null || draftState == SyncState.DRAFT
}

class PosViewModel : ViewModel() {

    private val app = DCompanyApp.instance
    private val db = app.db

    private val selectedCategory = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val directReviewOrderId = MutableStateFlow<String?>(null)
    private val checkoutBusy = MutableStateFlow(false)
    private val preparingHeldOrderId = MutableStateFlow<String?>(null)
    private val heldOrderReview = MutableStateFlow<HeldOrderReview?>(null)
    private val preparedHeldCheckout = MutableStateFlow<PreparedHeldCheckout?>(null)
    private val heldSelectionBlocked = MutableStateFlow(false)
    private val confirmingHeldOrderId = MutableStateFlow<String?>(null)
    private val retryingRejectedSaleIds = MutableStateFlow<Set<String>>(emptySet())
    private val retryingHeldPaymentIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * The overdue clock only exists while POS state has a UI subscriber. The
     * session ViewModelStore deliberately survives route changes, so an init
     * loop here otherwise woke every 30 seconds for the rest of the login even
     * after the cashier left POS.
     */
    private val heldAlarmClock = subscriptionClock(periodMillis = 30_000L)
    private val focusedHeldOrderId = MutableStateFlow<String?>(null)
    private val heldBannerMute = MutableStateFlow(HeldBannerMute())
    private var capturedSaleOutcomeJob: Job? = null
    private var retriedSaleOutcomeJob: Job? = null
    private var capturedHeldPaymentOutcomeJob: Job? = null
    private var retriedHeldPaymentOutcomeJob: Job? = null
    private var heldSelectionGuardJob: Job? = null
    private var abandonHeldPreparation = false
    private var heldClaimRequestInFlight = false
    private var heldReviewGeneration = 0L
    private val cartMutationMutex = Mutex()
    @Volatile private var access = PosAccess()
    private val heldOrderRows = db.heldOrderDao().observeAll()
    private val confirmedHeldOrderIds = db.heldOrderDao().observeConfirmedTargetIds()
    private val confirmedTerminalId = app.terminalStore.activeValidatedTerminal
        .map { it?.terminalId }
        .distinctUntilChanged()
    private val resolvedShift = db.shiftDao().observeResolvedOpenShift(confirmedTerminalId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recentReceipts: StateFlow<List<PosReceiptEntity>> = db.posReceiptDao()
        .observeRecent()
        .stateIn(viewModelScope, receiptUiSharing, emptyList())

    private val _receiptHistoryLoading = MutableStateFlow(false)
    val receiptHistoryLoading: StateFlow<Boolean> = _receiptHistoryLoading.asStateFlow()

    val receiptHistorySyncState: StateFlow<CanonicalReceiptSyncStateEntity?> =
        db.canonicalReceiptDao().observeSyncState()
            .stateIn(viewModelScope, receiptUiSharing, null)

    /**
     * Receipts from every web/Android till in the current company and branch.
     * Invalid cached JSON is omitted rather than crashing POS; the next
     * authoritative refresh replaces that order's projection.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val canonicalReceipts: StateFlow<List<CanonicalReceipt>> = combine(
        app.shiftCache.profile,
        receiptHistorySyncState,
    ) { profile, syncState ->
        profile to (syncState?.loadedCount ?: 0).coerceAtLeast(RECEIPT_HISTORY_PAGE_SIZE)
    }.flatMapLatest { (profile, limit) ->
        val branchId = profile?.branchId
        if (profile == null || branchId == null) {
            flowOf(emptyList())
        } else {
            db.canonicalReceiptDao().observeRecent(
                companyId = profile.companyId,
                branchId = branchId,
                limit = limit,
            )
        }
    }.map { rows -> rows.mapNotNull { it.decodedReceipt() } }
        // A history page contains complete receipt payloads. Decoding it on
        // Main can stall input/rendering when a page arrives or is refreshed.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, receiptUiSharing, emptyList())

    val receiptHistoryError: StateFlow<String?> = combine(
        receiptHistorySyncState,
        app.sync.resourceRefreshErrors,
    ) { state, errors ->
        // A later network/auth/server failure is more current than a cached
        // staged-rollout 404 notice and must not be hidden by it.
        errors["receipts"] ?: state?.unavailableMessage
    }.stateIn(viewModelScope, receiptUiSharing, null)

    val unacknowledgedReceipt: StateFlow<PosReceiptEntity?> = db.posReceiptDao()
        .observeOldestUnacknowledged()
        .stateIn(viewModelScope, receiptUiSharing, null)

    /**
     * The UI reads Room, never the network. That is what makes the screen work
     * offline: a dropped link changes the banner, not the till. This includes
     * the shift — ShiftViewModel writes the same `local_shifts` table this
     * reads, so a shift opened offline is immediately billable here too,
     * with no live "am I on a shift" round trip of its own.
     */
    val state: StateFlow<PosUiState> = combine(
        combine(
            db.menuDao().observeItems(),
            db.menuDao().observeCategories(),
            db.menuDao().observeVariants(),
            combine(
                db.menuDao().observeModifierGroups(),
                db.menuDao().observeModifiers(),
                ::Pair,
            ),
            combine(
                db.orderDao().observeActiveDraft(),
                db.customerDao().observeCache(),
                ::Pair,
            ),
        ) { items, categories, variants, modifierConfig, draftAndCustomers ->
            PosMenuSnapshot(
                items,
                categories,
                variants,
                modifierConfig.first,
                modifierConfig.second,
                draftAndCustomers.first,
                draftAndCustomers.second,
            )
        },
        combine(
            combine(selectedCategory, notice, directReviewOrderId, ::Triple),
            combine(
                combine(
                    checkoutBusy,
                    preparingHeldOrderId,
                    heldOrderReview,
                    preparedHeldCheckout,
                    heldSelectionBlocked,
                ) { busy, preparing, review, prepared, selectionBlocked ->
                    CheckoutSnapshot(busy, preparing, review, prepared, selectionBlocked)
                },
                combine(retryingRejectedSaleIds, retryingHeldPaymentIds) { direct, held ->
                    RetrySnapshot(direct, held)
                },
                ::Pair,
            ),
            ::Pair,
        ),
        combine(
            app.connectivity.online,
            app.sync.syncing,
            combine(resolvedShift, app.shiftCache.profile, ::Pair),
            ::Triple,
        ),
        combine(app.sync.pendingCount, db.orderDao().observeRejected()) { pending, rejected ->
            DirectQueueSnapshot(pending, rejected)
        },
        combine(
            db.syncMetaDao().observe("menu"),
            heldOrderRows,
            confirmedHeldOrderIds,
            db.heldOrderDao().observeUnresolvedPayments(),
            combine(heldAlarmClock, focusedHeldOrderId, heldBannerMute, ::Triple),
        ) { meta, orders, confirmedIds, unresolvedPayments, alarmUi ->
            meta to HeldQueueSnapshot(
                orders = orders,
                confirmedTargetIds = confirmedIds,
                unresolvedPayments = unresolvedPayments,
                nowMillis = alarmUi.first,
                focusedOrderId = alarmUi.second,
                bannerMute = alarmUi.third,
            )
        },
    ) { menu, ui, net, queue, extra ->
        val (meta, heldQueue) = extra
        val uiValues = ui.first
        val checkout = ui.second.first
        val retries = ui.second.second
        val resolved = net.third.first
        val profile = net.third.second
        val actor = profile?.let { ShiftActor(it.userId, it.protectedAccess) }
        val pendingHeldPayments = heldQueue.unresolvedPayments.count {
            it.syncState == HeldOrderPaymentState.PENDING
        }
        val rejectedHeldPayments = heldQueue.unresolvedPayments.count {
            it.syncState == HeldOrderPaymentState.REJECTED
        }
        val heldOrderLabels = heldQueue.orders.associate { order ->
            order.id to (order.sourceLabel ?: order.invoiceNo)
        }
        val visibleHeldOrders = heldQueue.orders.filter {
            it.id !in heldQueue.confirmedTargetIds
        }
        val overdueIds = overdueHeldOrderIds(
            orders = visibleHeldOrders,
            locallyConfirmedOrderIds = emptySet(),
            nowMillis = heldQueue.nowMillis,
        )
        val overdueFingerprint = overdueHeldOrderFingerprint(overdueIds)
        val muteMatchesCurrentWork = overdueFingerprint.isNotEmpty() &&
            heldQueue.bannerMute.overdueFingerprint == overdueFingerprint
        val bannerMuted = muteMatchesCurrentWork &&
            heldQueue.bannerMute.untilMillis > heldQueue.nowMillis
        val currentItems = menu.items.associateBy { it.id }
        val categoryNames = menu.categories.associate { it.id to it.name }
        val operationalItems = menu.items.filter { item ->
            WorkspaceFeatureProfiles.Active.operationalCatalogPolicy.allows(
                categoryName = categoryNames[item.categoryId],
                itemType = item.type,
                isAvailable = item.isAvailable,
            )
        }
        val operationalCategoryIds = operationalItems.mapTo(linkedSetOf()) { it.categoryId }
        val operationalCategories = menu.categories.filter { it.id in operationalCategoryIds }
        val currentVariants = menu.variants.associateBy { it.id }
        val currentModifiers = menu.modifiers.associateBy { it.id }
        val draftOrder = menu.draft?.order
        val directReview = draftOrder?.takeIf {
            it.syncState == SyncState.PREPARING &&
                it.localId == uiValues.third &&
                it.lastError == null
        }?.let { draft ->
            DirectCheckoutReview(
                localId = draft.localId,
                revision = draft.revision,
                orderId = draft.serverOrderId ?: return@let null,
                shiftId = draft.shiftId,
                subtotalMinor = draft.serverSubtotalMinor ?: return@let null,
                discountMinor = draft.serverDiscountMinor ?: return@let null,
                pointsRedeemedMinor = draft.serverPointsRedeemedMinor ?: 0L,
                pointsRedeemed = draft.serverPointsRedeemed ?: 0,
                taxMinor = draft.serverTaxMinor ?: return@let null,
                roundOffMinor = draft.serverRoundOffMinor ?: return@let null,
                totalMinor = draft.serverTotalMinor ?: return@let null,
                dueMinor = draft.serverDueMinor ?: return@let null,
                checkoutVersion = draft.checkoutVersion ?: return@let null,
            )
        }
        val directCheckout = draftOrder?.takeIf { it.syncState == SyncState.AWAITING_PAYMENT }
            ?.let { draft ->
                val serverOrderId = draft.serverOrderId ?: return@let null
                val claimExpiresAt = draft.checkoutClaimExpiresAtMillis ?: return@let null
                val claimVersion = draft.checkoutVersion ?: return@let null
                val claimToken = draft.checkoutClaimToken ?: return@let null
                PreparedDirectCheckout(
                    localId = draft.localId,
                    revision = draft.revision,
                    orderId = serverOrderId,
                    shiftIdAtClaim = draft.shiftId,
                    subtotalMinor = draft.serverSubtotalMinor ?: 0L,
                    discountMinor = draft.serverDiscountMinor ?: 0L,
                    pointsRedeemedMinor = draft.serverPointsRedeemedMinor ?: 0L,
                    pointsRedeemed = draft.serverPointsRedeemed ?: 0,
                    taxMinor = draft.serverTaxMinor ?: 0L,
                    roundOffMinor = draft.serverRoundOffMinor ?: 0L,
                    totalMinor = draft.serverTotalMinor ?: return@let null,
                    dueMinor = draft.serverDueMinor ?: return@let null,
                    claimToken = claimToken,
                    claimExpiresAtMillis = claimExpiresAt,
                    claimOrderVersion = claimVersion,
                )
            }
        PosUiState(
            items = menu.items,
            categories = menu.categories,
            operationalItems = operationalItems,
            operationalCategories = operationalCategories,
            variants = menu.variants,
            modifierGroups = menu.modifierGroups,
            modifiers = menu.modifiers,
            selectedCategoryId = uiValues.first?.takeIf { it in operationalCategoryIds },
            cart = menu.draft?.lines.orEmpty()
                .sortedBy { it.rowId }
                .map { it.toCartLine(currentItems, currentVariants, currentModifiers) },
            notice = uiValues.second,
            checkoutBusy = checkout.busy,
            preparingHeldOrderId = checkout.preparingOrderId,
            heldOrderReview = checkout.review,
            preparedHeldCheckout = checkout.prepared,
            preparedDirectCheckout = directCheckout,
            directCheckoutReview = directReview,
            heldSelectionBlocked = checkout.heldSelectionBlocked,
            online = net.first,
            syncing = net.second,
            activeShiftId = resolved?.shiftId,
            canCollectPayment = resolved?.canManageMoney(actor) == true,
            shiftAccessMessage = resolved?.moneyAccessMessage(actor),
            pendingCount = queue.pendingCount + pendingHeldPayments,
            rejectedCount = queue.rejected.size + rejectedHeldPayments,
            rejectedDirectSales = queue.rejected.map { row ->
                RejectedDirectSale(
                    localId = row.localId,
                    amountMinor = row.capturedAmountMinor ?: row.estimateMinor,
                    paymentMethod = row.paymentMethod,
                    createdAtMillis = row.createdAtMillis,
                    error = row.lastError?.takeIf(String::isNotBlank)
                        ?: "The server refused this sale without an explanation.",
                )
            },
            heldRejectedCount = rejectedHeldPayments,
            retryingRejectedSaleIds = retries.directSaleIds,
            heldPaymentStatuses = heldQueue.unresolvedPayments.map { row ->
                HeldPaymentStatus(
                    localId = row.localId,
                    targetOrderId = row.targetOrderId,
                    sourceLabel = heldOrderLabels[row.targetOrderId],
                    amountMinor = row.amountMinor,
                    paymentMethod = row.method,
                    createdAtMillis = row.createdAtMillis,
                    state = row.syncState,
                    error = row.lastError?.takeIf(String::isNotBlank),
                )
            },
            retryingHeldPaymentIds = retries.heldPaymentIds,
            menuEmpty = operationalItems.isEmpty(),
            everSynced = meta != null,
            heldOrders = visibleHeldOrders,
            overdueHeldOrderIds = overdueIds,
            showOverdueBanner = shouldShowOverdueHeldOrderBanner(
                overdueOrderIds = overdueIds,
                mutedFingerprint = heldQueue.bannerMute.overdueFingerprint,
                mutedUntilMillis = heldQueue.bannerMute.untilMillis,
                nowMillis = heldQueue.nowMillis,
            ),
            overdueBannerMutedUntilMillis = if (bannerMuted) {
                heldQueue.bannerMute.untilMillis
            } else {
                0L
            },
            focusedHeldOrderId = heldQueue.focusedOrderId
                ?.takeIf { focus -> visibleHeldOrders.any { it.id == focus } },
            draftState = draftOrder?.syncState,
            draftLocalId = draftOrder?.localId,
            draftRevision = draftOrder?.revision,
            customerName = draftOrder?.customerName,
            customerPhone = draftOrder?.customerPhone,
            customerLoyaltyPoints = draftOrder?.customerPhone?.let { phone ->
                menu.customers.firstOrNull { it.phone == phone }?.loyaltyPoints
            },
            orderNote = draftOrder?.orderNote,
            manualDiscountMinor = draftOrder?.manualDiscountMinor ?: 0L,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PosUiState())

    init {
        // A review owns no claim, but it must still remain bound to the exact
        // queue row that launched it. Never let cache removal silently move
        // the dialog to a neighbouring held order.
        viewModelScope.launch {
            combine(
                heldOrderRows,
                confirmedHeldOrderIds,
                heldOrderReview,
            ) { orders, confirmedIds, review ->
                Triple(
                    review,
                    orders.any { it.id == review?.orderId },
                    review?.orderId?.let { it in confirmedIds } == true,
                )
            }.distinctUntilChanged().collect { (review, stillHeld, locallyConfirmed) ->
                if (review == null || (stillHeld && !locallyConfirmed)) return@collect
                if (heldOrderReview.compareAndSet(review, null)) {
                    heldReviewGeneration += 1L
                    notice.value = "The selected held order is no longer available. Its review " +
                        "was closed; no payment was started."
                }
            }
        }
        // Keep the dialog bound to one immutable order id. A cache reorder is
        // harmless; removal closes that exact dialog. If settlement is already
        // in flight, closure must not release its claim underneath the request
        // or durable payment row.
        viewModelScope.launch {
            combine(
                heldOrderRows,
                confirmedHeldOrderIds,
                preparedHeldCheckout,
                confirmingHeldOrderId,
            ) { orders, confirmedIds, prepared, confirmingId ->
                prepared?.orderId to HeldCheckoutInteractionPolicy.reconcilePreparedSelection(
                    selectedOrderId = prepared?.orderId,
                    cachedOrderIds = orders.mapTo(mutableSetOf()) { it.id },
                    locallyConfirmedOrderIds = confirmedIds.toSet(),
                    confirmingOrderId = confirmingId,
                )
            }.distinctUntilChanged().collect { (selectedOrderId, action) ->
                if (
                    action != PreparedHeldCheckoutAction.CLOSE_AND_RELEASE_CLAIM &&
                    action != PreparedHeldCheckoutAction.CLOSE_PAYMENT_OWNS_CLAIM
                ) {
                    return@collect
                }
                val prepared = preparedHeldCheckout.value ?: return@collect
                if (prepared.orderId != selectedOrderId) return@collect
                if (!preparedHeldCheckout.compareAndSet(prepared, null)) return@collect
                if (action == PreparedHeldCheckoutAction.CLOSE_AND_RELEASE_CLAIM) {
                    releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                    notice.value = "The selected held order is no longer available. Its payment " +
                        "dialog was closed; no other order was selected."
                }
            }
        }
        app.sync.requestSync()
        viewModelScope.launch {
            app.sync.refresh("menu")
            if (db.syncMetaDao().observe("menu").first() == null) {
                notice.value = app.sync.lastError.value
                    ?: recoveryGuidance(
                        risk = RecoveryRisk.READ,
                        failure = null,
                        subject = "the POS menu",
                    ).message
            }
            app.sync.refresh("orders")
            app.sync.refresh("receipts")
        }
    }

    fun refresh() {
        app.sync.requestSync()
        viewModelScope.launch {
            app.sync.refresh("menu")
            if (db.syncMetaDao().observe("menu").first() == null) {
                notice.value = app.sync.lastError.value
                    ?: recoveryGuidance(
                        risk = RecoveryRisk.READ,
                        failure = null,
                        subject = "the POS menu",
                    ).message
            }
            app.sync.refresh("orders")
            app.sync.refresh("receipts")
        }
    }

    fun refreshReceiptHistory() {
        if (_receiptHistoryLoading.value) return
        viewModelScope.launch {
            _receiptHistoryLoading.value = true
            try {
                app.sync.refresh("receipts")
            } finally {
                _receiptHistoryLoading.value = false
            }
        }
    }

    fun loadMoreReceiptHistory() {
        if (_receiptHistoryLoading.value || receiptHistorySyncState.value?.hasMore != true) return
        viewModelScope.launch {
            _receiptHistoryLoading.value = true
            try {
                app.sync.loadMoreReceiptHistory()
            } finally {
                _receiptHistoryLoading.value = false
            }
        }
    }

    fun refreshReceiptHistoryDetail(orderId: String) {
        if (orderId.isBlank() || _receiptHistoryLoading.value) return
        viewModelScope.launch {
            _receiptHistoryLoading.value = true
            try {
                app.sync.refreshReceiptHistoryDetail(orderId)
            } finally {
                _receiptHistoryLoading.value = false
            }
        }
    }

    /** Scroll/highlight only; opening a payment claim remains an explicit card tap. */
    fun focusHeldOrder(orderId: String) {
        focusedHeldOrderId.value = orderId
    }

    fun focusOldestOverdueOrder() {
        state.value.overdueHeldOrderIds.firstOrNull()?.let(::focusHeldOrder)
    }

    fun dismissHeldOrderFocus() {
        focusedHeldOrderId.value = null
    }

    /**
     * Snooze is bounded and tied to the exact overdue set. The count/cards stay
     * visible, the banner returns in five minutes, and any new overdue order
     * breaks the mute immediately.
     */
    fun snoozeOverdueBanner() {
        val ids = state.value.overdueHeldOrderIds.sorted()
        if (ids.isEmpty()) return
        heldBannerMute.value = HeldBannerMute(
            overdueFingerprint = overdueHeldOrderFingerprint(ids),
            untilMillis = System.currentTimeMillis() + 5L * 60L * 1_000L,
        )
    }

    fun unmuteOverdueBanner() {
        heldBannerMute.value = HeldBannerMute()
    }

    fun updateAccess(next: PosAccess) {
        access = next
        // The cart is intentionally not erased here. It is a durable operational
        // record bound to the scoped tablet/shift, not transient UI owned by the
        // current composition. Revoked users simply lose every mutation action.
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canCreateAndCollect) {
        notice.value = VIEW_ONLY_MESSAGE
    }

    private fun requireVoid(): Boolean = authorizeAction(access.canVoid) {
        notice.value = "Voiding a released bill requires POS void permission. Ask a manager."
    }

    private fun requireDiscount(): Boolean = authorizeAction(access.canApplyDiscount) {
        notice.value = "Changing or clearing a manual discount requires manager discount permission."
    }

    fun selectCategory(id: String?) { selectedCategory.value = id }

    fun add(item: MenuItemEntity) {
        if (!requireWrite()) return
        if (state.value.operationalItems.none { it == item }) {
            notice.value = "This product is not available for a new Gaming Centre sale. Refresh Products."
            return
        }
        mutateEditableCart { lines ->
            val index = lines.indexOfFirst {
                it.item.id == item.id && it.variant == null &&
                    it.modifiers.isEmpty() && it.note == null
            }
            if (index < 0) {
                lines + newCartLine(item)
            } else {
                lines.toMutableList().also { rows ->
                    rows[index] = rows[index].copy(qty = rows[index].qty + 1)
                }
            }
        }
    }

    fun addConfigured(
        item: MenuItemEntity,
        variant: MenuVariantEntity?,
        modifiers: List<CartModifierSelection>,
        note: String?,
    ) {
        if (!requireWrite()) return
        if (state.value.operationalItems.none { it == item }) {
            notice.value = "This product is not available for a new Gaming Centre sale. Refresh Products."
            return
        }
        val candidate = newCartLine(item, variant, modifiers, note)
        mutateEditableCart { lines ->
            val index = lines.indexOfFirst { current ->
                current.item.id == candidate.item.id &&
                    current.variant?.id == candidate.variant?.id &&
                    current.modifiers.map { it.modifier.id to it.qty } ==
                    candidate.modifiers.map { it.modifier.id to it.qty } &&
                    current.note == candidate.note &&
                    current.unitPriceMinor == candidate.unitPriceMinor
            }
            if (index < 0) {
                lines + candidate
            } else {
                lines.toMutableList().also { rows ->
                    rows[index] = rows[index].copy(qty = rows[index].qty + 1)
                }
            }
        }
    }

    fun remove(item: MenuItemEntity) {
        if (!requireWrite()) return
        state.value.cart.lastOrNull { it.item.id == item.id }?.let { decrementLine(it.lineId) }
    }

    fun incrementLine(lineId: String) {
        if (!requireWrite()) return
        mutateEditableCart { lines ->
            lines.map { line -> if (line.lineId == lineId) line.copy(qty = line.qty + 1) else line }
        }
    }

    fun decrementLine(lineId: String) {
        if (!requireWrite()) return
        mutateEditableCart { lines ->
            lines.mapNotNull { line ->
                when {
                    line.lineId != lineId -> line
                    line.qty > 1 -> line.copy(qty = line.qty - 1)
                    else -> null
                }
            }
        }
    }

    fun updateLine(lineId: String, modifiers: List<CartModifierSelection>, note: String?) {
        if (!requireWrite()) return
        mutateEditableCart { lines ->
            lines.map { line ->
                if (line.lineId != lineId) line else line.copy(
                    modifiers = modifiers.sortedBy { it.modifier.id },
                    note = note?.trim()?.takeIf(String::isNotEmpty),
                    unitPriceMinor = configuredUnitPriceMinor(line.item, line.variant, modifiers),
                )
            }
        }
    }

    fun clearCart() {
        if (!requireWrite()) return
        if (checkoutBusy.value) return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            cartMutationMutex.withLock {
                val current = db.orderDao().activeDraft() ?: return@withLock
                if (current.order.syncState != SyncState.DRAFT) {
                    notice.value = "This bill was already prepared by the server. Cancel that bill before clearing it."
                    return@withLock
                }
                app.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.orderDao().deleteDraft(current.order.localId)
                }
            }
        }
    }

    fun updateDraftDetails(
        customerName: String?,
        customerPhone: String?,
        orderNote: String?,
        manualDiscountMinor: Long,
    ) {
        if (!requireWrite()) return
        if (manualDiscountMinor < 0L) {
            notice.value = "Enter a discount of ₹0.00 or more."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            cartMutationMutex.withLock {
                val current = db.orderDao().activeDraft()
                if (current == null || current.order.syncState != SyncState.DRAFT) {
                    notice.value = "Add an item before saving customer, note, or discount details."
                    return@withLock
                }
                val estimate = current.lines.sumOf { it.unitPriceMinor * it.qty }
                if (manualDiscountMinor > estimate) {
                    notice.value = "The discount cannot be greater than the cart subtotal."
                    return@withLock
                }
                val now = System.currentTimeMillis()
                app.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.orderDao().saveDraft(
                        current.order.copy(
                            customerName = customerName?.trim()?.takeIf(String::isNotEmpty),
                            customerPhone = customerPhone?.filter(Char::isDigit)?.takeIf(String::isNotEmpty),
                            orderNote = orderNote?.trim()?.takeIf(String::isNotEmpty),
                            manualDiscountMinor = manualDiscountMinor,
                            revision = current.order.revision + 1,
                            updatedAtMillis = now,
                        ),
                        current.lines,
                    )
                }
            }
        }
    }

    private fun mutateEditableCart(transform: (List<CartLine>) -> List<CartLine>) {
        if (checkoutBusy.value) return
        val shiftId = authorisedShiftId() ?: return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            cartMutationMutex.withLock {
                val current = db.orderDao().activeDraft()
                if (current != null && current.order.syncState != SyncState.DRAFT) {
                    notice.value = "This order is already prepared for payment. Finish or cancel it before editing items."
                    return@withLock
                }
                if (current != null && current.order.shiftId != shiftId) {
                    notice.value = "This saved cart belongs to a different shift. Ask a manager to review it before billing."
                    return@withLock
                }
                val currentState = state.value
                val itemsById = currentState.items.associateBy { it.id }
                val variantsById = currentState.variants.associateBy { it.id }
                val modifiersById = currentState.modifiers.associateBy { it.id }
                val before = current?.lines.orEmpty().sortedBy { it.rowId }.map {
                    it.toCartLine(itemsById, variantsById, modifiersById)
                }
                val after = transform(before)
                val now = System.currentTimeMillis()
                app.cacheIsolation.commitIfCurrent(scopeLease) {
                    if (after.isEmpty()) {
                        current?.let { db.orderDao().deleteDraft(it.order.localId) }
                    } else {
                        val localId = current?.order?.localId ?: UUID.randomUUID().toString()
                        val order = (current?.order ?: LocalOrderEntity(
                            localId = localId,
                            shiftId = shiftId,
                            type = DIRECT_COUNTER_SALE_ORDER_TYPE,
                            estimateMinor = 0L,
                            createdAtMillis = now,
                            syncState = SyncState.DRAFT,
                        )).copy(
                            estimateMinor = after.sumOf { it.lineTotalMinor },
                            revision = (current?.order?.revision ?: 0L) + 1L,
                            updatedAtMillis = now,
                            syncState = SyncState.DRAFT,
                            lastError = null,
                        )
                        db.orderDao().saveDraft(order, after.map { it.toLocalOrderLine(localId) })
                    }
                }
            }
        }
    }

    fun dismissNotice() { notice.value = null }

    fun acknowledgeReceipt(receiptId: String) {
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            app.cacheIsolation.commitIfCurrent(scopeLease) {
                db.posReceiptDao().acknowledge(receiptId, System.currentTimeMillis())
            }
        }
    }

    /**
     * Online checkout is a two-step boundary: create/recover and price the
     * server bill first, then acquire an exclusive snapshot before showing the
     * payment dialog. Staff never collect against the cached menu estimate.
     */
    fun prepareDirectCheckout() = prepareDirectCheckout(publishRequested = false)

    /** Continue only after customer, discount and points choices are final. */
    fun continueDirectCheckout() {
        if (state.value.directCheckoutReview == null) {
            notice.value = "Refresh the live bill before continuing to payment."
            prepareDirectCheckout()
            return
        }
        prepareDirectCheckout(publishRequested = true)
    }

    private fun prepareDirectCheckout(publishRequested: Boolean) {
        if (!requireWrite()) return
        if (authorisedShiftId() == null) return
        if (!app.connectivity.online.value) {
            // Offline has its own provisional capture path. The screen may show
            // the cached subtotal, but it must label it as provisional.
            return
        }
        if (
            checkoutBusy.value || heldOrderReview.value != null ||
            preparedHeldCheckout.value != null
        ) return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        checkoutBusy.value = true
        viewModelScope.launch {
            try {
                cartMutationMutex.withLock {
                    val snapshot = db.orderDao().activeDraft()
                        ?: throw IllegalStateException("Add at least one item before payment.")
                    val initialLocal = snapshot.order
                    if (snapshot.lines.isEmpty()) {
                        throw IllegalStateException("Add at least one item before payment.")
                    }
                    if (initialLocal.syncState !in setOf(
                            SyncState.DRAFT,
                            SyncState.PREPARING,
                            SyncState.AWAITING_PAYMENT,
                        )
                    ) {
                        throw IllegalStateException("This order is no longer editable or payable from the cart.")
                    }

                    if (
                        initialLocal.syncState == SyncState.AWAITING_PAYMENT &&
                        !initialLocal.checkoutClaimToken.isNullOrBlank() &&
                        initialLocal.checkoutClaimExpiresAtMillis?.let {
                            HeldOrderClaimPolicy.hasConfirmationWindow(it, System.currentTimeMillis())
                        } == true
                    ) {
                        return@withLock
                    }

                    if (initialLocal.syncState == SyncState.DRAFT) {
                        val transitioned = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                            db.orderDao().updateDraftState(
                                initialLocal.localId,
                                SyncState.PREPARING,
                                System.currentTimeMillis(),
                            )
                        }
                        if (transitioned !is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed) {
                            return@withLock
                        }
                    }
                    val local = db.orderDao().withLines(initialLocal.localId)?.order
                        ?: throw IllegalStateException("The saved cart is no longer available.")

                    val localShift = db.shiftDao().byLocalId(local.shiftId)
                    if (localShift != null && localShift.serverShiftId == null) {
                        app.sync.sync()
                    }
                    val serverShiftId = db.shiftDao().byLocalId(local.shiftId)?.serverShiftId
                        ?: local.shiftId
                    val currentResolvedShiftId = resolvedShift.value?.shiftId
                    if (currentResolvedShiftId == null || currentResolvedShiftId !in setOf(
                            local.shiftId,
                            serverShiftId,
                        )
                    ) {
                        throw IllegalStateException(
                            "The open shift changed while this order was being prepared. Review the shift and try again.",
                        )
                    }

                    val serverOrder = local.serverOrderId?.let { ApiClient.api.order(it) }
                        ?: ApiClient.api.createOrder(
                            CreateOrderRequest(
                                type = local.type,
                                shiftId = serverShiftId,
                                lines = snapshot.lines.map { line ->
                                    OrderLineRequest(
                                        clientLineId = line.clientLineId,
                                        menuItemId = line.menuItemId,
                                        qty = line.qty,
                                        variantId = line.variantId,
                                        modifiers = cloud.dcompany.erp.core.db
                                            .decodeModifierSelections(line.modifierSelectionsJson)
                                            .map { selected ->
                                                ModifierSelectionRequest(selected.modifierId, selected.qty)
                                            },
                                        note = line.note,
                                    )
                                },
                                customerName = local.customerName,
                                customerPhone = local.customerPhone,
                                notes = local.orderNote,
                            ),
                            idempotencyKey = "order:${local.localId}",
                        )
                    check(serverOrder.id.isNotBlank() && serverOrder.status in setOf("open", "held")) {
                        "The server did not return a payable bill for this cart."
                    }

                    // POST may have committed and lost its response. PREPARING
                    // retains the pre-publication version, while an expired
                    // AWAITING_PAYMENT row retains the single post-publication
                    // bump. Replaying the exact expected version lets the
                    // backend distinguish recovery from an unrelated held bill.
                    if (serverOrder.status == "held") {
                        val expectedVersion = DirectOrderPublishPolicy.expectedVersionForReplay(
                            syncState = local.syncState,
                            storedCheckoutVersion = local.checkoutVersion
                                ?: throw IllegalStateException(
                                    "The saved bill version is missing. Use the held-bill queue to reconcile it.",
                                ),
                        )
                        publishDirectCheckoutAndPersist(
                            scopeLease = scopeLease,
                            local = local,
                            pricedOrder = serverOrder,
                            expectedVersion = expectedVersion,
                        )
                        return@withLock
                    }

                    val checkpointed = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                        db.orderDao().checkpointServerDraft(
                            localId = local.localId,
                            serverOrderId = serverOrder.id,
                            serverShiftId = serverShiftId,
                            subtotalMinor = serverOrder.subtotalMinor,
                            discountMinor = serverOrder.discountMinor,
                            pointsRedeemedMinor = serverOrder.pointsRedeemedMinor,
                            pointsRedeemed = serverOrder.pointsRedeemed,
                            taxMinor = serverOrder.taxMinor,
                            roundOffMinor = serverOrder.roundOffMinor,
                            totalMinor = serverOrder.totalMinor,
                            dueMinor = serverOrder.dueMinor,
                            checkoutVersion = serverOrder.checkoutVersion,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    if (
                        checkpointed !is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed ||
                        checkpointed.value != 1
                    ) {
                        return@withLock
                    }

                    val priced = when {
                        local.manualDiscountMinor <= 0L -> serverOrder
                        // Recovery after an ambiguous response: the server
                        // already committed the requested value, so issuing a
                        // second body with a newer expected version would turn
                        // a safe retry into an idempotency-hash conflict.
                        serverOrder.manualDiscountMinor == local.manualDiscountMinor -> serverOrder
                        else -> {
                            val firstRequestVersion = local.discountRequestVersion
                                ?: serverOrder.checkoutVersion
                            val frozen = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                                db.orderDao().preserveDiscountRequestVersion(
                                    localId = local.localId,
                                    requestVersion = firstRequestVersion,
                                    updatedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            check(
                                frozen is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                                    frozen.value == 1,
                            ) { "The discount request could not be secured for a safe retry." }
                            val persistedRequestVersion = db.orderDao().withLines(local.localId)
                                ?.order?.discountRequestVersion
                                ?: throw IllegalStateException(
                                    "The discount request version was not saved. No payment was recorded.",
                                )
                            try {
                                ApiClient.api.updateOrderDiscount(
                                    id = serverOrder.id,
                                    body = OrderDiscountUpdateRequest(
                                        manualDiscountMinor = local.manualDiscountMinor,
                                        expectedCheckoutVersion = persistedRequestVersion,
                                    ),
                                    idempotencyKey = "order-discount:${local.localId}",
                                )
                            } catch (failure: ApiException) {
                                if (failure.isAmbiguous) throw failure
                                val canonical = ApiClient.api.order(serverOrder.id)
                                check(canonical.status == "open") {
                                    "The bill changed while its discount was being reviewed."
                                }
                                val canonicalCheckpoint =
                                    app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                                        db.orderDao().checkpointServerDraft(
                                            localId = local.localId,
                                            serverOrderId = canonical.id,
                                            serverShiftId = serverShiftId,
                                            subtotalMinor = canonical.subtotalMinor,
                                            discountMinor = canonical.discountMinor,
                                            pointsRedeemedMinor = canonical.pointsRedeemedMinor,
                                            pointsRedeemed = canonical.pointsRedeemed,
                                            taxMinor = canonical.taxMinor,
                                            roundOffMinor = canonical.roundOffMinor,
                                            totalMinor = canonical.totalMinor,
                                            dueMinor = canonical.dueMinor,
                                            checkoutVersion = canonical.checkoutVersion,
                                            updatedAtMillis = System.currentTimeMillis(),
                                        )
                                    }
                                check(
                                    canonicalCheckpoint is
                                        cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                                        canonicalCheckpoint.value == 1,
                                ) { "The restored bill could not be saved on this tablet." }
                                val adopted = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                                    db.orderDao().adoptCanonicalDiscountAfterRefusal(
                                        localId = local.localId,
                                        serverOrderId = canonical.id,
                                        canonicalManualDiscountMinor = canonical.manualDiscountMinor,
                                        updatedAtMillis = System.currentTimeMillis(),
                                    )
                                }
                                check(
                                    adopted is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                                        adopted.value == 1,
                                ) { "The rejected discount could not be cleared safely." }
                                directReviewOrderId.value = local.localId
                                notice.value = (failure.message
                                    ?: "The server did not accept that discount.") +
                                    " The live bill was restored without it; review before payment."
                                return@withLock
                            }
                        }
                    }
                    val pricedCheckpoint = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                        db.orderDao().checkpointServerDraft(
                            localId = local.localId,
                            serverOrderId = priced.id,
                            serverShiftId = serverShiftId,
                            subtotalMinor = priced.subtotalMinor,
                            discountMinor = priced.discountMinor,
                            pointsRedeemedMinor = priced.pointsRedeemedMinor,
                            pointsRedeemed = priced.pointsRedeemed,
                            taxMinor = priced.taxMinor,
                            roundOffMinor = priced.roundOffMinor,
                            totalMinor = priced.totalMinor,
                            dueMinor = priced.dueMinor,
                            checkoutVersion = priced.checkoutVersion,
                            updatedAtMillis = System.currentTimeMillis(),
                        )
                    }
                    check(
                        pricedCheckpoint is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                            pricedCheckpoint.value == 1,
                    ) { "The final reviewed bill could not be saved before checkout publication." }

                    // A customer-linked direct bill gets a pre-payment review
                    // so points can be applied while the server order is still
                    // open. No money controls exist in this state.
                    if (!publishRequested && !local.customerPhone.isNullOrBlank()) {
                        directReviewOrderId.value = local.localId
                        notice.value = "Live total verified. Apply any loyalty points, then continue to payment."
                        return@withLock
                    }

                    publishDirectCheckoutAndPersist(
                        scopeLease = scopeLease,
                        local = local,
                        pricedOrder = priced,
                        expectedVersion = priced.checkoutVersion,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val current = db.orderDao().activeDraft()
                current?.takeUnless { it.order.syncState == SyncState.AWAITING_PAYMENT }?.let {
                    db.orderDao().updateDraftState(
                        it.order.localId,
                        SyncState.PREPARING,
                        System.currentTimeMillis(),
                        e.message,
                    )
                }
                notice.value = directCheckoutPreparationNotice(e)
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    private suspend fun publishDirectCheckoutAndPersist(
        scopeLease: cloud.dcompany.erp.core.auth.CacheScopeLease,
        local: LocalOrderEntity,
        pricedOrder: Order,
        expectedVersion: Long,
    ) {
        val clientInstance = CheckoutClientInstancePolicy.requireStable(
            app.updateTelemetry.installation.installationId(),
        )
        val claim = ApiClient.api.publishDirectCheckoutClaim(
            id = pricedOrder.id,
            body = PublishDirectCheckoutClaimRequest(expectedVersion),
            idempotencyKey = DirectOrderPublishPolicy.idempotencyKey(local.localId),
            checkoutClientInstance = clientInstance,
        )
        check(
            DirectOrderPublishPolicy.matchesPricedOrder(
                order = pricedOrder,
                expectedPrePublishVersion = expectedVersion,
                claim = claim,
            ),
        ) { "The published checkout claim does not match the reviewed bill." }
        val expiresAtMillis = HeldOrderClaimPolicy.claimExpiryMillis(claim)
            ?: throw IllegalStateException("The checkout claim had an invalid expiry time.")
        if (!HeldOrderClaimPolicy.hasConfirmationWindow(expiresAtMillis, System.currentTimeMillis())) {
            releaseClaimBestEffort(pricedOrder.id, claim.claimToken)
            throw IllegalStateException("The checkout reservation expired too soon. Review the bill again.")
        }
        val saved = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
            db.orderDao().markDraftPrepared(
                localId = local.localId,
                serverOrderId = pricedOrder.id,
                subtotalMinor = pricedOrder.subtotalMinor,
                discountMinor = pricedOrder.discountMinor,
                pointsRedeemedMinor = pricedOrder.pointsRedeemedMinor,
                pointsRedeemed = pricedOrder.pointsRedeemed,
                taxMinor = pricedOrder.taxMinor,
                roundOffMinor = pricedOrder.roundOffMinor,
                totalMinor = pricedOrder.totalMinor,
                dueMinor = pricedOrder.dueMinor,
                claimToken = claim.claimToken,
                claimExpiresAtMillis = expiresAtMillis,
                checkoutVersion = claim.orderVersion,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        if (
            saved !is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed ||
            saved.value != 1
        ) {
            releaseClaimBestEffort(pricedOrder.id, claim.claimToken)
            throw IllegalStateException(
                "The checkout reservation could not be saved. No payment was recorded.",
            )
        }
        directReviewOrderId.value = null
        notice.value = "Live bill published and reserved. Review it, then choose the payment method."
    }

    fun dismissDirectCheckout() {
        if (checkoutBusy.value) return
        if (directReviewOrderId.value != null) {
            directReviewOrderId.value = null
            notice.value = "Payment review closed. The server bill is still open and no money was recorded."
            return
        }
        val prepared = state.value.preparedDirectCheckout ?: return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            // Remove the local collectible state first. Releasing the server
            // lease before this commit would leave a crash window where the
            // payment dialog still renders a stale, no-longer-owned bearer.
            val removed = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                db.orderDao().removeExactPreparedDirect(
                    localId = prepared.localId,
                    serverOrderId = prepared.orderId,
                    claimToken = prepared.claimToken,
                )
            }
            if (
                removed !is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed ||
                !removed.value
            ) {
                notice.value = "The bill is still reserved on this tablet. Try closing it again."
                return@launch
            }
            releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
            notice.value = "Bill saved in the payment queue. Select it there when the customer is ready."
            app.sync.refresh("orders")
        }
    }

    /**
     * Apply an absolute points amount to the exact server bill currently under
     * review. The deterministic key includes the frozen optimistic version and
     * body, so an ambiguous retry replays the same mutation rather than
     * reserving points twice. The cache balance is display-only; the locked
     * backend customer/order transaction is the authority.
     */
    fun redeemDirectPoints(points: Int) {
        if (!requireWrite()) return
        if (points < 0) {
            notice.value = "Enter zero or more loyalty points."
            return
        }
        val review = state.value.directCheckoutReview ?: run {
            notice.value = "Verify the live bill before applying loyalty points."
            return
        }
        if (state.value.customerPhone.isNullOrBlank()) {
            notice.value = "Add the customer's phone number before using loyalty points."
            return
        }
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before using loyalty points so the live balance can be reserved safely."
            return
        }
        if (checkoutBusy.value) return
        val currentShiftId = authorisedShiftId() ?: return
        if (currentShiftId != review.shiftId) {
            notice.value = "The open shift changed. Verify this bill again before using points."
            prepareDirectCheckout()
            return
        }
        if (points == review.pointsRedeemed) return

        val scopeLease = app.cacheIsolation.currentLease() ?: return
        checkoutBusy.value = true
        viewModelScope.launch {
            try {
                val updated = ApiClient.api.updateOrderPoints(
                    id = review.orderId,
                    body = OrderPointsRedemptionUpdateRequest(
                        points = points,
                        expectedCheckoutVersion = review.checkoutVersion,
                    ),
                    idempotencyKey = pointsRedemptionIdempotencyKey(
                        localId = review.localId,
                        orderId = review.orderId,
                        checkoutVersion = review.checkoutVersion,
                        points = points,
                    ),
                )
                check(updated.id == review.orderId && updated.status == "open") {
                    "The server returned the wrong bill after applying points."
                }
                val saved = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                    db.orderDao().checkpointServerDraft(
                        localId = review.localId,
                        serverOrderId = updated.id,
                        serverShiftId = review.shiftId,
                        subtotalMinor = updated.subtotalMinor,
                        discountMinor = updated.discountMinor,
                        pointsRedeemedMinor = updated.pointsRedeemedMinor,
                        pointsRedeemed = updated.pointsRedeemed,
                        taxMinor = updated.taxMinor,
                        roundOffMinor = updated.roundOffMinor,
                        totalMinor = updated.totalMinor,
                        dueMinor = updated.dueMinor,
                        checkoutVersion = updated.checkoutVersion,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
                check(
                    saved is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                        saved.value == 1,
                ) { "The points-adjusted bill could not be saved on this tablet." }
                notice.value = if (updated.pointsRedeemed > 0) {
                    "${updated.pointsRedeemed} points reserved for " +
                        "${updated.pointsRedeemedMinor.asRupees()}. New total ${updated.totalMinor.asRupees()}."
                } else {
                    "Loyalty points removed. New total ${updated.totalMinor.asRupees()}."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                notice.value = error.message
                    ?: "The server could not reserve those points. No payment was recorded."
            } catch (error: Exception) {
                notice.value = error.message
                    ?: "The points change could not be verified. No payment was recorded; try again."
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    fun confirmDirectZero() {
        if (!requireWrite()) return
        val prepared = state.value.preparedDirectCheckout ?: return
        if (prepared.dueMinor != 0L || prepared.totalMinor != 0L) {
            notice.value = "Only an exact ₹0.00 bill can be completed without payment."
            return
        }
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect to complete this zero-total benefit and issue its receipt."
            return
        }
        if (checkoutBusy.value) return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        checkoutBusy.value = true
        viewModelScope.launch {
            var acceptedInvoiceNo: String? = null
            try {
                val result = try {
                    ApiClient.api.finalizeZeroTotalOrder(
                        id = prepared.orderId,
                        idempotencyKey = HeldOrderClaimPolicy.zeroFinalizationIdempotencyKey(
                            prepared.orderId,
                            prepared.claimOrderVersion,
                        ),
                        checkoutClaimToken = prepared.claimToken,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: ApiException) {
                    if (!e.isAmbiguous) {
                        // A definitive refusal from the finalization POST wrote
                        // no invoice. Demote the local payable projection before
                        // releasing its bearer so it can never become stale UI.
                        val recovered = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                            db.orderDao().returnPublishedDirectToRecovery(
                                localId = prepared.localId,
                                serverOrderId = prepared.orderId,
                                prePublishVersion = DirectOrderPublishPolicy.expectedVersionForReplay(
                                    SyncState.AWAITING_PAYMENT,
                                    prepared.claimOrderVersion,
                                ),
                                error = e.message,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                        if (
                            recovered is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                            recovered.value == 1
                        ) {
                            releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                        } else {
                            notice.value = "The zero-total completion was refused, but this tablet " +
                                "could not safely close its checkout. Do not take money; restart and review the bill."
                            return@launch
                        }
                    }
                    notice.value = e.message
                        ?: "The zero-total bill is not confirmed yet. Do not collect money; retry the same completion."
                    return@launch
                } catch (_: Exception) {
                    // A transport or decoding failure may follow a committed
                    // invoice. Keep the exact publication state so the same
                    // idempotency key can reconcile it without another bill.
                    notice.value = "The completion response could not be confirmed. Collect no money and " +
                        "retry this same ₹0.00 completion when the connection is stable."
                    return@launch
                }

                if (
                    result.orderId != prepared.orderId || result.amountMinor != 0L ||
                    result.orderStatus != "paid" || result.invoiceNo.isNullOrBlank()
                ) {
                    notice.value = "The server returned incomplete completion evidence. Collect no money; " +
                        "retry this same ₹0.00 completion after refreshing."
                    return@launch
                }
                val invoiceNo = requireNotNull(result.invoiceNo)
                acceptedInvoiceNo = invoiceNo
                // This is the local irreversible acceptance boundary. It must
                // precede GET /order: a later receipt-hydration failure cannot
                // turn a paid invoice back into a payable checkout.
                val accepted = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                    db.orderDao().markExactZeroDirectFinalized(
                        localId = prepared.localId,
                        serverOrderId = prepared.orderId,
                        claimToken = prepared.claimToken,
                        invoiceNo = invoiceNo,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
                if (
                    accepted !is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed ||
                    accepted.value != 1
                ) {
                    notice.value = "Invoice $invoiceNo was completed on the server, but this tablet " +
                        "could not save the acknowledgement. Collect no money; restart and sync receipts."
                    return@launch
                }

                val receiptSaved = try {
                    val finalOrder = ApiClient.api.order(prepared.orderId)
                    app.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.posReceiptDao().store(
                            zeroTotalReceipt(
                                order = finalOrder,
                                result = result,
                                sourceKind = PosReceiptSource.ZERO_DIRECT,
                                shiftId = prepared.shiftIdAtClaim,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                notice.value = if (receiptSaved) {
                    "Bill completed once · $invoiceNo · ₹0.00 due. No money was collected."
                } else {
                    "Bill completed once · $invoiceNo · ₹0.00 due. No money was collected. " +
                        "The detailed receipt is still syncing."
                }
                app.sync.refresh("orders")
                app.sync.refresh("receipts")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice.value = acceptedInvoiceNo?.let { invoiceNo ->
                    "Invoice $invoiceNo was completed on the server. No money was collected. " +
                        "The tablet acknowledgement or receipt is still syncing."
                } ?: "The zero-total completion could not be verified. Collect no money and " +
                    "retry this same completion after refreshing."
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    fun voidOrder(orderId: String, reason: String) {
        if (!requireVoid()) return
        val cleanReason = reason.trim()
        if (cleanReason.isEmpty()) {
            notice.value = "Select or enter a reason before voiding this bill."
            return
        }
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before voiding a released bill so the audit and linked cancellation are saved together."
            return
        }
        if (checkoutBusy.value) return
        val direct = state.value.preparedDirectCheckout?.takeIf { it.orderId == orderId }
        val held = preparedHeldCheckout.value?.takeIf { it.orderId == orderId }
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        checkoutBusy.value = true
        viewModelScope.launch {
            try {
                if (direct != null) {
                    // A checkout lease must never be released while its local
                    // bearer is still rendered as payable. If the process
                    // stops after this commit, recovery can only discover the
                    // canonical held bill from the server queue; it cannot
                    // collect against the released credential.
                    val removed = app.cacheIsolation.commitResultIfCurrent(scopeLease) {
                        db.orderDao().removeExactPreparedDirect(
                            localId = direct.localId,
                            serverOrderId = direct.orderId,
                            claimToken = direct.claimToken,
                        )
                    }
                    check(
                        removed is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                            removed.value,
                    ) {
                        "The bill changed before it could be made safe for voiding. " +
                            "It remains reserved; close this message and try again."
                    }
                }
                if (held != null) {
                    check(preparedHeldCheckout.compareAndSet(held, null)) {
                        "The selected bill changed before it could be made safe for voiding. " +
                            "Reload the payment queue and try again."
                    }
                }
                ApiClient.api.voidOrder(
                    orderId,
                    cloud.dcompany.erp.core.net.VoidOrderRequest(cleanReason),
                    direct?.claimToken ?: held?.claimToken,
                )
                notice.value = "Bill voided with reason: $cleanReason. The audit and linked cancellation are retained."
                app.sync.refresh("orders")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (direct != null || held != null) app.sync.refresh("orders")
                notice.value = if (direct != null || held != null) {
                    "The void was not confirmed. The old checkout was closed safely; " +
                        "reload the payment queue before taking any further action. " +
                        (e.message ?: "Check the connection and try the void again.")
                } else if (e is ApiException) {
                    e.message ?: "The bill could not be voided. It remains in the payment queue."
                } else {
                    "The bill could not be voided. It remains in the payment queue; " +
                        "check the connection and retry."
                }
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    /**
     * The local insert is the one-way "money received" boundary. Direct and
     * held bills share the same crash-safe settlement outbox and unique target
     * constraint, so rapid taps or ambiguous responses cannot duplicate money.
     */
    fun captureSale(
        method: String,
        tenderedMinor: Long,
        confirmation: DirectPaymentConfirmation,
    ) {
        if (!requireWrite()) return
        if (!app.connectivity.online.value) {
            captureOfflineSale(method, tenderedMinor, confirmation)
            return
        }
        val prepared = state.value.preparedDirectCheckout
        if (prepared == null) {
            notice.value = "The live bill has not been verified yet. Wait for the exact server total before collecting."
            prepareDirectCheckout()
            return
        }
        if (
            prepared.localId != confirmation.localId ||
            prepared.revision != confirmation.revision ||
            prepared.dueMinor != confirmation.dueMinor
        ) {
            notice.value = "The bill changed after the payment screen opened. Review the refreshed total before collecting."
            prepareDirectCheckout()
            return
        }
        if (checkoutBusy.value) return
        val currentShiftId = authorisedShiftId() ?: return
        if (currentShiftId != prepared.shiftIdAtClaim) {
            notice.value = "The open shift changed after this total was verified. Review the bill again."
            dismissDirectCheckout()
            return
        }
        if (!HeldOrderClaimPolicy.hasConfirmationWindow(
                prepared.claimExpiresAtMillis,
                System.currentTimeMillis(),
            )
        ) {
            notice.value = "The verified total expired. Refreshing it now; review before collecting."
            prepareDirectCheckout()
            return
        }
        if (method !in setOf("cash", "upi", "card")) {
            notice.value = "Choose Cash, UPI, or Card."
            return
        }
        if (prepared.dueMinor <= 0L || (method == "cash" && tenderedMinor < prepared.dueMinor)) {
            notice.value = "Enter enough cash to cover the verified total."
            return
        }
        val terminalId = app.terminalStore.confirmedTerminalId()
        if (terminalId == null) {
            notice.value = "This tablet's workspace is not verified. Sign in online before collecting."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        checkoutBusy.value = true
        startHeldSelectionTapGuard()
        val payment = LocalHeldOrderPaymentEntity(
            localId = prepared.localId,
            targetOrderId = prepared.orderId,
            method = method,
            amountMinor = prepared.dueMinor,
            tenderedMinor = tenderedMinor.takeIf { method == "cash" },
            expectedTotalMinor = prepared.totalMinor,
            expectedDueMinor = prepared.dueMinor,
            claimToken = prepared.claimToken,
            claimExpiresAtMillis = prepared.claimExpiresAtMillis,
            claimOrderVersion = prepared.claimOrderVersion,
            requiresCheckoutClaim = false,
            shiftId = prepared.shiftIdAtClaim,
            terminalId = terminalId,
            createdAtMillis = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            try {
                var inserted = -1L
                var existing: LocalHeldOrderPaymentEntity? = null
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.withTransaction {
                            inserted = db.heldOrderDao().insertPayment(payment)
                            if (inserted == -1L) {
                                existing = db.heldOrderDao().paymentForTarget(prepared.orderId)
                            }
                            db.orderDao().deleteDraft(prepared.localId)
                        }
                    }
                ) return@launch
                if (inserted == -1L) {
                    notice.value = duplicateHeldPaymentNotice(existing)
                } else {
                    notice.value = "Payment saved once. Confirming the same settlement with the server; do not collect again."
                    watchCapturedHeldPaymentOutcome(payment.localId)
                    requestHeldPaymentSyncAfterActivePass()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                notice.value = e.shiftClosingMessageOr(
                    "The tablet could not save this payment. The bill remains open; do not collect again until a manager checks it.",
                )
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    private fun captureOfflineSale(
        method: String,
        tenderedMinor: Long,
        confirmation: DirectPaymentConfirmation,
    ) {
        if (checkoutBusy.value) return
        val shiftId = authorisedShiftId() ?: return
        if (method !in setOf("cash", "upi", "card")) {
            notice.value = "Choose Cash, UPI, or Card."
            return
        }
        if (
            confirmation.localId.isBlank() ||
            confirmation.revision < 1L ||
            confirmation.dueMinor <= 0L ||
            (method == "cash" && tenderedMinor < confirmation.dueMinor)
        ) {
            notice.value = "Review the cart and enter enough cash before saving this offline payment."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        checkoutBusy.value = true
        viewModelScope.launch {
            try {
                cartMutationMutex.withLock {
                    val current = db.orderDao().withLines(confirmation.localId)
                        ?: throw IllegalStateException("The saved cart is no longer available.")
                    if (
                        current.order.shiftId != shiftId ||
                        current.order.syncState != SyncState.DRAFT ||
                        current.order.revision != confirmation.revision
                    ) {
                        throw IllegalStateException(
                            "The cart or shift changed after the payment screen opened. Review it again before collecting.",
                        )
                    }
                    if (current.order.customerPhone != null || current.order.manualDiscountMinor > 0L) {
                        throw IllegalStateException(
                            "Customer benefits and manual discounts need a live server total. Reconnect before payment.",
                        )
                    }
                    val currentState = state.value
                    val cart = current.lines.sortedBy { it.rowId }.map {
                        it.toCartLine(
                            currentState.items.associateBy { item -> item.id },
                            currentState.variants.associateBy { variant -> variant.id },
                            currentState.modifiers.associateBy { modifier -> modifier.id },
                        )
                    }
                    cartPricingReviewReason(
                        cart,
                        currentState.items.associateBy { it.id },
                        currentState.variants.associateBy { it.id },
                        currentState.modifierGroups.associateBy { it.id },
                        currentState.modifiers.associateBy { it.id },
                    )?.let { reason -> throw IllegalStateException(reason) }
                    val currentDue = (
                        current.order.estimateMinor - current.order.manualDiscountMinor
                    ).coerceAtLeast(0L)
                    if (current.lines.isEmpty() || currentDue != confirmation.dueMinor) {
                        throw IllegalStateException(
                            "The amount changed after the payment screen opened. Review the cart before collecting.",
                        )
                    }
                    var captured = 0
                    if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                            captured = db.orderDao().captureOfflineDraft(
                                localId = confirmation.localId,
                                expectedRevision = confirmation.revision,
                                expectedDueMinor = confirmation.dueMinor,
                                method = method,
                                tenderedMinor = tenderedMinor.takeIf { method == "cash" } ?: 0L,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    ) return@withLock
                    check(captured == 1) {
                        "The cart changed before payment could be saved. Review it again; no payment was recorded."
                    }
                    notice.value = "Offline payment saved once as a provisional sale. Do not collect again; it will reconcile automatically after reconnecting."
                    watchCapturedSaleOutcome(confirmation.localId)
                    app.sync.requestSync()
                }
            } catch (e: Exception) {
                notice.value = e.shiftClosingMessageOr(
                    e.message ?: "The offline payment was not saved. Keep the cart and try again.",
                )
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    /**
     * Re-queues exactly the captured row after a manager has fixed the refusal.
     * The guarded DAO update preserves the local UUID used by both order and
     * payment idempotency keys, so this action can never create a second sale.
     */
    fun retryRejectedSale(localId: String) {
        if (!requireWrite()) return
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before retrying this failed sale. Do not collect payment again."
            return
        }
        if (localId in retryingRejectedSaleIds.value) return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        retryingRejectedSaleIds.update { it + localId }
        viewModelScope.launch {
            var moved = 0
            val committed = try {
                app.cacheIsolation.commitIfCurrent(scopeLease) {
                    moved = db.orderDao().retryRejected(localId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                notice.value = e.shiftClosingMessageOr(
                    "The tablet could not queue this retry. The failed sale is still " +
                        "saved; do not collect payment again.",
                )
                retryingRejectedSaleIds.update { it - localId }
                return@launch
            }
            if (!committed) return@launch
            if (moved == 0) {
                notice.value = "This failed sale has already changed state. Refresh and review it; " +
                    "do not collect payment again."
                retryingRejectedSaleIds.update { it - localId }
                return@launch
            }
            notice.value = "The original saved sale is queued for confirmation using the same " +
                "sale identity. Do not collect payment again."
            watchRetriedSaleOutcome(localId)
            try {
                // A refusal becomes visible while a sync pass may still be
                // draining other rows. Wait for that pass before starting the
                // human-requested replay, or SyncEngine's single-flight guard
                // would correctly drop the overlapping call.
                app.sync.syncing.first { syncing -> !syncing }
                app.sync.sync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice.value = "The original sale remains saved and is still awaiting server " +
                    "confirmation. Do not collect payment again; ask a manager to check the pending queue."
            } finally {
                retryingRejectedSaleIds.update { it - localId }
            }
        }
    }

    private fun watchCapturedSaleOutcome(localId: String) {
        capturedSaleOutcomeJob?.cancel()
        capturedSaleOutcomeJob = launchDirectSaleOutcomeObserver(localId)
    }

    private fun watchRetriedSaleOutcome(localId: String) {
        retriedSaleOutcomeJob?.cancel()
        retriedSaleOutcomeJob = launchDirectSaleOutcomeObserver(localId)
    }

    private fun launchDirectSaleOutcomeObserver(localId: String): Job =
        viewModelScope.launch {
            val outcome = db.orderDao().observeByLocalId(localId)
                .filterNotNull()
                .first { row -> row.syncState == SyncState.SYNCED || row.syncState == SyncState.REJECTED }
            directSaleOutcomeNotice(outcome)?.let { message -> notice.value = message }
        }

    /** Load the complete live bill before reserving it for checkout. */
    fun prepareHeldOrderReview(order: HeldOrderCacheEntity) {
        if (!requireWrite()) return
        val shiftIdAtReview = authorisedShiftId() ?: return
        if (heldSelectionBlocked.value) return
        if (!app.connectivity.online.value) {
            notice.value = "This shared order may have changed on another device. " +
                "Reconnect before reviewing its exact items and balance."
            return
        }
        if (
            checkoutBusy.value || heldOrderReview.value != null ||
            preparedHeldCheckout.value != null
        ) return
        val generation = ++heldReviewGeneration
        checkoutBusy.value = true
        preparingHeldOrderId.value = order.id
        viewModelScope.launch {
            try {
                val liveOrder = ApiClient.api.order(order.id)
                check(liveOrder.id == order.id) { "The server returned a different held bill." }
                if (generation != heldReviewGeneration) return@launch
                if (authorisedShiftId() != shiftIdAtReview) {
                    notice.value =
                        "The open shift changed while this bill was loading. Select it again under the correct shift."
                    return@launch
                }
                heldOrderReview.value = authoritativeHeldOrderReview(
                    order = liveOrder,
                    shiftIdAtReview = shiftIdAtReview,
                    fallbackSourceLabel = order.sourceLabel ?: order.invoiceNo,
                )
            } catch (e: ApiException) {
                notice.value = e.message
                    ?: "The live held bill could not be loaded. No checkout or payment was started."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice.value = error.message
                    ?: "The live held bill could not be loaded. No checkout or payment was started."
            } finally {
                preparingHeldOrderId.value = null
                checkoutBusy.value = false
            }
        }
    }

    fun dismissHeldOrderReview() {
        heldReviewGeneration += 1L
        heldOrderReview.value = null
    }

    /**
     * Absolute, versioned discount sets are safe to retry after an ambiguous
     * response: the same review/version/value always produces the same key.
     */
    fun updateHeldOrderDiscount(orderId: String, manualDiscountMinor: Long) {
        if (!requireWrite() || !requireDiscount()) return
        val review = heldOrderReview.value?.takeIf { it.orderId == orderId } ?: run {
            notice.value = "Reload the held bill before changing its discount."
            return
        }
        if (checkoutBusy.value) return
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before changing this shared bill's discount."
            return
        }
        val shiftId = authorisedShiftId() ?: return
        if (shiftId != review.shiftIdAtReview) {
            dismissHeldOrderReview()
            notice.value = "The open shift changed. Reload the bill before changing its discount."
            return
        }
        if (manualDiscountMinor == review.manualDiscountMinor) return
        if (review.paidMinor > 0L) {
            notice.value = "A manual discount cannot be changed after payment has been recorded on this bill."
            return
        }
        val mutation = try {
            heldOrderDiscountMutation(review, manualDiscountMinor)
        } catch (error: IllegalArgumentException) {
            notice.value = error.message
            return
        }

        checkoutBusy.value = true
        viewModelScope.launch {
            try {
                val updated = ApiClient.api.updateOrderDiscount(
                    id = review.orderId,
                    body = mutation.body,
                    idempotencyKey = mutation.idempotencyKey,
                )
                check(updated.id == review.orderId) {
                    "The server returned a different bill after changing the discount."
                }
                val refreshed = authoritativeHeldOrderReview(
                    order = updated,
                    shiftIdAtReview = review.shiftIdAtReview,
                    fallbackSourceLabel = review.sourceLabel,
                )
                if (authorisedShiftId() != review.shiftIdAtReview) {
                    heldOrderReview.compareAndSet(review, null)
                    notice.value = "The discount was confirmed by the server, but the open shift changed. " +
                        "Reload the bill before payment."
                    return@launch
                }
                if (!heldOrderReview.compareAndSet(review, refreshed)) return@launch
                notice.value = if (refreshed.manualDiscountMinor == 0L) {
                    "Manual discount cleared. Review the refreshed total before payment."
                } else {
                    "Manual discount ${refreshed.manualDiscountMinor.asRupees()} applied. " +
                        "Review the refreshed total before payment."
                }
                refreshHeldOrdersBestEffort()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                val refreshed = !error.isAmbiguous && refreshHeldReviewBestEffort(review)
                notice.value = if (error.isAmbiguous) {
                    "The discount response was interrupted. Do not continue to payment. " +
                        "Tap the same Apply or Clear action again to safely confirm its result."
                } else {
                    error.message ?: if (refreshed) {
                        "The discount was not changed. Review the refreshed bill."
                    } else {
                        "The discount was not changed. Cancel and reload the bill before payment."
                    }
                }
            } catch (_: Exception) {
                notice.value = "The discount response could not be confirmed. Do not continue to payment. " +
                    "Tap the same Apply or Clear action again to safely confirm its result."
            } finally {
                checkoutBusy.value = false
            }
        }
    }

    /**
     * Reserve only the exact version the cashier just reviewed. The claim is
     * deliberately acquired after discount editing, immediately before Pay.
     */
    fun prepareHeldOrderCheckout(orderId: String) {
        if (!requireWrite()) return
        val review = heldOrderReview.value?.takeIf { it.orderId == orderId } ?: run {
            notice.value = "Reload and review the held bill before payment."
            return
        }
        val shiftIdAtClaim = authorisedShiftId() ?: return
        if (shiftIdAtClaim != review.shiftIdAtReview) {
            dismissHeldOrderReview()
            notice.value = "The open shift changed. Reload and review the bill before payment."
            return
        }
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before payment so this exact reviewed bill can be reserved."
            return
        }
        if (checkoutBusy.value || preparedHeldCheckout.value != null) return
        abandonHeldPreparation = false
        heldClaimRequestInFlight = true
        checkoutBusy.value = true
        preparingHeldOrderId.value = orderId
        viewModelScope.launch {
            var acquiredToken: String? = null
            try {
                val checkoutClientInstance = CheckoutClientInstancePolicy.requireStable(
                    app.updateTelemetry.installation.installationId(),
                )
                val claim = ApiClient.api.acquireCheckoutClaim(
                    orderId,
                    checkoutClientInstance = checkoutClientInstance,
                )
                acquiredToken = claim.claimToken
                if (abandonHeldPreparation || heldOrderReview.value !== review) {
                    releaseClaimBestEffort(orderId, claim.claimToken)
                    acquiredToken = null
                    return@launch
                }
                if (authorisedShiftId() != shiftIdAtClaim) {
                    releaseClaimBestEffort(orderId, claim.claimToken)
                    acquiredToken = null
                    heldOrderReview.compareAndSet(review, null)
                    notice.value = "The open shift changed while checkout was reserved. " +
                        "Reload the bill before collecting."
                    return@launch
                }
                val terminalId = app.terminalStore.confirmedTerminalId()
                if (terminalId == null || claim.terminalId != terminalId) {
                    releaseClaimBestEffort(orderId, claim.claimToken)
                    acquiredToken = null
                    heldOrderReview.compareAndSet(review, null)
                    notice.value = "The checkout reservation belongs to a different or unverified terminal. " +
                        "Sign in again and reload the bill before collecting."
                    return@launch
                }
                val expiresAtMillis = HeldOrderClaimPolicy.claimExpiryMillis(claim)
                val hasTime = expiresAtMillis != null && HeldOrderClaimPolicy.hasConfirmationWindow(
                    expiresAtMillis,
                    System.currentTimeMillis(),
                )
                if (!hasTime || !heldClaimMatchesReview(review, claim)) {
                    releaseClaimBestEffort(orderId, claim.claimToken)
                    acquiredToken = null
                    val refreshed = refreshHeldReviewBestEffort(review)
                    if (!refreshed) heldOrderReview.compareAndSet(review, null)
                    notice.value = if (!hasTime) {
                        if (refreshed) {
                            "The checkout reservation was already too close to expiry. Review the refreshed bill and try again."
                        } else {
                            "The checkout reservation expired and the bill could not be reloaded. Select it again before payment."
                        }
                    } else {
                        if (refreshed) {
                            "The bill changed after review. Its latest breakdown is shown; review it again before payment."
                        } else {
                            "The bill changed after review and could not be reloaded. Select it again before payment."
                        }
                    }
                    return@launch
                }
                val prepared = PreparedHeldCheckout(
                    orderId = claim.orderId,
                    shiftIdAtClaim = shiftIdAtClaim,
                    sourceLabel = review.sourceLabel ?: review.invoiceNo,
                    subtotalMinor = review.subtotalMinor,
                    discountMinor = review.discountMinor,
                    manualDiscountMinor = review.manualDiscountMinor,
                    pointsRedeemedMinor = review.pointsRedeemedMinor,
                    pointsRedeemed = review.pointsRedeemed,
                    taxMinor = review.taxMinor,
                    roundOffMinor = review.roundOffMinor,
                    tipMinor = review.tipMinor,
                    totalMinor = claim.orderTotalMinor,
                    paidMinor = claim.paidMinor,
                    dueMinor = claim.dueMinor,
                    claimToken = claim.claimToken,
                    claimExpiresAtMillis = requireNotNull(expiresAtMillis),
                    claimOrderVersion = claim.orderVersion,
                )
                if (!heldOrderReview.compareAndSet(review, null)) {
                    releaseClaimBestEffort(orderId, claim.claimToken)
                    acquiredToken = null
                    return@launch
                }
                preparedHeldCheckout.value = prepared
            } catch (e: ApiException) {
                acquiredToken?.let { releaseClaimBestEffort(orderId, it) }
                notice.value = when (e.code) {
                    "checkout_claim_conflict" ->
                        "Another cashier is already billing this order. Do not collect it here."
                    else -> e.message ?: "The reviewed bill could not be reserved. Do not collect yet."
                }
            } catch (cancelled: CancellationException) {
                // A claim may already have been committed by the server when
                // the screen/view-model is cancelled. Release that exact
                // token before propagating cancellation so the order is not
                // left unavailable to every other cashier until TTL expiry.
                acquiredToken?.let { releaseClaimBestEffort(orderId, it) }
                throw cancelled
            } catch (failure: CheckoutClientInstanceUnavailableException) {
                acquiredToken?.let { releaseClaimBestEffort(orderId, it) }
                notice.value = failure.message
            } catch (_: Exception) {
                acquiredToken?.let { releaseClaimBestEffort(orderId, it) }
                notice.value = "The reviewed bill could not be reserved. Do not collect yet; review and try again."
            } finally {
                heldClaimRequestInFlight = false
                abandonHeldPreparation = false
                preparingHeldOrderId.value = null
                checkoutBusy.value = false
            }
        }
    }

    private suspend fun refreshHeldReviewBestEffort(review: HeldOrderReview): Boolean =
        try {
            val latest = ApiClient.api.order(review.orderId)
            check(latest.id == review.orderId) { "The server returned a different held bill." }
            if (latest.status != "held") {
                val closed = heldOrderReview.compareAndSet(review, null)
                refreshHeldOrdersBestEffort()
                closed
            } else {
                val refreshed = authoritativeHeldOrderReview(
                    order = latest,
                    shiftIdAtReview = review.shiftIdAtReview,
                    fallbackSourceLabel = review.sourceLabel,
                )
                val replaced = heldOrderReview.compareAndSet(review, refreshed)
                refreshHeldOrdersBestEffort()
                replaced
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The caller decides whether a failed reload may retain the old
            // review or must close it after evidence of a claim mismatch.
            false
        }

    fun dismissHeldOrderCheckout() {
        if (
            heldClaimRequestInFlight && checkoutBusy.value &&
            preparedHeldCheckout.value == null &&
            preparingHeldOrderId.value != null
        ) {
            // The screen disappeared while POST /checkout-claim was in flight.
            // Do not cancel an ambiguous POST; release its returned token as
            // soon as the response arrives instead.
            abandonHeldPreparation = true
            return
        }
        // Once confirmation is saving, the claim belongs to that durable
        // settlement attempt. Navigation must not release it out from under
        // the SyncEngine; success/failure below owns cleanup from that point.
        if (checkoutBusy.value) return
        val prepared = preparedHeldCheckout.value ?: return
        preparedHeldCheckout.value = null
        viewModelScope.launch { releaseClaimBestEffort(prepared.orderId, prepared.claimToken) }
    }

    /**
     * Local insertion is the irreversible staff-confirmation boundary. From
     * this point the order stays hidden even if sync is ambiguous or rejected;
     * retries replay one stable idempotency key and never ask for money again.
     */
    fun confirmHeldOrderPayment(orderId: String, method: String, tenderedMinor: Long) {
        if (!requireWrite()) return
        val prepared = preparedHeldCheckout.value ?: return
        if (checkoutBusy.value) return
        if (!HeldCheckoutInteractionPolicy.confirmationTargetsPreparedOrder(orderId, prepared.orderId)) {
            if (preparedHeldCheckout.compareAndSet(prepared, null)) {
                viewModelScope.launch {
                    releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                }
            }
            notice.value = "The selected bill changed before confirmation. The dialog was closed " +
                "without saving payment; choose the intended order again."
            return
        }
        val currentShiftId = authorisedShiftId()
        if (currentShiftId == null || currentShiftId != prepared.shiftIdAtClaim) {
            preparedHeldCheckout.value = null
            viewModelScope.launch {
                releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
            }
            if (currentShiftId != null) {
                notice.value =
                    "The drawer shift changed after this bill was checked. Review it again before collecting."
            }
            return
        }
        val terminalIdAtConfirmation = app.terminalStore.confirmedTerminalId()
        if (terminalIdAtConfirmation == null) {
            preparedHeldCheckout.value = null
            viewModelScope.launch {
                releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
            }
            notice.value = "This tablet's workspace is no longer verified. Nothing was saved; " +
                "sign in online and review the bill again before collecting."
            return
        }
        if (!app.connectivity.online.value) {
            // The dialog's atomic one-shot gate has already been consumed.
            // Close this preparation so a connection race cannot leave an
            // undismissable payment dialog on screen.
            preparedHeldCheckout.compareAndSet(prepared, null)
            notice.value = "Connection was lost before confirmation. Do not collect yet; reconnect first."
            return
        }
        if (!HeldOrderClaimPolicy.hasConfirmationWindow(
                prepared.claimExpiresAtMillis,
                System.currentTimeMillis(),
            )
        ) {
            preparedHeldCheckout.value = null
            notice.value = "The checkout hold expired before confirmation. It is being refreshed; " +
                "review the amount again before collecting."
            val current = state.value.heldOrders.firstOrNull { it.id == prepared.orderId }
            if (current != null) prepareHeldOrderReview(current) else refresh()
            return
        }
        if (method !in setOf("cash", "upi", "card")) {
            notice.value = "Choose a supported payment method."
            return
        }
        if (prepared.dueMinor <= 0L || (method == "cash" && tenderedMinor < prepared.dueMinor)) {
            notice.value = "The payment amount is invalid. Review the live total before confirming."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        // These writes are synchronous and precede the coroutine launch: a
        // second callback cannot bind itself to another order, and the second
        // pointer-up cannot click through onto the newly shifted held list.
        confirmingHeldOrderId.value = prepared.orderId
        startHeldSelectionTapGuard()
        checkoutBusy.value = true
        val payment = LocalHeldOrderPaymentEntity(
            localId = UUID.randomUUID().toString(),
            targetOrderId = prepared.orderId,
            method = method,
            amountMinor = prepared.dueMinor,
            tenderedMinor = tenderedMinor.takeIf { method == "cash" },
            expectedTotalMinor = prepared.totalMinor,
            expectedDueMinor = prepared.dueMinor,
            claimToken = prepared.claimToken,
            claimExpiresAtMillis = prepared.claimExpiresAtMillis,
            claimOrderVersion = prepared.claimOrderVersion,
            shiftId = prepared.shiftIdAtClaim,
            terminalId = terminalIdAtConfirmation,
            createdAtMillis = System.currentTimeMillis(),
            syncState = HeldOrderPaymentState.PENDING,
        )
        viewModelScope.launch {
            try {
                var inserted = -1L
                var existing: LocalHeldOrderPaymentEntity? = null
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        inserted = db.heldOrderDao().insertPayment(payment)
                        if (inserted == -1L) {
                            existing = db.heldOrderDao().paymentForTarget(prepared.orderId)
                        }
                    }
                ) return@launch
                preparedHeldCheckout.value = null
                if (inserted == -1L) {
                    releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                    notice.value = duplicateHeldPaymentNotice(existing)
                } else {
                    notice.value = "Payment saved once on this tablet. The server has not " +
                        "confirmed it yet; confirming the same settlement now. Do not collect again."
                    watchCapturedHeldPaymentOutcome(payment.localId)
                    requestHeldPaymentSyncAfterActivePass()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                preparedHeldCheckout.value = null
                releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                notice.value = e.shiftClosingMessageOr(
                    "The tablet could not save this confirmation. Do not retry or " +
                        "collect again until a manager checks the order and tablet storage.",
                )
            } finally {
                confirmingHeldOrderId.compareAndSet(prepared.orderId, null)
                checkoutBusy.value = false
            }
        }
    }

    /**
     * Completes a member-benefit bill whose authoritative total is exactly
     * zero. No payment row is created because no money changed hands; the
     * server atomically consumes the benefit, issues the invoice, and consumes
     * the same exclusive checkout claim used by normal held-order payment.
     */
    fun confirmHeldOrderZero(orderId: String) {
        if (!requireWrite()) return
        val prepared = preparedHeldCheckout.value ?: return
        if (checkoutBusy.value) return
        if (!HeldCheckoutInteractionPolicy.confirmationTargetsPreparedOrder(orderId, prepared.orderId)) {
            if (preparedHeldCheckout.compareAndSet(prepared, null)) {
                viewModelScope.launch {
                    releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                }
            }
            notice.value = "The selected benefit bill changed before confirmation. Nothing was " +
                "completed; choose the intended order again."
            return
        }
        val currentShiftId = authorisedShiftId()
        if (currentShiftId == null || currentShiftId != prepared.shiftIdAtClaim) {
            preparedHeldCheckout.value = null
            viewModelScope.launch {
                releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
            }
            if (currentShiftId != null) {
                notice.value = "The drawer shift changed after this benefit bill was checked. " +
                    "Review it again before completing."
            }
            return
        }
        if (!app.connectivity.online.value) {
            // The UI one-shot gate is already consumed. Close this exact
            // preparation rather than trapping staff in a disabled dialog;
            // no finalization request has been sent at this point.
            preparedHeldCheckout.compareAndSet(prepared, null)
            notice.value = "Connection was lost. Reconnect before completing this member benefit; " +
                "it must be confirmed by the server."
            return
        }
        if (!HeldOrderClaimPolicy.hasConfirmationWindow(
                prepared.claimExpiresAtMillis,
                System.currentTimeMillis(),
            )
        ) {
            preparedHeldCheckout.value = null
            notice.value = "The checkout hold expired before completion. The bill is being " +
                "refreshed; review the member benefit again."
            val current = state.value.heldOrders.firstOrNull { it.id == prepared.orderId }
            if (current != null) prepareHeldOrderReview(current) else refresh()
            return
        }
        if (!HeldOrderClaimPolicy.isExactZeroTotal(
                totalMinor = prepared.totalMinor,
                paidMinor = prepared.paidMinor,
                dueMinor = prepared.dueMinor,
            )
        ) {
            preparedHeldCheckout.value = null
            viewModelScope.launch {
                releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
            }
            notice.value = "Only a never-paid bill with an exact ₹0.00 total can be completed as " +
                "a member benefit. The order was refreshed; collect no money until you review it."
            refresh()
            return
        }

        // Synchronous state is the ViewModel-level one-shot boundary. The
        // dialog has its own atomic gate, so rapid queued callbacks are also
        // rejected before they reach here.
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        confirmingHeldOrderId.value = prepared.orderId
        startHeldSelectionTapGuard()
        checkoutBusy.value = true
        val idempotencyKey = HeldOrderClaimPolicy.zeroFinalizationIdempotencyKey(
            prepared.orderId,
            prepared.claimOrderVersion,
        )
        viewModelScope.launch {
            var acceptedInvoiceNo: String? = null
            try {
                val result = try {
                    ApiClient.api.finalizeZeroTotalOrder(
                        id = prepared.orderId,
                        idempotencyKey = idempotencyKey,
                        checkoutClaimToken = prepared.claimToken,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: ApiException) {
                    // Close the in-memory payable state before releasing a
                    // definitively refused claim. Ambiguous POST outcomes keep
                    // the server bearer for safe idempotent reconciliation.
                    preparedHeldCheckout.compareAndSet(prepared, null)
                    if (!e.isAmbiguous) {
                        releaseClaimBestEffort(prepared.orderId, prepared.claimToken)
                    }
                    notice.value = if (e.isAmbiguous) {
                        "The server reply was interrupted, so completion is not yet confirmed on this " +
                            "tablet. Collect no money and do not create another bill. Reconnect and " +
                            "refresh; retrying this same order is protected from duplicates."
                    } else {
                        e.message ?: "The member benefit could not be completed. Nothing was charged; " +
                            "refresh the bill and try again."
                    }
                    refreshHeldOrdersBestEffort()
                    return@launch
                } catch (_: Exception) {
                    // A transport/decoding failure may follow a committed
                    // invoice. Never release the claim or assert failure.
                    preparedHeldCheckout.compareAndSet(prepared, null)
                    notice.value = "Completion could not be confirmed on this tablet. Collect no " +
                        "money and do not create another bill. Reconnect and refresh the POS queue."
                    refreshHeldOrdersBestEffort()
                    return@launch
                }

                if (
                    result.orderId != prepared.orderId || result.amountMinor != 0L ||
                    result.orderStatus != "paid" || result.invoiceNo.isNullOrBlank()
                ) {
                    preparedHeldCheckout.compareAndSet(prepared, null)
                    notice.value = "The server returned incomplete completion evidence. Collect no " +
                        "money; refresh the POS queue before taking another action."
                    refreshHeldOrdersBestEffort()
                    return@launch
                }

                // The POST response is authoritative completion evidence. Close
                // the payment UI before the optional detail GET so a later read
                // failure cannot misclassify the accepted invoice as refused.
                acceptedInvoiceNo = result.invoiceNo
                preparedHeldCheckout.compareAndSet(prepared, null)
                val receiptSaved = try {
                    val finalOrder = ApiClient.api.order(prepared.orderId)
                    app.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.posReceiptDao().store(
                            zeroTotalReceipt(
                                order = finalOrder,
                                result = result,
                                sourceKind = PosReceiptSource.ZERO_HELD,
                                sourceLabel = prepared.sourceLabel,
                                shiftId = prepared.shiftIdAtClaim,
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                notice.value = if (receiptSaved) {
                    "Member benefit completed once · ${result.invoiceNo} · ₹0.00 due. " +
                        "No money was collected."
                } else {
                    "Member benefit completed once · ${result.invoiceNo} · ₹0.00 due. " +
                        "No money was collected. The detailed receipt is still syncing."
                }
                refreshHeldOrdersBestEffort()
                app.sync.refresh("receipts")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preparedHeldCheckout.compareAndSet(prepared, null)
                notice.value = acceptedInvoiceNo?.let { invoiceNo ->
                    "Member benefit $invoiceNo was completed on the server. No money was collected. " +
                        "The detailed receipt is still syncing."
                } ?: "Completion could not be confirmed on this tablet. Collect no money and " +
                    "refresh the POS queue before taking another action."
                refreshHeldOrdersBestEffort()
            } finally {
                confirmingHeldOrderId.compareAndSet(prepared.orderId, null)
                checkoutBusy.value = false
            }
        }
    }

    private suspend fun refreshHeldOrdersBestEffort() {
        try {
            app.sync.refresh("orders")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The settlement outcome above is authoritative. A local cache
            // refresh failure must not rewrite a confirmed success as an
            // ambiguous money outcome; realtime/manual refresh can retry it.
        }
    }

    private fun startHeldSelectionTapGuard() {
        heldSelectionBlocked.value = true
        heldSelectionGuardJob?.cancel()
        heldSelectionGuardJob = viewModelScope.launch {
            delay(HeldCheckoutInteractionPolicy.POST_CONFIRM_TAP_GUARD_MILLIS)
            heldSelectionBlocked.value = false
        }
    }

    /**
     * A rejected held settlement still represents money already taken. Replay
     * only the original row after a manager fixes the cause; never create a
     * replacement payment or put the source order back in the collection list.
     */
    fun retryRejectedHeldPayment(localId: String) {
        if (!requireWrite()) return
        if (!app.connectivity.online.value) {
            notice.value = "Reconnect before retrying this saved held-order payment. " +
                "Do not collect payment again."
            return
        }
        if (localId in retryingHeldPaymentIds.value) return
        val scopeLease = app.cacheIsolation.currentLease() ?: return
        retryingHeldPaymentIds.update { it + localId }
        viewModelScope.launch {
            var moved = 0
            val committed = try {
                app.cacheIsolation.commitIfCurrent(scopeLease) {
                    moved = db.heldOrderDao().retryRejectedPayment(localId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice.value = "The tablet could not queue this reconciliation. The original " +
                    "payment is still saved; do not collect again."
                retryingHeldPaymentIds.update { it - localId }
                return@launch
            }
            if (!committed) return@launch
            if (moved == 0) {
                notice.value = "This held-order payment has already changed state. Review its " +
                    "current status; do not collect again."
                retryingHeldPaymentIds.update { it - localId }
                return@launch
            }
            notice.value = "The original held-order payment is queued with the same safe payment " +
                "identity. Do not collect again."
            watchRetriedHeldPaymentOutcome(localId)
            requestHeldPaymentSyncAfterActivePass()
            retryingHeldPaymentIds.update { it - localId }
        }
    }

    private fun watchCapturedHeldPaymentOutcome(localId: String) {
        capturedHeldPaymentOutcomeJob?.cancel()
        capturedHeldPaymentOutcomeJob = launchHeldPaymentOutcomeObserver(localId)
    }

    private fun watchRetriedHeldPaymentOutcome(localId: String) {
        retriedHeldPaymentOutcomeJob?.cancel()
        retriedHeldPaymentOutcomeJob = launchHeldPaymentOutcomeObserver(localId)
    }

    private fun launchHeldPaymentOutcomeObserver(localId: String): Job =
        viewModelScope.launch {
            val outcome = db.heldOrderDao().observePayment(localId)
                .filterNotNull()
                .first { row ->
                    row.syncState == HeldOrderPaymentState.SYNCED ||
                        row.syncState == HeldOrderPaymentState.REJECTED
                }
            heldPaymentOutcomeNotice(outcome)?.let { message -> notice.value = message }
        }

    private fun requestHeldPaymentSyncAfterActivePass() {
        viewModelScope.launch {
            try {
                // If capture completed during an existing pass, wait until its
                // single-flight lock is released so this new durable row is not
                // left behind merely because an overlapping request was dropped.
                app.sync.syncing.first { syncing -> !syncing }
                app.sync.sync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice.value = "This payment remains saved and is awaiting server confirmation. " +
                    "Do not collect again; keep the tablet online and review its POS status."
            }
        }
    }

    private suspend fun releaseClaimBestEffort(orderId: String, token: String) {
        try {
            ApiClient.api.releaseCheckoutClaim(orderId, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A checkout claim has a short server TTL. Failure to release it is
            // temporary and must not replace the more important payment state.
        }
    }

    /** Re-check at the action boundary; a dialog may outlive a profile/shift update. */
    private fun authorisedShiftId(): String? {
        val resolved = resolvedShift.value
        if (resolved == null) {
            notice.value = "No shift is open. Open Shift before billing."
            return null
        }
        val actor = app.shiftCache.profile.value?.let {
            ShiftActor(it.userId, it.protectedAccess)
        }
        if (!resolved.canManageMoney(actor)) {
            notice.value = resolved.moneyAccessMessage(actor)
                ?: "Only the shift opener or a protected owner can collect POS payment."
            return null
        }
        return resolved.shiftId
    }
}

/** Small helper so cart edits read as one expression. */
private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
    value = block(value)
}

/** Stable across an ambiguous retry, distinct for every versioned absolute set. */
internal fun pointsRedemptionIdempotencyKey(
    localId: String,
    orderId: String,
    checkoutVersion: Long,
    points: Int,
): String {
    require(localId.isNotBlank() && orderId.isNotBlank())
    require(checkoutVersion >= 1L && points >= 0)
    return "order-points:$localId:$orderId:$checkoutVersion:$points"
}

/** Kept pure so confirmation/refusal wording is regression-testable on the JVM. */
internal fun directSaleOutcomeNotice(order: LocalOrderEntity): String? = when (order.syncState) {
    SyncState.SYNCED -> {
        val invoice = order.invoiceNo?.takeIf(String::isNotBlank)?.let { " Invoice $it." } ?: ""
        "Sale confirmed by the server.$invoice Total " +
            "${(order.serverTotalMinor ?: order.estimateMinor).asRupees()}. No further action is needed."
    }
    SyncState.REJECTED -> {
        val reason = order.lastError?.takeIf(String::isNotBlank)
            ?: "The server refused this sale without an explanation."
        "Sale saved, but the server refused it: $reason Do not collect payment again. " +
            "Fix the cause, then use Retry after fix on this failed sale."
    }
    else -> null
}

/**
 * Keep backend implementation failures out of the cashier workflow. A generic
 * 5xx envelope such as "An unexpected error occurred" gives staff neither a
 * recovery action nor the financially important confirmation that no payment
 * was captured. Specific business-rule messages remain intact.
 */
internal fun directCheckoutPreparationNotice(error: Exception): String {
    if (error is ApiException) {
        val serverMessage = error.message?.trim().orEmpty()
        val genericServerFailure = error.status?.let { it >= 500 } == true ||
            serverMessage.equals("An unexpected error occurred.", ignoreCase = true) ||
            serverMessage.equals("Internal server error", ignoreCase = true)
        if (!genericServerFailure && serverMessage.isNotEmpty()) return serverMessage
        return "The server could not prepare this bill. No payment was recorded. " +
            "Try again; if it continues, ask a manager to check the POS connection."
    }

    return error.message?.trim()?.takeIf(String::isNotEmpty)
        ?: "The bill could not be prepared. No payment was recorded; review it and try again."
}

/** Definitive feedback for money already captured against a shared held order. */
internal fun heldPaymentOutcomeNotice(payment: LocalHeldOrderPaymentEntity): String? =
    when (payment.syncState) {
        HeldOrderPaymentState.SYNCED ->
            "Held-order payment confirmed by the server. Total ${payment.amountMinor.asRupees()}. " +
                "No further collection is needed."
        HeldOrderPaymentState.REJECTED -> {
            val reason = payment.lastError?.takeIf(String::isNotBlank)
                ?: "The server refused this payment without an explanation."
            "Held-order payment saved, but the server refused it: $reason Do not collect again. " +
                "Fix the cause, then use Retry after fix on the saved payment."
        }
        else -> null
    }

/** A unique target-order constraint turns every repeated confirmation into this safe status lookup. */
internal fun duplicateHeldPaymentNotice(payment: LocalHeldOrderPaymentEntity?): String =
    when (payment?.syncState) {
        HeldOrderPaymentState.PENDING ->
            "This payment is already saved once and is still awaiting server confirmation. " +
                "Do not collect again."
        HeldOrderPaymentState.SYNCED ->
            "This payment was already confirmed by the server. Do not collect again."
        HeldOrderPaymentState.REJECTED -> {
            val reason = payment.lastError?.takeIf(String::isNotBlank)
                ?: "The server refused it without an explanation."
            "This payment is already saved, but needs manager reconciliation: $reason " +
                "Do not collect again."
        }
        else ->
            "A payment for this order is already saved on this tablet. Do not collect again; " +
                "review the saved-payment status."
    }
