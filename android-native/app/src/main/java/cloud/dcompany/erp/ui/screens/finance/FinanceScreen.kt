package cloud.dcompany.erp.ui.screens.finance

import android.app.DatePickerDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.FinanceAccess
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.CostingCoverage
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.DecimalField
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.FormDialog
import cloud.dcompany.erp.ui.components.PickerField
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import kotlin.math.abs

/**
 * Finance combines server-authoritative receipt-basis reporting with
 * permission-gated, idempotent offline entry for expenses, assets and partner
 * capital movements. Existing evidence is never edited optimistically.
 */
@Composable
fun FinanceScreen(
    access: FinanceAccess = FinanceAccess(),
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    val vm: FinanceViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    SideEffect { vm.updateAccess(access) }
    FinanceContent(state, vm, access, presentation)
}

@Composable
private fun FinanceContent(
    state: FinanceUiState,
    vm: FinanceViewModel,
    access: FinanceAccess,
    presentation: WorkspacePresentationPolicy,
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Expenses", "Collections", "Tip payouts", "Assets", "Partners")

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Header(state, onRefresh = vm::load)
        when {
            // Nothing to show yet and it failed: this is the whole screen.
            !state.loaded && state.error != null -> ErrorBlock(state.error, vm::load)

            !state.loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Brand.Information)
                    Text("Reading the books…", color = Brand.ForegroundMuted)
                }
            }

            else -> {
                PremiumTabBar(
                    options = tabs.mapIndexed { index, title ->
                        TabOption(index.toString(), title)
                    },
                    selectedId = tab.toString(),
                    onSelect = { selected -> tab = selected.toIntOrNull() ?: 0 },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                if (!state.online) {
                    FinanceOfflineBanner(state.lastUpdatedAtMillis)
                }
                // A refresh that failed on top of good data: keep the figures,
                // but never let the failure pass unmentioned.
                if (state.error != null && state.online) {
                    ErrorBanner(state.error, vm::load)
                }
                state.pendingOnlineWrite?.let { pending ->
                    Box(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        PendingOnlineFinanceWriteBanner(
                            pending = pending,
                            online = state.online,
                            canRetry = access.canRecordExpenses,
                            busy = state.busy,
                            onRetry = vm::retryPendingOnlineWrite,
                        )
                    }
                }
                if (state.notice != null) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        NoticeBanner(state.notice, vm::dismissNotice)
                    }
                }
                if (state.pendingExpenses.isNotEmpty() || state.pendingAssets.isNotEmpty() ||
                    state.pendingCapitalEntries.isNotEmpty()
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        PendingFinanceChangesPanel(state, vm, access)
                    }
                }
                when (tab) {
                    0 -> OverviewTab(state, presentation)
                    1 -> ExpensesTab(state, vm, access.canRecordExpenses)
                    2 -> ManualCollectionsTab(state, vm, access.canRecordExpenses, presentation)
                    3 -> TipPayoutsTab(state, vm, access.canRecordExpenses)
                    4 -> AssetsTab(state, vm, access.canManageAssets, presentation)
                    else -> PartnersTab(state, vm, access.canRecordPartnerCapital)
                }

                when (val dialog = state.dialog) {
                    FinanceDialog.ExpenseForm -> if (access.canRecordExpenses) ExpenseCreateDialog(state, vm)
                    FinanceDialog.AssetForm -> if (access.canManageAssets) {
                        AssetCreateDialog(state, vm, presentation)
                    }
                    is FinanceDialog.CapitalEntryForm -> if (access.canRecordPartnerCapital) {
                        CapitalEntryCreateDialog(dialog.partner, state, vm)
                    }
                    FinanceDialog.ManualCollectionForm -> if (access.canRecordExpenses) {
                        ManualCollectionCreateDialog(state, vm, presentation)
                    }
                    FinanceDialog.TipPayoutForm -> if (access.canRecordExpenses) {
                        TipPayoutCreateDialog(state, vm)
                    }
                    is FinanceDialog.VoidManualCollection -> if (access.canRecordExpenses) {
                        VoidManualCollectionDialog(dialog.row, state, vm)
                    }
                    is FinanceDialog.VoidTipPayout -> if (access.canRecordExpenses) {
                        VoidTipPayoutDialog(dialog.row, state, vm)
                    }
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun Header(state: FinanceUiState, onRefresh: () -> Unit) {
    Column(
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ActionBar(
            leading = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (state.loaded) "Financial controls" else "Loading financial records",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Review server-backed receipt-basis figures and authorised entries.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailing = {
                ErpButton(
                    text = if (state.loading) "Refreshing" else "Refresh",
                    onClick = onRefresh,
                    intent = ActionIntent.Secondary,
                    busy = state.loading,
                    leadingIcon = Icons.Default.Refresh,
                )
            },
        )
        if (state.loading && state.loaded) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Information,
                trackColor = Brand.Surface,
            )
        }
    }
}

