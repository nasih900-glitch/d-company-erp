package cloud.dcompany.erp.ui.screens.reservations

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.ReservationsAccess
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.ConfirmDialog
import cloud.dcompany.erp.ui.components.DecimalField
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.Field
import cloud.dcompany.erp.ui.components.FormDialog
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PickerField
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ReservationsScreen(access: ReservationsAccess = ReservationsAccess()) {
    val vm: ReservationsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }
    ReservationsContent(state, access, vm)
}

@Composable
private fun ReservationsContent(
    state: ReservationsUiState,
    access: ReservationsAccess,
    vm: ReservationsViewModel,
) {
    Column(
        Modifier.fillMaxSize().background(Brand.Background).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ReservationControls(state, access, vm)

        if (!state.online) {
            OperationalBanner(
                title = "Reservations need a live connection",
                detail = "Bookings and status changes are not stored offline. Reconnect and refresh before acting.",
                tone = UiTone.Warning,
                icon = Icons.Default.WifiOff,
            )
        }
        state.notice?.let { notice ->
            OperationalBanner(
                title = "Reservation update",
                detail = notice,
                tone = if (notice == reservationOfflineMessage()) UiTone.Warning else UiTone.Information,
                icon = if (notice == reservationOfflineMessage()) Icons.Default.WifiOff else Icons.Default.EventAvailable,
                action = {
                    ErpButton(
                        text = "Dismiss",
                        onClick = vm::dismissNotice,
                        intent = ActionIntent.Quiet,
                        leadingIcon = Icons.Default.Close,
                    )
                },
            )
        }

        val tabs = buildList {
            if (access.canReadTableReservations) {
                add(TabOption("tables", "Tables", state.tableReservations.size))
            }
            if (access.canReadGamingBookings) {
                add(TabOption("gaming", "Gaming", state.gamingBookings.size))
            }
        }
        if (tabs.size > 1) {
            PremiumTabBar(
                options = tabs,
                selectedId = if (state.selectedTab == ReservationTab.TABLES) "tables" else "gaming",
                onSelect = {
                    vm.selectTab(if (it == "tables") ReservationTab.TABLES else ReservationTab.GAMING)
                },
            )
        }

        ReservationSummary(state, access)

        val currentError = when (state.selectedTab) {
            ReservationTab.TABLES -> state.tableLoadError
            ReservationTab.GAMING -> state.gamingLoadError
        }
        when {
            state.loading -> LoadingReservationsPanel(Modifier.weight(1f))
            currentError != null && currentRowsEmpty(state) -> ReservationErrorPanel(
                message = currentError,
                refreshing = state.refreshing,
                onRetry = vm::refresh,
                modifier = Modifier.weight(1f),
            )
            state.selectedTab == ReservationTab.TABLES -> TableReservationsPanel(
                state = state,
                canManage = access.canManageTableReservations,
                onStatus = vm::confirmTableStatus,
                onCreate = vm::openCreate,
                modifier = Modifier.weight(1f),
            )
            state.selectedTab == ReservationTab.GAMING -> GamingBookingsPanel(
                state = state,
                canManage = access.canManageGamingBookings,
                onStatus = vm::confirmGamingStatus,
                onCreate = vm::openCreate,
                modifier = Modifier.weight(1f),
            )
        }

        ReservationDialogs(state, access, vm)
    }
}

@Composable
private fun ReservationControls(
    state: ReservationsUiState,
    access: ReservationsAccess,
    vm: ReservationsViewModel,
) {
    val canCreate = when (state.selectedTab) {
        ReservationTab.TABLES -> access.canManageTableReservations
        ReservationTab.GAMING -> access.canManageGamingBookings
    }
    val kind = if (state.selectedTab == ReservationTab.TABLES) "table reservation" else "gaming booking"
    ActionBar(
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Upcoming reservations",
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Live table and gaming bookings for this shop",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailing = {
            ErpButton(
                text = "Refresh",
                onClick = vm::refresh,
                enabled = state.online,
                busy = state.refreshing,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.Refresh,
            )
            if (canCreate) {
                ErpButton(
                    text = "New $kind",
                    onClick = vm::openCreate,
                    enabled = state.online,
                    leadingIcon = Icons.Default.Add,
                )
            }
        },
    )
    if (!canCreate) {
        ViewOnlyNotice("Reservations are view only — ask a manager to create, seat, mark no-show or cancel a booking.")
    }
}

