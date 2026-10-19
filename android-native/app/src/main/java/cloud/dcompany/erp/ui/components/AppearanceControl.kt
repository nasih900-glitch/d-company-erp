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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

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
        AppearancePreference.System -> "System · ${if (resolvedNight) "Night" else "Day"}"
    }
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = Radius.shapePill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = modifier.heightIn(min = 48.dp).clickable { expanded = true }
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
