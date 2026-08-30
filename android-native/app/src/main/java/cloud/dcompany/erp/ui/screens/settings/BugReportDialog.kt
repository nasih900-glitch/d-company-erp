package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import cloud.dcompany.erp.core.db.BugReportOutboxState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.remote.RemoteSensitiveContent
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
internal fun BugReportDialog(
    state: BugReportUiState,
    connectivity: BugReportConnectivity,
    onReasonChange: (SupportRequestReason) -> Unit,
    onContinuationChange: (WorkContinuation) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAttachmentChange: (BugReportAttachmentDraft?) -> Unit,
    onAttachmentRejected: (String) -> Unit,
    onAttachmentConsentChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    onRefreshHistory: () -> Unit,
    onRetryHistoryItem: (String) -> Unit,
    onDiscardHistoryItem: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.isOpen) return
    RemoteSensitiveContent {
        var pendingDiscardIdentity by remember { mutableStateOf<String?>(null) }
        val submitted = state.submittedLocalId != null
        val actionRequired = state.submittedState == BugReportOutboxState.ACTION_REQUIRED ||
            state.submittedAttachmentState == BugReportOutboxState.ACTION_REQUIRED
        AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.96f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        icon = {
            Icon(
                imageVector = when {
                    state.showingHistory -> Icons.Default.History
                    submitted -> Icons.Default.CheckCircle
                    else -> Icons.Default.ReportProblem
                },
                contentDescription = null,
                tint = if (submitted) Brand.Good else Brand.Gold,
            )
        },
        title = {
            Text(
                text = when {
                    state.showingHistory -> "My help requests"
                    submitted -> "Help request saved"
                    else -> "Help & support"
                },
                color = Brand.Foreground,
            )
        },
        text = {
            if (state.showingHistory) {
                SupportHistory(
                    state = state,
                    onRefresh = onRefreshHistory,
                    onRetry = onRetryHistoryItem,
                    onRequestDiscard = { pendingDiscardIdentity = it },
                )
            } else if (submitted) {
                SubmissionReceipt(state, connectivity)
            } else {
                BugReportForm(
                    state = state,
                    connectivity = connectivity,
                    onReasonChange = onReasonChange,
                    onContinuationChange = onContinuationChange,
                    onDescriptionChange = onDescriptionChange,
                    onAttachmentChange = onAttachmentChange,
                    onAttachmentRejected = onAttachmentRejected,
                    onAttachmentConsentChange = onAttachmentConsentChange,
                    onOpenHistory = onOpenHistory,
                )
            }
        },
        confirmButton = {
            when {
                state.showingHistory -> ErpButton(text = "Back", onClick = onCloseHistory)
                !submitted -> ErpButton(
                    text = "Send to owner",
                    onClick = onSubmit,
                    enabled = !state.saving,
                    busy = state.saving,
                    leadingIcon = Icons.Default.ReportProblem,
                )
                actionRequired -> ErpButton(
                    text = "Retry send",
                    onClick = onRetry,
                    enabled = !state.saving,
                    busy = state.saving,
                )
                else -> ErpButton(text = "Done", onClick = onDismiss)
            }
        },
        dismissButton = {
            if (!state.showingHistory && !submitted) {
                TextButton(onClick = onDismiss, enabled = !state.saving) {
                    Text("Cancel")
                }
            } else if (actionRequired) {
                TextButton(onClick = onDismiss, enabled = !state.saving) {
                    Text("Close")
                }
            }
        },
        )
        pendingDiscardIdentity?.let { identity ->
            AlertDialog(
            onDismissRequest = { pendingDiscardIdentity = null },
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            title = { Text("Remove saved tablet copy?", color = Brand.Foreground) },
            text = {
                Text(
                    "First check the owner's web Support inbox in case this request already arrived. " +
                        "Removing stops future retries and erases any saved description or image from this tablet. " +
                        "It does not delete a report that the server already received.",
                    color = Brand.ForegroundMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDiscardHistoryItem(identity)
                        pendingDiscardIdentity = null
                    },
                ) {
                    Text("Remove saved copy", color = Brand.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscardIdentity = null }) { Text("Keep it") }
            },
            )
        }
    }
}

