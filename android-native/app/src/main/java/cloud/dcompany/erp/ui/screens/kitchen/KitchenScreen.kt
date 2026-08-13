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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.theme.Brand
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
fun KitchenScreen(vm: KitchenViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    KeepScreenOn()

    // Polling replaces the web build's realtime subscription — this app has no
    // socket layer yet. Tied to RESUMED so a tablet that is off or showing
    // another screen is not hammering the server every five seconds.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                vm.refresh()
                delay(KITCHEN_POLL_MS)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        KitchenHeader(state, onToggleServed = vm::setIncludeServed, onRefresh = vm::retry)

        // With tickets on screen an error is a banner, never a takeover.
        state.error?.let { message ->
            if (state.orders.isNotEmpty()) {
                Banner(message, Brand.Danger, "Dismiss", vm::dismissError)
            }
        }
        if (state.error == null && state.stale && state.orders.isNotEmpty()) {
            Banner(
                "Not updating — this board may be out of date.",
                Brand.GoldMuted,
                "Retry",
                vm::retry,
            )
        }

        when {
            state.loading && state.orders.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Gold)
                }

            state.error != null && state.orders.isEmpty() ->
                CentredMessage(
                    title = "Cannot load the kitchen queue",
                    body = state.error!!,
                    actionLabel = "Retry",
                    onAction = vm::retry,
                )

            state.orders.isEmpty() -> CentredMessage(
                title = if (state.includeServed) "Nothing served yet today" else "Board is clear",
                body = if (state.includeServed) {
                    "Tickets the kitchen finishes today will be listed here."
                } else {
                    "No tickets waiting. New orders from the till appear here on their " +
                        "own within a few seconds — nothing to do until then."
                },
                actionLabel = "Check now",
                onAction = vm::retry,
            )

            else -> Board(state, vm::advance)
        }
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
    onToggleServed: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Kitchen",
                style = MaterialTheme.typography.headlineMedium,
                color = Brand.Gold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (val secs = state.secondsSinceSync) {
                        null -> "connecting…"
                        else -> "updated ${secs}s ago"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.stale) Brand.Danger else Brand.ForegroundMuted,
                )
                OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Refresh")
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountChip("${state.newCount} new", Brand.ForegroundMuted)
            CountChip("${state.preparingCount} preparing", Brand.Gold)
            CountChip("${state.readyCount} ready", Brand.Good)
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = state.includeServed,
                onClick = { onToggleServed(!state.includeServed) },
                label = { Text("Served today") },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                    labelColor = Brand.ForegroundMuted,
                ),
            )
        }
    }
}

@Composable
private fun CountChip(label: String, colour: Color) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colour.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = colour,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Banner(message: String, colour: Color, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(colour).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = Brand.Background,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) {
            Text(actionLabel, color = Brand.Background, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CentredMessage(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp).width(520.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground)
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.ForegroundMuted,
            )
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 56.dp).padding(top = 8.dp),
            ) { Text(actionLabel, fontSize = 17.sp) }
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
private fun Board(state: KitchenUiState, onAdvance: (KitchenOrder) -> Unit) {
    val lanes = buildList {
        add(KitchenState.RECEIVED)
        add(KitchenState.PREPARING)
        add(KitchenState.READY)
        if (state.includeServed) add(KitchenState.SERVED)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                lanes.forEach { lane ->
                    Lane(
                        title = lane.label,
                        orders = state.lane(lane),
                        state = state,
                        onAdvance = onAdvance,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.unknownState.isNotEmpty()) {
                    Lane(
                        title = "Other",
                        orders = state.unknownState,
                        state = state,
                        onAdvance = onAdvance,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            val ordered = lanes.flatMap { state.lane(it) } + state.unknownState
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ordered, key = { it.id }) { order ->
                    TicketCard(order, state, showStateTag = true, onAdvance = onAdvance)
                }
            }
        }
    }
}

@Composable
private fun Lane(
    title: String,
    orders: List<KitchenOrder>,
    state: KitchenUiState,
    onAdvance: (KitchenOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
                    TicketCard(order, state, showStateTag = false, onAdvance = onAdvance)
                }
            }
        }
    }
}

@Composable
private fun TicketCard(
    order: KitchenOrder,
    state: KitchenUiState,
    showStateTag: Boolean,
    onAdvance: (KitchenOrder) -> Unit,
) {
    val ticketState = KitchenState.from(order.kitchenState)
    val accent = accentFor(ticketState)
    val busy = state.busyOrderId == order.id

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (ticketState == KitchenState.SERVED) Brand.Surface else Brand.SurfaceRaised)
            .background(accent.copy(alpha = if (ticketState == KitchenState.RECEIVED) 0f else 0.10f))
            .border(2.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
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
                        line.name,
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
                enabled = !state.tapsLocked,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(12.dp),
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
