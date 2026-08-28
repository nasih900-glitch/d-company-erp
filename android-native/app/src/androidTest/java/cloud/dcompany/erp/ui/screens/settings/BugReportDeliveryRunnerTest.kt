package cloud.dcompany.erp.ui.screens.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.db.BugReportOutboxState
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.LocalBugReportAttachmentEntity
import cloud.dcompany.erp.core.db.LocalBugReportEntity
import cloud.dcompany.erp.core.net.ApiClient
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.MultipartBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BugReportDeliveryRunnerTest {
    private lateinit var database: ErpDatabase
    private val ownerA = OutboxOwnerIdentity("user-a", "company-1", "branch-1")
    private val ownerScope = BugReportOwnerScope(ownerA.companyId, ownerA.userId)

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun inFlightAccountSwitchStillUsesTheCapturedEmployeeABearer() = runBlocking {
        insertPendingReport("report-a")
        val bearerA = jwt("user-a", "company-1", "branch-1", "a")
        val bearerB = jwt("user-b", "company-1", "branch-1", "b")
        var currentBearer = bearerA
        val suppliedBearers = mutableListOf<String>()
        val runner = BugReportDeliveryRunner(
            dao = database.bugReportDao(),
            authority = BugReportRunAuthority(ownerA) { currentBearer },
            apiForBearer = { supplied ->
                suppliedBearers += supplied
                object : NoOpBugReportApi() {
                    override suspend fun create(
                        body: BugReportCreateRequest,
                        idempotencyKey: String,
                    ): BugReportCreateResponse {
                        // Account B replaces the process session after the
                        // request client was created. The immutable client
                        // still carries only A's captured bearer.
                        currentBearer = bearerB
                        return BugReportCreateResponse("server-a", "open", "2026-08-28T10:00:00Z")
                    }
                }
            },
            nowMillis = { 2L },
        )

        val outcome = runner.drain()

        assertEquals(listOf(bearerA), suppliedBearers)
        assertTrue(outcome.identityChanged)
        assertEquals(
            BugReportOutboxState.SENT,
            database.bugReportDao().observeForOwner(ownerA.companyId, ownerA.userId).first().single().state,
        )
    }

    @Test
    fun employeeBAlreadyActiveLeavesEmployeeAsQueuedBodyUntouched() = runBlocking {
        insertPendingReport("report-a")
        val bearerB = jwt("user-b", "company-1", "branch-1", "b")
        var apiCreated = false
        val runner = BugReportDeliveryRunner(
            dao = database.bugReportDao(),
            authority = BugReportRunAuthority(ownerA) { bearerB },
            apiForBearer = {
                apiCreated = true
                NoOpBugReportApi()
            },
        )

        val outcome = runner.drain()

        assertTrue(outcome.identityChanged)
        assertFalse(apiCreated)
        assertEquals(
            BugReportOutboxState.PENDING,
            database.bugReportDao().observeForOwner(ownerA.companyId, ownerA.userId).first().single().state,
        )
    }

    @Test
    fun moreThanOneReportPageIsDrainedInTheSameRun() = runBlocking {
        val localIds = (0 until 25).map { "report-${it.toString().padStart(2, '0')}" }
        for (localId in localIds) insertPendingReport(localId)
        val bearerA = jwt("user-a", "company-1", "branch-1", "a")
        var createCount = 0
        val runner = BugReportDeliveryRunner(
            dao = database.bugReportDao(),
            authority = BugReportRunAuthority(ownerA) { bearerA },
            apiForBearer = {
                object : NoOpBugReportApi() {
                    override suspend fun create(
                        body: BugReportCreateRequest,
                        idempotencyKey: String,
                    ): BugReportCreateResponse {
                        createCount += 1
                        return BugReportCreateResponse(
                            id = "server-$idempotencyKey",
                            status = "open",
                            createdAt = "2026-08-28T10:00:00Z",
                        )
                    }
                }
            },
        )

        val outcome = runner.drain()

        assertFalse(outcome.retryNeeded)
        assertEquals(25, createCount)
        localIds.forEach { localId ->
            assertEquals(
                BugReportOutboxState.SENT,
                database.bugReportDao().reportForOwner(localId, "company-1", "user-a")?.state,
            )
        }
    }

    @Test
    fun moreThanOneAttachmentPageIsDrainedInTheSameRun() = runBlocking {
        val dao = database.bugReportDao()
        repeat(25) { index ->
            val suffix = index.toString().padStart(2, '0')
            dao.insertBundle(
                report = LocalBugReportEntity(
                    localId = "report-$suffix",
                    ownerCompanyId = ownerScope.companyId,
                    ownerUserId = ownerScope.userId,
                    requestJson = "{}",
                    title = "Action failed · POS",
                    screen = "POS",
                    createdAtMillis = index.toLong(),
                    state = BugReportOutboxState.SENT,
                    serverId = "server-report-$suffix",
                ),
                attachment = LocalBugReportAttachmentEntity(
                    localId = "attachment-$suffix",
                    reportLocalId = "report-$suffix",
                    ownerCompanyId = ownerScope.companyId,
                    ownerUserId = ownerScope.userId,
                    filename = "support-image-$suffix.jpg",
                    contentType = "image/jpeg",
                    content = byteArrayOf(1, 2, 3),
                    byteSize = 3,
                    createdAtMillis = index.toLong(),
                ),
            )
        }
        val bearerA = jwt("user-a", "company-1", "branch-1", "a")
        var uploadCount = 0
        val runner = BugReportDeliveryRunner(
            dao = dao,
            authority = BugReportRunAuthority(ownerA) { bearerA },
            apiForBearer = {
                object : NoOpBugReportApi() {
                    override suspend fun uploadAttachment(
                        reportId: String,
                        file: MultipartBody.Part,
                        idempotencyKey: String,
                    ): BugReportAttachment {
                        uploadCount += 1
                        return BugReportAttachment(
                            id = "server-$idempotencyKey",
                            filename = "support-image.jpg",
                            contentType = "image/jpeg",
                            byteSize = 3,
                            createdAt = "2026-08-28T10:00:00Z",
                        )
                    }
                }
            },
        )

        val outcome = runner.drain()

        assertFalse(outcome.retryNeeded)
        assertEquals(25, uploadCount)
        val stored = dao.observeAttachmentsForOwner("company-1", "user-a").first()
        assertEquals(25, stored.size)
        assertTrue(stored.all { it.state == BugReportOutboxState.SENT && it.content.isEmpty() })
    }

    private suspend fun insertPendingReport(localId: String) {
        val request = BugReportCreateRequest(
            category = BugReportCategory.Other,
            severity = BugReportSeverity.High,
            title = "Action failed · POS",
            description = "The payment action did not complete.",
            clientContext = BugReportClientContext(
                platform = "android",
                currentScreen = "POS",
                connectivity = "offline",
            ),
        )
        database.bugReportDao().insert(
            LocalBugReportEntity(
                localId = localId,
                ownerCompanyId = ownerScope.companyId,
                ownerUserId = ownerScope.userId,
                requestJson = ApiClient.json.encodeToString(request),
                title = request.title,
                screen = "POS",
                createdAtMillis = 1L,
            ),
        )
    }

    private open class NoOpBugReportApi : BugReportApi {
        override suspend fun create(
            body: BugReportCreateRequest,
            idempotencyKey: String,
        ): BugReportCreateResponse = error("not expected")

        override suspend fun mine(limit: Int, offset: Int): BugReportMinePage = BugReportMinePage()

        override suspend fun uploadAttachment(
            reportId: String,
            file: MultipartBody.Part,
            idempotencyKey: String,
        ): BugReportAttachment = error("not expected")
    }

    private fun jwt(
        userId: String,
        companyId: String,
        branchId: String,
        marker: String,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload =
            """{"sub":"$userId","company_id":"$companyId","branch_id":"$branchId"}"""
        return listOf("{}", payload, marker)
            .joinToString(".") { encoder.encodeToString(it.encodeToByteArray()) }
    }
}
