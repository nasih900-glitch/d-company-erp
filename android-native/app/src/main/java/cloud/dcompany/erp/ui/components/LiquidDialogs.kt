package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog as MaterialDatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole

/** Material owns focus, dismissal and accessibility; only its surface treatment changes. */
@Composable
internal fun LiquidAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val glass = LocalWorkspaceGlassScene.current != null
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { LiquidPopupArtworkScope { confirmButton() } },
        modifier = if (glass) modifier.clip(shape).liquidWorkspaceSurface(
            shape, WorkspaceGlassRole.MODAL, allowWindowProjection = true,
        ) else modifier,
        dismissButton = dismissButton?.let { content ->
            { LiquidPopupArtworkScope { content() } }
        },
        icon = icon?.let { content ->
            { LiquidPopupArtworkScope { content() } }
        },
        title = title?.let { content ->
            { LiquidPopupArtworkScope { content() } }
        },
        text = text?.let { content ->
            { LiquidPopupArtworkScope { content() } }
        },
        shape = shape,
        containerColor = if (glass) Color.Transparent else containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        properties = properties,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiquidDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape = DatePickerDefaults.shape,
    tonalElevation: Dp = DatePickerDefaults.TonalElevation,
    colors: DatePickerColors = DatePickerDefaults.colors(),
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalWorkspaceGlassScene.current != null
    MaterialDatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { LiquidPopupArtworkScope { confirmButton() } },
        modifier = if (glass) modifier.clip(shape).liquidWorkspaceSurface(
            shape, WorkspaceGlassRole.MODAL, allowWindowProjection = true,
        ) else modifier,
        dismissButton = dismissButton?.let { content ->
            { LiquidPopupArtworkScope { content() } }
        },
        shape = shape,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        colors = if (glass) colors.copy(containerColor = Color.Transparent) else colors,
        properties = properties,
        content = { LiquidPopupArtworkScope { content() } },
    )
}

/** Share the dialog material instead of painting an opaque calendar inside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun liquidCalendarColors(): DatePickerColors {
    val base = DatePickerDefaults.colors()
    return if (LocalWorkspaceGlassScene.current != null) base.copy(containerColor = Color.Transparent) else base
}
