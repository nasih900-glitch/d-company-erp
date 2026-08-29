package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.DataListRow
import cloud.dcompany.erp.ui.components.DesignedEmptyState
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.InfoRow
import cloud.dcompany.erp.ui.components.LoadingSkeleton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.PanelDivider
import cloud.dcompany.erp.ui.components.PremiumTabBar
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.TabOption
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.components.fieldColors
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
fun SettingsScreen(
    canManageSystem: Boolean,
    onPasswordChanged: () -> Unit = {},
    onReportProblem: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
    presentation: WorkspacePresentationPolicy = WorkspaceFeatureProfiles.Active.presentationPolicy(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.passwordChanged) {
        if (state.passwordChanged) onPasswordChanged()
    }
    LaunchedEffect(canManageSystem, presentation.singleHybridTerminalOnly) {
        // The Gaming Centre profile intentionally has one server-managed
        // Hybrid workspace. Other profiles load terminal administration only
        // when they support the advanced multi-terminal workflow.
        if (canManageSystem && !presentation.singleHybridTerminalOnly) vm.loadTerminalsCache()
    }
    val showTerminalSettings = showAdvancedTerminalSettings(
        canManageSystem = canManageSystem,
        activeTerminalCount = state.allTerminals.size,
        pendingTerminalCount = state.pendingTerminals.size,
        terminalManagementEnabled = !presentation.singleHybridTerminalOnly,
    )
    val visibleTabs = if (canManageSystem) {
        SettingsTab.entries.filter { it != SettingsTab.Terminals || showTerminalSettings }
    } else {
        listOf(SettingsTab.Account)
    }
    val activeTab = state.tab.takeIf { it in visibleTabs } ?: SettingsTab.Account
    LaunchedEffect(activeTab) {
        if (state.tab != activeTab) vm.selectTab(activeTab)
    }

    Column(
        Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        PremiumTabBar(
            options = visibleTabs.map { TabOption(it.name, it.label) },
            selectedId = activeTab.name,
            onSelect = { id ->
                visibleTabs.firstOrNull { it.name == id }?.let(vm::selectTab)
            },
        )
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (activeTab) {
                SettingsTab.Account -> AccountTab(state, vm, onReportProblem)
                SettingsTab.Company -> CompanyTab(state, vm, presentation)
                SettingsTab.Branches -> BranchesTab(state, vm, presentation)
                SettingsTab.Terminals -> TerminalsTab(state, vm, presentation)
            }
        }
    }

}

internal fun showAdvancedTerminalSettings(
    canManageSystem: Boolean,
    activeTerminalCount: Int,
    pendingTerminalCount: Int,
    terminalManagementEnabled: Boolean = true,
): Boolean = terminalManagementEnabled && canManageSystem &&
    (activeTerminalCount > 1 || pendingTerminalCount > 0)

