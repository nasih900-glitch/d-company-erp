package cloud.dcompany.erp.ui.screens.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import cloud.dcompany.erp.core.auth.InventoryAccess
import cloud.dcompany.erp.core.db.BatchCacheEntity
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PageHeader
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SearchInput
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import kotlin.math.abs

private val UNITS = listOf(
    "g" to "g (grams)",
    "ml" to "ml (millilitres)",
    "unit" to "unit (each / piece)",
)

@Composable
fun InventoryScreen(access: InventoryAccess = InventoryAccess(), vm: InventoryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }

    Column(
        Modifier.fillMaxSize().background(Brand.Background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state, vm)
        if (!access.canManageInventory) ViewOnlyNotice()

        when {
            state.loading -> Box(Modifier.fillMaxSize().weight(1f), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Brand.Gold)
                    Text("Loading inventory…", color = Brand.ForegroundMuted)
                }
            }

            // Nothing cached and nothing coming: the screen is useless until
            // this is fixed, so it shows that plainly rather than a spinner
            // that never resolves.
            state.couldNotLoad -> SectionCard(Modifier.weight(1f), elevated = true) {
                DesignedEmptyState(
                    title = "Could not load inventory",
                    body = state.refreshError
                        ?: "No inventory is cached on this tablet yet. Check the connection and try again.",
                    icon = Icons.Default.CloudOff,
                    primaryLabel = "Retry",
                    onPrimary = vm::retry,
                )
            }

            else -> Column(
                Modifier.fillMaxSize().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOfNotNull(state.refreshError, state.branchesError).distinct().forEach { error ->
                    RefreshErrorBanner(error, vm::retry)
                }
                state.notice?.let {
                    NoticeBanner(it, vm::dismissNotice)
                }
                if (state.pendingGrns.isNotEmpty() || state.pendingAdjustments.isNotEmpty()) {
                    PendingStockChangesPanel(state, access.canManageInventory, vm)
                }
                StatRow(state)
                if (state.restockPriority.isNotEmpty()) {
                    RestockStrip(state, vm)
                }
                TabBar(state, vm)
                InventoryActionBar(state, access.canManageInventory, vm)
                when (state.tab) {
                    InventoryTab.INGREDIENTS -> IngredientsPane(
                        state,
                        access.canManageInventory,
                        vm,
                        Modifier.weight(1f),
                    )
                    InventoryTab.SUPPLIERS -> SuppliersPane(
                        state,
                        access.canManageInventory,
                        vm,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }

    when (val d = state.dialog.takeIf { access.canManageInventory }) {
        is InventoryDialog.IngredientForm -> IngredientDialog(d.editing, state, vm)
        is InventoryDialog.SupplierForm -> SupplierDialog(d.editing, state, vm)
        is InventoryDialog.Grn -> GrnDialog(state, vm)
        is InventoryDialog.Adjust -> AdjustDialog(d.ingredient, state, vm)
        is InventoryDialog.ConfirmDeleteIngredient -> ConfirmDialog(
            title = "Delete ${d.ingredient.name}?",
            body = "Existing batches stay in the audit trail. Recipes that use it will " +
                "stop deducting stock at checkout. If offline, this queues and applies once " +
                "back online — you can cancel it from this screen any time before it syncs.",
            confirmLabel = "Delete",
            busy = state.busy,
            error = state.formError,
            onConfirm = { vm.deleteIngredient(d.ingredient) },
            onDismiss = vm::closeDialog,
        )
        is InventoryDialog.ConfirmDeleteSupplier -> ConfirmDialog(
            title = "Delete ${d.supplier.name}?",
            body = "Past receipts from this supplier are kept. If offline, this queues and " +
                "applies once back online — you can cancel it from this screen any time before it syncs.",
            confirmLabel = "Delete",
            busy = state.busy,
            error = state.formError,
            onConfirm = { vm.deleteSupplier(d.supplier) },
            onDismiss = vm::closeDialog,
        )
        null -> Unit
    }
}

// ------------------------------------------------------------------- chrome

@Composable
private fun Header(state: InventoryUiState, vm: InventoryViewModel) {
    PageHeader(
        title = "Inventory",
        subtitle = "Ingredients, FIFO batches, supplier receipts, and low-stock control",
        eyebrow = "Stock control",
        actions = {
            ErpButton(
                text = if (state.syncing) "Refreshing" else "Refresh",
                onClick = vm::retry,
                enabled = !state.syncing,
                busy = state.syncing,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.Refresh,
            )
        },
    )
}

@Composable
private fun InventoryActionBar(state: InventoryUiState, canWrite: Boolean, vm: InventoryViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 700.dp
        ActionBar(
            leading = {
                OperationalStatusBadge(
                    label = if (canWrite) "Stock controls enabled" else "View only",
                    tone = if (canWrite) UiTone.Success else UiTone.Neutral,
                    icon = if (canWrite) Icons.Default.CheckCircle else Icons.Default.Inventory2,
                )
            },
            trailing = if (!canWrite) null else {
                {
                    if (state.tab == InventoryTab.INGREDIENTS) {
                        ErpButton(
                            text = if (compact) "Ingredient" else "New ingredient",
                            onClick = { vm.openDialog(InventoryDialog.IngredientForm(null)) },
                            enabled = state.ingredientsLoaded,
                            intent = ActionIntent.Secondary,
                            leadingIcon = Icons.Default.Add,
                        )
                        ErpButton(
                            text = if (compact) "Receive" else "Receive stock",
                            onClick = { vm.openDialog(InventoryDialog.Grn) },
                            enabled = state.branchId != null &&
                                state.syncedSuppliers.isNotEmpty() && state.syncedIngredients.isNotEmpty(),
                            leadingIcon = Icons.Default.LocalShipping,
                        )
                    } else {
                        ErpButton(
                            text = if (compact) "Supplier" else "New supplier",
                            onClick = { vm.openDialog(InventoryDialog.SupplierForm(null)) },
                            enabled = state.suppliersLoaded,
                            leadingIcon = Icons.Default.Add,
                        )
                    }
                }
            },
        )
    }
}

