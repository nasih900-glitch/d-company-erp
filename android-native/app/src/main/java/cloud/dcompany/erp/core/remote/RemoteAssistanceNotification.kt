package cloud.dcompany.erp.core.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.MainActivity
import cloud.dcompany.erp.R

internal class RemoteAssistanceNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Remote assistance",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Visible while an owner can view the D Company ERP app window."
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    fun canShowPersistentIndicator(): Boolean {
        if (manager?.areNotificationsEnabled() != true) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Capability alone is insufficient once capture starts; the exact ongoing indicator must exist. */
    fun isPersistentIndicatorPosted(): Boolean = canShowPersistentIndicator() && runCatching {
        manager?.activeNotifications?.any { it.id == NOTIFICATION_ID } == true
    }.getOrDefault(false)

    fun show(timeoutMillis: Long): Boolean {
        if (!canShowPersistentIndicator()) return false
        val notificationManager = manager ?: return false
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RemoteAssistanceStopReceiver::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dcompany_alarm)
            .setContentTitle("D Company ERP support is active")
            .setContentText("The owner can view this ERP app only. Tap Stop at any time.")
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_dcompany_alarm),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setTimeoutAfter(timeoutMillis.coerceIn(1_000L, REMOTE_SESSION_MAX_MILLIS))
            .build()
        return runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
            isPersistentIndicatorPosted()
        }.getOrDefault(false).also { visible ->
            if (!visible) notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    fun cancel() {
        manager?.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = REMOTE_ASSISTANCE_NOTIFICATION_CHANNEL_ID
        const val NOTIFICATION_ID = 44_901
        const val ACTION_STOP = "cloud.dcompany.erp.remote_assistance.STOP"
    }

    internal fun isStopAction(action: String?): Boolean = action == ACTION_STOP
}

internal const val REMOTE_ASSISTANCE_NOTIFICATION_CHANNEL_ID =
    "dcompany_remote_assistance_v1"

class RemoteAssistanceStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DCompanyApp ?: return
        if (!RemoteAssistanceNotification(context).isStopAction(intent.action)) return
        app.remoteAssistance.stopByUser()
    }
}
