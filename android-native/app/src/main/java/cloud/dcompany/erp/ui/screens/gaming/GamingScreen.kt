package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.VoidReasonInput
import cloud.dcompany.erp.ui.components.resolvedVoidReason
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import java.time.Instant

@Composable
fun GamingScreen(
    access: GamingAccess = GamingAccess(),
    focusSessionId: String? = null,
    focusStationId: String? = null,
    onDismissFocus: () -> Unit = {},
    vm: GamingViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    SideEffect { vm.updateAccess(access) }
    var starting by remember { mutableStateOf<Station?>(null) }
    var sending by remember { mutableStateOf<GameSession?>(null) }
    var cancelling by remember { mutableStateOf<GameSession?>(null) }
    val gridState = rememberLazyGridState()
    val focusIndex = focusStationId?.let { id -> state.stations.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
    LaunchedEffect(focusStationId, focusIndex) {
        if (focusIndex != null) gridState.animateScrollToItem(focusIndex)
    }

    when {
        state.stations.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                // Still shown even on a never-synced device: a bare spinner
                // with no way out is what this replaced — offline on the
                // very first open must not be a dead end.
                if (!state.everSynced) CircularProgressIndicator(color = Brand.Gold)
                Text(
                    if (state.everSynced) "No gaming stations set up" else "Waiting for the first sync",
                    color = Brand.Foreground,
                )
                Text(
                    state.error ?: "Add stations in the ERP, then refresh.",
                    color = Brand.ForegroundMuted,
                )
                Button(onClick = vm::load) { Text("Refresh") }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
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
            if (state.needsCancellation.isNotEmpty()) {
                NeedsCancellationStrip(
                    sessions = state.needsCancellation,
                    stations = state.stations,
                    busyStationId = state.busyStationId,
                    canWrite = access.canManageSessions,
                    onSelect = { cancelling = it },
                )
            }
            if (state.readyForPos.isNotEmpty()) {
                ReadyForPosStrip(
                    sessions = state.readyForPos,
                    stations = state.stations,
                    busyStationId = state.busyStationId,
                    canWrite = access.canManageSessions,
                    onSelect = { sending = it },
                )
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 240.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.stations, key = { it.id }) { station ->
                    StationTile(
                        station = station,
                        session = state.activeFor(station.id),
                        nowMillis = state.nowMillis,
                        busy = state.busyStationId == station.id,
                        focused = station.id == focusStationId,
                        canWrite = access.canManageSessions,
                        onStart = { starting = station },
                        onStop = { s -> vm.stop(s) },
                        onRetryStart = vm::retryStart,
                        onDiscardStart = vm::discardFailedStart,
                        onSend = { sending = it },
                        onCancelUnbilled = { cancelling = it },
                    )
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
                        "${(session.amountMinor ?: 0L).asRupees()}. " +
                        "POS will receive this as an unpaid held order for the cashier.",
                )
            },
            confirmButton = {
                Button(onClick = { sending = null; vm.sendToPos(session) }) { Text("Send to POS") }
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

    state.error?.takeIf { state.stations.isNotEmpty() }?.let { msg ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Gaming") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun GamingNotificationFocusBanner(
    sessionStillActive: Boolean,
    stationName: String?,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (sessionStillActive) Brand.GoldMuted else Brand.SurfaceRaised)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (sessionStillActive) {
                "Session alert opened for ${stationName ?: "this station"}. " +
                    "Review the highlighted station, then stop or extend it explicitly."
            } else {
                "This alerted session is no longer active. It may have been stopped on another tablet."
            },
            modifier = Modifier.weight(1f),
            color = Brand.Foreground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = onDismiss) { Text("Clear highlight") }
    }
}