@Composable
private fun Field(
    label: String,
    value: String,
    enabled: Boolean = true,
    // Last on purpose: every call site uses trailing-lambda syntax, and with
    // this in the middle the lambda silently bound to `enabled` instead.
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        shape = Radius.shapeMd,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NewPasswordField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = Radius.shapeMd,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccountTab(
    state: SettingsUiState,
    vm: SettingsViewModel,
    onReportProblem: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        Modifier.widthIn(max = 980.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        when (settingsReadPresentation(state.me != null, state.meLoading, state.meError)) {
            SettingsReadPresentation.INITIAL_LOADING -> Loading()
            SettingsReadPresentation.BLOCKING_ERROR ->
                Retry(state.meError ?: "Could not load your account.", vm::loadMe)
            SettingsReadPresentation.FRESH,
            SettingsReadPresentation.REFRESHING,
            SettingsReadPresentation.STALE -> {
            if (state.meError != null) {
                RefreshStatusBanner(
                    title = "Saved account details",
                    message = state.meError,
                    refreshing = state.meLoading,
                    onRetry = vm::loadMe,
                )
            } else if (state.meLoading) {
                RefreshingRow("Refreshing account details…")
            }
            SectionCard(
                title = "Profile",
                subtitle = "The account currently signed in on this tablet.",
                icon = Icons.Default.AccountCircle,
            ) {
                InfoRow("Name", state.me!!.name)
                PanelDivider()
                InfoRow("Email", state.me!!.email)
                if (state.me!!.roles.isNotEmpty()) {
                    PanelDivider()
                    InfoRow("Roles", state.me!!.roles.joinToString(", "))
                }
                state.me!!.branchName?.takeIf(String::isNotBlank)?.let { branchName ->
                    PanelDivider()
                    InfoRow("Shop", branchName)
                }
            }
            SectionCard(
                title = "Account security",
                subtitle = "Password changes require a short-lived approval code.",
                icon = Icons.Default.Lock,
            ) {
                if (state.challenge == null) {
                    Text(
                        "A one-time approval code is sent to the business security contact before " +
                        "the password can be changed.",
                        color = Brand.ForegroundMuted,
                    )
                    ErpButton(
                        text = if (state.accountBusy) "Sending…" else "Send approval code",
                        onClick = vm::requestPasswordCode,
                        enabled = !state.accountBusy,
                        busy = state.accountBusy,
                    )
                } else {
                    val destination = state.challenge.destination.ifBlank {
                        "the business security contact"
                    }
                    val expiryMinutes = state.challenge.expiresIn.coerceAtLeast(0) / 60
                    val expiryText = if (expiryMinutes > 0) {
                        "$expiryMinutes minute${if (expiryMinutes == 1) "" else "s"}"
                    } else {
                        "less than a minute"
                    }
                    Text(
                        "Code sent to $destination. It expires in $expiryText.",
                        color = Brand.ForegroundMuted,
                    )
                    Field("6-digit approval code", code) { code = it.filter(Char::isDigit).take(6) }
                    NewPasswordField("New password", pwd) { pwd = it }
                    NewPasswordField("Confirm new password", confirm) { confirm = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ErpButton(
                            text = "Cancel",
                            intent = ActionIntent.Secondary,
                            onClick = {
                                vm.cancelPasswordChange(); code = ""; pwd = ""; confirm = ""
                            },
                        )
                        ErpButton(
                            text = if (state.accountBusy) "Changing…" else "Change password",
                            enabled = !state.accountBusy,
                            onClick = { vm.confirmPasswordChange(code, pwd, confirm) },
                            busy = state.accountBusy,
                        )
                    }
                }
                state.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            }
        }
        SectionCard(
            title = "Help & feedback",
            subtitle = "Send a problem directly to the ERP web inbox for review.",
            icon = Icons.Default.BugReport,
            tone = UiTone.Information,
        ) {
            Text(
                "Describe what went wrong and the app will include only safe device, version, shop, workspace, screen, and connectivity details.",
                color = Brand.ForegroundMuted,
            )
            ErpButton(
                text = "Report a problem",
                onClick = onReportProblem,
                intent = ActionIntent.Secondary,
                leadingIcon = Icons.Default.BugReport,
            )
        }
    }

    Feedback(state.accountNotice, vm::dismissAccountFeedback)
}

@Composable
private fun CompanyTab(
    state: SettingsUiState,
    vm: SettingsViewModel,
    presentation: WorkspacePresentationPolicy,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    when (
        settingsReadPresentation(
            state.company != null,
            state.companyLoading || state.companyRefreshing,
            state.companyError,
        )
    ) {
        SettingsReadPresentation.INITIAL_LOADING -> Loading()
        SettingsReadPresentation.BLOCKING_ERROR ->
            Retry(state.companyError ?: "Could not load company settings.", vm::loadCompany)
        SettingsReadPresentation.FRESH,
        SettingsReadPresentation.REFRESHING,
        SettingsReadPresentation.STALE -> Column(
            Modifier.widthIn(max = 1040.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.companyError != null) {
                RefreshStatusBanner(
                    title = "Saved company settings",
                    message = state.companyError,
                    refreshing = state.companyRefreshing,
                    onRetry = vm::loadCompany,
                )
            } else if (state.companyRefreshing) {
                RefreshingRow("Refreshing company settings…")
            }
            if (state.companyPending) {
                PendingBanner(
                    text = if (state.companyRejectedError != null) {
                        "Could not sync your last change: ${state.companyRejectedError}"
                    } else {
                        "Change queued — not synced yet."
                    },
                    rejected = state.companyRejectedError != null,
                    onRetry = vm::retryCompanyEdit,
                    onDiscard = vm::discardRejectedCompanyEdit,
                    discardSubject = "failed company change",
                )
            }
            val f = state.companyForm
            SectionCard(
                title = "Business profile",
                subtitle = "Names printed and displayed across the ERP.",
                icon = Icons.Default.Business,
            ) {
                Field("Trading name", f.name) { value -> vm.editCompany { it.copy(name = value) } }
                Field("Legal name", f.legalName) { value -> vm.editCompany { it.copy(legalName = value) } }
            }
            SectionCard(
                title = if (presentation.showsRestaurantOperations) {
                    "Tax and payments"
                } else {
                    "Payments and company identity"
                },
                subtitle = if (presentation.showsRestaurantOperations) {
                    "Registration details and the UPI address used for payment QR codes."
                } else {
                    "Company identity and the UPI address used for payment QR codes."
                },
                icon = Icons.Default.PointOfSale,
            ) {
                if (presentation.showsRestaurantOperations) {
                    Field("GSTIN", f.gstin) { v -> vm.editCompany { it.copy(gstin = v.uppercase()) } }
                }
                Field("PAN", f.pan) { v -> vm.editCompany { it.copy(pan = v.uppercase()) } }
                Field("UPI VPA (for payment QR)", f.upiVpa) { v -> vm.editCompany { it.copy(upiVpa = v) } }
                if (presentation.showsRestaurantOperations) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = f.isComposition,
                            onCheckedChange = { v -> vm.editCompany { it.copy(isComposition = v) } },
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Column {
                            Text("Composition scheme", color = Brand.Foreground)
                            Text(
                                "Use the business's configured tax registration scheme.",
                                color = Brand.ForegroundMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
            SectionCard(
                title = "Regional operation",
                subtitle = "Timezone used for business dates, shifts and receipts.",
                icon = Icons.Default.Schedule,
            ) {
                // The backend validates this strictly as an IANA zone and 422s
                // otherwise; the message is surfaced verbatim below rather than
                // swallowed, because "Asia/Kolkata" vs "IST" is not guessable.
                Field("Timezone (e.g. Asia/Kolkata)", f.timezone) { v ->
                    vm.editCompany { it.copy(timezone = v) }
                }
            }
            state.companyFormError?.let { message ->
                OperationalBanner(
                    title = "Check these settings",
                    detail = message,
                    tone = UiTone.Danger,
                    icon = Icons.Default.ErrorOutline,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ErpButton(
                        text = "Discard",
                        onClick = { confirmDiscard = true },
                        intent = ActionIntent.Secondary,
                        enabled = state.companyDirty && !state.companySaving,
                    )
                    ErpButton(
                        text = if (state.companySaving) "Saving…" else "Save changes",
                        onClick = vm::saveCompany,
                        enabled = state.companyDirty && !state.companySaving,
                        busy = state.companySaving,
                    )
                }
            }
        }
    }
    if (confirmDiscard) {
        DestructiveConfirmationDialog(
            confirmation = settingsConfirmation(DestructiveSettingsAction.DiscardCompanyEdits),
            busy = state.companySaving,
            onConfirm = {
                confirmDiscard = false
                vm.resetCompanyEdits()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    Feedback(state.companyNotice, vm::dismissCompanyNotice)
}

@Composable
private fun PendingBanner(
    text: String,
    rejected: Boolean,
    onRetry: () -> Unit,
    onDiscard: (() -> Unit)? = null,
    discardSubject: String = "failed local change",
) {
    var confirmDiscard by remember(text) { mutableStateOf(false) }
    OperationalBanner(
        title = if (rejected) "Saved change needs attention" else "Waiting to sync",
        detail = text,
        tone = if (rejected) UiTone.Danger else UiTone.Warning,
        icon = if (rejected) Icons.Default.ErrorOutline else Icons.Default.CloudQueue,
        action = if (rejected) {
            {
                ErpButton("Retry", onRetry, intent = ActionIntent.Secondary)
                onDiscard?.let {
                    ErpButton(
                        text = "Discard",
                        onClick = { confirmDiscard = true },
                        intent = ActionIntent.Quiet,
                    )
                }
            }
        } else null,
    )
    if (confirmDiscard && onDiscard != null) {
        AlertDialog(
            containerColor = Brand.SurfaceOverlay,
            shape = Radius.shapeLg,
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard failed change?") },
            text = {
                Text(
                    "This removes the saved $discardSubject retry and cannot be undone. It does not undo " +
                        "anything already accepted by the server; Settings will refresh when online.",
                )
            },
            confirmButton = {
                ErpButton(
                    text = "Discard failed change",
                    intent = ActionIntent.Destructive,
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") }
            },
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f).imePadding(),
            properties = DialogProperties(usePlatformDefaultWidth = false),
        )
    }
}

@Composable
private fun BranchesTab(
    state: SettingsUiState,
    vm: SettingsViewModel,
    presentation: WorkspacePresentationPolicy,
) {
    val hasBranchData = state.branches.isNotEmpty() || state.pendingBranches.isNotEmpty()
    when (
        settingsReadPresentation(
            hasBranchData,
            state.branchesLoading || state.branchesRefreshing,
            state.branchesError,
            emptyIsValid = true,
        )
    ) {
        SettingsReadPresentation.INITIAL_LOADING -> Loading()
        SettingsReadPresentation.BLOCKING_ERROR -> Retry(state.branchesError!!, vm::loadBranches)
        SettingsReadPresentation.FRESH,
        SettingsReadPresentation.REFRESHING,
        SettingsReadPresentation.STALE -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (state.branchesError != null) {
                RefreshStatusBanner(
                    title = "Saved shop details",
                    message = state.branchesError,
                    refreshing = state.branchesRefreshing,
                    onRetry = vm::loadBranches,
                )
            } else if (state.branchesRefreshing) {
                RefreshingRow("Refreshing shop details…")
            }
            SectionCard(
                title = "Shop",
                subtitle = "Your single business location, operating hours and licence details.",
                icon = Icons.Default.Store,
                action = {
                    if (state.branches.isEmpty() && state.pendingBranches.isEmpty()) {
                        ErpButton("Add shop", vm::newBranch, leadingIcon = Icons.Default.Add)
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                if (state.branches.isEmpty() && state.pendingBranches.isEmpty()) {
                    DesignedEmptyState(
                        title = "No shop is configured yet",
                        body = if (presentation.showsRestaurantOperations) {
                            "Add the shop before configuring Cafe POS and Gaming Area."
                        } else {
                            "Add the shop before configuring its Gaming + POS till."
                        },
                        icon = Icons.Default.Store,
                        primaryLabel = "Add shop",
                        onPrimary = vm::newBranch,
                    )
                } else {
                    state.branches.forEachIndexed { index, branch ->
                        DataListRow(
                            content = {
                                Text(
                                    branch.name,
                                    color = Brand.Foreground,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    listOfNotNull(
                                        branch.invoiceSeriesCode.takeIf(String::isNotBlank)
                                            ?.let { "Invoice series $it" }
                                            ?: "Invoice series unavailable — refresh before billing",
                                        branch.code?.takeIf(String::isNotBlank)
                                            ?.let { "Display code $it" },
                                        branch.timezone?.takeIf(String::isNotBlank),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (branch.invoiceSeriesCode.isBlank()) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Brand.ForegroundMuted
                                    },
                                )
                            },
                            trailing = {
                                ErpButton(
                                    text = "Edit",
                                    onClick = { vm.editBranch(branch) },
                                    intent = ActionIntent.Secondary,
                                )
                            },
                        )
                        if (index != state.branches.lastIndex) PanelDivider()
                    }
                }
            }
            if (state.pendingBranches.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.pendingBranches.forEach { row ->
                        PendingBanner(
                            text = if (row.rejected) {
                                "\"${row.name}\" could not sync: ${row.error ?: "unknown error"}"
                            } else {
                                "\"${row.name}\" queued — not synced yet."
                            },
                            rejected = row.rejected,
                            onRetry = { vm.retryBranch(row.localId) },
                            onDiscard = { vm.discardRejectedBranch(row.localId) },
                            discardSubject = "failed shop \"${row.name}\"",
                        )
                    }
                }
            }
        }
    }
    state.branchForm?.let { BranchFormDialog(it, state, vm, presentation) }
    Feedback(state.branchNotice, vm::dismissBranchNotice)
}

@Composable
private fun BranchFormDialog(
    form: BranchForm,
    state: SettingsUiState,
    vm: SettingsViewModel,
    presentation: WorkspacePresentationPolicy,
) {
    var confirmDiscard by remember(form.id, form.isNew) { mutableStateOf(false) }
    val requestDismiss = {
        if (state.branchFormDirty) confirmDiscard = true else vm.closeBranchForm()
    }
    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = { if (!state.branchSaving) requestDismiss() },
        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.92f).imePadding(),
        title = { Text(if (form.isNew) "Add shop" else "Edit ${form.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (form.isNew) {
                    Text(
                        "Queued and synced when back online, like every other write on " +
                            "this tablet.",
                        style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
                    )
                }
                Field("Name", form.name) { v -> vm.updateBranchForm { it.copy(name = v) } }
                Field("Invoice series code (2 letters/digits)", form.invoiceSeriesCode) { v ->
                    vm.updateBranchForm { it.copy(invoiceSeriesCode = v.uppercase()) }
                }
                Text(
                    when {
                        !form.isNew && form.invoiceSeriesCode.isBlank() ->
                            "Invoice series has not been refreshed on this tablet. Refresh Shop " +
                                "before editing or billing."
                        form.isNew ->
                            "Required and unique within the company, for example MN. It is printed " +
                                "on every invoice from this shop."
                        else ->
                            "This identifies the shop on fiscal receipts. It can only change " +
                                "before the shop has issued its first invoice; existing receipt " +
                                "history is protected by the server."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!form.isNew && form.invoiceSeriesCode.isBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Brand.ForegroundMuted
                    },
                )
                Field("Operational short code (optional)", form.code) { v ->
                    vm.updateBranchForm { it.copy(code = v.uppercase()) }
                }
                Field("Address", form.address) { v -> vm.updateBranchForm { it.copy(address = v) } }
                Field("Timezone", form.timezone) { v -> vm.updateBranchForm { it.copy(timezone = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        Field("Opens (HH:MM)", form.opensAt) { v -> vm.updateBranchForm { it.copy(opensAt = v) } }
                    }
                    Box(Modifier.weight(1f)) {
                        Field("Closes (HH:MM)", form.closesAt) { v -> vm.updateBranchForm { it.copy(closesAt = v) } }
                    }
                }
                if (presentation.showsRestaurantOperations) {
                    Field("GST state code", form.stateCode) { v ->
                        vm.updateBranchForm { it.copy(stateCode = v) }
                    }
                    Field("FSSAI licence (14 digits)", form.fssaiLicenseNo) { v ->
                        vm.updateBranchForm { it.copy(fssaiLicenseNo = v) }
                    }
                }
                Field("Trade licence no.", form.tradeLicenseNo) { v ->
                    vm.updateBranchForm { it.copy(tradeLicenseNo = v) }
                }
                if (presentation.showsRestaurantOperations) {
                    Field("Shop GSTIN", form.branchGstin) { v ->
                        vm.updateBranchForm { it.copy(branchGstin = v.uppercase()) }
                    }
                }
                state.branchFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            ErpButton(
                text = when {
                    state.branchSaving -> "Saving…"
                    form.isNew -> "Queue shop"
                    else -> "Save"
                },
                onClick = vm::saveBranch,
                enabled = !state.branchSaving,
                busy = state.branchSaving,
            )
        },
        dismissButton = {
            TextButton(onClick = requestDismiss, enabled = !state.branchSaving) { Text("Cancel") }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
    if (confirmDiscard) {
        DestructiveConfirmationDialog(
            confirmation = settingsConfirmation(
                DestructiveSettingsAction.DiscardBranchForm(form.name, form.isNew),
            ),
            busy = state.branchSaving,
            onConfirm = {
                confirmDiscard = false
                vm.closeBranchForm()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun TerminalsTab(
    state: SettingsUiState,
    vm: SettingsViewModel,
    presentation: WorkspacePresentationPolicy,
) {
    var terminalToDelete by remember { mutableStateOf<TerminalDto?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionCard(
            title = "Till terminals",
            subtitle = "Every sale is stamped with a till so shifts and cash drawers remain reconcilable.",
            icon = Icons.Default.PointOfSale,
        ) {
            Text(
                if (presentation.showsRestaurantOperations) {
                    "Cafe POS and Gaming Area are separate work terminals inside this one shop. Each keeps its own accountable shift."
                } else {
                    "Use one Gaming + POS till for this shop unless a separate payment counter is genuinely required. Each configured till keeps its own accountable shift."
                },
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.branchesError != null) {
            RefreshStatusBanner(
                title = "Saved shop details",
                message = state.branchesError,
                refreshing = state.branchesRefreshing,
                onRetry = vm::loadBranches,
            )
        } else if (state.branchesRefreshing) {
            RefreshingRow("Refreshing shop details…")
        }
        state.terminalActionError?.let { message ->
            ActionErrorBanner(message, vm::dismissTerminalFeedback)
        }
        if (state.branches.size > 1) {
            PremiumTabBar(
                options = state.branches.map { branch ->
                    TabOption(
                        id = branch.id,
                        label = branch.name,
                        count = state.allTerminals.count { it.branchId == branch.id } +
                            state.pendingTerminals.count { it.branchId == branch.id },
                    )
                },
                selectedId = state.selectedBranchId.orEmpty(),
                onSelect = vm::selectBranch,
            )
        }
        val pendingForBranch = state.pendingTerminals.filter { it.branchId == state.selectedBranchId }
        when (
            settingsReadPresentation(
                state.terminals.isNotEmpty() || pendingForBranch.isNotEmpty(),
                state.terminalsLoading,
                state.terminalsError,
                emptyIsValid = true,
            )
        ) {
            SettingsReadPresentation.INITIAL_LOADING -> Loading()
            SettingsReadPresentation.BLOCKING_ERROR -> Retry(
                state.terminalsError!!,
                vm::loadTerminalsCache,
            )
            SettingsReadPresentation.FRESH,
            SettingsReadPresentation.REFRESHING,
            SettingsReadPresentation.STALE -> {
                if (state.terminalsError != null) {
                    RefreshStatusBanner(
                        title = "Saved terminals",
                        message = state.terminalsError,
                        refreshing = state.terminalsLoading,
                        onRetry = vm::loadTerminalsCache,
                    )
                } else if (state.terminalsLoading) {
                    RefreshingRow("Refreshing terminals…")
                }
                if (pendingForBranch.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pendingForBranch.forEach { row ->
                            PendingBanner(
                                text = if (row.rejected) {
                                    "\"${row.name}\" (${terminalPurposeLabel(row.purpose, presentation)}) could not sync: " +
                                        (row.error ?: "unknown error")
                                } else {
                                    "\"${row.name}\" (${terminalPurposeLabel(row.purpose, presentation)}) queued — not synced yet."
                                },
                                rejected = row.rejected,
                                onRetry = { vm.retryTerminal(row.localId) },
                                onDiscard = { vm.discardRejectedTerminal(row.localId) },
                                discardSubject = "failed terminal \"${row.name}\"",
                            )
                        }
                    }
                }
                SectionCard(
                    title = state.branchName(state.selectedBranchId)?.let { "$it terminals" }
                        ?: "Assigned terminals",
                    subtitle = "${state.terminals.size} configured till${if (state.terminals.size == 1) "" else "s"}",
                    icon = Icons.Default.PointOfSale,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    if (state.terminals.isEmpty() && pendingForBranch.isEmpty()) {
                        DesignedEmptyState(
                            title = "No terminals for this shop",
                            body = if (presentation.showsRestaurantOperations) {
                                "Add Cafe POS first, then Gaming Area. A device ID is optional until a tablet is assigned."
                            } else {
                                "Add one Gaming + POS till. A device ID is optional until a tablet is assigned."
                            },
                            icon = Icons.Default.PointOfSale,
                        )
                    } else {
                        state.terminals.forEachIndexed { index, terminal ->
                            DataListRow(
                                content = {
                                    Text(
                                        terminal.name,
                                        color = Brand.Foreground,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        terminalPurposeLabel(terminal.purpose, presentation),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Brand.Gold,
                                    )
                                    Text(
                                        terminal.deviceId?.takeIf(String::isNotBlank)
                                            ?: "No tablet device ID assigned",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Brand.ForegroundMuted,
                                    )
                                },
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                        ErpButton(
                                            text = "Edit",
                                            onClick = { vm.openTerminalEdit(terminal) },
                                            intent = ActionIntent.Secondary,
                                            enabled = !state.terminalBusy,
                                            leadingIcon = Icons.Default.Edit,
                                        )
                                        ErpButton(
                                            text = "Delete",
                                            onClick = { terminalToDelete = terminal },
                                            intent = ActionIntent.Quiet,
                                            enabled = !state.terminalBusy,
                                        )
                                    }
                                },
                            )
                            if (index != state.terminals.lastIndex) PanelDivider()
                        }
                    }
                }
                SectionCard(
                    title = "Add a terminal",
                    subtitle = if (presentation.showsRestaurantOperations) {
                        "Add Cafe POS or Gaming Area without creating another shop."
                    } else {
                        "Add another till only when this shop genuinely needs one."
                    },
                    icon = Icons.Default.Add,
                ) {
                    Field("Name", state.terminalName, onChange = vm::setTerminalName)
                    TerminalPurposeSelector(
                        selected = state.terminalPurpose,
                        onSelect = vm::setTerminalPurpose,
                        presentation = presentation,
                    )
                    Field("Tablet device ID (optional)", state.terminalDeviceId, onChange = vm::setTerminalDeviceId)
                    Text(
                        "Use a stable identifier such as the tablet asset tag. It helps owners " +
                            "trace which physical device used this till.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                    state.terminalFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ErpButton(
                        text = if (state.terminalBusy) "Saving…" else "Add terminal",
                        onClick = vm::addTerminal,
                        enabled = !state.terminalBusy && state.selectedBranchId != null &&
                            state.terminalPurpose != null,
                        busy = state.terminalBusy,
                        leadingIcon = Icons.Default.Add,
                    )
                }
            }
        }
    }
    terminalToDelete?.let { terminal ->
        DestructiveConfirmationDialog(
            confirmation = settingsConfirmation(
                DestructiveSettingsAction.DeleteTerminal(terminal.name),
            ),
            busy = state.terminalBusy,
            onConfirm = {
                terminalToDelete = null
                vm.deleteTerminal(terminal)
            },
            onDismiss = { terminalToDelete = null },
        )
    }
    state.terminalEdit?.let { form ->
        TerminalEditDialog(
            form = form,
            busy = state.terminalBusy,
            error = state.terminalEditError,
            onNameChange = vm::setTerminalEditName,
            onPurposeChange = vm::setTerminalEditPurpose,
            onDeviceIdChange = vm::setTerminalEditDeviceId,
            onSave = vm::saveTerminalEdit,
            onDismiss = vm::closeTerminalEdit,
            presentation = presentation,
        )
    }
    Feedback(state.terminalNotice, vm::dismissTerminalFeedback)
}

@Composable
private fun TerminalEditDialog(
    form: TerminalEditForm,
    busy: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onPurposeChange: (String) -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    presentation: WorkspacePresentationPolicy,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit ${form.originalName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "Rename this terminal in place so its existing shifts, orders, and audit history stay attached.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Field("Name", form.name, enabled = !busy, onChange = onNameChange)
                TerminalPurposeSelector(
                    selected = form.purpose,
                    enabled = !busy,
                    onSelect = onPurposeChange,
                    presentation = presentation,
                )
                if (form.purpose != form.originalPurpose) {
                    Text(
                        "Close this terminal's current shift before changing its work area. Renaming does not require closing the shift.",
                        color = Brand.Warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Field(
                    "Tablet device ID (optional)",
                    form.deviceId,
                    enabled = !busy,
                    onChange = onDeviceIdChange,
                )
                Text(
                    "Clear the device ID only when this till is no longer bound to that tablet.",
                    color = Brand.ForegroundMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !busy) {
                Text(if (busy) "Saving…" else "Save terminal")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@Composable
private fun TerminalPurposeSelector(
    selected: String?,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    presentation: WorkspacePresentationPolicy,
) {
    val options = terminalPurposeOptions(presentation)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            "Operational purpose",
            color = Brand.Foreground,
            style = MaterialTheme.typography.labelLarge,
        )
        PremiumTabBar(
            options = options.map { TabOption(it.id, it.label) },
            selectedId = selected.orEmpty(),
            onSelect = { if (enabled) onSelect(it) },
        )
        Text(
            options.firstOrNull { it.id == selected }?.description
                ?: "Choose deliberately. This controls where Gaming bills are allowed to go.",
            color = Brand.ForegroundMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Loading() = SectionCard(
    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
) {
    LoadingSkeleton(lines = 5)
}

@Composable
private fun Retry(message: String, onRetry: () -> Unit) =
    DesignedEmptyState(
        title = "Could not load these settings",
        body = message,
        icon = Icons.Default.ErrorOutline,
        primaryLabel = "Try again",
        onPrimary = onRetry,
    )

@Composable
private fun RefreshingRow(message: String) {
    OperationalBanner(
        title = "Refreshing",
        detail = message,
        tone = UiTone.Information,
        icon = Icons.Default.Refresh,
    )
}

@Composable
private fun RefreshStatusBanner(
    title: String,
    message: String,
    refreshing: Boolean,
    onRetry: () -> Unit,
) {
    OperationalBanner(
        title = "$title may be out of date",
        detail = message,
        tone = UiTone.Warning,
        icon = Icons.Default.ErrorOutline,
        action = {
            ErpButton(
                text = if (refreshing) "Retrying…" else "Retry",
                onClick = onRetry,
                intent = ActionIntent.Secondary,
                enabled = !refreshing,
                busy = refreshing,
            )
        },
    )
}

@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    OperationalBanner(
        title = "Terminal change failed",
        detail = message,
        tone = UiTone.Danger,
        icon = Icons.Default.ErrorOutline,
        action = {
            ErpButton("Dismiss", onDismiss, intent = ActionIntent.Quiet)
        },
    )
}

@Composable
private fun DestructiveConfirmationDialog(
    confirmation: SettingsConfirmation,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(confirmation.title) },
        text = { Text(confirmation.body) },
        confirmButton = {
            ErpButton(
                text = confirmation.confirmLabel,
                onClick = onConfirm,
                intent = ActionIntent.Destructive,
                enabled = !busy,
                busy = busy,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep it") }
        },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

@Composable
private fun Feedback(notice: String?, onDismiss: () -> Unit) {
    notice ?: return
    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Settings") },
        text = { Text(notice) },
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}
