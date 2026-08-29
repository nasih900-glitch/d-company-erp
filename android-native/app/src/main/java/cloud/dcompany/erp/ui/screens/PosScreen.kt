package cloud.dcompany.erp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuModifierGroupEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.db.PosReceiptEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.db.decodedLines
import cloud.dcompany.erp.core.checkout.HeldOrderClaimPolicy
import cloud.dcompany.erp.core.checkout.OneShotHeldPaymentConfirmation
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.NumericValue
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.PrimaryButton
import cloud.dcompany.erp.ui.components.SearchInput
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.TouchMoneyEntry
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.VoidReasonInput
import cloud.dcompany.erp.ui.components.resolvedVoidReason
import cloud.dcompany.erp.ui.screens.gaming.OperationalAlarmPermissionCard
import cloud.dcompany.erp.ui.screens.customers.MINOR_PER_POINT
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Motion
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PosScreen(
    state: PosUiState,
    recentReceipts: List<PosReceiptEntity>,
    unacknowledgedReceipt: PosReceiptEntity?,
    access: PosAccess,
    onAccessChanged: (PosAccess) -> Unit,
    onAdd: (MenuItemEntity) -> Unit,
    onAddConfigured: (
        MenuItemEntity,
        MenuVariantEntity?,
        List<CartModifierSelection>,
        String?,
    ) -> Unit,
    onRemove: (MenuItemEntity) -> Unit,
    onIncrementLine: (String) -> Unit,
    onDecrementLine: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClearCart: () -> Unit,
    onUpdateDraftDetails: (String?, String?, String?, Long) -> Unit,
    onRefresh: () -> Unit,
    onPrepareDirectCheckout: () -> Unit,
    onDismissDirectCheckout: () -> Unit,
    onConfirmDirectZero: () -> Unit,
    onRedeemDirectPoints: (Int) -> Unit,
    onCapture: (String, Long, DirectPaymentConfirmation) -> Unit,
    onRetryRejectedSale: (String) -> Unit,
    onRetryHeldPayment: (String) -> Unit,
    onPrepareHeldOrder: (HeldOrderCacheEntity) -> Unit,
    onConfirmHeldOrder: (String, String, Long) -> Unit,
    onConfirmHeldOrderZero: (String) -> Unit,
    onVoidOrder: (String, String) -> Unit,
    onDismissHeldOrder: () -> Unit,
    onDismissNotice: () -> Unit,
    onAcknowledgeReceipt: (String) -> Unit,
    onFocusOldestOverdue: () -> Unit,
    onSnoozeOverdue: () -> Unit,
    onUnmuteOverdue: () -> Unit,
    onDismissHeldFocus: () -> Unit,
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    var offlinePaymentConfirmation by remember { mutableStateOf<DirectPaymentConfirmation?>(null) }
    var showStatusDetails by rememberSaveable { mutableStateOf(false) }
    var showHeldOrders by rememberSaveable { mutableStateOf(false) }
    var showOrderDetails by rememberSaveable { mutableStateOf(false) }
    var configuringItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var voidTarget by remember { mutableStateOf<PosVoidTarget?>(null) }
    var selectedReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenAutomaticReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuQuery by rememberSaveable { mutableStateOf("") }
    val latestDismissHeldOrder by rememberUpdatedState(onDismissHeldOrder)
    val categoryItems = remember(state.items, state.selectedCategoryId) {
        state.selectedCategoryId?.let { selected ->
            state.items.filter { it.categoryId == selected }
        } ?: state.items
    }
    val searchedItems = remember(categoryItems, menuQuery) {
        filterPosMenuItems(categoryItems, menuQuery)
    }
    val overdueOrderIds = remember(state.overdueHeldOrderIds) {
        state.overdueHeldOrderIds.toSet()
    }
    val heldOrderIds = remember(state.heldOrders) {
        state.heldOrders.mapTo(mutableSetOf()) { it.id }
    }
    val hasOperationalAlerts = hasPosOperationalAlerts(state)
    val configuringItem = state.items.firstOrNull { it.id == configuringItemId }
    val heldOrderBlockReason = heldOrderSelectionBlockReason(state, access)
    val visibleReceipt = unacknowledgedReceipt
        ?.takeUnless { it.receiptId == hiddenAutomaticReceiptId }
        ?: selectedReceiptId?.let { id -> recentReceipts.firstOrNull { it.receiptId == id } }
    val addOrConfigure: (MenuItemEntity) -> Unit = { item ->
        val configurable = state.variants.any { it.menuItemId == item.id && it.isActive } ||
            state.modifierGroups.any { it.menuItemId == item.id && it.isActive }
        if (configurable) configuringItemId = item.id else onAdd(item)
    }

    LaunchedEffect(access) { onAccessChanged(access) }

    LaunchedEffect(access.canCreateAndCollect, state.preparedHeldCheckout?.orderId) {
        if (!access.canCreateAndCollect) {
            offlinePaymentConfirmation = null
            if (state.preparedHeldCheckout != null) onDismissHeldOrder()
        }
    }

    LaunchedEffect(state.online) {
        if (state.online) offlinePaymentConfirmation = null
    }

    LaunchedEffect(
        voidTarget?.orderId,
        state.preparedDirectCheckout?.orderId,
        state.preparedHeldCheckout?.orderId,
        state.checkoutBusy,
    ) {
        val target = voidTarget ?: return@LaunchedEffect
        if (
            shouldDismissPosVoidTarget(
                targetOrderId = target.orderId,
                preparedDirectOrderId = state.preparedDirectCheckout?.orderId,
                preparedHeldOrderId = state.preparedHeldCheckout?.orderId,
                checkoutBusy = state.checkoutBusy,
            )
        ) {
            voidTarget = null
        }
    }

    val requestDirectPayment: () -> Unit = {
        if (state.online) {
            onPrepareDirectCheckout()
        } else if (
            state.draftState == SyncState.DRAFT &&
            state.draftLocalId != null &&
            state.draftRevision != null
        ) {
            offlinePaymentConfirmation = DirectPaymentConfirmation(
                localId = state.draftLocalId,
                revision = state.draftRevision,
                dueMinor = state.estimatedDueMinor,
            )
        }
    }

    LaunchedEffect(state.focusedHeldOrderId, heldOrderIds) {
        if (shouldRevealHeldOrderQueue(state.focusedHeldOrderId, heldOrderIds)) {
            showHeldOrders = true
        }
    }

    LaunchedEffect(heldOrderIds) {
        if (heldOrderIds.isEmpty()) showHeldOrders = false
    }

    // Navigation, logout, or an Activity recreation can remove this screen
    // without the dialog's Cancel button running. Release the short-lived
    // lease in that case so another cashier is not blocked for its full TTL.
    DisposableEffect(Unit) {
        onDispose { latestDismissHeldOrder() }
    }

    Column(Modifier.fillMaxSize()) {
        if (!access.canCreateAndCollect) {
            ViewOnlyNotice("POS is view only — ask a cashier or manager to create or collect an order.")
        }
        // Keep one short, stable-height entry point for every operational
        // warning. Permission, sync and held-order cards live in dialogs so
        // their variable height can never displace the selling workspace.
        PosContextBar(
            state = state,
            onOpenStatus = { showStatusDetails = true },
            onOpenHeldOrders = { showHeldOrders = true },
            onOpenLastReceipt = recentReceipts.firstOrNull()?.let { receipt ->
                { selectedReceiptId = receipt.receiptId }
            },
        )

        if (state.menuEmpty) {
            EmptyMenuPanel(
                everSynced = state.everSynced,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f).padding(Spacing.md),
            )
            return@Column
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(Spacing.md),
        ) {
            val workspace = remember(maxWidth) {
                posWorkspaceMetrics(maxWidth = maxWidth, horizontalGap = Spacing.md)
            }
            if (workspace.sideBySide) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    ProductCatalogPanel(
                        categories = state.categories,
                        items = state.items,
                        selectedCategoryId = state.selectedCategoryId,
                        visibleItems = searchedItems,
                        query = menuQuery,
                        canWrite = access.canCreateAndCollect,
                        onQueryChange = { menuQuery = it },
                        onClearSearch = { menuQuery = "" },
                        onSelectCategory = onSelectCategory,
                        onAdd = addOrConfigure,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CartPanel(
                        state = state,
                        canWrite = access.canCreateAndCollect,
                        canDiscount = access.canApplyDiscount,
                        onIncrementLine = onIncrementLine,
                        onDecrementLine = onDecrementLine,
                        onClear = onClearCart,
                        onEditDetails = { showOrderDetails = true },
                        modifier = Modifier.width(requireNotNull(workspace.cartWidth)).fillMaxHeight(),
                    ) { requestDirectPayment() }
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    ProductCatalogPanel(
                        categories = state.categories,
                        items = state.items,
                        selectedCategoryId = state.selectedCategoryId,
                        visibleItems = searchedItems,
                        query = menuQuery,
                        canWrite = access.canCreateAndCollect,
                        onQueryChange = { menuQuery = it },
                        onClearSearch = { menuQuery = "" },
                        onSelectCategory = onSelectCategory,
                        onAdd = addOrConfigure,
                        modifier = Modifier.weight(1.05f).fillMaxWidth(),
                    )
                    CartPanel(
                        state = state,
                        canWrite = access.canCreateAndCollect,
                        canDiscount = access.canApplyDiscount,
                        onIncrementLine = onIncrementLine,
                        onDecrementLine = onDecrementLine,
                        onClear = onClearCart,
                        onEditDetails = { showOrderDetails = true },
                        modifier = Modifier.weight(0.95f).fillMaxWidth(),
                    ) { requestDirectPayment() }
                }
            }
        }
    }

    configuringItem?.let { item ->
        ProductConfigurationDialog(
            item = item,
            variants = state.variants.filter { it.menuItemId == item.id && it.isActive },
            modifierGroups = state.modifierGroups.filter { it.menuItemId == item.id && it.isActive },
            modifiers = state.modifiers.filter { it.menuItemId == item.id && it.isActive },
            onDismiss = { configuringItemId = null },
            onAdd = { variant, modifiers, note ->
                configuringItemId = null
                onAddConfigured(item, variant, modifiers, note)
            },
        )
    }

    if (showOrderDetails && state.cart.isNotEmpty()) {
        OrderDetailsDialog(
            state = state,
            canDiscount = access.canApplyDiscount,
            presentation = presentation,
            onDismiss = { showOrderDetails = false },
            onSave = { name, phone, note, discount ->
                showOrderDetails = false
                onUpdateDraftDetails(name, phone, note, discount)
            },
        )
    }

    if (showStatusDetails) {
        AlertDialog(
            onDismissRequest = { showStatusDetails = false },
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            title = { Text("Status & recovery details") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item { OperationalAlarmPermissionCard(contextLabel = "POS") }
                    if (hasOperationalAlerts) {
                        item {
                            PosOperationalAlerts(
                                state = state,
                                canRetry = access.canCreateAndCollect,
                                onRefresh = onRefresh,
                                onRetryRejectedSale = onRetryRejectedSale,
                                onRetryHeldPayment = onRetryHeldPayment,
                                onFocusOldestOverdue = {
                                    showStatusDetails = false
                                    showHeldOrders = true
                                    onFocusOldestOverdue()
                                },
                                onSnoozeOverdue = onSnoozeOverdue,
                                onUnmuteOverdue = onUnmuteOverdue,
                            )
                        }
                    } else {
                        item {
                            Text(
                                "No connection, sync, shift-access or payment recovery issues are currently reported.",
                                color = Brand.ForegroundMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusDetails = false }) { Text("Close") }
            },
        )
    }

    if (showHeldOrders && state.heldOrders.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showHeldOrders = false },
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            title = {
                Text(
                    if (state.focusedHeldOrderId != null) {
                        "Highlighted held order"
                    } else {
                        "Held orders awaiting payment"
                    },
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item {
                        HeldOrdersStrip(
                            orders = state.heldOrders,
                            overdueOrderIds = overdueOrderIds,
                            focusedOrderId = state.focusedHeldOrderId,
                            preparingOrderId = state.preparingHeldOrderId,
                            enabled = heldOrderBlockReason == null,
                            blockedReason = heldOrderBlockReason,
                            onSelect = { order ->
                                showHeldOrders = false
                                onPrepareHeldOrder(order)
                            },
                            onDismissFocus = onDismissHeldFocus,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHeldOrders = false }) { Text("Close") }
            },
        )
    }

    offlinePaymentConfirmation?.takeIf { access.canCreateAndCollect && !state.online }?.let { quote ->
        PayDialog(
            dueMinor = quote.dueMinor,
            online = state.online,
            offlineAllowed = true,
            confirmEnabled = state.canCollectPayment && !state.checkoutBusy,
            confirmationIdentity = "${quote.localId}:${quote.revision}:${quote.dueMinor}",
            showCustomerBenefits = presentation.showsCustomers,
            onDismiss = { offlinePaymentConfirmation = null },
            onConfirm = { method, tendered ->
                offlinePaymentConfirmation = null
                onCapture(method, tendered, quote)
            },
        )
    }

    state.preparedDirectCheckout
        ?.takeIf { access.canCreateAndCollect && voidTarget == null }
        ?.let { checkout ->
        key(checkout.orderId, checkout.claimOrderVersion) {
            if (checkout.dueMinor == 0L && checkout.totalMinor == 0L) {
                DirectZeroTotalCompletionDialog(
                    checkout = checkout,
                    online = state.online,
                    confirmEnabled = state.canCollectPayment && !state.checkoutBusy,
                    showCustomerBenefits = presentation.showsCustomers,
                    onDismiss = onDismissDirectCheckout,
                    onVoid = if (access.canVoid) {
                        {
                            voidTarget = PosVoidTarget(
                                checkout.orderId,
                                "Direct POS bill ${checkout.orderId.take(8)}",
                            )
                        }
                    } else null,
                    onConfirm = onConfirmDirectZero,
                )
            } else {
                PayDialog(
                    dueMinor = checkout.dueMinor,
                    online = state.online,
                    offlineAllowed = false,
                    confirmEnabled = state.canCollectPayment && !state.checkoutBusy,
                    verifiedSharedOrder = true,
                    paymentSubject = "Direct POS bill",
                    confirmationIdentity = checkout.orderId,
                    subtotalMinor = checkout.subtotalMinor,
                    discountMinor = checkout.discountMinor,
                    taxMinor = checkout.taxMinor,
                    roundOffMinor = checkout.roundOffMinor,
                    totalMinor = checkout.totalMinor,
                    loyaltyPointsBalance = state.customerLoyaltyPoints,
                    pointsRedeemed = checkout.pointsRedeemed,
                    pointsRedeemedMinor = checkout.pointsRedeemedMinor,
                    showCustomerBenefits = presentation.showsCustomers,
                    onApplyPoints = onRedeemDirectPoints.takeIf {
                        presentation.showsCustomers && !state.customerPhone.isNullOrBlank()
                    },
                    onDismiss = onDismissDirectCheckout,
                    onVoid = if (access.canVoid) {
                        {
                            voidTarget = PosVoidTarget(
                                checkout.orderId,
                                "Direct POS bill ${checkout.orderId.take(8)}",
                            )
                        }
                    } else null,
                    onConfirm = { method, tendered ->
                        onCapture(
                            method,
                            tendered,
                            DirectPaymentConfirmation(
                                localId = checkout.localId,
                                revision = checkout.revision,
                                dueMinor = checkout.dueMinor,
                            ),
                        )
                    },
                )
            }
        }
    }

    state.preparedHeldCheckout
        ?.takeIf { access.canCreateAndCollect && voidTarget == null }
        ?.let { checkout ->
        // This key resets every remembered dialog field only when the actual
        // immutable order id changes. A list reorder/removal can never reuse
        // T1's UPI selection or callback for the next held order.
        key(checkout.orderId) {
            if (
                HeldOrderClaimPolicy.isExactZeroTotal(
                    totalMinor = checkout.totalMinor,
                    paidMinor = checkout.paidMinor,
                    dueMinor = checkout.dueMinor,
                )
            ) {
                ZeroTotalCompletionDialog(
                    checkout = checkout,
                    online = state.online,
                    confirmEnabled = state.canCollectPayment && !state.checkoutBusy,
                    onDismiss = onDismissHeldOrder,
                    onVoid = if (access.canVoid) {
                        {
                            voidTarget = PosVoidTarget(
                                checkout.orderId,
                                checkout.sourceLabel ?: "Held bill ${checkout.orderId.take(8)}",
                            )
                        }
                    } else null,
                    onConfirm = { onConfirmHeldOrderZero(checkout.orderId) },
                )
            } else {
                PayDialog(
                    dueMinor = checkout.dueMinor,
                    online = state.online,
                    offlineAllowed = false,
                    confirmEnabled = state.canCollectPayment && !state.checkoutBusy,
                    verifiedSharedOrder = true,
                    paymentSubject = checkout.sourceLabel,
                    confirmationIdentity = checkout.orderId,
                    showCustomerBenefits = presentation.showsCustomers,
                    onDismiss = onDismissHeldOrder,
                    onVoid = if (access.canVoid) {
                        {
                            voidTarget = PosVoidTarget(
                                checkout.orderId,
                                checkout.sourceLabel ?: "Held bill ${checkout.orderId.take(8)}",
                            )
                        }
                    } else null,
                    onConfirm = { method, tendered ->
                        onConfirmHeldOrder(checkout.orderId, method, tendered)
                    },
                )
            }
        }
    }

    voidTarget?.let { target ->
        PosVoidDialog(
            target = target,
            online = state.online,
            busy = state.checkoutBusy,
            onDismiss = { if (!state.checkoutBusy) voidTarget = null },
            onConfirm = { reason -> onVoidOrder(target.orderId, reason) },
        )
    }

    visibleReceipt?.let { receipt ->
        PosReceiptDialog(
            receipt = receipt,
            onDismiss = {
                hiddenAutomaticReceiptId = receipt.receiptId
                selectedReceiptId = null
                onAcknowledgeReceipt(receipt.receiptId)
            },
        )
    }

    state.notice?.takeIf { visibleReceipt == null }?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            confirmButton = { TextButton(onClick = onDismissNotice) { Text("OK") } },
            title = { Text("POS update") },
            text = { Text(message) },
        )
    }
}

