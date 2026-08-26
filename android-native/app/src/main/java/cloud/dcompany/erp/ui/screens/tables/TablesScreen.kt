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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.TablesAccess
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.core.sync.CafeBillLineProjection
import cloud.dcompany.erp.core.sync.CafeBillProjection
import cloud.dcompany.erp.ui.components.VoidReasonInput
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.resolvedVoidReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TablesScreen(access: TablesAccess = TablesAccess(), vm: TablesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val selectedBill = state.selectedBill
    var discardAction by remember { mutableStateOf<BlockedCafeAction?>(null) }
    SideEffect { vm.updateAccess(access) }

    Column(Modifier.fillMaxSize()) {
        if (!access.canCreateOrders) ViewOnlyNotice()
        if (state.refreshError != null && state.tables.isNotEmpty()) {
            TablesRefreshErrorBanner(state.refreshError!!, vm::load)
        }
        if (state.blockedActions.isNotEmpty()) {
            BlockedCafeActionsPanel(
                actions = state.blockedActions,
                online = state.online,
                canWrite = access.canCreateOrders || access.canCancelItems || access.canSendToPos,
                onRetry = vm::retryBlockedAction,
                onDiscard = { discardAction = it },
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
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
                        if (!state.everSynced && state.blockingLoadError == null) {
                            CircularProgressIndicator(color = Brand.Gold)
                        }
                        Text(
                            if (state.everSynced) "No tables set up" else "Waiting for the first sync",
                            color = Brand.Foreground,
                        )
                        Text(
                            state.blockingLoadError
                                ?: "Add floors and tables in the ERP, then refresh.",
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
                            TableTile(table, enabled = true) { vm.openTable(table) }
                        }
                    }
                }
            }
        }
    }

    state.selectedTable?.let { table ->
        when {
            state.draftingRound -> OrderBuilder(
                table = table,
                state = state,
                canWrite = access.canCreateOrders,
                onAdd = vm::add,
                onIncrement = vm::increment,
                onRemove = vm::remove,
                onNote = vm::updateNote,
                onDismiss = vm::cancelDraft,
                onSaveRound = vm::saveRound,
            )
            selectedBill != null -> BillDialog(
                table = table,
                bill = selectedBill,
                busy = state.busy,
                access = access,
                onAddRound = vm::startAnotherRound,
                onCancelLine = vm::requestLineCancellation,
                onCancelBill = vm::requestBillCancellation,
                onSendToPos = vm::sendSelectedBillToPos,
                onDismiss = vm::closeTable,
            )
        }
    }

    state.notice?.let { msg ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(onClick = vm::dismissNotice) { Text("OK") } },
            title = { Text("Tables") },
            text = { Text(msg) },
        )
    }

    discardAction?.let { action ->
        AlertDialog(
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            onDismissRequest = { discardAction = null },
            title = { Text("Discard this saved ${action.actionLabel.lowercase()}?") },
            text = {
                Text(
                    "Use this only after refreshing and checking Table ${action.tableCode}. " +
                        "It removes this tablet's refused action; it never changes the confirmed " +
                        "server bill. If this was a refused first round, that round and any later " +
                        "saved actions that depended on its new bill will be removed.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        discardAction = null
                        vm.discardBlockedAction(action.actionId)
                    },
                    enabled = state.online,
                ) { Text(if (state.online) "Refresh and discard" else "Reconnect first") }
            },
            dismissButton = {
                TextButton(onClick = { discardAction = null }) { Text("Keep saved action") }
            },
        )
    }
}

@Composable
private fun TablesRefreshErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(Brand.DangerMuted)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Tables may be out of date", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, color = Brand.Foreground, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun BlockedCafeActionsPanel(
    actions: List<BlockedCafeAction>,
    online: Boolean,
    canWrite: Boolean,
    onRetry: (String) -> Unit,
    onDiscard: (BlockedCafeAction) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Brand.DangerMuted)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Table actions needing attention (${actions.size})",
            color = Brand.Foreground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "The confirmed bill is preserved. Refresh, review what changed, then retry the " +
                "original saved action — never recreate the order.",
            color = Brand.Foreground,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(actions, key = BlockedCafeAction::actionId) { action ->
                Column(
                    Modifier.width(360.dp).clip(Radius.shapeMd)
                        .background(Brand.SurfaceRaised).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Table ${action.tableCode} · ${action.actionLabel}",
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            formatRejectedTableOrderTime(action.createdAtMillis),
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        if (action.conflict) {
                            "Another device changed this bill. ${action.message}"
                        } else {
                            "Why it stopped: ${action.message}"
                        },
                        color = Brand.Danger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { onRetry(action.actionId) },
                        enabled = canWrite && online,
                    ) {
                        Text(
                            if (!online) "Reconnect to review" else "Refresh, review & retry",
                        )
                    }
                    TextButton(
                        onClick = { onDiscard(action) },
                        enabled = canWrite && online,
                    ) {
                        Text("Discard after verification", color = Brand.Danger)
                    }
                }
            }
        }
    }
}

