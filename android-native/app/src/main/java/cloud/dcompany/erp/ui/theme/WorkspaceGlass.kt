package cloud.dcompany.erp.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class DisplayedSceneState { REQUESTED_READY, BUNDLED_FALLBACK, UNAVAILABLE }

@Immutable
internal data class DisplayedSceneFrame(
    val actualKey: WorkspaceBackgroundRenderKey,
    val requestedKey: WorkspaceBackgroundRenderKey,
    val state: DisplayedSceneState,
    val clearBitmap: Bitmap,
    val epoch: Long,
)

internal data class WorkspaceGlassDerivativeKey(
    val actualKey: WorkspaceBackgroundRenderKey,
    val frameEpoch: Long,
    val algorithmVersion: Int,
    val width: Int,
    val height: Int,
    val blurRadius: Int,
)

internal const val WorkspaceGlassSafetyGridColumns = 32
internal const val WorkspaceGlassSafetyGridRows = 20

/** Component-wise maxima are conservative for the light Night foregrounds used by glass. */
internal class WorkspaceGlassSafetyGrid(
    val columns: Int,
    val rows: Int,
    maxima: IntArray,
) {
    private val maxima: IntArray = maxima.copyOf()

    init {
        require(columns > 0 && rows > 0 && this.maxima.size == columns * rows)
    }

    internal fun colorAt(column: Int, row: Int): Color =
        Color(maxima[row * columns + column])
}

internal data class WorkspaceGlassDerivative(
    val key: WorkspaceGlassDerivativeKey,
    val bitmap: Bitmap,
    val safetyGrid: WorkspaceGlassSafetyGrid,
)

internal data class WorkspaceGlassScene(
    val frame: DisplayedSceneFrame,
    val derivative: WorkspaceGlassDerivative?,
    val rootCoordinates: LayoutCoordinates,
    val rootBoundsInRoot: Rect,
    val layoutEpoch: Long,
    val applyWorkspaceSceneGrade: Boolean = true,
) {
    val softenedBitmap: Bitmap? get() = derivative?.bitmap
    val softenedKey: WorkspaceGlassDerivativeKey? get() = derivative?.key
}

internal val LocalWorkspaceGlassScene = staticCompositionLocalOf<WorkspaceGlassScene?> { null }

// Workspace accents never replace the app theme: the Gaming instrument keeps its own palette.
internal val WorkspacePresentationGold = Color(0xFFFFD24A)
internal val WorkspacePresentationOnGold = Color(0xFF181106)

internal fun workspacePresentationAccent(glassAvailable: Boolean, day: Boolean, fallback: Color): Color =
    if (glassAvailable && !day) WorkspacePresentationGold else fallback

@Composable
internal fun workspacePresentationAccent(): Color = workspacePresentationAccent(
    glassAvailable = LocalWorkspaceGlassScene.current != null,
    day = MaterialTheme.colorScheme.background.luminance() > .5f,
    fallback = MaterialTheme.colorScheme.primary,
)

internal const val WorkspaceClearCacheBudgetKiB = 40 * 1_024
internal const val WorkspaceGlassDerivativeCacheEntries = 2
// Admission ceiling for app-managed clear-cache entries, two published derivatives, one
// in-flight derivative (all five buffers), and one new 2560x1600 clear frame: about 59.9 MiB at
// the accepted maximum. This is not a process-PSS claim; platform decoder allocations, its input
// and output scratch, and an already leased source bitmap are outside this managed envelope.
internal const val WorkspaceGlassCombinedStartingBudgetBytes = 64L * 1_024 * 1_024

internal val WorkspaceGlassNeutralNightRoles = setOf(
    WorkspaceGlassRole.RAIL,
    WorkspaceGlassRole.PANEL,
    WorkspaceGlassRole.UTILITY,
    WorkspaceGlassRole.CONTROL,
)

internal val WorkspaceGlassAdaptiveNightRoles = WorkspaceGlassNeutralNightRoles + setOf(
    WorkspaceGlassRole.MODAL,
)

internal val WorkspaceGlassAdaptiveAlphaCandidates: List<Float> = listOf(
    .38f, .40f, .42f, .46f, .50f, .54f, .58f, .62f, .66f, .70f, .74f, .80f,
)

internal const val WorkspaceGlassNeutralNightTintFloor = .58f
internal val WorkspaceGlassNeutralNightContourWidth = .5.dp