// ============================================================================
// OVERVIEW
// ============================================================================
@Composable
private fun OverviewTab(state: FinanceUiState, presentation: WorkspacePresentationPolicy) {
    val pl = state.pl ?: return
    val metrics = state.metrics
    val distributable = state.distributable

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "This period · ${pl.periodStart.asDayShort()} – ${pl.periodEnd.asDay()}",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )

        val coverage = state.verifiedCostingCoverage
        Panel(border = if (coverage?.isComplete == true) Brand.Good else Brand.Warning) {
            Text(
                coverage?.warningTitle ?: "Inventory costing status unavailable",
                color = if (coverage?.isComplete == true) Brand.Good else Brand.Warning,
                fontWeight = FontWeight.Bold,
            )
            Text(
                coverage?.warningDetail
                    ?: "COGS may be understated, so gross profit and operating profit may be overstated " +
                        "until recipe and ingredient-cost coverage can be verified.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.ForegroundMuted,
            )
        }

        if (state.periodIdle) {
            Panel(border = Brand.BorderSubtle) {
                Text("No activity this period yet.", color = Brand.Foreground, fontWeight = FontWeight.Bold)
                Text(
                    "Take an order in POS or record an authorised expense. The next successful " +
                        "sync will update these server-calculated figures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
            }
        }

        StatGrid(
            listOfNotNull(
                StatSpec(
                    "Operating profit · this period",
                    pl.netProfitMinor.asRupees(),
                    "net revenue less COGS, expenses and depreciation",
                    if (pl.netProfitMinor < 0) Tone.Bad else Tone.Default,
                ),
                distributable?.let {
                    val spendable = it.authoritativeSpendableCashMinor()
                    StatSpec(
                        "Safe-to-distribute cap",
                        if (spendable == null) "Unavailable" else it.safeToDistributeMinor.asRupees(),
                        if (spendable == null) {
                            CASH_CONTRACT_UNAVAILABLE
                        } else {
                            "lower of profit-based and spendable-cash capacity after reserve"
                        },
                        if (spendable == null) Tone.Bad else Tone.Default,
                    )
                },
                distributable?.let {
                    val spendable = it.authoritativeSpendableCashMinor()
                    StatSpec(
                        SPENDABLE_FUNDS_LABEL,
                        spendable?.asRupees() ?: "Unavailable",
                        if (spendable == null) CASH_CONTRACT_UNAVAILABLE else SPENDABLE_FUNDS_DETAIL,
                        if (spendable == null || spendable < 0) Tone.Bad else Tone.Default,
                    )
                },
                metrics?.let {
                    StatSpec(
                        if (presentation.showsRestaurantOperations) {
                            "Average order value · this period"
                        } else {
                            "Average paid bill · this period"
                        },
                        it.aovMinor.asRupees(),
                        countLabel(it.ordersCount, "paid order"),
                    )
                },
            ),
        )

        Panel {
            SectionTitle("Profit and loss")
            Spacer(Modifier.height(6.dp))
            PlRow(
                if (presentation.showsRestaurantOperations) "Net revenue (after GST)" else "Net revenue",
                pl.revenueMinor,
            )
            if (pl.membershipsMinor > 0) {
                PlRow(
                    presentation.includedPrepaidRevenueLabel,
                    pl.membershipsMinor,
                    sub = presentation.prepaidRevenueDetail,
                )
            }
            PlRow(
                "Less: cost of goods sold",
                pl.cogsMinor,
                sub = if (presentation.showsRestaurantOperations) {
                    "what the food/drinks/items you sold actually cost you"
                } else {
                    "what the products and services you sold actually cost you"
                },
                less = true,
            )
            PlRow("Gross profit", pl.grossProfitMinor, bold = true)
            HairLine()
            PlRow("Less: total expenses", pl.expensesMinor, less = true)
            if (pl.depreciationMinor > 0) {
                PlRow(
                    "Less: equipment depreciation",
                    pl.depreciationMinor,
                    sub = "straight-line, computed from the asset register",
                    less = true,
                )
            }
            HairLine()
            PlRow(
                "Operating profit",
                pl.netProfitMinor,
                sub = "after equipment depreciation",
                bold = true,
            )
        }

        if (metrics != null) {
            SectionTitle(
                "Business metrics · ${metrics.periodStart.asDayShort()} – ${metrics.periodEnd.asDayShort()}",
            )
            StatGrid(metrics.presentedMetrics(presentation).map {
                StatSpec(it.label, it.value, it.detail)
            })
            if (presentation.showsMemberships) {
                Note(
                    "Memberships are prepaid manual terms, not recurring subscriptions. " +
                        "MRR/ARR stay hidden until recurring billing is operating.",
                )
            } else if (pl.membershipsMinor > 0) {
                Note(
                    "Historical hidden-module prepaid revenue remains included in net revenue. " +
                        "Owners should reconcile it against the protected audit history; it has not been removed.",
                )
            }
        }
    }
}

// ============================================================================
// EXPENSES
// ============================================================================
@Composable
private fun ExpensesTab(state: FinanceUiState, vm: FinanceViewModel, canWrite: Boolean) {
    if (state.expenses.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (!canWrite) {
                ViewOnlyNotice("Expenses are view only — ask an owner or manager to record one.")
            }
            ExpenseActionBar(state, canWrite, vm::openExpenseForm)
            EmptyBlock(
                title = "No expenses recorded yet",
                body = if (canWrite) {
                    "Use New expense to create the first evidence-backed entry. It is saved on this tablet first and syncs safely."
                } else {
                    "No expense entries are available for this account and branch."
                },
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!canWrite) {
            item {
                ViewOnlyNotice("Expenses are view only — ask an owner or manager to record one.")
            }
        }
        item {
            ExpenseActionBar(state, canWrite, vm::openExpenseForm)
        }
        items(state.expenses, key = { it.id }) { expense ->
            ExpenseRow(expense, state.categoryName(expense.categoryId))
        }
        item {
            Note("Authoritative expense entries, newest first. Corrections require an authorised evidence-preserving workflow.")
        }
    }
}

@Composable
private fun ExpenseActionBar(state: FinanceUiState, canWrite: Boolean, onCreate: () -> Unit) {
    ActionBar(
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    countLabel(state.expenses.size, "expense"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Loaded history total ${state.expenseTotalMinor.asRupees()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.ForegroundMuted,
                )
            }
        },
        trailing = {
            ErpButton(
                text = "New expense",
                onClick = onCreate,
                enabled = canWrite,
                leadingIcon = Icons.Default.Add,
            )
        },
    )
}

@Composable
private fun ExpenseRow(expense: Expense, categoryName: String) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    categoryName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${expense.paidAt.asDay()} · ${expense.vendorName ?: "No vendor"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                expense.invoiceNo?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Invoice $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                }
                expense.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    expense.amountMinor.asRupees(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    paidViaLabel(expense.paidVia),
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
        }
    }
}

