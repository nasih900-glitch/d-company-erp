package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftHistoryRow
import cloud.dcompany.erp.core.db.ShiftHistorySource
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.TouchMoneyEntry
import cloud.dcompany.erp.ui.components.WholeNumberStepper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShiftScreen(
    access: ShiftAccess = ShiftAccess(),
    vm: ShiftViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    SideEffect { vm.updateAccess(access) }

    Column(Modifier.fillMaxSize()) {
        val canActOnCurrentShift = if (state.open == null) access.canOpen else access.canClose
        if (!canActOnCurrentShift) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ViewOnlyNotice(
                    if (state.open == null) {
                        "Shift opening is view only for this account. Ask an authorised cashier or manager."
                    } else {
                        "Shift closing is view only for this account. Ask the opener or a protected owner."
                    },
                )
            }
        }
        Row(Modifier.fillMaxSize().padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                if (state.open == null) {
                    OpenShiftCard(state, vm, access.canOpen)
                } else {
                    CloseShiftCard(state, vm, access.canClose, access.canOpen)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(360.dp)) {
                Text("Past shifts", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Spacer(Modifier.height(8.dp))
                state.historyMessage?.let { message ->
                    Text(
                        message,
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (state.history.isEmpty()) {
                    Text(
                        if (state.historyRefreshing) "Refreshing shift history…" else "No closed shifts found for this terminal.",
                        color = Brand.ForegroundMuted,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.history, key = { it.stableId }) { s -> HistoryRow(s) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Showing up to the latest 200 shifts for this terminal.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    state.operationError?.let { message ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissOperationError,
            confirmButton = { TextButton(onClick = vm::dismissOperationError) { Text("OK") } },
            title = { Text("Shift not saved") },
            text = { Text(message) },
        )
    }

    state.operationNotice?.let { message ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissOperationNotice,
            confirmButton = {
                TextButton(onClick = vm::dismissOperationNotice) { Text("OK") }
            },
            title = { Text("Shift recovery complete") },
            text = { Text(message) },
        )
    }

    state.closedResult?.let { r ->
        val remotelyReconciled = shiftCloseResultKind(r) == ShiftCloseResultKind.REMOTE_RECONCILED
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissResult,
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text("OK") } },
            title = { Text(if (remotelyReconciled) "Shift closed elsewhere" else "Shift closed") },
            text = {
                Text(
                    when {
                        remotelyReconciled ->
                            r.lastError ?: "Live server reconciliation confirmed that this shift closed elsewhere."
                        r.varianceMinor == null -> "Closed. The drawer count is with the server — variance " +
                            "will show here once it syncs."
                        r.varianceMinor == 0L -> "The drawer balanced exactly."
                        r.varianceMinor > 0 -> "Over by ${r.varianceMinor.asRupees()} — more cash than expected."
                        else -> "Short by ${(-r.varianceMinor).asRupees()} — less cash than expected."
                    },
                )
            },
        )
    }
}

@Composable
private fun OpenShiftCard(state: ShiftUiState, vm: ShiftViewModel, canOpen: Boolean) {
    var float by remember { mutableStateOf("") }
    val parsedFloat = remember(float) { parseRupeesToMinor(float) }
    val floatMinor = parsedFloat ?: 0L
    val validFloat = float.isBlank() || parsedFloat != null

    state.rejectedShift?.let { rejected ->
        RejectedShiftCard(
            rejected = rejected,
            busy = state.busy,
            online = state.online,
            canRecover = canOpen,
            currentShiftOpen = false,
            onRetry = vm::retryRejectedOpen,
            onVerifyAndClear = vm::verifyAndClearRejectedOpen,
        )
        Spacer(Modifier.height(14.dp))
    }

    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(Brand.Surface).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("No shift is open", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "Billing is blocked until a shift is open. Enter the cash already in the " +
                "drawer at the start of this shift.",
            color = Brand.ForegroundMuted,
        )
        if (!state.online) {
            Text(
                "No connection — the shift opens on this tablet now and reaches the " +
                    "server once you're back online.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TouchMoneyEntry(
            value = float,
            onValueChange = { float = it },
            label = "Opening float (₹)",
            enabled = canOpen && !state.busy && state.rejectedShift == null,
            presetsMinor = listOf(0L, 50_000L, 100_000L),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { vm.openShift(floatMinor) },
            enabled = canOpen && !state.busy && validFloat && state.rejectedShift == null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Brand.Background)
            else Text("Open shift with ${floatMinor.asRupees()}")
        }
    }
}

