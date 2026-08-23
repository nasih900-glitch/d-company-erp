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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.ui.theme.Brand

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsTab.entries.forEach { tab ->
                FilterChip(
                    selected = state.tab == tab,
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
        when (state.tab) {
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
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
private fun AccountTab(state: SettingsUiState, vm: SettingsViewModel) {
    var code by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    when {
        state.meLoading -> Loading()
        state.me == null -> Retry(state.meError ?: "Could not load your account.", vm::loadMe)
        else -> Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
                        "A one-time code is sent to your email before the password can be changed.",
                        color = Brand.ForegroundMuted,
                    )
                    Button(onClick = vm::requestPasswordCode, enabled = !state.accountBusy) {
                        Text("Send me a code")
                    }
                } else {
                    Field("Code from email", code) { code = it }
                    Field("New password", pwd) { pwd = it }
                    Field("Confirm new password", confirm) { confirm = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            vm.cancelPasswordChange(); code = ""; pwd = ""; confirm = ""
                        }) { Text("Cancel") }
                        Button(
                            enabled = !state.accountBusy,
                            onClick = { vm.confirmPasswordChange(code, pwd, confirm) },
                        ) { Text("Change password") }
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
    when {
        state.companyLoading -> Loading()
        state.company == null -> Retry(state.companyError ?: "Could not load company settings.", vm::loadCompany)
        else -> Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.companyPending) {
                PendingBanner(
                    text = if (state.companyRejectedError != null) {
                        "Could not sync your last change: ${state.companyRejectedError}"
                    } else {
                        "Change queued — not synced yet."
                    },
                    rejected = state.companyRejectedError != null,
                    onRetry = vm::retryCompanyEdit,
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
                        onClick = vm::resetCompanyEdits,
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
    Feedback(state.companyNotice, vm::dismissAccountFeedback)
}

@Composable
private fun PendingBanner(text: String, rejected: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Brand.SurfaceRaised).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (rejected) Brand.Danger else Brand.GoldMuted, modifier = Modifier.weight(1f))
        if (rejected) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun BranchesTab(state: SettingsUiState, vm: SettingsViewModel) {
    when {
        state.branchesLoading -> Loading()
        state.branchesError != null && state.branches.isEmpty() ->
            Retry(state.branchesError!!, vm::loadBranches)
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
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
    AlertDialog(
        onDismissRequest = { if (!state.branchSaving) vm.closeBranchForm() },
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
            TextButton(onClick = vm::closeBranchForm, enabled = !state.branchSaving) { Text("Cancel") }
        },
    )
}

@Composable
private fun TerminalsTab(state: SettingsUiState, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Terminals", style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
        Text(
            "Each till is a terminal. Every sale is stamped with the terminal it " +
                "was taken on, which is what makes a drawer reconcilable.",
            color = Brand.ForegroundMuted,
        )
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
        if (state.terminalsLoading) {
            Loading()
        } else {
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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
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
                            onClick = { vm.deleteTerminal(t) },
                            enabled = !state.terminalBusy,
                        ) { Text("Delete", color = Brand.Danger) }
                    }
                }
            }
            Card {
                Text("Add a terminal", color = Brand.Foreground, fontWeight = FontWeight.SemiBold)
                Text(
                    "Queued and synced when back online.",
                    style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted,
                )
                Field("Name", state.terminalName, onChange = vm::setTerminalName)
                Field("Device id", state.terminalDeviceId, onChange = vm::setTerminalDeviceId)
                state.terminalFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = vm::addTerminal,
                    enabled = !state.terminalBusy && state.selectedBranchId != null,
                ) { Text("Add terminal") }
            }
        }
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
private fun Feedback(notice: String?, onDismiss: () -> Unit) {
    notice ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Settings") },
        text = { Text(notice) },
    )
}