internal fun workspaceGlassContourWidth(role: WorkspaceGlassRole, day: Boolean) = when {
    !day && role in WorkspaceGlassNeutralNightRoles -> WorkspaceGlassNeutralNightContourWidth
    role == WorkspaceGlassRole.RAIL || role == WorkspaceGlassRole.PANEL -> .75.dp
    else -> 1.dp
}

internal enum class WorkspaceGlassRole {
    RAIL,
    UTILITY,
    CONTROL,
    PANEL,
    SELECTED_OUTLINE,
    SELECTED,
    DISABLED,
    WARNING,
    ERROR,
    MODAL,
}

internal data class WorkspaceGlassRoleStyle(
    val tint: Color,
    val fallback: Color,
    val stroke: Color,
    val innerHighlight: Color,
    val primaryContent: Color,
    val secondaryContent: Color,
    val iconContent: Color,
)

/** Glass replaces the world behind it with an opaque derivative, so every role reapplies the full grade. */
@Suppress("UNUSED_PARAMETER")
internal fun workspaceGlassSceneGradeStrength(role: WorkspaceGlassRole, day: Boolean): Float = 1f

internal fun workspaceGlassRoleStyle(role: WorkspaceGlassRole, day: Boolean): WorkspaceGlassRoleStyle {
    // Warning, error and disabled roles retain denser semantic palettes; dialogs remain translucent.
    val dayPrimary = Color(0xFF101824)
    val daySecondary = Color(0xFF34445A)
    val nightPrimary = Color(0xFFF3F5F7)
    val nightSecondary = Color(0xFFEEF2F8)
    val nightNormalStroke = Color(0x24A9B2C4)
    val nightNormalHighlight = Color(0x33A9B2C4)
    val nightPanelHighlight = Color(0x40A9B2C4)
    // Normal transmissive roles use stronger foregrounds so the scene can remain visible without
    // reducing small-text or icon contrast. These do not flow into the denser semantic roles.
    val dayGlassPrimary = Color(0xFF0B111A)
    val dayGlassSecondary = Color(0xFF16202D)
    if (day) return when (role) {
        WorkspaceGlassRole.RAIL -> WorkspaceGlassRoleStyle(
            Color.White.copy(alpha = .60f), Color(0xFFF5F7FA), Color(0x8A718298),
            Color.White.copy(alpha = .44f), dayGlassPrimary, dayGlassSecondary, dayGlassSecondary,
        )
        WorkspaceGlassRole.UTILITY -> WorkspaceGlassRoleStyle(
            Color.White.copy(alpha = .58f), Color(0xFFF7F9FC), Color(0x8A718298),
            Color.White.copy(alpha = .48f), dayGlassPrimary, dayGlassSecondary, dayGlassSecondary,
        )
        WorkspaceGlassRole.CONTROL -> WorkspaceGlassRoleStyle(
            Color.White.copy(alpha = .60f), Color(0xFFFAFBFD), Color(0x96718298),
            Color.White.copy(alpha = .52f), dayGlassPrimary, dayGlassSecondary, dayGlassSecondary,
        )
        WorkspaceGlassRole.PANEL -> WorkspaceGlassRoleStyle(
            Color.White.copy(alpha = .67f), Color(0xFFF4F7FA), Color(0x8A718298),
            Color.White.copy(alpha = .42f), dayGlassPrimary, dayGlassSecondary, dayGlassSecondary,
        )
        WorkspaceGlassRole.SELECTED_OUTLINE -> WorkspaceGlassRoleStyle(
            Color(0xFFFFF2D2).copy(alpha = .56f), Color(0xFFF5E9C9), Color(0xFF503500),
            Color(0x4D9A722A), Color(0xFF2B1C00), Color(0xFF2B1C00), Color(0xFF372500),
        )
        WorkspaceGlassRole.SELECTED -> WorkspaceGlassRoleStyle(
            WorkspacePresentationGold.copy(alpha = .90f), WorkspacePresentationGold, Color(0xFF76530C),
            Color.White.copy(alpha = .54f), WorkspacePresentationOnGold, Color(0xFF3A2A08), WorkspacePresentationOnGold,
        )
        WorkspaceGlassRole.DISABLED -> WorkspaceGlassRoleStyle(
            Color.White.copy(alpha = .88f), Color(0xFFF0F2F5), Color(0x66707C8C),
            Color.White.copy(alpha = .64f), Color(0xFF3E4B5E), Color(0xFF526174), Color(0xFF526174),
        )
        WorkspaceGlassRole.WARNING -> WorkspaceGlassRoleStyle(
            Color(0xFFFFF1CC).copy(alpha = .90f), Color(0xFFFFF1CC), Color(0xFF8C6500),
            Color.White.copy(alpha = .54f), Color(0xFF3B2A00), Color(0xFF594000), Color(0xFF594000),
        )
        WorkspaceGlassRole.ERROR -> WorkspaceGlassRoleStyle(
            Color(0xFFFFE9E7).copy(alpha = .92f), Color(0xFFFFE9E7), Color(0xFFA5272D),
            Color.White.copy(alpha = .54f), Color(0xFF551015), Color(0xFF712228), Color(0xFF712228),
        )
        WorkspaceGlassRole.MODAL -> WorkspaceGlassRoleStyle(
            Color.White.copy(alpha = .88f), Color(0xFFFAFBFD), Color(0xFF68788C),
            Color.White.copy(alpha = .64f), dayPrimary, daySecondary, daySecondary,
        )
    }
    return when (role) {
        WorkspaceGlassRole.RAIL -> WorkspaceGlassRoleStyle(
            Color(0xFF05060B).copy(alpha = WorkspaceGlassNeutralNightTintFloor),
            Color(0xFF030609),
            nightNormalStroke,
            nightPanelHighlight, nightPrimary, nightSecondary, nightSecondary,
        )
        WorkspaceGlassRole.UTILITY -> WorkspaceGlassRoleStyle(
            Color(0xFF05060B).copy(alpha = WorkspaceGlassNeutralNightTintFloor),
            Color(0xFF05080D),
            nightNormalStroke,
            nightNormalHighlight, nightPrimary, nightSecondary, nightSecondary,
        )
        WorkspaceGlassRole.CONTROL -> WorkspaceGlassRoleStyle(
            Color(0xFF05060B).copy(alpha = WorkspaceGlassNeutralNightTintFloor),
            Color(0xFF06090E),
            nightNormalStroke,
            nightNormalHighlight, nightPrimary, nightSecondary, nightSecondary,
        )
        WorkspaceGlassRole.PANEL -> WorkspaceGlassRoleStyle(
            Color(0xFF05060B).copy(alpha = WorkspaceGlassNeutralNightTintFloor),
            Color(0xFF04070B),
            nightNormalStroke,
            nightPanelHighlight, nightPrimary, nightSecondary, nightSecondary,
        )
        WorkspaceGlassRole.SELECTED_OUTLINE -> WorkspaceGlassRoleStyle(
            Color(0xFF0B0803).copy(alpha = .70f), Color(0xFF0B0A08), Color(0xB8E8BD62),
            Color(0x22E8BD62), Color(0xFFFFE29A), nightSecondary, Color(0xFFE8BD62),
        )
        WorkspaceGlassRole.SELECTED -> WorkspaceGlassRoleStyle(
            WorkspacePresentationGold.copy(alpha = .88f), WorkspacePresentationGold, Color(0xFFFFE79A),
            Color.White.copy(alpha = .36f), WorkspacePresentationOnGold, Color(0xFF3A2A08), WorkspacePresentationOnGold,
        )
        WorkspaceGlassRole.DISABLED -> WorkspaceGlassRoleStyle(
            Color(0xFF0A111B).copy(alpha = .88f), Color(0xFF0A111B), Color(0x665D6D86),
            Color.White.copy(alpha = .08f), Color(0xFFAAB5C5), Color(0xFF8D99AA), Color(0xFF8D99AA),
        )
        WorkspaceGlassRole.WARNING -> WorkspaceGlassRoleStyle(
            Color(0xFF2C2108).copy(alpha = .90f), Color(0xFF2C2108), Color(0xFFE8BD62),
            Color(0x38E8BD62), Color(0xFFFFE2A3), Color(0xFFF3CA73), Color(0xFFF3CA73),
        )
        WorkspaceGlassRole.ERROR -> WorkspaceGlassRoleStyle(
            Color(0xFF321113).copy(alpha = .92f), Color(0xFF321113), Color(0xFFFFB4AB),
            Color(0x38FFB4AB), Color(0xFFFFDAD6), Color(0xFFFFB4AB), Color(0xFFFFB4AB),
        )
        WorkspaceGlassRole.MODAL -> WorkspaceGlassRoleStyle(
            Color(0xFF05060B).copy(alpha = .60f), Color(0xFF070D16), Color(0xFF65748A),
            Color.White.copy(alpha = .10f), nightPrimary, nightSecondary, nightSecondary,
        )
    }
}

