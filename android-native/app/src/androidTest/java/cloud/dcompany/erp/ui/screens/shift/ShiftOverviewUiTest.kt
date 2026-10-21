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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ShiftCloseBlockerCounts
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShiftOverviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unresolvedCurrentShiftDoesNotExposeOpenAction() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(900.dp).height(620.dp)) {
                    ShiftLoadingWorkspace()
                }
            }
        }

        compose.onNodeWithTag("shift-loading-workspace").assertIsDisplayed()
        compose.onNodeWithText("Checking shift").assertIsDisplayed()
        compose.onAllNodesWithText("Open shift", substring = true).assertCountEquals(0)
    }

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
    fun readinessAndReviewAreVisibleBeforeCollectionDetailsOnCompactTablet() {
        compose.setContent { ShiftFixture(width = 872, height = 400, onReview = {}) }
        val readiness = compose.onNodeWithText("Close review").assertIsDisplayed().getUnclippedBoundsInRoot()
        val review = compose.onNodeWithText("Review & close shift").assertIsDisplayed().getUnclippedBoundsInRoot()
        val collections = compose.onNodeWithText("Net collections").getUnclippedBoundsInRoot()
        assertTrue(readiness.top < review.top)
        assertTrue("The next action precedes financial detail", review.bottom <= collections.top)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun uncountedDrawerIsNeutralAndCloseExplainsTheRequiredNextAction() {
        var neutral = Color.Unspecified
        compose.setContent {
            DCompanyTheme {
                neutral = MaterialTheme.colorScheme.onSurfaceVariant
                val state = shiftFixtureState()
                Box(Modifier.width(900.dp).height(620.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        CloseShiftCard(state, true, true, true, remember { BringIntoViewRequester() },
                            ShiftCloseCallbacks({}, {}, {}, {}, {},
                                { state.open?.let(::shiftCloseUiIdentity) }, {}))
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Not counted yet").performScrollTo().performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { it(layouts) }
        assertEquals(neutral, layouts.single().layoutInput.style.color)
        compose.onNodeWithTag("shift-close-next-action").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close shift").assertIsNotEnabled()
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
    fun shiftOpenedOnAnotherAppShowsOriginBlockerInsteadOfReadyClose() {
        compose.setContent {
            ShiftFixture(
                width = 872,
                height = 400,
                originMessage = "This shift was opened in Web. Close it from the opening app.",
                onReview = {},
            )
        }

        compose.onNodeWithText("Close from the opening app").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review closing device").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Count cash · server review still required").assertCountEquals(0)
        compose.onAllNodesWithText("Review & close shift").assertCountEquals(0)
    }

    @Test
    fun mixedPosLegacyRefundsAndViewOnlyPermissionRemainInExpandedReconciliation() {
        val baseline = shiftFixtureState(canClose = false)
        val mixedShift = requireNotNull(baseline.open).copy(
            expectedMinor = 9_000L,
            posCollectionsMinor = 20_000L,
            membershipCollectionsMinor = 5_000L,
            grossCollectionsMinor = 25_000L,
            cashCollectionsMinor = 9_000L,
            cardCollectionsMinor = 6_000L,
            upiCollectionsMinor = 7_000L,
            otherCollectionsMinor = 3_000L,
            settledPosRefundsMinor = 2_000L,
            settledMembershipRefundsMinor = 1_000L,
            totalRefundsMinor = 3_000L,
            netCollectionsMinor = 22_000L,
        )
        val state = baseline.copy(open = mixedShift, expectedMinor = 9_000L)
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(872.dp).height(620.dp)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ShiftDashboardOverview(
                            state = state,
                            canClose = false,
                            presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy(),
                            onRefresh = {},
                            onReview = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("POS and gaming receipts (may include tips)").assertDoesNotExist()
        compose.onNodeWithTag("shift-reconciliation-details-toggle")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("shift-reconciliation-details").assertIsDisplayed()
        compose.onNodeWithText("POS and gaming receipts (may include tips)")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Legacy/other receipts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settled legacy/other refunds").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settled POS refunds").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Historical hidden-module money remains included", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review only · close permission required")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review shift").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun missingServerAccountingStaysUnavailable() {
        compose.setContent {
            ShiftFixture(width = 872, height = 400, accountingAvailable = false, onReview = {})
        }

        compose.onNodeWithText("Reconnect to verify collections. No payment total is assumed.")
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Unavailable").assertCountEquals(2)
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
        compose.onNodeWithText("Close shift").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Not counted yet").assertIsDisplayed()
        compose.onNodeWithText("Count cash").performClick()
        compose.onNodeWithText("Use drawer count").performScrollTo().performClick()
        compose.onNodeWithText("Close shift").assertIsDisplayed().assertIsEnabled()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun cachedHeldBlockerStaysReadableAndDisablesOnlyFinalCloseUntilClearedAtLargeText() {
        val empty = ShiftCloseBlockerCounts(0, 0, 0, 0, 0, 0)
        val state = mutableStateOf(shiftFixtureState().copy(closeBlockers = empty.copy(serverHeldOrderCount = 1)))
        var reviewed = false
        compose.setContent {
            DCompanyTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(872.dp).height(400.dp).testTag("shift-blocker-review-viewport")) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            ShiftDashboardOverview(state.value, onRefresh = {}, onReview = { reviewed = true })
                            CloseShiftCard(state.value, true, true, true,
                                remember { BringIntoViewRequester() },
                                ShiftCloseCallbacks({}, {}, {}, {}, {},
                                    { state.value.open?.let(::shiftCloseUiIdentity) }, {}))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("1 saved task needs review").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("shift-cached-close-blockers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review & close shift").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(reviewed) }
        compose.onNodeWithText("Not counted yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close shift").assertIsNotEnabled()
        compose.onNodeWithText("Count cash").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Use drawer count").performScrollTo().performClick()
        compose.onNodeWithTag("shift-close-next-action").performScrollTo().assertIsDisplayed()
        val close = compose.onNodeWithText("Close shift").performScrollTo().assertIsDisplayed()
            .assertIsNotEnabled().getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("shift-blocker-review-viewport").getUnclippedBoundsInRoot()
        assertTrue(close.top >= viewport.top && close.bottom <= viewport.bottom)
        assertTrue(close.bottom - close.top >= 48.dp)
        compose.runOnIdle { state.value = state.value.copy(closeBlockers = empty) }
        // The deliberate empty-drawer count survives; clearing a blocker never invents a count.
        compose.onNodeWithText("Not counted yet").assertDoesNotExist()
        compose.onNodeWithText("Close shift").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Count cash · server review still required").performScrollTo().assertIsDisplayed()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun unfinishedDrawerCountSurvivesRealWorkspaceTabsAndSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        val state = shiftFixtureState()
        val destination = mutableStateOf(Destination.Shift)

        restoration.setContent {
            DCompanyTheme {
                Box(Modifier.width(900.dp).height(620.dp)) {
                    WorkspaceScaffold(
                        destinations = listOf(Destination.Shift, Destination.Gaming),
                        currentDestination = destination.value,
                        employeeName = "Fixture closer",
                        locationLabel = "Fixture till",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(),
                        syncing = false,
                        canChangeTill = false,
                        onOpenSupport = {},
                        onChangeTill = {},
                        onSignOut = {},
                        onDestinationChanged = { destination.value = it },
                    ) { activeDestination, _ ->
                        when (activeDestination) {
                            Destination.Shift -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            ) {
                                CloseShiftCard(
                                    state = state,
                                    canClosePermission = true,
                                    canOpenPermission = true,
                                    compactLayout = true,
                                    reviewAnchor = remember { BringIntoViewRequester() },
                                    callbacks = ShiftCloseCallbacks(
                                        onRetryRejectedOpen = {},
                                        onVerifyAndClearRejectedOpen = {},
                                        onContinueShift = {},
                                        onRetryClose = {},
                                        onRefresh = {},
                                        currentShiftIdentity = {
                                            state.open?.let(::shiftCloseUiIdentity)
                                        },
                                        onCloseShift = {},
                                    ),
                                )
                            }
                            Destination.Gaming -> Box(
                                Modifier.fillMaxSize().testTag("workspace-away-from-shift"),
                            )
                            else -> error("Unexpected fixture destination: $activeDestination")
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Count cash").performScrollTo().performClick()
        repeat(2) {
            compose.onNodeWithContentDescription("Increase Count of ₹500 notes by one")
                .performScrollTo().assertIsDisplayed().performClick()
        }
        compose.onNodeWithText("Use drawer count").performScrollTo().performClick()
        compose.onNodeWithText("₹1,000.00").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Gaming. Manage stations and sessions").performClick()
        compose.onNodeWithTag("workspace-away-from-shift").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shift. Open, review and close today's shift").performClick()
        compose.onNodeWithText("Edit cash count").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("₹1,000.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit cash count").performClick()
        compose.onNodeWithContentDescription("Count of ₹500 notes")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("2"),
                ),
            )
        compose.onNodeWithText("Cancel").performClick()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Edit cash count").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("₹1,000.00").performScrollTo().assertIsDisplayed()
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
    originMessage: String? = null,
    onReview: () -> Unit,
    afterOverview: @Composable (ShiftUiState) -> Unit = {},
) {
    val state = shiftFixtureState(accountingAvailable, rejectedOpen, canClose, originMessage)
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

private fun shiftFixtureState(
    accountingAvailable: Boolean = true,
    rejectedOpen: Boolean = false,
    canClose: Boolean = true,
    originMessage: String? = null,
): ShiftUiState {
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
    return ShiftUiState(
        open = shift,
        currentShiftLoaded = true,
        online = true,
        expectedMinor = if (accountingAvailable) 10_000 else null,
        canClose = canClose && originMessage == null,
        closeOriginMessage = originMessage,
        rejectedShift = local.copy(
            localId = "rejected-open-attempt",
            serverShiftId = null,
            state = ShiftState.OPEN_REJECTED,
            lastError = "Duplicate open attempt needs review",
        ).takeIf { rejectedOpen },
    )
}
