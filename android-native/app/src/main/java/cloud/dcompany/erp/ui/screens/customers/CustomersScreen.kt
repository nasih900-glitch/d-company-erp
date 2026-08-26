package cloud.dcompany.erp.ui.screens.customers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.EventRepeat
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.OperationalStatusBadge
import cloud.dcompany.erp.ui.components.PageHeader
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
    SideEffect { vm.updateAccess(access) }

    Column(
        Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Header(
            state,
            canWrite = access.canManageCustomers,
            onRefresh = vm::retry,
            onAdd = vm::startCreate,
        )
        if (!access.canManageCustomers) ViewOnlyNotice()
        Totals(state)
        ActionBar(
            leading = {
                SearchInput(
                    value = state.query,
                    onValueChange = vm::search,
                    placeholder = "Search customers by phone or name",
                    modifier = Modifier.weight(1f),
                )
            },
            trailing = if (state.query.isNotBlank()) {
                {
                    ErpButton("Clear", vm::clearSearch, intent = ActionIntent.Quiet)
                }
            } else null,
        )

        state.notice?.let {
            NoticeBanner(it, vm::dismissNotice)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val twoPane = maxWidth >= 760.dp
            val selected = state.selected

            when {
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

                twoPane -> Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CustomerResultsPanel(state, vm, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.widthIn(min = 340.dp, max = 400.dp).fillMaxHeight()) {
                        if (selected == null) {
                            DetailPlaceholder()
                        } else {
                            DetailPane(
                                selected,
                                canWrite = access.canManageCustomers,
                                onEdit = { vm.startEdit(selected) },
                                onBack = null,
                                onRetrySync = { vm.retrySync(selected) },
                            )
                        }
                    }
                }

                selected != null -> DetailPane(
                    customer = selected,
                    canWrite = access.canManageCustomers,
                    onEdit = { vm.startEdit(selected) },
                    onBack = vm::clearSelection,
                    onRetrySync = { vm.retrySync(selected) },
                )

                else -> Column(Modifier.fillMaxSize()) {
                    CustomerResultsPanel(state, vm, Modifier.fillMaxSize())
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

// ------------------------------------------------------------------ header

@Composable
private fun Header(
    state: CustomersUiState,
    canWrite: Boolean,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
) {
    PageHeader(
        title = "Customers",
        subtitle = "Search profiles, review visits and manage phone-based loyalty details.",
        eyebrow = "Loyalty & relationships",
        actions = {
            ErpButton(
                text = if (state.syncing) "Refreshing…" else "Refresh",
                onClick = onRefresh,
                intent = ActionIntent.Secondary,
                enabled = !state.loading && !state.syncing,
                busy = state.syncing,
                leadingIcon = Icons.Default.Refresh,
            )
            ErpButton(
                text = "Add customer",
                onClick = onAdd,
                enabled = canWrite,
                leadingIcon = Icons.Default.PersonAdd,
            )
        },
    )
}

@Composable
private fun Totals(state: CustomersUiState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cards: @Composable RowScope.() -> Unit = {
            CompactStatCard(
                label = if (state.searching) "Matches" else "Customers",
                value = state.rows.size.grouped(),
                detail = if (state.searching) "Current search" else "Saved profiles",
                icon = Icons.Default.People,
                tone = UiTone.Information,
                modifier = Modifier.weight(1f),
            )
            CompactStatCard(
                label = "Total visits",
                value = state.totalVisits.grouped(),
                detail = "Current result set",
                icon = Icons.Default.EventRepeat,
                tone = UiTone.Success,
                modifier = Modifier.weight(1f),
            )
            CompactStatCard(
                label = "Total spend",
                value = state.totalSpentMinor.asRupees(),
                detail = "Current result set",
                icon = Icons.Default.Payments,
                tone = UiTone.Brand,
                modifier = Modifier.weight(1f),
            )
            CompactStatCard(
                label = "Points held",
                value = state.totalPoints.grouped(),
                detail = "Worth ${(state.totalPoints * MINOR_PER_POINT).asRupees()}",
                icon = Icons.Default.Star,
                tone = UiTone.Warning,
                modifier = Modifier.weight(1f),
            )
        }
        if (maxWidth >= 760.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = cards)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CompactStatCard(
                        label = if (state.searching) "Matches" else "Customers",
                        value = state.rows.size.grouped(),
                        detail = if (state.searching) "Current search" else "Saved profiles",
                        icon = Icons.Default.People,
                        tone = UiTone.Information,
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatCard(
                        label = "Total visits",
                        value = state.totalVisits.grouped(),
                        detail = "Current result set",
                        icon = Icons.Default.EventRepeat,
                        tone = UiTone.Success,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CompactStatCard(
                        label = "Total spend",
                        value = state.totalSpentMinor.asRupees(),
                        detail = "Current result set",
                        icon = Icons.Default.Payments,
                        tone = UiTone.Brand,
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatCard(
                        label = "Points held",
                        value = state.totalPoints.grouped(),
                        detail = "Worth ${(state.totalPoints * MINOR_PER_POINT).asRupees()}",
                        icon = Icons.Default.Star,
                        tone = UiTone.Warning,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
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
private fun CustomerResultsPanel(
    state: CustomersUiState,
    vm: CustomersViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Customer results", color = Brand.Foreground, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.searching) {
                        "${state.rows.size} match${if (state.rows.size == 1) "" else "es"} for “${state.query.trim()}”"
                    } else {
                        "${state.rows.size} saved profile${if (state.rows.size == 1) "" else "s"}"
                    },
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (state.syncing) {
                OperationalStatusBadge("Refreshing", UiTone.Information)
            }
        }
        PanelDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.rows, key = { it.id }) { customer ->
                CustomerRow(
                    customer = customer,
                    selected = customer.id == state.selectedId,
                    onClick = { vm.select(customer) },
                )
                if (customer.id != state.rows.lastOrNull()?.id) PanelDivider()
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
                customer.phone,
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
                    color = Brand.Gold,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${customer.visitCount.grouped()} visits · ${customer.totalSpentMinor.asRupees()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.ForegroundMuted,
                )
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
private fun DetailPlaceholder() {
    SectionCard(Modifier.fillMaxSize()) {
        DesignedEmptyState(
            title = "Select a customer",
            body = "Tap a result to review visits, spend, loyalty balance and contact details.",
            icon = Icons.Default.AccountCircle,
        )
    }
}

@Composable
private fun DetailPane(
    customer: Customer,
    canWrite: Boolean,
    onEdit: () -> Unit,
    onBack: (() -> Unit)?,
    onRetrySync: () -> Unit,
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
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = editor.phone,
                        onValueChange = { onChange(editor.copy(phone = it.trim())) },
                        label = { Text("Phone (10+ digits, +country if needed)") },
                        singleLine = true,
                        enabled = editor.isUnsyncedDraft || editor.phoneUnlocked,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                    if (!editor.isUnsyncedDraft && !editor.phoneUnlocked) {
                        OutlinedButton(onClick = { onChange(editor.copy(phoneUnlocked = true)) }) {
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
                    OutlinedButton(onClick = { pickingDate = true }) { Text("Pick") }
                    if (editor.birthday != null) {
                        TextButton(onClick = { onChange(editor.copy(birthday = null)) }) {
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
            TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = Brand.SurfaceOverlay,
        ),
    ) {
        DatePicker(state = state)
    }
}
