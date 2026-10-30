package cloud.dcompany.erp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.dcompany.erp.R
import cloud.dcompany.erp.ui.components.DCompanyBrandMark
import cloud.dcompany.erp.ui.components.liquidWorkspaceSurface
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundRenderer
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.workspaceGlassRoleStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal const val SplashLoungeHdR1Sha256 =
    "1a27f92dd5d921b290c9c430e8a1a100cc0a49a5fe4e7cd9bd56d62da0177684"

internal data class SplashSceneSourceCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * Center-crops the selected 16:9 scene without stretching it. The controller
 * remains the focal point as the app moves between tablet and compact windows.
 */
internal fun splashSceneSourceCrop(
    destinationWidthPx: Int,
    destinationHeightPx: Int,
): SplashSceneSourceCrop {
    require(destinationWidthPx > 0 && destinationHeightPx > 0) {
        "Destination dimensions must be positive"
    }
    val sourceWidth = 1_672
    val sourceHeight = 941
    val destinationRatio = destinationWidthPx.toDouble() / destinationHeightPx.toDouble()
    val sourceRatio = sourceWidth.toDouble() / sourceHeight.toDouble()
    val cropWidth: Int
    val cropHeight: Int
    if (destinationRatio >= sourceRatio) {
        cropWidth = sourceWidth
        cropHeight = (sourceWidth / destinationRatio).roundToInt().coerceIn(1, sourceHeight)
    } else {
        cropHeight = sourceHeight
        cropWidth = (sourceHeight * destinationRatio).roundToInt().coerceIn(1, sourceWidth)
    }
    return SplashSceneSourceCrop(
        left = (sourceWidth - cropWidth) / 2,
        top = (sourceHeight - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

@Composable
internal fun SplashLoungeScene(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap = ImageBitmap.imageResource(R.drawable.splash_lounge_hd_r1)
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier.then(semantics)) {
        drawSplashLounge(bitmap)
    }
}

private fun DrawScope.drawSplashLounge(bitmap: ImageBitmap) {
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    if (width <= 0 || height <= 0) return
    val crop = splashSceneSourceCrop(width, height)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(crop.left, crop.top),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
    )
    drawRect(Color.Black.copy(alpha = 0.18f))
}

@Composable
internal fun ExecutiveEntrySplash(
    statusText: String = "Preparing your workspace",
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier.fillMaxSize().background(Color(0xFF0E061F)).testTag("executive-entry-splash"),
    ) {
        SplashLoungeScene(
            Modifier.fillMaxSize().testTag("entry-splash-scene"),
            contentDescription = "D Company gaming lounge splash artwork",
        )
        val fontScale = LocalDensity.current.fontScale
        val compactHeight = maxHeight < 520.dp
        val markSize = if (compactHeight) 54.dp else 88.dp
        val nameLineHeight = if (compactHeight) 28.sp else 40.sp
        val taglineLineHeight = if (compactHeight) 16.sp else 20.sp
        val nameCenter = if (compactHeight) 0.465f else 0.47f
        val taglineCenter = if (compactHeight) 0.558f else 0.54f

        DCompanyBrandMark(
            size = markSize,
            contentDescription = "D Company logo",
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (maxHeight * 0.33f) - (markSize / 2))
                .testTag("entry-splash-brand-mark"),
        )
        Text(
            text = "D COMPANY",
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (maxHeight * nameCenter) - ((nameLineHeight.value * fontScale / 2f).dp))
                .fillMaxWidth(0.84f)
                .testTag("entry-splash-brand-name"),
            color = Color(0xFFF7F2FA),
            style = if (compactHeight) {
                MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = nameLineHeight)
            } else {
                MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp, lineHeight = nameLineHeight)
            },
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (compactHeight) 4.sp else 6.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = "GAMING LOUNGE",
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (maxHeight * taglineCenter) - ((taglineLineHeight.value * fontScale / 2f).dp))
                .fillMaxWidth(0.72f)
                .testTag("entry-splash-brand-tagline"),
            color = Color(0xFFEBC577),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = if (compactHeight) 12.sp else 16.sp,
                lineHeight = taglineLineHeight,
            ),
            letterSpacing = if (compactHeight) 2.4.sp else 4.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = statusText,
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth(0.88f)
                .padding(bottom = maxHeight * 0.045f)
                .testTag("entry-status"),
            color = Color(0xFFE9E4ED),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Clip,
        )
    }
}

internal fun shouldCompleteFreshLoginSwipe(
    upwardDistancePx: Float,
    upwardVelocityDpPerSecond: Float,
    distanceThresholdPx: Float,
): Boolean = upwardDistancePx >= distanceThresholdPx ||
    (upwardDistancePx >= distanceThresholdPx * 0.35f && upwardVelocityDpPerSecond >= 900f)

/**
 * A one-shot post-authentication handoff. The workspace is deliberately not
 * composed behind this screen; the callback carries the exact session nonce
 * and the session owner performs the final lineage/scope check.
 */
