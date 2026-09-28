package cloud.dcompany.erp.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.PosAccess
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.screens.customers.CustomerLeaderboardPanel
import cloud.dcompany.erp.ui.screens.customers.CustomerPlaytimeUiState
import cloud.dcompany.erp.ui.screens.customers.PlaytimeLeaderboardItem
import cloud.dcompany.erp.ui.screens.customers.PlaytimeProgramDraft
import cloud.dcompany.erp.ui.screens.shift.ShiftDashboardOverview
import cloud.dcompany.erp.ui.screens.shift.ShiftUiState
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Deterministic visual fixture: synthetic values are never written to the ERP. */
class ControlDeckFullShellScreenshotsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun posShiftAndCustomersRenderInsideRealWorkspaceShell() {
        val current = mutableStateOf(Destination.Pos)
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.fillMaxSize().testTag("control-deck-full-shell")) {
                    WorkspaceScaffold(
                        destinations = listOf(
                            Destination.Gaming, Destination.Pos, Destination.Shift,
                            Destination.Customers, Destination.Reports, Destination.Settings,
                        ),
                        currentDestination = current.value,
                        employeeName = "Fixture Staff",
                        locationLabel = "Fixture Branch",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(),
                        syncing = false,
                        canChangeTill = false,
                        onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                        onDestinationChanged = { current.value = it },
                    ) { destination, _ ->
                        when (destination) {
                            Destination.Pos -> PosFullShellFixture()
                            Destination.Shift -> ShiftFullShellFixture()
                            Destination.Customers -> CustomersFullShellFixture()
                            else -> Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Current order").assertIsDisplayed()
        compose.onNodeWithText("D COMPANY").assertIsDisplayed()
        compose.onNodeWithTag("pos-take-payment").assertIsDisplayed()
        compose.onNodeWithContentDescription("More modules").assertIsDisplayed()
        capture("control-deck-pos-full-1280x800.png")

        compose.runOnIdle { current.value = Destination.Shift }
        compose.onNodeWithText("Live collections").assertIsDisplayed()
        compose.onNodeWithText("D COMPANY").assertIsDisplayed()
        compose.onNodeWithText("Review & close shift").assertIsDisplayed()
        compose.onNodeWithContentDescription("More modules").assertIsDisplayed()
        capture("control-deck-shift-full-1280x800.png")

        compose.runOnIdle { current.value = Destination.Customers }
        compose.onNodeWithText("Playtime leaderboard").assertIsDisplayed()
        compose.onNodeWithText("D COMPANY").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rank 1 by completed playtime").assertIsDisplayed()
        compose.onNodeWithText("••••••3210").assertIsDisplayed()
        compose.onNodeWithText("9876543210").assertDoesNotExist()
        compose.onNodeWithContentDescription("More modules").assertIsDisplayed()
        capture("control-deck-customers-full-1280x800.png")
    }

    private fun capture(filename: String) {
        compose.waitForIdle()
        val output = File(requireNotNull(compose.activity.getExternalFilesDir(null)), filename)
        val bitmap = compose.onNodeWithTag("control-deck-full-shell")
            .captureToImage().asAndroidBitmap()
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}

@Composable
private fun PosFullShellFixture() {
    val category = MenuCategoryEntity("fixture", "Fixture menu", 0, false)
    val products = listOf(
        "Coffee" to 9_000L, "Fries" to 9_000L, "Energy drink" to 6_000L,
        "Water" to 2_000L, "Sandwich" to 12_000L, "Crisps" to 3_000L,
    ).mapIndexed { index, (name, price) ->
        MenuItemEntity(
            id = "fixture-$index", categoryId = category.id, sku = "FIXTURE-$index",
            name = "Fixture $name", type = "food", basePriceMinor = price,
            taxRate = 0.0, hsnCode = null, priceIncludesTax = true,
            isAvailable = true, description = null,
        )
    }
    val state = PosUiState(
        categories = listOf(category), operationalCategories = listOf(category),
        items = products, operationalItems = products,
        cart = listOf(
            CartLine("line-1", products[0], qty = 1, unitPriceMinor = 9_000L),
            CartLine("line-2", products[1], qty = 2, unitPriceMinor = 9_000L),
        ),
        everSynced = true, online = true, activeShiftId = "fixture-shift",
        canCollectPayment = true, draftState = SyncState.DRAFT,
        draftLocalId = "fixture-draft", draftRevision = 1L,
    )
    PosScreen(
        state = state,
        recentReceipts = emptyList(), canonicalReceipts = emptyList(),
        receiptHistoryHasMore = false, receiptHistoryLoading = false,
        receiptHistoryError = null, unacknowledgedReceipt = null,
        access = PosAccess(canCreateAndCollect = true, canVoid = true, canApplyDiscount = true),
        onAccessChanged = {}, onAdd = {}, onAddConfigured = { _, _, _, _ -> },
        onRemove = {}, onIncrementLine = {}, onDecrementLine = {},
        onSelectCategory = {}, onClearCart = {},
        onUpdateDraftDetails = { _, _, _, _ -> }, onRefresh = {},
        onPrepareDirectCheckout = {}, onContinueDirectCheckout = {},
        onDismissDirectCheckout = {}, onConfirmDirectZero = {},
        onRedeemDirectPoints = {}, onCapture = { _, _, _ -> },
        onCaptureSplit = { _, _ -> }, onRetryRejectedSale = {},
        onRetryHeldPayment = {}, onPrepareHeldOrder = {},
        onUpdateHeldOrderDiscount = { _, _ -> }, onContinueHeldOrder = {},
        onConfirmHeldOrder = { _, _, _ -> }, onConfirmHeldOrderSplit = { _, _ -> },
        onConfirmHeldOrderZero = {}, onVoidOrder = { _, _ -> },
        onDismissHeldOrderReview = {}, onDismissHeldOrder = {},
        onDismissNotice = {}, onAcknowledgeReceipt = {},
        onRefreshReceiptHistory = {}, onLoadMoreReceiptHistory = {},
        onOpenCanonicalReceipt = {}, onFocusOldestOverdue = {},
        onSnoozeOverdue = {}, onUnmuteOverdue = {}, onDismissHeldFocus = {},
    )
}

@Composable
private fun ShiftFullShellFixture() {
    val local = LocalShiftEntity(
        localId = "fixture-shift", serverShiftId = "fixture-confirmed-shift",
        openingFloatMinor = 0, openedAtMillis = 1_700_000_000_000,
        state = ShiftState.OPEN_SYNCED,
    )
    val shift = ResolvedOpenShift(
        shiftId = "fixture-confirmed-shift", source = ShiftSource.LOCAL_OUTBOX,
        local = local, openedAtMillis = local.openedAtMillis,
        openingFloatMinor = 0, expectedMinor = 10_000,
        posCollectionsMinor = 24_000, membershipCollectionsMinor = 0,
        grossCollectionsMinor = 24_000, cashCollectionsMinor = 10_000,
        cardCollectionsMinor = 0, upiCollectionsMinor = 14_000,
        otherCollectionsMinor = 0, settledPosRefundsMinor = 0,
        settledMembershipRefundsMinor = 0, totalRefundsMinor = 0,
        netCollectionsMinor = 24_000,
        openedByUserId = "fixture-staff", openedByName = "Fixture Staff",
        openedByEmail = null,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ShiftDashboardOverview(
            state = ShiftUiState(open = shift, online = true, expectedMinor = 10_000),
            onRefresh = {}, onReview = {},
        )
    }
}

@Composable
private fun CustomersFullShellFixture() {
    val names = listOf("Aarav", "Rohan", "Vivek", "Sara", "Priya", "Nia")
    val rows = names.mapIndexed { index, name ->
        PlaytimeLeaderboardItem(
            rank = index + 1, customerId = "fixture-customer-$index", name = name,
            maskedPhone = "••••••${3210 + index}",
            totalPlayedMinutes = 750 - index * 60,
            qualifyingPaidMinutes = 720 - index * 60,
            draftEstimatedRewardMinutes = 0,
            recordedVisits = 12 - index,
        )
    }
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        CustomerLeaderboardPanel(
            state = CustomerPlaytimeUiState(
                items = rows, total = rows.size, nextPage = 2,
                program = PlaytimeProgramDraft(
                    thresholdPaidMinutes = 600, rewardMinutes = 60,
                    messageTemplatePreview = "",
                ),
            ),
            onRefresh = {}, onLoadMore = {}, modifier = Modifier.fillMaxSize(),
        )
    }
}
