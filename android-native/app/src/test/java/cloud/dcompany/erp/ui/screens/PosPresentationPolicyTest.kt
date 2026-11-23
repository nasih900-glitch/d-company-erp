package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosPresentationPolicyTest {

    @Test
    fun directTabletCartUsesCounterSaleTypeWhileHeldWorkflowsKeepTheirServerTypes() {
        assertEquals("takeaway", DIRECT_COUNTER_SALE_ORDER_TYPE)
    }

    @Test
    fun blankSearchPreservesTheCategoryFilteredOrder() {
        val items = listOf(item("cold", "Cold Brew", "CB-01"), item("cap", "Cappuccino", "CP-01"))

        assertEquals(items, filterPosMenuItems(items, "   "))
    }

    @Test
    fun searchMatchesNameSkuAndDescriptionWithoutChangingCase() {
        val coldBrew = item("cold", "Cold Brew", "CB-01", "Slow-steeped coffee")
        val cappuccino = item("cap", "Cappuccino", "CP-01", "Double shot")
        val items = listOf(coldBrew, cappuccino)

        assertEquals(listOf(coldBrew), filterPosMenuItems(items, "cold"))
        assertEquals(listOf(cappuccino), filterPosMenuItems(items, "cp-01"))
        assertEquals(listOf(coldBrew), filterPosMenuItems(items, "SLOW-STEEPED"))
    }

    @Test
    fun unmatchedSearchReturnsAnIntentionalEmptyResult() {
        val items = listOf(item("cold", "Cold Brew", "CB-01"))

        assertEquals(emptyList<MenuItemEntity>(), filterPosMenuItems(items, "tea"))
    }

    @Test
    fun favouritesOnlyPromoteVisibleMatchesInAllAndPreserveRelativeOrder() {
        val items = listOf(
            item("a", "Cold Brew", "CB-01"), item("b", "Hot Coffee", "HC-01"),
            item("c", "Cold Latte", "CL-01"), item("d", "Hot Chocolate", "HC-02"),
        )
        val favourites = setOf("d", "c", "hidden-or-removed")

        assertEquals(listOf(items[2], items[3], items[0], items[1]), prioritisePosFavourites(items, null, favourites))
        assertEquals(items, prioritisePosFavourites(items, "coffee", favourites))
        assertEquals(
            listOf(items[2], items[0]),
            prioritisePosFavourites(filterPosMenuItems(items, "cold"), null, favourites),
        )
    }

    @Test
    fun favouriteStorageScopeSharesBetweenStaffButRequiresMatchingActiveAuthority() {
        val profile = MeResponse("staff-a", "staff-a@example.test", "A", companyId = "company", branchId = "branch")
        val lease = CacheScopeLease(CacheScope("staff-a", "company", "branch", "till"), 1L)
        val terminal = ValidatedTerminalDisplay("till", "Till", "branch", "pos")
        val expected = PosFavouritesScope("company", "branch")
        val otherStaff = profile.copy(userId = "staff-b")

        assertEquals(expected, posFavouritesScope(profile, lease, terminal))
        assertNull(posFavouritesScope(otherStaff, lease, terminal))
        assertNull(posFavouritesScope(profile.copy(companyId = "other"), lease, terminal))
        assertNull(posFavouritesScope(profile.copy(branchId = "other"), lease, terminal))
        assertEquals(expected, posFavouritesScope(otherStaff, lease.copy(scope = lease.scope.copy(userId = "staff-b")), terminal))
        val unassignedProfile = profile.copy(branchId = null)
        val unassignedLease = lease.copy(scope = lease.scope.copy(branchId = null))
        assertNull(posFavouritesScope(unassignedProfile, unassignedLease, terminal.copy(terminalId = "other")))
        assertEquals(expected, posFavouritesScope(unassignedProfile, unassignedLease, terminal))
        assertNotEquals(expected.preferenceKey, expected.copy(branchId = "other").preferenceKey)
    }

    private fun item(
        id: String,
        name: String,
        sku: String,
        description: String? = null,
    ) = MenuItemEntity(
        id = id,
        categoryId = "coffee",
        sku = sku,
        name = name,
        type = "food",
        basePriceMinor = 15_000,
        taxRate = 0.0,
        hsnCode = null,
        priceIncludesTax = false,
        isAvailable = true,
        description = description,
    )
}
