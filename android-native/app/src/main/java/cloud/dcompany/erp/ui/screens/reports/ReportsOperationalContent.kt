package cloud.dcompany.erp.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.components.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ReportsOperationalContent(state: ReportsUiState, onRetry: () -> Unit) {
    if (state.operationalTab == ReportsOperationalTab.INVENTORY) {
        ReportsInventoryContent(state, onRetry)
        return
    }
    val data = state.overview
    if (data == null || state.operationalTab == ReportsOperationalTab.OVERVIEW) {
        ReportsOverviewContent(state, onRetry)
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)
            .testTag("reports-operational-${state.operationalTab.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${data.periodStart} to ${data.periodEnd} · ${data.timezone}", style = MaterialTheme.typography.bodyMedium)
        val snapshot = Instant.ofEpochMilli(data.snapshotAtMillis).atZone(ZoneId.of(data.timezone))
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"))
        Text("Server snapshot $snapshot", style = MaterialTheme.typography.bodySmall)
        state.overviewError?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (state.overviewStale && state.overviewError == null) {
            Text(
                if (state.overviewLoading) "Saved snapshot · refreshing current data."
                else "Saved snapshot · refresh to verify current data.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(
            onClick = onRetry,
            enabled = !state.overviewLoading,
            modifier = Modifier.heightIn(min = 48.dp).testTag("reports-operational-refresh"),
        ) { Text(if (state.overviewLoading) "Refreshing…" else "Refresh report") }
        when (state.operationalTab) {
            ReportsOperationalTab.SALES -> {
                ReportsMetricCard("Sales", "Operational receipts for the selected range") {
                    ReportsMetric("Gross revenue", data.grossRevenueMinor.asReportsRupees())
                    ReportsMetric("Settled orders", data.ordersCount.toString())
                }
                SourcePanel(data.sources, Modifier.fillMaxWidth(), horizontalLegend = false)
                HourProfilePanel(data.localHourProfile, Modifier.fillMaxWidth())
                Text("Top five historical product snapshots by gross line sales", style = MaterialTheme.typography.bodyMedium)
                TopProductsPanel(data.topProducts, Modifier.fillMaxWidth(), wide = false)
            }
            ReportsOperationalTab.SESSIONS -> {
                ReportsMetricCard("Sessions", "Starts and completions are separate date cohorts") {
                    ReportsMetric("Started in this range", data.sessionsStartedCount.toString())
                    Text("Completed sessions below ended in this range. Cancelled sessions are excluded. Counts cannot be subtracted to infer active sessions.", style = MaterialTheme.typography.bodyMedium)
                }
                DeviceUsagePanel(data.deviceUsage, Modifier.fillMaxWidth(), wide = false)
            }
            ReportsOperationalTab.CUSTOMERS -> ReportsMetricCard("Customers", "Customer linkage on settled orders in this range") {
                ReportsMetric("Distinct linked customers", data.distinctLinkedCustomersCount.toString())
                ReportsMetric("Orders with a customer", data.linkedOrdersCount.toString())
                ReportsMetric("Orders without a customer", data.unlinkedOrdersCount.toString())
                Text("Paid and refunded orders are included. These are order links, not visits or new-customer counts.", style = MaterialTheme.typography.bodyMedium)
            }
            ReportsOperationalTab.PAYMENTS -> {
                Text("Collections by settlement method. These figures do not represent net cash after refunds.", style = MaterialTheme.typography.bodyMedium)
                PaymentPanel(data.payments, Modifier.fillMaxWidth(), wide = false)
            }
            ReportsOperationalTab.DEVICES -> {
                Text("Completed, non-cancelled sessions by device type and end date. Play time excludes recorded pauses; unavailable timing remains unknown.", style = MaterialTheme.typography.bodyMedium)
                DeviceUsagePanel(data.deviceUsage, Modifier.fillMaxWidth(), wide = false)
            }
            ReportsOperationalTab.OVERVIEW, ReportsOperationalTab.INVENTORY -> Unit
        }
    }
}

@Composable
private fun ReportsMetricCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    SectionCard(title = title, subtitle = subtitle, icon = Icons.Filled.Assessment, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ReportsMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ReportsOperationalStatus(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
    }
}

@Composable
private fun ReportsInventoryContent(state: ReportsUiState, onRetry: () -> Unit) {
    val inventory = state.inventory
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("reports-operational-inventory"),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ReportsMetricCard("Current inventory", "Ingredient stock · independent of the report period") {
                if (state.inventoryLoading) CircularProgressIndicator()
                state.inventoryError?.let { ReportsOperationalStatus(it, onRetry) }
                if (inventory == null && !state.inventoryLoading && state.inventoryError == null) {
                    ReportsOperationalStatus("Connect to verify current inventory for this branch.", onRetry)
                }
                if (inventory != null) {
                    Text("As of ${inventory.asOf} · company local date", style = MaterialTheme.typography.bodyMedium)
                    state.inventoryFetchedAtMillis?.let {
                        Text("Retrieved ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z"))}", style = MaterialTheme.typography.bodySmall)
                    }
                    ReportsMetric("Stock valuation", inventory.totalValuationMinor.asReportsRupees())
                    ReportsMetric("Ingredients", inventory.lines.size.toString())
                    ReportsMetric("Low stock", inventory.lowStockCount.toString())
                    Text("Valuation uses remaining batch layers, including shortage and expired layers. Rounded average cost is shown separately.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onRetry, enabled = !state.inventoryLoading, modifier = Modifier.heightIn(min = 48.dp).testTag("reports-inventory-refresh")) { Text("Refresh current stock") }
                    if (inventory.lines.isEmpty()) Text("No ingredients in this branch's inventory report.")
                }
            }
        }
        items(inventory?.lines.orEmpty(), key = { it.ingredientId }) { line ->
            ReportsMetricCard(line.name, line.sku) {
                ReportsMetric("Quantity (${line.baseUnit})", line.currentQty.asReportsStockQuantity())
                ReportsMetric("Valuation", line.valuationMinor.asReportsRupees())
                ReportsMetric("Average unit cost", line.avgCostMinor.asReportsRupees())
                ReportsMetric("Reorder threshold (${line.baseUnit})", line.reorderThreshold.asReportsStockQuantity())
                Text(if (line.isLowStock) "Low stock" else "Not flagged low stock", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
