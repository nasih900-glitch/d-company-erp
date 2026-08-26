package cloud.dcompany.erp.ui.screens.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Reports — daily / monthly / quarterly / yearly P&L, straight from
 * /reports/… endpoints. The wording is deliberately the plain-language wording the
 * owners already read on the web build ("what's really yours before any
 * costs"), because the whole point of this screen is that a non-accountant
 * can act on it without asking anyone what a line means.
 */
@Composable
fun ReportsScreen() {
    val vm: ReportsViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background)
    ) {
        Header()
        PeriodTabs(state.period, vm::selectPeriod)
        PeriodSelector(
            state = state,
            onStep = vm::step,
            onPickDate = vm::setDate,
            onPickMonth = vm::setMonth,
            onPickQuarter = vm::setQuarter,
            onJumpToCurrent = vm::jumpToCurrent,
        )

        Box(Modifier.fillMaxSize()) {
            val report = state.report
            when (reportPresentation(state)) {
                ReportPresentation.INITIAL_LOADING -> LoadingPanel()
                ReportPresentation.BLOCKING_ERROR -> ErrorPanel(state.error!!, vm::retry)
                ReportPresentation.FRESH_EMPTY -> EmptyPanel(
                    state.period,
                    report!!.label,
                    state.loading,
                    state.fetchedAtMillis,
                )
                ReportPresentation.FRESH_CONTENT -> ReportBody(
                    report!!,
                    state.period,
                    state.loading,
                    state.fetchedAtMillis,
                )
                ReportPresentation.STALE_EMPTY,
                ReportPresentation.STALE_CONTENT -> Column(Modifier.fillMaxSize()) {
                    StaleReportBanner(state.error!!, vm::retry)
                    Box(Modifier.weight(1f)) {
                        if (report!!.hasNothing) {
                            EmptyPanel(
                                state.period,
                                report.label,
                                state.loading,
                                state.fetchedAtMillis,
                            )
                        } else {
                            ReportBody(
                                report,
                                state.period,
                                state.loading,
                                state.fetchedAtMillis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
        Text(
            "Reports",
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.Foreground,
        )
        Text(
            "Management P&L · operational receipt basis · not statutory accounts",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun PeriodTabs(selected: ReportPeriod, onSelect: (ReportPeriod) -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReportPeriod.entries.forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.tab) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Gold,
                    selectedLabelColor = Brand.Background,
                    labelColor = Brand.ForegroundMuted,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- selectors

@Composable
private fun PeriodSelector(
    state: ReportsUiState,
    onStep: (Int) -> Unit,
    onPickDate: (LocalDate) -> Unit,
    onPickMonth: (YearMonth) -> Unit,
    onPickQuarter: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
) {
    var showCalendar by remember { mutableStateOf(false) }

    SectionCard(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepButton("‹", enabled = true) { onStep(-1) }

            // Tapping the label opens a calendar for the two periods where a
            // calendar is the natural way to reach a distant date. Stepping is
            // still there because "yesterday" is the query staff actually run.
            val labelModifier = when (state.period) {
                ReportPeriod.DAILY, ReportPeriod.MONTHLY ->
                    Modifier.clickable { showCalendar = true }
                else -> Modifier
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(labelModifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    selectorLabel(state),
                    style = MaterialTheme.typography.titleLarge,
                    color = Brand.Foreground,
                    textAlign = TextAlign.Center,
                )
                if (state.period == ReportPeriod.DAILY || state.period == ReportPeriod.MONTHLY) {
                    Text(
                        "Tap to pick",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.GoldMuted,
                    )
                }
            }

            StepButton("›", enabled = state.canStepForward) { onStep(1) }

            if (!isCurrentPeriod(state)) {
                OutlinedButton(onClick = onJumpToCurrent) { Text(currentWord(state.period)) }
            }
        }

        if (state.period == ReportPeriod.QUARTERLY) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUARTER_LABELS.forEachIndexed { index, label ->
                    val q = index + 1
                    FilterChip(
                        selected = state.quarter == q,
                        enabled = canSelectFiscalQuarter(state.fiscalYear, q),
                        onClick = { onPickQuarter(q) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Brand.Gold,
                            selectedLabelColor = Brand.Background,
                            labelColor = Brand.ForegroundMuted,
                        ),
                    )
                }
            }
        }
    }

    if (showCalendar) {
        CalendarDialog(
            initial = if (state.period == ReportPeriod.DAILY) {
                state.onDate
            } else {
                state.month.atDay(1)
            },
            onDismiss = { showCalendar = false },
            onPicked = { picked ->
                showCalendar = false
                if (state.period == ReportPeriod.DAILY) onPickDate(picked)
                else onPickMonth(YearMonth.from(picked))
            },
        )
    }
}

private val QUARTER_LABELS = listOf("Q1 Apr–Jun", "Q2 Jul–Sep", "Q3 Oct–Dec", "Q4 Jan–Mar")

private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

private fun selectorLabel(state: ReportsUiState): String = when (state.period) {
    ReportPeriod.DAILY -> {
        val today = businessToday()
        when (state.onDate) {
            today -> "Today · ${state.onDate.format(DAY_FORMAT)}"
            today.minusDays(1) -> "Yesterday · ${state.onDate.format(DAY_FORMAT)}"
            else -> state.onDate.format(DAY_FORMAT)
        }
    }
    ReportPeriod.MONTHLY -> state.month.atDay(1).format(MONTH_FORMAT)
    ReportPeriod.QUARTERLY -> "FY ${state.fiscalYear}"
    ReportPeriod.YEARLY -> "FY ${state.fiscalYear} (Apr–Mar)"
}

private fun isCurrentPeriod(state: ReportsUiState): Boolean {
    val today = businessToday()
    return when (state.period) {
        ReportPeriod.DAILY -> state.onDate == today
        ReportPeriod.MONTHLY -> state.month == YearMonth.from(today)
        ReportPeriod.QUARTERLY ->
            state.fiscalYear == fiscalYearFor(today) && state.quarter == fiscalQuarterFor(today)
        ReportPeriod.YEARLY -> state.fiscalYear == fiscalYearFor(today)
    }
}

private fun currentWord(period: ReportPeriod): String = when (period) {
    ReportPeriod.DAILY -> "Today"
    ReportPeriod.MONTHLY -> "This month"
    ReportPeriod.QUARTERLY -> "This quarter"
    ReportPeriod.YEARLY -> "This year"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val todayMillis = remember { businessToday().toUtcMillis() }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toUtcMillis(),
        // Nothing has been billed in the future, so offering future dates only
        // ever produces an empty report and a confused owner.
        selectableDates = remember(todayMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= businessToday().year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedDateMillis != null,
                onClick = {
                    pickerState.selectedDateMillis?.let { onPicked(it.toLocalDateUtc()) }
                },
            ) { Text("Show report") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        colors = DatePickerDefaults.colors(containerColor = Brand.Surface),
    ) {
        DatePicker(
            state = pickerState,
            colors = DatePickerDefaults.colors(containerColor = Brand.Surface),
        )
    }
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

// ------------------------------------------------------------ empty states

@Composable
private fun LoadingPanel() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Brand.Gold)
        Spacer(Modifier.height(12.dp))
        Text("Computing…", color = Brand.ForegroundMuted)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Could not load this report",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.Foreground,
        )
        Spacer(Modifier.height(8.dp))
        // The server's own words. "Report failed" would hide the difference
        // between "your account cannot see money reports" and "the link
        // dropped", which need completely different responses.
        Text(
            message,
            color = Brand.ForegroundMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun StaleReportBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(Radius.shapeMd)
            .background(Brand.SurfaceRaised)
            .border(BorderStroke(1.dp, Brand.Danger), Radius.shapeMd)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Saved report — refresh failed", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(
                "$message The figures below may be out of date.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyPanel(period: ReportPeriod, label: String, refreshing: Boolean, fetchedAtMillis: Long?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing recorded in this period",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.Foreground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No sales, tickets, payments or expenses fall inside " +
                label.ifBlank { "this period" } + ". " +
                "Use the arrows above to look at another ${unitWord(period)}, " +
                "or leave this open — figures appear here as soon as billing starts.",
            color = Brand.ForegroundMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        // Same reasoning as ReportHeading: an empty result is itself a cached
        // fact that can go stale — a period that had nothing yesterday but
        // has since been billed into must not still read as empty forever.
        if (fetchedAtMillis != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "As of ${relativeAge(fetchedAtMillis)}" + if (refreshing) " · refreshing…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.GoldMuted,
            )
        }
    }
}

private fun unitWord(period: ReportPeriod): String = when (period) {
    ReportPeriod.DAILY -> "day"
    ReportPeriod.MONTHLY -> "month"
    ReportPeriod.QUARTERLY -> "quarter"
    ReportPeriod.YEARLY -> "year"
}

// ----------------------------------------------------------------- report

@Composable
private fun ReportBody(
    report: ReportData,
    period: ReportPeriod,
    refreshing: Boolean,
    fetchedAtMillis: Long?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val columns = if (wide) 4 else 2

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReportHeading(report, period, refreshing, fetchedAtMillis)

            KpiGrid(report, columns)

            if (report.manualCollectionsMinor > 0) {
                ManualCollectionsNotice(report.manualCollectionsMinor)
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RevenueCard(report)
                        PaymentsCard(report)
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GstCard(report)
                        ExpensesCard(report)
                    }
                }
            } else {
                RevenueCard(report)
                GstCard(report)
                PaymentsCard(report)
                ExpensesCard(report)
            }

            BottomLine(report)

            Text(
                "Computed live from orders and journal entries. " +
                    "Collected GST is shown for accountant review — input tax credit is not applied.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun ReportHeading(
    report: ReportData,
    period: ReportPeriod,
    refreshing: Boolean,
    fetchedAtMillis: Long?,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                period.title,
                style = MaterialTheme.typography.titleLarge,
                color = Brand.Foreground,
            )
            Spacer(Modifier.size(10.dp))
            Text(report.label, color = Brand.ForegroundMuted)
            if (refreshing) {
                Spacer(Modifier.size(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Brand.Gold,
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            "${prettyDate(report.periodStart)} to ${prettyDate(report.periodEnd)} · FY ${report.fiscalYear}",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        // A cached figure must never pass for a fresh one — this is the one
        // line standing between "the tablet was offline" and an owner making
        // a call on numbers that are actually a day old.
        if (fetchedAtMillis != null) {
            Text(
                "As of ${relativeAge(fetchedAtMillis)}" + if (refreshing) " · refreshing…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.GoldMuted,
            )
        }
    }
}

private fun relativeAge(fetchedAtMillis: Long): String {
    val seconds = (System.currentTimeMillis() - fetchedAtMillis) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

@Composable
private fun KpiGrid(report: ReportData, columns: Int) {
    val tiles = listOf(
        KpiTile("Orders", report.ordersCount.toString(), null),
        KpiTile("Tickets", report.ticketsCount.toString(), null),
        KpiTile("Avg ticket", report.avgTicketMinor.asRupees(), null),
        KpiTile("Net revenue", report.netRevenueMinor.asRupees(), null),
        KpiTile(
            "Profit margin",
            percent(report.netProfitMinor, report.netRevenueMinor),
            report.netProfitMinor >= 0,
        ),
        KpiTile(
            "Cost ratio",
            percent(report.totalCostsMinor, report.netRevenueMinor),
            report.totalCostsMinor <= report.netRevenueMinor,
        ),
        KpiTile("Net profit", report.netProfitMinor.asRupees(), report.netProfitMinor >= 0),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    KpiCard(tile, Modifier.weight(1f))
                }
                // Keeps the last, short row's tiles the same width as the rest
                // instead of stretching two tiles across the whole tablet.
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class KpiTile(val label: String, val value: String, val good: Boolean?)

@Composable
private fun KpiCard(tile: KpiTile, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(
            tile.label.uppercase(Locale.ENGLISH),
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tile.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = when (tile.good) {
                null -> Brand.Foreground
                true -> Brand.Good
                false -> Brand.Danger
            },
        )
    }
}

@Composable
private fun ManualCollectionsNotice(amountMinor: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.SurfaceRaised)
            .border(BorderStroke(1.dp, Brand.GoldMuted), Radius.shapeLg)
            .padding(16.dp),
    ) {
        Text(
            "${amountMinor.asRupees()} is unitemized manual collection.",
            color = Brand.Gold,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "It is counted once in revenue and once in payment movement, but it has no POS " +
                "order, tax invoice, item mix or automatic cost of goods. The source " +
                "references and void history live in Finance → Manual collections.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun RevenueCard(report: ReportData) {
    val r = report.revenue
    SectionCard {
        CardTitle("Revenue")
        MoneyRow("Food / drinks / desserts", r.foodMinor)
        MoneyRow("Gaming", r.gamingMinor)
        if (r.hookahMinor > 0) MoneyRow("Hookah", r.hookahMinor)
        MoneyRow("Event tickets", r.eventTicketsMinor)
        if (r.membershipsMinor > 0) MoneyRow("Memberships", r.membershipsMinor)
        MoneyRow(
            "Delivery (Zomato/Swiggy §9(5))",
            r.deliveryAggregatorMinor,
            sub = "aggregator pays the GST",
        )
        if (r.manualCollectionsMinor > 0) {
            MoneyRow(
                "Manual collections (unitemized)",
                r.manualCollectionsMinor,
                sub = "off-POS / legacy daily totals",
            )
        }
        if (r.otherMinor > 0) MoneyRow("Other", r.otherMinor)
        if (r.discountsAndPointsRedeemedMinor > 0) {
            MoneyRow("Less: discounts & points redeemed", -r.discountsAndPointsRedeemedMinor)
        }
        if (r.roundingIncomeMinor > 0) {
            MoneyRow("Invoice round-up", r.roundingIncomeMinor)
        }
        if (r.roundingExpenseMinor > 0) {
            MoneyRow("Less: invoice round-down", -r.roundingExpenseMinor)
        }
        Divider()
        MoneyRow(
            "Gross revenue",
            r.totalMinor,
            sub = "everything customers paid, before tax and costs",
            bold = true,
        )
        if (report.refundsIssuedMinor > 0) {
            MoneyRow("Less: refunds", -report.refundsIssuedMinor)
        }
        if (report.refundedTipsMinor > 0) {
            MoneyRow(
                "Add back: refunded tips",
                report.refundedTipsMinor,
                sub = "tips were a staff liability, not cafe revenue",
            )
        }
        MoneyRow(
            "Less: GST collected",
            -report.taxCollected.totalMinor,
            sub = "owed to the government, never was your money",
        )
        Divider()
        MoneyRow(
            "Net revenue (after GST)",
            report.netRevenueMinor,
            sub = "what's really yours before any costs",
            bold = true,
        )
        MoneyRow(
            "Less: cost of goods sold",
            -report.cogsMinor,
            sub = "what the food/drinks/items you sold actually cost you",
        )
        Divider()
        MoneyRow(
            "Gross profit",
            report.grossProfitMinor,
            sub = "what's left after replacing what you sold",
            bold = true,
        )
    }
}

@Composable
private fun GstCard(report: ReportData) {
    val t = report.taxCollected
    SectionCard {
        CardTitle("GST collected")
        MoneyRow("CGST", t.cgstMinor)
        MoneyRow("SGST", t.sgstMinor)
        if (t.igstMinor > 0) MoneyRow("IGST (inter-state)", t.igstMinor)
        if (t.cessMinor > 0) MoneyRow("Cess", t.cessMinor)
        Divider()
        MoneyRow("Total GST", t.totalMinor, bold = true)
        Spacer(Modifier.height(8.dp))
        Text(
            "Collected GST for accountant review and return preparation. " +
                "Input tax credit is not applied by this report.",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun PaymentsCard(report: ReportData) {
    val p = report.paymentsReceived
    SectionCard {
        CardTitle("Payment movement")
        MoneyRow("Cash", p.cashMinor)
        MoneyRow("UPI", p.upiMinor)
        MoneyRow("Card", p.cardMinor)
        MoneyRow("Bank transfer", p.bankMinor)
        MoneyRow("QR", p.qrMinor)
        MoneyRow("Wallet", p.walletMinor)
        if (p.otherMinor > 0) MoneyRow("Other", p.otherMinor)
        Divider()
        MoneyRow("Gross payments collected", p.totalMinor)
        if (report.tipsCollectedMinor > 0) {
            MoneyRow(
                "Of which: tips held for staff",
                report.tipsCollectedMinor,
                sub = "included in payments, excluded from revenue",
            )
        }
        if (report.settledRefundsIssuedMinor > 0) {
            MoneyRow(
                "Less: cash/payment refunds",
                -report.settledRefundsIssuedMinor,
                sub = report.membershipRefundsIssuedMinor.takeIf { it > 0 }?.let {
                    "includes ${it.asRupees()} in settled membership reversals"
                },
            )
        }
        val storeCreditRefunds = report.refundsIssuedMinor - report.settledRefundsIssuedMinor
        if (storeCreditRefunds > 0) {
            MoneyRow(
                "Store-credit refunds (no cash movement)",
                storeCreditRefunds,
            )
        }
        Divider()
        MoneyRow(
            "Net payment movement",
            report.netPaymentsReceivedMinor,
            sub = "money that actually moved, not profit",
            bold = true,
        )
    }
}

@Composable
private fun ExpensesCard(report: ReportData) {
    SectionCard {
        CardTitle("Expenses")
        if (report.expenses.isEmpty()) {
            Text(
                "No expenses recorded in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.ForegroundMuted,
            )
        } else {
            report.expenses.forEach { line ->
                MoneyRow(line.category, line.amountMinor)
            }
            Divider()
            MoneyRow("Total expenses", report.expenseTotalMinor, bold = true)
        }
        if (report.depreciationMinor > 0) {
            // Sits outside expense_total_minor on the server but inside net
            // profit. Hiding it would leave the bottom line unexplainable.
            Spacer(Modifier.height(6.dp))
            MoneyRow(
                "Equipment wearing out (depreciation)",
                report.depreciationMinor,
                sub = "not cash — this period's share of gear already bought",
            )
        }
    }
}

@Composable
private fun BottomLine(report: ReportData) {
    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            Summary(
                Modifier.weight(1f),
                "Net revenue",
                report.netRevenueMinor.asRupees(),
                "what's really yours before any costs",
                Brand.Foreground,
            )
            Summary(
                Modifier.weight(1f),
                "Cost of goods + running costs",
                report.totalCostsMinor.asRupees(),
                if (report.depreciationMinor > 0) {
                    "what you sold cost, what it took to run the place, and equipment wear"
                } else {
                    "what you sold cost, plus what it took to run the place"
                },
                Brand.Foreground,
            )
            Summary(
                Modifier.weight(1f),
                "Net profit",
                report.netProfitMinor.asRupees(),
                "your real bottom line",
                if (report.netProfitMinor >= 0) Brand.Good else Brand.Danger,
            )
        }
    }
}

@Composable
private fun Summary(
    modifier: Modifier,
    label: String,
    value: String,
    note: String,
    valueColor: Color,
) {
    Column(
        modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(Locale.ENGLISH),
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = valueColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------- small parts

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .border(BorderStroke(1.dp, Brand.Border), Radius.shapeLg)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = Brand.Foreground,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * One P&L line. A negative value is a subtraction, shown in red as a positive
 * figure under a "Less: …" label — the same shape as the printed statement the
 * accountant already reads, rather than a minus sign that is easy to miss.
 */
@Composable
private fun MoneyRow(
    label: String,
    valueMinor: Long,
    sub: String? = null,
    bold: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = if (bold) MaterialTheme.typography.bodyLarge
                else MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = Brand.Foreground,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            abs(valueMinor).asRupees(),
            style = if (bold) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (valueMinor < 0) Brand.Danger else Brand.Foreground,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Brand.Border)
    )
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(Radius.shapeMd)
            .background(if (enabled) Brand.SurfaceRaised else Brand.Surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) Brand.Gold else Brand.Border,
        )
    }
}

private fun percent(numerator: Long, denominator: Long): String =
    if (denominator == 0L) "0.0%"
    else String.format(Locale.ENGLISH, "%.1f%%", numerator * 100.0 / denominator)

/** "2026-08-12" -> "12 Aug 2026", leaving anything unexpected untouched. */
private fun prettyDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DAY_FORMAT) }.getOrDefault(iso)