internal fun formatRejectedTableOrderTime(
    createdAtMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern("d MMM, HH:mm", locale)
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(createdAtMillis))

@Composable
private fun TableTile(table: CafeTable, enabled: Boolean, onClick: () -> Unit) {
    // Colour carries the status so a glance across a busy room is enough;
    // reading a word on every tile is not.
    val status = table.status.lowercase()
    val occupied = status != "available"
    val statusLabel = when (status) {
        "available" -> "Free"
        "sending to pos" -> "Sending to POS…"
        "at pos" -> "At POS · read only"
        "round syncing" -> "Round syncing…"
        "open bill" -> "Open bill"
        "needs attention" -> "Needs attention"
        "occupied" -> "Open bill"
        "reserved" -> "Reserved"
        "cleaning" -> "Cleaning"
        "merged" -> "Merged"
        else -> table.status
    }
    val statusColour = when (status) {
        "available" -> Brand.Good
        "sending to pos", "round syncing" -> Brand.Gold
        "open bill", "at pos" -> Brand.ForegroundMuted
        else -> Brand.Danger
    }
    Column(
        Modifier
            .clip(Radius.shapeLg)
            .background(if (occupied) Brand.SurfaceRaised else Brand.Surface)
            .clickable(enabled = enabled, onClick = onClick)
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
            statusLabel,
            color = statusColour,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OrderBuilder(
    table: CafeTable,
    state: TablesUiState,
    canWrite: Boolean,
    onAdd: (MenuItemEntity) -> Unit,
    onIncrement: (String) -> Unit,
    onRemove: (String) -> Unit,
    onNote: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onSaveRound: () -> Unit,
) {
    var confirmDiscard by remember(table.id) { mutableStateOf(false) }
    val requestDismiss = {
        if (state.cart.isEmpty()) onDismiss() else confirmDiscard = true
    }

    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = requestDismiss,
        title = {
            Text(if (state.selectedBill == null) "Table ${table.code} · First round" else "Table ${table.code} · New round")
        },
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
                                Modifier.clip(Radius.shapeSm)
                                    .background(Brand.SurfaceRaised)
                                    .clickable(enabled = canWrite) { onAdd(item) }
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
                        items(state.cart, key = { it.clientLineId }) { line ->
                            Column(
                                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                                    .background(Brand.SurfaceRaised).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
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
                                Qty("−", enabled = canWrite) { onRemove(line.clientLineId) }
                                Text(
                                    "${line.qty}",
                                    Modifier.padding(horizontal = 8.dp),
                                    color = Brand.Foreground,
                                    fontWeight = FontWeight.Bold,
                                )
                                Qty("+", enabled = canWrite) { onIncrement(line.clientLineId) }
                                }
                                OutlinedTextField(
                                    value = line.note,
                                    onValueChange = { onNote(line.clientLineId, it) },
                                    enabled = canWrite,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Special request (optional)") },
                                    placeholder = { Text("e.g. no ice, less spicy") },
                                    singleLine = true,
                                )
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
                        "This round is saved first, then released to Kitchen. Billing stays open until you choose Send to POS.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.GoldMuted,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSaveRound, enabled = canWrite && state.cart.isNotEmpty() && !state.busy) {
                Text(if (state.busy) "Saving…" else "Send round to Kitchen")
            }
        },
        dismissButton = { OutlinedButton(onClick = requestDismiss) { Text("Cancel") } },
    )

    if (confirmDiscard) {
        AlertDialog(
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this unsent round?") },
            text = {
                Text(
                    "The ${state.cart.sumOf { it.qty }} item(s) in Table ${table.code} " +
                        "have not been released to Kitchen.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDiscard = false
                        onDismiss()
                    },
                ) { Text("Discard round") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun BillDialog(
    table: CafeTable,
    bill: CafeBillProjection,
    busy: Boolean,
    access: TablesAccess,
    onAddRound: () -> Unit,
    onCancelLine: (CafeBillLineProjection, String) -> Unit,
    onCancelBill: (String) -> Unit,
    onSendToPos: () -> Unit,
    onDismiss: () -> Unit,
) {
    val billIdentity = bill.localBillId ?: bill.serverOrderId ?: "unresolved:${bill.tableId}"
    var cancellingLineKey by rememberSaveable(billIdentity) {
        mutableStateOf<String?>(null)
    }
    var cancellationReasonId by rememberSaveable(billIdentity) {
        mutableStateOf<String?>(null)
    }
    var customCancellationReason by rememberSaveable(billIdentity) {
        mutableStateOf("")
    }
    val cancellationReason = resolvedVoidReason(cancellationReasonId, customCancellationReason)
    val cancellingLine = cancellingLineKey?.let { key ->
        bill.lines.firstOrNull { it.stableKey == key }
    }
    val activeLineCount = bill.lines.count { !it.voided }
    val cancellingWholeBill = cancellingLine != null && activeLineCount == 1
    val held = bill.heldOrSending

    fun leaveCancellation() {
        cancellingLineKey = null
        cancellationReasonId = null
        customCancellationReason = ""
    }

    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = {
            if (!busy) {
                if (cancellingLine != null) leaveCancellation() else onDismiss()
            }
        },
        title = {
            if (cancellingWholeBill) {
                Text("Void the whole Table ${table.code} bill?")
            } else if (cancellingLine != null) {
                Text("Cancel ${cancellingLine.name}?")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Table ${table.code} · Current bill")
                    Text(
                        when {
                            bill.status == "held" -> "At POS · items locked"
                            bill.status == "sending_to_pos" -> "Sending to POS · waiting for confirmation"
                            bill.blockedActionId != null -> "Needs review before more changes"
                            bill.pendingActionCount > 0 -> "${bill.pendingActionCount} saved action(s) syncing"
                            else -> "Open · take another round or send for billing"
                        },
                        color = if (bill.blockedActionId != null) Brand.Danger else Brand.GoldMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        text = {
            if (cancellingLine != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (cancellingWholeBill) {
                            "This is the bill's last active item. Confirming will void the entire bill, " +
                                "not only this line. The reason remains in the audit history."
                        } else {
                            "The item remains visible on KDS until Kitchen acknowledges the cancellation."
                        },
                        color = Brand.ForegroundMuted,
                    )
                    VoidReasonInput(
                        selectedId = cancellationReasonId,
                        customReason = customCancellationReason,
                        onPresetSelected = { cancellationReasonId = it },
                        onCustomReasonChange = { customCancellationReason = it },
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (held) {
                        Text(
                            "This snapshot is read only. The cashier must select this bill in POS; " +
                                "do not create a replacement order.",
                            color = Brand.ForegroundMuted,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(bill.lines, key = CafeBillLineProjection::stableKey) { line ->
                            Column(
                                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                                    .background(if (line.voided) Brand.DangerMuted else Brand.SurfaceRaised)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${line.qty.toQtyLabel()} × ${line.name}",
                                            color = Brand.Foreground,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        line.note?.takeIf(String::isNotBlank)?.let { note ->
                                            Text("Request: $note", color = Brand.GoldMuted)
                                        }
                                        Text(
                                            buildString {
                                                line.roundNo?.let { append("Round $it · ") }
                                                append(
                                                    when {
                                                        line.locallyPending -> "Waiting for sync"
                                                        line.voided -> "Cancelled"
                                                        else -> line.kitchenStatus.replaceFirstChar(Char::uppercase)
                                                    },
                                                )
                                            },
                                            color = Brand.ForegroundMuted,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    if (
                                        access.canCancelItems && bill.editable && !line.voided &&
                                        line.kitchenStatus != "served"
                                    ) {
                                        TextButton(
                                            onClick = {
                                                cancellationReasonId = null
                                                customCancellationReason = ""
                                                cancellingLineKey = line.stableKey
                                            },
                                        ) {
                                            Text(
                                                if (activeLineCount == 1) "Void whole bill" else "Cancel item",
                                                color = Brand.Danger,
                                            )
                                        }
                                    }
                                }
                                if (line.voided) {
                                    Text(
                                        "Reason: ${line.voidReason ?: "Not recorded"}",
                                        color = Brand.Danger,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (line.kitchenCancellationPending) {
                                        Text(
                                            "Waiting for Kitchen acknowledgement",
                                            color = Brand.GoldMuted,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (bill.amountPending) "Estimated after sync" else "Current total",
                            color = Brand.ForegroundMuted,
                        )
                        Text(bill.totalMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                    }
                    if (bill.amountPending) {
                        Text(
                            bill.confirmedTotalMinor?.let {
                                "Last confirmed total: ${it.asRupees()}. Final pricing and tax are confirmed by the server."
                            } ?: "Final pricing and tax are confirmed by the server after this first round syncs.",
                            color = Brand.GoldMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (cancellingLine != null) {
                Button(
                    onClick = {
                        if (cancellingWholeBill) {
                            onCancelBill(cancellationReason)
                        } else {
                            onCancelLine(cancellingLine, cancellationReason)
                        }
                        leaveCancellation()
                    },
                    enabled = access.canCancelItems && bill.editable && !cancellingLine.voided &&
                        cancellingLine.kitchenStatus != "served" && cancellationReason.isNotBlank() && !busy,
                ) { Text(if (cancellingWholeBill) "Void whole bill" else "Cancel item") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!held && bill.editable) {
                        OutlinedButton(
                            onClick = onAddRound,
                            enabled = access.canCreateOrders && !busy,
                        ) { Text("Add another round") }
                        Button(
                            onClick = onSendToPos,
                            enabled = access.canSendToPos && !busy,
                        ) { Text(if (busy) "Saving…" else "Send to POS") }
                    }
                }
            }
        },
        dismissButton = {
            if (cancellingLine != null) {
                TextButton(onClick = ::leaveCancellation, enabled = !busy) {
                    Text(if (cancellingWholeBill) "Keep bill" else "Keep item")
                }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
            }
        },
    )
}

private fun Double.toQtyLabel(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

@Composable
private fun Qty(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(Brand.SurfaceRaised).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Brand.Gold, fontWeight = FontWeight.Bold) }
}
