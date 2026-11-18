package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.canVoidPosLinkedSessions
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.screens.staff.time.staffIdentityAccess
import cloud.dcompany.erp.ui.screens.staff.time.staffTimeAccess
import org.junit.Assert.*
import org.junit.Test

class StoreStaffNavigationTest {
    private fun profile(role: String, permissions: List<String>?, protected: Boolean = false) = MeResponse(
        userId = "actor", companyId = "company", email = "actor@example.test", name = "Actor",
        roles = listOf(role), protectedAccess = protected, effectivePermissions = permissions,
    )
    private val store = listOf("pos.read", "pos.write", "pos.discount.large", "pos.shift.open", "pos.shift.close", "gaming.read", "gaming.write", "menu.read", "staff.attendance.write")

    @Test fun `Store Staff gets manual discounts Gaming and shop shifts without protected money actions`() {
        val p = profile("store_staff", store)
        val permissions = EffectivePermissions.from(p)
        assertTrue(permissions.posAccess().canCreateAndCollect)
        assertTrue(permissions.posAccess().canApplyDiscount)
        assertFalse(permissions.posAccess().canVoid)
        assertFalse(p.canVoidPosLinkedSessions())
        assertTrue(permissions.gamingAccess().canManageSessions)
        assertTrue(permissions.shiftAccess().canOpen)
        assertTrue(permissions.shiftAccess().canClose)
        assertTrue(permissions.staffAccess().canUseAttendance)
        assertFalse(permissions.staffAccess().canReadDirectory)
        assertFalse(permissions.staffAccess().canManageDirectory)
        assertTrue(permissions.financeAccess().isViewOnly)
        assertFalse(staffTimeAccess(p).canManage)
        assertEquals(WorkspaceHomeMode.STAFF, workspaceHomeMode(p))
        val routes = allowedDestinations(p)
        assertTrue(routes.containsAll(listOf(Destination.Pos, Destination.Gaming, Destination.Shift, Destination.Staff, Destination.Dashboard)))
        assertFalse(routes.any { it in setOf(Destination.Finance, Destination.Reports, Destination.Refunds, Destination.AuditLog, Destination.AccessControl) })
    }

    @Test fun `standard Owner Manager and Business Viewer can reach exact authorized business reads`() {
        for (role in listOf("owner", "manager", "auditor")) {
            val p = profile(role, listOf("finance.read", "analytics.read", "staff.read"))
            assertEquals(WorkspaceHomeMode.EXECUTIVE, workspaceHomeMode(p))
            assertTrue(allowedDestinations(p).containsAll(listOf(Destination.Finance, Destination.Reports, Destination.Staff)))
            val identity = staffIdentityAccess(p, EffectivePermissions.from(p).staffAccess())
            assertTrue(identity.canReadDirectory)
            assertFalse(identity.canManageDirectory)
            assertFalse(staffTimeAccess(p).canManage)
            assertFalse(Destination.AuditLog in allowedDestinations(p))
        }
    }

    @Test fun `Manager staff administration requires its exact write but cannot inherit payroll`() {
        val p = profile("manager", listOf("staff.read", "staff.write", "staff.payroll.write"))
        val access = staffIdentityAccess(p, EffectivePermissions.from(p).staffAccess())
        assertTrue(access.canManageDirectory)
        assertFalse(staffTimeAccess(p).canManage)
        assertFalse(staffTimeAccess(p).canRead)
    }

    @Test fun `legacy null owner metadata retains reads while explicit empty denies and exports never fall back`() {
        val legacy = profile("owner", null, true)
        assertEquals(WorkspaceHomeMode.EXECUTIVE, workspaceHomeMode(legacy))
        assertTrue(Destination.Finance in allowedDestinations(legacy))
        assertFalse(EffectivePermissions.from(legacy).reportsAccess().canExportOverview)
        val empty = legacy.copy(effectivePermissions = emptyList())
        assertNull(workspaceHomeMode(empty))
        assertFalse(Destination.Finance in allowedDestinations(empty))
        assertFalse(Destination.Reports in allowedDestinations(empty))
    }

    @Test fun `new Store Staff never uses legacy fallback and owner substrings grant no audience`() {
        val missing = profile("store_staff", null, true)
        assertFalse(EffectivePermissions.from(missing).posAccess().canApplyDiscount)
        assertNull(workspaceHomeMode(missing))
        val misleading = profile("regional_owner_manager", listOf("finance.read", "analytics.read"), true)
        assertNull(workspaceHomeMode(misleading))
        assertFalse(Destination.Finance in allowedDestinations(misleading))
    }

    @Test fun `legacy Viewer directory retains reads without writes and explicit empty removes both`() {
        val legacy = profile("auditor", null)
        val legacyAccess = staffIdentityAccess(legacy, EffectivePermissions.from(legacy).staffAccess())
        assertTrue(Destination.Staff in allowedDestinations(legacy))
        assertTrue(legacyAccess.canReadDirectory)
        assertFalse(legacyAccess.canManageDirectory)
        val empty = legacy.copy(effectivePermissions = emptyList())
        assertFalse(Destination.Staff in allowedDestinations(empty))
        val emptyAccess = staffIdentityAccess(empty, EffectivePermissions.from(empty).staffAccess())
        assertFalse(emptyAccess.canReadDirectory)
        assertFalse(emptyAccess.canManageDirectory)
    }
}
