package cloud.dcompany.erp.ui.screens.inventory

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import kotlin.math.abs
import kotlin.math.roundToLong

private val UNITS = listOf(
    "g" to "g (grams)",
    "ml" to "ml (millilitres)",
    "unit" to "unit (each / piece)",
)

@Composable
fun InventoryScreen(vm: InventoryViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Header(state, vm)

        when {
            state.loading && state.ingredients.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Gold)
                }

            // Nothing loaded at all: the screen is useless until this is fixed,
            // so it shows what the server actually said rather than a spinner
            // that never resolves.
            state.error != null && state.ingredients.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp).width(460.dp),
                    ) {
                        Text(
                            "Inventory could not be loaded",
                            style = MaterialTheme.typography.titleLarge,
                            color = Brand.Foreground,
                        )
                        Text(state.error!!, color = Brand.Danger)
                        Button(onClick = vm::load) { Text("Retry") }
                    }
                }

            else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (state.error != null) ErrorBanner(state.error!!)
                StatRow(state)
                if (state.restockPriority.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    RestockStrip(state, vm)
                }
                Spacer(Modifier.height(12.dp))
                TabBar(state, vm)
                Spacer(Modifier.height(12.dp))
                when (state.tab) {
                    InventoryTab.INGREDIENTS -> IngredientsPane(state, vm)
                    InventoryTab.SUPPLIERS -> SuppliersPane(state, vm)
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        is InventoryDialog.IngredientForm -> IngredientDialog(dialog.editing, state, vm)
        is InventoryDialog.SupplierForm -> SupplierDialog(dialog.editing, state, vm)
        is InventoryDialog.Grn -> GrnDialog(state, vm)
        is InventoryDialog.Adjust -> AdjustDialog(dialog.ingredient, state, vm)
        is InventoryDialog.ConfirmDeleteIngredient -> ConfirmDialog(
            title = "Delete ${dialog.ingredient.name}?",
            body = "Existing batches stay in the audit trail. Recipes that use it will " +
                "stop deducting stock at checkout.",
            confirmLabel = "Delete",
            busy = state.busy,
            error = state.formError,
            onConfirm = { vm.deleteIngredient(dialog.ingredient) },
            onDismiss = vm::closeDialog,
        )
        is InventoryDialog.ConfirmDeleteSupplier -> ConfirmDialog(
            title = "Delete ${dialog.supplier.name}?",
            body = "Past receipts from this supplier are kept.",
            confirmLabel = "Delete",
            busy = state.busy,
            error = state.formError,
            onConfirm = { vm.deleteSupplier(dialog.supplier) },
            onDismiss = vm::closeDialog,
        )
        null -> Unit
    }

    // A stock write is confirmed in words, with the resulting quantity, because
    // an adjustment applied in the wrong direction looks identical to a correct
    // one until someone counts the shelf.
    state.notice?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
            title = { Text("Stock updated") },
            text = { Text(message) },
        )
    }
}

// ------------------------------------------------------------------- chrome

@Composable
private fun Header(state: InventoryUiState, vm: InventoryViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Inventory",
                style = MaterialTheme.typography.headlineMedium,
                color = Brand.Foreground,
            )
            Text(
                "FIFO batches · recipe-driven deductions · low-stock alerts",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::load, enabled = !state.busy) { Text("Refresh") }
            if (state.tab == InventoryTab.INGREDIENTS) {
                OutlinedButton(
                    onClick = { vm.openDialog(InventoryDialog.IngredientForm(null)) },
                ) { Text("New ingredient") }
                Button(onClick = { vm.openDialog(InventoryDialog.Grn) }) {
                    Text("Receive stock (GRN)")
                }
            } else {
                Button(onClick = { vm.openDialog(InventoryDialog.SupplierForm(null)) }) {
                    Text("New supplier")
                }
            }
        }
    }
}

@Composable
private fun StatRow(state: InventoryUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("Stock value", state.stockValueMinor.asRupees(), Brand.Foreground, Modifier.weight(1f))
        StatCard("Ingredients", state.ingredients.size.toString(), Brand.Foreground, Modifier.weight(1f))
        StatCard(
            "Low stock",
            state.lowCount.toString(),
            if (state.lowCount > 0) Brand.Danger else Brand.Good,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, tone: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Brand.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Text(value, style = MaterialTheme.typography.headlineMedium, color = tone)
    }
}

