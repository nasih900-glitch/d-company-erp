package cloud.dcompany.erp.core.alarm

import android.content.Context

/**
 * Notification ids are navigation hints, never authority. The target must
 * still exist in the currently activated account/branch/terminal Room scope
 * immediately before navigation. Both candidate builders also exclude local
 * terminal outcomes (payment/stop) that are newer than a cached server row.
 */
internal suspend fun operationalTargetExistsInCurrentScope(
    context: Context,
    target: OperationalNotificationTarget,
): Boolean = when (target) {
    is OperationalNotificationTarget.HeldOrder ->
        HeldOrderAlarmReconciler.currentAlarms(context).any { it.target == target }
    is OperationalNotificationTarget.GamingSession ->
        GamingAlarmReconciler.currentAlarms(context).any { it.target == target }
}
