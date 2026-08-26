package cloud.dcompany.erp.core.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.MainActivity
import cloud.dcompany.erp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal enum class OperationalAlarmKind(val storageKey: String) {
    GAMING("gaming_entries_v2"),
    HELD_ORDER("held_order_entries_v1"),
}

internal data class OperationalAlarmSpec(
    val kind: OperationalAlarmKind,
    val tag: String,
    val triggerAtMillis: Long,
    val title: String,
    val body: String,
    val target: OperationalNotificationTarget,
) {
    val fingerprint: String get() = "$tag|$triggerAtMillis"
}

internal data class OperationalAlarmPlan(
    val cancelTags: Set<String>,
    val schedule: List<OperationalAlarmSpec>,
    val deliveredAfterCleanup: Set<String>,
)

internal enum class AlarmScheduleMode {
    EXACT_ALLOW_IDLE,
    EXACT_ALLOW_IDLE_WITH_INEXACT_BACKUP,
    INEXACT_ALLOW_IDLE,
    UNAVAILABLE,
}

/** A reboot removes notifications and AlarmManager state, so unresolved work may alert again. */
internal fun deliveredFingerprintsAfterSystemReschedule(
    action: String?,
    deliveredFingerprints: Set<String>,
): Set<String> = if (action == Intent.ACTION_BOOT_COMPLETED) emptySet() else deliveredFingerprints

/** Pure desired-set planner used by the SharedPreferences/AlarmManager adapter. */
internal fun planOperationalAlarmReconciliation(
    previousFingerprints: Set<String>,
    deliveredFingerprints: Set<String>,
    desired: List<OperationalAlarmSpec>,
): OperationalAlarmPlan {
    val previous = decodeAlarmEntries(previousFingerprints)
    val next = desired.distinctBy(OperationalAlarmSpec::tag).associateBy(OperationalAlarmSpec::tag)
    val cancel = previous.mapNotNullTo(mutableSetOf()) { (tag, oldDeadline) ->
        val replacement = next[tag]
        tag.takeIf { replacement == null || replacement.triggerAtMillis != oldDeadline }
    }
    val delivered = deliveredFingerprints
        .filterNotTo(mutableSetOf()) { fingerprint ->
            cancel.any { tag -> fingerprint.startsWith("$tag|") }
        }
    return OperationalAlarmPlan(
        cancelTags = cancel,
        schedule = next.values.filter { it.fingerprint !in delivered },
        deliveredAfterCleanup = delivered,
    )
}

private fun decodeAlarmEntries(entries: Set<String>): Map<String, Long> = entries.mapNotNull { raw ->
    val split = raw.lastIndexOf('|')
    if (split <= 0 || split == raw.lastIndex) return@mapNotNull null
    val deadline = raw.substring(split + 1).toLongOrNull() ?: return@mapNotNull null
    raw.substring(0, split) to deadline
}.toMap()

internal data class ScheduledAlarmIdentity(
    val kind: OperationalAlarmKind,
    val tag: String,
    val triggerAtMillis: Long,
    val target: OperationalNotificationTarget,
)

internal enum class DeliveryRecordResult {
    RECORDED,
    ALREADY_RECORDED,
    STALE,
    PERSISTENCE_FAILED,
}

/**
 * AlarmManager cannot enumerate alarms, so this small registry is the exact
 * desired-set ledger. The deadline is part of the identity: changing a timer
 * re-arms one alert, while an already delivered deadline is never rung again.
 */
@SuppressLint("ApplySharedPref") // The ledger must survive immediate receiver/process death.
internal object OperationalAlarmRegistry {
    private const val PREFERENCES = "dcompany_alarm_schedule"
    private const val DELIVERED = "delivered_fingerprints_v2"
    private const val LEGACY_GAMING_TAGS = "gaming_tags"
    private val lock = Any()

    fun reconcile(
        context: Context,
        kind: OperationalAlarmKind,
        desired: List<OperationalAlarmSpec>,
    ) = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previousFingerprints =
            preferences.getStringSet(kind.storageKey, emptySet()).orEmpty()
        val plan = planOperationalAlarmReconciliation(
            previousFingerprints = previousFingerprints,
            deliveredFingerprints = preferences.getStringSet(DELIVERED, emptySet()).orEmpty(),
            desired = desired,
        )

        if (kind == OperationalAlarmKind.GAMING) {
            // Pre-v2 PendingIntents had no stable data URI. Cancel them once
            // during upgrade so they cannot later emit a bare/stale alert.
            preferences.getStringSet(LEGACY_GAMING_TAGS, emptySet()).orEmpty().forEach { tag ->
                AlarmScheduler.cancelLegacy(appContext, tag)
            }
        }

