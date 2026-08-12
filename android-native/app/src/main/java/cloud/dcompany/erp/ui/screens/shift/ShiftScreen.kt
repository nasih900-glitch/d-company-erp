package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand

@Composable
fun ShiftScreen(vm: ShiftViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    when {
        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = Brand.Gold)
        }
        state.error != null && state.open == null && state.history.isEmpty() ->
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load) { Text("Try again") }
                }
            }
        else -> Row(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                if (state.open == null) OpenShiftCard(state, vm) else CloseShiftCard(state, vm)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(360.dp)) {
                Text("Past shifts", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Spacer(Modifier.height(8.dp))
                if (state.history.isEmpty()) {
                    Text("No closed shifts yet.", color = Brand.ForegroundMuted)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.history, key = { it.id }) { s -> HistoryRow(s) }
                    }
                }
            }
        }
    }

    state.closedResult?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text("OK") } },
            title = { Text("Shift closed") },
            text = {
                Text(
                    when {
                        r.varianceMinor == 0L -> "The drawer balanced exactly."
                        r.varianceMinor > 0 -> "Over by ${r.varianceMinor.asRupees()} — more cash than expected."
                        else -> "Short by ${(-r.varianceMinor).asRupees()} — less cash than expected."
                    },
                )
            },
        )
    }
}

@Composable
private fun OpenShiftCard(state: ShiftUiState, vm: ShiftViewModel) {
    var float by remember { mutableStateOf("") }
    val floatMinor = remember(float) { Math.round((float.toDoubleOrNull() ?: 0.0) * 100) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brand.Surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("No shift is open", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "Billing is blocked until a shift is open. Enter the cash already in the " +
                "drawer at the start of this shift.",
            color = Brand.ForegroundMuted,
        )
        OutlinedTextField(
            value = float,
            onValueChange = { float = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Opening float (₹)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { vm.openShift(floatMinor) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Brand.Background)
            else Text("Open shift with ${floatMinor.asRupees()}")
        }
    }
}

@Composable
private fun CloseShiftCard(state: ShiftUiState, vm: ShiftViewModel) {
    val shift = state.open!!
    // Staff count notes, not totals. Typing a single number invites both
    // arithmetic slips and a "close enough" fudge; counting by denomination
    // makes the total fall out of the count itself.
    val counts = remember { mutableStateMapOf<Long, String>() }
    val countedMinor = DENOMINATIONS.sumOf { note ->
        note * 100 * (counts[note]?.toLongOrNull() ?: 0L)
    }
    var confirming by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            .background(Brand.Surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Close shift", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Opening float", color = Brand.ForegroundMuted)
            Text(shift.openingFloatMinor.asRupees(), color = Brand.Foreground)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Expected in drawer", color = Brand.ForegroundMuted)
            Text(
                shift.expectedMinor?.asRupees() ?: "not yet available",
                color = Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
        }

        Text("Count the drawer", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(DENOMINATIONS) { note ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("₹$note", color = Brand.Foreground, modifier = Modifier.width(70.dp))
                    OutlinedTextField(
                        value = counts[note] ?: "",
                        onValueChange = { counts[note] = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        (note * 100 * (counts[note]?.toLongOrNull() ?: 0L)).asRupees(),
                        color = Brand.ForegroundMuted,
                    )
                }
            }
        }

        val variance = shift.expectedMinor?.let { countedMinor - it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Counted", color = Brand.ForegroundMuted)
            Text(countedMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
        }
        if (variance != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Difference", color = Brand.ForegroundMuted)
                Text(
                    when {
                        variance == 0L -> "balanced"
                        variance > 0 -> "over ${variance.asRupees()}"
                        else -> "short ${(-variance).asRupees()}"
                    },
                    color = if (variance == 0L) Brand.Good else Brand.Danger,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::load, enabled = !state.busy) { Text("Refresh") }
            Button(
                onClick = { confirming = true },
                enabled = !state.busy && countedMinor > 0,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Close shift") }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Close this shift?") },
            text = {
                Text(
                    "Counted ${countedMinor.asRupees()}. Closing a shift is not reversible " +
                        "from this app — recount before confirming.",
                )
            },
            confirmButton = {
                Button(onClick = { confirming = false; vm.closeShift(countedMinor) }) {
                    Text("Close shift")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(s: ShiftDetail) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Brand.SurfaceRaised).padding(10.dp),
    ) {
        Text(s.openedAt.take(16).replace('T', ' '), color = Brand.Foreground)
        Text(
            "counted ${s.countedMinor?.asRupees() ?: "—"} · " +
                when {
                    s.varianceMinor == null -> "no variance recorded"
                    s.varianceMinor == 0L -> "balanced"
                    s.varianceMinor > 0 -> "over ${s.varianceMinor.asRupees()}"
                    else -> "short ${(-s.varianceMinor!!).asRupees()}"
                },
            style = MaterialTheme.typography.labelSmall,
            color = if (s.varianceMinor == 0L) Brand.Good else Brand.ForegroundMuted,
        )
    }
}