private data class PosVoidTarget(val orderId: String, val label: String)

/**
 * A successful void removes the prepared checkout before the request's busy
 * flag is cleared. Both transitions must participate in the effect key or the
 * dismissed success notice can reveal a stale, repeatable void dialog.
 */
internal fun shouldDismissPosVoidTarget(
    targetOrderId: String?,
    preparedDirectOrderId: String?,
    preparedHeldOrderId: String?,
    checkoutBusy: Boolean,
): Boolean = targetOrderId != null &&
    !checkoutBusy &&
    targetOrderId != preparedDirectOrderId &&
    targetOrderId != preparedHeldOrderId

@Composable
private fun PosReceiptDialog(
    receipt: PosReceiptEntity,
    onDismiss: () -> Unit,
) {
    val lines = remember(receipt.linesJson) { receipt.decodedLines() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var printLaunching by remember(receipt.receiptId) { mutableStateOf(false) }
    var printError by remember(receipt.receiptId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(receipt.invoiceNo?.let { "Receipt · $it" } ?: "Payment receipt")
                Text(
                    receipt.sourceLabel ?: "POS order ${receipt.orderId.take(8)}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 470.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    OperationalBanner(
                        title = "Payment confirmed",
                        detail = buildString {
                            append(receipt.method.paymentMethodLabel())
                            receipt.paidAt?.let { append(" · $it") }
                        },
                        tone = UiTone.Success,
                        icon = Icons.Default.CheckCircle,
                    )
                }
                if (receipt.customerName != null || receipt.customerPhone != null) {
                    item {
                        Text(
                            listOfNotNull(receipt.customerName, receipt.customerPhone).joinToString(" · "),
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(lines, key = { it.id ?: it.clientLineId ?: "${it.menuItemId}:${it.name}" }) { line ->
                    Row(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised)
                            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                            .padding(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${line.qty.toInt().coerceAtLeast(1)} × ${line.name}",
                                color = Brand.Foreground,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            val options = buildList {
                                line.variantSnapshot?.name?.takeIf(String::isNotBlank)?.let(::add)
                                line.modifiers.orEmpty().forEach { modifier ->
                                    add(
                                        if (modifier.qty == 1) modifier.name
                                        else "${modifier.name} ×${modifier.qty}",
                                    )
                                }
                                line.note?.takeIf(String::isNotBlank)?.let { add("Note: $it") }
                            }.joinToString(" · ")
                            if (options.isNotBlank()) {
                                Text(
                                    options,
                                    color = Brand.ForegroundMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        NumericValue(
                            value = line.lineTotalMinor.asRupees(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised)
                            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        PaymentAmountRow("Subtotal", receipt.subtotalMinor)
                        if (receipt.discountMinor > 0L) {
                            PaymentAmountRow("Discount", -receipt.discountMinor, Brand.Good)
                        }
                        if (receipt.taxMinor != 0L) PaymentAmountRow("Tax", receipt.taxMinor)
                        if (receipt.roundOffMinor != 0L) {
                            PaymentAmountRow("Round-off", receipt.roundOffMinor)
                        }
                        HorizontalDivider(color = Brand.BorderSubtle)
                        PaymentAmountRow("Total", receipt.totalMinor, emphasized = true)
                        PaymentAmountRow("Paid", receipt.amountMinor, Brand.Good)
                        receipt.tenderedMinor?.let { PaymentAmountRow("Cash received", it) }
                        receipt.changeMinor?.let { PaymentAmountRow("Change", it, Brand.Good) }
                    }
                }
                receipt.orderNote?.takeIf(String::isNotBlank)?.let { note ->
                    item {
                        Text(
                            "Order note: $note",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    Text(
                        buildString {
                            append("Order ${receipt.orderId.take(8)}")
                            receipt.paymentId?.let { append(" · Payment ${it.take(8)}") }
                            receipt.fiscalYear?.let { append(" · FY $it") }
                        },
                        color = Brand.ForegroundFaint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                printError?.let { error ->
                    item {
                        Text(
                            error,
                            color = Brand.Danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !printLaunching,
                onClick = {
                    if (printLaunching) return@TextButton
                    printLaunching = true
                    printError = null
                    launchPosReceiptPrint(
                        context = context,
                        receipt = receipt,
                        onLaunched = { printLaunching = false },
                        onFailure = { message ->
                            printLaunching = false
                            printError = message
                        },
                    )
                },
            ) {
                Text(if (printLaunching) "OPENING…" else "PRINT / SAVE")
            }
        },
        confirmButton = { PrimaryButton(onClick = onDismiss) { Text("DONE") } },
    )
}

@Composable
private fun PosVoidDialog(
    target: PosVoidTarget,
    online: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedReasonId by rememberSaveable(target.orderId) { mutableStateOf<String?>(null) }
    var customReason by rememberSaveable(target.orderId) { mutableStateOf("") }
    val reason = resolvedVoidReason(selectedReasonId, customReason)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text("Void ${target.label}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "This cancels the unpaid bill, keeps its audit history, and notifies any linked fulfilment workflow.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                VoidReasonInput(
                    selectedId = selectedReasonId,
                    customReason = customReason,
                    onPresetSelected = { selectedReasonId = it },
                    onCustomReasonChange = { customReason = it },
                )
                if (!online) {
                    Text(
                        "Reconnect before voiding so the audit record and linked cancellation are saved together.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (busy) {
                    Text(
                        "Saving this void once. Keep this dialog open until the server confirms it.",
                        color = Brand.Information,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            ErpButton(
                text = if (busy) "Voiding bill" else "VOID BILL",
                onClick = { onConfirm(reason) },
                enabled = online && reason.isNotBlank() && !busy,
                busy = busy,
                intent = ActionIntent.Destructive,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep bill") }
        },
    )
}

internal data class PosWorkspaceMetrics(
    val sideBySide: Boolean,
    val productWidth: androidx.compose.ui.unit.Dp,
    val cartWidth: androidx.compose.ui.unit.Dp?,
)

/**
 * Keep the checkout close to 36% of the operational workspace. The bounds stop
 * the receipt becoming too narrow at the 960x600 target or too dominant on a
 * larger tablet, while the returned product width makes the grid policy
 * deterministic and JVM-testable.
 */
internal fun posWorkspaceMetrics(
    maxWidth: androidx.compose.ui.unit.Dp,
    horizontalGap: androidx.compose.ui.unit.Dp = 12.dp,
): PosWorkspaceMetrics {
    if (maxWidth < 720.dp) {
        return PosWorkspaceMetrics(
            sideBySide = false,
            productWidth = maxWidth,
            cartWidth = null,
        )
    }
    val cartWidth = (maxWidth * 0.36f).coerceIn(300.dp, 400.dp)
    return PosWorkspaceMetrics(
        sideBySide = true,
        productWidth = (maxWidth - cartWidth - horizontalGap).coerceAtLeast(0.dp),
        cartWidth = cartWidth,
    )
}

/** Match the explicit 3–4 column tablet contract without shrinking cards. */
internal fun posProductColumnCount(
    panelWidth: androidx.compose.ui.unit.Dp,
    horizontalContentPadding: androidx.compose.ui.unit.Dp = 12.dp,
    horizontalGap: androidx.compose.ui.unit.Dp = 8.dp,
    minimumCardWidth: androidx.compose.ui.unit.Dp = 156.dp,
): Int {
    val usableWidth = (panelWidth - horizontalContentPadding * 2)
        .coerceAtLeast(0.dp)
    val count = ((usableWidth.value + horizontalGap.value) /
        (minimumCardWidth.value + horizontalGap.value)).toInt()
    return count.coerceIn(1, 4)
}

internal fun shouldRevealHeldOrderQueue(
    focusedOrderId: String?,
    visibleOrderIds: Set<String>,
): Boolean = focusedOrderId != null && focusedOrderId in visibleOrderIds

internal fun hasPosOperationalAlerts(state: PosUiState): Boolean =
    !state.online || state.pendingCount > 0 || state.rejectedCount > 0 ||
        state.shiftAccessMessage != null || state.rejectedDirectSales.isNotEmpty() ||
        state.heldPaymentStatuses.isNotEmpty() || state.overdueHeldOrderIds.isNotEmpty()

@Composable
private fun PosContextBar(
    state: PosUiState,
    onOpenStatus: () -> Unit,
    onOpenHeldOrders: () -> Unit,
    onOpenLastReceipt: (() -> Unit)?,
) {
    val urgent = state.rejectedCount > 0 || state.rejectedDirectSales.isNotEmpty() ||
        state.heldRejectedCount > 0
    val focused = shouldRevealHeldOrderQueue(
        state.focusedHeldOrderId,
        state.heldOrders.mapTo(mutableSetOf()) { it.id },
    )
    val title = when {
        focused -> "Held-order notification opened"
        urgent -> "POS action required"
        !state.online -> "POS offline"
        state.shiftAccessMessage != null -> "Payment access needs attention"
        state.heldOrders.isNotEmpty() -> "${state.heldOrders.size} held order${if (state.heldOrders.size == 1) "" else "s"} awaiting payment"
        else -> "POS operational context"
    }
    val details = buildList {
        if (state.pendingCount > 0) add("${state.pendingCount} pending sync")
        if (state.rejectedCount > 0) add("${state.rejectedCount} need review")
        if (state.overdueHeldOrderIds.isNotEmpty()) {
            add("${state.overdueHeldOrderIds.size} overdue")
        }
        if (state.heldOrders.isNotEmpty()) add("held queue available")
        if (isEmpty()) add(if (state.online) "Review alerts and Android alarm access" else "Reconnect before payment")
    }.joinToString(" · ")
    val tone = when {
        urgent -> UiTone.Danger
        !state.online || state.shiftAccessMessage != null || state.overdueHeldOrderIds.isNotEmpty() ->
            UiTone.Warning
        state.heldOrders.isNotEmpty() -> UiTone.Information
        else -> UiTone.Neutral
    }
    val icon = when {
        urgent -> Icons.Default.ErrorOutline
        !state.online -> Icons.Default.WifiOff
        state.overdueHeldOrderIds.isNotEmpty() -> Icons.Default.Schedule
        state.heldOrders.isNotEmpty() -> Icons.Default.Sync
        else -> Icons.Default.ShoppingCart
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (maxWidth >= 620.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PosContextSummary(
                    title = title,
                    details = details,
                    tone = tone,
                    icon = icon,
                    modifier = Modifier.weight(1f),
                )
                ErpButton("Status & alarms", onOpenStatus, intent = ActionIntent.Secondary)
                onOpenLastReceipt?.let {
                    ErpButton("Last receipt", it, intent = ActionIntent.Quiet)
                }
                if (state.heldOrders.isNotEmpty()) {
                    ErpButton(
                        text = if (focused) "Review highlighted" else "Held (${state.heldOrders.size})",
                        onClick = onOpenHeldOrders,
                        intent = ActionIntent.Primary,
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PosContextSummary(title, details, tone, icon)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ErpButton(
                        "Status & alarms",
                        onOpenStatus,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Secondary,
                    )
                    onOpenLastReceipt?.let {
                        ErpButton(
                            "Last receipt",
                            it,
                            modifier = Modifier.weight(1f),
                            intent = ActionIntent.Quiet,
                        )
                    }
                    if (state.heldOrders.isNotEmpty()) {
                        ErpButton(
                            text = if (focused) "Review" else "Held (${state.heldOrders.size})",
                            onClick = onOpenHeldOrders,
                            modifier = Modifier.weight(1f),
                            intent = ActionIntent.Primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PosContextSummary(
    title: String,
    details: String,
    tone: UiTone,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when (tone) {
                UiTone.Danger -> Brand.Danger
                UiTone.Warning -> Brand.Warning
                UiTone.Information -> Brand.Information
                else -> Brand.ForegroundMuted
            },
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = Brand.Foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                details,
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OperationalStatusBadge(
            label = when (tone) {
                UiTone.Danger -> "Action required"
                UiTone.Warning -> "Attention"
                UiTone.Information -> "Queue"
                else -> "Status"
            },
            tone = tone,
        )
    }
}

@Composable
private fun PosOperationalAlerts(
    state: PosUiState,
    canRetry: Boolean,
    onRefresh: () -> Unit,
    onRetryRejectedSale: (String) -> Unit,
    onRetryHeldPayment: (String) -> Unit,
    onFocusOldestOverdue: () -> Unit,
    onSnoozeOverdue: () -> Unit,
    onUnmuteOverdue: () -> Unit,
) {
    val urgent = state.rejectedCount > 0 || state.rejectedDirectSales.isNotEmpty() ||
        state.heldRejectedCount > 0
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .heightIn(max = 360.dp)
            .clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "POS status & recovery",
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Connection, shift access and payments already recorded on this tablet",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OperationalStatusBadge(
                label = when {
                    urgent -> "Action required"
                    !state.online -> "Offline"
                    else -> "Attention"
                },
                tone = if (urgent) UiTone.Danger else UiTone.Warning,
            )
        }
        HorizontalDivider(color = Brand.BorderSubtle)
        SyncBanner(state, onRefresh)
        state.shiftAccessMessage?.let { ShiftAccessBanner(it) }
        if (state.rejectedDirectSales.isNotEmpty()) {
            RejectedDirectSalesStrip(
                sales = state.rejectedDirectSales,
                retryingIds = state.retryingRejectedSaleIds,
                online = state.online,
                canRetry = canRetry,
                onRetry = onRetryRejectedSale,
            )
        }
        if (state.heldPaymentStatuses.isNotEmpty()) {
            HeldPaymentStatusStrip(
                payments = state.heldPaymentStatuses,
                retryingIds = state.retryingHeldPaymentIds,
                online = state.online,
                canRetry = canRetry,
                onSyncPending = onRefresh,
                onRetryRejected = onRetryHeldPayment,
            )
        }
        if (state.showOverdueBanner) {
            OverdueHeldOrdersBanner(
                count = state.overdueHeldOrderIds.size,
                onView = onFocusOldestOverdue,
                onSnooze = onSnoozeOverdue,
            )
        } else if (
            state.overdueHeldOrderIds.isNotEmpty() &&
            state.overdueBannerMutedUntilMillis > 0L
        ) {
            SnoozedHeldOrdersNotice(
                count = state.overdueHeldOrderIds.size,
                untilMillis = state.overdueBannerMutedUntilMillis,
                onView = onFocusOldestOverdue,
                onUnmute = onUnmuteOverdue,
            )
        }
    }
}

@Composable
private fun SnoozedHeldOrdersNotice(
    count: Int,
    untilMillis: Long,
    onView: () -> Unit,
    onUnmute: () -> Unit,
) {
    val until = remember(untilMillis) {
        Instant.ofEpochMilli(untilMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    }
    OperationalBanner(
        title = "$count overdue held order${if (count == 1) "" else "s"}",
        detail = "Reminder snoozed until $until. The overdue orders remain in the collection queue.",
        tone = UiTone.Warning,
        icon = Icons.Default.Schedule,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ErpButton("View", onView, intent = ActionIntent.Secondary)
                ErpButton("Unmute", onUnmute, intent = ActionIntent.Quiet)
            }
        },
    )
}

@Composable
private fun OverdueHeldOrdersBanner(
    count: Int,
    onView: () -> Unit,
    onSnooze: () -> Unit,
) {
    OperationalBanner(
        title = "$count held order${if (count == 1) "" else "s"} waiting over 15 minutes",
        detail = "Review the oldest bill. Snoozing pauses this reminder for five minutes; it does not remove the order.",
        tone = UiTone.Danger,
        icon = Icons.Default.ErrorOutline,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ErpButton("View oldest", onView, intent = ActionIntent.Secondary)
                ErpButton("Snooze 5 min", onSnooze, intent = ActionIntent.Quiet)
            }
        },
    )
}

@Composable
private fun ShiftAccessBanner(message: String) {
    OperationalBanner(
        title = "Payment unavailable for this account",
        detail = message,
        tone = UiTone.Warning,
        icon = Icons.Default.Lock,
    )
}

/**
 * Offline status has to be unmissable. A cashier who does not know the tablet
 * is offline cannot know why the saved sale has no invoice number, and a queue
 * that is silently stuck is worse than one that is loudly stuck.
 */
@Composable
private fun SyncBanner(state: PosUiState, onRefresh: () -> Unit) {
    val presentation = when {
        state.rejectedDirectSales.isNotEmpty() && state.heldRejectedCount > 0 ->
            Triple(
                "Payments need reconciliation",
                "${state.rejectedDirectSales.size} direct sale(s) and ${state.heldRejectedCount} held payment(s) need manager review. Do not charge those customers again.",
                UiTone.Danger,
            )
        state.rejectedDirectSales.isNotEmpty() ->
            Triple(
                "Direct sales need review",
                "${state.rejectedDirectSales.size} collected sale(s) were not accepted by the server. Review the original saved sales below; do not charge again.",
                UiTone.Danger,
            )
        state.heldRejectedCount > 0 ->
            Triple(
                "Held payments need owner review",
                "${state.heldRejectedCount} payment(s) were recorded on this tablet but not accepted by the server. Do not collect again.",
                UiTone.Danger,
            )
        !state.online && state.pendingCount > 0 ->
            Triple(
                "POS is offline",
                "${state.pendingCount} sale(s) are saved safely on this tablet and will be sent after reconnection.",
                UiTone.Warning,
            )
        !state.online ->
            Triple(
                "POS is offline",
                "You can keep building an order. Reconnect before collecting payment so the final total can be verified.",
                UiTone.Warning,
            )
        state.pendingCount > 0 ->
            Triple(
                "Confirming saved sales",
                "${state.pendingCount} saved sale(s) are waiting for server confirmation. Each keeps its original payment identity.",
                UiTone.Information,
            )
        else -> return
    }
    OperationalBanner(
        title = presentation.first,
        detail = presentation.second,
        tone = presentation.third,
        icon = when {
            presentation.third == UiTone.Danger -> Icons.Default.ErrorOutline
            !state.online -> Icons.Default.WifiOff
            else -> Icons.Default.Sync
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        action = if (state.online && state.rejectedCount == 0 && state.pendingCount > 0) {
            {
                ErpButton(
                    text = if (state.syncing) "Confirming" else "Confirm now",
                    onClick = onRefresh,
                    intent = ActionIntent.Secondary,
                    enabled = !state.syncing,
                    busy = state.syncing,
                )
            }
        } else {
            null
        },
    )
}

/**
 * A rejected direct sale represents money staff already marked as received.
 * Keep the exact amount, method, time and server reason together, and put the
 * only safe recovery action beside that row. Generic sync intentionally does
 * not touch these deterministic refusals.
 */
@Composable
private fun RejectedDirectSalesStrip(
    sales: List<RejectedDirectSale>,
    retryingIds: Set<String>,
    online: Boolean,
    canRetry: Boolean,
    onRetry: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .clip(Radius.shapeMd).background(Brand.SurfaceRaised)
            .border(1.dp, Brand.Danger.copy(alpha = 0.4f), Radius.shapeMd)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            "Failed direct sales (${sales.size})",
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Payment was already captured on this tablet. Do not charge the customer again. " +
                "Fix the server-side cause, then retry the original saved sale.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(sales, key = { sale -> sale.localId }) { sale ->
                val retrying = sale.localId in retryingIds
                Column(
                    Modifier.width(360.dp)
                        .clip(Radius.shapeMd)
                        .background(Brand.SurfaceRaised)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Collected ${sale.amountMinor.asRupees()}",
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            sale.createdAtMillis.asPosTime(),
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "Method · ${sale.paymentMethod.paymentMethodLabel()}",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        sale.error,
                        color = Brand.Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ErpButton(
                        text = when {
                            retrying -> "Retrying saved sale"
                            !online -> "Reconnect to retry"
                            else -> "Retry after fix"
                        },
                        onClick = { onRetry(sale.localId) },
                        intent = ActionIntent.Secondary,
                        enabled = canRetry && online && !retrying,
                        busy = retrying,
                    )
                }
            }
        }
    }
}

/**
 * Tables/Gaming payments are hidden from the collection queue the instant
 * staff confirm money locally. Keep their durable reconciliation rows visible
 * until the server confirms them so an app restart never turns a captured
 * payment into an anonymous count or an invitation to collect twice.
 */
@Composable
private fun HeldPaymentStatusStrip(
    payments: List<HeldPaymentStatus>,
    retryingIds: Set<String>,
    online: Boolean,
    canRetry: Boolean,
    onSyncPending: () -> Unit,
    onRetryRejected: (String) -> Unit,
) {
    val hasRejection = payments.any { it.state == HeldOrderPaymentState.REJECTED }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .clip(Radius.shapeMd).background(Brand.SurfaceRaised)
            .border(
                1.dp,
                if (hasRejection) Brand.Danger.copy(alpha = 0.4f)
                else Brand.Warning.copy(alpha = 0.4f),
                Radius.shapeMd,
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            "Saved held-order payments (${payments.size})",
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Money was already marked received for these held orders. Never collect it " +
                "again. Account switching stays locked until each saved payment is confirmed or reconciled.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(payments, key = { payment -> payment.localId }) { payment ->
                val rejected = payment.state == HeldOrderPaymentState.REJECTED
                val retrying = payment.localId in retryingIds
                Column(
                    Modifier.width(360.dp)
                        .clip(Radius.shapeMd)
                        .background(Brand.SurfaceRaised)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            payment.sourceLabel ?: "Order ${payment.targetOrderId.take(8)}",
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            payment.createdAtMillis.asPosTime(),
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "Collected ${payment.amountMinor.asRupees()} · " +
                            payment.paymentMethod.paymentMethodLabel(),
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        if (rejected) "Server refused this saved payment" else "Awaiting server confirmation",
                        color = if (rejected) Brand.Danger else Brand.Warning,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        payment.error ?: if (rejected) {
                            "No server explanation was recorded. Manager review is required."
                        } else {
                            "The original safe payment identity will be replayed automatically."
                        },
                        color = if (rejected) Brand.Danger else Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ErpButton(
                        text = when {
                            retrying -> "Retrying saved payment"
                            !online -> "Reconnect to retry"
                            rejected -> "Retry after fix"
                            else -> "Confirm with server"
                        },
                        onClick = {
                            if (rejected) onRetryRejected(payment.localId) else onSyncPending()
                        },
                        intent = ActionIntent.Secondary,
                        enabled = canRetry && online && !retrying,
                        busy = retrying,
                    )
                }
            }
        }
    }
}

/**
 * Orders waiting to be paid — a table's "Send to POS" lands here. Previously
 * these never appeared anywhere in the native app at all: nothing pulled or
 * displayed them, so a table order reached the server but the till had no
 * way to find and complete it.
 */
@Composable
private fun HeldOrdersStrip(
    orders: List<HeldOrderCacheEntity>,
    overdueOrderIds: Set<String>,
    focusedOrderId: String?,
    preparingOrderId: String?,
    enabled: Boolean,
    blockedReason: String?,
    onSelect: (HeldOrderCacheEntity) -> Unit,
    onDismissFocus: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusedIndex = focusedOrderId?.let { id -> orders.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    LaunchedEffect(focusedOrderId, focusedIndex) {
        if (focusedIndex != null) listState.animateScrollToItem(focusedIndex)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Text(
            buildString {
                append("Held orders (${orders.size})")
                if (overdueOrderIds.isNotEmpty()) append(" · ${overdueOrderIds.size} overdue")
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (overdueOrderIds.isEmpty()) Brand.ForegroundMuted else Brand.Danger,
        )
        blockedReason?.let { reason ->
            Text(
                reason,
                modifier = Modifier.padding(top = Spacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = Brand.Warning,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        focusedOrderId?.let { id ->
            val focused = orders.firstOrNull { it.id == id }
            if (focused != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        "Alert opened: ${focused.sourceLabel ?: focused.invoiceNo ?: "held order"}. " +
                            "Review the highlighted card, then tap it to verify and bill.",
                        modifier = Modifier.weight(1f),
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = onDismissFocus) { Text("Clear highlight") }
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(orders, key = { it.id }) { order ->
                val focused = order.id == focusedOrderId
                val overdue = order.id in overdueOrderIds
                Column(
                    Modifier.clip(Radius.shapeMd)
                        .background(
                            when {
                                focused -> Brand.InformationMuted
                                overdue -> Brand.Danger.copy(alpha = 0.16f)
                                else -> Brand.SurfaceRaised
                            },
                        )
                        .then(
                            if (focused) Modifier.border(2.dp, Brand.Information, Radius.shapeMd)
                            else Modifier,
                        )
                        .heightIn(min = 72.dp)
                        .clickable(enabled = enabled) { onSelect(order) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    Text(
                        order.sourceLabel ?: order.invoiceNo ?: "Order",
                        fontWeight = FontWeight.Bold,
                        color = Brand.Foreground,
                    )
                    Text(
                        if (preparingOrderId == order.id) {
                            "Verifying the live total — wait before selecting again"
                        } else if (overdue) {
                            "Overdue · ${order.itemsCount} item(s) · due ${order.dueMinor.asRupees()}"
                        } else {
                            "${order.itemsCount} item(s) · due ${order.dueMinor.asRupees()}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                    if (preparingOrderId == order.id) {
                        OperationalStatusBadge(
                            label = "Verifying",
                            tone = UiTone.Information,
                        )
                    }
                }
            }
        }
    }
}

/** Disabled held-order cards must always explain the operational dependency. */
internal fun heldOrderSelectionBlockReason(state: PosUiState, access: PosAccess): String? = when {
    !access.canCreateAndCollect ->
        "This account can view held orders but cannot collect them. Ask the shift cashier or a manager."
    !state.online ->
        "Held orders require a live server total. Reconnect before selecting one for payment."
    state.shiftAccessMessage != null -> state.shiftAccessMessage
    state.preparingHeldOrderId != null || state.checkoutBusy ->
        "A bill is already being verified or saved. Wait for that action to finish before selecting another."
    state.heldSelectionBlocked ->
        "The previous held payment is being secured against duplicate taps. Wait for its status update."
    state.preparedHeldCheckout != null ->
        "Finish or cancel the open held bill before selecting another order."
    state.draftState in setOf(SyncState.PREPARING, SyncState.AWAITING_PAYMENT) ->
        "Finish or recover the current direct POS bill before selecting a held order."
    !state.canCollectPayment ->
        "Open the correct shift before collecting a held order."
    else -> null
}

internal fun filterPosMenuItems(
    items: List<MenuItemEntity>,
    query: String,
): List<MenuItemEntity> {
    val term = query.trim()
    if (term.isEmpty()) return items
    return items.filter { item ->
        item.name.contains(term, ignoreCase = true) ||
            item.sku.contains(term, ignoreCase = true) ||
            item.description?.contains(term, ignoreCase = true) == true
    }
}

@Composable
private fun EmptyMenuPanel(
    everSynced: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Menu catalogue", color = Brand.Foreground, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Products available in this shop",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OperationalStatusBadge(
                label = if (everSynced) "No items configured" else "Awaiting first sync",
                tone = if (everSynced) UiTone.Neutral else UiTone.Warning,
            )
        }
        HorizontalDivider(color = Brand.BorderSubtle)
        DesignedEmptyState(
            title = if (everSynced) "The menu is empty" else "No menu on this tablet yet",
            body = if (everSynced) {
                "This tablet is up to date, but no products are configured. Add items on the Menu screen, then check again."
            } else {
                "Connect once to download the menu. After that, this tablet can keep browsing products offline."
            },
            icon = Icons.Filled.RestaurantMenu,
            primaryLabel = if (everSynced) "Check again" else "Download menu",
            onPrimary = onRefresh,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun ProductConfigurationDialog(
    item: MenuItemEntity,
    variants: List<MenuVariantEntity>,
    modifierGroups: List<MenuModifierGroupEntity>,
    modifiers: List<MenuModifierEntity>,
    onDismiss: () -> Unit,
    onAdd: (MenuVariantEntity?, List<CartModifierSelection>, String?) -> Unit,
) {
    var selectedVariantId by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    var selectedQuantities by remember(item.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var note by rememberSaveable(item.id) { mutableStateOf("") }
    val selectedVariant = variants.firstOrNull { it.id == selectedVariantId }
    val selections = modifiers.mapNotNull { option ->
        selectedQuantities[option.id]?.takeIf { it > 0 }?.let {
            CartModifierSelection(option, it)
        }
    }
    val groupError = modifierGroups.firstNotNullOfOrNull { group ->
        val count = modifiers.filter { it.modifierGroupId == group.id }
            .sumOf { selectedQuantities[it.id] ?: 0 }
        when {
            count < group.minSelect ->
                "${group.name} requires at least ${group.minSelect} selection${if (group.minSelect == 1) "" else "s"}."
            count > group.maxSelect ->
                "${group.name} allows at most ${group.maxSelect} selections."
            else -> null
        }
    }
    val configuredPrice = configuredUnitPriceMinor(item, selectedVariant, selections)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Customise ${item.name}")
                Text(
                    "Unit price ${configuredPrice.asRupees()}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (variants.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text("Variant", style = MaterialTheme.typography.titleSmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                item {
                                    FilterChip(
                                        selected = selectedVariantId == null,
                                        onClick = { selectedVariantId = null },
                                        label = { Text("Standard") },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                                items(variants, key = { it.id }) { variant ->
                                    FilterChip(
                                        selected = selectedVariantId == variant.id,
                                        onClick = { selectedVariantId = variant.id },
                                        label = {
                                            Text(
                                                if (variant.priceDeltaMinor == 0L) variant.name
                                                else "${variant.name} · +${variant.priceDeltaMinor.asRupees()}",
                                            )
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                items(modifierGroups, key = { it.id }) { group ->
                    val groupOptions = modifiers.filter { it.modifierGroupId == group.id }
                    val selectedCount = groupOptions.sumOf { selectedQuantities[it.id] ?: 0 }
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when {
                                        group.minSelect == group.maxSelect -> "Choose ${group.minSelect}"
                                        group.minSelect > 0 -> "Choose ${group.minSelect}–${group.maxSelect}"
                                        else -> "Optional · up to ${group.maxSelect}"
                                    },
                                    color = Brand.ForegroundMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            OperationalStatusBadge(
                                label = "$selectedCount/${group.maxSelect}",
                                tone = if (selectedCount in group.minSelect..group.maxSelect) {
                                    UiTone.Success
                                } else {
                                    UiTone.Warning
                                },
                            )
                        }
                        groupOptions.forEach { option ->
                            val qty = selectedQuantities[option.id] ?: 0
                            Row(
                                Modifier.fillMaxWidth().clip(Radius.shapeMd)
                                    .background(Brand.SurfaceRaised)
                                    .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                                    .padding(Spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(option.name, color = Brand.Foreground)
                                    Text(
                                        if (option.priceDeltaMinor == 0L) "Included"
                                        else "+${option.priceDeltaMinor.asRupees()} each",
                                        color = Brand.ForegroundMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                QtyButton("−", enabled = qty > 0) {
                                    selectedQuantities = selectedQuantities +
                                        (option.id to (qty - 1).coerceAtLeast(0))
                                }
                                Text("$qty", fontWeight = FontWeight.Bold)
                                QtyButton(
                                    "+",
                                    enabled = qty < option.maxQuantity && selectedCount < group.maxSelect,
                                ) {
                                    selectedQuantities = selectedQuantities + (option.id to qty + 1)
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(500) },
                        label = { Text("Item note (optional)") },
                        placeholder = { Text("Example: no ice, less spicy") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                groupError?.let { message ->
                    item { Text(message, color = Brand.Warning, style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = groupError == null,
                onClick = { onAdd(selectedVariant, selections, note) },
            ) { Text("ADD · ${configuredPrice.asRupees()}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OrderDetailsDialog(
    state: PosUiState,
    canDiscount: Boolean,
    presentation: WorkspacePresentationPolicy,
    onDismiss: () -> Unit,
    onSave: (String?, String?, String?, Long) -> Unit,
) {
    var customerName by remember { mutableStateOf(state.customerName.orEmpty()) }
    var customerPhone by remember { mutableStateOf(state.customerPhone.orEmpty()) }
    var orderNote by remember { mutableStateOf(state.orderNote.orEmpty()) }
    var discountText by remember {
        mutableStateOf(
            state.manualDiscountMinor.takeIf { it > 0L }?.let { minor ->
                "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
            }.orEmpty(),
        )
    }
    val discountMinor = if (discountText.isBlank()) 0L else parseRupeesToMinor(discountText)
    val phoneDigits = customerPhone.filter(Char::isDigit)
    val phoneInvalid = phoneDigits.isNotEmpty() && phoneDigits.length !in 7..20
    val discountInvalid = discountMinor == null || discountMinor > state.estimateMinor

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text("Sale details & discount") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    if (presentation.showsCustomers) {
                        "These details stay with this saved counter sale. Any customer benefit and the final total are verified online before payment."
                    } else {
                        "The note and discount stay with this saved counter sale. The final total is verified online before payment."
                    },
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (presentation.showsCustomers) {
                    OutlinedTextField(
                        value = customerName,
                        onValueChange = { customerName = it.take(200) },
                        label = { Text("Customer name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = customerPhone,
                        onValueChange = { input ->
                            customerPhone = input.filter { it.isDigit() || it == '+' }.take(20)
                        },
                        label = { Text("Customer phone (optional)") },
                        supportingText = {
                            Text(
                                if (phoneInvalid) "Enter 7–20 digits, or leave this blank."
                                else "Optional customer reference for this sale.",
                            )
                        },
                        isError = phoneInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = orderNote,
                    onValueChange = { orderNote = it.take(500) },
                    label = { Text("Order note (optional)") },
                    placeholder = { Text("Example: controller issue or product request") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = discountText,
                    onValueChange = { input ->
                        if (input.length <= 12 && input.all { it.isDigit() || it == '.' }) {
                            discountText = input
                        }
                    },
                    enabled = canDiscount,
                    label = { Text("Manual discount (₹)") },
                    supportingText = {
                        Text(
                            when {
                                !canDiscount -> "Manager discount permission is required."
                                discountInvalid -> "Enter a valid amount no greater than ${state.estimateMinor.asRupees()}."
                                else -> "The server revalidates this reduction before payment."
                            },
                        )
                    },
                    isError = canDiscount && discountInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = (!presentation.showsCustomers || !phoneInvalid) &&
                    (!canDiscount || !discountInvalid),
                onClick = {
                    onSave(
                        customerName.trim().takeIf(String::isNotEmpty),
                        phoneDigits.takeIf(String::isNotEmpty),
                        orderNote.trim().takeIf(String::isNotEmpty),
                        if (canDiscount) requireNotNull(discountMinor) else state.manualDiscountMinor,
                    )
                },
            ) { Text("Save details") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProductCatalogPanel(
    categories: List<MenuCategoryEntity>,
    items: List<MenuItemEntity>,
    selectedCategoryId: String?,
    visibleItems: List<MenuItemEntity>,
    query: String,
    canWrite: Boolean,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onAdd: (MenuItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categoryNames = remember(categories) {
        categories.associate { category -> category.id to category.name }
    }
    Column(
        modifier = modifier.clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(Spacing.lg),
        ) {
            if (maxWidth >= 560.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CatalogPanelSummary(
                        availableCount = items.size,
                        shownCount = visibleItems.size,
                        modifier = Modifier.weight(1f),
                    )
                    SearchInput(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "Search menu or SKU",
                        modifier = Modifier.width(264.dp),
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CatalogPanelSummary(
                        availableCount = items.size,
                        shownCount = visibleItems.size,
                    )
                    SearchInput(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = "Search menu or SKU",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        CategoryStrip(
            categories = categories,
            items = items,
            selectedCategoryId = selectedCategoryId,
            onSelect = onSelectCategory,
        )
        HorizontalDivider(color = Brand.BorderSubtle)

        if (visibleItems.isEmpty()) {
            DesignedEmptyState(
                title = if (query.isBlank()) "No items in this category" else "No matching products",
                body = if (query.isBlank()) {
                    "Choose another category to continue building the order."
                } else {
                    "Nothing matches “${query.trim()}”. Try a product name, description, or SKU."
                },
                icon = Icons.Filled.SearchOff,
                primaryLabel = if (query.isBlank()) "Show all items" else "Clear search",
                onPrimary = {
                    onClearSearch()
                    if (query.isBlank()) onSelectCategory(null)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columnCount = remember(maxWidth) {
                    posProductColumnCount(
                        panelWidth = maxWidth,
                        horizontalContentPadding = Spacing.md,
                        horizontalGap = Spacing.sm,
                    )
                }
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxSize(),
                    columns = GridCells.Fixed(columnCount),
                    contentPadding = PaddingValues(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        MenuTile(
                            item = item,
                            categoryName = categoryNames[item.categoryId],
                            enabled = canWrite,
                            modifier = Modifier.animateItem(),
                        ) { onAdd(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPanelSummary(
    availableCount: Int,
    shownCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Menu items",
                color = Brand.Foreground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            OperationalStatusBadge(
                label = "$availableCount in menu",
                tone = UiTone.Success,
            )
        }
        Text(
            "$shownCount shown in this view · Tap a product to add it to this order",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CategoryStrip(
    categories: List<MenuCategoryEntity>,
    items: List<MenuItemEntity>,
    selectedCategoryId: String?,
    onSelect: (String?) -> Unit,
) {
    val allId = "__all_pos_categories__"
    val options = remember(categories, items) {
        buildList {
            add(TabOption(id = allId, label = "All", count = items.size))
            categories.forEach { category ->
                add(
                    TabOption(
                        id = category.id,
                        label = category.name,
                        count = items.count { it.categoryId == category.id },
                    ),
                )
            }
        }
    }
    PremiumTabBar(
        options = options,
        selectedId = selectedCategoryId ?: allId,
        onSelect = { selected -> onSelect(selected.takeUnless { it == allId }) },
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

/** Scales down slightly on press — the one micro-interaction a cashier taps
 * hundreds of times a shift, so it's worth it being responsive-feeling even
 * though the tile itself is a plain, high-density grid item. */
@Composable
private fun MenuTile(
    item: MenuItemEntity,
    categoryName: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(Motion.fast, easing = Motion.emphasized), label = "tileScale")
    Column(
        modifier = modifier
            .height(132.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.55f
            }
            .clip(Radius.shapeLg)
            .background(Brand.SurfaceRaised)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
            .semantics {
                role = Role.Button
                contentDescription = if (enabled) {
                    "Add ${item.name} to the current order"
                } else {
                    "${item.name}, view only"
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(38.dp).clip(Radius.shapeMd).background(Brand.SurfaceHover),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.RestaurantMenu,
                    contentDescription = null,
                    tint = Brand.ForegroundMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    categoryName ?: item.type.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumericValue(
                value = item.basePriceMinor.asRupees(),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AddShoppingCart,
                    contentDescription = null,
                    tint = if (enabled) Brand.ForegroundMuted else Brand.Disabled,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    if (enabled) "Add" else "View only",
                    color = if (enabled) Brand.ForegroundMuted else Brand.Disabled,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun CartPanel(
    state: PosUiState,
    canWrite: Boolean,
    canDiscount: Boolean,
    onIncrementLine: (String) -> Unit,
    onDecrementLine: (String) -> Unit,
    onClear: () -> Unit,
    onEditDetails: () -> Unit,
    modifier: Modifier = Modifier,
    onPay: () -> Unit,
) {
    Column(
        modifier = modifier.clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Current order",
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state.cart.isEmpty()) "Ready for the next customer"
                    else "${state.cartCount} item${if (state.cartCount == 1) "" else "s"} in this sale",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AnimatedVisibility(state.cart.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ErpButton(
                        text = if (
                            state.customerPhone != null || state.orderNote != null ||
                            state.manualDiscountMinor > 0L
                        ) "Details ✓" else "Details",
                        onClick = onEditDetails,
                        enabled = canWrite && state.draftEditable,
                        intent = ActionIntent.Secondary,
                    )
                    ErpButton(
                        text = "Clear",
                        onClick = onClear,
                        enabled = canWrite && state.draftEditable,
                        intent = ActionIntent.Quiet,
                    )
                }
            }
        }
        HorizontalDivider(color = Brand.BorderSubtle)

        if (state.cart.isEmpty()) {
            DesignedEmptyState(
                title = if (canWrite) "Build this order" else "View-only POS",
                body = if (canWrite) {
                    "Choose a product from the menu. Quantity controls, the running estimate, and payment stay together here."
                } else {
                    "You can browse the menu and current order, but this role cannot add items or collect payment."
                },
                icon = Icons.Filled.ShoppingCart,
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.cart, key = { it.lineId }) { line ->
                    Row(
                        Modifier.fillMaxWidth().animateItem().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised)
                            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                            .padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                line.item.name,
                                color = Brand.Foreground,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val optionLabel = buildList {
                                line.variant?.name?.let(::add)
                                addAll(line.modifiers.map { modifier ->
                                    if (modifier.qty == 1) modifier.modifier.name
                                    else "${modifier.modifier.name} ×${modifier.qty}"
                                })
                                line.note?.let { add("Note: $it") }
                            }.joinToString(" · ")
                            if (optionLabel.isNotBlank()) {
                                Text(
                                    optionLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Brand.ForegroundMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                line.lineTotalMinor.asRupees(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.ForegroundFaint,
                            )
                        }
                        QtyButton("−", enabled = canWrite && state.draftEditable) {
                            onDecrementLine(line.lineId)
                        }
                        AnimatedContent(
                            targetState = line.qty,
                            transitionSpec = { fadeIn(tween(Motion.fast)).togetherWith(fadeOut(tween(Motion.fast))) },
                            label = "qty",
                        ) { qty ->
                            Text(
                                "$qty",
                                modifier = Modifier.padding(horizontal = Spacing.sm),
                                color = Brand.Foreground,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        QtyButton("+", enabled = canWrite && state.draftEditable) {
                            onIncrementLine(line.lineId)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Brand.BorderSubtle)
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "Subtotal estimate",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        if (state.online) "Server verifies the exact total before payment"
                        else "Provisional offline total; server reconciles on reconnect",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundFaint,
                    )
                }
                AnimatedContent(
                    targetState = state.estimateMinor,
                    transitionSpec = {
                        fadeIn(tween(Motion.fast)).togetherWith(fadeOut(tween(Motion.fast)))
                    },
                    label = "estimate",
                ) { minor ->
                    NumericValue(
                        value = minor.asRupees(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            if (state.manualDiscountMinor > 0L) {
                PaymentAmountRow("Manual discount", -state.manualDiscountMinor, Brand.Good)
            } else if (!canDiscount && state.cart.isNotEmpty()) {
                Text(
                    "Manager permission is required for manual discounts.",
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            ErpButton(
                text = when {
                    state.checkoutBusy && state.preparingHeldOrderId != null -> "Verifying held bill"
                    state.checkoutBusy -> "Recording payment once"
                    state.preparedHeldCheckout != null -> "Finish the held bill first"
                    state.heldSelectionBlocked -> "Finishing previous payment"
                    !canWrite -> "View-only POS"
                    !state.canCollectPayment -> "Payment unavailable"
                    !state.online && state.draftState in setOf(
                        SyncState.PREPARING,
                        SyncState.AWAITING_PAYMENT,
                    ) -> "RECONNECT TO FINISH BILL"
                    state.draftState == SyncState.PREPARING -> "RESUME SERVER CHECK"
                    state.draftState == SyncState.AWAITING_PAYMENT -> "REVIEW VERIFIED BILL"
                    else -> "PAY · ${state.estimatedDueMinor.asRupees()}"
                },
                onClick = onPay,
                enabled = canWrite && state.cart.isNotEmpty() && state.canCollectPayment &&
                    !state.checkoutBusy && state.preparedHeldCheckout == null &&
                    !state.heldSelectionBlocked &&
                    (state.online || state.draftState == SyncState.DRAFT),
                busy = state.checkoutBusy,
                intent = ActionIntent.Primary,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            )
            if (state.checkoutBusy) {
                Text(
                    "One checkout is already in progress. Wait for its confirmation before taking another payment.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Information,
                )
            }
            if (
                !state.online && state.draftState in setOf(
                    SyncState.PREPARING,
                    SyncState.AWAITING_PAYMENT,
                )
            ) {
                Text(
                    "This bill already exists on the server. Reconnect to refresh its exact total, take payment, or void it safely.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Warning,
                )
            }
            if (state.cart.isNotEmpty() && !state.canCollectPayment) {
                Text(
                    state.shiftAccessMessage ?: "Open a shift before taking payment.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Warning,
                )
            }
        }
    }
}

/**
 * Cash first, because that is what the cafe actually takes. Change due is
 * computed as the cashier types, which is the single most-used number at the
 * counter and the thing staff most often get wrong under pressure.
 */
internal fun cashTenderPresets(dueMinor: Long): List<Long> {
    if (dueMinor <= 0L) return emptyList()

    fun roundUp(value: Long, step: Long): Long {
        val remainder = value % step
        return if (remainder == 0L) value else value + (step - remainder)
    }

    // Exact payment plus the next ₹100 and ₹500 amounts cover the common
    // counter handovers without making staff do mental change arithmetic.
    return listOf(
        dueMinor,
        roundUp(dueMinor, 10_000L),
        roundUp(dueMinor, 50_000L),
    ).distinct()
}

internal fun pointsEntryError(pointsText: String, lastSyncedBalance: Int?): String? {
    val requested = pointsText.toIntOrNull()
        ?: return "Enter a whole number of points."
    if (requested < 0) return "Points cannot be negative."
    if (lastSyncedBalance != null && requested > lastSyncedBalance) {
        return "Only $lastSyncedBalance points are shown available."
    }
    return null
}

@Composable
private fun PayDialog(
    dueMinor: Long,
    online: Boolean,
    offlineAllowed: Boolean,
    confirmEnabled: Boolean,
    verifiedSharedOrder: Boolean = false,
    paymentSubject: String? = null,
    confirmationIdentity: String? = null,
    subtotalMinor: Long? = null,
    discountMinor: Long? = null,
    taxMinor: Long? = null,
    roundOffMinor: Long? = null,
    totalMinor: Long? = null,
    loyaltyPointsBalance: Int? = null,
    pointsRedeemed: Int = 0,
    pointsRedeemedMinor: Long = 0,
    showCustomerBenefits: Boolean = true,
    onApplyPoints: ((Int) -> Unit)? = null,
    onDismiss: () -> Unit,
    onVoid: (() -> Unit)? = null,
    onConfirm: (String, Long) -> Unit,
) {
    var method by rememberSaveable(confirmationIdentity) { mutableStateOf("cash") }
    var tendered by rememberSaveable(confirmationIdentity) { mutableStateOf("") }
    var confirmationConsumed by remember(confirmationIdentity) { mutableStateOf(false) }
    var editingPoints by rememberSaveable(confirmationIdentity) { mutableStateOf(false) }
    var pointsText by rememberSaveable(confirmationIdentity) {
        mutableStateOf(pointsRedeemed.takeIf { it > 0 }?.toString().orEmpty())
    }
    val oneShotConfirmation = remember(confirmationIdentity) {
        confirmationIdentity?.let(::OneShotHeldPaymentConfirmation)
    }

    val parsedTenderedMinor = remember(tendered) { parseRupeesToMinor(tendered) }
    val tenderedMinor = parsedTenderedMinor ?: 0L
    val changeMinor = tenderedMinor - dueMinor
    val cashInvalid = method == "cash" && (parsedTenderedMinor == null || changeMinor < 0)
    val connectionRequired = !online && !offlineAllowed
    val confirmLabel = when {
        confirmationConsumed -> "Payment submitted once"
        connectionRequired -> "Reconnect to continue"
        dueMinor <= 0L -> "No payable balance"
        method == "cash" && parsedTenderedMinor == null -> "Enter cash received"
        method == "cash" && changeMinor < 0 -> "Cash received is below the total"
        !confirmEnabled -> "Payment unavailable"
        else -> "CONFIRM ${method.paymentMethodLabel().uppercase(Locale.getDefault())} · ${dueMinor.asRupees()}"
    }

    AlertDialog(
        onDismissRequest = { if (!confirmationConsumed) onDismiss() },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = {
            Text(
                paymentSubject?.let { "$it · ${dueMinor.asRupees()}" }
                    ?: "Take payment · ${dueMinor.asRupees()}",
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (verifiedSharedOrder) {
                    Text(
                        "Live total verified and reserved for this checkout. Confirm only after " +
                            "receiving this exact amount.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Good,
                    )
                }
                if (totalMinor != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised)
                            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        subtotalMinor?.let { PaymentAmountRow("Subtotal", it) }
                        val nonPointsDiscount = ((discountMinor ?: 0L) - pointsRedeemedMinor)
                            .coerceAtLeast(0L)
                        nonPointsDiscount.takeIf { it > 0L }?.let {
                            PaymentAmountRow("Other discounts", -it, Brand.Good)
                        }
                        pointsRedeemedMinor.takeIf { it > 0L }?.let {
                            PaymentAmountRow(
                                if (showCustomerBenefits) {
                                    "Loyalty points ($pointsRedeemed)"
                                } else {
                                    "Legacy customer credit"
                                },
                                -it,
                                Brand.Good,
                            )
                        }
                        taxMinor?.takeIf { it != 0L }?.let { PaymentAmountRow("Tax", it) }
                        roundOffMinor?.takeIf { it != 0L }?.let { PaymentAmountRow("Round-off", it) }
                        HorizontalDivider(color = Brand.BorderSubtle)
                        PaymentAmountRow("Total", totalMinor, Brand.Foreground, emphasized = true)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card").forEach { (id, label) ->
                        FilterChip(
                            selected = method == id,
                            enabled = !confirmationConsumed && confirmEnabled,
                            onClick = { method = id },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = Radius.shapePill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.InformationMuted,
                                selectedLabelColor = Brand.Foreground,
                            ),
                        )
                    }
                }
                if (showCustomerBenefits && onApplyPoints != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised)
                            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Loyalty points",
                                    color = Brand.Foreground,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    loyaltyPointsBalance?.let {
                                        "$it last synced · worth ${(it.toLong() * MINOR_PER_POINT).asRupees()}"
                                    } ?: "Balance will be verified by the server",
                                    color = Brand.ForegroundMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (pointsRedeemed > 0) {
                                OperationalStatusBadge("$pointsRedeemed applied", UiTone.Success)
                            }
                        }

                        if (editingPoints) {
                            val pointsError = pointsEntryError(pointsText, loyaltyPointsBalance)
                            val applyEnteredPoints = {
                                if (pointsError == null) {
                                    val requested = requireNotNull(pointsText.toIntOrNull())
                                    editingPoints = false
                                    onApplyPoints(requested)
                                }
                            }
                            OutlinedTextField(
                                value = pointsText,
                                onValueChange = { pointsText = it.filter(Char::isDigit).take(7) },
                                label = { Text("Points to use") },
                                supportingText = {
                                    Text(
                                        pointsError
                                            ?: "10 points = ₹1 · the server verifies the live balance",
                                    )
                                },
                                isError = pointsError != null,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { applyEnteredPoints() }),
                                singleLine = true,
                                enabled = !confirmationConsumed && confirmEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                loyaltyPointsBalance?.takeIf { it > 0 }?.let { balance ->
                                    ErpButton(
                                        text = "Use all $balance",
                                        onClick = { pointsText = balance.toString() },
                                        intent = ActionIntent.Quiet,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                ErpButton(
                                    text = "Apply points",
                                    onClick = applyEnteredPoints,
                                    intent = ActionIntent.Secondary,
                                    enabled = pointsError == null &&
                                        !confirmationConsumed && confirmEnabled,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                ErpButton(
                                    text = if (pointsRedeemed > 0) "Adjust points" else "Use points",
                                    onClick = {
                                        pointsText = pointsRedeemed.takeIf { it > 0 }
                                            ?.toString()
                                            ?: loyaltyPointsBalance?.takeIf { it > 0 }?.toString().orEmpty()
                                        editingPoints = true
                                    },
                                    intent = ActionIntent.Secondary,
                                    enabled = !confirmationConsumed && confirmEnabled &&
                                        ((loyaltyPointsBalance ?: 0) > 0 || pointsRedeemed > 0),
                                    modifier = Modifier.weight(1f),
                                )
                                if (pointsRedeemed > 0) {
                                    ErpButton(
                                        text = "Remove",
                                        onClick = { onApplyPoints(0) },
                                        intent = ActionIntent.Quiet,
                                        enabled = !confirmationConsumed && confirmEnabled,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                if (method == "cash") {
                    // Keep the counter's most important result above the
                    // touch keypad. On a 600dp landscape tablet the keypad is
                    // intentionally scrollable, but staff must see the change
                    // before confirming without having to hunt below it.
                    AnimatedVisibility(
                        tendered.isNotBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        if (changeMinor >= 0) {
                            Text(
                                "Change due  ${changeMinor.asRupees()}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Brand.Good,
                            )
                        } else {
                            Text(
                                "Short by  ${(-changeMinor).asRupees()}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Brand.Danger,
                            )
                        }
                    }
                    TouchMoneyEntry(
                        value = tendered,
                        onValueChange = { tendered = it },
                        enabled = !confirmationConsumed,
                        label = "Cash received (₹)",
                        presetsMinor = cashTenderPresets(dueMinor),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!online) {
                    Text(
                        if (offlineAllowed) {
                            "Offline: this saves a provisional sale on this tablet; it does not " +
                                "print a receipt. The official receipt number is issued when the " +
                                "tablet reconnects and the server confirms it."
                        } else {
                            "Reconnect before taking this payment. Shared orders can change on " +
                                "another device, so an offline cached total is not safe to collect."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Warning,
                    )
                }
                if (confirmationConsumed) {
                    Text(
                        "This payment was submitted once. Keep the app open while the saved payment is confirmed; do not collect again.",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        color = Brand.Information,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = !confirmationConsumed && confirmEnabled && !cashInvalid &&
                    !connectionRequired && dueMinor > 0L,
                onClick = {
                    val consumed = oneShotConfirmation?.tryConsume(requireNotNull(confirmationIdentity))
                        ?: !confirmationConsumed
                    if (consumed) {
                        // Immediate local state disables the button before the
                        // ViewModel/Room round trip can trigger recomposition.
                        confirmationConsumed = true
                        onConfirm(method, if (method == "cash") tenderedMinor else dueMinor)
                    }
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                onVoid?.let {
                    TextButton(onClick = it, enabled = !confirmationConsumed) {
                        Text("Void bill", color = Brand.Danger)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !confirmationConsumed) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PaymentAmountRow(
    label: String,
    amountMinor: Long,
    valueColor: androidx.compose.ui.graphics.Color = Brand.Foreground,
    emphasized: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (emphasized) Brand.Foreground else Brand.ForegroundMuted,
            style = if (emphasized) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
        NumericValue(
            value = amountMinor.asRupees(),
            style = if (emphasized) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

@Composable
private fun DirectZeroTotalCompletionDialog(
    checkout: PreparedDirectCheckout,
    online: Boolean,
    confirmEnabled: Boolean,
    showCustomerBenefits: Boolean,
    onDismiss: () -> Unit,
    onVoid: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    var confirmationConsumed by remember(checkout.orderId) { mutableStateOf(false) }
    val oneShot = remember(checkout.orderId) {
        OneShotHeldPaymentConfirmation(checkout.orderId)
    }
    AlertDialog(
        onDismissRequest = { if (!confirmationConsumed) onDismiss() },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text("Complete bill · ₹0.00 due") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    if (showCustomerBenefits) {
                        "The server verified that discounts or customer benefits cover this bill in full."
                    } else {
                        "The server verified that recorded discounts or legacy credits cover this bill in full."
                    },
                    color = Brand.Good,
                    style = MaterialTheme.typography.labelMedium,
                )
                PaymentAmountRow("Subtotal", checkout.subtotalMinor)
                val nonPointsDiscount = (checkout.discountMinor - checkout.pointsRedeemedMinor)
                    .coerceAtLeast(0L)
                if (nonPointsDiscount > 0L) {
                    PaymentAmountRow("Other discounts", -nonPointsDiscount, Brand.Good)
                }
                if (checkout.pointsRedeemedMinor > 0L) {
                    PaymentAmountRow(
                        if (showCustomerBenefits) {
                            "Loyalty points (${checkout.pointsRedeemed})"
                        } else {
                            "Legacy customer credit"
                        },
                        -checkout.pointsRedeemedMinor,
                        Brand.Good,
                    )
                }
                PaymentAmountRow("Total", checkout.totalMinor, emphasized = true)
                Text(
                    "Collect no money. Completing issues the final receipt and consumes the benefit once.",
                    color = Brand.ForegroundMuted,
                )
                if (!online) {
                    Text("Reconnect before completing this bill.", color = Brand.Warning)
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = online && confirmEnabled && !confirmationConsumed,
                onClick = {
                    if (oneShot.tryConsume(checkout.orderId)) {
                        confirmationConsumed = true
                        onConfirm()
                    }
                },
            ) { Text(if (confirmationConsumed) "Submitted once" else "COMPLETE · ₹0.00") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                onVoid?.let {
                    TextButton(onClick = it, enabled = !confirmationConsumed) {
                        Text("Void bill", color = Brand.Danger)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !confirmationConsumed) { Text("Cancel") }
            }
        },
    )
}

/**
 * Exact-zero held orders are completion actions, not payments. Keeping this
 * separate from [PayDialog] prevents staff from choosing cash/UPI or being
 * told that money was received when a member benefit covered the whole bill.
 */
@Composable
private fun ZeroTotalCompletionDialog(
    checkout: PreparedHeldCheckout,
    online: Boolean,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onVoid: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    var confirmationConsumed by remember(checkout.orderId) { mutableStateOf(false) }
    val oneShotConfirmation = remember(checkout.orderId) {
        OneShotHeldPaymentConfirmation(checkout.orderId)
    }
    val confirmLabel = when {
        confirmationConsumed -> "Completion submitted once"
        !online -> "Reconnect to complete"
        !confirmEnabled -> "Completion unavailable"
        else -> "COMPLETE BENEFIT · ₹0 DUE"
    }

    AlertDialog(
        onDismissRequest = { if (!confirmationConsumed) onDismiss() },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = {
            Text(
                checkout.sourceLabel?.let { "$it · ₹0.00 due" }
                    ?: "Member benefit · ₹0.00 due",
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Live member-benefit total verified and reserved for this checkout.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Good,
                )
                Text(
                    "Collect no money.",
                    fontWeight = FontWeight.Bold,
                    color = Brand.Foreground,
                )
                Text(
                    "Complete this exact bill to consume the reserved benefit and issue its " +
                        "final invoice. This action does not create a cash, UPI, or card payment.",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    if (online) {
                        "Online confirmation is required because the benefit and invoice are " +
                            "completed together on the server."
                    } else {
                        "Offline: reconnect before completing this member benefit. Nothing is " +
                            "queued or consumed until the server confirms it."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (online) Brand.ForegroundMuted else Brand.Warning,
                )
                if (confirmationConsumed) {
                    Text(
                        "Completion was submitted once. Wait for server confirmation; do not repeat this action.",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        color = Brand.Information,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = !confirmationConsumed && confirmEnabled && online,
                onClick = {
                    if (oneShotConfirmation.tryConsume(checkout.orderId)) {
                        confirmationConsumed = true
                        onConfirm()
                    }
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                onVoid?.let {
                    TextButton(onClick = it, enabled = !confirmationConsumed) {
                        Text("Void bill", color = Brand.Danger)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !confirmationConsumed) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun QtyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, tween(Motion.fast), label = "qtyBtnScale")
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(48.dp)
            .clip(Radius.shapeMd)
            .background(Brand.SurfaceRaised)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Brand.Foreground, fontWeight = FontWeight.Bold)
    }
}

private fun Long.asPosTime(): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))

internal fun String.paymentMethodLabel(): String = when (lowercase(Locale.ROOT)) {
    "cash" -> "Cash"
    "upi" -> "UPI"
    "card" -> "Card"
    else -> replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }
}