        plan.cancelTags.forEach { tag ->
            AlarmScheduler.cancel(appContext, tag)
            AlarmScheduler.cancelNotification(appContext, tag)
        }

        plan.schedule.forEach { alarm -> AlarmScheduler.schedule(appContext, alarm) }

        preferences.edit()
            .putStringSet(kind.storageKey, desired.mapTo(mutableSetOf()) { it.fingerprint })
            .putStringSet(DELIVERED, plan.deliveredAfterCleanup)
            .remove(LEGACY_GAMING_TAGS)
            .commit()
    }

    fun shouldAttemptDelivery(
        context: Context,
        alarm: OperationalAlarmSpec,
    ): Boolean = synchronized(lock) {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val current = preferences.getStringSet(alarm.kind.storageKey, emptySet()).orEmpty()
        if (alarm.fingerprint !in current) return false
        alarm.fingerprint !in preferences.getStringSet(DELIVERED, emptySet()).orEmpty()
    }

    /**
     * Record after NotificationManager accepts the post. This deliberately
     * gives at-least-once delivery across process death; duplicate receivers
     * replace the same tag/id and `setOnlyAlertOnce` suppresses a second sound.
     */
    fun recordDeliveryAfterPost(
        context: Context,
        alarm: OperationalAlarmSpec,
    ): DeliveryRecordResult = synchronized(lock) {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val current = preferences.getStringSet(alarm.kind.storageKey, emptySet()).orEmpty()
        if (alarm.fingerprint !in current) return DeliveryRecordResult.STALE
        val delivered = preferences.getStringSet(DELIVERED, emptySet()).orEmpty().toMutableSet()
        if (!delivered.add(alarm.fingerprint)) return DeliveryRecordResult.ALREADY_RECORDED
        if (preferences.edit().putStringSet(DELIVERED, delivered).commit()) {
            DeliveryRecordResult.RECORDED
        } else {
            DeliveryRecordResult.PERSISTENCE_FAILED
        }
    }

    /**
     * Android clears scheduled alarms and visible notifications on reboot.
     * Forget only delivery memory then; package replacement and permission
     * changes must not ring an unchanged, already-visible alert twice.
     */
    fun prepareForSystemReschedule(context: Context, action: String?) = synchronized(lock) {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val before = preferences.getStringSet(DELIVERED, emptySet()).orEmpty()
        val after = deliveredFingerprintsAfterSystemReschedule(action, before)
        if (after != before) preferences.edit().putStringSet(DELIVERED, after).commit()
    }

    /** Sign-out/blocked scope cleanup that does not need to read Room. */
    fun cancelAll(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        OperationalAlarmKind.entries
            .flatMap { kind ->
                decodeAlarmEntries(
                    preferences.getStringSet(kind.storageKey, emptySet()).orEmpty(),
                ).keys
            }
            .toSet()
            .forEach { tag ->
                AlarmScheduler.cancel(appContext, tag)
                AlarmScheduler.cancelNotification(appContext, tag)
            }
        preferences.getStringSet(LEGACY_GAMING_TAGS, emptySet()).orEmpty().forEach { tag ->
            AlarmScheduler.cancelLegacy(appContext, tag)
        }
        preferences.edit()
            .remove(OperationalAlarmKind.GAMING.storageKey)
            .remove(OperationalAlarmKind.HELD_ORDER.storageKey)
            .remove(DELIVERED)
            .remove(LEGACY_GAMING_TAGS)
            .commit()
    }
}

object AlarmScheduler {
    private const val ACTION_DELIVER_ALARM = "cloud.dcompany.erp.action.DELIVER_ALARM"
    private const val RECEIVER_RETRY_DELAY_MILLIS = 60_000L
    private const val PRIMARY_AUTHORITY = "alarm"
    private const val BACKUP_AUTHORITY = "alarm-backup"

    internal fun schedule(context: Context, alarm: OperationalAlarmSpec): AlarmScheduleMode {
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return AlarmScheduleMode.UNAVAILABLE
        // Cancel the pre-v2 no-data identity as part of every ordinary
        // reschedule; package replacement can otherwise leave it behind.
        cancelLegacy(context, alarm.tag)
        val identity = ScheduledAlarmIdentity(
            kind = alarm.kind,
            tag = alarm.tag,
            triggerAtMillis = alarm.triggerAtMillis,
            target = alarm.target,
        )
        return schedulePending(
            manager = manager,
            primary = alarmPendingIntent(context, identity, PRIMARY_AUTHORITY),
            inexactBackup = alarmPendingIntent(context, identity, BACKUP_AUTHORITY),
            atMillis = alarm.triggerAtMillis,
        )
    }