@Composable
private fun NeedsCancellationStrip(
    sessions: List<GameSession>,
    stations: List<Station>,
    busyStationId: String?,
    canWrite: Boolean,
    onSelect: (GameSession) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Brand.Danger.copy(alpha = 0.14f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Stopped with no billable amount (${sessions.size})",
            color = Brand.Foreground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "A ₹0 session cannot be sent to POS. Cancel it with a reason before reusing the station.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                val name = stations.firstOrNull { it.id == session.stationId }?.name ?: "Session"
                OutlinedButton(
                    onClick = { onSelect(session) },
                    enabled = canWrite && busyStationId != session.stationId,
                ) {
                    Text("$name · Cancel")
                }
            }
        }
    }
}

@Composable
private fun ReadyForPosStrip(
    sessions: List<GameSession>,
    stations: List<Station>,
    busyStationId: String?,
    canWrite: Boolean,
    onSelect: (GameSession) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Brand.SurfaceRaised).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Ready to send to POS (${sessions.size})",
            color = Brand.Foreground,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "These sessions are stopped and calculated, but are not bills yet.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { session ->
                val name = stations.firstOrNull { it.id == session.stationId }?.name ?: "Session"
                Button(
                    onClick = { onSelect(session) },
                    enabled = canWrite && busyStationId != session.stationId,
                ) {
                    Text("$name · ${(session.amountMinor ?: 0L).asRupees()} · Send")
                }
            }
        }
    }
}

