package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.ui.components.liquidCalendarColors
import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

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
import androidx.compose.foundation.layout.heightIn
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
import cloud.dcompany.erp.ui.components.LiquidDatePickerDialog as DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.core.auth.ReportsAccess
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.LiquidDropdownMenu
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.liquidWorkspaceSurface
import cloud.dcompany.erp.ui.components.workspaceContentCanvas
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Operational tabs share one admitted Overview snapshot. Current ingredient
 * valuation and finance-only P&L retain independent authority and load attempts.
 */
@Composable
fun ReportsScreen(
    access: ReportsAccess,
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    val vm: ReportsViewModel = viewModel()
    val rawState by vm.state.collectAsStateWithLifecycle()
    val activeLease by vm.activeLease.collectAsStateWithLifecycle()
    val state = visibleReportsState(rawState, activeLease, access)
    LaunchedEffect(access) { vm.updateAccess(access) }
    val context = LocalContext.current
    val launchDocumentPicker = rememberReportsCsvDocumentPicker { requestId, uri ->
        vm.saveOverviewExport(requestId, uri, access)
    }
    DisposableEffect(vm, context) {
        onDispose {
            if (context.reportsActivity()?.isChangingConfigurations != true) vm.cancelOverviewExport()
        }
    }

    ReportsWorkspaceContent(
        state = state,
        presentation = presentation,
        onSurface = vm::selectSurface,
        onOperationalTab = vm::selectOperationalTab,
        onPeriod = vm::selectPeriod,
        onStep = vm::step,
        onPickDate = vm::setDate,
        onPickMonth = vm::setMonth,
        onPickQuarter = vm::setQuarter,
        onJumpToCurrent = vm::jumpToCurrent,
        onRetry = vm::retry,
        onExport = {
            vm.beginOverviewExport(access)?.let { request ->
                try {
                    launchDocumentPicker(request)
                } catch (_: Exception) {
                    vm.cancelOverviewExport("The document picker could not be opened. Try again.")
                }
            }
        },
    )
}

