package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.PickerField
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
internal fun BugReportDialog(
    state: BugReportUiState,
    connectivity: BugReportConnectivity,
    onCategoryChange: (BugReportCategory) -> Unit,
    onSeverityChange: (BugReportSeverity) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onReproductionStepsChange: (String) -> Unit,
    onExpectedBehaviorChange: (String) -> Unit,
    onActualBehaviorChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.isOpen) return
    val success = state.success
    AlertDialog(
        onDismissRequest = { if (!state.submitting) onDismiss() },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.96f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        icon = {
            androidx.compose.material3.Icon(
                imageVector = if (success == null) Icons.Default.ReportProblem else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (success == null) Brand.Gold else Brand.Good,
            )
        },
        title = {
            Text(
                if (success == null) "Report a problem" else "Report sent",
                color = Brand.Foreground,
            )
        },
        text = {
            if (success != null) {
                BugReportSuccess(success)
            } else {
                BugReportForm(
                    state = state,
                    connectivity = connectivity,
                    onCategoryChange = onCategoryChange,
                    onSeverityChange = onSeverityChange,
                    onTitleChange = onTitleChange,
                    onDescriptionChange = onDescriptionChange,
                    onReproductionStepsChange = onReproductionStepsChange,
                    onExpectedBehaviorChange = onExpectedBehaviorChange,
                    onActualBehaviorChange = onActualBehaviorChange,
                )
            }
        },
        confirmButton = {
            if (success != null) {
                ErpButton(text = "Done", onClick = onDismiss)
            } else {
                ErpButton(
                    text = "Send report",
                    onClick = onSubmit,
                    enabled = !state.submitting,
                    busy = state.submitting,
                    leadingIcon = Icons.Default.ReportProblem,
                )
            }
        },
        dismissButton = {
            if (success == null) {
                TextButton(onClick = onDismiss, enabled = !state.submitting) {
                    Text("Keep draft and close")
                }
            }
        },
    )
}