// ============================================================================
// MANUAL COLLECTIONS — online-only immutable off-POS revenue register
// ============================================================================
@Composable
private fun ManualCollectionsTab(
    state: FinanceUiState,
    vm: FinanceViewModel,
    canWrite: Boolean,
    presentation: WorkspacePresentationPolicy,
) {
    val totals = state.collectionTotals
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OperationalBanner(
                title = "Use only for genuine money collected outside POS",
                detail =
                    "This adds unitemized revenue and payment movement, but creates no order, invoice, item mix, tax split or automatic COGS. Mistakes must be voided with a reason.",
                tone = UiTone.Warning,
                icon = Icons.Default.Payments,
            )
        }
        if (!canWrite) {
            item {
                ViewOnlyNotice("Manual collections are view only — ask an owner or manager to record or void one.")
            }
        }
        item {
            StatGrid(
                listOf(
                    StatSpec("Active total", totals.totalMinor.asRupees(), countLabel(totals.activeCount, "entry")),
                    StatSpec("Cash", totals.cashMinor.asRupees()),
                    StatSpec("UPI", totals.upiMinor.asRupees()),
                    StatSpec(
                        "Card + bank",
                        (totals.cardMinor + totals.bankMinor).asRupees(),
                        countLabel(totals.voidedCount, "voided entry"),
                    ),
                ),
                columns = 4,
            )
        }
        item {
            ActionBar(
                leading = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Immutable collection register",
                            style = MaterialTheme.typography.titleSmall,
                            color = Brand.Foreground,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Newest business date first · server-authoritative",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.ForegroundMuted,
                        )
                    }
                },
                trailing = {
                    ErpButton(
                        text = "Add collection",
                        onClick = vm::openManualCollectionForm,
                        enabled = canWrite && state.online &&
                            state.pendingOnlineWrite == null && state.branches.isNotEmpty(),
                        leadingIcon = Icons.Default.Add,
                    )
                },
            )
        }
        if (!state.online) {
            item {
                Note("Online-only accounting: reconnect and refresh before recording or voiding a collection. Nothing is queued offline.")
            }
        }
        if (state.manualCollections.size == 500) {
            item {
                Note("Showing the newest 500 records. Use period reports for older collection totals.")
            }
        }
        if (state.manualCollections.isEmpty()) {
            item {
                Panel {
                    SectionTitle("No manual collections recorded")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (presentation.showsRestaurantOperations) {
                            "Normal sales should continue through Tables, Gaming or Shisha into POS. Use Add collection only when no itemized order exists."
                        } else {
                            "Normal sales should continue through Gaming or direct counter POS. Use Add collection only when no itemized bill exists."
                        },
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(state.manualCollections, key = { it.id }) { row ->
                ManualCollectionRow(
                    row = row,
                    branchName = state.branches.firstOrNull { it.id == row.branchId }?.name
                        ?: "Unknown shop",
                    canVoid = canWrite && state.online && state.pendingOnlineWrite == null,
                    onVoid = { vm.openVoidManualCollection(row) },
                )
            }
        }
    }
}

@Composable
private fun ManualCollectionRow(
    row: ManualCollection,
    branchName: String,
    canVoid: Boolean,
    onVoid: () -> Unit,
) {
    Panel(border = if (row.isVoided) Brand.Danger.copy(alpha = 0.45f) else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    row.sourceRef,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (row.isVoided) TextDecoration.LineThrough else null,
                )
                Text(
                    "${row.businessDate.asDay()} · $branchName · ${paidViaLabel(row.method)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "Recorded by ${row.createdByName ?: "verified employee"} · ${row.createdAt.asDay()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                row.note?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.ForegroundMuted)
                }
                row.voidReason?.let {
                    Text("Void reason: $it", style = MaterialTheme.typography.bodySmall, color = Brand.Danger)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    row.amountMinor.asRupees(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (row.isVoided) Brand.ForegroundMuted else Brand.Foreground,
                    textDecoration = if (row.isVoided) TextDecoration.LineThrough else null,
                )
                if (row.isVoided) {
                    Text("VOIDED", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                } else {
                    ErpButton(
                        text = "Void",
                        onClick = onVoid,
                        enabled = canVoid,
                        intent = ActionIntent.Destructive,
                        leadingIcon = Icons.Default.Block,
                    )
                }
            }
        }
    }
}

// ============================================================================
// TIP PAYOUTS — online-only settlement of Tips Payable
// ============================================================================
@Composable
private fun TipPayoutsTab(state: FinanceUiState, vm: FinanceViewModel, canWrite: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OperationalBanner(
                title = "Record money actually handed to staff",
                detail =
                    "This debits Tips Payable as one lump-sum payout. Use the note for the staff split; entries remain immutable and mistakes require a reasoned void.",
                tone = UiTone.Information,
                icon = Icons.Default.Payments,
            )
        }
        if (!canWrite) {
            item {
                ViewOnlyNotice("Tip payouts are view only — ask an owner or manager to record or void one.")
            }
        }
        item {
            StatGrid(
                listOf(
                    StatSpec(
                        "Owed to staff now",
                        state.tipsPayableMinor?.asRupees() ?: "Unavailable",
                        "live Tips Payable balance",
                        if (state.tipsPayableMinor == null) Tone.Bad else Tone.Default,
                    ),
                    StatSpec(
                        "Paid out to date",
                        state.tipPayoutTotalMinor.asRupees(),
                        countLabel(state.tipPayouts.count { !it.isVoided }, "active payout"),
                    ),
                ),
                columns = 2,
            )
        }
        item {
            ActionBar(
                leading = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Immutable payout register",
                            style = MaterialTheme.typography.titleSmall,
                            color = Brand.Foreground,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Newest payout first · server-authoritative",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.ForegroundMuted,
                        )
                    }
                },
                trailing = {
                    ErpButton(
                        text = "Pay out tips",
                        onClick = vm::openTipPayoutForm,
                        enabled = canWrite && state.online && state.tipsPayableMinor != null &&
                            state.pendingOnlineWrite == null && state.branches.isNotEmpty(),
                        leadingIcon = Icons.Default.Payments,
                    )
                },
            )
        }
        if (!state.online) {
            item {
                Note("Online-only accounting: reconnect and refresh the live Tips Payable balance before paying or voiding. Nothing is queued offline.")
            }
        }
        if (state.tipPayouts.size == 500) {
            item { Note("Showing the newest 500 payout records.") }
        }
        if (state.tipPayouts.isEmpty()) {
            item {
                Panel {
                    SectionTitle("No tip payouts recorded")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tips collected on paid orders remain in Tips Payable until staff are actually paid here.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(state.tipPayouts, key = { it.id }) { row ->
                TipPayoutRow(
                    row = row,
                    branchName = state.branches.firstOrNull { it.id == row.branchId }?.name
                        ?: "Unknown shop",
                    canVoid = canWrite && state.online && state.pendingOnlineWrite == null,
                    onVoid = { vm.openVoidTipPayout(row) },
                )
            }
        }
    }
}

