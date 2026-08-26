package cloud.dcompany.erp.ui.screens.customers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.auth.CustomersAccess
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
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
    val state by vm.state.collectAsState()
    SideEffect { vm.updateAccess(access) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header(
            state,
            canWrite = access.canManageCustomers,
            onRefresh = vm::retry,
            onAdd = vm::startCreate,
        )
        if (!access.canManageCustomers) ViewOnlyNotice()
        Spacer(Modifier.height(14.dp))
        Totals(state)
        Spacer(Modifier.height(14.dp))
        SearchField(
            query = state.query,
            onQuery = vm::search,
            onClear = vm::clearSearch,
        )

        state.notice?.let {
            Spacer(Modifier.height(10.dp))
            NoticeBanner(it, vm::dismissNotice)
        }

        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= 700.dp
            val selected = state.selected

            when {
                state.loading -> CentredBlock {
                    CircularProgressIndicator(color = Brand.Gold)
                    Text("Loading customers…", color = Brand.ForegroundMuted)
                }

                state.couldNotLoad -> CentredBlock {
                    Text(
                        "Could not load customers",
                        style = MaterialTheme.typography.titleLarge,
                        color = Brand.Foreground,
                    )
                    Text(
                        "No customers cached on this tablet yet, and there's no connection " +
                            "right now to fetch them.",
                        color = Brand.ForegroundMuted,
                    )
                    Button(onClick = vm::retry) { Text("Retry") }
                }

                state.rows.isEmpty() -> CentredBlock {
                    if (state.searching) {
                        Text(
                            "No customer matches “${state.query.trim()}”",
                            style = MaterialTheme.typography.titleLarge,
                            color = Brand.Foreground,
                        )
                        Text(
                            "Search matches the phone number or the name. If this is a new " +
                                "customer, add them with their phone number.",
                            color = Brand.ForegroundMuted,
                        )
                        Button(
                            onClick = vm::startCreate,
                            enabled = access.canManageCustomers,
                        ) { Text("Add customer") }
                    } else {
                        Text(
                            "No customers yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = Brand.Foreground,
                        )
                        Text(
                            "Customers are saved automatically the first time a bill is taken " +
                                "with a phone number. You can also add one here.",
                            color = Brand.ForegroundMuted,
                        )
                        Button(
                            onClick = vm::startCreate,
                            enabled = access.canManageCustomers,
                        ) { Text("Add customer") }
                    }
                }

                twoPane -> Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        CustomerList(state, vm)
                    }
                    Spacer(Modifier.width(14.dp))
                    Box(Modifier.width(360.dp).fillMaxHeight()) {
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
                    CustomerList(state, vm)
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Customers",
                style = MaterialTheme.typography.headlineMedium,
                color = Brand.Foreground,
            )
            Text(
                "Phone-based loyalty · saved automatically at checkout · " +
                    "2 points per ₹10 spent on gaming · 10 points = ₹1",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
            Button(onClick = onAdd, enabled = canWrite) { Text("Add customer") }
        }
    }
}

@Composable
private fun Totals(state: CustomersUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("Customers", state.rows.size.grouped(), Modifier.weight(1f))
        Tile("Total visits", state.totalVisits.grouped(), Modifier.weight(1f))
        Tile("Total spend", state.totalSpentMinor.asRupees(), Modifier.weight(1f))
        Tile(
            label = "Points held",
            value = state.totalPoints.grouped(),
            modifier = Modifier.weight(1f),
            // A points balance is a liability, so its rupee value is the
            // number an owner actually cares about.
            footnote = "worth ${(state.totalPoints * MINOR_PER_POINT).asRupees()}",
        )
    }
}

@Composable
private fun Tile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    Column(
        modifier
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .padding(14.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = Brand.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (footnote != null) {
            Text(footnote, style = MaterialTheme.typography.labelSmall, color = Brand.GoldMuted)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        label = { Text("Search by phone or name") },
        placeholder = { Text("e.g. 98470 or Anand") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        },
        modifier = Modifier.fillMaxWidth(0.6f),
    )
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .border(1.dp, Brand.GoldMuted, Radius.shapeSm)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun SyncFailedNotice(error: String?, canRetry: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised)
            .border(1.dp, Brand.Danger, Radius.shapeSm)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Could not sync this customer", color = Brand.Danger, fontWeight = FontWeight.SemiBold)
        Text(
            error ?: "The server refused this save.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
        )
        OutlinedButton(onClick = onRetry, enabled = canRetry) { Text("Retry") }
    }
}

