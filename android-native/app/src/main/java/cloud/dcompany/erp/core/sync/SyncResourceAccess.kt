package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse

/**
 * Least-privilege read policy for SyncEngine's on-demand resources.
 *
 * Navigation hiding is not enough: refreshes also originate from reconnects,
 * realtime broadcasts and background polling. Every pull therefore checks the
 * same server-authoritative `/auth/me` permission set before making a request.
 */
internal class SyncResourceAccess private constructor(
    private val permissions: EffectivePermissions?,
) {
    fun canPull(resource: String): Boolean = when (resource) {
        "shifts", "orders", "receipts", "customers", "memberships" ->
            permissions?.has(ErpPermission.PosRead) == true
        "gaming", "events" -> permissions?.has(ErpPermission.GamingRead) == true
        "kitchen" -> permissions?.has(ErpPermission.KitchenRead) == true
        "tables" -> permissions?.has(ErpPermission.TablesRead) == true
        "menu" -> permissions?.has(ErpPermission.MenuRead) == true
        "staff" -> permissions?.has(ErpPermission.StaffRead) == true
        "attendance" -> permissions?.hasAny(
            ErpPermission.StaffRead,
            ErpPermission.StaffAttendanceWrite,
        ) == true
        "inventory" -> permissions?.has(ErpPermission.InventoryRead) == true
        "finance" -> permissions?.has(ErpPermission.FinanceRead) == true
        "settings" -> permissions?.has(ErpPermission.SettingsManage) == true
        else -> false
    }

    companion object {
        fun from(profile: MeResponse?): SyncResourceAccess = SyncResourceAccess(
            profile?.let(EffectivePermissions::from),
        )
    }
}
