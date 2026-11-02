package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.auth.StaffAccess
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceFeature
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.allowedDestinations
import org.junit.Assert.*
import org.junit.Test

class StaffTimeNavigationTest {
    private fun me(roles: List<String> = listOf("staff"), protected: Boolean = false, permissions: List<String> = listOf("staff.attendance.write")) =
        MeResponse("11111111-1111-4111-8111-111111111111", "qa@example.test", "Synthetic", roles, protected,
            companyId = "22222222-2222-4222-8222-222222222222", effectivePermissions = permissions)
    @Test fun attendanceExceptionOpensOnlyStaffTimeAndNotOtherOwnerScreens() {
        val routes = allowedDestinations(me(), WorkspaceFeatureProfiles.GamingCentre)
        assertTrue(Destination.Staff in routes)
        assertFalse(Destination.Finance in routes); assertFalse(Destination.Reports in routes)
        assertFalse(Destination.Dashboard in routes); assertFalse(Destination.Settings in routes)
    }
    @Test fun attendanceDoesNotEnableAnExcludedProductFeature() {
        val profile = WorkspaceFeatureProfiles.GamingCentre.copy(enabled = WorkspaceFeatureProfiles.GamingCentre.enabled - WorkspaceFeature.Staff)
        assertFalse(Destination.Staff in allowedDestinations(me(), profile))
    }
    @Test fun ordinaryDirectoryOrPayrollPermissionDoesNotOpenIdentityAdministration() {
        val p = me(listOf("manager"), permissions = listOf("staff.read", "staff.write", "staff.attendance.write"))
        assertEquals(StaffAccess(false, false, false), staffIdentityAccess(p, StaffAccess(true, true, true)))
        assertFalse(Destination.Staff in allowedDestinations(me(listOf("partner"), permissions = listOf(STAFF_PAYROLL_WRITE)), WorkspaceFeatureProfiles.GamingCentre))
    }
    @Test fun ownerPayrollCanReachTimeWithoutGainingProtectedIdentityUi() {
        val p = me(listOf("owner"), permissions = listOf(STAFF_PAYROLL_WRITE))
        assertTrue(Destination.Staff in allowedDestinations(p, WorkspaceFeatureProfiles.GamingCentre))
        assertTrue(staffTimeAccess(p).canManage)
        assertEquals(StaffAccess(false, false, false), staffIdentityAccess(p, StaffAccess(true, true, true)))
    }
    @Test fun protectedIdentityAccessStillRequiresItsExistingExplicitPermissions() {
        val p = me(listOf("owner"), true, listOf("staff.read", "staff.write"))
        assertEquals(StaffAccess(true, true, false), staffIdentityAccess(p, StaffAccess(true, true, true)))
        assertEquals(StaffAccess(false, false, false), staffIdentityAccess(p, StaffAccess(false, false, true)))
    }
    @Test fun ownerIdentityBitsNeverGrantMissingAttendanceOrPayPermission() {
        val p = me(listOf("owner"), true, emptyList())
        assertFalse(staffTimeAccess(p).canRead); assertFalse(staffTimeAccess(p).canManage)
        assertFalse(Destination.Staff in allowedDestinations(p, WorkspaceFeatureProfiles.GamingCentre))
    }
}
