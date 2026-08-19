package cloud.dcompany.erp.ui.screens.tables

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand

@Composable
fun TablesScreen(vm: TablesViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    when {
        state.tables.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                // Still shown even on a never-synced device: a bare spinner
                // with no way out is what this replaced — offline on the
                // very first open must not be a dead end.
                if (!state.everSynced) CircularProgressIndicator(color = Brand.Gold)
                Text(
                    if (state.everSynced) "No tables set up" else "Waiting for the first sync",
                    color = Brand.Foreground,
                )
                Text(
                    state.error ?: "Add floors and tables in the ERP, then refresh.",
                    color = Brand.ForegroundMuted,
                )
                Button(onClick = vm::load) { Text("Refresh") }
            }
        }

        else -> Column(Modifier.fillMaxSize().padding(14.dp)) {
            if (state.floors.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = state.selectedFloorId == null,
                            onClick = { vm.selectFloor(null) },
                            label = { Text("All floors") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                        )
                    }
                    items(state.floors, key = { it.id }) { f ->
                        FilterChip(
                            selected = state.selectedFloorId == f.id,
                            onClick = { vm.selectFloor(f.id) },
                            label = { Text(f.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.visibleTables, key = { it.id }) { table ->
                    TableTile(table) { vm.openTable(table) }
                }
            }
        }
    }

    state.openTable?.let { table ->
        OrderBuilder(
            table = table,
            state = state,
            onAdd = vm::add,
            onRemove = vm::remove,
            onDismiss = vm::closeTable,
            onSend = vm::sendToPos,
        )
    }

    state.notice?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
            title = { Text("Tables") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun TableTile(table: CafeTable, onClick: () -> Unit) {
    // Colour carries the status so a glance across a busy room is enough;
    // reading a word on every tile is not.
    val occupied = table.status.lowercase() != "available"
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (occupied) Brand.SurfaceRaised else Brand.Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            table.code,
            style = MaterialTheme.typography.titleLarge,
            color = Brand.Foreground,
            fontWeight = FontWeight.Bold,
        )
        Text("${table.seats} seats", style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Text(
            if (occupied) table.status else "Free",
            color = if (occupied) Brand.Danger else Brand.Good,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OrderBuilder(
    table: CafeTable,
    state: TablesUiState,
    onAdd: (MenuItemEntity) -> Unit,
    onRemove: (MenuItemEntity) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Table ${table.code}") },
        text = {
            Row(Modifier.height(420.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Menu", color = Brand.ForegroundMuted)
                    Spacer(Modifier.height(6.dp))
                    if (state.menu.isEmpty()) {
                        Text(
                            "No menu on this tablet yet — open POS once while online.",
                            color = Brand.ForegroundMuted,
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 130.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.menu, key = { it.id }) { item ->
                            Column(
                                Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(Brand.SurfaceRaised)
                                    .clickable { onAdd(item) }
                                    .padding(10.dp),
                            ) {
                                Text(
                                    item.name,
                                    color = Brand.Foreground,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(item.basePriceMinor.asRupees(), color = Brand.Gold)
                            }
                        }
                    }
                }
                Column(Modifier.width(280.dp)) {
                    Text("Order", color = Brand.ForegroundMuted)
                    Spacer(Modifier.height(6.dp))
                    if (state.cart.isEmpty()) {
                        Text("Tap a menu item to add it.", color = Brand.ForegroundMuted)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.cart, key = { it.item.id }) { line ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                                Qty("−") { onRemove(line.item) }
                                Text(
                                    "${line.qty}",
                                    Modifier.padding(horizontal = 8.dp),
                                    color = Brand.Foreground,
                                    fontWeight = FontWeight.Bold,
                                )
                                Qty("+") { onAdd(line.item) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Estimate", color = Brand.ForegroundMuted)
                        Text(
                            state.estimateMinor.asRupees(),
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        "Sending needs a connection — the till has to see this bill now.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.GoldMuted,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSend, enabled = state.cart.isNotEmpty() && !state.busy) {
                Text(if (state.busy) "Sending…" else "Send to POS")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Qty(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
            .background(Brand.SurfaceRaised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Brand.Gold, fontWeight = FontWeight.Bold) }
}
