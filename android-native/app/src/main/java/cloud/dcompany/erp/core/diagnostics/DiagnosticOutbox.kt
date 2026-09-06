package cloud.dcompany.erp.core.diagnostics

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.update.InstallationIdentityStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import retrofit2.http.Body
import retrofit2.http.POST

@Entity(
    tableName = "diagnostic_outbox",
    indices = [
        Index(value = ["state", "occurredAtMillis"]),
        Index(value = ["localScopeHash", "state", "occurredAtMillis"]),
        Index(value = ["localDedupeKey"], unique = true),
    ],
)
internal data class DiagnosticEnvelopeEntity(
    @PrimaryKey val clientEventId: String,
    /** Local-only OS/source dedupe identity. It is never sent. */
    val localDedupeKey: String?,
    /** Local-only ownership guard. It is never included in a wire DTO. */
    val localScopeHash: String?,
    val eventType: String,
    val severity: String,
    val occurredAtMillis: Long,
    val capturedVersionName: String,
    val capturedVersionCode: Int,
    val capturedOsApiLevel: Int,
    val component: String,
    val reasonCode: String,
    val failureFingerprint: String?,
    val httpStatus: Int?,
    val durationBucket: String?,
    val connectivity: String,
    val pendingOutboxCount: Int?,
    val state: String = DiagnosticOutboxState.PENDING,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long? = null,
)

internal object DiagnosticOutboxState {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val REJECTED = "rejected"
    /**
     * Retained locally for bounded forensic accounting, but never eligible
     * for upload. This is used when ownership cannot be proven or the server
     * reports a permanent event-id/payload mismatch.
     */
    const val QUARANTINED = "quarantined"
}

@Dao
internal interface DiagnosticOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: DiagnosticEnvelopeEntity): Long

    @Query(
        "SELECT * FROM diagnostic_outbox WHERE state = 'pending' " +
            "AND localScopeHash = :scopeHash " +
            "ORDER BY occurredAtMillis ASC, clientEventId ASC LIMIT :limit",
    )
    suspend fun pendingForScope(
        scopeHash: String,
        limit: Int = MAX_DIAGNOSTIC_UPLOAD_BATCH,
    ): List<DiagnosticEnvelopeEntity>

    @Query(
        "UPDATE diagnostic_outbox SET state = 'sent', attemptCount = attemptCount + 1, " +
            "lastAttemptAtMillis = :acknowledgedAtMillis WHERE clientEventId IN (:clientEventIds) " +
            "AND state = 'pending'",
    )
    suspend fun markAcknowledged(
        clientEventIds: Set<String>,
        acknowledgedAtMillis: Long,
    ): Int

    @Query(
        "UPDATE diagnostic_outbox SET attemptCount = attemptCount + 1, " +
            "lastAttemptAtMillis = :attemptedAtMillis WHERE clientEventId IN (:clientEventIds) " +
            "AND state = 'pending'",
    )
    suspend fun noteRetry(clientEventIds: Set<String>, attemptedAtMillis: Long): Int

    @Query(
        "UPDATE diagnostic_outbox SET state = 'rejected', attemptCount = attemptCount + 1, " +
            "lastAttemptAtMillis = :attemptedAtMillis WHERE clientEventId IN (:clientEventIds) " +
            "AND state = 'pending'",
    )
    suspend fun markRejected(clientEventIds: Set<String>, attemptedAtMillis: Long): Int

    @Query(
        "UPDATE diagnostic_outbox SET state = 'quarantined', attemptCount = attemptCount + 1, " +
            "lastAttemptAtMillis = :attemptedAtMillis WHERE clientEventId IN (:clientEventIds) " +
            "AND state = 'pending'",
    )
    suspend fun markQuarantined(clientEventIds: Set<String>, attemptedAtMillis: Long): Int

    /** Upgrade guard for Code 15 builds that may already contain old unbound pending rows. */
    @Query(
        "UPDATE diagnostic_outbox SET state = 'quarantined' " +
            "WHERE state = 'pending' AND localScopeHash IS NULL",
    )
    suspend fun quarantineUnboundPending(): Int

    @Query("SELECT COUNT(*) FROM diagnostic_outbox WHERE state = :state")
    suspend fun count(state: String): Int

    @Query(
        "DELETE FROM diagnostic_outbox WHERE clientEventId IN (" +
            "SELECT clientEventId FROM diagnostic_outbox " +
            "ORDER BY CASE state " +
            "WHEN 'sent' THEN 0 WHEN 'quarantined' THEN 1 WHEN 'rejected' THEN 2 ELSE 3 END ASC, " +
            "occurredAtMillis ASC, clientEventId ASC " +
            "LIMIT MAX(0, (SELECT COUNT(*) FROM diagnostic_outbox) - :maximumRows))",
    )
    suspend fun pruneToMaximum(maximumRows: Int): Int

    @Transaction
    suspend fun insertBounded(
        entity: DiagnosticEnvelopeEntity,
        maximumRows: Int = MAX_DIAGNOSTIC_OUTBOX_ROWS,
    ): Boolean {
        require(maximumRows > 0)
        val inserted = insertIgnore(entity) != -1L
        pruneToMaximum(maximumRows)
        return inserted
    }
}

