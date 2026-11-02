package cloud.dcompany.erp.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.R
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import cloud.dcompany.erp.ui.components.LiquidDropdownMenu
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DisplayedSceneState
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundImage
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundDecoder
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundRenderer
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.WorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.decodeMeasuredBackground
import cloud.dcompany.erp.ui.theme.workspaceGlass
import kotlin.math.ceil

internal const val LoginReferenceAssetSha256 =
    "679707186d15f553b719ec0313a906f3ce2ce49303ea3ddf2b9a19a59930d49a"
internal const val LoginSceneHdR1Sha256 =
    "13aa59b3860110f143b0b0a0bba6e84a6847667bacc2e01ae0b3b5990cef2844"

private val LoginSceneBackground = WorkspaceBackgroundImage(
    slot = WorkspaceBackgroundSlot.WORKSPACE,
    source = WorkspaceBackgroundSource.BUNDLED,
    width = 1_672,
    height = 941,
    generation = 0L,
    contentId = LoginSceneHdR1Sha256,
    resource = R.drawable.login_scene_hd_r1,
)

/** Produces one immutable scene identity whose pixels exactly match the visible Login backdrop. */
private object LoginSceneDecoder : WorkspaceBackgroundDecoder {
    override fun decodeRequested(
        image: WorkspaceBackgroundImage,
        targetWidth: Int,
        targetHeight: Int,
        context: android.content.Context,
    ): Bitmap? {
        if (image.contentId != LoginSceneHdR1Sha256 || image.resource != R.drawable.login_scene_hd_r1) return null
        val decoded = decodeMeasuredBackground(
            sourceWidth = image.width,
            sourceHeight = image.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            decode = { options ->
                BitmapFactory.decodeResource(context.resources, R.drawable.login_scene_hd_r1, options)
            },
        ) ?: return null
        val mutable = if (decoded.isMutable) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true)?.also { decoded.recycle() } ?: run {
                decoded.recycle()
                return null
            }
        }
        return try {
            applyLoginSceneGrade(mutable)
            mutable
        } catch (failure: OutOfMemoryError) {
            mutable.recycle()
            throw failure
        } catch (failure: RuntimeException) {
            mutable.recycle()
            throw failure
        }
    }

    override fun decodeBundled(
        slot: WorkspaceBackgroundSlot,
        targetWidth: Int,
        targetHeight: Int,
        context: android.content.Context,
    ): Bitmap? = null
}

private fun applyLoginSceneGrade(bitmap: Bitmap) {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    if (width <= 0f || height <= 0f) return
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f,
        0f,
        width * .56f,
        0f,
        intArrayOf(
            LoginPanelBackground.copy(alpha = .58f).toArgb(),
            LoginPanelBackground.copy(alpha = .28f).toArgb(),
            Color.Transparent.toArgb(),
        ),
        floatArrayOf(0f, .42f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width, height, paint)
    val edgeFadeHeight = height * .055f
    paint.shader = LinearGradient(
        0f,
        0f,
        0f,
        edgeFadeHeight,
        LoginPanelBackground.toArgb(),
        Color.Transparent.toArgb(),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width, edgeFadeHeight, paint)
    paint.shader = LinearGradient(
        0f,
        height - edgeFadeHeight,
        0f,
        height,
        Color.Transparent.toArgb(),
        LoginPanelBackground.toArgb(),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, height - edgeFadeHeight, width, height, paint)
    paint.shader = null
}

private val LoginPurple = Color(0xFFBF32FF)
private val LoginButtonInk = Color(0xFF16051F)
internal val LoginPanelBackground = Color(0xFF0E061F)
private val LoginGold = Color(0xFFC8A15B)
internal val LoginText = Color(0xFFF7F2FA)
private val LoginMutedText = Color(0xFFD5C8DA)
private val LoginControlShape = RoundedCornerShape(5.dp)
private val LoginGlassShape = RoundedCornerShape(28.dp)

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
    val panelLeft: Float,
    val panelTop: Float,
    val panelHeight: Float,
    val formZoneWidth: Float,
    val artworkZoneWidth: Float,
    val controlWidth: Float,
    val brandTop: Float,
    val brandHeight: Float,
    val formTop: Float,
    val condensedForIme: Boolean,
    val showIdentity: Boolean,
)

internal data class LoginBackdropCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

internal fun loginBackdropCenterCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
): LoginBackdropCrop {
    require(sourceWidth > 0 && sourceHeight > 0 && containerWidth > 0 && containerHeight > 0)
    val scale = maxOf(
        containerWidth.toFloat() / sourceWidth,
        containerHeight.toFloat() / sourceHeight,
    )
    // Round outward so integer rasterization can never expose an uncovered edge.
    val width = ceil(sourceWidth * scale).toInt()
    val height = ceil(sourceHeight * scale).toInt()
    return LoginBackdropCrop(
        left = (containerWidth - width) / 2,
        top = (containerHeight - height) / 2,
        width = width,
        height = height,
    )
}

