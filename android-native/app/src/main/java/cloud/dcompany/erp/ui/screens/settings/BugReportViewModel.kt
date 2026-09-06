package cloud.dcompany.erp.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.BugReportOutboxState
import cloud.dcompany.erp.core.db.LocalBugReportEntity
import cloud.dcompany.erp.core.db.LocalBugReportAttachmentEntity
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
    val showingHistory: Boolean = false,
    val launchContext: BugReportLaunchContext = BugReportLaunchContext("ERP"),
    val draft: BugReportDraft = BugReportDraft(),
    val attachment: BugReportAttachmentDraft? = null,
    val attachmentPreviewReady: Boolean = false,
    val attachmentConsent: Boolean = false,
    val attachmentError: String? = null,
    val validation: BugReportValidation = BugReportValidation(),
    val saving: Boolean = false,
    val error: String? = null,
    val submittedLocalId: String? = null,
    val submittedState: String? = null,
    val submittedServerStatus: String? = null,
    val submittedError: String? = null,
    val submittedAttachmentState: String? = null,
    val submittedAttachmentError: String? = null,
    val pendingCount: Int = 0,
    val recentRequests: List<BugReportHistoryItem> = emptyList(),
    val refreshingHistory: Boolean = false,
    val historyError: String? = null,
)

data class BugReportHistoryItem(
    val identity: String,
    val title: String,
    val status: String,
    val createdAt: String,
    val latestReplyAuthor: String? = null,
    val latestReply: String? = null,
    val isLocalOnly: Boolean = false,
    val actionRequired: Boolean = false,
)

