package cloud.dcompany.erp.ui.screens.inventory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Constrained-height coverage for the recipe detail shown below the item list. */
class RecipeDetailUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun constrained224DpActiveRecipeKeepsLineAndAddActionsReachableWithExactCallbacks() {
        val state = recipeState(lines = listOf(recipeLine), ingredients = listOf(ingredient))
        val calls = Calls()

        render(state, calls, heightDp = 224)

        assertActiveActionsReachable(state, calls)
    }

    @Test
    fun constrained162DpActiveRecipeKeepsLineAndAddActionsReachableWithExactCallbacks() {
        val state = recipeState(lines = listOf(recipeLine), ingredients = listOf(ingredient))
        val calls = Calls()

        render(state, calls, heightDp = 162)

        assertActiveActionsReachable(state, calls)
    }

    private fun assertActiveActionsReachable(state: InventoryUiState, calls: Calls) {
        val panel = compose.onNodeWithTag(PANEL_TAG)
        val scroll = compose.onNode(hasScrollAction()).assertUsableScrollViewport(panel)

        compose.onNode(actionButton("Deactivate"))
            .assertCompleteActionInside(panel, "Deactivate")
            .performTouchInput { click() }
        scroll.performScrollToNode(actionButton("Edit"))
        compose.onNode(actionButton("Edit"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Edit")
            .performTouchInput { click() }
        scroll.performScrollToNode(actionButton("Remove"))
        compose.onNode(actionButton("Remove"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Remove")
            .performTouchInput { click() }
        scroll.performScrollToNode(actionButton("Add ingredient"))
        compose.onNode(actionButton("Add ingredient"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Add ingredient")
            .assertIsEnabled()
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(listOf(activeRecipe), calls.deactivated)
            assertEquals(listOf(activeRecipe to recipeLine), calls.edited)
            assertEquals(listOf(activeRecipe to recipeLine), calls.removed)
            assertEquals(listOf(activeRecipe), calls.added)
            assertEquals(activeRecipe, state.activeRecipe)
        }
    }

    @Test
    fun constrainedEmptyRecipeKeepsWarningAndAddActionReachableWithoutRenderMutation() {
        val emptyRecipe = activeRecipe.copy(lines = emptyList())
        val state = recipeState(
            lines = emptyList(),
            ingredients = listOf(ingredient),
            recipe = emptyRecipe,
        )
        val calls = Calls()

        render(state, calls, heightDp = 162)
        val panel = compose.onNodeWithTag(PANEL_TAG)
        val scroll = compose.onNode(hasScrollAction()).assertUsableScrollViewport(panel)

        compose.runOnIdle {
            assertTrue("Rendering an empty recipe must not invoke a callback", calls.isEmpty())
            assertEquals(emptyRecipe, state.activeRecipe)
        }
        scroll.performScrollToNode(hasText("Empty recipe"))
        compose.onNodeWithText("Empty recipe").performScrollTo().assertIsDisplayed()
        scroll.performScrollToNode(actionButton("Add ingredient"))
        compose.onNode(actionButton("Add ingredient"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Add ingredient")
            .assertIsEnabled()
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(listOf(emptyRecipe), calls.added)
            assertEquals(emptyRecipe, state.activeRecipe)
        }
    }

    @Test
    fun constrainedMissingIngredientStaysExplicitAndCannotEnableAddWithoutSyncedData() {
        val state = recipeState(lines = listOf(recipeLine), ingredients = emptyList())
        val calls = Calls()

        render(state, calls, heightDp = 162)
        val panel = compose.onNodeWithTag(PANEL_TAG)
        val scroll = compose.onNode(hasScrollAction()).assertUsableScrollViewport(panel)

        compose.runOnIdle {
            assertTrue("Rendering a missing ingredient must not invoke a callback", calls.isEmpty())
            assertEquals(activeRecipe, state.activeRecipe)
        }
        scroll.performScrollToNode(hasText("Unavailable ingredient"))
        compose.onNodeWithText("Unavailable ingredient").performScrollTo().assertIsDisplayed()
        scroll.performScrollToNode(actionButton("Edit"))
        compose.onNode(actionButton("Edit"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Edit")
            .performTouchInput { click() }
        scroll.performScrollToNode(actionButton("Add ingredient", requireClick = false))
        compose.onNode(actionButton("Add ingredient", requireClick = false))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Add ingredient")
            .assertIsNotEnabled()

        compose.runOnIdle {
            assertEquals(listOf(activeRecipe to recipeLine), calls.edited)
            assertTrue("Disabled Add must not invoke its callback", calls.added.isEmpty())
            assertEquals(activeRecipe, state.activeRecipe)
        }
    }

    @Test
    fun constrainedNoActiveRecipeKeepsLinkActionReachableWithExactCallback() {
        val state = recipeState(
            lines = emptyList(),
            ingredients = listOf(ingredient),
            recipe = null,
        )
        val calls = Calls()

        render(state, calls, heightDp = 162)
        val panel = compose.onNodeWithTag(PANEL_TAG)
        val scroll = compose.onNode(hasScrollAction()).assertUsableScrollViewport(panel)

        compose.runOnIdle {
            assertTrue("Rendering a missing recipe must not invoke a callback", calls.isEmpty())
        }
        scroll.performScrollToNode(actionButton("Link recipe"))
        compose.onNode(actionButton("Link recipe"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Link recipe")
            .assertIsEnabled()
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(listOf(menuItem), calls.linked)
            assertTrue(calls.deactivated.isEmpty() && calls.edited.isEmpty() && calls.added.isEmpty())
        }
    }

    @Test
    fun constrainedRecipeErrorKeepsRetryActionReachableWithExactCallback() {
        val state = recipeState(
            lines = emptyList(),
            ingredients = listOf(ingredient),
            recipe = null,
            recipesError = "Synthetic recipe service failure",
        )
        val calls = Calls()

        render(state, calls, heightDp = 162)
        val panel = compose.onNodeWithTag(PANEL_TAG)
        val scroll = compose.onNode(hasScrollAction()).assertUsableScrollViewport(panel)

        compose.runOnIdle {
            assertTrue("Rendering a recipe error must not invoke a callback", calls.isEmpty())
        }
        scroll.performScrollToNode(actionButton("Retry"))
        compose.onNode(actionButton("Retry"))
            .performScrollTo()
            .assertCompleteActionInside(panel, "Retry")
            .assertIsEnabled()
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, calls.retries)
            assertTrue(calls.linked.isEmpty() && calls.edited.isEmpty() && calls.added.isEmpty())
        }
    }

    private fun render(state: InventoryUiState, calls: Calls, heightDp: Int) {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(420.dp).height(heightDp.dp)) {
                    RecipeDetailPresentation(
                        state = state,
                        modifier = Modifier.fillMaxSize().testTag(PANEL_TAG),
                        onRetry = { calls.retries++ },
                        onLinkRecipe = calls.linked::add,
                        onDeactivateRecipe = calls.deactivated::add,
                        onEditRecipeLine = { recipe, line -> calls.edited += recipe to line },
                        onRemoveRecipeLine = { recipe, line -> calls.removed += recipe to line },
                        onAddRecipeLine = calls.added::add,
                    )
                }
            }
        }
    }

    private fun actionButton(label: String, requireClick: Boolean = true): SemanticsMatcher {
        val role = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        return if (requireClick) hasText(label) and role and hasClickAction() else hasText(label) and role
    }

    private fun SemanticsNodeInteraction.assertUsableScrollViewport(
        panel: SemanticsNodeInteraction,
    ): SemanticsNodeInteraction {
        val scrollNode = fetchSemanticsNode()
        val panelNode = panel.fetchSemanticsNode()
        check(scrollNode.root === panelNode.root) { "Recipe scroll viewport and panel must share one root" }
        val root = scrollNode.root as ViewRootForTest
        compose.runOnIdle {
            val screenOrigin = IntArray(2).also(root.view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(root.view::getLocationInWindow)
            val offsetX = (screenOrigin[0] - windowOrigin[0]).toFloat()
            val offsetY = (screenOrigin[1] - windowOrigin[1]).toFloat()
            val viewport = scrollNode.boundsInWindow.translate(offsetX, offsetY)
            val panelBounds = panelNode.boundsInWindow.translate(offsetX, offsetY)
            val minimumViewportPx = 48f * root.view.resources.displayMetrics.density
            val tolerancePx = 1f
            assertTrue(
                "Recipe scroll viewport must fit one complete 48dp action before scrolling: " +
                    "viewport=$viewport minimumPx=$minimumViewportPx panel=$panelBounds",
                viewport.height >= minimumViewportPx - tolerancePx,
            )
            assertTrue(
                "Recipe scroll viewport must be wholly inside its visible panel: " +
                    "viewport=$viewport panel=$panelBounds",
                viewport.left >= panelBounds.left - tolerancePx &&
                    viewport.top >= panelBounds.top - tolerancePx &&
                    viewport.right <= panelBounds.right + tolerancePx &&
                    viewport.bottom <= panelBounds.bottom + tolerancePx,
            )
        }
        return this
    }

    private fun SemanticsNodeInteraction.assertCompleteActionInside(
        panel: SemanticsNodeInteraction,
        label: String,
    ): SemanticsNodeInteraction {
        assertIsDisplayed()
        val actionNode = fetchSemanticsNode()
        val panelNode = panel.fetchSemanticsNode()
        check(actionNode.root === panelNode.root) { "$label and recipe panel must share one root" }
        val root = actionNode.root as ViewRootForTest
        compose.runOnIdle {
            val screenOrigin = IntArray(2).also(root.view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(root.view::getLocationInWindow)
            val offsetX = (screenOrigin[0] - windowOrigin[0]).toFloat()
            val offsetY = (screenOrigin[1] - windowOrigin[1]).toFloat()
            val actionVisual = actionNode.visualBoundsOnScreen()
            val actionClipped = actionNode.boundsInWindow.translate(offsetX, offsetY)
            val actionTouch = actionNode.touchBoundsInRoot.translate(
                screenOrigin[0].toFloat(),
                screenOrigin[1].toFloat(),
            )
            val panelClipped = panelNode.boundsInWindow.translate(offsetX, offsetY)
            val minimumTouchPx = 48f * root.view.resources.displayMetrics.density
            val tolerancePx = 1f
            assertTrue(
                "$label must expose a complete 48dp touch target: touch=$actionTouch " +
                    "minimumPx=$minimumTouchPx",
                actionTouch.width >= minimumTouchPx - tolerancePx &&
                    actionTouch.height >= minimumTouchPx - tolerancePx,
            )
            assertTrue(
                "$label visual button must not be clipped: visual=$actionVisual clipped=$actionClipped",
                abs(actionVisual.left - actionClipped.left) <= tolerancePx &&
                    abs(actionVisual.top - actionClipped.top) <= tolerancePx &&
                    abs(actionVisual.right - actionClipped.right) <= tolerancePx &&
                    abs(actionVisual.bottom - actionClipped.bottom) <= tolerancePx,
            )
            assertTrue(
                "$label touch target must be wholly inside the visible recipe panel: " +
                    "touch=$actionTouch panel=$panelClipped",
                actionTouch.left >= panelClipped.left - tolerancePx &&
                    actionTouch.top >= panelClipped.top - tolerancePx &&
                    actionTouch.right <= panelClipped.right + tolerancePx &&
                    actionTouch.bottom <= panelClipped.bottom + tolerancePx,
            )
        }
        return this
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.visualBoundsOnScreen(): Rect {
        val position = positionOnScreen
        return Rect(
            position.x,
            position.y,
            position.x + size.width,
            position.y + size.height,
        )
    }

    private fun recipeState(
        lines: List<RecipeLine>,
        ingredients: List<IngredientRow>,
        recipe: Recipe? = activeRecipe.copy(lines = lines),
        recipesError: String? = null,
    ) = InventoryUiState(
        ingredientsLoaded = true,
        suppliersLoaded = true,
        ingredients = ingredients,
        recipeMenuItems = listOf(menuItem),
        selectedRecipeMenuItemId = menuItem.id,
        recipes = listOfNotNull(recipe),
        recipesError = recipesError,
        tab = InventoryTab.RECIPES,
    )

    private data class Calls(
        var retries: Int = 0,
        val linked: MutableList<MenuItemEntity> = mutableListOf(),
        val deactivated: MutableList<Recipe> = mutableListOf(),
        val edited: MutableList<Pair<Recipe, RecipeLine>> = mutableListOf(),
        val removed: MutableList<Pair<Recipe, RecipeLine>> = mutableListOf(),
        val added: MutableList<Recipe> = mutableListOf(),
    ) {
        fun isEmpty(): Boolean = retries == 0 && linked.isEmpty() && deactivated.isEmpty() &&
            edited.isEmpty() && removed.isEmpty() && added.isEmpty()
    }

    private companion object {
        const val PANEL_TAG = "recipe-detail-panel"
        val menuItem = MenuItemEntity(
            id = "menu-cappuccino",
            categoryId = "category-coffee",
            sku = "CAP-01",
            name = "Cappuccino",
            type = "drink",
            basePriceMinor = 32000,
            taxRate = 0.05,
            hsnCode = null,
            priceIncludesTax = true,
            isAvailable = true,
            description = null,
        )
        val ingredient = IngredientRow(
            id = "ingredient-beans",
            sku = "BEANS-01",
            name = "Arabica beans",
            baseUnit = "g",
        )
        val recipeLine = RecipeLine(
            id = "line-beans",
            ingredientId = ingredient.id,
            qty = 18.0,
            wastagePct = 0.05,
        )
        val activeRecipe = Recipe(
            id = "recipe-cappuccino",
            menuItemId = menuItem.id,
            name = "Cappuccino recipe",
            yieldQty = 1.0,
            version = 3,
            isActive = true,
            costMinor = 1800,
            lines = listOf(recipeLine),
        )
    }
}
