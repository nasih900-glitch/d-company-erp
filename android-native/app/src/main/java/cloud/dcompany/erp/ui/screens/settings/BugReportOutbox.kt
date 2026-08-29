package cloud.dcompany.erp.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.db.BugReportDao
import cloud.dcompany.erp.core.db.BugReportOutboxState
import cloud.dcompany.erp.core.db.LocalBugReportEntity
import cloud.dcompany.erp.core.db.LocalBugReportAttachmentEntity
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

internal interface BugReportOutboxGateway {
    fun observe(owner: BugReportOwnerScope): kotlinx.coroutines.flow.Flow<BugReportOutboxSnapshot>
    suspend fun capture(
        owner: BugReportOwnerScope,
        localId: String,
        request: BugReportCreateRequest,
        attachment: BugReportAttachmentDraft?,
    )
    suspend fun retry(owner: BugReportOwnerScope, localId: String): Boolean
    suspend fun retryAttachment(owner: BugReportOwnerScope, localId: String): Boolean
    suspend fun discardAfterReview(owner: BugReportOwnerScope, localId: String): Boolean
    fun ensureDeliveryScheduled()
}

/**
 * Serialises staff-initiated support mutations with cache-scope transitions.
 * A sign-out or till reassignment therefore either observes the newly queued
 * request and stops, or revokes the lease before the write can reach Room.
 */
internal fun interface BugReportScopedCommitter {
    suspend fun commit(write: suspend () -> Unit): Boolean
}

internal class BugReportOutbox(
    private val dao: BugReportDao,
    private val scheduleDelivery: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val scopedCommitter: BugReportScopedCommitter = BugReportScopedCommitter { write ->
        write()
        true
    },
) : BugReportOutboxGateway {
    override fun observe(owner: BugReportOwnerScope) = combine(
        dao.observeForOwner(companyId = owner.companyId, userId = owner.userId),
        dao.observeAttachmentsForOwner(companyId = owner.companyId, userId = owner.userId),
    ) { reports, attachments -> BugReportOutboxSnapshot(reports, attachments) }

    override suspend fun capture(
        owner: BugReportOwnerScope,
        localId: String,
        request: BugReportCreateRequest,
        attachment: BugReportAttachmentDraft?,
    ) {
        // The till name is useful historical evidence, but the UUID is live
        // routing authority. Persisting that UUID would make a later retry
        // fail when the till is archived/reassigned. Every delivery uses no
        // terminal header, so its idempotency authority remains user-scoped.
        val durableRequest = request.withStableSupportRouting()
        val screen = durableRequest.clientContext.currentScreen ?: "ERP"
        val capturedAt = nowMillis()
        val committed = scopedCommitter.commit {
            dao.insertBundle(
                report = LocalBugReportEntity(
                    localId = localId,
                    ownerCompanyId = owner.companyId,
                    ownerUserId = owner.userId,
                    requestJson = ApiClient.json.encodeToString(durableRequest),
                    title = durableRequest.title,
                    screen = screen,
                    createdAtMillis = capturedAt,
                ),
                attachment = attachment?.let { image ->
                    LocalBugReportAttachmentEntity(
                        localId = UUID.randomUUID().toString(),
                        reportLocalId = localId,
                        ownerCompanyId = owner.companyId,
                        ownerUserId = owner.userId,
                        filename = image.filename,
                        contentType = image.contentType,
                        content = image.content.copyOf(),
                        byteSize = image.byteSize,
                        createdAtMillis = capturedAt,
                    )
                },
            )
        }
        check(committed) { "The authenticated workspace changed before the help request was saved" }
        // The durable work request is committed only after Room succeeds.
        scheduleDelivery()
    }

    override suspend fun retry(owner: BugReportOwnerScope, localId: String): Boolean {
        var queued = false
        val committed = scopedCommitter.commit {
            queued = dao.retry(localId, owner.companyId, owner.userId) == 1
        }
        if (!committed) return false
        if (queued) scheduleDelivery()
        return queued
    }

    override suspend fun retryAttachment(owner: BugReportOwnerScope, localId: String): Boolean {
        var queued = false
        val committed = scopedCommitter.commit {
            queued = dao.retryAttachment(localId, owner.companyId, owner.userId) == 1
        }
        if (!committed) return false
        if (queued) scheduleDelivery()
        return queued
    }

    override suspend fun discardAfterReview(
        owner: BugReportOwnerScope,
        localId: String,
    ): Boolean {
        var discarded = false
        val committed = scopedCommitter.commit {
            discarded = dao.discardAfterReview(localId, owner.companyId, owner.userId)
        }
        return committed && discarded
    }

    override fun ensureDeliveryScheduled() = scheduleDelivery()
}

/**
 * Keep immutable diagnostic context while removing a terminal UUID whose
 * server lifecycle can change before an offline report is delivered.
 */
internal fun BugReportCreateRequest.withStableSupportRouting(): BugReportCreateRequest = copy(
    clientContext = clientContext.copy(terminalId = null),
)

