package cloud.dcompany.erp.ui.screens.audit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.ui.components.*
import cloud.dcompany.erp.ui.screens.ReceiptInvestigationPanel
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Composable
fun AuditLogScreen(
    vm: AuditLogViewModel = viewModel(),
    initialReference: String? = null,
) {
    val profile by DCompanyApp.instance.shiftCache.profile.collectAsStateWithLifecycle()
    val current by vm.state.collectAsStateWithLifecycle()
    val allowed = profile?.let { it.auditAccess && it.userId.isNotBlank() && it.companyId.isNotBlank() } == true
    val ownerKey = profile?.takeIf { allowed }?.let { "${it.companyId}|${it.userId}|${it.branchId.orEmpty()}" }
    SideEffect {
        vm.bindIdentity(profile?.userId, profile?.companyId, profile?.branchId, allowed)
        vm.checkVerification()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ownerKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) vm.checkVerification()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Neither an earlier account nor an expired grant may be rendered while effects run.
    val state = if (current.ownerScopeKey == ownerKey && (current.locked || vm.verificationIsCurrent())) current
        else AuditLogUiState(allowed = allowed, unlockError = if (!current.locked) "Owner verification expired. Unlock to continue." else null)
    var password by remember(ownerKey) { mutableStateOf("") }
    var expandedFilters by remember(ownerKey) { mutableStateOf(false) }
    LaunchedEffect(ownerKey, initialReference) { initialReference?.let(vm::reference) }
    LaunchedEffect(state.locked) { password = "" }
    LaunchedEffect(ownerKey, state.locked) {
        if (!state.locked) while (true) { delay(30_000); vm.checkActivity() }
    }
    val busy = state.loading || state.loadingMore || state.unlocking || state.checking
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (state.locked) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Activity you can trace", style = MaterialTheme.typography.headlineSmall)
                Text("Business actions, their results and the evidence behind them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionCard(title = "Protected owner verification", modifier = Modifier.widthIn(max = 560.dp)) {
                    Text(if (allowed) "Use your current account password. Verification stays only in memory and locks automatically."
                        else "This account does not have protected Audit access.")
                    if (allowed) {
                        OutlinedTextField(value = password, onValueChange = { password = it },
                            label = { Text("Owner password") }, visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, enabled = !state.unlocking, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { val value=password; password=""; vm.unlock(value, ownerKey) }))
                        state.unlockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        ErpButton("Unlock activity", { val value=password; password=""; vm.unlock(value, ownerKey) },
                            enabled = password.isNotBlank() && !state.unlocking, busy = state.unlocking)
                    }
                }
            }
        } else {
            AuditHeader(state, vm, busy)
            AuditSearch(state, vm, busy, expandedFilters) { expandedFilters = !expandedFilters }
            if (state.newEntriesAvailable) OperationalBanner("New matching activity", "Your loaded history is unchanged. Refresh when you are ready to review a new snapshot.",
                UiTone.Neutral, Icons.Default.History, action = { TextButton(vm::refresh, enabled = !busy) { Text("Refresh history") } })
            state.error?.let { OperationalBanner("History needs attention", it, UiTone.Neutral, Icons.Default.History) }
            val page = state.page
            Text(if (page == null) "Results will use server-recorded shop dates."
                else "${state.entries.size} loaded of ${page.matchingCount} events in this snapshot · ${page.shopTimezone} · server-recorded shop dates",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = rememberLazyListState(state.scrollIndex, state.scrollOffset)
            LaunchedEffect(list, ownerKey) {
                snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                    .distinctUntilChanged().collect { (index,offset) -> vm.rememberViewport(index,offset) }
            }
            LaunchedEffect(state.viewportReset) { list.scrollToItem(state.scrollIndex, state.scrollOffset) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state.loading && state.entries.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!state.loading && state.entries.isEmpty()) item {
                    SectionCard(title = if(state.error == null) "No matching activity" else "Results unavailable") {
                        Text(if(state.error == null) "Try a different shop date, action, staff member or reference. This is an event count, not revenue."
                            else "Check the message above and retry. A service error is not an empty history.")
                    }
                }
                items(state.entries, key = { it.id }) { entry ->
                    SectionCard(contentPadding = PaddingValues(Spacing.md)) {
                        TextButton({ if(state.selected?.id == entry.id) vm.dismissDetails() else vm.select(entry) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(auditEntryTitle(entry), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(auditBusinessSummary(entry), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${auditActor(entry)} · ${auditBusinessLabel(entry.stage)} / ${auditBusinessLabel(entry.outcome)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${formatAuditTimestamp(entry.createdAt, auditZone(page?.shopTimezone))} · ${page?.shopTimezone ?: "shop timezone unavailable"}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if(state.selected?.id == entry.id) AuditInlineDetail(entry, page?.shopTimezone, ownerKey.orEmpty(), state)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        if(page?.hasMore == true) ErpButton("Load older activity", vm::loadMore, enabled = !busy && !state.snapshotExpired, busy = state.loadingMore)
                        else if(page != null) Text("End of this selected history snapshot.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditHeader(state: AuditLogUiState, vm: AuditLogViewModel, busy: Boolean) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column(Modifier.weight(1f)) {
            Text("Business activity", style = MaterialTheme.typography.titleLarge)
            Text("${state.page?.scope?.let(::auditBusinessLabel) ?: "Protected owner"} · names are current account labels", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ErpButton("Refresh", vm::refresh, intent = ActionIntent.Secondary, enabled = !busy, busy = state.loading)
        ErpButton("Lock", vm::lock, intent = ActionIntent.Secondary, leadingIcon = Icons.Default.Lock)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditSearch(state: AuditLogUiState, vm: AuditLogViewModel, busy: Boolean, expanded: Boolean, toggle: () -> Unit) {
    val f=state.filters
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val queryField: @Composable (Modifier) -> Unit = { fieldModifier ->
                OutlinedTextField(f.query,{ value -> vm.changeFilters { it.copy(query=value.take(200)) } },
                    label={Text("Search code, action or retained facts")}, modifier=fieldModifier, singleLine=true, enabled=!busy,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={vm.find()}))
            }
            val actions: @Composable () -> Unit = {
                ErpButton("Find",vm::find,enabled=!busy)
                ErpButton(if(expanded) "Fewer filters" else "Filters",toggle,intent=ActionIntent.Secondary)
            }
            if(maxWidth < 650.dp) Column(verticalArrangement=Arrangement.spacedBy(Spacing.sm)) {
                queryField(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(Spacing.sm)) { actions() }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(Spacing.sm)) {
                queryField(Modifier.weight(1f)); actions()
            }
        }
        if(f != state.appliedFilters) Text("Press Find to apply these filters.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(expanded) {
            Column(Modifier.heightIn(max=260.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(Spacing.sm)) {
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Spacing.sm),verticalArrangement=Arrangement.spacedBy(Spacing.sm)) {
                AuditChoice("View",f.view,listOf("business" to "Business actions","all" to "All, including technical"),!busy) { value -> vm.changeFilters { it.copy(view=value ?: "business") } }
                AuditChoice("Category",f.category,listOf(null to "All categories")+state.catalogue?.categories.orEmpty().map { it to auditBusinessLabel(it) },!busy) { value -> vm.changeFilters { it.copy(category=value,code=null) } }
                AuditChoice("Action",f.code,listOf(null to "All actions")+state.catalogue?.actions.orEmpty().filter { f.category==null || it.category==f.category }.map { it.code to "${it.code} · ${it.label}" },!busy) { value -> vm.changeFilters { it.copy(code=value) } }
                AuditChoice("Result",f.outcome,listOf(null to "All results")+state.catalogue?.outcomes.orEmpty().map { it to auditBusinessLabel(it) },!busy) { value -> vm.changeFilters { it.copy(outcome=value) } }
                val staff=listOf(null to "All accounts")+state.staff.map { it.id to "${it.name} · current account" }
                AuditChoice("Actor",f.actorUserId,staff,!busy) { value -> vm.changeFilters { it.copy(actorUserId=value) } }
                AuditChoice("Target account",f.targetUserId,staff,!busy) { value -> vm.changeFilters { it.copy(targetUserId=value) } }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow=maxWidth < 700.dp
                val fields: @Composable (Modifier) -> Unit = { fieldModifier ->
                    OutlinedTextField(f.reference,{value->vm.changeFilters{it.copy(reference=value.take(120))}},label={Text("Exact bill / receipt reference")},singleLine=true,enabled=!busy,modifier=fieldModifier)
                    OutlinedTextField(f.dateFrom,{value->vm.changeFilters{it.copy(dateFrom=value.take(10))}},label={Text("From · YYYY-MM-DD")},singleLine=true,enabled=!busy,modifier=fieldModifier)
                    OutlinedTextField(f.dateTo,{value->vm.changeFilters{it.copy(dateTo=value.take(10))}},label={Text("To · YYYY-MM-DD")},singleLine=true,enabled=!busy,modifier=fieldModifier)
                }
                if(narrow) Column(verticalArrangement=Arrangement.spacedBy(Spacing.sm)) { fields(Modifier.fillMaxWidth()) }
                else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Spacing.sm)) { fields(Modifier.weight(1f)) }
            }
            state.staffError?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
internal fun AuditChoice(label:String,value:String?,options:List<Pair<String?,String>>,enabled:Boolean=true,onSelect:(String?)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({open=true},enabled=enabled) { Text("$label · ${options.firstOrNull{it.first==value}?.second ?: "Retained account identity"}") }
        DropdownMenu(open,{open=false}) { options.forEach { (key,text) -> DropdownMenuItem(text={Text(text)},onClick={open=false;onSelect(key)}) } }
    }
}

@Composable
private fun AuditInlineDetail(entry:AuditEntry,zone:String?,ownerKey:String,state:AuditLogUiState) {
    var technical by remember(entry.id) { mutableStateOf(false) }
    var bill by remember(entry.id) { mutableStateOf(false) }
    val timezone=auditZone(zone)
    Column(verticalArrangement=Arrangement.spacedBy(Spacing.sm),modifier=Modifier.fillMaxWidth().padding(top=Spacing.sm)) {
        HorizontalDivider()
        AuditFact("Action",entry.actionLabel)
        AuditFact("Stage / result","${auditBusinessLabel(entry.stage)} · ${auditBusinessLabel(entry.outcome)}")
        AuditFact("Actor",auditActor(entry))
        AuditFact("Name provenance",auditBusinessLabel(entry.actorLabelSource))
        entry.targetUserId?.let { target ->
            AuditFact("Targeted account",state.staff.firstOrNull{it.id==target}?.name?.let{"$it · current account label"} ?: "Retained account identity; current label unavailable")
        }
        AuditFact("Classification",when(entry.classificationSource){"recorded"->"Recorded at action time";"derived_legacy"->"Derived from retained legacy evidence";else->"Other / legacy · not classified"})
        entry.matchExplanation?.let { AuditFact("Why this matched",it) }
        entry.sourceReference?.let { AuditFact("Business reference",it) }
        auditBusinessFacts(entry).forEach { (key,value)->AuditFact(auditBusinessLabel(key),auditBusinessValue(key,value,timezone)) }
        auditBusinessChanges(entry).take(12).forEach { (key,before,after) ->
            AuditFact("${auditBusinessLabel(key)} · Before → After", "${auditBusinessValue(key,before,timezone)} → ${auditBusinessValue(key,after,timezone)}")
        }
        if(auditBusinessChanges(entry).size > 12) Text("More retained fields are available in Technical details.",style=MaterialTheme.typography.bodySmall)
        entry.reason?.let { AuditFact("Reason",it) }
        AuditFact("Server recorded","${formatAuditTimestamp(entry.createdAt,timezone)} · ${zone ?: "timezone unavailable"}")
        entry.clientReportedAt?.let { AuditFact("Client-reported occurrence","${formatAuditTimestamp(it,timezone)} · ${zone ?: "timezone unavailable"} · reported, not independently verified") }
        entry.syncedAt?.let { AuditFact("Server received / synced","${formatAuditTimestamp(it,timezone)} · ${zone ?: "timezone unavailable"}") }
        AuditFact("Capture evidence",auditConnectionLabel(entry))
        auditOrderId(entry)?.let { orderId ->
            TextButton({bill=!bill}) { Text(if(bill) "Hide linked bill" else "Investigate linked bill") }
            if(bill) ReceiptInvestigationPanel(orderId=orderId,ownerScopeKey=ownerKey,shopTimezone=zone)
        }
        TextButton({technical=!technical}) { Text(if(technical) "Hide technical details" else "Technical details") }
        if(technical) {
            AuditFact("Event / operation","${entry.id} · ${entry.operationId ?: "Operation identity not retained"}")
            AuditFact("Source identity","${entry.entityType} · ${entry.entityId}")
            AuditFact("Actor kind",entry.actorKind)
            entry.actorUserId?.let{AuditFact("Actor identity",it)}
            entry.targetUserId?.let{AuditFact("Target account identity",it)}
            entry.ip?.let{AuditFact("Recorded network address",it)}
            entry.userAgent?.let{AuditFact("Recorded user agent",it)}
            AuditFact("Reported client",auditClientLabel(entry) ?: "No client identity retained")
            entry.requestId?.let{AuditFact("Request",it)}
            entry.clientActionId?.let{AuditFact("Client action",it)}
            entry.terminalId?.let{AuditFact("Terminal",it)}
            listOf("Business facts" to entry.businessDetails,"Before" to entry.before,"After" to entry.after).forEach{(label,value)->
                Text(label,fontWeight=FontWeight.SemiBold)
                Text(value?.let{prettyAuditJson(it)} ?: "Not recorded",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AuditFact(label:String,value:String) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium)
    }
}
private fun auditZone(value:String?):ZoneId=runCatching{ZoneId.of(value ?: "UTC")}.getOrDefault(ZoneId.of("UTC"))
private val prettyAuditFormat=Json{prettyPrint=true}
private fun prettyAuditJson(value:JsonObject):String=prettyAuditFormat.encodeToString(JsonElement.serializer(),redactAuditValue(value))