internal fun Modifier.workspaceGlass(
    scene: WorkspaceGlassScene?,
    role: WorkspaceGlassRole,
    day: Boolean,
    shape: Shape = RectangleShape,
    allowWindowProjection: Boolean = false,
): Modifier {
    val style = workspaceGlassRoleStyle(role, day)
    val selected = role == WorkspaceGlassRole.SELECTED || role == WorkspaceGlassRole.SELECTED_OUTLINE
    val neutral = role in WorkspaceGlassNeutralNightRoles
    val contour = if (selected || !neutral) {
        SolidColor(style.stroke)
    } else {
        Brush.linearGradient(
            colorStops = arrayOf(
                0f to style.innerHighlight,
                .28f to style.stroke,
                1f to style.stroke.copy(alpha = .10f),
            ),
        )
    }
    val contourWidth = workspaceGlassContourWidth(role, day)
    return this.then(WorkspaceGlassElement(scene, role, day, allowWindowProjection))
        .border(contourWidth, contour, shape)
}

private data class WorkspaceGlassElement(
    val scene: WorkspaceGlassScene?,
    val role: WorkspaceGlassRole,
    val day: Boolean,
    val allowWindowProjection: Boolean,
) : ModifierNodeElement<WorkspaceGlassNode>() {
    override fun create(): WorkspaceGlassNode = WorkspaceGlassNode(scene, role, day, allowWindowProjection)

    override fun update(node: WorkspaceGlassNode) {
        node.update(scene, role, day, allowWindowProjection)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "workspaceGlass"
        properties["role"] = role
        properties["day"] = day
    }
}

