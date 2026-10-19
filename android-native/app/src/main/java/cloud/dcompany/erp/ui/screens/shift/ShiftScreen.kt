package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
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
import cloud.dcompany.erp.ui.screens.businessDateTime
import kotlinx.coroutines.launch

internal data class ShiftLegacyMoneyRow(
    val label: String,
    val amountMinor: Long,
    val isRefund: Boolean,
)

internal data class ShiftStatusCopy(val value: String, val detail: String)

/** Staff-facing lifecycle wording: a saved close remains open until acknowledged by the server. */
internal fun shiftStatusCopy(
    hasOpenShift: Boolean,
    localState: String?,
    confirmingOpen: Boolean,
    offlineGamingSupported: Boolean,
): ShiftStatusCopy = when {
    !hasOpenShift -> ShiftStatusCopy("No open shift", "Open a shift before billing")
    localState == ShiftState.CLOSE_PENDING ->
        ShiftStatusCopy("Waiting for server", "Not closed until server confirms")
    localState == ShiftState.CLOSE_REJECTED ->
        ShiftStatusCopy("Open — action needed", "Close rejected; shift remains open")
    confirmingOpen && offlineGamingSupported ->
        ShiftStatusCopy("Saved", "Offline work can continue")
    confirmingOpen -> ShiftStatusCopy("Saved", "Gaming needs server confirmation")
    else -> ShiftStatusCopy("Open", "Billing is available")
}

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

/** Normal Shift and inline entry points share one command/busy owner for this login. */
@Composable
internal fun sharedShiftViewModel(): ShiftViewModel = viewModel()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShiftScreen(
    access: ShiftAccess = ShiftAccess(),
    vm: ShiftViewModel = sharedShiftViewModel(),
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }

    val openShift = state.open
    val shiftIdentity = when {
        !state.currentShiftLoaded -> "loading"
        openShift != null -> shiftCloseUiIdentity(openShift)
        else -> "closed"
    }
    key(shiftIdentity) {
        val closeReview = remember { BringIntoViewRequester() }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1180.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lgPlus, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                    Column {
                        Text("SHIFT", color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Track. Collect. Close.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                state.operationNotice?.let { message ->
                    Column(Modifier.fillMaxWidth().padding(vertical=Spacing.xs),verticalArrangement=Arrangement.spacedBy(Spacing.xs)) {
                        Text("Shift update",style=MaterialTheme.typography.labelMedium)
                        Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(vm::dismissOperationNotice) { Text("Dismiss status") }
                    }
                }
                if (!state.currentShiftLoaded) {
                    ShiftLoadingWorkspace()
                } else if (state.open == null) {
                    if (!access.canOpen) {
                        ViewOnlyNotice(
                            "Shift opening is view only for this account. Ask an authorised cashier or manager.",
                        )
                    }
                    OpenShiftCard(state, vm, access.canOpen)
                } else {
                    if (!access.canClose) {
                        ViewOnlyNotice(
                            "Shift closing is view only for this account. Ask a staff member with Shift close access.",
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().testTag("shift-open-workspace")) {
                        val overview: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier) {
                            ShiftDashboardOverview(
                                state = state,
                                canClose = access.canClose,
                                presentation = presentation,
                                onRefresh = vm::load,
                                onReview = { scope.launch { closeReview.bringIntoView() } },
                            )
                            }
                        }
                        val reconciliation: @Composable (Modifier) -> Unit = { modifier ->
                            Box(modifier.testTag("shift-reconciliation-panel")) {
                            CloseShiftCard(
                                state,
                                access.canClose,
                                access.canOpen,
                                compactLayout = true,
                                reviewAnchor = closeReview,
                                callbacks = ShiftCloseCallbacks(
                                    onRetryRejectedOpen = vm::retryRejectedOpen,
                                    onVerifyAndClearRejectedOpen = vm::verifyAndClearRejectedOpen,
                                    onContinueShift = vm::continueShift,
                                    onRetryClose = vm::retryClose,
                                    onRefresh = vm::load,
                                    currentShiftIdentity = {
                                        vm.state.value.open?.let(::shiftCloseUiIdentity)
                                    },
                                    onCloseShift = vm::closeShift,
                                ),
                            )
                            }
                        }
                        if (maxWidth >= 1_000.dp) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                verticalAlignment = Alignment.Top,
                            ) {
                                overview(Modifier.weight(0.9f))
                                reconciliation(Modifier.weight(1.1f))
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                overview(Modifier.fillMaxWidth())
                                reconciliation(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                if (state.open != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    PastShiftsPanel(state, Modifier.fillMaxWidth().height(280.dp))
                }
            }
        }
    }

    state.operationError?.let { message ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = vm::dismissOperationError,
            confirmButton = { TextButton(onClick = vm::dismissOperationError) { Text("OK") } },
            title = { Text("Shift action needs attention") },
            text = { Text(message) },
        )
    }

    state.closedResult?.let { r ->
        val dismiss = { vm.dismissResult(r.localId) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
            onDismissRequest = dismiss,
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
            title = { Text(shiftCloseResultTitle(r, state.open)) },
            text = { Text(shiftCloseResultMessage(r, state.open)) },
        )
    }
}