@Composable
private fun BugReportSuccess(result: BugReportCreateResponse) {
    val reference = result.id.take(8).uppercase()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OperationalBanner(
            title = "Saved to the ERP inbox",
            detail = "Reference $reference • Status ${result.status.replace('_', ' ')}",
            tone = UiTone.Success,
            icon = Icons.Default.CheckCircle,
        )
        Text(
            "The report is now available to authorised staff in the web ERP. You can safely close this window.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BugReportForm(
    state: BugReportUiState,
    connectivity: BugReportConnectivity,
    onCategoryChange: (BugReportCategory) -> Unit,
    onSeverityChange: (BugReportSeverity) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onReproductionStepsChange: (String) -> Unit,
    onExpectedBehaviorChange: (String) -> Unit,
    onActualBehaviorChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())
            .padding(end = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when (connectivity) {
            BugReportConnectivity.Offline -> OperationalBanner(
                title = "Offline — draft stays on this screen",
                detail = "Reconnect before sending. Closing this window will keep what you typed until you sign out or the app closes.",
                tone = UiTone.Warning,
                icon = Icons.Default.CloudOff,
            )
            BugReportConnectivity.Unknown -> OperationalBanner(
                title = "ERP connection is not confirmed",
                detail = "Internet may be available, but the ERP server has not answered. You can finish the draft now; sending will retry the connection.",
                tone = UiTone.Information,
                icon = Icons.Default.Info,
            )
            BugReportConnectivity.Online -> Unit
        }
        state.error?.let { message ->
            OperationalBanner(
                title = "Report not sent",
                detail = message,
                tone = UiTone.Danger,
                icon = Icons.Default.ReportProblem,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    CategoryPicker(
                        state.draft.category,
                        Modifier.weight(1f),
                        enabled = !state.submitting,
                        onSelect = onCategoryChange,
                    )
                    SeverityPicker(
                        state.draft.severity,
                        Modifier.weight(1f),
                        enabled = !state.submitting,
                        onSelect = onSeverityChange,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    CategoryPicker(
                        state.draft.category,
                        Modifier.fillMaxWidth(),
                        enabled = !state.submitting,
                        onSelect = onCategoryChange,
                    )
                    SeverityPicker(
                        state.draft.severity,
                        Modifier.fillMaxWidth(),
                        enabled = !state.submitting,
                        onSelect = onSeverityChange,
                    )
                }
            }
        }

        BugReportTextField(
            label = "Short title",
            value = state.draft.title,
            onValueChange = onTitleChange,
            minimum = BUG_REPORT_TITLE_MIN_LENGTH,
            maximum = BUG_REPORT_TITLE_MAX_LENGTH,
            error = state.validation.title,
            singleLine = true,
            enabled = !state.submitting,
        )
        BugReportTextField(
            label = "What happened?",
            value = state.draft.description,
            onValueChange = onDescriptionChange,
            minimum = BUG_REPORT_DESCRIPTION_MIN_LENGTH,
            maximum = BUG_REPORT_DETAIL_MAX_LENGTH,
            error = state.validation.description,
            enabled = !state.submitting,
        )
        BugReportTextField(
            label = "Steps to reproduce (optional)",
            value = state.draft.reproductionSteps,
            onValueChange = onReproductionStepsChange,
            maximum = BUG_REPORT_DETAIL_MAX_LENGTH,
            error = state.validation.reproductionSteps,
            enabled = !state.submitting,
        )
        BugReportTextField(
            label = "What did you expect? (optional)",
            value = state.draft.expectedBehavior,
            onValueChange = onExpectedBehaviorChange,
            maximum = BUG_REPORT_DETAIL_MAX_LENGTH,
            error = state.validation.expectedBehavior,
            enabled = !state.submitting,
        )
        BugReportTextField(
            label = "What actually happened? (optional)",
            value = state.draft.actualBehavior,
            onValueChange = onActualBehaviorChange,
            maximum = BUG_REPORT_DETAIL_MAX_LENGTH,
            error = state.validation.actualBehavior,
            enabled = !state.submitting,
        )

        OperationalBanner(
            title = "Safe diagnostics included automatically",
            detail = "App and Android versions, device model, Settings screen, branch/till labels, and connectivity only. No password, login token, customer/order content, screenshot, or log dump is attached.",
            tone = UiTone.Information,
            icon = Icons.Default.Info,
        )
        Text(
            "Do not type passwords, card details, customer information, or other private data into this report.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CategoryPicker(
    selected: BugReportCategory,
    modifier: Modifier,
    enabled: Boolean,
    onSelect: (BugReportCategory) -> Unit,
) {
    PickerField(
        label = "Category",
        selectedLabel = selected.label,
        options = BugReportCategory.entries.map { it.wireValue to it.label },
        modifier = modifier,
        enabled = enabled,
        onSelect = { value ->
            BugReportCategory.entries.firstOrNull { it.wireValue == value }?.let(onSelect)
        },
    )
}

@Composable
private fun SeverityPicker(
    selected: BugReportSeverity,
    modifier: Modifier,
    enabled: Boolean,
    onSelect: (BugReportSeverity) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PickerField(
            label = "Severity",
            selectedLabel = selected.label,
            options = BugReportSeverity.entries.map { it.wireValue to it.label },
            enabled = enabled,
            onSelect = { value ->
                BugReportSeverity.entries.firstOrNull { it.wireValue == value }?.let(onSelect)
            },
        )
        Text(
            selected.guidance,
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BugReportTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    maximum: Int,
    error: String?,
    minimum: Int? = null,
    singleLine: Boolean = false,
    enabled: Boolean,
) {
    val guidance = error ?: buildString {
        if (minimum != null) append("Minimum $minimum • ")
        append("${value.length} / $maximum")
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 6,
        isError = error != null,
        supportingText = {
            Text(
                guidance,
                modifier = if (error != null) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                } else {
                    Modifier
                },
            )
        },
        keyboardOptions = KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        shape = Radius.shapeMd,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
