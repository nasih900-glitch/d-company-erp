package cloud.dcompany.erp.ui.screens.staff.time

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.LiquidAlertDialog
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.workspaceContentCanvas
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun StaffTimeScreen(profile: MeResponse) {
    val app = DCompanyApp.instance
    val lease by app.cacheIsolation.activeLeaseFlow.collectAsStateWithLifecycle()
    val online by app.connectivity.online.collectAsStateWithLifecycle()
    val factory = remember(profile, lease) { viewModelFactory { initializer { StaffTimeViewModel(profile, lease) } } }
    val vm: StaffTimeViewModel = viewModel(key = "staff-time:${profile.userId}:${lease?.generation}:${profile.hashCode()}", factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm, online) { if (!online) vm.offline() else vm.refresh() }
    StaffTimeContent(state, vm::refresh, vm::person, vm::month, vm::morePeople, vm::olderPolicies,
        vm::clock, vm::correction, vm::policy, vm::edit, vm::closeDraft, vm::confirm, vm::retryExact, vm::acknowledgeRejected)
}

@Composable
internal fun StaffTimeContent(
    state: StaffTimeState,
    onRefresh: () -> Unit,
    onPerson: (String) -> Unit,
    onMonth: (String) -> Unit,
    onMorePeople: () -> Unit,
    onOlderPolicies: () -> Unit,
    onClock: (Boolean) -> Unit,
    onCorrection: (TimeAttendance) -> Unit,
    onPolicy: () -> Unit,
    onEdit: ((TimeEditDraft) -> TimeEditDraft) -> Unit,
    onCloseDraft: () -> Unit,
    onConfirm: () -> Unit,
    onRetryExact: () -> Unit,
    onAcknowledgeRejected: () -> Unit,
) {
    var peopleOpen by remember(state.actorId) { mutableStateOf(false) }
    var retryReview by remember(state.actorId) { mutableStateOf(false) }
    var rejectionReview by remember(state.actorId) { mutableStateOf(false) }
    var section by remember(state.actorId, state.selectedId) { mutableStateOf("time") }
    var monthText by remember(state.month) { mutableStateOf(state.month) }
    val blocked = state.busy || state.loading || state.draft != null
    val canWrite = state.fresh && state.storageReady && state.pending == null && !blocked
    val summary = state.summary
    Column(Modifier.fillMaxSize().workspaceContentCanvas().padding(16.dp).testTag("staff-time-screen"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (state.canManage) "Staff time & draft pay" else "My working time", style = MaterialTheme.typography.headlineSmall)
                Text("Attendance is recorded by the server. Pay and overtime remain drafts.", style = MaterialTheme.typography.bodySmall)
            }
            ErpButton("Refresh", onRefresh, enabled = !blocked, busy = state.loading, intent = ActionIntent.Secondary)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canManage) ErpButton(summary?.name ?: "Choose a person", { peopleOpen = true },
                enabled = !blocked && state.pending == null, intent = ActionIntent.Secondary, modifier = Modifier.testTag("staff-time-choose-person"))
            TextButton(onClick = { onMonth(YearMonth.parse(state.month).minusMonths(1).toString()) }, enabled = !blocked, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
            OutlinedTextField(monthText, { monthText = it }, label = { Text("Month YYYY-MM") }, singleLine = true,
                enabled = !blocked, modifier = Modifier.width(180.dp).testTag("staff-time-month"))
            ErpButton("Load month", { onMonth(monthText) }, enabled = !blocked, intent = ActionIntent.Secondary)
            TextButton(onClick = { onMonth(YearMonth.parse(state.month).plusMonths(1).toString()) }, enabled = !blocked, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("time" to "Time", "history" to "History").plus(if (state.canManage) listOf("pay" to "Draft pay") else emptyList()).forEach { (id, name) ->
                FilterChip(section == id, { section = id }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("staff-time-content-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { SectionCard(Modifier.fillMaxWidth()) { Text(it, color = MaterialTheme.colorScheme.error) } } }
            state.notice?.let { item { SectionCard(Modifier.fillMaxWidth()) { Text(it) } } }
            if (state.pending != null) item {
                SectionCard(Modifier.fillMaxWidth(), title = "Retained request") {
                    if (state.pendingAuthorityChanged) {
                        Text("Access or sign-in changed. This earlier request is kept for Owner review. It cannot be replayed or replaced here.")
                    } else {
                        Text(if (state.canManage) "${state.pending.intent.targetName} · ${timeAction(state.pending.intent.kind)}" else "Your working-time request")
                        Text(if (state.pending.rejectedStatus != null) "The server rejected this exact request. Review its response before starting a corrected request."
                            else "The outcome is unconfirmed. Refresh, then retry this same request only. No new request key will be created.")
                        ErpButton("Retry exact request", { retryReview = true }, enabled = state.fresh && !blocked, modifier = Modifier.fillMaxWidth())
                        if (state.pending.rejectedStatus != null) ErpButton("Review rejection", { rejectionReview = true },
                            enabled = state.fresh && !blocked, intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (summary == null) item { SectionCard(Modifier.fillMaxWidth()) { Text(if (state.loading) "Loading verified time…" else "No verified time is available. Connect and refresh.") } }
            else {
                item {
                    Text("${summary.name} · ${summary.month} · ${summary.timezone}", style = MaterialTheme.typography.titleMedium)
                    Text("${if (state.fresh) "Verified" else "Last verified snapshot"} ${timeStamp(summary.asOf, summary.timezone)}", style = MaterialTheme.typography.bodySmall)
                }
                when (section) {
                    "time" -> {
                        item { SectionCard(Modifier.fillMaxWidth(), title = "Recorded working time") {
                            Text("Closed presence: ${timeDuration(summary.closedGrossSeconds)}")
                            Text("Confirmed net work: ${summary.closedWorkSeconds?.let(::timeDuration) ?: "Needs Owner review"}")
                            Text("Open presence as of this snapshot: ${timeDuration(summary.provisionalOpenSeconds)}")
                            if (summary.reviewAttendanceIds.isNotEmpty()) Text("${summary.reviewAttendanceIds.size} entries need Owner review. Unknown breaks are not treated as zero.")
                        } }
                        summary.schedule?.let { schedule -> item { SectionCard(Modifier.fillMaxWidth(), title = "Planned schedule") {
                            Text("${schedule.dailyPresenceMinutes} minutes at the shop · ${schedule.dailyBreakMinutes} planned break minutes")
                            Text("Working days: ${weekdays(schedule.workingWeekdayMask)} · effective ${schedule.effectiveFrom}")
                            Text("A plan does not record an actual break or activate pay.", style = MaterialTheme.typography.bodySmall)
                        } } }
                        item { SectionCard(Modifier.fillMaxWidth(), title = "Clock in / out") {
                            val open = state.openAttendance
                            Text(if (open == null) "No open clock-in in this verified month." else "Arrived ${timeStamp(open.clockInAt, summary.timezone)}")
                            if (!state.currentMonth) Text("Open the current company month to clock in or out.")
                            ErpButton(if (open == null) "Review clock-in" else "Review clock-out", { onClock(open != null) },
                                enabled = canWrite && state.currentMonth, modifier = Modifier.fillMaxWidth().testTag("staff-time-clock"))
                        } }
                    }
                    "history" -> {
                        if (summary.entries.isEmpty()) item { SectionCard(Modifier.fillMaxWidth()) { Text("No attendance overlaps this month.") } }
                        items(summary.entries, key = { it.id }) { row ->
                            SectionCard(Modifier.fillMaxWidth()) {
                                Text("Arrival ${timeStamp(row.clockInAt, summary.timezone)}")
                                Text("Departure ${row.clockOutAt?.let { timeStamp(it, summary.timezone) } ?: "Still open"}")
                                val known = row.clockOutAt != null && (row.breakConfirmed || row.breakMinutes > 0)
                                Text(if (known) "Recorded break: ${row.breakMinutes} minutes" else "Actual break: not confirmed")
                                if (row.id in summary.reviewAttendanceIds || (row.clockOutAt != null && !known)) Text("Owner review required", color = MaterialTheme.colorScheme.error)
                                Text("Branch ${row.branchId} · ${row.source}", style = MaterialTheme.typography.bodySmall)
                                if (state.canManage) ErpButton("Review correction", { onCorrection(row) }, enabled = canWrite,
                                    intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    "pay" -> if (state.canManage) {
                        item { StaffPayEstimateCard(summary) }
                        item { SectionCard(Modifier.fillMaxWidth(), title = "Draft terms only") {
                            Text("Payable amount: not activated. Overtime pay: not activated.")
                            summary.policy?.let { p ->
                                Text("Draft monthly amount: ${p.monthlySalaryMinor.asRupees()}")
                                Text("Revision ${p.revision} · effective ${p.effectiveFrom}")
                                Text("${p.dailyPresenceMinutes} planned presence minutes / ${p.dailyBreakMinutes} planned break minutes · ${weekdays(p.workingWeekdayMask)}")
                                if (!summary.policyCoversFullMonth) Text("These terms do not cover the full selected month. No monthly estimate is inferred.")
                            } ?: Text("No draft terms apply to this month.")
                            ErpButton("Review new draft revision", onPolicy, enabled = canWrite && state.policyHistoryLoaded,
                                modifier = Modifier.fillMaxWidth().testTag("staff-time-policy"))
                        } }
                        items(state.policies, key = { "policy-${it.id}" }) { p -> SectionCard(Modifier.fillMaxWidth(), title = "Revision ${p.revision}") {
                            Text("Effective ${p.effectiveFrom} · ${p.monthlySalaryMinor.asRupees()} per month · draft")
                            Text("${p.dailyPresenceMinutes} presence / ${p.dailyBreakMinutes} planned break minutes · ${weekdays(p.workingWeekdayMask)}")
                            Text("Reason: ${p.reason}")
                            Text(timeStamp(p.createdAt, summary.timezone), style = MaterialTheme.typography.bodySmall)
                        } }
                        if (state.morePolicies) item { ErpButton("Load older revisions", onOlderPolicies, enabled = !blocked, intent = ActionIntent.Secondary) }
                    }
                }
            }
        }
    }
    if (peopleOpen && state.canManage) LiquidAlertDialog(onDismissRequest = { peopleOpen = false },
        title = { Text("Choose one person") }, confirmButton = {}, dismissButton = {
            TextButton({ peopleOpen = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }, text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ErpButton("My time", { peopleOpen = false; onPerson(state.actorId) }, intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth()) }
                items(state.people.filter { it.id != state.actorId }, key = { it.id }) { person ->
                    ErpButton(person.name + if (person.departed) " · departed / history" else if (person.status != "active") " · suspended" else "",
                        { peopleOpen = false; onPerson(person.id) }, intent = ActionIntent.Secondary, modifier = Modifier.fillMaxWidth())
                }
                if (state.nextPeopleCursor != null) item { ErpButton("Load more people", onMorePeople, enabled = !blocked, intent = ActionIntent.Secondary) }
            }
        })
    state.draft?.let { d -> TimeDraftDialog(d, state.busy, state.error, onEdit, onCloseDraft, onConfirm) }
    if (retryReview && state.pending != null) LiquidAlertDialog(onDismissRequest = { retryReview = false },
        title = { Text("Retry the retained request?") }, text = { Text("This repeats the original request with its original key and unchanged fields. It does not create another clock event or draft revision.") },
        confirmButton = { ErpButton("Retry exact request", { retryReview = false; onRetryExact() }, enabled = state.fresh && !state.busy) },
        dismissButton = { TextButton({ retryReview = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    if (rejectionReview && state.pending?.rejectedStatus != null) LiquidAlertDialog(onDismissRequest = { rejectionReview = false },
        title = { Text("Acknowledge server rejection") }, text = { Text("The server returned HTTP ${state.pending.rejectedStatus} without confirming a write. Keep the rejected request in local evidence and review corrected fields before a new confirmation.") },
        confirmButton = { ErpButton("Keep rejection evidence", { rejectionReview = false; onAcknowledgeRejected() }, enabled = state.fresh && !state.busy) },
        dismissButton = { TextButton({ rejectionReview = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}

@Composable
private fun TimeDraftDialog(draft: TimeEditDraft, busy: Boolean, error: String?, onEdit: ((TimeEditDraft) -> TimeEditDraft) -> Unit, onClose: () -> Unit, onConfirm: () -> Unit) {
    LiquidAlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(timeAction(draft.kind)) },
        properties = DialogProperties(usePlatformDefaultWidth = false), modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(0.94f).imePadding(),
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(draft.targetName, style = MaterialTheme.typography.titleMedium)
            Text("Verified person ${draft.targetId}", style = MaterialTheme.typography.bodySmall)
            when (draft.kind) {
                TimeWriteKind.SELF_IN, TimeWriteKind.OWNER_IN -> Text("The server records arrival when this request is confirmed.")
                TimeWriteKind.SELF_OUT, TimeWriteKind.OWNER_OUT -> {
                    Text("Arrival UTC ${draft.attendance?.clockInAt}")
                    TimeField("Actual break in minutes — enter 0 if none", draft.breakText, busy, KeyboardType.Number) { v -> onEdit { it.copy(breakText = v) } }
                    Text("The planned break is not an actual break.", style = MaterialTheme.typography.bodySmall)
                }
                TimeWriteKind.CORRECTION -> {
                    Text("Enter actual UTC timestamps ending in Z or +00:00. An empty departure keeps the entry open.")
                    TimeField("Arrival UTC", draft.inUtc, busy) { v -> onEdit { it.copy(inUtc = v) } }
                    TimeField("Departure UTC, or empty if open", draft.outUtc, busy) { v -> onEdit { it.copy(outUtc = v) } }
                    TimeField("Actual break minutes, including explicit 0", draft.breakText, busy, KeyboardType.Number) { v -> onEdit { it.copy(breakText = v) } }
                }
                TimeWriteKind.POLICY -> {
                    Text("Draft revision ${draft.expectedRevision + 1}. These terms cannot activate payable money or overtime.")
                    TimeField("Effective date YYYY-MM-DD", draft.effectiveFrom, busy) { v -> onEdit { it.copy(effectiveFrom = v) } }
                    TimeField("Draft monthly amount", draft.salaryText, busy, KeyboardType.Decimal) { v -> onEdit { it.copy(salaryText = v) } }
                    TimeField("Planned presence minutes per day", draft.presenceText, busy, KeyboardType.Number) { v -> onEdit { it.copy(presenceText = v) } }
                    TimeField("Planned break minutes per day", draft.plannedBreakText, busy, KeyboardType.Number) { v -> onEdit { it.copy(plannedBreakText = v) } }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, name ->
                            FilterChip(draft.weekdayMask and (1 shl i) != 0, { onEdit { it.copy(weekdayMask = it.weekdayMask xor (1 shl i)) } },
                                enabled = !busy, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    TextButton({ onEdit { it.copy(weekdayMask = 127) } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Every day") }
                }
            }
            if (draft.kind in setOf(TimeWriteKind.OWNER_IN, TimeWriteKind.OWNER_OUT, TimeWriteKind.CORRECTION, TimeWriteKind.POLICY)) {
                TimeField("Audit reason (1–500 characters)", draft.reason, busy) { v -> onEdit { it.copy(reason = v) } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { ErpButton("Confirm ${timeAction(draft.kind).lowercase()}", onConfirm, enabled = !busy, busy = busy, modifier = Modifier.heightIn(min = 48.dp)) },
        dismissButton = { TextButton(onClose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
}
@Composable private fun TimeField(label: String, value: String, busy: Boolean, keyboard: KeyboardType = KeyboardType.Ascii, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, label = { Text(label) }, enabled = !busy, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
}
private fun timeAction(kind: TimeWriteKind): String = when (kind) {
    TimeWriteKind.SELF_IN, TimeWriteKind.OWNER_IN -> "Clock-in"
    TimeWriteKind.SELF_OUT, TimeWriteKind.OWNER_OUT -> "Clock-out"
    TimeWriteKind.CORRECTION -> "Attendance correction"
    TimeWriteKind.POLICY -> "Draft pay terms"
}
internal fun timeDuration(seconds: Long): String = "${seconds / 3600}h ${(seconds % 3600) / 60}m ${seconds % 60}s"
private fun timeStamp(value: String, timezone: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss z").format(Instant.parse(value).atZone(ZoneId.of(timezone)))
}.getOrElse { "Needs Owner time review" }
private fun weekdays(mask: Int): String = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    .filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