internal fun isPreviousShiftCloseResult(shift: LocalShiftEntity, current: ResolvedOpenShift?): Boolean =
    current != null && current.shiftId != shift.localId && current.shiftId != shift.serverShiftId

internal fun shiftCloseResultTitle(shift: LocalShiftEntity, current: ResolvedOpenShift?): String {
    val previous = isPreviousShiftCloseResult(shift, current)
    return when (shiftCloseResultKind(shift)) {
        ShiftCloseResultKind.REMOTE_RECONCILED ->
            if (previous) "Previous shift closed elsewhere" else "Shift closed elsewhere"
        ShiftCloseResultKind.LOCAL_CONFIRMED ->
            if (previous) "Previous shift closed — server confirmed" else "Shift closed — server confirmed"
    }
}

internal fun shiftCloseResultMessage(
    shift: LocalShiftEntity,
    current: ResolvedOpenShift? = null,
): String {
    val previous = isPreviousShiftCloseResult(shift, current)
    val subject = if (previous) "the previous shift" else "this shift"
    val outcome = when {
        shiftCloseResultKind(shift) == ShiftCloseResultKind.REMOTE_RECONCILED ->
            shift.lastError ?: "Live server reconciliation confirmed that this shift closed elsewhere."
        shift.varianceMinor == null ->
            "The server confirmed $subject is closed. Final variance is not available on this tablet yet; refresh shift history."
        shift.varianceMinor == 0L ->
            "The server confirmed $subject is closed. The drawer balanced exactly."
        shift.varianceMinor > 0 ->
            "The server confirmed $subject is closed. Over by ${shift.varianceMinor.asRupees()} — more cash than expected."
        else ->
            "The server confirmed $subject is closed. Short by ${(-shift.varianceMinor).asRupees()} — less cash than expected."
    }
    return buildString {
        appendLine(outcome)
        appendLine("Shift ID: ${shift.serverShiftId ?: shift.localId}")
        appendLine("Opened: ${formatHistoryDate(shift.openedAtMillis)}")
        appendLine("Closed: ${shift.closedAtMillis?.let(::formatHistoryDate) ?: "time unavailable"}")
        append("Counted cash: ${shift.countedMinor?.asRupees() ?: "not available"}")
        if (previous) {
            append("\n\nCurrent shift ${current?.shiftId} remains open. This receipt does not close it.")
        }
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
internal fun ShiftDashboardOverview(
    state: ShiftUiState,
    canClose: Boolean = true,
    presentation: WorkspacePresentationPolicy =
        WorkspaceFeatureProfiles.Active.presentationPolicy(),
    onRefresh: () -> Unit,
    onReview: () -> Unit,
) {
    val shift = state.open ?: return
    val accounting = shift.accountingBreakdownOrNull()
    var reconciliationExpanded by remember(shift.shiftId) { mutableStateOf(false) }
    val localState = shift.local?.state
    val confirmingOpen = shift.server == null && shift.local?.serverShiftId == null
    val status = shiftStatusCopy(
        hasOpenShift = true,
        localState = localState,
        confirmingOpen = confirmingOpen,
        offlineGamingSupported = state.offlineGamingSupported,
    )
    val statusColor = when {
        localState == ShiftState.CLOSE_REJECTED -> MaterialTheme.colorScheme.operationalDanger
        localState == ShiftState.CLOSE_PENDING -> MaterialTheme.colorScheme.operationalInformation
        confirmingOpen -> MaterialTheme.colorScheme.operationalWarning
        else -> MaterialTheme.colorScheme.operationalGood
    }
    val workStatus = when {
        state.rejectedShift != null -> "Saved open needs review"
        localState == ShiftState.CLOSE_REJECTED -> "Close rejected"
        localState == ShiftState.CLOSE_PENDING -> "Close waiting for server"
        state.closeOriginMessage != null -> "Close from the opening app"
        state.expectedMinor == null -> "Waiting for verified drawer total"
        !canClose -> "Review only · close permission required"
        !state.canClose -> "Close blocked · review requirements"
        else -> "Ready to count cash and review"
    }
    val opener = shift.openedByName?.takeIf(String::isNotBlank)
        ?: shift.openedByEmail?.takeIf(String::isNotBlank)
        ?: "Opener not yet verified"

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 700.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(Radius.shapeLg)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeLg)
                    .padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(42.dp).clip(Radius.shapeMd)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(statusColor))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (status.value == "Open") "Shift Open" else status.value,
                        color = statusColor, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("Opened by $opener · ${status.detail}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(Icons.Filled.LockClock, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Time opened", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                        Text(formatShiftBusinessTime(shift.openedAtMillis), color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeLg)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeLg)
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Daily collections", color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Current shift · server accounting", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onRefresh, enabled = !state.busy && !state.syncing) {
                        Text(if (state.syncing) "Refreshing…" else "Refresh")
                    }
                }
                if (accounting == null) {
                    Text(
                        "Collection breakdown is unavailable until this shift is confirmed by the server. " +
                            "No cash or payment total is assumed.",
                        color = MaterialTheme.colorScheme.operationalWarning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Net collections",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        accounting?.netCollectionsMinor?.asRupees() ?: "Unavailable",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (accounting != null) {
                        Text(
                            "Gross ${accounting.grossCollectionsMinor.asRupees()} · " +
                                "Refunds ${accounting.totalRefundsMinor.asRupees()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 520.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            ShiftOverviewMoneyCard(
                                label = "Cash",
                                value = accounting?.cashCollectionsMinor?.asRupees() ?: "—",
                                icon = Icons.Filled.Payments,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            ShiftOverviewMoneyCard(
                                label = "UPI",
                                value = accounting?.upiCollectionsMinor?.asRupees() ?: "—",
                                icon = Icons.Filled.QrCode,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            ShiftOverviewMoneyCard(
                                label = "Cash",
                                value = accounting?.cashCollectionsMinor?.asRupees() ?: "—",
                                icon = Icons.Filled.Payments,
                                modifier = Modifier.weight(1f),
                            )
                            ShiftOverviewMoneyCard(
                                label = "UPI",
                                value = accounting?.upiCollectionsMinor?.asRupees() ?: "—",
                                icon = Icons.Filled.QrCode,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (accounting != null &&
                    (accounting.cardCollectionsMinor != 0L || accounting.otherCollectionsMinor != 0L)
                ) {
                    Text(
                        "Also collected: Card ${accounting.cardCollectionsMinor.asRupees()} · " +
                            "Other ${accounting.otherCollectionsMinor.asRupees()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (accounting != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Reconciliation details",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Source totals and settled refund categories",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(
                            onClick = { reconciliationExpanded = !reconciliationExpanded },
                            modifier = Modifier.testTag("shift-reconciliation-details-toggle"),
                        ) {
                            Text(if (reconciliationExpanded) "Hide details" else "View details")
                        }
                    }
                    if (reconciliationExpanded) {
                        Column(
                            Modifier.fillMaxWidth().clip(Radius.shapeMd)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd)
                                .padding(Spacing.md)
                                .testTag("shift-reconciliation-details"),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            ShiftReconciliationDetailRow(
                                label = if (presentation.showsRestaurantOperations) {
                                    "POS receipts (may include tips)"
                                } else {
                                    "POS and gaming receipts (may include tips)"
                                },
                                amountMinor = accounting.posCollectionsMinor,
                            )
                            shiftLegacyMoneyRows(accounting, presentation).forEach { row ->
                                ShiftReconciliationDetailRow(
                                    label = row.label,
                                    amountMinor = row.amountMinor,
                                    isRefund = row.isRefund,
                                )
                            }
                            ShiftReconciliationDetailRow(
                                label = "Settled POS refunds",
                                amountMinor = accounting.settledPosRefundsMinor,
                                isRefund = true,
                            )
                            if (!presentation.showsMemberships && accounting.hasLegacyPrepaidMoney()) {
                                Text(
                                    "Historical hidden-module money remains included in Gross, Refunds and Net. " +
                                        "Owners should reconcile it against protected history; it has not been removed.",
                                    color = MaterialTheme.colorScheme.operationalWarning,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow = maxWidth < 520.dp
                    val cards: @Composable (Modifier, Modifier) -> Unit = { expectedModifier, readinessModifier ->
                        ShiftOverviewMoneyCard(
                            label = "Expected cash in drawer",
                            value = state.expectedMinor?.asRupees() ?: "Unavailable",
                            icon = Icons.Filled.AccountBalanceWallet,
                            tint = if (state.expectedMinor == null) MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.operationalGood,
                            modifier = expectedModifier,
                        )
                        ShiftOverviewMoneyCard(
                            label = "Close readiness",
                            value = workStatus,
                            icon = Icons.Filled.LockClock,
                            tint = if (localState == ShiftState.CLOSE_REJECTED || state.rejectedShift != null)
                                MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.operationalInformation,
                            modifier = readinessModifier,
                        )
                    }
                    if (narrow) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            cards(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            cards(Modifier.weight(0.9f), Modifier.weight(1.1f))
                        }
                    }
                }
                val reviewLabel = when {
                    state.rejectedShift != null -> "Review saved open"
                    localState == ShiftState.CLOSE_REJECTED -> "Review rejected close"
                    localState == ShiftState.CLOSE_PENDING -> "Review saved close"
                    state.closeOriginMessage != null -> "Review closing device"
                    !canClose -> "Review shift"
                    !state.canClose -> "Review close requirements"
                    else -> "Review & close shift"
                }
                Button(
                    onClick = onReview,
                    shape = Radius.shapeMd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .clip(Radius.shapeMd)
                        .background(
                            MaterialTheme.colorScheme.primary,
                        )
                        .border(1.dp, MaterialTheme.colorScheme.onPrimaryContainer, Radius.shapeMd),
                ) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                        Text(reviewLabel, modifier = Modifier.align(Alignment.Center),
                            fontWeight = FontWeight.Bold)
                        Icon(Icons.Filled.ChevronRight, contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftReconciliationDetailRow(
    label: String,
    amountMinor: Long,
    isRefund: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            amountMinor.asRupees(),
            color = if (isRefund && amountMinor > 0L) {
                MaterialTheme.colorScheme.operationalDanger
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ShiftOverviewMoneyCard(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color? = null,
    modifier: Modifier = Modifier,
) {
    val accent = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.heightIn(min = 64.dp)
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(Radius.shapeSm)
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(23.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ShiftSummaryRow(state: ShiftUiState) {
    val open = state.open
    val localState = open?.local?.state
    val confirming = open != null && open.server == null && open.local?.serverShiftId == null
    val statusCopy = shiftStatusCopy(
        hasOpenShift = open != null,
        localState = localState,
        confirmingOpen = confirming,
        offlineGamingSupported = state.offlineGamingSupported,
    )
    val opener = open?.openedByName?.takeIf(String::isNotBlank)
        ?: open?.openedByEmail?.takeIf(String::isNotBlank)
    val statusCard: @Composable (Modifier) -> Unit = { modifier ->
        CompactStatCard(
            label = "Shift status",
            value = statusCopy.value,
            detail = statusCopy.detail,
            icon = Icons.Filled.LockClock,
            tone = when {
                localState == ShiftState.CLOSE_REJECTED -> UiTone.Danger
                open == null || confirming || localState == ShiftState.CLOSE_PENDING -> UiTone.Warning
                else -> UiTone.Success
            },
            modifier = modifier,
        )
    }
    val historyCard: @Composable (Modifier) -> Unit = { modifier ->
        CompactStatCard(
            label = if (open == null) "Business-day collections" else "Opened by",
            value = if (open == null) state.businessDayHistory.size.toString() else (opener ?: "Verifying"),
            detail = if (open == null) "IST business days"
            else formatHistoryDate(open.openedAtMillis),
            icon = if (open == null) Icons.Filled.History else Icons.Filled.Person,
            tone = UiTone.Neutral,
            modifier = modifier,
        )
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 520.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                statusCard(Modifier.weight(1.2f))
                historyCard(Modifier.weight(0.8f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                statusCard(Modifier.fillMaxWidth())
                historyCard(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PastShiftsPanel(state: ShiftUiState, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Business-day collections",
        subtitle = "Recorded shift segments · unallocated owner actuals are separate in Finance",
        icon = Icons.Filled.History,
        modifier = modifier,
    ) {
        state.historyMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        val businessDays = state.businessDayHistory
        if (businessDays.isEmpty()) {
            if (state.historyRefreshing) {
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                DesignedEmptyState(
                    title = "No closed business days yet",
                    body = "Completed shifts will appear as one IST business-day collection with their original segments retained.",
                    icon = Icons.Filled.History,
                    modifier = Modifier.heightIn(min = 170.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(businessDays, key = { it.stableId }) { day -> BusinessDayHistoryRow(day) }
            }
        }
        Text(
            "Built from up to the latest 200 immutable shift segments for this device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Startup placeholder for the current till's Room query. It deliberately has
 * no shift action: a null `open` value is not authoritative until the scoped
 * local query has emitted at least once.
 */
@Composable
internal fun ShiftLoadingWorkspace() {
    SectionCard(
        title = "Checking shift",
        subtitle = "Loading the saved shift state for this till.",
        icon = Icons.Filled.LockClock,
        modifier = Modifier.fillMaxWidth().testTag("shift-loading-workspace"),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Checking this device before shift actions become available.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
            currentShift = null,
            workspaceLabel = state.workspaceLabel,
            onRetry = vm::retryRejectedOpen,
            onVerifyAndClear = vm::verifyAndClearRejectedOpen,
        )
        Spacer(Modifier.height(14.dp))
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val opening: @Composable () -> Unit = {
            OpenShiftForm(online=state.online, busy=state.busy, canOpen=canOpen,
                blockedByRejectedShift=state.rejectedShift != null,
                offlineGamingSupported=state.offlineGamingSupported, onOpenShift=vm::openShift)
        }
        if (maxWidth >= 900.dp) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Spacing.lg),verticalAlignment=Alignment.Top) {
            Box(Modifier.weight(1f)) { opening() }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(Spacing.md)) {
                ShiftSummaryRow(state)
                PastShiftsPanel(state,Modifier.fillMaxWidth().height(400.dp))
            }
        } else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Spacing.md)) {
            ShiftSummaryRow(state)
            opening()
            PastShiftsPanel(state,Modifier.fillMaxWidth().height(280.dp))
        }
    }
}

/** Actual open-shift form, separated from ViewModel wiring for rendered UI tests. */
@Composable
internal fun OpenShiftForm(
    online: Boolean,
    busy: Boolean,
    canOpen: Boolean,
    blockedByRejectedShift: Boolean,
    onOpenShift: (Long) -> Unit,
    offlineGamingSupported: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitFocusRequester = remember { FocusRequester() }
    var float by remember { mutableStateOf("") }
    val parsedFloat = remember(float) { parseRupeesToMinor(float) }
    val floatMinor = parsedFloat ?: 0L
    val validFloat = float.isBlank() || parsedFloat != null

    BoxWithConstraints(Modifier.fillMaxWidth().testTag("shift-open-form")) {
        // The inline Gaming pane cannot fit an icon, readable title and the
        // submit action in one row. Keep the normal wide-screen header intact.
        val compact = maxWidth < 440.dp
        val submit: @Composable () -> Unit = {
            ErpButton(
                text = "Open shift with ${floatMinor.asRupees()}",
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    // Move focus off the money field before replacing the
                    // form so OEM focus restoration cannot reopen the IME.
                    submitFocusRequester.requestFocus()
                    onOpenShift(floatMinor)
                },
                enabled = canOpen && validFloat && !blockedByRejectedShift,
                busy = busy,
                modifier = Modifier.focusRequester(submitFocusRequester)
                    .then(if (compact) Modifier.fillMaxWidth() else Modifier),
            )
        }
        SectionCard(
            title = "Open shift",
            subtitle = "Record the drawer cash before accepting the first payment.",
            icon = Icons.Filled.AccountBalanceWallet,
            modifier = Modifier.fillMaxWidth(),
            action = if (compact) null else { { submit() } },
        ) {
            Text(
                "Billing remains blocked until the shift is open. The opening float may be ₹0.00 when the drawer starts empty.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!online) {
                Text(
                    if (offlineGamingSupported) {
                        "No connection — this verified workspace supports saving the shift and Gaming in order for automatic sync."
                    } else {
                        "No connection — the shift can be saved, but reconnect to confirm it before starting Gaming."
                    },
                    color = MaterialTheme.colorScheme.operationalInformation,
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
            if (compact) submit()
        }
    }
}

@Composable
internal fun RejectedShiftCard(
    rejected: LocalShiftEntity,
    busy: Boolean,
    online: Boolean,
    canRecover: Boolean,
    currentShift: ResolvedOpenShift?,
    workspaceLabel: String?,
    onRetry: () -> Unit,
    onVerifyAndClear: () -> Unit,
    actionModifier: Modifier = Modifier,
) {
    val actions = rejectedOpenRecoveryActions(
        hasCurrentShift = currentShift != null,
        online = online,
        canRecover = canRecover,
        busy = busy,
    )
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.operationalDanger, Radius.shapeLg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (currentShift == null) "Couldn't open a shift" else "A shift is already open",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.operationalDanger,
        )
        if (currentShift != null) {
            Text(
                shiftAlreadyOpenMessage(currentShift, workspaceLabel),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Saved attempt: ${rejected.lastError ?: "The server refused the duplicate open."}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text(rejected.lastError ?: "The server refused this shift open.", color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            actions.guidance,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!online) {
            Text(
                "A live connection is required to clear the attempt safely.",
                color = MaterialTheme.colorScheme.operationalWarning,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(actionModifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

internal class ShiftCloseCallbacks(
    val onRetryRejectedOpen: () -> Unit,
    val onVerifyAndClearRejectedOpen: () -> Unit,
    val onContinueShift: () -> Unit,
    val onRetryClose: () -> Unit,
    val onRefresh: () -> Unit,
    val currentShiftIdentity: () -> String?,
    val onCloseShift: (Long) -> Unit,
)

private val DrawerCountDraftSaver = listSaver<SnapshotStateMap<Long, String>, String>(
    save = { counts ->
        DENOMINATIONS.flatMap { note ->
            counts[note]?.takeIf(String::isNotBlank)?.let { count ->
                listOf(note.toString(), count)
            }.orEmpty()
        }
    },
    restore = { saved ->
        mutableStateMapOf<Long, String>().apply {
            saved.chunked(2).forEach { entry ->
                val note = entry.getOrNull(0)?.toLongOrNull()
                val count = entry.getOrNull(1)
                if (note in DENOMINATIONS && count?.toLongOrNull() != null) {
                    put(requireNotNull(note), count)
                }
            }
        }
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CloseShiftCard(
    state: ShiftUiState,
    canClosePermission: Boolean,
    canOpenPermission: Boolean,
    compactLayout: Boolean,
    reviewAnchor: BringIntoViewRequester,
    callbacks: ShiftCloseCallbacks,
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
    val counts = rememberSaveable(
        shiftIdentity,
        closeBlocked,
        saver = DrawerCountDraftSaver,
    ) { mutableStateMapOf<Long, String>() }
    var countReviewed by rememberSaveable(shiftIdentity, closeBlocked) { mutableStateOf(false) }
    val draftCountedMinor = if (countReviewed) drawerCountedMinor(counts) else null
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
    var drawerCountOpen by remember(shiftIdentity, closeBlocked) { mutableStateOf(false) }

    val cardModifier = if (compactLayout) Modifier.fillMaxWidth() else Modifier.fillMaxSize()
    Column(
        cardModifier.clip(Radius.shapeLg)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.52f), Radius.shapeLg)
            .padding(Spacing.lg),
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
                currentShift = shift,
                workspaceLabel = state.workspaceLabel,
                onRetry = callbacks.onRetryRejectedOpen,
                onVerifyAndClear = callbacks.onVerifyAndClearRejectedOpen,
                actionModifier = Modifier.bringIntoViewRequester(reviewAnchor),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Close current shift", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("Count the drawer, review the variance, then confirm.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            OperationalStatusBadge(
                label = when {
                    closing -> "Awaiting server"
                    closeRejected -> "Still open"
                    else -> "Open"
                },
                tone = when {
                    closing -> UiTone.Information
                    closeRejected -> UiTone.Danger
                    else -> UiTone.Success
                },
            )
        }
        ShiftOwnershipSummary(
            shift = shift,
            currentUserId = state.currentUserId,
            workspaceLabel = state.workspaceLabel,
            canClose = canClosePermission && state.canClose,
        )
        state.closeOriginMessage?.let { message ->
            ShiftCloseOriginFeedback(
                message = message,
                modifier = if (state.rejectedShift == null && !closeBlocked) {
                    Modifier.bringIntoViewRequester(reviewAnchor)
                } else {
                    Modifier
                },
            )
        }
        PanelDivider()
        InfoRow(label = "Opening float", value = shift.openingFloatMinor.asRupees())
        InfoRow(
            label = "Expected in drawer",
            value = state.expectedMinor?.asRupees() ?: "Not available offline",
            valueColor = if (state.expectedMinor == null) MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Daily collections are summarized once in the overview. Count only the physical drawer cash here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )

        if (closing) {
            Column(
                (if (state.rejectedShift == null) {
                    Modifier.fillMaxWidth().bringIntoViewRequester(reviewAnchor)
                } else {
                    Modifier.fillMaxWidth()
                }).clip(Radius.shapeSm)
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Close saved — waiting for server confirmation" +
                        (if (!state.online) " (no connection right now)" else "") + ".",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    closePresentation.savedCountMessage(
                        valid = "The saved drawer count is %s. This queued close is locked to that exact amount.",
                        missing = "This older queued close has no valid saved drawer count on this tablet. " +
                            "Do not create another close; reconnect and ask a protected owner to review it.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                shift.local?.lastError?.takeIf(String::isNotBlank)?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (closeRejected) {
            Column(
                (if (state.rejectedShift == null) {
                    Modifier.fillMaxWidth().bringIntoViewRequester(reviewAnchor)
                } else {
                    Modifier.fillMaxWidth()
                }).clip(Radius.shapeSm)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.operationalDanger, Radius.shapeSm)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Shift is still open",
                    color = MaterialTheme.colorScheme.operationalDanger,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    shift.local?.lastError ?: "The server refused to close this shift.",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (state.closeOriginMessage != null) {
                        "The saved count is retained. Retry is unavailable until the opening app is verified. Continue shift removes only this rejected close request so this tablet can resume work; it does not close the server shift or change recorded cash."
                    } else closePresentation.savedCountMessage(
                        valid = "Saved drawer count: %s. Resolve any unpaid orders or active sessions, " +
                            "then Retry saved close to reuse this exact amount. Choose Continue shift " +
                            "to discard this close request, resume billing, and count again later.",
                        missing = "This older rejected close has no valid saved drawer count. Retry is " +
                            "disabled. Choose Continue shift, then count the drawer again before closing.",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = callbacks.onContinueShift,
                        enabled = canClosePermission && !state.busy &&
                            closePresentation.canContinueShift,
                    ) {
                        Text("Continue shift")
                    }
                    Button(
                        onClick = callbacks.onRetryClose,
                        enabled = canClosePermission && !state.busy && state.canClose &&
                            closePresentation.canRetrySavedClose,
                    ) {
                        Text("Retry saved close")
                    }
                }
            }
        }

        if (closePresentation.usesSavedCount) {
            Text("Saved drawer count", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                closePresentation.displayedCountedMinor?.asRupees() ?: "Saved count unavailable",
                color = if (closePresentation.displayedCountedMinor == null) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Denomination fields are locked because this close already has a durable saved count.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            if (!compactLayout) Spacer(Modifier.weight(1f))
        }
        Column(
            if (state.rejectedShift == null && !closeBlocked && state.closeOriginMessage == null) {
                Modifier.fillMaxWidth().bringIntoViewRequester(reviewAnchor)
            } else {
                Modifier.fillMaxWidth()
            },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelDivider()
            if (!closePresentation.usesSavedCount) {
                Text("Count the drawer", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "Count each note and coin in a focused view. Your total and difference stay visible here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                ErpButton(
                    text = if (!countReviewed) "Count cash" else "Edit cash count",
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
                Text("Counted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    displayedCountedMinor?.asRupees() ?: if (closePresentation.usesSavedCount) "Saved count unavailable" else "Not counted yet",
                    color = if (displayedCountedMinor == null) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (variance != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Difference", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when {
                            variance == 0L -> "balanced"
                            variance > 0 -> "over ${variance.asRupees()}"
                            else -> "short ${(-variance).asRupees()}"
                        },
                        color = if (variance == 0L) MaterialTheme.colorScheme.operationalGood else MaterialTheme.colorScheme.operationalDanger,
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
                    onClick = callbacks.onRefresh,
                    enabled = !closing,
                    busy = state.busy && !closing,
                    intent = ActionIntent.Secondary,
                    leadingIcon = Icons.Filled.Refresh,
                )
                Spacer(Modifier.weight(1f))
                ErpButton(
                    text = if (closing) "Waiting for server" else "Close shift",
                    onClick = {
                        closePresentation.displayedCountedMinor?.let { counted ->
                            confirmation = ShiftCloseConfirmation(shiftIdentity, counted)
                        }
                    },
                    enabled = canClosePermission && !closeBlocked && !state.busy && state.canClose && closePresentation.displayedCountedMinor != null,
                    busy = closing,
                    intent = ActionIntent.Destructive,
                )
            }
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
                countReviewed = true
                drawerCountOpen = false
            },
        )
    }

    val activeConfirmation = confirmation?.takeIf {
        it.isFor(shiftIdentity) && !closeBlocked
    }
    if (activeConfirmation != null && canClosePermission) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                        val currentIdentity = callbacks.currentShiftIdentity()
                        if (activeConfirmation.isFor(currentIdentity)) {
                            callbacks.onCloseShift(activeConfirmation.countedMinor)
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
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                .widthIn(max = 960.dp)
                .heightIn(max = 720.dp),
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = Radius.shapeXl,
        tonalElevation = 0.dp,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, Radius.shapeXl)
            .semantics { contentDescription = "Drawer count dialog" },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .imePadding().padding(Spacing.lg),
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
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Enter how many of each note or coin is physically in the drawer.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    valueColor = if (expectedMinor == null) MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.onSurface,
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
                        differenceMinor == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        differenceMinor == 0L -> MaterialTheme.colorScheme.operationalGood
                        else -> MaterialTheme.colorScheme.operationalDanger
                    },
                )
            }

            PanelDivider()
            DenominationCountGrid(
                compactLayout = true,
                counts = draftCounts,
                onCountChange = { note, value -> draftCounts[note] = value },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
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
                    Text("₹$note", color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        (note * 100 * (counts[note]?.toLongOrNull() ?: 0L)).asRupees(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    draftCountedMinor: Long?,
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
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ShiftOwnershipSummary(
    shift: ResolvedOpenShift,
    currentUserId: String?,
    workspaceLabel: String?,
    canClose: Boolean,
) {
    val opener = shift.openedByName?.takeIf(String::isNotBlank)
        ?: shift.openedByEmail?.takeIf(String::isNotBlank)
        ?: "Opener not yet verified"
    val opened = formatHistoryDate(shift.openedAtMillis)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Opened by $opener",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!shift.openedByEmail.isNullOrBlank() && shift.openedByEmail != opener) {
            Text(shift.openedByEmail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Text("Opened $opened", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        workspaceLabel?.let {
            Text("Workspace: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        shiftCloseHandoverMessage(shift, currentUserId, canClose)?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.operationalInformation,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun formatHistoryDate(epochMillis: Long): String =
    epochMillis.businessDateTime()

@Composable
private fun BusinessDayHistoryRow(day: ShiftBusinessDaySummary) {
    var expanded by remember(day.stableId) { mutableStateOf(false) }
    val totalsAvailable = day.grossCollectionsMinor != null &&
        day.totalRefundsMinor != null && day.netCollectionsMinor != null
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            day.dateLabel,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "First open ${formatShiftBusinessTime(day.firstOpenedAtMillis)} by ${day.firstOpenerLabel}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            when {
                day.hasOpenSegment -> "Business day still open"
                day.finalClosedAtMillis != null ->
                    "Final close ${formatShiftBusinessTime(day.finalClosedAtMillis)} by ${day.finalCloserLabel}"
                else -> "Final close time unavailable — review the shift segments"
            },
            color = if (day.finalClosedAtMillis == null) MaterialTheme.colorScheme.operationalWarning else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CollectionMetric(
                label = "Gross",
                value = day.grossCollectionsMinor?.asRupees() ?: "Pending",
                modifier = Modifier.weight(1f),
            )
            CollectionMetric(
                label = "Refunds",
                value = day.totalRefundsMinor?.asRupees() ?: "Pending",
                modifier = Modifier.weight(1f),
                valueColor = if ((day.totalRefundsMinor ?: 0L) > 0L) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
            )
            CollectionMetric(
                label = "Net",
                value = day.netCollectionsMinor?.asRupees() ?: "Pending",
                modifier = Modifier.weight(1f),
            )
        }
        if (!totalsAvailable) {
            Text(
                "Combined totals will appear after every segment has authoritative server accounting.",
                color = MaterialTheme.colorScheme.operationalWarning,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                if (expanded) {
                    "Hide ${day.segments.size} shift segment${if (day.segments.size == 1) "" else "s"}"
                } else {
                    "Review ${day.segments.size} shift segment${if (day.segments.size == 1) "" else "s"}"
                },
            )
        }
        if (expanded) {
            PanelDivider()
            day.segments.forEachIndexed { index, segment ->
                Text(
                    "Shift segment ${index + 1}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                HistoryRow(segment)
            }
        }
    }
}

@Composable
private fun HistoryRow(s: ShiftHistoryRow) {
    val opener = shiftHistoryOpenerLabel(s)
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
    ) {
        Text(
            "Opened ${formatHistoryDate(s.openedAtMillis)} by $opener",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (s.closedAtMillis != null) {
            Text(
                "Closed ${formatHistoryDate(s.closedAtMillis)} by ${shiftHistoryCloserLabel(s)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            "counted ${s.countedMinor?.asRupees() ?: "—"} · " +
                when {
                    s.varianceMinor == null -> "not synced yet"
                    s.varianceMinor == 0L -> "balanced"
                    s.varianceMinor > 0 -> "over ${s.varianceMinor.asRupees()}"
                    else -> "short ${(-s.varianceMinor).asRupees()}"
                },
            style = MaterialTheme.typography.labelSmall,
            color = if (s.varianceMinor == 0L) MaterialTheme.colorScheme.operationalGood else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Recorded expected drawer ${s.expectedMinor?.asRupees() ?: "Not retained"} · recorded count / variance above remain unchanged.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if ((s.tenderCorrectionMinor ?: 0L) != 0L) {
            Text("Reconstructed tender · identified payment correction",style=MaterialTheme.typography.labelMedium)
            Text("Cash ${s.reconstructedCashCollectionsMinor?.asRupees() ?: "Unavailable"} · UPI ${s.reconstructedUpiCollectionsMinor?.asRupees() ?: "Unavailable"}",style=MaterialTheme.typography.bodySmall)
            Text("Reconstructed expected drawer ${s.reconstructedExpectedMinor?.asRupees() ?: "Unavailable"}. This does not replace the original counted cash or recorded variance.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.source == ShiftHistorySource.LOCAL) {
            Text(
                "Saved on this tablet; waiting for server history refresh.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.operationalWarning,
            )
        } else if (s.netCollectionsMinor != null) {
            Text(
                "net collections ${s.netCollectionsMinor.asRupees()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ShiftCloseOriginFeedback(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(Radius.shapeMd)
            .background(MaterialTheme.colorScheme.operationalWarning.copy(alpha = 0.10f))
            .border(1.dp, MaterialTheme.colorScheme.operationalWarning.copy(alpha = 0.6f), Radius.shapeMd)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text("Verify the opening app before closing", color = MaterialTheme.colorScheme.operationalWarning, fontWeight = FontWeight.SemiBold)
        Text(message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}

internal fun shiftCloseHandoverMessage(
    shift: ResolvedOpenShift,
    currentUserId: String?,
    canClose: Boolean,
): String? {
    if (!canClose) return null
    val opener = shift.openedByName?.takeIf(String::isNotBlank)
        ?: shift.openedByEmail?.takeIf(String::isNotBlank)
        ?: "the original staff member"
    return when {
        shift.openedByUserId == null ->
            "Opener identity is still syncing. You may close this shift; your signed-in account will be recorded as the closer."
        shift.openedByUserId == currentUserId ->
            "You opened this shift. Your signed-in account will also be recorded when you close it."
        else ->
            "You may close this shift even though $opener opened it. $opener remains the opener; your signed-in account will be recorded as the closer."
    }
}
