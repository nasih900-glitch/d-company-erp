package cloud.dcompany.erp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.handover.HandoverIntent
import cloud.dcompany.erp.core.handover.HandoverStage
import cloud.dcompany.erp.core.handover.TillOperatorCandidate
import cloud.dcompany.erp.ui.remote.RemoteSensitiveContent

internal data class TillTakeoverUi(
    val shiftId: String,
    val revision: Int? = null,
    val people: List<TillOperatorCandidate> = emptyList(),
    val nextCursor: String? = null,
    val intent: HandoverIntent? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun TillTakeoverDialog(state: TillTakeoverUi, onDismiss: () -> Unit, onMore: () -> Unit,
    onConfirm: (TillTakeoverUi, String, String) -> Unit) {
    var selected by remember(state.shiftId, state.revision) { mutableStateOf<TillOperatorCandidate?>(null) }
    var password by remember(state.shiftId, selected?.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!state.busy) { password = ""; onDismiss() } },
        title = { Text("Hand over this till") },
        text = {
            RemoteSensitiveContent {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The cash shift and running games stay open. This does not count cash or clock anyone in or out.")
                    if (state.busy) Text("Verifying this till…")
                    state.error?.let { Text(it) }
                    if (!state.busy && state.revision != null && state.people.isEmpty() && state.nextCursor == null) {
                        Text("No other eligible staff were found for this till.")
                    }
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(state.people, key = { it.id }) { person ->
                            TextButton(onClick = { selected = person }, enabled = !state.busy,
                                modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth()) {
                                Text(if (selected?.id == person.id) "${person.name} (selected)" else person.name)
                            }
                        }
                    }
                    if (state.nextCursor != null) TextButton(onClick = onMore, enabled = !state.busy) { Text("More staff") }
                    selected?.let {
                        TillPasswordField(password, { password = it }, "${it.name}’s password", !state.busy)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val person = selected ?: return@Button
                val entered = password
                password = ""
                onConfirm(state, person.id, entered)
            }, enabled = !state.busy && selected != null && password.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("till-takeover-confirm")) {
                Text("Approve handover")
            }
        },
        dismissButton = {
            TextButton(onClick = { password = ""; onDismiss() }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}

@Composable
internal fun TillTakeoverRecovery(state: TillTakeoverUi, onRecover: (TillTakeoverUi, String?) -> Unit,
    onActivate: (TillTakeoverUi, String, String, Boolean) -> Unit,
    onVerifyOriginal: (TillTakeoverUi, String, String) -> Unit, onCancelUnsent: (TillTakeoverUi) -> Unit) {
    val intent = requireNotNull(state.intent)
    var email by remember(intent.action.key, intent.stage) { mutableStateOf("") }
    var password by remember(intent.action.key, intent.stage) { mutableStateOf("") }
    var verifyOriginal by remember(intent.action.key, intent.stage) { mutableStateOf(false) }
    var ownerReturned by remember(intent.action.key, intent.stage) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    RemoteSensitiveContent {
        Column(Modifier.widthIn(max = 460.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                ownerReturned -> "Verify Owner recovery"
                intent.stage == HandoverStage.ACCEPTED -> "Incoming operator sign-in"
                else -> "Verify the saved till handover"
            })
            Text(when {
                ownerReturned -> "Sign in as the original cashier. The tablet will verify the Owner’s return for this exact handover."
                intent.stage == HandoverStage.ACCEPTED -> "The handover was recorded. The incoming operator must sign in before this till reopens."
                else -> "This tablet is holding the original handover. Check its receipt or retry the same handover with the incoming operator’s password."
            })
            state.error?.let { Text(it) }
            if (intent.stage == HandoverStage.ACCEPTED || verifyOriginal) {
                OutlinedTextField(email, { email = it.take(254) },
                    label = { Text(if (verifyOriginal || ownerReturned) "Original cashier email" else "Incoming operator email") },
                    enabled = !state.busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth())
            }
            TillPasswordField(password, { password = it },
                if (verifyOriginal || ownerReturned) "Original cashier password" else "Incoming operator password", !state.busy)
            Button(onClick = {
                val entered = password
                password = ""
                when {
                    verifyOriginal -> { onVerifyOriginal(state, email, entered); verifyOriginal = false }
                    intent.stage == HandoverStage.ACCEPTED -> onActivate(state, email, entered, ownerReturned)
                    else -> onRecover(state, entered)
                }
            }, enabled = !state.busy && password.isNotEmpty() &&
                ((!verifyOriginal && intent.stage != HandoverStage.ACCEPTED) || email.isNotBlank()),
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(when {
                    state.busy -> "Verifying…"
                    verifyOriginal -> "Verify original cashier"
                    ownerReturned -> "Verify return and reopen till"
                    intent.stage == HandoverStage.ACCEPTED -> "Sign in and reopen till"
                    else -> "Retry this handover"
                })
            }
            if (intent.stage == HandoverStage.SENT_UNKNOWN && !verifyOriginal) {
                TextButton(onClick = { password = ""; onRecover(state, null) }, enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Check handover receipt") }
            }
            if (intent.stage == HandoverStage.ACCEPTED) {
                TextButton(onClick = { password = ""; email = ""; ownerReturned = !ownerReturned },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (ownerReturned) "Back to incoming operator sign-in" else "Owner returned this till")
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString("/#/operations/operator-recovery/${intent.action.shiftId}"))
                }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Copy recovery path for Owner")
                }
            }
            if (intent.stage != HandoverStage.ACCEPTED) {
                TextButton(onClick = { password = ""; email = ""; verifyOriginal = !verifyOriginal },
                    enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (verifyOriginal) "Back to saved handover" else "Sign in as original cashier")
                }
                if (intent.stage == HandoverStage.NOT_SENT && !verifyOriginal) {
                    TextButton(onClick = { password = ""; onCancelUnsent(state) }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel unsent handover") }
                }
            }
        }
    }
}

@Composable
private fun TillPasswordField(password: String, update: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(password, { update(it.take(256)) }, label = { Text(label) }, enabled = enabled, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("till-takeover-password"))
}
