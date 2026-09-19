package cloud.dcompany.erp.ui.screens.finance

import android.app.DatePickerDialog
import android.net.Uri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
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
    val context = LocalContext.current
    LaunchedEffect(state.dialog) {
        if (state.dialog != FinanceDialog.ExpenseForm) {
            withContext(Dispatchers.IO) { pruneStaleExpenseReceiptCameraFiles(context) }
        }
    }
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
        when (state.primaryContentState) {
            FinancePrimaryContentState.ERROR -> ErrorBlock(
                state.error ?: financeLoadFailureMessage(
                    hasSavedFigures = false,
                    online = state.online,
                ),
                vm::load,
            )

            FinancePrimaryContentState.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Brand.Information)
                    Text("Reading the books…", color = Brand.ForegroundMuted)
                }
            }

            FinancePrimaryContentState.DATA -> {
                PremiumTabBar(
                    options = tabs.mapIndexed { index, title ->
                        TabOption(index.toString(), title)
                    },
                    selectedId = tab.toString(),
                    onSelect = { selected -> tab = selected.toIntOrNull() ?: 0 },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                FinanceStatusRegion(state = state, vm = vm, access = access)
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
                    is FinanceDialog.DiscardRejectedExpense -> if (access.canRecordExpenses) {
                        RejectedExpenseDiscardDialog(
                            row = dialog.row,
                            online = state.online,
                            busy = state.busy,
                            error = state.formError,
                            onConfirm = { vm.discardRejectedExpense(dialog.row) },
                            onDismiss = vm::closeDialog,
                        )
                    }
                    is FinanceDialog.DiscardExpenseReceipt -> if (access.canRecordExpenses) {
                        RejectedExpenseReceiptDiscardDialog(
                            filename = dialog.row.filename,
                            online = state.online,
                            busy = state.busy,
                            error = state.formError,
                            onConfirm = { vm.discardRejectedExpenseReceipt(dialog.row) },
                            onDismiss = vm::closeDialog,
                        )
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
                        when {
                            state.loaded -> "Financial controls"
                            state.loading -> "Loading financial records"
                            else -> "Financial records unavailable"
                        },
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
        // Reserve the progress rail so a background refresh never pushes the
        // tabs and report viewport down by one indicator height.
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (state.loading && state.loaded) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Brand.Information,
                    trackColor = Brand.Surface,
                )
            }
        }
    }
}

@Composable
private fun FinanceStatusRegion(
    state: FinanceUiState,
    vm: FinanceViewModel,
    access: FinanceAccess,
) {
    val hasPendingChanges = state.pendingExpenses.isNotEmpty() ||
        state.pendingExpenseReceipts.isNotEmpty() || state.pendingAssets.isNotEmpty() ||
        state.pendingCapitalEntries.isNotEmpty()
    val hasStatus = !state.online || (state.error != null && state.online) ||
        state.pendingOnlineWrite != null || state.notice != null || hasPendingChanges

    Box(
        Modifier
            .fillMaxWidth()
            .height(FINANCE_STATUS_REGION_HEIGHT)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        if (!hasStatus) {
            Text(
                "Finance records are online · no saved finance actions need attention",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (!state.online) FinanceOfflineBanner(state.lastUpdatedAtMillis)
                // A refresh that failed on top of good data: keep the figures,
                // but never let the failure pass unmentioned.
                if (state.error != null && state.online) ErrorBanner(state.error, vm::load)
                state.pendingOnlineWrite?.let { pending ->
                    PendingOnlineFinanceWriteBanner(
                        pending = pending,
                        online = state.online,
                        canRetry = access.canRecordExpenses,
                        busy = state.busy,
                        onRetry = vm::retryPendingOnlineWrite,
                    )
                }
                state.notice?.let { NoticeBanner(it, vm::dismissNotice) }
                if (hasPendingChanges) PendingFinanceChangesPanel(state, vm, access)
            }
        }
    }
}

