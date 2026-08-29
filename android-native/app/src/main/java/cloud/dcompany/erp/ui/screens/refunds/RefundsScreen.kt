package cloud.dcompany.erp.ui.screens.refunds

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AssignmentReturn
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.money.minorToRupeesInput
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.SearchInput
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

@Composable
fun RefundsScreen(vm: RefundsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var beginHandoff by remember { mutableStateOf<RefundTask?>(null) }
    var confirmCash by remember { mutableStateOf<RefundTask?>(null) }
    var beginProvider by remember { mutableStateOf<RefundTask?>(null) }
    var confirmProvider by remember { mutableStateOf<RefundTask?>(null) }
    var cancelRequest by remember { mutableStateOf<RefundTask?>(null) }
    var withdrawTask by remember { mutableStateOf<RefundTask?>(null) }
    var resolveCashTask by remember { mutableStateOf<RefundTask?>(null) }
    var withdrawProviderTask by remember { mutableStateOf<RefundTask?>(null) }
    var resolveProviderTask by remember { mutableStateOf<RefundTask?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        AdaptiveStatGrid(count = 3) { index, modifier ->
            when (index) {
                0 -> CompactStatCard(
                    label = "Refundable orders",
                    value = state.orders.size.toString(),
                    detail = "${state.orders.sumOf { it.refundableMinor }.asRupees()} available",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    tone = UiTone.Information,
                    modifier = modifier,
                )
                1 -> CompactStatCard(
                    label = "Open tasks",
                    value = state.tasks.size.toString(),
                    detail = if (state.tasks.isEmpty()) "No payout recovery pending" else "Resolve each exact task",
                    icon = Icons.AutoMirrored.Filled.AssignmentReturn,
                    tone = if (state.tasks.isEmpty()) UiTone.Success else UiTone.Warning,
                    modifier = modifier,
                )
                else -> CompactStatCard(
                    label = "Money access",
                    value = when {
                        !state.online -> "Offline"
                        state.canManageMoney -> "Ready"
                        else -> "Locked"
                    },
                    detail = when {
                        !state.online -> "Requests queue; cash payout waits"
                        state.canManageMoney -> "Server reachable"
                        else -> "Shift authority required"
                    },
                    icon = when {
                        !state.online -> Icons.Default.CloudOff
                        state.canManageMoney -> Icons.Default.Payments
                        else -> Icons.Default.Lock
                    },
                    tone = when {
                        !state.online -> UiTone.Warning
                        state.canManageMoney -> UiTone.Success
                        else -> UiTone.Danger
                    },
                    modifier = modifier,
                )
            }
        }

        state.moneyAccessMessage?.let { message ->
            OperationalBanner(
                title = "Refund money actions locked",
                detail = message,
                tone = UiTone.Danger,
                icon = Icons.Default.Lock,
            )
        }
        if (!state.online) {
            OperationalBanner(
                title = "Offline — no new payout is authorised",
                detail = "Requests can be preserved locally, but cash and provider payout starts require live server acceptance.",
                tone = UiTone.Warning,
                icon = Icons.Default.CloudOff,
            )
        }

        ActionBar(
            leading = {
                SearchInput(
                    value = state.query,
                    onValueChange = vm::search,
                    placeholder = "Find by invoice number",
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy,
                )
            },
            trailing = {
                ErpButton(
                    text = if (state.busy) "Checking…" else "Refresh",
                    onClick = vm::load,
                    intent = ActionIntent.Secondary,
                    enabled = !state.busy,
                    busy = state.busy,
                    leadingIcon = Icons.Default.Refresh,
                )
            },
        )

        SectionCard(
            modifier = Modifier.weight(1f),
            title = "Refund workspace",
            subtitle = "Resolve open tasks first, then select a paid order with a server-known refundable balance",
            icon = Icons.AutoMirrored.Filled.AssignmentReturn,
            elevated = true,
            contentPadding = PaddingValues(0.dp),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.tasks.isNotEmpty()) {
                    item("task-heading") {
                        Column(
                            Modifier.fillMaxWidth().background(Brand.SurfaceRaised)
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text("OPEN REFUND TASKS", color = Brand.ForegroundFaint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Complete or reconcile each exact task before refunding that order again.",
                                color = Brand.ForegroundMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(state.tasks, key = { "refund-task-${it.localId}" }) { task ->
                        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                            RefundTaskCard(
                                task = task,
                                busy = state.busy,
                                online = state.online,
                                protectedAccess = state.protectedAccess,
                                canManageMoney = state.canManageMoney,
                                onCheckNow = vm::load,
                                onRetry = vm::retryRejected,
                                onCancel = { cancelRequest = task },
                                onBeginHandoff = { beginHandoff = task },
                                onCashHanded = { confirmCash = task },
                                onBeginProvider = { beginProvider = task },
                                onProviderCompleted = { confirmProvider = task },
                                onWithdrawCash = { withdrawTask = task },
                                onResolveCash = { resolveCashTask = task },
                                onWithdrawProvider = { withdrawProviderTask = task },
                                onResolveProvider = { resolveProviderTask = task },
                            )
                        }
                    }
                    item("orders-divider") { PanelDivider() }
                }

                item("orders-heading") {
                    Column(
                        Modifier.fillMaxWidth().background(Brand.SurfaceRaised)
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("REFUNDABLE ORDERS", color = Brand.ForegroundFaint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Only paid orders with an available balance are shown.",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                when {
                    state.orders.isEmpty() && !state.everSynced -> item("initial-sync") {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 240.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(color = Brand.Gold)
                            Text(
                                "Waiting for the first server sync",
                                modifier = Modifier.padding(Spacing.md),
                                color = Brand.Foreground,
                            )
                            ErpButton(
                                text = "Refresh",
                                onClick = vm::load,
                                intent = ActionIntent.Secondary,
                                leadingIcon = Icons.Default.Refresh,
                            )
                        }
                    }

                    state.visible.isEmpty() -> item("empty-orders") {
                        DesignedEmptyState(
                            title = if (state.query.isNotBlank()) "No matching invoice" else "No refundable orders",
                            body = when {
                                state.query.isNotBlank() -> "No available paid order matches \"${state.query}\". Check the invoice number or clear the search."
                                state.tasks.isEmpty() -> "Paid orders with an available refundable balance will appear here after sync."
                                else -> "Resolve the open task above before refunding that order again."
                            },
                            icon = if (state.query.isNotBlank()) Icons.Default.Warning else Icons.AutoMirrored.Filled.ReceiptLong,
                            primaryLabel = if (state.query.isNotBlank()) "Clear search" else null,
                            onPrimary = if (state.query.isNotBlank()) ({ vm.search("") }) else null,
                        )
                    }

                    else -> itemsIndexed(state.visible, key = { _, order -> "refundable-order-${order.id}" }) { index, order ->
                        DataListRow(
                            onClick = if (!state.busy && state.canManageMoney) ({ vm.select(order) }) else null,
                            leading = {
                                OperationalStatusBadge("Refundable", UiTone.Success, icon = Icons.Default.Payments)
                            },
                            content = {
                                Text(
                                    order.invoiceNo ?: "No invoice number",
                                    color = Brand.Foreground,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${order.type} · collected ${order.paidMinor.asRupees()} · " +
                                        refundRailSummary(order.paymentMethods),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Brand.ForegroundMuted,
                                )
                            },
                            trailing = {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("AVAILABLE", color = Brand.ForegroundFaint, style = MaterialTheme.typography.labelSmall)
                                    Text(order.refundableMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                                }
                            },
                        )
                        if (index != state.visible.lastIndex) PanelDivider()
                    }
                }

                if (state.recentTasks.isNotEmpty()) {
                    item("history-divider") { PanelDivider() }
                    item("history-heading") {
                        Column(
                            Modifier.fillMaxWidth().background(Brand.SurfaceRaised)
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "RECENT REFUND HISTORY",
                                color = Brand.ForegroundFaint,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Server-backed receipts and the employees recorded at each money stage.",
                                color = Brand.ForegroundMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(state.recentTasks, key = { "refund-history-${it.localId}" }) { task ->
                        RefundHistoryRow(task)
                        PanelDivider()
                    }
                }
            }
        }
    }

    state.selected?.let { order -> RefundDialog(order, state.busy, state.online, vm) }

    beginHandoff?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) beginHandoff = null },
            title = { Text("Open server-confirmed handover?") },
            text = {
                Text(
                    "Verify the customer and ${task.amountMinor.asRupees()} first. This asks the server to reserve the exact drawer handover. Do not touch cash until the task changes to ‘handover in progress’.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !state.busy && state.online && state.canManageMoney,
                    onClick = {
                        beginHandoff = null
                        vm.beginCashHandoff(task.localId)
                    },
                ) { Text("Verify with server") }
            },
            dismissButton = {
                TextButton(onClick = { beginHandoff = null }, enabled = !state.busy) { Text("Not yet") }
            },
        )
    }

    confirmCash?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirmCash = null },
            title = { Text("Cash physically handed over once?") },
            text = {
                Text(
                    "Confirm only after this customer physically received ${task.amountMinor.asRupees()}. " +
                        "If the app restarted or you are unsure, stop and check the customer and drawer first—never pay a second time.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !state.busy && state.canManageMoney,
                    onClick = {
                        confirmCash = null
                        vm.confirmCashHandedOver(task.localId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text("Cash was handed over") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCash = null }, enabled = !state.busy) { Text("Stop and verify") }
            },
        )
    }

    beginProvider?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) beginProvider = null },
            title = { Text("Open server-confirmed provider payout?") },
            text = {
                Text(
                    "Verify the customer, ${task.amountMinor.asRupees()}, and original ${task.settlementMethod ?: "non-cash"} rail. " +
                        "The server must open this exact payout before you use the provider app.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !state.busy && state.online && state.canManageMoney,
                    onClick = {
                        beginProvider = null
                        vm.beginProviderPayout(task.localId)
                    },
                ) { Text("Verify with server") }
            },
            dismissButton = {
                TextButton(onClick = { beginProvider = null }, enabled = !state.busy) { Text("Not yet") }
            },
        )
    }

    confirmProvider?.let { task ->
        ProviderCompletionDialog(
            task = task,
            busy = state.busy,
            onDismiss = { confirmProvider = null },
            onConfirm = { reference ->
                confirmProvider = null
                vm.confirmProviderCompleted(task.localId, reference)
            },
        )
    }

    cancelRequest?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) cancelRequest = null },
            title = { Text("Cancel refused request?") },
            text = {
                Text(
                    "Use this only because the server refused the request and no cash or provider payout occurred. It does not create or withdraw a server refund.",
                )
            },
            confirmButton = {
                Button(
                    enabled = !state.busy,
                    onClick = {
                        cancelRequest = null
                        vm.cancelRejected(task.localId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text("Cancel refused request") }
            },
            dismissButton = {
                TextButton(onClick = { cancelRequest = null }, enabled = !state.busy) { Text("Keep task") }
            },
        )
    }

    withdrawTask?.let { task ->
        WithdrawCashDialog(
            task = task,
            busy = state.busy,
            onDismiss = { withdrawTask = null },
            onConfirm = { reason ->
                withdrawTask = null
                vm.withdrawCashRefund(task.localId, reason)
            },
        )
    }

    resolveCashTask?.let { task ->
        ResolveCashHandoffDialog(
            task = task,
            busy = state.busy,
            online = state.online,
            onDismiss = { resolveCashTask = null },
            onConfirm = { reason ->
                resolveCashTask = null
                vm.resolveStartedCashHandoff(task.localId, reason)
            },
        )
    }

    withdrawProviderTask?.let { task ->
        WithdrawProviderDialog(
            task = task,
            busy = state.busy,
            online = state.online,
            onDismiss = { withdrawProviderTask = null },
            onConfirm = { reason ->
                withdrawProviderTask = null
                vm.withdrawProviderRefund(task.localId, reason)
            },
        )
    }

    resolveProviderTask?.let { task ->
        ResolveProviderPayoutDialog(
            task = task,
            busy = state.busy,
            online = state.online,
            onDismiss = { resolveProviderTask = null },
            onConfirm = { status, reference, reason ->
                resolveProviderTask = null
                vm.resolveStartedProviderPayout(task.localId, status, reference, reason)
            },
        )
    }

    state.notice?.let { msg ->
        AlertDialog(
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
            title = { Text("Refund status") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun RefundTaskCard(
    task: RefundTask,
    busy: Boolean,
    online: Boolean,
    protectedAccess: Boolean,
    canManageMoney: Boolean,
    onCheckNow: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: () -> Unit,
    onBeginHandoff: () -> Unit,
    onCashHanded: () -> Unit,
    onBeginProvider: () -> Unit,
    onProviderCompleted: () -> Unit,
    onWithdrawCash: () -> Unit,
    onResolveCash: () -> Unit,
    onWithdrawProvider: () -> Unit,
    onResolveProvider: () -> Unit,
) {
    val reason = REFUND_REASONS.firstOrNull { it.first == task.reasonCode }?.second ?: task.reasonCode
    val created = remember(task.createdAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.createdAtMillis))
    }
    val (headline, instruction) = refundTaskCopy(task)
    val urgent = task.payoutConflict || task.state in setOf(
        RefundState.ACCEPTED_CASH_DUE,
        RefundState.CASH_HANDOFF_IN_PROGRESS,
        RefundState.CASH_SETTLE_PENDING,
        RefundState.CASH_SETTLE_REJECTED,
        RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
        RefundState.CASH_FINALIZE_REJECTED,
        RefundState.ACCEPTED_PROVIDER_DUE,
        RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
        RefundState.PROVIDER_COMPLETION_PENDING,
        RefundState.PROVIDER_COMPLETION_REJECTED,
        RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
        RefundState.PROVIDER_FINALIZE_REJECTED,
        RefundState.LEGACY_RECONCILIATION_REQUIRED,
    )
    val tone = when {
        task.state == RefundState.REQUEST_PENDING || task.state == RefundState.CASH_SETTLE_PENDING ||
            task.state == RefundState.WITHDRAWAL_PENDING -> UiTone.Information
        urgent || task.state == RefundState.REQUEST_REJECTED ||
            task.state == RefundState.WITHDRAWAL_REJECTED -> UiTone.Danger
        else -> UiTone.Warning
    }

    SectionCard(
        title = headline,
        subtitle = "${task.invoiceNo ?: "Order"} · ${task.amountMinor.asRupees()} · " +
            "${task.settlementMethod ?: task.mode ?: "unverified rail"} · $created",
        action = {
            OperationalStatusBadge(
                label = refundTaskStatusLabel(task),
                tone = tone,
                icon = if (tone == UiTone.Danger) Icons.Default.Warning else Icons.Default.Sync,
            )
        },
        elevated = true,
        contentPadding = PaddingValues(Spacing.md),
    ) {
        InfoRow("Reason", reason)
        task.acceptedByNameOrId()?.let { InfoRow("Accepted by", it) }
        task.moneyStartedByNameOrId()?.let {
            InfoRow(if (task.settlementMethod == "cash") "Cash handover started by" else "Provider payout started by", it)
        }
        task.moneyCompletedByNameOrId()?.let {
            InfoRow(if (task.settlementMethod == "cash") "Cash recorded by" else "Provider completion recorded by", it)
        }
        if (task.payoutConflict) {
            task.localPayoutAtMillis?.let { capturedAt ->
                val captured = remember(capturedAt) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(capturedAt))
                }
                InfoRow("Local payout captured", captured, valueColor = Brand.Danger)
            }
            task.externalReference?.takeIf(String::isNotBlank)?.let {
                InfoRow("Local provider reference", it, valueColor = Brand.Danger)
            }
            task.withdrawnByNameOrId()?.let { InfoRow("Server withdrawal recorded by", it) }
            task.withdrawalAtMillis?.let { withdrawnAt ->
                val withdrawn = remember(withdrawnAt) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(withdrawnAt))
                }
                InfoRow("Server withdrawal recorded", withdrawn)
            }
        }
        Text(instruction, color = Brand.Foreground, style = MaterialTheme.typography.bodySmall)
        task.receiptNo?.let {
            InfoRow("Receipt", it, valueColor = Brand.Good)
        }
        task.error?.takeIf(String::isNotBlank)?.let {
            OperationalBanner(
                title = "Status detail",
                detail = it,
                tone = UiTone.Danger,
                icon = Icons.Default.Warning,
            )
        }

        if (task.payoutConflict) {
            OperationalBanner(
                title = "Protected-owner reconciliation required",
                detail = "Do not pay again and do not close this shift. Preserve the customer, drawer or provider, timestamp, reference, and audit evidence for owner/support review.",
                tone = UiTone.Danger,
                icon = Icons.Default.Warning,
            )
        } else when (task.state) {
            RefundState.REQUEST_PENDING -> ErpButton(
                text = "Check server now",
                onClick = onCheckNow,
                intent = ActionIntent.Secondary,
                enabled = !busy,
                busy = busy,
                leadingIcon = Icons.Default.Refresh,
            )
            RefundState.REQUEST_REJECTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ErpButton(
                    text = "Retry same task",
                    onClick = { onRetry(task.localId) },
                    modifier = Modifier.weight(1f),
                    intent = ActionIntent.Secondary,
                    enabled = !busy,
                    leadingIcon = Icons.Default.Refresh,
                )
                ErpButton(
                    text = "Cancel refused request",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    intent = ActionIntent.Destructive,
                    enabled = !busy,
                )
            }
            RefundState.ACCEPTED_CASH_DUE -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ErpButton(
                        text = if (online) "Start cash handover" else "Reconnect to start handover",
                        onClick = onBeginHandoff,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Warning,
                        enabled = !busy && online && canManageMoney,
                    )
                    if (protectedAccess) {
                        ErpButton(
                            text = "Withdraw — no cash given",
                            onClick = onWithdrawCash,
                            modifier = Modifier.weight(1f),
                            intent = ActionIntent.Secondary,
                            enabled = !busy && canManageMoney,
                        )
                    }
                }
            }
            RefundState.CASH_HANDOFF_IN_PROGRESS -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ErpButton(
                        text = "Record ${task.amountMinor.asRupees()} handed over",
                        onClick = onCashHanded,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Destructive,
                        enabled = !busy && canManageMoney,
                    )
                    if (protectedAccess) {
                        ErpButton(
                            text = if (online) "Resolve — no cash was given" else "Reconnect to resolve handover",
                            onClick = onResolveCash,
                            modifier = Modifier.weight(1f),
                            intent = ActionIntent.Secondary,
                            enabled = !busy && online && canManageMoney,
                        )
                    }
                }
            }
            RefundState.ACCEPTED_PROVIDER_DUE -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ErpButton(
                    text = if (online) "Start provider payout" else "Reconnect to start provider payout",
                    onClick = onBeginProvider,
                    modifier = Modifier.weight(1f),
                    intent = ActionIntent.Warning,
                    enabled = !busy && online && canManageMoney,
                )
                if (protectedAccess) {
                    ErpButton(
                        text = "Withdraw — payout not started",
                        onClick = onWithdrawProvider,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Secondary,
                        enabled = !busy && online && canManageMoney,
                    )
                }
            }
            RefundState.PROVIDER_PAYOUT_IN_PROGRESS -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ErpButton(
                    text = "Record provider payout completed",
                    onClick = onProviderCompleted,
                    modifier = Modifier.weight(1f),
                    intent = ActionIntent.Destructive,
                    enabled = !busy && canManageMoney,
                )
                if (protectedAccess) {
                    ErpButton(
                        text = if (online) "Resolve failed payout" else "Reconnect to resolve",
                        onClick = onResolveProvider,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Secondary,
                        enabled = !busy && online && canManageMoney,
                    )
                }
            }
            RefundState.CASH_SETTLE_PENDING,
            RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING,
            RefundState.PROVIDER_COMPLETION_PENDING,
            RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING,
            RefundState.WITHDRAWAL_PENDING,
            -> ErpButton(
                text = "Check server now",
                onClick = onCheckNow,
                intent = ActionIntent.Secondary,
                enabled = !busy,
                busy = busy,
                leadingIcon = Icons.Default.Refresh,
            )
            RefundState.CASH_SETTLE_REJECTED,
            RefundState.CASH_FINALIZE_REJECTED,
            RefundState.PROVIDER_COMPLETION_REJECTED,
            RefundState.PROVIDER_FINALIZE_REJECTED,
            RefundState.WITHDRAWAL_REJECTED,
            -> ErpButton(
                text = "Retry same resolution",
                onClick = { onRetry(task.localId) },
                intent = ActionIntent.Secondary,
                enabled = !busy,
                leadingIcon = Icons.Default.Refresh,
            )
            RefundState.LEGACY_RECONCILIATION_REQUIRED -> OperationalBanner(
                title = "Protected-owner reconciliation required",
                detail = "No payout action is available in the app. Compare the server refund, original shift, and physical drawer before support resolves this row.",
                tone = UiTone.Danger,
                icon = Icons.Default.Warning,
            )
        }
    }
}

