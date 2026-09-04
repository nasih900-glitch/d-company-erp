package cloud.dcompany.erp.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
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

        render(state)

        compose.onNodeWithText("Review PS5 Station 2").assertIsDisplayed()
        compose.onNodeWithText("PS5 Station 2 · Dual · 60 min").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("CONTINUE TO PAYMENT · ₹180.00")
            .assertIsDisplayed()
            .assertIsEnabled()
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
    ) {
        compose.setContent {
            DCompanyTheme {
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                    PosScreen(
                        state = state,
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
                        onUpdateHeldOrderDiscount = { _, _ -> },
                        onContinueHeldOrder = {},
                        onConfirmHeldOrder = { _, _, _ -> },
                        onConfirmHeldOrderZero = {},
                        onVoidOrder = { _, _ -> },
                        onDismissHeldOrderReview = {},
                        onDismissHeldOrder = {},
                        onDismissNotice = {},
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
}
