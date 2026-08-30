package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.WorkspaceFeature
import cloud.dcompany.erp.ui.WorkspaceFeatureProfile
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles

/**
 * Least-privilege read policy for SyncEngine's on-demand resources.
 *
 * Navigation hiding is not enough: refreshes also originate from reconnects,
 * realtime broadcasts and background polling. Every pull therefore checks
 * both the active product feature profile and the server-authoritative
 * `/auth/me` permission set before making a request.
 */
internal class SyncResourceAccess private constructor(
    private val permissions: EffectivePermissions?,
    private val featureProfile: WorkspaceFeatureProfile,
) {
    fun canPull(resource: String): Boolean =
        featureProfileAllows(resource) && permissionAllows(resource)

    /**
     * Product-profile visibility is a pull concern only. Durable pushes and
     * their reconciliation paths never consult this policy, so an update that
     * hides a retired module cannot strand work captured by an older build.
     */
    private fun featureProfileAllows(resource: String): Boolean = when (resource) {
        // POS and Gaming both depend on live shift/order/customer/menu truth
        // even when their supporting management destinations are hidden.
        "shifts" -> includesAny(WorkspaceFeature.Shift, WorkspaceFeature.Pos, WorkspaceFeature.Gaming)
        "orders", "receipts" -> includesAny(WorkspaceFeature.Pos, WorkspaceFeature.Gaming)
        "customers" -> includesAny(
            WorkspaceFeature.Customers,
            WorkspaceFeature.Pos,
            WorkspaceFeature.Gaming,
        )
        "menu" -> includesAny(WorkspaceFeature.Menu, WorkspaceFeature.Pos, WorkspaceFeature.Gaming)
        "gaming" -> includesAny(WorkspaceFeature.Gaming)
        "kitchen" -> includesAny(WorkspaceFeature.Kitchen)
        "tables" -> includesAny(WorkspaceFeature.Tables)
        "reservations" -> includesAny(WorkspaceFeature.Reservations)
        "events" -> includesAny(WorkspaceFeature.Events)
        "memberships" -> includesAny(WorkspaceFeature.Memberships)
        "refunds" -> includesAny(WorkspaceFeature.Refunds)
        "staff", "attendance" -> includesAny(WorkspaceFeature.Staff)
        "inventory" -> includesAny(WorkspaceFeature.Inventory)
        "finance" -> includesAny(WorkspaceFeature.Finance)
        "settings" -> includesAny(WorkspaceFeature.Settings)
        else -> false
    }

    private fun includesAny(vararg features: WorkspaceFeature): Boolean =
        features.any(featureProfile.enabled::contains)

    private fun permissionAllows(resource: String): Boolean = when (resource) {
        "shifts", "orders", "receipts", "customers", "memberships" ->
            permissions?.has(ErpPermission.PosRead) == true
        "gaming", "events" -> permissions?.has(ErpPermission.GamingRead) == true
        "kitchen" -> permissions?.has(ErpPermission.KitchenRead) == true
        "tables" -> permissions?.has(ErpPermission.TablesRead) == true
        "reservations" -> permissions?.hasAny(
            ErpPermission.TablesRead,
            ErpPermission.GamingRead,
        ) == true
        "refunds" -> permissions?.has(ErpPermission.PosRefund) == true
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
        fun from(
            profile: MeResponse?,
            featureProfile: WorkspaceFeatureProfile = WorkspaceFeatureProfiles.Active,
        ): SyncResourceAccess = SyncResourceAccess(
            permissions = profile?.let(EffectivePermissions::from),
            featureProfile = featureProfile,
        )
    }
}
