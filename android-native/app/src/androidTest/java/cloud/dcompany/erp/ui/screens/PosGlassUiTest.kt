package cloud.dcompany.erp.ui.screens

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuModifierGroupEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundImage
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PosGlassDisplayArgument = "dcompany.testDisplayId"
private const val PosGlassSourceArgument = "dcompany.sourceInventorySha256"
private const val PosGlassTargetArgument = "dcompany.targetApkSha256"
private const val PosGlassTestArgument = "dcompany.testApkSha256"
private const val PosGlassRunArgument = "dcompany.captureRunId"

private val RequestedPosGlassDisplay: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(PosGlassDisplayArgument)?.let {
        val requested = it.toIntOrNull()
        require(requested in setOf(0, 2, 5, 6)) { "Only verified HD displays 0, 2, 5 and 6 are supported: $it" }
        requested
    }
}

private fun <A : ComponentActivity> ActivityScenarioRule<A>.posGlassActivity(): A {
    var activity: A? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity).also { current ->
        if (RequestedPosGlassDisplay == 0) {
            val display = checkNotNull(current.display)
            check(display.displayId == 0 && display.mode.physicalWidth == 2_560 &&
                display.mode.physicalHeight == 1_600 && current.resources.displayMetrics.densityDpi == 320) {
                "Display 0 POS proof requires the verified 2560x1600px, 320dpi host"
            }
        }
    }
}