internal fun loginWideGeometry(
    containerWidth: Float,
    containerHeight: Float,
    fontScale: Float = 1f,
): LoginWideGeometry {
    require(containerWidth > 0f && containerHeight > 0f && fontScale > 0f)
    val compositionWidth = containerWidth
    val compositionHeight = containerHeight
    // The IME changes only the available height. Keep the horizontal composition anchored to
    // width so opening the keyboard cannot move focused controls sideways, while the vertical
    // inset can still contract with the safe viewport.
    val panelHorizontalInset = minOf(48f, compositionWidth * 0.04f)
    val panelVerticalInset = minOf(48f, compositionHeight * 0.06f)
    val panelHeight = (compositionHeight - (panelVerticalInset * 2f)).coerceAtLeast(0f)
    val formZoneWidth = compositionWidth * 0.36f
    val canonicalFormTop = panelHeight * 0.36f
    val minimumFormTop = minOf(64f, panelHeight)
    val formTop = if (panelHeight <= 64f) {
        panelHeight
    } else {
        minOf(canonicalFormTop, maxOf(minimumFormTop, panelHeight - 356f))
    }
    val condensedForIme = formTop < 148f
    val canonicalBrandTop = panelHeight * 0.06f
    val brandTop = if (condensedForIme) {
        8f
    } else {
        minOf(canonicalBrandTop, maxOf(8f, formTop - 140f))
    }
    val brandHeight = if (condensedForIme) {
        48f
    } else {
        minOf(panelHeight * 0.24f, formTop - brandTop - 8f)
    }
    val identityMinimumHeight = 72f + ((40f + 20f) * fontScale)
    val showIdentity = !condensedForIme && brandHeight >= identityMinimumHeight
    return LoginWideGeometry(
        compositionWidth = compositionWidth,
        compositionHeight = compositionHeight,
        compositionLeft = (containerWidth - compositionWidth) / 2f,
        compositionTop = 0f,
        panelLeft = panelHorizontalInset,
        panelTop = panelVerticalInset,
        panelHeight = panelHeight,
        formZoneWidth = formZoneWidth,
        artworkZoneWidth = compositionWidth - panelHorizontalInset - formZoneWidth,
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
    overlay: @Composable () -> Unit = {},
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
        overlay = {
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
            overlay()
        },
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
    overlay: @Composable () -> Unit = {},
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
        WorkspaceBackgroundRenderer(
            image = LoginSceneBackground,
            modifier = Modifier.fillMaxSize().testTag("login-composition"),
            decoder = LoginSceneDecoder,
            allowBundledFallback = false,
            applyWorkspaceSceneGrade = false,
            adaptNightGlassForegrounds = false,
            backgroundColor = LoginPanelBackground,
            testTagPrefix = "login-scene",
        ) {
            LoginBackdropGrade(
                modifier = Modifier.fillMaxSize().testTag("login-backdrop"),
            )
            if (wide) {
                val geometry = loginWideGeometry(
                    containerWidth = maxWidth.value,
                    containerHeight = maxHeight.value,
                    fontScale = LocalDensity.current.fontScale,
                )
                Box(Modifier.fillMaxSize().testTag("login-layout-wide")) {
                    LoginGlassPanel(
                        Modifier.width(geometry.formZoneWidth.dp)
                            .height(geometry.panelHeight.dp)
                            .offset(x = geometry.panelLeft.dp, y = geometry.panelTop.dp)
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
                                .height((geometry.panelHeight - geometry.formTop).dp)
                                .align(Alignment.BottomCenter),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // Reserve the measured footer outside the scroll viewport. Content
                            // padding can scroll away when the IME brings a field into view.
                            Column(
                                Modifier.fillMaxWidth().weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 16.dp),
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
                                    .padding(top = 8.dp, bottom = 10.dp)
                                    .testTag("login-version"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.70f),
                                textAlign = TextAlign.Center,
                            )
                        }
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
            } else {
                Box(Modifier.fillMaxSize().testTag("login-layout-compact")) {
                    Box(
                        Modifier.fillMaxSize().testTag("login-photo")
                            .semantics { contentDescription = "D Company illustrated login artwork" },
                    )
                    LoginGlassPanel(
                        Modifier.align(Alignment.Center)
                            .padding(16.dp)
                            .widthIn(max = 488.dp)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .testTag("login-form-zone"),
                    ) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 20.dp),
                        ) {
                            LoginBrandHeader(
                                appearancePreference = appearancePreference,
                                onAppearancePreferenceChanged = onAppearancePreferenceChanged,
                                modifier = Modifier.fillMaxWidth(),
                                wide = false,
                            )
                            Box(
                                Modifier.fillMaxWidth().padding(top = 28.dp),
                            ) { LoginFormContainer(wide = false, content = form) }
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp)
                                    .testTag("login-version"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.70f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            // Recovery remains at one stable call site inside the same scene provider, so a
            // decoder/derivative readiness update cannot recreate its fields or transient state.
            overlay()
        }
    }
}

@Composable
private fun LoginGlassPanel(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scene = LocalWorkspaceGlassScene.current
    val glassReady = scene.isRequestedGlassReady()
    val clipped = modifier.shadow(24.dp, LoginGlassShape, clip = false)
        .clip(LoginGlassShape)
    Box(
        if (glassReady) {
            clipped.workspaceGlass(
                scene = scene,
                role = WorkspaceGlassRole.PANEL,
                // The entry artwork and explicit white form typography stay dark in every
                // application appearance. A Day PANEL would place those white labels on white.
                day = false,
                shape = LoginGlassShape,
            )
        } else {
            clipped.background(
                Brush.verticalGradient(
                    0f to Color(0xFF100A20).copy(alpha = 0.74f),
                    0.52f to Color(0xFF090512).copy(alpha = 0.64f),
                    1f to Color(0xFF050309).copy(alpha = 0.72f),
                ),
            ).border(1.dp, Color.White.copy(alpha = 0.24f), LoginGlassShape)
        },
    ) {
        if (glassReady) {
            Box(Modifier.fillMaxSize().testTag("login-glass-material"))
        } else {
            Canvas(Modifier.fillMaxSize().testTag("login-glass-material")) {
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.13f),
                        0.34f to Color.White.copy(alpha = 0.045f),
                        0.72f to Color.Transparent,
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.72f),
                    ),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.12f),
                        0.14f to Color.Transparent,
                        startY = 0f,
                        endY = size.height,
                    ),
                )
            }
        }
        content()
    }
}