@Composable
private fun StationTile(
    station: Station,
    session: GameSession?,
    nowMillis: Long,
    busy: Boolean,
    focused: Boolean,
    canWrite: Boolean,
    onStart: () -> Unit,
    onStop: (GameSession) -> Unit,
    onRetryStart: (GameSession) -> Unit,
    onDiscardStart: (GameSession) -> Unit,
    onSend: (GameSession) -> Unit,
    onCancelUnbilled: (GameSession) -> Unit,
) {
    val starting = session?.status == "starting"
    val startFailed = session?.status == "start_failed"
    val stopping = session?.status == "stopping"
    val paused = session?.status == "paused"
    val endedUnbilled = session?.status == "ended" && session.orderId == null
    val sendPending = session?.localState == "send_pending"
    val unbillable = endedUnbilled && (session?.amountMinor ?: 0L) <= 0L
    val occupied = session != null
    val overtime = !endedUnbilled && !paused && session?.timerEndsAt?.let { ends ->
        runCatching { Instant.parse(ends).toEpochMilli() < nowMillis }.getOrDefault(false)
    } ?: false

    Column(
        Modifier.clip(Radius.shapeLg)
            .background(
                when {
                    overtime -> Brand.Danger
                    occupied -> Brand.SurfaceRaised
                    else -> Brand.Surface
                },
            )
            .then(
                if (focused) Modifier.border(3.dp, Brand.Gold, Radius.shapeLg)
                else Modifier,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            station.name,
            style = MaterialTheme.typography.titleLarge,
            color = if (overtime) Brand.Background else Brand.Foreground,
        )
        Text(
            "${station.type} · ${station.ratePerHourMinor.asRupees()}/hr",
            style = MaterialTheme.typography.labelSmall,
            color = if (overtime) Brand.Background else Brand.ForegroundMuted,
        )

        if (endedUnbilled) {
            Text(
                "Stopped · ${session.billableMinutes ?: 0} min",
                color = Brand.Foreground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                (session.amountMinor ?: 0L).asRupees(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (unbillable) Brand.Danger else Brand.Gold,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (unbillable) {
                    "No billable amount — cancel with a reason before starting another session"
                } else {
                    "Send this stopped session to POS before starting another session"
                },
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            if (sendPending) {
                Text(
                    "POS handoff queued — waiting for server confirmation",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            session.lastError?.takeIf { session.localState == "send_rejected" }?.let { message ->
                Text(
                    "POS handoff was refused: $message",
                    color = Brand.Danger,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else if (session != null && !starting && !startFailed) {
            val elapsed = runCatching {
                (nowMillis - Instant.parse(session.startAt).toEpochMilli()).coerceAtLeast(0)
            }.getOrDefault(0L)
            Text(
                formatElapsed(elapsed),
                style = MaterialTheme.typography.headlineMedium,
                color = if (overtime) Brand.Background else Brand.Gold,
                fontWeight = FontWeight.Bold,
            )
            if (overtime) {
                Text(
                    "OVER TIME",
                    color = Brand.Background,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (stopping) {
                Text(
                    "Stop queued — session remains active until the server confirms",
                    color = if (overtime) Brand.Background else Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (paused) {
                Text(
                    "Paused on server — stop or resolve this session before reusing the station",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            session.lastError?.takeIf { session.localState == "stop_rejected" }?.let { message ->
                Text(
                    "Stop was refused: $message",
                    color = if (overtime) Brand.Background else Brand.Danger,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            session.customerPhone?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overtime) Brand.Background else Brand.ForegroundMuted,
                )
            }
        } else if (starting) {
            Text(
                "Waiting for server — do not begin play yet",
                color = Brand.Gold,
                fontWeight = FontWeight.SemiBold,
            )
            session?.customerPhone?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
            }
        } else if (startFailed) {
            Text(
                "Start was not accepted — do not begin play",
                color = Brand.Danger,
                fontWeight = FontWeight.SemiBold,
            )
            session?.lastError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
            }
        } else {
            Text("Free", color = Brand.Good, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(4.dp))
        if (startFailed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDiscardStart(session) },
                    enabled = canWrite && !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Discard") }
                Button(
                    onClick = { onRetryStart(session) },
                    enabled = canWrite && !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Retry start") }
            }
        } else if (endedUnbilled && unbillable) {
            Button(
                onClick = { onCancelUnbilled(session) },
                enabled = canWrite && !busy && !sendPending,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = Brand.Background,
                    )
                } else {
                    Text("Cancel session")
                }
            }
        } else if (endedUnbilled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onCancelUnbilled(session) },
                    enabled = canWrite && !busy && !sendPending,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Cancel / void") }
                Button(
                    onClick = { onSend(session) },
                    enabled = canWrite && !busy && !sendPending,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = Brand.Background,
                        )
                    } else {
                        Text(
                            when {
                                sendPending -> "Sending"
                                session.localState == "send_rejected" -> "Retry Send"
                                else -> "Send to POS"
                            },
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = { if (session != null) onStop(session) else onStart() },
                enabled = canWrite && !busy && !starting && !stopping,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp, color = Brand.Background)
                else Text(
                    when {
                        starting -> "Waiting for server"
                        stopping -> "Stopping on server"
                        session?.localState == "stop_rejected" -> "Retry stop"
                        occupied -> "Stop session"
                        else -> "Start session"
                    },
                )
            }
        }
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
        title = { Text("Cancel / void · $stationName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (hasBillableAmount) {
                        "This stopped session is worth ${amountMinor.asRupees()}. Cancelling will void it without " +
                            "sending it to POS, and the reason will be recorded in the audit trail."
                    } else {
                        "This stopped session has no billable amount and cannot be sent to POS. " +
                            "Cancellation is recorded in the audit trail."
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
            Button(
                onClick = { onConfirm(normalized) },
                enabled = normalized.isNotEmpty(),
            ) { Text("Cancel session") }
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
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = onDismiss,
        title = { Text("Start · ${station.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Booked time", color = Brand.ForegroundMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 90, 120).forEach { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = { Text("${m}m") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Brand.Gold,
                                selectedLabelColor = Brand.Background,
                            ),
                        )
                    }
                    FilterChip(
                        selected = minutes == null,
                        onClick = { minutes = null },
                        label = { Text("Open") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Brand.Gold,
                            selectedLabelColor = Brand.Background,
                        ),
                    )
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() } },
                    label = { Text("Member phone (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (minutes == null) {
                        "Open-ended: billed by server-confirmed elapsed time. No overtime alarm."
                    } else {
                        "The timer begins only after the server confirms the session. " +
                            "The tablet then schedules an alert for ${minutes}m."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.GoldMuted,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(phone.takeIf { it.isNotBlank() }, minutes) }) {
                Text("Start")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
