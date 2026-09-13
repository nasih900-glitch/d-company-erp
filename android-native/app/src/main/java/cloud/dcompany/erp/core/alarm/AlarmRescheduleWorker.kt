package cloud.dcompany.erp.core.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.PersistedStartupStateResult
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal enum class AlarmRescheduleAttemptResult { COMPLETE, RETRY }

internal enum class AlarmRescheduleWorkDisposition { SUCCESS, RETRY }

/**
 * Execute one local alarm-recovery attempt. Unknown startup/scope authority
 * preserves the alarm ledger; only a restored, definitive no-owner result may
 * cancel alarms.
 */
internal suspend fun runAlarmRescheduleAttempt(
    restorePersistedAuthority: suspend () -> PersistedStartupStateResult,
    ensureActiveOwnedScope: suspend () -> Boolean,
    cancelAll: () -> Unit,
    prepareForSystemReschedule: () -> Boolean,
    reconcileGaming: suspend () -> Unit,
    reconcileHeldOrders: suspend () -> Unit,
    requestLatestScopeReconciliation: () -> Unit = {},
): AlarmRescheduleAttemptResult {
    val restored = try {
        restorePersistedAuthority()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return AlarmRescheduleAttemptResult.RETRY
    }
    if (restored !is PersistedStartupStateResult.Ready) {
        return AlarmRescheduleAttemptResult.RETRY
    }

    return try {
        if (!ensureActiveOwnedScope()) {
            try {
                cancelAll()
            } finally {
                // cancelAll is intentionally global because this is the proven
                // no-owner path. A foreground workspace may still activate after
                // that proof but before this call. Always replay the process-owned
                // observers after cancellation, including after a failed ledger
                // commit; B activation itself requests another replay when it wins
                // after this request.
                requestLatestScopeReconciliation()
            }
        } else {
            if (!prepareForSystemReschedule()) return AlarmRescheduleAttemptResult.RETRY
            reconcileGaming()
            reconcileHeldOrders()
        }
        AlarmRescheduleAttemptResult.COMPLETE
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AlarmRescheduleAttemptResult.RETRY
    }
}

internal fun alarmRescheduleWorkDisposition(
    attempt: AlarmRescheduleAttemptResult,
    runAttemptCount: Int,
): AlarmRescheduleWorkDisposition {
    require(runAttemptCount >= 0)
    // Unknown local authority must remain durable even after many failures.
    // WorkManager caps exponential delay, while success or a definitive
    // restored no-owner result completes this unique job.
    return if (attempt == AlarmRescheduleAttemptResult.COMPLETE) {
        AlarmRescheduleWorkDisposition.SUCCESS
    } else {
        AlarmRescheduleWorkDisposition.RETRY
    }
}

// The API-31 action constant is a compile-time String. Comparing it on older
// Android versions does not invoke an unavailable platform API.
@SuppressLint("InlinedApi")
internal fun isSupportedAlarmRescheduleAction(action: String?): Boolean = action in setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
)

@SuppressLint("InlinedApi")
private fun alarmRescheduleActionKey(action: String): String = when (action) {
    Intent.ACTION_BOOT_COMPLETED -> "boot"
    Intent.ACTION_MY_PACKAGE_REPLACED -> "package-replaced"
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> "exact-alarm-access"
    else -> error("Unsupported alarm reschedule action")
}

internal object AlarmRescheduleRetryScheduler {
    private const val WORK_PREFIX = "dcompany-local-alarm-reschedule"

    fun enqueue(context: Context, action: String) {
        if (!isSupportedAlarmRescheduleAction(action)) return
        val workName = "$WORK_PREFIX-${alarmRescheduleActionKey(action)}"
        val request = OneTimeWorkRequestBuilder<AlarmRescheduleWorker>()
            // No network constraint: this retries only encrypted local stores
            // and the exact cache marker already committed by this install.
            .setInputData(Data.Builder().putString(INPUT_ACTION, action).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                ALARM_RESCHEDULE_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(WORK_PREFIX)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    internal const val INPUT_ACTION = "system_reschedule_action"
}

/** Durable local retry with WorkManager-capped exponential backoff. */
class AlarmRescheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val action = inputData.getString(AlarmRescheduleRetryScheduler.INPUT_ACTION)
            ?.takeIf(::isSupportedAlarmRescheduleAction)
            ?: return Result.failure()
        val app = applicationContext as? DCompanyApp ?: return Result.failure()
        val attempt = runAlarmRescheduleAttempt(
            restorePersistedAuthority = {
                // Restart a completed timeout/storage failure on the same
                // process-owned loaders; no user, branch, or till is selected.
                app.awaitPersistedStartupState(retryFailed = true)
            },
            ensureActiveOwnedScope = {
                OperationalAlarmRuntime.ensureActiveOwnedScope(applicationContext)
            },
            cancelAll = {
                check(OperationalAlarmRegistry.cancelAll(applicationContext)) {
                    "The stale alarm ledger could not be cleared durably"
                }
            },
            prepareForSystemReschedule = {
                OperationalAlarmRegistry.prepareForSystemReschedule(applicationContext, action)
            },
            reconcileGaming = { GamingAlarmReconciler.reconcile(applicationContext) },
            reconcileHeldOrders = { HeldOrderAlarmReconciler.reconcile(applicationContext) },
            requestLatestScopeReconciliation = {
                app.requestOperationalAlarmReconciliation()
            },
        )
        return when (alarmRescheduleWorkDisposition(attempt, runAttemptCount)) {
            AlarmRescheduleWorkDisposition.SUCCESS -> Result.success()
            AlarmRescheduleWorkDisposition.RETRY -> Result.retry()
        }
    }
}

private const val ALARM_RESCHEDULE_BACKOFF_SECONDS = 10L
