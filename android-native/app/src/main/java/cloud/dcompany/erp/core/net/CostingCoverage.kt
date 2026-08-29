package cloud.dcompany.erp.core.net

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server-authoritative catalogue coverage behind every COGS/P&L figure. */
@Serializable
data class CostingIssue(
    @SerialName("menu_item_id") val menuItemId: String,
    val sku: String,
    val name: String,
    val type: String,
    val issue: String,
    val detail: String,
)

@Serializable
data class CostingCoverage(
    @Required @SerialName("branch_id") val branchId: String = "",
    @SerialName("inventory_item_count") val inventoryItemCount: Int = 0,
    @SerialName("fully_costed_item_count") val fullyCostedItemCount: Int = 0,
    @SerialName("incomplete_item_count") val incompleteItemCount: Int = 0,
    @SerialName("missing_recipe_count") val missingRecipeCount: Int = 0,
    @SerialName("empty_recipe_count") val emptyRecipeCount: Int = 0,
    @SerialName("missing_ingredient_cost_count") val missingIngredientCostCount: Int = 0,
    @SerialName("is_complete") val isComplete: Boolean = false,
    val issues: List<CostingIssue> = emptyList(),
) {
    val warningTitle: String
        get() = when {
            isComplete -> "Inventory costing complete"
            incompleteItemCount == 1 -> "1 menu item is not fully costed"
            else -> "$incompleteItemCount menu items are not fully costed"
        }

    val warningDetail: String
        get() = if (isComplete) {
            "All $fullyCostedItemCount inventory-linked menu items have a recipe and ingredient cost."
        } else {
            "COGS may be understated, so gross profit and net profit may be overstated until " +
                "the missing recipes or ingredient costs are completed."
        }
}
