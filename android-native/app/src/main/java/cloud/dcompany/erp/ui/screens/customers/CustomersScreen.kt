package cloud.dcompany.erp.ui.screens.customers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.CustomersAccess
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionBar
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.CompactStatCard
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.LoadingSkeleton
import cloud.dcompany.erp.ui.components.NumericValue
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.SearchInput
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing
import cloud.dcompany.erp.ui.components.ViewOnlyNotice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Customers — the phone number is the loyalty identity.
 *
 * Most rows here are created by the till at checkout, not by hand; this screen
 * is where staff look someone up, see what their points are worth, and fix the
 * details the till could not capture while a queue was waiting.
 */
@Composable
fun CustomersScreen(access: CustomersAccess = CustomersAccess()) {
    val vm: CustomersViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val playtimeVm: CustomerPlaytimeViewModel = viewModel()
    val playtime by playtimeVm.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var listFilter by rememberSaveable { mutableStateOf(CustomerListFilter.All) }
    var viewMode by rememberSaveable { mutableStateOf(CustomerViewMode.Directory) }
    LaunchedEffect(viewMode) {
        if (viewMode == CustomerViewMode.PlayHours) playtimeVm.refresh()
    }
    SideEffect { vm.updateAccess(access) }
    SideEffect { playtimeVm.clearIfScopeChanged() }
    DisposableEffect(playtimeVm) {
        onDispose { playtimeVm.clear() }
    }

    Column(
        Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.People, contentDescription = null,
                tint = Brand.GoldBright, modifier = Modifier.size(32.dp))
            Column {
                Text("CUSTOMERS", color = Brand.Foreground,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Players make D Company", color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        CustomerViewModeSelector(viewMode) { next ->
            focusManager.clearFocus()
            if (next == CustomerViewMode.PlayHours) vm.clearSelection()
            viewMode = next
        }
        if (viewMode == CustomerViewMode.Directory) {
            CustomerActionRow(
                state = state,
                canWrite = access.canManageCustomers,
                onQueryChange = vm::search,
                onClearSearch = vm::clearSearch,
                onRefresh = vm::retry,
                onAdd = vm::startCreate,
                filter = listFilter,
                onFilterChange = { listFilter = it },
            )
        }
        if (!access.canManageCustomers) ViewOnlyNotice()

        state.notice?.let {
            NoticeBanner(it, vm::dismissNotice)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val selected = state.selected

            // The Customers destination is retained by the app shell. Refresh
            // the selected customer's server-linked bills whenever the
            // destination is re-entered, and whenever a completed payment
            // changes that customer's financial summary. Otherwise the
            // points/visit cards update after checkout while Purchase history
            // misleadingly remains one bill behind until a manual refresh.
            LaunchedEffect(
                selected?.id,
                selected?.visitCount,
                selected?.totalSpentMinor,
                selected?.lastVisitAt,
            ) {
                selected?.let(vm::refreshHistory)
            }

            when {
                viewMode == CustomerViewMode.PlayHours -> CustomerLeaderboardPanel(
                    state = playtimeVm.stateForCurrentScope(playtime),
                    onRefresh = playtimeVm::refresh,
                    onLoadMore = playtimeVm::loadMore,
                    modifier = Modifier.fillMaxSize(),
                )

                state.loading -> SectionCard(Modifier.fillMaxSize()) {
                    LoadingSkeleton(lines = 7)
                }

                state.couldNotLoad -> SectionCard(
                    modifier = Modifier.fillMaxSize(),
                    title = "Customer results",
                    subtitle = "Searchable customer and loyalty records",
                    icon = Icons.Default.People,
                ) {
                    DesignedEmptyState(
                        title = "Could not load customers",
                        body = "No customers are saved on this tablet yet, and the server could not be reached.",
                        icon = Icons.Default.ErrorOutline,
                        primaryLabel = "Retry",
                        onPrimary = vm::retry,
                        modifier = Modifier.weight(1f),
                    )
                }

                state.rows.isEmpty() -> SectionCard(
                    modifier = Modifier.fillMaxSize(),
                    title = "Customer results",
                    subtitle = if (state.searching) "Filtered by the current search" else "Customer and loyalty records",
                    icon = Icons.Default.People,
                ) {
                    DesignedEmptyState(
                        title = if (state.searching) {
                            "No customer matches “${state.query.trim()}”"
                        } else {
                            "No customers yet"
                        },
                        body = if (state.searching) {
                            "Search matches phone numbers and names. Clear the search or add a new customer."
                        } else {
                            "Customers are saved automatically when a bill includes a phone number."
                        },
                        icon = if (state.searching) Icons.Default.Search else Icons.Default.People,
                        primaryLabel = "Add customer".takeIf { access.canManageCustomers },
                        onPrimary = (vm::startCreate).takeIf { access.canManageCustomers },
                        secondaryLabel = "Clear search".takeIf { state.searching },
                        onSecondary = (vm::clearSearch).takeIf { state.searching },
                        modifier = Modifier.weight(1f),
                    )
                }

                selected != null -> DetailPane(
                    customer = selected,
                    canWrite = access.canManageCustomers,
                    onEdit = { vm.startEdit(selected) },
                    onBack = vm::clearSelection,
                    onRetrySync = { vm.retrySync(selected) },
                    history = state.history.takeIf {
                        state.historyCustomerId == selected.id
                    }.orEmpty(),
                    historyLoading = state.historyLoading && state.historyCustomerId == selected.id,
                    historyError = state.historyError.takeIf {
                        state.historyCustomerId == selected.id
                    },
                    onRetryHistory = vm::retryHistory,
                )

                else -> Column(Modifier.fillMaxSize()) {
                    CustomerResultsPanel(
                        state = state,
                        onSelect = vm::select,
                        filter = listFilter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    state.editor?.takeIf { access.canManageCustomers }?.let { editor ->
        EditorDialog(
            editor = editor,
            saving = state.saving,
            error = state.saveError,
            onChange = vm::editorChanged,
            onCancel = vm::cancelEdit,
            onSave = vm::save,
        )
    }
}

private fun formatPlayMinutes(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val remainder = safe % 60
    return when {
        hours == 0 -> "${remainder}m"
        remainder == 0 -> "${hours}h"
        else -> "${hours}h ${remainder}m"
    }
}

internal enum class CustomerViewMode { Directory, PlayHours }

@Composable
private fun CustomerViewModeSelector(selected: CustomerViewMode, onSelect: (CustomerViewMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        ErpButton(
            text = "Customer directory",
            onClick = { onSelect(CustomerViewMode.Directory) },
            modifier = Modifier.weight(1f),
            intent = if (selected == CustomerViewMode.Directory) ActionIntent.Primary else ActionIntent.Secondary,
        )
        ErpButton(
            text = "Playtime leaderboard",
            onClick = { onSelect(CustomerViewMode.PlayHours) },
            modifier = Modifier.weight(1f),
            intent = if (selected == CustomerViewMode.PlayHours) ActionIntent.Primary else ActionIntent.Secondary,
        )
    }
}

// ------------------------------------------------------------------ header

internal enum class CustomerListFilter(val label: String) {
    All("All customers"),
    NeedsSync("Waiting to sync"),
    SyncFailed("Sync failed"),
}

@Composable
private fun CustomerActionRow(
    state: CustomersUiState,
    canWrite: Boolean,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    filter: CustomerListFilter,
    onFilterChange: (CustomerListFilter) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 760.dp

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CustomerSearchBar(state, onQueryChange, onClearSearch)
                ActionBar(
                    leading = {
                        CustomerFilterAction(filter, onFilterChange)
                        CustomerRefreshAction(state, onRefresh, Modifier.weight(1f))
                        CustomerAddAction(canWrite, onAdd, Modifier.weight(1f))
                    },
                )
            }
        } else {
            ActionBar(
                leading = {
                    SearchInput(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = "Search customers by phone or name",
                        modifier = Modifier.weight(1f),
                    )
                    if (state.query.isNotBlank()) {
                        ErpButton("Clear", onClearSearch, intent = ActionIntent.Quiet)
                    }
                    CustomerFilterAction(filter, onFilterChange)
                    CustomerRefreshAction(state, onRefresh)
                    CustomerAddAction(canWrite, onAdd)
                },
            )
        }
    }
}

@Composable
private fun CustomerFilterAction(
    filter: CustomerListFilter,
    onFilterChange: (CustomerListFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (filter == CustomerListFilter.All) "Filter" else filter.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CustomerListFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onFilterChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun CustomerSearchBar(
    state: CustomersUiState,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    ActionBar(
        leading = {
            SearchInput(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = "Search customers by phone or name",
                modifier = Modifier.weight(1f),
            )
            if (state.query.isNotBlank()) {
                ErpButton("Clear", onClearSearch, intent = ActionIntent.Quiet)
            }
        },
    )
}

@Composable
private fun CustomerRefreshAction(
    state: CustomersUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ErpButton(
        text = if (state.syncing) "Refreshing…" else "Refresh",
        onClick = onRefresh,
        modifier = modifier,
        intent = ActionIntent.Secondary,
        enabled = !state.loading && !state.syncing,
        busy = state.syncing,
        leadingIcon = Icons.Default.Refresh,
    )
}

@Composable
private fun CustomerAddAction(
    canWrite: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ErpButton(
        text = "Add customer",
        onClick = onAdd,
        modifier = modifier,
        enabled = canWrite,
        leadingIcon = Icons.Default.PersonAdd,
    )
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    OperationalBanner(
        title = "Customer update",
        detail = message,
        tone = UiTone.Information,
        icon = Icons.Default.CheckCircle,
        action = {
            ErpButton("Dismiss", onDismiss, intent = ActionIntent.Quiet)
        },
    )
}

@Composable
private fun SyncFailedNotice(error: String?, canRetry: Boolean, onRetry: () -> Unit) {
    OperationalBanner(
        title = "Could not sync this customer",
        detail = error ?: "The server refused this save.",
        tone = UiTone.Danger,
        icon = Icons.Default.ErrorOutline,
        action = {
            ErpButton(
                text = "Retry",
                onClick = onRetry,
                intent = ActionIntent.Secondary,
                enabled = canRetry,
            )
        },
    )
}

// -------------------------------------------------------------------- list

@Composable
internal fun CustomerResultsPanel(
    state: CustomersUiState,
    onSelect: (Customer) -> Unit,
    filter: CustomerListFilter,
    modifier: Modifier = Modifier,
) {
    val rows = when (filter) {
        CustomerListFilter.All -> state.rows
        CustomerListFilter.NeedsSync -> state.rows.filter(Customer::isPendingSync)
        CustomerListFilter.SyncFailed -> state.rows.filter(Customer::isRejected)
    }
    Column(
        modifier.clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.Gold.copy(alpha = 0.38f), Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Brand.Gold.copy(alpha = 0.12f), Brand.Surface, Brand.Surface),
                    ),
                )
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Saved customers", color = Brand.GoldBright, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${rows.size} shown · ${state.rows.size} in current ${if (state.searching) "search" else "directory"}",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "${state.totalVisits.grouped()} visits · ${state.totalSpentMinor.asRupees()} spent · " +
                        "${state.totalPoints.grouped()} points",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(
                Modifier.clip(Radius.shapeMd)
                    .background(Brand.Gold.copy(alpha = 0.1f))
                    .border(1.dp, Brand.Gold.copy(alpha = 0.35f), Radius.shapeMd)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "${state.rows.size.grouped()} customers",
                    color = Brand.GoldBright,
                    fontWeight = FontWeight.Bold,
                )
                if (state.syncing) Text("Refreshing", color = Brand.Information,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        PanelDivider()
        if (rows.isEmpty()) {
            Text(
                "No customers match ${filter.label.lowercase(Locale.getDefault())}.",
                modifier = Modifier.weight(1f).padding(Spacing.lg),
                color = Brand.ForegroundMuted,
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows, key = Customer::id) { customer ->
                    CustomerRow(
                        customer = customer,
                        selected = customer.id == state.selectedId,
                        onClick = { onSelect(customer) },
                    )
                    PanelDivider()
                }
            }
        }
        Text(
            "Saved directory works offline · playtime ranks require an online refresh",
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun CustomerLeaderboardPanel(
    state: CustomerPlaytimeUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.Gold.copy(alpha = 0.38f), Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Playtime leaderboard", color = Brand.GoldBright,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    state.total?.let { "${state.items.size} of $it ranked customers" }
                        ?: "Online playtime ranking",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            ErpButton(
                text = "Refresh",
                onClick = onRefresh,
                enabled = !state.loading,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.Refresh,
            )
        }
        PanelDivider()
        when {
            state.loading && state.items.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth().padding(Spacing.lg)) {
                    LoadingSkeleton(lines = 7)
                }
            }
            state.items.isEmpty() -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(Spacing.lg),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            state.error != null -> "Playtime ranking unavailable"
                            state.total == 0 -> "No customers to rank yet"
                            else -> "Connect to load playtime ranks"
                        },
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.error ?: "Completed sessions will appear after the first online refresh.",
                        color = if (state.error == null) Brand.ForegroundMuted else Brand.Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.error != null) ErpButton("Retry", onRefresh)
                }
            }
            else -> {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 700.dp
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (wide) item(key = "header") { CustomerTableHeader() }
                        items(state.items, key = PlaytimeLeaderboardItem::customerId) { item ->
                            CustomerLeaderboardRow(item, state.program?.rewardsEnabled == true, wide)
                            PanelDivider()
                        }
                        if (state.error != null) item(key = "error") {
                            Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                                Text("Remaining ranks unavailable: ${state.error}", color = Brand.Warning)
                                ErpButton("Refresh leaderboard", onRefresh, intent = ActionIntent.Secondary)
                            }
                        } else if (state.canLoadMore) item(key = "load-more") {
                            ErpButton(
                                text = if (state.loading) "Loading more…" else "Load more ranks",
                                onClick = onLoadMore,
                                busy = state.loading,
                                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                                intent = ActionIntent.Secondary,
                            )
                        }
                    }
                }
            }
        }
        val rewardNote = if (state.program?.rewardsEnabled == true ||
            state.program?.messagingEnabled == true
        ) "Check Web ERP for play rewards and messages"
        else "Play rewards and messages are off"
        Text(
            "Server-ranked completed playtime · Recorded visits are settled purchases · $rewardNote",
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CustomerTableHeader() {
    Row(
        Modifier.fillMaxWidth().background(Brand.BackgroundSecondary)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", Modifier.width(48.dp), color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall)
        Text("Customer", Modifier.weight(1f), color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall)
        Text("Hours played", Modifier.width(112.dp), color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall)
        Text("Paid hours", Modifier.width(96.dp), color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall)
        Text("Recorded visits", Modifier.width(116.dp), color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall)
        Text("Play rewards", Modifier.width(132.dp), color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CustomerLeaderboardRow(
    playtime: PlaytimeLeaderboardItem,
    rewardsEnabled: Boolean,
    wide: Boolean,
) {
    val rank = playtime.rank
    val rankColor = leaderboardRankColor(rank)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(
                when {
                    rankColor != null -> Brush.horizontalGradient(
                        listOf(rankColor.copy(alpha = 0.14f), Brand.Surface, Brand.Surface),
                    )
                    else -> Brush.horizontalGradient(listOf(Brand.Surface, Brand.Surface))
                },
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(48.dp).semantics {
                contentDescription = "Rank $rank by completed playtime"
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (rankColor != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(Icons.Default.Star, contentDescription = null,
                        tint = rankColor, modifier = Modifier.size(15.dp))
                    Text(rank.toString(), color = rankColor, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(rank.toString(),
                    color = Brand.ForegroundMuted, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background((rankColor ?: Brand.Gold).copy(alpha = 0.16f))
                    .border(
                        1.dp,
                        (rankColor ?: Brand.Gold).copy(alpha = if (rankColor == null) 0.22f else 0.68f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(playtime.name?.trim()?.firstOrNull()?.uppercase() ?: "?",
                    color = rankColor ?: Brand.GoldBright, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(playtime.name?.takeIf(String::isNotBlank) ?: "— no name —", color = Brand.Foreground,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The leaderboard API has already masked this value and kept
                // the last four digits for staff identification. Masking it
                // again would erase that suffix.
                Text(playtime.maskedPhone,
                    color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1)
            }
        }
        Text(
            formatPlayMinutes(playtime.totalPlayedMinutes),
            modifier = Modifier.width(if (wide) 112.dp else 90.dp), color = rankColor ?: Brand.Foreground,
            fontWeight = FontWeight.SemiBold,
        )
        if (wide) {
            Text(formatPlayMinutes(playtime.qualifyingPaidMinutes), Modifier.width(96.dp),
                color = Brand.Foreground)
            Text(playtime.recordedVisits?.toString() ?: "—", Modifier.width(116.dp),
                color = Brand.Foreground)
            Text(
                if (rewardsEnabled) "See Web" else "Coming soon",
                modifier = Modifier.width(132.dp).clip(Radius.shapePill)
                    .background(Brand.SurfaceRaised)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** Only the server's actual top-three playtime ranks receive podium colours. */
private fun leaderboardRankColor(rank: Int?): Color? = when (rank) {
    1 -> Brand.GoldBright
    2 -> Color(0xFFB9C7D2)
    3 -> Color(0xFFD7A176)
    else -> null
}

private fun maskCustomerListPhone(phone: String): String {
    val digitCount = phone.count(Char::isDigit)
    if (digitCount <= 4) return phone.map { if (it.isDigit()) '*' else it }.joinToString("")
    val visiblePrefix = if (digitCount > 10) 5 else 2
    var digitIndex = 0
    return buildString(phone.length) {
        phone.forEach { character ->
            if (character.isDigit()) {
                append(if (digitIndex in visiblePrefix until digitCount - 2) '*' else character)
                digitIndex++
            } else {
                append(character)
            }
        }
    }
}

@Composable
private fun CustomerRow(customer: Customer, selected: Boolean, onClick: () -> Unit) {
    DataListRow(
        modifier = Modifier
            .background(if (selected) Brand.SurfaceHover else Brand.Surface)
            .semantics { this.selected = selected },
        onClick = onClick,
        leading = {
            Box(
                Modifier.size(42.dp).clip(Radius.shapeMd)
                    .background(if (selected) Brand.Gold.copy(alpha = 0.16f) else Brand.SurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    customer.name?.trim()?.firstOrNull()?.uppercase() ?: "?",
                    color = if (selected) Brand.Gold else Brand.ForegroundMuted,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    customer.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (customer.name.isNullOrBlank()) FontStyle.Italic else FontStyle.Normal,
                    color = if (customer.name.isNullOrBlank()) Brand.ForegroundMuted else Brand.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RankBadge(customer.gamingRank)
            }
                Text(
                    maskCustomerListPhone(customer.phone),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                color = Brand.ForegroundMuted,
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${customer.loyaltyPoints.grouped()} pts",
                    style = MaterialTheme.typography.labelLarge,
                    color = Brand.Foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${customer.visitCount.grouped()} visits · ",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                    Text(
                        customer.totalSpentMinor.asRupees(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Foreground,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                when {
                    customer.isRejected -> OperationalStatusBadge("Sync failed", UiTone.Danger)
                    customer.isPendingSync -> OperationalStatusBadge("Waiting to sync", UiTone.Warning)
                }
            }
        },
    )
}

@Composable
private fun RankBadge(rank: String) {
    val colour = rankColour(rank)
    val filled = rank == "Legend"
    Text(
        rank,
        style = MaterialTheme.typography.labelSmall,
        color = if (filled) Brand.Background else colour,
        modifier = Modifier
            .clip(Radius.shapePill)
            .background(if (filled) colour else Brand.Background)
            .border(1.dp, colour, Radius.shapePill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

private fun rankColour(rank: String) = when (rank) {
    "Legend" -> Brand.Gold
    "Pro" -> Brand.Gold
    "Player" -> Brand.Good
    else -> Brand.ForegroundMuted
}

// ------------------------------------------------------------------ detail

@Composable
private fun DetailPane(
    customer: Customer,
    canWrite: Boolean,
    onEdit: () -> Unit,
    onBack: (() -> Unit)?,
    onRetrySync: () -> Unit,
    history: List<CustomerOrderHistory>,
    historyLoading: Boolean,
    historyError: String?,
    onRetryHistory: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(Radius.shapeLg)
            .background(Brand.BackgroundSecondary)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
            .padding(Spacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (onBack != null) {
            ErpButton(
                text = "All customers",
                onClick = onBack,
                intent = ActionIntent.Quiet,
                leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            )
        }

        SectionCard(
            title = customer.displayName,
            subtitle = customer.phone,
            icon = Icons.Default.AccountCircle,
            elevated = true,
            action = {
                ErpButton(
                    text = "Edit",
                    onClick = onEdit,
                    intent = ActionIntent.Secondary,
                    enabled = canWrite,
                    leadingIcon = Icons.Default.Edit,
                )
            },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RankBadge(customer.gamingRank)
                when {
                    customer.isRejected -> OperationalStatusBadge("Sync failed", UiTone.Danger)
                    customer.isPendingSync -> OperationalStatusBadge("Waiting to sync", UiTone.Warning)
                    else -> OperationalStatusBadge("Saved", UiTone.Success)
                }
            }
        }

        if (customer.isRejected) {
            SyncFailedNotice(customer.rejectedError, canWrite, onRetrySync)
        } else if (customer.isPendingSync) {
            OperationalBanner(
                title = "Waiting to sync",
                detail = "This profile is saved on the tablet and will upload when the connection returns.",
                tone = UiTone.Warning,
                icon = Icons.Default.CloudUpload,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CompactStatCard(
                label = "Visits",
                value = customer.visitCount.grouped(),
                icon = Icons.Default.EventRepeat,
                tone = UiTone.Success,
                modifier = Modifier.weight(1f),
            )
            CompactStatCard(
                label = "Spent",
                value = customer.totalSpentMinor.asRupees(),
                icon = Icons.Default.Payments,
                tone = UiTone.Brand,
                modifier = Modifier.weight(1f),
            )
        }
        CompactStatCard(
            label = "Points balance",
            value = "${customer.loyaltyPoints.grouped()} pts",
            detail = "Redeemable for ${customer.loyaltyValueMinor.asRupees()} · 10 points = ₹1",
            icon = Icons.Default.Star,
            tone = UiTone.Warning,
            modifier = Modifier.fillMaxWidth(),
        )

        RankCard(customer)

        PurchaseHistoryCard(
            rows = history,
            loading = historyLoading,
            error = historyError,
            isUnsyncedCustomer = customer.id.startsWith("local:"),
            onRetry = onRetryHistory,
        )

        SectionCard(
            title = "Profile details",
            subtitle = "Contact details and visit information.",
            icon = Icons.Default.AccountCircle,
        ) {
            InfoRow("Phone", customer.phone, icon = Icons.Default.Phone)
            PanelDivider()
            InfoRow(
                "Email",
                customer.email?.takeIf(String::isNotBlank) ?: "Not recorded",
                icon = Icons.Default.Email,
            )
            PanelDivider()
            InfoRow("Birthday", customer.birthdayLabel(), icon = Icons.Default.Cake)
            PanelDivider()
            InfoRow("Last visit", customer.lastVisitLabel(), icon = Icons.Default.EventRepeat)
            PanelDivider()
            InfoRow(
                "Notes",
                customer.notes?.takeIf(String::isNotBlank) ?: "No notes",
            )
        }
    }
}

private val HISTORY_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)

@Composable
private fun PurchaseHistoryCard(
    rows: List<CustomerOrderHistory>,
    loading: Boolean,
    error: String?,
    isUnsyncedCustomer: Boolean,
    onRetry: () -> Unit,
) {
    SectionCard(
        title = "Purchase history",
        subtitle = "Server-linked bills for this exact customer profile.",
        icon = Icons.AutoMirrored.Filled.ReceiptLong,
        action = {
            ErpButton(
                text = if (loading) "Refreshing…" else "Refresh",
                onClick = onRetry,
                intent = ActionIntent.Quiet,
                enabled = !loading && !isUnsyncedCustomer,
                busy = loading,
            )
        },
    ) {
        when {
            loading && rows.isEmpty() -> LoadingSkeleton(lines = 3)

            isUnsyncedCustomer -> Text(
                "This new profile will show purchases after it syncs to the server.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            rows.isEmpty() && error != null -> OperationalBanner(
                title = "Could not load purchase history",
                detail = error,
                tone = UiTone.Warning,
                icon = Icons.Default.ErrorOutline,
                action = {
                    ErpButton("Retry", onRetry, intent = ActionIntent.Secondary)
                },
            )

            rows.isEmpty() -> Text(
                "No server-linked purchases yet. New paid, voided, and refunded bills will appear here.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            else -> {
                error?.let {
                    OperationalBanner(
                        title = "Showing saved history",
                        detail = "$it The bills below are the last copy saved on this tablet.",
                        tone = UiTone.Warning,
                        icon = Icons.Default.ErrorOutline,
                    )
                }
                rows.take(10).forEachIndexed { index, row ->
                    if (index > 0 || error != null) PanelDivider()
                    PurchaseHistoryRow(row)
                }
                if (rows.size > 10) {
                    PanelDivider()
                    Text(
                        "Showing the latest 10 of ${rows.size} cached bills.",
                        color = Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PurchaseHistoryRow(row: CustomerOrderHistory) {
    val source = row.sourceLabel?.takeIf(String::isNotBlank)
        ?: row.type.replace('_', ' ').replaceFirstChar(Char::uppercase)
    val timestamp = runCatching {
        Instant.parse(row.invoiceIssuedAt ?: row.createdAt)
            .atZone(ZoneId.systemDefault())
            .format(HISTORY_TIME_FORMAT)
    }.getOrDefault(row.createdAt.take(10))
    val tone = when (row.status.lowercase()) {
        "paid" -> UiTone.Success
        "refunded" -> UiTone.Information
        "void" -> UiTone.Danger
        "held" -> UiTone.Warning
        else -> UiTone.Neutral
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.invoiceNo ?: source,
                    color = Brand.Foreground,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OperationalStatusBadge(row.status.replaceFirstChar(Char::uppercase), tone)
            }
            Text(
                "$source · ${row.itemsCount} item${if (row.itemsCount == 1) "" else "s"}",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(timestamp, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
            val details = buildList {
                if (row.paymentMethods.isNotEmpty()) {
                    add(row.paymentMethods.joinToString(" + ") { it.uppercase(Locale.ENGLISH) })
                }
                if (row.pointsRedeemedMinor > 0) add("Points ${row.pointsRedeemedMinor.asRupees()}")
                if (row.refundedMinor > 0) add("Refunded ${row.refundedMinor.asRupees()}")
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    color = if (row.refundedMinor > 0) Brand.Warning else Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        NumericValue(
            value = row.totalMinor.asRupees(),
            style = MaterialTheme.typography.titleSmall,
            color = Brand.Foreground,
        )
    }
}

@Composable
private fun RankCard(customer: Customer) {
    SectionCard(
        title = "Gaming loyalty",
        subtitle = "Rank progress comes from lifetime gaming points.",
        icon = Icons.Default.Star,
        elevated = true,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RankBadge(customer.gamingRank)
            Text(
                "${customer.lifetimeGamingPointsEarned.grouped()} gaming pts lifetime",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }

        val next = customer.nextGamingRank
        if (next != null && customer.nextGamingRankFloor != null) {
            // Plain boxes rather than a progress component: the fill has to
            // read at a glance across a counter, not animate.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(Radius.shapePill)
                    .background(Brand.Border),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(customer.rankProgress)
                        .height(6.dp)
                        .clip(Radius.shapePill)
                        .background(Brand.Gold),
                )
            }
            Text(
                "${(customer.pointsToNextGamingRank ?: 0).grouped()} pts to $next",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        } else {
            Text("Top rank reached", color = Brand.Gold, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------------------------------------------ editor

@Composable
private fun EditorDialog(
    editor: CustomerEditor,
    saving: Boolean,
    error: String?,
    onChange: (CustomerEditor) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var pickingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = {
            Text(
                if (editor.isUnsyncedDraft) "New customer"
                else "Edit ${editor.name.ifBlank { editor.originalPhone }}",
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = editor.phone,
                        onValueChange = { onChange(editor.copy(phone = it.trim())) },
                        label = { Text("Phone (4–20 characters)") },
                        singleLine = true,
                        enabled = editor.isUnsyncedDraft || editor.phoneUnlocked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    if (!editor.isUnsyncedDraft && !editor.phoneUnlocked) {
                        OutlinedButton(
                            onClick = { onChange(editor.copy(phoneUnlocked = true)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Fix typo")
                        }
                    }
                }
                if (!editor.isUnsyncedDraft && editor.phoneUnlocked) {
                    Text(
                        "This number is the customer's loyalty identity. Changing it moves " +
                            "their points and visit history to the new number — it does not " +
                            "create a second customer.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Information,
                    )
                }

                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onChange(editor.copy(name = it)) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editor.email,
                    onValueChange = { onChange(editor.copy(email = it)) },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Birthday (optional)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.ForegroundMuted,
                        )
                        Text(
                            editor.birthday?.asDayLabel() ?: "Not set",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Brand.Foreground,
                        )
                    }
                    OutlinedButton(
                        onClick = { pickingDate = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Pick") }
                    if (editor.birthday != null) {
                        TextButton(
                            onClick = { onChange(editor.copy(birthday = null)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Clear")
                        }
                    }
                }

                OutlinedTextField(
                    value = editor.notes,
                    onValueChange = { onChange(editor.copy(notes = it)) },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!editor.phoneValid && editor.phone.isNotBlank()) {
                    Text(
                        "A phone number must be 4 to 20 characters.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.Danger,
                    )
                }
                if (editor.isUnsyncedDraft) {
                    Text(
                        "If this number is already on file, the existing customer is opened — " +
                            "their points are never split across two records.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                }
                if (error != null) {
                    Text(error, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            ErpButton(
                text = when {
                    saving -> "Saving…"
                    editor.isNew -> "Create"
                    else -> "Save"
                },
                onClick = onSave,
                enabled = !saving && editor.phoneValid,
                busy = saving,
            )
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !saving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel") }
        },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )

    if (pickingDate) {
        BirthdayPicker(
            initial = editor.birthday,
            onDismiss = { pickingDate = false },
            onPick = {
                pickingDate = false
                onChange(editor.copy(birthday = it))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPicker(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    // The picker works in UTC milliseconds, and so does the rest of this
    // feature — a birthday read back through IST would land on the day before.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("Use this date") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel") }
        },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = Brand.SurfaceOverlay,
        ),
    ) {
        DatePicker(state = state)
    }
}
