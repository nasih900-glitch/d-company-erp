package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.MenuItemEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PosPresentationPolicyTest {

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
