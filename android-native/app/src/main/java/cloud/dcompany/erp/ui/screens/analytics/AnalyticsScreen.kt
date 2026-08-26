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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
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
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import java.util.Locale

@Composable
fun AnalyticsScreen() {
    val vm: AnalyticsViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        PremiumTabBar(
            options = AnalyticsTab.entries.map { TabOption(it.name, it.label) },
            selectedId = state.tab.name,
            onSelect = { id -> AnalyticsTab.entries.firstOrNull { it.name == id }?.let(vm::selectTab) },
            modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
        )
        Box(Modifier.fillMaxSize()) {
            when (state.tab) {
                AnalyticsTab.Today -> TodayTab(state, vm::retryToday)
                AnalyticsTab.Growth -> GrowthTab(
                    state,
                    vm::selectGrowthPeriod,
                    vm::retryGrowth,
                    vm::retryTopItems,
                )
            }
        }
    }
}

// -------------------------------------------------------------------- today

@Composable
private fun TodayTab(state: AnalyticsUiState, onRetry: () -> Unit) {
    val dashboard = state.dashboard
    when (cachedDataPresentation(dashboard != null, state.todayLoading, state.todayError)) {
        CachedDataPresentation.INITIAL_LOADING -> LoadingPanel()
        CachedDataPresentation.BLOCKING_ERROR -> ErrorPanel(state.todayError!!, onRetry)
        CachedDataPresentation.FRESH -> TodayBody(
            dashboard!!,
            state.todayLoading,
            state.dashboardFetchedAtMillis,
        )
        CachedDataPresentation.STALE -> Column(Modifier.fillMaxSize()) {
            StaleDataBanner("Today's saved figures", state.todayError!!, onRetry)
            Box(Modifier.weight(1f)) {
                TodayBody(dashboard!!, state.todayLoading, state.dashboardFetchedAtMillis)
            }
        }
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
                SectionCard(
                    title = "Today's operating picture",
                    subtitle = "Live activity will populate this workspace as orders and sessions are recorded.",
                    icon = Icons.Filled.Analytics,
                ) {
                    DesignedEmptyState(
                        title = "No business activity today yet",
                        body = "Revenue, order and margin insights will update automatically after the first completed transaction.",
                        icon = Icons.AutoMirrored.Filled.TrendingUp,
                        modifier = Modifier.height(190.dp),
                    )
                }
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
                        .clip(RoundedCornerShape(4.dp)).background(Brand.Information),
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
    onRetryTopItems: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PremiumTabBar(
            options = GrowthPeriodOption.entries.map { TabOption(it.name, it.label) },
            selectedId = state.growthPeriod.name,
            onSelect = { id -> GrowthPeriodOption.entries.firstOrNull { it.name == id }?.let(onSelectPeriod) },
            modifier = Modifier.padding(horizontal = Spacing.lgPlus, vertical = Spacing.sm),
        )
        val growth = state.growth
        when (cachedDataPresentation(growth != null, state.growthLoading, state.growthError)) {
            CachedDataPresentation.INITIAL_LOADING -> LoadingPanel()
            CachedDataPresentation.BLOCKING_ERROR -> ErrorPanel(state.growthError!!, onRetry)
            CachedDataPresentation.FRESH -> GrowthBody(
                growth!!,
                state,
                onRetryTopItems,
            )
            CachedDataPresentation.STALE -> Column(Modifier.fillMaxSize()) {
                StaleDataBanner("Saved growth comparison", state.growthError!!, onRetry)
                Box(Modifier.weight(1f)) {
                    GrowthBody(growth!!, state, onRetryTopItems)
                }
            }
        }
    }
}

@Composable
private fun GrowthBody(
    growth: GrowthData,
    state: AnalyticsUiState,
    onRetryTopItems: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.growthFetchedAtMillis != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "As of ${relativeAge(state.growthFetchedAtMillis)}" +
                        if (state.growthLoading) " · refreshing…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.GoldMuted,
                )
                if (state.growthLoading) {
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
        if (growth.current.membershipsMinor > 0 || growth.previous.membershipsMinor > 0) {
            Text(
                "Paid memberships: ${growth.current.membershipsMinor.asRupees()} this period, " +
                    "${growth.previous.membershipsMinor.asRupees()} in ${growth.previous.label}. " +
                    "They do not increase POS order counts or top-item totals.",
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

        TopItemsCard(state, onRetryTopItems)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TopItemsCard(state: AnalyticsUiState, onRetry: () -> Unit) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Top items (this month)")
            if (state.topItemsLoading) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(14.dp), color = Brand.Gold, strokeWidth = 2.dp)
            }
        }
        when (
            supplementalListPresentation(
                hasSnapshot = state.topItemsFetchedAtMillis != null,
                isEmpty = state.topItems.isEmpty(),
                error = state.topItemsError,
            )
        ) {
            SupplementalListPresentation.INITIAL_LOADING ->
                Text("Loading top items…", color = Brand.ForegroundMuted)
            SupplementalListPresentation.BLOCKING_ERROR -> InlineListError(
                state.topItemsError!!,
                onRetry,
            )
            SupplementalListPresentation.FRESH_EMPTY ->
                DesignedEmptyState(
                    title = "Nothing sold this month",
                    body = "Product rankings will appear after completed sales are included in analytics.",
                    icon = Icons.Filled.PointOfSale,
                    modifier = Modifier.height(180.dp),
                )
            SupplementalListPresentation.STALE_EMPTY -> {
                InlineListError(
                    "${state.topItemsError} The saved empty result may be out of date.",
                    onRetry,
                )
            }
            SupplementalListPresentation.FRESH_CONTENT,
            SupplementalListPresentation.STALE_CONTENT -> {
                if (state.topItemsError != null) {
                    InlineListError(
                        "${state.topItemsError} Showing saved item rankings.",
                        onRetry,
                    )
                }
                state.topItems.forEachIndexed { index, item ->
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
                        Text(item.revenueMinor.asRupees(), color = Brand.Foreground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        state.topItemsFetchedAtMillis?.let { fetchedAt ->
            Text(
                "As of ${relativeAge(fetchedAt)}" + if (state.topItemsLoading) " · refreshing…" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.GoldMuted,
            )
        }
    }
}

@Composable
private fun InlineListError(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.SurfaceRaised).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, color = Brand.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun StaleDataBanner(title: String, message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(Radius.shapeMd).background(Brand.SurfaceRaised)
            .border(BorderStroke(1.dp, Brand.Danger), Radius.shapeMd)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("$title may be out of date", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onRetry) { Text("Retry") }
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
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun LoadingPanel() {
    SectionCard(
        title = "Business insight workspace",
        subtitle = "Computing current operational metrics",
        icon = Icons.Filled.Analytics,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Brand.Gold)
            Spacer(Modifier.height(Spacing.md))
            Text("Computing insights…", color = Brand.ForegroundMuted)
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    SectionCard(
        title = "Business insight workspace",
        subtitle = "Your selected view is preserved while the request is retried",
        icon = Icons.Filled.Analytics,
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lgPlus, vertical = Spacing.md),
    ) {
        DesignedEmptyState(
            title = "Could not load analytics",
            body = message,
            icon = Icons.Filled.Analytics,
            primaryLabel = "Try again",
            onPrimary = onRetry,
        )
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
