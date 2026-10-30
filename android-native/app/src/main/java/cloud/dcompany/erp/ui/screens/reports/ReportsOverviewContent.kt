package cloud.dcompany.erp.ui.screens.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.NumericValue
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.liquidWorkspaceSurface
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.WorkspacePanelShape
import cloud.dcompany.erp.ui.theme.workspacePresentationAccent
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalInformation
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun ReportsOverviewContent(
    state: ReportsUiState,
    onRetry: () -> Unit,
) {
    val overview = state.overview
    when {
        overview == null && state.overviewLoading -> OverviewLoading()
        overview == null -> OverviewUnavailable(state.overviewError, onRetry)
        else -> Column(Modifier.fillMaxSize()) {
            if (state.overviewError != null) {
                OperationalBanner(
                    title = if (state.overviewDenied) "Overview access unavailable" else "Saved Overview",
                    detail = state.overviewError,
                    tone = if (state.overviewDenied) UiTone.Danger else UiTone.Warning,
                    icon = Icons.Filled.Assessment,
                    modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.xs),
                )
            }
            Box(Modifier.weight(1f)) {
                OverviewBody(
                    overview = overview,
                    fetchedAtMillis = state.overviewFetchedAtMillis,
                )
            }
        }
    }
}

@Composable
private fun OverviewLoading() {
    SectionCard(
        title = "Reports Overview",
        subtitle = "Loading the selected branch report",
        icon = Icons.Filled.Assessment,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = workspacePresentationAccent())
        }
    }
}

@Composable
private fun OverviewUnavailable(message: String?, onRetry: () -> Unit) {
    SectionCard(
        title = "Reports Overview",
        subtitle = "No figures are shown until this account and branch are verified",
        icon = Icons.Filled.Assessment,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    ) {
        DesignedEmptyState(
            title = "Overview unavailable",
            body = message ?: "Select an authorized branch and connect to load Reports Overview.",
            icon = Icons.Filled.Assessment,
            primaryLabel = "Try again",
            onPrimary = onRetry,
        )
    }
}

@Composable
private fun OverviewBody(
    overview: ValidatedReportsOverview,
    fetchedAtMillis: Long?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp && LocalDensity.current.fontScale <= 1.15f
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = Spacing.lgPlus,
                    vertical = if (wide) Spacing.xs else Spacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(
                if (wide) Spacing.xs else Spacing.md,
            ),
        ) {
            OverviewKpis(overview, wide)
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    HourProfilePanel(
                        overview.localHourProfile,
                        Modifier.weight(1.65f).fillMaxHeight(),
                        denseWide = true,
                    )
                    SourcePanel(overview.sources, Modifier.weight(1f).fillMaxHeight(), horizontalLegend = true)
                }
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("reports-overview-bottom-row"),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    TopProductsPanel(overview.topProducts, Modifier.weight(1.2f).fillMaxHeight(), wide = true)
                    PaymentPanel(overview.payments, Modifier.weight(1f).fillMaxHeight(), wide = true)
                    DeviceUsagePanel(overview.deviceUsage, Modifier.weight(1.1f).fillMaxHeight(), wide = true)
                }
            } else {
                HourProfilePanel(overview.localHourProfile, Modifier.fillMaxWidth())
                SourcePanel(overview.sources, Modifier.fillMaxWidth(), horizontalLegend = false)
                TopProductsPanel(overview.topProducts, Modifier.fillMaxWidth(), wide = false)
                PaymentPanel(overview.payments, Modifier.fillMaxWidth(), wide = false)
                DeviceUsagePanel(overview.deviceUsage, Modifier.fillMaxWidth(), wide = false)
            }
            Text(
                buildString {
                    append("Updated ")
                    append(overviewSnapshotLabel(overview))
                    fetchedAtMillis?.let { append(" · saved ${relativeAge(it)}") }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("reports-overview-snapshot"),
            )
        }
    }
}

