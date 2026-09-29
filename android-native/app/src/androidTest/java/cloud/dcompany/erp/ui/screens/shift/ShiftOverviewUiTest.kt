package cloud.dcompany.erp.ui.screens.shift

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShiftOverviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun overviewAt1280ShowsServerAmountsAndReviewAction() {
        var reviewed = false
        compose.setContent { ShiftFixture(width = 900, height = 620, onReview = { reviewed = true }) }

        compose.onAllNodesWithText("₹100.00").assertCountEquals(2)
        compose.onNodeWithText("₹140.00").assertIsDisplayed()
        compose.onNodeWithText("₹240.00").assertIsDisplayed()
        screenshot("shift-overview-1280.png")
        compose.onNodeWithText("Review & close shift").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(reviewed) }
    }

    @Test
    fun overviewAt960KeepsReviewReachable() {
        var reviewed = false
        compose.setContent { ShiftFixture(width = 872, height = 400, onReview = { reviewed = true }) }

        screenshot("shift-overview-960.png")
        compose.onNodeWithText("Review & close shift").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(reviewed) }
    }

    @Test
    fun viewerCanReviewWithoutBeingPromisedShiftClosure() {
        compose.setContent {
            ShiftFixture(width = 872, height = 400, canClose = false, onReview = {})
        }

        compose.onNodeWithText("Review shift").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Review & close shift").assertCountEquals(0)
    }

    @Test
    fun missingServerAccountingStaysUnavailable() {
        compose.setContent {
            ShiftFixture(width = 872, height = 400, accountingAvailable = false, onReview = {})
        }

        compose.onNodeWithText("Collection breakdown is unavailable", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }

    @Test
    fun rejectedOpenOverviewOffersSavedAttemptReview() {
        var reviewed = false
        compose.setContent {
            ShiftFixture(
                width = 872,
                height = 400,
                rejectedOpen = true,
                onReview = { reviewed = true },
            )
        }

        compose.onNodeWithText("Saved open needs review").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review saved open").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(reviewed) }
        compose.onAllNodesWithText("Review & close shift").assertCountEquals(0)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun reviewAndCloseScrollsPastVisibleHeadingToDrawerAndCloseControls() {
        compose.setContent {
            val reviewTarget = remember { BringIntoViewRequester() }
            val scope = rememberCoroutineScope()
            ShiftFixture(
                width = 900,
                height = 620,
                onReview = { scope.launch { reviewTarget.bringIntoView() } },
                afterOverview = { state ->
                    Box(Modifier.fillMaxWidth().testTag("production-close-card")) {
                        CloseShiftCard(
                            state = state,
                            canClosePermission = true,
                            canOpenPermission = true,
                            compactLayout = true,
                            presentation = WorkspaceFeatureProfiles.Active.presentationPolicy(),
                            reviewAnchor = reviewTarget,
                            callbacks = ShiftCloseCallbacks(
                                onRetryRejectedOpen = {},
                                onVerifyAndClearRejectedOpen = {},
                                onContinueShift = {},
                                onRetryClose = {},
                                onRefresh = {},
                                currentShiftIdentity = { state.open?.let(::shiftCloseUiIdentity) },
                                onCloseShift = {},
                            ),
                        )
                    }
                },
            )
        }

        compose.onNodeWithText("Count cash").assertIsNotDisplayed()
        compose.onNodeWithText("Close shift").assertIsNotDisplayed()
        compose.onNodeWithText("Review & close shift").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Count cash").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Close shift").assertIsDisplayed().assertIsEnabled()
    }

    private fun screenshot(filename: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), filename)
        val bitmap = compose.onNodeWithTag("shift-overview-fixture").captureToImage().asAndroidBitmap()
        try {
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}

@Composable
private fun ShiftFixture(
    width: Int,
    height: Int,
    accountingAvailable: Boolean = true,
    rejectedOpen: Boolean = false,
    canClose: Boolean = true,
    onReview: () -> Unit,
    afterOverview: @Composable (ShiftUiState) -> Unit = {},
) {
    val local = LocalShiftEntity(
        localId = "test-shift",
        serverShiftId = "confirmed-shift",
        openingFloatMinor = 0,
        openedAtMillis = 1_700_000_000_000,
        state = ShiftState.OPEN_SYNCED,
    )
    val shift = ResolvedOpenShift(
        shiftId = "confirmed-shift",
        source = ShiftSource.LOCAL_OUTBOX,
        local = local,
        openedAtMillis = local.openedAtMillis,
        openingFloatMinor = 0,
        expectedMinor = if (accountingAvailable) 10_000 else null,
        posCollectionsMinor = 24_000L.takeIf { accountingAvailable },
        membershipCollectionsMinor = 0L.takeIf { accountingAvailable },
        grossCollectionsMinor = 24_000L.takeIf { accountingAvailable },
        cashCollectionsMinor = 10_000L.takeIf { accountingAvailable },
        cardCollectionsMinor = 0L.takeIf { accountingAvailable },
        upiCollectionsMinor = 14_000L.takeIf { accountingAvailable },
        otherCollectionsMinor = 0L.takeIf { accountingAvailable },
        settledPosRefundsMinor = 0L.takeIf { accountingAvailable },
        settledMembershipRefundsMinor = 0L.takeIf { accountingAvailable },
        totalRefundsMinor = 0L.takeIf { accountingAvailable },
        netCollectionsMinor = 24_000L.takeIf { accountingAvailable },
        openedByUserId = "staff-1",
        openedByName = "Admin",
        openedByEmail = null,
    )
    val state = ShiftUiState(
        open = shift,
        online = true,
        expectedMinor = if (accountingAvailable) 10_000 else null,
        canClose = canClose,
        rejectedShift = local.copy(
            localId = "rejected-open-attempt",
            serverShiftId = null,
            state = ShiftState.OPEN_REJECTED,
            lastError = "Duplicate open attempt needs review",
        ).takeIf { rejectedOpen },
    )
    DCompanyTheme {
        Box(Modifier.width(width.dp).height(height.dp).testTag("shift-overview-fixture")) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ShiftDashboardOverview(
                    state = state,
                    canClose = canClose,
                    onRefresh = {},
                    onReview = onReview,
                )
                afterOverview(state)
            }
        }
    }
}