@Composable
private fun ReservationSummary(state: ReservationsUiState, access: ReservationsAccess) {
    val cards = buildList {
        if (access.canReadTableReservations) {
            add(
                ReservationMetric(
                    "Table bookings",
                    state.tableReservations.size.toString(),
                    "${state.heldTableCount} awaiting guests",
                    Icons.Default.TableRestaurant,
                    UiTone.Information,
                ),
            )
        }
        if (access.canReadGamingBookings) {
            add(
                ReservationMetric(
                    "Gaming bookings",
                    state.gamingBookings.size.toString(),
                    "${state.heldGamingCount} awaiting guests",
                    Icons.Default.SportsEsports,
                    UiTone.Brand,
                ),
            )
        }
        add(
            ReservationMetric(
                "Awaiting arrival",
                (state.heldTableCount + state.heldGamingCount).toString(),
                "Actionable bookings",
                Icons.Default.Groups,
                UiTone.Warning,
            ),
        )
    }
    AdaptiveStatGrid(count = cards.size) { index, modifier ->
        val metric = cards[index]
        CompactStatCard(
            label = metric.label,
            value = metric.value,
            detail = metric.detail,
            icon = metric.icon,
            tone = metric.tone,
            modifier = modifier,
        )
    }
}

private data class ReservationMetric(
    val label: String,
    val value: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tone: UiTone,
)

@Composable
private fun LoadingReservationsPanel(modifier: Modifier = Modifier) {
    SectionCard(
        modifier = modifier,
        title = "Live booking board",
        subtitle = "Loading the current shop's upcoming reservations",
        icon = Icons.Default.CalendarMonth,
        elevated = true,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(color = Brand.Information)
                Text("Loading reservations…", color = Brand.ForegroundMuted)
            }
        }
    }
}

@Composable
private fun ReservationErrorPanel(
    message: String,
    refreshing: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, elevated = true) {
        DesignedEmptyState(
            title = "Could not load reservations",
            body = message,
            icon = Icons.Default.Warning,
            tone = UiTone.Danger,
            primaryLabel = "Try again",
            onPrimary = onRetry,
            primaryEnabled = !refreshing,
            primaryBusy = refreshing,
            primaryIcon = Icons.Default.Refresh,
        )
    }
}

@Composable
private fun TableReservationsPanel(
    state: ReservationsUiState,
    canManage: Boolean,
    onStatus: (TableReservation, String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "Table reservations",
        subtitle = "Held bookings can be seated, marked no-show or cancelled once",
        icon = Icons.Default.TableRestaurant,
        elevated = true,
        contentPadding = PaddingValues(Spacing.md),
    ) {
        state.tableLoadError?.let { InlineLoadError(it) }
        if (state.tableReservations.isEmpty()) {
            DesignedEmptyState(
                title = "No upcoming table reservations",
                body = "New table bookings for this shop will appear here in start-time order.",
                icon = Icons.Default.TableRestaurant,
                tone = UiTone.Information,
                primaryLabel = if (canManage) "New reservation" else null,
                onPrimary = if (canManage) onCreate else null,
                primaryEnabled = state.online,
                primaryIcon = Icons.Default.Add,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.tableReservations, key = TableReservation::id) { reservation ->
                    TableReservationRow(reservation, canManage && state.online, onStatus)
                }
            }
        }
    }
}

@Composable
private fun GamingBookingsPanel(
    state: ReservationsUiState,
    canManage: Boolean,
    onStatus: (GamingBooking, String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "Gaming bookings",
        subtitle = "Upcoming station bookings and deposits",
        icon = Icons.Default.SportsEsports,
        elevated = true,
        contentPadding = PaddingValues(Spacing.md),
    ) {
        state.gamingLoadError?.let { InlineLoadError(it) }
        if (state.gamingBookings.isEmpty()) {
            DesignedEmptyState(
                title = "No upcoming gaming bookings",
                body = "New station bookings for this shop will appear here in start-time order.",
                icon = Icons.Default.SportsEsports,
                tone = UiTone.Brand,
                primaryLabel = if (canManage) "New booking" else null,
                onPrimary = if (canManage) onCreate else null,
                primaryEnabled = state.online,
                primaryIcon = Icons.Default.Add,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.gamingBookings, key = GamingBooking::id) { booking ->
                    GamingBookingRow(booking, canManage && state.online, onStatus)
                }
            }
        }
    }
}

@Composable
private fun InlineLoadError(message: String) {
    OperationalBanner(
        title = "Latest refresh failed",
        detail = "$message Existing rows are kept until the next successful refresh.",
        tone = UiTone.Danger,
        icon = Icons.Default.Warning,
    )
}