private class WorkspaceGlassNode(
    private var scene: WorkspaceGlassScene?,
    private var role: WorkspaceGlassRole,
    private var day: Boolean,
    private var allowWindowProjection: Boolean,
) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode {
    private var consumerCoordinates: LayoutCoordinates? = null
    private var boundsInScene: Rect? = null
    private var roleStyle = workspaceGlassRoleStyle(role, day)
    private var derivativeBitmap: Bitmap? = null
    private var derivativeImage: ImageBitmap? = null
    private var sceneScrimBrush: Brush? = null
    private var sceneScrimTop = Float.NaN
    private var sceneScrimViewportHeight = 0
    private var admissionKey: WorkspaceGlassAdmissionKey? = null
    private var admittedTint: Color? = null

    fun update(scene: WorkspaceGlassScene?, role: WorkspaceGlassRole, day: Boolean, allowWindowProjection: Boolean) {
        val sceneChanged = this.scene != scene
        val roleStyleChanged = this.role != role || this.day != day
        val changed = sceneChanged || roleStyleChanged ||
            this.allowWindowProjection != allowWindowProjection
        if (sceneChanged) {
            // The renderer owns and recycles the Bitmap. Drop only this node's lightweight
            // Compose wrapper so a replacement scene cannot retain the previous derivative.
            derivativeBitmap = null
            derivativeImage = null
            admissionKey = null
            admittedTint = null
        }
        if (roleStyleChanged) {
            roleStyle = workspaceGlassRoleStyle(role, day)
            admissionKey = null
            admittedTint = null
        }
        this.scene = scene
        this.role = role
        this.day = day
        this.allowWindowProjection = allowWindowProjection
        if (changed) {
            boundsInScene = mapBoundsToScene(consumerCoordinates, scene, allowWindowProjection)
            invalidateDraw()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        consumerCoordinates = coordinates
        val mapped = mapBoundsToScene(coordinates, scene, allowWindowProjection)
        if (mapped != boundsInScene) {
            boundsInScene = mapped
            invalidateDraw()
        }
    }

    override fun onDetach() {
        consumerCoordinates = null
        boundsInScene = null
        derivativeBitmap = null
        derivativeImage = null
        admissionKey = null
        admittedTint = null
        sceneScrimBrush = null
        sceneScrimTop = Float.NaN
        sceneScrimViewportHeight = 0
    }

    override fun ContentDrawScope.draw() {
        val style = roleStyle
        val sceneGradeStrength = workspaceGlassSceneGradeStrength(role, day)
        val currentScene = scene
        // Coordinate attachment can change between placement and draw. Revalidate the live roots
        // so a detached or reparented consumer cannot reuse its last mapped rectangle.
        val local = mapBoundsToScene(consumerCoordinates, currentScene, allowWindowProjection)
        val derivativeEntry = currentScene?.derivative
        val derivative = derivativeEntry?.bitmap
        val keyMatches = derivative != null && derivativeEntry.key.actualKey == currentScene.frame.actualKey &&
            derivativeEntry.key.frameEpoch == currentScene.frame.epoch
        val viewportWidth = currentScene?.frame?.actualKey?.viewportWidth ?: 0
        val viewportHeight = currentScene?.frame?.actualKey?.viewportHeight ?: 0
        val intersectsScene = local != null && local.right > 0f && local.bottom > 0f &&
            local.left < viewportWidth && local.top < viewportHeight

        val adaptive = !day && role in WorkspaceGlassAdaptiveNightRoles
        val tint = if (adaptive && derivativeEntry != null && local != null && keyMatches && intersectsScene) {
            val nextKey = WorkspaceGlassAdmissionKey(
                derivativeKey = derivativeEntry.key,
                role = role,
                left = floor(local.left).toInt(),
                top = floor(local.top).toInt(),
                right = ceil(local.right).toInt(),
                bottom = ceil(local.bottom).toInt(),
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                applySceneGrade = currentScene.applyWorkspaceSceneGrade,
            )
            if (admissionKey != nextKey) {
                admittedTint = workspaceGlassAdmittedTintAlpha(
                    grid = derivativeEntry.safetyGrid,
                    derivativeKey = derivativeEntry.key,
                    boundsInScene = local,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    role = role,
                    style = style,
                    applySceneGrade = currentScene.applyWorkspaceSceneGrade,
                )?.let { Color(0xFF05060B).copy(alpha = it) }
                admissionKey = nextKey
            }
            admittedTint
        } else if (!adaptive) {
            style.tint
        } else {
            null
        }
        val canProject = derivative != null && keyMatches && intersectsScene && (!adaptive || tint != null)

        drawRect(style.fallback)
        if (canProject) {
            val image = if (derivativeBitmap === derivative) {
                derivativeImage ?: derivative.asImageBitmap().also { derivativeImage = it }
            } else {
                derivative.asImageBitmap().also {
                    derivativeBitmap = derivative
                    derivativeImage = it
                }
            }
            // Draw the complete softened scene translated into this consumer's local coordinates.
            // The explicit local clip prevents an unclipped consumer from painting outside its
            // material. Partially visible panels therefore retain the scene scale instead of
            // stretching a clamped source rectangle across their full bounds.
            clipRect(0f, 0f, size.width, size.height) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(derivative.width, derivative.height),
                    dstOffset = IntOffset(-local.left.roundToInt(), -local.top.roundToInt()),
                    dstSize = IntSize(viewportWidth, viewportHeight),
                )
                if (currentScene?.applyWorkspaceSceneGrade == true) {
                    val scrim = if (sceneScrimBrush == null || sceneScrimTop != local.top ||
                        sceneScrimViewportHeight != viewportHeight
                    ) {
                        workspaceSceneScrimBrush(local.top, viewportHeight.toFloat()).also {
                            sceneScrimBrush = it
                            sceneScrimTop = local.top
                            sceneScrimViewportHeight = viewportHeight
                        }
                    } else {
                        sceneScrimBrush ?: error("Scene scrim cache was unexpectedly empty")
                    }
                    drawRect(scrim, alpha = sceneGradeStrength)
                    drawRect(WorkspaceSceneScrimOverlay, alpha = sceneGradeStrength)
                }
            }
        }
        if (canProject) {
            drawRect(tint ?: style.tint)
        } else if (!adaptive) {
            // Day and semantic roles preserve their established solid fallback composition.
            drawRect(style.tint)
        }
        drawContent()
    }
}

