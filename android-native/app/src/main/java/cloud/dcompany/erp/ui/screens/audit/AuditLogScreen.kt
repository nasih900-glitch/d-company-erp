package cloud.dcompany.erp.ui.screens.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius

@Composable
fun AuditLogScreen(vm: AuditLogViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var password by remember { mutableStateOf("") }

    LaunchedEffect(state.locked) {
        // Never retain the owner's password after unlock or expiry.
        password = ""
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Audit Log", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Text(
                    "Protected-owner activity: who acted, what changed, when, and from which device.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!state.locked) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::lock) { Text("Lock") }
                    Button(onClick = vm::refresh, enabled = !state.loading && !state.loadingMore) {
                        Text(if (state.loading) "Refreshing…" else "Refresh")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (state.locked) {
            AuditUnlockPanel(
                modifier = Modifier.weight(1f),
                password = password,
                onPasswordChange = { password = it },
                unlocking = state.unlocking,
                error = state.unlockError,
                onUnlock = { vm.unlock(password) },
            )
        } else {
            AuditAreaFilters(selected = state.area, onSelect = vm::selectArea)
            Spacer(Modifier.height(10.dp))
            AuditEntries(
                modifier = Modifier.weight(1f),
                state = state,
                onRetry = vm::refresh,
                onLoadMore = vm::loadMore,
                onSelect = vm::select,
            )
        }
    }

    state.selected?.let { entry ->
        AuditEntryDialog(entry = entry, onDismiss = vm::dismissDetails)
    }
}

@Composable
private fun AuditUnlockPanel(
    modifier: Modifier,
    password: String,
    onPasswordChange: (String) -> Unit,
    unlocking: Boolean,
    error: String?,
    onUnlock: () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            color = Brand.Surface,
            shape = Radius.shapeLg,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Unlock Audit Log", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                Text(
                    "Re-enter your account password. Audit access locks automatically after 10 minutes and is never saved on this tablet.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Account password") },
                    singleLine = true,
                    enabled = !unlocking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (!unlocking) onUnlock() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = onUnlock,
                    enabled = !unlocking && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (unlocking) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = Brand.Background,
                        )
                    } else {
                        Text("Unlock")
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditAreaFilters(selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(AUDIT_AREAS, key = { it.value ?: "all" }) { area ->
            FilterChip(
                selected = selected == area.value,
                onClick = { onSelect(area.value) },
                label = { Text(area.label) },
            )
        }
    }
}

@Composable
private fun AuditEntries(
    modifier: Modifier,
    state: AuditLogUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (AuditEntry) -> Unit,
) {
    when {
        state.loading && state.entries.isEmpty() -> Box(modifier.fillMaxWidth(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Brand.Gold)
                Spacer(Modifier.height(10.dp))
                Text("Loading audit activity…", color = Brand.ForegroundMuted)
            }
        }
        state.error != null && state.entries.isEmpty() -> AuditErrorState(
            modifier = modifier,
            message = state.error,
            onRetry = onRetry,
        )
        state.entries.isEmpty() -> Box(modifier.fillMaxWidth(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("No audit entries in this area yet.", color = Brand.ForegroundMuted)
                OutlinedButton(onClick = onRetry) { Text("Check again") }
            }
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.error != null) {
                item("load-error") {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(Brand.DangerMuted, Radius.shapeMd)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.error,
                            color = Brand.Foreground,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }
            }
            items(state.entries, key = { "audit-${it.id}" }) { entry ->
                AuditEntryCard(entry = entry, onClick = { onSelect(entry) })
            }
            item("load-more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), Alignment.Center) {
                    when {
                        state.loadingMore -> CircularProgressIndicator(
                            modifier = Modifier.height(24.dp),
                            strokeWidth = 2.dp,
                            color = Brand.Gold,
                        )
                        !state.endReached -> OutlinedButton(onClick = onLoadMore) {
                            Text("Load older activity")
                        }
                        else -> Text("End of audit activity", color = Brand.ForegroundFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditErrorState(modifier: Modifier, message: String, onRetry: () -> Unit) {
    Box(modifier.fillMaxWidth(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = Brand.Danger)
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun AuditEntryCard(entry: AuditEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Brand.Surface, Radius.shapeMd)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    auditEntryTitle(entry),
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    auditActor(entry),
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Surface(color = auditActionColor(entry.action), shape = Radius.shapePill) {
                Text(
                    auditActionLabel(entry.action),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${auditEntityLabel(entry.entityType)} · ${shortAuditId(entry.entityId)}",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatAuditTimestamp(entry.createdAt),
                color = Brand.ForegroundFaint,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val client = auditClientLabel(entry)
        Text(
            listOfNotNull(auditConnectionLabel(entry), client).joinToString(" · "),
            color = Brand.ForegroundFaint,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!entry.reason.isNullOrBlank()) {
            Text(
                "Reason: ${entry.reason}",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AuditEntryDialog(entry: AuditEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(auditEntryTitle(entry)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                AuditDetailRow("Action", auditActionLabel(entry.action))
                AuditDetailRow("When", formatAuditTimestamp(entry.createdAt))
                AuditDetailRow("Who", auditActor(entry))
                entry.actorEmail?.let { AuditDetailRow("Account", it) }
                AuditDetailRow("Record", "${auditEntityLabel(entry.entityType)} · ${entry.entityId}")
                AuditDetailRow("Connection", auditConnectionLabel(entry))
                auditClientLabel(entry)?.let { AuditDetailRow("Client", it) }
                entry.terminalId?.let { AuditDetailRow("Terminal", it) }
                entry.clientReportedAt?.let {
                    AuditDetailRow("Device-reported time", "${formatAuditTimestamp(it)} (not authoritative)")
                }
                entry.syncedAt?.let { AuditDetailRow("Accepted by server", formatAuditTimestamp(it)) }
                entry.reason?.takeIf { it.isNotBlank() }?.let { AuditDetailRow("Reason", it) }
                entry.clientActionId?.let { AuditDetailRow("Client action ID", it) }
                entry.requestId?.let { AuditDetailRow("Request ID", it) }
                entry.ip?.let { AuditDetailRow("Source IP", it) }
                entry.userAgent?.let { AuditDetailRow("User agent", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Brand.SurfaceOverlay,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}

@Composable
private fun AuditDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 120.dp),
        )
        Text(
            value,
            color = Brand.Foreground,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun auditActionColor(action: String): Color = when {
    action == "create" || action.endsWith("_success") -> Brand.GoodMuted
    action == "delete" || action.endsWith("_failed") -> Brand.DangerMuted
    else -> Brand.SurfaceOverlay
}
