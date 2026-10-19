package cloud.dcompany.erp.ui.screens.finance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.ownerVerificationKeyboardOptions
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.decodeFromString

/** One inline workspace. Verification, evidence and final receipt stay beside their source. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun TenderWorkspace(
    context: TenderContext,
    onCommitted: (TenderReceipt) -> Unit = {},
    allowNewPreparation: Boolean = true,
    vm: TenderWorkspaceViewModel = viewModel(key = "protected-tender-${context.kind}"),
) {
    val account by DCompanyApp.instance.shiftCache.profile.collectAsStateWithLifecycle()
    val current by vm.state.collectAsStateWithLifecycle()
    val expectedScope = account?.tenderScope(context.branchId)
    val ownerKey = expectedScope?.ownerKey()
    SideEffect { vm.bind(context, account); vm.checkVerification() }
    val state = if (current.ownerScopeKey == ownerKey && current.context == context) current
        else TenderWorkspaceState(context = context, ownerScopeKey = ownerKey, allowed = expectedScope != null)
    val verified = state.allowed && current.context == context && current.ownerScopeKey == ownerKey && vm.verificationIsCurrent()
    val neededKind = state.pending?.kind ?: context.kind
    val canAct = verified && state.authorizedKind == neededKind
    var password by remember(ownerKey, context) { mutableStateOf("") }
    var historyOpen by remember(ownerKey, context) { mutableStateOf(false) }
    var notified by remember(ownerKey, context) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, ownerKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) vm.checkVerification()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(verified) { password = "" }
    LaunchedEffect(state.receipt?.id, verified) {
        val receipt = state.receipt
        if (verified && receipt != null && notified != receipt.id) { notified = receipt.id; onCommitted(receipt) }
    }
    val busy = state.busy || state.readingSavedWork
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val passwordView = remember { BringIntoViewRequester() }
    var passwordFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(passwordFocused, imeBottom) {
        if (passwordFocused) passwordView.bringIntoView()
    }
    fun verifyOwner() {
        if (busy || password.isBlank()) return
        val input = password
        password = ""
        keyboard?.hide()
        focusManager.clearFocus()
        vm.unlock(input, ownerKey, context)
    }
    SectionCard(title = if (context is TenderContext.Payment) "Correct recorded payment method" else "Review daily Cash / UPI actuals",
        subtitle = "Owner-attested evidence · original bills and recorded drawer closes stay unchanged") {
        if (!state.allowed) {
            Text("Protected owner access and correction permission are required. No correction can be sent from this account.")
            return@SectionCard
        }
        if (state.readingSavedWork) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.storageError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Text("Keep app storage. A missing or unreadable result must not become a replacement action.", style = MaterialTheme.typography.bodySmall)
            ErpButton("Check saved work again", vm::readSavedWork, intent = ActionIntent.Secondary, enabled = !busy)
        }
        val pending = state.pending
        if (pending != null) {
            Text(if (state.receipt == null) "Saved request needs its final receipt" else "Receipt recorded · saved recovery cleanup needs review", style = MaterialTheme.typography.titleSmall)
            Text(pending.targetLabel(), style = MaterialTheme.typography.bodySmall)
            Text("Recover uses the exact saved body and action key. It does not create a second correction.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!canAct) {
            Text(if (verified) "Verify owner access for ${if (neededKind == TenderKind.PAYMENT) "this payment correction" else "daily actuals"}." else "Use your current owner password. Verification stays only in memory and expires automatically.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(password, { password = it }, label = { Text("Owner password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                keyboardOptions = ownerVerificationKeyboardOptions,
                keyboardActions = KeyboardActions(onDone = { verifyOwner() }),
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(passwordView).onFocusChanged { passwordFocused = it.isFocused })
            ErpButton("Verify owner access", ::verifyOwner, enabled = !busy && password.isNotBlank(), busy = state.busy)
        }
        if (verified) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Verified owner · ${if (state.authorizedKind == TenderKind.PAYMENT) "payment purpose" else "daily actuals purpose"}", style = MaterialTheme.typography.labelMedium)
                TextButton({ password = ""; vm.lock() }) { Text("Lock") }
            }
            if (pending != null) {
                PendingTenderFacts(pending)
                ErpButton("Recover saved result", { vm.recover(pending) }, enabled = canAct && !busy, busy = state.busy)
                if (state.reviewedRejection == pending) {
                    Text("The server explicitly rejected this exact request. Review that failure before clearing its saved recovery identity.", style = MaterialTheme.typography.bodySmall)
                    ErpButton("Reviewed rejection · clear this saved request", { vm.acknowledgeRejected(pending) }, intent = ActionIntent.Secondary, enabled = !busy && canAct)
                }
            } else if (canAct && allowNewPreparation) {
                ErpButton(if (state.paymentPreview == null && state.dailyPreview == null) "Load source preview" else "Check source again",
                    vm::prepare, intent = ActionIntent.Secondary, enabled = !busy && state.savedWorkVerified && state.storageError == null)
                state.paymentPreview?.let { preview ->
                    TenderFacts(listOf("Bill" to preview.invoiceNo, "Full payment leg" to preview.amountMinor.asRupees(),
                        "Shop date" to "${preview.businessDate} · ${preview.businessTimezone}",
                        "Cash" to "${preview.beforeCashMinor.asRupees()} → ${preview.afterCashMinor.asRupees()}",
                        "UPI" to "${preview.beforeUpiMinor.asRupees()} → ${preview.afterUpiMinor.asRupees()}",
                        "Bill / collection total change" to preview.totalChangeMinor.asRupees()))
                    Text("This changes the classification of this entire Cash payment leg to UPI. No bill amount, original payment or original drawer count is overwritten.", style = MaterialTheme.typography.bodySmall)
                }
                state.dailyPreview?.let { preview ->
                    TenderFacts(listOf("Shop date" to "${preview.businessDate} · ${preview.businessTimezone}",
                        "Recorded Cash" to preview.recordedCashMinor.asRupees(), "Recorded UPI" to preview.recordedUpiMinor.asRupees(),
                        "Cash + UPI subtotal" to preview.recordedTotalMinor.asRupees()))
                    if (preview.unchangedOtherRails.isNotEmpty()) {
                        Text("Other recorded methods stay unchanged and are not independently recounted:", style = MaterialTheme.typography.bodySmall)
                        TenderFacts(preview.unchangedOtherRails.map { (method,amount) -> method.uppercase(Locale.ROOT) to amount.asRupees() })
                    }
                    Text("All recorded methods: ${preview.recordedAllRailsTotalMinor.asRupees()}. The attestation below covers Cash and UPI collections only; it is not a physical drawer count.", style = MaterialTheme.typography.bodySmall)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val fields: @Composable (Modifier) -> Unit = { modifier ->
                            OutlinedTextField(state.actualCash, { vm.edit(cash = it) }, label = { Text("Owner-confirmed Cash collected") }, modifier = modifier, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            OutlinedTextField(state.actualUpi, { vm.edit(upi = it) }, label = { Text("Owner-confirmed UPI collected") }, modifier = modifier, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                        if (maxWidth < 650.dp) Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { fields(Modifier.fillMaxWidth()) }
                        else Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { fields(Modifier.weight(1f)) }
                    }
                    dailyActualsReview(preview,state.actualCash,state.actualUpi)?.let { review ->
                        TenderFacts(listOf("Cash classification difference" to signedTenderAmount(review.cashDelta),
                            "UPI classification difference" to signedTenderAmount(review.upiDelta),
                            "Cash + UPI total difference" to signedTenderAmount(review.totalDifference)))
                        Text(when {
                            review.supportedTransfer -> "This is a zero-sum, unallocated Cash to UPI reclassification. No source bill is identified or changed."
                            review.totalDifference != 0L -> "These actuals do not balance to recorded collections. They will be retained as unresolved evidence with no financial posting."
                            else -> "This is a no-op or unsupported reverse transfer. Nothing can be posted from this preview."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.paymentPreview != null || state.dailyPreview != null) {
                    OutlinedTextField(state.reason, { vm.edit(reason = it) }, label = { Text("Reason · at least 3 characters") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    OutlinedTextField(state.evidenceReference, { vm.edit(evidence = it) }, label = { Text("Evidence / reference · optional") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(state.confirmed, vm::confirm, enabled = !busy)
                        Text(if (context is TenderContext.Payment) "I confirm this identified payment was collected by UPI, and I have reviewed the source preview."
                            else "I confirm these Cash and UPI collection actuals. Source bills are unallocated; this is not a verified bank statement or drawer count.", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    val review = dailyActualsReview(state.dailyPreview,state.actualCash,state.actualUpi)
                    val ready = state.reason.trim().length >= 3 && state.confirmed && (state.paymentPreview != null || review?.let { it.totalDifference != 0L || it.supportedTransfer } == true)
                    ErpButton(if (review?.totalDifference?.let { it != 0L } == true) "Record unresolved actuals · no posting" else "Confirm Cash → UPI", { vm.commit(state) }, enabled = ready && !busy, busy = state.busy)
                }
            }
            state.receipt?.let { TenderReceiptFacts(it, finalResult = true) }
            state.refreshError?.let {
                Text("The receipt is recorded. Some views may still show earlier figures.\n$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ErpButton("Refresh affected views · reads only", vm::refreshViews, intent = ActionIntent.Secondary, enabled = !busy)
            }
            TextButton({ historyOpen = !historyOpen }) { Text(if (historyOpen) "Hide protected correction history" else "Show protected correction history") }
            if (historyOpen) {
                Text("Newest 50 matching receipts. A bounded or unavailable history is not proof that no other action exists.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErpButton("Refresh protected history", vm::refreshHistory, intent = ActionIntent.Secondary, enabled = !busy)
                state.historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.history.isEmpty() && state.historyError == null) Text("No matching correction receipts were returned.", style = MaterialTheme.typography.bodySmall)
                state.history.forEach { TenderReceiptFacts(it) }
            }
        }
    }
}

@Composable
private fun PendingTenderFacts(command: PendingTenderCommand) {
    val facts = when (command.kind) {
        TenderKind.PAYMENT -> journalJson.decodeFromString<PaymentTenderCommit>(command.bodyJson).let {
            listOf("Saved payment identity" to it.paymentId, "Reason" to it.reason, "Evidence" to (it.evidenceReference ?: "Not provided"))
        }
        TenderKind.DAILY -> journalJson.decodeFromString<DailyTenderCommit>(command.bodyJson).let {
            listOf("Saved shop date" to it.businessDate, "Confirmed Cash collected" to it.actualCashMinor.asRupees(),
                "Confirmed UPI collected" to it.actualUpiMinor.asRupees(), "Reason" to it.reason,
                "Evidence" to (it.evidenceReference ?: "Not provided"))
        }
    }
    TenderFacts(facts)
    var technical by remember(command) { mutableStateOf(false) }
    TextButton({ technical = !technical }) { Text(if (technical) "Hide recovery identity" else "Recovery identity") }
    if (technical) Text("${command.idempotencyKey}\nCompany ${command.scope.companyId}\nOwner ${command.scope.ownerId}\nBranch ${command.scope.branchId}", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun TenderReceiptFacts(row: TenderReceipt, finalResult: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HorizontalDivider()
        Text(if (finalResult) "Recorded · ${row.receiptReference}" else row.receiptReference, style = MaterialTheme.typography.titleSmall)
        if (row.paymentId != null) {
            TenderFacts(listOf("Bill" to (row.invoiceNo ?: "Invoice label unavailable"),
                "Payment amount" to (row.amountMinor?.asRupees() ?: "Not returned"),
                "Originally recorded → Effective" to "${row.originalMethod?.uppercase(Locale.ROOT) ?: "Unknown"} → ${row.effectiveMethod?.uppercase(Locale.ROOT) ?: "Unknown"}"))
            row.originalTenderedMinor?.let { Text("Original Cash tender ${it.asRupees()} · original change ${row.originalChangeMinor?.asRupees() ?: "unknown"}", style = MaterialTheme.typography.bodySmall) }
        } else {
            TenderFacts(listOf("Recorded Cash → Owner actual" to "${row.beforeCashMinor?.asRupees() ?: "Unknown"} → ${row.actualCashMinor?.asRupees() ?: "Unknown"}",
                "Recorded UPI → Owner actual" to "${row.beforeUpiMinor?.asRupees() ?: "Unknown"} → ${row.actualUpiMinor?.asRupees() ?: "Unknown"}",
                "Cash posting change" to (row.cashDeltaMinor?.let(::signedTenderAmount) ?: "Unavailable"),
                "UPI posting change" to (row.upiDeltaMinor?.let(::signedTenderAmount) ?: "Unavailable"),
                "Cash + UPI total difference" to signedTenderAmount(row.totalDifferenceMinor)))
            Text(if (row.financialEffect) "Applied as an unallocated daily classification adjustment. Source bills and recorded shift closes remain unchanged."
                else "Unresolved owner actuals retained. No financial posting, bill correction, refund or drawer-count change.", style = MaterialTheme.typography.bodySmall)
            Text("Other methods were outside this Cash / UPI attestation and remain unchanged.", style = MaterialTheme.typography.bodySmall)
        }
        TenderFacts(listOf("Shop date" to "${row.businessDate} · ${row.businessTimezone}", "Evidence basis" to "Owner attestation · ${row.sourceAllocation.replace('_',' ')}",
            "Recorded" to tenderTimestamp(row.recordedAt,row.businessTimezone), "Reason" to row.reason))
        row.evidenceReference?.let { Text("Evidence / reference · $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TenderFacts(facts: List<Pair<String,String>>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 1000.dp) 3 else if (maxWidth >= 650.dp) 2 else 1
        val cell = (maxWidth - Spacing.md * (columns - 1)) / columns
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            facts.forEach { (label,value) -> Column(Modifier.width(cell), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            } }
        }
    }
}
internal fun signedTenderAmount(amount: Long): String = if (amount > 0) "+${amount.asRupees()}" else amount.asRupees()
private fun tenderTimestamp(value: String, zone: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss",Locale.ENGLISH).withZone(ZoneId.of(zone)).format(Instant.parse(value)) + " · $zone"
}.getOrDefault("Recorded timestamp unavailable")