@Composable
private fun TableReservationRow(
    reservation: TableReservation,
    actionsEnabled: Boolean,
    onStatus: (TableReservation, String) -> Unit,
) {
    ReservationRowSurface(
        resource = "Table ${reservation.tableCode}",
        guestName = reservation.guestName,
        partySize = reservation.partySize,
        startsAt = reservation.startsAt,
        endsAt = reservation.endsAt,
        contact = reservation.contact,
        detail = reservation.notes,
        status = reservation.status,
    ) {
        if (reservationStatusIsPending(reservation.status)) {
            ReservationActionGroup(
                arrivedLabel = "Seat guest",
                actionsEnabled = actionsEnabled,
                onArrived = { onStatus(reservation, "seated") },
                onNoShow = { onStatus(reservation, "no_show") },
                onCancel = { onStatus(reservation, "cancelled") },
            )
        }
    }
}

@Composable
private fun GamingBookingRow(
    booking: GamingBooking,
    actionsEnabled: Boolean,
    onStatus: (GamingBooking, String) -> Unit,
) {
    ReservationRowSurface(
        resource = booking.stationCode,
        guestName = booking.guestName,
        partySize = booking.partySize,
        startsAt = booking.startsAt,
        endsAt = booking.endsAt,
        contact = booking.contact,
        detail = if (booking.depositMinor > 0) "Deposit ${booking.depositMinor.asRupees()}" else "No deposit recorded",
        status = booking.status,
    ) {
        if (reservationStatusIsPending(booking.status)) {
            ReservationActionGroup(
                arrivedLabel = "Mark used",
                actionsEnabled = actionsEnabled,
                onArrived = { onStatus(booking, "consumed") },
                onNoShow = { onStatus(booking, "no_show") },
                onCancel = { onStatus(booking, "cancelled") },
            )
        }
    }
}

