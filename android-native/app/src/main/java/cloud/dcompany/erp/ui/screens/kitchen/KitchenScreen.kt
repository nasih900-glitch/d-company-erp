package cloud.dcompany.erp.ui.screens.kitchen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.KitchenAccess
import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.db.LocalKitchenCancellationAckEntity
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Kitchen Display System.
 *
 * This is not a screen someone leans over and reads — it is a board on a wall,
 * read from across a hot kitchen and tapped by a cook wearing gloves. So:
 * everything is oversized, the advance button is the only thing that can be
 * pressed on a ticket, and the board never goes blank while the server is
 * merely unreachable.
 */
@Composable
fun KitchenScreen(
    access: KitchenAccess = KitchenAccess(),
    onExit: () -> Unit = {},
    vm: KitchenViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showRecovery by remember { mutableStateOf(false) }
    var showCancellationRecovery by remember { mutableStateOf(false) }
    var discardCandidate by remember { mutableStateOf<LocalKitchenAdvanceEntity?>(null) }
    // Pass the state holder, not its value, into the two small time-dependent
    // regions below. Updating it therefore never invalidates the ticket board.
    val wallClock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    SideEffect { vm.updateAccess(access) }
    LaunchedEffect(showRecovery, state.rejectedAdvances.size) {
        if (showRecovery && state.rejectedAdvances.isEmpty()) showRecovery = false
    }
    LaunchedEffect(showCancellationRecovery, state.rejectedCancellationAcks.size) {
        if (showCancellationRecovery && state.rejectedCancellationAcks.isEmpty()) {
            showCancellationRecovery = false
        }
    }

    KeepScreenOn()

    // Realtime is the primary update path; this visible-screen poll is a
    // safety net. The ViewModel is activity-scoped, so the loop belongs here:
    // composition cancellation stops it immediately when staff leave KDS.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var nextRefreshAtMillis = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                wallClock.longValue = now
                if (now >= nextRefreshAtMillis) {
                    vm.refresh()
                    nextRefreshAtMillis = now + KITCHEN_POLL_MS
                }
                // Freshness copy changes only in five-second steps; matching
                // the poll cadence avoids waking an otherwise idle KDS every
                // second while keeping the stale threshold operationally clear.
                delay(KITCHEN_POLL_MS)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        KitchenHeader(
            state,
            wallClock = wallClock,
            onToggleServed = vm::setIncludeServed,
            onRefresh = vm::retry,
            onExit = onExit,
        )
        if (!access.canAdvanceTickets) ViewOnlyNotice()
        KitchenAlertStack(
            state = state,
            wallClock = wallClock,
            onDismissNotice = vm::dismissNotice,
            onDismissError = vm::dismissError,
            onRetry = vm::retry,
            onSync = vm::syncSavedAdvances,
            onReviewAdvances = { showRecovery = true },
            onReviewCancellations = { showCancellationRecovery = true },
        )

        if (state.includeServed) {
            KitchenHistoryContent(
                state = state,
                wallClock = wallClock,
                onRetry = vm::retry,
                modifier = Modifier.weight(1f),
            )
        } else {
            KitchenBoardShell(
                state = state,
                canAdvance = access.canAdvanceTickets,
                onAdvance = vm::advance,
                onAcknowledgeCancellation = vm::acknowledgeCancellation,
                wallClock = wallClock,
                onRetry = vm::retry,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showRecovery && discardCandidate == null) {
        KitchenAdvanceRecoveryDialog(
            rows = state.rejectedAdvances,
            canRetry = access.canAdvanceTickets,
            onRetry = vm::retryRejectedAdvance,
            onRemove = { discardCandidate = it },
            onDismiss = { showRecovery = false },
        )
    }

    if (showCancellationRecovery) {
        KitchenCancellationRecoveryDialog(
            rows = state.rejectedCancellationAcks,
            canRetry = access.canAdvanceTickets,
            onRetry = vm::retryRejectedCancellationAck,
            onDismiss = { showCancellationRecovery = false },
        )
    }

    discardCandidate?.let { row ->
        AlertDialog(
            onDismissRequest = { discardCandidate = null },
            title = { Text("Remove this saved kitchen update?") },
            text = {
                Text(
                    "This changes only this tablet; it does not move the server ticket. " +
                        "Remove it only after checking the ticket is already correct, or when " +
                        "you will advance it again from the current server state.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardCandidate = null
                        vm.discardRejectedAdvance(row.localId)
                    },
                ) { Text("Remove saved update") }
            },
            dismissButton = {
                TextButton(onClick = { discardCandidate = null }) { Text("Keep it") }
            },
        )
    }
}

