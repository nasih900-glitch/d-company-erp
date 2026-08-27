package cloud.dcompany.erp.ui.screens.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SyncProblem
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
import cloud.dcompany.erp.core.auth.EventsAccess
import cloud.dcompany.erp.core.money.minorToRupeesInput
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.PricingUnlockDialog
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.DecimalField
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.FormDialog
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PickerField
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ConfirmDialog
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

/**
 * Events — a hybrid of Menu's shape (CRUD header entity behind a pricing
 * lock, online-only) and Inventory's GRN shape (insert-only ticket-sale
 * rows under a parent, real offline outbox). See EventsViewModel's class
 * doc for the full reasoning.
 */
@Composable
fun EventsScreen(access: EventsAccess = EventsAccess()) {
    val vm: EventsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }
    EventsContent(state, vm, access)
}

@Composable
private fun EventsContent(state: EventsUiState, vm: EventsViewModel, access: EventsAccess) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Header(state, vm, access)

        if (!access.canManageEvents) {
            ViewOnlyNotice(
                if (access.canCheckInTickets) {
                    "Event scheduling is view only — you can still check in existing tickets."
                } else {
                    "Events are view only — ask a manager if an event action is required."
                },
            )
        } else if (!access.canCheckInTickets) {
            ViewOnlyNotice("Ticket check-in is view only for this account.")
        }

        if (state.notice != null) {
            NoticeBanner(state.notice, vm::dismissNotice)
        }
        if (state.formError != null && state.dialog == null) {
            ActionErrorBanner(state.formError, vm::dismissFormError)
        }
        if (state.couldNotLoad != null && state.events.isEmpty()) {
            ErrorBanner(state.couldNotLoad, vm::retry)
        }
        if (state.pendingTicketSales.isNotEmpty() || state.pendingCheckIns.isNotEmpty()) {
            PendingEventChangesPanel(state, vm, access)
        }

        if (state.everSynced || state.events.isNotEmpty()) {
            EventsSummary(state)
        }

        when {
            !state.everSynced && state.events.isEmpty() && state.couldNotLoad == null ->
                SectionCard(
                    modifier = Modifier.weight(1f),
                    title = "Event schedule",
                    subtitle = "Screenings, tournaments and ticket availability",
                    icon = Icons.Default.Celebration,
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            CircularProgressIndicator(color = Brand.Information)
                            Text("Loading events…", color = Brand.ForegroundMuted)
                        }
                    }
                }

            state.events.isEmpty() -> EventsEmptyPanel(state, vm, access, Modifier.weight(1f))

            else -> EventsList(state, vm, access, Modifier.weight(1f))
        }

        when (val dialog = state.dialog) {
            is EventsDialog.EventForm -> if (access.canManageEvents) {
                EventFormDialog(dialog.editing, state, vm)
            }
            is EventsDialog.SellTickets -> if (access.canCheckInTickets) {
                SellTicketsDialog(dialog.event, state, vm)
            }
            is EventsDialog.Tickets -> TicketsDialog(dialog.event, state, vm, access.canCheckInTickets)
            is EventsDialog.ConfirmDelete -> if (access.canManageEvents) ConfirmDialog(
                title = "Delete ${dialog.event.name}?",
                body = if (dialog.event.sold > 0) {
                    "This event has sold tickets — cancel it instead (Mark cancelled) rather than deleting."
                } else {
                    "This cannot be undone."
                },
                confirmLabel = "Delete",
                busy = state.busy,
                error = state.formError,
                onConfirm = { vm.deleteEvent(dialog.event) },
                onDismiss = vm::closeDialog,
            )
            is EventsDialog.ConfirmCancel -> if (access.canManageEvents) ConfirmDialog(
                title = "Cancel ${dialog.event.name}?",
                body = buildString {
                    append("The event will stop accepting tickets and remain in event history. ")
                    if (dialog.event.sold > 0) {
                        append("Its ${dialog.event.sold} sold ticket")
                        if (dialog.event.sold != 1) append("s")
                        append(" will be preserved for check-in and reconciliation. ")
                    }
                    append("This does not issue refunds automatically.")
                },
                confirmLabel = "Cancel event",
                busy = state.busy,
                error = state.formError,
                onConfirm = { vm.setEventStatus(dialog.event, "cancelled") },
                onDismiss = vm::closeDialog,
            )
            null -> {}
        }
        if (state.showPricingUnlock && access.canManageEvents) {
            PricingUnlockDialog(onDismiss = vm::dismissPricingUnlock, onUnlocked = vm::pricingUnlocked)
        }
    }
}

