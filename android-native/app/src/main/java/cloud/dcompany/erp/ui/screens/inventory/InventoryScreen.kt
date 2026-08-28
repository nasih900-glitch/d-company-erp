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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.ArrowDropDown
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
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.ConfirmDialog
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DecimalField
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.FormDialog
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.PickerField
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
                BranchContext(state, vm)
                StatRow(state)
                if (state.restockPriority.isNotEmpty()) {
                    RestockStrip(state, vm)
                }
                TabBar(state, access.canManageCosting, vm)
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
                    InventoryTab.RECIPES -> RecipesPane(
                        state = state,
                        canManageCosting = access.canManageCosting,
                        vm = vm,
                        modifier = Modifier.weight(1f),
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
        is InventoryDialog.RecipeCreate -> RecipeCreateDialog(d.menuItem, state, vm)
        is InventoryDialog.RecipeLineForm -> RecipeLineDialog(d.recipe, d.editing, state, vm)
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
        is InventoryDialog.ConfirmDeleteRecipe -> ConfirmDialog(
            title = "Deactivate ${d.recipe.name}?",
            body = "Future sales of this menu item will stop deducting ingredients until a new recipe is linked. Past stock movements remain unchanged.",
            confirmLabel = "Deactivate recipe",
            busy = state.busy,
            error = state.formError,
            onConfirm = { vm.deleteRecipe(d.recipe) },
            onDismiss = vm::closeDialog,
        )
        is InventoryDialog.ConfirmDeleteRecipeLine -> ConfirmDialog(
            title = "Remove this ingredient link?",
            body = "Future sales will no longer deduct this ingredient from the recipe. Past stock movements remain unchanged.",
            confirmLabel = "Remove line",
            busy = state.busy,
            error = state.formError,
            onConfirm = { vm.deleteRecipeLine(d.recipe, d.line) },
            onDismiss = vm::closeDialog,
        )
        null -> Unit
    }
}