internal class BugReportViewModel(
    private val owner: BugReportOwnerScope,
    private val outbox: BugReportOutboxGateway,
    private val api: BugReportApi,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
    private val requestScope: CoroutineScope? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(BugReportUiState())
    val state: StateFlow<BugReportUiState> = _state.asStateFlow()
    private var localRows: List<LocalBugReportEntity> = emptyList()
    private var localAttachments: List<LocalBugReportAttachmentEntity> = emptyList()
    private var remoteRows: List<BugReportMineItem> = emptyList()

    init {
        (requestScope ?: viewModelScope).launch {
            outbox.observe(owner).collect(::applyOutboxRows)
        }
        // A process may have died after Room committed but before WorkManager
        // received the hand-off. Re-establish it for this exact owner at login.
        outbox.ensureDeliveryScheduled()
    }

    fun open(context: BugReportLaunchContext) {
        val current = _state.value
        val keepExistingDraft = current.submittedLocalId == null &&
            current.draft.description.isNotBlank()
        _state.value = if (keepExistingDraft) {
            current.copy(isOpen = true)
        } else {
            current.copy(
                isOpen = true,
                showingHistory = false,
                launchContext = context,
                draft = BugReportDraft(),
                attachment = null,
                attachmentPreviewReady = false,
                attachmentConsent = false,
                attachmentError = null,
                validation = BugReportValidation(),
                saving = false,
                error = null,
                submittedLocalId = null,
                submittedState = null,
                submittedServerStatus = null,
                submittedError = null,
                submittedAttachmentState = null,
                submittedAttachmentError = null,
            )
        }
        refreshHistory(silent = true)
    }

    fun showHistory() {
        _state.value = _state.value.copy(showingHistory = true)
        refreshHistory(silent = false)
    }

    fun closeHistory() {
        _state.value = _state.value.copy(showingHistory = false, historyError = null)
    }

    fun dismiss() {
        val current = _state.value
        if (current.saving) return
        _state.value = if (current.submittedLocalId != null) {
            BugReportUiState(
                pendingCount = current.pendingCount,
                recentRequests = current.recentRequests,
            )
        } else {
            // An unsent description survives normal sheet close/reopen during
            // this authenticated session. Submitted work is durable in Room.
            current.copy(isOpen = false, error = null)
        }
    }

    fun reasonChanged(value: SupportRequestReason) = edit { copy(reason = value) }

    fun continuationChanged(value: WorkContinuation) = edit { copy(canContinue = value) }

    fun descriptionChanged(value: String) = edit {
        copy(description = value.take(BUG_REPORT_DETAIL_MAX_LENGTH))
    }

    fun attachmentChanged(value: BugReportAttachmentDraft?) {
        val current = _state.value
        if (current.saving || current.submittedLocalId != null) return
        _state.value = current.copy(
            attachment = value,
            attachmentPreviewReady = false,
            attachmentConsent = false,
            attachmentError = null,
        )
    }

    fun attachmentRejected(message: String) {
        val current = _state.value
        if (current.saving || current.submittedLocalId != null) return
        _state.value = current.copy(attachmentError = message)
    }

    fun attachmentConsentChanged(value: Boolean) {
        val current = _state.value
        if (current.saving || current.submittedLocalId != null || current.attachment == null) return
        if (value && !current.attachmentPreviewReady) {
            _state.value = current.copy(
                attachmentConsent = false,
                attachmentError = "Wait until the selected image is visible before confirming it is safe.",
            )
            return
        }
        _state.value = current.copy(attachmentConsent = value, attachmentError = null)
    }

    /**
     * Privacy consent is meaningful only after the operator has actually seen
     * the decoded, sanitised image. A corrupt or unsupported preview must not
     * be approvable merely because its bytes were accepted by the picker.
     */
    fun attachmentPreviewReadyChanged(value: Boolean) {
        val current = _state.value
        if (current.saving || current.submittedLocalId != null || current.attachment == null) return
        _state.value = current.copy(
            attachmentPreviewReady = value,
            attachmentConsent = if (value) current.attachmentConsent else false,
            attachmentError = if (value) null else current.attachmentError,
        )
    }

    private fun edit(change: BugReportDraft.() -> BugReportDraft) {
        val current = _state.value
        if (current.saving || current.submittedLocalId != null) return
        _state.value = current.copy(
            draft = current.draft.change(),
            validation = BugReportValidation(),
            error = null,
        )
    }

    fun submit(clientContext: BugReportClientContext) {
        val current = _state.value
        if (current.saving || current.submittedLocalId != null) return
        val validation = current.draft.validate()
        if (!validation.isValid) {
            _state.value = current.copy(
                validation = validation,
                error = "Add a short description, then send the request again.",
            )
            return
        }
        if (current.attachment != null && !current.attachmentPreviewReady) {
            _state.value = current.copy(
                attachmentConsent = false,
                attachmentError = "The selected image could not be reviewed. Remove it and choose it again, or send the report without an image.",
                error = "The image cannot be sent until its safe preview is visible.",
            )
            return
        }
        if (current.attachment != null && !current.attachmentConsent) {
            _state.value = current.copy(
                attachmentError = "Review the preview and confirm it contains no private information.",
                error = "Confirm the image is safe to share, or remove it.",
            )
            return
        }

        val localId = keyFactory()
        val request = current.draft.toRequest(clientContext)
        _state.value = current.copy(
            validation = BugReportValidation(),
            saving = true,
            error = null,
        )
        (requestScope ?: viewModelScope).launch {
            try {
                outbox.capture(owner, localId, request, current.attachment)
                _state.value = _state.value.copy(
                    saving = false,
                    submittedLocalId = localId,
                    submittedState = BugReportOutboxState.PENDING,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = "This request could not be saved on the tablet. Your description is still here; try again.",
                )
            }
        }
    }

    fun retrySubmitted() {
        val current = _state.value
        val localId = current.submittedLocalId ?: return
        val attachment = localAttachments.firstOrNull {
            it.reportLocalId == localId && it.state == BugReportOutboxState.ACTION_REQUIRED
        }
        val retryReport = current.submittedState == BugReportOutboxState.ACTION_REQUIRED
        if (current.saving || (!retryReport && attachment == null)) return
        _state.value = current.copy(saving = true, error = null)
        (requestScope ?: viewModelScope).launch {
            try {
                val queued = if (retryReport) {
                    outbox.retry(owner, localId)
                } else {
                    outbox.retryAttachment(owner, requireNotNull(attachment).localId)
                }
                _state.value = _state.value.copy(
                    saving = false,
                    submittedState = if (queued && retryReport) {
                        BugReportOutboxState.PENDING
                    } else {
                        _state.value.submittedState
                    },
                    submittedError = if (queued && retryReport) null else _state.value.submittedError,
                    submittedAttachmentState = if (queued && !retryReport) {
                        BugReportOutboxState.PENDING
                    } else {
                        _state.value.submittedAttachmentState
                    },
                    submittedAttachmentError = if (queued && !retryReport) {
                        null
                    } else {
                        _state.value.submittedAttachmentError
                    },
                    error = if (queued) null else "This request is no longer waiting for a retry.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = "Could not schedule another send attempt. The saved request remains on this tablet.",
                )
            }
        }
    }

    fun retryHistoryItem(identity: String) {
        val local = localRows.firstOrNull { it.localId == identity }
        val attachment = localAttachments.firstOrNull {
            it.reportLocalId == identity && it.state == BugReportOutboxState.ACTION_REQUIRED
        }
        if (local?.state != BugReportOutboxState.ACTION_REQUIRED && attachment == null) return
        (requestScope ?: viewModelScope).launch {
            try {
                val queued = if (local?.state == BugReportOutboxState.ACTION_REQUIRED) {
                    outbox.retry(owner, local.localId)
                } else {
                    outbox.retryAttachment(owner, requireNotNull(attachment).localId)
                }
                if (!queued) {
                    _state.value = _state.value.copy(
                        historyError = "This request is no longer waiting for a retry.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    historyError = "Could not schedule this saved request. It remains on the tablet.",
                )
            }
        }
    }

    fun discardHistoryItem(identity: String) {
        val local = localRows.firstOrNull { it.localId == identity } ?: return
        val attachmentNeedsReview = localAttachments.any {
            it.reportLocalId == identity && it.state == BugReportOutboxState.ACTION_REQUIRED
        }
        if (local.state != BugReportOutboxState.ACTION_REQUIRED && !attachmentNeedsReview) return
        (requestScope ?: viewModelScope).launch {
            try {
                val discarded = outbox.discardAfterReview(owner, identity)
                if (!discarded) {
                    _state.value = _state.value.copy(
                        historyError = "This saved copy no longer needs review.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    historyError = "Could not remove the saved tablet copy. It remains protected for review.",
                )
            }
        }
    }

    fun refreshHistory(silent: Boolean = false) {
        val current = _state.value
        if (current.refreshingHistory) return
        _state.value = current.copy(
            refreshingHistory = true,
            historyError = if (silent) current.historyError else null,
        )
        (requestScope ?: viewModelScope).launch {
            try {
                remoteRows = api.mine().items
                rebuildHistory()
                _state.value = _state.value.copy(refreshingHistory = false, historyError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    refreshingHistory = false,
                    historyError = if (silent) _state.value.historyError else {
                        "Could not refresh owner replies. Saved requests on this tablet are still shown below."
                    },
                )
            }
        }
    }

    private fun applyOutboxRows(snapshot: BugReportOutboxSnapshot) {
        val rows = snapshot.reports
        localRows = rows
        localAttachments = snapshot.attachments
        val current = _state.value
        val submitted = current.submittedLocalId?.let { id -> rows.firstOrNull { it.localId == id } }
        val submittedAttachment = current.submittedLocalId?.let { reportId ->
            snapshot.attachments.firstOrNull { it.reportLocalId == reportId }
        }
        val outstandingReportIds = buildSet {
            rows.filter {
                it.state == BugReportOutboxState.PENDING ||
                    it.state == BugReportOutboxState.ACTION_REQUIRED
            }.forEach { add(it.localId) }
            snapshot.attachments.filter {
                it.state == BugReportOutboxState.PENDING ||
                    it.state == BugReportOutboxState.ACTION_REQUIRED
            }.forEach { add(it.reportLocalId) }
        }
        _state.value = current.copy(
            pendingCount = outstandingReportIds.size,
            submittedState = submitted?.state ?: current.submittedState,
            submittedServerStatus = submitted?.serverStatus ?: current.submittedServerStatus,
            submittedError = submitted?.lastError ?: current.submittedError,
            submittedAttachmentState = submittedAttachment?.state ?: current.submittedAttachmentState,
            submittedAttachmentError = submittedAttachment?.lastError ?: current.submittedAttachmentError,
        )
        rebuildHistory()
    }

    private fun rebuildHistory() {
        val remote = remoteRows.associateBy { it.id }
        val localByServer = localRows.filter { it.serverId != null }.associateBy { it.serverId }
        val mappedRemote = remoteRows.map { row ->
            val latestReply = row.publicReplies.maxByOrNull { it.createdAt }
            val local = localByServer[row.id]
            val attachment = local?.let { report ->
                localAttachments.firstOrNull { it.reportLocalId == report.localId }
            }
            BugReportHistoryItem(
                identity = local?.localId ?: row.id,
                title = row.title,
                status = row.status,
                createdAt = row.createdAt,
                latestReplyAuthor = latestReply?.authorName,
                latestReply = latestReply?.message,
                actionRequired = attachment?.state == BugReportOutboxState.ACTION_REQUIRED,
            )
        }
        val pendingLocal = localRows.filter { row ->
            row.state != BugReportOutboxState.DISCARDED &&
                (row.serverId == null || row.serverId !in remote)
        }.map { row ->
            val attachment = localAttachments.firstOrNull { it.reportLocalId == row.localId }
            BugReportHistoryItem(
                identity = row.localId,
                title = row.title,
                status = when (row.state) {
                    BugReportOutboxState.SENT -> row.serverStatus ?: "sent"
                    BugReportOutboxState.ACTION_REQUIRED -> "needs attention"
                    else -> "waiting to send"
                },
                createdAt = row.serverCreatedAt ?: row.createdAtMillis.toString(),
                isLocalOnly = row.serverId == null,
                actionRequired = row.state == BugReportOutboxState.ACTION_REQUIRED ||
                    attachment?.state == BugReportOutboxState.ACTION_REQUIRED,
            )
        }
        _state.value = _state.value.copy(
            recentRequests = (pendingLocal + mappedRemote).take(20),
        )
    }

    companion object {
        fun factory(
            app: DCompanyApp,
            owner: BugReportOwnerScope,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(BugReportViewModel::class.java))
                return BugReportViewModel(
                    owner = owner,
                    outbox = BugReportOutbox(
                        dao = app.db.bugReportDao(),
                        scheduleDelivery = { BugReportSyncScheduler.enqueue(app) },
                        scopedCommitter = BugReportScopedCommitter { write ->
                            val lease = app.cacheIsolation.currentLease()
                            if (lease == null) {
                                false
                            } else {
                                app.cacheIsolation.commitIfCurrent(lease, write)
                            }
                        },
                    ),
                    api = ApiClient.create(),
                ) as T
            }
        }
    }
}

internal fun Throwable.bugReportReadable(): String {
    val apiError = this as? ApiException
        ?: return "The server did not confirm this request. It remains saved and will retry automatically."
    return when {
        apiError.status == 404 ->
            "Support reporting is not available on this ERP server yet. The request remains saved."
        apiError.status == 401 ->
            "Your login expired before this request was delivered. Sign in again to retry it safely."
        apiError.status == 403 ->
            "This account is not allowed to send support requests. Ask an owner to check account access."
        apiError.status == 422 ->
            "The server rejected the saved request format. Update the app, then retry it."
        apiError.status == 426 ->
            "This app version is too old to send support requests. Install the latest app, then retry it."
        apiError.status == 429 ->
            "This account reached the hourly support-request limit. The saved request will retry later."
        apiError.isAmbiguous ->
            "The server did not confirm this request. It remains saved and will retry with the same reference."
        apiError.status == 409 ->
            "The server could not safely match this saved request. Ask an owner to check the web support inbox."
        else ->
            "The server refused this request. It remains saved on the tablet for review."
    }
}
