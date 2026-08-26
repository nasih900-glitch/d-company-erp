package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius

@Composable
fun SettingsScreen(
    canManageSystem: Boolean,
    vm: SettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val visibleTabs = if (canManageSystem) SettingsTab.entries else listOf(SettingsTab.Account)
    val activeTab = state.tab.takeIf { it in visibleTabs } ?: SettingsTab.Account
    LaunchedEffect(activeTab) {
        if (state.tab != activeTab) vm.selectTab(activeTab)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleTabs.forEach { tab ->
                FilterChip(
                    selected = activeTab == tab,
                    onClick = { vm.selectTab(tab) },
                    label = { Text(tab.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Gold,
                        selectedLabelColor = Brand.Background,
                    ),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        when (activeTab) {
            SettingsTab.Account -> AccountTab(state, vm)
            SettingsTab.Company -> CompanyTab(state, vm)
            SettingsTab.Branches -> BranchesTab(state, vm)
            SettingsTab.Terminals -> TerminalsTab(state, vm)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(Brand.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

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
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccountTab(state: SettingsUiState, vm: SettingsViewModel) {
    var code by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    when (settingsReadPresentation(state.me != null, state.meLoading, state.meError)) {
        SettingsReadPresentation.INITIAL_LOADING -> Loading()
        SettingsReadPresentation.BLOCKING_ERROR ->
            Retry(state.meError ?: "Could not load your account.", vm::loadMe)
        SettingsReadPresentation.FRESH,
        SettingsReadPresentation.REFRESHING,
        SettingsReadPresentation.STALE -> Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
            Card {
                Text("Signed in", color = Brand.ForegroundMuted)
                Text(state.me!!.name, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Text(state.me!!.email, color = Brand.ForegroundMuted)
                if (state.me!!.roles.isNotEmpty()) {
                    Text(state.me!!.roles.joinToString(", "), color = Brand.GoldMuted)
                }
            }
            Card {
                Text("Change password", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                if (state.challenge == null) {
                    Text(
                        "A one-time approval code is sent to the business security contact before " +
                            "the password can be changed.",
                        color = Brand.ForegroundMuted,
                    )
                    Button(onClick = vm::requestPasswordCode, enabled = !state.accountBusy) {
                        Text(if (state.accountBusy) "Sending…" else "Send approval code")
                    }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            vm.cancelPasswordChange(); code = ""; pwd = ""; confirm = ""
                        }) { Text("Cancel") }
                        Button(
                            enabled = !state.accountBusy,
                            onClick = { vm.confirmPasswordChange(code, pwd, confirm) },
                        ) { Text(if (state.accountBusy) "Changing…" else "Change password") }
                    }
                }
                state.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    Feedback(state.accountNotice, vm::dismissAccountFeedback)
}

@Composable
private fun CompanyTab(state: SettingsUiState, vm: SettingsViewModel) {
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
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
            Card {
                Text("Company", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                val f = state.companyForm
                Field("Trading name", f.name) { v -> vm.editCompany { it.copy(name = v) } }
                Field("Legal name", f.legalName) { v -> vm.editCompany { it.copy(legalName = v) } }
                Field("GSTIN", f.gstin) { v -> vm.editCompany { it.copy(gstin = v.uppercase()) } }
                Field("PAN", f.pan) { v -> vm.editCompany { it.copy(pan = v.uppercase()) } }
                Field("UPI VPA (for payment QR)", f.upiVpa) { v -> vm.editCompany { it.copy(upiVpa = v) } }
                // The backend validates this strictly as an IANA zone and 422s
                // otherwise; the message is surfaced verbatim below rather than
                // swallowed, because "Asia/Kolkata" vs "IST" is not guessable.
                Field("Timezone (e.g. Asia/Kolkata)", f.timezone) { v ->
                    vm.editCompany { it.copy(timezone = v) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = f.isComposition,
                        onCheckedChange = { v -> vm.editCompany { it.copy(isComposition = v) } },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Composition scheme", color = Brand.Foreground)
                }
                state.companyFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { confirmDiscard = true },
                        enabled = state.companyDirty && !state.companySaving,
                    ) { Text("Discard") }
                    Button(
                        onClick = vm::saveCompany,
                        enabled = state.companyDirty && !state.companySaving,
                    ) { Text(if (state.companySaving) "Saving…" else "Save changes") }
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
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (rejected) Brand.Danger else Brand.GoldMuted, modifier = Modifier.weight(1f))
        if (rejected) {
            Row {
                TextButton(onClick = onRetry) { Text("Retry") }
                onDiscard?.let {
                    TextButton(onClick = { confirmDiscard = true }) {
                        Text("Discard", color = Brand.Danger)
                    }
                }
            }
        }
    }
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
                Button(
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    },
                ) { Text("Discard failed change") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun BranchesTab(state: SettingsUiState, vm: SettingsViewModel) {
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
        SettingsReadPresentation.STALE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.branchesError != null) {
                RefreshStatusBanner(
                    title = "Saved branches",
                    message = state.branchesError,
                    refreshing = state.branchesRefreshing,
                    onRetry = vm::loadBranches,
                )
            } else if (state.branchesRefreshing) {
                RefreshingRow("Refreshing branches…")
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Branches", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
                Button(onClick = vm::newBranch) { Text("Add branch") }
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
                            discardSubject = "failed branch \"${row.name}\"",
                        )
                    }
                }
            }
            if (state.branches.isEmpty() && state.pendingBranches.isEmpty()) {
                Text("No branches yet. Add one to start billing.", color = Brand.ForegroundMuted)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.branches, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(Radius.shapeMd)
                            .background(Brand.SurfaceRaised).padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(b.name, color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                            Text(b.code ?: "no code", style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
                        }
                        TextButton(onClick = { vm.editBranch(b) }) { Text("Edit") }
                    }
                }
            }
        }
    }
    state.branchForm?.let { BranchFormDialog(it, state, vm) }
    Feedback(state.branchNotice, vm::dismissBranchNotice)
}

@Composable
private fun BranchFormDialog(form: BranchForm, state: SettingsUiState, vm: SettingsViewModel) {
    var confirmDiscard by remember(form.id, form.isNew) { mutableStateOf(false) }
    val requestDismiss = {
        if (state.branchFormDirty) confirmDiscard = true else vm.closeBranchForm()
    }
    AlertDialog(
        containerColor = cloud.dcompany.erp.ui.theme.Brand.SurfaceOverlay,
        shape = cloud.dcompany.erp.ui.theme.Radius.shapeLg,
        onDismissRequest = { if (!state.branchSaving) requestDismiss() },
        modifier = Modifier.width(480.dp),
        title = { Text(if (form.isNew) "Add branch" else "Edit ${form.name}") },
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
                Field("Short code", form.code) { v -> vm.updateBranchForm { it.copy(code = v.uppercase()) } }
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
                Field("GST state code", form.stateCode) { v -> vm.updateBranchForm { it.copy(stateCode = v) } }
                Field("FSSAI licence (14 digits)", form.fssaiLicenseNo) { v ->
                    vm.updateBranchForm { it.copy(fssaiLicenseNo = v) }
                }
                Field("Trade licence no.", form.tradeLicenseNo) { v ->
                    vm.updateBranchForm { it.copy(tradeLicenseNo = v) }
                }
                Field("Branch GSTIN", form.branchGstin) { v -> vm.updateBranchForm { it.copy(branchGstin = v.uppercase()) } }
                state.branchFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = vm::saveBranch, enabled = !state.branchSaving) {
                Text(
                    when {
                        state.branchSaving -> "Saving…"
                        form.isNew -> "Queue branch"
                        else -> "Save"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = requestDismiss, enabled = !state.branchSaving) { Text("Cancel") }
        },
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
private fun TerminalsTab(state: SettingsUiState, vm: SettingsViewModel) {
    var terminalToDelete by remember { mutableStateOf<TerminalDto?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Terminals", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "Each till is a terminal. Every sale is stamped with the terminal it " +
                "was taken on, which is what makes a drawer reconcilable.",
            color = Brand.ForegroundMuted,
        )
        if (state.branchesError != null) {
            RefreshStatusBanner(
                title = "Saved branch list",
                message = state.branchesError,
                refreshing = state.branchesRefreshing,
                onRetry = vm::loadBranches,
            )
        } else if (state.branchesRefreshing) {
            RefreshingRow("Refreshing branches…")
        }
        state.terminalActionError?.let { message ->
            ActionErrorBanner(message, vm::dismissTerminalFeedback)
        }
        if (state.branches.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.branches.forEach { b ->
                    FilterChip(
                        selected = state.selectedBranchId == b.id,
                        onClick = { vm.selectBranch(b.id) },
                        label = { Text(b.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Brand.Gold,
                            selectedLabelColor = Brand.Background,
                        ),
                    )
                }
            }
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
                                    "\"${row.name}\" could not sync: ${row.error ?: "unknown error"}"
                                } else {
                                    "\"${row.name}\" queued — not synced yet."
                                },
                                rejected = row.rejected,
                                onRetry = { vm.retryTerminal(row.localId) },
                                onDiscard = { vm.discardRejectedTerminal(row.localId) },
                                discardSubject = "failed terminal \"${row.name}\"",
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.terminals, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(Radius.shapeMd)
                                .background(Brand.SurfaceRaised).padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(t.name, color = Brand.Foreground)
                                Text(
                                    t.deviceId ?: "no device id",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Brand.ForegroundMuted,
                                )
                            }
                            TextButton(
                                onClick = { terminalToDelete = t },
                                enabled = !state.terminalBusy,
                            ) { Text("Delete", color = Brand.Danger) }
                        }
                    }
                }
                Card {
                    Text("Add a terminal", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Queued and synced when back online.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                    Field("Name", state.terminalName, onChange = vm::setTerminalName)
                    Field("Tablet device ID (optional)", state.terminalDeviceId, onChange = vm::setTerminalDeviceId)
                    Text(
                        "Use a stable identifier such as the tablet asset tag. It helps owners " +
                            "trace which physical device used this till.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.ForegroundMuted,
                    )
                    state.terminalFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = vm::addTerminal,
                        enabled = !state.terminalBusy && state.selectedBranchId != null,
                    ) { Text(if (state.terminalBusy) "Saving…" else "Add terminal") }
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
    Feedback(state.terminalNotice, vm::dismissTerminalFeedback)
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), Alignment.Center) {
    CircularProgressIndicator(color = Brand.Gold)
}

@Composable
private fun Retry(message: String, onRetry: () -> Unit) =
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Try again") }
        }
    }

@Composable
private fun RefreshingRow(message: String) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), color = Brand.Gold, strokeWidth = 2.dp)
        Text(message, color = Brand.ForegroundMuted)
    }
}

@Composable
private fun RefreshStatusBanner(
    title: String,
    message: String,
    refreshing: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("$title may be out of date", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onRetry, enabled = !refreshing) {
            Text(if (refreshing) "Retrying…" else "Retry")
        }
    }
}

@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.shapeSm)
            .background(Brand.SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Terminal was not deleted", color = Brand.Danger, fontWeight = FontWeight.Bold)
            Text(message, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
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
            Button(onClick = onConfirm, enabled = !busy) { Text(confirmation.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Keep it") }
        },
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
    )
}
