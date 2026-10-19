package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.StaffCacheEntity
import cloud.dcompany.erp.core.net.CanonicalReceipt
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Spacing

data class ReceiptFilterChoice(val id: String, val label: String)
data class ReceiptFilterChoices(
    val stations: List<ReceiptFilterChoice> = emptyList(),
    val staff: List<ReceiptFilterChoice> = emptyList(),
    val staffDirectoryAvailable: Boolean = false,
)

internal fun buildReceiptFilterChoices(
    profile: MeResponse?,
    receipts: List<CanonicalReceipt>,
    stations: List<GamingStationEntity>,
    staff: List<StaffCacheEntity>,
): ReceiptFilterChoices {
    if (profile == null) return ReceiptFilterChoices()
    val permissions = EffectivePermissions.from(profile)
    val stationChoices = linkedMapOf<String, ReceiptFilterChoice>()
    receipts.flatMap(CanonicalReceipt::gamingSessions).forEach {
        stationChoices[it.stationId] = ReceiptFilterChoice(it.stationId, "${it.stationName} · current label from loaded receipt")
    }
    if (permissions.has(ErpPermission.GamingRead)) stations.forEach {
        stationChoices[it.id] = ReceiptFilterChoice(it.id, "${it.code} · ${it.name}${if (!it.isActive) " · disabled" else ""} · current")
    }
    val staffChoices = linkedMapOf<String, ReceiptFilterChoice>()
    receipts.forEach { receipt -> receipt.payments.forEach { payment -> payment.recordedBy?.let { id ->
        staffChoices.putIfAbsent(id, ReceiptFilterChoice(id,
            payment.recordedByName?.let { "$it · current label from loaded receipt" }
                ?: "Recorder name unavailable · ${receipt.invoiceNo}"))
    } } }
    staffChoices[profile.userId] = ReceiptFilterChoice(profile.userId, "${profile.name} · current account")
    val directoryAllowed = permissions.staffAccess().canReadDirectory
    if (directoryAllowed) staff.forEach {
        staffChoices[it.id] = ReceiptFilterChoice(it.id, "${it.name}${if (it.status != "active") " · ${it.status}" else ""} · current directory")
    }
    return ReceiptFilterChoices(stationChoices.values.sortedBy { it.label },
        staffChoices.values.sortedBy { it.label }, directoryAllowed)
}

@Composable
internal fun ReceiptFilterControls(
    draft: ReceiptHistoryFilters,
    onChange: (ReceiptHistoryFilters) -> Unit,
    amount: String,
    onAmountChange: (String) -> Unit,
    choices: ReceiptFilterChoices,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Dates are invoice-issued shop dates. Amount is the exact original invoice total. All filters intersect.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(draft.dateFrom, { onChange(draft.copy(dateFrom = it.take(10))) },
                label = { Text("Issued from · YYYY-MM-DD") }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("receipt-filter-from"))
            OutlinedTextField(draft.dateTo, { onChange(draft.copy(dateTo = it.take(10))) },
                label = { Text("Issued to · YYYY-MM-DD") }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("receipt-filter-to"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(amount, { onAmountChange(it.take(20)) }, label = { Text("Invoice amount · ₹") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("receipt-filter-amount"))
            Box(Modifier.weight(1f)) {
                ReceiptFilterChoiceMenu("Effective payment", draft.method,
                    listOf("cash" to "Cash", "upi" to "UPI", "card" to "Card", "qr" to "QR", "wallet" to "Wallet")
                        .map { ReceiptFilterChoice(it.first, it.second) },
                    "All methods", { onChange(draft.copy(method = it)) })
            }
        }
        ReceiptFilterChoiceMenu("Station", draft.stationId, choices.stations, "All stations",
            { onChange(draft.copy(stationId = it)) })
        ReceiptFilterChoiceMenu("Payment recorded by", draft.staffUserId, choices.staff, "All recorders",
            { onChange(draft.copy(staffUserId = it)) })
        Text(if (choices.staffDirectoryAvailable) "Names are current directory labels, including inactive accounts."
            else "Recorder choices are limited to this account and actors disclosed in loaded receipts; this is not the full staff directory.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Method and recorder must match the same payment leg. Unknown legacy recorders remain unknown.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReceiptFilterChoiceMenu(label: String, selected: String?, choices: List<ReceiptFilterChoice>,
    allLabel: String, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("receipt-filter-choice-$label")) {
                Text(if (selected == null) allLabel else choices.firstOrNull { it.id == selected }?.label
                    ?: "Saved selection · current label unavailable", maxLines = 2)
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                DropdownMenuItem(text = { Text(allLabel) }, onClick = { expanded = false; onChoose(null) })
                choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) },
                    onClick = { expanded = false; onChoose(choice.id) }) }
            }
        }
    }
}

internal fun receiptFilterSummary(filters: ReceiptHistoryFilters, choices: ReceiptFilterChoices): String = buildList {
    if (filters.query.isNotBlank()) add("Reference ${filters.query}")
    if (filters.dateFrom.isNotBlank()) add("from ${filters.dateFrom}")
    if (filters.dateTo.isNotBlank()) add("through ${filters.dateTo}")
    filters.amountMinor?.let { add("invoice total ${it.asRupees()}") }
    filters.method?.let { add("effective ${it.uppercase()}") }
    filters.stationId?.let { id -> add(choices.stations.firstOrNull { it.id == id }?.label ?: "saved station filter · label unavailable") }
    filters.staffUserId?.let { id -> add(choices.staff.firstOrNull { it.id == id }?.label ?: "saved recorder filter · label unavailable") }
}.joinToString(" · ")