internal data class BugReportOutboxSnapshot(
    val reports: List<LocalBugReportEntity>,
    val attachments: List<LocalBugReportAttachmentEntity>,
)

internal object BugReportSyncScheduler {
    private const val UNIQUE_WORK = "dcompany-bug-report-outbox"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<BugReportSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            // A single pass drains every pending report for the current user.
            // Replacing the hand-off closes the insert-vs-worker-finish race;
            // stable request JSON and localId keep replacement/cancellation safe.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

internal const val BUG_REPORT_ATTACHMENT_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L

internal object BugReportPrivacyScheduler {
    private const val UNIQUE_WORK = "dcompany-bug-report-image-expiry"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<BugReportAttachmentExpiryWorker>(1, TimeUnit.DAYS)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class BugReportAttachmentExpiryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DCompanyApp ?: return Result.failure()
        return try {
            app.db.bugReportDao().expireAttachmentContent(
                cutoffMillis = System.currentTimeMillis() - BUG_REPORT_ATTACHMENT_RETENTION_MILLIS,
                message = "Image removed automatically after 30 days for privacy. The help request is unchanged.",
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal enum class BugReportFailureDisposition { Retry, ActionRequired }

internal fun bugReportFailureDisposition(error: Throwable): BugReportFailureDisposition {
    val api = error as? ApiException ?: return BugReportFailureDisposition.Retry
    return when {
        api.isAmbiguous || api.status == 429 -> BugReportFailureDisposition.Retry
        else -> BugReportFailureDisposition.ActionRequired
    }
}

/**
 * One authenticated identity for a complete delivery pass. The provider may
 * return a rotated token only while it retains the original login lineage;
 * parsing it again prevents a same-company employee switch from being treated
 * as equivalent. The returned bearer is passed to an isolated API client and
 * is never replaced by ApiClient's mutable process token mid-request.
 */
internal class BugReportRunAuthority(
    val owner: OutboxOwnerIdentity,
    private val bearerProvider: () -> String?,
) {
    fun currentBearer(): String? {
        val bearer = bearerProvider()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return bearer.takeIf { AccessTokenIdentityParser.parse(it) == owner }
    }
}

internal data class BugReportDeliveryOutcome(
    val retryNeeded: Boolean,
    val identityChanged: Boolean = false,
)

private const val BUG_REPORT_DELIVERY_BATCH_SIZE = 20

/** Testable drain loop; every write is owner-scoped even after network IO. */
internal class BugReportDeliveryRunner(
    private val dao: BugReportDao,
    private val authority: BugReportRunAuthority,
    /** Always builds a non-refreshing client with no live terminal header. */
    private val apiForBearer: (String) -> BugReportApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val companyId = authority.owner.companyId
    private val userId = authority.owner.userId
    private var cachedBearer: String? = null
    private var cachedApi: BugReportApi? = null

    private fun api(bearer: String): BugReportApi {
        if (cachedBearer == bearer) return requireNotNull(cachedApi)
        return apiForBearer(bearer).also {
            cachedBearer = bearer
            cachedApi = it
        }
    }

    suspend fun drain(): BugReportDeliveryOutcome {
        var retryNeeded = false
        var reportCursorTime: Long? = null
        var reportCursorId = ""
        while (true) {
            val rows = dao.pendingForOwner(
                companyId = companyId,
                userId = userId,
                afterCreatedAtMillis = reportCursorTime,
                afterLocalId = reportCursorId,
                limit = BUG_REPORT_DELIVERY_BATCH_SIZE,
            )
            if (rows.isEmpty()) break
            for (row in rows) {
                reportCursorTime = row.createdAtMillis
                reportCursorId = row.localId
                val request = try {
                    ApiClient.json.decodeFromString<BugReportCreateRequest>(row.requestJson)
                } catch (_: Exception) {
                    dao.markActionRequired(
                        localId = row.localId,
                        companyId = companyId,
                        userId = userId,
                        message = "This saved request cannot be read by this app version. Update the app, then retry it.",
                        attemptedAtMillis = nowMillis(),
                    )
                    continue
                }
                val bearer = authority.currentBearer()
                    ?: return BugReportDeliveryOutcome(retryNeeded, identityChanged = true)
                try {
                    val response = api(bearer).create(request, row.localId)
                    dao.markSent(
                        localId = row.localId,
                        companyId = companyId,
                        userId = userId,
                        serverId = response.id,
                        serverStatus = response.status,
                        serverCreatedAt = response.createdAt,
                        attemptedAtMillis = nowMillis(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val readable = error.bugReportReadable()
                    when (bugReportFailureDisposition(error)) {
                        BugReportFailureDisposition.Retry -> {
                            dao.noteRetryableFailure(
                                row.localId,
                                companyId,
                                userId,
                                readable,
                                nowMillis(),
                            )
                            retryNeeded = true
                        }
                        BugReportFailureDisposition.ActionRequired -> {
                            dao.markActionRequired(
                                row.localId,
                                companyId,
                                userId,
                                readable,
                                nowMillis(),
                            )
                        }
                    }
                    Log.w("BugReportSync", "Support request delivery was not confirmed")
                }
                if (authority.currentBearer() == null) {
                    return BugReportDeliveryOutcome(retryNeeded, identityChanged = true)
                }
            }
            if (rows.size < BUG_REPORT_DELIVERY_BATCH_SIZE) break
        }

        var attachmentCursorTime: Long? = null
        var attachmentCursorId = ""
        while (true) {
            val attachments = dao.pushableAttachments(
                companyId = companyId,
                userId = userId,
                afterCreatedAtMillis = attachmentCursorTime,
                afterLocalId = attachmentCursorId,
                limit = BUG_REPORT_DELIVERY_BATCH_SIZE,
            )
            if (attachments.isEmpty()) break
            for (attachment in attachments) {
                attachmentCursorTime = attachment.createdAtMillis
                attachmentCursorId = attachment.localId
                val report = dao.reportForOwner(attachment.reportLocalId, companyId, userId)
                val reportId = report?.serverId
                if (reportId == null) {
                    retryNeeded = true
                    continue
                }
                val bearer = authority.currentBearer()
                    ?: return BugReportDeliveryOutcome(retryNeeded, identityChanged = true)
                try {
                    val body = attachment.content.toRequestBody(attachment.contentType.toMediaType())
                    val part = MultipartBody.Part.createFormData("file", attachment.filename, body)
                    val response = api(bearer).uploadAttachment(
                        reportId,
                        part,
                        attachment.localId,
                    )
                    dao.markAttachmentSent(
                        localId = attachment.localId,
                        companyId = companyId,
                        userId = userId,
                        serverId = response.id,
                        attemptedAtMillis = nowMillis(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val readable = error.bugReportReadable()
                    when (bugReportFailureDisposition(error)) {
                        BugReportFailureDisposition.Retry -> {
                            dao.noteAttachmentRetryableFailure(
                                attachment.localId,
                                companyId,
                                userId,
                                readable,
                                nowMillis(),
                            )
                            retryNeeded = true
                        }
                        BugReportFailureDisposition.ActionRequired -> {
                            dao.markAttachmentActionRequired(
                                attachment.localId,
                                companyId,
                                userId,
                                readable,
                                nowMillis(),
                            )
                        }
                    }
                    Log.w("BugReportSync", "Support attachment delivery was not confirmed")
                }
                if (authority.currentBearer() == null) {
                    return BugReportDeliveryOutcome(retryNeeded, identityChanged = true)
                }
            }
            if (attachments.size < BUG_REPORT_DELIVERY_BATCH_SIZE) break
        }
        return BugReportDeliveryOutcome(retryNeeded)
    }
}

/**
 * Sends only rows owned by the currently authenticated live identity. This is
 * intentionally separate from the financial SyncEngine, so Help never blocks
 * a shift close. It does participate in account/workspace switching until the
 * saved request is delivered or explicitly reviewed, preventing attribution
 * and terminal-idempotency drift on a shared tablet.
 */
class BugReportSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? DCompanyApp ?: return Result.failure()
        try {
            app.db.bugReportDao().expireAttachmentContent(
                cutoffMillis = System.currentTimeMillis() - BUG_REPORT_ATTACHMENT_RETENTION_MILLIS,
                message = "Image removed automatically after 30 days for privacy. The help request is unchanged.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Delivery can still proceed; the independent daily privacy job
            // will retry the bounded cleanup.
        }
        val sessionLease = app.tokens.refreshLease() ?: return Result.success()

        val me = try {
            ApiClient.api.me()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed refresh clears the session; leave the row for the same
            // employee's next sign-in instead of spinning as another account.
            return if (app.tokens.currentAccessFor(sessionLease) == null) {
                Result.success()
            } else {
                Result.retry()
            }
        }
        val authenticatedOwner = OutboxOwnerIdentity.from(me)
        val authority = BugReportRunAuthority(authenticatedOwner) {
            app.tokens.currentAccessFor(sessionLease)
        }
        // `/me` may have completed after a sign-out/login or a branch change.
        // Do not even select rows unless its exact authenticated identity still
        // owns the captured session lineage.
        if (authority.currentBearer() == null) return Result.success()
        val dao = app.db.bugReportDao()
        val outcome = BugReportDeliveryRunner(
            dao = dao,
            authority = authority,
            // Support requests carry their historical branch/till as evidence
            // in the body. They deliberately do not inherit the tablet's live
            // terminal header, so a later workspace change cannot alter the
            // server idempotency scope or conflict with the saved context.
            apiForBearer = { bearer ->
                ApiClient.createEphemeralAuthorityApi<BugReportApi>(
                    accessToken = bearer,
                    terminalId = null,
                )
            },
        ).drain()
        return if (outcome.retryNeeded && !outcome.identityChanged) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
