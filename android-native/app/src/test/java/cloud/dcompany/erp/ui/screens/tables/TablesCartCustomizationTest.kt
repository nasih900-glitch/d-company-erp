package cloud.dcompany.erp.ui.screens.tables

import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.ui.screens.CartModifierSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class TablesCartCustomizationTest {
    @Test
    fun `configured table line uses option price and preparation labels`() {
        val item = MenuItemEntity(
            id = "coffee",
            categoryId = "drinks",
            sku = "COFFEE",
            name = "Cappuccino",
            type = "drink",
            basePriceMinor = 18_000,
            taxRate = 0.0,
            hsnCode = null,
            priceIncludesTax = true,
            isAvailable = true,
            description = null,
        )
        val variant = MenuVariantEntity(
            id = "large",
            menuItemId = item.id,
            name = "Large",
            priceDeltaMinor = 3_000,
            sortOrder = 0,
            isActive = true,
        )
        val oat = MenuModifierEntity(
            id = "oat",
            menuItemId = item.id,
            modifierGroupId = "milk",
            name = "Oat milk",
            priceDeltaMinor = 2_000,
            maxQuantity = 1,
            sortOrder = 0,
            isActive = true,
        )

        val line = TableCartLine(
            item = item,
            qty = 2,
            note = "less hot",
            variant = variant,
            modifiers = listOf(CartModifierSelection(oat, 1)),
        )

        assertEquals(23_000L, line.unitPriceMinor)
        assertEquals(46_000L, line.lineTotalMinor)
        assertEquals(listOf("Large", "Oat milk"), line.optionLabels)
    }
}
