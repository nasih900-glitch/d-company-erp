package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole

@Composable
internal fun AppearanceControl(
    preference: AppearancePreference,
    onChange: (AppearancePreference) -> Unit,
    modifier: Modifier = Modifier,
    compactLabel: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val resolvedNight = preference == AppearancePreference.Night ||
        (preference == AppearancePreference.System && isSystemInDarkTheme())
    val label = when (preference) {
        AppearancePreference.Day -> if (compactLabel) "Day" else "Day mode"
        AppearancePreference.Night -> if (compactLabel) "Night" else "Night mode"
        AppearancePreference.System -> if (compactLabel) {
            "System"
        } else {
            "System · ${if (resolvedNight) "Night" else "Day"}"
        }
    }
    Box {
        val glass = LocalWorkspaceGlassScene.current != null
        Surface(
            color = if (glass) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
            shape = Radius.shapePill,
            border = if (glass) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = modifier
                .then(
                    if (glass) Modifier.clip(Radius.shapePill)
                        .liquidWorkspaceSurface(Radius.shapePill, WorkspaceGlassRole.CONTROL)
                    else Modifier,
                )
                .heightIn(min = 48.dp).clickable { expanded = true }
                .semantics {
                    role = Role.Button
                    contentDescription = "Day, night and system appearance"
                    stateDescription = label
                },
        ) {
            Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(Icons.Filled.Brightness6, null, Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
        LiquidDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            role = WorkspaceGlassRole.CONTROL,
        ) {
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
