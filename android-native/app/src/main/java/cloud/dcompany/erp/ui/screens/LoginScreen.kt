package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.R
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.Radius
import kotlin.math.roundToInt

internal const val LoginReferenceAssetSha256 =
    "679707186d15f553b719ec0313a906f3ce2ce49303ea3ddf2b9a19a59930d49a"
internal const val LoginSceneHdR1Sha256 =
    "13aa59b3860110f143b0b0a0bba6e84a6847667bacc2e01ae0b3b5990cef2844"

private val LoginPurple = Color(0xFFBF32FF)
private val LoginButtonInk = Color(0xFF16051F)
internal val LoginPanelBackground = Color(0xFF0E061F)
private val LoginGold = Color(0xFFC8A15B)
internal val LoginText = Color(0xFFF7F2FA)
private val LoginMutedText = Color(0xFFD5C8DA)
private val LoginControlShape = RoundedCornerShape(5.dp)

internal data class LoginArtworkSourceCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

internal val LoginReferenceArtworkBounds = LoginArtworkSourceCrop(
    left = 221,
    top = 42,
    width = 266,
    height = 261,
)

internal data class LoginWideGeometry(
    val compositionWidth: Float,
    val compositionHeight: Float,
    val compositionLeft: Float,
    val compositionTop: Float,
    val formZoneWidth: Float,
    val artworkZoneWidth: Float,
    val controlWidth: Float,
    val brandTop: Float,
    val brandHeight: Float,
    val formTop: Float,
    val condensedForIme: Boolean,
    val showIdentity: Boolean,
)

internal fun loginWideGeometry(
    containerWidth: Float,
    containerHeight: Float,
    fontScale: Float = 1f,
): LoginWideGeometry {
    require(containerWidth > 0f && containerHeight > 0f && fontScale > 0f)
    val compositionWidth = containerWidth
    val canonicalHeight = compositionWidth * (9f / 16f)
    val compositionHeight = minOf(containerHeight, canonicalHeight)
    val formZoneWidth = compositionWidth * 0.43f
    val canonicalFormTop = canonicalHeight * 0.43f
    val minimumFormTop = minOf(64f, compositionHeight)
    val formTop = minOf(canonicalFormTop, maxOf(minimumFormTop, compositionHeight - 356f))
    val condensedForIme = formTop < 148f
    val canonicalBrandTop = canonicalHeight * 0.12f
    val brandTop = if (condensedForIme) {
        8f
    } else {
        minOf(canonicalBrandTop, maxOf(8f, formTop - 140f))
    }
    val brandHeight = if (condensedForIme) {
        48f
    } else {
        minOf(canonicalHeight * 0.24f, formTop - brandTop - 8f)
    }
    val identityMinimumHeight = 72f + ((40f + 20f) * fontScale)
    val showIdentity = !condensedForIme && brandHeight >= identityMinimumHeight
    return LoginWideGeometry(
        compositionWidth = compositionWidth,
        compositionHeight = compositionHeight,
        compositionLeft = (containerWidth - compositionWidth) / 2f,
        compositionTop = (containerHeight - compositionHeight) / 2f,
        formZoneWidth = formZoneWidth,
        artworkZoneWidth = compositionWidth - formZoneWidth,
        controlWidth = formZoneWidth * 0.79f,
        brandTop = brandTop,
        brandHeight = brandHeight,
        formTop = formTop,
        condensedForIme = condensedForIme,
        showIdentity = showIdentity,
    )
}

