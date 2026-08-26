package cloud.dcompany.erp.core.alarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cloud.dcompany.erp.DCompanyApp

internal data class NotificationPermissionStatus(
    val runtimeGranted: Boolean,
    val notificationsEnabled: Boolean,
    val requestedBefore: Boolean,
) {
    val allowed: Boolean get() = runtimeGranted && notificationsEnabled
}

internal enum class NotificationPermissionRoute {
    NONE,
    REQUEST_SYSTEM_DIALOG,
    OPEN_APP_SETTINGS,
}

/**
 * Decides whether Android can still show a notification permission dialog.
 *
 * `shouldShowRationale` alone is ambiguous: it is false both before the first
 * request and after Android marks a denial as fixed. Recording whether this
 * app has already launched the dialog distinguishes a first request from a
 * prior no-rationale result, so that result routes to Settings instead of
 * launching a contract that can immediately return with no visible feedback.
 */
internal fun notificationPermissionRoute(
    sdkInt: Int,
    status: NotificationPermissionStatus,
    shouldShowRationale: Boolean,
): NotificationPermissionRoute {
    if (status.allowed) return NotificationPermissionRoute.NONE
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
        return NotificationPermissionRoute.OPEN_APP_SETTINGS
    }
    if (status.runtimeGranted) {
        return NotificationPermissionRoute.OPEN_APP_SETTINGS
    }
    if (shouldShowRationale) return NotificationPermissionRoute.REQUEST_SYSTEM_DIALOG
    return if (status.requestedBefore) {
        NotificationPermissionRoute.OPEN_APP_SETTINGS
    } else {
        NotificationPermissionRoute.REQUEST_SYSTEM_DIALOG
    }
}

internal fun readNotificationPermissionStatus(context: Context): NotificationPermissionStatus {
    val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(NotificationManager::class.java)
    val alarmChannelEnabled = manager?.getNotificationChannel(DCompanyApp.ALARM_CHANNEL_ID)
        ?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true
    return NotificationPermissionStatus(
        runtimeGranted = runtimeGranted,
        notificationsEnabled =
            NotificationManagerCompat.from(context).areNotificationsEnabled() && alarmChannelEnabled,
        requestedBefore = NotificationPermissionRequestStore.wasRequested(context),
    )
}

/** Non-sensitive process-independent history needed to interpret Android's rationale signal. */
internal object NotificationPermissionRequestStore {
    private const val PREFERENCES = "dcompany_alarm_permissions"
    private const val POST_NOTIFICATIONS_REQUESTED = "post_notifications_requested"

    fun wasRequested(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(POST_NOTIFICATIONS_REQUESTED, false)

    fun markRequested(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(POST_NOTIFICATIONS_REQUESTED, true)
            .apply()
    }
}
