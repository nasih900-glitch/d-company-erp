package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.CanonicalReceipt
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.ReceiptMistakeReason
import cloud.dcompany.erp.core.net.ReceiptMistakeReport
import cloud.dcompany.erp.core.net.ReceiptMistakeRequest
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.remote.RemoteSensitiveContent
import cloud.dcompany.erp.ui.screens.finance.FileTenderJournalStorage
import cloud.dcompany.erp.ui.theme.Spacing
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ReceiptMistakeAccess(val lease: CacheScopeLease, val report: Boolean, val review: Boolean)

internal fun receiptMistakeAccess(profile: MeResponse?, lease: CacheScopeLease?): ReceiptMistakeAccess? {
    if (profile == null || lease == null || profile.branchId == null || profile.branchId != lease.scope.branchId ||
        profile.userId != lease.scope.userId || profile.companyId != lease.scope.companyId
    ) return null
    val permissions = EffectivePermissions.from(profile)
    if (!permissions.has(ErpPermission.PosRead)) return null
    return ReceiptMistakeAccess(
        lease,
        report = permissions.has(ErpPermission.PosWrite),
        review = profile.auditAccess && profile.effectivePermissions?.contains("finance.receipt.correct") == true,
    ).takeIf { it.report || it.review }
}

internal data class ReceiptMistakeSubmitState(
    val attempt: ReceiptMistakeAttempt? = null,
    val checked: Boolean = false,
    val busy: Boolean = false,
    val acknowledgedKey: String? = null,
    val error: String? = null,
) {
    val sent: Boolean get() = acknowledgedKey != null
}

private fun ReceiptMistakeRecord?.submissionState() = ReceiptMistakeSubmitState(
    attempt = this?.attempt,
    checked = true,
    acknowledgedKey = this?.acknowledgedKey,
)

internal data class ReceiptMistakeReviewState(
    val items: List<ReceiptMistakeReport> = emptyList(),
    val nextBefore: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val selectedOrderId: String? = null,
    val selectedReceipt: CanonicalReceipt? = null,
    val detailBusy: Boolean = false,
    val detailError: String? = null,
)

internal class ReceiptMistakeReportsViewModel : ViewModel() {
    private val app = DCompanyApp.instance
    private val journal = ReceiptMistakeJournal(FileTenderJournalStorage(
        directory = { app.noBackupFilesDir.toPath().resolve("receipt-mistake-retries-v1") },
    ))
    private val access = receiptMistakeAccess(app.shiftCache.profile.value, app.cacheIsolation.currentLease())
    private val _reports = MutableStateFlow(ReceiptMistakeReviewState())
    val reports = _reports.asStateFlow()
    private val _submissions = MutableStateFlow<Map<String, ReceiptMistakeSubmitState>>(emptyMap())
    val submissions = _submissions.asStateFlow()
    private var reportsGeneration = 0L
    private var detailGeneration = 0L

    init {
        viewModelScope.launch {
            app.cacheIsolation.activeLeaseFlow.collect { lease ->
                if (lease != access?.lease) clear()
            }
        }
        viewModelScope.launch {
            app.shiftCache.profile.collect { latestProfile ->
                if (receiptMistakeAccess(latestProfile, app.cacheIsolation.currentLease()) != access) clear()
            }
        }
    }

    private fun clear() {
        reportsGeneration++
        detailGeneration++
        _submissions.value = emptyMap()
        _reports.value = ReceiptMistakeReviewState()
    }

    private fun verifiedScope(): ReceiptMistakeAccess? = access?.takeIf {
        receiptMistakeAccess(app.shiftCache.profile.value, app.cacheIsolation.currentLease()) == it
    }

    private fun operations(scope: ReceiptMistakeAccess) =
        app.handoverOperations.caller(app.shiftCache.profile.value, scope.lease)

    private fun reportingScope(receipt: CanonicalReceipt): ReceiptMistakeAccess? = verifiedScope()?.takeIf {
        it.report && receipt.companyId == it.lease.scope.companyId &&
            receipt.branchId == it.lease.scope.branchId && receipt.status in setOf("paid", "refunded")
    }

    fun restoreSubmission(receipt: CanonicalReceipt) {
        val scope = reportingScope(receipt) ?: return
        val orderId = receipt.orderId
        val current = _submissions.value[orderId]
        if (current?.busy == true || current?.checked == true) return
        val loading = ReceiptMistakeSubmitState(busy = true)
        _submissions.update { it + (orderId to loading) }
        viewModelScope.launch {
            val restored = try {
                val key = receiptMistakeJournalKey(BuildConfig.API_BASE_URL, scope.lease.scope, orderId)
                journal.read(key).submissionState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ReceiptMistakeSubmitState(error = "The tablet could not read the saved report. Retry before sending.")
            }
            if (verifiedScope() == scope && _submissions.value[orderId] === loading) {
                _submissions.update { it + (orderId to restored) }
            }
        }
    }