@Composable
private fun ReservationRowSurface(
    resource: String,
    guestName: String,
    partySize: Int,
    startsAt: String,
    endsAt: String,
    contact: String?,
    detail: String?,
    status: String,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.SurfaceRaised)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeMd).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    resource,
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$guestName · $partySize guest${if (partySize == 1) "" else "s"}",
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OperationalStatusBadge(
                label = reservationStatusLabel(status),
                tone = reservationTone(status),
            )
        }
        Text(
            "${formatReservationDateTime(startsAt)} — ${formatReservationDateTime(endsAt)}",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        contact?.takeIf(String::isNotBlank)?.let {
            Text("Contact · $it", color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        detail?.takeIf(String::isNotBlank)?.let {
            Text(it, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

@Composable
private fun ReservationActionGroup(
    arrivedLabel: String,
    actionsEnabled: Boolean,
    onArrived: () -> Unit,
    onNoShow: () -> Unit,
    onCancel: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ErpButton(arrivedLabel, onArrived, Modifier.fillMaxWidth(), ActionIntent.Success, actionsEnabled)
                ErpButton("No-show", onNoShow, Modifier.fillMaxWidth(), ActionIntent.Warning, actionsEnabled)
                ErpButton("Cancel", onCancel, Modifier.fillMaxWidth(), ActionIntent.Destructive, actionsEnabled)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ErpButton(arrivedLabel, onArrived, Modifier.weight(1f), ActionIntent.Success, actionsEnabled)
                ErpButton("No-show", onNoShow, Modifier.weight(1f), ActionIntent.Warning, actionsEnabled)
                ErpButton("Cancel", onCancel, Modifier.weight(1f), ActionIntent.Destructive, actionsEnabled)
            }
        }
    }
}

@Composable
private fun ReservationDialogs(
    state: ReservationsUiState,
    access: ReservationsAccess,
    vm: ReservationsViewModel,
) {
    when (val dialog = state.dialog) {
        ReservationsDialog.NewTableReservation -> if (access.canManageTableReservations) {
            NewTableReservationDialog(state, vm)
        }
        ReservationsDialog.NewGamingBooking -> if (access.canManageGamingBookings) {
            NewGamingBookingDialog(state, vm)
        }
        is ReservationsDialog.ConfirmTableStatus -> if (access.canManageTableReservations) {
            StatusConfirmationDialog(
                resource = "Table ${dialog.reservation.tableCode}",
                guestName = dialog.reservation.guestName,
                targetStatus = dialog.targetStatus,
                busy = state.busy,
                error = state.formError,
                onConfirm = { vm.updateTableStatus(dialog.reservation.id, dialog.targetStatus) },
                onDismiss = vm::closeDialog,
            )
        }
        is ReservationsDialog.ConfirmGamingStatus -> if (access.canManageGamingBookings) {
            StatusConfirmationDialog(
                resource = dialog.booking.stationCode,
                guestName = dialog.booking.guestName,
                targetStatus = dialog.targetStatus,
                busy = state.busy,
                error = state.formError,
                onConfirm = { vm.updateGamingStatus(dialog.booking.id, dialog.targetStatus) },
                onDismiss = vm::closeDialog,
            )
        }
        null -> Unit
    }
}

@Composable
private fun NewTableReservationDialog(state: ReservationsUiState, vm: ReservationsViewModel) {
    val initial = remember { roundedBookingStart() }
    var tableId by remember { mutableStateOf(state.tables.firstOrNull()?.id.orEmpty()) }
    var guestName by remember { mutableStateOf("") }
    var partySizeText by remember { mutableStateOf("2") }
    var contact by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf(initial) }
    var endsAt by remember { mutableStateOf(initial.plusHours(1).plusMinutes(30)) }
    var notes by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "New table reservation",
        confirmLabel = "Save reservation",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        confirmEnabled = state.online && state.tables.isNotEmpty(),
        onConfirm = {
            val partySize = partySizeText.toIntOrNull()
            val error = reservationDraftError(
                resourceId = tableId,
                resourceLabel = "table",
                guestName = guestName,
                partySize = partySize,
                contact = contact,
                startsAt = startsAt,
                endsAt = endsAt,
                notes = notes,
            )
            if (error != null) {
                localError = error
            } else {
                localError = null
                vm.createTableReservation(
                    TableReservationCreate(
                        tableId = tableId,
                        guestName = guestName.trim(),
                        partySize = requireNotNull(partySize),
                        contact = contact.trim().ifEmpty { null },
                        startsAt = startsAt.toReservationWire(),
                        endsAt = endsAt.toReservationWire(),
                        notes = notes.trim().ifEmpty { null },
                    ),
                )
            }
        },
    ) {
        if (state.tables.isEmpty()) {
            Text("No tables are available in this shop. Add a table on the web setup screen first.", color = Brand.Warning)
        } else {
            PickerField(
                label = "Table",
                selectedLabel = state.tables.firstOrNull { it.id == tableId }?.let {
                    "${it.code} · ${it.seats} seats"
                } ?: "Select a table",
                options = state.tables.map { it.id to "${it.code} · ${it.seats} seats" },
                onSelect = { tableId = it },
            )
        }
        GuestFields(
            guestName = guestName,
            onGuestName = { guestName = it },
            partySize = partySizeText,
            onPartySize = { partySizeText = it },
            contact = contact,
            onContact = { contact = it },
        )
        DateTimeFields(startsAt, endsAt, { startsAt = it }, { endsAt = it })
        Field(
            label = "Notes (optional)",
            value = notes,
            onChange = { notes = it.take(500) },
            singleLine = false,
        )
        OnlineOnlyFormNote()
    }
}

@Composable
private fun NewGamingBookingDialog(state: ReservationsUiState, vm: ReservationsViewModel) {
    val initial = remember { roundedBookingStart() }
    var stationId by remember { mutableStateOf(state.stations.firstOrNull()?.id.orEmpty()) }
    var guestName by remember { mutableStateOf("") }
    var partySizeText by remember { mutableStateOf("1") }
    var contact by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf(initial) }
    var endsAt by remember { mutableStateOf(initial.plusHours(1)) }
    var depositText by remember { mutableStateOf("0") }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "New gaming booking",
        confirmLabel = "Save booking",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        confirmEnabled = state.online && state.stations.isNotEmpty(),
        onConfirm = {
            val partySize = partySizeText.toIntOrNull()
            val deposit = parseRupeesToMinor(depositText)
            val error = when {
                deposit == null -> "Deposit must be rupees with no more than 2 decimal places."
                else -> reservationDraftError(
                    resourceId = stationId,
                    resourceLabel = "gaming station",
                    guestName = guestName,
                    partySize = partySize,
                    contact = contact,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    depositMinor = deposit,
                )
            }
            if (error != null) {
                localError = error
            } else {
                localError = null
                vm.createGamingBooking(
                    GamingBookingCreate(
                        stationId = stationId,
                        startsAt = startsAt.toReservationWire(),
                        endsAt = endsAt.toReservationWire(),
                        guestName = guestName.trim(),
                        contact = contact.trim().ifEmpty { null },
                        partySize = requireNotNull(partySize),
                        depositMinor = requireNotNull(deposit),
                    ),
                )
            }
        },
    ) {
        if (state.stations.isEmpty()) {
            Text("No active gaming stations are available in this shop.", color = Brand.Warning)
        } else {
            PickerField(
                label = "Gaming station",
                selectedLabel = state.stations.firstOrNull { it.id == stationId }?.let {
                    "${it.code} · ${it.name}"
                } ?: "Select a station",
                options = state.stations.map { it.id to "${it.code} · ${it.name}" },
                onSelect = { stationId = it },
            )
        }
        GuestFields(
            guestName = guestName,
            onGuestName = { guestName = it },
            partySize = partySizeText,
            onPartySize = { partySizeText = it },
            contact = contact,
            onContact = { contact = it },
        )
        DateTimeFields(startsAt, endsAt, { startsAt = it }, { endsAt = it })
        DecimalField(depositText, { depositText = it }, "Deposit (₹)")
        OnlineOnlyFormNote()
    }
}

