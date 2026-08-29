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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
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
fun ReportsScreen(
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    val vm: ReportsViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Brand.Background)
    ) {
        PeriodTabs(state.period, vm::selectPeriod)
        PeriodSelector(
            state = state,
            onStep = vm::step,
            onPickDate = vm::setDate,
            onPickMonth = vm::setMonth,
            onPickQuarter = vm::setQuarter,
            onJumpToCurrent = vm::jumpToCurrent,
        )
        CostingCoverageBanner(state)

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
                    presentation,
                )
                ReportPresentation.FRESH_CONTENT -> ReportBody(
                    report!!,
                    state.period,
                    state.loading,
                    state.fetchedAtMillis,
                    presentation,
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
                                presentation,
                            )
                        } else {
                            ReportBody(
                                report,
                                state.period,
                                state.loading,
                                state.fetchedAtMillis,
                                presentation,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CostingCoverageBanner(state: ReportsUiState) {
    val coverage = state.costingCoverage
    val error = state.costingError
    when {
        coverage != null && !coverage.isComplete -> OperationalBanner(
            title = coverage.warningTitle,
            detail = coverage.warningDetail,
            tone = UiTone.Warning,
            icon = Icons.Default.WarningAmber,
            modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.xs),
        )
        error != null -> OperationalBanner(
            title = "Costing status unavailable",
            detail = error,
            tone = UiTone.Warning,
            icon = Icons.Default.WarningAmber,
            modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.xs),
        )
        coverage == null && state.report != null -> OperationalBanner(
            title = "Checking inventory costing",
            detail = "Profit figures remain provisional until recipe and ingredient-cost coverage is verified.",
            tone = UiTone.Information,
            icon = Icons.Default.Assessment,
            modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun PeriodTabs(selected: ReportPeriod, onSelect: (ReportPeriod) -> Unit) {
    PremiumTabBar(
        options = ReportPeriod.entries.map { TabOption(it.name, it.tab) },
        selectedId = selected.name,
        onSelect = { id -> ReportPeriod.entries.firstOrNull { it.name == id }?.let(onSelect) },
        modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    )
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 620.dp
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                color = Brand.ForegroundMuted,
                            )
                        }
                    }

                    StepButton("›", enabled = state.canStepForward) { onStep(1) }

                    if (!compact && !isCurrentPeriod(state)) {
                        OutlinedButton(onClick = onJumpToCurrent) { Text(currentWord(state.period)) }
                    }
                }

                if (compact && !isCurrentPeriod(state)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = onJumpToCurrent) { Text(currentWord(state.period)) }
                    }
                }

                if (state.period == ReportPeriod.QUARTERLY) {
                    val entries = QUARTER_LABELS.withIndex().toList()
                    val perRow = if (compact) 2 else 4
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.chunked(perRow).forEach { quarterRow ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                quarterRow.forEach { (index, label) ->
                                    val quarter = index + 1
                                    FilterChip(
                                        selected = state.quarter == quarter,
                                        enabled = canSelectFiscalQuarter(state.fiscalYear, quarter),
                                        onClick = { onPickQuarter(quarter) },
                                        label = { Text(label, textAlign = TextAlign.Center) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Brand.SurfaceHover,
                                            selectedLabelColor = Brand.Foreground,
                                            labelColor = Brand.ForegroundMuted,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(perRow - quarterRow.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
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
    SectionCard(
        title = "Report result",
        subtitle = "Computing operational totals from saved orders and journals",
        icon = Icons.Filled.Assessment,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Brand.Gold)
            Spacer(Modifier.height(Spacing.md))
            Text("Computing report…", color = Brand.ForegroundMuted)
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    SectionCard(
        title = "Report result",
        subtitle = "The selected period remains unchanged while you retry",
        icon = Icons.Filled.Assessment,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    ) {
        DesignedEmptyState(
            title = "Could not load this report",
            body = message,
            icon = Icons.Filled.Assessment,
            primaryLabel = "Try again",
            onPrimary = onRetry,
        )
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
private fun EmptyPanel(
    period: ReportPeriod,
    label: String,
    refreshing: Boolean,
    fetchedAtMillis: Long?,
    presentation: WorkspacePresentationPolicy,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val cards: List<@Composable (Modifier) -> Unit> = listOf(
            { modifier ->
                CompactStatCard(
                    "Revenue", "₹0.00", modifier, "No recorded revenue",
                    Icons.AutoMirrored.Filled.TrendingUp, UiTone.Neutral,
                )
            },
            { modifier ->
                CompactStatCard(
                    "Orders", "0", modifier, "No completed receipts",
                    Icons.AutoMirrored.Filled.ReceiptLong, UiTone.Neutral,
                )
            },
            { modifier ->
                CompactStatCard(
                    if (presentation.showsEvents) "Average ticket" else "Average paid bill",
                    "₹0.00", modifier, "No completed orders",
                    Icons.Filled.Payments, UiTone.Neutral,
                )
            },
            { modifier ->
                CompactStatCard(
                    "Net profit", "₹0.00", modifier, "No activity",
                    Icons.Filled.Assessment, UiTone.Neutral,
                )
            },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 720.dp) 4 else 2
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                cards.chunked(columns).forEach { rowCards ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        rowCards.forEach { card -> card(Modifier.weight(1f)) }
                        repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        SecondaryMetricGrid(
            buildList {
                if (presentation.showsEvents) {
                    add(
                        ReportSecondaryMetric(
                            label = "Tickets",
                            value = "0",
                            detail = "No event tickets sold",
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            tone = UiTone.Neutral,
                        ),
                    )
                }
                addAll(
                    listOf(
                ReportSecondaryMetric(
                    label = "Profit margin",
                    value = "0.0%",
                    detail = "No revenue in this period",
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    tone = UiTone.Neutral,
                ),
                ReportSecondaryMetric(
                    label = "Cost ratio",
                    value = "0.0%",
                    detail = "No costs in this period",
                    icon = Icons.Filled.Assessment,
                    tone = UiTone.Neutral,
                ),
                    ),
                )
            },
        )
        SectionCard(
            title = "Report result",
            subtitle = fetchedAtMillis?.let {
                "As of ${relativeAge(it)}" + if (refreshing) " · refreshing…" else ""
            } ?: "No cached result timestamp",
            icon = Icons.Filled.Assessment,
        ) {
            DesignedEmptyState(
                title = "Nothing recorded in this period",
                body = (if (presentation.showsEvents) {
                    "No sales, tickets, payments or expenses fall inside "
                } else {
                    "No sales, payments or expenses fall inside "
                }) +
                    label.ifBlank { "this period" } + ". Use the controls above to inspect another ${unitWord(period)}.",
                icon = Icons.Filled.Assessment,
                modifier = Modifier.height(180.dp),
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
    presentation: WorkspacePresentationPolicy,
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

            if (report.unissuedPaidOrdersCount > 0) {
                OperationalBanner(
                    title = "Invoice reconciliation required",
                    detail = "${report.unissuedPaidOrdersCount} settled order(s) in this period " +
                        "have no immutable invoice timestamp. They are included using their " +
                        "recorded close time until an owner reconciles them.",
                    tone = UiTone.Warning,
                    icon = Icons.Filled.WarningAmber,
                )
            }

            KpiGrid(report, columns, presentation)
            SecondaryMetrics(report, presentation)

            if (report.manualCollectionsMinor > 0) {
                ManualCollectionsNotice(report.manualCollectionsMinor, presentation)
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RevenueCard(report, presentation)
                        PaymentsCard(report, presentation)
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (presentation.showsRestaurantOperations || report.taxCollected.totalMinor != 0L) {
                            TaxCard(report, presentation)
                        }
                        ExpensesCard(report)
                    }
                }
            } else {
                RevenueCard(report, presentation)
                if (presentation.showsRestaurantOperations || report.taxCollected.totalMinor != 0L) {
                    TaxCard(report, presentation)
                }
                PaymentsCard(report, presentation)
                ExpensesCard(report)
            }

            BottomLine(report)

            Text(
                if (presentation.showsMemberships || presentation.showsRestaurantOperations) {
                    "Computed from paid orders, payment and refund evidence, manual collections, " +
                        "memberships, expenses, depreciation and the FIFO inventory subledger. " +
                        "Collected GST is shown for accountant review — input tax credit is not applied."
                } else {
                    "Computed from paid orders, gaming and product receipts, payment and refund " +
                        "evidence, manual collections, legacy/other receipts, expenses, depreciation " +
                        "and the FIFO inventory subledger. Historical hidden-module amounts remain " +
                        "grouped under legacy/other for owner reconciliation."
                },
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
                color = Brand.ForegroundMuted,
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
private fun KpiGrid(
    report: ReportData,
    columns: Int,
    presentation: WorkspacePresentationPolicy,
) {
    val tiles = listOf(
        KpiTile("Revenue", report.netRevenueMinor.asRupees()),
        KpiTile("Orders", report.ordersCount.toString()),
        KpiTile(
            if (presentation.showsEvents) "Average ticket" else "Average paid bill",
            report.avgTicketMinor.asRupees(),
        ),
        KpiTile("Net profit", report.netProfitMinor.asRupees(), negative = report.netProfitMinor < 0),
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

private data class KpiTile(val label: String, val value: String, val negative: Boolean = false)

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
            color = if (tile.negative) Brand.Danger else Brand.Foreground,
        )
    }
}

internal data class ReportSecondaryMetric(
    val label: String,
    val value: String,
    val detail: String,
    val icon: ImageVector,
    val tone: UiTone,
)

/**
 * Secondary report context that was present before the visual refinement.
 *
 * Keeping this mapping outside the composable makes the accounting labels and
 * ratios directly testable. All values come from the report response; this
 * layer only formats them for display.
 */
internal fun reportSecondaryMetrics(
    report: ReportData,
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.FullHospitality.presentationPolicy(),
): List<ReportSecondaryMetric> = buildList {
    if (presentation.showsEvents) {
        add(
            ReportSecondaryMetric(
                label = "Tickets",
                value = report.ticketsCount.toString(),
                detail = "Event tickets sold",
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                tone = UiTone.Neutral,
            ),
        )
    }
    add(ReportSecondaryMetric(
        label = "Profit margin",
        value = percent(report.netProfitMinor, report.netRevenueMinor),
        detail = "Net profit ÷ net revenue",
        icon = Icons.AutoMirrored.Filled.TrendingUp,
        tone = when {
            report.netProfitMinor > 0 -> UiTone.Success
            report.netProfitMinor < 0 -> UiTone.Danger
            else -> UiTone.Neutral
        },
    ))
    add(ReportSecondaryMetric(
        label = "Cost ratio",
        value = percent(report.totalCostsMinor, report.netRevenueMinor),
        detail = "Total costs ÷ net revenue",
        icon = Icons.Filled.Assessment,
        tone = when {
            report.netRevenueMinor <= 0 -> UiTone.Neutral
            report.totalCostsMinor > report.netRevenueMinor -> UiTone.Danger
            else -> UiTone.Success
        },
    ))
}

@Composable
private fun SecondaryMetrics(
    report: ReportData,
    presentation: WorkspacePresentationPolicy,
) {
    SecondaryMetricGrid(reportSecondaryMetrics(report, presentation))
}

@Composable
private fun SecondaryMetricGrid(metrics: List<ReportSecondaryMetric>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 720.dp -> 3
            maxWidth >= 440.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            metrics.chunked(columns).forEach { rowMetrics ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    rowMetrics.forEach { metric ->
                        CompactStatCard(
                            label = metric.label,
                            value = metric.value,
                            detail = metric.detail,
                            icon = metric.icon,
                            tone = metric.tone,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ManualCollectionsNotice(
    amountMinor: Long,
    presentation: WorkspacePresentationPolicy,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(Brand.SurfaceRaised)
            .border(BorderStroke(1.dp, Brand.Warning), Radius.shapeLg)
            .padding(16.dp),
    ) {
        Text(
            "${amountMinor.asRupees()} is unitemized manual collection.",
            color = Brand.Foreground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "It is counted once in revenue and once in payment movement, but it has no POS " +
                if (presentation.showsRestaurantOperations) {
                    "order, tax invoice, item mix or automatic cost of goods. The source "
                } else {
                    "order, itemized receipt, item mix or automatic cost of goods. The source "
                } +
                "references and void history live in Finance → Manual collections.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun RevenueCard(
    report: ReportData,
    presentation: WorkspacePresentationPolicy,
) {
    val r = report.revenue
    SectionCard {
        CardTitle("Revenue")
        r.presentedSources(presentation).forEach { source ->
            MoneyRow(source.label, source.amountMinor, sub = source.detail)
        }
        if (r.hiddenLegacySourceMinor(presentation) > 0L) {
            Text(
                "Legacy/other revenue remains included in every total. Review source invoices " +
                    "and audit history before posting any correction.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        if (r.discountsAndPointsRedeemedMinor > 0) {
            MoneyRow(
                if (presentation.showsCustomers || presentation.showsMemberships) {
                    "Less: discounts & points redeemed"
                } else {
                    "Less: discounts & customer credits"
                },
                -r.discountsAndPointsRedeemedMinor,
            )
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
                sub = "tips were a staff liability, not business revenue",
            )
        }
        if (presentation.showsRestaurantOperations || report.taxCollected.totalMinor != 0L) {
            MoneyRow(
                if (presentation.showsRestaurantOperations) {
                    "Less: GST collected"
                } else {
                    "Less: recorded indirect tax"
                },
                -report.taxCollected.totalMinor,
                sub = "owed to the government, never was your money",
            )
        }
        Divider()
        MoneyRow(
            if (presentation.showsRestaurantOperations) {
                "Net revenue (after GST)"
            } else if (report.taxCollected.totalMinor != 0L) {
                "Net revenue (after recorded tax)"
            } else {
                "Net revenue"
            },
            report.netRevenueMinor,
            sub = "what's really yours before any costs",
            bold = true,
        )
        MoneyRow(
            "Less: cost of goods sold",
            -report.cogsMinor,
            sub = "what the products and services you sold actually cost you",
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
private fun TaxCard(
    report: ReportData,
    presentation: WorkspacePresentationPolicy,
) {
    val t = report.taxCollected
    SectionCard {
        CardTitle(if (presentation.showsRestaurantOperations) "GST collected" else "Legacy tax collected")
        MoneyRow(if (presentation.showsRestaurantOperations) "CGST" else "Central component", t.cgstMinor)
        MoneyRow(if (presentation.showsRestaurantOperations) "SGST" else "State component", t.sgstMinor)
        if (t.igstMinor > 0) {
            MoneyRow(
                if (presentation.showsRestaurantOperations) "IGST (inter-state)" else "Inter-state component",
                t.igstMinor,
            )
        }
        if (t.cessMinor > 0) MoneyRow("Cess", t.cessMinor)
        Divider()
        MoneyRow(if (presentation.showsRestaurantOperations) "Total GST" else "Total legacy tax", t.totalMinor, bold = true)
        Spacer(Modifier.height(8.dp))
        Text(
            if (presentation.showsRestaurantOperations) {
                "Collected GST for accountant review and return preparation. " +
                    "Input tax credit is not applied by this report."
            } else {
                "Historical tax remains visible for owner reconciliation. Filing and return " +
                    "preparation are deferred and are not provided by this Gaming Centre profile."
            },
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun PaymentsCard(
    report: ReportData,
    presentation: WorkspacePresentationPolicy,
) {
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
                    presentation.settledPrepaidReversalDetail(it.asRupees())
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
        val summaries: List<@Composable (Modifier) -> Unit> = listOf(
            { modifier ->
                Summary(
                    modifier,
                    "Net revenue",
                    report.netRevenueMinor.asRupees(),
                    "what's really yours before any costs",
                    Brand.Foreground,
                )
            },
            { modifier ->
                Summary(
                    modifier,
                    "Cost of goods + running costs",
                    report.totalCostsMinor.asRupees(),
                    if (report.depreciationMinor > 0) {
                        "what you sold cost, what it took to run the place, and equipment wear"
                    } else {
                        "what you sold cost, plus what it took to run the place"
                    },
                    Brand.Foreground,
                )
            },
            { modifier ->
                Summary(
                    modifier,
                    "Net profit",
                    report.netProfitMinor.asRupees(),
                    "your real bottom line",
                    if (report.netProfitMinor < 0) Brand.Danger else Brand.Foreground,
                )
            },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxWidth()) {
                    summaries.forEach { summary -> summary(Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    summaries.forEach { summary -> summary(Modifier.fillMaxWidth()) }
                }
            }
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
            color = if (enabled) Brand.Foreground else Brand.Border,
        )
    }
}

private fun percent(numerator: Long, denominator: Long): String =
    if (denominator == 0L) "0.0%"
    else String.format(Locale.ENGLISH, "%.1f%%", numerator * 100.0 / denominator)

/** "2026-08-12" -> "12 Aug 2026", leaving anything unexpected untouched. */
private fun prettyDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DAY_FORMAT) }.getOrDefault(iso)