@Composable
internal fun ReportsWorkspaceContent(
    state: ReportsUiState,
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
    onSurface: (ReportsSurface) -> Unit,
    onPeriod: (ReportPeriod) -> Unit,
    onStep: (Int) -> Unit,
    onPickDate: (LocalDate) -> Unit,
    onPickMonth: (YearMonth) -> Unit,
    onPickQuarter: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit = {},
    onOperationalTab: (ReportsOperationalTab) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .workspaceContentCanvas()
    ) {
        ReportsHeader(
            state = state,
            onSurface = onSurface,
            onOperationalTab = onOperationalTab,
            onPeriod = onPeriod,
            onStep = onStep,
            onPickDate = onPickDate,
            onPickMonth = onPickMonth,
            onPickQuarter = onPickQuarter,
            onJumpToCurrent = onJumpToCurrent,
            onExport = onExport,
        )
        state.overviewExportMessage?.let { message ->
            OperationalBanner(
                title = if (state.overviewExportFailed) "CSV export unavailable" else "Overview CSV",
                detail = message,
                tone = if (state.overviewExportFailed) UiTone.Warning else UiTone.Information,
                icon = Icons.Filled.Assessment,
                modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.xs)
                    .testTag("reports-overview-export-status"),
            )
        }
        when (state.surface) {
            ReportsSurface.OVERVIEW -> key(state.operationalTab) { ReportsOperationalContent(state, onRetry) }
            ReportsSurface.PNL -> {
                CostingCoverageBanner(state)
                Box(Modifier.fillMaxSize()) {
                    val report = state.report
                    when (reportPresentation(state)) {
                        ReportPresentation.INITIAL_LOADING -> LoadingPanel()
                        ReportPresentation.BLOCKING_ERROR -> ErrorPanel(state.error!!, onRetry)
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
                            StaleReportBanner(state.error!!, onRetry)
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
    }
}

internal fun reportsSelectionControlsVisible(state: ReportsUiState): Boolean =
    state.surface == ReportsSurface.PNL ||
        (state.operationalTab != ReportsOperationalTab.INVENTORY && state.overviewTimezone != null)

@Composable
private fun ReportsHeader(
    state: ReportsUiState,
    onOperationalTab: (ReportsOperationalTab) -> Unit,
    onSurface: (ReportsSurface) -> Unit,
    onPeriod: (ReportPeriod) -> Unit,
    onStep: (Int) -> Unit,
    onPickDate: (LocalDate) -> Unit,
    onPickMonth: (YearMonth) -> Unit,
    onPickQuarter: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
    onExport: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        val controlsVisible = reportsSelectionControlsVisible(state)
        Column(
            Modifier.fillMaxWidth().padding(
                horizontal = Spacing.lgPlus,
                vertical = if (wide) Spacing.xs else Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReportsHeading(state, onExport, Modifier.weight(1f))
                    if (controlsVisible) {
                        PeriodSelector(
                            state = state,
                            onStep = onStep,
                            onPickDate = onPickDate,
                            onPickMonth = onPickMonth,
                            onPickQuarter = onPickQuarter,
                            onJumpToCurrent = onJumpToCurrent,
                            modifier = Modifier.widthIn(min = 300.dp, max = 420.dp),
                            denseWide = true,
                        )
                    }
                }
            } else {
                ReportsHeading(state, onExport, Modifier.fillMaxWidth())
                if (controlsVisible) {
                    PeriodSelector(
                        state = state,
                        onStep = onStep,
                        onPickDate = onPickDate,
                        onPickMonth = onPickMonth,
                        onPickQuarter = onPickQuarter,
                        onJumpToCurrent = onJumpToCurrent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReportsSurfaceTabs(
                    state = state,
                    onSelect = onSurface,
                    onOperationalTab = onOperationalTab,
                    modifier = Modifier.weight(1f),
                )
                if (controlsVisible) {
                    PeriodMenu(
                        selected = state.period,
                        onSelect = onPeriod,
                        modifier = Modifier.widthIn(min = 152.dp, max = 220.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportsHeading(state: ReportsUiState, onExport: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Reports",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("reports-title"),
            )
            Text(
                "Analyze your lounge performance" +
                    if (state.overviewLoading || state.loading) " · refreshing…" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("reports-subtitle"),
            )
        }
        if (state.surface == ReportsSurface.OVERVIEW && state.operationalTab != ReportsOperationalTab.INVENTORY && state.access.canReadOverview && state.access.canExportOverview) {
            ReportsOverviewExportButton(
                enabled = canExportReportsOverview(state, state.lease),
                busy = state.overviewExportBusy,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun ReportsSurfaceTabs(
    state: ReportsUiState,
    onSelect: (ReportsSurface) -> Unit,
    onOperationalTab: (ReportsOperationalTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = buildList {
        availableReportsOperationalTabs(state).forEach { add(TabOption(it.name, it.label)) }
        if (state.access.canReadPnl) add(TabOption(ReportsSurface.PNL.name, "P&L"))
    }
    if (options.size > 1) {
        PremiumTabBar(
            options = options,
            selectedId = if (state.surface == ReportsSurface.PNL) ReportsSurface.PNL.name else state.operationalTab.name,
            onSelect = { id ->
                if (id == ReportsSurface.PNL.name) onSelect(ReportsSurface.PNL)
                else ReportsOperationalTab.entries.firstOrNull { it.name == id }?.let(onOperationalTab)
            },
            modifier = modifier,
        )
    } else {
        Spacer(modifier)
    }
}

@Composable
private fun PeriodMenu(
    selected: ReportPeriod,
    onSelect: (ReportPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reports-period-menu"),
        ) {
            Text(
                selected.tab,
                Modifier.weight(1f).testTag("reports-period-menu-label"),
                textAlign = TextAlign.Start,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose report period")
        }
        LiquidDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("reports-period-popup"),
            role = WorkspaceGlassRole.CONTROL,
        ) {
            ReportPeriod.entries.forEach { period ->
                DropdownMenuItem(
                    text = { Text(period.tab) },
                    onClick = {
                        expanded = false
                        onSelect(period)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reports-period-option-${period.name}"),
                )
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

// ---------------------------------------------------------------- selectors

@Composable
private fun PeriodSelector(
    state: ReportsUiState,
    onStep: (Int) -> Unit,
    onPickDate: (LocalDate) -> Unit,
    onPickMonth: (YearMonth) -> Unit,
    onPickQuarter: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
    modifier: Modifier = Modifier,
    denseWide: Boolean = false,
) {
    var showCalendar by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clip(Radius.shapeMd)
                .liquidWorkspaceSurface(Radius.shapeMd, WorkspaceGlassRole.UTILITY)
                .padding(horizontal = Spacing.xs, vertical = if (denseWide) 0.dp else Spacing.xs)
                .testTag("reports-period-selector"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            StepButton("‹", "reports-period-previous", enabled = true) { onStep(-1) }

            // Daily and Monthly labels remain direct calendar entry points;
            // stepping keeps the common adjacent-period workflow one tap away.
            val labelModifier = when (state.period) {
                ReportPeriod.DAILY, ReportPeriod.MONTHLY -> Modifier.clickable { showCalendar = true }
                else -> Modifier
            }
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).then(labelModifier)
                    .testTag("reports-period-label-action"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    selectorLabel(state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("reports-period-label"),
                )
            }

            StepButton("›", "reports-period-next", enabled = state.canStepForward) { onStep(1) }

            if (!isCurrentPeriod(state)) {
                OutlinedButton(
                    onClick = onJumpToCurrent,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("reports-period-current"),
                ) { Text(currentWord(state.period)) }
            }
        }

        if (state.period == ReportPeriod.QUARTERLY) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth < 620.dp) 2 else 4
                val entries = QUARTER_LABELS.withIndex().toList()
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    entries.chunked(perRow).forEach { quarterRow ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            quarterRow.forEach { (index, label) ->
                                val quarter = index + 1
                                FilterChip(
                                    selected = state.quarter == quarter,
                                    enabled = canSelectFiscalQuarter(state.fiscalYear, quarter),
                                    onClick = { onPickQuarter(quarter) },
                                    label = { Text(label, textAlign = TextAlign.Center) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                        .testTag("reports-quarter-$quarter"),
                                )
                            }
                            repeat(perRow - quarterRow.size) { Spacer(Modifier.weight(1f)) }
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
        colors = liquidCalendarColors(),
    ) {
        DatePicker(
            state = pickerState,
            colors = liquidCalendarColors(),
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
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.md))
            Text("Computing report…", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.operationalDanger), Radius.shapeMd)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Saved report — refresh failed", color = MaterialTheme.colorScheme.operationalDanger, fontWeight = FontWeight.Bold)
            Text(
                "$message The figures below may be out of date.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(10.dp))
            Text(report.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (refreshing) {
                Spacer(Modifier.size(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            "${prettyDate(report.periodStart)} to ${prettyDate(report.periodEnd)} · FY ${report.fiscalYear}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A cached figure must never pass for a fresh one — this is the one
        // line standing between "the tablet was offline" and an owner making
        // a call on numbers that are actually a day old.
        if (fetchedAtMillis != null) {
            Text(
                "As of ${relativeAge(fetchedAtMillis)}" + if (refreshing) " · refreshing…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun relativeAge(fetchedAtMillis: Long): String {
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
        KpiTile("Revenue", report.netRevenueMinor.asRupees(), tag = "reports-pnl-kpi-revenue"),
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

private data class KpiTile(
    val label: String,
    val value: String,
    val negative: Boolean = false,
    val tag: String? = null,
)

@Composable
private fun KpiCard(tile: KpiTile, modifier: Modifier = Modifier) {
    val taggedModifier = tile.tag?.let { modifier.testTag(it) } ?: modifier
    SectionCard(taggedModifier) {
        Text(
            tile.label.uppercase(Locale.ENGLISH),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tile.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (tile.negative) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.operationalWarning), Radius.shapeLg)
            .padding(16.dp),
    ) {
        Text(
            "${amountMinor.asRupees()} is unitemized manual collection.",
            color = MaterialTheme.colorScheme.onSurface,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        if (report.tenderReclassifications.isNotEmpty()) {
            Divider()
            Text("Separate owner-attested daily evidence",style=MaterialTheme.typography.titleSmall)
            Text("These unallocated records do not add a payment, refund or sale to the totals above. Original bills and drawer counts remain unchanged.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            report.tenderReclassifications.forEach { row ->
                Text("${row.businessDate} · ${row.businessTimezone}",style=MaterialTheme.typography.labelMedium)
                if (row.kind == "applied") {
                    MoneyRow("Unallocated Cash classification",row.cashDeltaMinor)
                    MoneyRow("Unallocated UPI classification",row.upiDeltaMinor)
                    Text("Applied as an unallocated Cash to UPI reclassification; no source bill is claimed.",style=MaterialTheme.typography.bodySmall)
                } else if (row.kind == "unresolved") {
                    MoneyRow("Unresolved Cash + UPI difference",row.totalDifferenceMinor)
                    Text("Recorded actuals for review only · no financial posting.",style=MaterialTheme.typography.bodySmall)
                } else {
                    Text("This saved evidence type is not recognised. Refresh and review its protected receipt; posting status is unknown.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    MaterialTheme.colorScheme.onSurface,
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
                    MaterialTheme.colorScheme.onSurface,
                )
            },
            { modifier ->
                Summary(
                    modifier,
                    "Net profit",
                    report.netProfitMinor.asRupees(),
                    "your real bottom line",
                    if (report.netProfitMinor < 0) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.onSurface,
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
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            abs(valueMinor).asRupees(),
            style = if (bold) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (valueMinor < 0) MaterialTheme.colorScheme.operationalDanger else MaterialTheme.colorScheme.onSurface,
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
            .background(MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun StepButton(glyph: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .liquidWorkspaceSurface(Radius.shapeMd, WorkspaceGlassRole.CONTROL)
            .then(
                if (enabled) Modifier else Modifier.background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    Radius.shapeMd,
                ),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

private fun percent(numerator: Long, denominator: Long): String =
    if (denominator == 0L) "0.0%"
    else String.format(Locale.ENGLISH, "%.1f%%", numerator * 100.0 / denominator)

/** "2026-08-12" -> "12 Aug 2026", leaving anything unexpected untouched. */
private fun prettyDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DAY_FORMAT) }.getOrDefault(iso)
