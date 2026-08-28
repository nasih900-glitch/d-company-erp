package cloud.dcompany.erp.core.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmLifecycleDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bootReceiverAndRequiredPermissionsAreInstalledSafely() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.SCHEDULE_EXACT_ALARM in permissions)

        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, AlarmRescheduleReceiver::class.java),
            0,
        )
        assertTrue(receiver.enabled)
        assertFalse(receiver.exported)
    }

    @Test
    fun notificationHidesOperationalDetailsOnSecureLockScreen() {
        val open = PendingIntent.getActivity(
            context,
            9127,
            Intent(context, MainActivity::class.java).setAction("alarm-device-test"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            val notification = buildOperationalAlarmNotification(context, alarm(), open)

            assertEquals(Notification.CATEGORY_ALARM, notification.category)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertNotNull(notification.publicVersion)
            val publicVersion = requireNotNull(notification.publicVersion)
            assertEquals(Notification.VISIBILITY_PUBLIC, publicVersion.visibility)
            assertEquals(
                "D Company ERP alert",
                publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )
            assertFalse(
                publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?.contains("Table 4") == true,
            )
        } finally {
            open.cancel()
        }
    }

    @Test
    fun deniedNotificationPermissionIsReportedAsUnavailable() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED,
        )

        val status = readNotificationPermissionStatus(context)
        assertFalse(status.runtimeGranted)
        assertFalse(status.allowed)
    }

    @Test
    fun grantedNotificationCanBePostedWithPrivateVisibilityAndCancelled() {
        assumeTrue(readNotificationPermissionStatus(context).allowed)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val alarm = alarm()
        try {
            assertTrue(OperationalAlarmNotifier.post(context, alarm))
            val posted = manager.activeNotifications.firstOrNull { it.tag == alarm.tag }
            assertNotNull(posted)
            assertEquals(Notification.VISIBILITY_PRIVATE, posted?.notification?.visibility)
        } finally {
            manager.cancel(alarm.tag, 0)
            (context.applicationContext as DCompanyApp).notificationRoutes.clearAllForScopeChange()
        }
    }

    @Test
    fun schedulerUsesDozeCapablePathWithPermissionAwareFallback() {
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarm = alarm(triggerAtMillis = System.currentTimeMillis() + 60L * 60L * 1_000L)
        try {
            val result = AlarmScheduler.schedule(context, alarm)
            assertNotEquals(AlarmScheduleMode.UNAVAILABLE, result)
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager?.canScheduleExactAlarms() == true
            assertEquals(
                if (exactAllowed) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AlarmScheduleMode.EXACT_ALLOW_IDLE_WITH_INEXACT_BACKUP
                    } else {
                        AlarmScheduleMode.EXACT_ALLOW_IDLE
                    }
                } else {
                    AlarmScheduleMode.INEXACT_ALLOW_IDLE
                },
                result,
            )
            if (exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertTrue(AlarmScheduler.hasInexactBackupIdentity(context, alarm.tag))
            } else {
                assertFalse(AlarmScheduler.hasInexactBackupIdentity(context, alarm.tag))
            }
        } finally {
            AlarmScheduler.cancel(context, alarm.tag)
        }
    }

    @Test
    fun exactAlarmReachesFailClosedReceiverDuringEmulatedDeepIdle() {
        val manager = context.getSystemService(AlarmManager::class.java)
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager?.canScheduleExactAlarms() == true)
        assumeFalse((context.applicationContext as DCompanyApp).tokens.hasSession())
        OperationalAlarmRegistry.cancelAll(context)
        val preferences = context.getSharedPreferences(
            "dcompany_alarm_schedule",
            Context.MODE_PRIVATE,
        )

        try {
            shell("dumpsys battery unplug")
            val idleResult = shell("dumpsys deviceidle force-idle deep")
            assertTrue(
                "Could not force the emulator into deep idle: ${idleResult.trim()}",
                idleResult.contains("forced", ignoreCase = true),
            )
            assertEquals("IDLE", shell("dumpsys deviceidle get deep").trim())

            // Enter idle before scheduling the near-term wakeup. DeviceIdleController
            // intentionally refuses to enter deep idle when a wake-from-idle alarm is
            // already inside its minimum-time-to-alarm window.
            val alarm = alarm(triggerAtMillis = System.currentTimeMillis() + 12_000L)
            OperationalAlarmRegistry.reconcile(
                context = context,
                kind = OperationalAlarmKind.HELD_ORDER,
                desired = listOf(alarm),
            )
            assertTrue(
                alarm.fingerprint in preferences
                    .getStringSet(OperationalAlarmKind.HELD_ORDER.storageKey, emptySet())
                    .orEmpty(),
            )

            val timeoutAt = SystemClock.elapsedRealtime() + 25_000L
            while (
                SystemClock.elapsedRealtime() < timeoutAt &&
                preferences.getStringSet(
                    OperationalAlarmKind.HELD_ORDER.storageKey,
                    emptySet(),
                ).orEmpty().isNotEmpty()
            ) {
                SystemClock.sleep(250L)
            }
            assertTrue(
                "The exact allow-while-idle alarm did not reach its receiver",
                preferences.getStringSet(
                    OperationalAlarmKind.HELD_ORDER.storageKey,
                    emptySet(),
                ).orEmpty().isEmpty(),
            )
        } finally {
            shell("dumpsys deviceidle unforce")
            shell("dumpsys battery reset")
            OperationalAlarmRegistry.cancelAll(context)
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private fun alarm(triggerAtMillis: Long = 1_000L) = OperationalAlarmSpec(
        kind = OperationalAlarmKind.HELD_ORDER,
        tag = "held-order-alarm-device-test",
        triggerAtMillis = triggerAtMillis,
        title = "Held order waiting — Table 4",
        body = "This bill has waited at least 15 minutes.",
        target = OperationalNotificationTarget.HeldOrder("alarm-device-test"),
    )
}