private fun mapBoundsToScene(
    consumer: LayoutCoordinates?,
    scene: WorkspaceGlassScene?,
    allowWindowProjection: Boolean,
): Rect? {
    val host = scene?.rootCoordinates ?: return null
    if (consumer == null || !consumer.isAttached || !host.isAttached) return null
    return try {
        val sameRoot = consumer.findRootCoordinates() === host.findRootCoordinates()
        // Dialogs and popups have distinct Compose roots. Only explicitly opted-in material
        // owners may project the static artwork through screen coordinates; ordinary foreign
        // roots still use the solid fallback. No application content is captured or blurred.
        val bounds = if (sameRoot) {
            host.localBoundingBoxOf(consumer, clipBounds = false)
        } else {
            if (!allowWindowProjection) return null
            val origin = host.localToScreen(Offset.Zero)
            val corners = arrayOf(
                consumer.localToScreen(Offset.Zero) - origin,
                consumer.localToScreen(Offset(consumer.size.width.toFloat(), 0f)) - origin,
                consumer.localToScreen(Offset(0f, consumer.size.height.toFloat())) - origin,
                consumer.localToScreen(Offset(consumer.size.width.toFloat(), consumer.size.height.toFloat())) - origin,
            )
            Rect(corners.minOf { it.x }, corners.minOf { it.y },
                corners.maxOf { it.x }, corners.maxOf { it.y })
        }
        bounds.takeIf {
            it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
                it.width > 0f && it.height > 0f
        }
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun workspaceGlassDerivativeSize(width: Int, height: Int): IntSize {
    require(width > 0 && height > 0)
    val scale = minOf(1f, 512f / maxOf(width, height).toFloat(), kotlin.math.sqrt(160_000f / (width.toFloat() * height)))
    return IntSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()))
}

