package cloud.dcompany.erp.ui.screens.accesscontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Spacing

private val ROLE_COLUMN_WIDTH = 220.dp
private val MODULE_COLUMN_WIDTH = 200.dp

private data class PendingAccessChange(
    val cell: AccessCell,
    val roleLabel: String,
    val moduleLabel: String,
    val reset: Boolean,
)

@Composable
fun AccessControlScreen(vm: AccessControlViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var pendingChange by remember { mutableStateOf<PendingAccessChange?>(null) }
    var detailsCell by remember { mutableStateOf<AccessCell?>(null) }
    val customOverrideCount = remember(state.cells) { state.cells.count { it.override != null } }

    Column(
        Modifier.fillMaxSize().background(Brand.Background)
            .padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ActionBar(
            leading = {
                Text(
                    "Permission matrix controls",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
            },
            trailing = {
                ErpButton(
                    text = if (state.loading) "Loading…" else "Refresh",
                    onClick = vm::load,
                    intent = ActionIntent.Secondary,
                    enabled = !state.loading && state.busyKeys.isEmpty(),
                    busy = state.loading,
                    leadingIcon = Icons.Default.Refresh,
                )
            },
        )

        AdaptiveStatGrid(count = 3) { index, modifier ->
            when (index) {
                0 -> CompactStatCard(
                    label = "Configurable roles",
                    value = if (state.loading && state.roles.isEmpty()) "—" else state.roles.size.toString(),
                    detail = "Protected owner excluded",
                    icon = Icons.Default.Groups,
                    tone = UiTone.Information,
                    modifier = modifier,
                )
                1 -> CompactStatCard(
                    label = "Operational modules",
                    value = if (state.loading && state.modules.isEmpty()) "—" else state.modules.size.toString(),
                    detail = "Server-authoritative scope",
                    icon = Icons.Default.ViewModule,
                    tone = UiTone.Neutral,
                    modifier = modifier,
                )
                else -> CompactStatCard(
                    label = "Custom overrides",
                    value = if (state.loading && state.cells.isEmpty()) "—" else customOverrideCount.toString(),
                    detail = if (state.busyKeys.isEmpty()) "All changes settled" else "${state.busyKeys.size} updating now",
                    icon = Icons.Default.Tune,
                    tone = if (state.busyKeys.isEmpty()) UiTone.Neutral else UiTone.Warning,
                    modifier = modifier,
                )
            }
        }

        OperationalBanner(
            title = "Module access has protected ceilings",
            detail = "Changes apply immediately, but an enabled module may be Partial: refunds, role administration, sensitive finance writes, and other high-trust actions cannot be granted beyond the role's secure defaults. The protected owner is intentionally absent.",
            tone = UiTone.Information,
            icon = Icons.Default.Security,
        )

        state.notice?.let { message ->
            OperationalBanner(
                title = "Access Control saved",
                detail = message,
                tone = UiTone.Success,
                icon = Icons.Default.Check,
                action = { TextButton(onClick = vm::dismissNotice) { Text("Dismiss") } },
            )
        }

        SectionCard(
            modifier = Modifier.weight(1f),
            title = "Role permission matrix",
            subtitle = "Tap Full, Partial, or Blocked to review a change. Use the info button for exact permissions; reset appears only for custom overrides.",
            icon = Icons.Default.Security,
            elevated = true,
            contentPadding = PaddingValues(0.dp),
        ) {
            when {
                state.loading && state.roles.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        CircularProgressIndicator(color = Brand.Gold)
                        Text("Loading live permission matrix…", color = Brand.ForegroundMuted)
                        Text(
                            "No permission can be changed until the current server rules arrive.",
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                state.roles.isEmpty() -> DesignedEmptyState(
                    modifier = Modifier.fillMaxSize(),
                    title = "Could not load access control",
                    body = state.error ?: "The live permission matrix is unavailable. Check the connection and try again.",
                    icon = Icons.Default.Security,
                    primaryLabel = "Try again",
                    onPrimary = vm::load,
                )
                else -> {
                    if (state.loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Brand.Information,
                            trackColor = Brand.SurfaceRaised,
                        )
                    }
                    AccessGrid(
                        modifier = Modifier.weight(1f),
                        state = state,
                        enabled = !state.loading && !state.authorityUnknown,
                        onToggle = { cell, roleLabel, moduleLabel ->
                            pendingChange = PendingAccessChange(cell, roleLabel, moduleLabel, reset = false)
                        },
                        onReset = { cell, roleLabel, moduleLabel ->
                            pendingChange = PendingAccessChange(cell, roleLabel, moduleLabel, reset = true)
                        },
                        onInspect = { detailsCell = it },
                    )
                }
            }
        }
    }

    pendingChange?.let { change ->
        val targetState = if (change.reset) {
            change.cell.defaultAccessLevel
        } else if (change.cell.accessLevel != "blocked") {
            "blocked"
        } else {
            "the maximum level permitted for this role"
        }
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = { pendingChange = null },
            title = { Text(if (change.reset) "Reset permission?" else "Change permission?") },
            text = {
                Text(
                    "${change.roleLabel} access to ${change.moduleLabel} will become $targetState immediately for every user in that role. Protected permission ceilings will still apply.",
                    color = Brand.ForegroundMuted,
                )
            },
            confirmButton = {
                ErpButton(
                    text = if (change.reset) "Reset to default" else "Apply change",
                    onClick = {
                        pendingChange = null
                        if (change.reset) vm.resetToDefault(change.cell) else vm.toggle(change.cell)
                    },
                    intent = if (targetState == "blocked") ActionIntent.Destructive else ActionIntent.Primary,
                )
            },
            dismissButton = { TextButton(onClick = { pendingChange = null }) { Text("Keep current") } },
        )
    }

    state.actionError?.let { msg ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissActionError,
            confirmButton = { TextButton(onClick = vm::dismissActionError) { Text("OK") } },
            title = { Text("Access Control") },
            text = { Text(msg) },
        )
    }


    detailsCell?.let { cell ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = { detailsCell = null },
            confirmButton = { TextButton(onClick = { detailsCell = null }) { Text("Done") } },
            title = { Text("${MODULE_LABELS[cell.module] ?: cell.module} access: ${cell.accessLevel.replaceFirstChar { it.uppercase() }}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (!cell.hasExactPermissionEvidence) {
                        Text(
                            "This server did not return exact permission evidence. Treat the enabled module as partial until the backend is updated and the matrix is refreshed.",
                            color = Brand.Warning,
                        )
                    } else {
                        Text(
                            "Effective permissions",
                            color = Brand.Foreground,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            cell.effectivePermissions.ifEmpty { listOf("None") }.joinToString("\n"),
                            color = Brand.ForegroundMuted,
                        )
                        if (cell.unavailablePermissions.isNotEmpty()) {
                            Text(
                                "Currently unavailable",
                                color = Brand.Warning,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(cell.unavailablePermissions.joinToString("\n"), color = Brand.ForegroundMuted)
                        }
                        if (cell.ceilingLimitedPermissions.isNotEmpty()) {
                            Text(
                                "Cannot be granted through this matrix for this role",
                                color = Brand.Danger,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                cell.ceilingLimitedPermissions.joinToString("\n"),
                                color = Brand.ForegroundMuted,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun AccessGrid(
    modifier: Modifier = Modifier,
    state: AccessControlUiState,
    enabled: Boolean,
    onToggle: (AccessCell, String, String) -> Unit,
    onReset: (AccessCell, String, String) -> Unit,
    onInspect: (AccessCell) -> Unit,
) {
    val cellsByKey = remember(state.cells) {
        state.cells.associateBy { it.roleCode to it.module }
    }
    val overrideCounts = remember(state.cells) {
        state.cells.filter { it.override != null }.groupingBy { it.roleCode }.eachCount()
    }
    val roleEntries = remember(state.roles) { state.roles.entries.toList() }

    Column(modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.background(Brand.SurfaceRaised).heightIn(min = 54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(ROLE_COLUMN_WIDTH).padding(horizontal = Spacing.lg)) {
                Text(
                    "ROLE",
                    color = Brand.ForegroundFaint,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            state.modules.forEach { module ->
                Box(Modifier.width(MODULE_COLUMN_WIDTH).padding(Spacing.sm), Alignment.Center) {
                    Text(
                        (MODULE_LABELS[module] ?: module).uppercase(),
                        color = Brand.ForegroundFaint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        PanelDivider()

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(roleEntries, key = { _, entry -> entry.key }) { index, (roleCode, description) ->
                Row(
                    modifier = Modifier.background(if (index % 2 == 0) Brand.Surface else Brand.SurfaceRaised.copy(alpha = 0.42f))
                        .heightIn(min = 68.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.width(ROLE_COLUMN_WIDTH).padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            roleDisplayName(roleCode),
                            color = Brand.Foreground,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            description,
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                        )
                        val overrideCount = overrideCounts[roleCode] ?: 0
                        Text(
                            if (overrideCount == 0) "Role defaults" else "$overrideCount custom override${if (overrideCount == 1) "" else "s"}",
                            color = if (overrideCount == 0) Brand.ForegroundFaint else Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    state.modules.forEach { module ->
                        Box(Modifier.width(MODULE_COLUMN_WIDTH), Alignment.Center) {
                            val cell = cellsByKey[roleCode to module]
                            if (cell != null) {
                                AccessCellToggle(
                                    cell = cell,
                                    roleLabel = roleDisplayName(roleCode),
                                    moduleLabel = MODULE_LABELS[module] ?: module,
                                    busy = "$roleCode:$module" in state.busyKeys,
                                    enabled = enabled,
                                    onToggle = { onToggle(cell, roleDisplayName(roleCode), MODULE_LABELS[module] ?: module) },
                                    onReset = { onReset(cell, roleDisplayName(roleCode), MODULE_LABELS[module] ?: module) },
                                    onInspect = { onInspect(cell) },
                                )
                            } else {
                                OperationalStatusBadge("N/A", UiTone.Neutral)
                            }
                        }
                    }
                }
                if (index != state.roles.size - 1) PanelDivider()
            }
        }
    }
}

@Composable
private fun AccessCellToggle(
    cell: AccessCell,
    roleLabel: String,
    moduleLabel: String,
    busy: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onInspect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 92.dp)
                .semantics {
                    contentDescription = "$roleLabel, $moduleLabel access"
                    stateDescription = when {
                        busy -> "Updating"
                        !enabled -> "Refreshing server permissions"
                        cell.accessLevel == "full" -> "Full access"
                        cell.accessLevel == "partial" -> "Partial access"
                        else -> "Blocked"
                    }
                }
                .toggleable(
                    value = cell.accessLevel != "blocked",
                    enabled = enabled && !busy,
                    role = Role.Switch,
                    onValueChange = { onToggle() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.Gold)
            } else {
                OperationalStatusBadge(
                    label = when (cell.accessLevel) {
                        "full" -> "Full"
                        "partial" -> "Partial"
                        else -> "Blocked"
                    },
                    tone = when (cell.accessLevel) {
                        "full" -> UiTone.Success
                        "partial" -> UiTone.Warning
                        else -> UiTone.Danger
                    },
                    icon = if (cell.accessLevel == "blocked") Icons.Filled.Close else Icons.Filled.Check,
                )
            }
        }
        IconButton(onClick = onInspect, enabled = !busy, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Show effective and unavailable permissions",
                tint = Brand.ForegroundMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // Only shown when this cell has an explicit override — an
        // unmodified cell is just following the role's static default and
        // has nothing to reset.
        if (cell.override != null) {
            IconButton(onClick = onReset, enabled = enabled && !busy, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Reset to default (${cell.defaultAccessLevel})",
                    tint = Brand.ForegroundMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

internal fun roleDisplayName(roleCode: String): String = when (roleCode) {
    "co_owner" -> "Co-owner"
    "gaming_supervisor" -> "Gaming supervisor"
    else -> roleCode.replace('_', ' ').replaceFirstChar(Char::uppercase)
}
