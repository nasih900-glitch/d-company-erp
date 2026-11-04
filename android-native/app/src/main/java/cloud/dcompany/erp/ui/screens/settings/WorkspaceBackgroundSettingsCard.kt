package cloud.dcompany.erp.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundPresets
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSelectionKind
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState

@Composable
internal fun WorkspaceBackgroundSettingsCard(
    state: WorkspaceBackgroundUiState,
    onImport: (WorkspaceBackgroundSlot, Uri) -> Unit,
    onReset: (WorkspaceBackgroundSlot) -> Unit,
    onSelectPreset: (String) -> Unit = {},
    onUseSavedCustom: () -> Unit = {},
    onCancelOperation: () -> Unit,
    onClearNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A picker restored from the former two-slot UI may still contain HOME. Both values now
    // normalize to the single canonical WORKSPACE mutation; no legacy filename is exposed.
    var pendingSlotName by rememberWorkspaceBackgroundPendingSlot()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val hadKnownSlot = WorkspaceBackgroundSlot.entries.any { it.name == pendingSlotName }
        pendingSlotName = null
        if (uri != null && hadKnownSlot) onImport(WorkspaceBackgroundSlot.WORKSPACE, uri)
    }
    val image = state.workspace
    val busy = state.busySlot != null
    val selectedPresetId = state.selection.presetId
        .takeIf { state.selection.kind == WorkspaceBackgroundSelectionKind.PRESET }
    val activeLabel = when (state.selection.kind) {
        WorkspaceBackgroundSelectionKind.PRESET ->
            WorkspaceBackgroundPresets.find(selectedPresetId)?.label ?: WorkspaceBackgroundPresets.default.label
        WorkspaceBackgroundSelectionKind.CUSTOM_WORKSPACE -> "Saved custom image"
        WorkspaceBackgroundSelectionKind.CUSTOM_LEGACY_HOME -> "Previous Home image"
    }

    SectionCard(
        title = "Workspace background",
        subtitle = "One image across Home and every workspace screen on this tablet.",
        icon = Icons.Default.Image,
        modifier = modifier.testTag("workspace-background-settings"),
    ) {
        Text(
            "Active: $activeLabel · ${image.dimensionsLabel}",
            modifier = Modifier.testTag("workspace-background-active-source"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.initializing) {
            Text(
                "Checking the saved workspace image…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("workspace-background-initializing"),
            )
        }

        Text("Game gallery", style = MaterialTheme.typography.titleSmall)
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("workspace-background-preset-gallery"),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(WorkspaceBackgroundPresets.entries, key = { it.id }) { preset ->
                val selected = selectedPresetId == preset.id
                Surface(
                    modifier = Modifier.width(224.dp)
                        .testTag("workspace-background-preset-${preset.id}"),
                    shape = Radius.shapeMd,
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                    tonalElevation = if (selected) 3.dp else 0.dp,
                ) {
                    Column(
                        Modifier.padding(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        WorkspaceBackgroundPresetPreview(preset)
                        Text(preset.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            preset.assetType,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ErpButton(
                            text = if (selected) "Selected" else "Use image",
                            onClick = {
                                onClearNotice()
                                onSelectPreset(preset.id)
                            },
                            enabled = !busy && !state.initializing && !selected,
                            intent = if (selected) ActionIntent.Primary else ActionIntent.Secondary,
                            modifier = Modifier
                                .testTag("workspace-background-select-${preset.id}")
                                .semantics { this.selected = selected },
                        )
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactActions = maxWidth < 720.dp
            @Composable fun Actions() {
            ErpButton(
                text = when {
                    busy && state.cancelling -> "Cancelling"
                    busy -> "Changing"
                    else -> "Choose custom image"
                },
                onClick = {
                    onClearNotice()
                    pendingSlotName = WorkspaceBackgroundSlot.WORKSPACE.name
                    picker.launch(arrayOf("image/jpeg", "image/png"))
                },
                enabled = !busy && !state.initializing,
                busy = busy && !state.cancelling,
                intent = ActionIntent.Secondary,
                modifier = Modifier.testTag("workspace-background-change-workspace"),
            )
            if (state.savedCustomAvailable &&
                state.selection.kind == WorkspaceBackgroundSelectionKind.PRESET
            ) {
                ErpButton(
                    text = "Use saved custom",
                    onClick = onUseSavedCustom,
                    enabled = !busy && !state.initializing,
                    intent = ActionIntent.Secondary,
                    modifier = Modifier.testTag("workspace-background-use-saved-custom"),
                )
            }
            if (selectedPresetId != WorkspaceBackgroundPresets.DefaultId) {
                ErpButton(
                    text = "Use default",
                    onClick = { onReset(WorkspaceBackgroundSlot.WORKSPACE) },
                    enabled = !busy && !state.initializing,
                    intent = ActionIntent.Quiet,
                    modifier = Modifier.testTag("workspace-background-reset-workspace"),
                )
            }
            if (busy && !state.cancelling) {
                ErpButton(
                    text = "Cancel",
                    onClick = onCancelOperation,
                    intent = ActionIntent.Quiet,
                    modifier = Modifier.testTag("workspace-background-cancel"),
                )
            }
            }
            if (compactActions) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) { Actions() }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Actions() }
            }
        }
        Text(
            "Custom images stay private on this tablet. Choosing a gallery image does not delete a saved custom image.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("workspace-background-privacy-copy"),
        )
        state.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("workspace-background-message"),
            )
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("workspace-background-error"),
            )
        }
    }
}

@Composable
internal fun rememberWorkspaceBackgroundPendingSlot(): MutableState<String?> =
    rememberSaveable { mutableStateOf(null) }
