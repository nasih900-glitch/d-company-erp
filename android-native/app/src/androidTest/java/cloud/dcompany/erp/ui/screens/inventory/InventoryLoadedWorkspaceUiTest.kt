package cloud.dcompany.erp.ui.screens.inventory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.InventoryAccess
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class InventoryLoadedWorkspaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landscapeErrorOverflowKeepsStockAndRetryActionsReachableWithoutUnsafeMutations() {
        val target = confirmingIngredient()
        val state = mutableStateOf(errorState(target))
        val calls = Calls()
        val actions = actions(state, calls)

        render(state.value, actions, widthDp = 1280, heightDp = 800) { state.value }

        val workspace = compose.onNodeWithTag(INVENTORY_WORKSPACE_TAG)
        val root = compose.onNodeWithTag(ROOT_TAG)
        val targetRow = SemanticsMatcher.expectValue(
            SemanticsProperties.TestTag,
            inventoryIngredientRowTag(target.sku),
        )
        workspace.bringIntoView(targetRow)
        compose.onNode(targetRow)
            .assertCompleteActionInside(root, "Confirming ingredient row")
            .performClick()

        assertPaneHeight(minimumDp = 360)
        workspace.assert(hasScrollAction())

        workspace.performScrollToNode(hasText("Physical Audit Shop"))
        compose.onNodeWithText("Physical Audit Shop").performScrollTo().assertIsDisplayed()

        workspace.performScrollToNode(hasText("Pending stock changes"))
        val grnRetry = compose.onNodeWithTag(inventoryPendingGrnRetryTag("grn-rejected-1"))
        grnRetry.assertCompleteActionInside(root, "GRN Retry").assertIsEnabled().performClick()

        val adjustmentRetry = compose.onNodeWithTag(
            inventoryPendingAdjustmentRetryTag("adjustment-rejected-1"),
        )
        adjustmentRetry.assertCompleteActionInside(root, "Adjustment Retry")
            .assertIsEnabled().performClick()

        workspace.performScrollToNode(
            hasText("Create confirmation pending — details stay locked so a retry cannot create a duplicate."),
        )
        compose.onNodeWithText(
            "Create confirmation pending — details stay locked so a retry cannot create a duplicate.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("More ingredient actions"),
            ) and hasAnyAncestor(targetRow),
        ).assertDoesNotExist()
        compose.onNodeWithText("Adjust stock").assertDoesNotExist()

        workspace.performScrollToNode(hasText("Search ingredients or SKU"))
        compose.onNodeWithText("Search ingredients or SKU").performScrollTo().assertIsDisplayed()
        workspace.performScrollToNode(actionButton("Refresh"))
        compose.onNode(actionButton("Refresh"))
            .assertCompleteActionInside(root, "Refresh")
            .assertIsEnabled()

        compose.runOnIdle {
            state.value = state.value.copy(
                refreshError = null,
                branchesError = null,
            )
        }
        workspace.performScrollToNode(hasText("Search ingredients or SKU"))
        compose.onNodeWithText("Search ingredients or SKU").performScrollTo().assertIsDisplayed()
        assertPaneHeight(minimumDp = 360)

        compose.runOnIdle {
            assertEquals(target.sku, state.value.selectedSku)
            assertEquals(listOf("grn-rejected-1"), calls.grnRetries)
            assertEquals(listOf("adjustment-rejected-1"), calls.adjustmentRetries)
            assertEquals(listOf(target.sku), calls.selections)
            assertEquals(0, calls.refreshes)
            assertTrue(calls.openedDialogs.isEmpty())
        }
    }

    @Test
    fun healthyWideWorkspaceUsesRemainingHeightAndKeepsToolbarAndStockReachable() {
        verifyHealthyWorkspace(widthDp = 1280, heightDp = 800)
    }

    @Test
    fun healthyNarrowWorkspaceKeepsFinitePaneAndCompleteActionsReachable() {
        verifyHealthyWorkspace(widthDp = 420, heightDp = 800)
    }

    private fun verifyHealthyWorkspace(
        widthDp: Int,
        heightDp: Int,
    ) {
        val state = mutableStateOf(healthyState())
        val calls = Calls()
        render(state.value, actions(state, calls), widthDp, heightDp) { state.value }

        val workspace = compose.onNodeWithTag(INVENTORY_WORKSPACE_TAG)
        val root = compose.onNodeWithTag(ROOT_TAG)
        workspace.bringIntoView(hasText("Physical Audit Shop"))
        compose.onNodeWithText("Physical Audit Shop").assertIsDisplayed()
        workspace.bringIntoView(actionButton("Refresh"))
        compose.onNode(actionButton("Refresh"))
            .assertCompleteActionInside(root, "Refresh")
            .assertIsEnabled()
        workspace.bringIntoView(hasText("Search ingredients or SKU"))
        compose.onNodeWithText("Search ingredients or SKU").assertIsDisplayed()
        workspace.bringIntoView(hasText("Healthy ingredient") and hasClickAction())
        compose.onNode(hasText("Healthy ingredient") and hasClickAction())
            .assertCompleteActionInside(root, "Healthy ingredient row")
            .performClick()

        compose.runOnIdle {
            assertEquals("HEALTHY-1", state.value.selectedSku)
            assertEquals(listOf("HEALTHY-1"), calls.selections)
            assertEquals(0, calls.refreshes)
            assertTrue(calls.grnRetries.isEmpty() && calls.adjustmentRetries.isEmpty())
            assertTrue(calls.openedDialogs.isEmpty())
        }
    }

    private fun render(
        initialState: InventoryUiState,
        actions: InventoryPresentationActions,
        widthDp: Int,
        heightDp: Int,
        state: () -> InventoryUiState = { initialState },
    ) {
        compose.setContent {
            DCompanyTheme {
                Box(
                    Modifier.width(widthDp.dp).height(heightDp.dp).testTag(ROOT_TAG),
                ) {
                    InventoryLoadedPresentation(
                        state = state(),
                        access = InventoryAccess(
                            canManageInventory = true,
                            canMakeLargeAdjustment = true,
                            canManageCosting = true,
                        ),
                        presentation = WorkspaceFeatureProfiles.Active.presentationPolicy(),
                        actions = actions,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun actions(
        state: androidx.compose.runtime.MutableState<InventoryUiState>,
        calls: Calls,
    ) = InventoryPresentationActions(
        retry = { calls.refreshes++ },
        retryGrn = calls.grnRetries::add,
        retryAdjustment = calls.adjustmentRetries::add,
        openDialog = calls.openedDialogs::add,
        selectIngredient = { ingredient ->
            calls.selections += ingredient?.sku
            state.value = state.value.copy(selectedSku = ingredient?.sku)
        },
    )

    private fun actionButton(label: String): SemanticsMatcher =
        hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(
            SemanticsProperties.Role,
            Role.Button,
        )

    private fun SemanticsNodeInteraction.bringIntoView(matcher: SemanticsMatcher) {
        if (fetchSemanticsNode().config.contains(SemanticsActions.ScrollBy)) {
            performScrollToNode(matcher)
        }
    }

    private fun assertPaneHeight(minimumDp: Int) {
        val pane = compose.onNodeWithTag(INVENTORY_PANE_TAG).fetchSemanticsNode()
        val density = (pane.root as ViewRootForTest).view.resources.displayMetrics.density
        val minimumPx = minimumDp * density
        compose.runOnIdle {
            assertTrue(
                "Inventory pane must be at least ${minimumDp}dp: ${pane.size.height}px",
                pane.size.height >= minimumPx - 1f,
            )
        }
    }

    private fun SemanticsNodeInteraction.assertCompleteActionInside(
        rootNode: SemanticsNodeInteraction,
        label: String,
    ): SemanticsNodeInteraction {
        assertIsDisplayed()
        val action = fetchSemanticsNode()
        val root = rootNode.fetchSemanticsNode()
        check(action.root === root.root) { "$label and inventory root must share one window" }
        val viewRoot = action.root as ViewRootForTest
        compose.runOnIdle {
            val screenOrigin = IntArray(2).also(viewRoot.view::getLocationOnScreen)
            val windowOrigin = IntArray(2).also(viewRoot.view::getLocationInWindow)
            val offsetX = (screenOrigin[0] - windowOrigin[0]).toFloat()
            val offsetY = (screenOrigin[1] - windowOrigin[1]).toFloat()
            val visual = action.visualBoundsOnScreen()
            val clipped = action.boundsInWindow.translate(offsetX, offsetY)
            val touch = action.touchBoundsInRoot.translate(
                screenOrigin[0].toFloat(),
                screenOrigin[1].toFloat(),
            )
            val visibleRoot = root.boundsInWindow.translate(offsetX, offsetY)
            val minimumTouchPx = 48f * viewRoot.view.resources.displayMetrics.density
            val tolerancePx = 1f
            assertTrue(
                "$label must expose a complete 48dp touch target: $touch",
                touch.width >= minimumTouchPx - tolerancePx &&
                    touch.height >= minimumTouchPx - tolerancePx,
            )
            assertTrue(
                "$label must not be clipped: visual=$visual clipped=$clipped",
                abs(visual.left - clipped.left) <= tolerancePx &&
                    abs(visual.top - clipped.top) <= tolerancePx &&
                    abs(visual.right - clipped.right) <= tolerancePx &&
                    abs(visual.bottom - clipped.bottom) <= tolerancePx,
            )
            assertTrue(
                "$label must be wholly inside the visible inventory root: touch=$touch root=$visibleRoot",
                touch.left >= visibleRoot.left - tolerancePx &&
                    touch.top >= visibleRoot.top - tolerancePx &&
                    touch.right <= visibleRoot.right + tolerancePx &&
                    touch.bottom <= visibleRoot.bottom + tolerancePx,
            )
        }
        return this
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.visualBoundsOnScreen(): Rect {
        val position = positionOnScreen
        return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
    }

    private fun errorState(target: IngredientRow) = InventoryUiState(
        everSynced = true,
        ingredientsLoaded = true,
        suppliersLoaded = true,
        refreshError = "Could not refresh inventory data: saved stock remains available.",
        branchesError = "Could not refresh branches: saved branch remains available.",
        ingredients = listOf(target, healthyIngredient()),
        suppliers = listOf(
            SupplierRow(id = "supplier-1", name = "Audit supplier", contact = "audit@example.test"),
        ),
        branches = listOf(Branch(id = "branch-1", name = "Physical Audit Shop")),
        branchesLoaded = true,
        branchId = "branch-1",
        pendingGrns = listOf(
            PendingGrnRow(
                localId = "grn-rejected-1",
                supplierName = "Audit supplier",
                rejected = true,
                error = "Synthetic receipt rejection",
            ),
        ),
        pendingAdjustments = listOf(
            PendingAdjustmentRow(
                localId = "adjustment-rejected-1",
                ingredientName = target.name,
                unit = target.baseUnit,
                qtyDelta = -0.5,
                rejected = true,
                error = "Synthetic adjustment rejection",
            ),
        ),
    )

    private fun healthyState() = InventoryUiState(
        everSynced = true,
        ingredientsLoaded = true,
        suppliersLoaded = true,
        ingredients = listOf(healthyIngredient()),
        suppliers = listOf(
            SupplierRow(id = "supplier-1", name = "Audit supplier", contact = "audit@example.test"),
        ),
        branches = listOf(Branch(id = "branch-1", name = "Physical Audit Shop")),
        branchesLoaded = true,
        branchId = "branch-1",
    )

    private fun confirmingIngredient() = IngredientRow(
        id = "local:ingredient-confirming",
        sku = "INV-AUDIT-20260912",
        name = "Inventory Audit Ingredient Initial",
        baseUnit = "g",
        currentQty = 0.0,
        reorderThreshold = 3.0,
        reorderQty = 18.0,
        valuationMinor = null,
        pendingLocalId = "ingredient-write-1",
        localWriteId = "ingredient-write-1",
        createConfirmationPending = true,
    )

    private fun healthyIngredient() = IngredientRow(
        id = "ingredient-healthy-1",
        sku = "HEALTHY-1",
        name = "Healthy ingredient",
        baseUnit = "unit",
        currentQty = 10.0,
        reorderThreshold = 3.0,
        reorderQty = 6.0,
        avgCostMinor = 100L,
        valuationMinor = 1_000L,
    )

    private data class Calls(
        var refreshes: Int = 0,
        val grnRetries: MutableList<String> = mutableListOf(),
        val adjustmentRetries: MutableList<String> = mutableListOf(),
        val selections: MutableList<String?> = mutableListOf(),
        val openedDialogs: MutableList<InventoryDialog> = mutableListOf(),
    )

    private companion object {
        const val ROOT_TAG = "inventory-workspace-test-root"
    }
}
