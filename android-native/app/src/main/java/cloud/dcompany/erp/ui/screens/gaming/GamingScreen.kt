package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import java.time.Instant

@Composable
fun GamingScreen(vm: GamingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var starting by remember { mutableStateOf<Station?>(null) }

    when {
        state.loading && state.stations.isEmpty() ->
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.Gold)
            }
        state.stations.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("No gaming stations set up", color = Brand.Foreground)
                Text(
                    state.error ?: "Add stations in the ERP, then refresh.",
                    color = Brand.ForegroundMuted,
                )
                Button(onClick = vm::load) { Text("Refresh") }
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.stations, key = { it.id }) { station ->
                StationTile(
                    station = station,
                    session = state.activeFor(station.id),
                    nowMillis = state.nowMillis,
                    busy = state.busyStationId == station.id,
                    onStart = { starting = station },
                    onStop = { s -> vm.stop(s) },
                )
            }
        }
    }

    starting?.let { station ->
        StartSessionDialog(
            station = station,
            onDismiss = { starting = null },
            onConfirm = { phone, minutes ->
                starting = null
                vm.start(station, phone, minutes)
            },
        )
    }

    state.error?.takeIf { state.stations.isNotEmpty() }?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Gaming") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun StationTile(
    station: Station,
    session: GameSession?,
    nowMillis: Long,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: (GameSession) -> Unit,
) {
    val running = session != null
    val overtime = session?.timerEndsAt?.let { ends ->
        runCatching { Instant.parse(ends).toEpochMilli() < nowMillis }.getOrDefault(false)
    } ?: false

    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    overtime -> Brand.Danger
                    running -> Brand.SurfaceRaised
                    else -> Brand.Surface
                },
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

        if (session != null) {
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
            session.customerPhone?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overtime) Brand.Background else Brand.ForegroundMuted,
                )
            }
        } else {
            Text("Free", color = Brand.Good, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { if (session != null) onStop(session) else onStart() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp, color = Brand.Background)
            else Text(if (running) "Stop & bill" else "Start session")
        }
    }
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
                        "Open-ended: billed by elapsed time. No overtime alarm."
                    } else {
                        "An alarm will sound when the ${minutes}m is up, even if the " +
                            "tablet screen is off."
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
