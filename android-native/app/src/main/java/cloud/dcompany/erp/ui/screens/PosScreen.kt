package cloud.dcompany.erp.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.HeldOrderPaymentState
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.checkout.HeldOrderClaimPolicy
import cloud.dcompany.erp.core.checkout.OneShotHeldPaymentConfirmation
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.EmptyState
import cloud.dcompany.erp.ui.components.PrimaryButton
import cloud.dcompany.erp.ui.components.TouchMoneyEntry
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.screens.gaming.OperationalAlarmPermissionCard
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
    access: PosAccess,
    onAccessChanged: (PosAccess) -> Unit,
    onAdd: (MenuItemEntity) -> Unit,
    onRemove: (MenuItemEntity) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClearCart: () -> Unit,
    onRefresh: () -> Unit,
    onCapture: (String, Long) -> Unit,
    onRetryRejectedSale: (String) -> Unit,
    onRetryHeldPayment: (String) -> Unit,
    onPrepareHeldOrder: (HeldOrderCacheEntity) -> Unit,
    onConfirmHeldOrder: (String, String, Long) -> Unit,
    onConfirmHeldOrderZero: (String) -> Unit,
    onDismissHeldOrder: () -> Unit,
    onDismissNotice: () -> Unit,
    onFocusOldestOverdue: () -> Unit,
    onSnoozeOverdue: () -> Unit,
    onUnmuteOverdue: () -> Unit,
    onDismissHeldFocus: () -> Unit,
) {
    var showPay by remember { mutableStateOf(false) }
    val latestDismissHeldOrder by rememberUpdatedState(onDismissHeldOrder)
    SideEffect { onAccessChanged(access) }

    LaunchedEffect(access.canCreateAndCollect, state.preparedHeldCheckout?.orderId) {
        if (!access.canCreateAndCollect) {
            showPay = false
            if (state.preparedHeldCheckout != null) onDismissHeldOrder()
        }
    }

    // Navigation, logout, or an Activity recreation can remove this screen
    // without the dialog's Cancel button running. Release the short-lived
    // lease in that case so another cashier is not blocked for its full TTL.
    DisposableEffect(Unit) {
        onDispose { latestDismissHeldOrder() }
    }

    Column(Modifier.fillMaxSize()) {
        SyncBanner(state, onRefresh)
        if (!access.canCreateAndCollect) {
            ViewOnlyNotice("POS is view only — ask a cashier or manager to create or collect an order.")
        }
        state.shiftAccessMessage?.let { ShiftAccessBanner(it) }
        if (state.heldOrders.isNotEmpty()) {
            OperationalAlarmPermissionCard(contextLabel = "Held-order")
        }
        if (state.rejectedDirectSales.isNotEmpty()) {
            RejectedDirectSalesStrip(
                sales = state.rejectedDirectSales,
                retryingIds = state.retryingRejectedSaleIds,
                online = state.online,
                canRetry = access.canCreateAndCollect,
                onRetry = onRetryRejectedSale,
            )
        }
        if (state.heldPaymentStatuses.isNotEmpty()) {
            HeldPaymentStatusStrip(
                payments = state.heldPaymentStatuses,
                retryingIds = state.retryingHeldPaymentIds,
                online = state.online,
                canRetry = access.canCreateAndCollect,
                onSyncPending = onRefresh,
                onRetryRejected = onRetryHeldPayment,
            )
        }
        if (state.heldOrders.isNotEmpty()) {
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
            HeldOrdersStrip(
                orders = state.heldOrders,
                overdueOrderIds = state.overdueHeldOrderIds.toSet(),
                focusedOrderId = state.focusedHeldOrderId,
                preparingOrderId = state.preparingHeldOrderId,
                enabled = state.canCollectPayment &&
                    access.canCreateAndCollect &&
                    !state.checkoutBusy && !state.heldSelectionBlocked &&
                    state.preparedHeldCheckout == null,
                onSelect = onPrepareHeldOrder,
                onDismissFocus = onDismissHeldFocus,
            )
        }

        if (state.menuEmpty) {
            EmptyState(
                title = if (state.everSynced) "The menu is empty" else "No menu on this tablet yet",
                body = if (state.everSynced) {
                    "This tablet is up to date — there are no menu items set up in the ERP " +
                        "yet. Add items on the Menu screen, then sync."
                } else {
                    "Connect once to download the menu. After that the till works offline."
                },
            )
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                PrimaryButton(onClick = onRefresh) {
                    Text(if (state.everSynced) "Check again" else "Download menu")
                }
            }
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(Spacing.md)) {
                CategoryStrip(state, onSelectCategory)
                Spacer(Modifier.height(Spacing.md))
                LazyVerticalGrid(
                    // Adaptive rather than a fixed count, so one build fits an
                    // 8" tablet and a 12" one.
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(state.visibleItems, key = { it.id }) { item ->
                        MenuTile(
                            item = item,
                            enabled = access.canCreateAndCollect,
                            modifier = Modifier.animateItem(),
                        ) { onAdd(item) }
                    }
                }
            }
            CartPanel(
                state = state,
                canWrite = access.canCreateAndCollect,
                onAdd = onAdd,
                onRemove = onRemove,
                onClear = onClearCart,
            ) { showPay = true }
        }
    }

    if (showPay && access.canCreateAndCollect) {
        PayDialog(
            dueMinor = state.estimateMinor,
            online = state.online,
            // A local menu price is not authoritative enough to collect
            // money: discounts, membership benefits, and another terminal's
            // changes are resolved by the server. Staff can keep building the
            // cart offline, but payment waits for a verified connection.
            offlineAllowed = false,
            confirmEnabled = state.canCollectPayment && !state.checkoutBusy,
            onDismiss = { showPay = false },
            onConfirm = { method, tendered ->
                showPay = false
                onCapture(method, tendered)
            },
        )
    }

    state.preparedHeldCheckout?.takeIf { access.canCreateAndCollect }?.let { checkout ->
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
                    onDismiss = onDismissHeldOrder,
                    onConfirm = { method, tendered ->
                        onConfirmHeldOrder(checkout.orderId, method, tendered)
                    },
                )
            }
        }
    }

    state.notice?.let { message ->
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
    Row(
        Modifier.fillMaxWidth()
            .background(Brand.SurfaceRaised)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            "$count overdue held order${if (count == 1) "" else "s"} · banner snoozed until $until",
            modifier = Modifier.weight(1f),
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        TextButton(onClick = onView) { Text("View") }
        TextButton(onClick = onUnmute) { Text("Unmute") }
    }
}