@Database(
    entities = [DiagnosticEnvelopeEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class DiagnosticDatabase : RoomDatabase() {
    abstract fun outboxDao(): DiagnosticOutboxDao
}

internal object DiagnosticDatabaseProvider {
    @Volatile private var instance: DiagnosticDatabase? = null

    fun get(context: Context): DiagnosticDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            DiagnosticDatabase::class.java,
            "dcompany-diagnostics.db",
        )
            // This database contains no business transaction or user content.
            // Future schema changes still require explicit migrations so a
            // diagnostics upgrade cannot hide a compatibility mistake.
            .build()
            .also { instance = it }
    }
}

internal class DiagnosticOutbox(
    private val dao: DiagnosticOutboxDao,
    private val scheduleDelivery: () -> Unit,
) {
    suspend fun capture(event: DiagnosticEvent, localScopeHash: String?): Boolean {
        val normalisedScope = localScopeHash?.takeIf { SAFE_SCOPE_HASH.matches(it) }
        val state = if (normalisedScope == null) {
            DiagnosticOutboxState.QUARANTINED
        } else {
            DiagnosticOutboxState.PENDING
        }
        val inserted = dao.insertBounded(event.toEntity(normalisedScope, state))
        if (inserted && state == DiagnosticOutboxState.PENDING) scheduleDelivery()
        return inserted
    }
}

private fun DiagnosticEvent.toEntity(
    localScopeHash: String?,
    state: String,
): DiagnosticEnvelopeEntity =
    DiagnosticEnvelopeEntity(
        clientEventId = clientEventId,
        localDedupeKey = localDedupeKey,
        localScopeHash = localScopeHash,
        eventType = eventType.wireValue,
        severity = severity.wireValue,
        occurredAtMillis = occurredAtMillis,
        capturedVersionName = capturedVersionName,
        capturedVersionCode = capturedVersionCode,
        capturedOsApiLevel = capturedOsApiLevel,
        component = component.wireValue,
        reasonCode = reasonCode,
        failureFingerprint = failureFingerprint,
        httpStatus = httpStatus,
        durationBucket = durationBucket?.wireValue,
        connectivity = connectivity.wireValue,
        pendingOutboxCount = pendingOutboxCount,
        state = state,
    )

private fun DiagnosticEnvelopeEntity.toEventOrNull(): DiagnosticEvent? = runCatching {
    DiagnosticEvent(
        clientEventId = clientEventId,
        localDedupeKey = localDedupeKey,
        eventType = DiagnosticEventType.entries.single { it.wireValue == eventType },
        severity = DiagnosticSeverity.entries.single { it.wireValue == severity },
        occurredAtMillis = occurredAtMillis,
        capturedVersionName = capturedVersionName,
        capturedVersionCode = capturedVersionCode,
        capturedOsApiLevel = capturedOsApiLevel,
        component = DiagnosticComponent.entries.single { it.wireValue == component },
        reasonCode = reasonCode,
        failureFingerprint = failureFingerprint,
        httpStatus = httpStatus,
        durationBucket = durationBucket?.let { value ->
            DiagnosticDurationBucket.entries.single { it.wireValue == value }
        },
        connectivity = DiagnosticConnectivity.entries.single { it.wireValue == connectivity },
        pendingOutboxCount = pendingOutboxCount,
    )
}.getOrNull()