/** Pending/rejected GRN and adjustment writes — the "Sync issues" surface for events with no natural list row of their own. */
@Composable
private fun PendingStockChangesPanel(
    state: InventoryUiState,
    canWrite: Boolean,
    vm: InventoryViewModel,
) {
    SectionCard(
        title = "Pending stock changes",
        subtitle = "Receipts and adjustments waiting for the server",
        icon = Icons.Default.SyncProblem,
        contentPadding = PaddingValues(12.dp),
    ) {
        state.pendingGrns.forEach { grn ->
            PendingRow(
                text = "Stock receipt from ${grn.supplierName}",
                rejected = grn.rejected,
                error = grn.error,
                canRetry = canWrite,
                onRetry = { vm.retryGrn(grn.localId) },
            )
        }
        state.pendingAdjustments.forEach { adj ->
            val direction = if (adj.qtyDelta < 0) "removed from" else "added to"
            PendingRow(
                text = "${abs(adj.qtyDelta).asQty()} ${adj.unit} $direction ${adj.ingredientName}",
                rejected = adj.rejected,
                error = adj.error,
                canRetry = canWrite,
                onRetry = { vm.retryAdjustment(adj.localId) },
            )
        }
    }
}

@Composable
private fun PendingRow(
    text: String,
    rejected: Boolean,
    error: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (rejected) TextButton(onClick = onRetry, enabled = canRetry) { Text("Retry") }
        }
        Text(
            if (rejected) "Could not sync: ${error ?: "unknown error"}" else "Not synced yet",
            color = if (rejected) Brand.Danger else Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    OperationalBanner(
        title = "Inventory updated",
        detail = message,
        tone = UiTone.Information,
        icon = Icons.Default.CheckCircle,
        action = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    )
}

@Composable
private fun RefreshErrorBanner(message: String, onRetry: () -> Unit) {
    OperationalBanner(
        title = "Inventory refresh failed",
        detail = message,
        tone = UiTone.Danger,
        icon = Icons.Default.CloudOff,
        action = {
            ErpButton("Retry", onRetry, intent = ActionIntent.Secondary, leadingIcon = Icons.Default.Refresh)
        },
    )
}