@Composable
private fun OverdueHeldOrdersBanner(
    count: Int,
    onView: () -> Unit,
    onSnooze: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Brand.Danger.copy(alpha = 0.18f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "$count held order${if (count == 1) "" else "s"} waiting over 15 minutes",
                color = Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Review the oldest bill. Snooze hides this banner for five minutes only; the overdue count stays visible.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(onClick = onView) { Text("View") }
        TextButton(onClick = onSnooze) { Text("Snooze 5 min") }
    }
}

@Composable
private fun ShiftAccessBanner(message: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brand.GoldMuted)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "POS payment locked for this account",
            color = Brand.Background,
            fontWeight = FontWeight.Bold,
        )
        Text(message, color = Brand.Background, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Offline status has to be unmissable. A cashier who does not know the tablet
 * is offline cannot know why the saved sale has no invoice number, and a queue
 * that is silently stuck is worse than one that is loudly stuck.
 */
@Composable
private fun SyncBanner(state: PosUiState, onRefresh: () -> Unit) {
    val (bg, label) = when {
        state.rejectedDirectSales.isNotEmpty() && state.heldRejectedCount > 0 ->
            Brand.Danger to "${state.rejectedDirectSales.size} direct sale(s) and " +
                "${state.heldRejectedCount} held payment(s) need manager review"
        state.rejectedDirectSales.isNotEmpty() ->
            Brand.Danger to "${state.rejectedDirectSales.size} direct sale(s) refused — " +
                "review below; Sync now does not retry them"
        state.heldRejectedCount > 0 ->
            Brand.Danger to "${state.heldRejectedCount} held payment(s) refused — needs owner review"
        !state.online && state.pendingCount > 0 ->
            Brand.GoldMuted to "Offline · ${state.pendingCount} sale(s) saved on this tablet"
        !state.online ->
            Brand.GoldMuted to "Offline · sales are saved here and sent when the link returns"
        state.pendingCount > 0 ->
            Brand.GoldMuted to "Sending ${state.pendingCount} saved sale(s)…"
        else -> return
    }
    val animatedBg by animateColorAsState(bg, tween(Motion.medium), label = "syncBannerBg")
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            Modifier.fillMaxWidth().background(animatedBg).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Brand.Background, fontWeight = FontWeight.SemiBold)
            if (state.online && state.rejectedCount == 0 && state.pendingCount > 0) {
                TextButton(onClick = onRefresh) { Text("Sync now", color = Brand.Background) }
            }
        }
    }
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
        Modifier.fillMaxWidth()
            .background(Brand.DangerMuted)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
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
            color = Brand.Foreground,
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
                    OutlinedButton(
                        onClick = { onRetry(sale.localId) },
                        enabled = canRetry && online && !retrying,
                        shape = Radius.shapePill,
                    ) {
                        Text(
                            when {
                                retrying -> "Retrying original sale…"
                                !online -> "Reconnect to retry"
                                else -> "Retry after fix"
                            },
                        )
                    }
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
        Modifier.fillMaxWidth()
            .background(if (hasRejection) Brand.DangerMuted else Brand.GoldMuted)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            "Saved held-order payments (${payments.size})",
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Money was already marked received for these Tables/Gaming orders. Never collect it " +
                "again. Account switching stays locked until each saved payment is confirmed or reconciled.",
            color = Brand.Foreground,
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
                        color = if (rejected) Brand.Danger else Brand.Gold,
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
                    OutlinedButton(
                        onClick = {
                            if (rejected) onRetryRejected(payment.localId) else onSyncPending()
                        },
                        enabled = canRetry && online && !retrying,
                        shape = Radius.shapePill,
                    ) {
                        Text(
                            when {
                                retrying -> "Retrying original payment…"
                                !online -> "Reconnect to retry confirmation"
                                rejected -> "Retry after fix"
                                else -> "Retry server confirmation"
                            },
                        )
                    }
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
                                focused -> Brand.GoldMuted
                                overdue -> Brand.Danger.copy(alpha = 0.16f)
                                else -> Brand.SurfaceRaised
                            },
                        )
                        .then(
                            if (focused) Modifier.border(2.dp, Brand.Gold, Radius.shapeMd)
                            else Modifier,
                        )
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
                            "Checking live bill…"
                        } else if (overdue) {
                            "Overdue · ${order.itemsCount} item(s) · due ${order.dueMinor.asRupees()}"
                        } else {
                            "${order.itemsCount} item(s) · due ${order.dueMinor.asRupees()}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryStrip(state: PosUiState, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item {
            FilterChip(
                selected = state.selectedCategoryId == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                shape = Radius.shapePill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                ),
            )
        }
        items(state.categories, key = { it.id }) { category ->
            FilterChip(
                selected = state.selectedCategoryId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text(category.name) },
                shape = Radius.shapePill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                ),
            )
        }
    }
}