@Composable
private fun OverviewKpis(overview: ValidatedReportsOverview, wide: Boolean) {
    val revenueAccent = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        Color(0xFF5D3F9E)
    } else {
        Color(0xFFC8B5FF)
    }
    val cards: List<@Composable (Modifier) -> Unit> = listOf(
        { modifier ->
            ReportsOverviewKpi(
                tag = "reports-overview-kpi-revenue",
                title = "Gross revenue",
                value = overview.grossRevenueMinor.asReportsRupees(),
                detail = "All included sales",
                icon = Icons.Filled.Payments,
                accent = revenueAccent,
                modifier = modifier,
            )
        },
        { modifier ->
            ReportsOverviewKpi(
                tag = "reports-overview-kpi-orders",
                title = "Orders",
                value = overview.ordersCount.toString(),
                detail = "Paid or refunded receipts",
                icon = Icons.Filled.Assessment,
                accent = MaterialTheme.colorScheme.operationalGood,
                modifier = modifier,
            )
        },
        { modifier ->
            ReportsOverviewKpi(
                tag = "reports-overview-kpi-sessions",
                title = "Sessions started",
                value = overview.sessionsStartedCount.toString(),
                detail = "Non-cancelled starts",
                icon = Icons.Filled.SportsEsports,
                accent = MaterialTheme.colorScheme.operationalInformation,
                modifier = modifier,
            )
        },
        { modifier ->
            ReportsOverviewKpi(
                tag = "reports-overview-kpi-linked-customers",
                title = "Linked customers",
                value = overview.distinctLinkedCustomersCount.toString(),
                detail = "${overview.linkedOrdersCount} linked · ${overview.unlinkedOrdersCount} unlinked",
                icon = Icons.Filled.Groups,
                accent = workspacePresentationAccent(),
                modifier = modifier,
            )
        },
    )
    val columns = if (wide) 4 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        cards.chunked(columns).forEach { rowCards ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                rowCards.forEach { it(Modifier.weight(1f)) }
                repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReportsOverviewKpi(
    tag: String,
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier,
) {
    Row(
        modifier
            .heightIn(min = 96.dp)
            .clip(WorkspacePanelShape)
            .liquidWorkspaceSurface(WorkspacePanelShape, WorkspaceGlassRole.PANEL)
            .testTag(tag)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            Modifier.size(44.dp).clip(Radius.shapeMd).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().testTag("$tag-title"),
            )
            NumericValue(
                value = value,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("$tag-value"),
            )
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().testTag("$tag-detail"),
            )
        }
    }
}