@Composable
private fun TipPayoutRow(
    row: TipPayout,
    branchName: String,
    canVoid: Boolean,
    onVoid: () -> Unit,
) {
    Panel(border = if (row.isVoided) Brand.Danger.copy(alpha = 0.45f) else null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    row.note,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (row.isVoided) TextDecoration.LineThrough else null,
                )
                Text(
                    "${row.paidAt.asDay()} · $branchName · ${paidViaLabel(row.method)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "Recorded by ${row.createdByName ?: "verified employee"} · ${row.createdAt.asDay()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                row.voidReason?.let {
                    Text("Void reason: $it", style = MaterialTheme.typography.bodySmall, color = Brand.Danger)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    row.amountMinor.asRupees(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (row.isVoided) Brand.ForegroundMuted else Brand.Foreground,
                    textDecoration = if (row.isVoided) TextDecoration.LineThrough else null,
                )
                if (row.isVoided) {
                    Text("VOIDED", color = Brand.Danger, style = MaterialTheme.typography.labelSmall)
                } else {
                    ErpButton(
                        text = "Void",
                        onClick = onVoid,
                        enabled = canVoid,
                        intent = ActionIntent.Destructive,
                        leadingIcon = Icons.Default.Block,
                    )
                }
            }
        }
    }
}

// ============================================================================
// ASSETS
// ============================================================================
internal fun assetCategoryLabel(
    type: String,
    presentation: WorkspacePresentationPolicy,
): String = when (type) {
    "kitchen_equipment" -> if (presentation.showsRestaurantOperations) {
        "Kitchen equipment"
    } else {
        "Legacy equipment"
    }
    "gaming" -> "Gaming"
    "furniture" -> "Furniture"
    "electronics" -> "Electronics"
    "other" -> "Other"
    else -> type.replace('_', ' ').replaceFirstChar(Char::titlecase)
}

@Composable
private fun AssetsTab(
    state: FinanceUiState,
    vm: FinanceViewModel,
    canWrite: Boolean,
    presentation: WorkspacePresentationPolicy,
) {
    if (state.assets.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (!canWrite) {
                ViewOnlyNotice("Assets are view only — ask an owner to register equipment.")
            }
            AssetActionBar(state, canWrite, vm::openAssetForm)
            EmptyBlock(
                title = "No assets registered yet",
                body = if (canWrite) {
                    if (presentation.showsRestaurantOperations) {
                        "Register PS5s, TVs, projectors, kitchen equipment or other fixed assets. Straight-line depreciation is server-calculated."
                    } else {
                        "Register PS5s, TVs, controllers, VR or simulator equipment and other fixed assets. Straight-line depreciation is server-calculated."
                    }
                } else {
                    "No fixed assets are available for this account and branch."
                },
                modifier = Modifier.weight(1f),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!canWrite) {
            item {
                ViewOnlyNotice("Assets are view only — ask an owner to register equipment.")
            }
        }
        item {
            AssetActionBar(state, canWrite, vm::openAssetForm)
        }
        items(state.assets, key = { it.id }) { asset -> AssetRow(asset, presentation) }
        item {
            Note("Straight-line depreciation is recomputed by the server as of each successful load.")
        }
    }
}

