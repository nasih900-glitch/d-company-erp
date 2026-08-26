package cloud.dcompany.erp.ui.screens.gaming

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cloud.dcompany.erp.core.alarm.GamingAlarmReconciler
import cloud.dcompany.erp.core.alarm.HeldOrderAlarmReconciler
import cloud.dcompany.erp.core.alarm.NotificationPermissionRequestStore
import cloud.dcompany.erp.core.alarm.NotificationPermissionRoute
import cloud.dcompany.erp.core.alarm.NotificationPermissionStatus
import cloud.dcompany.erp.core.alarm.notificationPermissionRoute
import cloud.dcompany.erp.core.alarm.readNotificationPermissionStatus
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import kotlinx.coroutines.launch

private data class GamingAlarmPermissionState(
    val notificationStatus: NotificationPermissionStatus,
    val exactAlarmsAllowed: Boolean,
) {
    val notificationsAllowed: Boolean get() = notificationStatus.allowed
    val ready: Boolean get() = notificationsAllowed && exactAlarmsAllowed
}

private enum class AlarmSettingsTarget {
    NOTIFICATIONS,
    EXACT_ALARMS,
}

/**
 * Android 13+ can suppress notifications and fresh Android 14+ installs do
 * not normally receive exact-alarm access automatically. A cafe timer must
 * surface that degraded state before staff trust a screen-off deadline.
 */
@Composable
@SuppressLint("InlinedApi") // Every POST_NOTIFICATIONS operation is routed away below API 33.
internal fun GamingAlarmPermissionCard() {
    OperationalAlarmPermissionCard(contextLabel = "Gaming timer")
}

@Composable
@SuppressLint("InlinedApi")
internal fun OperationalAlarmPermissionCard(contextLabel: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }

    fun readState(): GamingAlarmPermissionState {
        val manager = context.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager?.canScheduleExactAlarms() == true
        return GamingAlarmPermissionState(
            notificationStatus = readNotificationPermissionStatus(context),
            exactAlarmsAllowed = exactAllowed,
        )
    }

    var permissionState by remember { mutableStateOf(readState()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var pendingSettings by remember { mutableStateOf<AlarmSettingsTarget?>(null) }

    fun refreshAndReconcile(): GamingAlarmPermissionState {
        val updated = readState()
        permissionState = updated
        scope.launch {
            GamingAlarmReconciler.reconcile(context)
            HeldOrderAlarmReconciler.reconcile(context)
        }
        return updated
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val updated = refreshAndReconcile()
        feedback = when {
            granted && updated.notificationsAllowed -> null
            updated.notificationStatus.requestedBefore &&
                activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        it,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                } != true ->
                "Android did not grant notifications and no retry prompt is available here. " +
                    "Open notification settings and turn on D Company ERP alerts."
            activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } == true ->
                "Notifications are still off. Tap Try notifications again, then choose Allow."
            else ->
                "Notifications are still off. Open notification settings and turn on " +
                    "D Company ERP alerts."
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val target = pendingSettings
        pendingSettings = null
        val updated = refreshAndReconcile()
        feedback = when (target) {
            AlarmSettingsTarget.NOTIFICATIONS -> if (updated.notificationsAllowed) {
                null
            } else {
                "Notifications are still off. In Android Settings, turn on Allow notifications " +
                    "for D Company ERP."
            }
            AlarmSettingsTarget.EXACT_ALARMS -> if (updated.exactAlarmsAllowed) {
                null
            } else {
                "Precise alarms are still off. In Android Settings, enable Alarms & reminders " +
                    "for D Company ERP. Timer billing remains accurate, but screen-off alerts " +
                    "may arrive late."
            }
            null -> feedback
        }
    }

    fun launchSettings(
        target: AlarmSettingsTarget,
        candidates: List<Intent>,
        unavailableMessage: String,
    ) {
        pendingSettings = target
        feedback = null
        var launched = false
        // Do not preflight with resolveActivity(): Android 11 package visibility
        // can hide a valid system Settings activity. Launch each safe fallback
        // and handle the synchronous ActivityNotFoundException instead.
        for (intent in candidates) {
            if (runCatching { settingsLauncher.launch(intent) }.isSuccess) {
                launched = true
                break
            }
        }
        if (!launched) {
            pendingSettings = null
            feedback = unavailableMessage
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAndReconcile()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (permissionState.ready) return

    val shouldShowNotificationRationale = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(
            it,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } == true
    val notificationRoute = notificationPermissionRoute(
        sdkInt = Build.VERSION.SDK_INT,
        status = permissionState.notificationStatus,
        shouldShowRationale = shouldShowNotificationRationale,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .background(Brand.Danger, Radius.shapeSm)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$contextLabel alerts need attention",
            color = Brand.Background,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Without notifications and Alarms & reminders access, Android may hide or delay " +
                "a session-over alert while the screen is off. Billing still uses server time.",
            color = Brand.Background,
            style = MaterialTheme.typography.labelSmall,
        )
        feedback?.let { message ->
            Text(
                message,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = Brand.Background,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (notificationRoute) {
                NotificationPermissionRoute.REQUEST_SYSTEM_DIALOG -> {
                    Button(
                        onClick = {
                            feedback = null
                            NotificationPermissionRequestStore.markRequested(context)
                            runCatching {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }.onFailure {
                                feedback = "Android could not show the notification prompt. Open " +
                                    "notification settings and turn on D Company ERP alerts."
                            }
                        },
                    ) {
                        Text(
                            if (shouldShowNotificationRationale) {
                                "Try notifications again"
                            } else {
                                "Allow notifications"
                            },
                        )
                    }
                }
                NotificationPermissionRoute.OPEN_APP_SETTINGS -> {
                    Button(
                        onClick = {
                            launchSettings(
                                target = AlarmSettingsTarget.NOTIFICATIONS,
                                candidates = listOf(
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .putExtra(
                                            Settings.EXTRA_CHANNEL_ID,
                                            cloud.dcompany.erp.DCompanyApp.ALARM_CHANNEL_ID,
                                        ),
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                                        Settings.EXTRA_APP_PACKAGE,
                                        context.packageName,
                                    ),
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                ),
                                unavailableMessage = "Android could not open notification settings. " +
                                    "Open Settings > Apps > D Company ERP > Notifications.",
                            )
                        },
                    ) {
                        Text("Open notification settings")
                    }
                }
                NotificationPermissionRoute.NONE -> Unit
            }
            if (!permissionState.exactAlarmsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Button(
                    onClick = {
                        launchSettings(
                            target = AlarmSettingsTarget.EXACT_ALARMS,
                            candidates = listOf(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            ),
                            unavailableMessage = "Android could not open precise-alarm settings. " +
                                "Open Settings > Apps > Special app access > Alarms & reminders, " +
                                "then enable D Company ERP.",
                        )
                    },
                ) {
                    Text("Allow precise alarms")
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