private val FINANCE_STATUS_REGION_HEIGHT = 132.dp

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

        state.allocationWarning?.let { AllocationUnavailableNotice(it) }

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
                    val cap = it.authoritativeDistributionCapMinor()
                    StatSpec(
                        "Partner distribution cap",
                        cap?.asRupees() ?: "Unavailable",
                        if (cap == null) {
                            it.authoritativeAllocationUnavailableReason()
                        } else {
                            "lower of profit-based and spendable-cash capacity after reserve"
                        },
                        if (cap == null) Tone.Bad else Tone.Default,
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
                    "Active loaded total ${state.expenseTotalMinor.asRupees()}",
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
    Panel(border = if (expense.isCorrected) Brand.Warning.copy(alpha = 0.55f) else null) {
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
                Text(
                    expenseReceiptSummary(expense.receiptCount, expense.receiptStatus),
                    style = MaterialTheme.typography.labelSmall,
                    color = expenseReceiptStatusColor(expense.receiptStatus),
                )
                expense.correction?.let {
                    Text(
                        "Corrected ${it.correctedAt.asDay()}: ${it.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Warning,
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
                if (expense.isCorrected) {
                    Text("CORRECTED", color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                }
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
                        countLabel(totals.voidedCount, "voided entry", "voided entries") +
                            " · " + countLabel(
                                totals.correctedCount,
                                "corrected entry",
                                "corrected entries",
                            ),
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
    Panel(
        border = when {
            row.isVoided -> Brand.Danger.copy(alpha = 0.45f)
            row.isCorrected -> Brand.Warning.copy(alpha = 0.55f)
            else -> null
        },
    ) {
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
                row.correction?.let {
                    Text(
                        "Corrected ${it.correctedAt.asDay()}: ${it.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.Warning,
                    )
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
                } else if (row.isCorrected) {
                    Text("CORRECTED", color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                } else {
                    ErpButton(
                        text = "Void",
                        onClick = onVoid,
                        enabled = canVoid &&
                            (row.method != "cash" || row.sourceShiftStatus == "open"),
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
                        countLabel(
                            state.tipPayouts.count { !it.isVoided && !it.isCorrected },
                            "active payout",
                        ),
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
    Panel(
        border = when {
            row.isVoided -> Brand.Danger.copy(alpha = 0.45f)
            row.isCorrected -> Brand.Warning.copy(alpha = 0.55f)
            else -> null
        },
    ) {
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
                row.correction?.let {
                    Text(
                        "Corrected ${it.correctedAt.asDay()}: ${it.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.Warning,
                    )
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
                } else if (row.isCorrected) {
                    Text("CORRECTED", color = Brand.Warning, style = MaterialTheme.typography.labelSmall)
                } else {
                    ErpButton(
                        text = "Void",
                        onClick = onVoid,
                        enabled = canVoid &&
                            (row.method != "cash" || row.sourceShiftStatus == "open"),
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
        state.allocationWarning?.let { AllocationUnavailableNotice(it) }
        if (distributable != null) {
            DistributableCard(distributable)
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

        val shareByPartnerId = if (distributable?.authoritativeDistributionCapMinor() != null) {
            distributable.partners.mapNotNull { share ->
                distributable.authoritativePartnerDistributionMinor(share)?.let { amount ->
                    share.partnerId to (share to amount)
                }
            }.toMap()
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
private fun AllocationUnavailableNotice(reason: String) {
    Panel(border = Brand.Warning) {
        Text(
            "Partner allocations unavailable",
            color = Brand.Warning,
            fontWeight = FontWeight.Bold,
        )
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
        Text(
            "Sales, collections, P&L and capital history remain separate from partner allocations. " +
                "No profit shares or distribution amounts are assumed.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun DistributableCard(d: DistributableProfit) {
    val spendable = d.authoritativeSpendableCashMinor()
    val distributionCap = d.authoritativeDistributionCapMinor()
    val distributionUnavailable = distributionCap == null
    Panel(border = if (distributionUnavailable) Brand.Warning else Brand.BorderSubtle) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    if (distributionUnavailable) {
                        "Partner distribution unavailable"
                    } else {
                        "Safe-to-distribute cap · server calculation"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                )
                Text(
                    if (distributionUnavailable) {
                        "The provisional operating result remains visible, but no amount is presented " +
                            "as safe to withdraw until historical product costs reconcile."
                    } else {
                        "The lower of profit-based capacity and spendable-cash capacity after a " +
                            "${d.reserveMonths}-month operating reserve. Lifetime profit already " +
                            "includes ${d.lifetimeDepreciationMinor.asRupees()} of server-calculated depreciation."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Text(
                distributionCap?.asRupees() ?: "Unavailable",
                style = MaterialTheme.typography.headlineMedium,
                color = if (distributionUnavailable) Brand.Warning else Brand.Foreground,
            )
        }
        Spacer(Modifier.height(10.dp))
        HairLine()
        Spacer(Modifier.height(10.dp))
        if (distributionUnavailable) {
            StatGrid(
                listOf(
                    StatSpec(
                        "Provisional lifetime profit (after depreciation)",
                        d.lifetimeNetProfitMinor.asRupees(),
                        tone = if (d.lifetimeNetProfitMinor < 0) Tone.Bad else Tone.Default,
                    ),
                    StatSpec(
                        "Historical costing check",
                        d.costingConfidence?.let { confidence ->
                            countLabel(confidence.unresolvedOrderCount, "order") + " need reconciliation"
                        } ?: "Not available from server",
                        d.authoritativeAllocationUnavailableReason(),
                        Tone.Bad,
                    ),
                ),
                columns = 2,
                surface = Brand.SurfaceRaised,
            )
            Text(
                d.authoritativeAllocationUnavailableReason(),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Warning,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
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
            if (spendable != null && d.cashBasedCapacityMinor < d.profitBasedCapacityMinor) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Limited by spendable cash, not by profit — some value may be tied up in " +
                        "stock, equipment, or provider settlement receivables.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Warning,
                )
            }
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
    share: Pair<DistributablePartnerShare, Long>?,
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
            val (shareRecord, authoritativeAmount) = share
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
                        "Withdrawn to date: ${shareRecord.lifetimeWithdrawnMinor.asRupees()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                }
                Text(
                    authoritativeAmount.asRupees(),
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
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
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
            .border(1.dp, border ?: Brand.BorderSubtle, Radius.shapeLg)
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
                onDiscard = if (row.rejected && access.canRecordExpenses) {
                    { vm.openDiscardRejectedExpense(row) }
                } else {
                    null
                },
            )
        }
        state.pendingExpenseReceipts.forEach { row ->
            FinancePendingRow(
                text = "Receipt: ${row.filename}",
                rejected = row.rejected,
                error = row.error,
                pendingLabel = if (row.waitingForExpense) {
                    "Saved safely · waiting for the expense to sync first"
                } else {
                    "Saved safely · waiting to upload"
                },
                canRetry = access.canRecordExpenses,
                onRetry = { vm.retryExpenseReceipt(row.localId) },
                onDiscard = if (
                    row.rejected && row.expenseServerId != null && access.canRecordExpenses
                ) {
                    { vm.openDiscardExpenseReceipt(row) }
                } else {
                    null
                },
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
private fun RejectedExpenseDiscardDialog(
    row: PendingExpenseRow,
    online: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Resolve rejected expense?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${row.amountMinor.asRupees()} · ${row.categoryName}",
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The tablet will ask the server about this exact saved action. If it was recorded, the expense is marked synced and its saved receipts continue uploading. Only when the server proves it is absent will the exact rejected expense, receipts and private file chunks be removed.",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "Use Retry if the original request is now valid. An old server or an unclear answer keeps everything unchanged.",
                    color = Brand.ForegroundMuted,
                )
                if (!online) Text("Reconnect before resolving this entry.", color = Brand.Warning)
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            ErpButton(
                text = if (busy) "Checking server…" else "Check server and resolve",
                onClick = onConfirm,
                enabled = online && !busy,
                intent = ActionIntent.Destructive,
            )
        },
        dismissButton = {
            ErpButton(
                text = "Keep saved request",
                onClick = onDismiss,
                enabled = !busy,
                intent = ActionIntent.Quiet,
            )
        },
        containerColor = Brand.Surface,
    )
}

@Composable
private fun FinancePendingRow(
    text: String,
    rejected: Boolean,
    error: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDiscard: (() -> Unit)? = null,
    pendingLabel: String = "Not synced yet",
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ErpButton(
                        text = "Retry",
                        onClick = onRetry,
                        enabled = canRetry,
                        intent = ActionIntent.Secondary,
                    )
                    onDiscard?.let {
                        ErpButton(
                            text = "Remove saved copy",
                            onClick = it,
                            enabled = canRetry,
                            intent = ActionIntent.Quiet,
                        )
                    }
                }
            }
        }
        Text(
            if (rejected) "Could not sync: ${error ?: "unknown error"}" else pendingLabel,
            color = if (rejected) Brand.Danger else Brand.Warning,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun RejectedExpenseReceiptDiscardDialog(
    filename: String,
    online: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Remove rejected receipt?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(filename, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                Text(
                    "The tablet will first refresh the server receipt list. If this exact file is already there, it will be marked synced. If the server proves it is absent, only this rejected saved file is removed; the expense stays recorded.",
                    color = Brand.ForegroundMuted,
                )
                Text(
                    "Use Retry instead if the same file may now be accepted. A corrected replacement can be attached to the recorded expense from Web ERP.",
                    color = Brand.ForegroundMuted,
                )
                if (!online) {
                    Text("Reconnect before removing saved evidence.", color = Brand.Warning)
                }
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            ErpButton(
                text = if (busy) "Checking server…" else "Check server and remove",
                onClick = onConfirm,
                enabled = online && !busy,
                intent = ActionIntent.Destructive,
            )
        },
        dismissButton = {
            ErpButton(
                text = "Keep saved copy",
                onClick = onDismiss,
                enabled = !busy,
                intent = ActionIntent.Quiet,
            )
        },
        containerColor = Brand.Surface,
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
    )
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
internal fun FinanceFormDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable (formEnabled: Boolean) -> Unit,
) {
    FormDialog(
        title = title,
        confirmLabel = confirmLabel,
        busy = busy,
        error = error,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmEnabled = confirmEnabled,
    ) {
        content(!busy)
    }
}

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
    var shiftId by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val cashShiftOptions = state.cashExpenseShifts.filter { it.branchId == branchId }

    LaunchedEffect(branchId, method, cashShiftOptions) {
        shiftId = if (method == "cash") {
            cashShiftOptions.firstOrNull { it.id == shiftId }?.id
                ?: cashShiftOptions.firstOrNull()?.id.orEmpty()
        } else {
            ""
        }
    }

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

    FinanceFormDialog(
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
                        shiftId = shiftId.takeIf { method == "cash" },
                        businessDate = businessDate,
                        method = method,
                        amountMinor = amountMinor,
                        sourceRef = sourceRef,
                        note = note,
                    )
                }
            }
        },
    ) { formEnabled ->
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
        BusinessDatePickerField(businessDate, ::changeDate, enabled = formEnabled)
        PickerField(
            "Shop",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
            enabled = formEnabled,
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
            enabled = formEnabled,
        ) { changeMethod(it) }
        if (method == "cash") {
            PickerField(
                "Drawer shift",
                cashShiftOptions.firstOrNull { it.id == shiftId }?.let {
                    "${it.openedBy} · ${it.availableMinor.asRupees()} available"
                } ?: "No verified open drawer",
                cashShiftOptions.map {
                    it.id to "${it.openedBy} · ${it.availableMinor.asRupees()} available"
                },
                enabled = formEnabled && cashShiftOptions.isNotEmpty(),
            ) { shiftId = it }
            Note("This cash is added to the selected open shift drawer when the server records it.")
        }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)", enabled = formEnabled)
        OutlinedTextField(
            value = sourceRef,
            onValueChange = { sourceRef = it.take(160) },
            enabled = formEnabled,
            label = { Text("Evidence reference") },
            supportingText = {
                Text(
                    "Private ERP evidence only. Google Sheets receives a generated ERP reference.",
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(500) },
            enabled = formEnabled,
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
    var shiftId by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val amountMinor = parseRupeesToMinor(amountRupees)
    val owed = state.tipsPayableMinor
    val exceedsOwed = amountMinor != null && owed != null && amountMinor > owed
    val cashShiftOptions = state.cashExpenseShifts.filter { it.branchId == branchId }
    val selectedCashShift = cashShiftOptions.firstOrNull { it.id == shiftId }
    val exceedsDrawer = method == "cash" && amountMinor != null &&
        selectedCashShift != null && amountMinor > selectedCashShift.availableMinor

    LaunchedEffect(branchId, method, cashShiftOptions) {
        shiftId = if (method == "cash") {
            cashShiftOptions.firstOrNull { it.id == shiftId }?.id
                ?: cashShiftOptions.firstOrNull()?.id.orEmpty()
        } else {
            ""
        }
    }

    FinanceFormDialog(
        title = "Pay out tips",
        confirmLabel = "Record payout",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        confirmEnabled = !exceedsOwed && !exceedsDrawer,
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
                        shiftId = shiftId.takeIf { method == "cash" },
                        method = method,
                        amountMinor = parsedAmount,
                        paidAt = nowIso(),
                        note = note,
                    )
                }
            }
        },
    ) { formEnabled ->
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
            enabled = formEnabled,
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
            enabled = formEnabled,
        ) { method = it }
        if (method == "cash") {
            PickerField(
                "Drawer shift",
                selectedCashShift?.let {
                    "${it.openedBy} · ${it.availableMinor.asRupees()} available"
                } ?: "No verified open drawer",
                cashShiftOptions.map {
                    it.id to "${it.openedBy} · ${it.availableMinor.asRupees()} available"
                },
                enabled = formEnabled && cashShiftOptions.isNotEmpty(),
            ) { shiftId = it }
            Note("This cash is deducted from the selected open shift drawer atomically.")
        }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)", enabled = formEnabled)
        if (exceedsOwed) {
            Text(
                "This is more than the ${owed?.asRupees()} currently owed to staff.",
                color = Brand.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (exceedsDrawer) {
            Text(
                "This exceeds the ${selectedCashShift?.availableMinor?.asRupees()} available in the selected drawer.",
                color = Brand.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(500) },
            enabled = formEnabled,
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
    FinanceFormDialog(
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
    ) { formEnabled ->
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
            enabled = formEnabled,
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
    FinanceFormDialog(
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
    ) { formEnabled ->
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
            enabled = formEnabled,
            label = { Text("Void reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BusinessDatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
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
            enabled = enabled,
        )
    }
}

@Composable
private fun ExpenseCreateDialog(state: FinanceUiState, vm: FinanceViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id ?: "") }
    var categoryId by remember { mutableStateOf(state.categoryNames.keys.firstOrNull() ?: "") }
    var amountRupees by remember { mutableStateOf("") }
    var paidVia by remember { mutableStateOf(ExpensePaymentPolicy.DefaultRail) }
    var vendorName by remember { mutableStateOf("") }
    var invoiceNo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var shiftId by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var receipt by remember { mutableStateOf<ExpenseReceiptDraft?>(null) }
    var receiptError by remember { mutableStateOf<String?>(null) }
    var receiptLoading by remember { mutableStateOf(false) }
    var cameraUriValue by rememberSaveable { mutableStateOf<String?>(null) }
    val cashShiftOptions = state.cashExpenseShifts.filter { it.branchId == branchId }
    val selectedCashShift = cashShiftOptions.firstOrNull { it.id == shiftId }

    // Run once on form entry. Re-running when the camera callback clears its
    // URI could race the receipt reader after a very long-lived camera task.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            pruneStaleExpenseReceiptCameraFiles(
                context,
                activeUri = cameraUriValue?.let(Uri::parse),
            )
        }
    }

    fun loadReceipt(uri: Uri, source: String, discardAfter: Boolean = false) {
        receiptLoading = true
        receiptError = null
        coroutineScope.launch {
            try {
                when (val result = loadExpenseReceipt(context, uri, source)) {
                    is ExpenseReceiptLoadResult.Ready -> receipt = result.receipt
                    is ExpenseReceiptLoadResult.Rejected -> receiptError = result.message
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (discardAfter) discardExpenseReceiptCameraUri(context, uri)
                receiptLoading = false
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUriValue?.let(Uri::parse)
        cameraUriValue = null
        if (saved && uri != null) {
            loadReceipt(uri, EXPENSE_RECEIPT_CAMERA_SOURCE, discardAfter = true)
        } else {
            discardExpenseReceiptCameraUri(context, uri)
        }
    }
    val receiptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadReceipt(it, EXPENSE_RECEIPT_FILE_SOURCE) }
    }
    val takePhoto = {
        launchExpenseReceiptCapture(
            create = { createExpenseReceiptCameraUri(context) },
            retain = { uri -> cameraUriValue = uri.toString() },
            launch = cameraLauncher::launch,
            discard = { uri ->
                cameraUriValue = null
                discardExpenseReceiptCameraUri(context, uri)
            },
        )
            .onFailure {
                receiptError = "The camera could not be opened. Choose a receipt file instead."
            }
        Unit
    }

    FinanceFormDialog(
        title = "Add expense",
        confirmLabel = "Queue expense",
        busy = state.busy || receiptLoading,
        error = localError ?: receiptError ?: state.formError,
        onDismiss = {
            discardExpenseReceiptCameraUri(context, cameraUriValue?.let(Uri::parse))
            vm.closeDialog()
        },
        onConfirm = confirmExpense@{
            val amountMinor = parseRupeesToMinor(amountRupees)
            if (amountMinor == null) {
                localError = "Amount must be rupees with no more than 2 decimal places."
                return@confirmExpense
            }
            if (amountMinor <= 0) {
                localError = "Enter an amount greater than ₹0."
                return@confirmExpense
            }
            if (branchId.isBlank() || categoryId.isBlank()) {
                localError = "Pick a branch and a category."
                return@confirmExpense
            }
            val shiftError = cashExpenseSelectionError(
                paidVia = paidVia,
                branchId = branchId,
                shiftId = shiftId.ifBlank { null },
                amountMinor = amountMinor,
                options = cashShiftOptions,
            )
            if (shiftError != null) {
                localError = shiftError
                return@confirmExpense
            }
            localError = null
            vm.postExpense(
                branchId = branchId, categoryId = categoryId, amountMinor = amountMinor,
                paidVia = paidVia, paidAt = nowIso(), vendorName = vendorName,
                invoiceNo = invoiceNo, note = note,
                shiftId = shiftId.ifBlank { null }, receipt = receipt,
            )
        },
    ) { formEnabled ->
        PickerField(
            "Branch",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
            enabled = formEnabled,
        ) { branchId = it }
        PickerField(
            "Category",
            state.categoryNames[categoryId] ?: "Select…",
            state.categoryNames.entries.map { it.key to it.value },
            enabled = formEnabled,
        ) { categoryId = it }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)", enabled = formEnabled)
        PickerField(
            "Paid via",
            paidViaLabel(paidVia),
            ExpensePaymentPolicy.Options.map { it.value to it.label },
            enabled = formEnabled,
        ) { paidVia = it }
        if (paidVia == "cash") {
            Text(
                ExpensePaymentPolicy.CashDrawerGuidance,
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
            PickerField(
                "Open shift drawer",
                selectedCashShift?.let {
                    "${it.openedBy} · ${it.availableMinor.asRupees()} available"
                } ?: "Select the shift that supplied the cash…",
                cashShiftOptions.map {
                    it.id to "${it.openedBy} · ${it.availableMinor.asRupees()} available"
                },
                enabled = formEnabled,
            ) { shiftId = it }
            if (cashShiftOptions.isEmpty()) {
                Text(
                    "No eligible drawer is available on this tablet. Record a cash paid-out " +
                        "from the Android tablet that opened the shift; for a Web-opened drawer, " +
                        "record it in Web ERP. UPI and card expenses remain available here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Warning,
                )
            }
            selectedCashShift?.let { shift ->
                Column(
                    Modifier.fillMaxWidth().clip(Radius.shapeSm)
                        .background(Brand.SurfaceRaised).padding(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Opened by ${shift.openedBy}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.Foreground,
                    )
                    Text(
                        "${shift.expectedMinor.asRupees()} expected in drawer",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                    if (shift.queuedMinor > 0) {
                        Text(
                            "${shift.availableMinor.asRupees()} remains after saved cash paid-outs on this tablet",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.Warning,
                        )
                    }
                }
            }
        } else {
            Text(
                "UPI and business debit-card expenses reduce the Bank balance. Business credit-card liabilities are not supported yet; do not record a credit-card purchase as Card.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        OutlinedTextField(
            value = vendorName, onValueChange = { vendorName = it },
            enabled = formEnabled,
            label = { Text("Vendor (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = invoiceNo, onValueChange = { invoiceNo = it },
            enabled = formEnabled,
            label = { Text("Invoice no. (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            enabled = formEnabled,
            label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Receipt evidence (optional)",
            style = MaterialTheme.typography.labelLarge,
            color = Brand.Foreground,
        )
        Text(
            "Take a clear photo or choose a JPEG, PNG, WebP or PDF up to 10 MB. You can save the expense without a receipt.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 420.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ErpButton(
                        text = "Take photo",
                        onClick = takePhoto,
                        enabled = formEnabled,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.CameraAlt,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ErpButton(
                        text = "Choose receipt",
                        onClick = { receiptPicker.launch(EXPENSE_RECEIPT_PICKER_TYPES) },
                        enabled = formEnabled,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.UploadFile,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ErpButton(
                        text = "Take photo",
                        onClick = takePhoto,
                        enabled = formEnabled,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.CameraAlt,
                        modifier = Modifier.weight(1f),
                    )
                    ErpButton(
                        text = "Choose receipt",
                        onClick = { receiptPicker.launch(EXPENSE_RECEIPT_PICKER_TYPES) },
                        enabled = formEnabled,
                        intent = ActionIntent.Secondary,
                        leadingIcon = Icons.Default.UploadFile,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        receipt?.let { selected ->
            Row(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        selected.filename,
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        expenseReceiptSizeLabel(selected.content.size),
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                ErpButton(
                    text = "Remove",
                    onClick = {
                        receipt = null
                        receiptError = null
                    },
                    enabled = formEnabled,
                    intent = ActionIntent.Quiet,
                    leadingIcon = Icons.Default.Delete,
                )
            }
        }
    }
}

internal fun expenseReceiptSummary(count: Int, status: String): String {
    val evidence = if (count == 0) "No receipt" else countLabel(count, "receipt")
    val review = when (status) {
        "verified" -> "Verified"
        "rejected" -> "Rejected"
        "not_required" -> "Not required"
        else -> "Needs review"
    }
    return "$evidence · $review"
}

private fun expenseReceiptStatusColor(status: String): Color = when (status) {
    "verified", "not_required" -> Brand.Good
    "rejected" -> Brand.Danger
    else -> Brand.Warning
}

internal fun expenseReceiptSizeLabel(sizeBytes: Int): String = when {
    sizeBytes >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, sizeBytes / (1024.0 * 1024.0))
    sizeBytes >= 1024 -> "%.1f KB".format(Locale.ROOT, sizeBytes / 1024.0)
    else -> "$sizeBytes bytes"
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

    FinanceFormDialog(
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
    ) { formEnabled ->
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            enabled = formEnabled,
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        PickerField(
            "Branch",
            state.branches.firstOrNull { it.id == branchId }?.name ?: "Select…",
            state.branches.map { it.id to it.name },
            enabled = formEnabled,
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
            enabled = formEnabled,
        ) { type = it }
        DecimalField(
            purchaseRupees,
            { purchaseRupees = it },
            "Purchase cost (₹)",
            enabled = formEnabled,
        )
        OutlinedTextField(
            value = usefulLifeMonths,
            onValueChange = { usefulLifeMonths = it.filter(Char::isDigit) },
            enabled = formEnabled,
            label = { Text("Useful life (months)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        DecimalField(
            salvageRupees,
            { salvageRupees = it },
            "Salvage value (₹)",
            enabled = formEnabled,
        )
        OutlinedTextField(
            value = notesText, onValueChange = { notesText = it },
            enabled = formEnabled,
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

    FinanceFormDialog(
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
    ) { formEnabled ->
        PickerField(
            "Type", if (type == "invest") "Investment" else "Capital repayment",
            listOf("invest" to "Investment", "withdraw" to "Capital repayment"),
            enabled = formEnabled,
        ) { type = it }
        DecimalField(amountRupees, { amountRupees = it }, "Amount (₹)", enabled = formEnabled)
        PickerField(
            "Settlement account", paidViaLabel(settlementAccount).let { if (it == "Bank transfer") "Bank" else it },
            listOf("cash" to "Cash", "bank" to "Bank", "upi" to "UPI"),
            enabled = formEnabled,
        ) { settlementAccount = it }
        OutlinedTextField(
            value = sourceRef, onValueChange = { sourceRef = it },
            enabled = formEnabled,
            label = { Text("Bank UTR / UPI id / voucher no.") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Must be unique. On a network retry, submit the same reference instead of inventing a new one.",
            style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            enabled = formEnabled,
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