/** A stale empty board is still stale. Keeping this policy outside Compose
 * prevents a future visual cleanup from restoring the dangerous old
 * `orders.isNotEmpty()` condition. Explicit refresh/action errors already
 * communicate the outage, so they suppress the duplicate warning. */
internal fun shouldShowKitchenStaleWarning(
    state: KitchenUiState,
    nowMillis: Long,
): Boolean = !state.includeServed &&
    state.error == null &&
    state.refreshError == null &&
    kitchenFreshness(state.lastSyncedAtMillis, nowMillis).stale

@Composable
private fun KitchenAlertStack(
    state: KitchenUiState,
    wallClock: State<Long>,
    onDismissNotice: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onSync: () -> Unit,
    onReviewAdvances: () -> Unit,
    onReviewCancellations: () -> Unit,
) {
    val stale = shouldShowKitchenStaleWarning(state, wallClock.value)
    val showHistoryError = state.includeServed &&
        state.historyStatus == KitchenHistoryStatus.LOADED && state.historyError != null
    val showRefreshError = !state.includeServed && state.refreshError != null
    val hasAlerts = state.notice != null || state.error != null || showRefreshError ||
        showHistoryError || stale || state.rejectedAdvances.isNotEmpty() ||
        state.pendingAdvances.isNotEmpty() || state.rejectedCancellationAcks.isNotEmpty() ||
        state.pendingCancellationAcks.isNotEmpty()
    if (!hasAlerts) return

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            "Alerts & recovery",
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        state.notice?.let { message ->
            KitchenBanner(
                title = "Kitchen update saved",
                detail = message,
                tone = UiTone.Success,
                icon = Icons.Default.CheckCircle,
                actionLabel = "Dismiss",
                onAction = onDismissNotice,
            )
        }
        state.error?.let { message ->
            KitchenBanner(
                title = "Kitchen action needs attention",
                detail = message,
                tone = UiTone.Danger,
                icon = Icons.Default.ErrorOutline,
                actionLabel = "Dismiss",
                onAction = onDismissError,
            )
        }
        if (showRefreshError) {
            KitchenBanner(
                title = "Kitchen queue may be out of date",
                detail = state.refreshError!!,
                tone = UiTone.Danger,
                icon = Icons.Default.ErrorOutline,
                actionLabel = "Retry",
                onAction = onRetry,
            )
        }
        if (showHistoryError) {
            KitchenBanner(
                title = "Served history could not refresh",
                detail = "Showing the last history loaded on this screen. ${state.historyError}",
                tone = UiTone.Warning,
                icon = Icons.Default.History,
                actionLabel = "Retry",
                onAction = onRetry,
            )
        }
        if (stale) {
            KitchenBanner(
                title = "Kitchen queue is not updating",
                detail = if (state.orders.isEmpty()) {
                    "No tickets are cached, but the last successful update is old. Retry before treating the board as clear."
                } else {
                    "The visible tickets are saved locally, but this board may be out of date."
                },
                tone = UiTone.Warning,
                icon = Icons.Default.Schedule,
                actionLabel = "Retry",
                onAction = onRetry,
            )
        }
        if (state.rejectedAdvances.isNotEmpty()) {
            val count = state.rejectedAdvances.size
            KitchenBanner(
                title = "Kitchen updates need review",
                detail = if (count == 1) {
                    "1 kitchen update needs review before this account can sign out."
                } else {
                    "$count kitchen updates need review before this account can sign out."
                },
                tone = UiTone.Danger,
                icon = Icons.Default.ErrorOutline,
                actionLabel = "Review",
                onAction = onReviewAdvances,
            )
        } else if (state.pendingAdvances.isNotEmpty()) {
            val count = state.pendingAdvances.size
            KitchenBanner(
                title = "Kitchen updates are waiting to sync",
                detail = if (count == 1) {
                    "1 kitchen update is saved and waiting for server confirmation."
                } else {
                    "$count kitchen updates are saved and waiting for server confirmation."
                },
                tone = UiTone.Warning,
                icon = Icons.Default.Schedule,
                actionLabel = "Sync now",
                onAction = onSync,
            )
        }
        if (state.rejectedCancellationAcks.isNotEmpty()) {
            KitchenBanner(
                title = "Cancellation acknowledgements need review",
                detail = "${state.rejectedCancellationAcks.size} acknowledgement(s) were not accepted by the server.",
                tone = UiTone.Danger,
                icon = Icons.Default.ErrorOutline,
                actionLabel = "Review",
                onAction = onReviewCancellations,
            )
        } else if (state.pendingCancellationAcks.isNotEmpty()) {
            KitchenBanner(
                title = "Cancellation acknowledgements are syncing",
                detail = "${state.pendingCancellationAcks.size} acknowledgement(s) remain highlighted until confirmed.",
                tone = UiTone.Warning,
                icon = Icons.Default.Schedule,
                actionLabel = "Sync now",
                onAction = onSync,
            )
        }
    }
}