/** Scales down slightly on press — the one micro-interaction a cashier taps
 * hundreds of times a shift, so it's worth it being responsive-feeling even
 * though the tile itself is a plain, high-density grid item. */
@Composable
private fun MenuTile(
    item: MenuItemEntity,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(Motion.fast, easing = Motion.emphasized), label = "tileScale")
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.55f
            }
            .clip(Radius.shapeLg)
            .background(Brand.SurfaceRaised)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.Foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.basePriceMinor.asRupees(),
            style = MaterialTheme.typography.labelLarge,
            color = Brand.Gold,
        )
    }
}

@Composable
private fun CartPanel(
    state: PosUiState,
    canWrite: Boolean,
    onAdd: (MenuItemEntity) -> Unit,
    onRemove: (MenuItemEntity) -> Unit,
    onClear: () -> Unit,
    onPay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxSize()
            .background(Brand.Surface)
            .padding(Spacing.lg),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cart", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            AnimatedVisibility(state.cart.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = canWrite,
                    shape = Radius.shapePill,
                ) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (state.cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(
                    if (canWrite) "Tap an item to start" else "View-only menu",
                    color = Brand.ForegroundMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.cart, key = { it.item.id }) { line ->
                    Row(
                        Modifier.fillMaxWidth().animateItem(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                line.item.name,
                                color = Brand.Foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (line.item.basePriceMinor * line.qty).asRupees(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.ForegroundMuted,
                            )
                        }
                        QtyButton("−", enabled = canWrite) { onRemove(line.item) }
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
                        QtyButton("+", enabled = canWrite) { onAdd(line.item) }
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Estimate", color = Brand.ForegroundMuted)
            AnimatedContent(
                targetState = state.estimateMinor,
                transitionSpec = { fadeIn(tween(Motion.fast)).togetherWith(fadeOut(tween(Motion.fast))) },
                label = "estimate",
            ) { minor ->
                Text(minor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Server confirms the final taxed total",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        Spacer(Modifier.height(Spacing.sm))
        PrimaryButton(
            onClick = onPay,
            enabled = canWrite && state.cart.isNotEmpty() &&
                state.canCollectPayment && !state.checkoutBusy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (state.checkoutBusy) "Saving sale…"
                else if (!canWrite) "View-only POS"
                else if (!state.canCollectPayment) "Payment locked"
                else "Take payment · ${state.cartCount} item${if (state.cartCount == 1) "" else "s"}",
            )
        }
        if (state.cart.isNotEmpty() && !state.canCollectPayment) {
            Text(
                state.shiftAccessMessage ?: "Open a shift before taking payment.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.GoldMuted,
                modifier = Modifier.padding(top = Spacing.xs),
            )
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

@Composable
private fun PayDialog(
    dueMinor: Long,
    online: Boolean,
    offlineAllowed: Boolean,
    confirmEnabled: Boolean,
    verifiedSharedOrder: Boolean = false,
    paymentSubject: String? = null,
    confirmationIdentity: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var method by rememberSaveable(confirmationIdentity) { mutableStateOf("cash") }
    var tendered by rememberSaveable(confirmationIdentity) { mutableStateOf("") }
    var confirmationConsumed by remember(confirmationIdentity) { mutableStateOf(false) }
    val oneShotConfirmation = remember(confirmationIdentity) {
        confirmationIdentity?.let(::OneShotHeldPaymentConfirmation)
    }

    val parsedTenderedMinor = remember(tendered) { parseRupeesToMinor(tendered) }
    val tenderedMinor = parsedTenderedMinor ?: 0L
    val changeMinor = tenderedMinor - dueMinor
    val cashInvalid = method == "cash" && (parsedTenderedMinor == null || changeMinor < 0)
    val connectionRequired = !online && !offlineAllowed
    val processing = confirmationConsumed || !confirmEnabled

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
                        "Live total verified and reserved for this till. Confirm only after " +
                            "receiving this exact amount.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Good,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card").forEach { (id, label) ->
                        FilterChip(
                            selected = method == id,
                            enabled = !confirmationConsumed,
                            onClick = { method = id },
                            label = { Text(label) },
                            shape = Radius.shapePill,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                        )
                    }
                }

                if (method == "cash") {
                    TouchMoneyEntry(
                        value = tendered,
                        onValueChange = { tendered = it },
                        enabled = !confirmationConsumed,
                        label = "Cash received (₹)",
                        presetsMinor = cashTenderPresets(dueMinor),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AnimatedVisibility(tendered.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
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
                }

                if (!online) {
                    Text(
                        if (offlineAllowed) {
                            "Offline: this saves a provisional sale on this tablet; it does not " +
                                "print a receipt. The GST tax-invoice number is issued when the " +
                                "tablet reconnects and the server confirms it."
                        } else {
                            "Reconnect before taking this payment. Shared orders can change on " +
                                "another device, so an offline cached total is not safe to collect."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.GoldMuted,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = !processing && !cashInvalid && !connectionRequired && dueMinor > 0L,
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
            ) { Text(if (processing) "Saving once…" else "Payment received") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !confirmationConsumed) { Text("Cancel") }
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
    onConfirm: () -> Unit,
) {
    var confirmationConsumed by remember(checkout.orderId) { mutableStateOf(false) }
    val oneShotConfirmation = remember(checkout.orderId) {
        OneShotHeldPaymentConfirmation(checkout.orderId)
    }
    val processing = confirmationConsumed || !confirmEnabled

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
                    "Live member-benefit total verified and reserved for this till.",
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
                    color = if (online) Brand.ForegroundMuted else Brand.GoldMuted,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = !processing && online,
                onClick = {
                    if (oneShotConfirmation.tryConsume(checkout.orderId)) {
                        confirmationConsumed = true
                        onConfirm()
                    }
                },
            ) {
                Text(
                    if (confirmationConsumed) "Completing once…"
                    else "Complete member benefit · ₹0 due",
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !confirmationConsumed) { Text("Cancel") }
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
        Text(label, color = Brand.Gold, fontWeight = FontWeight.Bold)
    }
}

private fun Long.asPosTime(): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))

private fun String.paymentMethodLabel(): String = when (lowercase(Locale.ROOT)) {
    "cash" -> "Cash"
    "upi" -> "UPI"
    "card" -> "Card"
    else -> replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }
}
