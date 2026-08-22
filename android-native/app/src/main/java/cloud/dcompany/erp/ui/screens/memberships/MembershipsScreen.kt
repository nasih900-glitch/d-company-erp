package cloud.dcompany.erp.ui.screens.memberships

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand

/**
 * Memberships — greenfield screen. Tier browsing is read-only (create/edit
 * stays on web, per MembershipsApi's class doc). Subscribe/cancel are real
 * offline outbox writes — see MembershipsViewModel's class doc for why.
 */
@Composable
fun MembershipsScreen() {
    val vm: MembershipsViewModel = viewModel()
    val state by vm.state.collectAsState()
    MembershipsContent(state, vm)
}

@Composable
private fun MembershipsContent(state: MembershipsUiState, vm: MembershipsViewModel) {
    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Header()

        if (state.notice != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NoticeBanner(state.notice, vm::dismissNotice)
            }
        }
        if (state.pendingSubscriptions.isNotEmpty() || state.pendingCancellations.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                PendingMembershipChangesPanel(state, vm)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { CustomerSearchPanel(state, vm) }

            state.selectedCustomer?.let { customer ->
                item { SelectedCustomerPanel(customer, state, vm) }
            }

            item { SectionTitle("Tiers") }
            if (state.tiers.isEmpty()) {
                item {
                    Text(
                        "No tiers configured yet — add one from the web app's Settings → Memberships.",
                        color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.tiers.sortedBy { it.sortOrder }, key = { it.id }) { tier -> TierCard(tier) }
            }
        }

        when (val dialog = state.dialog) {
            is MembershipsDialog.SubscribeForm -> SubscribeFormDialog(dialog.customer, state, vm)
            is MembershipsDialog.ConfirmCancel -> ConfirmDialog(
                title = "Cancel ${dialog.customer.name.orEmpty().ifBlank { dialog.customer.phone }}'s membership?",
                body = "They'll lose tier discounts and perks immediately once this syncs.",
                confirmLabel = "Cancel membership",
                busy = state.busy,
                error = state.formError,
                onConfirm = {
                    val subId = dialog.membership.subscription?.id ?: return@ConfirmDialog
                    vm.cancelMembership(dialog.customer.id, subId)
                },
                onDismiss = vm::closeDialog,
            )
            null -> {}
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            "Memberships",
            style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            "Tiers · subscribe · cancel",
            style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
    }
}