@Composable
internal fun HourProfilePanel(
    profile: ReportsOverviewHourProfile,
    modifier: Modifier,
    denseWide: Boolean = false,
) {
    SectionCard(
        title = "POS sales by local hour",
        subtitle = "Tip-exclusive settled-order profile",
        icon = Icons.Filled.Assessment,
        modifier = modifier.testTag("reports-overview-hour-panel"),
        contentFillsAvailableHeight = denseWide,
        contentPadding = if (denseWide) PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
            else PaddingValues(Spacing.lg),
        headerPadding = if (denseWide) {
            PaddingValues(horizontal = Spacing.md, vertical = 6.dp)
        } else {
            PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md)
        },
    ) {
        when (profile) {
            ReportsOverviewHourProfile.MultiDayUnavailable -> Box(
                Modifier.fillMaxWidth().heightIn(min = 150.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Hourly detail is available for a single day. Choose Daily to inspect the local-hour profile.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Spacing.lg).testTag("reports-overview-hour-unavailable"),
                )
            }
            is ReportsOverviewHourProfile.Available -> {
                val values = profile.points.map(ReportsOverviewHourPoint::revenueMinor)
                val minValue = minOf(0L, values.minOrNull() ?: 0L)
                val maxValue = maxOf(0L, values.maxOrNull() ?: 0L)
                val lineColor = workspacePresentationAccent()
                val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                val peak = reportsPeakHour(profile.points)
                Column(
                    modifier = if (denseWide) Modifier.fillMaxHeight() else Modifier,
                    verticalArrangement = if (denseWide) Arrangement.SpaceBetween
                        else Arrangement.spacedBy(Spacing.sm),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Column(
                            Modifier.height(if (denseWide) 100.dp else 132.dp).padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                maxValue.asReportsRupees(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag("reports-overview-hour-axis-max"),
                            )
                            Text(
                                minValue.asReportsRupees(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag("reports-overview-hour-axis-min"),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Canvas(
                                Modifier.fillMaxWidth().height(if (denseWide) 100.dp else 132.dp)
                                    .testTag("reports-overview-hour-chart"),
                            ) {
                                val plotLeft = 8.dp.toPx()
                                val plotRight = size.width - 8.dp.toPx()
                                val plotTop = 8.dp.toPx()
                                val plotBottom = size.height - 8.dp.toPx()
                                val plotWidth = max(1f, plotRight - plotLeft)
                                val plotHeight = max(1f, plotBottom - plotTop)
                                val span = max(1.0, maxValue.toDouble() - minValue.toDouble())
                                fun y(value: Long): Float =
                                    (plotBottom - ((value.toDouble() - minValue.toDouble()) / span * plotHeight)).toFloat()
                                listOf(0f, 0.5f, 1f).forEach { fraction ->
                                    val gridY = plotTop + plotHeight * fraction
                                    drawLine(gridColor, Offset(plotLeft, gridY), Offset(plotRight, gridY), 1.dp.toPx())
                                }
                                val baseline = y(0L)
                                drawLine(gridColor.copy(alpha = 0.85f), Offset(plotLeft, baseline), Offset(plotRight, baseline), 1.dp.toPx())
                                val line = Path()
                                val area = Path().apply { moveTo(plotLeft, baseline) }
                                profile.points.forEachIndexed { index, point ->
                                    val x = plotLeft + plotWidth * index / 23f
                                    val pointY = y(point.revenueMinor)
                                    if (index == 0) line.moveTo(x, pointY) else line.lineTo(x, pointY)
                                    area.lineTo(x, pointY)
                                }
                                area.lineTo(plotRight, baseline)
                                area.close()
                                drawPath(area, lineColor.copy(alpha = 0.18f))
                                drawPath(line, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                                peak?.let { point ->
                                    val x = plotLeft + plotWidth * point.hour / 23f
                                    val pointY = y(point.revenueMinor)
                                    drawCircle(lineColor.copy(alpha = 0.22f), 7.dp.toPx(), Offset(x, pointY))
                                    drawCircle(lineColor, 3.dp.toPx(), Offset(x, pointY))
                                }
                            }
                            HourAxisLabels()
                        }
                    }
                    val totalText = buildString {
                        append(profile.totalRevenueMinor.asReportsRupees())
                        append(" across ${profile.totalOrdersCount} orders")
                        if (profile.dayLengthMinutes != 1_440) {
                            append(" · ${profile.dayLengthMinutes} minute DST day")
                        }
                    }
                    val peakText = if (peak == null || values.all { it == 0L }) {
                        "No hourly POS sales in this day"
                    } else {
                        "Peak ${peak.hour.toString().padStart(2, '0')}:00 · ${peak.revenueMinor.asReportsRupees()}"
                    }
                    if (denseWide) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                totalText,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).testTag("reports-overview-hour-total"),
                            )
                            Text(
                                peakText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f).testTag("reports-overview-hour-peak"),
                            )
                        }
                    } else {
                        Text(
                            totalText,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("reports-overview-hour-total"),
                        )
                        Text(
                            peakText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("reports-overview-hour-peak"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HourAxisLabels() {
    val hours = listOf(0, 6, 12, 18, 23)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            hours.forEach { hour ->
                val label = hour.toString().padStart(2, '0')
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("reports-overview-hour-axis-$label"),
                )
            }
        },
    ) { measurables, constraints ->
        val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val inset = 8.dp.toPx()
        val plotWidth = max(1f, width - 2f * inset)
        layout(width, labels.maxOfOrNull { it.height } ?: 0) {
            labels.forEachIndexed { index, label ->
                val center = inset + plotWidth * hours[index] / 23f
                val x = (center - label.width / 2f).roundToInt().coerceIn(0, width - label.width)
                // The plotted time axis runs left to right, including in an RTL locale.
                label.place(x, 0)
            }
        }
    }
}

