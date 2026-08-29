package cloud.dcompany.erp.core.auth

import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalAccessTest {

    @Test
    fun `authoritative permissions map to least privilege screen capabilities`() {
        val readOnly = permissions()
        assertFalse(readOnly.posAccess().canCreateAndCollect)
        assertFalse(readOnly.gamingAccess().canManageSessions)
        assertFalse(readOnly.gamingAccess().canReconcileLegacySessions)
        assertFalse(readOnly.kitchenAccess().canAdvanceTickets)
        assertFalse(readOnly.tablesAccess().canCreateOrders)
        assertFalse(readOnly.customersAccess().canManageCustomers)
        assertFalse(readOnly.menuAccess().canManageMenu)
        assertFalse(readOnly.inventoryAccess().canManageInventory)
        assertTrue(readOnly.financeAccess().isViewOnly)
        assertTrue(readOnly.eventsAccess().isViewOnly)
        assertTrue(readOnly.shiftAccess().isViewOnly)

        val cashier = permissions(
            ErpPermission.PosRead,
            ErpPermission.PosWrite,
            ErpPermission.TablesWrite,
            ErpPermission.PosShiftOpen,
            ErpPermission.PosShiftClose,
        )
        assertTrue(cashier.posAccess().canCreateAndCollect)
        assertTrue(cashier.tablesAccess().canCreateOrders)
        assertTrue(cashier.tablesAccess().canSendToPos)
        assertFalse(cashier.tablesAccess().canCancelItems)
        assertTrue(cashier.customersAccess().canManageCustomers)
        assertEquals(ShiftAccess(canOpen = true, canClose = true), cashier.shiftAccess())
        assertFalse(cashier.gamingAccess().canManageSessions)
        assertFalse(cashier.kitchenAccess().canAdvanceTickets)

        val tableOnly = permissions(ErpPermission.TablesWrite)
        // Every Tables order/round also enters the POS contract, so the
        // backend requires both writes; a tables-only profile is view-only.
        assertFalse(tableOnly.tablesAccess().canCreateOrders)
        assertFalse(tableOnly.tablesAccess().canSendToPos)
        assertFalse(tableOnly.tablesAccess().canCancelItems)
        assertFalse(tableOnly.customersAccess().canManageCustomers)

        val posOnly = permissions(ErpPermission.PosWrite)
        assertFalse(posOnly.tablesAccess().canCreateOrders)
        assertFalse(posOnly.tablesAccess().canSendToPos)
        assertFalse(posOnly.tablesAccess().canCancelItems)
        assertTrue(posOnly.customersAccess().canManageCustomers)

        val tableVoid = permissions(
            ErpPermission.TablesWrite,
            ErpPermission.PosWrite,
            ErpPermission.PosVoid,
        )
        assertEquals(
            TablesAccess(
                canCreateOrders = true,
                canCancelItems = true,
                canSendToPos = true,
            ),
            tableVoid.tablesAccess(),
        )

        val kitchen = permissions(ErpPermission.KitchenWrite)
        assertTrue(kitchen.kitchenAccess().canAdvanceTickets)
        assertFalse(kitchen.tablesAccess().canCreateOrders)

        val catalogAndStock = permissions(
            ErpPermission.MenuWrite,
            ErpPermission.InventoryWrite,
            ErpPermission.InventoryAdjustLarge,
        )
        assertTrue(catalogAndStock.menuAccess().canManageMenu)
        assertTrue(catalogAndStock.inventoryAccess().canManageInventory)
        assertTrue(catalogAndStock.inventoryAccess().canMakeLargeAdjustment)
        assertFalse(catalogAndStock.inventoryAccess().canManageCosting)

        val protectedCosting = permissions(
            ErpPermission.InventoryWrite,
            ErpPermission.AdminSystem,
        )
        assertTrue(protectedCosting.inventoryAccess().canManageInventory)
        assertTrue(protectedCosting.inventoryAccess().canManageCosting)

        val gaming = permissions(
            ErpPermission.PosRead,
            ErpPermission.GamingRead,
            ErpPermission.GamingWrite,
            ErpPermission.GamingTournamentManage,
        )
        assertTrue(gaming.gamingAccess().canManageSessions)
        assertFalse(gaming.gamingAccess().canReconcileLegacySessions)
        assertEquals(
            EventsAccess(canManageEvents = true, canCheckInTickets = true),
            gaming.eventsAccess(),
        )

        val protectedGaming = permissions(
            ErpPermission.PosRead,
            ErpPermission.GamingRead,
            ErpPermission.GamingWrite,
            ErpPermission.AdminAuditRead,
        )
        assertTrue(protectedGaming.gamingAccess().canManageSessions)
        assertTrue(protectedGaming.gamingAccess().canReconcileLegacySessions)

        val finance = permissions(ErpPermission.FinanceWrite)
        assertTrue(finance.financeAccess().canRecordExpenses)
        assertFalse(finance.financeAccess().canManageAssets)
        assertFalse(finance.financeAccess().canRecordPartnerCapital)

        val ownerFinance = permissions(
            ErpPermission.FinanceWrite,
            ErpPermission.FinanceAssetsWrite,
            ErpPermission.FinancePartnerWrite,
        )
        assertEquals(
            FinanceAccess(
                canRecordExpenses = true,
                canManageAssets = true,
                canRecordPartnerCapital = true,
            ),
            ownerFinance.financeAccess(),
        )
    }

    @Test
    fun `authoritative empty set never inherits role or protected owner writes`() {
        val profile = profile(
            effective = emptyList(),
            roles = listOf("owner"),
            protectedAccess = true,
        )
        val permissions = EffectivePermissions.from(profile)

        assertTrue(permissions.financeAccess().isViewOnly)
        assertTrue(permissions.eventsAccess().isViewOnly)
        assertTrue(permissions.shiftAccess().isViewOnly)
        assertFalse(permissions.inventoryAccess().canManageInventory)
    }

    @Test
    fun `only terminal users with workspace discovery authority require assignment`() {
        assertFalse(permissions().requiresOperationalWorkspace())
        assertTrue(permissions(ErpPermission.PosRead).requiresOperationalWorkspace())
        assertFalse(permissions(ErpPermission.PosWrite).requiresOperationalWorkspace())
        assertFalse(permissions(ErpPermission.PosShiftClose).requiresOperationalWorkspace())
        assertTrue(permissions(ErpPermission.GamingRead).requiresOperationalWorkspace())
        assertFalse(permissions(ErpPermission.GamingWrite).requiresOperationalWorkspace())
        assertTrue(
            permissions(
                ErpPermission.PosRead,
                ErpPermission.GamingWrite,
            ).requiresOperationalWorkspace(),
        )
        assertTrue(
            permissions(
                ErpPermission.SettingsManage,
                ErpPermission.GamingWrite,
            ).requiresOperationalWorkspace(),
        )
        assertFalse(permissions(ErpPermission.FinanceRead).requiresOperationalWorkspace())
        assertFalse(permissions(ErpPermission.AnalyticsRead).requiresOperationalWorkspace())
        assertFalse(permissions(ErpPermission.InventoryWrite).requiresOperationalWorkspace())
    }

    @Test
    fun `gaming read discovers workspace while missing shift read keeps controls view only`() {
        val gamingReader = permissions(ErpPermission.GamingRead)
        val gamingWithoutShiftRead = permissions(
            ErpPermission.GamingRead,
            ErpPermission.GamingWrite,
        )
        val staleWriteOnly = permissions(
            ErpPermission.GamingWrite,
        )

        assertTrue(gamingReader.requiresOperationalWorkspace())
        assertFalse(gamingReader.gamingAccess().canManageSessions)
        assertTrue(gamingWithoutShiftRead.requiresOperationalWorkspace())
        assertFalse(gamingWithoutShiftRead.gamingAccess().canManageSessions)
        assertTrue(gamingWithoutShiftRead.gamingAccess().writeBlockedByMissingShiftRead)
        assertFalse(staleWriteOnly.requiresOperationalWorkspace())
        assertFalse(staleWriteOnly.gamingAccess().canManageSessions)
    }

    @Test
    fun `terminal scoped writes fail closed without terminal discovery authority`() {
        val writeOnly = permissions(
            ErpPermission.PosWrite,
            ErpPermission.PosShiftOpen,
            ErpPermission.PosShiftClose,
        )

        assertFalse(writeOnly.posAccess().canCreateAndCollect)
        assertTrue(writeOnly.shiftAccess().isViewOnly)
    }

    @Test
    fun `denied ViewModel guard does not enter write block`() {
        var deniedFeedback = 0
        var apiOrOutboxWrites = 0

        if (authorizeAction(allowed = false) { deniedFeedback += 1 }) {
            apiOrOutboxWrites += 1
        }

        assertEquals(1, deniedFeedback)
        assertEquals(0, apiOrOutboxWrites)

        if (authorizeAction(allowed = true) { deniedFeedback += 1 }) {
            apiOrOutboxWrites += 1
        }
        assertEquals(1, deniedFeedback)
        assertEquals(1, apiOrOutboxWrites)
    }

    private fun permissions(vararg effective: String): EffectivePermissions =
        EffectivePermissions.from(profile(effective = effective.toList()))

    private fun profile(
        effective: List<String>,
        roles: List<String> = emptyList(),
        protectedAccess: Boolean = false,
    ) = MeResponse(
        userId = "user-1",
        email = "employee@example.com",
        name = "Employee",
        roles = roles,
        protectedAccess = protectedAccess,
        companyId = "company-1",
        branchId = "branch-1",
        accessibleModules = emptyList(),
        effectivePermissions = effective,
    )
}