@Composable
private fun AssetActionBar(state: FinanceUiState, canWrite: Boolean, onCreate: () -> Unit) {
    ActionBar(
        leading = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    countLabel(state.assets.size, "asset"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Straight-line depreciation is calculated automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.ForegroundMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailing = {
            ErpButton(
                text = "New asset",
                onClick = onCreate,
                enabled = canWrite,
                leadingIcon = Icons.Default.Add,
            )
        },
    )
}

@Composable
private fun AssetRow(asset: Asset, presentation: WorkspacePresentationPolicy) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${assetCategoryLabel(asset.type, presentation)} · Bought ${asset.purchaseDate.asDay()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "Purchase ${asset.purchaseMinor.asRupees()} · ${asset.usefulLifeMonths} months useful life",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Book value",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                Text(
                    asset.bookValueMinor.asRupees(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Depreciated ${asset.accumulatedDepreciationMinor.asRupees()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
        }
    }
}

// ============================================================================
// PARTNERS
// ============================================================================
@Composable
private fun PartnersTab(state: FinanceUiState, vm: FinanceViewModel, canWrite: Boolean) {
    val distributable = state.distributable

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!state.companyWidePartnerDataAvailable) {
            Panel {
                Text(
                    "Company-wide finance access required",
                    color = Brand.Foreground,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Partner ownership, capital and distribution capacity cannot be split " +
                        "safely by branch. Sign in with a company-wide owner or partner " +
                        "assignment to view these figures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
            }
            return@Column
        }
        if (!canWrite) {
            ViewOnlyNotice(
                "Partner capital is view only — ask a protected owner to record a movement.",
            )
        }
        if (distributable != null) {
            DistributableCard(distributable, state.verifiedCostingCoverage)
        }

        if (state.partners.isEmpty()) {
            Panel {
                Text("No partners yet", color = Brand.Foreground, fontWeight = FontWeight.Bold)
                Text(
                    "No partner records are available. A protected owner must create the partner before capital movements can be recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
            }
            return@Column
        }

        Text(
            countLabel(state.partners.size, "partner"),
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )

        val shareByPartnerId = if (distributable?.authoritativeSpendableCashMinor() != null) {
            distributable.partners.associateBy { it.partnerId }
        } else {
            emptyMap()
        }
        state.partners.forEach { partner ->
            PartnerCard(
                partner,
                shareByPartnerId[partner.id],
                canRecordCapital = canWrite && state.companyWidePartnerDataAvailable,
                onRecordCapital = { vm.openCapitalEntryForm(partner) },
            )
        }

        Note(
            "Investment records money a partner put in; capital repayment records money " +
                "paid back. Profit share is calculated separately and does not change " +
                "contributed capital. Corrections require an authorised reasoned void.",
        )
    }
}

@Composable
private fun DistributableCard(d: DistributableProfit, costing: CostingCoverage?) {
    val spendable = d.authoritativeSpendableCashMinor()
    Panel(border = if (costing?.isComplete == true) Brand.BorderSubtle else Brand.Warning) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Safe-to-distribute cap · server calculation",
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                )
                Text(
                    "The lower of profit-based capacity and spendable-cash capacity after a " +
                        "${d.reserveMonths}-month operating reserve. Lifetime profit already includes " +
                        "${d.lifetimeDepreciationMinor.asRupees()} of server-calculated depreciation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Text(
                if (spendable == null) "Unavailable" else d.safeToDistributeMinor.asRupees(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (spendable == null) Brand.Warning else Brand.Foreground,
            )
        }
        Spacer(Modifier.height(10.dp))
        HairLine()
        Spacer(Modifier.height(10.dp))
        StatGrid(
            listOf(
                StatSpec(
                    "Lifetime profit (after depreciation)",
                    d.lifetimeNetProfitMinor.asRupees(),
                    tone = if (d.lifetimeNetProfitMinor < 0) Tone.Bad else Tone.Default,
                ),
                StatSpec("Already withdrawn", d.lifetimeWithdrawnMinor.asRupees()),
                StatSpec(
                    "Reserve kept back",
                    d.reserveMinor.asRupees(),
                    "${d.reserveMonths} months at ${d.avgMonthlyCostMinor.asRupees()}/month",
                ),
                StatSpec(
                    SPENDABLE_FUNDS_LABEL,
                    spendable?.asRupees() ?: "Unavailable",
                    if (spendable == null) CASH_CONTRACT_UNAVAILABLE else SPENDABLE_FUNDS_DETAIL,
                    if (spendable == null || spendable < 0) Tone.Bad else Tone.Default,
                ),
            ),
            columns = 4,
            surface = Brand.SurfaceRaised,
        )
        d.cashPosition?.let { position ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Provider settlement receivables: " +
                    position.settlementReceivablesMinor.asRupees() +
                    " · UPI/QR ${position.upiQrClearingMinor.asRupees()}" +
                    " · Card ${position.cardClearingMinor.asRupees()}" +
                    " · Wallet ${position.walletClearingMinor.asRupees()}",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        if (spendable == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                CASH_CONTRACT_UNAVAILABLE,
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Warning,
            )
        } else if (d.cashBasedCapacityMinor < d.profitBasedCapacityMinor) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Limited by spendable cash, not by profit — some value may be tied up in " +
                    "stock, equipment, or provider settlement receivables.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Warning,
            )
        }
        if (costing?.isComplete != true) {
            Text(
                if (costing == null) {
                    "Costing coverage could not be verified. Do not distribute from this figure until " +
                        "Finance refreshes and confirms recipe and ingredient costing."
                } else {
                    "Costing is incomplete, so profit-based distribution capacity may be overstated. " +
                        "Do not distribute from this figure until recipe and ingredient costing is reconciled."
                },
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Warning,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "As of ${d.asOf.asDay()}",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PartnerCard(
    partner: Partner,
    share: DistributablePartnerShare?,
    canRecordCapital: Boolean,
    onRecordCapital: () -> Unit,
) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    partner.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                )
                Text(
                    "Share: ${partner.sharePct.asSharePct()} · Since ${partner.joinedAt.asDay()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Active capital balance",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
                Text(
                    partner.capitalBalanceMinor.asRupees(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (partner.capitalBalanceMinor < 0) Brand.Danger else Brand.Foreground,
                )
                Text(
                    "Voided entries excluded",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
        }
        if (share != null) {
            Spacer(Modifier.height(10.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Safe to take out right now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.ForegroundMuted,
                    )
                    Text(
                        "Withdrawn to date: ${share.lifetimeWithdrawnMinor.asRupees()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                }
                Text(
                    share.distributableShareMinor.asRupees(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ErpButton(
            text = "Record capital movement",
            onClick = onRecordCapital,
            enabled = canRecordCapital,
            modifier = Modifier.fillMaxWidth(),
            intent = ActionIntent.Secondary,
        )
    }
}

// ============================================================================
// SHARED PIECES
// ============================================================================
private enum class Tone { Default, Bad }

private data class StatSpec(
    val label: String,
    val value: String,
    val sub: String? = null,
    val tone: Tone = Tone.Default,
)

private fun Tone.color(): Color = when (this) {
    Tone.Bad -> Brand.Danger
    Tone.Default -> Brand.Foreground
}

/**
 * A hand-rolled grid rather than LazyVerticalGrid: these sit inside a scrolling
 * column, and nesting a lazy grid in a scroll container is the classic way to
 * get an infinite-height crash.
 */
@Composable
private fun StatGrid(
    stats: List<StatSpec>,
    columns: Int = 3,
    surface: Color = Brand.Surface,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val responsiveColumns = when {
            maxWidth < 560.dp -> 1
            maxWidth < 900.dp -> minOf(columns, 2)
            else -> columns
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            stats.chunked(responsiveColumns).forEach { rowStats ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowStats.forEach { stat ->
                        StatCard(stat, surface, Modifier.weight(1f))
                    }
                    // Keeps the last, short row's cards the same width as the rest.
                    repeat(responsiveColumns - rowStats.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(stat: StatSpec, surface: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(Radius.shapeLg)
            .background(surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stat.label,
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        Text(
            stat.value,
            style = MaterialTheme.typography.titleLarge,
            color = stat.tone.color(),
        )
        stat.sub?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        }
    }
}

/**
 * `less = true` renders a deduction: the amount is shown positive in red under
 * a "Less: …" label, so a subtraction never reads as a negative balance.
 */
@Composable
private fun PlRow(
    label: String,
    valueMinor: Long,
    sub: String? = null,
    less: Boolean = false,
    bold: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.Foreground,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            )
            sub?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
            }
        }
        Text(
            if (less) abs(valueMinor).asRupees() else valueMinor.asRupees(),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                less && valueMinor != 0L -> Brand.Danger
                valueMinor < 0 -> Brand.Danger
                else -> Brand.Foreground
            },
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .then(
                if (border != null) {
                    Modifier.border(1.dp, border, Radius.shapeLg)
                } else {
                    Modifier
                },
            )
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
}

@Composable
private fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Border))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
}

@Composable
private fun FinanceOfflineBanner(lastUpdatedAtMillis: Long?) {
    val lastUpdated = lastUpdatedAtMillis?.let {
        remember(it) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
    }
    OperationalBanner(
        title = "Offline · showing saved financial data",
        detail = buildString {
            if (lastUpdated != null) append("Last successful server load: $lastUpdated. ")
            append(
                "Expense, asset and capital drafts stay on this tablet and sync after reconnection. " +
                    "Manual collections and tip payouts are never saved offline.",
            )
        },
        tone = UiTone.Warning,
        icon = Icons.Default.CloudOff,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(Radius.shapeMd)
            .background(Brand.Surface)
            .border(1.dp, Brand.Danger, Radius.shapeMd)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Couldn't refresh", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
            Text(
                "The figures below are from the last successful load.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Couldn't load finance",
                style = MaterialTheme.typography.titleLarge,
                color = Brand.Foreground,
            )
            // The server's own words. "HTTP 403" tells an owner nothing;
            // "finance.read permission required" tells them who to ask.
            Text(message, color = Brand.ForegroundMuted)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyBlock(title: String, body: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.9f).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(body, color = Brand.ForegroundMuted)
        }
    }
}

// ============================================================================
// PENDING FINANCE CHANGES — mirrors Inventory's PendingStockChangesPanel
// ============================================================================
@Composable
private fun PendingFinanceChangesPanel(
    state: FinanceUiState,
    vm: FinanceViewModel,
    access: FinanceAccess,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeMd)
            .background(Brand.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pending finance changes", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        state.pendingExpenses.forEach { row ->
            FinancePendingRow(
                text = "${row.amountMinor.asRupees()} · ${row.categoryName}" +
                    (row.vendorName?.let { " · $it" } ?: ""),
                rejected = row.rejected,
                error = row.error,
                canRetry = access.canRecordExpenses,
                onRetry = { vm.retryExpense(row.localId) },
            )
        }
        state.pendingAssets.forEach { row ->
            FinancePendingRow(
                text = "New asset: ${row.name} (${row.purchaseMinor.asRupees()})",
                rejected = row.rejected,
                error = row.error,
                canRetry = access.canManageAssets,
                onRetry = { vm.retryAsset(row.localId) },
            )
        }
        state.pendingCapitalEntries.forEach { row ->
            val verb = if (row.type == "invest") "Investment from" else "Capital repayment to"
            FinancePendingRow(
                text = "$verb ${row.partnerName}: ${row.amountMinor.asRupees()}",
                rejected = row.rejected,
                error = row.error,
                canRetry = access.canRecordPartnerCapital,
                onRetry = { vm.retryCapitalEntry(row.localId) },
            )
        }
    }
}

