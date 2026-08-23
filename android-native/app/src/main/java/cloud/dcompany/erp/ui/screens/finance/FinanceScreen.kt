package cloud.dcompany.erp.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Finance — read-only.
 *
 * Mirrors the web Finance screen's Overview, Expenses and Partners tabs, using
 * its plain-language labels, but deliberately without the write paths. An
 * expense, a capital movement and a void are all immutable ledger entries that
 * need typed evidence (bank UTR, UPI id, voucher no., a written void reason);
 * those belong in the web ERP, not on a counter tablet. What an owner actually
 * wants here is to *look*: what came in, what went out, and how much is
 * genuinely safe to take out.
 */
@Composable
fun FinanceScreen() {
    val vm: FinanceViewModel = viewModel()
    val state by vm.state.collectAsState()
    FinanceContent(state, vm)
}

@Composable
private fun FinanceContent(state: FinanceUiState, vm: FinanceViewModel) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Expenses", "Assets", "Partners")

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
                    CircularProgressIndicator(color = Brand.Gold)
                    Text("Reading the books…", color = Brand.ForegroundMuted)
                }
            }

            else -> {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Brand.Background,
                    contentColor = Brand.Gold,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            selectedContentColor = Brand.Gold,
                            unselectedContentColor = Brand.ForegroundMuted,
                            text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                        )
                    }
                }
                // A refresh that failed on top of good data: keep the figures,
                // but never let the failure pass unmentioned.
                if (state.error != null) {
                    ErrorBanner(state.error, vm::load)
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
                        PendingFinanceChangesPanel(state, vm)
                    }
                }
                when (tab) {
                    0 -> OverviewTab(state)
                    1 -> ExpensesTab(state, vm)
                    2 -> AssetsTab(state, vm)
                    else -> PartnersTab(state, vm)
                }

                when (val dialog = state.dialog) {
                    FinanceDialog.ExpenseForm -> ExpenseCreateDialog(state, vm)
                    FinanceDialog.AssetForm -> AssetCreateDialog(state, vm)
                    is FinanceDialog.CapitalEntryForm -> CapitalEntryCreateDialog(dialog.partner, state, vm)
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun Header(state: FinanceUiState, onRefresh: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Finance",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Brand.Foreground,
                )
                Text(
                    "P&L · expenses · partner capital",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                Text(if (state.loading) "Refreshing…" else "Refresh")
            }
        }
        if (state.loading && state.loaded) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Gold,
                trackColor = Brand.Surface,
            )
        }
    }
}