// Scaled bitmap + three pixel buffers + unpublished output bitmap at peak.
internal fun workspaceGlassTransientBytes(size: IntSize): Long =
    size.width.toLong() * size.height * 4L * 5L +
        WorkspaceGlassSafetyGridColumns.toLong() * WorkspaceGlassSafetyGridRows * Int.SIZE_BYTES

internal fun workspaceGlassSourceRect(
    localBounds: Rect,
    viewportWidth: Int,
    viewportHeight: Int,
    derivativeWidth: Int,
    derivativeHeight: Int,
): IntRect? {
    if (localBounds.width <= 0f || localBounds.height <= 0f || viewportWidth <= 0 || viewportHeight <= 0) return null
    val left = floor(localBounds.left.coerceIn(0f, viewportWidth.toFloat()) / viewportWidth * derivativeWidth).toInt()
    val top = floor(localBounds.top.coerceIn(0f, viewportHeight.toFloat()) / viewportHeight * derivativeHeight).toInt()
    val right = ceil(localBounds.right.coerceIn(0f, viewportWidth.toFloat()) / viewportWidth * derivativeWidth).toInt()
    val bottom = ceil(localBounds.bottom.coerceIn(0f, viewportHeight.toFloat()) / viewportHeight * derivativeHeight).toInt()
    return if (right > left && bottom > top) IntRect(left, top, right, bottom) else null
}

internal fun workspaceGlassCanPublish(
    liveKey: WorkspaceBackgroundRenderKey?,
    liveEpoch: Long,
    liveBitmap: Bitmap?,
    candidate: DisplayedSceneFrame,
): Boolean = workspaceGlassIdentityCanPublish(liveKey, liveEpoch, candidate.actualKey, candidate.epoch) &&
    liveBitmap === candidate.clearBitmap

internal fun workspaceGlassIdentityCanPublish(
    liveKey: WorkspaceBackgroundRenderKey?,
    liveEpoch: Long,
    candidateKey: WorkspaceBackgroundRenderKey,
    candidateEpoch: Long,
): Boolean = liveKey == candidateKey && liveEpoch == candidateEpoch

private data class WorkspaceGlassAdmissionKey(
    val derivativeKey: WorkspaceGlassDerivativeKey,
    val role: WorkspaceGlassRole,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val applySceneGrade: Boolean,
)

