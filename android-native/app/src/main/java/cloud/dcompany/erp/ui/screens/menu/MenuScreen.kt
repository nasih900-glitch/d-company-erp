package cloud.dcompany.erp.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.MenuAccess
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.PricingUnlockDialog
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.SearchInput
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
fun MenuScreen(access: MenuAccess = MenuAccess()) {
    val vm: MenuViewModel = viewModel()
    val state by vm.state.collectAsState()
    SideEffect { vm.updateAccess(access) }
    var search by rememberSaveable { mutableStateOf("") }
    val searchedItems = remember(state.visibleItems, search) {
        val query = search.trim()
        if (query.isEmpty()) state.visibleItems else state.visibleItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.sku.contains(query, ignoreCase = true) ||
                it.type.contains(query, ignoreCase = true)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (!access.canManageMenu) ViewOnlyNotice()
        MenuSummary(state)
        state.notice?.let { NoticeBanner(it, vm::dismissNotice) }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val categoryPanel: @Composable (Modifier) -> Unit = { modifier ->
                MenuCategoryPanel(
                    state = state,
                    access = access,
                    modifier = modifier,
                    onCreate = vm::startCreateCategory,
                    onRetry = vm::retry,
                    onSelect = vm::selectCategory,
                    onEdit = vm::startEditCategory,
                    onRetrySync = vm::retryCategorySync,
                )
            }
            val itemsPanel: @Composable (Modifier) -> Unit = { modifier ->
                MenuItemsPanel(
                    state = state,
                    access = access,
                    search = search,
                    searchedItems = searchedItems,
                    modifier = modifier,
                    onSearch = { search = it },
                    onCreate = vm::startCreateItem,
                    onEditDetails = vm::startEditItemDetails,
                    onEditPricing = vm::startEditItemPricing,
                    onRetrySync = vm::retryItemDetailsSync,
                )
            }
            if (maxWidth >= 820.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    categoryPanel(Modifier.width(286.dp).fillMaxHeight())
                    itemsPanel(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    val categoryHeight = if (state.loading || state.couldNotLoad || state.categories.isEmpty()) {
                        360.dp
                    } else {
                        220.dp
                    }
                    categoryPanel(Modifier.fillMaxWidth().height(categoryHeight))
                    itemsPanel(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }

    state.categoryEditor?.takeIf { access.canManageMenu }?.let { ed ->
        CategoryEditorDialog(
            editor = ed,
            saving = state.categorySaving,
            error = state.categorySaveError,
            onChange = vm::categoryEditorChanged,
            onCancel = vm::cancelCategoryEdit,
            onSave = vm::saveCategory,
        )
    }
    state.itemDetailsEditor?.takeIf { access.canManageMenu }?.let { ed ->
        ItemDetailsDialog(
            editor = ed,
            // A still-unsynced category (a pending create, no real server id
            // yet) can't be assigned to an item — see LocalMenuItemEntity's
            // doc comment for why that dependency is deliberately not
            // resolved. It'll appear here the moment it syncs.
            categories = state.categories.filter { !it.id.startsWith("local:") },
            saving = state.itemDetailsSaving,
            error = state.itemDetailsSaveError,
            onChange = vm::itemDetailsChanged,
            onCancel = vm::cancelItemDetailsEdit,
            onSave = vm::saveItemDetails,
        )
    }
    state.itemPricingEditor?.takeIf { access.canManageMenu }?.let { ed ->
        ItemPricingDialog(
            editor = ed,
            saving = state.itemPricingSaving,
            error = state.itemPricingSaveError,
            onChange = vm::itemPricingChanged,
            onCancel = vm::cancelItemPricingEdit,
            onSave = vm::saveItemPricing,
        )
    }
    state.itemCreateEditor?.takeIf { access.canManageMenu }?.let { ed ->
        ItemCreateDialog(
            editor = ed,
            // Same reasoning as the details dialog above — a brand-new item
            // needs a real server category_id immediately (create is always
            // online), so a still-unsynced category can't be offered here.
            categories = state.categories.filter { !it.id.startsWith("local:") },
            saving = state.itemCreateSaving,
            error = state.itemCreateSaveError,
            onChange = vm::itemCreateChanged,
            onCancel = vm::cancelItemCreate,
            onSave = vm::saveItemCreate,
        )
    }
    if (state.showPricingUnlock && access.canManageMenu) {
        PricingUnlockDialog(onDismiss = vm::dismissPricingUnlock, onUnlocked = vm::pricingUnlocked)
    }
}

@Composable
private fun MenuSummary(state: MenuUiState) {
    val available = state.allItems.count(ItemRow::isAvailable)
    val soldOut = state.allItems.size - available
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cards: List<@Composable (Modifier) -> Unit> = listOf(
            { modifier -> CompactStatCard("Categories", state.categories.size.toString(), modifier, "Menu groups", Icons.Filled.Category, UiTone.Brand) },
            { modifier -> CompactStatCard("Menu items", state.allItems.size.toString(), modifier, "Configured products", Icons.Filled.RestaurantMenu, UiTone.Information) },
            { modifier -> CompactStatCard("Available", available.toString(), modifier, "Ready for sale", Icons.Filled.Inventory2, UiTone.Success) },
            { modifier -> CompactStatCard("Sold out", soldOut.toString(), modifier, "Unavailable at POS", Icons.Filled.Inventory2, if (soldOut > 0) UiTone.Warning else UiTone.Neutral) },
        )
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                cards.forEach { it(Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                cards.chunked(2).forEach { rowCards ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        rowCards.forEach { card -> card(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuCategoryPanel(
    state: MenuUiState,
    access: MenuAccess,
    modifier: Modifier,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (CategoryRow) -> Unit,
    onRetrySync: (CategoryRow) -> Unit,
) {
    SectionCard(
        title = "Categories",
        subtitle = "${state.categories.size} configured",
        icon = Icons.Filled.Category,
        modifier = modifier,
        action = {
            ErpButton(
                text = "Add",
                onClick = onCreate,
                enabled = access.canManageMenu,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Filled.Add,
            )
        },
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 150.dp), Alignment.Center) {
                CircularProgressIndicator(color = Brand.Gold)
            }
            state.couldNotLoad -> DesignedEmptyState(
                title = "Menu not available",
                body = state.loadError ?: "The tablet has not downloaded the menu. Check the connection and retry.",
                icon = Icons.Filled.RestaurantMenu,
                primaryLabel = "Retry",
                onPrimary = onRetry,
                primaryIcon = Icons.Filled.Refresh,
                modifier = Modifier.heightIn(min = 190.dp),
            )
            state.categories.isEmpty() -> DesignedEmptyState(
                title = "No categories yet",
                body = "Create the first category before adding products.",
                icon = Icons.Filled.Category,
                primaryLabel = if (access.canManageMenu) "Add category" else null,
                onPrimary = if (access.canManageMenu) onCreate else null,
                primaryIcon = Icons.Filled.Add,
                modifier = Modifier.heightIn(min = 190.dp),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(state.categories, key = { it.id }) { cat ->
                    CategoryRowItem(
                        cat = cat,
                        selected = cat.name == state.selectedCategoryName,
                        canWrite = access.canManageMenu,
                        onClick = { onSelect(cat.name) },
                        onEdit = { onEdit(cat) },
                        onRetrySync = { onRetrySync(cat) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuItemsPanel(
    state: MenuUiState,
    access: MenuAccess,
    search: String,
    searchedItems: List<ItemRow>,
    modifier: Modifier,
    onSearch: (String) -> Unit,
    onCreate: (String) -> Unit,
    onEditDetails: (ItemRow) -> Unit,
    onEditPricing: (ItemRow) -> Unit,
    onRetrySync: (ItemRow) -> Unit,
) {
    val selected = state.selectedCategory
    val canCreate = selected != null && !selected.id.startsWith("local:") && access.canManageMenu
    SectionCard(
        title = selected?.name ?: "Menu items",
        subtitle = if (selected == null) "Select a category to manage its products" else "${state.visibleItems.size} items in this category",
        icon = Icons.Filled.RestaurantMenu,
        modifier = modifier,
        action = {
            if (selected != null && !selected.id.startsWith("local:")) {
                ErpButton(
                    text = "Add item",
                    onClick = { onCreate(selected.id) },
                    enabled = canCreate,
                    leadingIcon = Icons.Filled.Add,
                )
            }
        },
    ) {
        SearchInput(
            value = search,
            onValueChange = onSearch,
            placeholder = "Search this category by item, SKU or type",
            enabled = selected != null && !state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 220.dp), Alignment.Center) {
                CircularProgressIndicator(color = Brand.Gold)
            }
            selected == null -> DesignedEmptyState(
                title = "Select a category",
                body = "Choose a category to review its products, availability, pricing and tax configuration.",
                icon = Icons.Filled.Category,
            )
            state.visibleItems.isEmpty() -> DesignedEmptyState(
                title = "No items in ${selected.name}",
                body = "Add the first product when this category is ready to sell.",
                icon = Icons.Filled.RestaurantMenu,
                primaryLabel = if (canCreate) "Add item" else null,
                onPrimary = if (canCreate) ({ onCreate(selected.id) }) else null,
            )
            searchedItems.isEmpty() -> DesignedEmptyState(
                title = "No matching items",
                body = "Try a different name, SKU or product type.",
                icon = Icons.Filled.RestaurantMenu,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(searchedItems, key = { it.id }) { item ->
                    ItemRowCard(
                        item = item,
                        canWrite = access.canManageMenu,
                        onEditDetails = { onEditDetails(item) },
                        onEditPricing = { onEditPricing(item) },
                        onRetrySync = { onRetrySync(item) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ rows

@Composable
private fun CategoryRowItem(
    cat: CategoryRow,
    selected: Boolean,
    canWrite: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRetrySync: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(if (selected) Brand.SurfaceHover else Brand.SurfaceRaised)
            .border(1.dp, if (selected) Brand.GoldMuted else Brand.BorderSubtle, Radius.shapeSm)
            .clickable(onClick = onClick)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(cat.name, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                Text("Sort order ${cat.sortOrder}", color = Brand.ForegroundFaint, style = MaterialTheme.typography.labelSmall)
            }
            ErpButton(
                text = "Edit",
                onClick = onEdit,
                enabled = canWrite,
                intent = ActionIntent.Quiet,
            )
        }
        if (cat.rejectedError != null) {
            Text("Sync failed: ${cat.rejectedError}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
            ErpButton(
                text = "Retry sync",
                onClick = onRetrySync,
                enabled = canWrite,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.Refresh,
            )
        } else if (cat.pendingLocalId != null) {
            OperationalStatusBadge("Pending sync", UiTone.Information)
        }
    }
}

@Composable
private fun ItemRowCard(
    item: ItemRow,
    canWrite: Boolean,
    onEditDetails: () -> Unit,
    onEditPricing: () -> Unit,
    onRetrySync: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Brand.Foreground, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${item.sku} · ${item.type}", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
                item.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = Brand.ForegroundFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            OperationalStatusBadge(
                label = if (item.isAvailable) "Available" else "Sold out",
                tone = if (item.isAvailable) UiTone.Success else UiTone.Danger,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(item.basePriceMinor.asRupees(), color = Brand.Foreground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${"%.0f".format(item.taxRate * 100)}% GST · ${if (item.priceIncludesTax) "tax included" else "tax added"}",
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ErpButton("Price & tax", onEditPricing, enabled = canWrite, intent = ActionIntent.Secondary)
                ErpButton("Details", onEditDetails, enabled = canWrite, intent = ActionIntent.Quiet)
            }
        }
        if (item.detailsRejected != null) {
            Text("Sync failed: ${item.detailsRejected}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
            ErpButton("Retry sync", onRetrySync, enabled = canWrite, intent = ActionIntent.Destructive, leadingIcon = Icons.Filled.Refresh)
        } else if (item.detailsPending && item.localWriteId != null) {
            OperationalStatusBadge("Details pending sync", UiTone.Information)
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .border(1.dp, Brand.GoldMuted, Radius.shapeSm)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// --------------------------------------------------------------- dialogs

@Composable
private fun CategoryEditorDialog(
    editor: CategoryEditor,
    saving: Boolean,
    error: String?,
    onChange: (CategoryEditor) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var sortOrderText by rememberSaveable(editor.id) { mutableStateOf(editor.sortOrder.toString()) }
    val sortOrderValid = sortOrderText.toIntOrNull() != null
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text(if (editor.isUnsyncedDraft) "New category" else "Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sortOrderText,
                    onValueChange = { raw ->
                        // Keep the operator's exact candidate visible. Validation below
                        // rejects malformed/overflow values without silently coercing them.
                        sortOrderText = raw
                        sortOrderText.toIntOrNull()?.let { onChange(editor.copy(sortOrder = it)) }
                    },
                    label = { Text("Sort order (lower shows first)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !sortOrderValid,
                    supportingText = if (!sortOrderValid) {
                        { Text("Enter a whole number, such as 0 or 10.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = Brand.Danger)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving && editor.valid && sortOrderValid) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}

@Composable
private fun ItemDetailsDialog(
    editor: ItemDetailsEditor,
    categories: List<CategoryRow>,
    saving: Boolean,
    error: String?,
    onChange: (ItemDetailsEditor) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Item details") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.description,
                    onValueChange = { onChange(editor.copy(description = it)) },
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                CategoryDropdown(
                    categories = categories,
                    selectedId = editor.categoryId,
                    onSelect = { onChange(editor.copy(categoryId = it)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Available for sale", color = Brand.Foreground)
                    Switch(
                        checked = editor.isAvailable,
                        onCheckedChange = { onChange(editor.copy(isAvailable = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand.Gold),
                    )
                }
                Text(
                    "Price, tax and HSN are edited separately (needs your password).",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                if (error != null) Text(error, color = Brand.Danger)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving && editor.valid) {
                Text(if (saving) "Saving…" else "Save details")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}

@Composable
private fun ItemPricingDialog(
    editor: ItemPricingEditor,
    saving: Boolean,
    error: String?,
    onChange: (ItemPricingEditor) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Price & tax") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = editor.basePriceRupees,
                    onValueChange = { onChange(editor.copy(basePriceRupees = it)) },
                    label = { Text("Price (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = editor.basePriceMinor == null,
                    supportingText = if (editor.basePriceMinor == null) {
                        { Text("Enter rupees with no more than 2 decimal places.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.taxRatePercent,
                    onValueChange = { onChange(editor.copy(taxRatePercent = it)) },
                    label = { Text("GST rate (%)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !editor.taxRateValid,
                    supportingText = if (!editor.taxRateValid) {
                        { Text("GST rate must be between 0 and 100.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.hsnCode,
                    onValueChange = { onChange(editor.copy(hsnCode = it)) },
                    label = { Text("HSN/SAC (optional — a default is used if blank)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Price includes tax", color = Brand.Foreground)
                    Switch(
                        checked = editor.priceIncludesTax,
                        onCheckedChange = { onChange(editor.copy(priceIncludesTax = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Brand.Gold),
                    )
                }
                if (error != null) Text(error, color = Brand.Danger)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving && editor.valid) {
                Text(if (saving) "Saving…" else "Save price")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}

@Composable
private fun ItemCreateDialog(
    editor: ItemCreateEditor,
    categories: List<CategoryRow>,
    saving: Boolean,
    error: String?,
    onChange: (ItemCreateEditor) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("New item") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CategoryDropdown(
                    categories = categories,
                    selectedId = editor.categoryId,
                    onSelect = { onChange(editor.copy(categoryId = it)) },
                )
                OutlinedTextField(
                    value = editor.sku,
                    onValueChange = { onChange(editor.copy(sku = it)) },
                    label = { Text("SKU") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TypeDropdown(selected = editor.type, onSelect = { onChange(editor.copy(type = it)) })
                OutlinedTextField(
                    value = editor.basePriceRupees,
                    onValueChange = { onChange(editor.copy(basePriceRupees = it)) },
                    label = { Text("Price (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = editor.basePriceMinor == null,
                    supportingText = if (editor.basePriceMinor == null) {
                        { Text("Enter rupees with no more than 2 decimal places.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.taxRatePercent,
                    onValueChange = { onChange(editor.copy(taxRatePercent = it)) },
                    label = { Text("GST rate (%, optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = !editor.taxRateValid,
                    supportingText = if (!editor.taxRateValid) {
                        { Text("GST rate must be between 0 and 100.") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.hsnCode,
                    onValueChange = { onChange(editor.copy(hsnCode = it)) },
                    label = { Text("HSN/SAC (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.description,
                    onValueChange = { onChange(editor.copy(description = it)) },
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = Brand.Danger)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving && editor.valid) {
                Text(if (saving) "Creating…" else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(categories: List<CategoryRow>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = categories.firstOrNull { it.id == selectedId }?.name ?: "Pick a category"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { cat ->
                DropdownMenuItem(text = { Text(cat.name) }, onClick = { onSelect(cat.id); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ITEM_TYPES.forEach { t ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onSelect(t); expanded = false })
            }
        }
    }
}
