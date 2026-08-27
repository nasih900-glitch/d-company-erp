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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingLegacyResolutionAttemptState
import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.LEGACY_PACKAGE_START_REVIEW_ERROR
import cloud.dcompany.erp.core.money.parseRupeesToMinor
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
import cloud.dcompany.erp.ui.components.TouchMoneyEntry
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.VOID_REASON_COMPACT_EDITOR_HEIGHT
import cloud.dcompany.erp.ui.components.VOID_REASON_OTHER_ID
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
    BillingMissing,
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
private data class PackageExtensionRequest(
    val session: GameSession,
    val extensions: List<GamingPackage>,
)

private data class PackageExtensionDiscardRequest(
    val action: PackageExtensionActionUi,
)

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    .withZone(ZoneId.systemDefault())

/**
 * Keeps fixed AlertDialog title/footer actions outside system navigation on
 * compact landscape windows while allowing the operational form body to
 * scroll. Taller windows retain the original, intentionally dense 440dp cap.
 */
internal fun gamingDialogBodyMaxHeight(screenHeightDp: Int) =
    (screenHeightDp.dp * 0.55f).coerceAtMost(440.dp)

/**
 * The API-35 landscape IME can cover an AlertDialog footer even when dialog
 * window insets are enabled. In the one state that opens a keyboard, move the
 * actions into a deliberately short body instead of depending on OEM inset
 * dispatch. Preset reasons keep the standard dialog layout.
 */
internal fun useCompactVoidCustomLayout(
    screenHeightDp: Int,
    selectedReasonId: String?,
) = screenHeightDp <= 640 && selectedReasonId == VOID_REASON_OTHER_ID

internal fun voidDialogBodyMaxHeight(
    screenHeightDp: Int,
    selectedReasonId: String?,
) = if (useCompactVoidCustomLayout(screenHeightDp, selectedReasonId)) {
    VOID_REASON_COMPACT_EDITOR_HEIGHT.coerceAtMost(gamingDialogBodyMaxHeight(screenHeightDp))
} else {
    gamingDialogBodyMaxHeight(screenHeightDp)
}

/**
 * Compose 1.7 uses ADJUST_NOTHING for Dialog windows on Android 12+. The
 * default `decorFitsSystemWindows = true` therefore consumes the IME inset
 * before `Modifier.imePadding()` can see it. Opting the full-width dialog
 * window into edge-to-edge makes its root inset modifiers authoritative.
 */
