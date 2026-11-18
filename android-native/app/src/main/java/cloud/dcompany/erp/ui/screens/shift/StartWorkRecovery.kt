package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.LiquidAlertDialog
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.screens.shift.work.PendingStartWork

@Composable
internal fun StartWorkRecovery(vm: ShiftViewModel) {
    val state by vm.startWorkState.collectAsStateWithLifecycle()
    var review by remember { mutableStateOf<PendingStartWork?>(null) }
    LaunchedEffect(vm) { vm.reviewStartWork() }
    if (!state.ready || state.pending.isNotEmpty() || state.error != null || state.notice != null) {
        SectionCard(Modifier.fillMaxWidth(), title = "Start work status") {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it) }
            if (!state.ready) ErpButton("Check saved starts", vm::reviewStartWork, enabled = !state.busy)
            state.pending.forEach { saved ->
                Text("${saved.actorName} · saved shift and clock-in request")
                Text("${saved.phase}. Keep this exact request until the server confirms its outcome.")
                ErpButton("Check / retry original start", { vm.retryStartWork(saved) }, enabled = !state.busy)
                ErpButton("Owner review", { review = saved }, enabled = !state.busy)
            }
        }
    }
    review?.let { saved -> OwnerStartWorkDialog(saved, state.busy, { review = null }) { email, password, reason, cancel ->
        review = null; vm.ownerStartWork(saved, email, password, reason, cancel)
    } }
}

@Composable
internal fun OwnerStartWorkDialog(saved: PendingStartWork, busy: Boolean, dismiss: () -> Unit,
    displayName: String = saved.actorName, initialReason: String = saved.cancelReason.orEmpty(),
    submit: (String, String, String, Boolean) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf(initialReason) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { password = ""; dismiss() } }
        lifecycle.addObserver(observer)
        onDispose { password = ""; lifecycle.removeObserver(observer) }
    }
    LiquidAlertDialog(onDismissRequest = { password = ""; dismiss() }, title = { Text("Owner review · ${displayName}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check the original result, or permanently retire only an uncommitted request. An already committed shift and clock-in are preserved. The signed-in account stays unchanged.")
            OutlinedTextField(email, { email = it.take(254) }, label = { Text("Owner email") }, singleLine = true)
            OutlinedTextField(password, { password = it.take(1024) }, label = { Text("Owner password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("Reason to retire an uncommitted request") })
        } }, confirmButton = { Column {
            ErpButton("Check original result", { val secret = password; password = ""; submit(email, secret, reason, false) },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty())
            ErpButton("Retire if uncommitted", { val secret = password; password = ""; submit(email, secret, reason, true) },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty() && reason.trim().length >= 12)
        } }, dismissButton = { TextButton({ password = ""; dismiss() }) { Text("Cancel") } })
}