// ============================================================================
// CUSTOMER SEARCH + SELECTED CUSTOMER
// ============================================================================
@Composable
private fun CustomerSearchPanel(state: MembershipsUiState, vm: MembershipsViewModel) {
    Panel {
        Text("Find a customer", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.search, onValueChange = vm::setSearch,
            label = { Text("Name or phone") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.search.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            if (state.customers.isEmpty()) {
                Text("No matching customers.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.customers.forEach { customer -> CustomerResultRow(customer, vm) }
                }
            }
        }
    }
}

@Composable
private fun CustomerResultRow(customer: CustomerCacheEntity, vm: MembershipsViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Brand.SurfaceRaised)
            .clickable { vm.selectCustomer(customer) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            customer.name.orEmpty().ifBlank { "(no name)" },
            color = Brand.Foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(customer.phone, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectedCustomerPanel(customer: CustomerCacheEntity, state: MembershipsUiState, vm: MembershipsViewModel) {
    val membership = state.selectedMembership
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(customer.name.orEmpty().ifBlank { "(no name)" }, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Text(customer.phone, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
            }
            TextButton(onClick = vm::clearSelection) { Text("Change") }
        }
        Spacer(Modifier.height(10.dp))
        when {
            membership?.pendingSubscribeLocalId != null -> {
                Text("Subscription queued — not synced yet.", color = Brand.GoldMuted, style = MaterialTheme.typography.bodyMedium)
            }
            membership?.pendingCancelLocalId != null -> {
                Text("Cancellation queued — not synced yet.", color = Brand.GoldMuted, style = MaterialTheme.typography.bodyMedium)
            }
            membership?.subscription != null && membership.subscription.isActive -> {
                val sub = membership.subscription
                Text(
                    "${sub.tierName} · ${billingCycleLabel(sub.billingCycle)}",
                    style = MaterialTheme.typography.bodyLarge, color = Brand.Foreground,
                )
                Text(
                    "Expires ${sub.expiresAt.take(10)}",
                    style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.openConfirmCancel(customer, membership) }) {
                    Text("Cancel membership", color = Brand.Danger)
                }
            }
            else -> {
                Text("No active membership.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.openSubscribeForm(customer) },
                    enabled = state.tiers.isNotEmpty(),
                ) { Text("Subscribe") }
            }
        }
    }
}

// ============================================================================
// TIER LIST
// ============================================================================
@Composable
private fun TierCard(tier: MembershipTier) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(tier.name, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                tier.description?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(tier.monthlyPriceMinor.asRupees() + " /mo", style = MaterialTheme.typography.bodyLarge, color = Brand.Gold)
                tier.annualPriceMinor?.let {
                    Text(it.asRupees() + " /yr", style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val perks = buildList {
            if (tier.foodDiscountPct > 0) add("Food ${tier.foodDiscountPct.asDiscountPct()} off")
            if (tier.gamingDiscountPct > 0) add("Gaming ${tier.gamingDiscountPct.asDiscountPct()} off")
            if (tier.hookahDiscountPct > 0) add("Hookah ${tier.hookahDiscountPct.asDiscountPct()} off")
            if (tier.pointMultiplier > 1.0) add("${tier.pointMultiplier}x points")
            if (tier.freeGamingMinutesPerWeek > 0) add("${tier.freeGamingMinutesPerWeek} free gaming min/wk")
            if (tier.freeHookahPerMonth > 0) add("${tier.freeHookahPerMonth} free hookah/mo")
            if (tier.priorityBooking) add("Priority booking")
        }
        if (perks.isNotEmpty()) {
            Text(perks.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        }
    }
}

// ============================================================================
// SUBSCRIBE DIALOG
// ============================================================================
@Composable
private fun SubscribeFormDialog(customer: CustomerCacheEntity, state: MembershipsUiState, vm: MembershipsViewModel) {
    var tierId by remember { mutableStateOf(state.tiers.firstOrNull()?.id) }
    var billingCycle by remember { mutableStateOf("monthly") }
    var paidVia by remember { mutableStateOf("cash") }
    var localError by remember { mutableStateOf<String?>(null) }

    val selectedTier = state.tiers.firstOrNull { it.id == tierId }

    FormDialog(
        title = "Subscribe ${customer.name.orEmpty().ifBlank { customer.phone }}",
        confirmLabel = "Queue subscription",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                tierId == null -> localError = "Pick a tier."
                billingCycle == "annual" && selectedTier?.annualPriceMinor == null ->
                    localError = "This tier has no annual price — pick monthly instead."
                else -> {
                    localError = null
                    vm.subscribe(customer.id, tierId!!, billingCycle, paidVia)
                }
            }
        },
    ) {
        PickerField(
            "Tier", selectedTier?.name ?: "Select…",
            state.tiers.map { it.id to it.name },
        ) { tierId = it }
        PickerField(
            "Billing cycle", billingCycleLabel(billingCycle),
            listOfNotNull(
                "monthly" to "Monthly",
                if (selectedTier?.annualPriceMinor != null) "annual" to "Annual" else null,
            ),
        ) { billingCycle = it }
        PickerField(
            "Paid via", paidVia.replaceFirstChar { it.uppercase() },
            listOf("cash" to "Cash", "card" to "Card", "upi" to "UPI"),
        ) { paidVia = it }
        selectedTier?.let { tier ->
            val price = if (billingCycle == "annual") tier.annualPriceMinor else tier.monthlyPriceMinor
            price?.let {
                Text(
                    "Amount: ${it.asRupees()}",
                    style = MaterialTheme.typography.bodyMedium, color = Brand.Gold,
                )
            }
        }
    }
}

// ============================================================================
// PENDING CHANGES
// ============================================================================
@Composable
private fun PendingMembershipChangesPanel(state: MembershipsUiState, vm: MembershipsViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brand.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pending membership changes", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        state.pendingSubscriptions.forEach { row ->
            MembershipPendingRow(
                text = "Subscribe ${row.customerName} — ${row.tierName}",
                rejected = row.rejected, error = row.error,
                onRetry = { vm.retrySubscription(row.localId) },
            )
        }
        state.pendingCancellations.forEach { row ->
            MembershipPendingRow(
                text = "Cancel ${row.customerName}'s membership",
                rejected = row.rejected, error = row.error,
                onRetry = { vm.retryCancellation(row.localId) },
            )
        }
    }
}

@Composable
private fun MembershipPendingRow(text: String, rejected: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Brand.SurfaceRaised).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (rejected) TextButton(onClick = onRetry) { Text("Retry") }
        }
        Text(
            if (rejected) "Could not sync: ${error ?: "unknown error"}" else "Not synced yet",
            color = if (rejected) Brand.Danger else Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brand.SurfaceRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

// ============================================================================
// SHARED PIECES — local copies, same shape as Inventory/Finance/Events'
// own copies of these primitives (see EventsScreen's class doc).
// ============================================================================
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Surface).padding(14.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = Brand.ForegroundMuted)
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Danger),
            ) { Text(confirmLabel, color = Brand.Foreground) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.width(480.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Working…" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun PickerField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Brand.SurfaceRaised)
                    .clickable(enabled = options.isNotEmpty()) { open = true }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLabel,
                    color = if (options.isEmpty()) Brand.ForegroundMuted else Brand.Foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("▾", color = Brand.Gold)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(id) })
                }
            }
        }
    }
}
