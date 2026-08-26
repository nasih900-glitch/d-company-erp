package cloud.dcompany.erp.ui.screens.refunds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.money.minorToRupeesInput
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import java.text.DateFormat
import java.util.Date

@Composable
fun RefundsScreen(vm: RefundsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var beginHandoff by remember { mutableStateOf<RefundTask?>(null) }
    var confirmCash by remember { mutableStateOf<RefundTask?>(null) }
    var cancelRequest by remember { mutableStateOf<RefundTask?>(null) }
    var withdrawTask by remember { mutableStateOf<RefundTask?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Refunds", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(
                "Every refund is tied to this tablet's exact open shift. Cash needs server acceptance and a guarded handover before it leaves the drawer.",
                color = Brand.ForegroundMuted,
            )
        }

        state.moneyAccessMessage?.let { message ->
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(Radius.shapeMd)
                        .background(Brand.Danger.copy(alpha = 0.13f)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Refund money actions locked", color = Brand.Danger, fontWeight = FontWeight.SemiBold)
                    Text(message, color = Brand.Foreground, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.tasks.isNotEmpty()) {
            item { Text("Refund tasks", color = Brand.Foreground, fontWeight = FontWeight.SemiBold) }
            items(state.tasks, key = { "refund-task-${it.localId}" }) { task ->
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
                    onWithdraw = { withdrawTask = task },
                )
            }
        }

        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::search,
                label = { Text("Find by invoice number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when {
            state.orders.isEmpty() && !state.everSynced -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Brand.Gold)
                    Text("Waiting for the first server sync", color = Brand.Foreground)
                    Button(onClick = vm::load) { Text("Refresh") }
                }
            }
            state.visible.isEmpty() -> item {
                Text(
                    when {
                        state.query.isNotBlank() -> "No available paid order matches \"${state.query}\"."
                        state.tasks.isEmpty() -> "No paid orders have a refundable balance."
                        else -> "Resolve the task above before refunding that order again."
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    color = Brand.ForegroundMuted,
                )
            }
            else -> items(state.visible, key = { "refundable-order-${it.id}" }) { order ->
                Row(
                    Modifier.fillMaxWidth().clip(Radius.shapeMd)
                        .background(Brand.Surface)
                        .clickable(enabled = !state.busy && state.canManageMoney) { vm.select(order) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            order.invoiceNo ?: "No invoice number",
                            color = Brand.Foreground,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${order.type} · collected ${order.paidMinor.asRupees()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.ForegroundMuted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Available", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
                        Text(order.refundableMinor.asRupees(), color = Brand.Gold, fontWeight = FontWeight.Bold)
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

    cancelRequest?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) cancelRequest = null },
            title = { Text("Cancel refused cash request?") },
            text = {
                Text(
                    "Use this only because the server refused the request and no cash was authorised or handed over. It does not create or withdraw a server refund.",
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
    onWithdraw: () -> Unit,
) {
    val reason = REFUND_REASONS.firstOrNull { it.first == task.reasonCode }?.second ?: task.reasonCode
    val created = remember(task.createdAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(task.createdAtMillis))
    }
    val (headline, instruction) = refundTaskCopy(task)
    val urgent = task.state in setOf(
        RefundState.ACCEPTED_CASH_DUE,
        RefundState.CASH_HANDOFF_IN_PROGRESS,
        RefundState.CASH_SETTLE_PENDING,
        RefundState.CASH_SETTLE_REJECTED,
        RefundState.LEGACY_RECONCILIATION_REQUIRED,
    )
    val tint = if (urgent) Brand.Danger else Brand.Gold

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.13f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(headline, color = tint, fontWeight = FontWeight.SemiBold)
        Text(
            "${task.invoiceNo ?: "Order"} · ${task.amountMinor.asRupees()} · " +
                "${task.settlementMethod ?: task.mode ?: "unverified rail"} · $created",
            color = Brand.Foreground,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Reason: $reason", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        Text(instruction, color = Brand.Foreground, style = MaterialTheme.typography.bodySmall)
        task.receiptNo?.let {
            Text("Receipt $it", color = Brand.Gold, style = MaterialTheme.typography.labelSmall)
        }
        task.error?.takeIf(String::isNotBlank)?.let {
            Text("Status detail: $it", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
        }

        when (task.state) {
            RefundState.REQUEST_PENDING -> OutlinedButton(onClick = onCheckNow, enabled = !busy) {
                Text("Check server now")
            }
            RefundState.REQUEST_REJECTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRetry(task.localId) }, enabled = !busy) { Text("Retry same task") }
                if (task.mode == "cash") {
                    OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel refused request") }
                }
            }
            RefundState.ACCEPTED_CASH_DUE -> {
                Button(
                    onClick = onBeginHandoff,
                    enabled = !busy && online && canManageMoney,
                ) { Text(if (online) "Start cash handover" else "Reconnect to start handover") }
                if (protectedAccess) {
                    OutlinedButton(onClick = onWithdraw, enabled = !busy && canManageMoney) {
                        Text("Withdraw — no cash given")
                    }
                }
            }
            RefundState.CASH_HANDOFF_IN_PROGRESS -> {
                Button(
                    onClick = onCashHanded,
                    enabled = !busy && canManageMoney,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text("Record ${task.amountMinor.asRupees()} handed over") }
                if (protectedAccess) {
                    OutlinedButton(onClick = onWithdraw, enabled = !busy && canManageMoney) {
                        Text("Withdraw — no cash was given")
                    }
                }
            }
            RefundState.CASH_SETTLE_PENDING,
            RefundState.WITHDRAWAL_PENDING,
            -> OutlinedButton(onClick = onCheckNow, enabled = !busy) { Text("Check server now") }
            RefundState.CASH_SETTLE_REJECTED,
            RefundState.WITHDRAWAL_REJECTED,
            -> Button(onClick = { onRetry(task.localId) }, enabled = !busy) { Text("Retry same resolution") }
            RefundState.LEGACY_RECONCILIATION_REQUIRED -> Text(
                "No payout action is available in the app. A protected owner must compare the server refund, original shift, and physical drawer before support resolves this row.",
                color = Brand.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun refundTaskCopy(task: RefundTask): Pair<String, String> = when (task.state) {
    RefundState.REQUEST_PENDING ->
        "Waiting for server — no payout authorised" to
            "Do not hand over cash or repeat a provider refund while this exact action is being checked."
    RefundState.REQUEST_REJECTED ->
        "Server refused this record" to if (task.mode == "cash") {
            "No cash is authorised. Fix the stated issue and retry the same task, or cancel this refused request."
        } else {
            "Do not repeat the external provider payout. Retry the same task or ask a protected owner to reconcile it."
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
    RefundState.WITHDRAWAL_PENDING ->
        "No cash given — withdrawal waiting" to
            "Do not hand over cash. Keep the shift open until the server confirms the append-only withdrawal."
    RefundState.WITHDRAWAL_REJECTED ->
        "No cash given — withdrawal needs recovery" to
            "Do not hand over cash. Fix the stated issue and retry the same owner withdrawal."
    RefundState.LEGACY_RECONCILIATION_REQUIRED ->
        "Older refund quarantined — do not pay again" to
            "Its exact shift and physical handover cannot be proven from this tablet. Owner reconciliation is required."
    else -> "Refund needs review" to "Ask a protected owner to verify server, customer, and drawer records."
}

@Composable
private fun RefundDialog(order: Order, busy: Boolean, online: Boolean, vm: RefundsViewModel) {
    var amount by remember(order.id) { mutableStateOf(minorToRupeesInput(order.refundableMinor)) }
    var reason by remember(order.id) { mutableStateOf(REFUND_REASONS.first().first) }
    var mode by remember(order.id) { mutableStateOf("cash") }
    var externalReference by remember(order.id) { mutableStateOf("") }
    var note by remember(order.id) { mutableStateOf("") }
    var confirming by remember(order.id) { mutableStateOf(false) }

    val parsedAmountMinor = remember(amount) { parseRupeesToMinor(amount) }
    val amountMinor = parsedAmountMinor ?: 0L
    val invalidAmount = parsedAmountMinor == null
    val overRefund = amountMinor > order.refundableMinor
    val invalidReference = mode == "original" && externalReference.trim().length < 3
    val valid = !invalidAmount && amountMinor > 0 && !overRefund && !invalidReference
    val alreadyRefunded = order.paidMinor - order.refundableMinor

    AlertDialog(
        onDismissRequest = { if (!busy) vm.select(null) },
        title = { Text("Refund ${order.invoiceNo ?: "order"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Collected", color = Brand.ForegroundMuted)
                    Text(order.paidMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                }
                if (alreadyRefunded > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Settled or reserved refunds", color = Brand.ForegroundMuted)
                        Text(alreadyRefunded.asRupees(), color = Brand.ForegroundMuted)
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

                Text("Payout rail", color = Brand.ForegroundMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "cash",
                        onClick = { mode = "cash" },
                        label = { Text("Cash") },
                        colors = refundChipColors(),
                    )
                    FilterChip(
                        selected = mode == "original",
                        onClick = { mode = "original" },
                        label = { Text("Original non-cash") },
                        colors = refundChipColors(),
                    )
                }
                if (mode == "original") {
                    Text(
                        "Use only when the receipt has one card/UPI/QR/wallet rail. Complete the provider refund first; ERP records it but does not send funds.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = externalReference,
                        onValueChange = { externalReference = it.take(200) },
                        label = { Text("Provider refund reference") },
                        isError = invalidReference,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text("Reason", color = Brand.ForegroundMuted)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    REFUND_REASONS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (code, label) ->
                                FilterChip(
                                    selected = reason == code,
                                    onClick = { reason = code },
                                    label = { Text(label) },
                                    colors = refundChipColors(),
                                )
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
                            "The provider reference and completion time will be captured with this exact shift. Do not repeat the provider payout."
                        mode == "original" ->
                            "Offline: provider evidence will be saved. Do not repeat the payout; this shift remains blocked until sync."
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
            title = { Text(if (mode == "cash") "Submit cash request?" else "Record provider refund?") },
            text = {
                Text(
                    if (mode == "cash") {
                        "Request ${amountMinor.asRupees()} against ${order.invoiceNo ?: "this order"}. No cash may leave now. A separate server handover task must appear first."
                    } else {
                        "Confirm the provider already completed ${amountMinor.asRupees()} and reference ${externalReference.trim()}. Do not run the provider refund again if ERP sync is delayed."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirming = false
                        vm.refund(order, amountMinor, reason, mode, externalReference, note)
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text(if (mode == "cash") "Submit request" else "Provider payout completed") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Go back") } },
        )
    }
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
        title = { Text("Withdraw unpaid cash refund?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
