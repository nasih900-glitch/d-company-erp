package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.WorkspacePanelShape
import cloud.dcompany.erp.ui.theme.workspaceGlass

internal val LocalLiquidPopupRoot = staticCompositionLocalOf<android.view.View?> { null }

/**
 * Binds decorative glass descendants to the popup window that owns them. The owner is read from
 * inside the popup subcomposition: inheriting this value across another raw window cannot opt that
 * window in because [liquidWorkspaceSurface] also checks the current root by identity.
 */
@Composable
internal fun LiquidPopupArtworkScope(content: @Composable () -> Unit) {
    val popupRoot = LocalView.current.rootView
    CompositionLocalProvider(LocalLiquidPopupRoot provides popupRoot, content = content)
}

/** Shared surfaces sample the same background, so nested panels never stack opaque fills. */
@Composable
internal fun Modifier.liquidWorkspaceSurface(
    shape: Shape = WorkspacePanelShape,
    role: WorkspaceGlassRole = WorkspaceGlassRole.PANEL,
    allowWindowProjection: Boolean = false,
): Modifier {
    val scene = LocalWorkspaceGlassScene.current
    val popupRoot = LocalLiquidPopupRoot.current
    val popupOwnsCurrentRoot = popupRoot != null && popupRoot === LocalView.current.rootView
    val popupNeutralRole = role == WorkspaceGlassRole.PANEL ||
        role == WorkspaceGlassRole.UTILITY ||
        role == WorkspaceGlassRole.CONTROL
    val effectiveWindowProjection = allowWindowProjection ||
        (popupOwnsCurrentRoot && popupNeutralRole)
    return if (scene != null) {
        workspaceGlass(
            scene,
            role,
            MaterialTheme.colorScheme.background.luminance() > .5f,
            shape,
            effectiveWindowProjection,
        )
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    }
}

/** Keep workspace artwork visible while retaining a solid canvas outside the workspace host. */
@Composable
internal fun Modifier.workspaceContentCanvas(): Modifier =
    if (LocalWorkspaceGlassScene.current != null) this
    else background(MaterialTheme.colorScheme.background)
