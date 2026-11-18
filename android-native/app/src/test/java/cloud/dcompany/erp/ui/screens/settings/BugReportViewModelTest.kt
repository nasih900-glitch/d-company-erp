package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.db.BugReportOutboxState
import cloud.dcompany.erp.core.db.LocalBugReportAttachmentEntity
import cloud.dcompany.erp.core.db.LocalBugReportEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportViewModelTest {
    private val owner = BugReportOwnerScope("company-1", "user-1")
    private val validContext = BugReportClientContext(
        platform = "android",
        currentScreen = "Gaming",
        connectivity = "offline",
        occurredAt = "2026-08-28T10:00:00Z",
    )

    @Test
    fun `offline send is durably captured and scheduled instead of rejected`() {
        val outbox = FakeOutbox()
        val vm = viewModel(outbox)
        vm.open(BugReportLaunchContext("Gaming"))
        vm.descriptionChanged("Send to POS did not complete for this session.")

        vm.submit(validContext)

        assertEquals(1, outbox.captures.size)
        assertEquals("offline", outbox.captures.single().request.clientContext.connectivity)
        assertEquals("test-key", vm.state.value.submittedLocalId)
        assertEquals(BugReportOutboxState.PENDING, vm.state.value.submittedState)
        assertFalse(vm.state.value.saving)
        assertTrue(outbox.scheduleCount >= 2) // login recovery plus post-capture hand-off
    }

    @Test
    fun `rapid double tap captures one logical request`() {
        val gate = CompletableDeferred<Unit>()
        val outbox = FakeOutbox(captureGate = gate)
        val vm = viewModel(outbox)
        vm.open(BugReportLaunchContext("POS"))
        vm.descriptionChanged("Cash payment button stayed on the same screen.")

        vm.submit(validContext.copy(currentScreen = "POS"))
        vm.submit(validContext.copy(currentScreen = "POS"))

        assertTrue(vm.state.value.saving)
        assertEquals(1, outbox.captureCalls)
        gate.complete(Unit)
        assertFalse(vm.state.value.saving)
        assertEquals(1, outbox.captures.size)
    }

    @Test
    fun `selected image requires explicit privacy confirmation`() {
        val outbox = FakeOutbox()
        val vm = viewModel(outbox)
        vm.open(BugReportLaunchContext("Tables"))
        vm.descriptionChanged("The table action failed after I tapped send.")
        vm.attachmentChanged(
            BugReportAttachmentDraft("support-image.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
        )
        vm.attachmentPreviewReadyChanged(true)

        vm.submit(validContext.copy(currentScreen = "Tables"))

        assertTrue(vm.state.value.attachmentError.orEmpty().contains("confirm", ignoreCase = true))
        assertTrue(outbox.captures.isEmpty())

        vm.attachmentConsentChanged(true)
        vm.submit(validContext.copy(currentScreen = "Tables"))
        assertEquals(3, outbox.captures.single().attachment?.byteSize)
    }

    @Test
    fun `image cannot be approved or submitted when safe preview is unavailable`() {
        val outbox = FakeOutbox()
        val vm = viewModel(outbox)
        vm.open(BugReportLaunchContext("Gaming"))
        vm.descriptionChanged("The station action failed and I need help.")
        vm.attachmentChanged(
            BugReportAttachmentDraft("support-image.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
        )

        vm.attachmentConsentChanged(true)
        assertFalse(vm.state.value.attachmentConsent)

        vm.submit(validContext)

        assertTrue(outbox.captures.isEmpty())
        assertTrue(vm.state.value.attachmentError.orEmpty().contains("could not be reviewed"))
    }

    @Test
    fun `mine endpoint supplies reporter visible status and latest owner reply`() {
        val api = FakeApi(
            page = BugReportMinePage(
                items = listOf(
                    BugReportMineItem(
                        id = "server-report",
                        title = "Action failed · Gaming",
                        status = "in_progress",
                        publicReplies = listOf(
                            BugReportPublicReply(
                                id = "reply-1",
                                authorName = "Nasih",
                                message = "I am checking this now.",
                                createdAt = "2026-08-28T10:10:00Z",
                            ),
                        ),
                        createdAt = "2026-08-28T10:00:00Z",
                        updatedAt = "2026-08-28T10:10:00Z",
                    ),
                ),
            ),
        )
        val vm = viewModel(FakeOutbox(), api)

        vm.open(BugReportLaunchContext("Gaming"))
        vm.showHistory()

        val item = vm.state.value.recentRequests.single()
        assertEquals("in_progress", item.status)
        assertEquals("Nasih", item.latestReplyAuthor)
        assertEquals("I am checking this now.", item.latestReply)
        assertNull(vm.state.value.historyError)
    }

    @Test
    fun `one report with a pending image counts as one waiting help request`() {
        val outbox = FakeOutbox()
        val vm = viewModel(outbox)
        outbox.snapshots.value = BugReportOutboxSnapshot(
            reports = listOf(localReport(state = BugReportOutboxState.PENDING)),
            attachments = listOf(localAttachment(state = BugReportOutboxState.PENDING)),
        )

        assertEquals(1, vm.state.value.pendingCount)
    }

    @Test
    fun `receipt retries a refused image after the report itself was delivered`() {
        val outbox = FakeOutbox()
        val vm = viewModel(outbox)
        vm.open(BugReportLaunchContext("Gaming"))
        vm.descriptionChanged("The selected station did not respond to the action.")
        vm.submit(validContext)
        outbox.snapshots.value = BugReportOutboxSnapshot(
            reports = listOf(
                localReport(
                    state = BugReportOutboxState.SENT,
                    serverId = "server-report",
                ),
            ),
            attachments = listOf(
                localAttachment(
                    state = BugReportOutboxState.ACTION_REQUIRED,
                    lastError = "The image could not be accepted.",
                ),
            ),
        )

        vm.retrySubmitted()

        assertEquals(0, outbox.retryCalls)
        assertEquals(1, outbox.retryAttachmentCalls)
        assertEquals(BugReportOutboxState.PENDING, vm.state.value.submittedAttachmentState)
        assertNull(vm.state.value.submittedAttachmentError)
    }

    @Test
    fun `reviewed action required item can remove only its owned saved copy`() {
        val outbox = FakeOutbox()
        val vm = viewModel(outbox)
        outbox.snapshots.value = BugReportOutboxSnapshot(
            reports = listOf(localReport(state = BugReportOutboxState.ACTION_REQUIRED)),
            attachments = listOf(localAttachment(state = BugReportOutboxState.ACTION_REQUIRED)),
        )

        vm.discardHistoryItem("test-key")

        assertEquals(1, outbox.discardCalls)
        assertNull(vm.state.value.historyError)
    }

    private fun localReport(
        state: String,
        serverId: String? = null,
    ) = LocalBugReportEntity(
        localId = "test-key",
        ownerCompanyId = owner.companyId,
        ownerUserId = owner.userId,
        requestJson = "{}",
        title = "Action failed · Gaming",
        screen = "Gaming",
        createdAtMillis = 1L,
        state = state,
        serverId = serverId,
    )

    private fun localAttachment(
        state: String,
        lastError: String? = null,
    ) = LocalBugReportAttachmentEntity(
        localId = "attachment-key",
        reportLocalId = "test-key",
        ownerCompanyId = owner.companyId,
        ownerUserId = owner.userId,
        filename = "support-image.jpg",
        contentType = "image/jpeg",
        content = byteArrayOf(1, 2, 3),
        byteSize = 3,
        createdAtMillis = 1L,
        state = state,
        lastError = lastError,
    )

    @Test
    fun `refused original operation clears busy without saving or reading another workspace`() {
        val outbox = FakeOutbox()
        val refused = cloud.dcompany.erp.core.handover.CallerOperations(null) { _, _ ->
            error("Refused caller must not execute")
        }
        val vm = viewModel(outbox, operations = refused)
        vm.open(BugReportLaunchContext("Workspace setup"))
        vm.descriptionChanged("The terminal could not be selected.")
        vm.submit(validContext)
        assertEquals(0, outbox.captureCalls)
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.refreshingHistory)
        assertTrue(vm.state.value.draft.description.isNotBlank())
    }

    @Test
    fun `pre terminal own history remains readable but send and retry explicitly require workspace`() {
        val outbox = FakeOutbox()
        var reads = 0
        val vm = viewModel(outbox, workspaceReady = false, preTerminalHistory = {
            reads++
            BugReportMinePage(items = emptyList())
        })
        vm.open(BugReportLaunchContext("Workspace setup"))
        assertEquals(1, reads)
        assertTrue(vm.state.value.workspaceRequired)
        assertFalse(vm.state.value.refreshingHistory)
        vm.descriptionChanged("The workspace cannot be selected.")
        vm.submit(validContext)
        vm.retrySubmitted()
        vm.retryHistoryItem("saved-request")
        vm.discardHistoryItem("saved-request")
        assertEquals(0, outbox.captureCalls)
        assertEquals(0, outbox.retryCalls)
        assertEquals(BUG_REPORT_WORKSPACE_REQUIRED, vm.state.value.error)
        vm.dismiss()
        assertTrue(vm.state.value.workspaceRequired)
    }

    private fun viewModel(
        outbox: FakeOutbox,
        api: BugReportApi = FakeApi(),
        operations: cloud.dcompany.erp.core.handover.CallerOperations = cloud.dcompany.erp.core.handover.standaloneOperationCaller(),
        workspaceReady: Boolean = true,
        preTerminalHistory: (suspend () -> BugReportMinePage)? = null,
    ) = BugReportViewModel(
        owner = owner,
        outbox = outbox,
        api = api,
        operations = operations,
        workspaceReady = workspaceReady,
        preTerminalHistory = preTerminalHistory,
        keyFactory = { "test-key" },
        requestScope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `awaited history completes only after actual original API read and clears cancelled busy`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(FakeOutbox(), FakeApi(historyGate = gate))
        val refresh = async(Dispatchers.Unconfined) { vm.refreshHistoryAndAwait(silent = true) }
        assertFalse(refresh.isCompleted); assertTrue(vm.state.value.refreshingHistory)
        assertFalse(vm.refreshHistoryAndAwait(silent = true))
        gate.complete(Unit); assertTrue(refresh.await()); assertFalse(vm.state.value.refreshingHistory)
        val secondGate = CompletableDeferred<Unit>()
        val second = viewModel(FakeOutbox(), FakeApi(historyGate = secondGate))
        val cancelled = async(Dispatchers.Unconfined) { second.refreshHistoryAndAwait() }
        assertTrue(second.state.value.refreshingHistory); cancelled.cancelAndJoin()
        assertFalse(second.state.value.refreshingHistory)
    }

    @Test
    fun `awaited history refuses original rows after authority changes during response`() = runBlocking<Unit> {
        var current = true
        val scope = cloud.dcompany.erp.core.handover.HandoverScope(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222", null, null,
        )
        val authority = cloud.dcompany.erp.core.handover.HandoverAuthority(scope, 1)
        val coordinator = cloud.dcompany.erp.core.handover.HandoverCoordinator(object : cloud.dcompany.erp.core.handover.HandoverIntentStore {
            override suspend fun read(): cloud.dcompany.erp.core.handover.HandoverIntent? = null
            override suspend fun begin(intent: cloud.dcompany.erp.core.handover.HandoverIntent): Unit = error("No handover")
            override suspend fun compareAndSet(expected: cloud.dcompany.erp.core.handover.HandoverIntent, next: cloud.dcompany.erp.core.handover.HandoverIntent): Unit = error("No handover")
            override suspend fun cancelNotSent(expected: cloud.dcompany.erp.core.handover.HandoverIntent): Unit = error("No handover")
        })
        coordinator.initialize(authority)
        val binding = cloud.dcompany.erp.core.handover.OriginalOperationBinding(authority,
            cloud.dcompany.erp.core.auth.CacheScopeLease(cloud.dcompany.erp.core.auth.CacheScope(scope.actorId, scope.companyId, null, null), 1),
            cloud.dcompany.erp.core.auth.SessionRefreshLease(cloud.dcompany.erp.core.auth.StoredSession("synthetic", "synthetic"), 1)) { current }
        val operations = cloud.dcompany.erp.core.handover.CallerOperations(binding) { original, block ->
            coordinator.runOperation(original.authority, block)
        }
        val gate = CompletableDeferred<Unit>()
        val page = BugReportMinePage(items = listOf(BugReportMineItem(
            id = "private-a-row", title = "Original A", status = "open", publicReplies = emptyList(),
            createdAt = "2026-08-28T10:00:00Z", updatedAt = "2026-08-28T10:00:00Z",
        )))
        val vm = viewModel(FakeOutbox(), FakeApi(page, gate), operations)
        val refresh = async(Dispatchers.Unconfined) { vm.refreshHistoryAndAwait() }
        assertTrue(vm.state.value.refreshingHistory)
        current = false
        gate.complete(Unit)
        assertFalse(refresh.await())
        assertTrue(vm.state.value.recentRequests.isEmpty())
        assertFalse(vm.state.value.refreshingHistory)
    }

    private data class Capture(
        val request: BugReportCreateRequest,
        val attachment: BugReportAttachmentDraft?,
    )

    private class FakeOutbox(
        private val captureGate: CompletableDeferred<Unit>? = null,
    ) : BugReportOutboxGateway {
        val snapshots = MutableStateFlow(BugReportOutboxSnapshot(emptyList(), emptyList()))
        val captures = mutableListOf<Capture>()
        var captureCalls = 0
        var scheduleCount = 0
        var retryCalls = 0
        var retryAttachmentCalls = 0
        var discardCalls = 0

        override fun observe(owner: BugReportOwnerScope): Flow<BugReportOutboxSnapshot> = snapshots

        override suspend fun capture(
            owner: BugReportOwnerScope,
            localId: String,
            request: BugReportCreateRequest,
            attachment: BugReportAttachmentDraft?,
        ) {
            captureCalls += 1
            captureGate?.await()
            captures += Capture(request, attachment)
            snapshots.value = BugReportOutboxSnapshot(
                reports = listOf(
                    LocalBugReportEntity(
                        localId = localId,
                        ownerCompanyId = owner.companyId,
                        ownerUserId = owner.userId,
                        requestJson = "{}",
                        title = request.title,
                        screen = request.clientContext.currentScreen.orEmpty(),
                        createdAtMillis = 1L,
                    ),
                ),
                attachments = emptyList(),
            )
            scheduleCount += 1
        }

        override suspend fun retry(owner: BugReportOwnerScope, localId: String): Boolean {
            retryCalls += 1
            return true
        }

        override suspend fun retryAttachment(owner: BugReportOwnerScope, localId: String): Boolean {
            retryAttachmentCalls += 1
            return true
        }
        override suspend fun discardAfterReview(
            owner: BugReportOwnerScope,
            localId: String,
        ): Boolean {
            discardCalls += 1
            return true
        }
        override fun ensureDeliveryScheduled() { scheduleCount += 1 }
    }

    private class FakeApi(
        private val page: BugReportMinePage = BugReportMinePage(),
        private val historyGate: CompletableDeferred<Unit>? = null,
    ) : BugReportApi {
        override suspend fun create(
            body: BugReportCreateRequest,
            idempotencyKey: String,
        ) = BugReportCreateResponse("report", "open", "2026-08-28T10:00:00Z")

        override suspend fun mine(limit: Int, offset: Int): BugReportMinePage { historyGate?.await(); return page }

        override suspend fun uploadAttachment(
            reportId: String,
            file: MultipartBody.Part,
            idempotencyKey: String,
        ): BugReportAttachment = error("not used")
    }
}
