package cloud.dcompany.erp.ui.screens.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LinearProgressIndicator
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
import cloud.dcompany.erp.ui.components.PricingUnlockDialog
import cloud.dcompany.erp.ui.theme.Brand
import kotlin.math.roundToLong

/**
 * Events — a hybrid of Menu's shape (CRUD header entity behind a pricing
 * lock, online-only) and Inventory's GRN shape (insert-only ticket-sale
 * rows under a parent, real offline outbox). See EventsViewModel's class
 * doc for the full reasoning.
 */
@Composable
fun EventsScreen() {
    val vm: EventsViewModel = viewModel()
    val state by vm.state.collectAsState()
    EventsContent(state, vm)
}

@Composable
private fun EventsContent(state: EventsUiState, vm: EventsViewModel) {
    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Header(state, vm)

        if (state.notice != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NoticeBanner(state.notice, vm::dismissNotice)
            }
        }
        if (state.couldNotLoad != null && state.events.isEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ErrorBanner(state.couldNotLoad, vm::retry)
            }
        }
        if (state.pendingTicketSales.isNotEmpty() || state.pendingCheckIns.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                PendingEventChangesPanel(state, vm)
            }
        }

        when {
            !state.everSynced && state.events.isEmpty() && state.couldNotLoad == null ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Brand.Gold)
                        Text("Loading events…", color = Brand.ForegroundMuted)
                    }
                }

            state.events.isEmpty() -> EmptyBlock(
                title = "No events yet",
                body = "Football/cricket screenings, movie nights, esports tournaments — " +
                    "create one to start selling tickets.",
            )

            else -> EventsList(state, vm)
        }

        when (val dialog = state.dialog) {
            is EventsDialog.EventForm -> EventFormDialog(dialog.editing, state, vm)
            is EventsDialog.SellTickets -> SellTicketsDialog(dialog.event, state, vm)
            is EventsDialog.Tickets -> TicketsDialog(dialog.event, state, vm)
            is EventsDialog.ConfirmDelete -> ConfirmDialog(
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
            null -> {}
        }
        if (state.showPricingUnlock) {
            PricingUnlockDialog(onDismiss = vm::dismissPricingUnlock, onUnlocked = vm::pricingUnlocked)
        }
    }
}

@Composable
private fun Header(state: EventsUiState, vm: EventsViewModel) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Events", style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground)
                Text(
                    "Screenings · ticket sales · check-in",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::retry, enabled = !state.syncing) {
                    Text(if (state.syncing) "Refreshing…" else "Refresh")
                }
                Button(onClick = vm::openCreateForm) { Text("New event") }
            }
        }
        if (state.syncing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Gold,
                trackColor = Brand.Surface,
            )
        }
    }
}

// ============================================================================
// LIST
// ============================================================================
@Composable
private fun EventsList(state: EventsUiState, vm: EventsViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.upcoming.isNotEmpty()) {
            item { SectionTitle("Upcoming") }
            items(state.upcoming, key = { it.id }) { event -> EventCard(event, vm) }
        }
        if (state.past.isNotEmpty()) {
            item { SectionTitle("Past / cancelled") }
            items(state.past, key = { it.id }) { event -> EventCard(event, vm) }
        }
    }
}