    fun submit(receipt: CanonicalReceipt, draft: ReceiptMistakeRequest?, fromAcknowledgement: String?) {
        val scope = reportingScope(receipt) ?: return
        val orderId = receipt.orderId
        val current = _submissions.value[orderId] ?: ReceiptMistakeSubmitState()
        if (current.busy) return
        if (!current.checked) {
            restoreSubmission(receipt)
            return
        }
        if (current.acknowledgedKey != fromAcknowledgement) return
        if (!app.connectivity.online.value || (current.attempt == null && draft == null)) return
        val key = receiptMistakeJournalKey(BuildConfig.API_BASE_URL, scope.lease.scope, orderId)
        val busy = current.copy(busy = true, error = null)
        fun publish(result: ReceiptMistakeSubmitState) {
            if (verifiedScope() == scope && _submissions.value[orderId] === busy) {
                _submissions.update { it + (orderId to result) }
            }
        }
        _submissions.update { it + (orderId to busy) }
        operations(scope).launch(viewModelScope, onRejected = {
            publish(current.copy(error = "The till is still being verified. Retry when the workspace is ready."))
        }) {
            try {
                val dispatched = journal.dispatch(key) {
                    val prepared = journal.prepare(key, draft, current.acknowledgedKey)
                    if (prepared == null) {
                        publish(journal.read(key).submissionState())
                        return@dispatch
                    }
                    if (verifiedScope() != scope) return@dispatch
                    val attempt = prepared.attempt
                    if (!prepared.newlySaved && current.attempt != attempt) {
                        publish(ReceiptMistakeSubmitState(attempt = attempt, checked = true))
                        return@dispatch
                    }
                    val failure = try {
                        val result = ApiClient.api.reportReceiptMistake(orderId, attempt.key, attempt.body)
                        check(result.orderId == orderId && UUID.fromString(result.id).toString() == result.id)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        error
                    }
                    if (failure == null) {
                        try {
                            journal.finish(key, attempt, acknowledgedKey = attempt.key)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            publish(ReceiptMistakeSubmitState(
                                error = "The server saved the report. Check the tablet’s saved copy before sending again.",
                            ))
                            return@dispatch
                        }
                        publish(ReceiptMistakeSubmitState(checked = true, acknowledgedKey = attempt.key))
                        return@dispatch
                    }
                    val api = failure as? ApiException
                    // A later rejection cannot resolve an earlier lost response for this key.
                    val keep = !prepared.newlySaved || api == null || api.mustPreserveOutbox || api.status == 429
                    if (!keep) journal.finish(key, attempt, current.acknowledgedKey)
                    publish(ReceiptMistakeSubmitState(
                        attempt = attempt.takeIf { keep },
                        checked = true,
                        acknowledgedKey = current.acknowledgedKey.takeUnless { keep },
                        error = reportError(failure),
                    ))
                }
                if (!dispatched) publish(ReceiptMistakeSubmitState(error = "This report is already being sent. Retry in a moment."))
            } catch (cancelled: CancellationException) {
                publish(ReceiptMistakeSubmitState(error = "The report was interrupted. Check the saved report before retrying."))
                throw cancelled
            } catch (_: Exception) {
                publish(ReceiptMistakeSubmitState(error = "The tablet could not verify the saved report. Retry before sending."))
            }
        }
    }