// ============================================================================
// OVERVIEW
// ============================================================================
@Composable
private fun OverviewTab(state: FinanceUiState) {
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

        if (state.periodIdle) {
            Panel(border = Brand.GoldMuted) {
                Text("No activity this period yet.", color = Brand.Gold, fontWeight = FontWeight.Bold)
                Text(
                    "Take an order in POS, or record an expense in the web ERP, and real " +
                        "numbers appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
            }
        }

        StatGrid(
            listOfNotNull(
                StatSpec(
                    "Net revenue (after GST)",
                    pl.revenueMinor.asRupees(),
                    "sales, once any GST collected is taken out",
                    if (pl.revenueMinor > 0) Tone.Good else Tone.Default,
                ),
                StatSpec(
                    "Cost of goods sold",
                    pl.cogsMinor.asRupees(),
                    "what the food/drinks/items you sold actually cost you",
                ),
                StatSpec(
                    "Gross profit",
                    pl.grossProfitMinor.asRupees(),
                    "net revenue less cost of goods sold",
                    if (pl.grossProfitMinor < 0) Tone.Bad else Tone.Good,
                ),
                StatSpec("Expenses", pl.expensesMinor.asRupees(), "running costs this period", Tone.Bad),
                StatSpec(
                    "Operating profit",
                    pl.netProfitMinor.asRupees(),
                    "after equipment depreciation",
                    if (pl.netProfitMinor < 0) Tone.Bad else Tone.Good,
                ),
                metrics?.let {
                    StatSpec(
                        "Burn rate",
                        it.burnRateMinor.asRupees(),
                        if (it.burnRateMinor > 0) {
                            "lost money this period, after cost of goods sold and expenses"
                        } else {
                            "profitable this period"
                        },
                        if (it.burnRateMinor > 0) Tone.Bad else Tone.Good,
                    )
                },
                distributable?.let {
                    StatSpec(
                        "Safe to distribute right now",
                        it.safeToDistributeMinor.asRupees(),
                        "after the reserve, capped by cash on hand · detail in Partners",
                        if (it.safeToDistributeMinor > 0) Tone.Good else Tone.Default,
                    )
                },
            ),
        )

        Panel {
            SectionTitle("Profit and loss")
            Spacer(Modifier.height(6.dp))
            PlRow("Net revenue (after GST)", pl.revenueMinor)
            PlRow(
                "Less: cost of goods sold",
                pl.cogsMinor,
                sub = "what the food/drinks/items you sold actually cost you",
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
            StatGrid(
                listOf(
                    StatSpec(
                        "Avg order value (this period)",
                        metrics.aovMinor.asRupees(),
                        "${countLabel(metrics.ordersCount, "order")} this period",
                    ),
                    StatSpec(
                        "MRR",
                        metrics.mrrMinor.asRupees(),
                        "${countLabel(metrics.activeMembersCount, "active member")} right now",
                    ),
                    StatSpec("ARR", metrics.arrMinor.asRupees(), "MRR × 12"),
                    StatSpec(
                        "Customer LTV (all-time)",
                        metrics.ltvMinor.asRupees(),
                        "avg across ${countLabel(metrics.customersCount, "customer")}, all-time",
                    ),
                    StatSpec(
                        "CAC",
                        metrics.cacMinor?.asRupees() ?: "—",
                        if (metrics.cacMinor == null) {
                            "no new customers this period"
                        } else {
                            "${metrics.marketingSpendMinor.asRupees()} marketing ÷ " +
                                "${metrics.newCustomersCount} new"
                        },
                    ),
                    StatSpec(
                        "Burn rate",
                        metrics.burnRateMinor.asRupees(),
                        if (metrics.burnRateMinor > 0) {
                            "lost money this period, after cost of goods sold and expenses"
                        } else {
                            "profitable this period"
                        },
                        if (metrics.burnRateMinor > 0) Tone.Bad else Tone.Good,
                    ),
                ),
            )
            Note(
                "These are the metrics that actually fit a single-location, self-funded " +
                    "business — not SaaS/VC fundraising metrics like Rule of 40 or " +
                    "TAM/SAM/SOM, which don't apply here.",
            )
        }

        Note(
            "Expenses, assets and partner capital movements can now be recorded right " +
                "here. For monthly, quarterly and yearly P&L, open Reports in the web ERP.",
        )
    }
}

// ============================================================================
// EXPENSES
// ============================================================================
@Composable
private fun ExpensesTab(state: FinanceUiState, vm: FinanceViewModel) {
    if (state.expenses.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = vm::openExpenseForm) { Text("New expense") }
            }
            EmptyBlock(
                title = "No expenses recorded yet",
                body = "Record one here, or in the web ERP (Finance → Expenses) — either " +
                    "way it shows up here, newest first.",
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${countLabel(state.expenses.size, "expense")} · Total: " +
                        state.expenseTotalMinor.asRupees(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
                Button(onClick = vm::openExpenseForm) { Text("New expense") }
            }
        }
        items(state.expenses, key = { it.id }) { expense ->
            ExpenseRow(expense, state.categoryName(expense.categoryId))
        }
        item {
            Note("Every expense ever recorded, newest first. Editing or deleting one still needs the web ERP.")
        }
    }
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
                    color = Brand.Gold,
                )
            }
        }
    }
}

// ============================================================================
// ASSETS
// ============================================================================
@Composable
private fun AssetsTab(state: FinanceUiState, vm: FinanceViewModel) {
    if (state.assets.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = vm::openAssetForm) { Text("New asset") }
            }
            EmptyBlock(
                title = "No assets registered yet",
                body = "PS5s, TVs, the projector, kitchen equipment — register one here, " +
                    "or in the web ERP (Finance → Assets). Depreciation is computed " +
                    "straight-line automatically.",
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    countLabel(state.assets.size, "asset"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
                Button(onClick = vm::openAssetForm) { Text("New asset") }
            }
        }
        items(state.assets, key = { it.id }) { asset -> AssetRow(asset) }
        item {
            Note("Straight-line depreciation, recomputed as of today on every load. Editing an asset still needs the web ERP.")
        }
    }
}

@Composable
private fun AssetRow(asset: Asset) {
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
                    "${asset.type} · Bought ${asset.purchaseDate.asDay()}",
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
                    color = Brand.Gold,
                )
            }
        }
    }
}

// ============================================================================
// PARTNERS
// ============================================================================
@Composable
private fun PartnersTab(state: FinanceUiState, vm: FinanceViewModel) {
    val distributable = state.distributable

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (distributable != null) {
            DistributableCard(distributable)
        }

        if (state.partners.isEmpty()) {
            Panel {
                Text("No partners yet", color = Brand.Foreground, fontWeight = FontWeight.Bold)
                Text(
                    "Add partners and record their capital in the web ERP " +
                        "(Finance → Partners). Their capital balance and safe-to-take-out " +
                        "share then appear here.",
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

        val shareByPartnerId = distributable?.partners.orEmpty().associateBy { it.partnerId }
        state.partners.forEach { partner ->
            PartnerCard(partner, shareByPartnerId[partner.id], onRecordCapital = { vm.openCapitalEntryForm(partner) })
        }

        Note(
            "Investment records money a partner put in; capital repayment records money " +
                "paid back. Profit share is calculated separately and does not change " +
                "contributed capital. Voiding a capital entry still needs the web ERP.",
        )
    }
}

@Composable
private fun DistributableCard(d: DistributableProfit) {
    Panel(border = Brand.Good) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Safe to distribute right now",
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                )
                Text(
                    "All-time profit, minus everything ever withdrawn, minus a " +
                        "${d.reserveMonths}-month safety buffer — capped by actual cash on " +
                        "hand, not just what the books say. Already charges straight-line " +
                        "depreciation on gaming/kitchen equipment wearing out " +
                        "(${d.lifetimeDepreciationMinor.asRupees()} to date), so this is the " +
                        "real number, not an upper bound.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
            Text(
                d.safeToDistributeMinor.asRupees(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (d.safeToDistributeMinor > 0) Brand.Good else Brand.Foreground,
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
                StatSpec("Cash on hand", d.liquidCashMinor.asRupees()),
            ),
            columns = 4,
            surface = Brand.SurfaceRaised,
        )
        if (d.cashBasedCapacityMinor < d.profitBasedCapacityMinor) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Limited by cash on hand, not by profit — some of what you've earned is " +
                    "currently tied up in stock or equipment.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.Gold,
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
                    color = if (share.distributableShareMinor > 0) Brand.Good else Brand.Foreground,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRecordCapital, modifier = Modifier.fillMaxWidth()) {
            Text("Record capital movement")
        }
    }
}