internal fun useWideLoginLayout(containerWidth: Float): Boolean = containerWidth >= 840f

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

    BoxWithConstraints(
        Modifier.fillMaxSize().background(LoginPanelBackground).imePadding(),
    ) {
        val wide = useWideLoginLayout(maxWidth.value)
        if (wide) {
            val geometry = loginWideGeometry(
                containerWidth = maxWidth.value,
                containerHeight = maxHeight.value,
                fontScale = LocalDensity.current.fontScale,
            )
            Box(Modifier.fillMaxSize().testTag("login-layout-wide"), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(geometry.compositionWidth.dp)
                        .height(geometry.compositionHeight.dp)
                        .testTag("login-composition"),
                ) {
                    LoginBackdrop(
                        modifier = Modifier.fillMaxSize().testTag("login-backdrop"),
                    )
                    Box(
                        Modifier.fillMaxHeight().width(geometry.formZoneWidth.dp)
                            .testTag("login-form-zone"),
                    ) {
                        LoginBrandHeader(
                            appearancePreference = appearancePreference,
                            onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                            modifier = Modifier.width(geometry.controlWidth.dp)
                                .height(geometry.brandHeight.dp)
                                .align(Alignment.TopCenter)
                                .offset(y = geometry.brandTop.dp),
                            wide = true,
                            showIdentity = geometry.showIdentity,
                        )
                        Column(
                            Modifier.fillMaxWidth()
                                .height((geometry.compositionHeight - geometry.formTop).dp)
                                .align(Alignment.BottomCenter)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 44.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.width(geometry.controlWidth.dp)) {
                                LoginFormContainer(wide = true, content = form)
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.width(geometry.controlWidth.dp)
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp)
                                .testTag("login-version"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.70f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Box(
                        Modifier.fillMaxHeight().width(geometry.artworkZoneWidth.dp)
                            .align(Alignment.CenterEnd)
                            .testTag("login-photo-zone"),
                    ) {
                        Box(
                            Modifier.fillMaxSize().testTag("login-photo")
                                .semantics { contentDescription = "D Company illustrated login artwork" },
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .background(LoginPanelBackground)
                    .testTag("login-layout-compact"),
            ) {
                LoginBrandHeader(
                    appearancePreference = appearancePreference,
                    onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    wide = false,
                )
                Box(
                    Modifier.widthIn(max = 488.dp).fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                        .testTag("login-form-zone"),
                ) { LoginFormContainer(wide = false, content = form) }
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        .testTag("login-version"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LoginBrandHeader(
    appearancePreference: AppearancePreference,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean,
    showIdentity: Boolean = true,
) {
    Box(modifier.testTag("login-brand-panel")) {
        if (showIdentity) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (wide) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.login_logo_d),
                    contentDescription = "D Company logo",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(if (wide) 72.dp else 52.dp).testTag("login-brand-mark"),
                )
                Text(
                    "D COMPANY",
                    modifier = Modifier.fillMaxWidth().testTag("login-brand-name"),
                    style = if (wide) {
                        MaterialTheme.typography.titleLarge.copy(fontSize = 36.sp, lineHeight = 40.sp)
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (wide) 6.sp else 3.sp,
                    color = LoginText,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "GAMING LOUNGE",
                    modifier = Modifier.fillMaxWidth().testTag("login-brand-subtitle"),
                    style = if (wide) {
                        MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp)
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    letterSpacing = if (wide) 4.5.sp else 2.sp,
                    color = LoginGold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        LoginAppearanceControl(
            preference = appearancePreference,
            onChange = onAppearancePreferenceChanged,
            modifier = Modifier.align(Alignment.TopEnd).testTag("login-appearance"),
        )
    }
}

@Composable
private fun LoginBackdrop(modifier: Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.login_scene_hd_r1)
    Canvas(modifier) {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        if (width <= 0 || height <= 0) return@Canvas
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF120622), LoginPanelBackground, Color(0xFF08030E)),
            ),
        )
        val scale = width.toFloat() / bitmap.width
        val drawnWidth = width
        val drawnHeight = (bitmap.height * scale).roundToInt()
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(
                x = 0,
                y = ((height - drawnHeight) / 2f).roundToInt(),
            ),
            dstSize = IntSize(drawnWidth, drawnHeight),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                0f to LoginPanelBackground,
                0.42f to LoginPanelBackground.copy(alpha = 0.72f),
                1f to Color.Transparent,
                startX = 0f,
                endX = width * 0.56f,
            ),
            size = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
        )
        val edgeFadeHeight = height * 0.055f
        drawRect(
            brush = Brush.verticalGradient(
                0f to LoginPanelBackground,
                1f to Color.Transparent,
                startY = 0f,
                endY = edgeFadeHeight,
            ),
            size = androidx.compose.ui.geometry.Size(width.toFloat(), edgeFadeHeight),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to LoginPanelBackground,
                startY = height - edgeFadeHeight,
                endY = height.toFloat(),
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, height - edgeFadeHeight),
            size = androidx.compose.ui.geometry.Size(width.toFloat(), edgeFadeHeight),
        )
    }
}

@Composable
private fun LoginFormContainer(wide: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLoginWide provides wide) { content() }
}