    fun loadReports(more: Boolean = false) {
        val scope = verifiedScope()?.takeIf { it.review } ?: return
        val current = _reports.value
        if (current.busy || current.selectedOrderId != null) return
        val cursor = if (more) current.nextBefore ?: return else null
        val generation = ++reportsGeneration
        _reports.update { it.copy(busy = true, error = null) }
        fun failed(failure: Throwable) {
            if (verifiedScope() == scope && generation == reportsGeneration) {
                _reports.update { it.copy(busy = false, error = reportError(failure)) }
            }
        }
        operations(scope).launch(viewModelScope, onRejected = ::failed) {
            try {
                val page = ApiClient.api.receiptMistakeReports(cursor)
                check(page.nextBefore == null || page.nextBefore != cursor)
                if (verifiedScope() == scope && generation == reportsGeneration) _reports.update {
                    it.copy(items = (if (more) it.items + page.items else page.items).distinctBy(ReceiptMistakeReport::id),
                        nextBefore = page.nextBefore, busy = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure) }
        }
    }

    fun openReceipt(orderId: String) {
        val scope = verifiedScope()?.takeIf { it.review } ?: return
        val current = _reports.value
        if (current.detailBusy || current.items.none { it.orderId == orderId }) return
        val generation = ++detailGeneration
        _reports.update { it.copy(selectedOrderId = orderId, detailBusy = true, detailError = null) }
        fun publish(update: (ReceiptMistakeReviewState) -> ReceiptMistakeReviewState) {
            if (verifiedScope() == scope && generation == detailGeneration) {
                _reports.update { if (it.selectedOrderId == orderId) update(it) else it }
            }
        }
        fun failed(failure: Throwable) = publish { it.copy(detailBusy = false, detailError = reportError(failure)) }
        operations(scope).launch(viewModelScope, onRejected = ::failed) {
            try {
                val receipt = ApiClient.api.receipt(orderId)
                check(receipt.orderId == orderId && receipt.companyId == scope.lease.scope.companyId && receipt.branchId == scope.lease.scope.branchId)
                publish { it.copy(selectedReceipt = receipt, detailBusy = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure) }
        }
    }

    fun backToReports() {
        detailGeneration++
        _reports.update { it.copy(selectedOrderId = null, selectedReceipt = null, detailBusy = false, detailError = null) }
    }
}

private fun reportError(failure: Throwable): String = when ((failure as? ApiException)?.status) {
    404, 405 -> "This server could not find the receipt reporting feature or the selected receipt. Refresh and try again."
    403 -> "This account no longer has access to this report."
    409 -> "The report could not be saved with this request. Refresh the receipt and try again."
    429 -> "The reporting limit was reached. Wait a while, then retry."
    else -> "The report result could not be verified. Reconnect and retry to continue."
}

@Composable
internal fun ReceiptMistakeForm(
    state: ReceiptMistakeSubmitState,
    online: Boolean,
    onSubmit: (ReceiptMistakeRequest?, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<ReceiptMistakeReason?>(null) }
    var note by remember { mutableStateOf("") }
    var another by remember(state.acknowledgedKey) { mutableStateOf(false) }
    val showAcknowledgement = state.sent && !another
    val attempt = state.attempt
    val formVisible = !showAcknowledgement && (expanded || attempt != null || state.error != null)
    RemoteSensitiveContent(enabled = formVisible) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (showAcknowledgement) {
                Text("Mistake reported to the Owner.", modifier = Modifier.testTag("receipt-mistake-sent"))
                ErpButton("Report another mistake", {
                    reason = null
                    note = ""
                    expanded = true
                    another = true
                }, intent = ActionIntent.Secondary)
            } else if (!state.checked) {
                if (state.error == null) {
                    Text("Checking for a saved report…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    ErpButton("Retry", { onSubmit(null, null) }, intent = ActionIntent.Secondary)
                }
            } else if (!formVisible) {
                ErpButton("Report a mistake", { expanded = true }, intent = ActionIntent.Secondary,
                    modifier = Modifier.testTag("receipt-mistake-form"))
            } else {
                Text("Report a mistake", style = MaterialTheme.typography.titleMedium)
                Text("The Owner can review this receipt and decide what to do.", style = MaterialTheme.typography.bodySmall)
                if (attempt == null) {
                    ReceiptMistakeReason.entries.forEach { option ->
                        FilterChip(selected = reason == option, onClick = { reason = option }, label = { Text(option.label) })
                    }
                    Text("Keep notes to what happened. Leave out passwords and customer payment details.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note (optional)") }, maxLines = 4,
                        modifier = Modifier.fillMaxWidth())
                } else Text(attempt.body.reason.label)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!online) Text("Reconnect to send this report.", style = MaterialTheme.typography.bodySmall)
                ErpButton(
                    text = if (attempt == null || state.busy) "Send report" else "Retry report",
                    onClick = {
                        if (attempt != null) onSubmit(null, null)
                        else reason?.let {
                            onSubmit(
                                ReceiptMistakeRequest(it, note.takeIf(String::isNotBlank)),
                                state.acknowledgedKey.takeIf { another },
                            )
                        }
                    },
                    enabled = online && (attempt != null || reason != null), busy = state.busy,
                    modifier = Modifier.testTag("receipt-mistake-send"),
                )
            }
        }
    }
}

@Composable
internal fun ReceiptMistakeReview(
    state: ReceiptMistakeReviewState,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    RemoteSensitiveContent {
        LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.items.isEmpty() && state.error == null) item {
                Text(if (state.busy) "Loading reports…" else "No receipt mistakes reported for this branch.")
            }
            items(state.items, key = { it.id }) { report ->
                Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(report.invoiceNo, style = MaterialTheme.typography.titleMedium)
                    Text(report.reason.label)
                    Text("${report.reporterName} · ${report.createdAt.receiptDateTime()}", style = MaterialTheme.typography.bodySmall)
                    report.note?.let { Text(it) }
                    ErpButton("Open original receipt", { onOpen(report.orderId) }, intent = ActionIntent.Secondary)
                }
            }
            if (state.error != null) item { ErpButton("Retry reports", onRefresh, enabled = !state.busy) }
            if (state.nextBefore != null) item { ErpButton("Load older reports", onMore, enabled = !state.busy) }
        }
    }
}