    /** A transient Room/notification-service failure must not consume the one OS delivery. */
    internal fun retryAfterReceiverFailure(
        context: Context,
        identity: ScheduledAlarmIdentity,
        nowMillis: Long = System.currentTimeMillis(),
    ): AlarmScheduleMode {
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return AlarmScheduleMode.UNAVAILABLE
        return schedulePending(
            manager = manager,
            primary = alarmPendingIntent(context, identity, PRIMARY_AUTHORITY),
            inexactBackup = alarmPendingIntent(context, identity, BACKUP_AUTHORITY),
            atMillis = nowMillis + RECEIVER_RETRY_DELAY_MILLIS,
        )
    }

    private fun schedulePending(
        manager: AlarmManager,
        primary: PendingIntent,
        inexactBackup: PendingIntent,
        atMillis: Long,
    ): AlarmScheduleMode {
        val permissionCanBeRevoked = exactAlarmPermissionCanBeRevoked()
        val exactAllowed = !permissionCanBeRevoked ||
            manager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, primary)
                if (permissionCanBeRevoked) {
                    // Revoking SCHEDULE_EXACT_ALARM kills this process and
                    // removes exact alarms. A distinct inexact delivery stays
                    // armed, so the reminder degrades to late instead of lost.
                    manager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        atMillis,
                        inexactBackup,
                    )
                    return AlarmScheduleMode.EXACT_ALLOW_IDLE_WITH_INEXACT_BACKUP
                }
                return AlarmScheduleMode.EXACT_ALLOW_IDLE
            } catch (_: SecurityException) {
                // Special access can change between the capability check and
                // the binder call. Preserve the reminder as an inexact alarm.
            }
        }
        manager.cancel(inexactBackup)
        inexactBackup.cancel()
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, primary)
        return AlarmScheduleMode.INEXACT_ALLOW_IDLE
    }

    private fun exactAlarmPermissionCanBeRevoked(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    internal fun cancel(context: Context, tag: String) {
        listOf(PRIMARY_AUTHORITY, BACKUP_AUTHORITY).forEach { authority ->
            val pending = alarmPendingIntent(context, tag, authority)
            context.getSystemService(AlarmManager::class.java)?.cancel(pending)
            pending.cancel()
        }
        cancelLegacy(context, tag)
    }

    internal fun cancelLegacy(context: Context, tag: String) {
        val pending = PendingIntent.getBroadcast(
            context,
            tag.hashCode(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    internal fun cancelNotification(context: Context, tag: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tag, 0)
    }

    internal fun hasInexactBackupIdentity(context: Context, tag: String): Boolean =
        PendingIntent.getBroadcast(
            context,
            0,
            alarmIdentityIntent(context, tag, BACKUP_AUTHORITY),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

    private fun alarmPendingIntent(
        context: Context,
        identity: ScheduledAlarmIdentity,
        authority: String,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_DELIVER_ALARM
            data = Uri.Builder()
                .scheme("dcompany")
                .authority(authority)
                .appendPath(identity.tag)
                .build()
            putExtra(EXTRA_KIND, identity.kind.name)
            putExtra(EXTRA_TAG, identity.tag)
            putExtra(EXTRA_TRIGGER_AT, identity.triggerAtMillis)
            OperationalNotificationIntents.putTarget(this, identity.target)
            // putTarget installs the activity action; restore this private
            // broadcast action after copying the target extras.
            action = ACTION_DELIVER_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmPendingIntent(context: Context, tag: String, authority: String): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            0,
            alarmIdentityIntent(context, tag, authority),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmIdentityIntent(context: Context, tag: String, authority: String) =
        Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_DELIVER_ALARM
            data = Uri.Builder()
                .scheme("dcompany")
                .authority(authority)
                .appendPath(tag)
                .build()
        }

    private fun readIdentity(intent: Intent): ScheduledAlarmIdentity? {
        if (intent.action != ACTION_DELIVER_ALARM) return null
        if (intent.data?.authority !in setOf(PRIMARY_AUTHORITY, BACKUP_AUTHORITY)) return null
        val kind = intent.getStringExtra(EXTRA_KIND)?.let {
            runCatching { OperationalAlarmKind.valueOf(it) }.getOrNull()
        } ?: return null
        val tag = intent.getStringExtra(EXTRA_TAG)?.takeIf(String::isNotBlank) ?: return null
        if (intent.data?.lastPathSegment != tag) return null
        val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, Long.MIN_VALUE)
            .takeIf { it > 0L } ?: return null
        val targetIntent = Intent().apply {
            action = OperationalNotificationIntents.ACTION_OPEN_TARGET
            putExtras(intent)
        }
        val target = OperationalNotificationIntents.readTarget(targetIntent) ?: return null
        return ScheduledAlarmIdentity(kind, tag, triggerAt, target)
    }

    internal fun identity(intent: Intent): ScheduledAlarmIdentity? = readIdentity(intent)

    private const val EXTRA_KIND = "alarm_kind"
    private const val EXTRA_TAG = "alarm_tag"
    private const val EXTRA_TRIGGER_AT = "alarm_trigger_at"
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val identity = AlarmScheduler.identity(intent) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!OperationalAlarmRuntime.ensureActiveOwnedScope(context)) {
                    OperationalAlarmRegistry.cancelAll(context)
                    return@launch
                }
                val now = System.currentTimeMillis()
                val current = when (identity.kind) {
                    OperationalAlarmKind.GAMING -> GamingAlarmReconciler.currentAlarms(context)
                    OperationalAlarmKind.HELD_ORDER -> HeldOrderAlarmReconciler.currentAlarms(context)
                }.firstOrNull {
                    it.tag == identity.tag &&
                        it.triggerAtMillis == identity.triggerAtMillis &&
                        it.target == identity.target
                }
                if (current == null) {
                    // A race can resolve work after AlarmManager launches the
                    // receiver but before Room is checked. Reconciliation does
                    // exact alarm and visible-notification cleanup.
                    when (identity.kind) {
                        OperationalAlarmKind.GAMING -> GamingAlarmReconciler.reconcile(context)
                        OperationalAlarmKind.HELD_ORDER -> HeldOrderAlarmReconciler.reconcile(context)
                    }
                } else if (now < current.triggerAtMillis) {
                    // Wall-clock correction or an early OEM delivery must not
                    // consume the only reminder.
                    AlarmScheduler.schedule(context, current)
                } else if (
                    readNotificationPermissionStatus(context).allowed &&
                    OperationalAlarmRegistry.shouldAttemptDelivery(context, current)
                ) {
                    if (!OperationalAlarmNotifier.post(context.applicationContext, current)) {
                        AlarmScheduler.retryAfterReceiverFailure(context, identity, now)
                    } else {
                        when (OperationalAlarmRegistry.recordDeliveryAfterPost(context, current)) {
                            DeliveryRecordResult.RECORDED,
                            DeliveryRecordResult.ALREADY_RECORDED,
                            -> AlarmScheduler.cancel(context, current.tag)
                            DeliveryRecordResult.STALE -> {
                                AlarmScheduler.cancel(context, current.tag)
                                AlarmScheduler.cancelNotification(context, current.tag)
                            }
                            DeliveryRecordResult.PERSISTENCE_FAILED ->
                                AlarmScheduler.retryAfterReceiverFailure(context, identity, now)
                        }
                    }
                }
            } catch (_: Exception) {
                // Receiver work is local and normally completes in milliseconds.
                // A transient Room/system-service failure gets one bounded retry
                // rather than crashing the process or silently losing the alert.
                runCatching { AlarmScheduler.retryAfterReceiverFailure(context, identity) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal object OperationalAlarmNotifier {
    fun post(context: Context, alarm: OperationalAlarmSpec): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data = Uri.Builder()
                .scheme("dcompany")
                .authority("notification")
                .appendPath(alarm.kind.name.lowercase())
                .appendPath(alarm.target.primaryId)
                .build()
        }
        DCompanyApp.instance.notificationRoutes.authorizeOpenIntent(openIntent, alarm.target)
        val open = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = buildOperationalAlarmNotification(context, alarm, open)
        return runCatching {
            manager.notify(alarm.tag, 0, notification)
            true
        }.getOrDefault(false)
    }
}

/** Kept separate from NotificationManager so lock-screen privacy is device-testable. */
internal fun buildOperationalAlarmNotification(
    context: Context,
    alarm: OperationalAlarmSpec,
    open: PendingIntent,
): Notification {
    val publicNotification = NotificationCompat.Builder(context, DCompanyApp.ALARM_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_dcompany_alarm)
        .setContentTitle("D Company ERP alert")
        .setContentText("Unlock the tablet to review an operational reminder.")
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    return NotificationCompat.Builder(context, DCompanyApp.ALARM_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_dcompany_alarm)
        .setContentTitle(alarm.title)
        .setContentText(alarm.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(alarm.body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicNotification)
        .setContentIntent(open)
        .build()
}

/** Rebuilds AlarmManager after reboot, app replacement, or exact-alarm access changes. */
class AlarmRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!OperationalAlarmRuntime.ensureActiveOwnedScope(context)) {
                    OperationalAlarmRegistry.cancelAll(context)
                    return@launch
                }
                OperationalAlarmRegistry.prepareForSystemReschedule(context, intent.action)
                GamingAlarmReconciler.reconcile(context)
                HeldOrderAlarmReconciler.reconcile(context)
            } catch (_: Exception) {
                // Application startup and every Room change also reconcile;
                // never crash a boot/package receiver on a local I/O failure.
            } finally {
                pendingResult.finish()
            }
        }
    }
}
