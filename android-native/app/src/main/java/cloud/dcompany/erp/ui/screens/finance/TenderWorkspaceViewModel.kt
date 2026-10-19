package cloud.dcompany.erp.ui.screens.finance

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

internal sealed interface TenderContext {
    val branchId: String
    val kind: TenderKind
    data class Payment(override val branchId: String, val paymentId: String, val invoiceNo: String) : TenderContext {
        override val kind = TenderKind.PAYMENT
    }
    data class Daily(override val branchId: String, val businessDate: String) : TenderContext {
        override val kind = TenderKind.DAILY
    }
}

internal data class TenderWorkspaceState(
    val context: TenderContext? = null,
    val ownerScopeKey: String? = null,
    val allowed: Boolean = false,
    val readingSavedWork: Boolean = false,
    val savedWorkVerified: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val storageError: String? = null,
    val historyError: String? = null,
    val authorizedKind: TenderKind? = null,
    val pending: PendingTenderCommand? = null,
    val paymentPreview: PaymentTenderPreview? = null,
    val dailyPreview: DailyTenderPreview? = null,
    val receipt: TenderReceipt? = null,
    val history: List<TenderReceipt> = emptyList(),
    val reason: String = "",
    val evidenceReference: String = "",
    val actualCash: String = "",
    val actualUpi: String = "",
    val confirmed: Boolean = false,
    val reviewedRejection: PendingTenderCommand? = null,
)

internal data class DailyActualsReview(val cashMinor: Long, val upiMinor: Long, val cashDelta: Long, val upiDelta: Long, val totalDifference: Long) {
    val supportedTransfer: Boolean get() = totalDifference == 0L && cashDelta < 0L && upiDelta > 0L
}

/** Display-only proposal. The server re-reads locked sources and derives posting deltas. */
internal fun dailyActualsReview(preview: DailyTenderPreview?, cash: String, upi: String): DailyActualsReview? {
    if (preview == null) return null
    val actualCash = parseRupeesToMinor(cash)?.takeIf { it <= 9_000_000_000_000_000L } ?: return null
    val actualUpi = parseRupeesToMinor(upi)?.takeIf { it <= 9_000_000_000_000_000L } ?: return null
    return runCatching {
        DailyActualsReview(actualCash, actualUpi,
            Math.subtractExact(actualCash, preview.recordedCashMinor),
            Math.subtractExact(actualUpi, preview.recordedUpiMinor),
            Math.subtractExact(Math.addExact(actualCash, actualUpi), preview.recordedTotalMinor))
    }.getOrNull()
}

internal fun TenderScope.ownerKey() = "$companyId|$ownerId|$branchId"
internal fun PendingTenderCommand.targetLabel(): String = when (kind) {
    TenderKind.PAYMENT -> "Payment ${journalJson.decodeFromString<PaymentTenderCommit>(bodyJson).paymentId}"
    TenderKind.DAILY -> "Shop date ${journalJson.decodeFromString<DailyTenderCommit>(bodyJson).businessDate}"
}