/** The one thing an owner opens this screen to find out: what to buy today. */
@Composable
private fun RestockStrip(state: InventoryUiState, vm: InventoryViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brand.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Restock priority",
            style = MaterialTheme.typography.labelLarge,
            color = Brand.Danger,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.restockPriority.forEach { ingredient ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(Brand.SurfaceRaised)
                        .clickable { vm.select(ingredient.id) }
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
                }
            }
            // Keep the tiles the same size when there are fewer than six.
            repeat(6 - state.restockPriority.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TabBar(state: InventoryUiState, vm: InventoryViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabButton("Ingredients", state.tab == InventoryTab.INGREDIENTS) {
            vm.selectTab(InventoryTab.INGREDIENTS)
        }
        TabButton("Suppliers", state.tab == InventoryTab.SUPPLIERS) {
            vm.selectTab(InventoryTab.SUPPLIERS)
        }
    }
}

@Composable
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Brand.Gold else Brand.ForegroundMuted,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Box(
            Modifier.height(2.dp).width(96.dp)
                .background(if (active) Brand.Gold else Color.Transparent),
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Brand.SurfaceRaised).padding(12.dp),
    ) {
        Text(text, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
    }
}

// -------------------------------------------------------------- ingredients

@Composable
private fun IngredientsPane(state: InventoryUiState, vm: InventoryViewModel) {
    if (state.ingredients.isEmpty()) {
        EmptyState(
            title = "No ingredients yet",
            body = "Add what the kitchen and bar actually consume — milk, beans, cups — " +
                "then record a stock receipt so each one has a costed batch to draw from.",
            actionLabel = "New ingredient",
            onAction = { vm.openDialog(InventoryDialog.IngredientForm(null)) },
        )
        return
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            items(state.sortedIngredients, key = { it.id }) { ingredient ->
                IngredientRow(
                    ingredient = ingredient,
                    selected = ingredient.id == state.selectedId,
                    onClick = { vm.select(ingredient.id) },
                    onAdjust = { vm.openDialog(InventoryDialog.Adjust(ingredient)) },
                    onEdit = { vm.openDialog(InventoryDialog.IngredientForm(ingredient)) },
                    onDelete = { vm.openDialog(InventoryDialog.ConfirmDeleteIngredient(ingredient)) },
                )
            }
        }
        DetailPanel(state, vm)
    }
}

