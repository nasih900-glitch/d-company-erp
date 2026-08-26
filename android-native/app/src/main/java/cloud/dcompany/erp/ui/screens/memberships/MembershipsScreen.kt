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
import androidx.compose.material3.Checkbox
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
import cloud.dcompany.erp.core.db.MembershipMoneyActionState
import cloud.dcompany.erp.core.db.MembershipPaymentActionKind
import cloud.dcompany.erp.core.db.MembershipPaymentTaskCacheEntity
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundActionKind
import cloud.dcompany.erp.core.db.MembershipRefundAttemptCacheEntity
import cloud.dcompany.erp.core.db.MembershipRefundTaskCacheEntity
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius

/**
 * Memberships — greenfield screen. Tier browsing is read-only (create/edit
 * stays on web, per MembershipsApi's class doc). Subscribe/cancel are real
 * offline outbox writes — see MembershipsViewModel's class doc for why.
 */
@Composable
fun MembershipsScreen(canManage: Boolean) {
    val vm: MembershipsViewModel = viewModel()
    val state by vm.state.collectAsState()
    MembershipsContent(state, vm, canManage)
}

@Composable
private fun MembershipsContent(
    state: MembershipsUiState,
    vm: MembershipsViewModel,
    canManage: Boolean,
) {
    Column(Modifier.fillMaxSize().background(Brand.Background)) {
        Header(canManage)

        if (canManage && state.notice != null) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                NoticeBanner(state.notice, vm::dismissNotice)
            }
        }
        if (
            canManage && (
                state.pendingSubscriptions.isNotEmpty() ||
                    state.pendingCancellations.isNotEmpty() ||
                    state.pendingRefunds.isNotEmpty() ||
                    state.paymentTasks.isNotEmpty() ||
                    state.paymentActions.isNotEmpty() ||
                    state.refundTasks.isNotEmpty() ||
                    state.refundActions.isNotEmpty() ||
                    state.legacyRefundAttempts.isNotEmpty()
            )
        ) {
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
                item { SelectedCustomerPanel(customer, state, vm, canManage) }
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

        when (val dialog = state.dialog.takeIf { canManage }) {
            is MembershipsDialog.SubscribeForm -> SubscribeFormDialog(dialog.customer, state, vm)
            is MembershipsDialog.ConfirmCancel -> ConfirmDialog(
                title = "Stop ${dialog.customer.name.orEmpty().ifBlank { dialog.customer.phone }}'s renewal?",
                body = "This only stops future renewal. Paid discounts and perks remain active until ${dialog.membership.subscription?.expiresAt?.take(10) ?: "the term ends"}.",
                confirmLabel = "Stop renewal",
                busy = state.busy,
                error = state.formError,
                onConfirm = {
                    val subId = dialog.membership.subscription?.id ?: return@ConfirmDialog
                    vm.cancelMembership(dialog.customer.id, subId)
                },
                onDismiss = vm::closeDialog,
            )
            is MembershipsDialog.RefundForm -> RefundFormDialog(
                dialog.customer,
                dialog.membership,
                state,
                vm,
            )
            is MembershipsDialog.ConfirmCashHandover -> ConfirmDialog(
                title = "Confirm cash was handed over",
                body = "Only confirm after the customer physically receives ${dialog.refund.amountMinor.asRupees()}. This posts the refund to the drawer and management P&L and cannot be undone by deleting a local row.",
                confirmLabel = "Cash handed over",
                busy = state.busy,
                error = state.formError,
                onConfirm = { vm.confirmCashHandover(dialog.refund.localId) },
                onDismiss = vm::closeDialog,
            )
            is MembershipsDialog.WithdrawCashRefund -> WithdrawCashRefundDialog(
                refund = dialog.refund,
                state = state,
                vm = vm,
            )
            is MembershipsDialog.CompletePayment -> CompleteMembershipPaymentDialog(
                task = dialog.task,
                state = state,
                vm = vm,
            )
            is MembershipsDialog.WithdrawPayment -> WithdrawMembershipPaymentDialog(
                task = dialog.task,
                state = state,
                vm = vm,
            )
            is MembershipsDialog.CompleteRefund -> CompleteMembershipRefundDialog(
                task = dialog.task,
                state = state,
                vm = vm,
            )
            is MembershipsDialog.ResolveRefund -> ResolveMembershipRefundDialog(
                task = dialog.task,
                state = state,
                vm = vm,
            )
            is MembershipsDialog.ResolveLegacyRefund -> ResolveLegacyMembershipRefundDialog(
                attempt = dialog.attempt,
                state = state,
                vm = vm,
            )
            null -> {}
        }
    }
}