internal fun diagnosticEventIsRetainedForUpload(
    event: DiagnosticEvent,
    nowMillis: Long,
): Boolean {
    if (nowMillis <= 0) return false
    val earliest = nowMillis - DIAGNOSTIC_UPLOAD_RETENTION_MILLIS
    val latest = nowMillis + DIAGNOSTIC_UPLOAD_FUTURE_SKEW_MILLIS
    return event.occurredAtMillis in earliest..latest
}

internal interface ClientDiagnosticApi {
    @POST("client-diagnostics/events")
    suspend fun submit(
        @Body request: ClientDiagnosticBatchRequest,
    ): ClientDiagnosticBatchResponse
}

internal enum class DiagnosticDeliveryFailureDisposition { RETRY, REJECT, QUARANTINE }

internal fun diagnosticDeliveryFailureDisposition(error: Throwable): DiagnosticDeliveryFailureDisposition {
    val api = error as? ApiException ?: return DiagnosticDeliveryFailureDisposition.RETRY
    return when {
        api.status == 409 &&
            api.code == "diagnostic_idempotency_conflict" &&
            !api.diagnosticConflictEventId.isNullOrBlank() ->
            DiagnosticDeliveryFailureDisposition.QUARANTINE
        // A server that does not identify the conflicting UUID cannot prove
        // which immutable row is bad. Retrying preserves the other evidence.
        api.status == 409 && api.code == "diagnostic_idempotency_conflict" ->
            DiagnosticDeliveryFailureDisposition.RETRY
        api.status == 409 && api.code == "diagnostic_ingest_retry" ->
            DiagnosticDeliveryFailureDisposition.RETRY
        // An unknown 409 must be retried: older/newer servers can use 409 for
        // a concurrent ingest, and dropping evidence would be irreversible.
        api.status == 409 -> DiagnosticDeliveryFailureDisposition.RETRY
        api.status in setOf(400, 422) -> DiagnosticDeliveryFailureDisposition.REJECT
        else -> DiagnosticDeliveryFailureDisposition.RETRY
    }
}

internal fun diagnosticConflictIdToQuarantine(
    error: Throwable,
    requestedIds: Set<String>,
): String? = (error as? ApiException)
    ?.takeIf {
        it.status == 409 &&
            it.code == "diagnostic_idempotency_conflict"
    }
    ?.diagnosticConflictEventId
    ?.takeIf(requestedIds::contains)

internal data class DiagnosticAcknowledgement(
    val acknowledgedIds: Set<String>,
    val responseValid: Boolean,
)

internal fun validateDiagnosticResponse(
    installationId: String,
    requestedIds: Set<String>,
    response: ClientDiagnosticBatchResponse,
): DiagnosticAcknowledgement {
    val accepted = response.acceptedEventIds.toSet()
    val duplicates = response.duplicateEventIds.toSet()
    val responseIds = accepted + duplicates
    val valid =
        response.installationId == installationId &&
            runCatching { Instant.parse(response.serverTime) }.isSuccess &&
            accepted.size == response.acceptedEventIds.size &&
            duplicates.size == response.duplicateEventIds.size &&
            accepted.intersect(duplicates).isEmpty() &&
            responseIds.all { it in requestedIds && isCanonicalUuid(it) }
    return DiagnosticAcknowledgement(
        acknowledgedIds = if (valid) responseIds else emptySet(),
        responseValid = valid,
    )
}

