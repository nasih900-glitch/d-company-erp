package cloud.dcompany.erp.ui.screens.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import kotlin.math.abs

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
    FinanceContent(state, onRetry = vm::load)
}

@Composable
private fun FinanceContent(state: FinanceUiState, onRetry: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Expenses", "Partners")

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Header(state, onRetry)

        when {
            // Nothing to show yet and it failed: this is the whole screen.
            !state.loaded && state.error != null -> ErrorBlock(state.error, onRetry)

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
                    ErrorBanner(state.error, onRetry)
                }
                when (tab) {
                    0 -> OverviewTab(state)
                    1 -> ExpensesTab(state)
                    else -> PartnersTab(state)
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
            "Read-only on the tablet. Recording expenses and partner capital movements " +
                "stays in the web ERP, where the evidence reference can be typed in. For " +
                "monthly, quarterly and yearly P&L, open Reports there.",
        )
    }
}

// ============================================================================
// EXPENSES
// ============================================================================
@Composable
private fun ExpensesTab(state: FinanceUiState) {
    if (state.expenses.isEmpty()) {
        EmptyBlock(
            title = "No expenses recorded yet",
            body = "Rent, salaries, supplies and utilities recorded in the web ERP " +
                "(Finance → Expenses) show up here, newest first.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "${countLabel(state.expenses.size, "expense")} · Total: " +
                    state.expenseTotalMinor.asRupees(),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.ForegroundMuted,
            )
        }
        items(state.expenses, key = { it.id }) { expense ->
            ExpenseRow(expense, state.categoryName(expense.categoryId))
        }
        item {
            Note("Every expense ever recorded, newest first.")
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
// PARTNERS
// ============================================================================
@Composable
private fun PartnersTab(state: FinanceUiState) {
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
            PartnerCard(partner, shareByPartnerId[partner.id])
        }

        Note(
            "Investment records money a partner put in; capital repayment records money " +
                "paid back. Profit share is calculated separately and does not change " +
                "contributed capital. Both are recorded in the web ERP.",
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
private fun PartnerCard(partner: Partner, share: DistributablePartnerShare?) {
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
            .clip(RoundedCornerShape(14.dp))
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
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Surface)
            .then(
                if (border != null) {
                    Modifier.border(1.dp, border, RoundedCornerShape(14.dp))
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
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Surface)
            .border(1.dp, Brand.Danger, RoundedCornerShape(12.dp))
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