@Composable
private fun Header(canManage: Boolean) {
    Column {
        Text(
            "Memberships",
            style = MaterialTheme.typography.headlineMedium, color = Brand.Foreground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            if (canManage) "Tiers · subscribe · refund · renewal" else "Tiers and customer membership status · read only",
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
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
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
private fun SelectedCustomerPanel(
    customer: CustomerCacheEntity,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
    canManage: Boolean,
) {
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
            membership?.pendingRefundTaskId != null -> {
                val task = state.refundTasks.firstOrNull { it.id == membership.pendingRefundTaskId }
                Text(
                    task?.let { membershipRefundStageMessage(it.status) }
                        ?: "A membership refund is unresolved. Refresh before moving any money.",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            membership?.pendingPaymentTaskId != null -> {
                val task = state.paymentTasks.firstOrNull { it.id == membership.pendingPaymentTaskId }
                Text(
                    task?.let { membershipPaymentStageMessage(it.status) }
                        ?: "A membership payment is unresolved. Refresh before taking any money.",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            membership?.pendingSubscribeLocalId != null -> {
                Text("Subscription queued — not synced yet.", color = Brand.GoldMuted, style = MaterialTheme.typography.bodyMedium)
            }
            membership?.pendingCancelLocalId != null -> {
                Text("Cancellation queued — not synced yet.", color = Brand.GoldMuted, style = MaterialTheme.typography.bodyMedium)
            }
            membership?.pendingRefundLocalId != null -> {
                val pending = state.pendingRefunds.firstOrNull {
                    it.localId == membership.pendingRefundLocalId
                }
                Text(
                    if (pending?.syncState == "accepted_cash_due") {
                        "Cash refund accepted. Hand over cash and confirm, or withdraw it if no cash was paid. This shift remains open until resolved."
                    } else {
                        "Refund queued — this shift cannot close until it is resolved."
                    },
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            membership?.subscription != null && membership.subscription.isActive -> {
                val sub = membership.subscription
                val alreadyCancelled = sub.cancelledAt != null
                Text(
                    "${sub.tierName} · ${billingCycleLabel(sub.billingCycle)}",
                    style = MaterialTheme.typography.bodyLarge, color = Brand.Foreground,
                )
                Text(
                    if (alreadyCancelled) {
                        "Cancelled — perks active until ${sub.expiresAt.take(10)}"
                    } else {
                        "Expires ${sub.expiresAt.take(10)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alreadyCancelled) Brand.GoldMuted else Brand.ForegroundMuted,
                )
                sub.paymentMethod?.let { method ->
                    Text(
                        "Paid ${sub.amountPaidMinor.asRupees()} via ${method.uppercase()}" +
                            (sub.paymentReceiptNo?.let { " · Receipt $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                }
                if (sub.paymentEvidenceTimeUntrusted) {
                    Text(
                        "Payment time needs owner reconciliation; use the receipt and independent evidence before relying on the timestamp.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!sub.paymentProviderEvidenceReconciled) {
                    Text(
                        "Provider evidence is not reconciled. Verify the provider record before refunding or reporting this payment as confirmed.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (sub.refundEvidenceTimeUntrusted) {
                    Text(
                        "Refund time needs owner reconciliation before relying on this record.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (!sub.refundProviderEvidenceReconciled || !sub.refundCustomerSpendReconciled) {
                    Text(
                        "Refund provider/LTV reconciliation is incomplete. Do not repeat the payout; open the recovery task.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (canManage && sub.autoRenew && !alreadyCancelled) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { vm.openConfirmCancel(customer, membership) }) {
                        Text("Stop renewal", color = Brand.Danger)
                    }
                }
                if (
                    canManage && sub.paymentId != null &&
                    (sub.refundId == null || sub.refundStatus == "withdrawn")
                ) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.openRefundForm(customer, membership) }) {
                        Text("Refund & end membership", color = Brand.Danger)
                    }
                } else if (canManage && sub.paymentId == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Payment not verified in the ERP. Reconcile independent evidence before refunding.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            else -> {
                Text("No active membership.", color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
                if (canManage) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.openSubscribeForm(customer) },
                        enabled = state.tiers.isNotEmpty(),
                    ) { Text("Prepare membership payment") }
                }
            }
        }
        if (state.membershipHistory.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Receipt history", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
            state.membershipHistory.forEach { history ->
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier.fillMaxWidth().clip(Radius.shapeSm)
                        .background(Brand.SurfaceRaised).padding(8.dp),
                ) {
                    Text(
                        "${history.tierName} · ${history.amountPaidMinor.asRupees()}",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        history.paymentReceiptNo?.let { "Receipt $it · ${history.paymentMethod?.uppercase().orEmpty()}" }
                            ?: "Legacy entitlement — no verified payment receipt",
                        color = if (history.paymentReceiptNo == null) Brand.GoldMuted else Brand.ForegroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    when (history.refundStatus) {
                        "settled" -> Text(
                            "Refund settled${history.refundReceiptNo?.let { " · Receipt $it" }.orEmpty()}",
                            color = Brand.Danger,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        "accepted_cash_due" -> Text(
                            "Cash refund accepted — handover still due",
                            color = Brand.GoldMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        "withdrawn" -> Text(
                            "Refund withdrawn — no cash handed over",
                            color = Brand.ForegroundMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (history.paymentEvidenceTimeUntrusted) {
                        Text(
                            "Payment time unverified",
                            color = Brand.GoldMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (!history.paymentProviderEvidenceReconciled) {
                        Text(
                            "Provider evidence pending reconciliation",
                            color = Brand.GoldMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (
                        history.refundEvidenceTimeUntrusted ||
                        !history.refundProviderEvidenceReconciled ||
                        !history.refundCustomerSpendReconciled
                    ) {
                        Text(
                            "Refund evidence or customer-spend reconciliation needs owner review",
                            color = Brand.GoldMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
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
        title = "Prepare payment for ${customer.name.orEmpty().ifBlank { customer.phone }}",
        confirmLabel = "Prepare — no money yet",
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
            "Billing cycle", "Monthly",
            listOf("monthly" to "Monthly"),
        ) { billingCycle = it }
        Text(
            "Annual plans are disabled until revenue can be recognized over the full service term.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "This first step only reserves the customer, tier, amount, rail, shift, and terminal. " +
                "It does not post a membership or authorise money movement. After preparation, " +
                "open the task and start its collection step.",
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
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

@Composable
private fun CompleteMembershipPaymentDialog(
    task: MembershipPaymentTaskCacheEntity,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    var externalReference by remember(task.id) { mutableStateOf("") }
    var takeoverReason by remember(task.id) { mutableStateOf("") }
    var confirmed by remember(task.id) { mutableStateOf(false) }
    var localError by remember(task.id) { mutableStateOf<String?>(null) }
    val isCash = task.paidVia == "cash"
    FormDialog(
        title = if (isCash) "Record cash received" else "Record completed ${task.paidVia.uppercase()} payment",
        confirmLabel = "Save payment received",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                !confirmed -> localError =
                    "Confirm only after ${task.amountMinor.asRupees()} was received exactly once."
                !isCash && externalReference.trim().length < 3 -> localError =
                    "Enter the completed provider transaction reference."
                else -> {
                    localError = null
                    vm.completePayment(task, externalReference, takeoverReason)
                }
            }
        },
    ) {
        Text(
            "${task.customerName ?: task.customerPhone} · ${task.tierName} · ${task.amountMinor.asRupees()}",
            color = Brand.Gold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (isCash) {
                "Use this only after the server-authorised collection step and after the customer has handed over the cash."
            } else {
                "Complete the provider action once, verify its success, then enter the provider reference below."
            },
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!isCash) {
            OutlinedTextField(
                value = externalReference,
                onValueChange = { externalReference = it.take(200) },
                label = { Text("Provider transaction reference") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(
                "I confirm the customer payment was received exactly once.",
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        task.actionStartedByName?.let { starter ->
            Text(
                "Collection was started by $starter. If that was another account, explain why you are taking over this physical-money action.",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = takeoverReason,
                onValueChange = { takeoverReason = it.take(500) },
                label = { Text("Takeover reason, if another owner started it") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!state.paymentActions.any {
                it.serverRequestId == task.id && it.kind == MembershipPaymentActionKind.COMPLETE
            }
        ) {
            Text(
                "If the connection drops after money moves, this confirmation is saved locally and blocks shift close until the same action reconciles.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun WithdrawMembershipPaymentDialog(
    task: MembershipPaymentTaskCacheEntity,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    val noActionStarted = task.status == MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE
    val isCash = task.paidVia == "cash"
    val options = when {
        noActionStarted -> listOf("payment_not_collected" to "No collection started")
        isCash -> listOf(
            "cash_not_collected" to "No cash changed hands",
            "cash_returned" to "Collected cash was returned",
        )
        else -> listOf(
            "provider_not_completed" to "Provider says not completed",
            "provider_reversed" to "Provider payment reversed",
        )
    }
    var resolution by remember(task.id) { mutableStateOf(options.first().first) }
    var reason by remember(task.id) { mutableStateOf("") }
    var verificationReference by remember(task.id) { mutableStateOf("") }
    var takeoverReason by remember(task.id) { mutableStateOf("") }
    var verified by remember(task.id) { mutableStateOf(false) }
    var localError by remember(task.id) { mutableStateOf<String?>(null) }
    FormDialog(
        title = if (noActionStarted) "Withdraw uncollected payment" else "Resolve interrupted payment",
        confirmLabel = "Save verified outcome",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                !verified -> localError = "Confirm the exact drawer/customer or provider outcome first."
                reason.trim().length < 3 -> localError = "Enter why this payment did not complete."
                !noActionStarted && !isCash && verificationReference.trim().length < 3 ->
                    localError = "Enter the provider verification or reversal reference."
                else -> {
                    localError = null
                    vm.withdrawPayment(
                        task = task,
                        resolution = resolution,
                        reason = reason,
                        verificationReference = verificationReference,
                        takeoverReason = takeoverReason,
                    )
                }
            }
        },
    ) {
        Text(
            "${task.customerName ?: task.customerPhone} · ${task.amountMinor.asRupees()} via ${task.paidVia.uppercase()}",
            color = Brand.Gold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (noActionStarted) {
                "No physical action is registered. Use this only when no payment was collected or approved."
            } else {
                "Do not guess. Verify the drawer and customer, or the provider terminal/account, before resolving this task. Unknown state must remain open."
            },
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        PickerField(
            label = "Verified outcome",
            selectedLabel = options.first { it.first == resolution }.second,
            options = options,
        ) { resolution = it }
        if (!noActionStarted && !isCash) {
            OutlinedTextField(
                value = verificationReference,
                onValueChange = { verificationReference = it.take(200) },
                label = {
                    Text(
                        if (resolution == "provider_reversed") {
                            "Provider reversal reference"
                        } else {
                            "Provider status verification reference"
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = verified, onCheckedChange = { verified = it })
            Text(
                when (resolution) {
                    "cash_returned" -> "I verified all collected cash was physically returned."
                    "cash_not_collected" -> "I verified no cash changed hands."
                    "provider_reversed" -> "I verified the provider reversal completed."
                    "provider_not_completed" -> "I verified the provider action never completed."
                    else -> "I verified no collection action or money movement occurred."
                },
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        task.actionStartedByName?.let { starter ->
            Text(
                "Action started by $starter. If that was another account, record the takeover reason.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = takeoverReason,
                onValueChange = { takeoverReason = it.take(500) },
                label = { Text("Takeover reason, if required") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RefundFormDialog(
    customer: CustomerCacheEntity,
    membership: MembershipRow,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    val sub = membership.subscription ?: return
    var method by remember { mutableStateOf(sub.paymentMethod ?: "upi") }
    var reason by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "Refund ${customer.name.orEmpty().ifBlank { customer.phone }}",
        confirmLabel = "Accept refund — no payout yet",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                reason.trim().length < 3 ->
                    localError = "Enter why this membership is being refunded."
                sub.paymentId == null ->
                    localError = "This membership has no verified payment ID and cannot be refunded automatically."
                else -> {
                localError = null
                vm.refundMembership(
                    customer.id,
                    sub.id,
                    sub.paymentId!!,
                    sub.amountPaidMinor,
                    method,
                    reason,
                )
                }
            }
        },
    ) {
        Text(
            "Full refund: ${sub.amountPaidMinor.asRupees()}. Benefits end after sync; the original term and payment remain in the audit trail.",
            color = Brand.Gold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "This acceptance step reserves the refund and pauses benefits. It does not hand over cash, " +
                "start a provider refund, change the drawer, or post accounting. Start the separate payout " +
                "step only after the server confirms acceptance.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        PickerField(
            "Refund via",
            method.uppercase(),
            listOf("cash" to "Cash", "card" to "Card", "upi" to "UPI", "razorpay" to "Razorpay"),
        ) { method = it }
        Text(
            "Do not hand over cash or start the provider reversal from this dialog.",
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CompleteMembershipRefundDialog(
    task: MembershipRefundTaskCacheEntity,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    var providerReference by remember(task.id) { mutableStateOf("") }
    var takeoverReason by remember(task.id) { mutableStateOf("") }
    var confirmed by remember(task.id) { mutableStateOf(false) }
    var localError by remember(task.id) { mutableStateOf<String?>(null) }
    val cash = task.method == "cash"
    FormDialog(
        title = if (cash) "Record cash handed over" else "Record completed ${task.method.uppercase()} refund",
        confirmLabel = "Save payout completed",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                !confirmed -> localError =
                    "Confirm only after ${task.amountMinor.asRupees()} was paid to the customer exactly once."
                !cash && providerReference.trim().length < 3 -> localError =
                    "Enter the completed provider refund reference."
                else -> {
                    localError = null
                    vm.completeRefund(task, providerReference, takeoverReason)
                }
            }
        },
    ) {
        Text(
            "${task.customerName ?: task.customerPhone ?: "Customer"} · ${task.amountMinor.asRupees()} via ${task.method.uppercase()}",
            color = Brand.Gold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (cash) {
                "Use this only after the server-authorised cash handover step and after the customer physically receives the notes."
            } else {
                "Complete the authorised provider refund once, verify success, and retain its unique reference."
            },
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!cash) {
            OutlinedTextField(
                value = providerReference,
                onValueChange = { providerReference = it.take(200) },
                label = { Text("Provider refund reference") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(
                if (cash) {
                    "I confirm the customer received this cash exactly once."
                } else {
                    "I confirm the provider refund completed exactly once."
                },
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        task.actionStartedByName?.let { starter ->
            Text(
                "Payout was started by $starter. If that was another account, explain why you are taking over.",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = takeoverReason,
                onValueChange = { takeoverReason = it.take(500) },
                label = { Text("Takeover reason, if required") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "If the connection drops after payout, this evidence remains on the tablet and blocks shift close. Never pay again to clear a pending task.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ResolveMembershipRefundDialog(
    task: MembershipRefundTaskCacheEntity,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    val cash = task.method == "cash"
    val noAction = task.status in setOf(
        MembershipRefundTaskStatus.ACCEPTED_CASH_DUE,
        MembershipRefundTaskStatus.ACCEPTED_PROVIDER_DUE,
    )
    val options = when {
        noAction && cash -> listOf("cash_not_handed_over" to "No cash handover started")
        noAction -> listOf("provider_not_completed" to "No provider refund started")
        cash -> listOf(
            "cash_not_handed_over" to "No cash left the drawer",
            "cash_returned" to "Paid cash was recovered from customer",
        )
        else -> listOf(
            "provider_not_completed" to "Provider says not completed",
            "provider_reversed" to "Provider refund was reversed",
        )
    }
    var resolution by remember(task.id) { mutableStateOf(options.first().first) }
    var reason by remember(task.id) { mutableStateOf("") }
    var verificationReference by remember(task.id) { mutableStateOf("") }
    var takeoverReason by remember(task.id) { mutableStateOf("") }
    var verified by remember(task.id) { mutableStateOf(false) }
    var localError by remember(task.id) { mutableStateOf<String?>(null) }
    FormDialog(
        title = if (noAction) "Withdraw unstarted refund" else "Resolve interrupted payout",
        confirmLabel = "Save verified outcome",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                !verified -> localError = "Verify the exact drawer/customer or provider outcome first."
                reason.trim().length < 3 -> localError = "Enter why this payout did not complete."
                !noAction && !cash && verificationReference.trim().length < 3 ->
                    localError = "Enter the provider verification or reversal reference."
                else -> {
                    localError = null
                    vm.resolveRefund(task, resolution, reason, verificationReference, takeoverReason)
                }
            }
        },
    ) {
        Text(
            if (noAction) {
                "No payout action is registered. Confirm only that nothing was started or paid."
            } else {
                "Unknown state must remain unresolved. Check the drawer and customer or the provider account before selecting an outcome."
            },
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        PickerField(
            "Verified outcome",
            options.first { it.first == resolution }.second,
            options,
        ) { resolution = it }
        if (!noAction && !cash) {
            OutlinedTextField(
                value = verificationReference,
                onValueChange = { verificationReference = it.take(200) },
                label = { Text("Provider verification / reversal reference") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = verified, onCheckedChange = { verified = it })
            Text(
                when (resolution) {
                    "cash_returned" -> "I verified all paid cash was physically recovered."
                    "cash_not_handed_over" -> "I verified no cash left the drawer."
                    "provider_reversed" -> "I verified the provider refund reversal completed."
                    else -> "I verified the provider refund never completed."
                },
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        task.actionStartedByName?.let { starter ->
            Text(
                "Payout action started by $starter. If that was another account, record the takeover reason.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedTextField(
                value = takeoverReason,
                onValueChange = { takeoverReason = it.take(500) },
                label = { Text("Takeover reason, if required") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResolveLegacyMembershipRefundDialog(
    attempt: MembershipRefundAttemptCacheEntity,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    val cash = attempt.paidVia == "cash"
    val options = if (cash) {
        listOf(
            "cash_not_handed_over" to "No cash was handed over",
            "cash_handed_over" to "Cash was handed over",
        )
    } else {
        listOf(
            "no_payout" to "Provider payout never completed",
            "provider_reversed" to "Provider payout was reversed",
            "provider_completed" to "Provider payout completed",
        )
    }
    var outcome by remember(attempt.id) { mutableStateOf(options.first().first) }
    var reason by remember(attempt.id) { mutableStateOf("") }
    var reference by remember(attempt.id) { mutableStateOf("") }
    var confirmed by remember(attempt.id) { mutableStateOf(false) }
    var localError by remember(attempt.id) { mutableStateOf<String?>(null) }
    FormDialog(
        title = "Resolve older-app refund",
        confirmLabel = "Save audited recovery",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            when {
                !confirmed -> localError = "Verify the original drawer/provider evidence first."
                reason.trim().length < 3 -> localError = "Enter the recovery reason."
                !cash && reference.trim().length < 3 -> localError =
                    "Enter the provider verification reference."
                else -> {
                    localError = null
                    vm.resolveLegacyRefund(attempt, outcome, reason, reference)
                }
            }
        },
    ) {
        Text(
            "This task came from an older app that could move value before the server reserved it. Never repeat a payout to make it disappear.",
            color = Brand.Danger,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${attempt.expectedAmountMinor.asRupees()} via ${attempt.paidVia.uppercase()} · captured ${if (attempt.capturedTimeUntrusted) "at an untrusted device time" else "with saved time evidence"}",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        PickerField("Verified outcome", options.first { it.first == outcome }.second, options) {
            outcome = it
        }
        if (!cash) {
            OutlinedTextField(
                value = reference,
                onValueChange = { reference = it.take(200) },
                label = { Text("Provider verification reference") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Evidence and reason") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(
                "I verified this outcome from the drawer/customer or provider record.",
                color = Brand.Foreground,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (outcome in setOf("cash_handed_over", "provider_completed")) {
            Text(
                "A currently open shift on this same terminal will be used as the reconciliation shift for the historical payout.",
                color = Brand.GoldMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun WithdrawCashRefundDialog(
    refund: PendingMembershipRefundRow,
    state: MembershipsUiState,
    vm: MembershipsViewModel,
) {
    var reason by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "No cash handed over",
        confirmLabel = "Withdraw unpaid refund",
        busy = state.busy,
        error = localError ?: state.formError,
        onDismiss = vm::closeDialog,
        onConfirm = {
            if (reason.trim().length < 3) {
                localError = "Enter why the customer did not receive cash."
            } else {
                vm.withdrawCashRefund(refund.localId, reason)
            }
        },
    ) {
        Text(
            "Use this only if the customer received no cash. The ERP will retain an audit record, release the shift, and restore unexpired benefits when no newer term overlaps.",
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(500) },
            label = { Text("Why no cash was handed over") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ============================================================================
// PENDING CHANGES
// ============================================================================
@Composable
private fun PendingMembershipChangesPanel(state: MembershipsUiState, vm: MembershipsViewModel) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeMd)
            .background(Brand.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pending membership changes", style = MaterialTheme.typography.labelLarge, color = Brand.Foreground)
        state.refundTasks.forEach { task ->
            val legacyBlock = state.refundActions.firstOrNull { action ->
                action.serverRefundId == task.id &&
                    action.kind == MembershipRefundActionKind.LEGACY_RECONCILE_SERVER &&
                    action.state != MembershipMoneyActionState.SYNCED
            }
            MembershipRefundTaskCard(
                task = task,
                vm = vm,
                legacyRecoveryMessage = legacyBlock?.lastError
                    ?: legacyBlock?.let {
                        "Older-app payout evidence is still being reconciled. Do not move money or withdraw this task as unpaid."
                    },
            )
        }
        state.legacyRefundAttempts.forEach { attempt ->
            Column(
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
                    .background(Brand.SurfaceRaised).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Older-app refund recovery · ${attempt.expectedAmountMinor.asRupees()} via ${attempt.paidVia.uppercase()}",
                    color = Brand.Danger,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Do not repeat this payout. Verify the original drawer/customer or provider evidence.",
                    color = Brand.GoldMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Button(onClick = { vm.openLegacyRefundResolution(attempt) }) {
                    Text("Resolve verified evidence")
                }
            }
        }
        state.refundActions.forEach { action ->
            val recovery = action.state in setOf(
                MembershipMoneyActionState.LEGACY_RECOVERY_REQUIRED,
                MembershipMoneyActionState.LEGACY_PROVENANCE_MISSING,
            )
            MembershipPendingRow(
                text = when (action.kind) {
                    MembershipRefundActionKind.ACCEPT -> "Accepting membership refund"
                    MembershipRefundActionKind.BEGIN_CASH -> "Opening cash handover"
                    MembershipRefundActionKind.BEGIN_PROVIDER -> "Opening provider refund"
                    MembershipRefundActionKind.COMPLETE_CASH,
                    MembershipRefundActionKind.COMPLETE_PROVIDER,
                    -> "Completed refund evidence"
                    MembershipRefundActionKind.FINALIZE -> "Posting refund receipt"
                    MembershipRefundActionKind.WITHDRAW -> "Resolving refund task"
                    MembershipRefundActionKind.LEGACY_REGISTER -> "Registering older-app refund recovery"
                    MembershipRefundActionKind.LEGACY_RECONCILE_SERVER -> "Adopting older server refund"
                    MembershipRefundActionKind.LEGACY_RESOLVE -> "Resolving older-app refund evidence"
                    else -> "Membership refund action"
                },
                rejected = action.state in setOf(
                    MembershipMoneyActionState.REJECTED,
                    MembershipMoneyActionState.AMBIGUOUS,
                ),
                error = action.lastError,
                onRetry = { vm.retryRefundAction(action.actionId) },
                retryEnabled = !recovery,
                pendingText = when {
                    recovery -> action.lastError
                        ?: "Protected recovery required. Do not repeat a payout."
                    action.state == MembershipMoneyActionState.AMBIGUOUS ->
                        "Server outcome is unknown. Do not repeat the payout; retry only this saved action."
                    else -> "Saved locally — waiting for exact server reconciliation"
                },
            )
        }
        state.paymentTasks.forEach { task ->
            MembershipPaymentTaskCard(task, vm)
        }
        state.paymentActions.forEach { action ->
            val recovery = action.state in setOf(
                MembershipMoneyActionState.LEGACY_RECOVERY_REQUIRED,
                MembershipMoneyActionState.LEGACY_PROVENANCE_MISSING,
            )
            MembershipPendingRow(
                text = when (action.kind) {
                    MembershipPaymentActionKind.PREPARE -> "Preparing membership payment"
                    MembershipPaymentActionKind.BEGIN_CASH -> "Opening cash collection"
                    MembershipPaymentActionKind.BEGIN_PROVIDER -> "Opening provider payment"
                    MembershipPaymentActionKind.COMPLETE -> "Completed payment evidence"
                    MembershipPaymentActionKind.FINALIZE -> "Posting membership receipt"
                    MembershipPaymentActionKind.WITHDRAW -> "Resolving membership payment"
                    MembershipPaymentActionKind.LEGACY_PROBE -> "Legacy payment attempt"
                    MembershipPaymentActionKind.LEGACY_RESOLVE -> "Legacy payment recovery"
                    else -> "Membership payment action"
                },
                rejected = action.state in setOf(
                    MembershipMoneyActionState.REJECTED,
                    MembershipMoneyActionState.AMBIGUOUS,
                ),
                error = action.lastError,
                onRetry = { vm.retryPaymentAction(action.actionId) },
                retryEnabled = !recovery,
                pendingText = when {
                    recovery -> action.lastError
                        ?: "Owner recovery required. Verify original drawer/provider evidence; never collect again to clear this row."
                    action.state == MembershipMoneyActionState.AMBIGUOUS ->
                        "Server outcome is unknown. Do not repeat money movement; retry only this saved action."
                    else -> "Saved locally — waiting for exact server reconciliation"
                },
            )
        }
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
        state.pendingRefunds.forEach { row ->
            if (row.syncState == "accepted_cash_due") {
                Column(
                    Modifier.fillMaxWidth().clip(Radius.shapeSm)
                        .background(Brand.SurfaceRaised).padding(8.dp),
                ) {
                    Text(
                        "Cash refund accepted: ${row.customerName} — ${row.amountMinor.asRupees()}",
                        color = Brand.Foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "No accounting entry has posted yet. Choose exactly what happened next.",
                        color = Brand.GoldMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.openCashHandover(row) }) {
                            Text("Cash handed over")
                        }
                        OutlinedButton(onClick = { vm.openRefundWithdrawal(row) }) {
                            Text("No cash paid")
                        }
                    }
                }
            } else {
                MembershipPendingRow(
                    text = "Refund ${row.customerName} — ${row.amountMinor.asRupees()}",
                    rejected = row.rejected,
                    error = row.error,
                    onRetry = { vm.retryRefund(row.localId) },
                    pendingText = when (row.syncState) {
                        "cash_settle_pending" -> "Cash handed over — settlement waiting to sync"
                        "withdrawal_pending" -> "Unpaid-refund withdrawal waiting to sync"
                        else -> "Not synced yet"
                    },
                )
            }
        }
    }
}

@Composable
private fun MembershipPendingRow(
    text: String,
    rejected: Boolean,
    error: String?,
    onRetry: () -> Unit,
    retryEnabled: Boolean = true,
    pendingText: String = "Not synced yet",
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised).padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Brand.Foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (rejected && retryEnabled) TextButton(onClick = onRetry) { Text("Retry") }
        }
        Text(
            if (rejected) "Could not sync: ${error ?: "unknown error"}" else pendingText,
            color = if (rejected) Brand.Danger else Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MembershipPaymentTaskCard(
    task: MembershipPaymentTaskCacheEntity,
    vm: MembershipsViewModel,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${task.customerName ?: task.customerPhone} · ${task.tierName}",
            color = Brand.Foreground,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${task.amountMinor.asRupees()} via ${task.paidVia.uppercase()} · prepared by ${task.preparedByName ?: "owner"}",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            membershipPaymentStageMessage(task.status),
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        when (task.status) {
            MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.beginPayment(task) }) {
                    Text(if (task.paidVia == "cash") "Start cash collection" else "Start ${task.paidVia.uppercase()}")
                }
                OutlinedButton(onClick = { vm.openPaymentWithdrawal(task) }) {
                    Text("No payment taken")
                }
            }
            MembershipPaymentTaskStatus.CASH_COLLECTION_IN_PROGRESS,
            MembershipPaymentTaskStatus.PROVIDER_ACTION_IN_PROGRESS,
            -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openCompletePayment(task) }) {
                    Text(if (task.paidVia == "cash") "Cash received" else "Provider completed")
                }
                OutlinedButton(onClick = { vm.openPaymentWithdrawal(task) }) {
                    Text("Resolve interruption")
                }
            }
            MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING -> Text(
                "Receipt posting is automatic. Keep this task open and do not collect again.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MembershipRefundTaskCard(
    task: MembershipRefundTaskCacheEntity,
    vm: MembershipsViewModel,
    legacyRecoveryMessage: String?,
) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${task.customerName ?: task.customerPhone ?: "Customer"} · ${task.tierName ?: "Membership"}",
            color = Brand.Foreground,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${task.amountMinor.asRupees()} via ${task.method.uppercase()} · accepted by ${task.acceptedByName ?: "owner"}",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            membershipRefundStageMessage(task.status),
            color = Brand.GoldMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (!task.customerSpendReconciled || !task.providerEvidenceReconciled || task.evidenceTimeUntrusted) {
            Text(
                "Evidence/LTV reconciliation is incomplete. Keep this task open; never repeat a payout.",
                color = Brand.Danger,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (legacyRecoveryMessage != null) {
            Text(
                legacyRecoveryMessage,
                color = Brand.Danger,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Normal payout controls are locked until a protected owner verifies the original drawer/provider evidence.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            return@Column
        }
        when (task.status) {
            MembershipRefundTaskStatus.ACCEPTED_CASH_DUE,
            MembershipRefundTaskStatus.ACCEPTED_PROVIDER_DUE,
            -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.beginRefund(task) }) {
                    Text(if (task.method == "cash") "Start cash handover" else "Start ${task.method.uppercase()} refund")
                }
                OutlinedButton(onClick = { vm.openRefundResolution(task) }) {
                    Text("No payout started")
                }
            }
            MembershipRefundTaskStatus.CASH_HANDOFF_IN_PROGRESS,
            MembershipRefundTaskStatus.PROVIDER_ACTION_IN_PROGRESS,
            -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openCompleteRefund(task) }) {
                    Text(if (task.method == "cash") "Cash handed over" else "Provider refund completed")
                }
                OutlinedButton(onClick = { vm.openRefundResolution(task) }) {
                    Text("Resolve interruption")
                }
            }
            MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING -> Text(
                "Receipt posting is automatic. Keep this task open and do not pay again.",
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm).background(Brand.SurfaceRaised)
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
        modifier = modifier.fillMaxWidth().clip(Radius.shapeLg).background(Brand.Surface).padding(14.dp),
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
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
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
                Modifier.fillMaxWidth().clip(Radius.shapeSm)
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
