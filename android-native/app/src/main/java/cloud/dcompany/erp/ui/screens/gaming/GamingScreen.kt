package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.MetricCard
import cloud.dcompany.erp.ui.components.NumericValue
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.VoidReasonInput
import cloud.dcompany.erp.ui.components.resolvedVoidReason
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal enum class StationVisualState {
    Available,
    Disabled,
    Starting,
    StartFailed,
    Active,
    Overtime,
    Paused,
    Stopping,
    StopFailed,
    PaymentDue,
    SendPending,
    SendRejected,
    CancellationRequired,
    Unavailable,
}

internal data class StationPresentation(
    val state: StationVisualState,
    val statusLabel: String,
    val tone: UiTone,
    val statusIcon: ImageVector,
)

private data class StationFilter(val id: String, val label: String)

private data class StopRequest(val station: Station, val session: GameSession)

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    .withZone(ZoneId.systemDefault())

@Composable
fun GamingScreen(
    access: GamingAccess = GamingAccess(),
    focusSessionId: String? = null,
    focusStationId: String? = null,
    onDismissFocus: () -> Unit = {},
    vm: GamingViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }

    // One lifecycle-aware clock drives every visible active station. This
    // avoids one coroutine per card and stops all timer wakeups while the app
    // is backgrounded, while the backend remains authoritative for billing.
    val lifecycleOwner = LocalLifecycleOwner.current
    val wallClock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasTickingSession = state.sessions.any { it.status in setOf("active", "stopping") }
    LaunchedEffect(lifecycleOwner, hasTickingSession) {
        if (!hasTickingSession) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                wallClock.longValue = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    var selectedFilter by rememberSaveable { mutableStateOf("all") }
    var starting by remember { mutableStateOf<Station?>(null) }
    var stopping by remember { mutableStateOf<StopRequest?>(null) }
    var sending by remember { mutableStateOf<GameSession?>(null) }
    var cancelling by remember { mutableStateOf<GameSession?>(null) }
    var paymentQueueOpen by rememberSaveable { mutableStateOf(false) }
    var cancellationQueueOpen by rememberSaveable { mutableStateOf(false) }

    val filters = remember(state.stations) { stationFilters(state.stations) }
    LaunchedEffect(filters, selectedFilter) {
        if (filters.none { it.id == selectedFilter }) selectedFilter = "all"
    }
    LaunchedEffect(focusStationId, state.stations) {
        // A notification/deep link takes precedence over the operator's saved
        // filter so the highlighted station can never be silently hidden.
        if (focusStationId != null && state.stations.any { it.id == focusStationId }) {
            selectedFilter = "all"
        }
    }
    val visibleStations = remember(state.stations, selectedFilter) {
        state.stations.filter { selectedFilter == "all" || stationFilterId(it.type) == selectedFilter }
    }
    val gridState = rememberLazyGridState()
    val focusIndex = focusStationId?.let { id -> visibleStations.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    LaunchedEffect(focusStationId, focusIndex) {
        if (focusIndex != null) gridState.animateScrollToItem(focusIndex)
    }

    if (state.stations.isEmpty()) {
        GamingEmptyState(state = state, onRefresh = vm::load)
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (!access.canManageSessions) ViewOnlyNotice()
            GamingAlarmPermissionCard()

            if (focusSessionId != null && focusStationId != null) {
                GamingNotificationFocusBanner(
                    sessionStillActive = state.sessions.any {
                        it.id == focusSessionId && it.stationId == focusStationId &&
                            it.status in setOf("active", "paused", "stopping")
                    },
                    stationName = state.stations.firstOrNull { it.id == focusStationId }?.name,
                    onDismiss = onDismissFocus,
                )
            }

            state.refreshError?.let { message ->
                OperationalBanner(
                    title = "Gaming board may be out of date",
                    detail = message,
                    tone = UiTone.Warning,
                    icon = Icons.Filled.CloudOff,
                ) {
                    ErpButton(
                        text = if (state.refreshing) "Refreshing…" else "Retry",
                        onClick = vm::load,
                        intent = ActionIntent.Secondary,
                        enabled = !state.refreshing,
                        busy = state.refreshing,
                        leadingIcon = Icons.Filled.Refresh,
                    )
                }
            }

            GamingMetrics(state)

            if (state.needsCancellation.isNotEmpty()) {
                OperationalBanner(
                    title = "${state.needsCancellation.size} stopped ${sessionWord(state.needsCancellation.size)} need resolution",
                    detail = "Zero-value sessions cannot become POS bills. Review and cancel each with a reason.",
                    tone = UiTone.Danger,
                    icon = Icons.Filled.Error,
                ) {
                    ErpButton(
                        text = "Review",
                        onClick = { cancellationQueueOpen = true },
                        intent = ActionIntent.Destructive,
                        enabled = access.canManageSessions && state.busyStationId == null,
                    )
                }
            }

            if (state.readyForPos.isNotEmpty()) {
                val total = state.readyForPos.sumOf { it.amountMinor ?: 0L }
                OperationalBanner(
                    title = "${state.readyForPos.size} ${sessionWord(state.readyForPos.size)} awaiting payment",
                    detail = "${total.asRupees()} total · review each session before sending it to POS",
                    tone = UiTone.Warning,
                    icon = Icons.Filled.Payments,
                ) {
                    ErpButton(
                        text = "Review & send",
                        onClick = { paymentQueueOpen = true },
                        intent = ActionIntent.Warning,
                        enabled = access.canManageSessions && state.busyStationId == null,
                    )
                }
            }

            if (state.busyStationId != null) {
                OperationalBanner(
                    title = "Saving gaming action",
                    detail = "Other station actions are paused until this change is safely stored.",
                    tone = UiTone.Information,
                    icon = Icons.Filled.CloudUpload,
                )
            }

            GamingFilterRow(
                filters = filters,
                stations = state.stations,
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
            )

            if (visibleStations.isEmpty()) {
                SectionCard(
                    title = "Station workspace",
                    subtitle = "The selected station type has no configured resources.",
                    modifier = Modifier.weight(1f),
                ) {
                    DesignedEmptyState(
                        title = "No stations in this filter",
                        body = "Choose All or another station type to return to the operational board.",
                        icon = Icons.Filled.SportsEsports,
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 232.dp),
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    modifier = Modifier.weight(1f),
                ) {
                    items(visibleStations, key = { it.id }) { station ->
                        GamingStationCard(
                            station = station,
                            session = state.activeFor(station.id),
                            wallClock = wallClock,
                            actionInProgress = state.busyStationId != null,
                            busyHere = state.busyStationId == station.id,
                            focused = station.id == focusStationId,
                            canWrite = access.canManageSessions,
                            onStart = { starting = station },
                            onStop = { session -> stopping = StopRequest(station, session) },
                            onRetryStart = vm::retryStart,
                            onDiscardStart = vm::discardFailedStart,
                            onSend = { sending = it },
                            onCancelUnbilled = { cancelling = it },
                        )
                    }
                }
            }
        }
    }

    starting?.takeIf { access.canManageSessions }?.let { station ->
        StartSessionDialog(
            station = station,
            onDismiss = { starting = null },
            onConfirm = { phone, minutes ->
                starting = null
                vm.start(station, phone, minutes)
            },
        )
    }

    stopping?.takeIf { access.canManageSessions }?.let { request ->
        StopSessionDialog(
            request = request,
            onDismiss = { stopping = null },
            onConfirm = {
                stopping = null
                vm.stop(request.session)
            },
        )
    }

    sending?.takeIf { access.canManageSessions }?.let { session ->
        val stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
            ?: "Gaming session"
        AlertDialog(
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            onDismissRequest = { sending = null },
            title = { Text("Send to POS?") },
            text = {
                Text(
                    "$stationName · ${session.billableMinutes ?: 0} minutes · " +
                        "${(session.amountMinor ?: 0L).asRupees()}. POS will receive a separate unpaid order " +
                        "for the cashier to review and collect.",
                    color = Brand.ForegroundMuted,
                )
            },
            confirmButton = {
                ErpButton(
                    text = "Send to POS",
                    onClick = { sending = null; vm.sendToPos(session) },
                    leadingIcon = Icons.AutoMirrored.Filled.Send,
                )
            },
            dismissButton = { TextButton(onClick = { sending = null }) { Text("Not yet") } },
        )
    }

    cancelling?.takeIf { access.canManageSessions }?.let { session ->
        val stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
            ?: "Gaming session"
        CancelUnbilledSessionDialog(
            stationName = stationName,
            amountMinor = session.amountMinor ?: 0L,
            onDismiss = { cancelling = null },
            onConfirm = { reason ->
                cancelling = null
                vm.cancelUnbilled(session, reason)
            },
        )
    }

    if (paymentQueueOpen) {
        GamingQueueDialog(
            title = "Sessions awaiting payment",
            detail = "Send sessions individually so each remains a separate, traceable POS order.",
            sessions = state.readyForPos,
            stations = state.stations,
            actionLabel = "Send",
            actionIntent = ActionIntent.Primary,
            busyStationId = state.busyStationId,
            onDismiss = { paymentQueueOpen = false },
            onSelect = {
                paymentQueueOpen = false
                sending = it
            },
        )
    }

    if (cancellationQueueOpen) {
        GamingQueueDialog(
            title = "Sessions requiring cancellation",
            detail = "A reason is required and recorded in the audit trail.",
            sessions = state.needsCancellation,
            stations = state.stations,
            actionLabel = "Review",
            actionIntent = ActionIntent.Destructive,
            busyStationId = state.busyStationId,
            onDismiss = { cancellationQueueOpen = false },
            onSelect = {
                cancellationQueueOpen = false
                cancelling = it
            },
        )
    }

    state.error?.let { message ->
        AlertDialog(
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Gaming action not completed") },
            text = { Text(message, color = Brand.ForegroundMuted) },
        )
    }
}

