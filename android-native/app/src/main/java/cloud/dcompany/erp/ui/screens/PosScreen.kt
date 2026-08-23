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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.EmptyState
import cloud.dcompany.erp.ui.components.PrimaryButton
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Motion
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

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
                        MenuTile(item, Modifier.animateItem()) { onAdd(item) }
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
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
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
    val animatedBg by animateColorAsState(bg, tween(Motion.medium), label = "syncBannerBg")
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            Modifier.fillMaxWidth().background(animatedBg).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Brand.Background, fontWeight = FontWeight.SemiBold)
            if (state.online) {
                TextButton(onClick = onRefresh) { Text("Sync now", color = Brand.Background) }
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
private fun MenuTile(item: MenuItemEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(Motion.fast, easing = Motion.emphasized), label = "tileScale")
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Radius.shapeLg)
            .background(Brand.SurfaceRaised)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
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
                OutlinedButton(onClick = onClear, shape = Radius.shapePill) { Text("Clear") }
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (state.cart.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("Tap an item to start", color = Brand.ForegroundMuted)
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
                        QtyButton("−") { onRemove(line.item) }
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
                        QtyButton("+") { onAdd(line.item) }
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
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text("Take payment · ${dueMinor.asRupees()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card").forEach { (id, label) ->
                        FilterChip(
                            selected = method == id,
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
                    OutlinedTextField(
                        value = tendered,
                        onValueChange = { tendered = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Cash received (₹)") },
                        singleLine = true,
                        shape = Radius.shapeMd,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        "Offline: this prints a provisional receipt. The GST tax-invoice " +
                            "number is issued automatically when the tablet reconnects.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.GoldMuted,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                enabled = !cashShort,
                onClick = { onConfirm(method, if (method == "cash") tenderedMinor else dueMinor) },
            ) { Text("Payment received") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QtyButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, tween(Motion.fast), label = "qtyBtnScale")
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(36.dp)
            .clip(Radius.shapeMd)
            .background(Brand.SurfaceRaised)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Brand.Gold, fontWeight = FontWeight.Bold)
    }
}
