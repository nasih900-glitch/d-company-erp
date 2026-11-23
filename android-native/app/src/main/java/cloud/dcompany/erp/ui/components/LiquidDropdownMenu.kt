package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu as MaterialDropdownMenu
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole

/**
 * Keeps Material's popup ownership while projecting the workspace scene into
 * the popup window. Outside a workspace scene every Material default and any
 * caller-provided fallback color remain unchanged.
 */
@Composable
internal fun LiquidDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    role: WorkspaceGlassRole = WorkspaceGlassRole.MODAL,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalWorkspaceGlassScene.current != null
    MaterialDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = if (glass) {
            modifier.clip(shape).liquidWorkspaceSurface(
                shape = shape,
                role = role,
                allowWindowProjection = true,
            )
        } else {
            modifier
        },
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = if (glass) Color.Transparent else containerColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        shadowElevation = if (glass) 0.dp else shadowElevation,
        border = if (glass) null else border,
        content = { LiquidPopupArtworkScope { content() } },
    )
}
