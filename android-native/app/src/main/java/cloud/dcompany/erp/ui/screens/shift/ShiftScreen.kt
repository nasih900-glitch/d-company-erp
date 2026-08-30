package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftHistoryRow
import cloud.dcompany.erp.core.db.ShiftHistorySource
import cloud.dcompany.erp.core.db.ShiftAccountingBreakdown
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import cloud.dcompany.erp.ui.components.TouchMoneyEntry
import cloud.dcompany.erp.ui.components.WholeNumberStepper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ShiftLegacyMoneyRow(
    val label: String,
    val amountMinor: Long,
    val isRefund: Boolean,
)

internal fun shiftLegacyMoneyRows(
    accounting: ShiftAccountingBreakdown,
    presentation: WorkspacePresentationPolicy,
): List<ShiftLegacyMoneyRow> = buildList {
    if (presentation.showsMemberships || accounting.membershipCollectionsMinor != 0L) {
        add(
            ShiftLegacyMoneyRow(
                presentation.shiftPrepaidReceiptsLabel,
                accounting.membershipCollectionsMinor,
                isRefund = false,
            ),
        )
    }
    if (presentation.showsMemberships || accounting.settledMembershipRefundsMinor != 0L) {
        add(
            ShiftLegacyMoneyRow(
                presentation.shiftPrepaidRefundsLabel,
                accounting.settledMembershipRefundsMinor,
                isRefund = true,
            ),
        )
    }
}

internal fun ShiftAccountingBreakdown.hasLegacyPrepaidMoney(): Boolean =
    membershipCollectionsMinor != 0L || settledMembershipRefundsMinor != 0L

@Composable
fun ShiftScreen(
    access: ShiftAccess = ShiftAccess(),
    vm: ShiftViewModel = viewModel(),
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val canActOnCurrentShift = if (state.open == null) access.canOpen else access.canClose
        if (!canActOnCurrentShift) {
            ViewOnlyNotice(
                if (state.open == null) {
                    "Shift opening is view only for this account. Ask an authorised cashier or manager."
                } else {
                    "Shift closing is view only for this account. Ask the opener or a protected owner."
                },
            )
        }
        ShiftSummaryRow(state)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 900.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    WideCurrentShiftPanel(
                        stateIdentity = state.open?.let(::shiftCloseUiIdentity) ?: "closed",
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.open == null) OpenShiftCard(state, vm, access.canOpen)
                        else CloseShiftCard(
                            state,
                            vm,
                            access.canClose,
                            access.canOpen,
                            // The wide panel now owns vertical scrolling. Let
                            // the card wrap its complete content instead of
                            // stretching to the bounded viewport and clipping
                            // the actions below it.
                            compactLayout = true,
                            presentation = presentation,
                        )
                    }
                    PastShiftsPanel(state, Modifier.width(350.dp))
                }
            } else {
                CompactShiftPanels(
                    stateIdentity = state.open?.let(::shiftCloseUiIdentity) ?: "closed",
                    currentPanel = {
                        if (state.open == null) OpenShiftCard(state, vm, access.canOpen)
                        else CloseShiftCard(
                            state,
                            vm,
                            access.canClose,
                            access.canOpen,
                            compactLayout = true,
                            presentation = presentation,
                        )
                    },
                    historyPanel = {
                        PastShiftsPanel(state, Modifier.heightIn(min = 280.dp, max = 320.dp))
                    },
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

/**
 * The target tablet is wide (1280dp) but only 800dp tall. The shell, summary
 * cards and page padding leave less height than the touch keypad/open action or
 * the complete close-shift workflow require. Give only the current-shift panel
 * a scroll owner so its primary action remains reachable while shift history
 * stays independently visible.
 */
@Composable
internal fun WideCurrentShiftPanel(
    stateIdentity: String,
    modifier: Modifier = Modifier,
    currentPanel: @Composable () -> Unit,
) {
    // Opening or closing replaces the workflow. Reset its scroll position so
    // staff always land on the new panel heading, while ordinary state updates
    // preserve their current position.
    key(stateIdentity) {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            item(key = "wide-current-shift-panel") { currentPanel() }
        }
    }
}

/**
 * A compact tablet cannot reserve fixed-height current-shift and history
 * panels simultaneously. A single scroll owner keeps both complete panels
 * reachable instead of clipping the opening input and action at 960 x 600dp.
 */
@Composable
internal fun CompactShiftPanels(
    stateIdentity: String,
    modifier: Modifier = Modifier,
    currentPanel: @Composable () -> Unit,
    historyPanel: @Composable () -> Unit,
) {
    // A successful open/close replaces the complete current panel. Keeping the
    // prior list offset would land staff halfway through the new panel. Keying
    // the scroll owner to the authoritative shift identity starts each new
    // workflow at its heading while preserving scroll during ordinary updates.
    key(stateIdentity) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "current-shift-panel") { currentPanel() }
            item(key = "shift-history-panel") { historyPanel() }
        }
    }
}

