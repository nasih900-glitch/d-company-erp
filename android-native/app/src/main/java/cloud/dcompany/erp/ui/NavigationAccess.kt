package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse

/** Membership money writes require both server permission and protected-owner identity. */
fun canManageMemberships(profile: MeResponse): Boolean =
    EffectivePermissions.from(profile).membershipAccess(profile).canManageMoney

/** Legacy attempt/evidence recovery is Audit Control, not ordinary owner work. */
fun canRecoverMembershipEvidence(profile: MeResponse): Boolean =
    EffectivePermissions.from(profile).membershipAccess(profile).canRecoverLegacyEvidence

/** Settings management follows the same server permission as its write endpoints. */
fun canManageSystemSettings(profile: MeResponse): Boolean =
    EffectivePermissions.from(profile).has(ErpPermission.SettingsManage)

/**
 * Product-profile, owner-tier and server-permission intersection for every
 * Android route. Feature flags never grant authority and permissions never
 * make a dormant product module visible.
 */
fun allowedDestinations(
    profile: MeResponse,
    featureProfile: WorkspaceFeatureProfile = WorkspaceFeatureProfiles.Active,
): List<Destination> {
    val permissions = EffectivePermissions.from(profile)
    val ownerWorkspace = profile.protectedAccess || profile.auditAccess
    val managementWorkspace = ownerWorkspace || profile.roles.any { role ->
        val normalized = role.trim().lowercase().replace('-', '_').replace(' ', '_')
        normalized.contains("manager") || normalized.contains("owner")
    }
    return featureProfile.navigationOrder.filter { destination ->
        if (!featureProfile.includes(destination)) return@filter false
        val audienceAllowed = (!featureProfile.requiresOwner(destination) || ownerWorkspace) &&
            (!featureProfile.requiresManagement(destination) || managementWorkspace)
        if (!audienceAllowed) return@filter false
        when (destination) {
            Destination.Dashboard -> permissions.has(ErpPermission.AnalyticsRead)
            Destination.Pos -> permissions.has(ErpPermission.PosRead)
            Destination.Gaming -> permissions.has(ErpPermission.GamingRead)
            Destination.Tables -> permissions.has(ErpPermission.TablesRead)
            Destination.Reservations -> permissions.hasAny(
                ErpPermission.TablesRead,
                ErpPermission.GamingRead,
            )
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
            Destination.SupportInbox -> permissions.has(ErpPermission.AdminSystem)
            Destination.Settings -> true
            Destination.Help -> true
        }
    }
}