@Composable
internal fun SourcePanel(
    sources: ReportsOverviewSources,
    modifier: Modifier,
    horizontalLegend: Boolean,
) {
    SectionCard(
        title = "Revenue sources",
        subtitle = "Sales included in the chart; adjustments shown below",
        icon = Icons.Filled.Assessment,
        modifier = modifier.testTag("reports-overview-source-panel"),
        contentPadding = if (horizontalLegend) PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
            else PaddingValues(Spacing.lg),
        headerPadding = if (horizontalLegend) {
            PaddingValues(horizontal = Spacing.md, vertical = 6.dp)
        } else {
            PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md)
        },
    ) {
        val slices = sources.positiveSlices
        val colors = listOf(workspacePresentationAccent(), Color(0xFF8ED6C7), Color(0xFF88A8E8), Color(0xFFD99AD5), Color(0xFFE28B70))
        val emptyRingColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val donut: @Composable () -> Unit = {
            Box(Modifier.size(if (horizontalLegend) 104.dp else 132.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    if (sources.positiveSourceDenominatorMinor <= 0L) {
                        drawCircle(emptyRingColor, style = Stroke(14.dp.toPx()))
                    } else {
                        var startAngle = -90f
                        slices.forEachIndexed { index, (_, value) ->
                            val sweep = value.toDouble() / sources.positiveSourceDenominatorMinor.toDouble() * 360.0
                            drawArc(
                                color = colors[index % colors.size],
                                startAngle = startAngle,
                                sweepAngle = sweep.toFloat(),
                                useCenter = false,
                                topLeft = Offset(7.dp.toPx(), 7.dp.toPx()),
                                size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()),
                                style = Stroke(14.dp.toPx(), cap = StrokeCap.Butt),
                            )
                            startAngle += sweep.toFloat()
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        sources.positiveSourceDenominatorMinor.asReportsRupees(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("included", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val legendEntries = slices.ifEmpty { listOf("No positive sales" to 0L) }
        val legend: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(
                    if (horizontalLegend) Spacing.xs else Spacing.sm,
                ),
            ) {
                legendEntries.forEachIndexed { index, (label, value) ->
                    SourceLegendItem(
                        index = index,
                        label = label,
                        value = value,
                        color = colors[index % colors.size],
                        dense = horizontalLegend,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (horizontalLegend) Spacing.xs else Spacing.sm,
            ),
        ) {
            if (horizontalLegend) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    donut()
                    Box(Modifier.weight(1f)) { legend() }
                }
            } else {
                Box(Modifier.align(Alignment.CenterHorizontally)) { donut() }
                legend()
            }
            val denominatorText =
                "Sales included in chart ${sources.positiveSourceDenominatorMinor.asReportsRupees()}"
            val adjustmentsText =
                "Adjustments ${sources.adjustmentsMinor.asReportsRupees()} · gross ${sources.totalMinor.asReportsRupees()}"
            if (horizontalLegend) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        denominatorText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                            .testTag("reports-overview-source-denominator"),
                    )
                    Text(
                        adjustmentsText,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                            .testTag("reports-overview-source-adjustments"),
                    )
                }
            } else {
                Text(
                    denominatorText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("reports-overview-source-denominator"),
                )
                Text(
                    adjustmentsText,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("reports-overview-source-adjustments"),
                )
            }
        }
    }
}

@Composable
private fun SourceLegendItem(
    index: Int,
    label: String,
    value: Long,
    color: Color,
    dense: Boolean,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            label,
            Modifier.weight(1f).testTag("reports-overview-source-label-$index"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            value.asReportsRupees(),
            color = MaterialTheme.colorScheme.onSurface,
            style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("reports-overview-source-value-$index"),
        )
    }
}

@Composable
internal fun PaymentPanel(
    payments: ReportsOverviewPayments,
    modifier: Modifier,
    wide: Boolean,
) {
    SectionCard(
        title = "Payments received",
        icon = if (wide) null else Icons.Filled.Payments,
        modifier = modifier.testTag("reports-overview-payment-panel"),
        contentPadding = if (wide) PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
            else PaddingValues(Spacing.lg),
        headerPadding = if (wide) {
            PaddingValues(horizontal = Spacing.md, vertical = 6.dp)
        } else {
            PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md)
        },
    ) {
        // The bar length is a share of the validated payment total. Normalizing
        // to the largest method would visually invent a different denominator.
        val paymentDenominator = payments.totalMinor.coerceAtLeast(1L)
        val methodColors = listOf(
            workspacePresentationAccent(),
            MaterialTheme.colorScheme.operationalGood,
            MaterialTheme.colorScheme.operationalInformation,
            Color(0xFFB69CFF),
            Color(0xFFE98CB1),
            Color(0xFF74C7C4),
            Color(0xFFE28B70),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(
                if (wide) 0.dp else Spacing.sm,
            ),
        ) {
            val visibleMethods = payments.components.mapIndexed { index, (label, amount) ->
                PaymentMethodPresentation(index, label, amount, methodColors[index % methodColors.size])
            }.filter { it.amount != 0L }.ifEmpty {
                listOf(PaymentMethodPresentation(0, "No collections", 0L, MaterialTheme.colorScheme.onSurfaceVariant))
            }
            PanelCaption("Settlement method collections", "reports-overview-payment-caption", wide)
            if (wide) ReportsTableHeader(
                listOf("Method" to .9f, "Share" to 1.1f, "Amount" to 1f),
                setOf(2), "reports-overview-payment-columns",
            )
            visibleMethods.forEach { method ->
                PaymentMethodRow(method, paymentDenominator, Modifier.fillMaxWidth(), wide)
            }
            Text(
                "Total ${payments.totalMinor.asReportsRupees()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.End).testTag("reports-overview-payment-total"),
            )
        }
    }
}

