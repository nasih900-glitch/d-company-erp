package cloud.dcompany.erp.ui.screens

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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

        render(state)

        compose.onNodeWithText("The menu is empty").assertIsDisplayed()
        compose.onNodeWithText("Current order").assertIsDisplayed()
        compose.onNodeWithText("PS5 Station 1 · Standard · 3 players").assertIsDisplayed()
        compose.onNodeWithText("PAY · ₹180.00").assertIsDisplayed().assertIsEnabled()
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

    private fun render(
        state: PosUiState,
        presentation: WorkspacePresentationPolicy =
            WorkspaceFeatureProfiles.Active.presentationPolicy(),
        onUpdateHeldOrderDiscount: (String, Long) -> Unit = { _, _ -> },
    ) {
        renderDynamic(
            state = mutableStateOf(state),
            presentation = presentation,
            onUpdateHeldOrderDiscount = onUpdateHeldOrderDiscount,
        )
    }

    private fun renderDynamic(
        state: State<PosUiState>,
        presentation: WorkspacePresentationPolicy =
            WorkspaceFeatureProfiles.Active.presentationPolicy(),
        onUpdateHeldOrderDiscount: (String, Long) -> Unit = { _, _ -> },
        onDismissNotice: () -> Unit = {},
    ) {
        compose.setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
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
                        onAdd = {},
                        onAddConfigured = { _, _, _, _ -> },
                        onRemove = {},
                        onIncrementLine = {},
                        onDecrementLine = {},
                        onSelectCategory = {},
                        onClearCart = {},
                        onUpdateDraftDetails = { _, _, _, _ -> },
                        onRefresh = {},
                        onPrepareDirectCheckout = {},
                        onContinueDirectCheckout = {},
                        onDismissDirectCheckout = {},
                        onConfirmDirectZero = {},
                        onRedeemDirectPoints = {},
                        onCapture = { _, _, _ -> },
                        onRetryRejectedSale = {},
                        onRetryHeldPayment = {},
                        onPrepareHeldOrder = {},
                        onUpdateHeldOrderDiscount = onUpdateHeldOrderDiscount,
                        onContinueHeldOrder = {},
                        onConfirmHeldOrder = { _, _, _ -> },
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
                        presentation = presentation,
                    )
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

        compose.onNode(hasSetTextAction()).performClick().performTextReplacement("20")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithText("Apply discount").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("held-notice-1" to 2_000L), updates)
        }

        if (noticeBeforeRefresh) {
            compose.runOnIdle { state.value = state.value.copy(notice = notice) }
            awaitActiveWindowNode(notice, actionable = false)
            compose.runOnIdle { state.value = state.value.copy(heldOrderReview = refreshedReview) }
        } else {
            compose.runOnIdle { state.value = state.value.copy(heldOrderReview = refreshedReview) }
            settleActiveWindow()
            compose.runOnIdle { state.value = state.value.copy(notice = notice) }
        }

        val noticeNode = awaitActiveWindowNode(notice, actionable = false)
        assertEquals(notice, noticeNode.text?.toString())
        val ok = awaitActiveWindowNode("OK", actionable = true)
        assertTrue("The active-window OK action must dismiss the notice", ok.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settleActiveWindow()

        compose.onNodeWithText(notice).assertDoesNotExist()
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

    private fun awaitActiveWindowNode(
        exactText: String,
        actionable: Boolean,
    ): AccessibilityNodeInfo {
        var matched: AccessibilityNodeInfo? = null
        settleActiveWindow()
        compose.waitUntil(timeoutMillis = 5_000L) {
            matched = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?.findExactText(exactText, actionable)
            matched != null
        }
        assertNotNull("Active window did not expose exact text: $exactText", matched)
        return checkNotNull(matched)
    }

    private fun settleActiveWindow() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250L, 5_000L)
        compose.waitForIdle()
    }

    private fun AccessibilityNodeInfo.findExactText(
        exactText: String,
        actionable: Boolean,
    ): AccessibilityNodeInfo? {
        if (text?.toString() == exactText) {
            if (!actionable) return this
            var actionTarget: AccessibilityNodeInfo? = this
            while (actionTarget != null) {
                if (actionTarget.isClickable && actionTarget.isEnabled) return actionTarget
                actionTarget = actionTarget.parent
            }
        }
        for (index in 0 until childCount) {
            getChild(index)?.findExactText(exactText, actionable)?.let { return it }
        }
        return null
    }
}