private fun createPosGlassRule(): AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> {
    val rule = if (RequestedPosGlassDisplay == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(RequestedPosGlassDisplay!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(rule) { it.posGlassActivity() }
}

@RunWith(AndroidJUnit4::class)
class PosGlassUiTest {
    @get:Rule val compose = createPosGlassRule()

    @Test
    fun nightWideRendersRealCatalogAndCartInSharedWorkspace() {
        val fixture = renderPos(1280, 800, 1f, AppearancePreference.Night)
        awaitWorkspaceGlass()
        capture("pos-glass-night-catalog-cart-wide", 1280, 800, 1f, "Night")
        assertWideHierarchy()
        assertWidePaneGeometry("night 1280x800")
        assertFullTarget(
            compose.onNodeWithTag("pos-product-item-01"),
            "night product tile",
            viewportTag = "pos-catalog",
        ).assertHasClickAction()
        assertFullTarget(
            compose.onNodeWithTag("pos-take-payment"),
            "night checkout",
            viewportTag = "pos-cart",
        ).assertIsEnabled()
        compose.runOnIdle { assertEquals(0, fixture.writeCalls) }
    }

    @Test
    fun masterAspectKeepsCatalogDominantAndMoneyActionsReachable() {
        val viewportWidth = mutableStateOf(1280)
        val fixture = renderPos(
            1280,
            563,
            1f,
            AppearancePreference.Night,
            cartItemCount = 2,
            viewportWidthDp = viewportWidth,
        )
        awaitWorkspaceGlass()
        capture("pos-glass-night-master-aspect-1280x563", 1280, 563, 1f, "Night")
        assertWideHierarchy()
        assertWidePaneGeometry("night master aspect")
        assertFourColumnCatalog("night master aspect")
        assertPaymentFooterTextFits()
        assertEquals(
            "the shared shell already identifies POS; the local context bar must not duplicate the route title",
            0,
            compose.onAllNodes(
                hasText("POS") and hasAnyAncestor(hasTestTag("pos-context-bar")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        assertFullTarget(
            compose.onNode(
                hasText("Status") and hasClickAction() and hasAnyAncestor(hasTestTag("pos-context-bar")),
            ),
            "master-aspect status action",
            viewportTag = "pos-context-bar",
        )
        assertFullTarget(
            compose.onNode(
                hasText("Receipts") and hasClickAction() and hasAnyAncestor(hasTestTag("pos-context-bar")),
            ),
            "master-aspect receipts action",
            viewportTag = "pos-context-bar",
        )
        compose.onNode(
            hasText("Total estimate") and hasAnyAncestor(hasTestTag("pos-cart-total")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNode(
            hasText("₹32.00") and hasAnyAncestor(hasTestTag("pos-cart-total")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertFullyContained(
            compose.onNodeWithTag("pos-cart-total"),
            "master-aspect cart total",
            viewportTag = "pos-cart",
        )
        assertFullTarget(
            compose.onNodeWithTag("pos-inline-cash-received"),
            "master-aspect cash field",
            viewportTag = "pos-cart",
        )
        assertFullTarget(
            compose.onNodeWithTag("pos-take-payment"),
            "master-aspect checkout",
            viewportTag = "pos-cart",
        )
        compose.onNodeWithTag("pos-inline-cash-received").performClick()
        compose.onNodeWithTag("pos-inline-cash-received").performTextReplacement("100")
        compose.onNodeWithTag("pos-inline-cash-received").assertIsFocused()
        compose.runOnIdle { viewportWidth.value = 1600 }
        compose.onNodeWithTag("pos-inline-cash-received")
            .assertIsFocused()
            .assertTextContains("100")
        assertPaymentFooterTextFits()
        compose.runOnIdle { viewportWidth.value = 1280 }
        compose.onNodeWithTag("pos-inline-cash-received")
            .assertIsFocused()
            .assertTextContains("100")
        assertPaymentFooterTextFits()
        compose.runOnIdle { assertEquals(0, fixture.writeCalls) }
    }

    @Test
    fun dayWideUsesCurrentCatalogCallbacksAndKeepsMoneyActionsReachable() {
        val fixture = renderPos(
            1280, 800, 1f, AppearancePreference.Day,
            backgrounds = mutableStateOf(
                WorkspaceBackgroundUiState(workspace = controlledWorkspaceImage("pos-day-bright.jpg", 42L)),
            ),
        )
        awaitWorkspaceGlass()
        capture("pos-glass-day-bright-catalog-cart-wide", 1280, 800, 1f, "Day")
        assertFullTarget(
            compose.onNodeWithTag("pos-receipts-action"),
            "day receipts action",
            viewportTag = "pos-context-bar",
        ).assertIsEnabled()
        assertRenderedTextContrast("Receipts", isDay = true, parentTag = "pos-receipts-action")
        assertFullTarget(
            compose.onNodeWithTag("pos-product-item-01"),
            "day product tile",
            viewportTag = "pos-catalog",
        ).performClick()
        compose.runOnIdle { assertEquals(listOf("item-01"), fixture.addedIds) }
        compose.onNodeWithTag("pos-cart-total").assertIsDisplayed()
        assertFullTarget(
            compose.onNodeWithTag("pos-take-payment"),
            "day checkout",
            viewportTag = "pos-cart",
        )
    }

    @Test
    fun brightSceneViewOnlyKeepsEssentialProductTextReadableAndBlocksWrites() {
        val fixture = renderPos(
            1280, 800, 1f, AppearancePreference.Day,
            canWrite = false,
            backgrounds = mutableStateOf(
                WorkspaceBackgroundUiState(workspace = controlledWorkspaceImage("pos-bright.jpg", 41L)),
            ),
        )
        awaitWorkspaceGlass()
        val tile = compose.onNodeWithContentDescription("Product 01, view only")
            .assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        assertFullTarget(
            compose.onNodeWithTag("pos-product-item-01"),
            "day view-only product tile",
            viewportTag = "pos-catalog",
        )
        assertRenderedTextContrast("Product 01", isDay = true)
        assertRenderedTextContrast("₹10.00", isDay = true)
        tile.performClick()
        compose.onNode(
            hasText("Product 01") and hasAnyAncestor(hasTestTag("pos-product-item-01")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNode(
            hasText("Product 01") and hasAnyAncestor(hasTestTag("pos-cart")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNode(
            hasText("₹10.00") and hasAnyAncestor(hasTestTag("pos-product-item-01")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNode(
            hasText("₹10.00") and hasAnyAncestor(hasTestTag("pos-cart")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag("pos-take-payment").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, fixture.writeCalls) }
    }

    @Test
    fun compactLargeTextKeepsSearchCashTotalAndCheckoutReachableWithKeyboardFocusTransfer() {
        val fixture = renderPos(600, 800, 1.3f, AppearancePreference.Night)
        awaitWorkspaceGlass()
        capture("pos-glass-compact-font1_3", 600, 800, 1.3f, "Night")
        assertFullTarget(
            compose.onNodeWithTag("pos-receipts-action"),
            "compact receipts action",
            viewportTag = "pos-context-bar",
        ).assertIsEnabled()
        assertPaymentFooterTextFits()
        val search = assertFullTarget(
            compose.onNodeWithText("Search items…"),
            "compact search",
            viewportTag = "pos-catalog",
        )
        search.performClick()
        search.performTextReplacement("Product 01")
        val recomposedSearch = assertFullTarget(
            compose.onNode(
                hasSetTextAction() and hasAnyAncestor(hasTestTag("pos-catalog")),
                useUnmergedTree = true,
            ),
            "recomposed compact search",
            viewportTag = "pos-catalog",
        ).assertIsFocused()
        compose.onNodeWithContentDescription("Add Product 01, ₹10.00, Drinks to the current order")
            .assertIsDisplayed()
        val cash = assertFullTarget(
            compose.onNodeWithTag("pos-inline-cash-received"),
            "compact cash field",
            viewportTag = "pos-cart",
        ).performClick()
        recomposedSearch.assertIsNotFocused()
        cash.performTextReplacement("100")
        compose.onNodeWithTag("pos-cart-total").assertIsDisplayed()
        assertFullTarget(
            compose.onNodeWithTag("pos-take-payment"),
            "compact checkout",
            viewportTag = "pos-cart",
        )
        compose.runOnIdle { assertEquals(0, fixture.writeCalls) }
    }

    @Test
    fun physicalGridAndCartScrollRetainKeysAndDoNotDispatchWrites() {
        val fixture = renderPos(
            1280,
            800,
            1f,
            AppearancePreference.Night,
            cartItemCount = 16,
        )
        awaitWorkspaceGlass()
        val firstProduct = compose.onNodeWithContentDescription(
            "Add Product 01, ₹10.00, Drinks to the current order",
        ).assertIsDisplayed()
        val initialTop = firstProduct.fetchSemanticsNode().boundsInRoot.top
        val catalogScroll = verticalScrollOwner("pos-catalog")
        val initialCatalogScroll = verticalScrollValue(catalogScroll)
        catalogScroll.performTouchInput {
            swipe(center.copy(y = bottom - 24f), center.copy(y = top + 180f), 450L)
        }
        compose.waitForIdle()
        assertTrue(
            "physical grid swipe must advance the actual grid owner",
            verticalScrollValue(catalogScroll) > initialCatalogScroll + 1f,
        )
        val firstAfter = compose.onAllNodesWithContentDescription(
            "Add Product 01, ₹10.00, Drinks to the current order",
        ).fetchSemanticsNodes()
        assertTrue("physical grid swipe must move or virtualize the first keyed item", firstAfter.isEmpty() || firstAfter.single().boundsInRoot.top < initialTop - 1f)
        val firstCartItem = compose.onNode(
            hasText("Product 01") and hasAnyAncestor(hasTestTag("pos-cart")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val initialCartTop = firstCartItem.fetchSemanticsNode().boundsInRoot.top
        val cartScroll = verticalScrollOwner("pos-cart")
        val initialCartScroll = verticalScrollValue(cartScroll)
        cartScroll.performTouchInput {
            swipe(center.copy(y = bottom - 28f), center.copy(y = top + 150f), 450L)
        }
        compose.waitForIdle()
        val forwardCartScroll = verticalScrollValue(cartScroll)
        assertTrue(
            "physical cart swipe must advance the actual cart list owner",
            forwardCartScroll > initialCartScroll + 1f,
        )
        val afterForward = compose.onAllNodes(
            hasText("Product 01") and hasAnyAncestor(hasTestTag("pos-cart")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(
            "physical cart swipe must move or virtualize the first keyed line",
            afterForward.isEmpty() || afterForward.single().boundsInRoot.top < initialCartTop - 1f,
        )
        cartScroll.performTouchInput {
            swipe(center.copy(y = top + 150f), center.copy(y = bottom - 28f), 450L)
        }
        compose.waitForIdle()
        assertTrue(
            "physical reverse cart swipe must return the actual cart list owner",
            verticalScrollValue(cartScroll) < forwardCartScroll - 1f,
        )
        val returnedCartTop = compose.onNode(
            hasText("Product 01") and hasAnyAncestor(hasTestTag("pos-cart")),
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "physical reverse cart swipe must return the first keyed line",
            returnedCartTop <= initialCartTop + 1f,
        )
        compose.runOnIdle { assertEquals(0, fixture.writeCalls) }
    }

    @Test
    fun actualCatalogUsesBlankMediaOnlyAndExposesNoScannerOrPhotoContract() {
        val fixture = renderPos(
            1280,
            800,
            1f,
            AppearancePreference.Night,
            canWrite = false,
            catalogItemCount = 32,
        )
        awaitWorkspaceGlass()
        assertRenderedTextContrast("Product 01", isDay = false)
        assertRenderedTextContrast("₹10.00", isDay = false)
        assertEquals(0, compose.onAllNodesWithText("Scan").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithContentDescription("Product photo").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Product 01, view only")
            .assertIsDisplayed()
        val catalogScroll = verticalScrollOwner("pos-catalog")
        val initialScroll = verticalScrollValue(catalogScroll)
        val initialLastNodes = compose.onAllNodesWithContentDescription("Product 32, view only")
            .fetchSemanticsNodes()
        assertTrue(
            "the deterministic 32-item fixture must begin with its final tile outside the catalogue viewport",
            initialLastNodes.isEmpty() || initialLastNodes.none { isFullyInside(it.boundsInRoot, "pos-catalog") },
        )
        for (attempt in 0 until 8) {
            val nodes = compose.onAllNodesWithContentDescription("Product 32, view only")
                .fetchSemanticsNodes()
            if (nodes.size == 1 && isFullyInside(nodes.single().boundsInRoot, "pos-catalog")) {
                break
            }
            catalogScroll.performTouchInput {
                swipe(center.copy(y = bottom - 24f), center.copy(y = top + 180f), 300L)
            }
            compose.waitForIdle()
        }
        assertTrue(
            "physical catalogue scroll must advance before the virtualized final item is asserted",
            verticalScrollValue(catalogScroll) > initialScroll + 1f,
        )
        assertFullTarget(
            compose.onNodeWithContentDescription("Product 32, view only"),
            "final blank-media catalogue tile",
            viewportTag = "pos-catalog",
        ).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, fixture.writeCalls) }
    }

    @Test
    fun emptyCatalogUsesTruthfulFirstSyncStateWithoutRemovingCartPane() {
        renderPos(1280, 800, 1f, AppearancePreference.Night, emptyCatalog = true)
        awaitWorkspaceGlass()
        capture("pos-glass-empty-catalog-wide", 1280, 800, 1f, "Night")
        compose.onNodeWithText("No menu on this tablet yet").assertIsDisplayed()
        compose.onNodeWithText("Current order").assertIsDisplayed()
        compose.onNodeWithTag("pos-cart-total").assertIsDisplayed()
    }

    @Test
    fun configurationDialogUsesNativeFallbackAndPreservesExactCallbackBoundary() {
        val fixture = renderPos(1280, 800, 1f, AppearancePreference.Night, configurable = true)
        awaitWorkspaceGlass()
        assertFullTarget(
            compose.onNodeWithTag("pos-product-item-01"),
            "configurable product tile",
            viewportTag = "pos-catalog",
        ).performClick()
        compose.onNodeWithText("Customise Product 01").assertIsDisplayed()
        assertFullTarget(
            compose.onNode(
                hasText("ADD · ₹10.00") and hasClickAction() and hasAnyAncestor(isDialog()),
            ),
            "configuration confirmation",
            viewport = compose.onNode(isDialog()),
        ).assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.configuredCalls)
            assertEquals(0, fixture.addedIds.size)
        }
    }

    private data class Fixture(
        val addedIds: MutableList<String> = mutableListOf(),
        var configuredCalls: Int = 0,
        var otherWrites: Int = 0,
    ) {
        val writeCalls: Int get() = addedIds.size + configuredCalls + otherWrites
    }

    private fun renderPos(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        appearance: AppearancePreference,
        canWrite: Boolean = true,
        configurable: Boolean = false,
        emptyCatalog: Boolean = false,
        cartItemCount: Int = 8,
        catalogItemCount: Int = 16,
        viewportWidthDp: MutableState<Int>? = null,
        backgrounds: MutableState<WorkspaceBackgroundUiState> = mutableStateOf(WorkspaceBackgroundUiState()),
    ): Fixture {
        val fixture = Fixture()
        val category = MenuCategoryEntity("drinks", "Drinks", 1, true)
        val items = (1..catalogItemCount).map { index -> menuItem(index) }
        val cart = items.take(cartItemCount).mapIndexed { index, item ->
            CartLine("line-${index + 1}", item, qty = index % 2 + 1, unitPriceMinor = item.basePriceMinor)
        }
        val state = PosUiState(
            categories = if (emptyCatalog) emptyList() else listOf(category),
            items = if (emptyCatalog) emptyList() else items,
            operationalCategories = if (emptyCatalog) emptyList() else listOf(category),
            operationalItems = if (emptyCatalog) emptyList() else items,
            variants = if (configurable) listOf(MenuVariantEntity("large", "item-01", "Large", 500L, 1, true)) else emptyList(),
            modifierGroups = if (configurable) listOf(MenuModifierGroupEntity("extras", "item-01", "Extras", 0, 1, 1, true)) else emptyList(),
            modifiers = if (configurable) listOf(MenuModifierEntity("ice", "item-01", "extras", "Ice", 0L, 1, 1, true)) else emptyList(),
            cart = cart, online = true, menuEmpty = emptyCatalog, everSynced = !emptyCatalog,
            activeShiftId = "shift-1", canCollectPayment = true,
            draftState = SyncState.DRAFT, draftLocalId = "draft-pos-glass", draftRevision = 1L,
        )
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            val resolvedWidthDp = viewportWidthDp?.value ?: widthDp
            val hostConfiguration = LocalConfiguration.current
            val configuration = remember(resolvedWidthDp, heightDp, fontScale, hostConfiguration) {
                android.content.res.Configuration(hostConfiguration).apply {
                    screenWidthDp = resolvedWidthDp
                    screenHeightDp = heightDp
                    smallestScreenWidthDp = minOf(resolvedWidthDp, heightDp)
                    this.fontScale = fontScale
                    orientation = if (resolvedWidthDp >= heightDp) android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    else android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalConfiguration provides configuration,
            ) {
                DCompanyTheme(appearance) {
                    Surface {
                        Box(Modifier.width(resolvedWidthDp.dp).height(heightDp.dp).testTag("pos-glass-fixture-root")) {
                            WorkspaceScaffold(
                                destinations = listOf(Destination.Dashboard, Destination.Gaming, Destination.Pos, Destination.Shift, Destination.Inventory, Destination.Reports, Destination.Settings),
                                currentDestination = Destination.Pos,
                                employeeName = "Fixture cashier",
                                locationLabel = "Main Shop",
                                connectivityProblem = SyncAvailabilityProblem.NONE,
                                outboxWorkStatus = OutboxWorkStatus(), syncing = false,
                                canChangeTill = false, appearancePreference = appearance,
                                onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                                backgroundState = backgrounds.value,
                            ) { _, _ ->
                                PosScreen(
                                    state = state, recentReceipts = emptyList(), canonicalReceipts = emptyList(),
                                    receiptHistoryHasMore = false, receiptHistoryLoading = false,
                                    receiptHistoryError = null, unacknowledgedReceipt = null,
                                    access = PosAccess(canCreateAndCollect = canWrite, canVoid = canWrite, canApplyDiscount = canWrite),
                                    shiftAccess = ShiftAccess(), onAccessChanged = {},
                                    onAdd = { fixture.addedIds += it.id },
                                    onAddConfigured = { _, _, _, _ -> fixture.configuredCalls += 1 },
                                    onRemove = { fixture.otherWrites += 1 }, onIncrementLine = { fixture.otherWrites += 1 },
                                    onDecrementLine = { fixture.otherWrites += 1 }, onSelectCategory = {},
                                    onClearCart = { fixture.otherWrites += 1 }, onUpdateDraftDetails = { _, _, _, _ -> fixture.otherWrites += 1 },
                                    onRefresh = {}, onPrepareDirectCheckout = { fixture.otherWrites += 1 },
                                    onContinueDirectCheckout = {}, onDismissDirectCheckout = {}, onConfirmDirectZero = {},
                                    onRedeemDirectPoints = {}, onRedeemDirectReward = {},
                                    onCapture = { _, _, _ -> fixture.otherWrites += 1; true },
                                    onCaptureSplit = { _, _ -> fixture.otherWrites += 1; true },
                                    onRetryRejectedSale = {}, onRetryHeldPayment = {}, onPrepareHeldOrder = {},
                                    onUpdateHeldOrderDiscount = { _, _ -> }, onContinueHeldOrder = {},
                                    onConfirmHeldOrder = { _, _, _ -> true }, onConfirmHeldOrderSplit = { _, _ -> true },
                                    onConfirmHeldOrderZero = {}, onVoidOrder = { _, _ -> },
                                    onDismissHeldOrderReview = {}, onDismissHeldOrder = {}, onDismissNotice = {},
                                    onAcknowledgeReceipt = {}, onRefreshReceiptHistory = {}, onLoadMoreReceiptHistory = {},
                                    onOpenCanonicalReceipt = {}, onFocusOldestOverdue = {}, onSnoozeOverdue = {},
                                    onUnmuteOverdue = {}, onDismissHeldFocus = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        return fixture
    }

    private fun assertWideHierarchy() {
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
        compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
        compose.onNodeWithTag("pos-catalog").assertIsDisplayed()
        compose.onNodeWithTag("pos-cart").assertIsDisplayed()
        val blankMedia = compose.onNodeWithTag(
            "pos-product-media-empty-item-01",
            useUnmergedTree = true,
        ).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(
            "blank media width",
            32f,
            (blankMedia.right - blankMedia.left).value,
            0.5f,
        )
        assertEquals(
            "blank media height",
            32f,
            (blankMedia.bottom - blankMedia.top).value,
            0.5f,
        )
    }

    private fun assertWidePaneGeometry(diagnostic: String) {
        val catalog = compose.onNodeWithTag("pos-catalog", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val cart = compose.onNodeWithTag("pos-cart", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val context = compose.onNodeWithTag("pos-context-bar", useUnmergedTree = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val catalogWidth = catalog.right.value - catalog.left.value
        val cartWidth = cart.right.value - cart.left.value
        val contextHeight = context.bottom.value - context.top.value
        val contentWidth = catalogWidth + cartWidth
        val catalogRatio = catalogWidth / contentWidth
        val gap = cart.left.value - catalog.right.value
        assertTrue("$diagnostic catalogue ratio=$catalogRatio", catalogRatio in 0.64f..0.68f)
        assertTrue("$diagnostic cart must retain its readable minimum: $cart", cartWidth >= 320f)
        assertTrue("$diagnostic pane gap=$gap", gap in 12f..16f)
        assertTrue("$diagnostic context bar must stay compact: $context", contextHeight in 48f..64f)
        assertTrue("$diagnostic panes must share their top edge", kotlin.math.abs(catalog.top.value - cart.top.value) <= 0.5f)
    }

    private fun assertFourColumnCatalog(diagnostic: String) {
        val firstRow = (1..4).map { index ->
            compose.onNodeWithTag("pos-product-item-${index.toString().padStart(2, '0')}")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        val fifth = compose.onNodeWithTag("pos-product-item-05")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val firstTop = firstRow.first().top.value
        assertTrue(
            "$diagnostic first four products must share one row: $firstRow",
            firstRow.all { kotlin.math.abs(it.top.value - firstTop) <= 0.5f },
        )
        assertTrue(
            "$diagnostic fifth product must start the second row: first=$firstRow fifth=$fifth",
            fifth.top.value >= firstRow.maxOf { it.bottom.value } + 1f,
        )
    }

    private fun awaitWorkspaceGlass() {
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1
        }
    }

    private fun verticalScrollOwner(viewportTag: String): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyAncestor(hasTestTag(viewportTag)),
        useUnmergedTree = true,
    )

    private fun verticalScrollValue(owner: SemanticsNodeInteraction): Float =
        owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun isFullyInside(
        target: androidx.compose.ui.geometry.Rect,
        viewportTag: String,
    ): Boolean {
        val viewport = compose.onNodeWithTag(viewportTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return target.left >= viewport.left - 0.5f &&
            target.top >= viewport.top - 0.5f &&
            target.right <= viewport.right + 0.5f &&
            target.bottom <= viewport.bottom + 0.5f
    }

    private fun assertFullTarget(
        target: SemanticsNodeInteraction,
        diagnostic: String,
        viewportTag: String? = null,
        viewport: SemanticsNodeInteraction? = null,
    ): SemanticsNodeInteraction {
        check(viewportTag == null || viewport == null)
        val displayed = target.assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val targetBounds = displayed.getUnclippedBoundsInRoot()
        val rootBounds = compose.onNodeWithTag("pos-glass-fixture-root", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val viewportBounds = when {
            viewport != null -> viewport.getUnclippedBoundsInRoot()
            viewportTag != null -> compose.onNodeWithTag(viewportTag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            else -> rootBounds
        }
        val visibleLeft = max(rootBounds.left.value, viewportBounds.left.value)
        val visibleTop = max(rootBounds.top.value, viewportBounds.top.value)
        val visibleRight = min(rootBounds.right.value, viewportBounds.right.value)
        val visibleBottom = min(rootBounds.bottom.value, viewportBounds.bottom.value)
        assertTrue("$diagnostic must have a non-empty visible viewport", visibleRight > visibleLeft && visibleBottom > visibleTop)
        assertTrue(
            "$diagnostic must be fully inside root and viewport: target=$targetBounds root=$rootBounds viewport=$viewportBounds",
            targetBounds.left.value >= visibleLeft - 0.5f &&
                targetBounds.top.value >= visibleTop - 0.5f &&
                targetBounds.right.value <= visibleRight + 0.5f &&
                targetBounds.bottom.value <= visibleBottom + 0.5f,
        )
        return displayed
    }

    private fun assertFullyContained(
        target: SemanticsNodeInteraction,
        diagnostic: String,
        viewportTag: String,
    ): SemanticsNodeInteraction {
        val displayed = target.assertIsDisplayed()
        val targetBounds = displayed.getUnclippedBoundsInRoot()
        val rootBounds = compose.onNodeWithTag("pos-glass-fixture-root", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val viewportBounds = compose.onNodeWithTag(viewportTag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "$diagnostic must have positive rendered bounds: $targetBounds",
            targetBounds.right - targetBounds.left > 0.dp && targetBounds.bottom - targetBounds.top > 0.dp,
        )
        val visibleLeft = max(rootBounds.left.value, viewportBounds.left.value)
        val visibleTop = max(rootBounds.top.value, viewportBounds.top.value)
        val visibleRight = min(rootBounds.right.value, viewportBounds.right.value)
        val visibleBottom = min(rootBounds.bottom.value, viewportBounds.bottom.value)
        assertTrue("$diagnostic must have a non-empty visible viewport", visibleRight > visibleLeft && visibleBottom > visibleTop)
        assertTrue(
            "$diagnostic must be fully inside root and viewport: target=$targetBounds root=$rootBounds viewport=$viewportBounds",
            targetBounds.left.value >= visibleLeft - 0.5f &&
                targetBounds.top.value >= visibleTop - 0.5f &&
                targetBounds.right.value <= visibleRight + 0.5f &&
                targetBounds.bottom.value <= visibleBottom + 0.5f,
        )
        return displayed
    }

    private fun assertPaymentFooterTextFits() {
        listOf("Cash", "UPI", "Split").forEach { label ->
            assertSingleLineText(
                compose.onNode(
                    hasText(label) and hasAnyAncestor(hasTestTag("pos-payment-method-selector")),
                    useUnmergedTree = true,
                ),
                label,
                "pos-payment-method-selector",
            )
        }
        assertSingleLineText(
            compose.onNode(
                hasText("Cash received") and hasAnyAncestor(hasTestTag("pos-inline-cash-received")),
                useUnmergedTree = true,
            ),
            "Cash received",
            "pos-cart",
            ownerTag = "pos-inline-cash-received",
            nonOverlappingTag = "pos-payment-method-selector",
        )
    }

    private fun assertSingleLineText(
        interaction: SemanticsNodeInteraction,
        expected: String,
        viewportTag: String,
        ownerTag: String? = null,
        nonOverlappingTag: String? = null,
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        interaction.assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$expected must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals("$expected must remain one line", 1, layout.lineCount)
        assertEquals("$expected final line end", expected.length, layout.getLineEnd(0))
        assertEquals(
            "$expected final visible end",
            expected.length,
            layout.getLineEnd(0, visibleEnd = true),
        )
        assertFalse("$expected height overflow", layout.didOverflowHeight)
        assertFalse("$expected must not be ellipsized", layout.isLineEllipsized(0))
        assertTrue("$expected line left", layout.getLineLeft(0) >= -1f)
        assertTrue("$expected line right", layout.getLineRight(0) <= layout.size.width + 1f)
        assertTrue("$expected line top", layout.getLineTop(0) >= -1f)
        assertTrue("$expected line bottom", layout.getLineBottom(0) <= layout.size.height + 1f)
        expected.forEachIndexed { index, character ->
            if (!character.isWhitespace()) {
                val glyph = layout.getBoundingBox(index)
                assertTrue("$expected glyph $index left", glyph.left >= -1f)
                assertTrue("$expected glyph $index right", glyph.right <= layout.size.width + 1f)
                assertTrue("$expected glyph $index top", glyph.top >= -1f)
                assertTrue("$expected glyph $index bottom", glyph.bottom <= layout.size.height + 1f)
            }
        }

        val nodeBounds = interaction.getUnclippedBoundsInRoot()
        val viewportBounds = compose.onNodeWithTag(viewportTag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val rootBounds = compose.onNodeWithTag("pos-glass-fixture-root", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val visibleLeft = max(viewportBounds.left.value, rootBounds.left.value)
        val visibleTop = max(viewportBounds.top.value, rootBounds.top.value)
        val visibleRight = min(viewportBounds.right.value, rootBounds.right.value)
        val visibleBottom = min(viewportBounds.bottom.value, rootBounds.bottom.value)
        assertTrue(
            "$expected text must be fully inside $viewportTag and root: node=$nodeBounds " +
                "viewport=$viewportBounds root=$rootBounds didOverflowWidth=${layout.didOverflowWidth}",
            nodeBounds.left.value >= visibleLeft - 0.5f &&
                nodeBounds.top.value >= visibleTop - 0.5f &&
                nodeBounds.right.value <= visibleRight + 0.5f &&
                nodeBounds.bottom.value <= visibleBottom + 0.5f,
        )
        ownerTag?.let { tag ->
            val ownerBounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "$expected owner must be fully inside $viewportTag and root: owner=$ownerBounds " +
                    "viewport=$viewportBounds root=$rootBounds",
                ownerBounds.left.value >= visibleLeft - 0.5f &&
                    ownerBounds.top.value >= visibleTop - 0.5f &&
                    ownerBounds.right.value <= visibleRight + 0.5f &&
                    ownerBounds.bottom.value <= visibleBottom + 0.5f,
            )
            assertTrue(
                "$expected floating label must intersect its $tag owner: label=$nodeBounds owner=$ownerBounds",
                nodeBounds.left < ownerBounds.right &&
                    nodeBounds.right > ownerBounds.left &&
                    nodeBounds.top < ownerBounds.bottom &&
                    nodeBounds.bottom > ownerBounds.top,
            )
        }
        nonOverlappingTag?.let { tag ->
            val otherBounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertFalse(
                "$expected floating label must not overlap $tag: label=$nodeBounds other=$otherBounds",
                nodeBounds.left < otherBounds.right &&
                    nodeBounds.right > otherBounds.left &&
                    nodeBounds.top < otherBounds.bottom &&
                    nodeBounds.bottom > otherBounds.top,
            )
        }
    }

    /**
     * Samples the actual composited Text pixels. The middle local luminance is the nearby glass
     * background; multiple glyph-core pixels must independently clear the normal-text threshold.
     * Anti-aliased edge pixels are intentionally not treated as foreground cores.
     */
    private fun assertRenderedTextContrast(
        text: String,
        isDay: Boolean,
        parentTag: String = "pos-product-item-01",
    ) {
        val node = compose.onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(parentTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            assertTrue("$text capture must contain measurable pixels", bitmap.width >= 4 && bitmap.height >= 4)
            val luminances = ArrayList<Double>(bitmap.width * bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                luminances += relativeLuminance(bitmap.getPixel(x, y))
            }
            luminances.sort()
            val background = luminances[luminances.size / 2]
            val core = if (isDay) luminances.take(max(1, luminances.size / 40))
            else luminances.takeLast(max(1, luminances.size / 40))
            val passingCorePixels = core.count { contrastRatio(it, background) >= 4.5 }
            assertTrue(
                "$text must render multiple 4.5:1 glyph-core pixels over its local glass; " +
                    "background=$background best=${core.maxOf { contrastRatio(it, background) }} passing=$passingCorePixels",
                passingCorePixels >= min(8, core.size),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(Color.red(color)) +
            0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }

    private fun contrastRatio(first: Double, second: Double): Double {
        val lighter = max(first, second)
        val darker = min(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun menuItem(index: Int): MenuItemEntity = MenuItemEntity(
        id = "item-${index.toString().padStart(2, '0')}", categoryId = "drinks",
        sku = "DRINK-${index.toString().padStart(2, '0')}", name = "Product ${index.toString().padStart(2, '0')}",
        type = "drink", basePriceMinor = 900L + index * 100L, taxRate = 0.0,
        hsnCode = null, priceIncludesTax = true, isAvailable = true,
        description = "Operational catalogue product $index",
    )

    private fun controlledWorkspaceImage(name: String, generation: Long): WorkspaceBackgroundImage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.filesDir.resolve("pos-glass-fixtures").apply { mkdirs() }.resolve(name)
        val bitmap = Bitmap.createBitmap(2560, 1080, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.rgb(255, 250, 226), Color.rgb(246, 188, 42), Color.rgb(220, 244, 255))
        for (y in 0 until bitmap.height) {
            val row = IntArray(bitmap.width) { x -> colors[(x / 160 + y / 120) % colors.size] }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
        bitmap.recycle()
        return WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE, source = WorkspaceBackgroundSource.IMPORTED,
            width = 2560, height = 1080, generation = generation, contentId = sha256(file),
            privatePath = file.absolutePath,
        )
    }

    private fun capture(name: String, widthDp: Int, heightDp: Int, fontScale: Float, appearance: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val density = compose.activity.resources.displayMetrics.density
            assertEquals((widthDp * density).roundToInt(), bitmap.width)
            assertEquals((heightDp * density).roundToInt(), bitmap.height)
            val directory = outputDirectory()
            val png = directory.resolve("$name.png")
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val display = compose.activity.display ?: error("Host activity has no display")
            directory.resolve("$name.provenance.json").writeText(
                """
                {
                  "capture_run_id": ${argument(PosGlassRunArgument)},
                  "test_class": "cloud.dcompany.erp.ui.screens.PosGlassUiTest",
                  "source_inventory_sha256": ${argument(PosGlassSourceArgument)},
                  "target_apk_sha256": ${argument(PosGlassTargetArgument)},
                  "test_apk_sha256": ${argument(PosGlassTestArgument)},
                  "process_pid": ${Process.myPid()},
                  "android_api": ${Build.VERSION.SDK_INT},
                  "observed_display_id": ${display.displayId},
                  "display_mode_width_px": ${display.mode.physicalWidth},
                  "display_mode_height_px": ${display.mode.physicalHeight},
                  "density": $density,
                  "density_dpi": ${compose.activity.resources.displayMetrics.densityDpi},
                  "expected_width_dp": $widthDp,
                  "expected_height_dp": $heightDp,
                  "font_scale": $fontScale,
                  "appearance": "$appearance",
                  "actual_width_px": ${bitmap.width},
                  "actual_height_px": ${bitmap.height},
                  "png_file": "${png.name}",
                  "png_bytes": ${png.length()},
                  "png_sha256": "${sha256(png)}"
                }
                """.trimIndent() + "\n",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun outputDirectory(): File {
        val nonce = InstrumentationRegistry.getArguments().getString(PosGlassRunArgument)
            ?: error("Missing $PosGlassRunArgument")
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        val profile = RequestedPosGlassDisplay?.let { "display-$it-hd" } ?: "default-display"
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-b3-pos-glass-screenshots-v1").resolve(profile).resolve(nonce)
            .apply { check(isDirectory || mkdirs()) }
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name) ?: error("Missing $name")
        val length = if (name == PosGlassRunArgument) 32 else 64
        require(value.matches(Regex("[0-9a-f]{$length}")))
        return "\"$value\""
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