@Composable
private fun GuestFields(
    guestName: String,
    onGuestName: (String) -> Unit,
    partySize: String,
    onPartySize: (String) -> Unit,
    contact: String,
    onContact: (String) -> Unit,
) {
    Field(
        label = "Guest name",
        value = guestName,
        onChange = { onGuestName(it.take(200)) },
    )
    Field(
        label = "Party size",
        value = partySize,
        onChange = { onPartySize(it.filter(Char::isDigit).take(4)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Field(
        label = "Phone or contact (optional)",
        value = contact,
        onChange = { onContact(it.take(50)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
}

@Composable
private fun DateTimeFields(
    startsAt: LocalDateTime,
    endsAt: LocalDateTime,
    onStart: (LocalDateTime) -> Unit,
    onEnd: (LocalDateTime) -> Unit,
) {
    DateTimeTouchField("Starts", startsAt, onStart)
    DateTimeTouchField("Ends", endsAt, onEnd)
}

@Composable
private fun DateTimeTouchField(
    label: String,
    value: LocalDateTime,
    onValue: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(
            onClick = { showReservationDateTimePicker(context, value, onValue) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = Radius.shapeMd,
        ) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Brand.Gold)
            Spacer(Modifier.width(Spacing.sm))
            Text(value.format(LOCAL_BOOKING_TIME), color = Brand.Foreground)
        }
    }
}

@Composable
private fun OnlineOnlyFormNote() {
    Text(
        "This booking is saved directly to the live shop. If the connection drops, it is not queued or retried automatically.",
        color = Brand.ForegroundMuted,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun StatusConfirmationDialog(
    resource: String,
    guestName: String,
    targetStatus: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = reservationStatusLabel(targetStatus)
    val destructive = targetStatus == "cancelled"
    ConfirmDialog(
        title = "$label · $resource?",
        body = when (targetStatus) {
            "seated" -> "Mark $guestName as seated at $resource. This finalises the reservation status."
            "consumed" -> "Mark $guestName's booking at $resource as used. This finalises the booking status."
            "no_show" -> "Mark $guestName as a no-show. This finalises the booking status."
            else -> "Cancel $guestName's booking at $resource. The booking remains in history for auditability."
        },
        confirmLabel = label,
        busy = busy,
        error = error,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        danger = destructive,
    )
}

private fun currentRowsEmpty(state: ReservationsUiState): Boolean = when (state.selectedTab) {
    ReservationTab.TABLES -> state.tableReservations.isEmpty()
    ReservationTab.GAMING -> state.gamingBookings.isEmpty()
}

private fun reservationTone(status: String): UiTone = when (status) {
    "held" -> UiTone.Warning
    "seated", "consumed" -> UiTone.Success
    "cancelled" -> UiTone.Danger
    "no_show" -> UiTone.Neutral
    else -> UiTone.Information
}

private val LOCAL_BOOKING_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.UK)

private fun roundedBookingStart(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
    val base = now.withSecond(0).withNano(0).plusMinutes(30)
    val remainder = base.minute % 15
    return if (remainder == 0) base else base.plusMinutes((15 - remainder).toLong())
}

private fun showReservationDateTimePicker(
    context: Context,
    initial: LocalDateTime,
    onSelected: (LocalDateTime) -> Unit,
) {
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(LocalDateTime.of(year, month + 1, day, hour, minute))
                },
                initial.hour,
                initial.minute,
                false,
            ).show()
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}
