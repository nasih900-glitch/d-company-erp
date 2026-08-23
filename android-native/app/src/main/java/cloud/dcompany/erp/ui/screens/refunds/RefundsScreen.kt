package cloud.dcompany.erp.ui.screens.refunds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius

@Composable
fun RefundsScreen(vm: RefundsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Refunds", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "Only paid orders can be refunded, and only up to what was actually " +
                "collected. The server decides — its answer is shown as-is.",
            color = Brand.ForegroundMuted,
        )
        if (state.rejected.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeMd)
                    .background(Brand.Danger.copy(alpha = 0.15f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${state.rejected.size} refund(s) could not be sent — the cash is already " +
                        "given, this needs reconciling:",
                    color = Brand.Danger,
                    fontWeight = FontWeight.SemiBold,
                )
                state.rejected.forEach {
                    Text(
                        "${it.invoiceNo ?: "order"} · ${it.amountMinor.asRupees()} — ${it.reason}",
                        color = Brand.Danger,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::search,
            label = { Text("Find by invoice number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        when {
            // Still shown even on a never-synced device: a bare spinner with
            // no way out is what this replaced — offline on the very first
            // open must not be a dead end (see Gaming/Tables for the same fix).
            state.orders.isEmpty() && !state.everSynced ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Brand.Gold)
                        Text("Waiting for the first sync", color = Brand.Foreground)
                        Button(onClick = vm::load) { Text("Refresh") }
                    }
                }
            state.visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    if (state.query.isBlank()) "No paid orders to refund."
                    else "No paid order matches \"${state.query}\".",
                    color = Brand.ForegroundMuted,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.visible, key = { it.id }) { order ->
                    Row(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.Surface)
                            .clickable { vm.select(order) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                order.invoiceNo ?: "no invoice number",
                                color = Brand.Foreground,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${order.type} · paid ${order.paidMinor.asRupees()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.ForegroundMuted,
                            )
                        }
                        Text(order.totalMinor.asRupees(), color = Brand.Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    state.selected?.let { order -> RefundDialog(order, state.busy, vm) }

    state.notice?.let { msg ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
            title = { Text("Refund") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun RefundDialog(order: Order, busy: Boolean, vm: RefundsViewModel) {
    // Defaults to whatever's still refundable (paid minus every refund
    // already issued, minus anything this device already has queued), which
    // is the common case for a first refund but correctly starts lower for
    // a second partial one — stays editable regardless.
    var amount by remember(order.id) { mutableStateOf((order.refundableMinor / 100.0).toString()) }
    var reason by remember { mutableStateOf(REFUND_REASONS.first().first) }
    var note by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    val amountMinor = remember(amount) { Math.round((amount.toDoubleOrNull() ?: 0.0) * 100) }
    val overRefund = amountMinor > order.refundableMinor
    val valid = amountMinor > 0 && !overRefund
    val alreadyRefunded = order.paidMinor - order.refundableMinor

    AlertDialog(
        onDismissRequest = { if (!busy) vm.select(null) },
        title = { Text("Refund ${order.invoiceNo ?: "order"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Collected on this order", color = Brand.ForegroundMuted)
                    Text(order.paidMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                }
                if (alreadyRefunded > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Already refunded", color = Brand.ForegroundMuted)
                        Text(alreadyRefunded.asRupees(), color = Brand.ForegroundMuted)
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Refund amount (₹)") },
                    singleLine = true,
                    isError = overRefund,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (overRefund) {
                    Text(
                        "That is more than is still refundable on this order.",
                        color = Brand.Danger,
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
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Brand.Gold,
                                        selectedLabelColor = Brand.Background,
                                    ),
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
            }
        },
        confirmButton = {
            Button(
                onClick = { confirming = true },
                enabled = valid && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text(if (busy) "Refunding…" else "Refund ${amountMinor.asRupees()}") }
        },
        dismissButton = { OutlinedButton(onClick = { vm.select(null) }, enabled = !busy) { Text("Cancel") } },
    )

    // A second, explicit step showing the exact figure. Money leaving the
    // drawer should never be one tap away from a text field.
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Give back ${amountMinor.asRupees()}?") },
            text = {
                Text(
                    "This returns ${amountMinor.asRupees()} in cash against " +
                        "${order.invoiceNo ?: "this order"} and cannot be undone from this app.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirming = false; vm.refund(order, amountMinor, reason, note) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
                ) { Text("Yes, refund") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Go back") } },
        )
    }
}