// ============================================================================
// SHARED PIECES
// ============================================================================
private enum class Tone { Default, Good, Bad }

private data class StatSpec(
    val label: String,
    val value: String,
    val sub: String? = null,
    val tone: Tone = Tone.Default,
)

private fun Tone.color(): Color = when (this) {
    Tone.Good -> Brand.Good
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(columns).forEach { rowStats ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowStats.forEach { stat ->
                    StatCard(stat, surface, Modifier.weight(1f))
                }
                // Keeps the last, short row's cards the same width as the rest.
                repeat(columns - rowStats.size) {
                    Spacer(Modifier.weight(1f))
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
                less -> Brand.Danger
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
            modifier = Modifier.width(420.dp).padding(24.dp),
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
private fun EmptyBlock(title: String, body: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            modifier = Modifier.width(420.dp).padding(24.dp),
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
private fun PendingFinanceChangesPanel(state: FinanceUiState, vm: FinanceViewModel) {
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
                onRetry = { vm.retryExpense(row.localId) },
            )
        }
        state.pendingAssets.forEach { row ->
            FinancePendingRow(
                text = "New asset: ${row.name} (${row.purchaseMinor.asRupees()})",
                rejected = row.rejected,
                error = row.error,
                onRetry = { vm.retryAsset(row.localId) },
            )
        }
        state.pendingCapitalEntries.forEach { row ->
            val verb = if (row.type == "invest") "Investment from" else "Capital repayment to"
            FinancePendingRow(
                text = "$verb ${row.partnerName}: ${row.amountMinor.asRupees()}",
                rejected = row.rejected,
                error = row.error,
                onRetry = { vm.retryCapitalEntry(row.localId) },
            )
        }
    }
}

@Composable
private fun FinancePendingRow(text: String, rejected: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (rejected) TextButton(onClick = onRetry) { Text("Retry") }
        }
        Text(
            if (rejected) "Could not sync: ${error ?: "unknown error"}" else "Not synced yet",
            color = if (rejected) Brand.Danger else Brand.GoldMuted,
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
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// ============================================================================
// CREATE DIALOGS
// ============================================================================
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
            val amountMinor = amountRupees.toRupeesMinor()
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
            listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card", "bank" to "Bank transfer"),
        ) { paidVia = it }
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
private fun AssetCreateDialog(state: FinanceUiState, vm: FinanceViewModel) {
    var branchId by remember { mutableStateOf(state.branches.firstOrNull()?.id ?: "") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("kitchen_equipment") }
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
            val purchaseMinor = purchaseRupees.toRupeesMinor()
            val salvageMinor = salvageRupees.ifBlank { "0" }.toRupeesMinor()
            val lifeMonths = usefulLifeMonths.toIntOrNull() ?: 0
            when {
                name.isBlank() -> localError = "Enter a name for this asset."
                branchId.isBlank() -> localError = "Pick a branch."
                purchaseMinor <= 0 -> localError = "Enter a purchase cost greater than ₹0."
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
            type,
            listOf(
                "kitchen_equipment" to "Kitchen equipment", "gaming" to "Gaming",
                "furniture" to "Furniture", "electronics" to "Electronics", "other" to "Other",
            ),
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
            val amountMinor = amountRupees.toRupeesMinor()
            val trimmedRef = sourceRef.trim()
            when {
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
@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.width(480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Working…" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun PickerField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised)
                    .clickable(enabled = options.isNotEmpty()) { open = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLabel,
                    color = if (options.isEmpty()) Brand.ForegroundMuted else Brand.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("▾", color = Brand.Gold)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            open = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(filterDecimal(it)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun filterDecimal(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> {
                dotSeen = true
                sb.append(c)
            }
        }
    }
    return sb.toString()
}

/** Rupees typed by a human -> whole paise, rounded not truncated (the exact
 * same IEEE-754 edge case Menu/Inventory's own equivalents guard against —
 * .toLong() on rupees*100 can silently truncate a value like 2.66). */
private fun String.toRupeesMinor(): Long = ((toDoubleOrNull() ?: 0.0) * 100).roundToLong()

private fun nowIso(): String = java.time.OffsetDateTime.now().toString()
