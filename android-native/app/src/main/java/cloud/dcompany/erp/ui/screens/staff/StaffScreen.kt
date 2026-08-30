package cloud.dcompany.erp.ui.screens.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import cloud.dcompany.erp.core.auth.StaffAccess
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.AdaptiveStatGrid
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.SearchInput
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Staff — login/role/attendance management. Price-free (there is no pay-rate
 * field anywhere in this backend — see StaffViewModel's class doc comment):
 * this screen only ever touches identity and access, never money.
 */
@Composable
fun StaffScreen(profile: MeResponse, access: StaffAccess) {
    val factory = remember(profile.userId, access) {
        viewModelFactory { initializer { StaffViewModel(profile, access) } }
    }
    val vm: StaffViewModel = viewModel(
        key = "staff:${profile.userId}:$access",
        factory = factory,
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(Brand.Background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(
            loading = state.syncing,
            attendanceOnly = !state.canReadDirectory,
            canManage = state.canManageDirectory,
            onRefresh = vm::retry,
            onAdd = vm::startCreateLogin,
        )
        if (state.canReadDirectory) {
            StaffSummary(state)
        }
        if (state.canReadDirectory || state.canUseAttendance) {
            AttendanceCard(
                state,
                canClock = state.canUseAttendance,
                onClockIn = vm::clockIn,
                onClockOut = vm::clockOut,
                onDismissError = vm::dismissAttendanceError,
                onResolveUncertain = vm::retry,
            )
        }

        state.notice?.let {
            NoticeBanner(it, vm::dismissNotice)
        }

        when {
            !state.canReadDirectory -> SectionCard(Modifier.weight(1f), elevated = true) {
                DesignedEmptyState(
                    title = "Attendance access",
                    body = "Your account can clock in and out, but it cannot view or manage staff logins.",
                    icon = Icons.Default.AccessTime,
                )
            }

            state.loading -> SectionCard(Modifier.weight(1f), elevated = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(color = Brand.Gold)
                        Text("Loading staff…", color = Brand.ForegroundMuted)
                    }
                }
            }

            state.couldNotLoad -> SectionCard(Modifier.weight(1f), elevated = true) {
                DesignedEmptyState(
                    title = "Could not load staff",
                    body = "No staff is cached on this tablet yet. Check the connection and try again.",
                    icon = Icons.Default.CloudOff,
                    primaryLabel = "Retry",
                    onPrimary = vm::retry,
                )
            }

            state.rows.isEmpty() -> SectionCard(Modifier.weight(1f), elevated = true) {
                DesignedEmptyState(
                    title = "No staff yet",
                    body = "Create the first staff login and assign the role needed for daily operations.",
                    icon = Icons.Default.Groups,
                    primaryLabel = if (state.canManageDirectory) "Add staff" else null,
                    onPrimary = if (state.canManageDirectory) vm::startCreateLogin else null,
                )
            }

            else -> StaffDirectory(state, vm, Modifier.weight(1f))
        }
    }

    state.editor?.takeIf { state.canManageDirectory }?.let { editor ->
        EditDialog(
            editor = editor,
            roles = state.roles,
            rolesError = state.rolesError,
            saving = state.savingEdit,
            error = state.editError,
            onChange = vm::editorChanged,
            onCancel = vm::cancelEdit,
            onSave = vm::saveEdit,
        )
    }

    state.deleteConfirmFor?.takeIf { state.canManageDirectory }?.let { row ->
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("Remove ${row.name}?") },
            text = {
                Text(
                    "This cannot be undone. If offline, this queues and applies once back online " +
                        "— you can cancel it from this screen any time before it syncs.",
                    color = Brand.ForegroundMuted,
                )
            },
            confirmButton = {
                ErpButton("Remove", vm::confirmDelete, intent = ActionIntent.Destructive, leadingIcon = Icons.Default.Delete)
            },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Cancel") } },
            containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
            titleContentColor = Brand.Foreground,
            textContentColor = Brand.Foreground,
        )
    }

    state.createDraft?.takeIf { state.canManageDirectory }?.let { draft ->
        CreateLoginDialog(
            draft = draft,
            challenge = state.createChallenge,
            code = state.createCode,
            busy = state.creating,
            error = state.createError,
            onChange = vm::createDraftChanged,
            onCodeChange = vm::createCodeChanged,
            onRequestCode = vm::requestCreateLogin,
            onConfirm = vm::confirmCreateLogin,
            onCancel = vm::cancelCreateLogin,
        )
    }

    state.passwordReset?.takeIf { state.canManageDirectory }?.let { draft ->
        PasswordResetDialog(
            draft = draft,
            challenge = state.passwordResetChallenge,
            code = state.passwordResetCode,
            busy = state.resettingPassword,
            error = state.passwordResetError,
            onChange = vm::passwordResetDraftChanged,
            onCodeChange = vm::passwordResetCodeChanged,
            onRequestCode = vm::requestPasswordReset,
            onConfirm = vm::confirmPasswordReset,
            onCancel = vm::cancelPasswordReset,
        )
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun Header(
    loading: Boolean,
    attendanceOnly: Boolean,
    canManage: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
) {
    ActionBar(
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (attendanceOnly) "Attendance controls" else "Directory controls",
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (attendanceOnly) {
                        "Clock in, clock out, and see who is working now"
                    } else {
                        "Refresh access data or create a new staff login"
                    },
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailing = {
            ErpButton(
                text = if (loading) "Refreshing" else "Refresh",
                onClick = onRefresh,
                enabled = !loading,
                busy = loading,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.Refresh,
            )
            if (canManage) {
                ErpButton("Add staff", onAdd, leadingIcon = Icons.Default.Add)
            }
        },
    )
}

@Composable
private fun StaffSummary(state: StaffUiState) {
    val active = state.rows.count { it.status.equals("active", ignoreCase = true) }
    val suspended = state.rows.count { it.status.equals("suspended", ignoreCase = true) }
    AdaptiveStatGrid(count = 4) { index, modifier ->
        when (index) {
            0 -> StaffTotalMetric(state.rows.size, modifier)
            1 -> StaffActiveMetric(active, modifier)
            2 -> StaffOnShiftMetric(state.onShift.size, modifier)
            else -> StaffSuspendedMetric(suspended, modifier)
        }
    }
}

@Composable
private fun StaffTotalMetric(count: Int, modifier: Modifier) = CompactStatCard(
    label = "Directory",
    value = count.toString(),
    detail = "Staff logins",
    icon = Icons.Default.Groups,
    tone = UiTone.Information,
    modifier = modifier,
)

@Composable
private fun StaffActiveMetric(count: Int, modifier: Modifier) = CompactStatCard(
    label = "Active accounts",
    value = count.toString(),
    detail = "Can sign in",
    icon = Icons.Default.CheckCircle,
    tone = UiTone.Success,
    modifier = modifier,
)

@Composable
private fun StaffOnShiftMetric(count: Int, modifier: Modifier) = CompactStatCard(
    label = "On shift",
    value = count.toString(),
    detail = "Clocked in now",
    icon = Icons.Default.AccessTime,
    tone = if (count > 0) UiTone.Information else UiTone.Neutral,
    modifier = modifier,
)

@Composable
private fun StaffSuspendedMetric(count: Int, modifier: Modifier) = CompactStatCard(
    label = "Suspended",
    value = count.toString(),
    detail = "Access disabled",
    icon = Icons.Default.PersonOff,
    tone = if (count > 0) UiTone.Warning else UiTone.Neutral,
    modifier = modifier,
)

// -------------------------------------------------------------- attendance

@Composable
private fun AttendanceCard(
    state: StaffUiState,
    canClock: Boolean,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onDismissError: () -> Unit,
    onResolveUncertain: () -> Unit,
) {
    SectionCard(
        title = "Attendance",
        subtitle = if (state.clockedIn) "You are clocked in" else "Current attendance at this branch",
        icon = Icons.Default.AccessTime,
        action = if (!canClock) null else {
            {
                ErpButton(
                    text = if (state.clockedIn) "Clock out" else "Clock in",
                    onClick = if (state.clockedIn) onClockOut else onClockIn,
                    enabled = !state.clockingInOrOut && !state.attendanceUncertain,
                    busy = state.clockingInOrOut,
                    intent = if (state.clockedIn) ActionIntent.Secondary else ActionIntent.Success,
                    leadingIcon = Icons.Default.AccessTime,
                )
            }
        },
        contentPadding = PaddingValues(12.dp),
    ) {
        state.attendanceError?.let {
            OperationalBanner(
                title = "Attendance needs attention",
                detail = it,
                tone = UiTone.Danger,
                icon = Icons.Default.SyncProblem,
                action = {
                    TextButton(onClick = if (state.attendanceUncertain) onResolveUncertain else onDismissError) {
                        Text(if (state.attendanceUncertain) "Refresh attendance" else "Dismiss")
                    }
                },
            )
        }
        if (state.onShift.isEmpty()) {
            Text("No staff are clocked in at this branch.", color = Brand.ForegroundMuted)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.onShift, key = { it.id }) { row ->
                    Column(
                        Modifier.width(220.dp).clip(Radius.shapeMd).background(Brand.SurfaceRaised)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            row.userName ?: row.userEmail ?: "Unknown staff",
                            color = Brand.Foreground,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "Clocked in at ${formatClockInTime(row.clockInAt)}",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- row

@Composable
private fun StaffDirectory(state: StaffUiState, vm: StaffViewModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(state.rows, query) {
        val needle = query.trim()
        if (needle.isBlank()) state.rows else state.rows.filter { row ->
            row.name.contains(needle, ignoreCase = true) ||
                row.email.contains(needle, ignoreCase = true) ||
                row.phone.orEmpty().contains(needle, ignoreCase = true) ||
                row.roles.any { it.contains(needle, ignoreCase = true) }
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchInput(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search staff, email, phone, or role",
            modifier = Modifier.fillMaxWidth(),
        )
        SectionCard(
            modifier = Modifier.weight(1f),
            title = "Staff directory",
            subtitle = "${filtered.size} result${if (filtered.size == 1) "" else "s"} · roles and account access",
            icon = Icons.Default.Badge,
            contentPadding = PaddingValues(0.dp),
        ) {
            if (filtered.isEmpty()) {
                DesignedEmptyState(
                    title = "No matching staff",
                    body = "Try another name, email, phone number, or role.",
                    icon = Icons.Default.Groups,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, row -> row.id }) { index, row ->
                        StaffRowCard(
                            row = row,
                            onEdit = { vm.startEdit(row) },
                            onDelete = { vm.startDelete(row) },
                            onResetPassword = { vm.startPasswordReset(row) },
                            onRetrySync = { vm.retrySync(row) },
                            onCancelRemoval = { vm.cancelPendingRemoval(row) },
                            onDiscardRejected = { vm.discardRejectedChange(row) },
                            canManage = state.canManageDirectory,
                        )
                        if (index < filtered.lastIndex) PanelDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StaffRowCard(
    row: StaffRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResetPassword: () -> Unit,
    onRetrySync: () -> Unit,
    onCancelRemoval: () -> Unit,
    onDiscardRejected: () -> Unit,
    canManage: Boolean,
) {
    val statusTone = when {
        row.rejectedError != null -> UiTone.Danger
        row.pendingDelete || row.pendingLocalId != null -> UiTone.Warning
        row.status.equals("active", ignoreCase = true) -> UiTone.Success
        row.status.equals("suspended", ignoreCase = true) -> UiTone.Warning
        else -> UiTone.Neutral
    }
    val statusLabel = when {
        row.rejectedError != null -> "Sync issue"
        row.pendingDelete -> "Removal pending"
        row.pendingLocalId != null -> "Pending sync"
        else -> row.status.ifBlank { "Unknown" }
    }

    Column(Modifier.fillMaxWidth().background(Brand.Surface)) {
        DataListRow(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Brand.Foreground,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (row.isSelf) {
                        Spacer(Modifier.width(6.dp))
                        Text("(you)", style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
                    }
                }
                Text(
                    row.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.ForegroundMuted,
                )
                Text(
                    row.phone?.takeIf(String::isNotBlank)?.let { "$it · ${row.roles.joinToString(", ").ifBlank { "No role" }}" }
                        ?: row.roles.joinToString(", ").ifBlank { "No role assigned" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            },
            trailing = {
                OperationalStatusBadge(
                    statusLabel,
                    statusTone,
                    icon = when (statusTone) {
                        UiTone.Success -> Icons.Default.CheckCircle
                        UiTone.Danger -> Icons.Default.SyncProblem
                        else -> Icons.Default.PersonOff
                    },
                )
                if (canManage && !row.pendingDelete) {
                    StaffActionsMenu(row.isSelf, row.canDelete, onEdit, onResetPassword, onDelete)
                }
            },
        )
        if (canManage && row.pendingDelete) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "Pending removal — will sync when back online",
                    color = Brand.Warning,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelRemoval) { Text("Cancel") }
            }
        } else if (canManage && row.rejectedError != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised)
                    .border(1.dp, Brand.Danger, Radius.shapeSm)
                    .padding(8.dp),
            ) {
                Text("Could not sync: ${row.rejectedError}", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = onRetrySync) { Text("Retry") }
                    // Retrying a rejected delete just resends the same
                    // DELETE (see StaffViewModel.retrySync — it preserves
                    // pendingDelete), which is pointless for a rejection
                    // like "protected owner cannot be deleted" that will
                    // never succeed. Offer a way out of the loop too.
                    if (row.hasQueuedDelete) {
                        TextButton(onClick = onCancelRemoval) { Text("Cancel removal") }
                    } else {
                        TextButton(onClick = onDiscardRejected) { Text("Discard local change") }
                    }
                }
            }
        } else if (canManage && row.pendingLocalId != null) {
            Text(
                "Not synced yet",
                color = Brand.Information,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StaffActionsMenu(
    isSelf: Boolean,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onResetPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More staff actions", tint = Brand.ForegroundMuted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Edit staff") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { expanded = false; onEdit() },
            )
            DropdownMenuItem(
                text = { Text("Reset password") },
                leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null) },
                onClick = { expanded = false; onResetPassword() },
            )
            if (!isSelf && canDelete) {
                DropdownMenuItem(
                    text = { Text("Remove staff", color = Brand.Danger) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Brand.Danger) },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDialog(
    editor: StaffEditor,
    roles: List<StaffRole>,
    rolesError: String?,
    saving: Boolean,
    error: String?,
    onChange: (StaffEditor) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Edit staff") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.phone,
                    onValueChange = { onChange(editor.copy(phone = it)) },
                    label = { Text("Phone (optional)") },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editor.accessChangesLocked) {
                    Text(
                        if (editor.isSelf) {
                            "You can't change your own role or status."
                        } else {
                            "Only the protected owner can change another owner account's role or status."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                } else {
                    RoleDropdown(roles, editor.roleCode) { onChange(editor.copy(roleCode = it)) }
                    rolesError?.let {
                        Text(it, color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Suspended", color = Brand.Foreground, modifier = Modifier.weight(1f))
                        Switch(
                            checked = editor.status == "suspended",
                            onCheckedChange = { onChange(editor.copy(status = if (it) "suspended" else "active")) },
                        )
                    }
                }
                val validationError = editor.validationError
                if (error != null) {
                    Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
                } else if (validationError != null) {
                    Text(validationError, color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            ErpButton(
                text = if (saving) "Saving…" else "Save",
                onClick = onSave,
                enabled = editor.valid && !saving,
                busy = saving,
            )
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleDropdown(roles: List<StaffRole>, selectedCode: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = roles.firstOrNull { it.code == selectedCode }?.name ?: selectedCode.ifBlank { "Pick a role" }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Role") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roles.forEach { r ->
                DropdownMenuItem(text = { Text(r.name) }, onClick = { onSelect(r.code); expanded = false })
            }
        }
    }
}

/**
 * Two-phase: request a code (sent to the company's security mailbox, not the
 * new user's own inbox — see StaffApi.kt's doc comment), then redeem it.
 * Matches StaffScreen.tsx's AddUserModal exactly, including the plain
 * client-side checks (password length, confirm match) — anything deeper is
 * the server's job.
 */
@Composable
private fun CreateLoginDialog(
    draft: CreateLoginDraft,
    challenge: OtpChallenge?,
    code: String,
    busy: Boolean,
    error: String?,
    onChange: (CreateLoginDraft) -> Unit,
    onCodeChange: (String) -> Unit,
    onRequestCode: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Add staff") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (challenge == null) {
                    OutlinedTextField(
                        value = draft.name, onValueChange = { onChange(draft.copy(name = it)) },
                        label = { Text("Name") }, singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.email, onValueChange = { onChange(draft.copy(email = it)) },
                        label = { Text("Email") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.phone, onValueChange = { onChange(draft.copy(phone = it)) },
                        label = { Text("Phone (optional)") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.password, onValueChange = { onChange(draft.copy(password = it)) },
                        label = { Text("Password (min 10 characters)") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.confirmPassword, onValueChange = { onChange(draft.copy(confirmPassword = it)) },
                        label = { Text("Confirm password") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val untouched = draft.name.isEmpty() && draft.email.isEmpty() && draft.phone.isEmpty() &&
                        draft.password.isEmpty() && draft.confirmPassword.isEmpty()
                    if (untouched) {
                        Text(
                            "Name, email, and a 10–256 character password are required.",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        draft.validationError?.let { validation ->
                            Text(validation, color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(
                        "A 6-digit code was sent to ${challenge.destination}. Enter it to finish creating this login.",
                        color = Brand.ForegroundMuted,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(6)) },
                        label = { Text("Code") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Wrong-code attempts are capped server-side and the
                    // challenge is permanently spent once that cap is hit —
                    // not just a timeout. A mistyped hand-copied code is
                    // routine enough that "restart the whole form" is a bad
                    // default recovery; requesting again reuses this same
                    // draft and silently supersedes the old challenge.
                    TextButton(onClick = onRequestCode, enabled = !busy) { Text("Send a new code") }
                }
                if (error != null) Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (challenge == null) {
                ErpButton(
                    text = if (busy) "Sending…" else "Send code",
                    onClick = onRequestCode,
                    enabled = draft.requestValid && !busy,
                    busy = busy,
                )
            } else {
                ErpButton(
                    text = if (busy) "Confirming…" else "Confirm",
                    onClick = onConfirm,
                    enabled = code.length == 6 && !busy,
                    busy = busy,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

/** Admin-initiated reset against an existing staff member's email — same two-phase shape as [CreateLoginDialog]. */
@Composable
private fun PasswordResetDialog(
    draft: PasswordResetDraft,
    challenge: OtpChallenge?,
    code: String,
    busy: Boolean,
    error: String?,
    onChange: (PasswordResetDraft) -> Unit,
    onCodeChange: (String) -> Unit,
    onRequestCode: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Reset password for ${draft.forName}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (challenge == null) {
                    Text(
                        "A 6-digit code will be sent to the company's security mailbox to confirm this reset.",
                        color = Brand.ForegroundMuted,
                    )
                } else {
                    Text("Code sent to ${challenge.destination}.", color = Brand.ForegroundMuted)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(6)) },
                        label = { Text("Code") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = onRequestCode, enabled = !busy) { Text("Send a new code") }
                    OutlinedTextField(
                        value = draft.newPassword, onValueChange = { onChange(draft.copy(newPassword = it)) },
                        label = { Text("New password (min 10 characters)") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.confirmNewPassword, onValueChange = { onChange(draft.copy(confirmNewPassword = it)) },
                        label = { Text("Confirm new password") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draft.newPassword.isEmpty() && draft.confirmNewPassword.isEmpty()) {
                        Text(
                            "Enter and confirm a 10–256 character password.",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        draft.validationError?.let { validation ->
                            Text(validation, color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (error != null) Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (challenge == null) {
                ErpButton(
                    text = if (busy) "Sending…" else "Send code",
                    onClick = onRequestCode,
                    enabled = !busy,
                    busy = busy,
                )
            } else {
                ErpButton(
                    text = if (busy) "Confirming…" else "Confirm",
                    onClick = onConfirm,
                    enabled = code.length == 6 && draft.newPasswordValid && !busy,
                    busy = busy,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

// ------------------------------------------------------------------- shared

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    OperationalBanner(
        title = "Staff activity",
        detail = message,
        tone = UiTone.Information,
        icon = Icons.Default.CheckCircle,
        action = { TextButton(onClick = onDismiss) { Text("Dismiss") } },
    )
}

/**
 * `clockInAt` is server-stamped `datetime.now(timezone.utc)`, which almost
 * always carries microseconds (e.g. "2026-08-22T09:17:39.974253Z") — a fixed
 * trailing-character slice does not land on a stable "HH:mm" substring
 * across that varying precision. Parse properly instead, same `Instant`
 * precedent GamingScreen/SyncEngine already use for backend timestamps.
 */
private fun formatClockInTime(iso: String): String =
    runCatching {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(iso))
    }.getOrDefault("—")

@Composable
private fun CentredBlock(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp).fillMaxWidth(0.7f),
        ) { content() }
    }
}
