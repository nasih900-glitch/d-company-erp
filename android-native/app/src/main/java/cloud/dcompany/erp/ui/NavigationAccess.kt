package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse

/** Membership money writes require both server permission and protected-owner identity. */
fun canManageMemberships(profile: MeResponse): Boolean =
    profile.protectedAccess && EffectivePermissions.from(profile).has(ErpPermission.AdminSystem)

/** Settings management follows the same server permission as its write endpoints. */
fun canManageSystemSettings(profile: MeResponse): Boolean =
    EffectivePermissions.from(profile).has(ErpPermission.AdminSystem)

/** The minimum server permission needed for each Android destination. */
fun allowedDestinations(profile: MeResponse): List<Destination> {
    val permissions = EffectivePermissions.from(profile)
    return Destination.entries.filter { destination ->
        when (destination) {
            Destination.Pos -> permissions.has(ErpPermission.PosRead)
            Destination.Gaming -> permissions.has(ErpPermission.GamingRead)
            Destination.Tables -> permissions.has(ErpPermission.TablesRead)
            Destination.Kitchen -> permissions.has(ErpPermission.KitchenRead)
            Destination.Shift -> permissions.hasAny(
                ErpPermission.PosShiftOpen,
                ErpPermission.PosShiftClose,
            )
            Destination.Customers -> permissions.has(ErpPermission.PosRead)
            Destination.Menu -> permissions.has(ErpPermission.MenuRead)
            Destination.Staff -> permissions.hasAny(
                ErpPermission.StaffRead,
                ErpPermission.StaffWrite,
                ErpPermission.StaffAttendanceWrite,
            )
            Destination.Inventory -> permissions.has(ErpPermission.InventoryRead)
            Destination.Reports,
            Destination.Analytics,
            -> permissions.has(ErpPermission.AnalyticsRead)
            Destination.Finance -> permissions.has(ErpPermission.FinanceRead)
            Destination.Events -> permissions.has(ErpPermission.GamingRead)
            Destination.Memberships -> permissions.has(ErpPermission.PosRead)
            Destination.Refunds -> permissions.has(ErpPermission.PosRefund)
            // Keep this tied to the dedicated server identity bit rather than
            // a role name or protected_access. That is the web contract too.
            Destination.AuditLog,
            Destination.AccessControl,
            -> profile.auditAccess
            // Account/password access must remain available to every user.
            Destination.Settings -> true
        }
    }
}
