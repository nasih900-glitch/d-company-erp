package cloud.dcompany.erp.core.remote

internal data class RemoteAssistanceNotificationCopy(
    val title: String,
    val detail: String,
)

/**
 * User-facing disclosure for the persistent remote-assistance indicator.
 *
 * Keep this copy independent from Android framework classes so the security
 * promise can be verified by ordinary JVM tests. The notification never
 * implies whole-app or whole-device visibility: Code 18 shares Help only.
 */
internal fun remoteAssistanceNotificationCopy(
    sharingPaused: Boolean,
): RemoteAssistanceNotificationCopy = if (sharingPaused) {
    RemoteAssistanceNotificationCopy(
        title = "ERP Help sharing paused",
        detail = "Offline: sharing is paused. Owner can view Help only; other ERP screens " +
            "stay hidden. Stop remains available.",
    )
} else {
    RemoteAssistanceNotificationCopy(
        title = "D Company ERP Help support active",
        detail = "Owner can view Help only; other ERP screens are hidden. " +
            "Stop is always available.",
    )
}
