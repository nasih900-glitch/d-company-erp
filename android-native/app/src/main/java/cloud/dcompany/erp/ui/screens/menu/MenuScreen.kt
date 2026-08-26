package cloud.dcompany.erp.ui.screens.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.MenuAccess
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.PricingUnlockDialog
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius

@Composable
fun MenuScreen(access: MenuAccess = MenuAccess()) {
    val vm: MenuViewModel = viewModel()
    val state by vm.state.collectAsState()
    SideEffect { vm.updateAccess(access) }

    Row(Modifier.fillMaxSize().background(Brand.Background)) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Menu", style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground)
            }
            Text(
                "Categories & items · price/tax changes need your password",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
            if (!access.canManageMenu) ViewOnlyNotice()
            Button(
                onClick = vm::startCreateCategory,
                enabled = access.canManageMenu,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add category")
            }

            when {
                state.loading -> CircularProgressIndicator(color = Brand.Gold)
                state.couldNotLoad -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Could not load the menu", color = Brand.Foreground)
                    Text(
                        state.loadError
                            ?: "The tablet has not downloaded the menu yet. Check the connection and retry.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::retry) { Text("Retry") }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.categories, key = { it.id }) { cat ->
                        CategoryRowItem(
                            cat = cat,
                            selected = cat.name == state.selectedCategoryName,
                            canWrite = access.canManageMenu,
                            onClick = { vm.selectCategory(cat.name) },
                            onEdit = { vm.startEditCategory(cat) },
                            onRetrySync = { vm.retryCategorySync(cat) },
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Brand.Border),
        )

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            val selectedCategory = state.selectedCategory
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selectedCategory?.name ?: "Pick a category",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Brand.Foreground,
                )
                // A still-unsynced category (a pending create, no real
                // server id yet) has nothing an item could be assigned to —
                // the button reappears the moment this category syncs.
                if (selectedCategory != null && !selectedCategory.id.startsWith("local:")) {
                    Button(
                        onClick = { vm.startCreateItem(selectedCategory.id) },
                        enabled = access.canManageMenu,
                    ) { Text("Add item") }
                }
            }
            Spacer(Modifier.height(12.dp))

            state.notice?.let {
                NoticeBanner(it, vm::dismissNotice)
                Spacer(Modifier.height(10.dp))
            }

            if (state.loading) {
                // Otherwise, while the very first sync is still in flight,
                // this panel would invite "Add a category" (the left panel
                // shows a spinner for the exact same reason) right as a real
                // category list might just not have arrived yet — a tap on
                // that invitation before the real data lands can produce a
                // genuine, avoidable duplicate.
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Gold)
                }
            } else if (selectedCategory == null) {
                Text(
                    "Add a category to start building the menu.",
                    color = Brand.ForegroundMuted,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else if (state.visibleItems.isEmpty()) {
                Text(
                    "No items in this category yet.",
                    color = Brand.ForegroundMuted,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.visibleItems, key = { it.id }) { item ->
                        ItemRowCard(
                            item = item,
                            canWrite = access.canManageMenu,
                            onEditDetails = { vm.startEditItemDetails(item) },
                            onEditPricing = { vm.startEditItemPricing(item) },
                            onRetrySync = { vm.retryItemDetailsSync(item) },
                        )
                    }
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
            .background(if (selected) Brand.SurfaceRaised else Brand.Surface)
            .border(1.dp, if (selected) Brand.Gold else Brand.Border, Radius.shapeSm)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(cat.name, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onEdit, enabled = canWrite) { Text("Edit") }
        }
        if (cat.rejectedError != null) {
            Text("Sync failed: ${cat.rejectedError}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onRetrySync, enabled = canWrite) { Text("Retry") }
        } else if (cat.pendingLocalId != null) {
            Text("Not synced yet", color = Brand.GoldMuted, style = MaterialTheme.typography.labelSmall)
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
            .border(1.dp, Brand.Border, Radius.shapeMd)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                Text("${item.sku} · ${item.type}", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
            }
            if (!item.isAvailable) {
                Text("SOLD OUT", color = Brand.Danger, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${item.basePriceMinor.asRupees()} · ${"%.0f".format(item.taxRate * 100)}% GST",
                color = Brand.Gold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEditPricing, enabled = canWrite) { Text("Price") }
                TextButton(onClick = onEditDetails, enabled = canWrite) { Text("Details") }
            }
        }
        if (item.detailsRejected != null) {
            Text("Sync failed: ${item.detailsRejected}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onRetrySync, enabled = canWrite) { Text("Retry") }
        } else if (item.detailsPending && item.localWriteId != null) {
            Text("Details not synced yet", color = Brand.GoldMuted, style = MaterialTheme.typography.labelSmall)
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
                    value = editor.sortOrder.toString(),
                    onValueChange = { onChange(editor.copy(sortOrder = it.toIntOrNull() ?: editor.sortOrder)) },
                    label = { Text("Sort order (lower shows first)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = Brand.Danger)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !saving && editor.valid) {
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
            modifier = Modifier.fillMaxWidth().menuAnchor(),
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
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ITEM_TYPES.forEach { t ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onSelect(t); expanded = false })
            }
        }
    }
}
