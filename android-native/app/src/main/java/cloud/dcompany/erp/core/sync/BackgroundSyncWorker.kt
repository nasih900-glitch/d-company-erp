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
import cloud.dcompany.erp.PersistedStartupStateResult
import cloud.dcompany.erp.retryFailedPersistedStartupForWorkAttempt
import cloud.dcompany.erp.core.auth.AccessTokenIdentityParser
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeException
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.CachedScopeLeaseAdoption
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import cloud.dcompany.erp.core.auth.ScopedCommitResult
import cloud.dcompany.erp.core.auth.TerminalResolution
import cloud.dcompany.erp.core.auth.resolveTerminalAssignment
import cloud.dcompany.erp.core.db.UnresolvedOutboxGroup
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.Terminal
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
        if (
            app.awaitPersistedStartupState(
                retryFailed = retryFailedPersistedStartupForWorkAttempt(runAttemptCount),
            ) !is PersistedStartupStateResult.Ready
        ) {
            return Result.retry()
        }
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
                        releaseWorkerAdoptedScope(app, prepared.workerActivatedLease)
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

        val requiresTerminal = EffectivePermissions.from(liveProfile).requiresOperationalWorkspace()
        val terminalId = if (requiresTerminal) {
            val branchId = liveProfile.branchId?.trim()?.takeIf(String::isNotEmpty)
                ?: return BackgroundScopeResult.BLOCKED
            val savedTerminalId = app.terminalStore.terminalId()?.trim()?.takeIf(String::isNotEmpty)
                ?: return BackgroundScopeResult.BLOCKED
            val verifiedTerminalId = confirmedBackgroundHybridTerminalId(
                branchId = branchId,
                availableTerminals = ApiClient.api.terminals(branchId),
                savedTerminalId = savedTerminalId,
            ) ?: return BackgroundScopeResult.BLOCKED
            if (!app.terminalStore.hasCachedValidated(savedTerminalId, branchId)) {
                return BackgroundScopeResult.BLOCKED
            }
            verifiedTerminalId
        } else {
            null
        }

        val expectedScope = CacheScope(
            userId = liveProfile.userId.trim(),
            companyId = liveProfile.companyId.trim(),
            branchId = liveProfile.branchId?.trim()?.takeIf(String::isNotEmpty),
            terminalId = terminalId,
        )
        if (app.tokens.currentAccessFor(sessionLease) == null) {
            return BackgroundScopeResult.NO_SESSION
        }
        val adoptionResult = try {
            app.cacheIsolation.adoptCachedOnlyIfInactive(expectedScope)
        } catch (_: CacheScopeException) {
            return BackgroundScopeResult.BLOCKED
        }
        val adoption = adoptionResult as? CachedScopeLeaseAdoption.Ready
            ?: return BackgroundScopeResult.BLOCKED
        val workerActivatedLease = adoption.adoptedLease
        val terminalActivation = app.cacheIsolation.commitResultIfCurrent(adoption.lease) {
            !requiresTerminal || app.terminalStore.activateCachedValidated(
                terminalId,
                expectedScope.branchId,
            )
        }
        if (
            terminalActivation !is ScopedCommitResult.Committed ||
            !terminalActivation.value
        ) {
            releaseWorkerAdoptedScope(app, workerActivatedLease)
            return BackgroundScopeResult.BLOCKED
        }
        if (app.tokens.currentAccessFor(sessionLease) == null) {
            releaseWorkerAdoptedScope(app, workerActivatedLease)
            return BackgroundScopeResult.NO_SESSION
        }
        // Publish the terminal header only while the exact lease selected
        // above is still current. A foreground B activation that wins first
        // must not inherit a stale A worker's header.
        val terminalHeaderActivated = app.cacheIsolation.commitIfCurrent(adoption.lease) {
            ApiClient.activateTerminalScope(terminalId)
        }
        if (!terminalHeaderActivated) {
            releaseWorkerAdoptedScope(app, workerActivatedLease)
            return BackgroundScopeResult.BLOCKED
        }
        return BackgroundScopeResult.Ready(workerActivatedLease)
    }

    private suspend fun releaseWorkerAdoptedScope(
        app: DCompanyApp,
        adoptedLease: CacheScopeLease?,
    ) {
        if (adoptedLease != null) {
            app.cacheIsolation.deactivateIfCurrentWithCleanup(adoptedLease) {
                // These are process-global projections of the exact lease.
                // Clear them while the cache-scope mutex is still held so a
                // foreground B activation cannot be published in between and
                // then erased by this stale A worker.
                ApiClient.deactivateTerminalScope()
                app.terminalStore.deactivateValidatedDisplay()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "DCompanyBackgroundSync"
    }
}

/**
 * Background work may reopen only the exact identity previously chosen by the
 * foreground. It never repairs or reassigns a stale cache. The active Gaming
 * Centre release also fails closed unless the server returns exactly one
 * Hybrid workspace for the authenticated branch.
 */
internal fun confirmedBackgroundHybridTerminalId(
    branchId: String?,
    availableTerminals: List<Terminal>,
    savedTerminalId: String?,
): String? = (resolveTerminalAssignment(
        requiresPosTerminal = true,
        branchId = branchId,
        availableTerminals = availableTerminals,
        cachedTerminalId = savedTerminalId,
        // A worker is a recovery path, never an assignment path.
        hasUnresolvedLocalWork = true,
        singleHybridOnly = true,
    ) as? TerminalResolution.Resolved)?.terminal?.id

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
