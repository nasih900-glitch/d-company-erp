package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResourceAccessTest {
    @Test
    fun `kitchen profile pulls only its server-authoritative read resources`() {
        val access = SyncResourceAccess.from(
            profile(
                roles = listOf("kitchen"),
                permissions = listOf(
                    ErpPermission.KitchenRead,
                    ErpPermission.MenuRead,
                    ErpPermission.StaffAttendanceWrite,
                ),
            ),
        )

        assertTrue(access.canPull("kitchen"))
        assertTrue(access.canPull("menu"))
        assertTrue(access.canPull("attendance"))
        for (forbidden in listOf("shifts", "orders", "customers", "memberships", "tables")) {
            assertFalse(forbidden, access.canPull(forbidden))
        }
    }

    @Test
    fun `combined operations profile can refresh table bill after a kitchen change`() {
        val access = SyncResourceAccess.from(
            profile(
                roles = listOf("staff"),
                permissions = listOf(
                    ErpPermission.KitchenRead,
                    ErpPermission.TablesRead,
                ),
            ),
        )

        assertTrue(access.canPull("kitchen"))
        assertTrue(access.canPull("tables"))
        assertFalse(access.canPull("finance"))
    }

    @Test
    fun `POS read grants shift pulls without granting unrelated finance pulls`() {
        val access = SyncResourceAccess.from(
            profile(
                roles = listOf("cashier"),
                permissions = listOf(ErpPermission.PosRead),
            ),
        )

        assertTrue(access.canPull("shifts"))
        assertTrue(access.canPull("orders"))
        assertFalse(access.canPull("finance"))
        assertFalse(access.canPull("settings"))
    }

    @Test
    fun `settings cache follows dedicated management permission not admin system`() {
        val settingsOwner = SyncResourceAccess.from(
            profile(
                roles = listOf("owner"),
                permissions = listOf(ErpPermission.SettingsManage),
            ),
        )
        val protectedAdminOnly = SyncResourceAccess.from(
            profile(
                roles = listOf("super_owner"),
                permissions = listOf(ErpPermission.AdminSystem),
            ),
        )

        assertTrue(settingsOwner.canPull("settings"))
        assertFalse(protectedAdminOnly.canPull("settings"))
    }

    @Test
    fun `missing profile fails closed for every background pull`() {
        val access = SyncResourceAccess.from(null)

        assertFalse(access.canPull("kitchen"))
        assertFalse(access.canPull("shifts"))
        assertFalse(access.canPull("unknown"))
    }

    private fun profile(
        roles: List<String>,
        permissions: List<String>,
    ) = MeResponse(
        userId = "user-id",
        email = "user@example.com",
        name = "User",
        roles = roles,
        companyId = "company-id",
        branchId = "branch-id",
        accessibleModules = emptyList(),
        effectivePermissions = permissions,
    )
}
