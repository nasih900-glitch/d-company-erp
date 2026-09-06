package cloud.dcompany.erp.ui.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cloud.dcompany.erp.core.alarm.NotificationPermissionRequestStore
import cloud.dcompany.erp.core.alarm.NotificationPermissionRoute
import cloud.dcompany.erp.core.alarm.NotificationPermissionStatus
import cloud.dcompany.erp.core.alarm.notificationPermissionRoute
import cloud.dcompany.erp.core.remote.REMOTE_ASSISTANCE_NOTIFICATION_CHANNEL_ID

/**
 * Returns the explicit remediation action shown beside the fail-closed warning.
 * It reuses the app's recorded Android notification-permission history so a
 * fixed denial opens Settings instead of launching an invisible retry.
 */
@Composable
@SuppressLint("InlinedApi") // Permission access is routed away below API 33.
internal fun rememberRemoteNotificationAccessAction(
    notificationReady: Boolean,
    onStatusChanged: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val activity = context.findActivity()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onStatusChanged()
    }

    return {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val status = NotificationPermissionStatus(
            runtimeGranted = runtimeGranted,
            notificationsEnabled = notificationReady,
            requestedBefore = NotificationPermissionRequestStore.wasRequested(context),
        )
        val shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } == true

        when (
            notificationPermissionRoute(
                sdkInt = Build.VERSION.SDK_INT,
                status = status,
                shouldShowRationale = shouldShowRationale,
            )
        ) {
            NotificationPermissionRoute.REQUEST_SYSTEM_DIALOG -> {
                NotificationPermissionRequestStore.markRequested(context)
                runCatching {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }.onFailure {
                    showNotificationSettingsUnavailable(context)
                }
            }
            NotificationPermissionRoute.OPEN_APP_SETTINGS -> {
                val launched = remoteNotificationSettingsIntents(context.packageName).any { intent ->
                    runCatching { context.startActivity(intent) }.isSuccess
                }
                if (!launched) showNotificationSettingsUnavailable(context)
            }
            NotificationPermissionRoute.NONE -> onStatusChanged()
        }
    }
}

internal fun remoteNotificationSettingsIntents(packageName: String): List<Intent> = buildList {
    // minSdk is 26, so both notification Settings intents are always defined.
    add(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(
                Settings.EXTRA_CHANNEL_ID,
                REMOTE_ASSISTANCE_NOTIFICATION_CHANNEL_ID,
            ),
    )
    add(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
    add(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ),
    )
}

private fun showNotificationSettingsUnavailable(context: Context) {
    Toast.makeText(
        context,
        "Open Settings > Apps > D Company ERP > Notifications and enable Remote assistance.",
        Toast.LENGTH_LONG,
    ).show()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
