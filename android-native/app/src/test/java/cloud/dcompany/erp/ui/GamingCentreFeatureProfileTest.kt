package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingCentreFeatureProfileTest {
    @Test
    fun `ordinary staff receive only the five focused operational destinations`() {
        val staff = profile(
            roles = listOf("cashier"),
            permissions = everyModulePermission,
        )

        assertEquals(
            listOf(
                Destination.Gaming,
                Destination.Pos,
                Destination.Shift,
                Destination.Inventory,
                Destination.Help,
            ),
            allowedDestinations(staff),
        )
    }

    @Test
    fun `authorised manager gets Products but not protected owner modules`() {
        val manager = profile(
            roles = listOf("gaming_manager"),
            permissions = everyModulePermission,
        )

        assertEquals(
            listOf(
                Destination.Gaming,
                Destination.Pos,
                Destination.Shift,
                Destination.Inventory,
                Destination.Menu,
                Destination.Help,
            ),
            allowedDestinations(manager),
        )
        assertEquals("Products", Destination.Menu.label)
    }

    @Test
    fun `protected audit owner receives focused operations and owner workspace`() {
        val owner = profile(
            roles = listOf("owner"),
            protectedAccess = true,
            auditAccess = true,
            permissions = everyModulePermission,
        )

        assertEquals(
            listOf(
                Destination.Dashboard,
                Destination.Gaming,
                Destination.Pos,
                Destination.Shift,
                Destination.Inventory,
                Destination.Menu,
                Destination.Finance,
                Destination.Reports,
                Destination.Staff,
                Destination.Settings,
                Destination.AuditLog,
                Destination.SupportInbox,
                Destination.Help,
            ),
            allowedDestinations(owner),
        )
    }

    @Test
    fun `restaurant and membership routes remain compiled but dormant`() {
        val owner = profile(
            roles = listOf("owner"),
            protectedAccess = true,
            auditAccess = true,
            permissions = everyModulePermission,
        )
        val focused = allowedDestinations(owner)

        listOf(
            Destination.Tables,
            Destination.Reservations,
            Destination.Kitchen,
            Destination.Customers,
            Destination.Events,
            Destination.Memberships,
            Destination.Refunds,
        ).forEach { assertFalse(it in focused) }

        val full = allowedDestinations(owner, WorkspaceFeatureProfiles.FullHospitality)
        assertTrue(Destination.Tables in full)
        assertTrue(Destination.Kitchen in full)
        assertTrue(Destination.Memberships in full)
    }

    @Test
    fun `gaming centre presentation hides dormant workflows but full hospitality retains them`() {
        val focused = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()
        val full = WorkspaceFeatureProfiles.FullHospitality.presentationPolicy()

        assertFalse(focused.showsMemberships)
        assertFalse(focused.showsRestaurantOperations)
        assertFalse(focused.showsCustomers)
        assertFalse(focused.showsEvents)
        assertEquals("Legacy/other prepaid revenue", focused.prepaidRevenueLabel)
        assertEquals("Gaming + POS", focused.hybridTerminalLabel)

        assertTrue(full.showsMemberships)
        assertTrue(full.showsRestaurantOperations)
        assertTrue(full.showsCustomers)
        assertTrue(full.showsEvents)
        assertEquals("Memberships", full.prepaidRevenueLabel)
        assertEquals("Hybrid", full.hybridTerminalLabel)
    }

    @Test
    fun `hidden or restored route cannot bypass active allowlist`() {
        val staff = profile(
            roles = listOf("cashier"),
            permissions = everyModulePermission,
        )
        val allowed = allowedDestinations(staff)

        assertEquals(Destination.Gaming, resolveWorkspaceDestination(Destination.Memberships, allowed))
        assertEquals(Destination.Pos, resolveWorkspaceDestination(Destination.Pos, allowed))
        assertEquals(Destination.Help, resolveWorkspaceDestination(Destination.Memberships, emptyList()))
    }

    private fun profile(
        roles: List<String>,
        protectedAccess: Boolean = false,
        auditAccess: Boolean = false,
        permissions: List<String>,
    ) = MeResponse(
        userId = "user-id",
        email = "user@example.test",
        name = "User",
        roles = roles,
        protectedAccess = protectedAccess,
        auditAccess = auditAccess,
        companyId = "company-id",
        branchId = "branch-id",
        effectivePermissions = permissions,
    )

    private val everyModulePermission = listOf(
        ErpPermission.PosRead,
        ErpPermission.PosShiftOpen,
        ErpPermission.PosShiftClose,
        ErpPermission.GamingRead,
        ErpPermission.TablesRead,
        ErpPermission.KitchenRead,
        ErpPermission.MenuRead,
        ErpPermission.StaffRead,
        ErpPermission.InventoryRead,
        ErpPermission.AnalyticsRead,
        ErpPermission.FinanceRead,
        ErpPermission.PosRefund,
        ErpPermission.MembershipsManage,
        ErpPermission.SettingsManage,
        ErpPermission.AdminAuditRead,
        ErpPermission.AdminSystem,
    )
}
