package cloud.dcompany.erp.ui.screens.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
fun AuditLogScreen(vm: AuditLogViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }

    LaunchedEffect(state.locked) {
        // Never retain the owner's password after unlock or expiry.
        password = ""
    }

    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.locked) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AdaptiveStatGrid(count = 3) { index, modifier ->
                    when (index) {
                        0 -> CompactStatCard(
                            label = "Access",
                            value = "Locked",
                            detail = "Protected owner only",
                            icon = Icons.Default.Lock,
                            tone = UiTone.Neutral,
                            modifier = modifier,
                        )
                        1 -> CompactStatCard(
                            label = "Auto-lock",
                            value = "10 min",
                            detail = "Short-lived audit session",
                            icon = Icons.Default.History,
                            tone = UiTone.Information,
                            modifier = modifier,
                        )
                        else -> CompactStatCard(
                            label = "Credential storage",
                            value = "None",
                            detail = "Password is never saved",
                            icon = Icons.Default.Security,
                            tone = UiTone.Success,
                            modifier = modifier,
                        )
                    }
                }
                OperationalBanner(
                    title = "Owner verification required",
                    detail = "Re-enter the current account password. The temporary audit token stays only in memory and expires automatically.",
                    tone = UiTone.Information,
                    icon = Icons.Default.Security,
                )
                AuditUnlockPanel(
                    modifier = Modifier.fillMaxWidth(),
                    password = password,
                    onPasswordChange = { password = it },
                    unlocking = state.unlocking,
                    error = state.unlockError,
                    onUnlock = { vm.unlock(password) },
                )
            }
        } else {
            val selectedArea = AUDIT_AREAS.firstOrNull { it.value == state.area }?.label ?: "All"
            ActionBar(
                leading = {
                    Text(
                        "Audit session controls",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                trailing = {
                    ErpButton(
                        text = "Lock",
                        onClick = vm::lock,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.Lock,
                    )
                    ErpButton(
                        text = if (state.loading) "Refreshing…" else "Refresh",
                        onClick = vm::refresh,
                        enabled = !state.loading && !state.loadingMore,
                        busy = state.loading,
                        leadingIcon = Icons.Default.Refresh,
                    )
                },
            )
            AdaptiveStatGrid(count = 3) { index, modifier ->
                when (index) {
                    0 -> CompactStatCard(
                        label = "Loaded activity",
                        value = state.entries.size.toString(),
                        detail = if (state.endReached) "Complete selected history" else "Older activity available",
                        icon = Icons.AutoMirrored.Filled.FactCheck,
                        tone = UiTone.Information,
                        modifier = modifier,
                    )
                    1 -> CompactStatCard(
                        label = "Current area",
                        value = selectedArea,
                        detail = "Server-authoritative filter",
                        icon = Icons.Default.History,
                        tone = UiTone.Neutral,
                        modifier = modifier,
                    )
                    else -> CompactStatCard(
                        label = "Protection",
                        value = "Unlocked",
                        detail = "Locks automatically after 10 min",
                        icon = Icons.Default.VpnKey,
                        tone = UiTone.Success,
                        modifier = modifier,
                    )
                }
            }
            AuditAreaFilters(selected = state.area, onSelect = vm::selectArea)
            SectionCard(
                modifier = Modifier.weight(1f),
                title = "Activity history",
                subtitle = "Tap a row for identifiers, connection evidence, reason, and server timestamps",
                icon = Icons.AutoMirrored.Filled.FactCheck,
                elevated = true,
                contentPadding = PaddingValues(0.dp),
            ) {
                AuditEntries(
                    modifier = Modifier.weight(1f),
                    state = state,
                    onRetry = vm::refresh,
                    onLoadMore = vm::loadMore,
                    onSelect = vm::select,
                )
            }
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
        SectionCard(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            title = "Unlock protected history",
            subtitle = "Verify the signed-in owner's account before any activity is shown",
            icon = Icons.Default.VpnKey,
            action = { OperationalStatusBadge("Locked", UiTone.Warning, icon = Icons.Default.Lock) },
            elevated = true,
        ) {
            InfoRow("Access scope", "Audit history only")
            InfoRow("Session lifetime", "10 minutes")
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
                keyboardActions = KeyboardActions(
                    onDone = { if (!unlocking && password.isNotBlank()) onUnlock() },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                OperationalBanner(
                    title = "Could not unlock Audit Log",
                    detail = error,
                    tone = UiTone.Danger,
                    icon = Icons.Default.CloudOff,
                )
            }
            ErpButton(
                text = if (unlocking) "Verifying…" else "Unlock Audit Log",
                onClick = onUnlock,
                enabled = !unlocking && password.isNotBlank(),
                busy = unlocking,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Default.VpnKey,
            )
        }
    }
}

@Composable
private fun AuditAreaFilters(selected: String?, onSelect: (String?) -> Unit) {
    PremiumTabBar(
        options = AUDIT_AREAS.map { TabOption(it.value ?: "__all__", it.label) },
        selectedId = selected ?: "__all__",
        onSelect = { id -> onSelect(id.takeUnless { it == "__all__" }) },
    )
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(color = Brand.Gold)
                Text("Loading audit activity…", color = Brand.ForegroundMuted)
            }
        }
        state.error != null && state.entries.isEmpty() -> DesignedEmptyState(
            modifier = modifier,
            title = "Could not load audit activity",
            body = state.error,
            icon = Icons.Default.CloudOff,
            primaryLabel = "Try again",
            onPrimary = onRetry,
        )
        state.entries.isEmpty() -> DesignedEmptyState(
            modifier = modifier,
            title = "No activity in this area",
            body = "Server-authoritative events for the selected area will appear here when an auditable action occurs.",
            icon = Icons.AutoMirrored.Filled.FactCheck,
            primaryLabel = "Check again",
            onPrimary = onRetry,
        )
        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
        ) {
            if (state.error != null) {
                item("load-error") {
                    OperationalBanner(
                        title = "Audit refresh needs attention",
                        detail = state.error,
                        tone = UiTone.Danger,
                        icon = Icons.Default.CloudOff,
                        modifier = Modifier.padding(Spacing.md),
                        action = {
                            ErpButton(
                                text = "Try again",
                                onClick = onRetry,
                                intent = ActionIntent.Secondary,
                            )
                        },
                    )
                }
            }
            itemsIndexed(state.entries, key = { _, entry -> "audit-${entry.id}" }) { index, entry ->
                AuditEntryCard(entry = entry, onClick = { onSelect(entry) })
                if (index != state.entries.lastIndex) PanelDivider()
            }
            item("load-more") {
                Box(Modifier.fillMaxWidth().padding(Spacing.md), Alignment.Center) {
                    when {
                        state.loadingMore -> CircularProgressIndicator(
                            modifier = Modifier.heightIn(max = 24.dp),
                            strokeWidth = 2.dp,
                            color = Brand.Gold,
                        )
                        !state.endReached -> ErpButton(
                            text = "Load older activity",
                            onClick = onLoadMore,
                            intent = ActionIntent.Secondary,
                            leadingIcon = Icons.Default.History,
                        )
                        else -> Text("End of audit activity", color = Brand.ForegroundFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditEntryCard(entry: AuditEntry, onClick: () -> Unit) {
    val client = auditClientLabel(entry)
    DataListRow(
        onClick = onClick,
        leading = {
            OperationalStatusBadge(
                label = auditConnectionLabel(entry),
                tone = if (entry.clientWasOffline == true) UiTone.Warning else UiTone.Information,
                icon = Icons.AutoMirrored.Filled.FactCheck,
            )
        },
        content = {
            Text(
                auditEntryTitle(entry),
                color = Brand.Foreground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${auditActor(entry)} · ${auditEntityLabel(entry.entityType)} · ${shortAuditId(entry.entityId)}",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(client, entry.reason?.takeIf(String::isNotBlank)?.let { "Reason: $it" })
                    .joinToString(" · ").ifBlank { "Server-recorded audit event" },
                color = Brand.ForegroundFaint,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OperationalStatusBadge(
                    label = auditActionLabel(entry.action),
                    tone = auditActionTone(entry.action),
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                )
                Text(
                    formatAuditTimestamp(entry.createdAt),
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
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

private fun auditActionTone(action: String): UiTone = when {
    action == "create" || action.endsWith("_success") -> UiTone.Success
    action == "delete" || action.endsWith("_failed") -> UiTone.Danger
    else -> UiTone.Neutral
}
