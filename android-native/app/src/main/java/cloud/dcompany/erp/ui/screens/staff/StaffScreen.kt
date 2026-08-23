package cloud.dcompany.erp.ui.screens.staff

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
fun StaffScreen() {
    val vm: StaffViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header(loading = state.loading, onRefresh = vm::retry, onAdd = vm::startCreateLogin)
        Spacer(Modifier.height(14.dp))
        AttendanceCard(state, onClockIn = vm::clockIn, onClockOut = vm::clockOut, onDismissError = vm::dismissAttendanceError)
        Spacer(Modifier.height(14.dp))

        state.notice?.let {
            NoticeBanner(it, vm::dismissNotice)
            Spacer(Modifier.height(10.dp))
        }

        when {
            state.loading -> CentredBlock {
                CircularProgressIndicator(color = Brand.Gold)
                Text("Loading staff…", color = Brand.ForegroundMuted)
            }

            state.couldNotLoad -> CentredBlock {
                Text("Could not load staff", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Text(
                    "No staff cached on this tablet yet, and there's no connection right now to fetch them.",
                    color = Brand.ForegroundMuted,
                )
                Button(onClick = vm::retry) { Text("Retry") }
            }

            state.rows.isEmpty() -> CentredBlock {
                Text("No staff yet", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Button(onClick = vm::startCreateLogin) { Text("Add staff") }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    StaffRowCard(
                        row = row,
                        onEdit = { vm.startEdit(row) },
                        onDelete = { vm.startDelete(row) },
                        onResetPassword = { vm.startPasswordReset(row) },
                        onRetrySync = { vm.retrySync(row) },
                        onCancelRemoval = { vm.cancelPendingRemoval(row) },
                    )
                }
            }
        }
    }

    state.editor?.let { editor ->
        EditDialog(
            editor = editor,
            roles = state.roles,
            saving = state.savingEdit,
            error = state.editError,
            onChange = vm::editorChanged,
            onCancel = vm::cancelEdit,
            onSave = vm::saveEdit,
        )
    }

    state.deleteConfirmFor?.let { row ->
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
            confirmButton = { Button(onClick = vm::confirmDelete) { Text("Remove") } },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Cancel") } },
            containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
            titleContentColor = Brand.Foreground,
            textContentColor = Brand.Foreground,
        )
    }

    state.createDraft?.let { draft ->
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

    state.passwordReset?.let { draft ->
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
private fun Header(loading: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Staff", style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground)
            Text(
                "Logins, roles, and attendance",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            Button(onClick = onAdd) { Text("Add staff") }
        }
    }
}

// -------------------------------------------------------------- attendance

@Composable
private fun AttendanceCard(
    state: StaffUiState,
    onClockIn: () -> Unit,
    onClockOut: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Attendance", style = MaterialTheme.typography.titleMedium, color = Brand.Foreground)
            Button(
                onClick = if (state.clockedIn) onClockOut else onClockIn,
                enabled = !state.clockingInOrOut,
            ) { Text(if (state.clockedIn) "Clock out" else "Clock in") }
        }
        state.attendanceError?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = Brand.Danger, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "On shift now (${state.onShift.size})",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        if (state.onShift.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.onShift.forEach { row ->
                    Text(
                        "${row.userName ?: row.userEmail ?: "Unknown"} · since ${formatClockInTime(row.clockInAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Foreground,
                        modifier = Modifier
                            .clip(Radius.shapeXl)
                            .background(Brand.SurfaceRaised)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------- row

@Composable
private fun StaffRowCard(
    row: StaffRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onResetPassword: () -> Unit,
    onRetrySync: () -> Unit,
    onCancelRemoval: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(Brand.Surface)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                    if (row.isSelf) {
                        Spacer(Modifier.width(6.dp))
                        Text("(you)", style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
                    }
                }
                Text(
                    "${row.email} · ${row.roles.joinToString(", ").ifBlank { "no role" }} · ${row.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.ForegroundMuted,
                )
                row.phone?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.ForegroundMuted)
                }
            }
            if (!row.pendingDelete) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onResetPassword) { Text("Password") }
                    TextButton(onClick = onEdit) { Text("Edit") }
                    if (!row.isSelf) TextButton(onClick = onDelete) { Text("Remove") }
                }
            }
        }
        if (row.pendingDelete) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Pending removal — will sync when back online",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancelRemoval) { Text("Cancel") }
            }
        } else if (row.pendingLocalId != null) {
            Text("Not synced yet", color = Brand.GoldMuted, style = MaterialTheme.typography.labelSmall)
        } else if (row.rejectedError != null) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
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
                    }
                }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (editor.isSelf) {
                    Text(
                        "You can't change your own role or status.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                } else {
                    RoleDropdown(roles, editor.roleCode) { onChange(editor.copy(roleCode = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Suspended", color = Brand.Foreground, modifier = Modifier.weight(1f))
                        Switch(
                            checked = editor.status == "suspended",
                            onCheckedChange = { onChange(editor.copy(status = if (it) "suspended" else "active")) },
                        )
                    }
                }
                if (error != null) Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = editor.valid && !saving) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
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
            modifier = Modifier.fillMaxWidth().menuAnchor(),
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.confirmPassword, onValueChange = { onChange(draft.copy(confirmPassword = it)) },
                        label = { Text("Confirm password") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draft.password.isNotEmpty() && !draft.passwordsMatch) {
                        Text("Passwords don't match.", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
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
                Button(onClick = onRequestCode, enabled = draft.requestValid && !busy) {
                    Text(if (busy) "Sending…" else "Send code")
                }
            } else {
                Button(onClick = onConfirm, enabled = code.length == 6 && !busy) {
                    Text(if (busy) "Confirming…" else "Confirm")
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.confirmNewPassword, onValueChange = { onChange(draft.copy(confirmNewPassword = it)) },
                        label = { Text("Confirm new password") }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draft.newPassword.isNotEmpty() && !draft.passwordsMatch) {
                        Text("Passwords don't match.", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (error != null) Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (challenge == null) {
                Button(onClick = onRequestCode, enabled = !busy) { Text(if (busy) "Sending…" else "Send code") }
            } else {
                Button(onClick = onConfirm, enabled = code.length == 6 && draft.newPasswordValid && !busy) {
                    Text(if (busy) "Confirming…" else "Confirm")
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}

// ------------------------------------------------------------------- shared

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .border(1.dp, Brand.GoldMuted, Radius.shapeSm)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/**
 * `clockInAt` is server-stamped `datetime.now(timezone.utc)`, which almost
 * always carries microseconds (e.g. "2026-08-22T09:17:39.974253Z") — a fixed
 * trailing-character slice does not land on a stable "HH:mm" substring
 * across that varying precision. Parse properly instead, same `Instant`
 * precedent GamingScreen/SyncEngine already use for backend timestamps.
 */
private val ON_SHIFT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatClockInTime(iso: String): String =
    runCatching { ON_SHIFT_TIME_FORMAT.format(Instant.parse(iso)) }.getOrDefault("—")

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
