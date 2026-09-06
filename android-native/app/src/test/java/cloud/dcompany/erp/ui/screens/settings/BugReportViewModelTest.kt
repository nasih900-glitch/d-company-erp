package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.db.BugReportOutboxState
import cloud.dcompany.erp.core.db.LocalBugReportAttachmentEntity
import cloud.dcompany.erp.core.db.LocalBugReportEntity
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

    private fun viewModel(
        outbox: FakeOutbox,
        api: BugReportApi = FakeApi(),
    ) = BugReportViewModel(
        owner = owner,
        outbox = outbox,
        api = api,
        keyFactory = { "test-key" },
        requestScope = CoroutineScope(Dispatchers.Unconfined),
    )

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
    ) : BugReportApi {
        override suspend fun create(
            body: BugReportCreateRequest,
            idempotencyKey: String,
        ) = BugReportCreateResponse("report", "open", "2026-08-28T10:00:00Z")

        override suspend fun mine(limit: Int, offset: Int): BugReportMinePage = page

        override suspend fun uploadAttachment(
            reportId: String,
            file: MultipartBody.Part,
            idempotencyKey: String,
        ): BugReportAttachment = error("not used")
    }
}