@Composable
private fun Header(state: EventsUiState, vm: EventsViewModel, access: EventsAccess) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ActionBar(
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Event controls",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.events.isEmpty()) {
                            "Refresh the schedule or create the first event."
                        } else {
                            "${state.events.size} saved · ${state.upcoming.size} upcoming"
                        },
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailing = {
                ErpButton(
                    text = if (state.syncing) "Refreshing…" else "Refresh",
                    onClick = vm::retry,
                    intent = ActionIntent.Secondary,
                    enabled = !state.syncing,
                    busy = state.syncing,
                    leadingIcon = Icons.Default.Refresh,
                )
                ErpButton(
                    text = "New event",
                    onClick = vm::openCreateForm,
                    enabled = access.canManageEvents,
                    leadingIcon = Icons.Default.Add,
                )
            },
        )
        if (state.syncing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Information,
                trackColor = Brand.Surface,
            )
        }
    }
}

@Composable
private fun EventsSummary(state: EventsUiState) {
    val soldTickets = state.events.sumOf { it.sold }
    val pendingChanges = state.pendingTicketSales.size + state.pendingCheckIns.size
    AdaptiveStatGrid(count = 4) { index, modifier ->
        when (index) {
            0 -> CompactStatCard(
                label = "Upcoming",
                value = state.upcoming.size.toString(),
                detail = "Scheduled or live",
                icon = Icons.Default.EventAvailable,
                tone = UiTone.Information,
                modifier = modifier,
            )
            1 -> CompactStatCard(
                label = "Live now",
                value = state.events.count { it.status == "live" }.toString(),
                detail = "Accepting check-ins",
                icon = Icons.Default.Schedule,
                tone = UiTone.Success,
                modifier = modifier,
            )
            2 -> CompactStatCard(
                label = "Tickets sold",
                value = soldTickets.toString(),
                detail = "Across saved events",
                icon = Icons.Default.ConfirmationNumber,
                tone = UiTone.Information,
                modifier = modifier,
            )
            else -> CompactStatCard(
                label = "Pending sync",
                value = pendingChanges.toString(),
                detail = "Sales and check-ins",
                icon = Icons.Default.SyncProblem,
                tone = if (pendingChanges > 0) UiTone.Warning else UiTone.Neutral,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun EventsEmptyPanel(
    state: EventsUiState,
    vm: EventsViewModel,
    access: EventsAccess,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "Event schedule",
        subtitle = "Screenings, tournaments and ticket availability",
        icon = Icons.Default.Celebration,
    ) {
        DesignedEmptyState(
            title = if (state.couldNotLoad != null) "No saved events available" else "No events yet",
            body = if (state.couldNotLoad != null) {
                "This tablet has no cached events and the latest refresh could not complete. Check the connection and retry."
            } else {
                "Create a football or cricket screening, movie night, or esports tournament when the schedule is ready."
            },
            icon = Icons.Default.Celebration,
            primaryLabel = if (state.couldNotLoad != null) "Retry" else "New event".takeIf { access.canManageEvents },
            onPrimary = if (state.couldNotLoad != null) vm::retry else (vm::openCreateForm).takeIf { access.canManageEvents },
            secondaryLabel = "Refresh".takeIf { state.couldNotLoad == null },
            onSecondary = (vm::retry).takeIf { state.couldNotLoad == null },
            modifier = Modifier.weight(1f),
        )
    }
}

// ============================================================================
// LIST
// ============================================================================
@Composable
private fun EventsList(
    state: EventsUiState,
    vm: EventsViewModel,
    access: EventsAccess,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "Event schedule",
        subtitle = "${state.upcoming.size} upcoming · ${state.past.size} past or cancelled",
        icon = Icons.Default.Celebration,
        contentPadding = PaddingValues(0.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.upcoming.isNotEmpty()) {
                item { SectionTitle("Upcoming") }
                items(state.upcoming, key = { it.id }) { event -> EventCard(event, vm, access) }
            }
            if (state.past.isNotEmpty()) {
                item { SectionTitle("Past / cancelled") }
                items(state.past, key = { it.id }) { event -> EventCard(event, vm, access) }
            }
        }
    }
}

@Composable
private fun EventCard(event: Event, vm: EventsViewModel, access: EventsAccess) {
    val soldFraction = if (event.capacity > 0) event.sold.toFloat() / event.capacity else 0f
    val progressColor = when {
        soldFraction >= 0.9f -> Brand.Danger
        soldFraction >= 0.6f -> Brand.Warning
        else -> Brand.Good
    }
    SectionCard(elevated = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = Brand.Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Chip(eventTypeLabel(event.eventType), Brand.SurfaceRaised)
                    Spacer(Modifier.width(6.dp))
                    OperationalStatusBadge(
                        label = eventStatusLabel(event.status),
                        tone = eventStatusTone(event.status),
                    )
                }
                Text(
                    "${event.startsAt.asDay()} · ${event.screen}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Text(
                event.baseTicketPriceMinor.asRupees(),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.Foreground,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${event.sold} / ${event.capacity} sold · ${event.remaining} remaining",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { soldFraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = progressColor,
            trackColor = Brand.SurfaceRaised,
        )
        Spacer(Modifier.height(10.dp))
        EventActionBar(event, vm, access)
        if (event.status in setOf("scheduled", "live") && event.remaining > 0) {
            Text(
                "New tickets require POS payment, invoice, shift, and GST reconciliation.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
    }
}

@Composable
private fun EventActionBar(event: Event, vm: EventsViewModel, access: EventsAccess) {
    ActionBar(
        leading = {
            ErpButton(
                text = "Tickets (${event.sold})",
                onClick = { vm.openTickets(event) },
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.ConfirmationNumber,
            )
        },
        trailing = {
            if (event.status == "scheduled" && access.canManageEvents) {
                ErpButton(
                    text = "Mark live",
                    onClick = { vm.setEventStatus(event, "live") },
                    intent = ActionIntent.Success,
                )
            }
            if (access.canManageEvents) {
                EventManagementMenu(event, vm)
            }
        },
    )
}

@Composable
private fun EventManagementMenu(event: Event, vm: EventsViewModel) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    Box {
        ErpButton(
            text = "Manage",
            onClick = { expanded = true },
            intent = ActionIntent.Secondary,
            leadingIcon = Icons.Default.MoreVert,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = {
                    expanded = false
                    vm.openEditForm(event)
                },
            )
            if (event.status in setOf("scheduled", "live")) {
                DropdownMenuItem(
                    text = { Text("Cancel event", color = Brand.Danger) },
                    onClick = {
                        expanded = false
                        vm.openConfirmCancel(event)
                    },
                )
            }
            if (event.sold == 0) {
                DropdownMenuItem(
                    text = { Text("Delete event", color = Brand.Danger) },
                    onClick = {
                        expanded = false
                        vm.openConfirmDelete(event)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.DangerMuted)
            .border(1.dp, Brand.Danger, Radius.shapeMd).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Event action not completed", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
        }
        ErpButton("Dismiss", onDismiss, intent = ActionIntent.Quiet)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Brand.Foreground,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun eventStatusTone(status: String): UiTone = when (status) {
    "live" -> UiTone.Success
    "cancelled" -> UiTone.Danger
    "ended" -> UiTone.Neutral
    else -> UiTone.Information
}

// ============================================================================
// PENDING CHANGES
// ============================================================================
@Composable
private fun PendingEventChangesPanel(
    state: EventsUiState,
    vm: EventsViewModel,
    access: EventsAccess,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeMd)
            .background(Brand.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pending event changes", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        state.pendingTicketSales.forEach { row ->
            EventPendingRow(
                text = "${row.qty} ticket(s) for ${row.eventName} — ${row.customerName}",
                rejected = row.rejected,
                error = row.error,
                actionLabel = if (row.rejected) "Remove" else null,
                actionEnabled = access.canCheckInTickets,
                onAction = { vm.discardRejectedTicketSale(row.localId) },
            )
        }
        state.pendingCheckIns.forEach { row ->
            EventPendingRow(
                text = "Check-in — ${row.eventName}",
                rejected = row.rejected,
                error = row.error,
                actionLabel = if (row.rejected) "Retry" else null,
                actionEnabled = access.canCheckInTickets,
                onAction = { vm.retryCheckIn(row.localId) },
            )
        }
    }
}

@Composable
private fun EventPendingRow(
    text: String,
    rejected: Boolean,
    error: String?,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null) {
                ErpButton(
                    text = actionLabel,
                    onClick = onAction,
                    enabled = actionEnabled,
                    intent = if (actionLabel == "Remove") {
                        ActionIntent.Destructive
                    } else {
                        ActionIntent.Secondary
                    },
                )
            }
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
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        ErpButton("Dismiss", onDismiss, intent = ActionIntent.Quiet)
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.Surface)
            .border(1.dp, Brand.Danger, Radius.shapeMd).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Couldn't refresh", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
        }
        ErpButton("Retry", onRetry)
    }
}

