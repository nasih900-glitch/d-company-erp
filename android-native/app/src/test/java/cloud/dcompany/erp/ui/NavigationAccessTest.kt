package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.loadPlan
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAccessTest {
    @Test
    fun `cashier sees operational tabs but no privileged reporting or refunds`() {
        val profile = profile(
            roles = listOf("cashier"),
            effective = listOf(
                ErpPermission.PosRead,
                ErpPermission.PosShiftOpen,
                ErpPermission.PosShiftClose,
                ErpPermission.TablesRead,
                ErpPermission.MenuRead,
                ErpPermission.GamingRead,
                ErpPermission.KitchenRead,
                ErpPermission.StaffAttendanceWrite,
            ),
        )

        assertEquals(
            listOf(
                Destination.Pos,
                Destination.Gaming,
                Destination.Tables,
                Destination.Kitchen,
                Destination.Shift,
                Destination.Customers,
                Destination.Menu,
                Destination.Staff,
                Destination.Events,
                Destination.Memberships,
                Destination.Settings,
            ),
            allowedDestinations(profile),
        )
    }

    @Test
    fun `attendance-only account gets Staff and Account settings only`() {
        val profile = profile(
            roles = listOf("attendance_only"),
            effective = listOf(ErpPermission.StaffAttendanceWrite),
        )
        val permissions = EffectivePermissions.from(profile)

        assertEquals(
            listOf(Destination.Staff, Destination.Settings),
            allowedDestinations(profile),
        )
        assertEquals(
            false to true,
            permissions.staffAccess().let { it.canReadDirectory to it.canUseAttendance },
        )
        assertEquals(
            cloud.dcompany.erp.core.auth.StaffLoadPlan(
                loadRoles = false,
                pullDirectory = false,
                pullAttendance = true,
                pushManagementOutbox = false,
            ),
            permissions.staffAccess().loadPlan(),
        )
    }

    @Test
    fun `authoritative empty permission list does not fall back to broad role defaults`() {
        val profile = profile(
            roles = listOf("owner"),
            protectedAccess = true,
            effective = emptyList(),
        )

        assertEquals(listOf(Destination.Settings), allowedDestinations(profile))
    }

    @Test
    fun `legacy cached staff profile remains usable but module denial is respected`() {
        val profile = profile(
            roles = listOf("staff"),
            accessibleModules = listOf("pos", "tables", "menu", "staff"),
            effective = null,
        )

        assertEquals(
            listOf(
                Destination.Pos,
                Destination.Tables,
                Destination.Customers,
                Destination.Menu,
                Destination.Staff,
                Destination.Memberships,
                Destination.Settings,
            ),
            allowedDestinations(profile),
        )
    }

    @Test
    fun `old JSON and exact admin contract remain distinguishable`() {
        val oldPayload =
            """{"user_id":"u","email":"u@example.com","name":"U","roles":["staff"],"company_id":"c"}"""
        val oldProfile = Json { ignoreUnknownKeys = true }.decodeFromString(
            MeResponse.serializer(),
            oldPayload,
        )
        assertNull(oldProfile.effectivePermissions)
        assertNull(oldProfile.accessibleModules)
        assertTrue(Destination.Pos in allowedDestinations(oldProfile))

        val admin = profile(
            roles = listOf("owner"),
            auditAccess = true,
            effective = listOf(
                ErpPermission.PosRead,
                ErpPermission.AdminAuditRead,
                ErpPermission.AdminSystem,
            ),
        )
        val permissions = EffectivePermissions.from(admin)
        assertTrue(Destination.AuditLog in allowedDestinations(admin))
        assertTrue(Destination.AccessControl in allowedDestinations(admin))
        assertTrue(Destination.Memberships in allowedDestinations(admin))
        assertTrue(permissions.has(ErpPermission.AdminSystem))
        assertFalse(permissions.has(ErpPermission.FinanceRead))
    }

    @Test
    fun `protected co-owner cannot see audit log or access control without audit access`() {
        val coOwner = profile(
            roles = listOf("co_owner"),
            protectedAccess = true,
            auditAccess = false,
            effective = listOf(ErpPermission.AdminAuditRead, ErpPermission.AdminSystem),
        )

        assertFalse(Destination.AuditLog in allowedDestinations(coOwner))
        assertFalse(Destination.AccessControl in allowedDestinations(coOwner))
    }

    @Test
    fun `membership writes require protected access and admin system permission`() {
        val permissionOnly = profile(
            roles = listOf("manager"),
            protectedAccess = false,
            effective = listOf(ErpPermission.PosRead, ErpPermission.AdminSystem),
        )
        val protectedOwner = profile(
            roles = listOf("co_owner"),
            protectedAccess = true,
            effective = listOf(ErpPermission.PosRead),
        )
        val authorisedOwner = profile(
            roles = listOf("co_owner"),
            protectedAccess = true,
            effective = listOf(ErpPermission.PosRead, ErpPermission.AdminSystem),
        )

        assertTrue(Destination.Memberships in allowedDestinations(permissionOnly))
        assertFalse(canManageMemberships(permissionOnly))
        assertTrue(Destination.Memberships in allowedDestinations(protectedOwner))
        assertFalse(canManageMemberships(protectedOwner))
        assertTrue(canManageMemberships(authorisedOwner))
    }

    private fun profile(
        roles: List<String>,
        protectedAccess: Boolean = false,
        auditAccess: Boolean = false,
        accessibleModules: List<String>? = emptyList(),
        effective: List<String>?,
    ) = MeResponse(
        userId = "user-id",
        email = "user@example.com",
        name = "User",
        roles = roles,
        protectedAccess = protectedAccess,
        auditAccess = auditAccess,
        companyId = "company-id",
        branchId = "branch-id",
        accessibleModules = accessibleModules,
        effectivePermissions = effective,
    )
}