@Composable
internal fun FreshLoginSuccessScreen(
    nonce: Long,
    employeeName: String,
    backgroundState: WorkspaceBackgroundUiState,
    onContinue: (Long) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(nonce) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    WorkspaceBackgroundRenderer(
        image = backgroundState.image(WorkspaceBackgroundSlot.WORKSPACE),
        modifier = modifier.fillMaxSize().testTag("fresh-login-background"),
        testTagPrefix = "fresh-login",
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("fresh-login-root"),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val day = MaterialTheme.colorScheme.background.luminance() > .5f
            val panelStyle = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day)
            val promptStyle = workspaceGlassRoleStyle(WorkspaceGlassRole.CONTROL, day)
            val scope = rememberCoroutineScope()
            val latestContinue by rememberUpdatedState(onContinue)
            val availableHeight = maxHeight
            val exitDistancePx = with(density) { availableHeight.toPx().coerceAtLeast(1f) }
            val distanceThresholdPx = with(density) {
                (availableHeight * 0.16f).coerceIn(64.dp, 112.dp).toPx()
            }
            var offsetPx by remember(nonce) { mutableFloatStateOf(0f) }
            var exiting by remember(nonce) { mutableStateOf(false) }
            var settling by remember(nonce) { mutableStateOf(false) }

            fun settleOrContinue(
                upwardVelocityDpPerSecond: Float,
                commitAllowed: Boolean = true,
                explicitContinuation: Boolean = false,
            ) {
                if (exiting || settling) return
                val shouldContinue = commitAllowed && (
                    explicitContinuation || shouldCompleteFreshLoginSwipe(
                        upwardDistancePx = -offsetPx,
                        upwardVelocityDpPerSecond = upwardVelocityDpPerSecond,
                        distanceThresholdPx = distanceThresholdPx,
                    )
                )
                settling = true
                if (shouldContinue) exiting = true
                scope.launch {
                    val target = if (shouldContinue) -exitDistancePx else 0f
                    animate(
                        initialValue = offsetPx,
                        targetValue = target,
                        animationSpec = tween(durationMillis = if (shouldContinue) 260 else 180),
                    ) { value, _ -> offsetPx = value }
                    if (shouldContinue) {
                        if (latestContinue(nonce)) return@launch
                        // The exact lineage/scope may change during the exit
                        // animation. A rejected stale acknowledgement must
                        // restore a usable prompt rather than leave a faded,
                        // offscreen and noninteractive gate behind.
                        exiting = false
                        animate(
                            initialValue = offsetPx,
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 180),
                        ) { value, _ -> offsetPx = value }
                        settling = false
                    } else {
                        settling = false
                    }
                }
            }

            BackHandler(enabled = true) {
                settleOrContinue(0f, explicitContinuation = true)
            }
            val progress = (-offsetPx / exitDistancePx).coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .offset { IntOffset(0, offsetPx.roundToInt()) }
                    .graphicsLayer { alpha = 1f - (progress * 0.72f) }
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("fresh-login-stage"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = (availableHeight - 220.dp).coerceAtLeast(240.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 520.dp)
                            .fillMaxWidth(0.9f)
                            .clip(Radius.shapeLg)
                            .liquidWorkspaceSurface(Radius.shapeLg, WorkspaceGlassRole.PANEL)
                            .padding(horizontal = 32.dp, vertical = 28.dp)
                            .semantics {
                                contentDescription = "Login successful."
                                liveRegion = LiveRegionMode.Polite
                            }
                            .testTag("fresh-login-success-card"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        DCompanyBrandMark(
                            size = 64.dp,
                            contentDescription = "D Company logo",
                            modifier = Modifier.testTag("fresh-login-brand-mark"),
                        )
                        Text(
                            text = "Login successful",
                            style = MaterialTheme.typography.headlineSmall,
                            color = panelStyle.primaryContent,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                                .semantics { heading() }
                                .testTag("fresh-login-title"),
                        )
                        Text(
                            text = "Welcome, $employeeName",
                            style = MaterialTheme.typography.bodyLarge,
                            color = panelStyle.secondaryContent,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxWidth()
                                .testTag("fresh-login-employee"),
                        )
                    }
                }

                var velocityTracker by remember(nonce) { mutableStateOf(VelocityTracker()) }
                Column(
                    modifier = Modifier.widthIn(max = 520.dp)
                        .fillMaxWidth(0.9f)
                        .clip(Radius.shapeLg)
                        .liquidWorkspaceSurface(Radius.shapeLg, WorkspaceGlassRole.CONTROL)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .pointerInput(nonce, exiting, settling, distanceThresholdPx) {
                            detectVerticalDragGestures(
                                onDragStart = { velocityTracker = VelocityTracker() },
                                onVerticalDrag = { change, dragAmount ->
                                    if (!exiting && !settling) {
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        offsetPx = (offsetPx + dragAmount).coerceIn(-exitDistancePx, 0f)
                                        change.consume()
                                    }
                                },
                                onDragEnd = {
                                    settleOrContinue(
                                        -velocityTracker.calculateVelocity().y / density.density,
                                    )
                                },
                                onDragCancel = {
                                    settleOrContinue(0f, commitAllowed = false)
                                },
                            )
                        }
                        .semantics {
                            contentDescription =
                                "Swipe up or use Continue to open the workspace."
                        }
                        .testTag("fresh-login-swipe-owner"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "↑",
                        style = MaterialTheme.typography.headlineMedium,
                        color = promptStyle.iconContent,
                        modifier = Modifier.testTag("fresh-login-up-arrow"),
                    )
                    Text(
                        text = "Swipe up to open your workspace",
                        style = MaterialTheme.typography.bodyMedium,
                        color = promptStyle.secondaryContent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                            .testTag("fresh-login-instruction"),
                    )
                    Button(
                        onClick = { settleOrContinue(0f, explicitContinuation = true) },
                        enabled = !exiting && !settling,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("fresh-login-continue"),
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}
