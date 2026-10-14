package cloud.dcompany.erp.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.CustomerRewardEligibility
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.screens.settings.LoyaltyProgramRulesDto
import cloud.dcompany.erp.ui.screens.settings.LoyaltyRankRuleDto
import cloud.dcompany.erp.ui.screens.settings.LoyaltyRewardRuleDto
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PosEmptyCatalogueUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun savedGamingBillRemainsVisibleAndPayableWhenProductCatalogueIsEmpty() {
        val gamingLine = CartLine(
            lineId = "gaming-line",
            item = MenuItemEntity(
                id = "gaming-session",
                categoryId = "gaming",
                sku = "SESSION-PS5",
                name = "PS5 Station 1 · Standard · 3 players",
                type = "gaming",
                basePriceMinor = 18_000,
                taxRate = 0.0,
                hsnCode = null,
                priceIncludesTax = true,
                isAvailable = false,
                description = null,
            ),
            qty = 1,
            unitPriceMinor = 18_000,
        )
        val state = PosUiState(
            // This is the production edge: the saved bill can be reconstructed
            // from its immutable line snapshot even if the mutable catalogue
            // has no products at all.
            items = emptyList(),
            operationalItems = emptyList(),
            cart = listOf(gamingLine),
            menuEmpty = true,
            everSynced = true,
            online = true,
            activeShiftId = "shift-1",
            canCollectPayment = true,
            draftState = SyncState.DRAFT,
            draftLocalId = "draft-1",
            draftRevision = 1L,
        )

        var checkoutPreparations = 0
        render(state, onPrepareDirectCheckout = { checkoutPreparations++ })

        compose.onNodeWithText("The menu is empty").assertIsDisplayed()
        compose.onNodeWithText("Current order").assertIsDisplayed()
        compose.onNodeWithText("PS5 Station 1 · Standard · 3 players").assertIsDisplayed()
        compose.onNodeWithText("Split").assertIsDisplayed()
        compose.onNodeWithText("TAKE PAYMENT").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, checkoutPreparations) }
    }

    @Test
    fun inlineCashEntryNormalisesCommaRejectsMalformedEditAndResetsForNextBill() {
        val cola = menuItem("cola", "Audit Cola", 10_000L)
        val state = mutableStateOf(
            PosUiState(
                items = listOf(cola),
                operationalItems = listOf(cola),
                cart = listOf(CartLine("cola-line-a", cola, qty = 1, unitPriceMinor = 10_000L)),
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                draftState = SyncState.DRAFT,
                draftLocalId = "draft-a",
                draftRevision = 1L,
            ),
        )
        renderDynamic(state)

        val cashEntry = compose.onNodeWithTag("pos-inline-cash-received")
        cashEntry.assertIsDisplayed().performTextReplacement("100,50")
        cashEntry.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("100.50"),
            ),
        )

        // Invalid input remains visible as the last valid amount. It must not
        // be stripped into a different amount that staff never entered.
        cashEntry.performTextReplacement("100x50")
        cashEntry.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("100.50"),
            ),
        )

        compose.runOnIdle {
            state.value = state.value.copy(
                cart = listOf(CartLine("cola-line-b", cola, qty = 1, unitPriceMinor = 10_000L)),
                draftLocalId = "draft-b",
                draftRevision = 1L,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("pos-inline-cash-received").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(""),
            ),
        )
    }

    @Test
    fun onlineSplitSelectionFallsBackToPayableCashWhenConnectionDrops() {
        val cola = menuItem("cola", "Audit Cola", 5_000L)
        val state = mutableStateOf(
            PosUiState(
                items = listOf(cola),
                operationalItems = listOf(cola),
                cart = listOf(CartLine("cola-line", cola, qty = 1, unitPriceMinor = 5_000L)),
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                draftState = SyncState.DRAFT,
                draftLocalId = "draft-offline",
                draftRevision = 3L,
            ),
        )
        val captures = mutableListOf<Pair<String, Long>>()
        renderDynamic(
            state = state,
            onCapture = { method, tendered, _ ->
                captures += method to tendered
                true
            },
        )

        compose.onNodeWithText("Split").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(online = false) }
        compose.waitForIdle()

        compose.onNodeWithTag("pos-inline-cash-received").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("pos-take-payment").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Split tender").assertDoesNotExist()
        compose.onNodeWithText("Cash received (₹)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Digit 5").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Digit 0").performScrollTo().performClick()
        compose.onNodeWithText("CONFIRM CASH · ₹50.00")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("cash" to 5_000L), captures) }
    }

    @Test
    fun catalogueAndOrderRemainSideBySideAtBothApprovedTabletSizes() {
        val category = MenuCategoryEntity("audit", "Audit menu", 0, false)
        val cola = menuItem("cola", "Audit Cola", 5_000L)
        val crisps = menuItem("crisps", "Audit Crisps", 3_000L)
        // Content bounds after status bars, the 68dp brand bar and 68dp bottom nav.
        val viewport = mutableStateOf(1_280 to 616)
        val actions = mutableListOf<String>()
        renderDynamic(
            state = mutableStateOf(
                PosUiState(
                    categories = listOf(category),
                    items = listOf(cola, crisps),
                    operationalCategories = listOf(category),
                    operationalItems = listOf(cola, crisps),
                    cart = listOf(
                        CartLine("cola-line", cola, qty = 1, unitPriceMinor = 5_000L),
                        CartLine("crisps-line", crisps, qty = 2, unitPriceMinor = 3_000L),
                    ),
                    online = true,
                    activeShiftId = "shift-1",
                    canCollectPayment = true,
                    draftState = SyncState.DRAFT,
                    draftLocalId = "draft-1",
                    draftRevision = 1L,
                ),
            ),
            viewportSize = viewport,
            onAddItem = { actions += "add:${it.id}" },
            onIncrementLine = { actions += "increment:$it" },
            onClearCart = { actions += "clear" },
            onPrepareDirectCheckout = { actions += "pay" },
        )

        for ((size, filename) in listOf(
            (1_280 to 616) to "pos-1280x800-content.png",
            (960 to 416) to "pos-960x600-content.png",
        )) {
            compose.runOnIdle { viewport.value = size }
            compose.waitForIdle()
            val catalog = compose.onNodeWithTag("pos-catalog").getUnclippedBoundsInRoot()
            val cart = compose.onNodeWithTag("pos-cart").getUnclippedBoundsInRoot()
            val root = compose.onNodeWithTag("pos-layout-root").getUnclippedBoundsInRoot()
            assertTrue("Catalog and cart must remain side by side at $size", catalog.right <= cart.left)
            assertTrue("Cart must fit within the content viewport at $size", cart.right <= root.right)
            assertTrue("Cart must fit vertically at $size", cart.bottom <= root.bottom)
            compose.onNodeWithText("Split").assertIsDisplayed()
            compose.onNodeWithTag("pos-take-payment").assertIsDisplayed().assertIsEnabled()
            writeLayoutScreenshot(filename)
        }

        compose.onNodeWithTag("pos-product-crisps").performClick()
        compose.onNodeWithContentDescription("Increase Audit Cola quantity from 1")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithTag("pos-take-payment").performClick()
        compose.runOnIdle {
            assertEquals(listOf("add:crisps", "increment:cola-line", "clear", "pay"), actions)
        }
    }

    private fun menuItem(id: String, name: String, priceMinor: Long) = MenuItemEntity(
        id = id,
        categoryId = "audit",
        sku = id.uppercase(),
        name = name,
        type = "food",
        basePriceMinor = priceMinor,
        taxRate = 0.0,
        hsnCode = null,
        priceIncludesTax = true,
        isAvailable = true,
        description = null,
    )

    private fun writeLayoutScreenshot(filename: String) {
        val output = File(requireNotNull(compose.activity.getExternalFilesDir(null)), filename)
        val bitmap = compose.onNodeWithTag("pos-layout-root").captureToImage().asAndroidBitmap()
        try {
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun heldGamingBillRemainsDiscountableAndPayableWhenProductCatalogueIsEmpty() {
        val appliedDiscounts = mutableListOf<Pair<String, Long>>()
        val state = PosUiState(
            items = emptyList(),
            operationalItems = emptyList(),
            menuEmpty = true,
            everSynced = true,
            online = true,
            activeShiftId = "shift-1",
            canCollectPayment = true,
            heldOrderReview = HeldOrderReview(
                orderId = "held-gaming-1",
                shiftIdAtReview = "shift-1",
                sourceLabel = "PS5 Station 2",
                invoiceNo = null,
                type = "gaming",
                subtotalMinor = 18_000,
                discountMinor = 0,
                manualDiscountMinor = 0,
                pointsRedeemedMinor = 0,
                pointsRedeemed = 0,
                taxMinor = 0,
                roundOffMinor = 0,
                tipMinor = 0,
                totalMinor = 18_000,
                paidMinor = 0,
                dueMinor = 18_000,
                checkoutVersion = 1,
                maxManualDiscountMinor = 18_000,
                lines = listOf(
                    HeldOrderReviewLine(
                        identity = "gaming-line",
                        name = "PS5 Station 2 · Dual · 60 min",
                        quantity = 1.0,
                        unitPriceMinor = 18_000,
                        discountMinor = 0,
                        lineTotalMinor = 18_000,
                        variantName = null,
                        modifiers = emptyList(),
                        note = null,
                    ),
                ),
            ),
        )

        render(state, onUpdateHeldOrderDiscount = { id, amount -> appliedDiscounts += id to amount })

        compose.onNodeWithText("Review PS5 Station 2").assertIsDisplayed()
        compose.onNodeWithText("PS5 Station 2 · Dual · 60 min").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("CONTINUE TO PAYMENT · ₹180.00")
            .assertIsDisplayed()
            .assertIsEnabled()
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement("30")
        // Root IME handling must not leave the primary footer below the
        // keyboard; semantic presence alone is not enough for this assertion.
        compose.onNodeWithText("CONTINUE TO PAYMENT · ₹180.00")
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithText("Apply discount").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("held-gaming-1" to 3_000L), appliedDiscounts) }
    }

    @Test
    fun refreshedHeldReviewDoesNotReplaceVisibleDiscountSuccessNotice() {
        verifyHeldDiscountNoticeLayering(noticeBeforeRefresh = true)
    }

    @Test
    fun discountSuccessNoticeAlsoLayersOverAnAlreadyRefreshedHeldReview() {
        verifyHeldDiscountNoticeLayering(noticeBeforeRefresh = false)
    }

    @Test
    fun directCustomerBenefitsAreEditedBeforePaymentControlsExist() {
        val review = DirectCheckoutReview(
            localId = "direct-1",
            revision = 1,
            orderId = "server-direct-1",
            shiftId = "shift-1",
            subtotalMinor = 12_000,
            discountMinor = 0,
            pointsRedeemedMinor = 0,
            pointsRedeemed = 0,
            taxMinor = 0,
            roundOffMinor = 0,
            totalMinor = 12_000,
            dueMinor = 12_000,
            checkoutVersion = 7,
        )
        render(
            PosUiState(
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                customerPhone = "9999999999",
                customerLoyaltyPoints = 100,
                directCheckoutReview = review,
            ),
            presentation = WorkspacePresentationPolicy(
                showsMemberships = false,
                showsRestaurantOperations = false,
                showsCustomers = true,
                showsEvents = false,
                singleHybridTerminalOnly = true,
            ),
        )

        compose.onNodeWithText("Review live bill · ₹120.00").assertIsDisplayed()
        compose.onNodeWithText("Use points").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("CONTINUE TO PAYMENT").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cash").assertDoesNotExist()
    }

    @Test
    fun publishedDirectClaimShowsPaymentButNoLongerOffersBillEdits() {
        render(
            PosUiState(
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                customerPhone = "9999999999",
                customerLoyaltyPoints = 100,
                preparedDirectCheckout = PreparedDirectCheckout(
                    localId = "direct-1",
                    revision = 1,
                    orderId = "server-direct-1",
                    shiftIdAtClaim = "shift-1",
                    subtotalMinor = 12_000,
                    discountMinor = 0,
                    pointsRedeemedMinor = 0,
                    pointsRedeemed = 0,
                    taxMinor = 0,
                    roundOffMinor = 0,
                    totalMinor = 12_000,
                    dueMinor = 12_000,
                    claimToken = "claim-1",
                    claimExpiresAtMillis = Long.MAX_VALUE,
                    claimOrderVersion = 8,
                ),
            ),
        )

        compose.onNodeWithText("Direct POS bill · ₹120.00").assertIsDisplayed()
        compose.onNodeWithText("Cash").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Use points").assertDoesNotExist()
        compose.onNodeWithText("CONTINUE TO PAYMENT").assertDoesNotExist()
    }

    @Test
    fun expiredDirectClaimRefreshGetsOneFreshConfirmationWithoutDoubleCapture() {
        fun prepared(claimToken: String) = PreparedDirectCheckout(
            localId = "direct-1",
            revision = 1,
            orderId = "server-direct-1",
            shiftIdAtClaim = "shift-1",
            subtotalMinor = 12_000,
            discountMinor = 0,
            pointsRedeemedMinor = 0,
            pointsRedeemed = 0,
            taxMinor = 0,
            roundOffMinor = 0,
            totalMinor = 12_000,
            dueMinor = 12_000,
            claimToken = claimToken,
            claimExpiresAtMillis = Long.MAX_VALUE,
            claimOrderVersion = 8,
        )
        val state = mutableStateOf(
            PosUiState(
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                preparedDirectCheckout = prepared("claim-a"),
            ),
        )
        var attempts = 0
        renderDynamic(
            state = state,
            onCapture = { _, _, _ ->
                attempts += 1
                if (attempts == 1) {
                    // The ViewModel rejected the expired claim before Room and
                    // persisted a refreshed claim for the same order/version.
                    state.value = state.value.copy(
                        preparedDirectCheckout = prepared("claim-b"),
                    )
                    false
                } else {
                    true
                }
            },
        )

        compose.onNodeWithText("UPI").assertIsEnabled().performClick()
        compose.onNodeWithText("CONFIRM UPI · ₹120.00").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithText("Payment submitted once").assertDoesNotExist()

        compose.onNodeWithText("UPI").assertIsEnabled().performClick()
        compose.onNodeWithText("CONFIRM UPI · ₹120.00").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, attempts) }
        compose.onNodeWithText("Payment submitted once").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Payment submitted once").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun namedRewardRequiresReviewAndDispatchesItsStableKeyOnce() {
        val dispatched = mutableListOf<String>()
        render(
            PosUiState(
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                customerPhone = "9999999999",
                customerLoyaltyPoints = 100,
                loyaltyProgramRevision = 4,
                loyaltyProgramRules = LoyaltyProgramRulesDto(
                    gamingEarnPoints = 2,
                    gamingEarnRupees = 10,
                    minorPerPoint = 11,
                    ranks = listOf(
                        LoyaltyRankRuleDto("rookie", "Rookie", 0, 0),
                        LoyaltyRankRuleDto("player", "Player", 200, 50),
                        LoyaltyRankRuleDto("pro", "Pro", 500, 150),
                        LoyaltyRankRuleDto("legend", "Legend", 1_000, 300),
                    ),
                    rewards = listOf(
                        LoyaltyRewardRuleDto(
                            key = "snack",
                            label = "Free snack or drink",
                            description = "One fixed credit on this bill",
                            pointsCost = 60,
                            valueMinor = 4_000,
                            minRankKey = "rookie",
                            enabled = true,
                        ),
                    ),
                ),
                directCheckoutReview = DirectCheckoutReview(
                    localId = "direct-reward-1",
                    revision = 1,
                    orderId = "server-reward-1",
                    shiftId = "shift-1",
                    subtotalMinor = 12_000,
                    discountMinor = 0,
                    pointsRedeemedMinor = 0,
                    pointsRedeemed = 0,
                    taxMinor = 0,
                    roundOffMinor = 0,
                    totalMinor = 12_000,
                    dueMinor = 12_000,
                    checkoutVersion = 7,
                ),
            ),
            eligibleRewardCatalog = EligibleRewardCatalogState(
                phone = "9999999999",
                programRevision = 4,
                rewards = listOf(
                    CustomerRewardEligibility(
                        programRevision = 4,
                        key = "snack",
                        name = "Free snack or drink",
                        description = "One fixed credit on this bill",
                        pointsCost = 60,
                        valueMinor = 4_000,
                        minRank = "Rookie",
                        affordable = true,
                    ),
                ),
                verified = true,
            ),
            onRedeemDirectReward = { dispatched += it.key },
        )

        compose.onNodeWithText("Free snack or drink · 60 pts → ₹40.00")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(dispatched.isEmpty()) }
        compose.onNodeWithText("Review Free snack or drink")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "60 points → ₹40.00 fixed bill credit · program revision 4",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm reward")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("snack"), dispatched) }
    }

    private fun render(
        state: PosUiState,
        presentation: WorkspacePresentationPolicy =
            WorkspaceFeatureProfiles.Active.presentationPolicy(),
        onUpdateHeldOrderDiscount: (String, Long) -> Unit = { _, _ -> },
        onPrepareDirectCheckout: () -> Unit = {},
        onRedeemDirectReward: (ReviewedRewardRedemption) -> Unit = {},
        eligibleRewardCatalog: EligibleRewardCatalogState = EligibleRewardCatalogState(),
    ) {
        renderDynamic(
            state = mutableStateOf(state),
            presentation = presentation,
            onUpdateHeldOrderDiscount = onUpdateHeldOrderDiscount,
            onPrepareDirectCheckout = onPrepareDirectCheckout,
            onRedeemDirectReward = onRedeemDirectReward,
            eligibleRewardCatalog = eligibleRewardCatalog,
        )
    }

    private fun renderDynamic(
        state: State<PosUiState>,
        presentation: WorkspacePresentationPolicy =
            WorkspaceFeatureProfiles.Active.presentationPolicy(),
        onUpdateHeldOrderDiscount: (String, Long) -> Unit = { _, _ -> },
        onDismissNotice: () -> Unit = {},
        onPrepareDirectCheckout: () -> Unit = {},
        onCapture: (String, Long, DirectPaymentConfirmation) -> Boolean = { _, _, _ -> true },
        onRedeemDirectReward: (ReviewedRewardRedemption) -> Unit = {},
        eligibleRewardCatalog: EligibleRewardCatalogState = EligibleRewardCatalogState(),
        onAddItem: (MenuItemEntity) -> Unit = {},
        onIncrementLine: (String) -> Unit = {},
        onClearCart: () -> Unit = {},
        viewportSize: State<Pair<Int, Int>>? = null,
    ) {
        compose.setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewportModifier = viewportSize?.value?.let { (widthDp, heightDp) ->
                        Modifier.width(widthDp.dp).height(heightDp.dp)
                    } ?: Modifier.fillMaxSize()
                    // Surface propagates its full-window minimum constraints to its
                    // direct child. A loose parent lets this test exercise the
                    // requested content viewport instead of silently using 1280x900.
                    Box(Modifier.fillMaxSize()) {
                    Box(viewportModifier.background(MaterialTheme.colorScheme.background).testTag("pos-layout-root")) {
                        PosScreen(
                            state = state.value,
                            recentReceipts = emptyList(),
                            canonicalReceipts = emptyList(),
                            receiptHistoryHasMore = false,
                            receiptHistoryLoading = false,
                            receiptHistoryError = null,
                            unacknowledgedReceipt = null,
                            access = PosAccess(
                                canCreateAndCollect = true,
                                canVoid = true,
                                canApplyDiscount = true,
                            ),
                            onAccessChanged = {},
                            onAdd = onAddItem,
                            onAddConfigured = { _, _, _, _ -> },
                            onRemove = {},
                            onIncrementLine = onIncrementLine,
                            onDecrementLine = {},
                            onSelectCategory = {},
                            onClearCart = onClearCart,
                            onUpdateDraftDetails = { _, _, _, _ -> },
                            onRefresh = {},
                            onPrepareDirectCheckout = onPrepareDirectCheckout,
                            onContinueDirectCheckout = {},
                            onDismissDirectCheckout = {},
                            onConfirmDirectZero = {},
                            onRedeemDirectPoints = {},
                            onRedeemDirectReward = onRedeemDirectReward,
                            onCapture = onCapture,
                            onCaptureSplit = { _, _ -> true },
                            onRetryRejectedSale = {},
                            onRetryHeldPayment = {},
                            onPrepareHeldOrder = {},
                            onUpdateHeldOrderDiscount = onUpdateHeldOrderDiscount,
                            onContinueHeldOrder = {},
                            onConfirmHeldOrder = { _, _, _ -> true },
                            onConfirmHeldOrderSplit = { _, _ -> true },
                            onConfirmHeldOrderZero = {},
                            onVoidOrder = { _, _ -> },
                            onDismissHeldOrderReview = {},
                            onDismissHeldOrder = {},
                            onDismissNotice = onDismissNotice,
                            onAcknowledgeReceipt = {},
                            onRefreshReceiptHistory = {},
                            onLoadMoreReceiptHistory = {},
                            onOpenCanonicalReceipt = {},
                            onFocusOldestOverdue = {},
                            onSnoozeOverdue = {},
                            onUnmuteOverdue = {},
                            onDismissHeldFocus = {},
                            eligibleRewardCatalog = eligibleRewardCatalog,
                            presentation = presentation,
                        )
                    }
                    }
                }
            }
        }
    }

    private fun verifyHeldDiscountNoticeLayering(noticeBeforeRefresh: Boolean) {
        val initialReview = heldDiscountReview(
            manualDiscountMinor = 0L,
            totalMinor = 23_000L,
            dueMinor = 23_000L,
            checkoutVersion = 1L,
        )
        val refreshedReview = heldDiscountReview(
            manualDiscountMinor = 2_000L,
            totalMinor = 21_000L,
            dueMinor = 21_000L,
            checkoutVersion = 2L,
        )
        val notice = "Manual discount ₹20.00 applied. Review the refreshed total before payment."
        val state = mutableStateOf(
            PosUiState(
                items = emptyList(),
                operationalItems = emptyList(),
                menuEmpty = true,
                everSynced = true,
                online = true,
                activeShiftId = "shift-1",
                canCollectPayment = true,
                heldOrderReview = initialReview,
            ),
        )
        val updates = mutableListOf<Pair<String, Long>>()
        renderDynamic(
            state = state,
            onUpdateHeldOrderDiscount = { orderId, amount -> updates += orderId to amount },
            onDismissNotice = { state.value = state.value.copy(notice = null) },
        )

        // This helper verifies how the success notice and refreshed held-bill
        // review layer. Exercise the entry's touch fallback so the assertion
        // does not race a still-opening platform IME; keyboard Done/focus is
        // covered independently by the held-bill input test above.
        compose.onNodeWithContentDescription("Clear Manual discount (₹)")
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Digit 2")
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Digit 0")
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Apply discount").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("held-notice-1" to 2_000L), updates)
        }

        if (noticeBeforeRefresh) {
            compose.runOnIdle { state.value = state.value.copy(notice = notice) }
            compose.onNodeWithTag("pos-inline-notice").assertIsDisplayed()
            compose.onNodeWithText(notice).assertIsDisplayed()
            compose.runOnIdle { state.value = state.value.copy(heldOrderReview = refreshedReview) }
        } else {
            compose.runOnIdle { state.value = state.value.copy(heldOrderReview = refreshedReview) }
            compose.waitForIdle()
            compose.runOnIdle { state.value = state.value.copy(notice = notice) }
        }

        compose.onNodeWithTag("pos-inline-notice")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        compose.onNodeWithTag("pos-held-review-inline").assertIsDisplayed()
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithTag("pos-inline-notice").assertDoesNotExist()
        compose.onNodeWithTag("pos-held-review-inline").assertIsDisplayed()
        compose.onNodeWithText("Review PS5 Station 3").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("20"),
            ),
        ).assertIsEnabled()
        compose.onNodeWithText("Apply discount").performScrollTo()
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("CONTINUE TO PAYMENT · ₹210.00")
            .assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf("held-notice-1" to 2_000L), updates)
        }
    }

    private fun heldDiscountReview(
        manualDiscountMinor: Long,
        totalMinor: Long,
        dueMinor: Long,
        checkoutVersion: Long,
    ) = HeldOrderReview(
        orderId = "held-notice-1",
        shiftIdAtReview = "shift-1",
        sourceLabel = "PS5 Station 3",
        invoiceNo = null,
        type = "gaming",
        subtotalMinor = 23_000L,
        discountMinor = manualDiscountMinor,
        manualDiscountMinor = manualDiscountMinor,
        pointsRedeemedMinor = 0L,
        pointsRedeemed = 0,
        taxMinor = 0L,
        roundOffMinor = 0L,
        tipMinor = 0L,
        totalMinor = totalMinor,
        paidMinor = 0L,
        dueMinor = dueMinor,
        checkoutVersion = checkoutVersion,
        maxManualDiscountMinor = 23_000L,
        lines = listOf(
            HeldOrderReviewLine(
                identity = "held-notice-line",
                name = "PS5 Station 3 · Solo · 60 min",
                quantity = 1.0,
                unitPriceMinor = 23_000L,
                discountMinor = manualDiscountMinor,
                lineTotalMinor = totalMinor,
                variantName = null,
                modifiers = emptyList(),
                note = null,
            ),
        ),
    )

}