@Composable
private fun StatRow(state: InventoryUiState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 760.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StockValueMetric(state, Modifier.weight(1f))
                    IngredientCountMetric(state, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LowStockMetric(state, Modifier.weight(1f))
                    SupplierCountMetric(state, Modifier.weight(1f))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StockValueMetric(state, Modifier.weight(1f))
                IngredientCountMetric(state, Modifier.weight(1f))
                LowStockMetric(state, Modifier.weight(1f))
                SupplierCountMetric(state, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StockValueMetric(state: InventoryUiState, modifier: Modifier) = CompactStatCard(
    label = "Stock value",
    value = state.stockValueMinor.asRupees(),
    detail = "Cost value on hand",
    icon = Icons.Default.Inventory2,
    tone = UiTone.Brand,
    modifier = modifier,
)

@Composable
private fun IngredientCountMetric(state: InventoryUiState, modifier: Modifier) = CompactStatCard(
    label = "Ingredients",
    value = state.ingredients.size.toString(),
    detail = "Tracked stock items",
    icon = Icons.Default.Tune,
    tone = UiTone.Information,
    modifier = modifier,
)

@Composable
private fun LowStockMetric(state: InventoryUiState, modifier: Modifier) = CompactStatCard(
    label = "Low stock",
    value = state.lowCount.toString(),
    detail = if (state.lowCount == 0) "All above reorder level" else "Needs attention",
    icon = if (state.lowCount == 0) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
    tone = if (state.lowCount == 0) UiTone.Success else UiTone.Danger,
    modifier = modifier,
)

@Composable
private fun SupplierCountMetric(state: InventoryUiState, modifier: Modifier) = CompactStatCard(
    label = "Suppliers",
    value = state.suppliers.size.toString(),
    detail = "Available for receipts",
    icon = Icons.Default.LocalShipping,
    tone = UiTone.Neutral,
    modifier = modifier,
)

/** The one thing an owner opens this screen to find out: what to buy today. */
@Composable
private fun RestockStrip(state: InventoryUiState, vm: InventoryViewModel) {
    SectionCard(
        title = "Restock priority",
        subtitle = "Lowest stock relative to each ingredient's reorder level",
        icon = Icons.Default.WarningAmber,
        contentPadding = PaddingValues(12.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.restockPriority, key = { it.localWriteId ?: it.id }) { ingredient ->
                Column(
                    Modifier.width(210.dp).clip(Radius.shapeSm)
                        .background(Brand.SurfaceRaised)
                        .clickable { vm.select(ingredient) }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        ingredient.name,
                        color = Brand.Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${ingredient.currentQty.asQty()} / " +
                            "${ingredient.reorderThreshold.asQty()} ${ingredient.baseUnit}",
                        color = Brand.Danger,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (ingredient.reorderQty > 0) {
                        Text(
                            "Order ${ingredient.reorderQty.asQty()} ${ingredient.baseUnit}",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OperationalStatusBadge(
                        label = "Low stock",
                        tone = UiTone.Danger,
                        icon = Icons.Default.WarningAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBar(state: InventoryUiState, vm: InventoryViewModel) {
    PremiumTabBar(
        options = listOf(
            TabOption(InventoryTab.INGREDIENTS.name, "Ingredients", state.ingredients.size),
            TabOption(InventoryTab.SUPPLIERS.name, "Suppliers", state.suppliers.size),
        ),
        selectedId = state.tab.name,
        onSelect = { id -> vm.selectTab(InventoryTab.valueOf(id)) },
    )
}

// -------------------------------------------------------------- ingredients

@Composable
private fun IngredientsPane(
    state: InventoryUiState,
    canWrite: Boolean,
    vm: InventoryViewModel,
    modifier: Modifier = Modifier,
) {
    if (state.ingredientsUnavailable) {
        SectionCard(modifier, elevated = true) {
            DesignedEmptyState(
                title = "Ingredients unavailable",
                body = state.refreshError
                    ?: "This tablet has not successfully downloaded ingredients yet.",
                icon = Icons.Default.CloudOff,
                primaryLabel = "Retry",
                onPrimary = vm::retry,
            )
        }
        return
    }
    if (state.ingredients.isEmpty()) {
        SectionCard(modifier, elevated = true) {
            DesignedEmptyState(
                title = "No ingredients yet",
                body = "Add what the kitchen and bar actually consume, then record a stock receipt " +
                    "so each ingredient has a costed FIFO batch to draw from.",
                icon = Icons.Default.Inventory2,
                primaryLabel = if (canWrite) "New ingredient" else null,
                onPrimary = if (canWrite) {
                    { vm.openDialog(InventoryDialog.IngredientForm(null)) }
                } else null,
            )
        }
        return
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(state.sortedIngredients, query) {
        val needle = query.trim()
        if (needle.isBlank()) state.sortedIngredients else state.sortedIngredients.filter {
            it.name.contains(needle, ignoreCase = true) || it.sku.contains(needle, ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search ingredients or SKU",
            modifier = Modifier.fillMaxWidth(),
        )
        BoxWithConstraints(Modifier.fillMaxSize().weight(1f)) {
            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IngredientResultsPanel(filtered, state, canWrite, vm, Modifier.weight(1f))
                    DetailPanel(state, canWrite, vm, Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IngredientResultsPanel(filtered, state, canWrite, vm, Modifier.weight(0.58f))
                    if (state.selected != null) {
                        DetailPanel(state, canWrite, vm, Modifier.weight(0.42f))
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientResultsPanel(
    rows: List<IngredientRow>,
    state: InventoryUiState,
    canWrite: Boolean,
    vm: InventoryViewModel,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "Ingredient stock",
        subtitle = "${rows.size} result${if (rows.size == 1) "" else "s"} · lowest stock first",
        icon = Icons.Default.Inventory2,
        contentPadding = PaddingValues(0.dp),
    ) {
        if (rows.isEmpty()) {
            DesignedEmptyState(
                title = "No matching ingredients",
                body = "Try another ingredient name or SKU.",
                icon = Icons.Default.Inventory2,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, item -> item.localWriteId ?: item.id }) { index, ingredient ->
                IngredientRowCard(
                    ingredient = ingredient,
                    selected = ingredient.sku == state.selectedSku,
                    canWrite = canWrite,
                    onClick = { vm.select(ingredient) },
                    onAdjust = { vm.openDialog(InventoryDialog.Adjust(ingredient)) },
                    onEdit = { vm.openDialog(InventoryDialog.IngredientForm(ingredient)) },
                    onDelete = { vm.openDialog(InventoryDialog.ConfirmDeleteIngredient(ingredient)) },
                    onCancelRemoval = { vm.cancelIngredientRemoval(ingredient) },
                    onRetrySync = { vm.retryIngredientSync(ingredient) },
                )
                    if (index < rows.lastIndex) PanelDivider()
                }
            }
        }
    }
}

@Composable
private fun IngredientRowCard(
    ingredient: IngredientRow,
    selected: Boolean,
    canWrite: Boolean,
    onClick: () -> Unit,
    onAdjust: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelRemoval: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val statusLabel: String
    val statusTone: UiTone
    val statusIcon = when {
        ingredient.rejectedError != null -> Icons.Default.SyncProblem
        ingredient.pendingDelete || ingredient.pendingLocalId != null || ingredient.createConfirmationPending ->
            Icons.Default.SyncProblem
        ingredient.isLow -> Icons.Default.WarningAmber
        else -> Icons.Default.CheckCircle
    }
    when {
        ingredient.rejectedError != null -> {
            statusLabel = "Sync issue"
            statusTone = UiTone.Danger
        }
        ingredient.pendingDelete -> {
            statusLabel = "Removal pending"
            statusTone = UiTone.Warning
        }
        ingredient.createConfirmationPending -> {
            statusLabel = "Confirming create"
            statusTone = UiTone.Warning
        }
        ingredient.pendingLocalId != null -> {
            statusLabel = "Pending sync"
            statusTone = UiTone.Warning
        }
        ingredient.isLow -> {
            statusLabel = "Low stock"
            statusTone = UiTone.Danger
        }
        else -> {
            statusLabel = "Healthy"
            statusTone = UiTone.Success
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .background(if (selected) Brand.SurfaceRaised else Brand.Surface)
            .border(1.dp, if (selected) Brand.GoldMuted else Color.Transparent),
    ) {
        DataListRow(
            onClick = onClick,
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ingredient.name,
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OperationalStatusBadge(statusLabel, statusTone, icon = statusIcon)
                }
                Text(
                    "${ingredient.sku} · reorder at ${ingredient.reorderThreshold.asQty()} ${ingredient.baseUnit}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LevelBar(ingredient, Modifier.fillMaxWidth())
            },
            trailing = {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(132.dp)) {
                Text(
                    "${ingredient.currentQty.asQty()} ${ingredient.baseUnit}",
                    color = if (ingredient.isLow) Brand.Danger else Brand.Foreground,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    ingredient.stockValueMinor.asRupees(),
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                }
                if (canWrite && !ingredient.pendingDelete && !ingredient.createConfirmationPending) {
                    IngredientActionsMenu(
                        canAdjust = !ingredient.isUnsyncedDraft,
                        onAdjust = onAdjust,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            },
        )
        if (ingredient.createConfirmationPending) {
            Text(
                "Create confirmation pending — details stay locked so a retry cannot create a duplicate.",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (ingredient.pendingDelete) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "Removal pending sync",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelRemoval, enabled = canWrite) { Text("Cancel") }
            }
        } else if (ingredient.pendingLocalId != null) {
            Text(
                "Not synced yet",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (ingredient.rejectedError != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).border(1.dp, Brand.Danger, Radius.shapeSm)
                    .padding(8.dp),
            ) {
                Text("Could not sync: ${ingredient.rejectedError}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = onRetrySync, enabled = canWrite) { Text("Retry") }
                    if (ingredient.hasQueuedDelete) {
                        TextButton(onClick = onCancelRemoval, enabled = canWrite) { Text("Cancel removal") }
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientActionsMenu(
    canAdjust: Boolean,
    onAdjust: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (canAdjust) {
            ErpButton("Adjust", onAdjust, intent = ActionIntent.Secondary, leadingIcon = Icons.Default.Tune)
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More ingredient actions", tint = Brand.ForegroundMuted)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Edit ingredient") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { expanded = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Delete ingredient", color = Brand.Danger) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Brand.Danger) },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun LevelBar(ingredient: IngredientRow, modifier: Modifier = Modifier) {
    // Full bar = four times the reorder level, so "comfortable" and "about to
    // run out" are visibly different rather than both reading as near-full.
    val denom = if (ingredient.reorderThreshold > 0) ingredient.reorderThreshold * 4 else 1.0
    val fraction = (ingredient.currentQty / denom).coerceIn(0.0, 1.0).toFloat()
    val colour = when {
        ingredient.isLow -> Brand.Danger
        fraction < 0.4f -> Brand.Gold
        else -> Brand.Good
    }
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brand.SurfaceRaised)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(colour))
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit, tint: Color = Brand.Gold) {
    Text(
        label,
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(Radius.shapeSm)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** FIFO batches for the selected ingredient — what the deduction engine eats first. */
@Composable
private fun DetailPanel(
    state: InventoryUiState,
    canWrite: Boolean,
    vm: InventoryViewModel,
    modifier: Modifier = Modifier,
) {
    val ingredient = state.selected
    SectionCard(
        modifier = modifier,
        title = ingredient?.name ?: "FIFO batches",
        subtitle = ingredient?.let { "Oldest available stock is consumed first" }
            ?: "Select an ingredient to inspect its costed stock",
        icon = Icons.Default.Inventory2,
        contentPadding = PaddingValues(14.dp),
    ) {
        if (ingredient == null) {
            DesignedEmptyState(
                title = "Select an ingredient",
                body = "Choose an ingredient to see the batches it will be drawn from, oldest first.",
                icon = Icons.Default.Inventory2,
            )
            return@SectionCard
        }

        InfoRow(
            label = "On hand",
            value = "${ingredient.currentQty.asQty()} ${ingredient.baseUnit}",
            valueColor = if (ingredient.isLow) Brand.Danger else Brand.Foreground,
        )
        InfoRow(
            label = "Average cost",
            value = "${ingredient.avgCostMinor.asRupees()}/${ingredient.baseUnit}",
        )
        if (ingredient.isUnsyncedDraft) {
            OperationalBanner(
                title = "Ingredient pending sync",
                detail = "Stock actions unlock after the ingredient is confirmed by the server.",
                tone = UiTone.Warning,
                icon = Icons.Default.SyncProblem,
            )
        } else {
            ErpButton(
                text = "Adjust stock",
                onClick = { vm.openDialog(InventoryDialog.Adjust(ingredient)) },
                enabled = canWrite,
                modifier = Modifier.fillMaxWidth(),
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.Tune,
            )
        }

        Text(
            "Batches (oldest first)",
            style = MaterialTheme.typography.labelLarge,
            color = Brand.Foreground,
        )
        if (state.batchesLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    color = Brand.Gold,
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(20.dp).height(20.dp),
                )
                Text("Refreshing batches…", color = Brand.ForegroundMuted)
            }
        }
        state.batchesError?.let { message ->
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised)
                    .border(1.dp, Brand.Danger, Radius.shapeSm)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(message, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
                if (state.batches.isNotEmpty()) {
                    Text(
                        "Saved batch quantities are shown below and may be out of date.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = vm::retryBatches) { Text("Retry batch refresh") }
            }
        }
        when {
            ingredient.isUnsyncedDraft -> Text(
                "No batches yet — this ingredient hasn't synced.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.batches.isEmpty() && state.batchesLoading -> Unit
            state.batches.isEmpty() && state.batchesError != null -> Unit
            state.batches.isEmpty() -> Text(
                "No open batches. Record a stock receipt (GRN) — a positive adjustment " +
                    "needs an existing batch to attach to.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.batches, key = { it.id }) { batch ->
                    BatchRow(batch, ingredient.baseUnit)
                }
            }
        }
    }
}

@Composable
private fun BatchRow(batch: BatchCacheEntity, unit: String) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${batch.qtyOnHand.asQty()} $unit",
                color = Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
            Text("${batch.costPerUnitMinor.asRupees()}/$unit", color = Brand.Gold)
        }
        Text(
            "Received ${batch.receivedAt.asDay()}" +
                (batch.lotCode?.let { " · lot $it" } ?: ""),
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        batch.expiresAt?.let { expiry ->
            val day = expiry.asDay()
            val expired = day < today()
            Text(
                if (expired) "Expired $day" else "Expires $day",
                color = if (expired) Brand.Danger else Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------- suppliers

@Composable
private fun SuppliersPane(
    state: InventoryUiState,
    canWrite: Boolean,
    vm: InventoryViewModel,
    modifier: Modifier = Modifier,
) {
    if (state.suppliersUnavailable) {
        SectionCard(modifier, elevated = true) {
            DesignedEmptyState(
                title = "Suppliers unavailable",
                body = state.refreshError
                    ?: "This tablet has not successfully downloaded suppliers yet.",
                icon = Icons.Default.CloudOff,
                primaryLabel = "Retry",
                onPrimary = vm::retry,
            )
        }
        return
    }
    if (state.suppliers.isEmpty()) {
        SectionCard(modifier, elevated = true) {
            DesignedEmptyState(
                title = "No suppliers yet",
                body = "Every goods receipt must identify a supplier. Add the shops and distributors " +
                    "you buy from before recording stock receipts.",
                icon = Icons.Default.LocalShipping,
                primaryLabel = if (canWrite) "New supplier" else null,
                onPrimary = if (canWrite) {
                    { vm.openDialog(InventoryDialog.SupplierForm(null)) }
                } else null,
            )
        }
        return
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(state.suppliers, query) {
        val needle = query.trim()
        if (needle.isBlank()) state.suppliers else state.suppliers.filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.contact.orEmpty().contains(needle, ignoreCase = true) ||
                it.gstin.orEmpty().contains(needle, ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search suppliers, contact, or GSTIN",
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            modifier = Modifier.weight(1f),
            title = "Supplier directory",
            subtitle = "${filtered.size} result${if (filtered.size == 1) "" else "s"} available for goods receipts",
            icon = Icons.Default.LocalShipping,
            contentPadding = PaddingValues(0.dp),
        ) {
            if (filtered.isEmpty()) {
                DesignedEmptyState(
                    title = "No matching suppliers",
                    body = "Try another supplier name, contact, or GSTIN.",
                    icon = Icons.Default.LocalShipping,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, item -> item.localWriteId ?: item.id }) { index, supplier ->
                        SupplierRowCard(
                            supplier = supplier,
                            canWrite = canWrite,
                            onEdit = { vm.openDialog(InventoryDialog.SupplierForm(supplier)) },
                            onDelete = { vm.openDialog(InventoryDialog.ConfirmDeleteSupplier(supplier)) },
                            onCancelRemoval = { vm.cancelSupplierRemoval(supplier) },
                            onRetrySync = { vm.retrySupplierSync(supplier) },
                        )
                        if (index < filtered.lastIndex) PanelDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierRowCard(
    supplier: SupplierRow,
    canWrite: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelRemoval: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val (statusLabel, statusTone) = when {
        supplier.rejectedError != null -> "Sync issue" to UiTone.Danger
        supplier.pendingDelete -> "Removal pending" to UiTone.Warning
        supplier.createConfirmationPending -> "Confirming create" to UiTone.Warning
        supplier.pendingLocalId != null -> "Pending sync" to UiTone.Warning
        else -> "Ready" to UiTone.Success
    }
    Column(
        Modifier.fillMaxWidth().background(Brand.Surface),
    ) {
        DataListRow(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        supplier.name,
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OperationalStatusBadge(
                        statusLabel,
                        statusTone,
                        icon = if (statusTone == UiTone.Success) Icons.Default.CheckCircle else Icons.Default.SyncProblem,
                    )
                }
                Text(
                    supplier.contact ?: "No contact saved",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailing = {
                Column(Modifier.width(180.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        supplier.gstin?.let { "GSTIN $it" } ?: "GSTIN not saved",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        supplier.paymentTerms ?: "No payment terms",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canWrite && !supplier.pendingDelete && !supplier.createConfirmationPending) {
                    SupplierActionsMenu(onEdit, onDelete)
                }
            },
        )
        if (supplier.createConfirmationPending) {
            Text(
                "Create confirmation pending — details stay locked so a retry cannot create a duplicate.",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (supplier.pendingDelete) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "Removal pending sync",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelRemoval, enabled = canWrite) { Text("Cancel") }
            }
        } else if (supplier.pendingLocalId != null) {
            Text(
                "Not synced yet",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (supplier.rejectedError != null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).border(1.dp, Brand.Danger, Radius.shapeSm)
                    .padding(8.dp),
            ) {
                Text("Could not sync: ${supplier.rejectedError}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = onRetrySync, enabled = canWrite) { Text("Retry") }
                    if (supplier.hasQueuedDelete) {
                        TextButton(onClick = onCancelRemoval, enabled = canWrite) { Text("Cancel removal") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierActionsMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More supplier actions", tint = Brand.ForegroundMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit supplier") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { expanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Delete supplier", color = Brand.Danger) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Brand.Danger) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(480.dp).padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(body, color = Brand.ForegroundMuted)
            Button(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun IngredientDialog(
    editing: IngredientRow?,
    state: InventoryUiState,
    vm: InventoryViewModel,
) {
    var sku by remember { mutableStateOf(editing?.sku ?: "") }
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var unit by remember { mutableStateOf(editing?.baseUnit ?: "g") }
    var threshold by remember { mutableStateOf(editing?.reorderThreshold?.asQtyInput() ?: "") }
    var reorderQty by remember { mutableStateOf(editing?.reorderQty?.asQtyInput() ?: "") }

    FormDialog(
        title = if (editing == null) "New ingredient" else "Edit ${editing.name}",
        confirmLabel = if (editing == null) "Create" else "Save",
        busy = state.busy,
        error = state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                editing == null && sku.isBlank() -> vm.showFormError("SKU is required.")
                name.isBlank() -> vm.showFormError("Name is required.")
                else -> vm.saveIngredient(
                    editing = editing,
                    sku = sku,
                    name = name,
                    baseUnit = unit,
                    reorderThreshold = threshold.toDoubleOrNull() ?: 0.0,
                    reorderQty = reorderQty.toDoubleOrNull() ?: 0.0,
                )
            }
        },
    ) {
        OutlinedTextField(
            value = sku,
            onValueChange = { sku = it },
            // The SKU is the identity other records point at, so it's locked
            // once *other* records can actually reference it — i.e. once
            // synced. A still-local draft is provably unreferenceable by
            // anything (see InventoryUiState.syncedIngredients — a GRN/
            // adjustment picker excludes it) until it syncs, so there's
            // nothing to protect yet; without this, a typo in a still-local
            // SKU could only be fixed by delete-and-recreate.
            enabled = editing == null || editing.isUnsyncedDraft,
            label = { Text("SKU (e.g. MILK-1L, COFFEE-BEAN)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PickerField(
            label = "Base unit",
            selectedLabel = UNITS.firstOrNull { it.first == unit }?.second ?: unit,
            options = UNITS,
            onSelect = { unit = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DecimalField(
                value = threshold,
                onValueChange = { threshold = it },
                label = "Reorder at",
                modifier = Modifier.weight(1f),
            )
            DecimalField(
                value = reorderQty,
                onValueChange = { reorderQty = it },
                label = "Reorder qty",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SupplierDialog(editing: SupplierRow?, state: InventoryUiState, vm: InventoryViewModel) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var contact by remember { mutableStateOf(editing?.contact ?: "") }
    var gstin by remember { mutableStateOf(editing?.gstin ?: "") }
    var terms by remember { mutableStateOf(editing?.paymentTerms ?: "") }

    FormDialog(
        title = if (editing == null) "New supplier" else "Edit ${editing.name}",
        confirmLabel = if (editing == null) "Create" else "Save",
        busy = state.busy,
        error = state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            if (name.isBlank()) vm.showFormError("Name is required.")
            else vm.saveSupplier(editing, name, contact, gstin, terms)
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it },
            label = { Text("Contact (phone / email)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = gstin,
            onValueChange = { gstin = it.uppercase() },
            label = { Text("GSTIN") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = terms,
            onValueChange = { terms = it },
            label = { Text("Payment terms (e.g. Net 30, COD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class GrnLineDraft(
    val ingredientId: String = "",
    val qty: String = "",
    val unitCostRupees: String = "",
    val expiresAt: String = "",
    val lotCode: String = "",
)

@Composable
private fun GrnDialog(state: InventoryUiState, vm: InventoryViewModel) {
    val pickableIngredients = state.syncedIngredients
    val pickableSuppliers = state.syncedSuppliers
    var supplierId by remember { mutableStateOf(pickableSuppliers.firstOrNull()?.id ?: "") }
    var invoiceNo by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var lines by remember {
        mutableStateOf(listOf(GrnLineDraft(ingredientId = pickableIngredients.firstOrNull()?.id ?: "")))
    }

    val branchId = state.branchId
    // Mirrors the backend's `int(qty * unit_cost_minor)` so the number shown
    // here is the number the purchase order will actually carry.
    val totalMinor = lines.sumOf { line ->
        val qty = line.qty.toDoubleOrNull() ?: 0.0
        val cost = parseRupeesToMinor(line.unitCostRupees) ?: 0L
        (qty * cost).toLong()
    }

    FormDialog(
        title = "Receive stock (GRN)",
        confirmLabel = "Queue receipt",
        busy = state.busy,
        error = state.formError,
        wide = true,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val lineError = lines.mapIndexedNotNull { index, line ->
                line.validationError(index + 1)
            }.firstOrNull()
            val ready = lines.mapNotNull { it.toBody() }
            when {
                branchId.isNullOrBlank() -> vm.showFormError("Select a branch.")
                supplierId.isBlank() -> vm.showFormError("Select a supplier.")
                lineError != null -> vm.showFormError(lineError)
                ready.isEmpty() -> vm.showFormError("Add at least one line.")
                else -> vm.postGrn(supplierId, branchId, invoiceNo, notes, ready)
            }
        },
    ) {
        if (state.branches.isEmpty()) {
            Text(
                "No branches cached, so this receipt cannot be attributed to one. " +
                    "Close, tap Refresh once back online, and try again.",
                color = Brand.Danger,
            )
        }
        if (pickableSuppliers.isEmpty()) {
            Text(
                "No synced suppliers yet — add one on the Suppliers tab first and wait for " +
                    "it to sync. A GRN can't reference a supplier that hasn't synced.",
                color = Brand.Danger,
            )
        }
        if (pickableIngredients.isEmpty()) {
            Text(
                "No synced ingredients yet — add one first and wait for it to sync.",
                color = Brand.Danger,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PickerField(
                label = "Branch",
                selectedLabel = state.branches.firstOrNull { it.id == branchId }?.name ?: "— select —",
                options = state.branches.map { it.id to it.name },
                modifier = Modifier.weight(1f),
                onSelect = vm::selectBranch,
            )
            PickerField(
                label = "Supplier",
                selectedLabel = pickableSuppliers.firstOrNull { it.id == supplierId }?.name ?: "— select —",
                options = pickableSuppliers.map { it.id to it.name },
                modifier = Modifier.weight(1f),
                onSelect = { supplierId = it },
            )
        }
        OutlinedTextField(
            value = invoiceNo,
            onValueChange = { invoiceNo = it },
            label = { Text("Supplier invoice no.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Lines", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        lines.forEachIndexed { index, line ->
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    PickerField(
                        label = "Ingredient",
                        selectedLabel = pickableIngredients.firstOrNull { it.id == line.ingredientId }
                            ?.let { "${it.name} (${it.baseUnit})" } ?: "— select —",
                        options = pickableIngredients.map { it.id to "${it.name} (${it.baseUnit})" },
                        modifier = Modifier.weight(1.4f),
                        onSelect = { id ->
                            lines = lines.replaceAt(index) { it.copy(ingredientId = id) }
                        },
                    )
                    DecimalField(
                        value = line.qty,
                        onValueChange = { v -> lines = lines.replaceAt(index) { it.copy(qty = v) } },
                        label = "Qty",
                        modifier = Modifier.weight(0.7f),
                    )
                    DecimalField(
                        value = line.unitCostRupees,
                        onValueChange = { v ->
                            lines = lines.replaceAt(index) { it.copy(unitCostRupees = v) }
                        },
                        label = "₹ / unit",
                        modifier = Modifier.weight(0.7f),
                    )
                    if (lines.size > 1) {
                        SmallAction("Remove", { lines = lines.removeAt(index) }, Brand.Danger)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = line.expiresAt,
                        onValueChange = { v ->
                            lines = lines.replaceAt(index) { it.copy(expiresAt = v) }
                        },
                        label = { Text("Expiry YYYY-MM-DD (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = line.lotCode,
                        onValueChange = { v ->
                            lines = lines.replaceAt(index) { it.copy(lotCode = v) }
                        },
                        label = { Text("Lot code (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                val unitCost = parseRupeesToMinor(line.unitCostRupees)
                val qty = line.qty.toDoubleOrNull() ?: 0.0
                if (qty > 0 && unitCost != null) {
                    Text(
                        "Line total ${(qty * unitCost).toLong().asRupees()}",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { lines = lines + GrnLineDraft() }) { Text("Add line") }
            Text(
                "Total ${totalMinor.asRupees()}",
                color = Brand.Gold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdjustDialog(
    ingredient: IngredientRow,
    state: InventoryUiState,
    vm: InventoryViewModel,
) {
    var type by remember { mutableStateOf(ADJ_WASTE) }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val branchId = state.branchId
    val typed = qty.toDoubleOrNull()
    // The exact number that will be sent, from the same function the request
    // uses. Nothing here re-derives the sign.
    val delta = typed?.let { adjustmentDelta(type, it) }
    val after = delta?.let { (ingredient.currentQty + it).coerceAtLeast(0.0) }

    FormDialog(
        title = "Adjust ${ingredient.name}",
        confirmLabel = "Queue adjustment",
        busy = state.busy,
        error = state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                branchId.isNullOrBlank() -> vm.showFormError("Select a branch.")
                typed == null -> vm.showFormError("Enter a quantity.")
                delta == null || delta == 0.0 ->
                    vm.showFormError("A zero adjustment changes nothing — enter an amount.")
                else -> vm.postAdjustment(ingredient, branchId, type, typed, note)
            }
        },
    ) {
        Text(
            "On hand now: ${ingredient.currentQty.asQty()} ${ingredient.baseUnit}",
            color = Brand.ForegroundMuted,
        )
        PickerField(
            label = "Branch",
            selectedLabel = state.branches.firstOrNull { it.id == branchId }?.name ?: "— select —",
            options = state.branches.map { it.id to it.name },
            onSelect = vm::selectBranch,
        )
        PickerField(
            label = "Type",
            selectedLabel = ADJUSTMENT_TYPES.firstOrNull { it.first == type }?.second ?: type,
            options = ADJUSTMENT_TYPES,
            onSelect = { picked ->
                // A minus sign carried over from a count correction would flip a
                // waste entry into a stock increase, so the field is cleared.
                if (picked != type) qty = ""
                type = picked
            },
        )
        DecimalField(
            value = qty,
            onValueChange = { qty = it },
            label = if (type == ADJ_COUNT) {
                "Correction in ${ingredient.baseUnit} (minus if the real count is lower)"
            } else {
                "Quantity in ${ingredient.baseUnit} (positive — it will be subtracted)"
            },
            allowNegative = allowsNegativeInput(type),
            modifier = Modifier.fillMaxWidth(),
        )

        // Written out in full before it is queued. A sign error here does not
        // fail loudly — it just silently corrupts stock — so the operator gets
        // to see the resulting number first.
        if (delta != null && delta != 0.0 && after != null) {
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (delta < 0) {
                        "Removes ${abs(delta).asQty()} ${ingredient.baseUnit} from stock"
                    } else {
                        "Adds ${delta.asQty()} ${ingredient.baseUnit} to stock"
                    },
                    color = if (delta < 0) Brand.Danger else Brand.Good,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${ingredient.currentQty.asQty()} → ${after.asQty()} ${ingredient.baseUnit}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                )
                if (delta < 0 && ingredient.currentQty + delta < 0) {
                    Text(
                        "That is more than the system thinks is on hand — the server will " +
                            "floor stock at zero.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = Brand.ForegroundMuted)
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text(confirmLabel, color = Brand.Foreground) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** One dialog shell so every form errors, busies and cancels the same way. */
@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    wide: Boolean = false,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = if (wide) Modifier.width(760.dp) else Modifier.width(480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                // The server's own words (or, for a local validation issue,
                // this dialog's own words), next to the button that caused
                // them — "SKU already exists" is actionable, "Request
                // failed" is not.
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Working…" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------- inputs

@Composable
private fun PickerField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised)
                    .clickable(enabled = options.isNotEmpty()) { open = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLabel,
                    color = if (options.isEmpty()) Brand.ForegroundMuted else Brand.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("▾", color = Brand.Gold)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            open = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(filterDecimal(it, allowNegative)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Keeps a numeric field numeric. The minus sign is only ever accepted where a
 * negative is meaningful — on a waste entry it would silently invert the
 * movement and put stock *back* on the shelf.
 */
private fun filterDecimal(raw: String, allowNegative: Boolean): String {
    val sb = StringBuilder()
    var dotSeen = false
    raw.forEachIndexed { index, c ->
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> {
                dotSeen = true
                sb.append(c)
            }
            c == '-' && allowNegative && index == 0 -> sb.append(c)
        }
    }
    return sb.toString()
}

// ------------------------------------------------------------------ helpers

private fun Double.asQtyInput(): String = if (this == 0.0) "" else asQty().replace(",", "")

private fun GrnLineDraft.validationError(lineNumber: Int): String? {
    if (ingredientId.isBlank()) return "Line $lineNumber: select an ingredient."
    val quantity = qty.toDoubleOrNull()
    if (quantity == null || !quantity.isFinite() || quantity <= 0.0) {
        return "Line $lineNumber: quantity must be greater than 0."
    }
    if (parseRupeesToMinor(unitCostRupees) == null) {
        return "Line $lineNumber: unit cost must be rupees with no more than 2 decimal places."
    }
    val expiry = expiresAt.trim()
    if (expiry.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(expiry)) {
        return "Line $lineNumber: expiry must be written as YYYY-MM-DD."
    }
    return null
}

private fun GrnLineDraft.toBody(): GrnLineBody? {
    if (ingredientId.isBlank()) return null
    val quantity = qty.toDoubleOrNull() ?: return null
    if (!quantity.isFinite() || quantity <= 0) return null
    val unitCostMinor = parseRupeesToMinor(unitCostRupees) ?: return null
    val expiry = expiresAt.trim()
    if (expiry.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(expiry)) return null
    return GrnLineBody(
        ingredientId = ingredientId,
        qty = quantity,
        unitCostMinor = unitCostMinor,
        // The backend field is a datetime; a bare date would not parse.
        expiresAt = expiry.ifEmpty { null }?.let { "${it}T00:00:00Z" },
        lotCode = lotCode.trim().ifBlank { null },
    )
}

private fun <T> List<T>.replaceAt(index: Int, block: (T) -> T): List<T> =
    mapIndexed { i, item -> if (i == index) block(item) else item }

private fun <T> List<T>.removeAt(index: Int): List<T> =
    filterIndexed { i, _ -> i != index }

/** Local date as YYYY-MM-DD, which compares correctly as a plain string. */
private fun today(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        .format(java.util.Date())