@Composable
private fun GamingEmptyState(state: GamingUiState, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionCard(
            title = "Gaming station workspace",
            subtitle = "Configured stations and live sessions will appear here after synchronisation.",
            icon = Icons.Filled.SportsEsports,
            modifier = Modifier.weight(1f),
        ) {
            if (state.initialLoading) {
                Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        CircularProgressIndicator(color = Brand.Gold, modifier = Modifier.size(30.dp))
                        Text("Waiting for the first station sync", color = Brand.Foreground, style = MaterialTheme.typography.titleLarge)
                        Text("The board will populate as soon as the ERP responds.", color = Brand.ForegroundMuted)
                    }
                }
            } else if (state.initialLoadFailed) {
                DesignedEmptyState(
                    title = "Could not load gaming stations",
                    body = state.refreshError
                        ?: "This tablet has no saved station data yet. Check the connection and try again.",
                    icon = Icons.Filled.CloudOff,
                    primaryLabel = "Retry",
                    onPrimary = onRefresh,
                )
            } else {
                DesignedEmptyState(
                    title = "No gaming stations configured",
                    body = state.refreshError ?: if (state.refreshing) {
                        "Checking the ERP for configured stations. Saved data remains available."
                    } else {
                        "Add stations in ERP settings, then refresh this operational board."
                    },
                    icon = Icons.Filled.SportsEsports,
                    primaryLabel = if (state.refreshing) "Refreshing…" else "Refresh",
                    onPrimary = onRefresh,
                    primaryEnabled = !state.refreshing,
                    primaryBusy = state.refreshing,
                    primaryIcon = Icons.Filled.Refresh,
                )
            }
        }
    }
}

