package cloud.dcompany.erp.core.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeException
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup
import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Durable hand-off for an outbox drain when the UI process is killed. */
internal object BackgroundSyncScheduler {
    private const val UNIQUE_WORK = "dcompany-durable-outbox-sync"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackgroundSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            // KEEP can lose a process-death hand-off: an insert that arrives
            // after the running worker's final Room query is ignored, then
            // that worker succeeds and no durable request remains. REPLACE
            // leaves exactly one newest hand-off without building hundreds of
            // no-op chained jobs during a long offline shift. One pass drains
            // the whole outbox, so the newest request subsumes the older one;
            // every network write has a stable idempotency identity if a
            // replaced worker was cancelled after the server committed it.
            DURABLE_SYNC_EXISTING_WORK_POLICY,
            request,
        )
    }
}

internal val DURABLE_SYNC_EXISTING_WORK_POLICY = ExistingWorkPolicy.REPLACE

/**
 * WorkManager starts [DCompanyApp] before constructing this worker. The worker
 * still re-verifies the live account and till, then reopens only the exact
 * cache scope previously committed online. It never selects another terminal,
 * changes ownership, or creates a new shift.
 */
class BackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? DCompanyApp ?: return Result.failure()
        return try {
            when (val prepared = prepareVerifiedScope(app)) {
                BackgroundScopeResult.NO_SESSION,
                BackgroundScopeResult.BLOCKED,
                -> Result.success()

                is BackgroundScopeResult.Ready -> {
                    try {
                        app.sync.syncFromBackgroundWorker()
                        val groups = app.db.outboxSafetyDao().unresolvedGroups()
                        if (shouldRetryBackgroundSync(groups)) {
                            Result.retry()
                        } else {
                            Result.success()
                        }
                    } finally {
                        // A cold WorkManager process must not leave an
                        // authenticated cache lease active after its job. A
                        // foreground restore creates a newer lease, and the
                        // compare-and-deactivate guard cannot revoke that one.
                        prepared.workerActivatedLease?.let { lease ->
                            if (app.cacheIsolation.deactivateIfCurrent(lease)) {
                                ApiClient.deactivateTerminalScope()
                                app.terminalStore.deactivateValidatedDisplay()
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(LOG_TAG, "Durable outbox sync could not complete", failure)
            // A temporary ERP outage must never turn a captured payment,
            // stock movement, shift action, or other pushable write into an
            // abandoned queue merely because the app stayed closed through a
            // fixed number of retries. WorkManager already provides bounded
            // exponential backoff and its network constraint prevents an
            // offline spin. If Room itself cannot be inspected, retry too:
            // success is safe only once we can prove no automatic work remains.
            val groups = runCatching {
                app.db.outboxSafetyDao().unresolvedGroups()
            }.getOrNull()
            if (groups == null || shouldRetryBackgroundSync(groups)) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

    private suspend fun prepareVerifiedScope(app: DCompanyApp): BackgroundScopeResult {
        val sessionLease = app.tokens.refreshLease() ?: return BackgroundScopeResult.NO_SESSION
        val cachedProfile = app.shiftCache.profile.value ?: return BackgroundScopeResult.BLOCKED
        val cachedIdentity = OutboxOwnerIdentity.from(cachedProfile)
        if (AccessTokenIdentityParser.parse(sessionLease.accessToken) != cachedIdentity) {
            return BackgroundScopeResult.BLOCKED
        }

        // Both routes deliberately omit a terminal header. They prove the
        // current server account and the persisted till before any write drain.
        val liveProfile = ApiClient.api.me()
        val activeAccess = app.tokens.currentAccessFor(sessionLease)
            ?: return BackgroundScopeResult.NO_SESSION
        val liveIdentity = OutboxOwnerIdentity.from(liveProfile)
        if (
            AccessTokenIdentityParser.parse(activeAccess) != liveIdentity ||
            liveIdentity != cachedIdentity
        ) {
            return BackgroundScopeResult.BLOCKED
        }

        val requiresTerminal = EffectivePermissions.from(liveProfile).has(ErpPermission.PosRead)
        val terminalId = if (requiresTerminal) {
            val branchId = liveProfile.branchId?.trim()?.takeIf(String::isNotEmpty)
                ?: return BackgroundScopeResult.BLOCKED
            val savedTerminalId = app.terminalStore.terminalId()?.trim()?.takeIf(String::isNotEmpty)
                ?: return BackgroundScopeResult.BLOCKED
            val savedStillValid = ApiClient.api.terminals(branchId).any {
                it.id == savedTerminalId && it.branchId == branchId
            }
            if (!savedStillValid) return BackgroundScopeResult.BLOCKED
            if (!app.terminalStore.hasCachedValidated(savedTerminalId, branchId)) {
                return BackgroundScopeResult.BLOCKED
            }
            savedTerminalId
        } else {
            null
        }

        val expectedScope = CacheScope(
            userId = liveProfile.userId.trim(),
            companyId = liveProfile.companyId.trim(),
            branchId = liveProfile.branchId?.trim()?.takeIf(String::isNotEmpty),
            terminalId = terminalId,
        )
        val currentLease = app.cacheIsolation.currentLease()
        if (currentLease != null && currentLease.scope != expectedScope) {
            return BackgroundScopeResult.BLOCKED
        }
        if (app.tokens.currentAccessFor(sessionLease) == null) {
            return BackgroundScopeResult.NO_SESSION
        }
        val workerActivatedLease: CacheScopeLease?
        if (currentLease == null) {
            try {
                workerActivatedLease = app.cacheIsolation
                    .activateCachedWithLease(expectedScope)
                    .lease
            } catch (_: CacheScopeException) {
                return BackgroundScopeResult.BLOCKED
            }
        } else {
            workerActivatedLease = null
        }
        if (requiresTerminal && !app.terminalStore.activateCachedValidated(
                terminalId,
                expectedScope.branchId,
            )
        ) {
            workerActivatedLease?.let { app.cacheIsolation.deactivateIfCurrent(it) }
            return BackgroundScopeResult.BLOCKED
        }
        ApiClient.activateTerminalScope(terminalId)
        return BackgroundScopeResult.Ready(workerActivatedLease)
    }

    private companion object {
        const val LOG_TAG = "DCompanyBackgroundSync"
    }
}

internal sealed interface BackgroundScopeResult {
    data object NO_SESSION : BackgroundScopeResult
    data object BLOCKED : BackgroundScopeResult
    data class Ready(val workerActivatedLease: CacheScopeLease?) : BackgroundScopeResult
}

/** Only automatically replay states that SyncEngine itself considers pushable. */
internal fun hasBackgroundRetryableWork(groups: List<UnresolvedOutboxGroup>): Boolean = groups.any {
    isBackgroundRetryableGroup(it)
}

internal fun shouldRetryBackgroundSync(
    groups: List<UnresolvedOutboxGroup>,
): Boolean = hasBackgroundRetryableWork(groups)
