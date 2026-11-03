package cloud.dcompany.erp.ui.screens.staff.time

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.SectionCard

@Composable
internal fun StaffPayEstimateCard(summary: StaffTimeSummary) {
    // A fresh person/month/snapshot never inherits a previous overtime choice.
    var overtime by remember(summary.userId, summary.month, summary.asOf, summary.payEstimate) { mutableIntStateOf(0) }
    var showDays by remember(summary.userId, summary.month, summary.asOf) { mutableStateOf(false) }
    val p = summary.payEstimate
    SectionCard(Modifier.fillMaxWidth().testTag("staff-pay-estimate"), title = "${summary.name} · Hours & pay estimate") {
        Text("Estimate for completed attendance only. No payment, payroll deduction or expense is posted.", style = MaterialTheme.typography.bodySmall)
        if (p == null || p.status != "available") {
            Text(p?.reason ?: "Pay estimates require the updated server. No amount is inferred.")
        } else {
            Text("Monthly reference: ${p.monthlySalaryMinor!!.asRupees()} ÷ ${timeDuration(p.scheduledPaidSeconds!!)} scheduled paid hours")
            Text("Reference / hour: ${p.hourlyRateMinor!!.asRupees()} (display rounded)")
            Text("Paid presence includes breaks. Workdays are dated by arrival in ${summary.timezone}; each overnight visit stays together in its arrival month. Base time is capped at ${timeDuration(p.dailyPaidSeconds!!)} per scheduled arrival workday; additional time is separate.", style = MaterialTheme.typography.bodySmall)
            Text(if (p.attendanceScope == "selected_branch") "Selected branch attendance only." else "All company branches.", style = MaterialTheme.typography.bodySmall)
            Text("Recorded paid presence: ${timeDuration(p.recordedPaidSeconds!!)}")
            Text("Base hours: ${timeDuration(p.ordinarySeconds!!)}")
            Text("Base-pay estimate: ${p.basePayMinor!!.asRupees()}", style = MaterialTheme.typography.titleMedium)
            Text("Extra hours · optional overtime: ${timeDuration(p.extraSeconds!!)}")
            Text("Optional overtime preview")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "No overtime", 125 to "1.25× overtime", 150 to "1.5× overtime").forEach { (value, label) ->
                    FilterChip(overtime == value, { overtime = value }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("staff-pay-ot-$value"))
                }
            }
            val extra = when (overtime) { 125 -> p.overtime125Minor!!; 150 -> p.overtime150Minor!!; else -> 0L }
            Text("Optional overtime amount: ${extra.asRupees()}", modifier = Modifier.testTag("staff-pay-ot-amount"))
            Text("Total estimate: ${(p.basePayMinor + extra).asRupees()}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("staff-pay-total"))
            Text("Overtime choices are comparison previews, not an approved overtime rate. Open entries remain provisional and excluded. Amounts use exact timestamps, rounded once per monthly component.", style = MaterialTheme.typography.bodySmall)
            TextButton({ showDays = !showDays }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showDays) "Hide daily hours" else "Daily hours breakdown") }
            if (showDays) p.days.forEach { day ->
                Text("${day.date} · paid ${timeDuration(day.paidSeconds)} · base ${timeDuration(day.ordinarySeconds)} · extra ${timeDuration(day.extraSeconds)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