@Composable
private fun IngredientRow(
    ingredient: Ingredient,
    selected: Boolean,
    onClick: () -> Unit,
    onAdjust: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brand.SurfaceRaised else Brand.Surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ingredient.name,
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ingredient.sku,
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(140.dp)) {
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
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallAction("Adjust", onAdjust)
                SmallAction("Edit", onEdit)
                SmallAction("Delete", onDelete, Brand.Danger)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelBar(ingredient, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                if (ingredient.reorderThreshold > 0) {
                    "Reorder at ${ingredient.reorderThreshold.asQty()} ${ingredient.baseUnit}"
                } else {
                    "No reorder level set"
                },
                color = if (ingredient.isLow) Brand.Danger else Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun LevelBar(ingredient: Ingredient, modifier: Modifier = Modifier) {
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
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** FIFO batches for the selected ingredient — what the deduction engine eats first. */
@Composable
private fun DetailPanel(state: InventoryUiState, vm: InventoryViewModel) {
    val ingredient = state.selected
    Column(
        Modifier.width(360.dp).fillMaxSize().clip(RoundedCornerShape(14.dp))
            .background(Brand.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ingredient == null) {
            Text("Batches", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(
                "Tap an ingredient to see the batches it will be drawn from, oldest first.",
                color = Brand.ForegroundMuted,
            )
            return@Column
        }

        Text(ingredient.name, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "${ingredient.currentQty.asQty()} ${ingredient.baseUnit} on hand · " +
                "avg ${ingredient.avgCostMinor.asRupees()}/${ingredient.baseUnit}",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Button(
            onClick = { vm.openDialog(InventoryDialog.Adjust(ingredient)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Adjust stock") }

        Text(
            "Batches (oldest first)",
            style = MaterialTheme.typography.labelLarge,
            color = Brand.Foreground,
        )
        when {
            state.batchesLoading -> Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                CircularProgressIndicator(color = Brand.Gold)
            }
            state.batchesError != null -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.batchesError!!, color = Brand.Danger)
                OutlinedButton(onClick = { vm.select(ingredient.id) }) { Text("Retry") }
            }
            state.batches.isEmpty() -> Text(
                "No open batches. Record a stock receipt (GRN) — a positive adjustment " +
                    "needs an existing batch to attach to.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.batches, key = { it.id }) { batch ->
                    BatchRow(batch, ingredient.baseUnit)
                }
            }
        }
    }
}

@Composable
private fun BatchRow(batch: Batch, unit: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
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
private fun SuppliersPane(state: InventoryUiState, vm: InventoryViewModel) {
    if (state.suppliers.isEmpty()) {
        EmptyState(
            title = "No suppliers yet",
            body = "A stock receipt has to be attributed to a supplier, so add the shops " +
                "and distributors you buy from before recording a GRN.",
            actionLabel = "New supplier",
            onAction = { vm.openDialog(InventoryDialog.SupplierForm(null)) },
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.suppliers, key = { it.id }) { supplier ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Brand.Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        supplier.name,
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        supplier.contact ?: "No contact saved",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Column(Modifier.width(220.dp)) {
                    Text("GSTIN", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
                    Text(supplier.gstin ?: "—", color = Brand.Foreground)
                }
                Column(Modifier.width(180.dp)) {
                    Text("Terms", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
                    Text(supplier.paymentTerms ?: "—", color = Brand.Foreground)
                }
                SmallAction("Edit", { vm.openDialog(InventoryDialog.SupplierForm(supplier)) })
                SmallAction("Delete", { vm.openDialog(InventoryDialog.ConfirmDeleteSupplier(supplier)) }, Brand.Danger)
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(480.dp).padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(body, color = Brand.ForegroundMuted)
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun IngredientDialog(
    editing: Ingredient?,
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
            // The SKU is the identity other records point at, so it is set once.
            enabled = editing == null,
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
private fun SupplierDialog(editing: Supplier?, state: InventoryUiState, vm: InventoryViewModel) {
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
    var supplierId by remember { mutableStateOf(state.suppliers.firstOrNull()?.id ?: "") }
    var invoiceNo by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var lines by remember {
        mutableStateOf(listOf(GrnLineDraft(ingredientId = state.ingredients.firstOrNull()?.id ?: "")))
    }

    val branchId = state.branchId
    // Mirrors the backend's `int(qty * unit_cost_minor)` so the number shown
    // here is the number the purchase order will actually carry.
    val totalMinor = lines.sumOf { line ->
        val qty = line.qty.toDoubleOrNull() ?: 0.0
        val cost = line.unitCostRupees.toRupeesMinor()
        (qty * cost).toLong()
    }

    FormDialog(
        title = "Receive stock (GRN)",
        confirmLabel = "Record receipt",
        busy = state.busy,
        error = state.formError,
        wide = true,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val ready = lines.mapNotNull { it.toBody() }
            when {
                branchId.isNullOrBlank() -> vm.showFormError("Select a branch.")
                supplierId.isBlank() -> vm.showFormError("Select a supplier.")
                ready.size != lines.count { it.ingredientId.isNotBlank() || it.qty.isNotBlank() } ->
                    vm.showFormError(
                        "Every line needs an ingredient and a quantity above zero, and any " +
                            "expiry must be written as YYYY-MM-DD.",
                    )
                ready.isEmpty() -> vm.showFormError("Add at least one line.")
                else -> vm.postGrn(supplierId, branchId, invoiceNo, notes, ready)
            }
        },
    ) {
        if (state.branches.isEmpty()) {
            Text(
                "No branches loaded, so this receipt cannot be attributed to one. " +
                    "Close, tap Refresh, and try again.",
                color = Brand.Danger,
            )
        }
        if (state.suppliers.isEmpty()) {
            Text(
                "No suppliers yet — add one on the Suppliers tab first. The backend " +
                    "refuses a GRN without a supplier.",
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
                selectedLabel = state.suppliers.firstOrNull { it.id == supplierId }?.name ?: "— select —",
                options = state.suppliers.map { it.id to it.name },
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Brand.SurfaceRaised).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    PickerField(
                        label = "Ingredient",
                        selectedLabel = state.ingredients.firstOrNull { it.id == line.ingredientId }
                            ?.let { "${it.name} (${it.baseUnit})" } ?: "— select —",
                        options = state.ingredients.map { it.id to "${it.name} (${it.baseUnit})" },
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
                val unitCost = line.unitCostRupees.toRupeesMinor()
                val qty = line.qty.toDoubleOrNull() ?: 0.0
                if (qty > 0 && unitCost > 0) {
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
    ingredient: Ingredient,
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
        confirmLabel = "Record",
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

        // Written out in full before it is committed. A sign error here does not
        // fail loudly — it just silently corrupts stock — so the operator gets
        // to see the resulting number first.
        if (delta != null && delta != 0.0 && after != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
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
                // The server's own words, next to the button that caused them —
                // "SKU already exists" is actionable, "Request failed" is not.
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
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

/** Rupees typed by a human -> whole paise. Money never stays a Double. */
private fun String.toRupeesMinor(): Long = ((toDoubleOrNull() ?: 0.0) * 100).roundToLong()

private fun Double.asQtyInput(): String = if (this == 0.0) "" else asQty().replace(",", "")

private fun GrnLineDraft.toBody(): GrnLineBody? {
    if (ingredientId.isBlank()) return null
    val quantity = qty.toDoubleOrNull() ?: return null
    if (quantity <= 0) return null
    val expiry = expiresAt.trim()
    if (expiry.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(expiry)) return null
    return GrnLineBody(
        ingredientId = ingredientId,
        qty = quantity,
        unitCostMinor = unitCostRupees.toRupeesMinor(),
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
