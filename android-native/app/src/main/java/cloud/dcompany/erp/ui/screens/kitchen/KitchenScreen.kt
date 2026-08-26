package cloud.dcompany.erp.ui.screens.kitchen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import cloud.dcompany.erp.ui.components.PageHeader
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
    val state by vm.state.collectAsState()
    var showRecovery by remember { mutableStateOf(false) }
    var showCancellationRecovery by remember { mutableStateOf(false) }
    var discardCandidate by remember { mutableStateOf<LocalKitchenAdvanceEntity?>(null) }
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
                vm.tick()
                if (now >= nextRefreshAtMillis) {
                    vm.refresh()
                    nextRefreshAtMillis = now + KITCHEN_POLL_MS
                }
                delay(1_000)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        KitchenHeader(
            state,
            onToggleServed = vm::setIncludeServed,
            onRefresh = vm::retry,
            onExit = onExit,
        )
        if (!access.canAdvanceTickets) ViewOnlyNotice()

        state.notice?.let { message ->
            KitchenBanner(
                title = "Kitchen update saved",
                detail = message,
                tone = UiTone.Success,
                icon = Icons.Default.CheckCircle,
                actionLabel = "Dismiss",
                onAction = vm::dismissNotice,
            )
        }

        // With tickets on screen an error is a banner, never a takeover.
        state.error?.let { message ->
            if (state.orders.isNotEmpty()) {
                KitchenBanner(
                    title = "Kitchen action needs attention",
                    detail = message,
                    tone = UiTone.Danger,
                    icon = Icons.Default.ErrorOutline,
                    actionLabel = "Dismiss",
                    onAction = vm::dismissError,
                )
            }
        }
        state.refreshError?.let { message ->
            if (state.orders.isNotEmpty()) {
                KitchenBanner(
                    title = "Kitchen queue may be out of date",
                    detail = message,
                    tone = UiTone.Danger,
                    icon = Icons.Default.ErrorOutline,
                    actionLabel = "Retry",
                    onAction = vm::retry,
                )
            }
        }
        if (shouldShowKitchenStaleWarning(state)) {
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
                onAction = vm::retry,
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
                onAction = { showRecovery = true },
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
                onAction = vm::syncSavedAdvances,
            )
        }
        if (state.rejectedCancellationAcks.isNotEmpty()) {
            KitchenBanner(
                title = "Cancellation acknowledgements need review",
                detail = "${state.rejectedCancellationAcks.size} acknowledgement(s) were not accepted by the server.",
                tone = UiTone.Danger,
                icon = Icons.Default.ErrorOutline,
                actionLabel = "Review",
                onAction = { showCancellationRecovery = true },
            )
        } else if (state.pendingCancellationAcks.isNotEmpty()) {
            KitchenBanner(
                title = "Cancellation acknowledgements are syncing",
                detail = "${state.pendingCancellationAcks.size} acknowledgement(s) remain highlighted until confirmed.",
                tone = UiTone.Warning,
                icon = Icons.Default.Schedule,
                actionLabel = "Sync now",
                onAction = vm::syncSavedAdvances,
            )
        }

        when {
            !state.everSynced && state.orders.isEmpty() && state.blockingLoadError == null ->
                KitchenEmptyBoard(
                    title = "Connecting to the kitchen queue",
                    body = "This tablet is downloading the live preparation queue. Retry is safe and does not change any ticket.",
                    actionLabel = "Check now",
                    onAction = vm::retry,
                    modifier = Modifier.weight(1f),
                )

            state.blockingLoadError != null && state.orders.isEmpty() ->
                KitchenEmptyBoard(
                    title = "Cannot load the kitchen queue",
                    body = state.blockingLoadError!!,
                    actionLabel = "Retry",
                    onAction = vm::retry,
                    modifier = Modifier.weight(1f),
                )

            state.orders.isEmpty() -> KitchenEmptyBoard(
                title = when {
                    state.stale -> "No cached tickets — live status unverified"
                    state.includeServed -> "Nothing served yet today"
                    else -> "Board is clear"
                },
                body = when {
                    state.stale -> "The last successful update is old. Use Check now before assuming no new orders are waiting."
                    state.includeServed -> {
                    "Tickets the kitchen finishes today will be listed here."
                    }
                    else -> {
                    "No tickets waiting. New orders from the till appear here on their " +
                        "own within a few seconds — nothing to do until then."
                    }
                },
                actionLabel = "Check now",
                onAction = vm::retry,
                modifier = Modifier.weight(1f),
            )

            else -> KitchenBoardShell(
                state = state,
                canAdvance = access.canAdvanceTickets,
                onAdvance = vm::advance,
                onAcknowledgeCancellation = vm::acknowledgeCancellation,
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
internal fun shouldShowKitchenStaleWarning(state: KitchenUiState): Boolean =
    state.error == null && state.refreshError == null && state.stale

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
                PageHeader(
                    title = "Kitchen display",
                    subtitle = "Move every ticket through New, Preparing and Ready without losing offline work.",
                    eyebrow = "Live operations",
                    actions = {
                        KitchenConnectionBadge(state)
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
                    },
                )
            } else {
                PageHeader(
                    title = "Kitchen display",
                    subtitle = "Live preparation queue",
                    eyebrow = "KDS",
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KitchenConnectionBadge(state)
                    Spacer(Modifier.weight(1f))
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
private fun KitchenConnectionBadge(state: KitchenUiState) {
    val (label, tone) = when {
        state.secondsSinceSync == null -> "Connecting" to UiTone.Information
        state.stale -> "Update delayed" to UiTone.Warning
        else -> "Updated ${state.secondsSinceSync}s ago" to UiTone.Success
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
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Assertive },
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
            val ordered = sections.flatMap(KitchenBoardSection::orders)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ordered, key = { it.id }) { order ->
                    TicketCard(
                        order,
                        state,
                        canAdvance,
                        showStateTag = true,
                        onAdvance = onAdvance,
                        onAcknowledgeCancellation = onAcknowledgeCancellation,
                    )
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
            Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
                Text(
                    "—",
                    color = Brand.Border,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(orders, key = { it.id }) { order ->
                    TicketCard(
                        order,
                        state,
                        canAdvance,
                        showStateTag = false,
                        onAdvance = onAdvance,
                        onAcknowledgeCancellation = onAcknowledgeCancellation,
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketCard(
    order: KitchenOrder,
    state: KitchenUiState,
    canAdvance: Boolean,
    showStateTag: Boolean,
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
            .background(accent.copy(alpha = if (ticketState == KitchenState.RECEIVED) 0f else 0.10f))
            .border(2.dp, accent.copy(alpha = 0.65f), Radius.shapeLg)
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
                if (showStateTag) {
                    Text(
                        ticketState?.label ?: order.kitchenState,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
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
                        color = Brand.Gold,
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
                        color = Brand.Gold,
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
        minutes >= 10 -> Brand.Gold
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
    KitchenState.PREPARING -> Brand.Gold
    KitchenState.READY -> Brand.Good
    KitchenState.SERVED -> Brand.Border
    else -> Brand.GoldMuted
}
