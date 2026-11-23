package cloud.dcompany.erp.core.auth

import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingCashierAccessTest {
    private val approved = listOf(
        "pos.read", "pos.write", "pos.void", "pos.shift.open", "pos.shift.close",
        "menu.read", "gaming.read", "gaming.write", "staff.attendance.write",
    )

    private fun profile(effective: List<String>?) = MeResponse(
        userId = "fixture-user", email = "operator@example.test", name = "Operator",
        roles = listOf("gaming_cashier"), protectedAccess = false,
        companyId = "fixture-company", branchId = "fixture-branch",
        effectivePermissions = effective,
    )

    @Test
    fun serverPermissionsEnableDailyWorkWithoutManagementOrRefunds() {
        val access = EffectivePermissions.from(profile(approved))
        assertTrue(access.posAccess().canCreateAndCollect)
        assertTrue(access.posAccess().canVoid)
        assertFalse(access.posAccess().canApplyDiscount)
        assertTrue(access.gamingAccess().canManageSessions)
        assertFalse(access.gamingAccess().canReconcileLegacySessions)
        assertEquals(ShiftAccess(canOpen = true, canClose = true), access.shiftAccess())
        assertFalse(access.has(ErpPermission.PosRefund))
        assertFalse(access.has(ErpPermission.GamingTournamentManage))
        assertFalse(access.has(ErpPermission.SettingsManage))
        assertFalse(access.has(ErpPermission.AdminSystem))
        assertFalse(access.has(ErpPermission.AdminAuditRead))
        assertFalse(access.staffAccess().canManageDirectory)
        assertFalse(access.inventoryAccess().canManageInventory)
        assertTrue(access.financeAccess().isViewOnly)
    }

    @Test
    fun missingOrEmptyServerPermissionsDoNotTrustTheNewRoleName() {
        listOf(null, emptyList<String>()).forEach { effective ->
            val access = EffectivePermissions.from(profile(effective))
            assertFalse(access.posAccess().canCreateAndCollect)
            assertFalse(access.gamingAccess().canManageSessions)
            assertTrue(access.shiftAccess().isViewOnly)
        }
    }
}