internal object DiagnosticSyncScheduler {
    private const val UNIQUE_NOW = "dcompany-client-diagnostics-now"
    private const val UNIQUE_PERIODIC = "dcompany-client-diagnostics-periodic"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<DiagnosticSyncWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(UNIQUE_NOW)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NOW,
            // Event UUIDs make cancellation after a server commit safe. REPLACE
            // also closes the insert-vs-worker-finish race while offline.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<DiagnosticSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint())
            .addTag(UNIQUE_PERIODIC)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraint(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

class DiagnosticSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DCompanyApp ?: return Result.retry()
        val token = app.tokens.accessToken() ?: return Result.success()
        val identity = AccessTokenIdentityParser.parse(token) ?: return Result.success()
        val scopeHash = diagnosticScopeHash(identity.companyId, identity.userId, identity.branchId)
        val installationId = InstallationIdentityStore(applicationContext).installationId()
            ?: return Result.retry()
        val dao = DiagnosticDatabaseProvider.get(applicationContext).outboxDao()
        // Rows captured by an older Code 15 candidate without a proven scope
        // must never become attributable merely because somebody later logs in.
        dao.quarantineUnboundPending()
        val api = ApiClient.createEphemeralAuthorityApi<ClientDiagnosticApi>(
            accessToken = token,
            // Diagnostics describe the client, not a till mutation. The server
            // derives company/user from JWT and accepts a missing till scope.
            terminalId = null,
        )

        repeat(MAX_BATCHES_PER_WORKER_RUN) {
            val rows = dao.pendingForScope(scopeHash)
            if (rows.isEmpty()) return Result.success()
            val nowMillis = System.currentTimeMillis()
            val prepared = rows.mapNotNull { row ->
                val event = row.toEventOrNull()
                event
                    ?.takeIf { diagnosticEventIsRetainedForUpload(it, nowMillis) }
                    ?.let { PreparedDiagnostic(row.clientEventId, it.toWireRequest()) }
            }
            val invalidIds = rows.mapTo(linkedSetOf()) { it.clientEventId } -
                prepared.mapTo(linkedSetOf()) { it.clientEventId }
            if (invalidIds.isNotEmpty()) dao.markRejected(invalidIds, nowMillis)
            if (prepared.isEmpty()) return@repeat
            val ids = prepared.mapTo(linkedSetOf()) { it.clientEventId }
            val response = try {
                api.submit(
                    ClientDiagnosticBatchRequest(
                        installationId = installationId,
                        events = prepared.map(PreparedDiagnostic::request),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val attemptedAt = System.currentTimeMillis()
                return when (diagnosticDeliveryFailureDisposition(error)) {
                    DiagnosticDeliveryFailureDisposition.RETRY -> {
                        dao.noteRetry(ids, attemptedAt)
                        Result.retry()
                    }
                    DiagnosticDeliveryFailureDisposition.REJECT -> {
                        dao.markRejected(ids, attemptedAt)
                        Result.success()
                    }
                    DiagnosticDeliveryFailureDisposition.QUARANTINE -> {
                        val conflictingId = diagnosticConflictIdToQuarantine(error, ids)
                        if (conflictingId == null) {
                            dao.noteRetry(ids, attemptedAt)
                            Result.retry()
                        } else {
                            dao.markQuarantined(setOf(conflictingId), attemptedAt)
                            val retryIds = ids - conflictingId
                            if (retryIds.isNotEmpty()) {
                                dao.noteRetry(retryIds, attemptedAt)
                                Result.retry()
                            } else {
                                Result.success()
                            }
                        }
                    }
                }
            }

            val acknowledgement = validateDiagnosticResponse(installationId, ids, response)
            if (!acknowledgement.responseValid) {
                dao.noteRetry(ids, System.currentTimeMillis())
                return Result.retry()
            }
            if (acknowledgement.acknowledgedIds.isNotEmpty()) {
                dao.markAcknowledged(
                    acknowledgement.acknowledgedIds,
                    System.currentTimeMillis(),
                )
            }
            if (acknowledgement.acknowledgedIds.size < ids.size) {
                dao.noteRetry(ids - acknowledgement.acknowledgedIds, System.currentTimeMillis())
                return Result.retry()
            }
        }
        // The queue is bounded, but avoid holding one worker/network lease for
        // an unbounded time after a large offline period.
        return Result.retry()
    }

    private companion object {
        const val MAX_BATCHES_PER_WORKER_RUN = 4
    }
}

private data class PreparedDiagnostic(
    val clientEventId: String,
    val request: ClientDiagnosticEventRequest,
)

private val SAFE_SCOPE_HASH = Regex("^[0-9a-f]{64}$")
private const val DIAGNOSTIC_UPLOAD_RETENTION_MILLIS = 89L * 24L * 60L * 60L * 1_000L
private const val DIAGNOSTIC_UPLOAD_FUTURE_SKEW_MILLIS = 23L * 60L * 60L * 1_000L
