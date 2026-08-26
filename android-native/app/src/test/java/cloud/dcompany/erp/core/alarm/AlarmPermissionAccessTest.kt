package cloud.dcompany.erp.core.alarm

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmPermissionAccessTest {

    @Test
    fun `allowed notifications require no action`() {
        assertEquals(
            NotificationPermissionRoute.NONE,
            route(runtimeGranted = true, notificationsEnabled = true),
        )
    }

    @Test
    fun `first Android 13 request uses the system dialog`() {
        assertEquals(
            NotificationPermissionRoute.REQUEST_SYSTEM_DIALOG,
            route(),
        )
    }

    @Test
    fun `ordinary denial with rationale can be requested again`() {
        assertEquals(
            NotificationPermissionRoute.REQUEST_SYSTEM_DIALOG,
            route(requestedBefore = true, shouldShowRationale = true),
        )
    }

    @Test
    fun `prior no-rationale result opens settings instead of silently relaunching request`() {
        assertEquals(
            NotificationPermissionRoute.OPEN_APP_SETTINGS,
            route(requestedBefore = true),
        )
    }

    @Test
    fun `pre Android 13 notification switch opens settings`() {
        assertEquals(
            NotificationPermissionRoute.OPEN_APP_SETTINGS,
            notificationPermissionRoute(
                sdkInt = Build.VERSION_CODES.S,
                status = status(runtimeGranted = true, notificationsEnabled = false),
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `granted runtime permission with notifications disabled opens settings`() {
        assertEquals(
            NotificationPermissionRoute.OPEN_APP_SETTINGS,
            route(runtimeGranted = true, notificationsEnabled = false),
        )
    }

    private fun route(
        runtimeGranted: Boolean = false,
        notificationsEnabled: Boolean = false,
        requestedBefore: Boolean = false,
        shouldShowRationale: Boolean = false,
    ) = notificationPermissionRoute(
        sdkInt = Build.VERSION_CODES.TIRAMISU,
        status = status(runtimeGranted, notificationsEnabled, requestedBefore),
        shouldShowRationale = shouldShowRationale,
    )

    private fun status(
        runtimeGranted: Boolean,
        notificationsEnabled: Boolean,
        requestedBefore: Boolean = false,
    ) = NotificationPermissionStatus(
        runtimeGranted = runtimeGranted,
        notificationsEnabled = notificationsEnabled,
        requestedBefore = requestedBefore,
    )
}
