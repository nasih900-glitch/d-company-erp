package cloud.dcompany.erp.ui.screens.inventory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryLoadStateTest {

    @Test
    fun `partial first load never presents unfetched suppliers as real empty data`() {
        val state = InventoryUiState(
            ingredientsLoaded = true,
            suppliersLoaded = false,
            ingredients = listOf(
                IngredientRow(
                    id = "ingredient-1",
                    sku = "MILK",
                    name = "Milk",
                    baseUnit = "ml",
                ),
            ),
            refreshError = "Supplier refresh failed",
        )

        assertFalse(state.couldNotLoad)
        assertTrue(state.suppliersUnavailable)
        assertFalse(state.ingredientsUnavailable)
    }

    @Test
    fun `successfully fetched empty supplier list remains a legitimate empty state`() {
        val state = InventoryUiState(
            ingredientsLoaded = true,
            suppliersLoaded = true,
            suppliers = emptyList(),
        )

        assertFalse(state.suppliersUnavailable)
    }
}