@Composable
private fun BranchContext(state: InventoryUiState, vm: InventoryViewModel) {
    val selected = state.branches.firstOrNull { it.id == state.branchId }
    if (state.branches.size <= 1) {
        OperationalBanner(
            title = selected?.name ?: "Branch unavailable",
            detail = if (selected == null) {
                "Refresh Inventory before receiving or adjusting stock."
            } else {
                "Quantities, FIFO value, receipts and adjustments below are scoped to this branch."
            },
            tone = if (selected == null) UiTone.Warning else UiTone.Information,
            icon = Icons.Default.Inventory2,
        )
    } else {
        SectionCard(
            title = "Stock location",
            subtitle = "Changing branch reloads quantities and FIFO value before stock actions unlock",
            icon = Icons.Default.Inventory2,
            contentPadding = PaddingValues(12.dp),
        ) {
            PickerField(
                label = "Branch",
                selectedLabel = selected?.name ?: "— select —",
                options = state.branches.map { it.id to it.name },
                onSelect = vm::selectBranch,
            )
        }
    }
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
            trailing = {
                ErpButton(
                    text = if (state.syncing) "Refreshing" else "Refresh",
                    onClick = vm::retry,
                    enabled = !state.syncing,
                    busy = state.syncing,
                    intent = ActionIntent.Quiet,
                    leadingIcon = Icons.Default.Refresh,
                )
                if (canWrite) {
                    when (state.tab) {
                    InventoryTab.INGREDIENTS -> {
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
                    }
                    InventoryTab.SUPPLIERS -> {
                        ErpButton(
                            text = if (compact) "Supplier" else "New supplier",
                            onClick = { vm.openDialog(InventoryDialog.SupplierForm(null)) },
                            enabled = state.suppliersLoaded,
                            leadingIcon = Icons.Default.Add,
                        )
                    }
                    InventoryTab.RECIPES -> state.selectedRecipeMenuItem?.let { item ->
                        if (state.activeRecipe == null) {
                            ErpButton(
                                text = if (compact) "Link" else "Link recipe",
                                onClick = { vm.openDialog(InventoryDialog.RecipeCreate(item)) },
                                enabled = state.syncedIngredients.isNotEmpty() && !state.recipesLoading,
                                leadingIcon = Icons.Default.Add,
                            )
                        }
                    }
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
            color = if (rejected) Brand.Danger else Brand.Warning,
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
    AdaptiveStatGrid(count = 4) { index, modifier ->
        when (index) {
            0 -> StockValueMetric(state, modifier)
            1 -> IngredientCountMetric(state, modifier)
            2 -> LowStockMetric(state, modifier)
            else -> SupplierCountMetric(state, modifier)
        }
    }
}

@Composable
private fun StockValueMetric(state: InventoryUiState, modifier: Modifier) = CompactStatCard(
    label = "Stock value",
    value = state.stockValueMinor?.asRupees() ?: "Unavailable",
    detail = if (state.stockValueMinor == null) {
        "Exact FIFO valuation has not synced"
    } else {
        "Exact remaining FIFO batch value"
    },
    icon = Icons.Default.Inventory2,
    tone = UiTone.Information,
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
        contentPadding = PaddingValues(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.width(190.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Brand.Danger,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        "Restock priority",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Lowest stock first",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.restockPriority, key = { it.localWriteId ?: it.id }) { ingredient ->
                    Row(
                        Modifier.width(205.dp).heightIn(min = 64.dp)
                            .clip(Radius.shapeSm)
                            .background(Brand.SurfaceRaised)
                            .clickable { vm.select(ingredient) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Brand.Danger,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
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
private fun TabBar(state: InventoryUiState, canManageCosting: Boolean, vm: InventoryViewModel) {
    PremiumTabBar(
        options = buildList {
            add(
            TabOption(InventoryTab.INGREDIENTS.name, "Ingredients", state.ingredients.size),
            )
            add(
            TabOption(InventoryTab.SUPPLIERS.name, "Suppliers", state.suppliers.size),
            )
            if (canManageCosting) {
                add(TabOption(InventoryTab.RECIPES.name, "Recipe links", state.recipeMenuItems.size))
            }
        },
        selectedId = state.tab.name,
        onSelect = { id -> vm.selectTab(InventoryTab.valueOf(id)) },
    )
}

// -------------------------------------------------------------- recipe links

@Composable
private fun RecipesPane(
    state: InventoryUiState,
    canManageCosting: Boolean,
    vm: InventoryViewModel,
    modifier: Modifier = Modifier,
) {
    if (!canManageCosting) return
    if (state.recipeMenuItems.isEmpty()) {
        SectionCard(modifier, elevated = true) {
            DesignedEmptyState(
                title = "No stock-costed menu items",
                body = "Food, drink, dessert and hookah items appear here after the menu has synced.",
                icon = Icons.Default.Inventory2,
                primaryLabel = "Refresh",
                onPrimary = vm::retry,
            )
        }
        return
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(state.recipeMenuItems, query) {
        val needle = query.trim()
        if (needle.isEmpty()) state.recipeMenuItems else state.recipeMenuItems.filter {
            it.name.contains(needle, ignoreCase = true) || it.sku.contains(needle, ignoreCase = true)
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OperationalBanner(
            title = "Protected recipe costing",
            detail = "These links decide which ingredients are deducted after a paid sale. Changes require a live connection and are never queued offline.",
            tone = UiTone.Warning,
            icon = Icons.Default.WarningAmber,
        )
        SearchInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search menu item or SKU",
            modifier = Modifier.fillMaxWidth(),
        )
        BoxWithConstraints(Modifier.fillMaxSize().weight(1f)) {
            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecipeItemList(filtered, state, vm, Modifier.weight(1f))
                    RecipeDetail(state, vm, Modifier.width(420.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecipeItemList(filtered, state, vm, Modifier.weight(0.46f))
                    RecipeDetail(state, vm, Modifier.weight(0.54f))
                }
            }
        }
    }
}

@Composable
private fun RecipeItemList(
    items: List<cloud.dcompany.erp.core.db.MenuItemEntity>,
    state: InventoryUiState,
    vm: InventoryViewModel,
    modifier: Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "Menu items",
        subtitle = "Select an item to inspect its active stock link",
        icon = Icons.Default.Inventory2,
        contentPadding = PaddingValues(0.dp),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                val selected = state.selectedRecipeMenuItemId == item.id
                DataListRow(
                    modifier = Modifier.background(
                        if (selected) Brand.SurfaceRaised else Color.Transparent,
                    ),
                    onClick = { vm.selectRecipeMenuItem(item) },
                    content = {
                        Text(
                            item.name,
                            color = Brand.Foreground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${item.sku} · ${item.type}",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                PanelDivider()
            }
        }
    }
}

@Composable
private fun RecipeDetail(state: InventoryUiState, vm: InventoryViewModel, modifier: Modifier) {
    val item = state.selectedRecipeMenuItem
    val active = state.activeRecipe
    SectionCard(
        modifier = modifier,
        title = item?.name ?: "Recipe details",
        subtitle = item?.let { "${it.sku} · future paid sales only" }
            ?: "Select a menu item to inspect its stock deductions",
        icon = Icons.Default.Inventory2,
        contentPadding = PaddingValues(14.dp),
    ) {
        when {
            item == null -> DesignedEmptyState(
                title = "Select a menu item",
                body = "Choose an item to view or create its ingredient deductions.",
                icon = Icons.Default.Inventory2,
            )
            state.recipesLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.Gold)
            }
            state.recipesError != null -> DesignedEmptyState(
                title = "Could not load recipe",
                body = state.recipesError,
                icon = Icons.Default.CloudOff,
                primaryLabel = "Retry",
                onPrimary = vm::retryRecipes,
            )
            active == null -> DesignedEmptyState(
                title = "No active recipe",
                body = "Sales of ${item.name} do not currently deduct ingredient stock. Link at least one ingredient before relying on COGS.",
                icon = Icons.Default.WarningAmber,
                primaryLabel = "Link recipe",
                onPrimary = { vm.openDialog(InventoryDialog.RecipeCreate(item)) },
            )
            else -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active.name, color = Brand.Foreground, fontWeight = FontWeight.Bold)
                        Text(
                            "Version ${active.version} · yield ${active.yieldQty.asQty()}",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = { vm.openDialog(InventoryDialog.ConfirmDeleteRecipe(active)) },
                    ) { Text("Deactivate", color = Brand.Danger) }
                }
                InfoRow("Recorded recipe cost", active.costMinor.asRupees())
                PanelDivider()
                Text("Ingredient deductions", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                if (active.lines.isEmpty()) {
                    OperationalBanner(
                        title = "Empty recipe",
                        detail = "This item will not deduct stock until at least one ingredient line is added.",
                        tone = UiTone.Warning,
                        icon = Icons.Default.WarningAmber,
                    )
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(active.lines, key = { it.id }) { line ->
                        val ingredient = state.syncedIngredients.firstOrNull { it.id == line.ingredientId }
                        Column(
                            Modifier.fillMaxWidth().clip(Radius.shapeSm)
                                .background(Brand.SurfaceRaised).padding(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ingredient?.name ?: "Unavailable ingredient",
                                        color = if (ingredient == null) Brand.Danger else Brand.Foreground,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${line.qty.asQty()} ${ingredient?.baseUnit.orEmpty()}" +
                                            if (line.wastagePct > 0) " + ${(line.wastagePct * 100).asQty()}% waste" else "",
                                        color = Brand.ForegroundMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        vm.openDialog(InventoryDialog.RecipeLineForm(active, line))
                                    },
                                ) { Text("Edit") }
                                TextButton(
                                    onClick = {
                                        vm.openDialog(
                                            InventoryDialog.ConfirmDeleteRecipeLine(active, line),
                                        )
                                    },
                                ) { Text("Remove", color = Brand.Danger) }
                            }
                        }
                    }
                }
                ErpButton(
                    text = "Add ingredient",
                    onClick = { vm.openDialog(InventoryDialog.RecipeLineForm(active, null)) },
                    enabled = state.syncedIngredients.isNotEmpty(),
                    leadingIcon = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
                    ingredient.stockValueMinor?.asRupees() ?: "Value unavailable",
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
                color = Brand.Warning,
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
                    color = Brand.Warning,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelRemoval, enabled = canWrite) { Text("Cancel") }
            }
        } else if (ingredient.pendingLocalId != null) {
            Text(
                "Not synced yet",
                color = Brand.Information,
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
        fraction < 0.4f -> Brand.Warning
        else -> Brand.Good
    }
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brand.SurfaceRaised)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(colour))
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit, tint: Color = Brand.Information) {
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
            label = "Weighted cost",
            value = "${ingredient.avgCostMinor.asRupees()}/${ingredient.baseUnit}",
        )
        InfoRow(
            label = "Exact FIFO value",
            value = ingredient.stockValueMinor?.asRupees() ?: "Unavailable — refresh Inventory",
            valueColor = if (ingredient.stockValueMinor == null) Brand.Warning else Brand.Foreground,
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
                    BatchRow(
                        batch,
                        ingredient.baseUnit,
                        state.branches.firstOrNull { it.id == batch.branchId }?.name,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchRow(batch: BatchCacheEntity, unit: String, branchName: String?) {
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
            Text("${batch.costPerUnitMinor.asRupees()}/$unit", color = Brand.Foreground)
        }
        Text(
            (branchName?.let { "$it · " } ?: "") + "Received ${batch.receivedAt.asDay()}" +
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
                color = Brand.Warning,
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
                    color = Brand.Warning,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelRemoval, enabled = canWrite) { Text("Cancel") }
            }
        } else if (supplier.pendingLocalId != null) {
            Text(
                "Not synced yet",
                color = Brand.Information,
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
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
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

private data class RecipeLineDraft(
    val ingredientId: String = "",
    val qty: String = "",
    val wastagePercent: String = "0",
) {
    fun error(lineNumber: Int): String? = when {
        ingredientId.isBlank() -> "Select an ingredient on recipe line $lineNumber."
        qty.toDoubleOrNull() == null || qty.toDouble() <= 0 ->
            "Enter a quantity greater than zero on recipe line $lineNumber."
        wastagePercent.toDoubleOrNull() == null || wastagePercent.toDouble() !in 0.0..100.0 ->
            "Wastage on recipe line $lineNumber must be from 0 to 100%."
        else -> null
    }

    fun toBody(): RecipeLineBody = RecipeLineBody(
        ingredientId = ingredientId,
        qty = qty.toDouble(),
        wastagePct = wastagePercent.toDouble() / 100.0,
    )
}

@Composable
private fun RecipeCreateDialog(
    menuItem: cloud.dcompany.erp.core.db.MenuItemEntity,
    state: InventoryUiState,
    vm: InventoryViewModel,
) {
    var name by remember(menuItem.id) { mutableStateOf("${menuItem.name} recipe") }
    var yieldQty by remember(menuItem.id) { mutableStateOf("1") }
    var lines by remember(menuItem.id) {
        mutableStateOf(
            listOf(RecipeLineDraft(ingredientId = state.syncedIngredients.firstOrNull()?.id.orEmpty())),
        )
    }
    FormDialog(
        title = "Link recipe to ${menuItem.name}",
        confirmLabel = "Activate recipe",
        busy = state.busy,
        error = state.formError,
        width = 760.dp,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val parsedYield = yieldQty.toDoubleOrNull()
            val lineError = lines.mapIndexedNotNull { index, line -> line.error(index + 1) }.firstOrNull()
            val duplicates = lines.map { it.ingredientId }.filter(String::isNotBlank)
                .groupingBy { it }.eachCount().any { it.value > 1 }
            when {
                name.isBlank() -> vm.showFormError("Enter a recipe name.")
                parsedYield == null || parsedYield <= 0 ->
                    vm.showFormError("Yield must be greater than zero.")
                lineError != null -> vm.showFormError(lineError)
                duplicates -> vm.showFormError(
                    "Each ingredient can appear only once. Combine duplicate quantities into one line.",
                )
                else -> vm.createRecipe(menuItem, name, parsedYield, lines.map(RecipeLineDraft::toBody))
            }
        },
    ) {
        OperationalBanner(
            title = "Future sales only",
            detail = "Activating this link changes ingredient deductions for future paid sales. Past sales and stock movements are not rewritten.",
            tone = UiTone.Warning,
            icon = Icons.Default.WarningAmber,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Recipe name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalField(
            value = yieldQty,
            onValueChange = { yieldQty = it },
            label = "Menu units produced by these quantities",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Example: if the quantities make 4 portions, enter yield 4. The server divides each ingredient deduction by 4 per sale.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        lines.forEachIndexed { index, line ->
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PickerField(
                    label = "Ingredient ${index + 1}",
                    selectedLabel = state.syncedIngredients.firstOrNull { it.id == line.ingredientId }
                        ?.let { "${it.name} (${it.baseUnit})" } ?: "— select —",
                    options = state.syncedIngredients.map { it.id to "${it.name} (${it.baseUnit})" },
                    onSelect = { id -> lines = lines.replaceAt(index) { it.copy(ingredientId = id) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DecimalField(
                        value = line.qty,
                        onValueChange = { value -> lines = lines.replaceAt(index) { it.copy(qty = value) } },
                        label = "Quantity for full yield",
                        modifier = Modifier.weight(1f),
                    )
                    DecimalField(
                        value = line.wastagePercent,
                        onValueChange = { value ->
                            lines = lines.replaceAt(index) { it.copy(wastagePercent = value) }
                        },
                        label = "Wastage %",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (lines.size > 1) {
                    TextButton(onClick = { lines = lines.removeAt(index) }) {
                        Text("Remove line", color = Brand.Danger)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { lines = lines + RecipeLineDraft() },
            enabled = state.syncedIngredients.isNotEmpty(),
        ) { Text("Add ingredient") }
    }
}

@Composable
private fun RecipeLineDialog(
    recipe: Recipe,
    editing: RecipeLine?,
    state: InventoryUiState,
    vm: InventoryViewModel,
) {
    val initialIngredient = editing?.ingredientId ?: state.syncedIngredients.firstOrNull()?.id.orEmpty()
    var ingredientId by remember(recipe.id, editing?.id) { mutableStateOf(initialIngredient) }
    var qty by remember(recipe.id, editing?.id) { mutableStateOf(editing?.qty?.asQty() ?: "") }
    var wastage by remember(recipe.id, editing?.id) {
        mutableStateOf(editing?.let { (it.wastagePct * 100).asQty() } ?: "0")
    }
    FormDialog(
        title = if (editing == null) "Add recipe ingredient" else "Edit recipe ingredient",
        confirmLabel = if (editing == null) "Add line" else "Save line",
        busy = state.busy,
        error = state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val draft = RecipeLineDraft(ingredientId, qty, wastage)
            val duplicate = editing == null && recipe.lines.any { it.ingredientId == ingredientId }
            when {
                draft.error(1) != null -> vm.showFormError(draft.error(1)!!)
                duplicate -> vm.showFormError(
                    "This ingredient is already linked. Edit its existing line instead.",
                )
                else -> vm.saveRecipeLine(recipe, editing, draft.toBody())
            }
        },
    ) {
        PickerField(
            label = "Ingredient",
            selectedLabel = state.syncedIngredients.firstOrNull { it.id == ingredientId }
                ?.let { "${it.name} (${it.baseUnit})" } ?: "— select —",
            options = state.syncedIngredients.map { it.id to "${it.name} (${it.baseUnit})" },
            onSelect = { ingredientId = it },
        )
        DecimalField(
            value = qty,
            onValueChange = { qty = it },
            label = "Quantity for full yield (${recipe.yieldQty.asQty()} menu units)",
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalField(
            value = wastage,
            onValueChange = { wastage = it },
            label = "Wastage %",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GrnDialog(state: InventoryUiState, vm: InventoryViewModel) {
    val pickableIngredients = state.syncedIngredients
    val pickableSuppliers = state.syncedSuppliers
    var supplierId by remember { mutableStateOf(pickableSuppliers.firstOrNull()?.id ?: "") }
    var invoiceNo by remember { mutableStateOf("") }
    var invoiceTotal by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var lines by remember {
        mutableStateOf(listOf(GrnLineDraft(ingredientId = pickableIngredients.firstOrNull()?.id ?: "")))
    }

    val branchId = state.branchId
    val lineTotals = lines.map { line ->
        grnLineTotalMinor(line.qty, parseRupeesToMinor(line.unitCostRupees))
    }
    // The backend rounds each line HALF_UP to whole paise before summing.
    // Never round the combined receipt or truncate fractional paise here.
    val totalMinor = grnReceiptTotalMinor(
        lines.map { it.qty to parseRupeesToMinor(it.unitCostRupees) },
    )

    FormDialog(
        title = "Receive stock (GRN)",
        confirmLabel = "Queue receipt",
        busy = state.busy,
        error = state.formError,
        width = 760.dp,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val lineError = lines.mapIndexedNotNull { index, line ->
                line.validationError(index + 1)
            }.firstOrNull()
            val ready = lines.mapNotNull { it.toBody() }
            val invoiceTotalMinor = parseRupeesToMinor(invoiceTotal)
            when {
                branchId.isNullOrBlank() -> vm.showFormError("Select a branch.")
                supplierId.isBlank() -> vm.showFormError("Select a supplier.")
                lineError != null -> vm.showFormError(lineError)
                ready.isEmpty() -> vm.showFormError("Add at least one line.")
                invoiceNo.isNotBlank() && invoiceTotal.isBlank() ->
                    vm.showFormError("Enter the supplier invoice total, or clear the invoice number.")
                invoiceTotal.isNotBlank() && invoiceNo.isBlank() ->
                    vm.showFormError("Enter the supplier invoice number, or clear the invoice total.")
                invoiceTotal.isNotBlank() && invoiceTotalMinor == null ->
                    vm.showFormError("Enter a valid supplier invoice total.")
                totalMinor == null -> vm.showFormError(
                    "Check every quantity and unit cost. Quantities support up to 4 decimal places.",
                )
                invoiceTotalMinor != null && invoiceTotalMinor != totalMinor ->
                    vm.showFormError(
                        "Invoice total ${invoiceTotalMinor.asRupees()} does not match the " +
                            "capitalised line total ${totalMinor.asRupees()}. Correct the quantities, " +
                            "unit costs or invoice total; no receipt was queued.",
                    )
                else -> vm.postGrn(
                    supplierId = supplierId,
                    branchId = branchId,
                    invoiceNo = invoiceNo,
                    supplierInvoiceAmountMinor = invoiceTotalMinor,
                    notesText = notes,
                    lines = ready,
                )
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
        DecimalField(
            value = invoiceTotal,
            onValueChange = { invoiceTotal = it },
            label = "Supplier invoice total (₹)",
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "The invoice total must equal the capitalised line total after each line is rounded to paise. " +
                "Separate freight, tax, discounts or unexplained variance cannot be queued here. " +
                "Received time is captured when you tap Queue receipt.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
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
                lineTotals[index]?.let { lineTotal ->
                    Text(
                        "Line total ${lineTotal.asRupees()}",
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
                totalMinor?.let { "Capitalised total ${it.asRupees()}" } ?: "Complete all lines",
                color = Brand.Foreground,
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
                delta < 0 && -delta > ingredient.currentQty ->
                    vm.showFormError(
                        "Only ${ingredient.currentQty.asQty()} ${ingredient.baseUnit} is available in this branch.",
                    )
                else -> vm.postAdjustment(ingredient, branchId, type, typed, note)
            }
        },
    ) {
        Text(
            "On hand now: ${ingredient.currentQty.asQty()} ${ingredient.baseUnit}",
            color = Brand.ForegroundMuted,
        )
        InfoRow(
            label = "Branch",
            value = state.branches.firstOrNull { it.id == branchId }?.name ?: "Unavailable",
        )
        Text(
            TRANSFER_UNAVAILABLE_MESSAGE,
            color = Brand.Warning,
            style = MaterialTheme.typography.labelSmall,
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
                        "That exceeds this branch's recorded balance and cannot be queued.",
                        color = Brand.Warning,
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

// ------------------------------------------------------------------ helpers

private fun Double.asQtyInput(): String = if (this == 0.0) "" else asQty().replace(",", "")

private fun GrnLineDraft.validationError(lineNumber: Int): String? {
    if (ingredientId.isBlank()) return "Line $lineNumber: select an ingredient."
    val quantity = qty.toDoubleOrNull()
    if (quantity == null || !quantity.isFinite() || quantity <= 0.0) {
        return "Line $lineNumber: quantity must be greater than 0."
    }
    if (!isSupportedGrnQuantity(qty)) {
        return "Line $lineNumber: quantity supports up to 10 whole-number digits and 4 decimal places."
    }
    val unitCostMinor = parseRupeesToMinor(unitCostRupees)
    if (unitCostMinor == null) {
        return "Line $lineNumber: unit cost must be rupees with no more than 2 decimal places."
    }
    if (grnLineTotalMinor(qty, unitCostMinor) == null) {
        return "Line $lineNumber: quantity and unit cost are too large to value safely."
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