private fun WorkspaceGlassScene?.isRequestedGlassReady(): Boolean {
    val current = this ?: return false
    return current.frame.state == DisplayedSceneState.REQUESTED_READY &&
        current.frame.actualKey == current.frame.requestedKey &&
        current.softenedBitmap != null &&
        current.softenedKey?.actualKey == current.frame.actualKey
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
                DCompanyBrandMark(
                    size = if (wide) 72.dp else 52.dp,
                    contentDescription = "D Company logo",
                    modifier = Modifier.testTag("login-brand-mark"),
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
private fun LoginBackdropGrade(modifier: Modifier) {
    val requestedImageReady = LocalWorkspaceGlassScene.current?.let { scene ->
        scene.frame.state == DisplayedSceneState.REQUESTED_READY &&
            scene.frame.actualKey == scene.frame.requestedKey
    } == true
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        if (requestedImageReady) return@Canvas
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF120622), LoginPanelBackground, Color(0xFF08030E)),
            ),
        )
        drawRect(
            brush = Brush.horizontalGradient(
                0f to LoginPanelBackground.copy(alpha = 0.58f),
                0.42f to LoginPanelBackground.copy(alpha = 0.28f),
                1f to Color.Transparent,
                startX = 0f,
                endX = width * 0.56f,
            ),
            size = androidx.compose.ui.geometry.Size(width, height),
        )
        val edgeFadeHeight = height * 0.055f
        drawRect(
            brush = Brush.verticalGradient(
                0f to LoginPanelBackground,
                1f to Color.Transparent,
                startY = 0f,
                endY = edgeFadeHeight,
            ),
            size = androidx.compose.ui.geometry.Size(width, edgeFadeHeight),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to LoginPanelBackground,
                startY = height - edgeFadeHeight,
                endY = height,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(0f, height - edgeFadeHeight),
            size = androidx.compose.ui.geometry.Size(width, edgeFadeHeight),
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
        LiquidDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                    disabledContainerColor = Color(0xFF3A2748),
                    disabledContentColor = Color(0xFFD8CFDC),
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
    val scene = LocalWorkspaceGlassScene.current
    val glassReady = scene.isRequestedGlassReady()
    val day = MaterialTheme.colorScheme.background.luminance() > .5f
    val shape = Radius.shapeLg
    Surface(
        modifier = Modifier.fillMaxWidth()
            .then(
                if (glassReady) {
                    Modifier.clip(shape).workspaceGlass(
                        scene = scene,
                        role = WorkspaceGlassRole.CONTROL,
                        day = day,
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .testTag("login-notice")
            .semantics {
                liveRegion = if (success) LiveRegionMode.Polite else LiveRegionMode.Assertive
            },
        color = if (glassReady) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        border = if (glassReady) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
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
