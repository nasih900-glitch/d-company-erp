package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.ui.screens.shift.InlineShiftOpeningWorkspace
import cloud.dcompany.erp.ui.screens.shift.ShiftBlockerActions

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwapHoriz
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cloud.dcompany.erp.core.auth.GAMING_SHIFT_ACCESS_REQUIRED_MESSAGE
import cloud.dcompany.erp.R
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingLegacyResolutionAttemptState
import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.GamingCleanupWorkflowStatus
import cloud.dcompany.erp.core.db.GamingSessionAddonActionState
import cloud.dcompany.erp.core.db.GamingSessionAddonActionType
import cloud.dcompany.erp.core.db.GamingSessionActionState
import cloud.dcompany.erp.core.db.GamingSessionActionType
import cloud.dcompany.erp.core.db.LocalGamingSessionActionEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.LEGACY_PACKAGE_START_REVIEW_ERROR
import cloud.dcompany.erp.core.db.gamingCleanupWorkflowMessageOrNull
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.MetricCard
import cloud.dcompany.erp.ui.components.NumericValue
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TouchMoneyEntry
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.VOID_REASON_COMPACT_EDITOR_HEIGHT
import cloud.dcompany.erp.ui.components.VOID_REASON_OTHER_ID
import cloud.dcompany.erp.ui.components.VoidReasonInput
import cloud.dcompany.erp.ui.components.resolvedVoidReason
import cloud.dcompany.erp.ui.screens.ProductConfigurationDialog
import cloud.dcompany.erp.ui.screens.CartModifierSelection
import cloud.dcompany.erp.ui.screens.configuredUnitPriceMinor
import cloud.dcompany.erp.ui.screens.businessClockTime
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.remote.RemoteSensitiveContent
import java.time.Instant
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

internal data class StationFilter(val id: String, val label: String)

private val GAMING_INLINE_EDITOR_STATES = setOf(
    StationVisualState.Starting,
    StationVisualState.Active,
    StationVisualState.Overtime,
    StationVisualState.Paused,
    StationVisualState.StopFailed,
)

internal fun gamingInlineEditorAvailable(state: StationVisualState?): Boolean =
    state in GAMING_INLINE_EDITOR_STATES

internal fun gamingInlineStopVisible(
    state: StationVisualState?,
    canRequestStop: Boolean,
): Boolean = canRequestStop && gamingInlineEditorAvailable(state)

private val GamingPrimaryActionSupportHeight = 36.dp

/**
 * Reserves the same space below Start/Stop in every selected-station state.
 * Guidance may occupy it, but opening an inline editor or gaining pause
 * capability must not move the primary action under the operator's finger.
 */
@Composable
private fun GamingPrimaryActionSupportArea(
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier.fillMaxWidth().height(GamingPrimaryActionSupportHeight),
        contentAlignment = Alignment.TopStart,
    ) {
        content?.invoke()
    }
}

private data class StopRequest(val station: Station, val session: GameSession)
private data class PackageExtensionRequest(
    val session: GameSession,
    val extensions: List<GamingPackage>,
)

internal data class PackageExtensionReview(
    val station: Station,
    val session: GameSession,
    val extension: GamingPackage,
)

private data class InlinePackageExtensionPreview(
    val station: Station,
    val session: GameSession,
    val extensions: List<GamingPackage>,
    val selectedPackageId: String,
) {
    fun review(): PackageExtensionReview? = extensions.firstOrNull {
        it.id == selectedPackageId
    }?.let { PackageExtensionReview(station, session, it) }
}

private data class PackageExtensionDiscardRequest(
    val action: PackageExtensionActionUi,
)

private data class AddonConfigurationRequest(
    val session: GameSession,
    val item: MenuItemEntity,
    val qty: Int = 1,
)

internal data class AddonReviewRequest(
    val session: GameSession,
    val stationName: String,
    val item: MenuItemEntity,
    val variant: cloud.dcompany.erp.core.db.MenuVariantEntity?,
    val modifiers: List<CartModifierSelection>,
    val note: String?,
    val qty: Int,
)

private data class AddonVoidRequest(
    val session: GameSession,
    val addon: GamingSessionAddonUi,
)

private data class ParticipantJoinReview(
    val customerId: String?,
    val name: String?,
    val phone: String?,
    val participantIdentityId: String? = null,
)

/**
 * Keeps fixed AlertDialog title/footer actions outside system navigation on
 * compact landscape windows while allowing the operational form body to
 * scroll. Taller windows retain the original, intentionally dense 440dp cap.
 */
internal fun gamingDialogBodyMaxHeight(screenHeightDp: Int) =
    (screenHeightDp.dp * 0.55f).coerceAtMost(440.dp)

/**
 * Android dialog/IME inset dispatch varies by OEM and can cover or displace a
 * footer even on a tall tablet. The one state that opens a keyboard therefore
 * always moves its recovery and confirmation actions into a bounded body.
 * Preset reasons keep the standard dialog layout.
 */
internal fun useCompactVoidCustomLayout(selectedReasonId: String?) =
    selectedReasonId == VOID_REASON_OTHER_ID

internal fun voidDialogBodyMaxHeight(
    screenHeightDp: Int,
    selectedReasonId: String?,
) = if (useCompactVoidCustomLayout(selectedReasonId)) {
    VOID_REASON_COMPACT_EDITOR_HEIGHT.coerceAtMost(gamingDialogBodyMaxHeight(screenHeightDp))
} else {
    gamingDialogBodyMaxHeight(screenHeightDp)
}

/**
 * The command workspace needs enough room for a useful station floor and a
 * persistent action panel at the same time. Smaller landscape tablets retain
 * the proven single-board card flow instead of squeezing two unusable panes
 * beside one another.
 */
