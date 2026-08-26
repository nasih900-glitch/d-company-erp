package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius

@Composable
internal fun PasswordRecoveryDialog(
    state: PasswordRecoveryState,
    onEmailChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onNewPasswordChanged: (String) -> Unit,
    onConfirmPasswordChanged: (String) -> Unit,
    onRequestCode: () -> Unit,
    onConfirm: () -> Unit,
    onUseDifferentEmail: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!state.open) return
    var showPasswords by remember { mutableStateOf(false) }
    val retryLabel = if (state.retrySecondsRemaining > 0) {
        "Request new code in ${state.retrySecondsRemaining}s"
    } else {
        "Request a new code"
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Reset password") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.stage == PasswordRecoveryStage.REQUEST) {
                    Text(
                        "Enter the employee login email. Approval is sent to the business security " +
                            "mailbox, not to the employee email.",
                        color = Brand.ForegroundMuted,
                    )
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = onEmailChanged,
                        label = { Text("Login email") },
                        enabled = !state.busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "For privacy, the response is the same whether or not that login exists.",
                        color = Brand.ForegroundFaint,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val challenge = checkNotNull(state.challenge)
                    Text(
                        "Approval was sent to the business security mailbox: " +
                            challenge.destination.ifBlank { "the protected owner's mailbox" } + ".",
                        color = Brand.Foreground,
                    )
                    Text(
                        approvalExpiryLabel(challenge.expiresIn),
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Ask an authorised owner for the 6-digit approval code. This screen does " +
                            "not confirm whether a login exists for ${state.email}.",
                        color = Brand.ForegroundMuted,
                    )
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = onCodeChanged,
                        label = { Text("6-digit approval code") },
                        enabled = !state.busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.newPassword,
                        onValueChange = onNewPasswordChanged,
                        label = { Text("New password (10–256 characters)") },
                        enabled = !state.busy,
                        singleLine = true,
                        visualTransformation = if (showPasswords) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.confirmPassword,
                        onValueChange = onConfirmPasswordChanged,
                        label = { Text("Confirm new password") },
                        enabled = !state.busy,
                        singleLine = true,
                        visualTransformation = if (showPasswords) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { showPasswords = !showPasswords },
                        enabled = !state.busy,
                    ) {
                        Text(if (showPasswords) "Hide passwords" else "Show passwords")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onUseDifferentEmail, enabled = !state.busy) {
                            Text("Change email")
                        }
                        TextButton(
                            onClick = onRequestCode,
                            enabled = !state.busy && state.retrySecondsRemaining == 0,
                        ) {
                            Text(retryLabel)
                        }
                    }
                }

                if (state.error != null) {
                    Text(
                        state.error,
                        color = Brand.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(
                            color = Brand.Gold,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            if (state.stage == PasswordRecoveryStage.REQUEST) {
                                "Requesting approval…"
                            } else {
                                "Updating password…"
                            },
                            color = Brand.ForegroundMuted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.stage == PasswordRecoveryStage.REQUEST) {
                Button(
                    onClick = onRequestCode,
                    enabled = !state.busy && state.retrySecondsRemaining == 0,
                ) { Text("Request approval code") }
            } else {
                Button(
                    onClick = onConfirm,
                    enabled = !state.busy,
                ) { Text("Update password") }
            }
        },
        dismissButton = {
            // Cancellation stays available during an in-flight request; the
            // ViewModel invalidates its generation so a late response is ignored.
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
}