// ============================================================================
// CREATE / EDIT DIALOG
// ============================================================================
@Composable
private fun EventFormDialog(editing: Event?, state: EventsUiState, vm: EventsViewModel) {
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var description by remember { mutableStateOf(editing?.description.orEmpty()) }
    var eventType by remember { mutableStateOf(editing?.eventType ?: "movie") }
    var screen by remember { mutableStateOf(editing?.screen ?: "Main Screen") }
    var startsAtText by remember { mutableStateOf(editing?.startsAt?.take(16) ?: "") }
    var capacityText by remember { mutableStateOf(editing?.capacity?.toString() ?: "") }
    var priceRupees by remember {
        mutableStateOf(editing?.let { minorToRupeesInput(it.baseTicketPriceMinor) } ?: "")
    }
    var posterUrl by remember { mutableStateOf(editing?.posterUrl.orEmpty()) }
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id) }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (editing == null) "New event" else "Edit ${editing.name}",
        confirmLabel = if (editing == null) "Create event" else "Save",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val capacity = capacityText.toIntOrNull()
            val priceMinor = parseRupeesToMinor(priceRupees)
            val startsAtIso = startsAtText.toIsoOrNull()
            when {
                name.isBlank() -> localError = "Enter a name for this event."
                startsAtIso == null -> localError = "Pick a valid start date/time."
                capacity == null || capacity <= 0 -> localError = "Capacity must be a whole number greater than 0."
                priceMinor == null ->
                    localError = "Ticket price must be rupees with no more than 2 decimal places."
                else -> {
                    localError = null
                    val priceChanged = editing == null || priceMinor != editing.baseTicketPriceMinor
                    vm.saveEvent(
                        editingId = editing?.id, name = name, description = description,
                        eventType = eventType, screen = screen, startsAt = startsAtIso, endsAt = null,
                        capacity = capacity, priceMinor = priceMinor, posterUrl = posterUrl,
                        branchId = branchId, priceChanged = priceChanged,
                    )
                }
            }
        },
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        PickerField(
            "Type", eventTypeLabel(eventType),
            listOf(
                "football" to "Football", "cricket" to "Cricket", "movie" to "Movie",
                "esports" to "Esports", "other" to "Other",
            ),
        ) { eventType = it }
        if (editing == null) {
            PickerField(
                "Branch",
                state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
                state.branches.map { it.id to it.name },
            ) { branchId = it }
        }
        OutlinedTextField(
            value = screen, onValueChange = { screen = it }, label = { Text("Screen / area") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = startsAtText, onValueChange = { startsAtText = it },
            label = { Text("Starts at (YYYY-MM-DDTHH:MM)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = capacityText, onValueChange = { capacityText = it.filter(Char::isDigit) },
            label = { Text("Capacity") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalField(priceRupees, { priceRupees = it }, "Ticket price (₹)")
        if (editing != null) {
            Text(
                "Changing the price needs a fresh password unlock, same as Menu.",
                style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
            )
        }
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = posterUrl, onValueChange = { posterUrl = it },
            label = { Text("Poster image URL (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// SELL TICKETS DIALOG
// ============================================================================
@Composable
private fun SellTicketsDialog(event: Event, state: EventsUiState, vm: EventsViewModel) {
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var seat by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "Sell tickets — ${event.name}",
        confirmLabel = "Queue sale",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val qty = qtyText.toIntOrNull()
            when {
                customerName.isBlank() -> localError = "Enter the customer's name."
                qty == null || qty <= 0 -> localError = "Quantity must be a whole number greater than 0."
                qty > event.remaining -> localError = "Only ${event.remaining} seat(s) remaining."
                else -> {
                    localError = null
                    vm.sellTickets(event.id, customerName, customerPhone, seat, qty, note)
                }
            }
        },
    ) {
        Text(
            "${event.remaining} of ${event.capacity} remaining · ${event.baseTicketPriceMinor.asRupees()} each",
            style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
        )
        OutlinedTextField(
            value = customerName, onValueChange = { customerName = it },
            label = { Text("Customer name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = customerPhone, onValueChange = { customerPhone = it },
            label = { Text("Phone (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = seat, onValueChange = { seat = it },
            label = { Text("Seat (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = qtyText, onValueChange = { qtyText = it.filter(Char::isDigit) },
            label = { Text("Quantity") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// TICKETS LIST / CHECK-IN DIALOG
// ============================================================================
@Composable
private fun TicketsDialog(
    event: Event,
    state: EventsUiState,
    vm: EventsViewModel,
    canCheckIn: Boolean,
) {
    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = vm::closeDialog,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Tickets — ${event.name}") },
        text = {
            if (state.tickets.isEmpty()) {
                Text("No tickets sold yet.", color = Brand.ForegroundMuted)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tickets, key = { it.id }) { ticket ->
                        TicketRow(event, ticket, state, vm, canCheckIn)
                    }
                }
            }
        },
        confirmButton = {
            ErpButton("Close", vm::closeDialog, intent = ActionIntent.Secondary)
        },
    )
}

@Composable
private fun TicketRow(
    event: Event,
    ticket: EventTicket,
    state: EventsUiState,
    vm: EventsViewModel,
    canCheckIn: Boolean,
) {
    val pendingCheckIn = state.pendingCheckIns.any { it.ticketId == ticket.id }
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(ticket.ticketNo, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
            Text(
                "${ticket.customerName ?: "—"}${ticket.seat?.let { " · Seat $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
            )
            Text(
                if (pendingCheckIn) "Checking in — not synced yet" else ticketStatusLabel(ticket.status),
                style = MaterialTheme.typography.labelSmall,
                color = if (ticket.status == "checked_in" || pendingCheckIn) Brand.Good else Brand.ForegroundMuted,
            )
        }
        Text(ticket.pricePaidMinor.asRupees(), style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
        Spacer(Modifier.width(8.dp))
        if (ticket.status == "sold" && !pendingCheckIn) {
            ErpButton(
                text = "Check in",
                onClick = { vm.checkIn(event.id, ticket) },
                enabled = canCheckIn,
                intent = ActionIntent.Success,
            )
        }
    }
}

// ============================================================================
// SHARED PIECES — local copies, third occurrence of this shape (Inventory,
// Finance, now Events); a future cleanup could promote these to a shared
// ui/components/FormPrimitives.kt, per Inventory/Finance's own class docs.
// ============================================================================
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground, modifier = Modifier.padding(bottom = 2.dp))
}

/** "2026-08-22T20:00" (from an HTML-datetime-local-style input) -> a full
 * ISO instant string the backend's `datetime` field will parse. Assumes the
 * device's local timezone, same as the OS's own date/time. */
private fun String.toIsoOrNull(): String? {
    if (isBlank()) return null
    return runCatching {
        val local = java.time.LocalDateTime.parse(this)
        local.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
    }.getOrNull()
}

private val DAY_TIME: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", java.util.Locale.UK)

private fun String.asDay(): String = runCatching {
    java.time.OffsetDateTime.parse(this)
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(DAY_TIME)
}.getOrDefault(this)