internal fun useGamingCommandWorkspace(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= 980 && heightDp >= 600

/** Keeps selection deterministic across filtering and station refreshes. */
internal fun resolveGamingCommandStationId(
    visibleStationIds: List<String>,
    currentStationId: String?,
): String? = currentStationId?.takeIf(visibleStationIds::contains)
    ?: visibleStationIds.firstOrNull()

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

internal fun legacyRecoveryVisibleBounds(
    visibleFrame: IntRect,
    rootOriginX: Int,
    rootOriginY: Int,
    rootWidth: Int,
    rootHeight: Int,
): IntRect? {
    if (rootWidth <= 0 || rootHeight <= 0) return null
    val left = (visibleFrame.left - rootOriginX).coerceAtLeast(0)
    val top = (visibleFrame.top - rootOriginY).coerceAtLeast(0)
    val right = (visibleFrame.right - rootOriginX).coerceAtMost(rootWidth)
    val bottom = (visibleFrame.bottom - rootOriginY).coerceAtMost(rootHeight)
    return IntRect(left, top, right, bottom).takeIf { it.width > 0 && it.height > 0 }
}

internal class LegacyRecoveryBoundsState {
    var visibleBounds by mutableStateOf<IntRect?>(null)
        private set

    /** Refresh geometry without blocking the frame that lets Compose and the IME advance. */
    fun refreshBeforeDraw(sampledBounds: IntRect?): Boolean {
        if (visibleBounds != sampledBounds) visibleBounds = sampledBounds
        return true
    }
}

@Composable
fun GamingScreen(
    access: GamingAccess = GamingAccess(),
    shiftAccess: ShiftAccess = ShiftAccess(),
    focusSessionId: String? = null,
    focusStationId: String? = null,
    onDismissFocus: () -> Unit = {},
    vm: GamingViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeTerminal by vm.activeTerminal.collectAsStateWithLifecycle()
    val posTargetSelection by vm.posTargetSelection.collectAsStateWithLifecycle()
    val noticeHostState = remember { SnackbarHostState() }
    SideEffect { vm.updateAccess(access) }

    LaunchedEffect(state.notice) {
        val message = state.notice ?: return@LaunchedEffect
        try {
            noticeHostState.currentSnackbarData?.dismiss()
            noticeHostState.showSnackbar(
                message = message,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short,
            )
        } finally {
            // Navigation cancels this effect. Consume only the message this
            // effect owned so it cannot replay later or erase a newer result.
            vm.dismissNotice(message)
        }
    }

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
    var pausing by remember { mutableStateOf<GameSession?>(null) }
    var extendingPackage by remember { mutableStateOf<PackageExtensionRequest?>(null) }
    var reconciling by remember { mutableStateOf<GameSession?>(null) }
    var repairingBilling by remember { mutableStateOf<GameSession?>(null) }
    var resolvingLegacyStart by remember { mutableStateOf<GameSession?>(null) }
    var discardingPackageExtension by remember { mutableStateOf<PackageExtensionDiscardRequest?>(null) }
    var choosingAddonFor by remember { mutableStateOf<GameSession?>(null) }
    var configuringAddon by remember { mutableStateOf<AddonConfigurationRequest?>(null) }
    var reviewingAddon by remember { mutableStateOf<AddonReviewRequest?>(null) }
    var voidingAddon by remember { mutableStateOf<AddonVoidRequest?>(null) }
    var reviewingRejectedAddon by remember { mutableStateOf<SessionAddonActionUi?>(null) }
    var managingParticipants by remember { mutableStateOf<GameSession?>(null) }
    var paymentQueueOpen by rememberSaveable { mutableStateOf(false) }
    var cancellationQueueOpen by rememberSaveable { mutableStateOf(false) }
    var attentionCenterOpen by rememberSaveable { mutableStateOf(false) }
    var selectedCommandStationId by rememberSaveable { mutableStateOf(focusStationId) }
    var commandDetailOpen by rememberSaveable { mutableStateOf(focusStationId != null) }

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
    LaunchedEffect(visibleStations, selectedCommandStationId) {
        selectedCommandStationId = resolveGamingCommandStationId(
            visibleStationIds = visibleStations.map(Station::id),
            currentStationId = selectedCommandStationId,
        )
    }
    LaunchedEffect(focusStationId, state.stations) {
        if (focusStationId != null && state.stations.any { it.id == focusStationId }) {
            selectedCommandStationId = focusStationId
            commandDetailOpen = true
        }
    }
    val orphanedExtensionActions = state.orphanedPackageExtensionActions()
    // Read-only viewers cannot start a session. Setup guidance therefore
    // belongs only beside the write controls it can actually unblock.
    val startTerminalBlockMessage = if (access.canManageSessions) {
        gamingStartTerminalBlockMessage(activeTerminal?.purpose)
    } else {
        null
    }
    val gridState = rememberLazyGridState()
    val gridHeaderCount = 3 + // alarm, metrics, filters
        (if (!access.canManageSessions) 1 else 0) +
        (if (startTerminalBlockMessage != null) 1 else 0) +
        (if (state.cleanupAcknowledgementsPending.isNotEmpty()) 1 else 0) +
        (if (focusSessionId != null && focusStationId != null) 1 else 0) +
        (if (state.refreshError != null) 1 else 0) +
        orphanedExtensionActions.size +
        (if (state.needsCancellation.isNotEmpty()) 1 else 0) +
        (if (state.readyForPos.isNotEmpty()) 1 else 0)
    val focusIndex = focusStationId?.let { id -> visibleStations.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    LaunchedEffect(focusStationId, focusIndex, gridHeaderCount) {
        if (focusIndex != null) gridState.animateScrollToItem(gridHeaderCount + focusIndex)
    }
    val commandAttentionCount = gamingCommandAttentionCount(
        canManageSessions = access.canManageSessions,
        terminalBlocked = startTerminalBlockMessage != null,
        focusRequested = focusSessionId != null && focusStationId != null,
        hasRefreshError = state.refreshError != null,
        orphanedExtensionCount = orphanedExtensionActions.size,
        rejectedSessionCount = state.rejectedSessionsForReview.size,
        needsCancellation = state.needsCancellation.isNotEmpty(),
        awaitingPayment = state.readyForPos.isNotEmpty(),
        busy = state.busyStationId != null,
    )
    LaunchedEffect(commandAttentionCount) {
        if (commandAttentionCount == 0) attentionCenterOpen = false
    }

    Box(Modifier.fillMaxSize()) {
        if (state.stations.isEmpty() && orphanedExtensionActions.isEmpty() &&
            state.cleanupAcknowledgementsPending.isEmpty()
        ) {
            GamingEmptyState(state = state, onRefresh = vm::load)
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 700.dp) {
                GamingCommandWorkspace(
                    state = state,
                    access = access,
                    shiftAccess = shiftAccess,
                    filters = filters,
                    selectedFilter = selectedFilter,
                    visibleStations = visibleStations,
                    selectedStationId = selectedCommandStationId,
                    showDetail = commandDetailOpen,
                    focusStationId = focusStationId,
                    focusRequested = focusSessionId != null && focusStationId != null,
                    startTerminalBlockMessage = startTerminalBlockMessage,
                    wallClock = wallClock,
                    attentionCount = commandAttentionCount,
                    onSelectFilter = {
                        selectedFilter = it
                        commandDetailOpen = false
                    },
                    onSelectStation = {
                        selectedCommandStationId = it
                        commandDetailOpen = true
                    },
                    onBackToBoard = { commandDetailOpen = false },
                    onOpenAttention = { attentionCenterOpen = true },
                    onRefresh = vm::load,
                    onStart = {
                        vm.refreshCustomersForStart()
                        starting = it
                    },
                    onConfirmStart = { station, customerId, name, phone, minutes, packageId, extraControllers ->
                        vm.start(station, customerId, name, phone, minutes, packageId, extraControllers)
                    },
                    onPrepareStart = vm::refreshCustomersForStart,
                    onSearchCustomer = vm::searchCustomersForStart,
                    onStop = { station, session -> stopping = StopRequest(station, session) },
                    onSend = { sending = it },
                    onCancelUnbilled = { cancelling = it },
                    onExtendTimer = vm::extendTimer,
                    onExtendPackage = { session, extensions ->
                        extendingPackage = PackageExtensionRequest(session, extensions)
                    },
                    onConfirmExtension = { review ->
                        val currentState = vm.state.value
                        val reviewError = packageExtensionReviewError(review, currentState, access)
                        if (reviewError != null) {
                            reviewError
                        } else {
                            val currentSession = currentState.sessions.first { it.id == review.session.id }
                            val currentExtension = currentState.packages.first { it.id == review.extension.id }
                            vm.extendWithPackage(currentSession, currentExtension)
                            null
                        }
                    },
                    onTransfer = { transferring = it },
                    onPauseResume = { if (it.status == "paused") vm.resumeSession(it) else pausing = it },
                    onReconcile = { reconciling = it },
                    onRepairBilling = { repairingBilling = it },
                    onResolveLegacyStart = { resolvingLegacyStart = it },
                    onDiscardPackageExtension = { action ->
                        discardingPackageExtension = PackageExtensionDiscardRequest(action)
                    },
                    onAddItems = { choosingAddonFor = it },
                    onQuickAddItem = { session, item, qty ->
                        vm.addSessionAddon(
                            session = session,
                            item = item,
                            variant = null,
                            modifiers = emptyList(),
                            note = null,
                            qty = qty,
                        )
                    },
                    onConfigureItem = { session, item ->
                        configuringAddon = AddonConfigurationRequest(session, item)
                    },
                    onVoidAddon = { session, addon ->
                        voidingAddon = AddonVoidRequest(session, addon)
                    },
                    onReviewRejectedAddon = { actionId ->
                        reviewingRejectedAddon = state.sessionAddonActions.firstOrNull {
                            it.actionId == actionId
                        }
                    },
                    onManageParticipants = {
                        vm.refreshCustomersForStart()
                    },
                    onJoinParticipant = { session, customerId, name, phone, identityId ->
                        vm.joinParticipant(session, customerId, name, phone, identityId)
                    },
                    onLeaveParticipant = vm::leaveParticipant,
                )
                return@BoxWithConstraints
            }
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
                    ViewOnlyNotice(
                        if (access.writeBlockedByMissingShiftRead) {
                            GAMING_SHIFT_ACCESS_REQUIRED_MESSAGE
                        } else {
                            VIEW_ONLY_MESSAGE
                        },
                    )
                }
            }
            startTerminalBlockMessage?.let { message ->
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OperationalBanner(
                        title = if (activeTerminal?.purpose == TerminalPurpose.CAFE_POS) {
                            "Gaming starts are disabled on a POS-only till"
                        } else {
                            "Terminal purpose needs verification"
                        },
                        detail = message,
                        tone = UiTone.Warning,
                        icon = Icons.Filled.Warning,
                    )
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                GamingAlarmPermissionCard()
            }

            if (state.cleanupAcknowledgementsPending.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    GamingCleanupAcknowledgementBanner(state, vm::load)
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
                val total = state.readyForPos.sumOf(state::pendingBillSnapshotMinor)
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    OperationalBanner(
                        title = "${state.readyForPos.size} ${sessionWord(state.readyForPos.size)} awaiting payment",
                        detail = "Estimated ${total.asRupees()} combined · POS confirms each final total",
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
                        sessionAddons = stationSession?.let(state::addonsFor).orEmpty(),
                        wallClock = wallClock,
                        actionInProgress = state.busyStationId != null,
                        busyHere = state.busyStationId == station.id,
                        focused = station.id == focusStationId,
                        canWrite = access.canManageSessions,
                        canReconcileLegacy = access.canReconcileLegacySessions,
                        activeShiftId = state.activeShiftId,
                        activeShiftServerConfirmed = state.activeShiftServerConfirmed,
                        activeShiftAllowsQueuedStart = state.activeShiftAllowsQueuedStart,
                        startTerminalBlockMessage = startTerminalBlockMessage,
                        online = state.online,
                        packages = state.packages,
                        hasTransferTarget = state.stations.any { candidate ->
                            candidate.id != station.id && candidate.type == station.type &&
                                candidate.isActive && state.activeFor(candidate.id) == null
                        },
                        onStart = {
                            vm.refreshCustomersForStart()
                            starting = station
                        },
                        onStop = { session -> stopping = StopRequest(station, session) },
                        onSend = { sending = it },
                        onCancelUnbilled = { cancelling = it },
                        onExtendTimer = { vm.extendTimer(it) },
                        onExtendPackage = { session, extensions ->
                            extendingPackage = PackageExtensionRequest(session, extensions)
                        },
                        onTransfer = { transferring = it },
                        onPauseResume = { if (it.status == "paused") vm.resumeSession(it) else pausing = it },
                        onReconcile = { reconciling = it },
                        onRepairBilling = { repairingBilling = it },
                        onResolveLegacyStart = { resolvingLegacyStart = it },
                        onDiscardPackageExtension = { action ->
                            discardingPackageExtension = PackageExtensionDiscardRequest(action)
                        },
                        onAddItems = { choosingAddonFor = it },
                        onVoidAddon = { session, addon ->
                            voidingAddon = AddonVoidRequest(session, addon)
                        },
                        onReviewRejectedAddon = { actionId ->
                            reviewingRejectedAddon = state.sessionAddonActions.firstOrNull {
                                it.actionId == actionId
                            }
                        },
                    onManageParticipants = {
                        android.util.Log.d("Code304Trial", "manage-click-grid")
                        vm.refreshCustomersForStart()
                        managingParticipants = it
                    },
                    )
                }
            }
            }
        }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            state.busyStationId?.let { stationId ->
                GamingSavingOverlay(
                    stationName = state.stations.firstOrNull { it.id == stationId }?.name
                        ?: "Gaming station",
                )
            }
            SnackbarHost(hostState = noticeHostState)
        }
    }

    managingParticipants?.let { selected ->
        android.util.Log.d("Code304Trial", "manage-dialog-compose")
        val current = state.sessions.firstOrNull { it.id == selected.id } ?: selected
        val target30 = state.packages.firstOrNull { candidate ->
            candidate.kind == "base" && candidate.stationType == current.packageStationTypeSnapshot &&
                candidate.pricingTier == current.packagePricingTierSnapshot &&
                candidate.variant == current.packageVariantSnapshot && candidate.durationMinutes == 30
        }
        GamingParticipantsDialog(
            session = current,
            participants = state.participantsFor(current),
            rejectedAction = state.unresolvedSequencedActionsFor(current)
                .firstOrNull { it.state == GamingSessionActionState.REJECTED },
            customers = state.customers,
            online = state.online,
            amendTarget = target30,
            onSearchCustomer = vm::searchCustomersForStart,
            onJoin = { customerId, name, phone, identityId ->
                vm.joinParticipant(current, customerId, name, phone, identityId)
            },
            onLeave = { reference -> vm.leaveParticipant(current, reference) },
            onAmend = { target -> vm.amendPackage(current, target) },
            onDiscardRejected = { actionId -> vm.discardRejectedSessionAction(current, actionId) },
            onDismiss = { managingParticipants = null },
        )
    }

    if (attentionCenterOpen) {
        RemoteSensitiveContent(
            enabled = state.rejectedSessionsForReview.isNotEmpty() ||
                state.needsCancellation.isNotEmpty() || state.readyForPos.isNotEmpty(),
        ) {
            GamingCommandAttentionDialog(
                state = state,
                access = access,
                activeTerminalPurpose = activeTerminal?.purpose,
                startTerminalBlockMessage = startTerminalBlockMessage,
                focusSessionId = focusSessionId,
                focusStationId = focusStationId,
                orphanedExtensionActions = orphanedExtensionActions,
                onDismiss = { attentionCenterOpen = false },
                onDismissFocus = onDismissFocus,
                onRefresh = vm::load,
                onReviewOrphan = { action ->
                    attentionCenterOpen = false
                    discardingPackageExtension = PackageExtensionDiscardRequest(action)
                },
                onReviewRejectedSession = { session ->
                    val stationId = rejectedSessionReviewStationId(
                        session = session,
                        stationIds = state.stations.map(Station::id),
                    )
                    if (stationId != null) {
                        attentionCenterOpen = false
                        selectedFilter = "all"
                        selectedCommandStationId = stationId
                        commandDetailOpen = true
                    }
                },
                onReviewCancellations = {
                    attentionCenterOpen = false
                    cancellationQueueOpen = true
                },
                onReviewPayments = {
                    attentionCenterOpen = false
                    paymentQueueOpen = true
                },
            )
        }
    }

    starting?.takeIf { access.canManageSessions }?.let { station ->
        var inlineShiftOpen by rememberSaveable(station.id) { mutableStateOf(false) }
        val shiftBlocksStart = state.activeShiftId == null ||
            (!state.activeShiftServerConfirmed && !state.activeShiftAllowsQueuedStart)
        RemoteSensitiveContent {
            StartSessionDialog(
                station = station,
                packages = state.packages.filter { it.stationType == station.type && it.kind == "base" },
                customers = state.customers,
                online = state.online,
                startEnabled = !shiftBlocksStart && startTerminalBlockMessage == null,
                startBlockMessage = if (shiftBlocksStart) "Open and confirm this till's shift before starting Gaming." else startTerminalBlockMessage,
                blockerActions = {
                    if (inlineShiftOpen) InlineShiftOpeningWorkspace(shiftAccess, onReturn = { inlineShiftOpen = false })
                    else if (shiftBlocksStart) ShiftBlockerActions(shiftAccess.canOpen && startTerminalBlockMessage == null,
                        state.activeShiftId == null, { inlineShiftOpen = true }, vm::load)
                },
                onSearchCustomer = vm::searchCustomersForStart,
                onDismiss = { starting = null },
                onConfirm = { customerId, name, phone, minutes, packageId, extraControllers ->
                    starting = null
                    vm.start(station, customerId, name, phone, minutes, packageId, extraControllers)
                },
            )
        }
    }

    choosingAddonFor?.takeIf { access.canManageSessions }?.let { session ->
        GamingAddonPickerDialog(
            items = state.addonCatalog,
            onDismiss = { choosingAddonFor = null },
            onSelect = { item ->
                choosingAddonFor = null
                configuringAddon = AddonConfigurationRequest(session, item)
            },
        )
    }

    configuringAddon?.takeIf { access.canManageSessions }?.let { request ->
        RemoteSensitiveContent {
            ProductConfigurationDialog(
                item = request.item,
                variants = state.addonVariants.filter { it.menuItemId == request.item.id },
                modifierGroups = state.addonModifierGroups.filter { it.menuItemId == request.item.id },
                modifiers = state.addonModifiers.filter { it.menuItemId == request.item.id },
                onDismiss = { configuringAddon = null },
                onAdd = { variant, modifiers, note ->
                    configuringAddon = null
                    reviewingAddon = AddonReviewRequest(
                        session = request.session,
                        stationName = state.stations.firstOrNull { it.id == request.session.stationId }?.name
                            ?: "Gaming station",
                        item = request.item,
                        variant = variant,
                        modifiers = modifiers,
                        note = note,
                        qty = request.qty,
                    )
                },
            )
        }
    }

    reviewingAddon?.takeIf { access.canManageSessions }?.let { request ->
        GamingAddonReviewDialog(
            request = request,
            onDismiss = { reviewingAddon = null },
            onConfirm = {
                reviewingAddon = null
                vm.addSessionAddon(
                    session = request.session,
                    item = request.item,
                    variant = request.variant,
                    modifiers = request.modifiers,
                    note = request.note,
                    qty = request.qty,
                )
            },
        )
    }

    voidingAddon?.takeIf { access.canManageSessions }?.let { request ->
        RemoteSensitiveContent {
        GamingAddonReasonDialog(
            title = "Void ${request.addon.menuItemName}?",
            detail = "The original line remains visible for audit. It will be excluded from the POS bill only after the server confirms this reason.",
            confirmLabel = "Void item",
            onDismiss = { voidingAddon = null },
            onConfirm = { reason ->
                voidingAddon = null
                vm.voidSessionAddon(request.session, request.addon, reason)
            },
        )
        }
    }

    reviewingRejectedAddon?.takeIf { access.canManageSessions }?.let { action ->
        val isVoid = action.actionType == GamingSessionAddonActionType.VOID
        RemoteSensitiveContent {
        GamingAddonReasonDialog(
            title = if (isVoid) "Review refused item void" else "Review refused item Add",
            detail = buildString {
                append(action.lastError ?: "The server definitively refused this saved action.")
                if (isVoid) {
                    append(" Acknowledging does not void the item; it remains billable. Retry Void afterwards if it should still be removed.")
                } else {
                    append(" The server did not add this item. Acknowledging releases the rejected local attempt without replaying it.")
                }
            },
            confirmLabel = if (isVoid) "Keep item & acknowledge" else "Acknowledge refusal",
            onDismiss = { reviewingRejectedAddon = null },
            onConfirm = { reason ->
                reviewingRejectedAddon = null
                vm.discardRejectedSessionAddonAction(action.actionId, reason)
            },
        )
        }
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
        val pendingBillSnapshotMinor = state.pendingBillSnapshotMinor(session)
        RemoteSensitiveContent {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = Radius.shapeLg,
                onDismissRequest = { sending = null },
                title = { Text("Send to POS?") },
                text = {
                    Text(
                        "$stationName · ${session.billableMinutes ?: 0} minutes · " +
                            "estimated combined bill ${pendingBillSnapshotMinor.asRupees()}. " +
                            "POS will confirm the final total and receive a separate unpaid order " +
                            "for the cashier to review and collect. " +
                            if (activeTerminal?.purpose == TerminalPurpose.GAMING) {
                                "You will choose the receiving POS shift next."
                            } else {
                                "It will stay on the current open shift."
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }

    posTargetSelection?.takeIf { access.canManageSessions }?.let { selection ->
        RemoteSensitiveContent {
            PosTargetShiftDialog(
                selection = selection,
                stationName = state.stations.firstOrNull { it.id == selection.session.stationId }?.name
                    ?: "Gaming session",
                pendingBillSnapshotMinor = state.pendingBillSnapshotMinor(selection.session),
                busy = state.busyStationId == selection.session.stationId,
                onDismiss = vm::dismissPosTargetSelection,
                onConfirm = vm::handoffToPos,
            )
        }
    }

    cancelling?.takeIf { access.canManageSessions }?.let { session ->
        val stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
            ?: "Gaming session"
        RemoteSensitiveContent {
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
    }

    pausing?.takeIf { access.canManageSessions }?.let { session ->
        RemoteSensitiveContent(enabled = true) {
            PauseSessionDialog(
                onDismiss = { pausing = null },
                onPause = { reason ->
                    pausing = null
                    vm.pauseSession(session, reason)
                },
            )
        }
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
            packages = state.packages,
            onDismiss = { transferring = null },
            onSelect = { target ->
                transferring = null
                vm.transfer(session, target)
            },
        )
    }

    extendingPackage?.takeIf { access.canManageSessions }?.let { request ->
        RemoteSensitiveContent {
            PackageExtensionDialog(
                extensions = request.extensions,
                extraControllers = request.session.extraControllers,
                currentDurationMinutes = request.session.timerMinutes ?: 0,
                onDismiss = { extendingPackage = null },
                onSelect = { extension ->
                    extendingPackage = null
                    vm.extendWithPackage(request.session, extension)
                },
            )
        }
    }

    reconciling?.takeIf { access.canReconcileLegacySessions }?.let { session ->
        RemoteSensitiveContent {
        ReconcileSessionDialog(
            stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
                ?: "Gaming session",
            pendingBillSnapshotMinor = state.pendingBillSnapshotMinor(session),
            onDismiss = { reconciling = null },
            onConfirm = { reason ->
                reconciling = null
                vm.reconcileToPos(session, reason)
            },
        )
        }
    }

    repairingBilling?.takeIf { access.canReconcileLegacySessions }?.let { session ->
        RemoteSensitiveContent {
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
    }

    resolvingLegacyStart?.let { session ->
        RemoteSensitiveContent {
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
    }

    discardingPackageExtension?.takeIf { access.canManageSessions }?.let { request ->
        RemoteSensitiveContent {
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
    }

    if (paymentQueueOpen) {
        RemoteSensitiveContent {
            GamingQueueDialog(
                title = "Sessions awaiting payment",
                detail = "Send sessions individually so each remains a separate, traceable POS order.",
                sessions = state.readyForPos,
                stations = state.stations,
                activeShiftId = state.activeShiftId,
                pendingBillSnapshotMinor = state::pendingBillSnapshotMinor,
                actionLabel = { session ->
                    when {
                        session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT -> "Send"
                        access.canReconcileLegacySessions && state.activeShiftId != null -> "Reconcile closed shift"
                        else -> "Unavailable"
                    }
                },
                actionEnabled = { session ->
                    state.unresolvedAddonsFor(session).isEmpty() &&
                        (
                            session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT ||
                                (access.canReconcileLegacySessions && state.activeShiftId != null)
                        )
                },
                actionDisabledReason = { session ->
                    if (state.unresolvedAddonsFor(session).isNotEmpty()) {
                        "Saved Gaming item actions must finish syncing or be reviewed before POS handoff."
                    } else {
                        sessionAuthorityMessage(session, state.activeShiftId)
                            ?: "Open the session's shift on its recorded terminal before POS handoff."
                    }
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
    }

    if (cancellationQueueOpen) {
        RemoteSensitiveContent {
            GamingQueueDialog(
                title = "Sessions requiring cancellation",
                detail = "A reason is required and recorded in the audit trail.",
                sessions = state.needsCancellation,
                stations = state.stations,
                activeShiftId = state.activeShiftId,
                pendingBillSnapshotMinor = { it.amountMinor ?: 0L },
                actionLabel = { "Review" },
                actionEnabled = { session ->
                    sessionCancellationAddonBlockMessage(state.addonsFor(session)) == null &&
                        state.unresolvedAddonsFor(session).isEmpty() &&
                        (
                            session.authority(state.activeShiftId) == GamingSessionAuthority.CURRENT_SHIFT ||
                                access.canReconcileLegacySessions
                        )
                },
                actionDisabledReason = { session ->
                    sessionCancellationAddonBlockMessage(state.addonsFor(session))
                        ?: if (state.unresolvedAddonsFor(session).isNotEmpty()) {
                            "Saved Gaming item actions must finish syncing or be reviewed before session cancellation."
                        } else {
                            null
                        }
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
    }

    state.error?.let { message ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = Radius.shapeLg,
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Gaming action not completed") },
            text = { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

@Composable
private fun PosTargetShiftDialog(
    selection: PosTargetSelectionUi,
    stationName: String,
    pendingBillSnapshotMinor: Long,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedShiftId by remember(selection.serverSessionId, selection.targets) {
        mutableStateOf<String?>(null)
    }
    val selectedTarget = selection.targets.firstOrNull { it.shiftId == selectedShiftId }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Choose the receiving POS shift") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "$stationName · estimated combined bill ${pendingBillSnapshotMinor.asRupees()}. " +
                        "POS confirms the final total. " +
                        "The Gaming shift keeps the session history; the selected POS shift " +
                        "receives the unpaid order.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(selection.targets, key = PosTargetShift::shiftId) { target ->
                        val selected = target.shiftId == selectedShiftId
                        OutlinedButton(
                            onClick = { selectedShiftId = target.shiftId },
                            enabled = !busy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            ),
                            contentPadding = PaddingValues(Spacing.md),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        target.terminalName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        posTargetShiftSupportingText(target),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = "Selected receiving shift",
                                        tint = MaterialTheme.colorScheme.operationalGood,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "If the receiving POS shift is not listed, keep the bill pending and ask its cashier to open a shift.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            ErpButton(
                text = selectedTarget?.let { "Send to ${it.terminalName}" } ?: "Choose a shift",
                onClick = { selectedShiftId?.let(onConfirm) },
                enabled = !busy && canConfirmPosTargetSelection(
                    selectedShiftId,
                    selection.targets,
                ),
                busy = busy,
                leadingIcon = Icons.AutoMirrored.Filled.Send,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep pending") }
        },
    )
}

internal fun posTargetShiftSupportingText(target: PosTargetShift): String {
    val openedAt = target.openedAt.businessClockTime()
    return buildString {
        append("Opened by ${target.openedByName}")
        append(" at $openedAt")
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
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Text("Waiting for the first station sync", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                        Text("The board will populate as soon as the ERP responds.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val awaitingTotal = state.readyForPos.sumOf(state::pendingBillSnapshotMinor)
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
                detail = "Est. ${awaitingTotal.asRupees()} combined",
                icon = Icons.Filled.Payments,
                tone = if (state.readyForPos.isEmpty()) UiTone.Neutral else UiTone.Warning,
                modifier = modifier,
            )
        },
        { modifier ->
            MetricCard(
                title = "POS shift",
                value = gamingShiftSummary(state.activeShiftId, state.activeShiftServerConfirmed, state.online, state.activeShiftAllowsQueuedStart).label,
                detail = gamingShiftSummary(state.activeShiftId, state.activeShiftServerConfirmed, state.online, state.activeShiftAllowsQueuedStart).detail,
                icon = Icons.Filled.Schedule,
                tone = if (state.activeShiftId == null || !state.activeShiftServerConfirmed) UiTone.Warning else UiTone.Success,
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
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
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

internal fun gamingCommandAttentionCount(
    canManageSessions: Boolean,
    terminalBlocked: Boolean,
    focusRequested: Boolean,
    hasRefreshError: Boolean,
    orphanedExtensionCount: Int,
    rejectedSessionCount: Int,
    needsCancellation: Boolean,
    awaitingPayment: Boolean,
    // Saving is deliberately local progress on the affected station/button.
    // Keep it out of this count so a save cannot insert or remove global UI.
    @Suppress("UNUSED_PARAMETER")
    busy: Boolean,
): Int =
    listOf(
        !canManageSessions,
        terminalBlocked,
        focusRequested,
        hasRefreshError,
        needsCancellation,
        awaitingPayment,
    ).count { it } + orphanedExtensionCount + rejectedSessionCount

internal fun rejectedSessionReviewStationId(
    session: GameSession,
    stationIds: List<String>,
): String? = session.stationId.takeIf {
    session.localState in setOf(
        GamingSessionState.START_REJECTED,
        GamingSessionState.STOP_REJECTED,
    ) && it in stationIds
}

@Composable
private fun GamingPermanentSessionDial(
    session: GameSession?,
    wallClock: State<Long>,
    choices: List<GamingDurationChoice>,
    selectedPackageId: String?,
    onSelect: (String) -> Unit,
    dialSize: Dp,
    gestureContextKey: Any,
    tariffUnavailable: Boolean,
) {
    // The one-second clock is owned by the permanent dial child. Keeping the
    // read below this composition boundary prevents the station workspace,
    // editors and fixed action footer from recomposing every second.
    val ticking = session?.status in setOf("active", "starting")
    val frozenNow = remember(session?.id, session?.status) { System.currentTimeMillis() }
    val dialNow = if (ticking) wallClock.value else frozenNow
    val sessionReadout = session?.let { current ->
        val playedMillis = sessionPlayElapsedMillis(current, dialNow)
        val bookedMillis = current.timerMinutes?.takeIf { it > 0 }?.times(60_000L)
        when {
            current.status == "start_failed" -> GamingDurationDialReadout(
                primary = "--:--:--",
                label = "START FAILED",
                detail = "Review saved start",
            )
            playedMillis == null -> GamingDurationDialReadout(
                primary = "--:--:--",
                label = "TIME UNAVAILABLE",
                detail = "Review session timing",
            )
            else -> {
                val remainingMillis = bookedMillis?.minus(playedMillis)
                GamingDurationDialReadout(
                    primary = formatElapsed(playedMillis),
                    label = when {
                        current.status == "paused" -> "PAUSED"
                        current.status == "stopping" -> "STOPPING"
                        current.status in setOf("ended", "stopped", "completed") ||
                            current.endAt != null -> "STOPPED"
                        current.status == "starting" -> "STARTING"
                        else -> "ELAPSED TIME"
                    },
                    detail = remainingMillis?.let { remaining ->
                        if (remaining <= 0L) "+${formatElapsed(-remaining)} overtime"
                        else "${formatElapsed(remaining)} remaining"
                    } ?: "Open-ended session",
                )
            }
        }
    }
    val remainingFraction = session?.let { current ->
        val bookedMillis = current.timerMinutes?.takeIf { it > 0 }?.times(60_000L) ?: return@let null
        val playedMillis = sessionPlayElapsedMillis(current, dialNow) ?: return@let null
        gamingDurationRemainingFraction(bookedMillis, playedMillis)
    }
    GamingDurationDial(
        choices = choices,
        selectedPackageId = selectedPackageId,
        onSelect = onSelect,
        dialSize = dialSize,
        showChoiceChips = false,
        gestureContextKey = gestureContextKey,
        readout = sessionReadout ?: if (tariffUnavailable) {
            GamingDurationDialReadout(
                primary = "--",
                label = "TARIFF UNAVAILABLE",
                detail = "Refresh Gaming",
            )
        } else null,
        remainingFraction = remainingFraction,
    )
}

internal fun gamingDurationRemainingFraction(bookedMillis: Long, playedMillis: Long): Float? {
    if (bookedMillis <= 0L || playedMillis < 0L) return null
    return ((bookedMillis - playedMillis).coerceAtLeast(0L).toFloat() / bookedMillis.toFloat())
        .coerceIn(0f, 1f)
}

@Composable
internal fun GamingCommandWorkspace(
    state: GamingUiState,
    access: GamingAccess,
    filters: List<StationFilter>,
    selectedFilter: String,
    visibleStations: List<Station>,
    selectedStationId: String?,
    showDetail: Boolean,
    focusStationId: String?,
    focusRequested: Boolean,
    startTerminalBlockMessage: String?,
    wallClock: State<Long>,
    attentionCount: Int,
    onSelectFilter: (String) -> Unit,
    onSelectStation: (String) -> Unit,
    onBackToBoard: () -> Unit,
    onOpenAttention: () -> Unit,
    onRefresh: () -> Unit,
    onStart: (Station) -> Unit,
    onConfirmStart: (Station, String?, String?, String?, Int?, String?, Int) -> Unit,
    onPrepareStart: () -> Unit = {},
    onSearchCustomer: (String) -> Unit = {},
    onStop: (Station, GameSession) -> Unit,
    onSend: (GameSession) -> Unit,
    onCancelUnbilled: (GameSession) -> Unit,
    onExtendTimer: (GameSession) -> Unit,
    onExtendPackage: (GameSession, List<GamingPackage>) -> Unit,
    onTransfer: (GameSession) -> Unit,
    onPauseResume: (GameSession) -> Unit,
    onReconcile: (GameSession) -> Unit,
    onRepairBilling: (GameSession) -> Unit,
    onResolveLegacyStart: (GameSession) -> Unit,
    onDiscardPackageExtension: (PackageExtensionActionUi) -> Unit,
    onAddItems: (GameSession) -> Unit,
    onQuickAddItem: (GameSession, MenuItemEntity, Int) -> Unit = { _, _, _ -> },
    onConfigureItem: (GameSession, MenuItemEntity) -> Unit = { _, _ -> },
    onVoidAddon: (GameSession, GamingSessionAddonUi) -> Unit,
    onReviewRejectedAddon: (String) -> Unit,
    onManageParticipants: (GameSession) -> Unit,
    onJoinParticipant: (GameSession, String?, String?, String?, String?) -> Unit = { _, _, _, _, _ -> },
    onLeaveParticipant: (GameSession, String) -> Unit = { _, _ -> },
    onConfirmExtension: ((PackageExtensionReview) -> String?)? = null,
    shiftAccess: ShiftAccess = ShiftAccess(),
) {
    // Selection changes the control pane; it never removes the station board.
    // `showDetail`/`onBackToBoard` remain as compatibility inputs for deep-link
    // callers while they move to the same-screen selection model.
    val compactListState = rememberLazyListState()
    val compactScrollScope = rememberCoroutineScope()
    val selectedStation = visibleStations.firstOrNull { it.id == selectedStationId }
        ?: visibleStations.firstOrNull()
    val selectedSession = selectedStation?.let { state.activeFor(it.id) }
    var extensionPreview by remember { mutableStateOf<InlinePackageExtensionPreview?>(null) }
    var extensionReviewError by remember { mutableStateOf<String?>(null) }
    var extensionConfirming by remember { mutableStateOf(false) }
    var customerCacheRequested by remember { mutableStateOf(false) }
    var inlineShiftOpen by rememberSaveable(selectedStationId) { mutableStateOf(false) }
    var inlinePanel by rememberSaveable(selectedStationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedStation != null && selectedSession == null, access.canManageSessions) {
        if (selectedStation != null && selectedSession == null &&
            access.canManageSessions && !customerCacheRequested
        ) {
            customerCacheRequested = true
            onPrepareStart()
        }
    }
    val startBlockMessage = when {
        !access.canManageSessions -> if (access.writeBlockedByMissingShiftRead) {
            GAMING_SHIFT_ACCESS_REQUIRED_MESSAGE
        } else VIEW_ONLY_MESSAGE
        selectedStation?.isActive != true -> "This station is unavailable."
        state.busyStationId != null -> "Another station action is saving. Wait for it to finish."
        startTerminalBlockMessage != null -> startTerminalBlockMessage
        state.activeShiftId == null -> "Open a POS shift before starting a session."
        !state.activeShiftServerConfirmed && !state.activeShiftAllowsQueuedStart ->
            "Waiting for the open shift to sync before starting."
        else -> null
    }
    LaunchedEffect(selectedStation?.id, selectedSession?.id) {
        val preview = extensionPreview ?: return@LaunchedEffect
        if (preview.station.id != selectedStation?.id || preview.session.id != selectedSession?.id) {
            extensionPreview = null
            extensionReviewError = "The selected station or session changed. Reopen Extend to review current details."
            extensionConfirming = false
        }
    }
    LaunchedEffect(extensionPreview, state, access) {
        val preview = extensionPreview ?: return@LaunchedEffect
        val review = preview.review() ?: return@LaunchedEffect
        val currentSession = state.sessions.firstOrNull { it.id == review.session.id }
        val terminalChange = currentSession == null || currentSession.status !in setOf("active", "paused") ||
            currentSession.orderId != null
        val operationalBlock = currentSession != null && (
            gamingCleanupWorkflowMessageOrNull(currentSession.lastError) != null ||
                state.packageExtensionFor(currentSession.id) != null ||
                state.unresolvedSequencedActionsFor(currentSession).any {
                    it.state == GamingSessionActionState.REJECTED
                }
            )
        val error = packageExtensionReviewError(review, state, access)
        if (terminalChange || operationalBlock) {
            extensionPreview = null
            extensionReviewError = error
            extensionConfirming = false
        } else {
            extensionReviewError = error
        }
    }
    val openPackageExtension: (GameSession, List<GamingPackage>) -> Unit = { session, extensions ->
        if (onConfirmExtension == null) {
            onExtendPackage(session, extensions)
        } else {
            val station = state.stations.firstOrNull { it.id == session.stationId }
            val first = extensions.firstOrNull()
            if (station != null && first != null) {
                val preview = InlinePackageExtensionPreview(
                    station = station,
                    session = session,
                    extensions = extensions,
                    selectedPackageId = first.id,
                )
                extensionPreview = preview
                extensionReviewError = preview.review()?.let {
                    packageExtensionReviewError(it, state, access)
                }
                extensionConfirming = false
            }
        }
    }

    val controlPane: @Composable (Modifier) -> Unit = { paneModifier ->
        val selectedHeroState = selectedSession?.let { current ->
            stationPresentation(
                station = selectedStation ?: return@let null,
                session = current,
                // Active and overtime both use the hero action. A stable value
                // avoids making the whole control pane a per-second reader.
                nowMillis = 0L,
                hasActiveAddons = state.addonsFor(current).any {
                    !it.voided && !it.isRejectedLocalAdd()
                },
            ).state
        }
        when {
            selectedStation == null -> DesignedEmptyState(
                title = "Select a station", body = "Choose a station on the board.",
                icon = Icons.Filled.SportsEsports, modifier = paneModifier,
            )
            else -> androidx.compose.runtime.key(selectedStation.id) {
                LaunchedEffect(selectedSession?.id, selectedHeroState) {
                    if (inlinePanel != null && !gamingInlineEditorAvailable(selectedHeroState)) {
                        inlinePanel = null
                    }
                }
                val stationPackages = state.packages.filter {
                    it.stationType == selectedStation.type && it.kind == "base"
                }
                val startSelection = rememberGamingStartSelectionState(selectedStation, stationPackages)
                val durationChoices = gamingDurationChoices(selectedStation, stationPackages, startSelection)
                LaunchedEffect(selectedSession?.id, selectedSession?.effectivePackageId, selectedSession?.packageId) {
                    val bookedVariant = selectedSession?.effectivePackageVariant
                        ?: selectedSession?.packageVariantSnapshot
                    if (bookedVariant != null) startSelection.selectedVariant.value = bookedVariant
                    if (bookedVariant == "dual") {
                        startSelection.playerCount.intValue = (2 + (selectedSession?.extraControllers ?: 0))
                            .coerceAtMost(4)
                    } else if (bookedVariant == "single") {
                        startSelection.playerCount.intValue = 1
                    }
                    val bookedPackageId = selectedSession?.effectivePackageId ?: selectedSession?.packageId
                    if (bookedPackageId != null) startSelection.selectedPackageId.value = bookedPackageId
                }
                val fixedTabletWorkspace = LocalConfiguration.current.screenWidthDp >= 900
                Column(
                paneModifier.then(
                    if (fixedTabletWorkspace) Modifier else Modifier.verticalScroll(
                        remember(selectedStation.id, inlinePanel) { ScrollState(0) },
                    ),
                ).padding(
                    horizontal = Spacing.md,
                    vertical = Spacing.sm,
                ),
                // Match the draft editor's fixed header-to-instrument gap so a
                // state update never makes the permanent ring jump vertically.
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val compactWorkspaceHeader = LocalConfiguration.current.screenHeightDp < 700
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!compactWorkspaceHeader) Text(
                        if (selectedSession == null) "START SESSION" else "SESSION STATUS",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        selectedStation.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = if (compactWorkspaceHeader) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!compactWorkspaceHeader) Text(
                        if (selectedSession == null) {
                            "Available · select a published duration"
                        } else {
                            selectedHeroState?.name?.replace('_', ' ')
                                ?.lowercase()?.replaceFirstChar(Char::titlecase)
                                ?: selectedSession.status.replaceFirstChar(Char::titlecase)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Row(
                    // The draft editor constrains this same instrument/detail
                    // row to 760dp. Reuse that measured scaffold for starting,
                    // running, paused and stopped states.
                    Modifier.widthIn(max = 760.dp).fillMaxWidth()
                        .then(if (fixedTabletWorkspace) Modifier.weight(1f) else Modifier)
                        .align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                Box(
                    Modifier.weight(0.62f).testTag("gaming-ring-host"),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (requiresCanonicalGamingTariff(selectedStation.type)) {
                        val fixedChoices = durationChoices.ifEmpty {
                            listOf(
                                GamingDurationChoice(
                                    packageId = selectedSession?.effectivePackageId
                                        ?: selectedSession?.packageId ?: "${selectedStation.id}:unavailable",
                                    minutes = selectedSession?.timerMinutes ?: 0,
                                    totalMinor = selectedSession?.amountMinor ?: 0L,
                                ),
                            )
                        }
                        GamingPermanentSessionDial(
                            session = selectedSession,
                            wallClock = wallClock,
                            choices = fixedChoices,
                            selectedPackageId = startSelection.selectedPackageId.value,
                            onSelect = { startSelection.selectedPackageId.value = it },
                            dialSize = gamingControlRingSize(LocalConfiguration.current.screenHeightDp),
                            gestureContextKey = selectedStation.id,
                            tariffUnavailable = durationChoices.isEmpty(),
                        )
                        if (selectedSession == null) {
                            durationChoices.firstOrNull {
                                it.packageId == startSelection.selectedPackageId.value
                            }?.let { selectedDuration ->
                                Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    ExpectedSessionFinishPreview(selectedDuration.minutes)
                                }
                            }
                        }
                    } else if (selectedSession != null) {
                        GamingStationHero(
                            station = selectedStation,
                            session = selectedSession,
                            sessionAddons = state.addonsFor(selectedSession),
                            wallClock = wallClock,
                            stopActionEnabled = gamingSessionActionsEnabled(
                                canWrite = access.canManageSessions,
                                actionInProgress = state.busyStationId != null,
                                lastError = selectedSession.lastError,
                            ) && selectedSession.resolvedStopShiftId(
                                activeShiftId = state.activeShiftId,
                                activeShiftServerConfirmed = state.activeShiftServerConfirmed,
                                online = state.online,
                            ) != null,
                            stopBusy = state.busyStationId == selectedStation.id,
                            onStop = { onStop(selectedStation, it) },
                            ringOnly = true,
                        )
                    } else {
                        GamingStationArtwork(selectedStation.type, Modifier.size(180.dp))
                    }
                }
                Column(
                    Modifier.weight(0.38f)
                        .then(if (fixedTabletWorkspace) Modifier.fillMaxHeight() else Modifier),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                if (selectedSession == null && selectedStation.isActive) {
                    RemoteSensitiveContent {
                        StartSessionEditor(
                            station = selectedStation,
                            packages = stationPackages,
                            customers = state.customers,
                            online = state.online,
                            onSearchCustomer = onSearchCustomer,
                            onDismiss = onBackToBoard,
                            onConfirm = { customerId, name, phone, minutes, packageId, extraControllers ->
                                if (startBlockMessage == null) {
                                    onConfirmStart(
                                        selectedStation, customerId, name, phone, minutes,
                                        packageId, extraControllers,
                                    )
                                }
                            },
                            embedded = true,
                            startEnabled = startBlockMessage == null,
                            startBlockMessage = startBlockMessage,
                            blockerActions = {
                                if (inlineShiftOpen) {
                                    InlineShiftOpeningWorkspace(shiftAccess, onReturn = { inlineShiftOpen = false })
                                } else if (startBlockMessage != null && access.canManageSessions) {
                                    ShiftBlockerActions(
                                        canOpen = shiftAccess.canOpen && startTerminalBlockMessage == null,
                                        missingShift = state.activeShiftId == null,
                                        onOpen = { inlineShiftOpen = true }, onSync = onRefresh,
                                    )
                                }
                            },
                            selectionState = startSelection,
                            externallyHostedDial = true,
                            pinActions = fixedTabletWorkspace,
                        )
                    }
                } else {
                val visibleExtensionPreview = extensionPreview?.takeIf {
                    it.station.id == selectedStation.id && it.session.id == selectedSession?.id
                }
                visibleExtensionPreview?.let { preview ->
                    RemoteSensitiveContent {
                        PackageExtensionInlineReview(
                            preview = preview,
                            error = extensionReviewError,
                            confirming = extensionConfirming,
                            pinActions = fixedTabletWorkspace,
                            onSelect = { packageId ->
                                extensionPreview = preview.copy(selectedPackageId = packageId)
                                extensionReviewError = preview.extensions.firstOrNull {
                                    it.id == packageId
                                }?.let { extension ->
                                    packageExtensionReviewError(
                                        PackageExtensionReview(preview.station, preview.session, extension),
                                        state,
                                        access,
                                    )
                                }
                            },
                            onConfirm = {
                                val review = preview.review()
                                if (review != null && extensionReviewError == null && !extensionConfirming) {
                                    extensionConfirming = true
                                    val error = onConfirmExtension?.invoke(review)
                                    if (error == null) {
                                        extensionPreview = null
                                        extensionReviewError = null
                                    } else {
                                        extensionReviewError = error
                                        extensionConfirming = false
                                    }
                                }
                            },
                            onCancel = {
                                extensionPreview = null
                                extensionReviewError = null
                                extensionConfirming = false
                            },
                        )
                    }
                }
                if (extensionPreview == null) extensionReviewError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.operationalDanger,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("gaming-extension-review-error"),
                    )
                }
                when {
                    visibleExtensionPreview != null -> Unit
                    inlinePanel == "players" -> if (selectedSession != null) {
                        GamingInlineParticipantEditor(
                            session = selectedSession,
                            participants = state.participantsFor(selectedSession),
                            customers = state.customers,
                            wallClock = wallClock,
                            busy = state.busyStationId != null,
                            onJoin = { customerId, name, phone, identityId ->
                                onJoinParticipant(selectedSession, customerId, name, phone, identityId)
                            },
                            onLeave = { reference -> onLeaveParticipant(selectedSession, reference) },
                            onClose = { inlinePanel = null },
                            modifier = Modifier.fillMaxWidth().then(
                                if (fixedTabletWorkspace) Modifier.weight(1f) else Modifier,
                            ),
                            pinConfirmAction = fixedTabletWorkspace,
                        )
                    }
                    inlinePanel == "drinks" -> if (selectedSession != null) {
                        GamingInlineDrinkEditor(
                            session = selectedSession,
                            items = state.addonCatalog,
                            configurableItemIds = remember(
                                state.addonVariants,
                                state.addonModifierGroups,
                            ) {
                                (state.addonVariants.map { it.menuItemId } +
                                    state.addonModifierGroups.map { it.menuItemId }).toSet()
                            },
                            busy = state.busyStationId != null,
                            onConfirm = { item, qty -> onQuickAddItem(selectedSession, item, qty) },
                            onConfigure = { item -> onConfigureItem(selectedSession, item) },
                            onClose = { inlinePanel = null },
                            modifier = Modifier.fillMaxWidth().then(
                                if (fixedTabletWorkspace) Modifier.weight(1f) else Modifier,
                            ),
                            pinActions = fixedTabletWorkspace,
                        )
                    }
                    else -> {
                        // The existing card continues to own authority,
                        // recovery, billing and all active/payment actions in
                        // this side pane. A routine inline editor temporarily
                        // replaces it so the form starts beside the ring,
                        // rather than below the viewport.
                        GamingStationCard(
        station = selectedStation,
        session = selectedSession,
        packageExtensionAction = selectedSession?.let {
            state.packageExtensionFor(it.id)
        },
        sessionAddons = selectedSession?.let(state::addonsFor).orEmpty(),
        wallClock = wallClock,
        actionInProgress = state.busyStationId != null,
        busyHere = state.busyStationId == selectedStation.id,
        focused = selectedStation.id == focusStationId,
        canWrite = access.canManageSessions,
        canReconcileLegacy = access.canReconcileLegacySessions,
        activeShiftId = state.activeShiftId,
        activeShiftServerConfirmed = state.activeShiftServerConfirmed,
        activeShiftAllowsQueuedStart = state.activeShiftAllowsQueuedStart,
        startTerminalBlockMessage = startTerminalBlockMessage,
        online = state.online,
        packages = state.packages,
        hasTransferTarget = state.stations.any { candidate ->
            candidate.id != selectedStation.id &&
                candidate.type == selectedStation.type &&
                candidate.isActive && state.activeFor(candidate.id) == null
        },
        onStart = { onStart(selectedStation) },
        onStop = { onStop(selectedStation, it) },
        onSend = onSend,
        onCancelUnbilled = onCancelUnbilled,
        onExtendTimer = onExtendTimer,
        onExtendPackage = openPackageExtension,
        onTransfer = onTransfer,
        onPauseResume = onPauseResume,
        onReconcile = onReconcile,
        onRepairBilling = onRepairBilling,
        onResolveLegacyStart = onResolveLegacyStart,
        onDiscardPackageExtension = onDiscardPackageExtension,
        onAddItems = {
            inlinePanel = if (inlinePanel == "drinks") null else "drinks"
        },
        onVoidAddon = onVoidAddon,
        onReviewRejectedAddon = onReviewRejectedAddon,
        onManageParticipants = {
            inlinePanel = if (inlinePanel == "players") null else "players"
            onManageParticipants(it)
        },
        compactDetail = true,
        // The compact detail pane owns all live actions when the hero is a
        // ring-only instrument. Keep Stop visible in the same workspace.
        showActiveStopAction = true,
        pinActiveSessionActions = fixedTabletWorkspace,
        modifier = if (fixedTabletWorkspace) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
    )
                    }
                }
                if (
                    inlinePanel != null && selectedSession != null && selectedHeroState != null &&
                    gamingInlineStopVisible(selectedHeroState, selectedSession.canRequestStop())
                ) {
                    val canStopFromInlineEditor = selectedSession.resolvedStopShiftId(
                        activeShiftId = state.activeShiftId,
                        activeShiftServerConfirmed = state.activeShiftServerConfirmed,
                        online = state.online,
                    ) != null
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth()) {
                        GamingStopSessionAction(
                            session = selectedSession,
                            presentationState = selectedHeroState,
                            enabled = gamingSessionActionsEnabled(
                                canWrite = access.canManageSessions,
                                actionInProgress = state.busyStationId != null,
                                lastError = selectedSession.lastError,
                            ) && canStopFromInlineEditor,
                            busy = state.busyStationId == selectedStation.id,
                            onStop = { onStop(selectedStation, it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        GamingPrimaryActionSupportArea()
                    }
                }
                }
                }
                }
            } }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        GamingAmbientBackdrop()
        // The 960dp tablet must keep its station board and duration dial in
        // the same viewport; narrower screens retain the scroll fallback.
        val sideBySide = maxWidth >= 900.dp
        // The approved tablet board is always two columns when it shares the
        // viewport with the command workspace. Four rows must remain visible
        // even on a 1280x800 tablet with the required operational notice rail.
        val denseGrid = sideBySide
        val compactHeightGrid = sideBySide && maxHeight < 760.dp
        val noticeRailMaxHeight = minOf(144.dp, maxHeight * 0.19f)
        val columns = when {
            sideBySide -> 2
            maxWidth >= 680.dp -> 4
            maxWidth >= 490.dp -> 3
            else -> 2
        }
        Column(
            Modifier.fillMaxSize().padding(
                start = Spacing.lgPlus, top = Spacing.sm,
                end = Spacing.lgPlus, bottom = Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Several simultaneous operational warnings must not collapse the
            // station board and duration controls on a compact tablet.
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = noticeRailMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag("gaming-notice-rail"),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                GamingAlarmPermissionCard()
                if (state.cleanupAcknowledgementsPending.isNotEmpty()) {
                    GamingCleanupAcknowledgementBanner(state, onRefresh)
                }
                // Keep one useful status row present in both healthy and attention states.
                // Its content can change after Stop without moving the station board or dial.
                GamingCommandAttentionBar(
                    state = state, access = access,
                    terminalBlocked = startTerminalBlockMessage != null,
                    focusRequested = focusRequested,
                    orphanedExtensionCount = state.orphanedPackageExtensionActions().size,
                    attentionCount = attentionCount, onOpen = onOpenAttention,
                    onRefresh = onRefresh,
                )
            }
            if (compactHeightGrid) {
                GamingCompactBoardHeader(
                    state = state,
                    filters = filters,
                    selected = selectedFilter,
                    onSelect = onSelectFilter,
                )
            } else {
                GamingBoardHeadline(state, onRefresh)
                GamingFilterRow(
                    filters = filters, stations = state.stations,
                    selected = selectedFilter, onSelect = onSelectFilter,
                )
            }
            if (visibleStations.isEmpty()) {
                DesignedEmptyState(
                    title = "No stations in this filter",
                    body = "Choose All or another station type to return to the board.",
                    icon = Icons.Filled.SportsEsports,
                )
            } else if (sideBySide) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    GamingStationSelectionGrid(
                        state = state, stations = visibleStations,
                        selectedStationId = selectedStation?.id,
                        focusStationId = focusStationId,
                        wallClock = wallClock, columns = columns,
                        dense = denseGrid,
                        compactHeight = compactHeightGrid,
                        onSelectStation = onSelectStation,
                        modifier = Modifier.weight(0.29f).fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    )
                    controlPane(
                        Modifier.weight(0.71f).fillMaxHeight()
                            .clip(Radius.shapeLg)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), Radius.shapeLg)
                            .testTag("gaming-control-pane"),
                    )
                }
            } else {
                // Compact tablets retain the board and selected controls in
                // one scroll owner, with a bounded form so its footer stays
                // reachable instead of nesting unbounded vertical lists.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .testTag("gaming-board-and-controls"),
                    state = compactListState,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                ) {
                    item(key = "stations") {
                        GamingStationSelectionGrid(
                            state = state, stations = visibleStations,
                            selectedStationId = selectedStation?.id,
                            focusStationId = focusStationId,
                            wallClock = wallClock, columns = columns,
                            dense = denseGrid,
                            compactHeight = compactHeightGrid,
                            onSelectStation = { stationId ->
                                onSelectStation(stationId)
                                compactScrollScope.launch {
                                    compactListState.animateScrollToItem(1)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "control") {
                        controlPane(
                            Modifier.fillMaxWidth().height(560.dp)
                                .clip(Radius.shapeLg)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), Radius.shapeLg)
                                .testTag("gaming-control-pane"),
                        )
                    }
                }
            }
        }
    }
}

/** Static ambient light keeps the board legible and does not schedule idle redraws. */
@Composable
private fun GamingAmbientBackdrop() {
    Box(Modifier.fillMaxSize())
}

@Composable
private fun GamingStationSelectionGrid(
    state: GamingUiState,
    stations: List<Station>,
    selectedStationId: String?,
    focusStationId: String?,
    wallClock: State<Long>,
    columns: Int,
    dense: Boolean,
    compactHeight: Boolean,
    onSelectStation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.testTag("gaming-station-board"),
        verticalArrangement = Arrangement.spacedBy(
            if (compactHeight) 2.dp else Spacing.sm,
        ),
    ) {
        stations.chunked(columns).forEach { rowStations ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowStations.forEach { station ->
                    val session = state.activeFor(station.id)
                    GamingStationTile(
                        station = station, session = session,
                        sessionAddons = session?.let(state::addonsFor).orEmpty(),
                        packages = state.packages, wallClock = wallClock,
                        selected = station.id == selectedStationId,
                        focused = station.id == focusStationId,
                        combinedBillSnapshotMinor = session?.let(state::pendingBillSnapshotMinor),
                        onSelect = { onSelectStation(station.id) },
                        compact = true, dense = dense,
                        compactHeight = compactHeight,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowStations.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GamingBoardHeadline(state: GamingUiState, onRefresh: () -> Unit) {
    val active = operationalActiveGamingSessionCount(state.sessions)
    val available = state.stations.count { it.isActive && state.activeFor(it.id) == null }
    val shift = gamingShiftSummary(
        state.activeShiftId,
        state.activeShiftServerConfirmed,
        state.online,
        state.activeShiftAllowsQueuedStart,
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.SportsEsports, contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                "GAMING", color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                "${state.stations.size} stations", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        listOf(
            "Active" to active.toString(),
            "Available" to available.toString(),
            "Payment due" to state.readyForPos.size.toString(),
            "POS shift" to shift.label,
        ).forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Text(
                    value,
                    color = if (label == "Payment due" && state.readyForPos.isNotEmpty() ||
                        label == "POS shift" && state.activeShiftId == null
                    ) MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onRefresh, enabled = !state.refreshing) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text(if (state.refreshing) "Refreshing" else "Refresh")
        }
    }
}

@Composable
private fun GamingCommandPanel(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    toneProvider: @Composable () -> UiTone = { UiTone.Neutral },
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    content: @Composable () -> Unit,
) {
    val accent = statusColor(toneProvider())
    Column(
        modifier = modifier.clip(Radius.shapeLg)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f), Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f))
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(Radius.shapeMd).background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(contentPadding),
        ) {
            content()
        }
    }
}

@Composable
private fun GamingCommandMetrics(state: GamingUiState) {
    val active = operationalActiveGamingSessionCount(state.sessions)
    val available = state.stations.count { it.isActive && state.activeFor(it.id) == null }
    val awaitingTotal = state.readyForPos.sumOf(state::pendingBillSnapshotMinor)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CompactStatCard(
            label = "Stations",
            value = state.stations.size.toString(),
            detail = "Configured",
            icon = Icons.Filled.SportsEsports,
            tone = UiTone.Brand,
            modifier = Modifier.weight(1f),
        )
        CompactStatCard(
            label = "Active",
            value = active.toString(),
            detail = "Running now",
            icon = Icons.Filled.PlayArrow,
            tone = UiTone.Success,
            modifier = Modifier.weight(1f),
        )
        CompactStatCard(
            label = "Available",
            value = available.toString(),
            detail = "Ready",
            icon = Icons.Filled.CheckCircle,
            // Availability is routine operational context, not a separate
            // blue brand state. Keep the palette restrained and reserve
            // semantic colour for active, warning and failure states.
            tone = UiTone.Neutral,
            modifier = Modifier.weight(1f),
        )
        CompactStatCard(
            label = "Payment due",
            value = state.readyForPos.size.toString(),
            detail = awaitingTotal.asRupees(),
            icon = Icons.Filled.Payments,
            tone = if (state.readyForPos.isEmpty()) UiTone.Neutral else UiTone.Warning,
            modifier = Modifier.weight(1f),
        )
        CompactStatCard(
            label = "Shift",
            value = gamingShiftSummary(state.activeShiftId, state.activeShiftServerConfirmed, state.online, state.activeShiftAllowsQueuedStart).label,
            detail = gamingShiftSummary(state.activeShiftId, state.activeShiftServerConfirmed, state.online, state.activeShiftAllowsQueuedStart).detail,
            icon = Icons.Filled.Schedule,
            tone = if (state.activeShiftId == null || !state.activeShiftServerConfirmed) UiTone.Warning else UiTone.Success,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun GamingCommandAttentionBar(
    state: GamingUiState,
    access: GamingAccess,
    terminalBlocked: Boolean,
    focusRequested: Boolean,
    orphanedExtensionCount: Int,
    attentionCount: Int,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
) {
    val danger = state.needsCancellation.isNotEmpty() ||
        state.rejectedSessionsForReview.isNotEmpty() ||
        state.orphanedPackageExtensionActions().any {
            it.state == GamingPackageExtensionState.REJECTED
        }
    val warning = terminalBlocked || state.refreshError != null ||
        state.readyForPos.isNotEmpty() || focusRequested || !access.canManageSessions
    val tone = when {
        danger -> UiTone.Danger
        warning -> UiTone.Warning
        else -> UiTone.Success
    }
    val accent = statusColor(tone)
    val details = buildList {
        if (state.rejectedSessionsForReview.isNotEmpty()) {
            add("${state.rejectedSessionsForReview.size} rejected ${sessionWord(state.rejectedSessionsForReview.size)}")
        }
        if (state.needsCancellation.isNotEmpty()) add("${state.needsCancellation.size} need resolution")
        if (state.readyForPos.isNotEmpty()) add("${state.readyForPos.size} payment due")
        if (orphanedExtensionCount > 0) add("$orphanedExtensionCount retained sync")
        if (state.refreshError != null) add("board may be stale")
        if (terminalBlocked) add("terminal setup")
        if (!access.canManageSessions) add("view only")
        if (focusRequested) add("session alert")
    }
    val healthy = attentionCount == 0
    val statusTitle = when {
        healthy && state.refreshing -> "Refreshing Gaming board"
        healthy -> "Gaming board ready"
        danger || warning -> "Action centre · $attentionCount to review"
        else -> "Gaming activity · $attentionCount update${if (attentionCount == 1) "" else "s"}"
    }
    val statusDetail = when {
        healthy && state.refreshing -> "Checking the server · saved station controls remain available"
        healthy && state.online -> "Live data is connected · no action needs attention"
        healthy -> "Saved station data is available · reconnect to refresh live changes"
        else -> details.joinToString(" · ")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clip(Radius.shapeLg)
            .background(statusIconBackground(tone))
            .border(1.dp, accent.copy(alpha = 0.48f), Radius.shapeLg)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$statusTitle. $statusDetail"
            }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(Radius.shapeMd).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (danger) Icons.Filled.Error else if (warning) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                statusTitle,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                statusDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ErpButton(
            text = if (healthy && state.refreshing) "Refreshing…" else if (healthy) "Refresh" else "Review",
            onClick = if (healthy) onRefresh else onOpen,
            intent = if (danger) ActionIntent.Warning else ActionIntent.Secondary,
            enabled = !healthy || !state.refreshing,
            busy = healthy && state.refreshing,
            leadingIcon = if (healthy) Icons.Filled.Refresh else Icons.Filled.Visibility,
        )
    }
}

/**
 * Global writes are intentionally serialized to protect session and billing
 * ordering. Keep that temporary lock visible without inserting a row into the
 * station board: adding/removing a banner there was the source of a full-screen
 * jump that staff perceived as flicker.
 */
@Composable
internal fun GamingSavingOverlay(stationName: String) {
    Row(
        Modifier.widthIn(max = 560.dp)
            .clip(Radius.shapeLg)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.operationalInformation.copy(alpha = 0.62f), Radius.shapeLg)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Saving $stationName. Other station actions are temporarily paused."
            }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.operationalInformation,
            strokeWidth = 2.dp,
            modifier = Modifier.size(22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Saving $stationName",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Other station actions are paused until this change is safely stored.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun GamingStationHero(
    station: Station,
    session: GameSession?,
    sessionAddons: List<GamingSessionAddonUi>,
    wallClock: State<Long>,
    stopActionEnabled: Boolean,
    stopBusy: Boolean,
    onStop: (GameSession) -> Unit,
    ringOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val compactWorkspace = LocalConfiguration.current.screenHeightDp < 700
    val ticking = session?.status in setOf("active", "starting")
    val frozenNow = remember(session?.id, session?.status) { System.currentTimeMillis() }
    val now = if (ticking) wallClock.value else frozenNow
    val presentation = stationPresentation(station, session, now)
    val elapsedSession = session?.takeIf { current ->
        presentation.state in setOf(
            StationVisualState.Starting,
            StationVisualState.Active,
            StationVisualState.Overtime,
            StationVisualState.StopFailed,
        ) || (current.status == "paused" && current.pausedAt != null) || current.endAt != null
    }
    val billedSession = session?.takeIf {
        presentation.state in setOf(
            StationVisualState.PaymentDue,
            StationVisualState.SendPending,
            StationVisualState.SendRejected,
            StationVisualState.CancellationRequired,
        )
    }
    val showsElapsed = elapsedSession != null
    val showsBill = billedSession != null
    val combinedBillMinor = billedSession?.amountMinor?.let {
        Math.addExact(it, gamingSessionAddonBillableTotalMinor(sessionAddons))
    }
    if (ringOnly && elapsedSession != null) {
        val playedMillis = elapsedMillis(elapsedSession, now)
        val bookedMillis = elapsedSession.timerMinutes
            ?.takeIf { it > 0 }
            ?.times(60_000L)
        val remainingMillis = bookedMillis?.minus(playedMillis)
        Box(
            modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            GamingElapsedRing(
                clock = formatElapsed(playedMillis),
                clockLabel = "ELAPSED TIME",
                detail = remainingMillis?.let { remaining ->
                    if (remaining <= 0L) "+${formatElapsed(-remaining)} overtime"
                    else "${formatElapsed(remaining)} remaining"
                },
                progress = bookedMillis?.let { booked ->
                    (playedMillis.toFloat() / booked.toFloat()).coerceIn(0f, 1f)
                },
                tint = if (presentation.state in setOf(
                        StationVisualState.Overtime, StationVisualState.StopFailed,
                    )
                ) MaterialTheme.colorScheme.operationalDanger
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth().then(
            if (showsElapsed && compactWorkspace) {
                // This matches the compact draft editor's control-ring slot so
                // starting a session changes the data, not the instrument's position.
                Modifier.padding(top = 18.dp)
            } else {
                Modifier
            },
        ),
        color = MaterialTheme.colorScheme.surface,
        shape = Radius.shapeLg,
        border = BorderStroke(
            1.dp,
            if (showsBill) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (!showsElapsed) GamingStationArtwork(station.type, Modifier.size(92.dp))
            if (elapsedSession != null) {
                val playedMillis = elapsedMillis(elapsedSession, now)
                val bookedMillis = elapsedSession.timerMinutes
                    ?.takeIf { it > 0 }
                    ?.times(60_000L)
                val remainingMillis = bookedMillis?.minus(playedMillis)
                Box(
                    Modifier.weight(0.62f)
                        .then(if (compactWorkspace) Modifier.offset(x = (-3).dp) else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                GamingElapsedRing(
                    clock = formatElapsed(playedMillis),
                    clockLabel = "ELAPSED TIME",
                    detail = remainingMillis?.let { remaining ->
                        if (remaining <= 0L) "+${formatElapsed(-remaining)} overtime"
                        else "${formatElapsed(remaining)} remaining"
                    },
                    progress = bookedMillis?.let { booked ->
                        (playedMillis.toFloat() / booked.toFloat()).coerceIn(0f, 1f)
                    },
                    tint = if (presentation.state in setOf(
                            StationVisualState.Overtime, StationVisualState.StopFailed,
                        )
                    ) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                }
            }
            if (!ringOnly) Column(
                Modifier.weight(if (showsElapsed) 0.38f else 1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    station.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                OperationalStatusBadge(
                    label = presentation.statusLabel,
                    tone = presentation.tone,
                    icon = presentation.statusIcon,
                )
                when {
                    elapsedSession != null -> {
                        Text(
                            elapsedSession.startAt.businessClockTime()?.let { "Started $it" }
                                ?: "Session in progress",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (gamingHeroShowsStopAction(elapsedSession, presentation.state)) {
                            GamingStopSessionAction(
                                session = elapsedSession,
                                presentationState = presentation.state,
                                enabled = stopActionEnabled,
                                busy = stopBusy,
                                onStop = onStop,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    billedSession != null -> {
                        RemoteSensitiveContent {
                            Text(
                                combinedBillMinor?.asRupees() ?: "Amount unavailable",
                                color = if (combinedBillMinor == null) MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            if (presentation.state == StationVisualState.CancellationRequired) {
                                "Stopped · ${billedSession.billableMinutes ?: 0} min · reason required"
                            } else {
                                "Estimated combined bill · stopped ${billedSession.billableMinutes ?: 0} min"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            if (presentation.state == StationVisualState.PaymentDue) {
                                "Awaiting POS payment"
                            } else {
                                presentation.statusLabel
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    else -> Text(
                        "Actions and billing for this station",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Displays authoritative play progress only when the session has a valid booked duration. */
@Composable
private fun GamingElapsedRing(
    clock: String,
    clockLabel: String,
    detail: String? = null,
    progress: Float?,
    tint: Color,
) {
    val ringBorder = MaterialTheme.colorScheme.outlineVariant
    val ringSize = gamingControlRingSize(LocalConfiguration.current.screenHeightDp)
    Box(
        Modifier.size(ringSize).testTag("gaming-control-ring").semantics {
            contentDescription = listOfNotNull(clockLabel, clock, detail).joinToString(". ")
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 14.dp.toPx()
            drawCircle(ringBorder, radius, center, style = Stroke(2.dp.toPx()))
            if (progress != null) {
                drawArc(
                    color = tint,
                    startAngle = 135f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            drawCircle(tint.copy(alpha = 0.28f), radius - 11.dp.toPx(), center,
                style = Stroke(1.dp.toPx()))
            repeat(48) { tick ->
                val angle = Math.toRadians(tick * 7.5)
                val outer = radius + 8.dp.toPx()
                val inner = outer - if (tick % 4 == 0) 7.dp.toPx() else 4.dp.toPx()
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                drawLine(
                    tint.copy(alpha = if (tick % 4 == 0) 0.56f else 0.22f),
                    Offset(center.x + inner * dx, center.y + inner * dy),
                    Offset(center.x + outer * dx, center.y + outer * dy),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clock, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(clockLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            detail?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun gamingControlRingSize(screenHeightDp: Int) =
    if (screenHeightDp < 700) 232.dp else 320.dp

private fun gamingHeroShowsStopAction(
    session: GameSession?,
    presentationState: StationVisualState?,
): Boolean = session != null &&
    presentationState in setOf(
        StationVisualState.Active,
        StationVisualState.Overtime,
        StationVisualState.Paused,
        StationVisualState.StopFailed,
    ) && (presentationState != StationVisualState.Paused || session.pausedAt != null)

@Composable
private fun GamingStationArtwork(type: String, modifier: Modifier = Modifier) {
    val image = when (stationFilterId(type)) {
        "ps5" -> R.drawable.station_console
        "racing" -> R.drawable.station_racing
        "vr" -> R.drawable.station_vr
        "shisha" -> R.drawable.station_shisha
        "streaming" -> R.drawable.station_streaming
        else -> null
    }
    if (image != null) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                stationTypeIcon(type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

private fun compactStationTitle(name: String): String = when {
    name.startsWith("PS5 Station ") -> "PS5 " + name.removePrefix("PS5 Station ")
    name.startsWith("Racing Simulator ") -> "Racing " + name.removePrefix("Racing Simulator ")
    name.startsWith("Streaming Booth ") -> "Stream " + name.removePrefix("Streaming Booth ")
    name.startsWith("Shisha Table ") -> "Shisha " + name.removePrefix("Shisha Table ")
    else -> name
}

@Composable
internal fun GamingStationTile(
    station: Station,
    session: GameSession?,
    sessionAddons: List<GamingSessionAddonUi>,
    packages: List<GamingPackage>,
    wallClock: State<Long>,
    selected: Boolean,
    focused: Boolean,
    startsSessionOnTap: Boolean = false,
    combinedBillSnapshotMinor: Long?,
    onSelect: () -> Unit,
    compact: Boolean = false,
    dense: Boolean = false,
    compactHeight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shouldTick = session?.status == "active" ||
        (session?.status == "starting" && session.localState == GamingSessionState.START_PENDING)
    val frozenMillis = remember(session?.id, session?.status) { System.currentTimeMillis() }
    val nowMillis = if (shouldTick) wallClock.value else frozenMillis
    val hasActiveAddons = sessionAddons.any { !it.voided && !it.isRejectedLocalAdd() }
    val presentation = stationPresentation(station, session, nowMillis, hasActiveAddons)
    val accent = statusColor(presentation.tone)
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.onPrimaryContainer
        selected -> MaterialTheme.colorScheme.primary
        presentation.state in setOf(StationVisualState.Active, StationVisualState.Overtime) ->
            MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val primaryValue = when (presentation.state) {
        StationVisualState.Starting,
        StationVisualState.Active,
        StationVisualState.Overtime,
        StationVisualState.Stopping,
        StationVisualState.StopFailed,
        -> formatElapsed(session?.let { elapsedMillis(it, nowMillis) } ?: 0L)
        StationVisualState.Paused -> "Paused"
        StationVisualState.PaymentDue,
        StationVisualState.SendPending,
        StationVisualState.SendRejected,
        StationVisualState.CancellationRequired,
        -> (combinedBillSnapshotMinor ?: session?.amountMinor ?: 0L).asRupees()
        StationVisualState.BillingMissing -> "Review bill"
        StationVisualState.StartFailed -> "Review start"
        StationVisualState.Available -> "Ready"
        StationVisualState.Disabled -> "Unavailable"
        StationVisualState.Unavailable -> "Check station"
    }
    val supportingValue = when (presentation.state) {
        StationVisualState.Starting,
        StationVisualState.Active,
        StationVisualState.Overtime,
        StationVisualState.Paused,
        StationVisualState.Stopping,
        StationVisualState.StopFailed,
        -> session?.let { running ->
            estimatedCurrentAmountMinor(running, nowMillis)?.let {
                val combined = Math.addExact(
                    it,
                    gamingSessionAddonBillableTotalMinor(sessionAddons),
                )
                "Estimated ${combined.asRupees()}"
            }
        } ?: "Session in progress"
        StationVisualState.PaymentDue,
        StationVisualState.SendPending,
        StationVisualState.SendRejected,
        -> "Send to POS"
        StationVisualState.CancellationRequired -> "Reason required"
        StationVisualState.Available -> availableStationPricingDescription(station, packages)
        StationVisualState.Disabled -> "Disabled"
        StationVisualState.BillingMissing -> "Amount unavailable"
        StationVisualState.StartFailed -> "Evidence retained"
        StationVisualState.Unavailable -> "Refresh required"
    }
    val missingTariff = session == null && supportingValue == "Fixed-price tariff not synced"
    val tileStatusLabel = when {
        missingTariff -> if (dense) "Sync" else if (compact) "Sync rates" else "Rates unavailable"
        dense -> when (presentation.state) {
            StationVisualState.Available -> "Ready"
            StationVisualState.PaymentDue -> "Due"
            StationVisualState.Overtime -> "Overtime"
            StationVisualState.StartFailed, StationVisualState.StopFailed,
            StationVisualState.SendRejected, StationVisualState.BillingMissing -> "Review"
            StationVisualState.CancellationRequired -> "Resolve"
            StationVisualState.SendPending -> "Sending"
            StationVisualState.Unavailable -> "Check"
            else -> presentation.statusLabel
        }
        else -> presentation.statusLabel
    }
    val tileStatusColor = if (missingTariff) MaterialTheme.colorScheme.operationalWarning else accent


    val highlighted = selected || focused
    Column(
        modifier.fillMaxWidth().heightIn(min = when {
            compactHeight -> 60.dp
            dense -> 98.dp
            compact -> 154.dp
            else -> 178.dp
        })
            .clip(Radius.shapeLg)
            .background(
                Brush.verticalGradient(
                    if (highlighted) listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.colorScheme.surface,
                    ) else listOf(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .border(if (highlighted) 2.dp else 1.dp, borderColor, Radius.shapeLg)
            .clickable(role = Role.Button, onClick = onSelect)
            .testTag("gaming-station-" + station.id)
            .semantics {
                this.selected = selected
                contentDescription = if (startsSessionOnTap) {
                    station.name + ". Available. Start session. " + supportingValue
                } else if (session == null) {
                    "${station.name}. ${if (missingTariff) "Rates unavailable" else presentation.statusLabel}. " +
                        "$primaryValue. $supportingValue. Select station"
                } else {
                    station.name + ". " + presentation.statusLabel + ". Select station controls"
                }
            }
            .padding(
                horizontal = Spacing.sm,
                vertical = if (dense || compactHeight) Spacing.xs else Spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 4.dp),
    ) {
        if (compactHeight) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GamingStationArtwork(station.type, Modifier.size(28.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        compactStationTitle(station.name),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(tileStatusColor) }
                        Text(
                            tileStatusLabel,
                            color = tileStatusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session != null) {
                            Spacer(Modifier.weight(1f))
                            RemoteSensitiveContent {
                                Text(
                                    primaryValue,
                                    color = accent,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().height(
                    if (dense) 32.dp else if (compact) 76.dp else 96.dp,
                )
                    .background(
                        Brush.radialGradient(
                            if (highlighted) listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f), Color.Transparent,
                            ) else listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), Color.Transparent),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                GamingStationArtwork(station.type, Modifier.fillMaxSize())
            }
            Text(
                if (dense) compactStationTitle(station.name) else station.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (compact) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(tileStatusColor) }
                    Text(
                        tileStatusLabel,
                        color = tileStatusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!missingTariff && !dense) Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = if (selected || focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    OperationalStatusBadge(
                        label = presentation.statusLabel,
                        tone = presentation.tone,
                        icon = presentation.statusIcon,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = if (selected || focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (session != null) {
                RemoteSensitiveContent {
                    Text(
                        primaryValue,
                        color = accent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (!compact) {
                Text(
                    supportingValue,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

}

@Composable
internal fun GamingCommandAttentionDialog(
    state: GamingUiState,
    access: GamingAccess,
    activeTerminalPurpose: String?,
    startTerminalBlockMessage: String?,
    focusSessionId: String?,
    focusStationId: String?,
    orphanedExtensionActions: List<PackageExtensionActionUi>,
    onDismiss: () -> Unit,
    onDismissFocus: () -> Unit,
    onRefresh: () -> Unit,
    onReviewOrphan: (PackageExtensionActionUi) -> Unit,
    onReviewRejectedSession: (GameSession) -> Unit,
    onReviewCancellations: () -> Unit,
    onReviewPayments: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Gaming action centre") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (!access.canManageSessions) {
                    ViewOnlyNotice(
                        if (access.writeBlockedByMissingShiftRead) {
                            GAMING_SHIFT_ACCESS_REQUIRED_MESSAGE
                        } else {
                            VIEW_ONLY_MESSAGE
                        },
                    )
                }
                startTerminalBlockMessage?.let { message ->
                    OperationalBanner(
                        title = if (activeTerminalPurpose == TerminalPurpose.CAFE_POS) {
                            "Gaming starts are disabled on a POS-only till"
                        } else {
                            "Terminal purpose needs verification"
                        },
                        detail = message,
                        tone = UiTone.Warning,
                        icon = Icons.Filled.Warning,
                    )
                }
                if (focusSessionId != null && focusStationId != null) {
                    GamingNotificationFocusBanner(
                        sessionStillActive = state.sessions.any {
                            it.id == focusSessionId && it.stationId == focusStationId &&
                                it.status in setOf("starting", "active", "paused", "stopping")
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
                            onClick = onRefresh,
                            intent = ActionIntent.Secondary,
                            enabled = !state.refreshing,
                            busy = state.refreshing,
                            leadingIcon = Icons.Filled.Refresh,
                        )
                    }
                }
                orphanedExtensionActions.forEach { action ->
                    OrphanPackageExtensionBanner(
                        action = action,
                        activeShiftId = state.activeShiftId,
                        canWrite = access.canManageSessions,
                        busy = state.busyStationId != null,
                        onReview = { onReviewOrphan(action) },
                    )
                }
                state.rejectedSessionsForReview.forEach { session ->
                    val stationName = state.stations.firstOrNull { it.id == session.stationId }?.name
                        ?: "Unknown station"
                    OperationalBanner(
                        title = if (session.localState == GamingSessionState.START_REJECTED) {
                            "Start rejected on $stationName"
                        } else {
                            "Stop rejected on $stationName"
                        },
                        detail = session.lastError?.takeIf(String::isNotBlank)
                            ?: "The saved session action needs review on its station.",
                        tone = UiTone.Danger,
                        icon = Icons.Filled.Error,
                    ) {
                        ErpButton(
                            text = "Review station",
                            onClick = { onReviewRejectedSession(session) },
                            intent = ActionIntent.Warning,
                            leadingIcon = Icons.Filled.Visibility,
                        )
                    }
                }
                if (state.needsCancellation.isNotEmpty()) {
                    OperationalBanner(
                        title = "${state.needsCancellation.size} stopped ${sessionWord(state.needsCancellation.size)} need resolution",
                        detail = "Zero-value sessions cannot become POS bills. Review and cancel each with a reason.",
                        tone = UiTone.Danger,
                        icon = Icons.Filled.Error,
                    ) {
                        ErpButton(
                            text = "Review",
                            onClick = onReviewCancellations,
                            intent = ActionIntent.Destructive,
                            enabled = access.canManageSessions && state.busyStationId == null,
                        )
                    }
                }
                if (state.readyForPos.isNotEmpty()) {
                    val total = state.readyForPos.sumOf(state::pendingBillSnapshotMinor)
                    OperationalBanner(
                        title = "${state.readyForPos.size} ${sessionWord(state.readyForPos.size)} awaiting payment",
                        detail = "Estimated ${total.asRupees()} combined · POS confirms each final total",
                        tone = UiTone.Warning,
                        icon = Icons.Filled.Payments,
                    ) {
                        ErpButton(
                            text = "Review & send",
                            onClick = onReviewPayments,
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
        },
        confirmButton = {
            ErpButton(
                text = "Done",
                onClick = onDismiss,
                intent = ActionIntent.Secondary,
            )
        },
    )
}

@Composable
private fun GamingFilterRow(
    filters: List<StationFilter>,
    stations: List<Station>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("gaming-filter-chips"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(filters, key = StationFilter::id) { filter ->
            val count = if (filter.id == "all") stations.size
                else stations.count { stationFilterId(it.type) == filter.id }
            FilterChip(
                selected = selected == filter.id,
                onClick = { onSelect(filter.id) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(filter.label)
                        Text(count.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                },
                colors = gamingPackageChipColors(),
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("gaming-filter-${filter.id}"),
            )
        }
    }
}

@Composable
private fun GamingCompactBoardHeader(
    state: GamingUiState,
    filters: List<StationFilter>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val active = operationalActiveGamingSessionCount(state.sessions)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Text(
                "${state.stations.size} stations · $active active",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        GamingFilterRow(
            filters = filters,
            stations = state.stations,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun GamingStationCard(
    station: Station,
    session: GameSession?,
    packageExtensionAction: PackageExtensionActionUi?,
    sessionAddons: List<GamingSessionAddonUi> = emptyList(),
    wallClock: State<Long>,
    actionInProgress: Boolean,
    busyHere: Boolean,
    focused: Boolean,
    canWrite: Boolean,
    canReconcileLegacy: Boolean,
    activeShiftId: String?,
    activeShiftServerConfirmed: Boolean,
    activeShiftAllowsQueuedStart: Boolean = false,
    startTerminalBlockMessage: String? = null,
    online: Boolean = true,
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
    onAddItems: (GameSession) -> Unit = {},
    onVoidAddon: (GameSession, GamingSessionAddonUi) -> Unit = { _, _ -> },
    onReviewRejectedAddon: (String) -> Unit = {},
    onManageParticipants: (GameSession) -> Unit = {},
    onPauseResume: (GameSession) -> Unit = {},
    showActiveSessionActions: Boolean = true,
    showActiveStopAction: Boolean = true,
    showStartingActions: Boolean = true,
    pinActiveSessionActions: Boolean = false,
    compactDetail: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Only operational play ticks. Paused clocks use the server's captured
    // pause instant, including after navigation or a process restart.
    val shouldTick = session?.status == "active" ||
        (session?.status == "starting" && session.localState == GamingSessionState.START_PENDING)
    // Re-capture when the authoritative status changes. Keying only by id made
    // an active -> paused transition jump back to the instant the card first
    // entered composition instead of freezing when the pause was observed.
    val frozenMillis = remember(session?.id, session?.status) { System.currentTimeMillis() }

    val hasActiveSessionAddons = sessionAddons.any {
        !it.voided && !it.isRejectedLocalAdd()
    }
    // The outer card owns actions/authority, not the per-second clock. Only a
    // real presentation change (including crossing the overtime boundary)
    // should recompose its header, extension choices and action buttons.
    val presentation by remember(
        station,
        session,
        wallClock,
        shouldTick,
        frozenMillis,
        hasActiveSessionAddons,
    ) {
        derivedStateOf(structuralEqualityPolicy()) {
            stationPresentation(
                station,
                session,
                if (shouldTick) wallClock.value else frozenMillis,
                hasActiveAddons = hasActiveSessionAddons,
            )
        }
    }
    // Available cards contain one short action and should not consume the same
    // height as active/payment cards with timers, billing and recovery copy.
    // Keep the operational states roomy while fitting more ready stations on
    // an 11-inch 16:10 tablet without shrinking touch targets.
    val minimumCardHeight = if (presentation.state == StationVisualState.Available) {
        224.dp
    } else {
        260.dp
    }
    val stationIcon = stationTypeIcon(station.type)
    // Once the exact stale-session protocol owns this row, ordinary session,
    // item, void, and POS actions must not race its protected owner review.
    // Refresh remains available at the board level and is the only retry path.
    val actionsEnabled = gamingSessionActionsEnabled(
        canWrite = canWrite,
        actionInProgress = actionInProgress,
        lastError = session?.lastError,
    )
    val authority = session?.authority(activeShiftId)
    val ownsSession = authority == GamingSessionAuthority.CURRENT_SHIFT
    val canStopSession = session?.resolvedStopShiftId(
        activeShiftId = activeShiftId,
        activeShiftServerConfirmed = activeShiftServerConfirmed,
        online = online,
    ) != null
    val matchingExtensions = matchingPackageExtensions(session, station, packages)
    val alreadySettledAtPos = session?.orderId != null
    val packageBillingSnapshotMissing = session?.isPackageBilling() == true &&
        !session.hasLockedPackageExtensionSnapshot()
    val matchingPackageExtensionUnavailable = session?.isPackageBilling() == true &&
        !packageBillingSnapshotMissing && matchingExtensions.isEmpty()
    val unresolvedAddonActions = sessionAddons.filter {
        it.localState !in setOf(null, GamingSessionAddonActionState.CONFIRMED)
    }
    val cancellationAddonBlock = sessionCancellationAddonBlockMessage(sessionAddons)
        ?: if (unresolvedAddonActions.isNotEmpty()) {
            "Saved Gaming item actions must finish syncing or be reviewed before voiding the whole session."
        } else {
            null
        }

    if (pinActiveSessionActions) {
        val hasPinnedActions = packageExtensionAction == null && session != null &&
            presentation.state in setOf(
                StationVisualState.Starting,
                StationVisualState.Active,
                StationVisualState.Overtime,
                StationVisualState.Paused,
                StationVisualState.StopFailed,
            )
        if (hasPinnedActions) {
            Column(modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = Spacing.xs),
                ) {
                    item(key = session.id) {
                        GamingStationCard(
                            station = station,
                            session = session,
                            packageExtensionAction = packageExtensionAction,
                            sessionAddons = sessionAddons,
                            wallClock = wallClock,
                            actionInProgress = actionInProgress,
                            busyHere = busyHere,
                            focused = focused,
                            canWrite = canWrite,
                            canReconcileLegacy = canReconcileLegacy,
                            activeShiftId = activeShiftId,
                            activeShiftServerConfirmed = activeShiftServerConfirmed,
                            activeShiftAllowsQueuedStart = activeShiftAllowsQueuedStart,
                            startTerminalBlockMessage = startTerminalBlockMessage,
                            online = online,
                            packages = packages,
                            hasTransferTarget = hasTransferTarget,
                            onStart = onStart,
                            onStop = onStop,
                            onSend = onSend,
                            onCancelUnbilled = onCancelUnbilled,
                            onExtendTimer = onExtendTimer,
                            onExtendPackage = onExtendPackage,
                            onTransfer = onTransfer,
                            onReconcile = onReconcile,
                            onRepairBilling = onRepairBilling,
                            onResolveLegacyStart = onResolveLegacyStart,
                            onDiscardPackageExtension = onDiscardPackageExtension,
                            onAddItems = onAddItems,
                            onVoidAddon = onVoidAddon,
                            onReviewRejectedAddon = onReviewRejectedAddon,
                            onPauseResume = onPauseResume,
                            showActiveSessionActions = false,
                            showActiveStopAction = showActiveStopAction,
                            showStartingActions = false,
                            // This recursive card renders the selected
                            // workspace summary. Preserve the caller's compact
                            // contract so the persistent dial remains the only
                            // elapsed-time display.
                            compactDetail = compactDetail,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (presentation.state == StationVisualState.Starting) {
                    GamingStartingSessionActions(
                        station = station,
                        session = session,
                        actionsEnabled = actionsEnabled,
                        ownsSession = ownsSession,
                        canStopSession = canStopSession,
                        busyHere = busyHere,
                        onAddItems = onAddItems,
                        onManageParticipants = onManageParticipants,
                        onStop = onStop,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    )
                } else {
                    GamingActiveSessionActions(
                        session = session,
                        presentationState = presentation.state,
                        actionsEnabled = actionsEnabled,
                        ownsSession = ownsSession,
                        online = online,
                        alreadySettledAtPos = alreadySettledAtPos,
                        packageBillingSnapshotMissing = packageBillingSnapshotMissing,
                        matchingExtensions = matchingExtensions,
                        hasTransferTarget = hasTransferTarget,
                        canStopSession = canStopSession,
                        busyHere = busyHere,
                        onAddItems = onAddItems,
                        onExtendTimer = onExtendTimer,
                        onExtendPackage = onExtendPackage,
                        onTransfer = onTransfer,
                        onPauseResume = onPauseResume,
                        onStop = onStop,
                        onManageParticipants = onManageParticipants,
                        showStopAction = showActiveStopAction,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xs),
            ) {
                item(key = station.id) {
                    GamingStationCard(
                        station = station,
                        session = session,
                        packageExtensionAction = packageExtensionAction,
                        sessionAddons = sessionAddons,
                        wallClock = wallClock,
                        actionInProgress = actionInProgress,
                        busyHere = busyHere,
                        focused = focused,
                        canWrite = canWrite,
                        canReconcileLegacy = canReconcileLegacy,
                        activeShiftId = activeShiftId,
                        activeShiftServerConfirmed = activeShiftServerConfirmed,
                        activeShiftAllowsQueuedStart = activeShiftAllowsQueuedStart,
                        startTerminalBlockMessage = startTerminalBlockMessage,
                        online = online,
                        packages = packages,
                        hasTransferTarget = hasTransferTarget,
                        onStart = onStart,
                        onStop = onStop,
                        onSend = onSend,
                        onCancelUnbilled = onCancelUnbilled,
                        onExtendTimer = onExtendTimer,
                        onExtendPackage = onExtendPackage,
                        onTransfer = onTransfer,
                        onReconcile = onReconcile,
                        onRepairBilling = onRepairBilling,
                        onResolveLegacyStart = onResolveLegacyStart,
                        onDiscardPackageExtension = onDiscardPackageExtension,
                        onAddItems = onAddItems,
                        onVoidAddon = onVoidAddon,
                        onReviewRejectedAddon = onReviewRejectedAddon,
                        onManageParticipants = onManageParticipants,
                        onPauseResume = onPauseResume,
                    )
                }
            }
        }
        return
    }

    val packageSelection = session?.let(::gamingPackageSelectionLabel)
    val pricingDescription = when {
        session?.isPackageBilling() == true -> buildString {
            append(packageSelection ?: "Fixed package")
            append(" · ")
            append(session.amountMinor?.let { "${it.asRupees()} fixed total" }
                ?: "locked total unavailable")
        }
        session?.ratePerHourMinor != null && requiresCanonicalGamingTariff(station.type) ->
            "Legacy locked rate ${session.ratePerHourMinor.asRupees()} per hour"
        session?.ratePerHourMinor != null -> "${session.ratePerHourMinor.asRupees()} per hour"
        session != null -> "Locked session rate unavailable"
        else -> availableStationPricingDescription(station, packages)
    }

    Column(
        modifier.fillMaxWidth().then(
            if (compactDetail) Modifier else Modifier.heightIn(min = minimumCardHeight),
        )
            // Operational state belongs in the badge, icon and copy. Keeping
            // the complete card neutral prevents payment/warning states from
            // turning the board into a field of competing colour blocks.
            .clip(Radius.shapeLg)
            .background(
                if (compactDetail) {
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainerLow))
                } else {
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
                },
            )
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    compactDetail && presentation.state in setOf(
                        StationVisualState.PaymentDue,
                        StationVisualState.SendRejected,
                    ) -> MaterialTheme.colorScheme.primary.copy(alpha = 0.68f)
                    presentation.state in setOf(StationVisualState.Active, StationVisualState.Overtime) ->
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                Radius.shapeLg,
            )
            .semantics {
                contentDescription = "${station.name}. ${presentation.statusLabel}. " +
                    "$pricingDescription."
            }
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (!compactDetail) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    station.name,
                    color = MaterialTheme.colorScheme.onSurface,
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
        }
        // Keep rate details out of the badge-constrained title row. Long
        // names such as "Racing Simulator 1" remain identifiable and their
        // price never disappears behind an ellipsis on a four-column tablet.
        Text(
            "${stationTypeLabel(station.type)} · $pricingDescription",
            color = if (pricingDescription.contains("unavailable") || pricingDescription.contains("not synced")) {
                MaterialTheme.colorScheme.operationalWarning
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
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
            if (!compactDetail) {
                Box(
                    Modifier.size(40.dp).clip(Radius.shapeMd).background(statusIconBackground(presentation.tone)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(stationIcon, contentDescription = null, tint = statusColor(presentation.tone), modifier = Modifier.size(23.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StationBody(
                    presentation = presentation,
                    station = station,
                    session = session,
                    wallClock = wallClock,
                    shouldTick = shouldTick,
                    frozenMillis = frozenMillis,
                    activeShiftId = activeShiftId,
                    activeShiftServerConfirmed = activeShiftServerConfirmed,
                    online = online,
                    combinedBillSnapshotMinor = session?.let {
                        Math.addExact(
                            it.amountMinor ?: 0L,
                            gamingSessionAddonBillableTotalMinor(sessionAddons),
                        )
                    },
                    showPrimaryValue = !compactDetail ||
                        (presentation.state == StationVisualState.Paused && session?.pausedAt == null),
                )
            }
        }

        if (session != null && sessionAddons.isNotEmpty()) {
            GamingSessionAddonSummary(
                session = session,
                addons = sessionAddons,
                actionsEnabled = actionsEnabled && ownsSession,
                onVoid = { onVoidAddon(session, it) },
                onReviewRejected = onReviewRejectedAddon,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    startTerminalBlockMessage != null -> "Change terminal"
                    activeShiftId == null -> "Open POS shift to start"
                    !activeShiftServerConfirmed && !activeShiftAllowsQueuedStart -> "Waiting for shift sync"
                    else -> "Start session"
                },
                onClick = onStart,
                enabled = actionsEnabled && startTerminalBlockMessage == null &&
                    activeShiftId != null && (activeShiftServerConfirmed || activeShiftAllowsQueuedStart),
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
            -> if (session != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (alreadySettledAtPos) {
                        Text(
                            "This session is already linked to a paid POS order. Extra paid time is locked; Stop still records the final play time without creating another bill.",
                            color = MaterialTheme.colorScheme.operationalWarning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (packageBillingSnapshotMissing) {
                        Text(
                            "Locked package timer or total is unavailable. Refresh Gaming; if it remains missing, ask the protected owner to review billing before extending.",
                            color = MaterialTheme.colorScheme.operationalWarning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (matchingPackageExtensionUnavailable) {
                        Text(
                            "No active extension matches this session's original package variant. Refresh Gaming or ask a manager to check the package catalogue.",
                            color = MaterialTheme.colorScheme.operationalWarning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (showActiveSessionActions) {
                        GamingActiveSessionActions(
                            session = session,
                            presentationState = presentation.state,
                            actionsEnabled = actionsEnabled,
                            ownsSession = ownsSession,
                            online = online,
                            alreadySettledAtPos = alreadySettledAtPos,
                            packageBillingSnapshotMissing = packageBillingSnapshotMissing,
                            matchingExtensions = matchingExtensions,
                            hasTransferTarget = hasTransferTarget,
                            canStopSession = canStopSession,
                            busyHere = busyHere,
                            onAddItems = onAddItems,
                            onExtendTimer = onExtendTimer,
                            onExtendPackage = onExtendPackage,
                            onTransfer = onTransfer,
                            onPauseResume = onPauseResume,
                            onStop = onStop,
                            onManageParticipants = onManageParticipants,
                            showStopAction = showActiveStopAction,
                        )
                    }
                }
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
            -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                cancellationAddonBlock?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val crossTerminalBlocked = !ownsSession &&
                    !(canReconcileLegacy && activeShiftId != null)
                if (!ownsSession) {
                    Text(
                        CROSS_TERMINAL_PAYMENT_GUIDANCE,
                        color = MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!crossTerminalBlocked) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { session?.let(onCancelUnbilled) },
                        enabled = actionsEnabled && session != null &&
                            cancellationAddonBlock == null &&
                            (ownsSession || canReconcileLegacy),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.operationalDanger),
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                        modifier = Modifier.weight(0.8f).heightIn(min = 48.dp),
                    ) { Text("Void", maxLines = 1) }
                    ErpButton(
                        text = when {
                            ownsSession && presentation.state == StationVisualState.SendRejected -> "Retry send"
                            ownsSession -> "Send to POS"
                            canReconcileLegacy && activeShiftId != null -> "Reconcile closed shift"
                            else -> "Reconciliation unavailable"
                        },
                        onClick = {
                            session?.let { if (ownsSession) onSend(it) else onReconcile(it) }
                        },
                        enabled = actionsEnabled && session != null &&
                            unresolvedAddonActions.isEmpty() &&
                            (ownsSession || (canReconcileLegacy && activeShiftId != null)),
                        busy = busyHere,
                        modifier = Modifier.weight(1.7f),
                    )
                }
            }

            StationVisualState.CancellationRequired -> Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                cancellationAddonBlock?.let { reason ->
                    Text(
                        reason,
                        color = MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                ErpButton(
                    text = "Cancel with reason",
                    onClick = { session?.let(onCancelUnbilled) },
                    enabled = actionsEnabled && session != null &&
                        cancellationAddonBlock == null &&
                        (ownsSession || canReconcileLegacy),
                    busy = busyHere,
                    intent = ActionIntent.Destructive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

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

            StationVisualState.Starting -> if (showStartingActions && session != null) {
                GamingStartingSessionActions(
                    station = station,
                    session = session,
                    actionsEnabled = actionsEnabled,
                    ownsSession = ownsSession,
                    canStopSession = canStopSession,
                    busyHere = busyHere,
                    onAddItems = onAddItems,
                    onManageParticipants = onManageParticipants,
                    onStop = onStop,
                )
            }

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
private fun GamingStartingSessionActions(
    station: Station,
    session: GameSession,
    actionsEnabled: Boolean,
    ownsSession: Boolean,
    canStopSession: Boolean,
    busyHere: Boolean,
    onAddItems: (GameSession) -> Unit,
    onManageParticipants: (GameSession) -> Unit,
    onStop: (GameSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (stationFilterId(station.type) == "ps5" && session.isLocallyPendingPs5PackageStart()) {
            ErpButton(
                text = "Players",
                onClick = { onManageParticipants(session) },
                enabled = actionsEnabled && ownsSession && session.orderId == null,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Filled.People,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ErpButton(
            text = "Add drinks & snacks",
            onClick = { onAddItems(session) },
            enabled = actionsEnabled && ownsSession && session.orderId == null,
            intent = ActionIntent.Secondary,
            leadingIcon = Icons.Filled.RestaurantMenu,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(Modifier.fillMaxWidth()) {
            ErpButton(
                text = "Stop & save end",
                onClick = { onStop(session) },
                enabled = actionsEnabled && canStopSession && session.canRequestStop(),
                busy = busyHere,
                intent = ActionIntent.Destructive,
                leadingIcon = Icons.Filled.StopCircle,
                modifier = Modifier.fillMaxWidth().testTag("gaming-primary-action-host"),
            )
            GamingPrimaryActionSupportArea()
        }
    }
}

@Composable
private fun GamingActiveSessionActions(
    session: GameSession,
    presentationState: StationVisualState,
    actionsEnabled: Boolean,
    ownsSession: Boolean,
    online: Boolean,
    alreadySettledAtPos: Boolean,
    packageBillingSnapshotMissing: Boolean,
    matchingExtensions: List<GamingPackage>,
    hasTransferTarget: Boolean,
    canStopSession: Boolean,
    busyHere: Boolean,
    onAddItems: (GameSession) -> Unit,
    onExtendTimer: (GameSession) -> Unit,
    onExtendPackage: (GameSession, List<GamingPackage>) -> Unit,
    onTransfer: (GameSession) -> Unit,
    onPauseResume: (GameSession) -> Unit,
    onStop: (GameSession) -> Unit,
    onManageParticipants: (GameSession) -> Unit,
    showStopAction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val pauseSupportMessage = when {
            !online -> "Reconnect to pause or resume safely across devices."
            session.pauseVersion == null ->
                "Refresh Gaming after the server update to enable pause controls."
            session.status != "paused" && !session.pauseAvailable ->
                "Pause is not enabled yet. The owner must update all tablets before enabling it."
            else -> null
        }
        val showsParticipantAction =
            session.status in setOf("active", "paused") &&
            (session.effectivePackageStationType ?: session.packageStationTypeSnapshot) == "ps5"
        if (showsParticipantAction) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ErpButton(
                    text = "Players",
                    onClick = { onManageParticipants(session) },
                    enabled = actionsEnabled && ownsSession && session.orderId == null,
                    intent = ActionIntent.Secondary,
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                    modifier = Modifier.weight(1f),
                )
                ErpButton(
                    text = "Add drinks",
                    onClick = { onAddItems(session) },
                    enabled = actionsEnabled && ownsSession && session.orderId == null,
                    intent = ActionIntent.Secondary,
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ErpButton(
                text = "Add drinks & snacks",
                onClick = { onAddItems(session) },
                enabled = actionsEnabled && ownsSession &&
                    session.orderId == null && session.status in setOf("active", "paused"),
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Filled.RestaurantMenu,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Three controls share one row. Narrow grid cards keep complete
            // words instead of spending their text width on decorative icons;
            // wide command panels retain the icons.
            val compactActions = maxWidth < 360.dp
            val actionSpacing = if (compactActions) Spacing.xs else Spacing.sm
            Row(horizontalArrangement = Arrangement.spacedBy(actionSpacing)) {
                ErpButton(
                    text = if (compactActions || session.isPackageBilling()) "Extend" else "+30 min",
                    onClick = {
                        if (session.isPackageBilling()) onExtendPackage(session, matchingExtensions)
                        else onExtendTimer(session)
                    },
                    enabled = actionsEnabled && ownsSession &&
                        !alreadySettledAtPos &&
                        !packageBillingSnapshotMissing &&
                        (!session.isPackageBilling() || matchingExtensions.isNotEmpty()),
                    intent = ActionIntent.Secondary,
                    leadingIcon = if (compactActions) null else Icons.Filled.Add,
                    contentPadding = PaddingValues(horizontal = actionSpacing),
                    modifier = Modifier.weight(1f),
                )
                ErpButton(
                    text = "Transfer",
                    onClick = { onTransfer(session) },
                    enabled = actionsEnabled && ownsSession && hasTransferTarget,
                    intent = ActionIntent.Secondary,
                    leadingIcon = if (compactActions) null else Icons.Filled.SwapHoriz,
                    contentPadding = PaddingValues(horizontal = actionSpacing),
                    modifier = Modifier.weight(1f),
                )
                ErpButton(
                    text = if (session.status == "paused") "Resume" else "Pause",
                    onClick = { onPauseResume(session) },
                    enabled = actionsEnabled && ownsSession && online &&
                        pauseActionError(session, session.status != "paused", "Operator pause") == null,
                    intent = ActionIntent.Secondary,
                    leadingIcon = if (compactActions) null
                    else if (session.status == "paused") Icons.Filled.PlayArrow else Icons.Filled.PauseCircle,
                    contentPadding = PaddingValues(horizontal = actionSpacing),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showStopAction) {
            Column(Modifier.fillMaxWidth()) {
                GamingStopSessionAction(
                    session = session,
                    presentationState = presentationState,
                    enabled = actionsEnabled && canStopSession,
                    busy = busyHere,
                    onStop = onStop,
                    modifier = Modifier.fillMaxWidth(),
                )
                GamingPrimaryActionSupportArea {
                    pauseSupportMessage?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = Spacing.sm),
                        )
                    }
                }
            }
        } else if (pauseSupportMessage != null) {
            Text(
                pauseSupportMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun GamingStopSessionAction(
    session: GameSession,
    presentationState: StationVisualState,
    enabled: Boolean,
    busy: Boolean,
    onStop: (GameSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    ErpButton(
        text = if (presentationState == StationVisualState.StopFailed) "Retry stop" else "Stop & calculate",
        onClick = { onStop(session) },
        enabled = enabled,
        busy = busy,
        intent = ActionIntent.Destructive,
        leadingIcon = Icons.Filled.StopCircle,
        modifier = modifier.testTag("gaming-primary-action-host"),
    )
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
private fun GamingCleanupAcknowledgementBanner(
    state: GamingUiState,
    onRefresh: () -> Unit,
) {
    val content = gamingCleanupAcknowledgementBannerContent(state)
    OperationalBanner(
        title = content.title,
        detail = content.detail,
        tone = if (content.requiresOwnerReview) UiTone.Danger else UiTone.Warning,
        icon = if (content.requiresOwnerReview) Icons.Filled.Warning else Icons.Filled.CloudUpload,
    ) {
        ErpButton(
            text = if (state.refreshing) "Checking…" else "Retry acknowledgement",
            onClick = onRefresh,
            enabled = !state.refreshing,
            busy = state.refreshing,
            intent = ActionIntent.Secondary,
            leadingIcon = Icons.Filled.Refresh,
        )
    }
}

internal data class GamingCleanupAcknowledgementBannerContent(
    val title: String,
    val detail: String,
    val requiresOwnerReview: Boolean,
)

internal fun gamingCleanupAcknowledgementBannerContent(
    state: GamingUiState,
): GamingCleanupAcknowledgementBannerContent {
    val pending = state.cleanupAcknowledgementsPending
    val ownerReview = pending.firstOrNull {
        it.detail == GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_REVIEW_REQUIRED.persistedMessage
    }
    val singleStationName = pending.singleOrNull()?.let { row ->
        state.stations.firstOrNull { it.id == row.stationId }?.name
    }
    return GamingCleanupAcknowledgementBannerContent(
        title = when {
            ownerReview != null -> "Cleanup acknowledgement needs owner review"
            singleStationName != null -> "$singleStationName cleanup receipt is pending"
            else -> "${pending.size} cleanup receipts are pending"
        },
        detail = ownerReview?.detail
            ?: pending.firstOrNull()?.detail
            ?: GamingCleanupWorkflowStatus.ACKNOWLEDGEMENT_PENDING.persistedMessage,
        requiresOwnerReview = ownerReview != null,
    )
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
            color = if (rejected) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.operationalWarning,
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
    wallClock: State<Long>,
    shouldTick: Boolean,
    frozenMillis: Long,
    activeShiftId: String?,
    activeShiftServerConfirmed: Boolean,
    online: Boolean,
    combinedBillSnapshotMinor: Long?,
    showPrimaryValue: Boolean = true,
) {
    // Read the 1 Hz state in this small body rather than invalidating the
    // complete station card. Paused/stopping sessions retain their previous
    // frozen/captured-time behavior; server data still determines billing.
    val nowMillis = if (shouldTick) wallClock.value else frozenMillis
    val startedLabel = remember(session?.startAt, session?.timerMinutes) {
        val started = session?.startAt?.businessClockTime()
        buildString {
            if (started != null) append("Started $started")
            session?.timerMinutes?.let { minutes ->
                if (isNotEmpty()) append(" · ")
                append("${minutes}m booking")
            }
        }.ifEmpty { "Session in progress" }
    }
    val cleanupWorkflowMessage = gamingCleanupWorkflowMessageOrNull(session?.lastError)
    when (presentation.state) {
        StationVisualState.Available -> {
            Text("Ready for a new session", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Choose the customer and duration when you start.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        StationVisualState.Disabled -> {
            Text("Station disabled", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text("Ask a manager to enable it before taking a booking.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        StationVisualState.StartFailed -> {
            Text(
                if (session?.requiresProtectedStartReview() == true) {
                    "Rejected start evidence retained"
                } else {
                    "Start was not accepted"
                },
                color = MaterialTheme.colorScheme.operationalDanger,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (showPrimaryValue) {
                if (presentation.state == StationVisualState.Paused && session?.pausedAt == null) {
                    Text(
                        "Timer paused",
                        color = MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    val elapsed = session?.let { elapsedMillis(it, nowMillis) } ?: 0L
                    NumericValue(
                        value = formatElapsed(elapsed),
                        color = if (presentation.state in setOf(StationVisualState.Overtime, StationVisualState.StopFailed)) {
                            MaterialTheme.colorScheme.operationalDanger
                        } else if (presentation.state in setOf(StationVisualState.Starting, StationVisualState.Paused)) {
                            MaterialTheme.colorScheme.operationalWarning
                        } else {
                            MaterialTheme.colorScheme.operationalGood
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
            Text(
                startedLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                cleanupWorkflowMessage ?: when (presentation.state) {
                    StationVisualState.Starting ->
                        "Saved on this tablet and waiting to sync. Play time and the booked timer use the captured start time."
                    StationVisualState.Overtime -> if (session?.isPackageBilling() == true) {
                        "Booked time has ended. Add the matching paid extension or stop now; the selected package remains locked."
                    } else {
                        "Booked time has ended. Stop when play finishes."
                    }
                    StationVisualState.Paused -> "Paused on the server. Play time is frozen; resume or stop when ready."
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
                    else -> if (session?.isPackageBilling() == true) {
                        "${gamingPackageSelectionLabel(session) ?: "Fixed package"}. The displayed package total was locked when the session started."
                    } else {
                        "Final elapsed-time charge is calculated by the server when stopped."
                    }
                },
                color = if (cleanupWorkflowMessage != null) {
                    MaterialTheme.colorScheme.operationalWarning
                } else if (presentation.state in setOf(StationVisualState.Overtime, StationVisualState.StopFailed)) {
                    MaterialTheme.colorScheme.operationalDanger
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (
                    cleanupWorkflowMessage != null || presentation.state == StationVisualState.StopFailed ||
                    session?.legacyOriginalCapturedStopAt != session?.endAt
                ) 4 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            sessionCustomerLabel(session)?.let { customer ->
                RemoteSensitiveContent {
                    Text(
                        customer,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                sessionAuthorityMessage(
                    running,
                    activeShiftId,
                    activeShiftServerConfirmed,
                    online,
                )?.let { ownership ->
                    Text(
                        ownership,
                        color = MaterialTheme.colorScheme.operationalWarning,
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
            RemoteSensitiveContent {
                if (showPrimaryValue) {
                    NumericValue(
                        value = (combinedBillSnapshotMinor ?: session?.amountMinor ?: 0L).asRupees(),
                        color = if (presentation.state == StationVisualState.CancellationRequired) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                if (showPrimaryValue) {
                    Text(
                        if (presentation.state == StationVisualState.CancellationRequired) {
                            "Stopped · ${session?.billableMinutes ?: 0} min"
                        } else {
                            "Estimated combined bill · stopped ${session?.billableMinutes ?: 0} min"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                sessionCustomerLabel(session)?.let { customer ->
                    Text(
                        customer,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    unbilledSessionDetail(presentation.state, session),
                    color = if (presentation.state == StationVisualState.SendRejected) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = if (presentation.state == StationVisualState.SendRejected) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                session?.let { ended ->
                    sessionAuthorityMessage(ended, activeShiftId)?.let { ownership ->
                        Text(
                            ownership,
                            color = MaterialTheme.colorScheme.operationalWarning,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        StationVisualState.BillingMissing -> {
            Text(
                "Billing data unavailable",
                color = MaterialTheme.colorScheme.operationalDanger,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This stopped session has no authoritative amount. It cannot be sent or voided safely.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                cleanupWorkflowMessage
                    ?: "Refresh Gaming. If the amount remains missing, ask the protected owner to repair the source record.",
                color = MaterialTheme.colorScheme.operationalWarning,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        StationVisualState.Unavailable -> {
            Text("Action unavailable", color = MaterialTheme.colorScheme.operationalDanger, fontWeight = FontWeight.SemiBold)
            Text("Refresh Gaming or ask a manager to review this station.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun stationPresentation(
    station: Station,
    session: GameSession?,
    nowMillis: Long,
    hasActiveAddons: Boolean = false,
): StationPresentation {
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
        if (session.amountMinor <= 0L && !hasActiveAddons) {
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

internal const val CROSS_TERMINAL_PAYMENT_GUIDANCE =
    "This payment belongs to another terminal. Finish it there while its source shift is open. " +
        "After that source shift closes, ask the protected owner to reconcile it into a current POS shift."

internal fun gamingSessionActionsEnabled(
    canWrite: Boolean,
    actionInProgress: Boolean,
    lastError: String?,
): Boolean = canWrite && !actionInProgress && gamingCleanupWorkflowMessageOrNull(lastError) == null

internal fun unbilledSessionDetail(state: StationVisualState, session: GameSession?): String {
    gamingCleanupWorkflowMessageOrNull(session?.lastError)?.let { return it }
    return when (state) {
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
    pendingBillSnapshotMinor: (GameSession) -> Long,
    actionLabel: (GameSession) -> String,
    actionEnabled: (GameSession) -> Boolean,
    actionDisabledReason: (GameSession) -> String? = { null },
    actionIntent: ActionIntent,
    busyStationId: String?,
    onDismiss: () -> Unit,
    onSelect: (GameSession) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        // Never impose a minimum wider than the available tablet content.
        // AlertDialog still uses its platform width on large screens while a
        // small tablet can safely contract the queue instead of clipping it.
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(sessions, key = GameSession::id) { session ->
                        val station = stations.firstOrNull { it.id == session.stationId }
                        Row(
                            Modifier.fillMaxWidth().clip(Radius.shapeMd).background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd)
                                .padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    station?.name ?: "Gaming session",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    "${session.billableMinutes ?: 0} min · " +
                                        "${pendingBillSnapshotMinor(session).asRupees()} estimated combined",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                sessionCustomerLabel(session)?.let { customer ->
                                    Text(
                                        customer,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                sessionAuthorityMessage(session, activeShiftId)?.let { ownership ->
                                        Text(
                                            ownership,
                                            color = MaterialTheme.colorScheme.operationalWarning,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                }
                                if (!actionEnabled(session)) {
                                    actionDisabledReason(session)?.let { reason ->
                                        Text(
                                            reason,
                                            color = MaterialTheme.colorScheme.operationalWarning,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                            if (actionEnabled(session)) {
                                ErpButton(
                                    text = actionLabel(session),
                                    onClick = { onSelect(session) },
                                    intent = actionIntent,
                                    enabled = busyStationId == null,
                                    busy = busyStationId == session.stationId,
                                )
                            }
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
private fun GamingAddonPickerDialog(
    items: List<MenuItemEntity>,
    onDismiss: () -> Unit,
    onSelect: (MenuItemEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        title = { Text("Add session items") },
        text = {
            if (items.isEmpty()) {
                DesignedEmptyState(
                    title = "No available items",
                    body = "Refresh the menu or ask a manager to enable a drink or snack product.",
                    icon = Icons.Filled.RestaurantMenu,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(items, key = MenuItemEntity::id) { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(Radius.shapeMd)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd)
                                .padding(Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${item.type.replaceFirstChar { it.titlecase(Locale.ROOT) }} · ${item.basePriceMinor.asRupees()}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            ErpButton(
                                text = "Choose",
                                onClick = { onSelect(item) },
                                intent = ActionIntent.Secondary,
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
internal fun GamingAddonReviewDialog(
    request: AddonReviewRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val unitPriceMinor = configuredUnitPriceMinor(request.item, request.variant, request.modifiers)
    val totalMinor = Math.multiplyExact(unitPriceMinor, request.qty.toLong())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        title = { Text("Review item before adding") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "No bill line is created until Confirm add is pressed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                SectionCard(
                    title = request.item.name,
                    subtitle = "${request.stationName} · session ${request.session.id.take(8)}",
                ) {
                    Text("Quantity · ${request.qty}")
                    Text("Unit price · ${unitPriceMinor.asRupees()}")
                    Text("Total · ${totalMinor.asRupees()}", fontWeight = FontWeight.Bold)
                    Text(
                        "Modifiers · ${request.modifiers.joinToString { "${it.modifier.name} × ${it.qty}" }.ifBlank { "None" }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    request.variant?.let {
                        Text("Variant · ${it.name}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    request.note?.trim()?.takeIf(String::isNotEmpty)?.let {
                        Text("Note · $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "The immutable item ledger and Sync receipt prevent duplicate retry charges; the server rechecks current catalogue pricing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            ErpButton(text = "Confirm add · ${totalMinor.asRupees()}", onClick = onConfirm)
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GamingAddonReasonDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selectedReasonId by rememberSaveable(title) { mutableStateOf<String?>(null) }
    var customReason by rememberSaveable(title) { mutableStateOf("") }
    val reason = resolvedVoidReason(selectedReasonId, customReason)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.92f)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
        properties = gamingImeAwareDialogProperties,
        title = { Text(title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                text = confirmLabel,
                onClick = { onConfirm(reason) },
                enabled = reason.length in 3..500,
                intent = ActionIntent.Destructive,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun GamingSessionAddonSummary(
    session: GameSession,
    addons: List<GamingSessionAddonUi>,
    actionsEnabled: Boolean,
    onVoid: (GamingSessionAddonUi) -> Unit,
    onReviewRejected: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeMd).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd).padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val activeTotal = gamingSessionAddonBillableTotalMinor(addons)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Session items", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            Text(activeTotal.asRupees(), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        }
        addons.forEach { addon ->
            val status = when {
                addon.voided -> "VOIDED"
                addon.localState == GamingSessionAddonActionState.REJECTED -> "REVIEW"
                addon.localState == GamingSessionAddonActionState.AMBIGUOUS -> "CHECKING"
                addon.localActionType == GamingSessionAddonActionType.VOID -> "VOID PENDING"
                addon.localState == GamingSessionAddonActionState.PENDING -> "SYNCING"
                else -> "SAVED"
            }
            val tone = when (status) {
                "VOIDED" -> UiTone.Neutral
                "REVIEW" -> UiTone.Danger
                "CHECKING", "VOID PENDING", "SYNCING" -> UiTone.Warning
                else -> UiTone.Success
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${addon.menuItemName} ×${addon.qty}",
                        color = if (addon.voided) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        if (addon.isRejectedLocalAdd()) {
                            "Not billable · ${addon.lineTotalMinor.asRupees()} requested"
                        } else {
                            addon.lineTotalMinor.asRupees()
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OperationalStatusBadge(label = status, tone = tone)
                when {
                    addon.localState == GamingSessionAddonActionState.REJECTED && addon.actionId != null ->
                        TextButton(
                            onClick = { onReviewRejected(addon.actionId) },
                            enabled = actionsEnabled,
                        ) { Text("Review") }
                    !addon.voided && addon.localActionType != GamingSessionAddonActionType.VOID &&
                        session.orderId == null &&
                        session.status in setOf("starting", "active", "paused", "stopping", "ended") ->
                        TextButton(onClick = { onVoid(addon) }, enabled = actionsEnabled) { Text("Void") }
                }
            }
            addon.lastError?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.operationalDanger, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StopSessionDialog(
    request: StopRequest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        title = { Text("Stop ${request.station.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "This ends billable time. The server will calculate the final duration and amount, then the " +
                        "station will remain blocked until the session is sent to POS or voided.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "If the tablet is temporarily offline, the stop request is saved and shown as pending until sync completes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val compactCustomLayout = useCompactVoidCustomLayout(selectedReasonId)
    val contentMaxHeight = voidDialogBodyMaxHeight(screenHeightDp, selectedReasonId)
    val normalized = resolvedVoidReason(selectedReasonId, customReason)
    val hasBillableAmount = amountMinor > 0L

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    packages: List<GamingPackage>,
    onDismiss: () -> Unit,
    onSelect: (Station) -> Unit,
) {
    var selectedId by rememberSaveable(sourceName) { mutableStateOf<String?>(null) }
    val selected = targets.firstOrNull { it.id == selectedId }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Transfer · $sourceName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "Choose an available station of the same type. The original rate, package, timer and shift stay unchanged.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                    containerColor = if (selectedId == target.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Text(
                                    "${target.name} · " +
                                        availableStationPricingDescription(target, packages),
                                )
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
private fun PackageExtensionInlineReview(
    preview: InlinePackageExtensionPreview,
    error: String?,
    confirming: Boolean,
    pinActions: Boolean,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val selected = preview.extensions.firstOrNull { it.id == preview.selectedPackageId }
        ?: return
    val selectedSurcharge = extraControllerExtensionSurchargeMinor(
        extraControllers = preview.session.extraControllers,
        currentDurationMinutes = preview.session.timerMinutes ?: 0,
        extensionMinutes = selected.durationMinutes,
    )
    val incrementalTotal = runCatching {
        Math.addExact(selected.priceMinor, selectedSurcharge)
    }.getOrNull()
    val resultingTotal = incrementalTotal?.let { increment ->
        preview.session.amountMinor?.let { current ->
            runCatching { Math.addExact(current, increment) }.getOrNull()
        }
    }
    val choices = preview.extensions.map { extension ->
        val surcharge = extraControllerExtensionSurchargeMinor(
            extraControllers = preview.session.extraControllers,
            currentDurationMinutes = preview.session.timerMinutes ?: 0,
            extensionMinutes = extension.durationMinutes,
        )
        GamingDurationChoice(
            packageId = extension.id,
            minutes = extension.durationMinutes,
            totalMinor = runCatching { Math.addExact(extension.priceMinor, surcharge) }
                .getOrDefault(extension.priceMinor),
        )
    }
    val reviewBody: @Composable () -> Unit = {
        Text("ADD PAID TIME", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall)
        Text("Review extension", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        // The elapsed ring is the session's permanent instrument. Paid
        // extensions are explicit review choices beside it; drawing a
        // second dial here made the timer look replaceable and duplicated
        // the hero geometry during a money action.
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice.packageId == selected.id,
                    onClick = { onSelect(choice.packageId) },
                    label = {
                        Text("Add ${choice.minutes} min · ${choice.totalMinor.asRupees()}")
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = gamingPackageChipColors(),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Added time", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text("+${selected.durationMinutes} min", fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Incremental charge", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(incrementalTotal?.asRupees() ?: "Unavailable", fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "Resulting booked total · ${resultingTotal?.asRupees() ?: "Unavailable"}",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Selecting a duration only updates this review. No time or charge is added until Confirm extension.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    val reviewActions: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().testTag("gaming-extension-actions"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ErpButton(
                text = "Cancel",
                onClick = onCancel,
                intent = ActionIntent.Secondary,
                enabled = !confirming,
                modifier = Modifier.weight(1f),
            )
            ErpButton(
                text = "Confirm extension",
                onClick = onConfirm,
                enabled = error == null && resultingTotal != null && !confirming,
                busy = confirming,
                leadingIcon = Icons.Filled.Add,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val reviewFooter: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.operationalDanger,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("gaming-extension-review-error"),
                )
            }
            reviewActions()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = Radius.shapeLg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
            .then(if (pinActions) Modifier.fillMaxHeight() else Modifier)
            .testTag("gaming-extension-preview"),
    ) {
        if (pinActions) {
            Column(
                Modifier.fillMaxSize().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(
                    Modifier.fillMaxWidth().then(
                        if (pinActions) Modifier.weight(1f) else Modifier.heightIn(max = 180.dp),
                    ).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    reviewBody()
                }
                reviewFooter()
            }
        } else {
            Column(
                Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                reviewBody()
                reviewFooter()
            }
        }
    }
}

@Composable
private fun PackageExtensionDialog(
    extensions: List<GamingPackage>,
    extraControllers: Int,
    currentDurationMinutes: Int,
    onDismiss: () -> Unit,
    onSelect: (GamingPackage) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = extensions.firstOrNull { it.id == selectedId }
    val selectedSurcharge = selected?.let {
        extraControllerExtensionSurchargeMinor(
            extraControllers = extraControllers,
            currentDurationMinutes = currentDurationMinutes,
            extensionMinutes = it.durationMinutes,
        )
    } ?: 0L
    val selectedTotal = selected?.let { it.priceMinor + selectedSurcharge }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Add paid time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    "This adds a priced extension to the package total. It does not replace the existing package.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                containerColor = if (selectedId == extension.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            val surcharge = extraControllerExtensionSurchargeMinor(
                                extraControllers = extraControllers,
                                currentDurationMinutes = currentDurationMinutes,
                                extensionMinutes = extension.durationMinutes,
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
                            "The controller charge shown is only the increase needed for the " +
                                "session's new total duration; already-paid time is not charged again.",
                        color = MaterialTheme.colorScheme.operationalWarning,
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
@OptIn(ExperimentalLayoutApi::class)
private fun LegacyRecoveryDialogFrame(
    onDismissRequest: () -> Unit,
    title: String = "Resolve rejected gaming start",
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = gamingImeAwareDialogProperties) {
        val view = LocalView.current
        val density = LocalDensity.current
        val boundsState = remember(view) { LegacyRecoveryBoundsState() }
        fun sampleVisibleBounds(): IntRect? {
            if (!view.isAttachedToWindow) return null
            val frame = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(frame)
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            return legacyRecoveryVisibleBounds(
                visibleFrame = IntRect(frame.left, frame.top, frame.right, frame.bottom),
                rootOriginX = origin[0],
                rootOriginY = origin[1],
                rootWidth = view.width,
                rootHeight = view.height,
            )
        }
        DisposableEffect(view) {
            // Compose 1.7 floating-dialog IME insets can omit the dialog's
            // screen offset. Android's visible frame includes the real IME
            // and system-bar bounds. Sample both frame and origin immediately
            // before draw because WindowManager can relocate this full-height
            // root without dispatching another global layout.
            val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                boundsState.refreshBeforeDraw(sampleVisibleBounds())
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            boundsState.refreshBeforeDraw(sampleVisibleBounds())
            onDispose {
                val observer = view.viewTreeObserver
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        Box(Modifier.fillMaxSize()) {
            // A sibling backdrop never participates in a field/scroll gesture.
            Box(Modifier.matchParentSize().pointerInput(onDismissRequest) {
                detectTapGestures { onDismissRequest() }
            })
            boundsState.visibleBounds?.let { bounds ->
                Box(
                    modifier = Modifier
                        .absoluteOffset(
                            with(density) { bounds.left.toDp() },
                            with(density) { bounds.top.toDp() },
                        )
                        .size(
                            with(density) { bounds.width.toDp() },
                            with(density) { bounds.height.toDp() },
                        )
                        .padding(Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                            .semantics { paneTitle = title },
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = Radius.shapeLg,
                    ) {
                        Column(
                            Modifier.padding(Spacing.xl),
                            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Box(Modifier.weight(1f, fill = false)) { text() }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                dismissButton()
                                confirmButton()
                            }
                        }
                    }
                }
            }
        }
    }
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

    LegacyRecoveryDialogFrame(
        onDismissRequest = dismissSecurely,
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (exactAttemptLocked) {
                    Text(
                        "The exact saved decision is locked because the previous response may have committed. " +
                            "Retrying uses the same request and cannot create a second receipt. " +
                            if (requiresOwnerStepUp) {
                                "The same protected owner who made the first attempt must approve this retry."
                            } else {
                                ""
                            },
                        color = MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
                        color = MaterialTheme.colorScheme.operationalDanger,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    "Choose one audited recovery path",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorDetail?.takeIf(String::isNotBlank)?.let {
                    Text("Server reason: $it", color = MaterialTheme.colorScheme.operationalDanger, style = MaterialTheme.typography.labelSmall)
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

internal fun packageExtensionReviewError(
    review: PackageExtensionReview,
    state: GamingUiState,
    access: GamingAccess,
): String? {
    if (!access.canManageSessions) return "Session access changed. Reopen the extension after access is restored."
    if (state.busyStationId != null) return "Another station action is saving. Reopen the extension when it finishes."
    val currentStation = state.stations.firstOrNull { it.id == review.station.id }
        ?: return "This station is no longer available. Reopen the station before extending."
    if (currentStation != review.station) {
        return "The station details changed. Reopen Extend and review the current station."
    }
    if (!currentStation.isActive) return "This station is disabled. No extension was added."
    val currentSession = state.sessions.firstOrNull { it.id == review.session.id }
        ?: return "This session is no longer available. No extension was added."
    if (currentSession != review.session) {
        return "The session changed after this review opened. Reopen Extend to review its current timer and total."
    }
    val currentExtension = state.packages.firstOrNull { it.id == review.extension.id }
        ?: return "This extension is no longer available. Reopen Extend and choose a current option."
    if (currentExtension != review.extension) {
        return "The extension price or duration changed. Reopen Extend and review the new total."
    }
    if (currentExtension.durationMinutes !in 1..1_440 || currentExtension.priceMinor <= 0L) {
        return "This extension has an invalid duration or price. Refresh Gaming before extending."
    }
    if (currentSession.status !in setOf("active", "paused")) {
        return "This session is no longer running. No extension was added."
    }
    if (currentSession.orderId != null) {
        return "This session is already linked to POS. No extension was added."
    }
    gamingCleanupWorkflowMessageOrNull(currentSession.lastError)?.let {
        return "Finish the protected session recovery before adding paid time."
    }
    if (!currentSession.hasLockedPackageExtensionSnapshot()) {
        return "The locked package timer or total is unavailable. Refresh Gaming before extending."
    }
    val currentMinutes = currentSession.timerMinutes
        ?: return "The booked timer is unavailable. Refresh Gaming."
    val resultingMinutes = currentMinutes.toLong() + currentExtension.durationMinutes.toLong()
    if (currentMinutes !in 1..1_440 || resultingMinutes !in 1L..1_440L) {
        return "This extension would exceed the 24-hour gaming limit. Choose a shorter extension."
    }
    val currentAmount = currentSession.amountMinor
        ?: return "The locked package total is unavailable. Refresh Gaming before extending."
    val surcharge = extraControllerExtensionSurchargeMinor(
        extraControllers = currentSession.extraControllers,
        currentDurationMinutes = currentMinutes,
        extensionMinutes = currentExtension.durationMinutes,
    )
    val safeTotal = runCatching {
        Math.addExact(currentAmount, Math.addExact(currentExtension.priceMinor, surcharge))
    }.getOrNull()
    if (currentAmount < 0L || safeTotal == null) {
        return "The reviewed extension total is invalid. Refresh Gaming before extending."
    }
    if (currentSession.authority(state.activeShiftId) != GamingSessionAuthority.CURRENT_SHIFT) {
        return "The current POS shift no longer owns this session. Reopen it on the terminal that started it."
    }
    if (state.packageExtensionFor(currentSession.id) != null) {
        return "A paid extension is already unresolved. Wait for it to finish or review it before adding another."
    }
    if (state.unresolvedSequencedActionsFor(currentSession).any {
            it.state == GamingSessionActionState.REJECTED
        }
    ) {
        return "Resolve the refused saved session action before adding paid time."
    }
    if (matchingPackageExtensions(currentSession, currentStation, state.packages).none {
            it == currentExtension
        }
    ) {
        return "This extension no longer matches the session's locked package. Reopen Extend and choose again."
    }
    return null
}

@Composable
private fun ExpectedSessionFinishPreview(durationMinutes: Int) {
    var nowMillis by remember(durationMinutes) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(durationMinutes) {
        while (isActive) {
            val untilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(untilNextMinute)
            nowMillis = System.currentTimeMillis()
        }
    }
    val finish = remember(durationMinutes, nowMillis) {
        Instant.ofEpochMilli(nowMillis + durationMinutes.toLong() * 60_000L)
            .toString()
            .businessClockTime()
    }
    Text(
        "Est. finish · $finish",
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.semantics {
            contentDescription = "Expected finish if started now, about $finish"
        },
    )
}

@Composable
private fun ReconcileSessionDialog(
    stationName: String,
    pendingBillSnapshotMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by rememberSaveable(stationName) { mutableStateOf("") }
    val normalized = reason.trim()
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    "$stationName · estimated combined bill ${pendingBillSnapshotMinor.asRupees()}. " +
                        "POS confirms the final total. The original closed shift remains unchanged; " +
                        "a traceable unpaid POS order is created on the current shift.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun rememberGamingStartSelectionState(
    station: Station,
    packages: List<GamingPackage>,
): GamingStartSelectionState {
    val basePackages = remember(packages) {
        packages.filter {
            it.kind == "base" && it.code.isNotBlank() && it.pricingTier == "standard"
        }
    }
    val availableVariants = remember(basePackages) {
        basePackages.map(GamingPackage::variant).distinct()
    }
    val initialVariant = availableVariants.firstOrNull { it == "simdrive" }
        ?: availableVariants.firstOrNull()
    val selectedVariant = rememberSaveable(station.id) { mutableStateOf(initialVariant) }
    val playerCount = rememberSaveable(station.id) { mutableIntStateOf(1) }
    val requiredVariant = if (
        stationFilterId(station.type) == "ps5" &&
        basePackages.any { it.variant in setOf("single", "dual") }
    ) {
        if (playerCount.intValue == 1) "single" else "dual"
    } else {
        selectedVariant.value
    }
    val initialPackageId = basePackages.firstOrNull {
        requiredVariant == null || it.variant == requiredVariant
    }?.id
    val selectedPackageId = rememberSaveable(station.id) { mutableStateOf(initialPackageId) }
    return remember(station.id, selectedVariant, playerCount, selectedPackageId) {
        GamingStartSelectionState(selectedVariant, playerCount, selectedPackageId)
    }
}

internal data class GamingStartSelectionState(
    val selectedVariant: MutableState<String?>,
    val playerCount: MutableIntState,
    val selectedPackageId: MutableState<String?>,
)

private fun gamingDurationChoices(
    station: Station,
    packages: List<GamingPackage>,
    selection: GamingStartSelectionState,
): List<GamingDurationChoice> {
    val basePackages = packages.filter {
        it.kind == "base" && it.code.isNotBlank() && it.pricingTier == "standard"
    }
    val supportsPlayerModes = stationFilterId(station.type) == "ps5" &&
        basePackages.any { it.variant in setOf("single", "dual") }
    val requiredVariant = if (supportsPlayerModes) {
        if (selection.playerCount.intValue == 1) "single" else "dual"
    } else selection.selectedVariant.value
    return basePackages.filter { requiredVariant == null || it.variant == requiredVariant }.map { option ->
        val extraControllers = if (option.variant == "dual") {
            (selection.playerCount.intValue - option.includedPlayers).coerceAtLeast(0)
        } else 0
        GamingDurationChoice(
            packageId = option.id,
            minutes = option.durationMinutes,
            totalMinor = option.priceMinor + extraControllerSurchargeMinor(
                extraControllers,
                option.durationMinutes,
            ),
        )
    }
}

@Composable
internal fun StartSessionEditor(
    station: Station,
    packages: List<GamingPackage> = emptyList(),
    customers: List<GamingCustomerOption> = emptyList(),
    online: Boolean = false,
    onSearchCustomer: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (String?, String?, String?, Int?, String?, Int) -> Unit,
    embedded: Boolean = false,
    startEnabled: Boolean = true,
    startBlockMessage: String? = null,
    blockerActions: (@Composable () -> Unit)? = null,
    selectionState: GamingStartSelectionState? = null,
    externallyHostedDial: Boolean = false,
    pinActions: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var customerMode by rememberSaveable(station.id) { mutableStateOf("search") }
    var customerQuery by rememberSaveable(station.id) { mutableStateOf("") }
    var selectedCustomer by remember(station.id) { mutableStateOf<GamingCustomerOption?>(null) }
    var minutes by remember { mutableStateOf<Int?>(60) }
    val basePackages = remember(packages) {
        packages.filter {
            it.kind == "base" && it.code.isNotBlank() && it.pricingTier == "standard"
        }
    }
    val availableVariants = remember(basePackages) {
        basePackages.map(GamingPackage::variant).distinct()
    }
    val localSelectionState = rememberGamingStartSelectionState(station, packages)
    val effectiveSelectionState = selectionState ?: localSelectionState
    var selectedVariant by effectiveSelectionState.selectedVariant
    val supportsPlayerModes = stationFilterId(station.type) == "ps5" &&
        basePackages.any { it.variant in setOf("single", "dual") }
    var playerCount by effectiveSelectionState.playerCount
    val maximumPlayers = remember(basePackages) {
        basePackages.maxOfOrNull(GamingPackage::maxPlayers)?.coerceIn(1, 8) ?: 1
    }
    val requiredVariant = if (supportsPlayerModes) {
        if (playerCount == 1) "single" else "dual"
    } else {
        selectedVariant
    }
    val eligiblePackages = basePackages.filter {
        requiredVariant == null || it.variant == requiredVariant
    }
    var selectedPackageId by effectiveSelectionState.selectedPackageId
    LaunchedEffect(selectedVariant, playerCount, packages) {
        if (selectedVariant !in availableVariants) {
            selectedVariant = availableVariants.firstOrNull { it == "simdrive" }
                ?: availableVariants.firstOrNull()
        }
        playerCount = playerCount.coerceAtMost(maximumPlayers)
        if (eligiblePackages.none { it.id == selectedPackageId }) {
            selectedPackageId = eligiblePackages.firstOrNull()?.id
        }
    }
    val selectedPackage = eligiblePackages.firstOrNull { it.id == selectedPackageId }
    val extraControllers = if (selectedPackage?.variant == "dual") {
        (playerCount - selectedPackage.includedPlayers).coerceAtLeast(0)
    } else 0
    val selectedControllerSurchargeMinor = selectedPackage?.let {
        extraControllerSurchargeMinor(extraControllers, it.durationMinutes)
    } ?: 0L
    val selectedPackageTotalMinor = selectedPackage?.let {
        it.priceMinor + selectedControllerSurchargeMinor
    }
    val durationChoices = eligiblePackages.map { option ->
        val optionExtraControllers = if (option.variant == "dual") {
            (playerCount - option.includedPlayers).coerceAtLeast(0)
        } else 0
        GamingDurationChoice(
            packageId = option.id,
            minutes = option.durationMinutes,
            totalMinor = option.priceMinor + extraControllerSurchargeMinor(
                optionExtraControllers,
                option.durationMinutes,
            ),
        )
    }
    val prominentDurationDial = stationFilterId(station.type) in setOf("ps5", "racing", "vr") &&
        durationChoices.size >= 2 &&
        durationChoices.map(GamingDurationChoice::minutes).distinct().size == durationChoices.size
    val hasFixedTariff = basePackages.isNotEmpty()
    val fixedTariffRequired = requiresCanonicalGamingTariff(station.type)
    val fixedTariffUnavailable = fixedTariffRequired && !hasFixedTariff
    val customerResults = remember(customers, customerQuery) {
        filterGamingCustomerOptions(customers, customerQuery).take(8)
    }
    LaunchedEffect(customers) {
        val selectedId = selectedCustomer?.customerId
        if (selectedId != null && customers.none { it.customerId == selectedId }) {
            selectedCustomer = null
            name = ""
            phone = ""
            customerQuery = ""
        }
    }

    // One form owns the package, customer and start payload in either presentation.
    val optionsContent: @Composable () -> Unit = {
        if (!prominentDurationDial) Row(
            Modifier.fillMaxWidth().clip(Radius.shapeMd).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd).padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stationTypeLabel(station.type), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(
                    if (fixedTariffRequired) "Fixed-price tariff" else "Hourly rate",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            NumericValue(
                value = when {
                    selectedPackageTotalMinor != null -> selectedPackageTotalMinor.asRupees()
                    fixedTariffUnavailable -> "Unavailable"
                    fixedTariffRequired -> "Select a package"
                    else -> "${station.ratePerHourMinor.asRupees()}/hour"
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!prominentDurationDial) Text(
            "Billing option", color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
        if (fixedTariffUnavailable) {
            Text(
                "The fixed-price tariff has not synced to this tablet. Connect to the " +
                    "server and refresh Gaming before starting this station; the old " +
                    "hourly fallback is intentionally disabled so the customer is not " +
                    "charged the wrong amount.",
                color = MaterialTheme.colorScheme.operationalDanger,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (hasFixedTariff) {
            if (stationFilterId(station.type) in setOf("racing", "vr") && availableVariants.size > 1) {
                Text("Mode", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(availableVariants) { variant ->
                        FilterChip(
                            selected = selectedVariant == variant,
                            onClick = { selectedVariant = variant },
                            label = { Text(gamingModeLabel(variant)) },
                            colors = gamingPackageChipColors(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            if (!prominentDurationDial) Text(
                "Duration", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (!prominentDurationDial && durationChoices.size in 2..4 &&
                durationChoices.map(GamingDurationChoice::minutes).distinct().size == durationChoices.size
            ) {
                GamingDurationDial(
                    choices = durationChoices,
                    selectedPackageId = selectedPackageId,
                    onSelect = { selectedPackageId = it },
                    gestureContextKey = station,
                )
            } else if (!prominentDurationDial) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(durationChoices, key = GamingDurationChoice::packageId) { option ->
                        FilterChip(
                            selected = selectedPackageId == option.packageId,
                            onClick = { selectedPackageId = option.packageId },
                            label = { Text("${option.minutes} min · ${option.totalMinor.asRupees()} total") },
                            colors = gamingPackageChipColors(),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
        } else {
            Text("Booked time", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(listOf<Int?>(30, 60, 90, 120, null)) { option ->
                FilterChip(
                    selected = minutes == option,
                    onClick = { minutes = option },
                    label = { Text(option?.let { "${it}m" } ?: "Open-ended") },
                    colors = gamingPackageChipColors(),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                }
            }
        }
        if (selectedPackage != null && !prominentDurationDial) {
            SectionCard(
                title = selectedPackage.name,
                subtitle = "${selectedPackage.durationMinutes} minutes · ${requireNotNull(selectedPackageTotalMinor).asRupees()} total",
            ) {
                if (supportsPlayerModes) {
                    Text(
                        if (extraControllers == 0) {
                            "$playerCount ${if (playerCount == 1) "player" else "players"} · controllers included."
                        } else {
                            "$playerCount players · ${extraControllers.asControllerCount()} · " +
                                "${selectedControllerSurchargeMinor.asRupees()} surcharge."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        val previewMinutes = selectedPackage?.durationMinutes ?: minutes
        if (previewMinutes != null && !externallyHostedDial) {
            ExpectedSessionFinishPreview(previewMinutes)
        }
        if (supportsPlayerModes) {
            if (!embedded) Text("Mode", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            val compactEmbeddedPlayers = embedded &&
                LocalConfiguration.current.screenHeightDp < 700
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    if (compactEmbeddedPlayers) Spacing.xs else Spacing.sm,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = playerCount == 1,
                    onClick = { playerCount = 1 },
                    label = { Text(if (compactEmbeddedPlayers) "Single" else "Single · 1 player") },
                    colors = gamingPackageChipColors(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                if (maximumPlayers >= 2) FilterChip(
                    selected = playerCount >= 2,
                    onClick = { playerCount = 2 },
                    label = { Text(if (compactEmbeddedPlayers) {
                        if (playerCount > 2) "Dual $playerCount" else "Dual"
                    } else "Dual · 2+ players") },
                    colors = gamingPackageChipColors(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                if (compactEmbeddedPlayers) IconButton(
                    onClick = { playerCount = (playerCount + 1).coerceAtMost(maximumPlayers) },
                    enabled = playerCount < maximumPlayers,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add player")
                }
            }
            if (!compactEmbeddedPlayers) Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("Players", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                Text(playerCount.toString(), color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { playerCount = (playerCount + 1).coerceAtMost(maximumPlayers) },
                    enabled = playerCount < maximumPlayers,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add player")
                }
                if (playerCount > 2) TextButton(
                    onClick = { playerCount-- },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Remove last") }
            }
            if (compactEmbeddedPlayers && playerCount > 2) TextButton(
                onClick = { playerCount-- },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Remove last player") }
            if (extraControllers > 0) {
                Text(
                    "${extraControllers.asControllerCount()} · " +
                        "${selectedControllerSurchargeMinor.asRupees()} surcharge",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (playerCount > 1) Text(
                "Only the selected customer is linked at start. Add each friend's saved customer in the live session to track individual play time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!embedded) Text("Customer", color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge)
        selectedCustomer?.let { selected ->
            SectionCard(
                title = selected.name?.takeIf(String::isNotBlank) ?: "Saved customer",
                subtitle = selected.phone + if (selected.pending) " · waiting to sync" else "",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(
                        onClick = {
                            selectedCustomer = null
                            customerMode = "search"
                            customerQuery = ""
                            onSearchCustomer("")
                            name = ""
                            phone = ""
                        },
                    ) { Text("Change") }
                    TextButton(
                        onClick = {
                            selectedCustomer = null
                            customerMode = "search"
                            customerQuery = ""
                            onSearchCustomer("")
                            name = ""
                            phone = ""
                        },
                    ) { Text("Clear") }
                }
            }
        } ?: run {
            val compactEmbeddedCustomer = embedded && externallyHostedDial &&
                LocalConfiguration.current.screenHeightDp < 700
            if (embedded && !compactEmbeddedCustomer) Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Customer", color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = {
                    customerMode = if (customerMode == "search") "add" else "search"
                    customerQuery = ""
                    onSearchCustomer("")
                    name = ""
                    phone = ""
                }) { Text(if (customerMode == "search") "Add new" else "Saved customer") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(
                    selected = customerMode == "search",
                    onClick = {
                        customerMode = "search"
                        customerQuery = ""
                        onSearchCustomer("")
                        name = ""
                        phone = ""
                    },
                    label = { Text("Saved customer") },
                    colors = gamingPackageChipColors(),
                )
                FilterChip(
                    selected = customerMode == "add",
                    onClick = {
                        customerMode = "add"
                        customerQuery = ""
                        onSearchCustomer("")
                    },
                    label = { Text("Add new") },
                    colors = gamingPackageChipColors(),
                )
            }
            if (customerMode == "search") {
                OutlinedTextField(
                    value = customerQuery,
                    onValueChange = {
                        customerQuery = it.take(200)
                        onSearchCustomer(customerQuery)
                    },
                    placeholder = { Text(if (embedded) "Search customer" else "Search by name or phone") },
                    leadingIcon = if (embedded && !compactEmbeddedCustomer) {
                        { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    trailingIcon = if (compactEmbeddedCustomer) {
                        {
                            IconButton(onClick = {
                                customerMode = "add"
                                customerQuery = ""
                                onSearchCustomer("")
                                name = ""
                                phone = ""
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add new customer")
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Search saved customers by name or phone"
                    },
                )
                customerResults.forEach { option ->
                    TextButton(
                        onClick = {
                            selectedCustomer = option
                            name = option.name.orEmpty()
                            phone = option.phone
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.name?.takeIf(String::isNotBlank) ?: "— no name —")
                            Text(
                                option.phone + if (option.pending) " · waiting to sync" else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (customerQuery.isNotBlank() && customerResults.isEmpty()) {
                    Text(
                        if (online) {
                            "Searching saved customers online. Cached matches appear immediately; online matches may take a moment."
                        } else {
                            "No match in this tablet's latest saved customer cache (up to 500). Connect to search beyond it, or choose Add new."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else if (!embedded || !online || customerQuery.isNotBlank()) {
                    Text(
                        if (online) {
                            "Search checks the server by name or phone and saves matches on this tablet for offline reuse."
                        } else {
                            "Offline search uses this workspace's latest cached customers (up to 500)."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(200) },
                    label = { Text("Customer name (optional)") },
                    trailingIcon = if (compactEmbeddedCustomer) {
                        {
                            IconButton(onClick = {
                                customerMode = "search"
                                customerQuery = ""
                                onSearchCustomer("")
                                name = ""
                                phone = ""
                            }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search saved customer")
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Customer name (optional)"
                    },
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.take(20) },
                    label = { Text("Customer phone (optional)") },
                    supportingText = {
                        Text("A valid new phone is saved on Start. A name alone stays an untracked receipt note.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Customer phone (optional)"
                    },
                )
            }
        }
        startBlockMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.operationalWarning, style = MaterialTheme.typography.labelMedium)
        }
        blockerActions?.invoke()
        if (!embedded) Text(
            if (selectedPackage != null) {
                "Price, duration and controller surcharge are captured now and verified by the server. The timer starts at this tap."
            } else if (fixedTariffUnavailable) {
                "Starting is disabled until the exact tariff has synced. No session or charge will be created."
            } else if (minutes == null) {
                "The start time is saved at this tap. The server verifies it and calculates the final elapsed-time bill."
            } else {
                "The timer and alarm begin at this tap. If the connection drops, the saved start syncs automatically."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val startActionText = selectedPackageTotalMinor?.let {
        "Start ${selectedPackage?.durationMinutes} min · ${it.asRupees()}"
    } ?: "Start session"
    val startActionEnabled = startEnabled && !fixedTariffUnavailable &&
        (!hasFixedTariff || selectedPackage != null)
    val startAction: @Composable (Modifier) -> Unit = { actionModifier ->
        Button(
            onClick = {
                val selected = selectedCustomer
                val snapshotName = when {
                    selected?.customerId != null -> null
                    selected != null -> selected.name?.takeIf(String::isNotBlank)
                    customerMode == "add" -> name.takeIf(String::isNotBlank)
                    else -> null
                }
                val snapshotPhone = when {
                    selected?.customerId != null -> null
                    selected != null -> selected.phone.takeIf(String::isNotBlank)
                    customerMode == "add" -> phone.takeIf(String::isNotBlank)
                    else -> null
                }
                onConfirm(
                    selected?.customerId,
                    snapshotName,
                    snapshotPhone,
                    if (selectedPackage == null) minutes else null,
                    selectedPackage?.id,
                    extraControllers,
                )
            },
            enabled = startActionEnabled,
            shape = Radius.shapeMd,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            border = BorderStroke(
                1.dp,
                if (startActionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = actionModifier.heightIn(min = 48.dp).testTag("gaming-start-action"),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(startActionText, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    if (embedded && externallyHostedDial) {
        Column(
            modifier.fillMaxSize().testTag("gaming-start-inline")
                // Match the shared starting/active footer baseline. A draft-
                // only bottom inset moved Start four pixels above Stop even
                // though both actions use the same reserved support region.
                .padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (pinActions) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    optionsContent()
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    optionsContent()
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().testTag("gaming-primary-action-host")) {
                    startAction(Modifier.fillMaxWidth())
                }
                GamingPrimaryActionSupportArea()
            }
        }
        return
    }
    val wideEmbeddedWorkspace = embedded && prominentDurationDial &&
        LocalConfiguration.current.screenWidthDp >= 700

    val formContent: @Composable (Boolean, Modifier) -> Unit = { compactIme, formModifier ->
        Surface(
            color = if (embedded) Color.Transparent else MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = formModifier,
        ) {
            Column(
                Modifier.fillMaxSize()
                    .then(if (embedded) Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.055f),
                                Color.Transparent,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.012f),
                            ),
                            endY = 800f,
                        ),
                    ) else Modifier)
                    .padding(
                    horizontal = if (embedded) Spacing.md else Spacing.lgPlus,
                    vertical = if (compactIme || embedded) Spacing.sm else Spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(if (compactIme || embedded) Spacing.sm else Spacing.md),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    if (!embedded) TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = null)
                        Text("Back")
                    }
                    val compactEmbeddedHeader = embedded && LocalConfiguration.current.screenHeightDp < 700
                    Column(Modifier.weight(1f), horizontalAlignment = if (embedded) Alignment.Start else Alignment.CenterHorizontally) {
                        if (embedded && !compactEmbeddedHeader) Text(
                            "START SESSION",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (embedded) station.name else "START SESSION",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = if (compactEmbeddedHeader) MaterialTheme.typography.titleMedium
                                else if (embedded) MaterialTheme.typography.titleLarge
                                else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!compactIme && !embedded) Text(station.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (embedded && !compactEmbeddedHeader) Text("Available · select a published duration",
                            color = statusColor(UiTone.Success), style = MaterialTheme.typography.labelMedium)
                    }
                    if (supportsPlayerModes && !embedded) {
                        Text(
                            if (playerCount == 1) "Single mode" else "$playerCount players",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                BoxWithConstraints(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val availableFormWidth = maxWidth
                    val availableFormHeight = maxHeight
                    Column(
                        Modifier.widthIn(max = 760.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = if (embedded) Spacing.xs else Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(if (embedded) Spacing.xs else Spacing.md),
                    ) {
                        // On tablets the primary action belongs beside the ring. Keeping it
                        // out of a separate footer gives the ring and authoritative timer the
                        // same measured slot while leaving customer controls visible.
                        val wideEmbeddedDial = wideEmbeddedWorkspace && availableFormWidth >= 420.dp
                        if (wideEmbeddedDial) {
                            val dialColumnWidth = (availableFormWidth - Spacing.md) * 0.62f
                            val dialSize = minOf(
                                gamingControlRingSize(LocalConfiguration.current.screenHeightDp),
                                dialColumnWidth,
                                (availableFormHeight - Spacing.xs * 2).coerceAtLeast(160.dp),
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                verticalAlignment = Alignment.Top,
                            ) {
                                GamingDurationDial(
                                    choices = durationChoices,
                                    selectedPackageId = selectedPackageId,
                                    onSelect = { selectedPackageId = it },
                                    modifier = Modifier.weight(0.62f),
                                    dialSize = dialSize,
                                    showChoiceChips = durationChoices.size > 2,
                                    gestureContextKey = station,
                                )
                                Column(
                                    Modifier.weight(0.38f),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                                ) {
                                    optionsContent()
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    startAction(Modifier.fillMaxWidth())
                                }
                            }
                        } else {
                            if (prominentDurationDial) {
                                GamingDurationDial(
                                    choices = durationChoices,
                                    selectedPackageId = selectedPackageId,
                                    onSelect = { selectedPackageId = it },
                                    dialSize = when {
                                        embedded && LocalConfiguration.current.screenHeightDp < 700 -> 170.dp
                                        embedded || LocalConfiguration.current.screenHeightDp < 700 -> 220.dp
                                        else -> 320.dp
                                    },
                                    showChoiceChips = durationChoices.size > 2,
                                    gestureContextKey = station,
                                )
                            }
                            optionsContent()
                        }
                    }
                }
                if (!wideEmbeddedWorkspace) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        startAction(
                            Modifier.widthIn(max = 760.dp)
                                .fillMaxWidth(if (embedded) 1f else 0.9f),
                        )
                    }
                }
                if (!compactIme && !embedded) Text(
                    "No payment yet. Bill after the session ends.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
    if (embedded) {
        formContent(false, modifier.fillMaxSize().testTag("gaming-start-inline"))
    } else {
        Dialog(onDismissRequest = onDismiss, properties = gamingImeAwareDialogProperties) {
            val view = LocalView.current
            val density = LocalDensity.current
            val boundsState = remember(view) { LegacyRecoveryBoundsState() }
            fun sampleVisibleBounds(): IntRect? {
                if (!view.isAttachedToWindow) return null
                val frame = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(frame)
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                return legacyRecoveryVisibleBounds(
                    visibleFrame = IntRect(frame.left, frame.top, frame.right, frame.bottom),
                    rootOriginX = origin[0], rootOriginY = origin[1],
                    rootWidth = view.width, rootHeight = view.height,
                )
            }
            DisposableEffect(view) {
                val listener = android.view.ViewTreeObserver.OnPreDrawListener {
                    boundsState.refreshBeforeDraw(sampleVisibleBounds())
                }
                view.viewTreeObserver.addOnPreDrawListener(listener)
                boundsState.refreshBeforeDraw(sampleVisibleBounds())
                onDispose {
                    val observer = view.viewTreeObserver
                    if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("gaming-start-dialog")) {
                boundsState.visibleBounds?.let { bounds ->
                    formContent(
                        bounds.height < view.height * 0.75f,
                        Modifier.absoluteOffset(
                            with(density) { bounds.left.toDp() },
                            with(density) { bounds.top.toDp() },
                        ).size(
                            with(density) { bounds.width.toDp() },
                            with(density) { bounds.height.toDp() },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun StartSessionDialog(
    station: Station,
    packages: List<GamingPackage> = emptyList(),
    customers: List<GamingCustomerOption> = emptyList(),
    online: Boolean = false,
    onSearchCustomer: (String) -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (String?, String?, String?, Int?, String?, Int) -> Unit,
    startEnabled: Boolean = true,
    startBlockMessage: String? = null,
    blockerActions: (@Composable () -> Unit)? = null,
) = StartSessionEditor(
    station = station, packages = packages, customers = customers,
    online = online, onSearchCustomer = onSearchCustomer,
    onDismiss = onDismiss, onConfirm = onConfirm,
    startEnabled = startEnabled, startBlockMessage = startBlockMessage, blockerActions = blockerActions,
)

@Composable
internal fun gamingPackageChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surface,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.background,
)

/** One operational action per stable person; attendance intervals remain intact for billing. */
internal fun currentGamingParticipantRows(
    participants: List<GamingParticipantUi>,
): List<GamingParticipantUi> = participants
    .groupBy(GamingParticipantUi::participantIdentityId)
    .values
    .mapNotNull { intervals ->
        val activeIntervals = intervals.filter(GamingParticipantUi::active)
        (activeIntervals.ifEmpty { intervals }).maxWithOrNull(
            compareBy<GamingParticipantUi> { it.joinedAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.leftAtMillis ?: Long.MIN_VALUE }
                .thenBy(GamingParticipantUi::reference),
        )
    }

internal fun gamingParticipantStateLabel(participant: GamingParticipantUi): String = when {
    participant.lastError != null -> "Needs review"
    participant.pending && participant.active -> "Waiting to sync Join"
    participant.pending -> "Waiting to sync Leave"
    participant.active -> "Playing now"
    else -> "Left · cumulative fee retained"
}

@Composable
internal fun GamingInlineParticipantEditor(
    session: GameSession,
    participants: List<GamingParticipantUi>,
    customers: List<GamingCustomerOption>,
    wallClock: State<Long>,
    busy: Boolean,
    onJoin: (String?, String?, String?, String?) -> Unit,
    onLeave: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pinConfirmAction: Boolean = false,
) {
    var savedCustomerMode by rememberSaveable(session.id) { mutableStateOf(false) }
    var registerCustomerMode by rememberSaveable(session.id) { mutableStateOf(false) }
    var query by rememberSaveable(session.id) { mutableStateOf("") }
    var guestName by rememberSaveable(session.id) { mutableStateOf("") }
    var registrationPhone by rememberSaveable(session.id) { mutableStateOf("") }
    var selected by remember(session.id) { mutableStateOf<GamingCustomerOption?>(null) }
    val active = participants.filter(GamingParticipantUi::active)
    val currentParticipants = remember(participants) { currentGamingParticipantRows(participants) }
    val activeIdentityIds = active.map(GamingParticipantUi::participantIdentityId).toSet()
    val basePlayers = gamingBookedPlayerCount(session)
    val canAdd = session.status == "active" && gamingLateJoinHasCapacity(session, active.size)
    val frozenNow = remember(session.id, session.status) { System.currentTimeMillis() }
    val nowMillis = if (session.status in setOf("active", "starting")) wallClock.value else frozenNow
    val playElapsedMs = sessionPlayElapsedMillis(session, nowMillis) ?: 0L
    val matches = remember(customers, query) {
        filterGamingCustomerOptions(customers, query).take(5)
    }
    SectionCard(
        title = "Players · ${basePlayers + active.size}/4",
        subtitle = "${active.size} joined during session · capacity four",
        modifier = modifier.testTag("gaming-player-inline"),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        contentFillsAvailableHeight = pinConfirmAction,
        action = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("gaming-player-inline-close"),
            ) { Text("Close") }
        },
    ) {
        val editorBody: @Composable () -> Unit = {
        currentParticipants.forEach { participant ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        participant.name?.takeIf(String::isNotBlank)
                            ?: if (participant.customerId == null) {
                                "Guest player ${participant.participantIdentityId.take(4)}"
                            } else "Saved customer",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        gamingParticipantStateLabel(participant),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val joinedLabel = participant.joinedAtMillis?.let {
                        Instant.ofEpochMilli(it).toString().businessClockTime()
                    } ?: "time pending"
                    Text(
                        "Joined $joinedLabel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val identityIntervals = participants.filter {
                        it.participantIdentityId == participant.participantIdentityId
                    }
                    val cumulativeMillis = gamingParticipantCumulativePlayMillis(
                        identityIntervals,
                        playElapsedMs,
                    )
                    val estimatedFeeMinor = estimatedGamingFriendControllerMinor(
                        identityIntervals,
                        playElapsedMs,
                    )
                    Text(
                        "${formatGamingParticipantDuration(cumulativeMillis)} cumulative play · " +
                            "${estimatedFeeMinor.asRupees()} estimated",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "₹30 per started cumulative hour · paused time excluded",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    participant.lastError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.operationalDanger,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(
                    enabled = !busy && (participant.active || (
                        canAdd && participant.identityResolved &&
                            participant.participantIdentityId !in activeIdentityIds
                        )),
                    modifier = Modifier.testTag(
                        "gaming-participant-${participant.participantIdentityId}-" +
                            if (participant.active) "leave" else "rejoin",
                    ),
                    onClick = {
                        if (participant.active) onLeave(participant.reference)
                        else onJoin(participant.customerId, participant.name, null, participant.participantIdentityId)
                    },
                ) { Text(if (participant.active) "Leave" else "Rejoin") }
            }
        }
        if (canAdd) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FilterChip(
                        selected = !savedCustomerMode && !registerCustomerMode,
                        onClick = {
                            savedCustomerMode = false
                            registerCustomerMode = false
                            selected = null
                            registrationPhone = ""
                        },
                        label = { Text("Guest") },
                        colors = gamingPackageChipColors(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = savedCustomerMode,
                        onClick = {
                            savedCustomerMode = true
                            registerCustomerMode = false
                            selected = null
                            registrationPhone = ""
                        },
                        label = { Text("Saved customer") },
                        colors = gamingPackageChipColors(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
                FilterChip(
                    selected = registerCustomerMode,
                    onClick = {
                        savedCustomerMode = false
                        registerCustomerMode = true
                        selected = null
                        registrationPhone = ""
                    },
                    label = { Text("Save new customer") },
                    colors = gamingPackageChipColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
            }
            if (savedCustomerMode) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(200); selected = null },
                    label = { Text("Search name or phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                matches.forEach { option ->
                    TextButton(
                        onClick = { selected = option },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${option.name?.takeIf(String::isNotBlank) ?: "Saved customer"} · ${option.phone}" +
                                if (selected == option) " · selected" else "",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (registerCustomerMode) {
                OutlinedTextField(
                    value = guestName,
                    onValueChange = { guestName = it.take(200) },
                    label = { Text("Customer name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = registrationPhone,
                    onValueChange = { registrationPhone = it.take(20) },
                    label = { Text("Phone (required)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "This deliberately saves a customer profile before linking the player.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                OutlinedTextField(
                    value = guestName,
                    onValueChange = { guestName = it.take(200) },
                    label = { Text("Guest name (optional · no points)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                if (session.status != "active") "Resume the session before adding a player."
                else "All four player places are assigned. A player must leave before another can join.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        }
        val confirmAction: @Composable () -> Unit = {
            if (canAdd) ErpButton(
                text = "Confirm join · ₹30 first hour",
                onClick = {
                    onJoin(
                        selected?.customerId,
                        if (selected == null) guestName.trim().ifEmpty { null } else null,
                        gamingInlineJoinPhone(registerCustomerMode, registrationPhone),
                        null,
                    )
                },
                enabled = !busy && when {
                    savedCustomerMode -> selected?.customerId != null
                    registerCustomerMode -> registrationPhone.trim().length in 4..20
                    else -> true
                },
                leadingIcon = Icons.Filled.People,
                modifier = Modifier.fillMaxWidth().testTag("gaming-player-inline-confirm"),
            )
        }
        if (pinConfirmAction) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) { editorBody() }
                confirmAction()
            }
        } else {
            editorBody()
            confirmAction()
        }
    }
}

internal fun gamingInlineJoinPhone(registerCustomerMode: Boolean, rawPhone: String): String? =
    rawPhone.trim().takeIf { registerCustomerMode && it.length in 4..20 }

@Composable
internal fun GamingInlineDrinkEditor(
    session: GameSession,
    items: List<MenuItemEntity>,
    configurableItemIds: Set<String>,
    busy: Boolean,
    onConfirm: (MenuItemEntity, Int) -> Unit,
    onConfigure: (MenuItemEntity) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pinActions: Boolean = false,
) {
    var selectedId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var quantity by rememberSaveable(session.id) { mutableIntStateOf(1) }
    var query by rememberSaveable(session.id) { mutableStateOf("") }
    var selectedType by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    val selected = items.firstOrNull { it.id == selectedId }
    val types = remember(items) { items.map(MenuItemEntity::type).distinct().sorted() }
    val visibleItems = remember(items, query, selectedType) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        items.asSequence()
            .filter { selectedType == null || it.type == selectedType }
            .filter { normalizedQuery.isEmpty() || it.name.lowercase(Locale.ROOT).contains(normalizedQuery) }
            .sortedBy(MenuItemEntity::name)
            .toList()
    }
    SectionCard(
        title = "Drinks & snacks",
        subtitle = "Add to this session without leaving the timer.",
        modifier = modifier.testTag("gaming-drinks-inline"),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        contentFillsAvailableHeight = pinActions,
        action = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("gaming-drinks-inline-close"),
            ) { Text("Close") }
        },
    ) {
        if (items.isEmpty()) {
            Text(
                "No session items are available. Refresh the menu or enable products in Menu.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else Column(
            modifier = if (pinActions) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (selected == null) {
                // The real tablet side pane is short once the permanent timer
                // and action footer are accounted for. Search, categories and
                // results therefore share one scroll owner instead of letting
                // the headers consume the catalogue's entire measured height.
                LazyColumn(
                    Modifier.fillMaxWidth().then(
                        if (pinActions) Modifier.weight(1f) else Modifier.heightIn(max = 360.dp),
                    ).testTag("gaming-drinks-catalogue"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it.take(100)
                                selectedId = null
                                quantity = 1
                            },
                            label = { Text("Search drinks & snacks") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("gaming-drinks-search"),
                        )
                    }
                    item(key = "categories") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            item {
                                FilterChip(
                                    selected = selectedType == null,
                                    onClick = {
                                        selectedType = null
                                        selectedId = null
                                        quantity = 1
                                    },
                                    label = { Text("All") },
                                    colors = gamingPackageChipColors(),
                                )
                            }
                            items(types, key = { it }) { type ->
                                FilterChip(
                                    selected = selectedType == type,
                                    onClick = {
                                        selectedType = type
                                        selectedId = null
                                        quantity = 1
                                    },
                                    label = { Text(type.replaceFirstChar { it.titlecase(Locale.ROOT) }) },
                                    colors = gamingPackageChipColors(),
                                )
                            }
                        }
                    }
                    if (visibleItems.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "No catalogue items match this search.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        items(visibleItems, key = MenuItemEntity::id) { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        item.basePriceMinor.asRupees(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        if (item.id in configurableItemIds) onConfigure(item)
                                        else {
                                            selectedId = item.id
                                            quantity = 1
                                        }
                                    },
                                ) { Text(if (item.id in configurableItemIds) "Customise" else "Select") }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().then(
                        if (pinActions) {
                            Modifier.weight(1f).verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Review ${selected.name}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "No bill line is created until Confirm add.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        OutlinedButton(
                            enabled = quantity > 1 && !busy,
                            onClick = { quantity = (quantity - 1).coerceAtLeast(1) },
                        ) { Text("−") }
                        Text("$quantity", fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            enabled = quantity < 20 && !busy,
                            onClick = { quantity = (quantity + 1).coerceAtMost(20) },
                        ) { Text("+") }
                    }
                    TextButton(
                        onClick = { selectedId = null; quantity = 1 },
                        enabled = !busy,
                    ) { Text("Choose another item") }
                }
                ErpButton(
                    text = "Confirm add · ${Math.multiplyExact(selected.basePriceMinor, quantity.toLong()).asRupees()}",
                    enabled = !busy,
                    onClick = {
                        onConfirm(selected, quantity)
                        selectedId = null
                        quantity = 1
                    },
                    modifier = Modifier.fillMaxWidth().testTag("gaming-drinks-inline-confirm"),
                )
            }
        }
    }
}

@Composable
internal fun GamingParticipantsDialog(
    session: GameSession,
    participants: List<GamingParticipantUi>,
    rejectedAction: LocalGamingSessionActionEntity?,
    customers: List<GamingCustomerOption>,
    online: Boolean,
    amendTarget: GamingPackage?,
    onSearchCustomer: (String) -> Unit,
    onJoin: (String?, String?, String?, String?) -> Unit,
    onLeave: (String) -> Unit,
    onAmend: (GamingPackage) -> Unit,
    onDiscardRejected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var customerMode by rememberSaveable(session.id) { mutableStateOf("search") }
    var query by rememberSaveable(session.id) { mutableStateOf("") }
    var selected by remember(session.id) { mutableStateOf<GamingCustomerOption?>(null) }
    var name by rememberSaveable(session.id) { mutableStateOf("") }
    var phone by rememberSaveable(session.id) { mutableStateOf("") }
    var pendingJoin by remember(session.id) { mutableStateOf<ParticipantJoinReview?>(null) }
    var pendingLeave by remember(session.id) { mutableStateOf<GamingParticipantUi?>(null) }
    val matches = remember(customers, query) { filterGamingCustomerOptions(customers, query).take(8) }
    val active = participants.filter(GamingParticipantUi::active)
    val basePlayers = gamingBookedPlayerCount(session)
    val includedPlayers = if ((session.effectivePackageVariant ?: session.packageVariantSnapshot) == "dual") 2 else 1
    val playElapsedMs = sessionPlayElapsedMillis(session, System.currentTimeMillis()) ?: 0L
    val friendControllerMinor = estimatedGamingFriendControllerMinor(participants, playElapsedMs)
    val bookedControllerMinor = extraControllerSurchargeMinor(
        session.extraControllers,
        session.timerMinutes ?: session.effectivePackageDurationMinutes
            ?: session.packageDurationMinutesSnapshot ?: 0,
    )
    LegacyRecoveryDialogFrame(
        onDismissRequest = {
            when {
                pendingJoin != null -> pendingJoin = null
                pendingLeave != null -> pendingLeave = null
                else -> onDismiss()
            }
        },
        title = when {
            pendingJoin != null -> "Review friend join"
            pendingLeave != null -> "Confirm friend leave"
            else -> "Players & booking"
        },
        text = {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val review = pendingJoin
            val leaving = pendingLeave
            when {
                review != null -> {
                    val afterJoinFriendMinor = estimatedGamingFriendControllerMinorAfterJoin(
                        participants = participants,
                        currentPlayElapsedMs = playElapsedMs,
                        customerId = review.customerId,
                        phone = review.phone,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            review.name?.takeIf(String::isNotBlank)
                                ?: review.phone?.takeIf(String::isNotBlank)
                                ?: "Saved customer",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("$basePlayers booked and fixed · ${active.size} late active · ${basePlayers + active.size}/4 now")
                        Text("₹30 per started cumulative hour · ₹30 minimum · paused time excluded")
                        Text(
                            "Player-controller fees after join · ${(bookedControllerMinor + afterJoinFriendMinor).asRupees()} estimated",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "No attendance interval or charge is saved until Confirm join is pressed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                leaving != null -> Text(
                    "${leaving.name?.takeIf(String::isNotBlank) ?: "This friend"} will be marked left at the current actual play-time. Saved intervals remain in the audit history; a later rejoin continues the same cumulative ₹30-per-started-hour charge.",
                )
                else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .testTag("gaming-players-list"),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    SectionCard(
                        title = "$basePlayers booked and fixed · ${active.size} late active · ${basePlayers + active.size}/4 total",
                        subtitle = "$includedPlayers included in the package · capacity four",
                    ) {
                        Text(
                            "Added friends are ₹30 per started cumulative hour each, minimum ₹30 per friend. Paused time is excluded.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Player-controller fees now · ${(bookedControllerMinor + friendControllerMinor).asRupees()} estimated",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (session.isLocallyPendingStart()) {
                    item {
                        Text(
                            "Start and player changes are saved on this tablet. They will sync in order; the final charge is confirmed when the session stops.",
                            color = MaterialTheme.colorScheme.operationalWarning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                rejectedAction?.let { rejected ->
                    item {
                        SectionCard(
                            title = "Saved action needs review",
                            subtitle = rejected.lastError
                                ?: "The server refused this saved session action.",
                        ) {
                            if (rejected.actionType in setOf(
                                    GamingSessionActionType.AMEND,
                                    GamingSessionActionType.PARTICIPANT_JOIN,
                                    GamingSessionActionType.PARTICIPANT_LEAVE,
                                )
                            ) {
                                TextButton(onClick = { onDiscardRejected(rejected.actionId) }) {
                                    Text("Remove refused action")
                                }
                            }
                        }
                    }
                }
                if (canAmendGamingPackageTo30(session, amendTarget, System.currentTimeMillis())) {
                    item {
                        val target = requireNotNull(amendTarget)
                        val preservedControllerMinor = ((session.amountMinor ?: 0L) -
                            (session.packagePriceMinorSnapshot ?: 0L)).coerceAtLeast(0L)
                        SectionCard(
                            title = "Change booking to 30 minutes",
                            subtitle = "Published ${target.priceMinor.asRupees()} base + " +
                                "${preservedControllerMinor.asRupees()} existing controller fee",
                        ) {
                            ErpButton(
                                text = "Use 30 min · ${(target.priceMinor + preservedControllerMinor).asRupees()}",
                                onClick = { onAmend(target) },
                                intent = ActionIntent.Warning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                item {
                    Text("Added friends", style = MaterialTheme.typography.titleSmall)
                }
                if (participants.isEmpty()) {
                    item {
                        Text("No late friends are recorded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(participants, key = GamingParticipantUi::reference) { participant ->
                        SectionCard(
                            title = participant.name?.takeIf(String::isNotBlank)
                                ?: if (participant.customerId != null) "Saved customer"
                                else "Guest player ${participant.participantIdentityId.take(4)}",
                            subtitle = listOfNotNull(
                                participant.phone?.takeIf(String::isNotBlank),
                                when {
                                    participant.lastError != null -> "Needs review"
                                    participant.pending && participant.active -> "Waiting to sync Join"
                                    participant.pending -> "Waiting to sync Leave"
                                    participant.active -> "Playing"
                                    else -> "Left"
                                },
                            ).joinToString(" · "),
                        ) {
                            val joinedLabel = participant.joinedAtMillis?.let {
                                Instant.ofEpochMilli(it).toString().businessClockTime()
                            } ?: "time pending"
                            val leftLabel = participant.leftAtMillis?.let {
                                Instant.ofEpochMilli(it).toString().businessClockTime()
                            }
                            val playedMs = ((participant.leftPlayElapsedMs ?: playElapsedMs) -
                                (participant.joinedPlayElapsedMs ?: playElapsedMs)).coerceAtLeast(0L)
                            val identityIntervals = participants.filter {
                                it.participantIdentityId == participant.participantIdentityId
                            }
                            val cumulativeMillis = gamingParticipantCumulativePlayMillis(
                                identityIntervals,
                                playElapsedMs,
                            )
                            val estimatedFeeMinor = estimatedGamingFriendControllerMinor(
                                identityIntervals,
                                playElapsedMs,
                            )
                            Text(
                                "Joined $joinedLabel · ${leftLabel?.let { "Left $it" } ?: "Playing now"} · " +
                                    "${formatGamingParticipantDuration(playedMs)} this interval",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "${formatGamingParticipantDuration(cumulativeMillis)} cumulative · " +
                                    "${estimatedFeeMinor.asRupees()} estimated · ₹30 per started cumulative hour",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (participant.active) {
                                TextButton(onClick = { pendingLeave = participant }) {
                                    Text(if (session.status == "paused") "Leave at paused time" else "Leave now")
                                }
                            } else if (session.status == "active" &&
                                participant.identityResolved &&
                                participant.participantIdentityId !in active.map(
                                    GamingParticipantUi::participantIdentityId,
                                ).toSet() &&
                                basePlayers + active.size < 4
                            ) {
                                TextButton(onClick = {
                                    pendingJoin = ParticipantJoinReview(
                                        customerId = participant.customerId,
                                        name = participant.name,
                                        phone = null,
                                        participantIdentityId = participant.participantIdentityId,
                                    )
                                }) { Text("Rejoin") }
                            }
                        }
                    }
                }
                if (session.status == "paused") {
                    item {
                        Text(
                            "Friends may leave while paused. Resume before adding another friend.",
                            color = MaterialTheme.colorScheme.operationalWarning,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else if (basePlayers + active.size < 4) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            FilterChip(
                                selected = customerMode == "search",
                                onClick = { customerMode = "search"; selected = null },
                                label = { Text("Saved customer") },
                                colors = gamingPackageChipColors(),
                            )
                            FilterChip(
                                selected = customerMode == "add",
                                onClick = { customerMode = "add"; selected = null },
                                label = { Text("Guest player") },
                                colors = gamingPackageChipColors(),
                            )
                        }
                    }
                    if (customerMode == "search") {
                        item {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it.take(200); selected = null; onSearchCustomer(query) },
                                label = { Text("Search name or phone") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gaming-customer-search"),
                            )
                        }
                        items(matches, key = { it.customerId ?: it.phone }) { option ->
                            TextButton(
                                onClick = {
                                    selected = option
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${option.name?.takeIf(String::isNotBlank) ?: "Saved customer"} · ${option.phone}" +
                                        if (selected == option) " · selected" else "",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        item {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(200) },
                                label = { Text("Guest name (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        ErpButton(
                            text = "Add player",
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                if (customerMode == "search") {
                                    selected?.let {
                                        pendingJoin = ParticipantJoinReview(it.customerId, it.name, it.phone)
                                    }
                                } else {
                                    pendingJoin = ParticipantJoinReview(
                                        customerId = null,
                                        name = name.trim().takeIf(String::isNotEmpty),
                                        phone = null,
                                    )
                                }
                            },
                            enabled = if (customerMode == "search") {
                                selected?.customerId != null
                            } else {
                                true
                            },
                            leadingIcon = Icons.Filled.People,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gaming-add-player"),
                        )
                        Text(
                            if (online) "Saved actions upload in session order." else "Offline: Join/Leave/Stop remain queued in this exact session order.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    item {
                        Text(
                            "Capacity full: this PS5 session already has four players.",
                            color = MaterialTheme.colorScheme.operationalWarning,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            }
        },
        confirmButton = {
            val review = pendingJoin
            val leaving = pendingLeave
            when {
                review != null -> {
                    ErpButton(
                        text = "Confirm join",
                        onClick = {
                            pendingJoin = null
                            if (review.customerId != null) {
                                onJoin(review.customerId, null, null, review.participantIdentityId)
                            } else {
                                onJoin(null, review.name, review.phone, review.participantIdentityId)
                            }
                        },
                    )
                }
                leaving != null -> {
                ErpButton(
                    text = if (session.status == "paused") "Confirm leave at paused time" else "Confirm leave now",
                    onClick = {
                        pendingLeave = null
                        onLeave(leaving.reference)
                    },
                )
                }
                else -> TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            when {
                pendingJoin != null -> TextButton(onClick = { pendingJoin = null }) { Text("Edit") }
                pendingLeave != null -> TextButton(onClick = { pendingLeave = null }) { Text("Keep playing") }
                else -> Unit
            }
        },
    )
}

internal fun estimatedGamingFriendControllerMinor(
    participants: List<GamingParticipantUi>,
    currentPlayElapsedMs: Long,
): Long {
    val cumulativeByCustomer = mutableMapOf<String, Long>()
    participants.filterNot {
        it.pending && !it.active && it.leftPlayElapsedMs == null && it.leftAtMillis == null
    }.forEach { participant ->
        val joined = participant.joinedPlayElapsedMs ?: return@forEach
        val ended = participant.leftPlayElapsedMs ?: currentPlayElapsedMs
        val identity = participant.participantIdentityId
        cumulativeByCustomer[identity] = (cumulativeByCustomer[identity] ?: 0L) +
            (ended - joined).coerceAtLeast(0L)
    }
    return cumulativeByCustomer.values.sumOf { duration ->
        maxOf(1L, (duration + 3_599_999L) / 3_600_000L) * 3_000L
    }
}

internal fun gamingParticipantCumulativePlayMillis(
    participantIntervals: List<GamingParticipantUi>,
    currentPlayElapsedMs: Long,
): Long = participantIntervals.filterNot {
    it.pending && !it.active && it.leftPlayElapsedMs == null && it.leftAtMillis == null
}.sumOf { participant ->
    val joined = participant.joinedPlayElapsedMs ?: return@sumOf 0L
    val ended = participant.leftPlayElapsedMs ?: currentPlayElapsedMs
    (ended - joined).coerceAtLeast(0L)
}

internal fun formatGamingParticipantDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
}

internal fun estimatedGamingFriendControllerMinorAfterJoin(
    participants: List<GamingParticipantUi>,
    currentPlayElapsedMs: Long,
    customerId: String?,
    phone: String?,
): Long {
    val normalizedPhone = phone?.filter(Char::isDigit)?.takeLast(10)?.takeIf(String::isNotEmpty)
    val resolvedCustomerId = customerId ?: normalizedPhone?.let { candidate ->
        participants.mapNotNull { participant ->
            participant.customerId?.takeIf {
                participant.phone?.filter(Char::isDigit)?.takeLast(10) == candidate
            }
        }.distinct().singleOrNull()
    }
    return estimatedGamingFriendControllerMinor(
        participants + GamingParticipantUi(
            reference = "join-review",
            participantIdentityId = resolvedCustomerId ?: "join-review",
            customerId = resolvedCustomerId,
            name = null,
            phone = normalizedPhone,
            active = true,
            pending = true,
            joinedPlayElapsedMs = currentPlayElapsedMs,
        ),
        currentPlayElapsedMs,
    )
}

internal fun canAmendGamingPackageTo30(
    session: GameSession,
    target: GamingPackage?,
    nowMillis: Long,
): Boolean =
    target != null &&
        (session.status in setOf("active", "paused") ||
            session.isLocallyPendingPs5PackageStart()) &&
        session.orderId == null && session.pauseVersion != null && session.amountMinor != null &&
        session.billingRevision == 0 && session.participantRevision == 0 &&
        session.timerMinutes == 60 && session.packageDurationMinutesSnapshot == 60 &&
        session.packageVariantSnapshot in setOf("single", "dual") &&
        target.kind == "base" && target.durationMinutes == 30 &&
        target.variant == session.packageVariantSnapshot &&
        target.stationType == session.packageStationTypeSnapshot &&
        target.pricingTier == session.packagePricingTierSnapshot &&
        (sessionPlayElapsedMillis(session, nowMillis) ?: Long.MAX_VALUE) < 1_800_000L

private fun Int.asControllerCount(): String =
    "$this extra ${if (this == 1) "controller" else "controllers"}"

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

internal fun stationFilterId(type: String): String {
    val normalized = type.trim().lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
    return when (normalized) {
        "ps5", "ps 5", "playstation", "playstation 5", "console", "gaming console" -> "ps5"
        "simulator", "racing", "racing simulator", "simdrive" -> "racing"
        "vr", "virtual reality", "vr pod" -> "vr"
        "streaming", "streaming booth" -> "streaming"
        "hookah", "shisha", "shisha table" -> "shisha"
        else -> "other"
    }
}

internal fun requiresCanonicalGamingTariff(stationType: String): Boolean =
    stationFilterId(stationType) in setOf("ps5", "racing", "vr")

/**
 * Price copy for an available station. PS5 and simulator operation is governed
 * by Code22's fixed catalogue, so the legacy station hourly field must never be
 * advertised as a valid customer tariff for those station types.
 */
internal fun availableStationPricingDescription(
    station: Station,
    packages: List<GamingPackage>,
): String {
    if (!requiresCanonicalGamingTariff(station.type)) {
        return "${station.ratePerHourMinor.asRupees()}/hour"
    }
    val baseTariffs = packages.filter {
        it.stationType == station.type && it.kind == "base" &&
            it.pricingTier == "standard" && it.code.isNotBlank()
    }
    return baseTariffs.minOfOrNull(GamingPackage::priceMinor)?.let {
        "Fixed packages from ${it.asRupees()}"
    } ?: "Fixed-price tariff not synced"
}

internal fun gamingPackageSelectionLabel(session: GameSession): String? {
    if (!session.isPackageBilling()) return null
    val tier = session.packagePricingTierSnapshot?.takeIf { it == "premium" }?.let { "Premium" }
    val mode = when (session.packageVariantSnapshot) {
        "single" -> "Single"
        "dual" -> when (val players = 2 + session.extraControllers.coerceAtLeast(0)) {
            2 -> "Two players"
            else -> "$players players"
        }
        else -> session.packageVariantSnapshot?.takeIf(String::isNotBlank)?.let(::gamingModeLabel)
    }
    return listOfNotNull(tier, mode).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun gamingModeLabel(variant: String): String = when (variant) {
    "simdrive" -> "Racing Sim"
    "vr_racing" -> "VR Racing Sim"
    "vr_games" -> "VR Games"
    else -> variant.replace('_', ' ').split(' ').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
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

@Composable
private fun statusColor(tone: UiTone): Color = when (tone) {
    UiTone.Success -> MaterialTheme.colorScheme.operationalGood
    UiTone.Warning -> MaterialTheme.colorScheme.operationalWarning
    UiTone.Danger -> MaterialTheme.colorScheme.operationalDanger
    UiTone.Information -> MaterialTheme.colorScheme.operationalInformation
    UiTone.Brand -> MaterialTheme.colorScheme.primary
    UiTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun statusIconBackground(tone: UiTone): Color = when (tone) {
    UiTone.Success -> MaterialTheme.colorScheme.operationalGoodContainer
    UiTone.Warning -> MaterialTheme.colorScheme.operationalWarningContainer
    UiTone.Danger -> MaterialTheme.colorScheme.operationalDangerContainer
    UiTone.Information -> MaterialTheme.colorScheme.operationalInformationContainer
    UiTone.Brand -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    UiTone.Neutral -> MaterialTheme.colorScheme.secondaryContainer
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
internal fun elapsedMillis(session: GameSession, nowMillis: Long): Long =
    sessionPlayElapsedMillis(session, nowMillis) ?: 0L

/** Mirrors the fixed backend package surcharge for a session's cumulative duration. */
internal fun extraControllerSurchargeMinor(extraControllers: Int, durationMinutes: Int): Long {
    if (extraControllers <= 0 || durationMinutes <= 0) return 0L
    val startedHours = (durationMinutes + 59L) / 60L
    return extraControllers.toLong() * startedHours * 3_000L
}

/** An extension only charges the increase across the cumulative started-hour boundary. */
internal fun extraControllerExtensionSurchargeMinor(
    extraControllers: Int,
    currentDurationMinutes: Int,
    extensionMinutes: Int,
): Long {
    if (currentDurationMinutes < 0 || extensionMinutes <= 0) return 0L
    return (
        extraControllerSurchargeMinor(extraControllers, currentDurationMinutes + extensionMinutes) -
            extraControllerSurchargeMinor(extraControllers, currentDurationMinutes)
        ).coerceAtLeast(0L)
}

internal fun matchingPackageExtensions(
    session: GameSession?,
    station: Station,
    packages: List<GamingPackage>,
): List<GamingPackage> {
    val lockedSession = session ?: return emptyList()
    val lockedVariant = lockedSession.effectivePackageVariant?.takeIf(String::isNotBlank)
        ?: lockedSession.packageVariantSnapshot?.takeIf(String::isNotBlank)
        ?: return emptyList()
    val lockedStationType = lockedSession.effectivePackageStationType?.takeIf(String::isNotBlank)
        ?: lockedSession.packageStationTypeSnapshot?.takeIf(String::isNotBlank)
        ?: return emptyList()
    val compatibleTiers = packages.filter {
        it.kind == "extension" && it.stationType == lockedStationType &&
            it.variant == lockedVariant
    }.map(GamingPackage::pricingTier).distinct()
    val lockedPricingTier = lockedSession.effectivePackagePricingTier?.takeIf(String::isNotBlank)
        ?: lockedSession.packagePricingTierSnapshot?.takeIf(String::isNotBlank)
        ?: lockedSession.packageId?.let { packageId ->
        packages.firstOrNull { it.id == packageId && it.kind == "base" }?.pricingTier
    } ?: compatibleTiers.singleOrNull() ?: return emptyList()
    return packages.filter {
        it.kind == "extension" && it.stationType == station.type &&
            it.stationType == lockedStationType && it.variant == lockedVariant &&
            it.pricingTier == lockedPricingTier
    }
}

/** Mirrors backend minute-ceiling and minor-unit rounding for a labelled estimate. */
internal fun estimatedCurrentAmountMinor(session: GameSession, nowMillis: Long): Long? {
    if (session.isPackageBilling()) return session.amountMinor
    val rate = session.ratePerHourMinor ?: return null
    val elapsedMillis = sessionPlayElapsedMillis(session, nowMillis) ?: return null
    val billableMinutes = if (elapsedMillis == 0L) 0L else (elapsedMillis + 59_999L) / 60_000L
    if (billableMinutes == 0L || rate <= 0L) return 0L
    return (billableMinutes * rate + 59L) / 60L
}

internal fun sessionAuthorityMessage(
    session: GameSession,
    activeShiftId: String?,
    activeShiftServerConfirmed: Boolean = false,
    online: Boolean = false,
): String? =
    when (session.authority(activeShiftId)) {
        GamingSessionAuthority.CURRENT_SHIFT -> null
        GamingSessionAuthority.NO_OPEN_SHIFT -> "Open a shift to manage this session."
        GamingSessionAuthority.OTHER_SHIFT -> "Managed by the POS shift or terminal that started it."
        GamingSessionAuthority.UNKNOWN -> if (
            session.resolvedStopShiftId(activeShiftId, activeShiftServerConfirmed, online) != null
        ) {
            "This server omitted the session shift. The server will verify this terminal's confirmed open shift."
        } else if (activeShiftServerConfirmed && !online) {
            "The session shift could not be verified while offline. Reconnect and refresh Gaming."
        } else {
            "The session shift could not be verified. Refresh Gaming."
        }
    }

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun sessionWord(count: Int): String = if (count == 1) "session" else "sessions"