@Composable
private fun RefundHistoryRow(task: RefundTask) {
    val terminalActor = if (task.state == RefundState.WITHDRAWN) {
        task.withdrawnByNameOrId()
    } else {
        task.settledByNameOrId() ?: task.moneyCompletedByNameOrId()
    }
    val needsReview = task.error?.isNotBlank() == true ||
        task.customerSpendReconciled == false ||
        task.loyaltyReconciliationState == "legacy_unknown" ||
        task.capturedTimeReconciled == false ||
        task.providerEvidenceReconciled == false
    DataListRow(
        leading = {
            OperationalStatusBadge(
                label = refundTaskStatusLabel(task),
                tone = if (task.state == RefundState.SETTLED) UiTone.Success else UiTone.Neutral,
                icon = Icons.AutoMirrored.Filled.AssignmentReturn,
            )
        },
        content = {
            Text(
                task.invoiceNo ?: "Order ${task.orderId.take(8)}…",
                color = Brand.Foreground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(refundPaymentMethodLabel(task.settlementMethod.orEmpty()))
                    terminalActor?.let { append(" · recorded by $it") }
                },
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            if (needsReview) {
                Text(
                    task.error ?: "Owner reconciliation evidence remains incomplete.",
                    color = Brand.Warning,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(task.amountMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                Text(
                    task.receiptNo ?: when {
                        task.payoutConflict -> "Payout conflict"
                        task.state == RefundState.WITHDRAWN -> "No payout"
                        else -> "Receipt pending"
                    },
                    color = if (needsReview) Brand.Warning else Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

private fun RefundTask.acceptedByNameOrId(): String? = actorNameOrId(acceptedByName, acceptedByUserId)

private fun RefundTask.moneyStartedByNameOrId(): String? =
    actorNameOrId(moneyStartedByName, moneyStartedByUserId)

private fun RefundTask.moneyCompletedByNameOrId(): String? =
    actorNameOrId(moneyCompletedByName, moneyCompletedByUserId)

private fun RefundTask.settledByNameOrId(): String? = actorNameOrId(settledByName, settledByUserId)

private fun RefundTask.withdrawnByNameOrId(): String? = actorNameOrId(withdrawnByName, withdrawnByUserId)

private fun actorNameOrId(name: String?, id: String?): String? =
    name?.trim()?.takeIf(String::isNotEmpty)
        ?: id?.trim()?.takeIf(String::isNotEmpty)?.let { "Employee ${it.take(8)}…" }

internal fun refundRailSummary(methods: List<String>): String {
    val policy = refundRailPolicy(methods)
    return when (policy.kind) {
        RefundRailKind.UNKNOWN -> "Payment rail unavailable"
        RefundRailKind.CASH -> "Cash"
        RefundRailKind.SINGLE_PROVIDER -> refundPaymentMethodLabel(policy.methods.single())
        RefundRailKind.MIXED -> "Mixed: " + policy.methods.joinToString(" + ") {
            refundPaymentMethodLabel(it)
        }
    }
}

private fun refundTaskStatusLabel(task: RefundTask): String = if (task.payoutConflict) {
    "Owner reconciliation"
} else when (task.state) {
    RefundState.REQUEST_PENDING -> "Awaiting server"
    RefundState.REQUEST_REJECTED -> "Request refused"
    RefundState.ACCEPTED_CASH_DUE -> "Cash due"
    RefundState.ACCEPTED_PROVIDER_DUE -> "Provider due"
    RefundState.CASH_HANDOFF_IN_PROGRESS -> "Handover active"
    RefundState.CASH_SETTLE_PENDING -> "Settlement pending"
    RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING -> "Accounting pending"
    RefundState.CASH_SETTLE_REJECTED -> "Recovery required"
    RefundState.CASH_FINALIZE_REJECTED -> "Accounting recovery"
    RefundState.PROVIDER_PAYOUT_IN_PROGRESS -> "Provider active"
    RefundState.PROVIDER_COMPLETION_PENDING -> "Provider evidence pending"
    RefundState.PROVIDER_COMPLETION_REJECTED -> "Provider recovery"
    RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING -> "Accounting pending"
    RefundState.PROVIDER_FINALIZE_REJECTED -> "Accounting recovery"
    RefundState.WITHDRAWAL_PENDING -> "Withdrawal pending"
    RefundState.WITHDRAWAL_REJECTED -> "Withdrawal refused"
    RefundState.LEGACY_RECONCILIATION_REQUIRED -> "Quarantined"
    RefundState.SETTLED -> "Settled"
    RefundState.WITHDRAWN -> "Withdrawn"
    else -> "Review"
}

internal fun refundTaskCopy(task: RefundTask): Pair<String, String> = if (task.payoutConflict) {
    "Payout evidence conflicts with server withdrawal — do not pay again" to
        "This tablet recorded that physical cash or provider value moved, while the server records no payout. " +
        "Do not repeat or reverse value movement. Keep the shift open and ask a protected owner to reconcile " +
        "the customer, drawer or provider, timestamps, reference, and audit evidence; contact support if needed."
} else when (task.state) {
    RefundState.REQUEST_PENDING ->
        "Waiting for server — no payout authorised" to
            "Do not hand over cash or start a provider payout while this exact request is being checked."
    RefundState.REQUEST_REJECTED ->
        "Server refused this record" to if (task.mode == "cash") {
            "No cash is authorised. Fix the stated issue and retry the same task, or cancel this refused request."
        } else {
            "No provider payout is authorised. Fix the stated issue and retry the same task, or cancel this refused request."
        }
    RefundState.ACCEPTED_CASH_DUE ->
        "Cash refund accepted — drawer untouched" to
            "Verify the customer and amount, then start the live server-confirmed handover before touching cash."
    RefundState.CASH_HANDOFF_IN_PROGRESS ->
        "Cash handover in progress — do not pay twice" to
            "After a restart, verify the customer and drawer first. Record settlement only if this exact cash was handed over once."
    RefundState.CASH_SETTLE_PENDING ->
        "Cash was handed over — settlement waiting" to
            "Do not pay again. Keep the shift open and reconnect until the server issues the refund receipt."
    RefundState.CASH_SETTLE_REJECTED ->
        "Cash was handed over — server needs recovery" to
            "Do not pay again or withdraw. Fix the stated issue and retry this same settlement identity."
    RefundState.CASH_HANDED_OVER_PENDING_ACCOUNTING ->
        "Cash payout recorded — accounting waiting" to
            "Do not pay again. Keep the shift open while the ERP safely materialises the refund receipt and balances."
    RefundState.CASH_FINALIZE_REJECTED ->
        "Cash payout recorded — accounting needs recovery" to
            "Do not pay again. Retry the accounting step; the server already holds the immutable cash handover fact."
    RefundState.ACCEPTED_PROVIDER_DUE ->
        "Provider refund accepted — payout not started" to
            "Verify the original rail and amount, then obtain a live server-confirmed provider start before moving money."
    RefundState.PROVIDER_PAYOUT_IN_PROGRESS ->
        "Provider payout in progress — do not start twice" to
            "Complete this exact payout once. After success, record the provider reference; after restart, verify the provider first."
    RefundState.PROVIDER_COMPLETION_PENDING ->
        "Provider completed — evidence waiting" to
            "Do not run the payout again. Keep the shift open and reconnect until the server records the immutable provider outcome."
    RefundState.PROVIDER_COMPLETION_REJECTED ->
        "Provider completed — server needs recovery" to
            "Do not run the payout again. Fix the stated issue and retry the same provider evidence."
    RefundState.PROVIDER_COMPLETED_PENDING_ACCOUNTING ->
        "Provider payout recorded — accounting waiting" to
            "Do not run the payout again. Keep the shift open while the ERP creates the receipt and reconciles balances."
    RefundState.PROVIDER_FINALIZE_REJECTED ->
        "Provider payout recorded — accounting needs recovery" to
            "Do not run the payout again. Retry accounting; the server already holds the provider completion fact."
    RefundState.WITHDRAWAL_PENDING ->
        "No cash given — withdrawal waiting" to
            "Do not hand over cash. Keep the shift open until the server confirms the append-only withdrawal."
    RefundState.WITHDRAWAL_REJECTED ->
        "No cash given — withdrawal needs recovery" to
            "Do not hand over cash. Fix the stated issue and retry the same owner withdrawal."
    RefundState.LEGACY_RECONCILIATION_REQUIRED ->
        "Older refund quarantined — do not pay again" to
            "Its exact shift and physical handover cannot be proven from this tablet. Owner reconciliation is required."
    RefundState.SETTLED ->
        "Refund settled — receipt recorded" to
            "Money movement and accounting are complete. Stock and COGS were not changed automatically."
    RefundState.WITHDRAWN ->
        "Refund withdrawn — no payout recorded" to
            "The server recorded that no cash or provider value moved."
    else -> "Refund needs review" to "Ask a protected owner to verify server, customer, and drawer records."
}

@Composable
private fun RefundDialog(order: Order, busy: Boolean, online: Boolean, vm: RefundsViewModel) {
    val railPolicy = remember(order.id, order.paymentMethods) { refundRailPolicy(order.paymentMethods) }
    var amount by remember(order.id) { mutableStateOf(minorToRupeesInput(order.refundableMinor)) }
    var reason by remember(order.id) { mutableStateOf(REFUND_REASONS.first().first) }
    var mode by remember(order.id, order.paymentMethods) { mutableStateOf(railPolicy.defaultMode) }
    var note by remember(order.id) { mutableStateOf("") }
    var confirming by remember(order.id) { mutableStateOf(false) }

    val parsedAmountMinor = remember(amount) { parseRupeesToMinor(amount) }
    val amountMinor = parsedAmountMinor ?: 0L
    val invalidAmount = parsedAmountMinor == null
    val overRefund = amountMinor > order.refundableMinor
    val valid = !invalidAmount && amountMinor > 0 && !overRefund &&
        railPolicy.requestReady && railPolicy.allows(mode)
    val pendingRefund = order.pendingRefundMinor.coerceAtLeast(0).coerceAtMost(order.paidMinor)
    val alreadyRefunded = (order.paidMinor - order.refundableMinor - pendingRefund).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = { if (!busy) vm.select(null) },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Refund ${order.invoiceNo ?: "order"}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Collected", color = Brand.ForegroundMuted)
                    Text(order.paidMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                }
                if (alreadyRefunded > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Already settled refunds", color = Brand.ForegroundMuted)
                        Text(alreadyRefunded.asRupees(), color = Brand.ForegroundMuted)
                    }
                }
                if (pendingRefund > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Reserved in open refund tasks", color = Brand.ForegroundMuted)
                        Text(pendingRefund.asRupees(), color = Brand.Warning)
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Refund amount (₹)") },
                    singleLine = true,
                    isError = invalidAmount || overRefund || amountMinor <= 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    invalidAmount -> Text("Use rupees with no more than 2 decimal places.", color = Brand.Danger)
                    amountMinor <= 0 -> Text("Refund amount must be greater than ₹0.", color = Brand.Danger)
                    overRefund -> Text("That is above the latest refundable balance.", color = Brand.Danger)
                }

                Text("Original payment", color = Brand.ForegroundMuted)
                when (railPolicy.kind) {
                    RefundRailKind.UNKNOWN -> OperationalBanner(
                        title = "Payment rail unavailable — refund locked",
                        detail = "Refresh after the server update. The app will not guess whether this was Cash, Card, UPI, QR or Wallet.",
                        tone = UiTone.Danger,
                        icon = Icons.Default.Lock,
                    )
                    RefundRailKind.CASH -> {
                        InfoRow("Collected through", "Cash")
                        Text(
                            "This refund must use the cash handover workflow.",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    RefundRailKind.MIXED -> {
                        InfoRow("Collected through", refundRailSummary(railPolicy.methods))
                        Text(
                            "Mixed-payment refunds use the explicit cash workflow under the current server contract.",
                            color = Brand.Warning,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    RefundRailKind.SINGLE_PROVIDER -> {
                        val originalLabel = refundPaymentMethodLabel(railPolicy.methods.single())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = mode == "original",
                                onClick = { mode = "original" },
                                label = { Text("Original $originalLabel") },
                                modifier = Modifier.weight(1f),
                                colors = refundChipColors(),
                            )
                            FilterChip(
                                selected = mode == "cash",
                                onClick = { mode = "cash" },
                                label = { Text("Cash instead") },
                                modifier = Modifier.weight(1f),
                                colors = refundChipColors(),
                            )
                        }
                        Text(
                            if (mode == "original") {
                                "The server must open this exact $originalLabel payout before the provider app is used."
                            } else {
                                "Cash uses the guarded drawer handover workflow even though the original payment was $originalLabel."
                            },
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                OperationalBanner(
                    title = "Money refund only — stock and COGS stay unchanged",
                    detail = "There is no item-level refund restock workflow. Sale-time stock is already consumed, so do not deduct prepared food, used ingredients or gaming time again. Only unopened goods physically returned to stock may use a separately authorised inventory adjustment with evidence.",
                    tone = UiTone.Warning,
                    icon = Icons.Default.Warning,
                )

                Text("Reason", color = Brand.ForegroundMuted)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = when {
                        maxWidth >= 500.dp -> 3
                        maxWidth >= 300.dp -> 2
                        else -> 1
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        REFUND_REASONS.chunked(columns).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                            row.forEach { (code, label) ->
                                FilterChip(
                                    selected = reason == code,
                                    onClick = { reason = code },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f),
                                    colors = refundChipColors(),
                                )
                            }
                            repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        mode == "original" && online ->
                            "Submitting reserves the provider refund only. Do not open the provider app until the accepted task tells you to start."
                        mode == "original" ->
                            "Offline: only the request is saved. No provider payout is authorised until reconnection and server acceptance."
                        online -> "Submitting asks the server to reserve the cash refund. Do not give cash yet."
                        else -> "Offline: the request will be saved. Do not give cash until server acceptance appears."
                    },
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { confirming = true },
                enabled = valid && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text(if (busy) "Saving…" else "Review ${amountMinor.asRupees()}") }
        },
        dismissButton = {
            OutlinedButton(onClick = { vm.select(null) }, enabled = !busy) { Text("Cancel") }
        },
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (mode == "cash") "Submit cash request?" else "Submit provider request?") },
            text = {
                Text(
                    if (mode == "cash") {
                        "Request ${amountMinor.asRupees()} against ${order.invoiceNo ?: "this order"}. No cash may leave now. A separate server handover task must appear first. This money refund does not restock items or reverse COGS; do not deduct consumed stock again."
                    } else {
                        "Request ${amountMinor.asRupees()} against ${order.invoiceNo ?: "this order"}. This does not move provider money. Wait for the separate server-confirmed payout task. It does not restock items or reverse COGS; do not deduct consumed stock again."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirming = false
                        vm.refund(order, amountMinor, reason, mode, note)
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text("Submit request") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Go back") } },
        )
    }
}

@Composable
private fun ProviderCompletionDialog(
    task: RefundTask,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var externalReference by remember(task.localId) { mutableStateOf("") }
    val valid = externalReference.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Provider payout completed once?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Confirm only after the original ${task.settlementMethod ?: "non-cash"} provider successfully refunded " +
                        "${task.amountMinor.asRupees()}. If the app restarted or you are unsure, check the provider first—never run it twice.",
                )
                OutlinedTextField(
                    value = externalReference,
                    onValueChange = { externalReference = it.take(200) },
                    label = { Text("Successful provider refund reference") },
                    singleLine = true,
                    isError = externalReference.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The reference and completion time are saved durably before sync. A connection failure must not cause another payout.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && valid,
                onClick = { onConfirm(externalReference.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text("Provider payout completed") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Stop and verify") }
        },
    )
}

@Composable
private fun ResolveCashHandoffDialog(
    task: RefundTask,
    busy: Boolean,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember(task.localId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Resolve started cash handover?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Protected-owner recovery only. Continue only after checking the customer and drawer and confirming none of " +
                        "${task.amountMinor.asRupees()} left the drawer. If any cash moved, stop and record settlement instead.",
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Why the drawer remained unchanged") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && online && reason.trim().length >= 3,
                onClick = { onConfirm(reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text("Confirm no cash moved") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Stop and verify") } },
    )
}

@Composable
private fun WithdrawProviderDialog(
    task: RefundTask,
    busy: Boolean,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember(task.localId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Withdraw provider refund request?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Protected-owner action only. Use this before the provider payout starts and only when no provider value moved.",
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Why the payout will not be started") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && online && reason.trim().length >= 3,
                onClick = { onConfirm(reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text("Withdraw — no payout started") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep task") } },
    )
}

private val PROVIDER_FAILURE_STATUSES = listOf(
    "no_matching_transaction" to "No matching transaction",
    "provider_declined" to "Provider declined",
    "provider_reversed" to "Provider reversed",
)

@Composable
private fun ResolveProviderPayoutDialog(
    task: RefundTask,
    busy: Boolean,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var providerStatus by remember(task.localId) { mutableStateOf(PROVIDER_FAILURE_STATUSES.first().first) }
    var verificationReference by remember(task.localId) { mutableStateOf("") }
    var reason by remember(task.localId) { mutableStateOf("") }
    val valid = verificationReference.trim().length >= 3 && reason.trim().length >= 3
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Resolve failed provider payout?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Protected-owner recovery only. Search the provider for this exact ${task.amountMinor.asRupees()} payout first. " +
                        "If it succeeded, stop and record its successful reference instead.",
                )
                Text("Verified provider outcome", color = Brand.ForegroundMuted)
                PROVIDER_FAILURE_STATUSES.forEach { (code, label) ->
                    FilterChip(
                        selected = providerStatus == code,
                        onClick = { providerStatus = code },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = refundChipColors(),
                    )
                }
                OutlinedTextField(
                    value = verificationReference,
                    onValueChange = { verificationReference = it.take(200) },
                    label = { Text("Provider search / case / reversal reference") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Why no payout completed") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && online && valid,
                onClick = {
                    onConfirm(providerStatus, verificationReference.trim(), reason.trim())
                },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text("Confirm no provider payout") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Stop and verify") } },
    )
}

@Composable
private fun WithdrawCashDialog(
    task: RefundTask,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember(task.localId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Withdraw unpaid cash refund?") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Protected-owner action only. Confirm that none of ${task.amountMinor.asRupees()} reached the customer. If any cash left the drawer, cancel and settle instead.",
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Why no cash was handed over") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && reason.trim().length >= 3,
                onClick = { onConfirm(reason) },
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text("Withdraw unpaid refund") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Go back") } },
    )
}

@Composable
private fun refundChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Brand.Gold,
    selectedLabelColor = Brand.Background,
)