@Composable
private fun SubmissionReceipt(
    state: BugReportUiState,
    connectivity: BugReportConnectivity,
) {
    val reference = state.submittedLocalId.orEmpty().take(8).uppercase()
    val (title, detail, tone, icon) = when {
        state.submittedState == BugReportOutboxState.SENT &&
            state.submittedAttachmentState == BugReportOutboxState.ACTION_REQUIRED -> Quadruple(
            "Report delivered · image needs attention",
            state.submittedAttachmentError
                ?: "The report is safe in the owner inbox, but the selected image was refused.",
            UiTone.Warning,
            Icons.Default.ReportProblem,
        )
        state.submittedState == BugReportOutboxState.SENT &&
            state.submittedAttachmentState == BugReportOutboxState.PENDING -> Quadruple(
            "Report delivered · image is still sending",
            "Reference $reference · You can close this window; the image will retry automatically.",
            UiTone.Information,
            Icons.Default.Schedule,
        )
        state.submittedState == BugReportOutboxState.SENT -> Quadruple(
            "Delivered to the owner inbox",
            "Reference $reference · Status ${state.submittedServerStatus.orEmpty().ifBlank { "open" }}",
            UiTone.Success,
            Icons.Default.CloudDone,
        )
        state.submittedState == BugReportOutboxState.ACTION_REQUIRED -> Quadruple(
            "Saved, but needs attention",
            state.submittedError ?: "The server refused this request. Retry after the problem is corrected.",
            UiTone.Danger,
            Icons.Default.ReportProblem,
        )
        state.submittedState == BugReportOutboxState.DISCARDED -> Quadruple(
            "Saved tablet copy removed",
            "Automatic retries stopped. A report already received by the server remains in the owner's inbox.",
            UiTone.Information,
            Icons.Default.Info,
        )
        else -> Quadruple(
            if (connectivity == BugReportConnectivity.Offline) "Saved safely while offline" else "Sending to the owner",
            "Reference $reference · It will send automatically and cannot be duplicated.",
            if (connectivity == BugReportConnectivity.Offline) UiTone.Warning else UiTone.Information,
            if (connectivity == BugReportConnectivity.Offline) Icons.Default.CloudOff else Icons.Default.Schedule,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OperationalBanner(title = title, detail = detail, tone = tone, icon = icon)
        Text(
            "The issue time and screen were preserved. You can close this window and continue working.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BugReportForm(
    state: BugReportUiState,
    connectivity: BugReportConnectivity,
    onReasonChange: (SupportRequestReason) -> Unit,
    onContinuationChange: (WorkContinuation) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onAttachmentChange: (BugReportAttachmentDraft?) -> Unit,
    onAttachmentRejected: (String) -> Unit,
    onAttachmentConsentChange: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachmentLoading by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachmentLoading = true
            scope.launch {
                try {
                    when (val result = loadBugReportAttachment(context, uri)) {
                        is BugReportAttachmentLoadResult.Ready -> onAttachmentChange(result.attachment)
                        is BugReportAttachmentLoadResult.Rejected -> onAttachmentRejected(result.message)
                    }
                } finally {
                    attachmentLoading = false
                }
            }
        }
    }
    Column(
        modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(end = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.recentRequests.isNotEmpty()) {
            Surface(
                color = Brand.Surface,
                shape = Radius.shapeMd,
                border = BorderStroke(1.dp, Brand.BorderSubtle),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenHistory),
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Brand.ForegroundMuted)
                    Text(
                        "My help requests (${state.recentRequests.size})",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.pendingCount > 0) {
                        Text(
                            "${state.pendingCount} waiting",
                            color = Brand.Gold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        if (connectivity != BugReportConnectivity.Online) {
            OperationalBanner(
                title = if (connectivity == BugReportConnectivity.Offline) {
                    "Offline — you can still send this"
                } else {
                    "ERP connection is not confirmed"
                },
                detail = "The request will be saved on this tablet and delivered automatically when the server is available.",
                tone = if (connectivity == BugReportConnectivity.Offline) UiTone.Warning else UiTone.Information,
                icon = if (connectivity == BugReportConnectivity.Offline) Icons.Default.CloudOff else Icons.Default.Info,
            )
        }
        state.error?.let { message ->
            OperationalBanner(
                title = "Request not saved",
                detail = message,
                tone = UiTone.Danger,
                icon = Icons.Default.ReportProblem,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        Text(
            "What do you need help with?",
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 620.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SupportRequestReason.entries.forEach { reason ->
                        ReasonChoice(
                            reason = reason,
                            selected = state.draft.reason == reason,
                            enabled = !state.saving,
                            modifier = Modifier.weight(1f),
                            onClick = { onReasonChange(reason) },
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SupportRequestReason.entries.forEach { reason ->
                        ReasonChoice(
                            reason = reason,
                            selected = state.draft.reason == reason,
                            enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onReasonChange(reason) },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.draft.description,
            onValueChange = onDescriptionChange,
            label = { Text("Tell us what happened") },
            placeholder = { Text("Example: I tapped Send to POS, but the session stayed here.") },
            enabled = !state.saving,
            minLines = 3,
            maxLines = 5,
            isError = state.validation.description != null,
            supportingText = {
                Text(
                    state.validation.description
                        ?: "${state.draft.description.length} / $BUG_REPORT_DETAIL_MAX_LENGTH",
                    modifier = if (state.validation.description != null) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    } else Modifier,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            shape = Radius.shapeMd,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Can you continue working?",
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            WorkContinuation.entries.forEach { option ->
                ContinuationChoice(
                    option = option,
                    selected = state.draft.canContinue == option,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                    onClick = { onContinuationChange(option) },
                )
            }
        }

        OperationalBanner(
            title = "Context added automatically",
            detail = buildString {
                append(state.launchContext.currentScreen)
                state.launchContext.lastAction?.let { append(" · $it") }
                state.launchContext.errorCode?.let { append(" · Code $it") }
                append(" · ${connectivity.wireValue}")
            },
            tone = UiTone.Information,
            icon = Icons.Default.Info,
        )

        Text(
            "Screenshot (optional)",
            color = Brand.Foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.attachment == null) {
            Surface(
                color = Brand.Surface,
                shape = Radius.shapeMd,
                border = BorderStroke(1.dp, Brand.BorderSubtle),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(enabled = !state.saving && !attachmentLoading) {
                        imagePicker.launch("image/*")
                    },
            ) {
                Row(
                    Modifier.padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (attachmentLoading) {
                        CircularProgressIndicator(
                            color = Brand.Gold,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = Brand.ForegroundMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (attachmentLoading) "Preparing safe preview…" else "Choose an image",
                            color = Brand.Foreground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            "PNG, JPEG or WebP · choose up to 12 MiB · compressed below 2 MiB",
                            color = Brand.ForegroundFaint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            val preview = remember(state.attachment.content) {
                decodeBugReportPreview(state.attachment.content)
            }
            Surface(
                color = Brand.Surface,
                shape = Radius.shapeMd,
                border = BorderStroke(1.dp, Brand.BorderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = "Selected support screenshot preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Safe preview · ${state.attachment.byteSize / 1024} KiB",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onAttachmentChange(null) }, enabled = !state.saving) {
                            Text("Remove image")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = state.attachmentConsent,
                            enabled = !state.saving,
                            role = Role.Checkbox,
                            onValueChange = onAttachmentConsentChange,
                        ).padding(vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = state.attachmentConsent,
                            onCheckedChange = null,
                            enabled = !state.saving,
                        )
                        Text(
                            "I reviewed this image. It contains no password, payment or customer information.",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        state.attachmentError?.let { message ->
            OperationalBanner(
                title = "Image not ready",
                detail = message,
                tone = UiTone.Warning,
                icon = Icons.Default.PhotoCamera,
            )
        }
        Text(
            "Included: app/device version, current screen, safe action/error code, shop context and connectivity. " +
                "Never included automatically: passwords, login tokens, customer/order content, payment details, logs or screen images.",
            color = Brand.ForegroundFaint,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SupportHistory(
    state: BugReportUiState,
    onRefresh: () -> Unit,
    onRetry: (String) -> Unit,
    onRequestDiscard: (String) -> Unit,
) {
    Column(
        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Your reports and owner replies",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRefresh, enabled = !state.refreshingHistory) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (state.refreshingHistory) "Refreshing…" else "Refresh")
            }
        }
        state.historyError?.let {
            OperationalBanner(
                title = "Could not refresh",
                detail = it,
                tone = UiTone.Warning,
                icon = Icons.Default.CloudOff,
            )
        }
        if (state.recentRequests.isEmpty()) {
            OperationalBanner(
                title = if (state.refreshingHistory) "Checking for requests" else "No help requests yet",
                detail = if (state.refreshingHistory) {
                    "Saved requests will appear here in a moment."
                } else {
                    "Return and send a request when something fails or you need guidance."
                },
                tone = UiTone.Information,
                icon = Icons.Default.Info,
            )
        } else {
            state.recentRequests.forEach { request ->
                Surface(
                    color = Brand.Surface,
                    shape = Radius.shapeMd,
                    border = BorderStroke(1.dp, Brand.BorderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                request.title,
                                color = Brand.Foreground,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                request.status.replace('_', ' '),
                                color = if (request.actionRequired) Brand.Danger else Brand.ForegroundMuted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (request.isLocalOnly) {
                            Text(
                                "Saved on this tablet · ${request.identity.take(8).uppercase()}",
                                color = Brand.ForegroundFaint,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        request.latestReply?.let { reply ->
                            Text(
                                "${request.latestReplyAuthor ?: "Owner"}: $reply",
                                color = Brand.ForegroundMuted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (request.actionRequired) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                TextButton(onClick = { onRetry(request.identity) }) {
                                    Text("Retry send")
                                }
                                TextButton(onClick = { onRequestDiscard(request.identity) }) {
                                    Text("Remove saved copy", color = Brand.Danger)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Replies are private to your signed-in account. Internal owner notes are never shown here.",
            color = Brand.ForegroundFaint,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReasonChoice(
    reason: SupportRequestReason,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) Brand.Gold.copy(alpha = 0.10f) else Brand.Surface,
        shape = Radius.shapeMd,
        border = BorderStroke(1.dp, if (selected) Brand.GoldMuted else Brand.BorderSubtle),
        modifier = modifier.heightIn(min = 76.dp).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ).semantics { this.selected = selected },
    ) {
        Column(
            Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                reason.label,
                color = if (selected) Brand.Foreground else Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                reason.detail,
                color = Brand.ForegroundFaint,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ContinuationChoice(
    option: WorkContinuation,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) Brand.SurfaceHover else Color.Transparent,
        shape = Radius.shapePill,
        border = BorderStroke(1.dp, if (selected) Brand.ForegroundMuted else Brand.BorderSubtle),
        modifier = modifier.heightIn(min = 48.dp).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                option.label,
                color = if (selected) Brand.Foreground else Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
