package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.theme.Radius

@Composable
fun LoginScreen(
    signingIn: Boolean,
    error: String?,
    onSignIn: (String, String) -> Unit,
) {
    val recovery: PasswordRecoveryViewModel = viewModel()
    val recoveryState by recovery.state.collectAsStateWithLifecycle()
    // rememberSaveable so a keyboard-driven config change does not wipe a
    // half-typed email.
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must never enter saved-instance state. A rotation or process
    // recreation may keep the email, but always requires the secret again.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(recoveryState.successfulEmail) {
        recoveryState.successfulEmail?.let { email = it }
    }

    val canSubmit = email.isNotBlank() && password.isNotBlank() && !signingIn
    fun submit() = if (canSubmit) onSignIn(email, password) else Unit

    Box(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "D COMPANY",
                style = MaterialTheme.typography.headlineMedium,
                color = Brand.Gold,
            )
            Text(
                "Cafe + Gaming Lounge",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.ForegroundMuted,
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = Radius.shapeMd,
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = Radius.shapeMd,
                colors = fieldColors(),
                visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )

            TextButton(
                onClick = { showPassword = !showPassword },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (showPassword) "Hide password" else "Show password")
            }

            TextButton(
                onClick = {
                    password = ""
                    showPassword = false
                    recovery.open(email)
                },
                enabled = !signingIn,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Forgot password?")
            }

            if (recoveryState.successNotice != null) {
                Text(
                    recoveryState.successNotice.orEmpty(),
                    color = Brand.Good,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(
                    onClick = recovery::dismissSuccess,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Dismiss") }
            }

            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            Button(
                onClick = { submit() },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .semantics {
                        if (signingIn) stateDescription = "Signing in"
                    },
            ) {
                if (signingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Brand.ForegroundMuted,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                }
                Text(if (signingIn) "Signing in…" else "Sign in")
            }
        }
    }

    PasswordRecoveryDialog(
        state = recoveryState,
        onEmailChanged = recovery::emailChanged,
        onCodeChanged = recovery::codeChanged,
        onNewPasswordChanged = recovery::newPasswordChanged,
        onConfirmPasswordChanged = recovery::confirmPasswordChanged,
        onRequestCode = recovery::requestCode,
        onConfirm = recovery::confirm,
        onUseDifferentEmail = recovery::useDifferentEmail,
        onCancel = recovery::cancel,
    )
}
