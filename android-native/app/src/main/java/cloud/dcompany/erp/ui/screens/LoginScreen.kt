package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.R
import cloud.dcompany.erp.ui.components.AppearanceControl
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.Radius
import kotlin.math.roundToInt

internal const val LoginReferenceAssetSha256 =
    "679707186d15f553b719ec0313a906f3ce2ce49303ea3ddf2b9a19a59930d49a"

private val LoginPurple = Color(0xFFBF32FF)
private val LoginButtonInk = Color(0xFF16051F)
private val LoginPanelBackground = Color(0xFF0E061F)

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

internal val LoginReferencePalmBounds = LoginArtworkSourceCrop(
    left = 221,
    top = 42,
    width = 112,
    height = 50,
)

/** Center-crops only the clean artwork region inside the supplied Login reference. */
internal fun loginReferenceArtworkSourceCrop(
    destinationWidthPx: Int,
    destinationHeightPx: Int,
): LoginArtworkSourceCrop {
    require(destinationWidthPx > 0 && destinationHeightPx > 0) {
        "Destination dimensions must be positive"
    }
    val bounds = LoginReferenceArtworkBounds
    val destinationRatio = destinationWidthPx.toDouble() / destinationHeightPx.toDouble()
    val sourceRatio = bounds.width.toDouble() / bounds.height.toDouble()
    val cropWidth: Int
    val cropHeight: Int
    if (destinationRatio >= sourceRatio) {
        cropWidth = bounds.width
        cropHeight = (bounds.width / destinationRatio).roundToInt().coerceIn(1, bounds.height)
    } else {
        cropHeight = bounds.height
        cropWidth = (bounds.height * destinationRatio).roundToInt().coerceIn(1, bounds.width)
    }
    return LoginArtworkSourceCrop(
        left = bounds.left + (bounds.width - cropWidth) / 2,
        top = bounds.top + (bounds.height - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

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
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
    ) {
        val wide = maxWidth >= 840.dp
        if (wide) {
            val brandToFormGap = if (maxHeight >= 720.dp) 148.dp else 88.dp
            Row(Modifier.fillMaxSize().testTag("login-layout-wide")) {
                Box(
                    Modifier.fillMaxHeight().weight(0.4f).testTag("login-form-zone")
                        .background(LoginPanelBackground),
                ) {
                    LoginReferencePalmAmbience(
                        Modifier.fillMaxWidth().height(260.dp).align(Alignment.TopStart),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            if (appearancePreference == AppearancePreference.Night) {
                                Color(0xC90A0419)
                            } else {
                                MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
                            },
                        ),
                    )
                    Column(
                        Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 40.dp, top = 88.dp, end = 40.dp, bottom = 24.dp),
                    ) {
                        LoginBrandHeader(appearancePreference, onAppearancePreferenceChanged)
                        Spacer(Modifier.height(brandToFormGap))
                        Box(
                            Modifier.widthIn(max = 390.dp).fillMaxWidth()
                                .align(Alignment.CenterHorizontally),
                        ) { form() }
                        Spacer(Modifier.height(24.dp))
                    }
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .testTag("login-version"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center,
                    )
                }
                Box(
                    Modifier.fillMaxHeight().weight(0.6f).testTag("login-photo-zone"),
                ) {
                    LoginReferenceArtwork(
                        modifier = Modifier.fillMaxSize().testTag("login-photo"),
                        contentDescription = "D Company illustrated login artwork",
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("login-layout-compact"),
            ) {
                LoginBrandHeader(
                    appearancePreference = appearancePreference,
                    onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
                Box(
                    Modifier.widthIn(max = 488.dp).fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                        .testTag("login-form-zone"),
                ) { form() }
            }
        }
    }
}

@Composable
private fun LoginBrandHeader(
    appearancePreference: AppearancePreference,
    onAppearancePreferenceChanged: (AppearancePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().testTag("login-brand-panel"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DCompanyBrandMark(size = 52.dp, contentDescription = "D Company logo")
            Spacer(Modifier.height(8.dp))
            Text(
                "D COMPANY",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                "GAMING LOUNGE",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        AppearanceControl(
            preference = appearancePreference,
            onChange = onAppearancePreferenceChanged,
            modifier = Modifier.align(Alignment.TopEnd).testTag("login-appearance"),
            compactLabel = true,
        )
    }
}

@Composable
private fun LoginReferencePalmAmbience(modifier: Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.login_reference_original)
    Canvas(modifier) {
        val width = size.width.roundToInt()
        val height = size.height.roundToInt()
        if (width <= 0 || height <= 0) return@Canvas
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(LoginReferencePalmBounds.left, LoginReferencePalmBounds.top),
            srcSize = IntSize(LoginReferencePalmBounds.width, LoginReferencePalmBounds.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(width, height),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0.45f to Color.Transparent,
                1f to LoginPanelBackground,
            ),
        )
    }
}

@Composable
private fun LoginReferenceArtwork(
    modifier: Modifier,
    contentDescription: String?,
) {
    val bitmap = ImageBitmap.imageResource(R.drawable.login_reference_original)
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier.then(semantics)) {
        drawLoginReferenceArtwork(bitmap)
    }
}

private fun DrawScope.drawLoginReferenceArtwork(bitmap: ImageBitmap) {
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    if (width <= 0 || height <= 0) return
    val crop = loginReferenceArtworkSourceCrop(width, height)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(crop.left, crop.top),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
    )
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
        focusedBorderColor = LoginPurple,
        unfocusedBorderColor = colors.onSurfaceVariant.copy(alpha = 0.72f),
        focusedContainerColor = colors.surface.copy(alpha = 0.78f),
        unfocusedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.78f),
        disabledContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.62f),
        cursorColor = LoginPurple,
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
    )
    Column(Modifier.fillMaxWidth().testTag("login-form")) {
        Text("Email", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChanged,
            enabled = !signingIn,
            placeholder = { Text("Enter email", color = colors.onSurfaceVariant) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("login-email")
                .semantics { contentDescription = "Email" },
            shape = Radius.shapeSm,
            colors = inputColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
        )
        Spacer(Modifier.height(16.dp))
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("login-password")
                .semantics { contentDescription = "Password" },
            shape = Radius.shapeSm,
            colors = inputColors,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        TextButton(
            onClick = onRecover,
            enabled = !signingIn,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login-recover"),
        ) {
            Text("Forgot password?", Modifier.fillMaxWidth(), textAlign = TextAlign.End)
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
            shape = Radius.shapeSm,
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("login-submit")
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