@Composable
private fun ShiftSummaryRow(state: ShiftUiState) {
    val open = state.open
    val opener = open?.openedByName?.takeIf(String::isNotBlank)
        ?: open?.openedByEmail?.takeIf(String::isNotBlank)
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { modifier ->
            CompactStatCard(
                label = "Shift status",
                value = if (open == null) "Closed" else "Open",
                detail = if (open == null) "Billing requires a shift" else "Billing is available",
                icon = Icons.Filled.LockClock,
                tone = if (open == null) UiTone.Warning else UiTone.Success,
                modifier = modifier,
            )
        },
        { modifier ->
            CompactStatCard(
                label = if (open == null) "Closed shift history" else "Opened by",
                value = if (open == null) state.history.size.toString() else (opener ?: "Verifying"),
                detail = if (open == null) {
                    "Loaded for this device"
                } else {
                    historyDateFormat.format(Date(open.openedAtMillis))
                },
                icon = if (open == null) Icons.Filled.History else Icons.Filled.Person,
                tone = UiTone.Neutral,
                modifier = modifier,
            )
        },
        { modifier ->
            CompactStatCard(
                label = if (open == null) "Opening float" else "Expected drawer",
                value = if (open == null) "₹0.00 allowed" else (state.expectedMinor?.asRupees() ?: "Unavailable"),
                detail = if (open == null) {
                    "Count existing drawer cash"
                } else if (state.expectedMinor == null) {
                    "Refresh when online"
                } else {
                    "Before final count"
                },
                icon = Icons.Filled.AccountBalanceWallet,
                tone = if (open != null && state.expectedMinor == null) UiTone.Warning else UiTone.Information,
                modifier = modifier,
            )
        },
    )

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when {
            maxWidth >= 760.dp -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                cards.forEach { card -> card(Modifier.weight(1f)) }
            }
            maxWidth >= 520.dp -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    cards.take(2).forEach { card -> card(Modifier.weight(1f)) }
                }
                cards.last()(Modifier.fillMaxWidth())
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                cards.forEach { card -> card(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun PastShiftsPanel(state: ShiftUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Past shifts",
        subtitle = "Latest closed shifts for this device",
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        state.historyMessage?.let { message ->
            Text(message, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        if (state.history.isEmpty()) {
            if (state.historyRefreshing) {
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Gold)
                }
            } else {
                DesignedEmptyState(
                    title = "No closed shifts yet",
                    body = "Completed shifts from this device will remain available here for reconciliation.",
                    icon = Icons.Filled.History,
                    modifier = Modifier.heightIn(min = 170.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(state.history, key = { it.stableId }) { s -> HistoryRow(s) }
            }
        }
        Text(
            "Showing up to the latest 200 shifts for this device.",
            color = Brand.ForegroundFaint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun OpenShiftCard(state: ShiftUiState, vm: ShiftViewModel, canOpen: Boolean) {
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

    OpenShiftForm(
        online = state.online,
        busy = state.busy,
        canOpen = canOpen,
        blockedByRejectedShift = state.rejectedShift != null,
        onOpenShift = vm::openShift,
    )
}

/** Actual open-shift form, separated from ViewModel wiring for rendered UI tests. */
@Composable
internal fun OpenShiftForm(
    online: Boolean,
    busy: Boolean,
    canOpen: Boolean,
    blockedByRejectedShift: Boolean,
    onOpenShift: (Long) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitFocusRequester = remember { FocusRequester() }
    var float by remember { mutableStateOf("") }
    val parsedFloat = remember(float) { parseRupeesToMinor(float) }
    val floatMinor = parsedFloat ?: 0L
    val validFloat = float.isBlank() || parsedFloat != null

    SectionCard(
        title = "Open shift",
        subtitle = "Record the drawer cash before accepting the first payment.",
        icon = Icons.Filled.AccountBalanceWallet,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Billing remains blocked until the shift is open. The opening float may be ₹0.00 when the drawer starts empty.",
            color = Brand.ForegroundMuted,
        )
        if (!online) {
            Text(
                "No connection — the shift opens safely on this tablet and synchronises when connectivity returns.",
                color = Brand.Information,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TouchMoneyEntry(
            value = float,
            onValueChange = { float = it },
            label = "Opening float (₹)",
            enabled = canOpen && !busy && !blockedByRejectedShift,
            presetsMinor = listOf(0L, 50_000L, 100_000L),
            modifier = Modifier.fillMaxWidth(),
        )
        ErpButton(
            text = "Open shift with ${floatMinor.asRupees()}",
            onClick = {
                // The form is replaced immediately after a successful open.
                // Clear its IME focus first so the old numeric keyboard cannot
                // cover the newly rendered close-shift panel.
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                // Compose buttons do not necessarily take focus after a
                // touch/semantics click. Move focus explicitly so OEM focus
                // restoration cannot put the IME back on the money field
                // while the open request is in flight.
                submitFocusRequester.requestFocus()
                onOpenShift(floatMinor)
            },
            enabled = canOpen && validFloat && !blockedByRejectedShift,
            busy = busy,
            modifier = Modifier.fillMaxWidth().focusRequester(submitFocusRequester),
        )
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
            .background(Brand.SurfaceRaised)
            .border(1.dp, Brand.Danger, Radius.shapeLg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Couldn't open a shift",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Brand.Danger,
        )
        Text(rejected.lastError ?: "The server refused this.", color = Brand.Foreground)
        Text(
            actions.guidance,
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!online) {
            Text(
                "A live connection is required to clear the attempt safely.",
                color = Brand.Warning,
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
    compactLayout: Boolean,
    presentation: WorkspacePresentationPolicy,
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
    val draftCountedMinor = drawerCountedMinor(counts)
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
    var drawerCountOpen by remember(shiftIdentity, closeBlocked) { mutableStateOf(false) }

    val cardModifier = if (compactLayout) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
    Column(
        cardModifier.clip(Radius.shapeLg)
            .background(Brand.Surface).border(1.dp, Brand.BorderSubtle, Radius.shapeLg).padding(Spacing.lg),
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Close current shift", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Text("Review collections, count the drawer, then confirm.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
            }
            OperationalStatusBadge(
                label = when {
                    closing -> "Close pending"
                    closeRejected -> "Close rejected"
                    else -> "Open"
                },
                tone = when {
                    closing -> UiTone.Information
                    closeRejected -> UiTone.Danger
                    else -> UiTone.Success
                },
            )
        }
        ShiftOwnershipSummary(shift)
        state.moneyAccessMessage?.let { message ->
            Text(
                message,
                color = Brand.Warning,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        PanelDivider()
        InfoRow(label = "Opening float", value = shift.openingFloatMinor.asRupees())
        InfoRow(
            label = "Expected in drawer",
            value = state.expectedMinor?.asRupees() ?: "Not available offline",
            valueColor = if (state.expectedMinor == null) Brand.Warning else Brand.Foreground,
        )
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
            Text(
                "Payment methods (gross collections)",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CollectionMetric(
                    label = "Cash",
                    value = accounting.cashCollectionsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
                CollectionMetric(
                    label = "Card",
                    value = accounting.cardCollectionsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
                CollectionMetric(
                    label = "UPI",
                    value = accounting.upiCollectionsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
                CollectionMetric(
                    label = "Other",
                    value = accounting.otherCollectionsMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (accountingExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (presentation.showsRestaurantOperations) {
                            "POS receipts (may include tips)"
                        } else {
                            "POS and gaming receipts (may include tips)"
                        },
                        color = Brand.ForegroundMuted,
                    )
                    Text(accounting.posCollectionsMinor.asRupees(), color = Brand.Foreground)
                }
                shiftLegacyMoneyRows(accounting, presentation).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.label, color = Brand.ForegroundMuted)
                        Text(
                            row.amountMinor.asRupees(),
                            color = if (row.isRefund && row.amountMinor > 0) Brand.Danger else Brand.Foreground,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Settled POS refunds", color = Brand.ForegroundMuted)
                    Text(
                        accounting.settledPosRefundsMinor.asRupees(),
                        color = if (accounting.settledPosRefundsMinor > 0) Brand.Danger else Brand.ForegroundMuted,
                    )
                }
                if (!presentation.showsMemberships && accounting.hasLegacyPrepaidMoney()) {
                    Text(
                        "Historical hidden-module money remains included in Gross, Refunds and Net. " +
                            "Owners should reconcile it against protected history; it has not been removed.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else if (shift.posCollectionsMinor != null) {
            Text(
                "Detailed collection and refund totals are not available from the server yet. " +
                    "Use Expected in drawer for the cash count; POS receipts can include non-cash payments.",
                color = Brand.ForegroundMuted,
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
                    .background(Brand.SurfaceRaised)
                    .border(1.dp, Brand.Danger, Radius.shapeSm)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Shift is still open",
                    color = Brand.Danger,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    shift.local?.lastError ?: "The server refused to close this shift.",
                    color = Brand.Foreground,
                )
                Text(
                    closePresentation.savedCountMessage(
                        valid = "Saved drawer count: %s. Resolve any unpaid orders or active sessions, " +
                            "then Retry saved close to reuse this exact amount. Choose Continue shift " +
                            "to discard this close request, resume billing, and count again later.",
                        missing = "This older rejected close has no valid saved drawer count. Retry is " +
                            "disabled. Choose Continue shift, then count the drawer again before closing.",
                    ),
                    color = Brand.ForegroundMuted,
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

        PanelDivider()
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
            if (!compactLayout) Spacer(Modifier.weight(1f))
        } else {
            Text("Count the drawer", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
            Text(
                "Count each note and coin in a focused view. Your total and difference stay visible here.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            ErpButton(
                text = if (counts.isEmpty()) "Count cash" else "Edit cash count",
                onClick = { drawerCountOpen = true },
                enabled = closePresentation.canEditCount,
                leadingIcon = Icons.Filled.AccountBalanceWallet,
                modifier = Modifier.fillMaxWidth(),
            )
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

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ErpButton(
                text = "Refresh",
                onClick = vm::load,
                enabled = !closing,
                busy = state.busy && !closing,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Filled.Refresh,
            )
            Spacer(Modifier.weight(1f))
            ErpButton(
                text = "Close shift",
                onClick = {
                    closePresentation.displayedCountedMinor?.let { counted ->
                        confirmation = ShiftCloseConfirmation(shiftIdentity, counted)
                    }
                },
                enabled = canClosePermission && !closeBlocked && !state.busy && state.canClose,
                busy = closing,
                intent = ActionIntent.Destructive,
            )
        }
    }

    if (drawerCountOpen && !closePresentation.usesSavedCount) {
        DrawerCountDialog(
            initialCounts = counts,
            expectedMinor = state.expectedMinor,
            enabled = closePresentation.canEditCount,
            onDismiss = { drawerCountOpen = false },
            onApply = { updatedCounts ->
                counts.clear()
                updatedCounts
                    .filterValues(String::isNotBlank)
                    .forEach { (note, value) -> counts[note] = value }
                drawerCountOpen = false
            },
        )
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
                ErpButton(
                    text = "Close shift",
                    intent = ActionIntent.Destructive,
                    onClick = {
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
                    },
                )
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

internal fun drawerCountedMinor(counts: Map<Long, String>): Long =
    DENOMINATIONS.sumOf { note ->
        note * 100 * (counts[note]?.toLongOrNull() ?: 0L)
    }

@Composable
internal fun DrawerCountDialog(
    initialCounts: Map<Long, String>,
    expectedMinor: Long?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (Map<Long, String>) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        DrawerCountDialogContent(
            initialCounts = initialCounts,
            expectedMinor = expectedMinor,
            enabled = enabled,
            onDismiss = onDismiss,
            onApply = onApply,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 960.dp)
                .heightIn(min = 480.dp, max = 720.dp),
        )
    }
}

@Composable
internal fun DrawerCountDialogContent(
    initialCounts: Map<Long, String>,
    expectedMinor: Long?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onApply: (Map<Long, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draftCounts = remember {
        mutableStateMapOf<Long, String>().apply { putAll(initialCounts) }
    }
    val countedMinor = drawerCountedMinor(draftCounts)
    val differenceMinor = expectedMinor?.let { countedMinor - it }

    Surface(
        color = Brand.SurfaceOverlay,
        shape = Radius.shapeXl,
        tonalElevation = 0.dp,
        modifier = modifier
            .border(1.dp, Brand.Border, Radius.shapeXl)
            .semantics { contentDescription = "Drawer count dialog" },
    ) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Count the drawer",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Enter how many of each note or coin is physically in the drawer.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        DENOMINATIONS.forEach { draftCounts.remove(it) }
                    },
                    enabled = enabled && draftCounts.isNotEmpty(),
                ) {
                    Text("Clear all")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                CollectionMetric(
                    label = "Expected",
                    value = expectedMinor?.asRupees() ?: "Unavailable",
                    modifier = Modifier.weight(1f),
                    valueColor = if (expectedMinor == null) Brand.Warning else Brand.Foreground,
                )
                CollectionMetric(
                    label = "Counted",
                    value = countedMinor.asRupees(),
                    modifier = Modifier.weight(1f),
                )
                CollectionMetric(
                    label = "Difference",
                    value = when {
                        differenceMinor == null -> "Unavailable"
                        differenceMinor == 0L -> "Balanced"
                        differenceMinor > 0 -> "Over ${differenceMinor.asRupees()}"
                        else -> "Short ${(-differenceMinor).asRupees()}"
                    },
                    modifier = Modifier.weight(1f),
                    valueColor = when {
                        differenceMinor == null -> Brand.ForegroundMuted
                        differenceMinor == 0L -> Brand.Good
                        else -> Brand.Danger
                    },
                )
            }

            PanelDivider()
            DenominationCountGrid(
                compactLayout = false,
                counts = draftCounts,
                onCountChange = { note, value -> draftCounts[note] = value },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PanelDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                ErpButton(
                    text = "Use drawer count",
                    onClick = {
                        onApply(
                            DENOMINATIONS.associateWith { note -> draftCounts[note].orEmpty() },
                        )
                    },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
internal fun DenominationCountGrid(
    compactLayout: Boolean,
    counts: Map<Long, String>,
    onCountChange: (Long, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnCount = denominationColumnCount(maxWidth)
        val denominationRows = DENOMINATIONS.chunked(columnCount)
        if (compactLayout) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                denominationRows.forEach { rowNotes ->
                    DenominationCountRow(
                        rowNotes = rowNotes,
                        columnCount = columnCount,
                        counts = counts,
                        onCountChange = onCountChange,
                        enabled = enabled,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(denominationRows) { rowNotes ->
                    DenominationCountRow(
                        rowNotes = rowNotes,
                        columnCount = columnCount,
                        counts = counts,
                        onCountChange = onCountChange,
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

/** Keep both 48dp stepper buttons and the editable count visible at compact widths. */
internal fun denominationColumnCount(availableWidth: Dp): Int = when {
    availableWidth >= 720.dp -> 3
    availableWidth >= 480.dp -> 2
    else -> 1
}

@Composable
private fun DenominationCountRow(
    rowNotes: List<Long>,
    columnCount: Int,
    counts: Map<Long, String>,
    onCountChange: (Long, String) -> Unit,
    enabled: Boolean,
) {
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
                    onValueChange = { value -> onCountChange(note, value) },
                    description = "Count of ₹$note notes",
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        repeat(columnCount - rowNotes.size) { Spacer(Modifier.weight(1f)) }
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
                color = Brand.Warning,
            )
        } else if (s.netCollectionsMinor != null) {
            Text(
                "net collections ${s.netCollectionsMinor.asRupees()}",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
            if (
                s.cashCollectionsMinor != null &&
                s.cardCollectionsMinor != null &&
                s.upiCollectionsMinor != null &&
                s.otherCollectionsMinor != null
            ) {
                val other = if (s.otherCollectionsMinor > 0L) {
                    " · other ${s.otherCollectionsMinor.asRupees()}"
                } else {
                    ""
                }
                Text(
                    "cash ${s.cashCollectionsMinor.asRupees()} · " +
                        "card ${s.cardCollectionsMinor.asRupees()} · " +
                        "UPI ${s.upiCollectionsMinor.asRupees()}$other",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
        }
    }
}