private data class PaymentMethodPresentation(
    val index: Int,
    val label: String,
    val amount: Long,
    val color: Color,
)

@Composable
private fun PaymentMethodRow(
    method: PaymentMethodPresentation,
    denominator: Long,
    modifier: Modifier,
    wide: Boolean,
) {
    val label: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            method.label,
            textModifier.testTag("reports-overview-payment-label-${method.index}"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    val amount: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            method.amount.asReportsRupees(),
            textModifier.testTag("reports-overview-payment-value-${method.index}"),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
        )
    }
    val bar: @Composable (Modifier) -> Unit = { barModifier ->
        Box(
            barModifier.height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .testTag("reports-overview-payment-track-${method.index}"),
        ) {
            Box(
                Modifier.fillMaxWidth(
                    (method.amount.coerceAtLeast(0L).toDouble() / denominator.toDouble())
                        .toFloat().coerceIn(0f, 1f),
                ).height(8.dp).background(method.color, CircleShape),
            )
        }
    }
    if (wide) {
        // Inline rows recover height for padded headings without compressing text or bars.
        Row(modifier.heightIn(min = reportsTableRowHeight()).testTag("reports-overview-payment-row-${method.index}"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            label(Modifier.weight(0.9f))
            bar(Modifier.weight(1.1f))
            amount(Modifier.weight(1f))
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                label(Modifier.weight(0.8f))
                amount(Modifier.weight(1.2f))
            }
            bar(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun TopProductsPanel(products: List<ReportsOverviewTopProduct>, modifier: Modifier, wide: Boolean) {
    SectionCard(
        title = "Top products",
        icon = if (wide) null else Icons.Filled.Assessment,
        modifier = modifier.testTag("reports-overview-products-panel"),
        contentPadding = if (wide) PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
            else PaddingValues(Spacing.lg),
        headerPadding = if (wide) PaddingValues(horizontal = Spacing.md, vertical = 6.dp)
            else PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (wide) 0.dp else Spacing.xs)) {
            PanelCaption(
                "Gross line sales · before order adjustments and refund deductions",
                "reports-overview-products-caption",
                wide,
            )
            if (wide) ReportsTableHeader(
                listOf("Product" to 1.5f, "Type" to .65f, "Qty" to .55f, "Gross" to .9f),
                setOf(2, 3), "reports-overview-products-columns",
            )
            if (products.isEmpty()) {
                PanelCaption("No settled product sales in this period", "reports-overview-products-empty")
            }
            products.forEachIndexed { index, product ->
                if (wide) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = reportsTableRowHeight())
                            .testTag("reports-overview-product-row-$index"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(product.name, Modifier.weight(1.5f).testTag("reports-overview-product-name-$index"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(product.productType,
                            Modifier.weight(.65f).testTag("reports-overview-product-detail-$index")
                                .semantics { contentDescription = "${product.productType} · Qty ${product.quantitySold.asReportsQuantity()}" },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(product.quantitySold.asReportsQuantity(),
                            Modifier.weight(.55f).testTag("reports-overview-product-quantity-$index"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End)
                        Text(product.grossLineSalesMinor.asReportsRupees(),
                            Modifier.weight(.9f).testTag("reports-overview-product-gross-$index"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End)
                    }
                } else Column {
                    Text(
                        product.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("reports-overview-product-name-$index"),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            "${product.productType} · Qty ${product.quantitySold.asReportsQuantity()}",
                            Modifier.weight(1f).testTag("reports-overview-product-detail-$index"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            product.grossLineSalesMinor.asReportsRupees(),
                            Modifier.weight(1f).testTag("reports-overview-product-gross-$index"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceUsagePanel(devices: List<ReportsOverviewDeviceUsage>, modifier: Modifier, wide: Boolean) {
    SectionCard(
        title = "Device usage",
        icon = if (wide) null else Icons.Filled.SportsEsports,
        modifier = modifier.testTag("reports-overview-devices-panel"),
        contentPadding = if (wide) PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
            else PaddingValues(Spacing.lg),
        headerPadding = if (wide) PaddingValues(horizontal = Spacing.md, vertical = 6.dp)
            else PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (wide) 0.dp else Spacing.xs)) {
            PanelCaption(
                "By device type · sessions ended in period · whole-session play excluding pauses",
                "reports-overview-devices-caption",
                wide,
            )
            if (wide) ReportsTableHeader(
                listOf("Type" to .65f, "Completed" to .9f, "Play" to 1.75f),
                setOf(1, 2), "reports-overview-devices-columns",
            )
            if (devices.isEmpty()) {
                PanelCaption("No completed sessions in this period", "reports-overview-devices-empty")
            }
            devices.forEachIndexed { index, device ->
                if (wide) {
                    val playDescription = device.knownPlayElapsedMs?.let { "Play ${it.asReportsPlayDuration()}" }
                        ?: "Play unavailable · ${device.unknownPlaySessionsCount} with unknown time"
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = reportsTableRowHeight())
                            .testTag("reports-overview-device-row-$index"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(device.deviceType, Modifier.weight(.65f).testTag("reports-overview-device-type-$index"),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(device.completedSessionsCount.toString(),
                            Modifier.weight(.9f).testTag("reports-overview-device-count-$index")
                                .semantics { contentDescription = "${device.deviceType} · ${device.completedSessionsCount} completed" },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End)
                        Text(device.knownPlayElapsedMs?.asReportsPlayDuration()
                                ?: "Unavailable · ${device.unknownPlaySessionsCount} unknown",
                            Modifier.weight(1.75f).testTag("reports-overview-device-time-$index")
                                .semantics { contentDescription = playDescription },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End)
                    }
                } else Column {
                    Text(
                        "${device.deviceType} · ${device.completedSessionsCount} completed",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("reports-overview-device-count-$index"),
                    )
                    Text(
                        device.knownPlayElapsedMs?.let { "Play ${it.asReportsPlayDuration()}" }
                            ?: "Play unavailable · ${device.unknownPlaySessionsCount} with unknown time",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("reports-overview-device-time-$index"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelCaption(text: String, tag: String, wide: Boolean = false) {
    val captionHeight = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() * 2 }
    Text(text, Modifier.then(if (wide) Modifier.heightIn(min = captionHeight) else Modifier).testTag(tag),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun reportsTableRowHeight() = with(LocalDensity.current) {
    maxOf(18.dp, MaterialTheme.typography.labelMedium.lineHeight.toDp())
}

@Composable
private fun ReportsTableHeader(columns: List<Pair<String, Float>>, numeric: Set<Int>, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = reportsTableRowHeight())
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f), Radius.shapeSm)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        columns.forEachIndexed { index, (label, weight) ->
            Text(label, Modifier.weight(weight).testTag("$tag-$index"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (index in numeric) TextAlign.End else TextAlign.Start)
        }
    }
}

/** Decimal quantities never pass through floating point, including totals above 2^53. */
internal fun String.asReportsQuantity(): String = BigDecimal(this).stripTrailingZeros().toPlainString()

/** Preserve milliseconds and zero; unknown is handled separately by the panel. */
internal fun Long.asReportsPlayDuration(): String {
    require(this >= 0)
    val hours = this / 3_600_000
    val minutes = this % 3_600_000 / 60_000
    val seconds = BigDecimal.valueOf(this % 60_000, 3).stripTrailingZeros().toPlainString()
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (this@asReportsPlayDuration % 60_000 > 0 || isEmpty()) add("${seconds}s")
    }.joinToString(" ")
}

internal fun reportsPeakHour(points: List<ReportsOverviewHourPoint>): ReportsOverviewHourPoint? =
    points.maxByOrNull(ReportsOverviewHourPoint::revenueMinor)

private val OverviewSnapshotFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.ENGLISH)

private fun overviewSnapshotLabel(overview: ValidatedReportsOverview): String =
    Instant.ofEpochMilli(overview.snapshotAtMillis)
        .atZone(ZoneId.of(overview.timezone))
        .format(OverviewSnapshotFormatter)

/** Long.MIN_VALUE-safe paise formatting for the signed aggregate contract. */
internal fun Long.asReportsRupees(): String {
    val magnitude = BigInteger.valueOf(this).abs()
    val parts = magnitude.divideAndRemainder(BigInteger.valueOf(100L))
    val digits = parts[0].toString()
    val grouped = if (digits.length <= 3) digits else {
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        buildList {
            var rest = head
            while (rest.length > 2) {
                add(0, rest.takeLast(2))
                rest = rest.dropLast(2)
            }
            if (rest.isNotEmpty()) add(0, rest)
            add(tail)
        }.joinToString(",")
    }
    val sign = if (this < 0L) "-" else ""
    return "$sign₹$grouped.${parts[1].toString().padStart(2, '0')}"
}
