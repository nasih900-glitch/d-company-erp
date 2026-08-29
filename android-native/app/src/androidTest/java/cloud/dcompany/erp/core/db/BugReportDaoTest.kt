package cloud.dcompany.erp.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.ui.screens.settings.BugReportCategory
import cloud.dcompany.erp.ui.screens.settings.BugReportClientContext
import cloud.dcompany.erp.ui.screens.settings.BugReportCreateRequest
import cloud.dcompany.erp.ui.screens.settings.BugReportOutbox
import cloud.dcompany.erp.ui.screens.settings.BugReportOwnerScope
import cloud.dcompany.erp.ui.screens.settings.BugReportScopedCommitter
import cloud.dcompany.erp.ui.screens.settings.BugReportSeverity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BugReportDaoTest {
    private lateinit var database: ErpDatabase
    private lateinit var dao: BugReportDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.bugReportDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun attachmentWaitsForConfirmedReportAndIsErasedAfterUpload() = runBlocking {
        dao.insertBundle(
            report = LocalBugReportEntity(
                localId = "report-local",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                requestJson = "{}",
                title = "Action failed · Gaming",
                screen = "Gaming",
                createdAtMillis = 1L,
            ),
            attachment = LocalBugReportAttachmentEntity(
                localId = "attachment-local",
                reportLocalId = "report-local",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                filename = "support-image.jpg",
                contentType = "image/jpeg",
                content = byteArrayOf(1, 2, 3, 4),
                byteSize = 4,
                createdAtMillis = 1L,
            ),
        )

        assertTrue(dao.pushableAttachments("company-a", "user-a").isEmpty())
        dao.markSent(
            localId = "report-local",
            companyId = "company-a",
            userId = "user-a",
            serverId = "report-server",
            serverStatus = "open",
            serverCreatedAt = "2026-08-28T10:00:00Z",
            attemptedAtMillis = 2L,
        )

        assertEquals(
            listOf("attachment-local"),
            dao.pushableAttachments("company-a", "user-a").map { it.localId },
        )
        assertTrue(dao.pushableAttachments("company-a", "another-user").isEmpty())

        dao.markAttachmentSent(
            localId = "attachment-local",
            companyId = "company-a",
            userId = "user-a",
            serverId = "attachment-server",
            attemptedAtMillis = 3L,
        )
        val stored = dao.observeAttachmentsForOwner("company-a", "user-a").first().single()
        assertEquals(BugReportOutboxState.SENT, stored.state)
        assertEquals("attachment-server", stored.serverAttachmentId)
        assertEquals(0, stored.content.size)
        assertTrue(dao.pushableAttachments("company-a", "user-a").isEmpty())
    }

    @Test
    fun ownerScopedUpdatesCannotAffectAnotherEmployeesReport() = runBlocking {
        dao.insert(
            LocalBugReportEntity(
                localId = "report-a",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                requestJson = "{}",
                title = "Staff needs help",
                screen = "POS",
                createdAtMillis = 1L,
            ),
        )

        val changed = dao.markSent(
            localId = "report-a",
            companyId = "company-a",
            userId = "user-b",
            serverId = "wrong-server",
            serverStatus = "open",
            serverCreatedAt = "2026-08-28T10:00:00Z",
            attemptedAtMillis = 2L,
        )

        assertEquals(0, changed)
        assertEquals(
            BugReportOutboxState.PENDING,
            dao.reportForOwner("report-a", "company-a", "user-a")?.state,
        )
    }

    @Test
    fun revokedWorkspaceCannotCaptureAfterSignOutGate() = runBlocking {
        var scheduled = 0
        val outbox = BugReportOutbox(
            dao = dao,
            scheduleDelivery = { scheduled += 1 },
            scopedCommitter = BugReportScopedCommitter { false },
        )
        val request = BugReportCreateRequest(
            category = BugReportCategory.Other,
            severity = BugReportSeverity.High,
            title = "Action failed · POS",
            description = "The payment action did not complete.",
            clientContext = BugReportClientContext(
                platform = "android",
                connectivity = "offline",
            ),
        )

        val result = runCatching {
            outbox.capture(
                owner = BugReportOwnerScope("company-a", "user-a"),
                localId = "late-report",
                request = request,
                attachment = null,
            )
        }

        assertTrue(result.isFailure)
        assertTrue(dao.observeForOwner("company-a", "user-a").first().isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun unresolvedOldAttachmentAlwaysSurfacesItsParentBeyondRecentHistoryLimit() = runBlocking {
        dao.insertBundle(
            report = LocalBugReportEntity(
                localId = "old-blocked-report",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                requestJson = "{}",
                title = "Action failed · Gaming",
                screen = "Gaming",
                createdAtMillis = 0L,
                state = BugReportOutboxState.SENT,
                serverId = "server-old",
            ),
            attachment = LocalBugReportAttachmentEntity(
                localId = "old-blocked-image",
                reportLocalId = "old-blocked-report",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                filename = "support-image.jpg",
                contentType = "image/jpeg",
                content = byteArrayOf(1),
                byteSize = 1,
                createdAtMillis = 0L,
                state = BugReportOutboxState.ACTION_REQUIRED,
            ),
        )
        repeat(25) { index ->
            dao.insert(
                LocalBugReportEntity(
                    localId = "new-sent-$index",
                    ownerCompanyId = "company-a",
                    ownerUserId = "user-a",
                    requestJson = "{}",
                    title = "Staff needs help",
                    screen = "POS",
                    createdAtMillis = index.toLong() + 1L,
                    state = BugReportOutboxState.SENT,
                    serverId = "server-$index",
                ),
            )
        }

        val visible = dao.observeForOwner("company-a", "user-a").first()

        assertEquals(21, visible.size)
        assertTrue(visible.any { it.localId == "old-blocked-report" })
    }

    @Test
    fun attachmentOwnerMustMatchItsParentAndPushJoin() = runBlocking {
        val report = LocalBugReportEntity(
            localId = "report-a",
            ownerCompanyId = "company-a",
            ownerUserId = "user-a",
            requestJson = "{}",
            title = "Staff needs help",
            screen = "Gaming",
            createdAtMillis = 1L,
        )
        val wrongOwner = LocalBugReportAttachmentEntity(
            localId = "attachment-b",
            reportLocalId = report.localId,
            ownerCompanyId = "company-a",
            ownerUserId = "user-b",
            filename = "support-image.jpg",
            contentType = "image/jpeg",
            content = byteArrayOf(1),
            byteSize = 1,
            createdAtMillis = 1L,
        )

        val result = runCatching { dao.insertBundle(report, wrongOwner) }

        assertTrue(result.isFailure)
        assertTrue(dao.observeForOwner("company-a", "user-a").first().isEmpty())

        dao.insert(report)
        val foreignKeyResult = runCatching { dao.insertAttachment(wrongOwner) }
        assertTrue(foreignKeyResult.isFailure)
        assertTrue(dao.observeAttachmentsForOwner("company-a", "user-b").first().isEmpty())
    }

    @Test
    fun expiryAndReviewedDiscardErasePrivateBytesWithoutDiscardingTheReport() = runBlocking {
        dao.insertBundle(
            report = LocalBugReportEntity(
                localId = "report-local",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                requestJson = "{\"description\":\"private detail\"}",
                title = "Action failed",
                screen = "POS",
                createdAtMillis = 1L,
                state = BugReportOutboxState.SENT,
                serverId = "report-server",
            ),
            attachment = LocalBugReportAttachmentEntity(
                localId = "attachment-local",
                reportLocalId = "report-local",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                filename = "support-image.jpg",
                contentType = "image/jpeg",
                content = byteArrayOf(1, 2, 3),
                byteSize = 3,
                createdAtMillis = 100L,
                state = BugReportOutboxState.ACTION_REQUIRED,
            ),
        )

        assertEquals(0, dao.expireAttachmentContent(100L, "expired"))
        assertEquals(1, dao.expireAttachmentContent(101L, "expired"))
        val expired = dao.observeAttachmentsForOwner("company-a", "user-a").first().single()
        assertEquals(BugReportOutboxState.DISCARDED, expired.state)
        assertEquals(0, expired.content.size)
        assertEquals(BugReportOutboxState.SENT, dao.reportForOwner("report-local", "company-a", "user-a")?.state)
        assertFalse(dao.discardAfterReview("report-local", "company-a", "user-a"))
    }

    @Test
    fun reviewedRefusedReportIsRedactedAndStopsBlockingAccountSwitch() = runBlocking {
        dao.insertBundle(
            report = LocalBugReportEntity(
                localId = "report-local",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                requestJson = "{\"description\":\"private detail\"}",
                title = "Action failed",
                screen = "POS",
                createdAtMillis = 1L,
                state = BugReportOutboxState.ACTION_REQUIRED,
            ),
            attachment = LocalBugReportAttachmentEntity(
                localId = "attachment-local",
                reportLocalId = "report-local",
                ownerCompanyId = "company-a",
                ownerUserId = "user-a",
                filename = "support-image.jpg",
                contentType = "image/jpeg",
                content = byteArrayOf(1, 2, 3),
                byteSize = 3,
                createdAtMillis = 1L,
                state = BugReportOutboxState.ACTION_REQUIRED,
            ),
        )
        assertEquals("support_requests", database.outboxSafetyDao().unresolvedGroups().single().resource)

        assertTrue(dao.discardAfterReview("report-local", "company-a", "user-a"))

        val report = requireNotNull(dao.reportForOwner("report-local", "company-a", "user-a"))
        val attachment = dao.observeAttachmentsForOwner("company-a", "user-a").first().single()
        assertEquals(BugReportOutboxState.DISCARDED, report.state)
        assertEquals("{}", report.requestJson)
        assertEquals(BugReportOutboxState.DISCARDED, attachment.state)
        assertEquals(0, attachment.content.size)
        assertTrue(database.outboxSafetyDao().unresolvedGroups().isEmpty())
    }
}