// -------------------------------------------------------------------- list

@Composable
private fun CustomerList(state: CustomersUiState, vm: CustomersViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.rows, key = { it.id }) { customer ->
            CustomerRow(
                customer = customer,
                selected = customer.id == state.selectedId,
                onClick = { vm.select(customer) },
            )
        }
    }
}

@Composable
private fun CustomerRow(customer: Customer, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(if (selected) Brand.SurfaceRaised else Brand.Surface)
            .border(
                width = 1.dp,
                color = if (selected) Brand.Gold else Brand.Border,
                shape = Radius.shapeMd,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            if (customer.isRejected) {
                SyncStatusBadge("Sync failed", Brand.Danger)
            } else if (customer.isPendingSync) {
                SyncStatusBadge("Not synced", Brand.GoldMuted)
            }
            RankBadge(customer.gamingRank)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                customer.phone,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.Foreground,
            )
            Text(
                "${customer.visitCount.grouped()} visits",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
            Text(
                customer.totalSpentMinor.asRupees(),
                style = MaterialTheme.typography.labelSmall,
                color = Brand.ForegroundMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${customer.loyaltyPoints.grouped()} pts · ${customer.loyaltyValueMinor.asRupees()}",
                style = MaterialTheme.typography.labelLarge,
                color = Brand.Gold,
            )
        }
    }
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

@Composable
private fun SyncStatusBadge(label: String, colour: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = colour,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(Radius.shapePill)
            .border(1.dp, colour, Radius.shapePill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
    Column(
        Modifier
            .fillMaxSize()
            .clip(Radius.shapeLg)
            .background(Brand.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pick a customer", color = Brand.Foreground, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap a row to see their visits, spend, points balance and gaming rank.",
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
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
            .background(Brand.Surface)
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("← All customers") }
        }

        Text(
            customer.displayName,
            style = MaterialTheme.typography.headlineMedium,
            color = Brand.Foreground,
        )
        Text(
            customer.phone,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
            color = Brand.Gold,
        )

        if (customer.isRejected) {
            SyncFailedNotice(customer.rejectedError, canWrite, onRetrySync)
        } else if (customer.isPendingSync) {
            Text(
                "Not synced yet — will save the next time this tablet is online.",
                style = MaterialTheme.typography.labelSmall,
                color = Brand.GoldMuted,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tile("Visits", customer.visitCount.grouped(), Modifier.weight(1f))
            Tile("Spent", customer.totalSpentMinor.asRupees(), Modifier.weight(1f))
        }
        Tile(
            label = "Points balance",
            value = "${customer.loyaltyPoints.grouped()} pts",
            modifier = Modifier.fillMaxWidth(),
            footnote = "redeemable for ${customer.loyaltyValueMinor.asRupees()} (10 pts = ₹1)",
        )

        RankCard(customer)

        DetailLine("Last visit", customer.lastVisitLabel())
        DetailLine("Birthday", customer.birthdayLabel())
        DetailLine("Email", customer.email?.takeIf { it.isNotBlank() } ?: "—")
        DetailLine("Notes", customer.notes?.takeIf { it.isNotBlank() } ?: "—")

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onEdit,
            enabled = canWrite,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text("Edit customer")
        }
    }
}

@Composable
private fun RankCard(customer: Customer) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radius.shapeMd)
            .background(Brand.SurfaceRaised)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.ForegroundMuted,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Brand.Foreground)
    }
}

@Composable
private fun CentredBlock(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp).fillMaxWidth(0.7f),
        ) { content() }
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
                        color = Brand.Gold,
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
            Button(onClick = onSave, enabled = !saving && editor.phoneValid) {
                Text(
                    when {
                        saving -> "Saving…"
                        editor.isNew -> "Create"
                        else -> "Save"
                    }
                )
            }
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
