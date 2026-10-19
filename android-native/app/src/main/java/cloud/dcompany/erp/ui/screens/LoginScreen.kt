package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.components.AppearanceControl
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.Radius

@Composable
fun LoginScreen(
    signingIn: Boolean,
    error: String?,
    onSignIn: (String, String) -> Unit,
    appearancePreference: AppearancePreference,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit,
) {
    val recovery: PasswordRecoveryViewModel = viewModel()
    val recoveryState by recovery.state.collectAsStateWithLifecycle()

    LoginWorkspace(
        signingIn = signingIn,
        error = error,
        onSignIn = onSignIn,
        appearancePreference = appearancePreference,
        onAppearancePreferenceChanged = onAppearancePreferenceChanged,
        successfulEmail = recoveryState.successfulEmail,
        successNotice = recoveryState.successNotice,
        onRecover = recovery::open,
        onDismissSuccess = recovery::dismissSuccess,
    )
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

/** The real login workspace, separated from recovery IO for layout and input tests. */
@Composable
internal fun LoginWorkspace(
    signingIn: Boolean,
    error: String?,
    onSignIn: (String, String) -> Unit,
    appearancePreference: AppearancePreference,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit,
    successfulEmail: String? = null,
    successNotice: String? = null,
    onRecover: (String) -> Unit,
    onDismissSuccess: () -> Unit = {},
) {
    var email by rememberSaveable { mutableStateOf("") }
    // Never save a password (or its visibility) in restored instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(successfulEmail) { successfulEmail?.let { email = it } }
    val canSubmit = email.isNotBlank() && password.isNotBlank() && !signingIn
    fun submit() {
        if (canSubmit) {
            focus.clearFocus()
            onSignIn(email, password)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DCompanyBrandMark(size = 44.dp, contentDescription = "D Company logo")
            Column(Modifier.weight(1f)) {
                Text("D COMPANY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "BUSINESS OPERATIONS",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppearanceControl(
                preference = appearancePreference,
                onChange = onAppearancePreferenceChanged,
                modifier = Modifier.testTag("login-appearance"),
                compactLabel = true,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 840.dp
            val form: @Composable () -> Unit = {
                LoginForm(
                    email = email,
                    password = password,
                    showPassword = showPassword,
                    signingIn = signingIn,
                    canSubmit = canSubmit,
                    error = error,
                    successNotice = successNotice,
                    onEmailChanged = { email = it },
                    onPasswordChanged = { password = it },
                    onTogglePassword = { showPassword = !showPassword },
                    onNext = { focus.moveFocus(FocusDirection.Next) },
                    onSubmit = ::submit,
                    onRecover = {
                        password = ""
                        showPassword = false
                        onRecover(email)
                    },
                    onDismissSuccess = onDismissSuccess,
                )
            }
            if (wide) {
                Row(
                    Modifier.widthIn(max = 1120.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(72.dp),
                ) {
                    Column(Modifier.weight(1f).testTag("login-brand-panel")) {
                        Text(
                            "THE DAILY WORKSPACE",
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 1.8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Every session.\nEvery sale.\nOne place.",
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp, lineHeight = 56.sp),
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "A clear view of your gaming lounge,\nfrom the first game to the day’s close.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(40.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(16.dp))
                        Text("Gaming  /  POS  /  Shift", style = MaterialTheme.typography.labelLarge)
                    }
                    Box(Modifier.weight(1f).widthIn(max = 440.dp)) { form() }
                }
            } else {
                Box(
                    Modifier.widthIn(max = 488.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                ) { form() }
            }
        }
    }
}

@Composable
private fun LoginForm(
    email: String,
    password: String,
    showPassword: Boolean,
    signingIn: Boolean,
    canSubmit: Boolean,
    error: String?,
    successNotice: String?,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
    onRecover: () -> Unit,
    onDismissSuccess: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.onSurface,
        unfocusedBorderColor = colors.onSurfaceVariant.copy(alpha = 0.72f),
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surfaceContainerHigh,
        cursorColor = colors.onSurface,
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
    )
    Column(Modifier.fillMaxWidth().testTag("login-form")) {
        Text("Welcome back.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text("Sign in to your workspace.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Text("Email", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChanged,
            enabled = !signingIn,
            placeholder = { Text("Your work email", color = colors.onSurfaceVariant) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("login-email")
                .semantics { contentDescription = "Email" },
            shape = Radius.shapeLg,
            colors = inputColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
        )
        Spacer(Modifier.height(20.dp))
        Text("Password", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            enabled = !signingIn,
            placeholder = { Text("Enter your password", color = colors.onSurfaceVariant) },
            trailingIcon = {
                IconButton(onClick = onTogglePassword, enabled = !signingIn, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = colors.onSurfaceVariant,
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("login-password")
                .semantics { contentDescription = "Password" },
            shape = Radius.shapeLg,
            colors = inputColors,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        TextButton(
            onClick = onRecover,
            enabled = !signingIn,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("login-recover"),
        ) { Text("Forgot password?") }
        successNotice?.let {
            LoginNotice(it, success = true)
            TextButton(onClick = onDismissSuccess, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text("Dismiss")
            }
        }
        error?.let {
            LoginNotice(it, success = false)
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            shape = Radius.shapeLg,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("login-submit")
                .semantics { if (signingIn) stateDescription = "Signing in" },
        ) {
            Text(if (signingIn) "Signing in…" else "Sign in", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (signingIn) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary)
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Use the account assigned to you by your shop owner.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoginNotice(message: String, success: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("login-notice").semantics {
            liveRegion = if (success) LiveRegionMode.Polite else LiveRegionMode.Assertive
        },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeLg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (success) Icons.Filled.CheckCircleOutline else Icons.Filled.ErrorOutline, null, Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
