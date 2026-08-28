package cloud.dcompany.erp.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class BugReportUiState(
    val isOpen: Boolean = false,
    val draft: BugReportDraft = BugReportDraft(),
    val validation: BugReportValidation = BugReportValidation(),
    val submitting: Boolean = false,
    val error: String? = null,
    val success: BugReportCreateResponse? = null,
)

class BugReportViewModel(
    private val api: BugReportApi = ApiClient.create<BugReportApi>(),
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val requestScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(BugReportUiState())
    val state: StateFlow<BugReportUiState> = _state.asStateFlow()

    /**
     * A timed-out POST may already have committed. Retain the same key for an
     * unchanged retry so the backend replays the original result instead of
     * creating a duplicate report. Editing the body produces a fresh key.
     */
    private data class RetryAttempt(
        val draft: BugReportDraft,
        val request: BugReportCreateRequest,
        val key: String,
    )

    private var retryAttempt: RetryAttempt? = null

    fun open() {
        _state.value = _state.value.copy(isOpen = true)
    }

    fun dismiss() {
        val current = _state.value
        if (current.submitting) return
        if (current.success != null) {
            retryAttempt = null
            _state.value = BugReportUiState()
        } else {
            // A failed/offline draft survives closing and reopening the sheet.
            _state.value = current.copy(isOpen = false)
        }
    }

    fun categoryChanged(value: BugReportCategory) = edit { copy(category = value) }

    fun severityChanged(value: BugReportSeverity) = edit { copy(severity = value) }

    fun titleChanged(value: String) = edit {
        copy(title = value.take(BUG_REPORT_TITLE_MAX_LENGTH))
    }

    fun descriptionChanged(value: String) = edit {
        copy(description = value.take(BUG_REPORT_DETAIL_MAX_LENGTH))
    }

    fun reproductionStepsChanged(value: String) = edit {
        copy(reproductionSteps = value.take(BUG_REPORT_DETAIL_MAX_LENGTH))
    }

    fun expectedBehaviorChanged(value: String) = edit {
        copy(expectedBehavior = value.take(BUG_REPORT_DETAIL_MAX_LENGTH))
    }

    fun actualBehaviorChanged(value: String) = edit {
        copy(actualBehavior = value.take(BUG_REPORT_DETAIL_MAX_LENGTH))
    }

    private fun edit(change: BugReportDraft.() -> BugReportDraft) {
        val current = _state.value
        if (current.submitting || current.success != null) return
        _state.value = current.copy(
            draft = current.draft.change(),
            validation = BugReportValidation(),
            error = null,
        )
    }

    fun submit(clientContext: BugReportClientContext) {
        val current = _state.value
        if (current.submitting || current.success != null) return

        val validation = current.draft.validate()
        if (!validation.isValid) {
            _state.value = current.copy(
                validation = validation,
                error = "Check the highlighted fields, then send the report again.",
            )
            return
        }
        if (clientContext.connectivity == BugReportConnectivity.Offline.wireValue) {
            _state.value = current.copy(
                error = "You're offline. Your draft is still here. Reconnect, then tap Send report.",
            )
            return
        }

        val retained = retryAttempt?.takeIf { it.draft == current.draft }
        // A retry reuses the original occurred_at and diagnostics as well as
        // the key. Regenerating the timestamp would change the request hash
        // and turn a safe replay into an idempotency mismatch.
        val request = retained?.request ?: current.draft.toRequest(clientContext)
        val key = retained?.key ?: keyFactory()
        retryAttempt = RetryAttempt(current.draft, request, key)
        _state.value = current.copy(
            validation = BugReportValidation(),
            submitting = true,
            error = null,
        )
        (requestScope ?: viewModelScope).launch {
            try {
                val result = api.create(request, key)
                retryAttempt = null
                _state.value = _state.value.copy(
                    submitting = false,
                    error = null,
                    success = result,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    submitting = false,
                    error = error.bugReportReadable(),
                )
            }
        }
    }
}

internal fun Throwable.bugReportReadable(): String {
    val apiError = this as? ApiException
        ?: return "The report could not be sent. Your draft is still here. Check the connection and try again."
    return when {
        apiError.status == 404 ->
            "Bug reporting is not available on this ERP server yet. Your draft is still here."
        apiError.status == 401 ->
            "Your login expired before the report was sent. Your draft is still here. Sign in again, then retry."
        apiError.status == 403 ->
            "This account is not allowed to send bug reports. Your draft is still here. Ask an owner to check account access."
        apiError.status == 422 ->
            "The server rejected one of the report fields. Your draft is still here; review the highlighted limits and try again."
        apiError.status == 426 ->
            "This app version is too old to send reports. Your draft is still here. Install the latest D Company ERP app, then try again."
        apiError.status == 429 ->
            "This account has reached the hourly bug-report limit. Your draft is still here. Wait a while, then try again."
        apiError.isAmbiguous ->
            "The ERP did not confirm whether this report was saved. Keep the draft unchanged and tap Send report again; it will not be created twice."
        apiError.status == 409 ->
            "This report changed while a previous send was being checked. Your draft is still here. Ask a system owner to check the web bug-report inbox before trying again."
        else ->
            "The report could not be saved. Your draft is still here. Try again or ask an owner to check the ERP server."
    }
}
