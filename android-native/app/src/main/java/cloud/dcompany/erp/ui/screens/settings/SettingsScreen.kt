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
            if (state.branches.isEmpty()) {
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
    Feedback(state.branchNotice, vm::dismissBranchNotice)
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
        if (state.terminalsLoading) {
            Loading()
        } else {
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
                Field("Name", state.terminalName, onChange = vm::setTerminalName)
                Field("Device id", state.terminalDeviceId, onChange = vm::setTerminalDeviceId)
                state.terminalFormError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = vm::addTerminal, enabled = !state.terminalBusy) { Text("Add terminal") }
            }
        }
        state.terminalsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