@Composable
private fun EventCard(event: Event, vm: EventsViewModel) {
    val soldFraction = if (event.capacity > 0) event.sold.toFloat() / event.capacity else 0f
    val progressColor = when {
        soldFraction >= 0.9f -> Brand.Danger
        soldFraction >= 0.6f -> Brand.Gold
        else -> Brand.Good
    }
    Panel {
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
                    Chip(eventStatusLabel(event.status), statusColor(event.status))
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
                color = Brand.Gold,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (event.status in setOf("scheduled", "live") && event.remaining > 0) {
                Button(onClick = { vm.openSellTickets(event) }) { Text("Sell tickets") }
            }
            OutlinedButton(onClick = { vm.openTickets(event) }) { Text("Tickets (${event.sold})") }
            TextButton(onClick = { vm.openEditForm(event) }) { Text("Edit") }
            if (event.status == "scheduled") {
                TextButton(onClick = { vm.setEventStatus(event, "live") }) { Text("Mark live") }
            }
            if (event.status in setOf("scheduled", "live")) {
                TextButton(onClick = { vm.setEventStatus(event, "cancelled") }) {
                    Text("Cancel", color = Brand.Danger)
                }
            }
            if (event.sold == 0) {
                TextButton(onClick = { vm.openConfirmDelete(event) }) {
                    Text("Delete", color = Brand.Danger)
                }
            }
        }
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

private fun statusColor(status: String): Color = when (status) {
    "live" -> Brand.Good
    "cancelled" -> Brand.Danger
    else -> Brand.SurfaceRaised
}

// ============================================================================
// PENDING CHANGES
// ============================================================================
@Composable
private fun PendingEventChangesPanel(state: EventsUiState, vm: EventsViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brand.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pending event changes", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        state.pendingTicketSales.forEach { row ->
            EventPendingRow(
                text = "${row.qty} ticket(s) for ${row.eventName} — ${row.customerName}",
                rejected = row.rejected,
                error = row.error,
                onRetry = { vm.retryTicketSale(row.localId) },
            )
        }
        state.pendingCheckIns.forEach { row ->
            EventPendingRow(
                text = "Check-in — ${row.eventName}",
                rejected = row.rejected,
                error = row.error,
                onRetry = { vm.retryCheckIn(row.localId) },
            )
        }
    }
}

@Composable
private fun EventPendingRow(text: String, rejected: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceRaised).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (rejected) TextButton(onClick = onRetry) { Text("Retry") }
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brand.SurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Surface)
            .border(1.dp, Brand.Danger, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Couldn't refresh", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
        }
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyBlock(title: String, body: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            modifier = Modifier.width(420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(body, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
        }
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
        mutableStateOf(editing?.let { (it.baseTicketPriceMinor / 100.0).toPlainString() } ?: "")
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
            val priceMinor = priceRupees.toRupeesMinor()
            val startsAtIso = startsAtText.toIsoOrNull()
            when {
                name.isBlank() -> localError = "Enter a name for this event."
                startsAtIso == null -> localError = "Pick a valid start date/time."
                capacity == null || capacity <= 0 -> localError = "Capacity must be a whole number greater than 0."
                priceMinor < 0 -> localError = "Ticket price cannot be negative."
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
private fun TicketsDialog(event: Event, state: EventsUiState, vm: EventsViewModel) {
    AlertDialog(
        onDismissRequest = vm::closeDialog,
        modifier = Modifier.width(560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Tickets — ${event.name}") },
        text = {
            if (state.tickets.isEmpty()) {
                Text("No tickets sold yet.", color = Brand.ForegroundMuted)
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.tickets.forEach { ticket -> TicketRow(event, ticket, state, vm) }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::closeDialog) { Text("Close") } },
    )
}

@Composable
private fun TicketRow(event: Event, ticket: EventTicket, state: EventsUiState, vm: EventsViewModel) {
    val pendingCheckIn = state.pendingCheckIns.any { it.ticketId == ticket.id }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceRaised).padding(10.dp),
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
            TextButton(onClick = { vm.checkIn(event.id, ticket) }) { Text("Check in") }
        }
    }
}

// ============================================================================
// SHARED PIECES — local copies, third occurrence of this shape (Inventory,
// Finance, now Events); a future cleanup could promote these to a shared
// ui/components/FormPrimitives.kt, per Inventory/Finance's own class docs.
// ============================================================================
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Surface).padding(14.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground, modifier = Modifier.padding(bottom = 2.dp))
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
                onClick = onConfirm, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text(confirmLabel, color = Brand.Foreground) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.width(480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
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

@Composable
private fun PickerField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
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
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("▾", color = Brand.Gold)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(id) })
                }
            }
        }
    }
}

@Composable
private fun DecimalField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(filterDecimal(it)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun filterDecimal(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> { dotSeen = true; sb.append(c) }
        }
    }
    return sb.toString()
}

/** Rupees typed by a human -> whole paise, rounded not truncated (the same
 * IEEE-754 edge case Menu/Inventory/Finance's own equivalents guard against). */
private fun String.toRupeesMinor(): Long = ((toDoubleOrNull() ?: 0.0) * 100).roundToLong()

private fun Double.toPlainString(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()

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