@Composable
private fun KitchenEmptyBoardForState(
    state: KitchenUiState,
    wallClock: State<Long>,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Served history has its own explicit load state. Active-queue freshness
    // must not rename a successfully loaded, empty history result as stale.
    val stale = !state.includeServed &&
        kitchenFreshness(state.lastSyncedAtMillis, wallClock.value).stale
    KitchenEmptyBoard(
        title = when {
            stale -> "No cached tickets — live status unverified"
            state.includeServed -> "Nothing served yet today"
            else -> "Board is clear"
        },
        body = when {
            stale -> "The last successful update is old. Use Check now before assuming no new orders are waiting."
            state.includeServed -> "Tickets the kitchen finishes today will be listed here."
            else -> "No tickets waiting. New orders from the till appear here on their " +
                "own within a few seconds — nothing to do until then."
        },
        actionLabel = "Check now",
        onAction = onAction,
        modifier = modifier,
    )
}

@Composable
private fun KitchenHistoryContent(
    state: KitchenUiState,
    wallClock: State<Long>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.historyStatus) {
        KitchenHistoryStatus.INACTIVE,
        KitchenHistoryStatus.LOADING -> KitchenHistoryLoadingBoard(modifier)

        KitchenHistoryStatus.FAILED -> KitchenEmptyBoard(
            title = "Could not load served history",
            body = buildString {
                append("The live kitchen queue has not been shown in its place. ")
                append(state.historyError ?: "Check the connection and try again.")
            },
            actionLabel = "Retry",
            onAction = onRetry,
            modifier = modifier,
        )

        KitchenHistoryStatus.LOADED -> if (state.orders.isEmpty()) {
            KitchenEmptyBoardForState(
                state = state,
                wallClock = wallClock,
                onAction = onRetry,
                modifier = modifier,
            )
        } else {
            KitchenBoardShell(
                state = state,
                // History is explicitly read-only; return to Live board for
                // state transitions or cancellation acknowledgements.
                canAdvance = false,
                onAdvance = {},
                onAcknowledgeCancellation = { _, _ -> },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun KitchenHistoryLoadingBoard(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Brand.Gold, strokeWidth = 3.dp)
        Text(
            "Loading served history",
            modifier = Modifier.padding(top = Spacing.lg),
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Fetching today's kitchen history. Live tickets will never be shown as a substitute.",
            modifier = Modifier.padding(top = Spacing.sm),
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A kitchen board that has dimmed itself is useless, and a cook with wet or
 * gloved hands should not have to wake it. Held only while this screen is on
 * screen, and released on the way out so the rest of the app keeps normal
 * screen-timeout behaviour.
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Composable
private fun KitchenHeader(
    state: KitchenUiState,
    wallClock: State<Long>,
    onToggleServed: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(Brand.Surface)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        val wide = maxWidth >= 760.dp
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KitchenSourceStatusBadge(state, wallClock)
                    Text(
                        if (state.includeServed) {
                            "Review server-confirmed tickets completed today."
                        } else {
                            "Move tickets through New → Preparing → Ready."
                        },
                        modifier = Modifier.weight(1f),
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ErpButton(
                        text = "Refresh",
                        onClick = onRefresh,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.Refresh,
                    )
                    ErpButton(
                        text = "Exit KDS",
                        onClick = onExit,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.AutoMirrored.Filled.ExitToApp,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KitchenSourceStatusBadge(state, wallClock)
                    Spacer(Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ErpButton(
                        text = "Refresh",
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.Refresh,
                    )
                    ErpButton(
                        text = "Exit KDS",
                        onClick = onExit,
                        modifier = Modifier.weight(1f),
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.AutoMirrored.Filled.ExitToApp,
                    )
                }
            }

            KitchenStatusSummary(state = state, wide = wide)
            PremiumTabBar(
                options = listOf(
                    TabOption("live", "Live board"),
                    TabOption("served", "Served today"),
                ),
                selectedId = if (state.includeServed) "served" else "live",
                onSelect = { selected -> onToggleServed(selected == "served") },
            )
        }
    }
}

@Composable
private fun KitchenSourceStatusBadge(
    state: KitchenUiState,
    wallClock: State<Long>,
) {
    if (!state.includeServed) {
        KitchenConnectionBadge(state.lastSyncedAtMillis, wallClock)
        return
    }
    val (label, tone) = when {
        state.historyStatus == KitchenHistoryStatus.FAILED -> "History unavailable" to UiTone.Danger
        state.historyRefreshing -> "Updating history" to UiTone.Information
        state.historyStatus == KitchenHistoryStatus.LOADED && state.historyError != null ->
            "History may be out of date" to UiTone.Warning
        state.historyStatus == KitchenHistoryStatus.LOADED -> "History loaded" to UiTone.Success
        else -> "Loading history" to UiTone.Information
    }
    OperationalStatusBadge(label = label, tone = tone, icon = Icons.Default.History)
}

@Composable
private fun KitchenConnectionBadge(
    lastSyncedAtMillis: Long?,
    wallClock: State<Long>,
) {
    val freshness = kitchenFreshness(lastSyncedAtMillis, wallClock.value)
    val (label, tone) = when {
        freshness.secondsSinceSync == null -> "Connecting" to UiTone.Information
        freshness.stale -> "Update delayed" to UiTone.Warning
        else -> "Updated ${freshness.secondsSinceSync}s ago" to UiTone.Success
    }
    OperationalStatusBadge(label = label, tone = tone)
}

@Composable
private fun KitchenStatusSummary(state: KitchenUiState, wide: Boolean) {
    if (wide) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            CompactStatCard(
                label = "New",
                value = state.newCount.toString(),
                detail = "Waiting to start",
                icon = Icons.Default.Restaurant,
                tone = UiTone.Information,
                modifier = Modifier.weight(1f),
            )
            CompactStatCard(
                label = "Preparing",
                value = state.preparingCount.toString(),
                detail = "Being made now",
                icon = Icons.Default.Schedule,
                tone = UiTone.Warning,
                modifier = Modifier.weight(1f),
            )
            CompactStatCard(
                label = "Ready",
                value = state.readyCount.toString(),
                detail = "Waiting to serve",
                icon = Icons.Default.CheckCircle,
                tone = UiTone.Success,
                modifier = Modifier.weight(1f),
            )
            if (state.includeServed) {
                CompactStatCard(
                    label = "Served",
                    value = state.lane(KitchenState.SERVED).size.toString(),
                    detail = "Completed today",
                    icon = Icons.Default.History,
                    tone = UiTone.Neutral,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                CompactStatCard(
                    label = "New",
                    value = state.newCount.toString(),
                    detail = "Waiting to start",
                    icon = Icons.Default.Restaurant,
                    tone = UiTone.Information,
                    modifier = Modifier.width(210.dp),
                )
            }
            item {
                CompactStatCard(
                    label = "Preparing",
                    value = state.preparingCount.toString(),
                    detail = "Being made now",
                    icon = Icons.Default.Schedule,
                    tone = UiTone.Warning,
                    modifier = Modifier.width(210.dp),
                )
            }
            item {
                CompactStatCard(
                    label = "Ready",
                    value = state.readyCount.toString(),
                    detail = "Waiting to serve",
                    icon = Icons.Default.CheckCircle,
                    tone = UiTone.Success,
                    modifier = Modifier.width(210.dp),
                )
            }
            if (state.includeServed) {
                item {
                    CompactStatCard(
                        label = "Served",
                        value = state.lane(KitchenState.SERVED).size.toString(),
                        detail = "Completed today",
                        icon = Icons.Default.History,
                        tone = UiTone.Neutral,
                        modifier = Modifier.width(210.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KitchenBanner(
    title: String,
    detail: String,
    tone: UiTone,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
) {
    OperationalBanner(
        title = title,
        detail = detail,
        tone = tone,
        icon = icon,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        action = {
            ErpButton(
                text = actionLabel,
                onClick = onAction,
                intent = ActionIntent.Secondary,
            )
        },
    )
}

@Composable
private fun KitchenAdvanceRecoveryDialog(
    rows: List<LocalKitchenAdvanceEntity>,
    canRetry: Boolean,
    onRetry: (String) -> Unit,
    onRemove: (LocalKitchenAdvanceEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review saved kitchen updates") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "These updates were kept because the server could not safely apply or " +
                        "confirm them. Check each ticket before choosing an action.",
                    color = Brand.ForegroundMuted,
                )
                if (!canRetry) {
                    Text(
                        "This account can view recovery but cannot resend a kitchen update. " +
                            "Check the ticket, then remove only a failed local update.",
                        color = Brand.GoldMuted,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.localId }) { row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(Radius.shapeMd)
                                .background(Brand.SurfaceRaised)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val target = KitchenState.from(row.targetState)?.label
                                ?: row.targetState
                            Text(
                                "Ticket #${row.orderId.take(8)} · move to $target",
                                color = Brand.Foreground,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                row.lastError ?: "The server did not provide a reason.",
                                color = Brand.Danger,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { onRetry(row.localId) },
                                    enabled = canRetry,
                                ) { Text("Check again") }
                                TextButton(onClick = { onRemove(row) }) {
                                    Text("Remove saved update")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun KitchenCancellationRecoveryDialog(
    rows: List<LocalKitchenCancellationAckEntity>,
    canRetry: Boolean,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review cancellation acknowledgements") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.localId }) { row ->
                    Column(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Ticket #${row.orderId.take(8)} · cancelled line ${row.lineId.take(8)}",
                            color = Brand.Foreground,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            row.lastError ?: "The server did not provide a reason.",
                            color = Brand.Danger,
                        )
                        TextButton(
                            onClick = { onRetry(row.localId) },
                            enabled = canRetry,
                        ) { Text("Refresh and retry acknowledgement") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun KitchenEmptyBoard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DesignedEmptyState(
            title = title,
            body = body,
            icon = Icons.Default.Restaurant,
        )
        Box(Modifier.fillMaxWidth().padding(bottom = Spacing.xl), contentAlignment = Alignment.Center) {
            ErpButton(
                text = actionLabel,
                onClick = onAction,
                leadingIcon = Icons.Default.Refresh,
            )
        }
    }
}

@Composable
private fun KitchenBoardShell(
    state: KitchenUiState,
    canAdvance: Boolean,
    onAdvance: (KitchenOrder) -> Unit,
    onAcknowledgeCancellation: (String, String) -> Unit,
    wallClock: State<Long>? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.BackgroundSecondary)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (state.includeServed) "Today's served tickets" else "Live production board",
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (state.includeServed) {
                        "History is read-only and comes directly from the server."
                    } else {
                        "Ticket moves are saved on this tablet first and remain visible until reconciled."
                    },
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            OperationalStatusBadge(
                label = if (state.includeServed) "History" else "Live queue",
                tone = if (state.includeServed) UiTone.Neutral else UiTone.Success,
                icon = if (state.includeServed) Icons.Default.History else Icons.Default.Restaurant,
            )
        }
        HorizontalDivider(color = Brand.BorderSubtle)
        if (!state.includeServed && state.orders.isEmpty() && wallClock != null && onRetry != null) {
            KitchenLiveBoardStatus(
                state = state,
                wallClock = wallClock,
                onRetry = onRetry,
                modifier = Modifier.padding(Spacing.md),
            )
            HorizontalDivider(color = Brand.BorderSubtle)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Board(
                state = state,
                canAdvance = canAdvance,
                onAdvance = onAdvance,
                onAcknowledgeCancellation = onAcknowledgeCancellation,
            )
        }
    }
}

@Composable
private fun KitchenLiveBoardStatus(
    state: KitchenUiState,
    wallClock: State<Long>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stale = kitchenFreshness(state.lastSyncedAtMillis, wallClock.value).stale
    val presentation = when {
        state.blockingLoadError != null -> Triple(
            "Queue unavailable — lanes retained",
            "Reconnect and retry. Saved kitchen work has not been removed.",
            UiTone.Danger,
        )
        !state.everSynced -> Triple(
            "Connecting to the kitchen queue",
            "Downloading the live preparation queue. Retrying does not change a ticket.",
            UiTone.Information,
        )
        stale -> Triple(
            "No cached tickets — live status unverified",
            "The last successful update is old. Check now before treating the board as clear.",
            UiTone.Warning,
        )
        else -> Triple(
            "Board is clear",
            "New orders from the till appear automatically in the New lane.",
            UiTone.Success,
        )
    }
    OperationalBanner(
        title = presentation.first,
        detail = presentation.second,
        tone = presentation.third,
        icon = if (presentation.third == UiTone.Danger) Icons.Default.ErrorOutline else Icons.Default.Restaurant,
        modifier = modifier,
        action = {
            ErpButton(
                text = "Check now",
                onClick = onRetry,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.Refresh,
            )
        },
    )
}

/**
 * Lanes on a tablet, one list on a phone. Lanes are how a kitchen already
 * thinks — what is waiting, what is on, what is up — and they keep each
 * ticket's position stable as others move, which matters when the thing
 * pointing at a ticket is a gloved finger and not a mouse.
 */
@Composable
private fun Board(
    state: KitchenUiState,
    canAdvance: Boolean,
    onAdvance: (KitchenOrder) -> Unit,
    onAcknowledgeCancellation: (String, String) -> Unit,
) {
    val sections = buildKitchenBoardSections(state)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            val visibleLaneCount = minOf(3, sections.size).coerceAtLeast(1)
            val gap = Spacing.md
            val laneWidth = ((maxWidth - Spacing.xl - gap * (visibleLaneCount - 1)) / visibleLaneCount)
                .coerceAtLeast(280.dp)
            LazyRow(
                Modifier.fillMaxSize().padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(sections, key = KitchenBoardSection::title) { section ->
                    Lane(
                        title = section.title,
                        orders = section.orders,
                        state = state,
                        canAdvance = canAdvance,
                        onAdvance = onAdvance,
                        onAcknowledgeCancellation = onAcknowledgeCancellation,
                        modifier = Modifier.width(laneWidth).height(maxHeight - Spacing.xl),
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                sections.forEach { section ->
                    item(key = "lane:${section.title}") {
                        Row(
                            Modifier.fillMaxWidth().clip(Radius.shapeMd)
                                .background(Brand.Surface)
                                .border(1.dp, Brand.BorderSubtle, Radius.shapeMd)
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                section.title.uppercase(),
                                color = Brand.ForegroundMuted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            OperationalStatusBadge(
                                label = "${section.orders.size}",
                                tone = laneTone(section.title),
                            )
                        }
                    }
                    if (section.orders.isEmpty()) {
                        item(key = "empty:${section.title}") {
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                    .clip(Radius.shapeMd).background(Brand.Surface)
                                    .border(1.dp, Brand.BorderSubtle, Radius.shapeMd),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    emptyLaneMessage(section.title),
                                    color = Brand.ForegroundFaint,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else {
                        items(section.orders, key = { "ticket:${it.id}" }) { order ->
                            TicketCard(
                                order,
                                state,
                                canAdvance,
                                onAdvance = onAdvance,
                                onAcknowledgeCancellation = onAcknowledgeCancellation,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Produces the single section assignment used by both board layouts. A ticket
 * can match more than one derived group (for example, an unknown-state ticket
 * whose only remaining work is a cancellation), but it must render only once:
 * duplicate cards are confusing and duplicate Lazy keys crash compact KDS.
 */
internal data class KitchenBoardSection(
    val title: String,
    val orders: List<KitchenOrder>,
)

internal fun buildKitchenBoardSections(state: KitchenUiState): List<KitchenBoardSection> {
    val seenOrderIds = mutableSetOf<String>()
    val sections = mutableListOf<KitchenBoardSection>()

    fun addSection(title: String, candidates: List<KitchenOrder>, keepWhenEmpty: Boolean) {
        val uniqueOrders = candidates.filter { seenOrderIds.add(it.id) }
        if (keepWhenEmpty || uniqueOrders.isNotEmpty()) {
            sections += KitchenBoardSection(title, uniqueOrders)
        }
    }

    if (!state.includeServed) {
        addSection("Cancellations", state.cancellationOnly, keepWhenEmpty = false)
    }

    val lanes = buildList {
        add(KitchenState.RECEIVED)
        add(KitchenState.PREPARING)
        add(KitchenState.READY)
        if (state.includeServed) add(KitchenState.SERVED)
    }
    lanes.forEach { lane ->
        addSection(lane.label, state.lane(lane), keepWhenEmpty = true)
    }
    addSection("Other", state.unknownState, keepWhenEmpty = false)

    return sections
}

@Composable
private fun Lane(
    title: String,
    orders: List<KitchenOrder>,
    state: KitchenUiState,
    canAdvance: Boolean,
    onAdvance: (KitchenOrder) -> Unit,
    onAcknowledgeCancellation: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg).padding(Spacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Brand.ForegroundMuted,
            )
            Text(
                "${orders.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Brand.ForegroundMuted,
            )
        }
        if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    emptyLaneMessage(title),
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(orders, key = { it.id }) { order ->
                    TicketCard(
                        order,
                        state,
                        canAdvance,
                        onAdvance = onAdvance,
                        onAcknowledgeCancellation = onAcknowledgeCancellation,
                    )
                }
            }
        }
    }
}

private fun emptyLaneMessage(title: String): String = when (title) {
    KitchenState.RECEIVED.label -> "Waiting for new tickets"
    KitchenState.PREPARING.label -> "Nothing being prepared"
    KitchenState.READY.label -> "Nothing waiting to serve"
    KitchenState.SERVED.label -> "No served tickets"
    "Cancellations" -> "No cancellations waiting"
    else -> "No tickets in this lane"
}

private fun laneTone(title: String): UiTone = when (title) {
    KitchenState.RECEIVED.label -> UiTone.Information
    KitchenState.PREPARING.label -> UiTone.Warning
    KitchenState.READY.label -> UiTone.Success
    "Cancellations" -> UiTone.Danger
    else -> UiTone.Neutral
}

@Composable
private fun TicketCard(
    order: KitchenOrder,
    state: KitchenUiState,
    canAdvance: Boolean,
    onAdvance: (KitchenOrder) -> Unit,
    onAcknowledgeCancellation: (String, String) -> Unit,
) {
    val ticketState = KitchenState.from(order.kitchenState)
    val accent = accentFor(ticketState)
    val busy = state.busyOrderId == order.id

    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(if (ticketState == KitchenState.SERVED) Brand.Surface else Brand.SurfaceRaised)
            .border(1.dp, accent.copy(alpha = 0.48f), Radius.shapeLg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    order.ticketLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Brand.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${order.typeLabel} · ${order.whoFor}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                WaitBadge(order.minutesWaiting)
                OperationalStatusBadge(
                    label = ticketState?.label ?: order.kitchenState,
                    tone = ticketTone(ticketState),
                )
            }
        }

        order.pendingCancellations.forEach { cancellation ->
            val saved = cancellation.lineId in state.acknowledgingLineIds
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeMd)
                    .background(Brand.DangerMuted).border(2.dp, Brand.Danger, Radius.shapeMd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "CANCEL ${cancellation.qty.asQtyPrefix()}× ${cancellation.name}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Brand.Danger,
                )
                Text(
                    "Round ${cancellation.roundNo} · ${cancellation.reason}",
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                cancellation.notes?.takeIf(String::isNotBlank)?.let { note ->
                    Text("Original request: $note", color = Brand.ForegroundMuted)
                }
                Button(
                    onClick = { onAcknowledgeCancellation(order.id, cancellation.lineId) },
                    enabled = canAdvance && !saved,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand.Danger,
                        contentColor = Brand.Background,
                    ),
                ) {
                    Text(
                        if (saved) "Acknowledgement saved…" else "Acknowledge cancellation",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        order.lines.forEach { line ->
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${line.qty.asQtyPrefix()}×",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Foreground,
                        modifier = Modifier.width(48.dp),
                    )
                    Text(
                        "${line.name} · R${line.roundNo}",
                        fontSize = 18.sp,
                        color = Brand.Foreground,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Notes are the single most expensive thing to miss on a
                // ticket — "no onions" reaching the table wrong is a remake.
                line.notes?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        "Note: $note",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Warning,
                        modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                    )
                }
            }
        }

        val advanceLabel = ticketState?.advanceLabel
        if (advanceLabel != null) {
            Button(
                onClick = { onAdvance(order) },
                enabled = canAdvance && !state.tapsLocked,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = Radius.shapeMd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Brand.Background,
                    disabledContainerColor = accent.copy(alpha = 0.35f),
                    disabledContentColor = Brand.Background.copy(alpha = 0.6f),
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Brand.Background,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Text(advanceLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else if (ticketState == KitchenState.SERVED) {
            Text(
                "Served",
                style = MaterialTheme.typography.labelLarge,
                color = Brand.ForegroundMuted,
            )
        } else {
            // Unknown state from a newer backend: show it, do not offer a move
            // this build cannot describe.
            Text(
                "State: ${order.kitchenState} — advance it from the till.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
    }
}

/**
 * The number the cook actually acts on. Colour, not just digits, because at
 * three metres a red badge reads before a "22" does.
 */
@Composable
private fun WaitBadge(minutes: Int) {
    val colour = when {
        minutes >= 20 -> Brand.Danger
        minutes >= 10 -> Brand.Warning
        else -> Brand.ForegroundMuted
    }
    Text(
        "${minutes}m",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = colour,
    )
}

private fun accentFor(state: KitchenState?): Color = when (state) {
    KitchenState.RECEIVED -> Brand.Information
    KitchenState.PREPARING -> Brand.Warning
    KitchenState.READY -> Brand.Good
    KitchenState.SERVED -> Brand.ForegroundMuted
    else -> Brand.Danger
}

private fun ticketTone(state: KitchenState?): UiTone = when (state) {
    KitchenState.RECEIVED -> UiTone.Information
    KitchenState.PREPARING -> UiTone.Warning
    KitchenState.READY -> UiTone.Success
    KitchenState.SERVED -> UiTone.Neutral
    else -> UiTone.Danger
}
