package cloud.dcompany.erp.ui.screens.staff.time

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.LiquidAlertDialog
import cloud.dcompany.erp.ui.components.SectionCard

@Composable
internal fun SharedAttendanceCard(
    state: SharedAttendanceState,
    online: Boolean,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onRetry: (String) -> Unit,
    onAcknowledge: () -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(false) }
    var reviewRejection by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) { picker = false; retry = false; onDismiss() }
    }
    SectionCard(Modifier.fillMaxWidth().testTag("shared-attendance"), title = "Shared tablet attendance") {
        Text("Clock in or out with your own password. The signed-in cashier stays unchanged.")
        Text("Attendance is separate from the till. Several staff can be on duty together.", style = MaterialTheme.typography.bodySmall)
        state.notice?.let { Text(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val pending = state.pending
        if (pending != null) {
            Text("${pending.intent.targetName} · retained clock-${if (pending.intent.kind == TimeWriteKind.SHARED_IN) "in" else "out"}")
            Text(if (pending.rejectedStatus == null) "The outcome is unknown. Re-enter that staff member's password to retry the same request."
                else "The server rejected this request (HTTP ${pending.rejectedStatus}). Review it before making a corrected request.")
            ErpButton("Retry retained attendance", { retry = true }, enabled = online && state.ready && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("shared-attendance-retry"))
            if (pending.rejectedStatus != null) ErpButton("Review rejected attendance", { reviewRejection = true },
                enabled = online && !state.busy, intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth())
        } else ErpButton("Clock another staff member", { picker = true; onRefresh() }, enabled = online && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("shared-attendance-open"))
        if (!state.ready || pending != null) ErpButton("Refresh attendance", onRefresh, enabled = online && !state.busy,
            busy = state.busy, intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth())
        if (!online) Text("Online verification is required. Nothing is queued for automatic replay.")
    }
    if (picker) LiquidAlertDialog(onDismissRequest = { picker = false }, title = { Text("Who is clocking in or out?") },
        confirmButton = {}, dismissButton = { TextButton({ picker = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.ready) item { Text(if (state.busy) "Verifying staff…" else "Refresh to verify the staff directory.") }
                items(state.people, key = { it.id }) { person ->
                    ErpButton("${person.name} · ${if (person.onDuty) "On duty / clock out" else "Clock in"}",
                        { onSelect(person.id); picker = false }, enabled = state.ready && !state.busy && state.pending == null,
                        intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth().testTag("shared-person-${person.id}"))
                }
                // An eligible page may be empty while the backend cursor still advances.
                if (state.nextCursor != null) item { ErpButton("Load more staff", onMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }
                if (state.ready && state.people.isEmpty() && state.nextCursor == null) item { Text("No eligible staff were found in this branch.") }
            }
        })
    val person = state.selected
    val retained = state.pending.takeIf { retry }
    if (online && (person != null || retained != null)) {
        val name = retained?.intent?.targetName ?: requireNotNull(person).name
        val out = retained?.intent?.kind == TimeWriteKind.SHARED_OUT || (retained == null && person?.onDuty == true)
        val fields = retained?.let { StaffTimeJson.decodeFromString<SharedTimeFields>(it.intent.payload) }
        SharedAttendancePasswordDialog(name, out, fields?.breakMinutes, state.busy,
            onDismiss = { retry = false; onDismiss() },
            onSubmit = { password, breaks ->
                if (retained != null) onRetry(password) else onConfirm(password, breaks)
                retry = false
            })
    }
    if (reviewRejection && state.pending?.rejectedStatus != null) LiquidAlertDialog(
        onDismissRequest = { reviewRejection = false }, title = { Text("Keep rejected attendance evidence?") },
        text = { Text("This retains the rejected request privately, then lets you review corrected details. An unknown outcome cannot be discarded here.") },
        confirmButton = { ErpButton("Keep rejection evidence", { reviewRejection = false; onAcknowledge() }, enabled = !state.busy) },
        dismissButton = { TextButton({ reviewRejection = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}

@Composable
private fun SharedAttendancePasswordDialog(
    name: String, out: Boolean, retainedBreak: Int?, busy: Boolean,
    onDismiss: () -> Unit, onSubmit: (String, String) -> Unit,
) {
    // Never rememberSaveable: credentials must not enter saved state or the pending intent.
    var password by remember(name, out) { mutableStateOf("") }
    var breaks by remember(name, out) { mutableStateOf(retainedBreak?.toString().orEmpty()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { password = ""; onDismiss() } }
        lifecycle.addObserver(observer)
        onDispose { password = ""; lifecycle.removeObserver(observer) }
    }
    LiquidAlertDialog(onDismissRequest = { password = ""; onDismiss() },
        title = { Text("$name · clock-${if (out) "out" else "in"}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Use $name's own sign-in password. This does not switch the cashier account.")
            OutlinedTextField(password, { password = it.take(256) }, label = { Text("Staff password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("shared-attendance-password"))
            if (out) OutlinedTextField(breaks, { breaks = it.take(4) }, label = { Text("Actual break minutes, including 0") },
                singleLine = true, readOnly = retainedBreak != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("shared-attendance-break"))
        } },
        confirmButton = { ErpButton(if (out) "Confirm clock-out" else "Confirm clock-in", {
            val entered = password; password = ""; onSubmit(entered, breaks)
        }, enabled = password.isNotEmpty() && !busy && (!out || runCatching { actualBreak(breaks) }.isSuccess),
            modifier = Modifier.testTag("shared-attendance-confirm")) },
        dismissButton = { TextButton({ password = ""; onDismiss() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}
