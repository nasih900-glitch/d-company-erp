package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

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
        modifier = Modifier.fillMaxSize().background(Brand.Background).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Surface,
                shape = Radius.shapeXl,
                border = BorderStroke(1.dp, Brand.BorderSubtle),
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    DCompanyBrandMark(
                        size = 92.dp,
                        contentDescription = "D Company logo",
                    )
                    Text(
                        "D COMPANY",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Brand.Foreground,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    )
                    Text(
                        "GAMING CENTRE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Brand.GoldMuted,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    )

                    Spacer(Modifier.height(Spacing.xs))
                    HorizontalDivider(color = Brand.BorderSubtle)
                    Spacer(Modifier.height(Spacing.xs))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Welcome back",
                            style = MaterialTheme.typography.titleLarge,
                            color = Brand.Foreground,
                        )
                        Text(
                            "Sign in to continue securely.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Brand.ForegroundMuted,
                        )
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Email, contentDescription = null, tint = Brand.ForegroundFaint)
                        },
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
                        leadingIcon = {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = Brand.ForegroundFaint)
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showPassword = !showPassword },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = Brand.ForegroundMuted,
                                )
                            }
                        },
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
                        onClick = {
                            password = ""
                            showPassword = false
                            recovery.open(email)
                        },
                        enabled = !signingIn,
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) {
                        Text("Forgot password?")
                    }

                    recoveryState.successNotice?.let { notice ->
                        LoginNotice(
                            message = notice,
                            color = Brand.Good,
                            icon = Icons.Filled.CheckCircle,
                            liveRegionMode = LiveRegionMode.Polite,
                        )
                        TextButton(
                            onClick = recovery::dismissSuccess,
                            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        ) { Text("Dismiss") }
                    }

                    error?.let { message ->
                        LoginNotice(
                            message = message,
                            color = Brand.Danger,
                            icon = Icons.Filled.ErrorOutline,
                            liveRegionMode = LiveRegionMode.Assertive,
                        )
                    }

                    Button(
                        onClick = { submit() },
                        enabled = canSubmit,
                        shape = Radius.shapeMd,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics {
                                if (signingIn) stateDescription = "Signing in"
                            },
                    ) {
                        if (signingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(if (signingIn) "Signing in…" else "Sign in")
                    }
                }
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

@Composable
private fun LoginNotice(
    message: String,
    color: Color,
    icon: ImageVector,
    liveRegionMode: LiveRegionMode,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = liveRegionMode },
        color = color.copy(alpha = 0.10f),
        shape = Radius.shapeMd,
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                message,
                color = color,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
