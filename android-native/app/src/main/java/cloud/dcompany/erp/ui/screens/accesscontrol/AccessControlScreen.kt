package cloud.dcompany.erp.ui.screens.accesscontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.theme.Brand

private val ROLE_COLUMN_WIDTH = 150.dp
private val MODULE_COLUMN_WIDTH = 120.dp

@Composable
fun AccessControlScreen(vm: AccessControlViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Access Control", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "Owner-only. Every change applies immediately — there is no separate save step. " +
                "The protected owner role isn't shown here because it can't be restricted.",
            color = Brand.ForegroundMuted,
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.Gold)
            }
            state.roles.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        state.error ?: "Could not load access control.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = vm::load) { Text("Try again") }
                }
            }
            else -> AccessGrid(state, vm)
        }
    }

    state.actionError?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissActionError,
            confirmButton = { TextButton(onClick = vm::dismissActionError) { Text("OK") } },
            title = { Text("Access Control") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun AccessGrid(state: AccessControlUiState, vm: AccessControlViewModel) {
    Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        // Header row — module names.
        Row {
            Box(Modifier.width(ROLE_COLUMN_WIDTH))
            state.modules.forEach { module ->
                Box(Modifier.width(MODULE_COLUMN_WIDTH).padding(6.dp), Alignment.Center) {
                    Text(
                        MODULE_LABELS[module] ?: module,
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.roles.entries.toList(), key = { it.key }) { (roleCode, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(ROLE_COLUMN_WIDTH).padding(end = 8.dp)) {
                        Text(
                            label,
                            color = Brand.Foreground,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    state.modules.forEach { module ->
                        Box(Modifier.width(MODULE_COLUMN_WIDTH), Alignment.Center) {
                            val cell = state.cellFor(roleCode, module)
                            if (cell != null) {
                                AccessCellToggle(
                                    cell = cell,
                                    busy = "$roleCode:$module" in state.busyKeys,
                                    onToggle = { vm.toggle(cell) },
                                    onReset = { vm.resetToDefault(cell) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessCellToggle(
    cell: AccessCell,
    busy: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (cell.allowed) Brand.Good else Brand.Danger)
                .clickable(enabled = !busy, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Brand.Background)
            } else {
                Icon(
                    if (cell.allowed) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = if (cell.allowed) "Allowed" else "Blocked",
                    tint = Brand.Background,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // Only shown when this cell has an explicit override — an
        // unmodified cell is just following the role's static default and
        // has nothing to reset.
        if (cell.override != null) {
            IconButton(onClick = onReset, enabled = !busy, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Reset to default (${if (cell.defaultAllowed) "allowed" else "blocked"})",
                    tint = Brand.GoldMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