@Composable
private fun FinancePendingRow(
    text: String,
    rejected: Boolean,
    error: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (rejected) {
                ErpButton(
                    text = "Retry",
                    onClick = onRetry,
                    enabled = canRetry,
                    intent = ActionIntent.Secondary,
                )
            }
        }
        Text(
            if (rejected) "Could not sync: ${error ?: "unknown error"}" else "Not synced yet",
            color = if (rejected) Brand.Danger else Brand.Warning,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        ErpButton("Dismiss", onDismiss, intent = ActionIntent.Quiet)
    }
}

@Composable
private fun PendingOnlineFinanceWriteBanner(
    pending: PendingFinanceOnlineWrite,
    online: Boolean,
    canRetry: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    OperationalBanner(
        title = "Finance request needs exact recovery",
        detail =
            "${pending.summary()}. The previous server result was not confirmed. Do not enter or pay it again; retrying reuses the exact saved request.",
        tone = UiTone.Warning,
        icon = Icons.Default.Refresh,
        action = {
            ErpButton(
                text = if (online) "Retry exact request" else "Reconnect to retry",
                onClick = onRetry,
                enabled = online && canRetry,
                busy = busy,
                intent = ActionIntent.Warning,
                leadingIcon = Icons.Default.Refresh,
            )
        },
    )
}

