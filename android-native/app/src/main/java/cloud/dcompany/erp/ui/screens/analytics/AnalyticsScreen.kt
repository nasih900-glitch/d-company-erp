package cloud.dcompany.erp.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import java.util.Locale

@Composable
fun AnalyticsScreen() {
    val vm: AnalyticsViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
            Text("Analytics", style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground)
            Text(
                "How the business is actually doing",
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.ForegroundMuted,
            )
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsTab.entries.forEach { tab ->
                FilterChip(
                    selected = state.tab == tab,
                    onClick = { vm.selectTab(tab) },
                    label = { Text(tab.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Gold,
                        selectedLabelColor = Brand.Background,
                        labelColor = Brand.ForegroundMuted,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxSize()) {
            when (state.tab) {
                AnalyticsTab.Today -> TodayTab(state, vm::retryToday)
                AnalyticsTab.Growth -> GrowthTab(state, vm::selectGrowthPeriod, vm::retryGrowth)
            }
        }
    }
}

// -------------------------------------------------------------------- today

@Composable
private fun TodayTab(state: AnalyticsUiState, onRetry: () -> Unit) {
    val dashboard = state.dashboard
    when {
        dashboard != null -> TodayBody(dashboard, state.todayLoading, state.dashboardFetchedAtMillis)
        state.todayError != null -> ErrorPanel(state.todayError, onRetry)
        else -> LoadingPanel()
    }
}

@Composable
private fun TodayBody(dashboard: DashboardKpis, refreshing: Boolean, fetchedAtMillis: Long?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val columns = if (wide) 4 else 2

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(prettyDate(dashboard.date), style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                if (refreshing) {
                    Spacer(Modifier.size(10.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), color = Brand.Gold, strokeWidth = 2.dp)
                }
            }
            if (fetchedAtMillis != null) {
                Text(
                    "As of ${relativeAge(fetchedAtMillis)}" + if (refreshing) " · refreshing…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.GoldMuted,
                )
            }

            if (!dashboard.hasActivity) {
                Spacer(Modifier.height(24.dp))
                Text("No business activity today yet.", color = Brand.ForegroundMuted)
                Spacer(Modifier.height(24.dp))
            }

            val tiles = listOf(
                KpiTile("Revenue", dashboard.revenueTotalMinor.asRupees(), null),
                KpiTile("Orders", "${dashboard.ordersCount} (${dashboard.ticketsCount} tickets)", null),
                KpiTile("Avg ticket", dashboard.avgTicketMinor.asRupees(), null),
                KpiTile("Net profit today", dashboard.netProfitMinor.asRupees(), dashboard.netProfitMinor >= 0),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { KpiCard(it, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            if (dashboard.discountsAndPointsRedeemedMinor > 0) {
                Text(
                    "Revenue above is already net of ${dashboard.discountsAndPointsRedeemedMinor.asRupees()} " +
                        "in discounts and loyalty points redeemed today.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
            }

            if (dashboard.revenueStreams.isNotEmpty()) {
                SectionCard {
                    CardTitle("Revenue by stream (today)")
                    val max = dashboard.revenueStreams.maxOf { it.second }
                    dashboard.revenueStreams.forEach { (label, amountMinor) ->
                        RevenueBar(label, amountMinor, max)
                    }
                }
            }

            SectionCard {
                CardTitle("Right now")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("Inventory value", dashboard.inventoryValueMinor.asRupees(), null)
                    MiniStat(
                        "Low stock",
                        dashboard.lowStockItems.toString(),
                        if (dashboard.lowStockItems > 0) false else null,
                    )
                    MiniStat("Open gaming sessions", dashboard.openSessions.toString(), null)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RevenueBar(label: String, amountMinor: Long, maxMinor: Long) {
    val fraction = if (maxMinor <= 0) 0f else (amountMinor.toFloat() / maxMinor.toFloat()).coerceIn(0.02f, 1f)
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium)
            Text(amountMinor.asRupees(), color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brand.SurfaceRaised)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxHeight().width(maxWidth * fraction)
                        .clip(RoundedCornerShape(4.dp)).background(Brand.Gold),
                )
            }
        }
    }
}

// ------------------------------------------------------------------- growth

@Composable
private fun GrowthTab(
    state: AnalyticsUiState,
    onSelectPeriod: (GrowthPeriodOption) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrowthPeriodOption.entries.forEach { option ->
                FilterChip(
                    selected = state.growthPeriod == option,
                    onClick = { onSelectPeriod(option) },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Gold,
                        selectedLabelColor = Brand.Background,
                        labelColor = Brand.ForegroundMuted,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val growth = state.growth
        when {
            growth != null -> GrowthBody(growth, state.topItems, state.growthLoading, state.growthFetchedAtMillis)
            state.growthError != null -> ErrorPanel(state.growthError, onRetry)
            else -> LoadingPanel()
        }
    }
}

@Composable
private fun GrowthBody(growth: GrowthData, topItems: List<TopItem>, refreshing: Boolean, fetchedAtMillis: Long?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fetchedAtMillis != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "As of ${relativeAge(fetchedAtMillis)}" + if (refreshing) " · refreshing…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.GoldMuted,
                )
                if (refreshing) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), color = Brand.Gold, strokeWidth = 2.dp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ComparisonCard(
                Modifier.weight(1f),
                "Revenue",
                growth.current.revenueMinor.asRupees(),
                growth.previous.label,
                growth.previous.revenueMinor.asRupees(),
                growth.revenueDeltaPct,
            )
            ComparisonCard(
                Modifier.weight(1f),
                "Orders",
                growth.current.ordersCount.toString(),
                growth.previous.label,
                growth.previous.ordersCount.toString(),
                growth.ordersDeltaPct,
            )
        }
        if (growth.current.manualCollectionsMinor > 0) {
            Text(
                "Includes ${growth.current.manualCollectionsMinor.asRupees()} in manual collections this period.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        if (growth.current.refundsMinor > 0) {
            Text(
                "${growth.current.refundsMinor.asRupees()} refunded this period.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }

        SectionCard {
            CardTitle("Top items (this month)")
            if (topItems.isEmpty()) {
                Text("Nothing sold yet this month.", color = Brand.ForegroundMuted)
            } else {
                topItems.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            color = Brand.GoldMuted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.widthIn(min = 28.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${item.type} · ${qtyLabel(item.qtySold)} sold",
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.ForegroundMuted,
                            )
                        }
                        Text(item.revenueMinor.asRupees(), color = Brand.Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun qtyLabel(qty: Double): String =
    if (qty == qty.toLong().toDouble()) qty.toLong().toString()
    else String.format(Locale.ENGLISH, "%.1f", qty)

@Composable
private fun ComparisonCard(
    modifier: Modifier,
    metricLabel: String,
    currentValue: String,
    previousLabel: String,
    previousValue: String,
    deltaPct: Double,
) {
    SectionCard(modifier) {
        Text(metricLabel.uppercase(Locale.ENGLISH), style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(4.dp))
        Text(currentValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Brand.Foreground)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (deltaPct >= 0) "▲ " else "▼ ") + String.format(Locale.ENGLISH, "%.1f%%", kotlin.math.abs(deltaPct)),
                color = if (deltaPct >= 0) Brand.Good else Brand.Danger,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "vs $previousValue $previousLabel",
            style = MaterialTheme.typography.labelSmall,
            color = Brand.ForegroundMuted,
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, good: Boolean?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = when (good) { null -> Brand.Foreground; true -> Brand.Good; false -> Brand.Danger },
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------- shared parts

private data class KpiTile(val label: String, val value: String, val good: Boolean?)

@Composable
private fun KpiCard(tile: KpiTile, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(tile.label.uppercase(Locale.ENGLISH), style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(4.dp))
        Text(
            tile.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = when (tile.good) { null -> Brand.Foreground; true -> Brand.Good; false -> Brand.Danger },
        )
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface)
            .border(BorderStroke(1.dp, Brand.Border), RoundedCornerShape(16.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun LoadingPanel() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Brand.Gold)
        Spacer(Modifier.height(12.dp))
        Text("Computing…", color = Brand.ForegroundMuted)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not load this", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Brand.ForegroundMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
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

/** "2026-08-20" -> "20 Aug 2026", leaving anything unexpected untouched. */
private fun prettyDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
}.getOrDefault(iso)