@Composable
private fun RejectedShiftCard(
    rejected: LocalShiftEntity,
    busy: Boolean,
    online: Boolean,
    canRecover: Boolean,
    currentShiftOpen: Boolean,
    onRetry: () -> Unit,
    onVerifyAndClear: () -> Unit,
) {
    val actions = rejectedOpenRecoveryActions(
        hasCurrentShift = currentShiftOpen,
        online = online,
        canRecover = canRecover,
        busy = busy,
    )
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(Brand.Danger).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Couldn't open a shift",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.Background,
        )
        Text(rejected.lastError ?: "The server refused this.", color = Brand.Background)
        Text(
            actions.guidance,
            color = Brand.Background,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!online) {
            Text(
                "A live connection is required to clear the attempt safely.",
                color = Brand.Background,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetry,
                enabled = actions.retryEnabled,
            ) { Text("Retry saved shift") }
            OutlinedButton(
                onClick = onVerifyAndClear,
                enabled = actions.verifyEnabled,
            ) { Text("Verify & clear attempt") }
        }
    }
}

@Composable
private fun CloseShiftCard(
    state: ShiftUiState,
    vm: ShiftViewModel,
    canClosePermission: Boolean,
    canOpenPermission: Boolean,
) {
    val shift = state.open!!
    val closing = shift.local?.state == ShiftState.CLOSE_PENDING
    val closeRejected = shift.local?.state == ShiftState.CLOSE_REJECTED
    val closeBlocked = closing || closeRejected
    val shiftIdentity = shiftCloseUiIdentity(shift)
    // Staff count notes, not totals. Typing a single number invites both
    // arithmetic slips and a "close enough" fudge; counting by denomination
    // makes the total fall out of the count itself.
    // The exact shift identity and close-intent phase are keys. A newly opened
    // shift must never inherit another shift's drawer count or confirmation;
    // continuing a rejected shift must also start a fresh recount.
    val counts = remember(shiftIdentity, closeBlocked) { mutableStateMapOf<Long, String>() }
    val draftCountedMinor = DENOMINATIONS.sumOf { note ->
        note * 100 * (counts[note]?.toLongOrNull() ?: 0L)
    }
    val closePresentation = shiftClosePresentation(
        localState = shift.local?.state,
        savedCountedMinor = shift.local?.countedMinor,
        draftCountedMinor = draftCountedMinor,
        canEdit = canClosePermission && state.canClose && !state.busy,
    )
    var confirmation by remember(shiftIdentity, closeBlocked) {
        mutableStateOf<ShiftCloseConfirmation?>(null)
    }
    var confirmationGuardMessage by remember { mutableStateOf<String?>(null) }
    var accountingExpanded by remember(shiftIdentity) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().clip(Radius.shapeLg)
            .background(Brand.Surface).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A later, unrelated server shift must not hide an older rejected
        // attempt that still blocks account switching. Keep its explicit
        // recovery controls visible while showing the authoritative shift.
        state.rejectedShift?.let { rejected ->
            RejectedShiftCard(
                rejected = rejected,
                busy = state.busy,
                online = state.online,
                canRecover = canOpenPermission,
                currentShiftOpen = true,
                onRetry = vm::retryRejectedOpen,
                onVerifyAndClear = vm::verifyAndClearRejectedOpen,
            )
        }
        Text("Close shift", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        ShiftOwnershipSummary(shift)
        state.moneyAccessMessage?.let { message ->
            Text(
                message,
                color = Brand.Gold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Opening float", color = Brand.ForegroundMuted)
            Text(shift.openingFloatMinor.asRupees(), color = Brand.Foreground)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Expected in drawer", color = Brand.ForegroundMuted)
            Text(
                state.expectedMinor?.asRupees() ?: "not available offline",
                color = Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
        }
        val accounting = shift.accountingBreakdownOrNull()
        if (accounting != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Collections (excludes opening float)",
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { accountingExpanded = !accountingExpanded }) {
                    Text(if (accountingExpanded) "Hide details" else "View details")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CollectionMetric(
                    label = "Gross",
                    value = accounting.grossCollectionsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
                CollectionMetric(
                    label = "Refunds",
                    value = accounting.totalRefundsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                    valueColor = if (accounting.totalRefundsMinor > 0) Brand.Danger else Brand.Foreground,
                )
                CollectionMetric(
                    label = "Net",
                    value = accounting.netCollectionsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (accountingExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("POS receipts (may include tips)", color = Brand.ForegroundMuted)
                    Text(accounting.posCollectionsMinor.asRupees(), color = Brand.Foreground)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Membership receipts", color = Brand.ForegroundMuted)
                    Text(accounting.membershipCollectionsMinor.asRupees(), color = Brand.Foreground)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Settled POS refunds", color = Brand.ForegroundMuted)
                    Text(
                        accounting.settledPosRefundsMinor.asRupees(),
                        color = if (accounting.settledPosRefundsMinor > 0) Brand.Danger else Brand.ForegroundMuted,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Settled membership refunds", color = Brand.ForegroundMuted)
                    Text(
                        accounting.settledMembershipRefundsMinor.asRupees(),
                        color = if (accounting.settledMembershipRefundsMinor > 0) Brand.Danger else Brand.ForegroundMuted,
                    )
                }
            }
        } else if (shift.posCollectionsMinor != null) {
            Text(
                "Detailed collection and refund totals are not available from the server yet. " +
                    "Use Expected in drawer for the cash count; POS receipts can include non-cash payments.",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        if (closing) {
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Close sent — waiting for server confirmation" +
                        (if (!state.online) " (no connection right now)" else "") + ".",
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    closePresentation.savedCountMessage(
                        valid = "The saved drawer count is %s. This queued close is locked to that exact amount.",
                        missing = "This older queued close has no valid saved drawer count on this tablet. " +
                            "Do not create another close; reconnect and ask a protected owner to review it.",
                    ),
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (closeRejected) {
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.Danger).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Shift is still open",
                    color = Brand.Background,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    shift.local?.lastError ?: "The server refused to close this shift.",
                    color = Brand.Background,
                )
                Text(
                    closePresentation.savedCountMessage(
                        valid = "Saved drawer count: %s. Resolve any unpaid orders or active sessions, " +
                            "then Retry saved close to reuse this exact amount. Choose Continue shift " +
                            "to discard this close request, resume billing, and count again later.",
                        missing = "This older rejected close has no valid saved drawer count. Retry is " +
                            "disabled. Choose Continue shift, then count the drawer again before closing.",
                    ),
                    color = Brand.Background,
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::continueShift,
                        enabled = canClosePermission && !state.busy && state.canClose &&
                            closePresentation.canContinueShift,
                    ) {
                        Text("Continue shift")
                    }
                    Button(
                        onClick = vm::retryClose,
                        enabled = canClosePermission && !state.busy && state.canClose &&
                            closePresentation.canRetrySavedClose,
                    ) {
                        Text("Retry saved close")
                    }
                }
            }
        }

        if (closePresentation.usesSavedCount) {
            Text("Saved drawer count", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
            Text(
                closePresentation.displayedCountedMinor?.asRupees() ?: "Saved count unavailable",
                color = if (closePresentation.displayedCountedMinor == null) Brand.Danger else Brand.Foreground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Denomination fields are locked because this close already has a durable saved count.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
        } else {
            Text("Count the drawer", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(DENOMINATIONS.chunked(3)) { rowNotes ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        rowNotes.forEach { note ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("₹$note", color = Brand.Foreground)
                                    Text(
                                        (note * 100 * (counts[note]?.toLongOrNull() ?: 0L)).asRupees(),
                                        color = Brand.ForegroundMuted,
                                    )
                                }
                                WholeNumberStepper(
                                    value = counts[note] ?: "",
                                    onValueChange = { counts[note] = it },
                                    description = "Count of ₹$note notes",
                                    enabled = closePresentation.canEditCount,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        repeat(3 - rowNotes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        val displayedCountedMinor = closePresentation.displayedCountedMinor
        val variance = displayedCountedMinor?.let { counted ->
            state.expectedMinor?.let { expected -> counted - expected }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Counted", color = Brand.ForegroundMuted)
            Text(
                displayedCountedMinor?.asRupees() ?: "unavailable",
                color = if (displayedCountedMinor == null) Brand.Danger else Brand.Foreground,
                fontWeight = FontWeight.Bold,
            )
        }
        if (variance != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Difference", color = Brand.ForegroundMuted)
                Text(
                    when {
                        variance == 0L -> "balanced"
                        variance > 0 -> "over ${variance.asRupees()}"
                        else -> "short ${(-variance).asRupees()}"
                    },
                    color = if (variance == 0L) Brand.Good else Brand.Danger,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::load, enabled = !closing && !state.busy) { Text("Refresh") }
            Button(
                onClick = {
                    closePresentation.displayedCountedMinor?.let { counted ->
                        confirmation = ShiftCloseConfirmation(shiftIdentity, counted)
                    }
                },
                enabled = canClosePermission && !closeBlocked && !state.busy && state.canClose,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                if (closing) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = Brand.Background)
                else Text("Close shift")
            }
        }
    }

    val activeConfirmation = confirmation?.takeIf {
        it.isFor(shiftIdentity) && !closeBlocked
    }
    if (activeConfirmation != null && canClosePermission) {
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = { confirmation = null },
            title = { Text("Close this shift?") },
            text = {
                Text(
                    "Counted ${activeConfirmation.countedMinor.asRupees()}. Closing a shift is not reversible " +
                        "from this app — recount before confirming.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    val currentIdentity = vm.state.value.open?.let(::shiftCloseUiIdentity)
                    if (activeConfirmation.isFor(currentIdentity)) {
                        vm.closeShift(activeConfirmation.countedMinor)
                    } else {
                        // The dialog belonged to a shift that has since changed.
                        // Explain the safe rejection rather than ever applying
                        // A's count to B or silently ignoring the tap.
                        confirmationGuardMessage =
                            "The open shift changed while this confirmation was on screen. " +
                            "Nothing was closed. Review the current shift and count its drawer again."
                    }
                }) {
                    Text("Close shift")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }

    confirmationGuardMessage?.let { message ->
        AlertDialog(
            containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = { confirmationGuardMessage = null },
            title = { Text("Close cancelled safely") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { confirmationGuardMessage = null }) { Text("Review shift") }
            },
        )
    }
}

internal data class ShiftClosePresentation(
    val displayedCountedMinor: Long?,
    val usesSavedCount: Boolean,
    val canEditCount: Boolean,
    val canRetrySavedClose: Boolean,
    val canContinueShift: Boolean,
) {
    fun savedCountMessage(valid: String, missing: String): String =
        displayedCountedMinor?.let { valid.replace("%s", it.asRupees()) } ?: missing
}

/** Pure state-to-presentation rule used by the close UI and its JVM tests. */
internal fun shiftClosePresentation(
    localState: String?,
    savedCountedMinor: Long?,
    draftCountedMinor: Long,
    canEdit: Boolean,
): ShiftClosePresentation {
    val usesSavedCount = localState == ShiftState.CLOSE_PENDING ||
        localState == ShiftState.CLOSE_REJECTED
    val validSavedCount = savedCountedMinor?.takeIf { it >= 0L }
    return ShiftClosePresentation(
        displayedCountedMinor = if (usesSavedCount) validSavedCount else draftCountedMinor,
        usesSavedCount = usesSavedCount,
        canEditCount = canEdit && !usesSavedCount,
        canRetrySavedClose = localState == ShiftState.CLOSE_REJECTED && validSavedCount != null,
        canContinueShift = localState == ShiftState.CLOSE_REJECTED,
    )
}

/** Stable through local open -> server sync, distinct across different shifts and namespaces. */
internal fun shiftCloseUiIdentity(shift: ResolvedOpenShift): String =
    shift.local?.let { "local:${it.localId}" } ?: "server:${shift.shiftId}"

internal data class ShiftCloseConfirmation(
    val shiftIdentity: String,
    val countedMinor: Long,
) {
    fun isFor(currentShiftIdentity: String?): Boolean = shiftIdentity == currentShiftIdentity
}

@Composable
private fun CollectionMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = Brand.Foreground,
) {
    Column(modifier) {
        Text(label, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShiftOwnershipSummary(shift: ResolvedOpenShift) {
    val opener = shift.openedByName?.takeIf(String::isNotBlank)
        ?: shift.openedByEmail?.takeIf(String::isNotBlank)
        ?: "Opener not yet verified"
    val opened = historyDateFormat.format(Date(shift.openedAtMillis))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Opened by $opener",
            color = Brand.Foreground,
            fontWeight = FontWeight.SemiBold,
        )
        if (!shift.openedByEmail.isNullOrBlank() && shift.openedByEmail != opener) {
            Text(shift.openedByEmail, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        Text("Opened $opened", color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
    }
}

private val historyDateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

@Composable
private fun HistoryRow(s: ShiftHistoryRow) {
    val opener = s.openedByName?.takeIf(String::isNotBlank)
        ?: s.openedByEmail?.takeIf(String::isNotBlank)
        ?: "Unknown staff — refresh"
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised).padding(10.dp),
    ) {
        Text(historyDateFormat.format(Date(s.openedAtMillis)), color = Brand.Foreground)
        Text(
            "Opened by $opener",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "counted ${s.countedMinor?.asRupees() ?: "—"} · " +
                when {
                    s.varianceMinor == null -> "not synced yet"
                    s.varianceMinor == 0L -> "balanced"
                    s.varianceMinor > 0 -> "over ${s.varianceMinor.asRupees()}"
                    else -> "short ${(-s.varianceMinor).asRupees()}"
                },
            style = MaterialTheme.typography.labelSmall,
            color = if (s.varianceMinor == 0L) Brand.Good else Brand.ForegroundMuted,
        )
        if (s.source == ShiftHistorySource.LOCAL) {
            Text(
                "Saved on this tablet; waiting for server history refresh.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.GoldMuted,
            )
        } else if (s.netCollectionsMinor != null) {
            Text(
                "net collections ${s.netCollectionsMinor.asRupees()}",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
    }
}