internal val gamingImeAwareDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

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
    val hasTickingSession = hasTickingGamingSession(state.sessions)
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
    var transferring by remember { mutableStateOf<GameSession?>(null) }
    var extendingPackage by remember { mutableStateOf<PackageExtensionRequest?>(null) }
    var reconciling by remember { mutableStateOf<GameSession?>(null) }
    var repairingBilling by remember { mutableStateOf<GameSession?>(null) }
    var resolvingLegacyStart by remember { mutableStateOf<GameSession?>(null) }
    var discardingPackageExtension by remember { mutableStateOf<PackageExtensionDiscardRequest?>(null) }
    var paymentQueueOpen by rememberSaveable { mutableStateOf(false) }
    var cancellationQueueOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(paymentQueueOpen, state.readyForPos) {
        if (paymentQueueOpen && state.readyForPos.isEmpty()) paymentQueueOpen = false
    }
    LaunchedEffect(cancellationQueueOpen, state.needsCancellation) {
        if (cancellationQueueOpen && state.needsCancellation.isEmpty()) cancellationQueueOpen = false
    }

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
    val orphanedExtensionActions = state.orphanedPackageExtensionActions()
    val gridState = rememberLazyGridState()
    val gridHeaderCount = 3 + // alarm, metrics, filters
        (if (!access.canManageSessions) 1 else 0) +
        (if (focusSessionId != null && focusStationId != null) 1 else 0) +
        (if (state.notice != null) 1 else 0) +
        (if (state.refreshError != null) 1 else 0) +
        orphanedExtensionActions.size +
        (if (state.needsCancellation.isNotEmpty()) 1 else 0) +
        (if (state.readyForPos.isNotEmpty()) 1 else 0) +
        (if (state.busyStationId != null) 1 else 0)
    val focusIndex = focusStationId?.let { id -> visibleStations.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    LaunchedEffect(focusStationId, focusIndex, gridHeaderCount) {
        if (focusIndex != null) gridState.animateScrollToItem(gridHeaderCount + focusIndex)
    }

    if (state.stations.isEmpty() && orphanedExtensionActions.isEmpty()) {
        GamingEmptyState(state = state, onRefresh = vm::load)
    } else {
        // One scroll owner keeps the station board reachable even when compact
        // landscape windows stack permissions, alarms, sync warnings and
        // payment queues above it. Nested vertical scroll containers are
        // deliberately avoided.
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 232.dp),
            contentPadding = PaddingValues(
                start = Spacing.lgPlus,
                top = Spacing.lg,
                end = Spacing.lgPlus,
                bottom = Spacing.lg,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!access.canManageSessions) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    ViewOnlyNotice()
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GamingAlarmPermissionCard()
            }

            state.notice?.let { message ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OperationalBanner(
                        title = "Gaming action completed",
                        detail = message,
                        tone = UiTone.Success,
                        icon = Icons.Filled.CheckCircle,
                    ) {
                        TextButton(onClick = vm::dismissNotice) { Text("Dismiss") }
                    }
                }
            }

            if (focusSessionId != null && focusStationId != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    GamingNotificationFocusBanner(
                        sessionStillActive = state.sessions.any {
                            it.id == focusSessionId && it.stationId == focusStationId &&
                                it.status in setOf("starting", "active", "paused", "stopping")
                        },
                        stationName = state.stations.firstOrNull { it.id == focusStationId }?.name,
                        onDismiss = onDismissFocus,
                    )
                }
            }

            state.refreshError?.let { message ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
            }

            orphanedExtensionActions.forEach { action ->
                item(
                    key = "orphan-extension-${action.actionId}",
                    span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                ) {
                    OrphanPackageExtensionBanner(
                        action = action,
                        activeShiftId = state.activeShiftId,
                        canWrite = access.canManageSessions,
                        busy = state.busyStationId != null,
                        onReview = {
                            discardingPackageExtension = PackageExtensionDiscardRequest(action)
                        },
                    )
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GamingMetrics(state)
            }

            if (state.needsCancellation.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
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
            }

            if (state.readyForPos.isNotEmpty()) {
                val total = state.readyForPos.sumOf { it.amountMinor ?: 0L }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OperationalBanner(
                        title = "${state.readyForPos.size} ${sessionWord(state.readyForPos.size)} awaiting payment",
                        detail = "${total.asRupees()} total · review each session before sending it to POS",
                        tone = UiTone.Warning,
                        icon = Icons.Filled.Payments,
                    ) {
                        ErpButton(
                            text = "Review & send",
                            onClick = { paymentQueueOpen = true },
                            // The warning badge and border already communicate the
                            // queue state. Keep the one action staff should take as
                            // the sole brand-primary control in this panel.
                            intent = ActionIntent.Primary,
                            enabled = access.canManageSessions && state.busyStationId == null &&
                                state.readyForPos.any { session ->
                                    session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT ||
                                        (access.canReconcileLegacySessions && state.activeShiftId != null)
                                },
                        )
                    }
                }
            }

            if (state.busyStationId != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OperationalBanner(
                        title = "Saving gaming action",
                        detail = "Other station actions are paused until this change is safely stored.",
                        tone = UiTone.Information,
                        icon = Icons.Filled.CloudUpload,
                    )
                }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GamingFilterRow(
                    filters = filters,
                    stations = state.stations,
                    selected = selectedFilter,
                    onSelect = { selectedFilter = it },
                )
            }

            if (visibleStations.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    SectionCard(
                        title = "Station workspace",
                        subtitle = "The selected station type has no configured resources.",
                    ) {
                        DesignedEmptyState(
                            title = "No stations in this filter",
                            body = "Choose All or another station type to return to the operational board.",
                            icon = Icons.Filled.SportsEsports,
                        )
                    }
                }
            } else {
                items(visibleStations, key = { it.id }) { station ->
                    val stationSession = state.activeFor(station.id)
                    GamingStationCard(
                        station = station,
                        session = stationSession,
                        packageExtensionAction = stationSession?.let { state.packageExtensionFor(it.id) },
                        wallClock = wallClock,
                        actionInProgress = state.busyStationId != null,
                        busyHere = state.busyStationId == station.id,
                        focused = station.id == focusStationId,
                        canWrite = access.canManageSessions,
                        canReconcileLegacy = access.canReconcileLegacySessions,
                        activeShiftId = state.activeShiftId,
                        activeShiftServerConfirmed = state.activeShiftServerConfirmed,
                        packages = state.packages,
                        hasTransferTarget = state.stations.any { candidate ->
                            candidate.id != station.id && candidate.type == station.type &&
                                candidate.isActive && state.activeFor(candidate.id) == null
                        },
                        onStart = { starting = station },
                        onStop = { session -> stopping = StopRequest(station, session) },
                        onSend = { sending = it },
                        onCancelUnbilled = { cancelling = it },
                        onExtendTimer = { vm.extendTimer(it) },
                        onExtendPackage = { session, extensions ->
                            extendingPackage = PackageExtensionRequest(session, extensions)
                        },
                        onTransfer = { transferring = it },
                        onReconcile = { reconciling = it },
                        onRepairBilling = { repairingBilling = it },
                        onResolveLegacyStart = { resolvingLegacyStart = it },
                        onDiscardPackageExtension = { action ->
                            discardingPackageExtension = PackageExtensionDiscardRequest(action)
                        },
                    )
                }
            }
        }
    }

    starting?.takeIf { access.canManageSessions }?.let { station ->
        StartSessionDialog(
            station = station,
            packages = state.packages.filter { it.stationType == station.type && it.kind == "base" },
            onDismiss = { starting = null },
            onConfirm = { phone, minutes, packageId, extraControllers ->
                starting = null
                vm.start(station, phone, minutes, packageId, extraControllers)
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

    transferring?.takeIf { access.canManageSessions }?.let { session ->
        val source = state.stations.firstOrNull { it.id == session.stationId }
        val targets = state.stations.filter { candidate ->
            source != null && candidate.id != source.id && candidate.type == source.type &&
                candidate.isActive && state.activeFor(candidate.id) == null
        }
        TransferSessionDialog(
            sourceName = source?.name ?: "Gaming station",
            targets = targets,
            onDismiss = { transferring = null },
            onSelect = { target ->
                transferring = null
                vm.transfer(session, target)
            },
        )
    }

    extendingPackage?.takeIf { access.canManageSessions }?.let { request ->
        PackageExtensionDialog(
            extensions = request.extensions,
            extraControllers = request.session.extraControllers,
            onDismiss = { extendingPackage = null },
            onSelect = { extension ->
                extendingPackage = null
                vm.extendWithPackage(request.session, extension)
            },
        )
    }

    reconciling?.takeIf { access.canReconcileLegacySessions }?.let { session ->
        ReconcileSessionDialog(
            stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
                ?: "Gaming session",
            amountMinor = session.amountMinor ?: 0L,
            onDismiss = { reconciling = null },
            onConfirm = { reason ->
                reconciling = null
                vm.reconcileToPos(session, reason)
            },
        )
    }

    repairingBilling?.takeIf { access.canReconcileLegacySessions }?.let { session ->
        RepairSessionBillingDialog(
            stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
                ?: "Gaming session",
            onDismiss = { repairingBilling = null },
            onConfirm = { amountMinor, reason ->
                repairingBilling = null
                vm.repairMissingBilling(session, amountMinor, reason)
            },
        )
    }

    resolvingLegacyStart?.let { session ->
        LegacyPackageResolutionDialog(
            session = session,
            stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
                ?: "Gaming station",
            requiresOwnerStepUp = !access.canReconcileLegacySessions,
            onDismiss = { resolvingLegacyStart = null },
            onConfirm = { resolution, referenceOrderId, reason, ownerEmail, ownerPassword ->
                resolvingLegacyStart = null
                vm.resolveLegacyPackageStart(
                    session = session,
                    resolution = resolution,
                    referenceOrderId = referenceOrderId,
                    reason = reason,
                    ownerEmail = ownerEmail,
                    ownerPassword = ownerPassword,
                )
            },
        )
    }

    discardingPackageExtension?.takeIf { access.canManageSessions }?.let { request ->
        DiscardRejectedExtensionDialog(
            errorDetail = request.action.lastError,
            onDismiss = { discardingPackageExtension = null },
            onConfirm = { reason ->
                discardingPackageExtension = null
                vm.discardRejectedPackageExtension(
                    request.action.actionId,
                    reason,
                )
            },
        )
    }

    if (paymentQueueOpen) {
        GamingQueueDialog(
            title = "Sessions awaiting payment",
            detail = "Send sessions individually so each remains a separate, traceable POS order.",
            sessions = state.readyForPos,
            stations = state.stations,
            activeShiftId = state.activeShiftId,
            actionLabel = { session ->
                when {
                    session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT -> "Send"
                    access.canReconcileLegacySessions && state.activeShiftId != null -> "Reconcile"
                    else -> "Other terminal"
                }
            },
            actionEnabled = { session ->
                session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT ||
                    (access.canReconcileLegacySessions && state.activeShiftId != null)
            },
            actionIntent = ActionIntent.Primary,
            busyStationId = state.busyStationId,
            onDismiss = { paymentQueueOpen = false },
            onSelect = {
                paymentQueueOpen = false
                if (it.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT) {
                    sending = it
                } else {
                    reconciling = it
                }
            },
        )
    }

    if (cancellationQueueOpen) {
        GamingQueueDialog(
            title = "Sessions requiring cancellation",
            detail = "A reason is required and recorded in the audit trail.",
            sessions = state.needsCancellation,
            stations = state.stations,
            activeShiftId = state.activeShiftId,
            actionLabel = { "Review" },
            actionEnabled = { session ->
                session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT ||
                    access.canReconcileLegacySessions
            },
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
    val active = operationalActiveGamingSessionCount(state.sessions)
    val hasPendingStart = state.sessions.any {
        it.status == "starting" && it.localState == GamingSessionState.START_PENDING
    }
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
                detail = if (hasPendingStart) "Running or saved locally" else "Running now",
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
internal fun GamingStationCard(
    station: Station,
    session: GameSession?,
    packageExtensionAction: PackageExtensionActionUi?,
    wallClock: State<Long>,
    actionInProgress: Boolean,
    busyHere: Boolean,
    focused: Boolean,
    canWrite: Boolean,
    canReconcileLegacy: Boolean,
    activeShiftId: String?,
    activeShiftServerConfirmed: Boolean,
    packages: List<GamingPackage>,
    hasTransferTarget: Boolean,
    onStart: () -> Unit,
    onStop: (GameSession) -> Unit,
    onSend: (GameSession) -> Unit,
    onCancelUnbilled: (GameSession) -> Unit,
    onExtendTimer: (GameSession) -> Unit,
    onExtendPackage: (GameSession, List<GamingPackage>) -> Unit,
    onTransfer: (GameSession) -> Unit,
    onReconcile: (GameSession) -> Unit,
    onRepairBilling: (GameSession) -> Unit,
    onResolveLegacyStart: (GameSession) -> Unit,
    onDiscardPackageExtension: (PackageExtensionActionUi) -> Unit,
) {
    // A paused session must look paused. Without an authoritative paused-at
    // field, freezing the local display is safer than inventing elapsed time.
    val shouldTick = session?.status == "active" ||
        (session?.status == "starting" && session.localState == GamingSessionState.START_PENDING)
    // Re-capture when the authoritative status changes. Keying only by id made
    // an active -> paused transition jump back to the instant the card first
    // entered composition instead of freezing when the pause was observed.
    val frozenMillis = remember(session?.id, session?.status) { System.currentTimeMillis() }
    val nowMillis = if (shouldTick) wallClock.value else frozenMillis

    val presentation = stationPresentation(station, session, nowMillis)
    val stationIcon = stationTypeIcon(station.type)
    val actionsEnabled = canWrite && !actionInProgress
    val authority = session?.authority(activeShiftId)
    val ownsSession = authority == GamingSessionAuthority.CURRENT_SHIFT
    val matchingExtensions = matchingPackageExtensions(session, station, packages)
    val alreadySettledAtPos = session?.orderId != null
    val packageBillingSnapshotMissing = session?.isPackageBilling() == true &&
        !session.hasLockedPackageExtensionSnapshot()
    val matchingPackageExtensionUnavailable = session?.isPackageBilling() == true &&
        !packageBillingSnapshotMissing && matchingExtensions.isEmpty()

    val rateDescription = when {
        session == null -> "${station.ratePerHourMinor.asRupees()} per hour"
        session.ratePerHourMinor != null -> "${session.ratePerHourMinor.asRupees()} per hour"
        else -> "Locked session rate unavailable"
    }

    Column(
        Modifier.fillMaxWidth().heightIn(min = 260.dp)
            // Operational state belongs in the badge, icon and copy. Keeping
            // the complete card neutral prevents payment/warning states from
            // turning the board into a field of competing colour blocks.
            .clip(Radius.shapeLg).background(Brand.Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Brand.Gold else Brand.BorderSubtle, Radius.shapeLg)
            .semantics {
                contentDescription = "${station.name}. ${presentation.statusLabel}. " +
                    "$rateDescription."
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
            if (session != null && session.ratePerHourMinor == null) {
                "${stationTypeLabel(station.type)} · Locked rate unavailable"
            } else {
                val displayedRate = session?.ratePerHourMinor ?: station.ratePerHourMinor
                "${stationTypeLabel(station.type)} · ${displayedRate.asRupees()}/hour"
            },
            color = if (session != null && session.ratePerHourMinor == null) Brand.Warning else Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            // Do not weight this variable-height operational content. A
            // weighted child is measured only into the card's minimum-height
            // remainder and silently clipped the start time, running amount,
            // package total and ownership warning on compact tablets.
            Modifier.fillMaxWidth(),
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
                StationBody(presentation, station, session, nowMillis, activeShiftId)
            }
        }

        HorizontalDivider(color = Brand.BorderSubtle)

        if (packageExtensionAction != null && session != null) {
            PackageExtensionBlockingActions(
                action = packageExtensionAction,
                actionsEnabled = actionsEnabled,
                busyHere = busyHere,
                onDiscard = { onDiscardPackageExtension(packageExtensionAction) },
            )
        } else when (presentation.state) {
            StationVisualState.Available -> ErpButton(
                text = when {
                    activeShiftId == null -> "Open POS shift to start"
                    !activeShiftServerConfirmed -> "Waiting for shift sync"
                    else -> "Start session"
                },
                onClick = onStart,
                enabled = actionsEnabled && activeShiftId != null && activeShiftServerConfirmed,
                busy = busyHere,
                // Several available stations may be visible at once. A quiet
                // repeated action keeps Review/Send as the dominant workflow.
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
            )

            StationVisualState.Active,
            StationVisualState.Overtime,
            StationVisualState.Paused,
            StationVisualState.StopFailed,
            -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (alreadySettledAtPos) {
                    Text(
                        "This session is already linked to a paid POS order. Extra paid time is locked; Stop still records the final play time without creating another bill.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else if (packageBillingSnapshotMissing) {
                    Text(
                        "Locked package timer or total is unavailable. Refresh Gaming; if it remains missing, ask the protected owner to review billing before extending.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else if (matchingPackageExtensionUnavailable) {
                    Text(
                        "No active extension matches this session's original package variant. Refresh Gaming or ask a manager to check the package catalogue.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ErpButton(
                        text = if (session?.isPackageBilling() == true) "Extend" else "+30 min",
                        onClick = {
                            session?.let {
                                if (it.isPackageBilling()) onExtendPackage(it, matchingExtensions)
                                else onExtendTimer(it)
                            }
                        },
                        enabled = session != null && actionsEnabled && ownsSession &&
                            !alreadySettledAtPos &&
                            !packageBillingSnapshotMissing &&
                            (!session.isPackageBilling() || matchingExtensions.isNotEmpty()),
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Filled.Add,
                        // Two equal actions share a 246dp card content row on
                        // the 960 x 600dp tablet. Keep the 48dp target and icon
                        // while avoiding Material's wide default button inset.
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                        modifier = Modifier.weight(1f),
                    )
                    ErpButton(
                        text = "Transfer",
                        onClick = { session?.let(onTransfer) },
                        enabled = actionsEnabled && ownsSession && hasTransferTarget,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Filled.SwapHoriz,
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                        modifier = Modifier.weight(1f),
                    )
                }
                ErpButton(
                    text = if (presentation.state == StationVisualState.StopFailed) "Retry stop" else "Stop & calculate",
                    onClick = { session?.let(onStop) },
                    enabled = actionsEnabled && ownsSession,
                    busy = busyHere,
                    intent = ActionIntent.Destructive,
                    leadingIcon = Icons.Filled.StopCircle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            StationVisualState.StartFailed -> {
                val attemptState = session?.legacyResolutionAttemptState
                ErpButton(
                    text = when (attemptState) {
                        GamingLegacyResolutionAttemptState.RESOLVED -> "Billing review retained"
                        GamingLegacyResolutionAttemptState.PENDING,
                        GamingLegacyResolutionAttemptState.AMBIGUOUS,
                        -> "Retry owner audit"
                        GamingLegacyResolutionAttemptState.REJECTED -> "Review rejected resolution"
                        else -> if (!canReconcileLegacy) {
                            "Request owner approval"
                        } else if (session?.hasCapturedLegacyPlayEvidence() == true) {
                            "Owner resolve captured play"
                        } else {
                            "Owner resolve rejected start"
                        }
                    },
                    onClick = { session?.let(onResolveLegacyStart) },
                    enabled = actionsEnabled &&
                        attemptState != GamingLegacyResolutionAttemptState.RESOLVED,
                    busy = busyHere,
                    intent = ActionIntent.Warning,
                    leadingIcon = Icons.Filled.Warning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            StationVisualState.PaymentDue,
            StationVisualState.SendRejected,
            -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = { session?.let(onCancelUnbilled) },
                    enabled = actionsEnabled && session != null &&
                        (ownsSession || canReconcileLegacy),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Danger),
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                    modifier = Modifier.weight(0.8f).heightIn(min = 48.dp),
                ) { Text("Void", maxLines = 1) }
                ErpButton(
                    text = when {
                        ownsSession && presentation.state == StationVisualState.SendRejected -> "Retry send"
                        ownsSession -> "Send to POS"
                        canReconcileLegacy && activeShiftId != null -> "Reconcile to POS"
                        else -> "Other terminal"
                    },
                    onClick = {
                        session?.let { if (ownsSession) onSend(it) else onReconcile(it) }
                    },
                    enabled = actionsEnabled && session != null &&
                        (ownsSession || (canReconcileLegacy && activeShiftId != null)),
                    busy = busyHere,
                    modifier = Modifier.weight(1.7f),
                )
            }

            StationVisualState.CancellationRequired -> ErpButton(
                text = "Cancel with reason",
                onClick = { session?.let(onCancelUnbilled) },
                enabled = actionsEnabled && session != null &&
                    (ownsSession || canReconcileLegacy),
                busy = busyHere,
                intent = ActionIntent.Destructive,
                modifier = Modifier.fillMaxWidth(),
            )

            StationVisualState.BillingMissing -> {
                if (canReconcileLegacy) {
                    ErpButton(
                        text = "Owner repair billing",
                        onClick = { session?.let(onRepairBilling) },
                        enabled = actionsEnabled && session != null,
                        busy = busyHere,
                        intent = ActionIntent.Destructive,
                        leadingIcon = Icons.Filled.Warning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            StationVisualState.Starting -> ErpButton(
                text = "Stop & save end",
                onClick = { session?.let(onStop) },
                enabled = actionsEnabled && ownsSession && session?.canRequestStop() == true,
                busy = busyHere,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.StopCircle,
                modifier = Modifier.fillMaxWidth(),
            )

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
internal fun OrphanPackageExtensionBanner(
    action: PackageExtensionActionUi,
    activeShiftId: String?,
    canWrite: Boolean,
    busy: Boolean,
    onReview: () -> Unit,
) {
    val rejected = action.state == GamingPackageExtensionState.REJECTED
    val hasExactShift = !action.shiftId.isNullOrBlank()
    val ownsShift = hasExactShift && action.shiftId == activeShiftId
    val canResolve = canResolveRejectedExtensionForShift(
        actionShiftId = action.shiftId,
        canWrite = canWrite,
    )
    OperationalBanner(
        title = if (rejected) {
            "Rejected paid extension needs verification"
        } else {
            "Paid extension confirmation retained"
        },
        detail = when {
            !hasExactShift ->
                "This retained extension is missing exact shift provenance and cannot be replayed safely. " +
                    "Keep this tablet signed in and contact support; the possible charge was not removed."
            rejected ->
                "Session ${action.serverSessionId.takeLast(8)} is no longer on this board. " +
                    "Replay the original request before resolving it; a possible charge is never discarded."
            else ->
                "Session ${action.serverSessionId.takeLast(8)} is no longer on this board, but its exact charge request is retained for safe replay."
        },
        tone = if (rejected) UiTone.Danger else UiTone.Warning,
        icon = if (rejected) Icons.Filled.Warning else Icons.Filled.CloudUpload,
    ) {
        if (rejected) {
            ErpButton(
                text = when {
                    ownsShift -> "Verify original attempt"
                    hasExactShift -> "Verify saved attempt"
                    else -> "Exact shift missing"
                },
                onClick = onReview,
                enabled = canResolve && !busy,
                busy = busy,
                intent = ActionIntent.Warning,
                leadingIcon = Icons.Filled.Refresh,
            )
        }
    }
}

@Composable
private fun PackageExtensionBlockingActions(
    action: PackageExtensionActionUi,
    actionsEnabled: Boolean,
    busyHere: Boolean,
    onDiscard: () -> Unit,
) {
    val rejected = action.state == GamingPackageExtensionState.REJECTED
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            when (action.state) {
                GamingPackageExtensionState.AMBIGUOUS ->
                    "The paid extension may already be charged. The original transaction is being confirmed; no other session action is safe yet."
                GamingPackageExtensionState.REJECTED -> action.lastError?.takeIf(String::isNotBlank)
                    ?.let { "The previous request was rejected: $it Verify the original transaction before resolving this blocker." }
                    ?: "The previous request was rejected. Verify the original transaction before resolving this blocker."
                else ->
                    "The paid extension is saved and waiting for server confirmation. Other session actions are temporarily blocked."
            },
            color = if (rejected) Brand.Danger else Brand.Warning,
            style = MaterialTheme.typography.labelSmall,
        )
        ErpButton(
            text = when (action.state) {
                GamingPackageExtensionState.REJECTED -> "Verify rejected extension"
                GamingPackageExtensionState.AMBIGUOUS -> "Confirming original charge"
                else -> "Extension queued"
            },
            onClick = { if (rejected) onDiscard() },
            enabled = actionsEnabled && rejected,
            busy = busyHere || !rejected,
            intent = if (rejected) ActionIntent.Warning else ActionIntent.Secondary,
            leadingIcon = if (rejected) Icons.Filled.Warning else Icons.Filled.CloudUpload,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StationBody(
    presentation: StationPresentation,
    station: Station,
    session: GameSession?,
    nowMillis: Long,
    activeShiftId: String?,
) {
    when (presentation.state) {
        StationVisualState.Available -> {
            Text("Ready for a new session", color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium)
            Text("Start time is saved at the tap and synced safely.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        StationVisualState.Disabled -> {
            Text("Station disabled", color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium)
            Text("Ask a manager to enable it before taking a booking.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        StationVisualState.StartFailed -> {
            Text(
                if (session?.requiresProtectedStartReview() == true) {
                    "Rejected start evidence retained"
                } else {
                    "Start was not accepted"
                },
                color = Brand.Danger,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    session?.legacyResolutionAttemptState == GamingLegacyResolutionAttemptState.RESOLVED ->
                        session.legacyResolutionError?.takeIf(String::isNotBlank)
                            ?: "The server session was recovered, but ordinary billing actions remain locked for audited owner review."
                    session?.legacyResolutionAttemptState == GamingLegacyResolutionAttemptState.AMBIGUOUS ->
                        "The owner decision may already be recorded. Retry the exact saved audit request; do not start a replacement."
                    session?.legacyResolutionAttemptState == GamingLegacyResolutionAttemptState.PENDING ->
                        "The owner decision is saved and awaiting its server audit receipt."
                    session?.legacyResolutionAttemptState == GamingLegacyResolutionAttemptState.REJECTED ->
                        "The server rejected the saved resolution. The protected owner must review and correct it."
                    session?.hasCapturedLegacyPlayEvidence() == true ->
                        "A Stop timestamp proves possible play. The protected owner must link a verified manual bill or confirm no play with a reason."
                    else ->
                        "No Stop was captured, but that does not prove no play. The protected owner must verify the outcome; an audit receipt is required before reuse."
                },
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StationVisualState.Starting,
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
                    } else if (presentation.state == StationVisualState.Starting) {
                        Brand.Warning
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
                    StationVisualState.Starting ->
                        "Saved on this tablet and waiting to sync. Play time and the booked timer use the captured start time."
                    StationVisualState.Overtime -> "Booked time has ended. Stop when play finishes."
                    StationVisualState.Paused -> "Paused on the server. Stop or resolve before reuse."
                    StationVisualState.Stopping -> if (
                        session?.legacyOriginalCapturedStopAt != null &&
                        session.legacyOriginalCapturedStopAt != session.endAt
                    ) {
                        "Original offline Stop is retained in the owner audit. Replay was adjusted to the authoritative server Start time."
                    } else {
                        "Stop is saved; session stays active until confirmed."
                    }
                    StationVisualState.StopFailed -> buildString {
                        append(
                            session?.lastError
                                ?: "Stop was not accepted. Check the connection and retry.",
                        )
                        if (
                            session?.legacyOriginalCapturedStopAt != null &&
                            session.legacyOriginalCapturedStopAt != session.endAt
                        ) {
                            append(
                                " Original offline Stop remains in the owner audit; " +
                                    "the queued replay time stays adjusted to the authoritative server Start.",
                            )
                        }
                    }
                    else -> "Final charge is calculated by the server when stopped."
                },
                color = if (presentation.state in setOf(StationVisualState.Overtime, StationVisualState.StopFailed)) {
                    Brand.Danger
                } else {
                    Brand.ForegroundFaint
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (
                    presentation.state == StationVisualState.StopFailed ||
                    session?.legacyOriginalCapturedStopAt != session?.endAt
                ) 3 else 1,
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
            session?.let { running ->
                val currentAmount = estimatedCurrentAmountMinor(running, nowMillis)
                if (currentAmount != null) {
                    Text(
                        if (running.isPackageBilling()) {
                            "Package total · ${currentAmount.asRupees()}"
                        } else {
                            "Estimated now · ${currentAmount.asRupees()}"
                        },
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                sessionAuthorityMessage(running, activeShiftId)?.let { ownership ->
                    Text(
                        ownership,
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        StationVisualState.PaymentDue,
        StationVisualState.SendPending,
        StationVisualState.SendRejected,
        StationVisualState.CancellationRequired,
        -> {
            NumericValue(
                value = (session?.amountMinor ?: 0L).asRupees(),
                color = if (presentation.state == StationVisualState.CancellationRequired) Brand.Danger else Brand.Foreground,
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
            session?.let { ended ->
                sessionAuthorityMessage(ended, activeShiftId)?.let { ownership ->
                    Text(
                        ownership,
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        StationVisualState.BillingMissing -> {
            Text(
                "Billing data unavailable",
                color = Brand.Danger,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This stopped session has no authoritative amount. It cannot be sent or voided safely.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Refresh Gaming. If the amount remains missing, ask the protected owner to repair the source record.",
                color = Brand.Warning,
                style = MaterialTheme.typography.labelSmall,
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
        return if (session.requiresProtectedStartReview()) {
            StationPresentation(StationVisualState.StartFailed, "Owner review", UiTone.Danger, Icons.Filled.Warning)
        } else {
            StationPresentation(StationVisualState.StartFailed, "Start failed", UiTone.Danger, Icons.Filled.Error)
        }
    }
    if (session.status == "starting") {
        return StationPresentation(
            StationVisualState.Starting,
            "Pending sync",
            UiTone.Warning,
            Icons.Filled.CloudUpload,
        )
    }
    if (session.status == "ended" && session.orderId == null) {
        if (session.amountMinor == null) {
            return StationPresentation(
                StationVisualState.BillingMissing,
                "Billing missing",
                UiTone.Danger,
                Icons.Filled.Error,
            )
        }
        if (session.amountMinor <= 0L) {
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
    StationVisualState.PaymentDue,
    StationVisualState.SendPending,
    StationVisualState.SendRejected,
    -> if (session?.hasUnverifiedLegacyBillingMode() == true) {
        "Older session · billing mode unverified. The server amount is retained and POS excludes package benefits."
    } else when (state) {
        StationVisualState.SendPending -> "POS handoff saved and waiting for confirmation."
        StationVisualState.SendRejected -> session?.lastError?.takeIf(String::isNotBlank)
            ?: "POS refused the handoff. Check the shift and connection, then retry."
        else -> "Send to POS before the next session."
    }
    StationVisualState.CancellationRequired -> "No billable amount. Cancel with a reason before reuse."
    else -> "Review this session before continuing."
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
    activeShiftId: String?,
    actionLabel: (GameSession) -> String,
    actionEnabled: (GameSession) -> Boolean,
    actionIntent: ActionIntent,
    busyStationId: String?,
    onDismiss: () -> Unit,
    onSelect: (GameSession) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        // Never impose a minimum wider than the available tablet content.
        // AlertDialog still uses its platform width on large screens while a
        // small tablet can safely contract the queue instead of clipping it.
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
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
                                sessionAuthorityMessage(session, activeShiftId)?.let { ownership ->
                                        Text(
                                            ownership,
                                            color = Brand.Warning,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                }
                            }
                            ErpButton(
                                text = actionLabel(session),
                                onClick = { onSelect(session) },
                                intent = actionIntent,
                                enabled = busyStationId == null && actionEnabled(session),
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
internal fun CancelUnbilledSessionDialog(
    stationName: String,
    amountMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedReasonId by rememberSaveable(stationName) { mutableStateOf<String?>(null) }
    var customReason by rememberSaveable(stationName) { mutableStateOf("") }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val compactCustomLayout = useCompactVoidCustomLayout(screenHeightDp, selectedReasonId)
    val contentMaxHeight = voidDialogBodyMaxHeight(screenHeightDp, selectedReasonId)
    val normalized = resolvedVoidReason(selectedReasonId, customReason)
    val hasBillableAmount = amountMinor > 0L

    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        // Use the available window instead of the platform's fixed dialog
        // width. This keeps the form inside compact tablet windows while the
        // cap prevents it from becoming an overly wide desktop form.
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth(0.92f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text("Void session · $stationName") },
        text = {
            if (compactCustomLayout) {
                VoidReasonInput(
                    selectedId = selectedReasonId,
                    customReason = customReason,
                    onPresetSelected = { selectedReasonId = it },
                    onCustomReasonChange = { customReason = it },
                    modifier = Modifier.heightIn(max = contentMaxHeight),
                    compactCustomLayout = true,
                    onExitCustomMode = { selectedReasonId = null },
                    compactCustomActions = {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) { Text("Keep session", maxLines = 1) }
                        ErpButton(
                            text = "Void session",
                            onClick = { onConfirm(normalized) },
                            enabled = normalized.isNotEmpty(),
                            intent = ActionIntent.Destructive,
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                            modifier = Modifier.weight(1.15f),
                        )
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = contentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
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
            }
        },
        confirmButton = {
            if (!compactCustomLayout) {
                ErpButton(
                    text = "Void session",
                    onClick = { onConfirm(normalized) },
                    enabled = normalized.isNotEmpty(),
                    intent = ActionIntent.Destructive,
                )
            }
        },
        dismissButton = if (compactCustomLayout) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text("Keep session") } }
        },
    )
}

@Composable
private fun TransferSessionDialog(
    sourceName: String,
    targets: List<Station>,
    onDismiss: () -> Unit,
    onSelect: (Station) -> Unit,
) {
    var selectedId by rememberSaveable(sourceName) { mutableStateOf<String?>(null) }
    val selected = targets.firstOrNull { it.id == selectedId }
    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Transfer · $sourceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Choose an available station of the same type. The original rate, package, timer and shift stay unchanged.",
                    color = Brand.ForegroundMuted,
                )
                if (targets.isEmpty()) {
                    DesignedEmptyState(
                        title = "No transfer target available",
                        body = "Finish or resolve another matching station, then refresh Gaming.",
                        icon = Icons.Filled.SwapHoriz,
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(targets, key = Station::id) { target ->
                            OutlinedButton(
                                onClick = { selectedId = target.id },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selectedId == target.id) Brand.SurfaceHover else Brand.Surface,
                                    contentColor = Brand.Foreground,
                                ),
                            ) {
                                Text("${target.name} · ${target.ratePerHourMinor.asRupees()}/hour")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ErpButton(
                text = "Transfer session",
                onClick = { selected?.let(onSelect) },
                enabled = selected != null,
                leadingIcon = Icons.Filled.SwapHoriz,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PackageExtensionDialog(
    extensions: List<GamingPackage>,
    extraControllers: Int,
    onDismiss: () -> Unit,
    onSelect: (GamingPackage) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = extensions.firstOrNull { it.id == selectedId }
    val selectedSurcharge = selected?.let {
        extraControllerSurchargeMinor(extraControllers, it.durationMinutes)
    } ?: 0L
    val selectedTotal = selected?.let { it.priceMinor + selectedSurcharge }
    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Add paid time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "This adds a priced extension to the package total. It does not replace the existing package.",
                    color = Brand.ForegroundMuted,
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(extensions, key = GamingPackage::id) { extension ->
                        OutlinedButton(
                            onClick = { selectedId = extension.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selectedId == extension.id) Brand.SurfaceHover else Brand.Surface,
                                contentColor = Brand.Foreground,
                            ),
                        ) {
                            val surcharge = extraControllerSurchargeMinor(
                                extraControllers,
                                extension.durationMinutes,
                            )
                            Text(
                                "${extension.name} · ${extension.durationMinutes} min · " +
                                    "${(extension.priceMinor + surcharge).asRupees()} total",
                            )
                        }
                    }
                }
                if (extraControllers > 0) {
                    Text(
                        "Selected extension: ${selected?.priceMinor?.asRupees() ?: "—"} base + " +
                            "${selectedSurcharge.asRupees()} controller surcharge. " +
                            "Each extra controller costs ₹30 per started hour (₹30 minimum).",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            ErpButton(
                text = selectedTotal?.let { "Add · ${it.asRupees()}" } ?: "Choose extension",
                onClick = { selected?.let(onSelect) },
                enabled = selected != null,
                leadingIcon = Icons.Filled.Add,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun LegacyPackageResolutionDialog(
    session: GameSession,
    stationName: String,
    requiresOwnerStepUp: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        resolution: String,
        referenceOrderId: String?,
        reason: String,
        ownerEmail: String?,
        ownerPassword: String?,
    ) -> Unit,
) {
    val noCapturedStop = session.endAt == null
    val exactAttemptLocked = session.legacyResolutionAttemptState in setOf(
        GamingLegacyResolutionAttemptState.PENDING,
        GamingLegacyResolutionAttemptState.AMBIGUOUS,
    )
    var resolution by rememberSaveable(session.id) {
        mutableStateOf(
            session.legacyResolution
                ?: GamingLegacyResolution.SERVER_SESSION_RECOVERED.takeIf {
                    !noCapturedStop
                }.orEmpty(),
        )
    }
    var referenceOrderId by rememberSaveable(session.id) {
        mutableStateOf(session.legacyResolutionReferenceOrderId.orEmpty())
    }
    var reason by rememberSaveable(session.id) {
        mutableStateOf(session.legacyResolutionReason.orEmpty())
    }
    var ownerEmail by rememberSaveable(session.id) { mutableStateOf("") }
    // Passwords must never enter SavedState/Bundle. They live only for this
    // composition and are cleared before dismiss or submit callbacks run.
    var ownerPassword by remember(session.id) { mutableStateOf("") }
    val effectiveReference = referenceOrderId.trim().takeIf {
        resolution == GamingLegacyResolution.MANUAL_BILL_RECORDED && it.isNotEmpty()
    }
    val inputError = legacyResolutionInputError(resolution, effectiveReference, reason)
    val ownerStepUpReady = !requiresOwnerStepUp ||
        (ownerEmail.trim().isNotEmpty() && ownerPassword.isNotEmpty())
    val dismissSecurely = {
        ownerPassword = ""
        onDismiss()
    }

    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = dismissSecurely,
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth(0.94f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text("Resolve rejected gaming start") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                OperationalBanner(
                    title = if (requiresOwnerStepUp) {
                        "Protected-owner approval required"
                    } else {
                        "Protected-owner audit required"
                    },
                    detail = if (session.lastError == LEGACY_PACKAGE_START_REVIEW_ERROR) {
                        "$stationName retained an older package start whose tap-time price cannot be trusted. " +
                            "The tablet could not confirm whether that Start reached the server. " +
                            "Recover the accepted server Start first when play may have happened. Otherwise verify a distinct paid POS order or confirm that play never began. Never bill from the current package price."
                    } else if (noCapturedStop) {
                        "$stationName has no captured Stop timestamp, and the tablet could not confirm whether its saved Start reached the server. " +
                            "Probe for the exact accepted Start if play may have begun. Otherwise explicitly confirm no play or link the distinct paid POS order. " +
                            "The station stays blocked until the server returns an audit receipt."
                    } else {
                        "$stationName retained Start and Stop evidence, but the server rejected the start. " +
                            "Recover the exact accepted server Start so the saved Stop can replay. Use manual bill only for an existing paid order, or confirm no play only after owner verification. The station stays blocked until the server returns an audit receipt."
                    },
                    tone = UiTone.Danger,
                    icon = Icons.Filled.Warning,
                )
                if (requiresOwnerStepUp) {
                    OperationalBanner(
                        title = "Approve without changing accounts",
                        detail = "The staff workspace and saved action stay open. Enter a protected owner's credentials for this one audited decision; they are not saved and will not replace the signed-in staff account.",
                        tone = UiTone.Warning,
                        icon = Icons.Filled.Visibility,
                    )
                    OutlinedTextField(
                        value = ownerEmail,
                        onValueChange = { ownerEmail = it.take(254) },
                        label = { Text("Protected-owner email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Protected owner approval email"
                        },
                    )
                    OutlinedTextField(
                        value = ownerPassword,
                        onValueChange = { ownerPassword = it.take(256) },
                        label = { Text("Protected-owner password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Protected owner approval password"
                        },
                    )
                }
                session.legacyResolutionError?.takeIf(String::isNotBlank)?.let { failure ->
                    Text(
                        "Previous attempt: $failure",
                        color = Brand.Danger,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (exactAttemptLocked) {
                    Text(
                        "The exact saved decision is locked because the previous response may have committed. " +
                            "Retrying uses the same request and cannot create a second receipt. " +
                            if (requiresOwnerStepUp) {
                                "The same protected owner who made the first attempt must approve this retry."
                            } else {
                                ""
                            },
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "Choose one audited recovery path",
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.Foreground,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FilterChip(
                        selected = resolution == GamingLegacyResolution.SERVER_SESSION_RECOVERED,
                        onClick = {
                            resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED
                            referenceOrderId = ""
                        },
                        enabled = !exactAttemptLocked,
                        label = { Text("Recover accepted server start") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = resolution == GamingLegacyResolution.MANUAL_BILL_RECORDED,
                        onClick = {
                            resolution = GamingLegacyResolution.MANUAL_BILL_RECORDED
                        },
                        enabled = !exactAttemptLocked,
                        label = { Text("Manual bill recorded") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = resolution == GamingLegacyResolution.CONFIRMED_NO_PLAY,
                        onClick = {
                            resolution = GamingLegacyResolution.CONFIRMED_NO_PLAY
                            referenceOrderId = ""
                        },
                        enabled = !exactAttemptLocked,
                        label = { Text("Confirmed no play") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                }
                Text(
                    when (resolution) {
                        GamingLegacyResolution.SERVER_SESSION_RECOVERED ->
                            "Use this when play may have happened. It does not create or guess a bill; the tablet restores the session only when the server proves the exact original Start."
                        GamingLegacyResolution.MANUAL_BILL_RECORDED ->
                            "Use only when an exact paid, invoiced POS order already records this session."
                        GamingLegacyResolution.CONFIRMED_NO_PLAY ->
                            "Use only when the owner has verified that play never began. The server will cancel only an eligible untouched Start."
                        else ->
                            "Select the path that matches the retained evidence."
                    },
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (resolution == GamingLegacyResolution.MANUAL_BILL_RECORDED) {
                    OutlinedTextField(
                        value = referenceOrderId,
                        onValueChange = { referenceOrderId = it.take(64) },
                        enabled = !exactAttemptLocked,
                        label = { Text("POS order ID") },
                        supportingText = {
                            Text("Required UUID from the verified non-voided POS order")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Legacy resolution POS order ID"
                        },
                    )
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    enabled = !exactAttemptLocked,
                    label = { Text("Resolution reason") },
                    supportingText = { Text("Required audit explanation · ${reason.length}/500") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Legacy package resolution reason"
                    },
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = when {
                    exactAttemptLocked && requiresOwnerStepUp -> "Owner approve & retry"
                    exactAttemptLocked -> "Retry audit receipt"
                    resolution == GamingLegacyResolution.SERVER_SESSION_RECOVERED &&
                        requiresOwnerStepUp -> "Owner approve & recover"
                    resolution == GamingLegacyResolution.SERVER_SESSION_RECOVERED ->
                        "Recover accepted Start"
                    requiresOwnerStepUp -> "Owner approve & record"
                    else -> "Record resolution"
                },
                onClick = {
                    val approvalEmail = ownerEmail.trim().lowercase().takeIf { requiresOwnerStepUp }
                    val approvalPassword = ownerPassword.takeIf { requiresOwnerStepUp }
                    ownerPassword = ""
                    onConfirm(
                        resolution,
                        effectiveReference,
                        reason.trim(),
                        approvalEmail,
                        approvalPassword,
                    )
                },
                enabled = inputError == null && ownerStepUpReady,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.Warning,
            )
        },
        dismissButton = {
            TextButton(onClick = dismissSecurely) { Text("Keep station blocked") }
        },
    )
}

@Composable
private fun RepairSessionBillingDialog(
    stationName: String,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
) {
    var amountInput by rememberSaveable(stationName) { mutableStateOf("") }
    var reason by rememberSaveable(stationName) { mutableStateOf("") }
    val amountMinor = parseRupeesToMinor(amountInput)
    val normalizedReason = reason.trim()
    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth(0.92f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text("Repair missing billing") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                OperationalBanner(
                    title = "Protected-owner recovery",
                    detail = "$stationName ended without an authoritative amount. Enter the verified bill only after checking the session record. This action is audited and cannot be used to void or send an unknown amount.",
                    tone = UiTone.Danger,
                    icon = Icons.Filled.Warning,
                )
                TouchMoneyEntry(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    label = "Verified bill amount",
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Repair reason") },
                    supportingText = { Text("Required audit explanation · ${reason.length}/500") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Billing repair reason"
                    },
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = amountMinor?.let { "Save ${it.asRupees()}" } ?: "Enter amount",
                onClick = { amountMinor?.let { onConfirm(it, normalizedReason) } },
                enabled = amountMinor != null && normalizedReason.length >= 3,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.Warning,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DiscardRejectedExtensionDialog(
    errorDetail: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    val normalizedReason = reason.trim()
    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth(0.92f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text("Verify rejected extension") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "The app will first replay the exact original request with the same transaction ID. " +
                        "If the server confirms a charge, it is kept and cannot be discarded. Only a definitive refusal plus an exact session check releases this blocker, with your reason retained.",
                    color = Brand.ForegroundMuted,
                )
                errorDetail?.takeIf(String::isNotBlank)?.let {
                    Text("Server reason: $it", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Resolution reason") },
                    supportingText = { Text("Required · 3–500 characters · ${reason.length}/500") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Rejected extension resolution reason"
                    },
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = "Verify original attempt",
                onClick = { onConfirm(normalizedReason) },
                enabled = normalizedReason.length >= 3,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.Warning,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep blocked") } },
    )
}

@Composable
private fun ReconcileSessionDialog(
    stationName: String,
    amountMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by rememberSaveable(stationName) { mutableStateOf("") }
    val normalized = reason.trim()
    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth(0.92f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text("Reconcile to current POS shift") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "$stationName · ${amountMinor.asRupees()}. The original closed shift remains unchanged; " +
                        "a traceable unpaid POS order is created on the current shift.",
                    color = Brand.ForegroundMuted,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Reconciliation reason") },
                    supportingText = { Text("Required for the audit trail · ${reason.length}/500") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Reconciliation reason"
                    },
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = "Reconcile & send",
                onClick = { onConfirm(normalized) },
                enabled = normalized.length >= 3,
                leadingIcon = Icons.AutoMirrored.Filled.Send,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun StartSessionDialog(
    station: Station,
    packages: List<GamingPackage> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String?, Int?, String?, Int) -> Unit,
) {
    val contentMaxHeight = gamingDialogBodyMaxHeight(
        LocalConfiguration.current.screenHeightDp,
    )
    var phone by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf<Int?>(60) }
    var selectedPackageId by rememberSaveable(station.id) { mutableStateOf<String?>(null) }
    var extraControllers by rememberSaveable(station.id) { mutableIntStateOf(0) }
    val selectedPackage = packages.firstOrNull { it.id == selectedPackageId }
    LaunchedEffect(selectedPackageId) {
        if (selectedPackageId == null) extraControllers = 0
    }

    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth(0.92f)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text("Start · ${station.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
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
                        color = Brand.Foreground,
                    )
                }
                Text("Billing option", color = Brand.Foreground, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    item {
                        FilterChip(
                            selected = selectedPackageId == null,
                            onClick = { selectedPackageId = null },
                            label = { Text("Hourly / flexible") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Brand.Surface,
                                labelColor = Brand.ForegroundMuted,
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    items(packages, key = GamingPackage::id) { option ->
                        FilterChip(
                            selected = selectedPackageId == option.id,
                            onClick = { selectedPackageId = option.id },
                            label = { Text("${option.name} · ${option.priceMinor.asRupees()}") },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Brand.Surface,
                                labelColor = Brand.ForegroundMuted,
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (selectedPackage == null) {
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
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                } else {
                    val controllerSurcharge = extraControllerSurchargeMinor(
                        extraControllers,
                        selectedPackage.durationMinutes,
                    )
                    val confirmedTotal = selectedPackage.priceMinor + controllerSurcharge
                    SectionCard(
                        title = selectedPackage.name,
                        subtitle = "${selectedPackage.durationMinutes} minutes · ${confirmedTotal.asRupees()} total",
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Extra controllers", color = Brand.Foreground, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (extraControllers == 0) {
                                        "No controller surcharge."
                                    } else {
                                        "${controllerSurcharge.asRupees()} surcharge · ₹30 per controller per started hour (₹30 minimum)."
                                    },
                                    color = Brand.ForegroundMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                OutlinedButton(
                                    onClick = { extraControllers = (extraControllers - 1).coerceAtLeast(0) },
                                    enabled = extraControllers > 0,
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = "Decrease extra controllers"
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text("−") }
                                NumericValue(
                                    value = extraControllers.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Brand.Foreground,
                                )
                                OutlinedButton(
                                    onClick = { extraControllers = (extraControllers + 1).coerceAtMost(8) },
                                    enabled = extraControllers < 8,
                                    modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = "Increase extra controllers"
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text("+") }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                    label = { Text("Member phone (optional)") },
                    supportingText = { Text("Used to attach the session to an existing member when found.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Member phone (optional)"
                    },
                )
                Text(
                    if (selectedPackage != null) {
                        "Price, duration and controller surcharge are captured now and verified by the server. The timer starts at this tap."
                    } else if (minutes == null) {
                        "The start time is saved at this tap. The server verifies it and calculates the final elapsed-time bill."
                    } else {
                        "The timer and alarm begin at this tap. If the connection drops, the saved start syncs automatically."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
        },
        confirmButton = {
            val startTotal = selectedPackage?.let {
                it.priceMinor + extraControllerSurchargeMinor(extraControllers, it.durationMinutes)
            }
            ErpButton(
                text = startTotal?.let { "Start · ${it.asRupees()}" } ?: "Start session",
                onClick = {
                    onConfirm(
                        phone.takeIf(String::isNotBlank),
                        if (selectedPackage == null) minutes else null,
                        selectedPackage?.id,
                        extraControllers,
                    )
                },
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

internal fun hasTickingGamingSession(sessions: List<GameSession>): Boolean =
    sessions.any {
        it.status == "active" ||
            (it.status == "starting" && it.localState == GamingSessionState.START_PENDING)
    }

/** START_PENDING is operational play, even before the durable start reaches the server. */
internal fun operationalActiveGamingSessionCount(sessions: List<GameSession>): Int =
    sessions.count {
        it.status in setOf("active", "paused", "stopping") ||
            (it.status == "starting" && it.localState == GamingSessionState.START_PENDING)
    }

/** A locally queued Stop owns an immutable tap timestamp; never keep its visible clock running. */
internal fun elapsedMillis(session: GameSession, nowMillis: Long): Long = runCatching {
    val effectiveEnd = if (session.status == "stopping") {
        session.endAt?.let { Instant.parse(it).toEpochMilli() } ?: nowMillis
    } else {
        nowMillis
    }
    (effectiveEnd - Instant.parse(session.startAt).toEpochMilli()).coerceAtLeast(0L)
}.getOrDefault(0L)

/** Mirrors the fixed backend package surcharge used for both starts and paid extensions. */
internal fun extraControllerSurchargeMinor(extraControllers: Int, durationMinutes: Int): Long {
    if (extraControllers <= 0 || durationMinutes <= 0) return 0L
    val startedHours = (durationMinutes + 59L) / 60L
    return extraControllers.toLong() * startedHours * 3_000L
}

internal fun matchingPackageExtensions(
    session: GameSession?,
    station: Station,
    packages: List<GamingPackage>,
): List<GamingPackage> {
    val lockedVariant = session?.packageVariantSnapshot?.takeIf(String::isNotBlank)
        ?: return emptyList()
    val lockedStationType = session.packageStationTypeSnapshot?.takeIf(String::isNotBlank)
        ?: return emptyList()
    return packages.filter {
        it.kind == "extension" && it.stationType == station.type &&
            it.stationType == lockedStationType && it.variant == lockedVariant
    }
}

/** Mirrors backend minute-ceiling and minor-unit rounding for a labelled estimate. */
internal fun estimatedCurrentAmountMinor(session: GameSession, nowMillis: Long): Long? {
    if (session.isPackageBilling()) return session.amountMinor
    if (session.status == "paused") return null // no authoritative paused-at snapshot on Android
    val rate = session.ratePerHourMinor ?: return null
    val effectiveNow = if (session.status == "stopping") {
        session.endAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: nowMillis
    } else {
        nowMillis
    }
    val start = runCatching { Instant.parse(session.startAt).toEpochMilli() }.getOrNull() ?: return null
    val elapsedMillis = (effectiveNow - start).coerceAtLeast(0L)
    val billableMinutes = if (elapsedMillis == 0L) 0L else (elapsedMillis + 59_999L) / 60_000L
    if (billableMinutes == 0L || rate <= 0L) return 0L
    return (billableMinutes * rate + 59L) / 60L
}

internal fun sessionAuthorityMessage(session: GameSession, activeShiftId: String?): String? =
    when (session.authority(activeShiftId)) {
        GamingSessionAuthority.CURRENT_SHIFT -> null
        GamingSessionAuthority.NO_OPEN_SHIFT -> "Open this terminal's POS shift to manage this session."
        GamingSessionAuthority.OTHER_SHIFT -> "Managed by the POS shift or terminal that started it."
        GamingSessionAuthority.UNKNOWN -> "Session ownership is not verified yet. Refresh Gaming."
    }

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun sessionWord(count: Int): String = if (count == 1) "session" else "sessions"