// ============================================================================
// CREATE DIALOGS
// ============================================================================
@Composable
private fun ManualCollectionCreateDialog(
    state: FinanceUiState,
    vm: FinanceViewModel,
    presentation: WorkspacePresentationPolicy,
) {
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id ?: "") }
    var businessDate by remember { mutableStateOf(financeBusinessToday().toString()) }
    var method by remember { mutableStateOf("cash") }
    var amountRupees by remember { mutableStateOf("") }
    var sourceRef by remember {
        mutableStateOf(defaultManualCollectionReference(businessDate, method))
    }
    var note by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun changeDate(next: String) {
        val oldDefault = defaultManualCollectionReference(businessDate, method)
        businessDate = next
        if (sourceRef == oldDefault) {
            sourceRef = defaultManualCollectionReference(next, method)
        }
    }

    fun changeMethod(next: String) {
        val oldDefault = defaultManualCollectionReference(businessDate, method)
        method = next
        if (sourceRef == oldDefault) {
            sourceRef = defaultManualCollectionReference(businessDate, next)
        }
    }

    FormDialog(
        title = "Add manual collection",
        confirmLabel = "Record collection",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val amountMinor = parseRupeesToMinor(amountRupees)
            when {
                amountMinor == null || amountMinor <= 0 ->
                    localError = "Enter an amount greater than ₹0 with no more than 2 decimal places."
                sourceRef.trim().isEmpty() ->
                    localError = "Enter a reference that can be matched to payment evidence."
                else -> {
                    localError = null
                    vm.createManualCollection(
                        branchId = branchId,
                        businessDate = businessDate,
                        method = method,
                        amountMinor = amountMinor,
                        sourceRef = sourceRef,
                        note = note,
                    )
                }
            }
        },
    ) {
        OperationalBanner(
            title = "Unitemized revenue only",
            detail = if (presentation.showsRestaurantOperations) {
                "This will not create an order, receipt, table ticket, gaming session, tax split or automatic COGS. It is sent live and never queued offline."
            } else {
                "This will not create an itemized bill, gaming session, item mix or automatic COGS. It is sent live and never queued offline."
            },
            tone = UiTone.Warning,
            icon = Icons.Default.Payments,
        )
        BusinessDatePickerField(businessDate, ::changeDate)
        PickerField(
            "Shop",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
        ) { branchId = it }
        PickerField(
            "Payment method",
            paidViaLabel(method),
            listOf(
                "cash" to "Cash",
                "upi" to "UPI",
                "card" to "Card",
                "bank" to "Bank transfer",
            ),
        ) { changeMethod(it) }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)")
        OutlinedTextField(
            value = sourceRef,
            onValueChange = { sourceRef = it.take(160) },
            label = { Text("Evidence reference") },
            supportingText = { Text("Daily sheet row, settlement reference, or bank evidence") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(500) },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TipPayoutCreateDialog(state: FinanceUiState, vm: FinanceViewModel) {
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id ?: "") }
    var method by remember { mutableStateOf("cash") }
    var amountRupees by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val amountMinor = parseRupeesToMinor(amountRupees)
    val owed = state.tipsPayableMinor
    val exceedsOwed = amountMinor != null && owed != null && amountMinor > owed

    FormDialog(
        title = "Pay out tips",
        confirmLabel = "Record payout",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        confirmEnabled = !exceedsOwed,
        onConfirm = {
            val parsedAmount = parseRupeesToMinor(amountRupees)
            when {
                parsedAmount == null || parsedAmount <= 0 ->
                    localError = "Enter an amount greater than ₹0 with no more than 2 decimal places."
                owed == null ->
                    localError = "Refresh the live Tips Payable balance before paying staff."
                parsedAmount > owed ->
                    localError = "This exceeds the ${owed.asRupees()} currently owed to staff."
                note.trim().length < 3 ->
                    localError = "Explain how the payout was split (at least 3 characters)."
                else -> {
                    localError = null
                    vm.createTipPayout(
                        branchId = branchId,
                        method = method,
                        amountMinor = parsedAmount,
                        paidAt = nowIso(),
                        note = note,
                    )
                }
            }
        },
    ) {
        OperationalBanner(
            title = "Owed to staff: ${owed?.asRupees() ?: "Unavailable"}",
            detail =
                "Record only money actually handed over now. This is one lump-sum payout; the note is the staff-split record until Payroll exists.",
            tone = if (owed == null) UiTone.Danger else UiTone.Information,
            icon = Icons.Default.Payments,
        )
        PickerField(
            "Shop",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
        ) { branchId = it }
        PickerField(
            "Paid via",
            paidViaLabel(method),
            listOf(
                "cash" to "Cash",
                "upi" to "UPI",
                "card" to "Card",
                "bank" to "Bank transfer",
            ),
        ) { method = it }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)")
        if (exceedsOwed) {
            Text(
                "This is more than the ${owed?.asRupees()} currently owed to staff.",
                color = Brand.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(500) },
            label = { Text("Staff split / payout note") },
            supportingText = { Text("For example: split among Anu, Basil and Reji on shift") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Note("The server records the actual current date/time when you confirm this payout.")
    }
}

@Composable
private fun VoidManualCollectionDialog(
    row: ManualCollection,
    state: FinanceUiState,
    vm: FinanceViewModel,
) {
    var reason by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "Void manual collection",
        confirmLabel = "Void collection",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        confirmEnabled = reason.trim().length >= 3,
        onConfirm = {
            if (reason.trim().length < 3) {
                localError = "Enter a reason with at least 3 characters."
            } else {
                localError = null
                vm.voidManualCollection(row, reason)
            }
        },
    ) {
        OperationalBanner(
            title = "Void ${row.amountMinor.asRupees()} · ${paidViaLabel(row.method)}?",
            detail =
                "The original record remains visible for audit, but its revenue and payment movement will be reversed.",
            tone = UiTone.Danger,
            icon = Icons.Default.Block,
        )
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Void reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun VoidTipPayoutDialog(row: TipPayout, state: FinanceUiState, vm: FinanceViewModel) {
    var reason by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "Void tip payout",
        confirmLabel = "Void payout",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        confirmEnabled = reason.trim().length >= 3,
        onConfirm = {
            if (reason.trim().length < 3) {
                localError = "Enter a reason with at least 3 characters."
            } else {
                localError = null
                vm.voidTipPayout(row, reason)
            }
        },
    ) {
        OperationalBanner(
            title = "Void ${row.amountMinor.asRupees()} · ${paidViaLabel(row.method)}?",
            detail =
                "The original record remains visible for audit and this amount becomes owed to staff in Tips Payable again.",
            tone = UiTone.Danger,
            icon = Icons.Default.Block,
        )
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Void reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BusinessDatePickerField(value: String, onValueChange: (String) -> Unit) {
    val context = LocalContext.current
    val current = runCatching { LocalDate.parse(value) }.getOrDefault(financeBusinessToday())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Business date", style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        ErpButton(
            text = current.toString().asDay(),
            onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        onValueChange(LocalDate.of(year, month + 1, day).toString())
                    },
                    current.year,
                    current.monthValue - 1,
                    current.dayOfMonth,
                ).apply {
                    datePicker.maxDate = financeBusinessToday().plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
                }.show()
            },
            modifier = Modifier.fillMaxWidth(),
            intent = ActionIntent.Secondary,
        )
    }
}