private val LocalLoginWide = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
private fun LoginAppearanceControl(
    preference: AppearancePreference,
    onChange: (AppearancePreference) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val resolvedNight = preference == AppearancePreference.Night ||
        (preference == AppearancePreference.System && isSystemInDarkTheme())
    val state = when (preference) {
        AppearancePreference.Day -> "Day"
        AppearancePreference.Night -> "Night"
        AppearancePreference.System -> "System · ${if (resolvedNight) "Night" else "Day"}"
    }
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = "Day, night and system appearance"
                stateDescription = state
            },
        ) {
            Icon(Icons.Filled.Brightness6, contentDescription = null, tint = Color.White.copy(alpha = 0.78f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppearancePreference.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(when (option) {
                        AppearancePreference.System -> "Use system setting"
                        AppearancePreference.Day -> "Day"
                        AppearancePreference.Night -> "Night"
                    }) },
                    onClick = { expanded = false; onChange(option) },
                    modifier = Modifier.testTag("appearance-option-${option.name}"),
                )
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
    val wide = LocalLoginWide.current
    val controlHeight = if (wide) 64.dp else 56.dp
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LoginPurple,
        unfocusedBorderColor = Color(0xFF765485).copy(alpha = 0.78f),
        focusedContainerColor = Color(0xFF2A0B3B).copy(alpha = 0.88f),
        unfocusedContainerColor = Color(0xFF1B0A2B).copy(alpha = 0.84f),
        disabledContainerColor = Color(0xFF1B0A2B).copy(alpha = 0.66f),
        cursorColor = LoginPurple,
        focusedTextColor = LoginText,
        unfocusedTextColor = LoginText,
        disabledTextColor = LoginText.copy(alpha = 0.70f),
        focusedPlaceholderColor = LoginMutedText,
        unfocusedPlaceholderColor = LoginMutedText,
    )
    Column(Modifier.fillMaxWidth().testTag("login-form")) {
        Text(
            "Email",
            Modifier.fillMaxWidth().testTag("login-email-label"),
            style = if (wide) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = LoginText,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChanged,
            enabled = !signingIn,
            placeholder = { Text("Enter email", color = LoginMutedText) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = controlHeight).testTag("login-email")
                .semantics { contentDescription = "Email" },
            shape = LoginControlShape,
            colors = inputColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
        )
        Spacer(Modifier.height(if (wide) 10.dp else 16.dp))
        Text(
            "Password",
            style = if (wide) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = LoginText,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            enabled = !signingIn,
            placeholder = { Text("Enter your password", color = LoginMutedText) },
            trailingIcon = {
                IconButton(
                    onClick = onTogglePassword,
                    enabled = !signingIn,
                    modifier = Modifier.size(48.dp).testTag("login-password-visibility"),
                ) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = if (signingIn) LoginMutedText.copy(alpha = 0.72f) else LoginMutedText,
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = controlHeight).testTag("login-password")
                .semantics { contentDescription = "Password" },
            shape = LoginControlShape,
            colors = inputColors,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        TextButton(
            onClick = onRecover,
            enabled = !signingIn,
            colors = ButtonDefaults.textButtonColors(
                contentColor = LoginMutedText,
                disabledContentColor = LoginMutedText.copy(alpha = 0.72f),
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login-recover"),
        ) {
            Text(
                "Forgot password?",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
            )
        }
        successNotice?.let {
            LoginNotice(it, success = true)
            TextButton(onClick = onDismissSuccess, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Dismiss", Modifier.fillMaxWidth(), textAlign = TextAlign.End)
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
            shape = LoginControlShape,
            colors = if (signingIn) {
                ButtonDefaults.buttonColors(
                    containerColor = LoginPurple,
                    contentColor = LoginButtonInk,
                    disabledContainerColor = colors.surfaceContainerHigh,
                    disabledContentColor = colors.onSurface,
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = LoginPurple,
                    contentColor = LoginButtonInk,
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = controlHeight).testTag("login-submit")
                .semantics { if (signingIn) stateDescription = "Signing in" },
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    if (signingIn) "Signing in…" else "Login",
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (signingIn) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.CenterEnd).size(20.dp).testTag("login-progress"),
                        strokeWidth = 2.dp,
                        color = colors.onSurface,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        null,
                        Modifier.align(Alignment.CenterEnd).size(20.dp),
                    )
                }
            }
        }
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