/** Online protected writes are deliberately separate from the generic financial outbox. */
internal class TenderWorkspaceViewModel(
    private val api: FinanceApi = ApiClient.create(),
    private val journal: TenderJournal = TenderJournal(privateTenderJournalStorage(DCompanyApp.instance)),
    private val profile: () -> MeResponse? = { DCompanyApp.instance.shiftCache.profile.value },
    private val suppliedScope: CoroutineScope? = null,
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallNow: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val tasks get() = suppliedScope ?: viewModelScope
    private val _state = MutableStateFlow(TenderWorkspaceState())
    val state = _state.asStateFlow()
    private var scope: TenderScope? = null
    private var grant: TenderGrant? = null
    private var generation = 0L
    private var work: Job? = null
    private var expiry: Job? = null

    fun bind(context: TenderContext, account: MeResponse?) {
        val next = account?.tenderScope(context.branchId)
        if (_state.value.context == context && scope == next) return
        val retainedGrant = grant?.takeIf { it.scope == next && elapsedNow() < it.expiresAtMillis }
        invalidate()
        scope = next
        grant = retainedGrant
        _state.value = TenderWorkspaceState(context = context, ownerScopeKey = next?.ownerKey(), allowed = next != null,
            authorizedKind = retainedGrant?.kind)
        if (retainedGrant != null) scheduleExpiry(retainedGrant)
        readSavedWork()
    }

    fun verificationIsCurrent(): Boolean {
        val active = scope ?: return false
        return authorityMatches() && grant?.let { it.scope == active && elapsedNow() < it.expiresAtMillis } == true
    }

    fun checkVerification() {
        if (scope == null) return
        if (!authorityMatches()) { revoke(); return }
        if (grant?.let { elapsedNow() >= it.expiresAtMillis } == true) lock("Owner verification expired. Saved work is retained for exact recovery.")
    }

    fun lock(message: String? = null) {
        invalidate()
        _state.value = _state.value.copy(busy = false, readingSavedWork = false, authorizedKind = null,
            paymentPreview = null, dailyPreview = null, receipt = null, history = emptyList(),
            historyError = null, confirmed = false, error = message, reviewedRejection = null)
    }

    fun readSavedWork() {
        val owner = scope ?: return
        val source = _state.value.context ?: return
        if (_state.value.busy || _state.value.readingSavedWork || !authorityMatches()) return
        val operation = ++generation
        _state.value = _state.value.copy(readingSavedWork = true, storageError = null)
        work = tasks.launch {
            try {
                val saved = journal.read(owner)
                if (!current(operation, owner, source)) return@launch
                // Another view clearing disk does not erase a retained exact recovery command.
                _state.value = _state.value.copy(pending = saved ?: _state.value.pending,
                    readingSavedWork = false, savedWorkVerified = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current(operation, owner, source)) _state.value = _state.value.copy(readingSavedWork = false,
                    savedWorkVerified = false, storageError = failure.message ?: "Saved work cannot be read. Keep app storage for recovery.")
            }
        }
    }

    fun unlock(password: String, expectedOwner: String?, expectedContext: TenderContext? = _state.value.context) {
        val owner = scope ?: return
        val source = _state.value.context ?: return
        if (source != expectedContext || owner.ownerKey() != expectedOwner || busy() || password.isBlank() || !authorityMatches()) return
        val kind = _state.value.pending?.kind ?: source.kind
        val started = elapsedNow()
        val operation = ++generation
        _state.value = _state.value.copy(busy = true, error = null)
        work = tasks.launch {
            try {
                val result = api.unlockTender(TenderUnlockRequest(owner.branchId, if (kind == TenderKind.PAYMENT) "payment" else "daily", password))
                if (!current(operation, owner, source)) return@launch
                require(result.token.isNotBlank() && result.historyToken.isNotBlank() && result.expiresIn in 1..600) { "Owner verification returned incomplete authority. Nothing was sent." }
                val next = TenderGrant(owner, kind, result.token, result.historyToken, started + result.expiresIn * 1000L)
                if (elapsedNow() >= next.expiresAtMillis) { lock("Owner verification expired. Verify again."); return@launch }
                grant = next
                scheduleExpiry(next)
                _state.value = _state.value.copy(authorizedKind = kind)
                fetchHistory(next, operation, owner, source)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current(operation, owner, source)) {
                    grant = null
                    expiry?.cancel()
                    _state.value = _state.value.copy(error = failure.message ?: "Owner access could not be confirmed.", authorizedKind = null)
                }
            } finally { finish(operation, owner, source) }
        }
    }

    fun edit(reason: String = _state.value.reason, evidence: String = _state.value.evidenceReference,
             cash: String = _state.value.actualCash, upi: String = _state.value.actualUpi) {
        if (busy() || _state.value.pending != null || !requireGrant(_state.value.context?.kind)) return
        _state.value = _state.value.copy(reason = reason.take(500), evidenceReference = evidence.take(200),
            actualCash = cash.take(32), actualUpi = upi.take(32), confirmed = false, reviewedRejection = null)
    }

    fun confirm(value: Boolean) {
        if (!busy() && _state.value.pending == null && requireGrant(_state.value.context?.kind)) _state.value = _state.value.copy(confirmed = value)
    }

    fun prepare() {
        val owner = scope ?: return
        val source = _state.value.context ?: return
        if (busy() || !requireGrant(source.kind) || !_state.value.savedWorkVerified || _state.value.storageError != null) return
        if (_state.value.pending != null) { _state.value = _state.value.copy(error = "Recover the saved request before preparing another correction."); return }
        if (source is TenderContext.Daily && runCatching { LocalDate.parse(source.businessDate).toString() == source.businessDate }.getOrDefault(false) != true) {
            _state.value = _state.value.copy(error = "Enter a valid shop date as YYYY-MM-DD."); return
        }
        val token = grant ?: return
        val operation = ++generation
        _state.value = _state.value.copy(busy = true, error = null, receipt = null, confirmed = false,
            paymentPreview = null, dailyPreview = null, reviewedRejection = null)
        work = tasks.launch {
            try {
                journal.read(owner)?.let { saved ->
                    if (current(operation, owner, source)) _state.value = _state.value.copy(pending = saved, error = "Another saved request needs explicit recovery. Nothing new was sent.")
                    return@launch
                }
                if (!current(operation, owner, source) || !requireGrant(source.kind)) return@launch
                when (source) {
                    is TenderContext.Payment -> {
                        val preview = api.preparePaymentTender(token.token, PaymentTenderPrepare(source.paymentId))
                        if (!current(operation, owner, source) || !requireGrant(source.kind)) return@launch
                        check(preview.paymentId == source.paymentId && preview.branchId == owner.branchId && preview.originalMethod == "cash" && preview.effectiveMethod == "upi" && preview.totalChangeMinor == 0L) { "The returned source preview did not match this payment. Nothing was sent." }
                        _state.value = _state.value.copy(paymentPreview = preview)
                    }
                    is TenderContext.Daily -> {
                        val preview = api.prepareDailyTender(token.token, DailyTenderPrepare(owner.branchId, source.businessDate))
                        if (!current(operation, owner, source) || !requireGrant(source.kind)) return@launch
                        check(preview.branchId == owner.branchId && preview.businessDate == source.businessDate && preview.attestationScope == "cash_upi_only" && !preview.physicalCashCount) { "The returned source preview did not match this day or attestation scope. Nothing was sent." }
                        _state.value = _state.value.copy(dailyPreview = preview)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(operation, owner, source, failure) }
            finally { finish(operation, owner, source) }
        }
    }

    fun commit(expected: TenderWorkspaceState = _state.value) {
        if (_state.value != expected) return
        if (_state.value.pending != null) return // Recovery has its own explicit command.
        val source = _state.value.context ?: return
        val owner = scope ?: return
        if (busy() || !requireGrant(source.kind) || !_state.value.confirmed || !_state.value.savedWorkVerified || _state.value.storageError != null) return
        val before = _state.value
        val command = try {
            val reason = before.reason.trim()
            val evidence = before.evidenceReference.trim().takeIf(String::isNotEmpty)
            when (source) {
                is TenderContext.Payment -> {
                    val preview = requireNotNull(before.paymentPreview) { "Load and review this exact source first." }
                    paymentTenderCommand(owner, PaymentTenderCommit(source.paymentId, preview.sourceFingerprint, reason, evidence), nowMillis = wallNow())
                }
                is TenderContext.Daily -> {
                    val preview = requireNotNull(before.dailyPreview) { "Load and review this exact shop date first." }
                    val actuals = requireNotNull(dailyActualsReview(preview, before.actualCash, before.actualUpi)) { "Enter nonnegative Cash and UPI actuals with at most two decimals." }
                    check(actuals.totalDifference != 0L || actuals.supportedTransfer) { "Only a supported Cash to UPI transfer can post. No-op or reverse transfers are not supported." }
                    dailyTenderCommand(owner, DailyTenderCommit(owner.branchId, source.businessDate, preview.sourceFingerprint,
                        actuals.cashMinor, actuals.upiMinor, reason, evidence), nowMillis = wallNow())
                }
            }
        } catch (failure: Exception) { _state.value = before.copy(error = failure.message); return }
        send(command)
    }

    fun recover(expected: PendingTenderCommand) {
        if (_state.value.pending != expected || busy() || !requireGrant(expected.kind)) return
        send(expected)
    }

    private fun send(command: PendingTenderCommand) {
        val owner = scope ?: return
        val source = _state.value.context ?: return
        val token = grant ?: return
        if (!token.allows(command, elapsedNow()) || !authorityMatches()) return
        val operation = ++generation
        _state.value = _state.value.copy(busy = true, error = null, reviewedRejection = null)
        work = tasks.launch {
            var sent = false
            try {
                val stored = journal.read(owner)
                if (!current(operation, owner, source) || !requireGrant(command.kind)) return@launch
                if (stored != null && stored != command) {
                    _state.value = _state.value.copy(pending = stored, paymentPreview = null, dailyPreview = null,
                        confirmed = false, error = "Another saved source needs recovery. Review its identity and choose Recover saved result explicitly.")
                    return@launch
                }
                // Keep this exact identity even if durable save fails after publishing bytes.
                _state.value = _state.value.copy(pending = command)
                journal.reserve(command)
                if (!current(operation, owner, source) || !requireGrant(command.kind)) return@launch
                sent = true
                val result = executeTenderCommand(api, token.token, command)
                if (!current(operation, owner, source) || !requireGrant(command.kind)) return@launch
                journal.clearExact(command)
                val remaining = journal.read(owner)
                if (!current(operation, owner, source) || !requireGrant(command.kind)) return@launch
                _state.value = _state.value.copy(pending = remaining, receipt = result, paymentPreview = null,
                    dailyPreview = null, confirmed = false, reviewedRejection = null, storageError = null,
                    history = (listOf(result) + _state.value.history).distinctBy { it.id })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (!current(operation, owner, source)) return@launch
                val apiFailure = failure as? ApiException
                val definiteRejection = sent && apiFailure?.status in setOf(409, 422) &&
                    apiFailure?.code in setOf("conflict", "business_rule", "validation_error") && apiFailure?.retryable != true
                _state.value = _state.value.copy(error = failure.message ?: "The result is unknown. Keep and recover the same saved request.",
                    reviewedRejection = command.takeIf { definiteRejection },
                    storageError = if (!sent) failure.message ?: "Saved work could not be verified. Nothing was sent." else _state.value.storageError)
                if (apiFailure?.status in setOf(401, 403)) lock("Owner access must be verified again. Saved request retained.")
            } finally { finish(operation, owner, source) }
        }
    }

    fun acknowledgeRejected(expected: PendingTenderCommand) {
        val owner = scope ?: return
        val source = _state.value.context ?: return
        if (busy() || _state.value.reviewedRejection != expected || _state.value.pending != expected || !requireGrant(expected.kind)) return
        val operation = ++generation
        _state.value = _state.value.copy(busy = true)
        work = tasks.launch {
            try {
                journal.clearExact(expected)
                val remaining = journal.read(owner)
                if (!current(operation, owner, source)) return@launch
                _state.value = _state.value.copy(pending = remaining, reviewedRejection = null, confirmed = false,
                    paymentPreview = null, dailyPreview = null, error = null, storageError = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current(operation, owner, source)) _state.value = _state.value.copy(storageError = failure.message) }
            finally { finish(operation, owner, source) }
        }
    }

    fun refreshHistory() {
        val owner = scope ?: return
        val source = _state.value.context ?: return
        val token = grant ?: return
        if (busy() || !requireGrant(_state.value.pending?.kind ?: source.kind)) return
        val operation = ++generation
        _state.value = _state.value.copy(busy = true, historyError = null)
        work = tasks.launch { try { fetchHistory(token, operation, owner, source) } finally { finish(operation, owner, source) } }
    }

    private suspend fun fetchHistory(token: TenderGrant, operation: Long, owner: TenderScope, source: TenderContext) {
        try {
            val pending = _state.value.pending
            val date = when {
                pending?.kind == TenderKind.DAILY -> journalJson.decodeFromString<DailyTenderCommit>(pending.bodyJson).businessDate
                pending != null -> null
                else -> (source as? TenderContext.Daily)?.businessDate?.takeIf(String::isNotBlank)
            }
            val payment = when {
                pending?.kind == TenderKind.PAYMENT -> journalJson.decodeFromString<PaymentTenderCommit>(pending.bodyJson).paymentId
                pending != null -> null
                else -> (source as? TenderContext.Payment)?.paymentId
            }
            val rows = api.tenderHistory(token.historyToken, owner.branchId, if (payment == null) date else null, payment)
            if (current(operation, owner, source) && requireGrant(token.kind)) _state.value = _state.value.copy(history = rows, historyError = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(operation, owner, source)) return
            if ((failure as? ApiException)?.status in setOf(401, 403)) lock("Owner access must be verified again. Saved work retained.")
            else _state.value = _state.value.copy(historyError = failure.message ?: "Protected history could not load. Its absence is not proof that nothing was recorded.")
        }
    }

    private fun busy() = _state.value.busy || _state.value.readingSavedWork
    private fun authorityMatches(): Boolean = scope?.let { profile()?.tenderScope(it.branchId) == it } == true
    private fun requireGrant(kind: TenderKind?): Boolean {
        checkVerification()
        val current = grant ?: return false
        return kind != null && current.kind == kind && current.scope == scope && elapsedNow() < current.expiresAtMillis
    }
    private fun current(operation: Long, owner: TenderScope, source: TenderContext): Boolean {
        if (operation != generation || scope != owner || _state.value.context != source) return false
        if (!authorityMatches()) { revoke(); return false }
        return true
    }
    private fun fail(operation: Long, owner: TenderScope, source: TenderContext, failure: Exception) {
        if (!current(operation, owner, source)) return
        _state.value = _state.value.copy(error = failure.message ?: "Source could not be checked. Nothing was sent.")
        if ((failure as? ApiException)?.status in setOf(401, 403)) lock("Owner access must be verified again. Saved work retained.")
    }
    private fun finish(operation: Long, owner: TenderScope, source: TenderContext) {
        if (current(operation, owner, source)) _state.value = _state.value.copy(busy = false)
    }
    private fun revoke() {
        val context = _state.value.context
        invalidate()
        scope = null
        _state.value = TenderWorkspaceState(context = context, error = "This account no longer has protected correction access.")
    }
    private fun scheduleExpiry(token: TenderGrant) {
        expiry?.cancel()
        expiry = tasks.launch {
            delay((token.expiresAtMillis - elapsedNow()).coerceAtLeast(0))
            if (grant == token) checkVerification()
        }
    }
    private fun invalidate() { generation++; work?.cancel(); expiry?.cancel(); grant = null }
    override fun onCleared() { invalidate(); super.onCleared() }
}