@Composable
private fun ExpenseCreateDialog(state: FinanceUiState, vm: FinanceViewModel) {
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id ?: "") }
    var categoryId by remember { mutableStateOf(state.categoryNames.keys.firstOrNull() ?: "") }
    var amountRupees by remember { mutableStateOf("") }
    var paidVia by remember { mutableStateOf("cash") }
    var vendorName by remember { mutableStateOf("") }
    var invoiceNo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "Add expense",
        confirmLabel = "Queue expense",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val amountMinor = parseRupeesToMinor(amountRupees)
            if (amountMinor == null) {
                localError = "Amount must be rupees with no more than 2 decimal places."
                return@FormDialog
            }
            if (amountMinor <= 0) {
                localError = "Enter an amount greater than ₹0."
                return@FormDialog
            }
            if (branchId.isBlank() || categoryId.isBlank()) {
                localError = "Pick a branch and a category."
                return@FormDialog
            }
            localError = null
            vm.postExpense(
                branchId = branchId, categoryId = categoryId, amountMinor = amountMinor,
                paidVia = paidVia, paidAt = nowIso(), vendorName = vendorName,
                invoiceNo = invoiceNo, note = note,
            )
        },
    ) {
        PickerField(
            "Branch",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
        ) { branchId = it }
        PickerField(
            "Category",
            state.categoryNames[categoryId] ?: "Select…",
            state.categoryNames.entries.map { it.key to it.value },
        ) { categoryId = it }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)")
        PickerField(
            "Paid via",
            paidViaLabel(paidVia),
            listOf(
                "cash" to "Cash",
                "upi" to "UPI (from business bank)",
                "card" to "Business debit card",
                "bank" to "Bank transfer",
            ),
        ) { paidVia = it }
        Text(
            "UPI and business debit-card expenses reduce the Bank balance. " +
                "Business credit-card liabilities are not supported yet; do not record " +
                "a credit-card purchase as Card.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        OutlinedTextField(
            value = vendorName, onValueChange = { vendorName = it },
            label = { Text("Vendor (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = invoiceNo, onValueChange = { invoiceNo = it },
            label = { Text("Invoice no. (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AssetCreateDialog(
    state: FinanceUiState,
    vm: FinanceViewModel,
    presentation: WorkspacePresentationPolicy,
) {
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id ?: "") }
    var name by remember { mutableStateOf("") }
    var type by remember {
        mutableStateOf(if (presentation.showsRestaurantOperations) "kitchen_equipment" else "gaming")
    }
    var purchaseRupees by remember { mutableStateOf("") }
    var usefulLifeMonths by remember { mutableStateOf("60") }
    var salvageRupees by remember { mutableStateOf("0") }
    var notesText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "Register asset",
        confirmLabel = "Queue asset",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val purchaseMinor = parseRupeesToMinor(purchaseRupees)
            val salvageMinor = parseRupeesToMinor(salvageRupees.ifBlank { "0" })
            val lifeMonths = usefulLifeMonths.toIntOrNull() ?: 0
            when {
                name.isBlank() -> localError = "Enter a name for this asset."
                branchId.isBlank() -> localError = "Pick a branch."
                purchaseMinor == null ->
                    localError = "Purchase cost must be rupees with no more than 2 decimal places."
                purchaseMinor <= 0 -> localError = "Enter a purchase cost greater than ₹0."
                salvageMinor == null ->
                    localError = "Salvage value must be rupees with no more than 2 decimal places."
                lifeMonths <= 0 -> localError = "Useful life must be a whole number of months greater than 0."
                salvageMinor > purchaseMinor -> localError = "Salvage value cannot exceed the purchase cost."
                else -> {
                    localError = null
                    vm.postAsset(
                        branchId = branchId, name = name, type = type, purchaseMinor = purchaseMinor,
                        purchaseDate = nowIso(), usefulLifeMonths = lifeMonths,
                        salvageMinor = salvageMinor, notesText = notesText,
                    )
                }
            }
        },
    ) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        PickerField(
            "Branch",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
        ) { branchId = it }
        PickerField(
            "Category",
            assetCategoryLabel(type, presentation),
            buildList {
                if (presentation.showsRestaurantOperations) {
                    add("kitchen_equipment" to "Kitchen equipment")
                }
                add("gaming" to "Gaming")
                add("furniture" to "Furniture")
                add("electronics" to "Electronics")
                add("other" to "Other")
            },
        ) { type = it }
        DecimalField(purchaseRupees, { purchaseRupees = it }, "Purchase cost (₹)")
        OutlinedTextField(
            value = usefulLifeMonths,
            onValueChange = { usefulLifeMonths = it.filter(Char::isDigit) },
            label = { Text("Useful life (months)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalField(salvageRupees, { salvageRupees = it }, "Salvage value (₹)")
        OutlinedTextField(
            value = notesText, onValueChange = { notesText = it },
            label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CapitalEntryCreateDialog(partner: Partner, state: FinanceUiState, vm: FinanceViewModel) {
    var type by remember { mutableStateOf("invest") }
    var amountRupees by remember { mutableStateOf("") }
    var settlementAccount by remember { mutableStateOf("bank") }
    var sourceRef by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "Capital movement — ${partner.name}",
        confirmLabel = "Queue entry",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            val amountMinor = parseRupeesToMinor(amountRupees)
            val trimmedRef = sourceRef.trim()
            when {
                amountMinor == null ->
                    localError = "Amount must be rupees with no more than 2 decimal places."
                amountMinor <= 0 -> localError = "Enter an amount greater than ₹0."
                trimmedRef.isBlank() ->
                    localError = "Enter the unique bank, UPI, or cash-voucher reference that proves this movement."
                else -> {
                    localError = null
                    vm.postCapitalEntry(
                        partnerId = partner.id, type = type, amountMinor = amountMinor,
                        effectiveAt = nowIso(), settlementAccount = settlementAccount,
                        sourceRef = trimmedRef, note = note,
                    )
                }
            }
        },
    ) {
        PickerField(
            "Type", if (type == "invest") "Investment" else "Capital repayment",
            listOf("invest" to "Investment", "withdraw" to "Capital repayment"),
        ) { type = it }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)")
        PickerField(
            "Settlement account", paidViaLabel(settlementAccount).let { if (it == "Bank transfer") "Bank" else it },
            listOf("cash" to "Cash", "bank" to "Bank", "upi" to "UPI"),
        ) { settlementAccount = it }
        OutlinedTextField(
            value = sourceRef, onValueChange = { sourceRef = it },
            label = { Text("Bank UTR / UPI id / voucher no.") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Must be unique. On a network retry, submit the same reference instead of inventing a new one.",
            style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// FORM PRIMITIVES — local copies of InventoryScreen.kt's shell/inputs, kept
// per-screen rather than promoted to a shared file (same convention as
// InventoryModels.kt's own local Branch DTO).
// ============================================================================
private fun nowIso(): String = java.time.OffsetDateTime.now().toString()
