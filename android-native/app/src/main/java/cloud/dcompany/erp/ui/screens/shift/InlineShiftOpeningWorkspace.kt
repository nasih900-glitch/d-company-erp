package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.ui.theme.Spacing

/** Uses the normal Room/outbox command. A button callback never declares the shift open. */
@Composable
internal fun InlineShiftOpeningWorkspace(
    access: ShiftAccess,
    onReturn: () -> Unit,
    vm: ShiftViewModel = sharedShiftViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }
    InlineShiftOpeningContent(
        state = state,
        access = access,
        onOpen = vm::openShift,
        onSync = vm::load,
        onReturn = onReturn,
        onRetryRejected = vm::retryRejectedOpen,
        onVerifyRejected = vm::verifyAndClearRejectedOpen,
    )
}

internal fun inlineShiftOpeningStatus(state: ShiftUiState): String = when {
    !state.currentShiftLoaded -> "Checking this till's saved shift state."
    state.open == null -> "Your station selection and current order stay in this workspace."
    state.open.local?.state == ShiftState.CLOSE_PENDING ->
        "The saved close is waiting for server confirmation. Review it in Shift before opening another."
    state.open.local?.state == ShiftState.CLOSE_REJECTED ->
        "The previous close needs review. This shift is still open."
    (state.open.server == null && state.open.local?.serverShiftId == null) && state.offlineGamingSupported ->
        "Shift saved on this tablet. This verified workspace supports queued work; server confirmation is still pending."
    (state.open.server == null && state.open.local?.serverShiftId == null) ->
        "Shift saved on this tablet. Waiting for server confirmation before Gaming becomes available."
    else -> "Open shift confirmed. Return to your saved work."
}

/** No scroll owner here: Gaming's detail pane and POS's cart own their bounded scroll. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineShiftOpeningContent(
    state: ShiftUiState,
    access: ShiftAccess,
    onOpen: (Long) -> Unit,
    onSync: () -> Unit,
    onReturn: () -> Unit,
    onRetryRejected: () -> Unit = {},
    onVerifyRejected: () -> Unit = {},
) {
    val heading = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { heading.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().testTag("inline-shift-opening-workspace"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(heading), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Shift · same workspace", style = MaterialTheme.typography.titleSmall)
            TextButton(onReturn) { Text("Return to work") }
        }
        Text(inlineShiftOpeningStatus(state), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.operationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (!state.currentShiftLoaded) {
            TextButton(onSync) { Text("Sync shift state") }
        } else if (state.open == null) {
            if (!access.canOpen) {
                Text("Shift open permission is required. Ask an authorised cashier or manager.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.rejectedShift?.let { rejected ->
                    RejectedShiftCard(rejected = rejected, busy = state.busy, online = state.online,
                        canRecover = access.canOpen, currentShift = null, workspaceLabel = state.workspaceLabel,
                        onRetry = onRetryRejected, onVerifyAndClear = onVerifyRejected)
                }
                OpenShiftForm(online = state.online, busy = state.busy, canOpen = access.canOpen,
                    blockedByRejectedShift = state.rejectedShift != null,
                    onOpenShift = onOpen, offlineGamingSupported = state.offlineGamingSupported)
            }
        } else if ((state.open.server == null && state.open.local?.serverShiftId == null) || state.syncing) {
            TextButton(onSync, enabled = !state.syncing) {
                Text(if (state.syncing) "Syncing shift…" else "Sync shift state")
            }
        }
    }
}

@Composable
internal fun ShiftBlockerActions(
    canOpen: Boolean,
    missingShift: Boolean,
    onOpen: () -> Unit,
    onSync: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (missingShift && canOpen) TextButton(onOpen,
            modifier = Modifier.testTag("open-shift-here")) { Text("Open shift here") }
        TextButton(onSync, modifier = Modifier.testTag("sync-shift-here")) { Text("Sync shift state") }
    }
}