@Composable
private fun GamingMetrics(state: GamingUiState) {
    val active = state.sessions.count { it.status in setOf("active", "paused", "stopping") }
    val available = state.stations.count { it.isActive && state.activeFor(it.id) == null }
    val disabled = state.stations.count { !it.isActive }
    val awaitingTotal = state.readyForPos.sumOf { it.amountMinor ?: 0L }
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { modifier ->
            MetricCard(
                title = "Total stations",
                value = state.stations.size.toString(),
                detail = if (disabled == 0) "All configured" else "$disabled disabled",
                icon = Icons.Filled.SportsEsports,
                tone = UiTone.Brand,
                modifier = modifier,
            )
        },
        { modifier ->
            MetricCard(
                title = "Active sessions",
                value = active.toString(),
                detail = "Running now",
                icon = Icons.Filled.PlayArrow,
                tone = UiTone.Success,
                modifier = modifier,
            )
        },
        { modifier ->
            MetricCard(
                title = "Available",
                value = available.toString(),
                detail = "Ready to use",
                icon = Icons.Filled.CheckCircle,
                tone = UiTone.Information,
                modifier = modifier,
            )
        },
        { modifier ->
            MetricCard(
                title = "Awaiting payment",
                value = state.readyForPos.size.toString(),
                detail = awaitingTotal.asRupees(),
                icon = Icons.Filled.Payments,
                tone = if (state.readyForPos.isEmpty()) UiTone.Neutral else UiTone.Warning,
                modifier = modifier,
            )
        },
        { modifier ->
            MetricCard(
                title = "POS shift",
                value = if (state.activeShiftId == null) "Required" else "Open",
                detail = if (state.activeShiftId == null) "Open shift to start" else "Session starts enabled",
                icon = Icons.Filled.Schedule,
                tone = if (state.activeShiftId == null) UiTone.Warning else UiTone.Success,
                modifier = modifier,
            )
        },
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 880.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                cards.forEach { card -> card(Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                cards.chunked(3).forEach { rowCards ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        rowCards.forEach { card -> card(Modifier.weight(1f)) }
                        repeat(3 - rowCards.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GamingFilterRow(
    filters: List<StationFilter>,
    stations: List<Station>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    PremiumTabBar(
        options = filters.map { filter ->
            TabOption(
                id = filter.id,
                label = filter.label,
                count = if (filter.id == "all") stations.size
                else stations.count { stationFilterId(it.type) == filter.id },
            )
        },
        selectedId = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun GamingStationCard(
    station: Station,
    session: GameSession?,
    wallClock: State<Long>,
    actionInProgress: Boolean,
    busyHere: Boolean,
    focused: Boolean,
    canWrite: Boolean,
    onStart: () -> Unit,
    onStop: (GameSession) -> Unit,
    onRetryStart: (GameSession) -> Unit,
    onDiscardStart: (GameSession) -> Unit,
    onSend: (GameSession) -> Unit,
    onCancelUnbilled: (GameSession) -> Unit,
) {
    // A paused session must look paused. Without an authoritative paused-at
    // field, freezing the local display is safer than inventing elapsed time.
    val shouldTick = session?.status in setOf("active", "stopping")
    val frozenMillis = remember(session?.id) { System.currentTimeMillis() }
    val nowMillis = if (shouldTick) wallClock.value else frozenMillis

    val presentation = stationPresentation(station, session, nowMillis)
    val cardBackground = when (presentation.tone) {
        UiTone.Warning -> Brand.WarningMuted.copy(alpha = 0.40f)
        UiTone.Danger -> Brand.DangerMuted.copy(alpha = 0.42f)
        UiTone.Information -> Brand.InformationMuted.copy(alpha = 0.40f)
        else -> Brand.Surface
    }
    val stationIcon = stationTypeIcon(station.type)
    val actionsEnabled = canWrite && !actionInProgress

    Column(
        Modifier.fillMaxWidth().heightIn(min = 228.dp)
            .clip(Radius.shapeLg).background(cardBackground)
            .border(if (focused) 2.dp else 1.dp, if (focused) Brand.Gold else Brand.BorderSubtle, Radius.shapeLg)
            .semantics {
                contentDescription = "${station.name}. ${presentation.statusLabel}. " +
                    "${station.ratePerHourMinor.asRupees()} per hour."
            }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                station.name,
                color = Brand.Foreground,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OperationalStatusBadge(
                label = presentation.statusLabel,
                tone = presentation.tone,
                icon = presentation.statusIcon,
            )
        }
        // Keep rate details out of the badge-constrained title row. Long
        // names such as "Racing Simulator 1" remain identifiable and their
        // price never disappears behind an ellipsis on a four-column tablet.
        Text(
            "${stationTypeLabel(station.type)} · ${station.ratePerHourMinor.asRupees()}/hour",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                Modifier.size(40.dp).clip(Radius.shapeMd).background(statusIconBackground(presentation.tone)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(stationIcon, contentDescription = null, tint = statusColor(presentation.tone), modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StationBody(presentation, station, session, nowMillis)
            }
        }

        HorizontalDivider(color = Brand.BorderSubtle)

        when (presentation.state) {
            StationVisualState.Available -> ErpButton(
                text = "Start session",
                onClick = onStart,
                enabled = actionsEnabled,
                busy = busyHere,
                leadingIcon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
            )

            StationVisualState.Active,
            StationVisualState.Overtime,
            StationVisualState.Paused,
            StationVisualState.StopFailed,
            -> ErpButton(
                text = if (presentation.state == StationVisualState.StopFailed) "Retry stop" else "Stop & calculate",
                onClick = { session?.let(onStop) },
                enabled = actionsEnabled && session != null,
                busy = busyHere,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.StopCircle,
                modifier = Modifier.fillMaxWidth(),
            )

            StationVisualState.StartFailed -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ErpButton(
                    text = "Discard",
                    onClick = { session?.let(onDiscardStart) },
                    enabled = actionsEnabled && session != null,
                    intent = ActionIntent.Secondary,
                    modifier = Modifier.weight(1f),
                )
                ErpButton(
                    text = "Retry",
                    onClick = { session?.let(onRetryStart) },
                    enabled = actionsEnabled && session != null,
                    busy = busyHere,
                    leadingIcon = Icons.Filled.Refresh,
                    modifier = Modifier.weight(1f),
                )
            }

            StationVisualState.PaymentDue,
            StationVisualState.SendRejected,
            -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = { session?.let(onCancelUnbilled) },
                    enabled = actionsEnabled && session != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Danger),
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                    modifier = Modifier.weight(0.8f).heightIn(min = 48.dp),
                ) { Text("Void", maxLines = 1) }
                ErpButton(
                    text = if (presentation.state == StationVisualState.SendRejected) "Retry send" else "Send to POS",
                    onClick = { session?.let(onSend) },
                    enabled = actionsEnabled && session != null,
                    busy = busyHere,
                    modifier = Modifier.weight(1.7f),
                )
            }

            StationVisualState.CancellationRequired -> ErpButton(
                text = "Cancel with reason",
                onClick = { session?.let(onCancelUnbilled) },
                enabled = actionsEnabled && session != null,
                busy = busyHere,
                intent = ActionIntent.Destructive,
                modifier = Modifier.fillMaxWidth(),
            )

            StationVisualState.Starting,
            StationVisualState.Stopping,
            StationVisualState.SendPending,
            -> ErpButton(
                text = when (presentation.state) {
                    StationVisualState.Starting -> "Waiting for server"
                    StationVisualState.Stopping -> "Saving stop request"
                    else -> "Sending to POS"
                },
                onClick = {},
                enabled = false,
                busy = busyHere,
                modifier = Modifier.fillMaxWidth(),
            )

            StationVisualState.Disabled,
            StationVisualState.Unavailable,
            -> Unit
        }
    }
}

@Composable
private fun StationBody(
    presentation: StationPresentation,
    station: Station,
    session: GameSession?,
    nowMillis: Long,
) {
    when (presentation.state) {
        StationVisualState.Available -> {
            Text("Ready for a new session", color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium)
            Text("Billing starts only after server confirmation.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        StationVisualState.Disabled -> {
            Text("Station disabled", color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium)
            Text("Ask a manager to enable it before taking a booking.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        StationVisualState.Starting -> {
            Text("Do not begin play yet", color = Brand.Warning, fontWeight = FontWeight.SemiBold)
            Text("Waiting for the server to confirm the start.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        StationVisualState.StartFailed -> {
            Text("Start was not accepted", color = Brand.Danger, fontWeight = FontWeight.SemiBold)
            Text(
                session?.lastError ?: "Review the shift and connection, then retry or discard.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StationVisualState.Active,
        StationVisualState.Overtime,
        StationVisualState.Paused,
        StationVisualState.Stopping,
        StationVisualState.StopFailed,
        -> {
            if (presentation.state == StationVisualState.Paused) {
                Text(
                    "Timer paused",
                    color = Brand.Warning,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                val elapsed = session?.let { elapsedMillis(it, nowMillis) } ?: 0L
                NumericValue(
                    value = formatElapsed(elapsed),
                    color = if (presentation.state in setOf(StationVisualState.Overtime, StationVisualState.StopFailed)) {
                        Brand.Danger
                    } else {
                        Brand.Good
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            val started = session?.startAt?.let { runCatching { timeFormatter.format(Instant.parse(it)) }.getOrNull() }
            Text(
                buildString {
                    if (started != null) append("Started $started")
                    session?.timerMinutes?.let { minutes ->
                        if (isNotEmpty()) append(" · ")
                        append("${minutes}m booking")
                    }
                }.ifEmpty { "Session in progress" },
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                when (presentation.state) {
                    StationVisualState.Overtime -> "Booked time has ended. Stop when play finishes."
                    StationVisualState.Paused -> "Paused on the server. Stop or resolve before reuse."
                    StationVisualState.Stopping -> "Stop is saved; session stays active until confirmed."
                    StationVisualState.StopFailed -> session?.lastError
                        ?: "Stop was not accepted. Check the connection and retry."
                    else -> "Final charge is calculated by the server when stopped."
                },
                color = if (presentation.state in setOf(StationVisualState.Overtime, StationVisualState.StopFailed)) {
                    Brand.Danger
                } else {
                    Brand.ForegroundFaint
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (presentation.state == StationVisualState.StopFailed) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            sessionCustomerLabel(session)?.let { customer ->
                Text(
                    customer,
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StationVisualState.PaymentDue,
        StationVisualState.SendPending,
        StationVisualState.SendRejected,
        StationVisualState.CancellationRequired,
        -> {
            NumericValue(
                value = (session?.amountMinor ?: 0L).asRupees(),
                color = if (presentation.state == StationVisualState.CancellationRequired) Brand.Danger else Brand.Warning,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "Stopped · ${session?.billableMinutes ?: 0} min",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            sessionCustomerLabel(session)?.let { customer ->
                Text(
                    customer,
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                unbilledSessionDetail(presentation.state, session),
                color = if (presentation.state == StationVisualState.SendRejected) Brand.Danger else Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (presentation.state == StationVisualState.SendRejected) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StationVisualState.Unavailable -> {
            Text("Action unavailable", color = Brand.Danger, fontWeight = FontWeight.SemiBold)
            Text("Refresh Gaming or ask a manager to review this station.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun stationPresentation(station: Station, session: GameSession?, nowMillis: Long): StationPresentation {
    if (session == null) {
        return if (station.isActive) {
            StationPresentation(StationVisualState.Available, "Available", UiTone.Success, Icons.Filled.CheckCircle)
        } else {
            StationPresentation(StationVisualState.Disabled, "Disabled", UiTone.Neutral, Icons.Filled.Block)
        }
    }
    if (session.status == "start_failed") {
        return StationPresentation(StationVisualState.StartFailed, "Start failed", UiTone.Danger, Icons.Filled.Error)
    }
    if (session.status == "starting") {
        return StationPresentation(StationVisualState.Starting, "Starting", UiTone.Warning, Icons.Filled.HourglassTop)
    }
    if (session.status == "ended" && session.orderId == null) {
        if ((session.amountMinor ?: 0L) <= 0L) {
            return StationPresentation(
                StationVisualState.CancellationRequired,
                "Needs review",
                UiTone.Danger,
                Icons.Filled.Error,
            )
        }
        if (session.localState == "send_pending") {
            return StationPresentation(StationVisualState.SendPending, "Sending", UiTone.Information, Icons.Filled.CloudUpload)
        }
        if (session.localState == "send_rejected") {
            return StationPresentation(StationVisualState.SendRejected, "Send failed", UiTone.Danger, Icons.Filled.Error)
        }
        return StationPresentation(StationVisualState.PaymentDue, "Payment due", UiTone.Warning, Icons.Filled.Payments)
    }
    if (session.status == "stopping") {
        return StationPresentation(StationVisualState.Stopping, "Stopping", UiTone.Information, Icons.Filled.CloudUpload)
    }
    if (
        session.localState == GamingSessionState.STOP_REJECTED &&
        session.status in setOf("active", "paused")
    ) {
        return StationPresentation(StationVisualState.StopFailed, "Stop failed", UiTone.Danger, Icons.Filled.Error)
    }
    if (session.status == "paused") {
        return StationPresentation(StationVisualState.Paused, "Paused", UiTone.Warning, Icons.Filled.PauseCircle)
    }
    if (session.status == "active") {
        val overtime = session.timerEndsAt?.let {
            runCatching { Instant.parse(it).toEpochMilli() < nowMillis }.getOrDefault(false)
        } ?: false
        return if (overtime) {
            StationPresentation(StationVisualState.Overtime, "Overtime", UiTone.Danger, Icons.Filled.Warning)
        } else {
            StationPresentation(StationVisualState.Active, "Active", UiTone.Success, Icons.Filled.PlayArrow)
        }
    }
    return StationPresentation(StationVisualState.Unavailable, "Unavailable", UiTone.Danger, Icons.Filled.Error)
}

internal fun unbilledSessionDetail(state: StationVisualState, session: GameSession?): String = when (state) {
    StationVisualState.CancellationRequired -> "No billable amount. Cancel with a reason before reuse."
    StationVisualState.SendPending -> "POS handoff saved and waiting for confirmation."
    StationVisualState.SendRejected -> session?.lastError?.takeIf(String::isNotBlank)
        ?: "POS refused the handoff. Check the shift and connection, then retry."
    else -> "Send to POS before the next session."
}

internal fun sessionCustomerLabel(session: GameSession?): String? {
    val name = session?.customerName?.trim().orEmpty()
    val phone = session?.customerPhone?.trim().orEmpty()
    return when {
        name.isNotEmpty() && phone.isNotEmpty() -> "$name · $phone"
        name.isNotEmpty() -> name
        phone.isNotEmpty() -> phone
        else -> null
    }
}

@Composable
private fun GamingQueueDialog(
    title: String,
    detail: String,
    sessions: List<GameSession>,
    stations: List<Station>,
    actionLabel: String,
    actionIntent: ActionIntent,
    busyStationId: String?,
    onDismiss: () -> Unit,
    onSelect: (GameSession) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        modifier = Modifier.widthIn(min = 520.dp, max = 720.dp),
        title = { Text(title, color = Brand.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(detail, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(sessions, key = GameSession::id) { session ->
                        val station = stations.firstOrNull { it.id == session.stationId }
                        Row(
                            Modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.Surface)
                                .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                                .padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    station?.name ?: "Gaming session",
                                    color = Brand.Foreground,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    "${session.billableMinutes ?: 0} min · ${(session.amountMinor ?: 0L).asRupees()}",
                                    color = Brand.ForegroundMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                sessionCustomerLabel(session)?.let { customer ->
                                    Text(
                                        customer,
                                        color = Brand.ForegroundMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            ErpButton(
                                text = actionLabel,
                                onClick = { onSelect(session) },
                                intent = actionIntent,
                                enabled = busyStationId == null,
                                busy = busyStationId == session.stationId,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StopSessionDialog(
    request: StopRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text("Stop ${request.station.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "This ends billable time. The server will calculate the final duration and amount, then the " +
                        "station will remain blocked until the session is sent to POS or voided.",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "If the tablet is temporarily offline, the stop request is saved and shown as pending until sync completes.",
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = "Stop & calculate",
                onClick = onConfirm,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.StopCircle,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep running") } },
    )
}

@Composable
private fun GamingNotificationFocusBanner(
    sessionStillActive: Boolean,
    stationName: String?,
    onDismiss: () -> Unit,
) {
    OperationalBanner(
        title = if (sessionStillActive) "Session alert · ${stationName ?: "Gaming station"}" else "Session alert resolved",
        detail = if (sessionStillActive) {
            "Review the highlighted station, then stop it explicitly when play finishes."
        } else {
            "This session may already have been stopped on another tablet."
        },
        tone = if (sessionStillActive) UiTone.Warning else UiTone.Neutral,
        icon = if (sessionStillActive) Icons.Filled.Warning else Icons.Filled.CheckCircle,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        TextButton(onClick = onDismiss) { Text("Clear") }
    }
}

@Composable
private fun CancelUnbilledSessionDialog(
    stationName: String,
    amountMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedReasonId by rememberSaveable(stationName) { mutableStateOf<String?>(null) }
    var customReason by rememberSaveable(stationName) { mutableStateOf("") }
    val normalized = resolvedVoidReason(selectedReasonId, customReason)
    val hasBillableAmount = amountMinor > 0L

    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        title = { Text("Void session · $stationName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    if (hasBillableAmount) {
                        "This stopped session is worth ${amountMinor.asRupees()}. Voiding removes it from POS collection, " +
                            "but preserves the original amount, employee and reason in the audit trail."
                    } else {
                        "This session has no billable amount and cannot be sent to POS. Cancellation is still recorded in the audit trail."
                    },
                    color = Brand.ForegroundMuted,
                )
                VoidReasonInput(
                    selectedId = selectedReasonId,
                    customReason = customReason,
                    onPresetSelected = { selectedReasonId = it },
                    onCustomReasonChange = { customReason = it },
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = "Void session",
                onClick = { onConfirm(normalized) },
                enabled = normalized.isNotEmpty(),
                intent = ActionIntent.Destructive,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep session") } },
    )
}

@Composable
private fun StartSessionDialog(
    station: Station,
    onDismiss: () -> Unit,
    onConfirm: (String?, Int?) -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf<Int?>(60) }

    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        title = { Text("Start · ${station.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(
                    Modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.Surface)
                        .border(1.dp, Brand.BorderSubtle, Radius.shapeMd).padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(stationTypeLabel(station.type), color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
                        Text("Rate", color = Brand.ForegroundFaint, style = MaterialTheme.typography.labelSmall)
                    }
                    NumericValue(
                        value = "${station.ratePerHourMinor.asRupees()}/hour",
                        style = MaterialTheme.typography.titleLarge,
                        color = Brand.Gold,
                    )
                }
                Text("Booked time", color = Brand.Foreground, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(listOf<Int?>(30, 60, 90, 120, null)) { option ->
                        FilterChip(
                            selected = minutes == option,
                            onClick = { minutes = option },
                            label = { Text(option?.let { "${it}m" } ?: "Open-ended") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Brand.Surface,
                                labelColor = Brand.ForegroundMuted,
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                    label = { Text("Member phone (optional)") },
                    supportingText = { Text("Used to attach the session to an existing member when found.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (minutes == null) {
                        "Open-ended sessions have no overtime alarm. Billing still uses server-confirmed elapsed time."
                    } else {
                        "The timer and alarm begin only after the server confirms the start."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.GoldMuted,
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = "Start session",
                onClick = { onConfirm(phone.takeIf(String::isNotBlank), minutes) },
                leadingIcon = Icons.Filled.PlayArrow,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun stationFilters(stations: List<Station>): List<StationFilter> {
    val order = listOf("ps5", "racing", "vr", "streaming", "shisha", "other")
    val labels = mapOf(
        "all" to "All",
        "ps5" to "PS5",
        "racing" to "Racing",
        "vr" to "VR",
        "streaming" to "Streaming",
        "shisha" to "Shisha",
        "other" to "Other",
    )
    val present = stations.map { stationFilterId(it.type) }.toSet()
    return listOf(StationFilter("all", "All")) + order.filter { it in present }.map { StationFilter(it, labels.getValue(it)) }
}

private fun stationFilterId(type: String): String = when {
    type.contains("ps", ignoreCase = true) || type.contains("console", ignoreCase = true) -> "ps5"
    type.contains("sim", ignoreCase = true) || type.contains("racing", ignoreCase = true) -> "racing"
    type.contains("vr", ignoreCase = true) -> "vr"
    type.contains("stream", ignoreCase = true) -> "streaming"
    type.contains("hookah", ignoreCase = true) || type.contains("shisha", ignoreCase = true) -> "shisha"
    else -> "other"
}

private fun stationTypeLabel(type: String): String = when (stationFilterId(type)) {
    "ps5" -> "PS5"
    "racing" -> "Racing simulator"
    "vr" -> "VR"
    "streaming" -> "Streaming"
    "shisha" -> "Shisha"
    else -> type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private fun stationTypeIcon(type: String): ImageVector = when (stationFilterId(type)) {
    "racing" -> Icons.Filled.DirectionsCar
    "vr" -> Icons.Filled.Visibility
    "streaming" -> Icons.Filled.LiveTv
    "shisha" -> Icons.Filled.Air
    else -> Icons.Filled.SportsEsports
}

private fun statusColor(tone: UiTone): Color = when (tone) {
    UiTone.Success -> Brand.Good
    UiTone.Warning -> Brand.Warning
    UiTone.Danger -> Brand.Danger
    UiTone.Information -> Brand.Information
    UiTone.Brand -> Brand.Gold
    UiTone.Neutral -> Brand.ForegroundMuted
}

private fun statusIconBackground(tone: UiTone): Color = when (tone) {
    UiTone.Success -> Brand.GoodMuted
    UiTone.Warning -> Brand.WarningMuted
    UiTone.Danger -> Brand.DangerMuted
    UiTone.Information -> Brand.InformationMuted
    UiTone.Brand -> Brand.Gold.copy(alpha = 0.12f)
    UiTone.Neutral -> Brand.SurfaceHover
}

private fun elapsedMillis(session: GameSession, nowMillis: Long): Long = runCatching {
    (nowMillis - Instant.parse(session.startAt).toEpochMilli()).coerceAtLeast(0L)
}.getOrDefault(0L)

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun sessionWord(count: Int): String = if (count == 1) "session" else "sessions"
