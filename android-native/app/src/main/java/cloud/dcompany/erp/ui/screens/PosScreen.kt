package cloud.dcompany.erp.ui.screens

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand

@Composable
fun PosScreen(
    state: PosUiState,
    onAdd: (MenuItemEntity) -> Unit,
    onRemove: (MenuItemEntity) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClearCart: () -> Unit,
    onRefresh: () -> Unit,
    onCapture: (String, Long) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var showPay by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SyncBanner(state, onRefresh)

        if (state.menuEmpty) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("No menu on this tablet yet", color = Brand.Foreground)
                    Text(
                        "Connect once to download the menu. After that the till works offline.",
                        color = Brand.ForegroundMuted,
                    )
                    Button(onClick = onRefresh) { Text("Download menu") }
                }
            }
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(12.dp)) {
                CategoryStrip(state, onSelectCategory)
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    // Adaptive rather than a fixed count, so one build fits an
                    // 8" tablet and a 12" one.
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.visibleItems, key = { it.id }) { item ->
                        MenuTile(item) { onAdd(item) }
                    }
                }
            }
            CartPanel(state, onAdd, onRemove, onClearCart) { showPay = true }
        }
    }

    if (showPay) {
        PayDialog(
            dueMinor = state.estimateMinor,
            online = state.online,
            onDismiss = { showPay = false },
            onConfirm = { method, tendered ->
                showPay = false
                onCapture(method, tendered)
            },
        )
    }

    state.notice?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            confirmButton = { TextButton(onClick = onDismissNotice) { Text("OK") } },
            title = { Text("Sale recorded") },
            text = { Text(message) },
        )
    }
}

/**
 * Offline status has to be unmissable. A cashier who does not know the tablet
 * is offline cannot know why the receipt has no invoice number, and a queue
 * that is silently stuck is worse than one that is loudly stuck.
 */
@Composable
private fun SyncBanner(state: PosUiState, onRefresh: () -> Unit) {
    val (bg, label) = when {
        state.rejectedCount > 0 ->
            Brand.Danger to "${state.rejectedCount} sale(s) refused by the server — needs an owner"
        !state.online && state.pendingCount > 0 ->
            Brand.GoldMuted to "Offline · ${state.pendingCount} sale(s) saved on this tablet"
        !state.online ->
            Brand.GoldMuted to "Offline · sales are saved here and sent when the link returns"
        state.pendingCount > 0 ->
            Brand.GoldMuted to "Sending ${state.pendingCount} saved sale(s)…"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Brand.Background, fontWeight = FontWeight.SemiBold)
        if (state.online) {
            TextButton(onClick = onRefresh) { Text("Sync now", color = Brand.Background) }
        }
    }
}

@Composable
private fun CategoryStrip(state: PosUiState, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = state.selectedCategoryId == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
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
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                ),
            )
        }
    }
}

@Composable
private fun MenuTile(item: MenuItemEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.SurfaceRaised)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Cart", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            if (state.cart.isNotEmpty()) {
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (state.cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("Tap an item to start", color = Brand.ForegroundMuted)
            }
        } else {
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
                        QtyButton("−") { onRemove(line.item) }
                        Text(
                            "${line.qty}",
                            modifier = Modifier.padding(horizontal = 10.dp),
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                        QtyButton("+") { onAdd(line.item) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Estimate", color = Brand.ForegroundMuted)
            Text(
                state.estimateMinor.asRupees(),
                color = Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            "Server confirms the final taxed total",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onPay,
            enabled = state.cart.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Take payment · ${state.cartCount} item${if (state.cartCount == 1) "" else "s"}")
        }
    }
}

/**
 * Cash first, because that is what the cafe actually takes. Change due is
 * computed as the cashier types, which is the single most-used number at the
 * counter and the thing staff most often get wrong under pressure.
 */
@Composable
private fun PayDialog(
    dueMinor: Long,
    online: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var method by remember { mutableStateOf("cash") }
    var tendered by remember { mutableStateOf("") }

    val tenderedMinor = remember(tendered) {
        val rupees = tendered.toDoubleOrNull() ?: 0.0
        Math.round(rupees * 100)
    }
    val changeMinor = tenderedMinor - dueMinor
    val cashShort = method == "cash" && tendered.isNotBlank() && changeMinor < 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take payment · ${dueMinor.asRupees()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card").forEach { (id, label) ->
                        FilterChip(
                            selected = method == id,
                            onClick = { method = id },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                        )
                    }
                }

                if (method == "cash") {
                    OutlinedTextField(
                        value = tendered,
                        onValueChange = { tendered = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Cash received (₹)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        tendered.isBlank() -> Unit
                        changeMinor >= 0 -> Text(
                            "Change due  ${changeMinor.asRupees()}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Brand.Good,
                        )
                        else -> Text(
                            "Short by  ${(-changeMinor).asRupees()}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Brand.Danger,
                        )
                    }
                }

                if (!online) {
                    Text(
                        "Offline: this prints a provisional receipt. The GST tax-invoice " +
                            "number is issued automatically when the tablet reconnects.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.GoldMuted,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !cashShort,
                onClick = { onConfirm(method, if (method == "cash") tenderedMinor else dueMinor) },
            ) { Text("Payment received") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QtyButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.SurfaceRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Brand.Gold, fontWeight = FontWeight.Bold)
    }
}