internal fun workspaceGlassAdmittedTintAlpha(
    grid: WorkspaceGlassSafetyGrid,
    derivativeKey: WorkspaceGlassDerivativeKey,
    boundsInScene: Rect,
    viewportWidth: Int,
    viewportHeight: Int,
    role: WorkspaceGlassRole,
    style: WorkspaceGlassRoleStyle = workspaceGlassRoleStyle(role, day = false),
    applySceneGrade: Boolean = true,
): Float? {
    if (viewportWidth <= 0 || viewportHeight <= 0 || role !in WorkspaceGlassAdaptiveNightRoles) return null
    val source = workspaceGlassSourceRect(
        boundsInScene,
        viewportWidth,
        viewportHeight,
        derivativeKey.width,
        derivativeKey.height,
    ) ?: return null
    val firstColumn = floor(source.left.toFloat() / derivativeKey.width * grid.columns).toInt()
        .coerceIn(0, grid.columns - 1)
    val lastColumn = ceil(source.right.toFloat() / derivativeKey.width * grid.columns).toInt()
        .minus(1).coerceIn(firstColumn, grid.columns - 1)
    val firstRow = floor(source.top.toFloat() / derivativeKey.height * grid.rows).toInt()
        .coerceIn(0, grid.rows - 1)
    val lastRow = ceil(source.bottom.toFloat() / derivativeKey.height * grid.rows).toInt()
        .minus(1).coerceIn(firstRow, grid.rows - 1)
    val floor = when (role) {
        in WorkspaceGlassNeutralNightRoles -> WorkspaceGlassNeutralNightTintFloor
        WorkspaceGlassRole.MODAL -> .60f
        else -> return null
    }
    // Foreground luminance is invariant across every sampled cell and alpha candidate.
    val primaryLuminance = style.primaryContent.luminance()
    val secondaryLuminance = style.secondaryContent.luminance()
    val iconLuminance = style.iconContent.luminance()
    return WorkspaceGlassAdaptiveAlphaCandidates.firstOrNull { alpha ->
        alpha >= floor && (firstRow..lastRow).all { row ->
            val rowTop = maxOf(boundsInScene.top / viewportHeight, row.toFloat() / grid.rows).coerceIn(0f, 1f)
            val rowBottom = minOf(boundsInScene.bottom / viewportHeight, (row + 1f) / grid.rows).coerceIn(0f, 1f)
            (firstColumn..lastColumn).all { column ->
                val scene = grid.colorAt(column, row)
                fun passesAt(y: Float): Boolean {
                    val graded = workspaceGlassCompleteSceneColor(
                        scene = scene,
                        role = role,
                        normalizedY = y,
                        applySceneGrade = applySceneGrade,
                    )
                    val material = Color(0xFF05060B).copy(alpha = alpha).compositeOver(graded)
                    val materialLuminance = material.luminance()
                    return workspaceGlassContrastRatio(primaryLuminance, materialLuminance) >= 4.8 &&
                        workspaceGlassContrastRatio(secondaryLuminance, materialLuminance) >= 4.8 &&
                        workspaceGlassContrastRatio(iconLuminance, materialLuminance) >= 3.2
                }
                passesAt(rowTop) && passesAt(rowBottom) &&
                    (!(rowTop <= .45f && rowBottom >= .45f) || passesAt(.45f))
            }
        }
    }
}

internal fun workspaceGlassCompleteSceneColor(
    scene: Color,
    role: WorkspaceGlassRole,
    normalizedY: Float,
    applySceneGrade: Boolean,
): Color {
    if (!applySceneGrade) return scene
    val y = normalizedY.coerceIn(0f, 1f)
    val scrimAlpha = if (y <= .45f) {
        .44f + (.26f - .44f) * (y / .45f)
    } else {
        .26f + (.60f - .26f) * ((y - .45f) / .55f)
    }
    val strength = workspaceGlassSceneGradeStrength(role, day = false)
    val scrimmed = Color.Black.copy(alpha = scrimAlpha * strength).compositeOver(scene)
    return Color.Black.copy(alpha = WorkspaceSceneScrimOverlay.alpha * strength).compositeOver(scrimmed)
}

internal fun workspaceGlassContrastRatio(foreground: Color, background: Color): Double =
    workspaceGlassContrastRatio(foreground.luminance(), background.luminance())

private fun workspaceGlassContrastRatio(foregroundLuminance: Float, backgroundLuminance: Float): Double {
    val light = maxOf(foregroundLuminance, backgroundLuminance).toDouble()
    val dark = minOf(foregroundLuminance, backgroundLuminance).toDouble()
    return (light + .05) / (dark + .05)
}

