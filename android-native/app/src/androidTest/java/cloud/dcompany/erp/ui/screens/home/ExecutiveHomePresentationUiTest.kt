package cloud.dcompany.erp.ui.screens.home

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val HomeDisplayIdArgument = "dcompany.testDisplayId"
private const val HomeSourceInventoryArgument = "dcompany.sourceInventorySha256"
private const val HomeTargetApkArgument = "dcompany.targetApkSha256"
private const val HomeTestApkArgument = "dcompany.testApkSha256"
private const val HomeCaptureRunArgument = "dcompany.captureRunId"
private const val HomeFetchedAtMillis = 1_792_758_600_000L

private val RequestedHomeDisplayId: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(HomeDisplayIdArgument)?.let { raw ->
        val requested = raw.toIntOrNull()
        require(requested in setOf(0, 2, 5, 6)) { "Only verified HD displays 0, 2, 5 and 6 are supported: $raw" }
        requested
    }
}

private fun <A : ComponentActivity> ActivityScenarioRule<A>.homeActivity(): A {
    var activity: A? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity)
}

private fun createHomeComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val activityRule = if (RequestedHomeDisplayId == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(RequestedHomeDisplayId!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(activityRule) { it.homeActivity() }
}

private data class HomeHostProof(
    val displayId: Int,
    val modeWidthPx: Int,
    val modeHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val bounds: Rect,
)

@RunWith(AndroidJUnit4::class)
class ExecutiveHomePresentationUiTest {
    @get:Rule
    val compose = createHomeComposeRule()

    @Test
    fun actualWorkspaceShellShowsApprovedHierarchyAndLowerPanelsInInitialViewport() {
        val refreshes = mutableStateOf(0)
        renderShell(
            widthDp = 1_280,
            heightDp = 800,
            state = populatedState(),
            onRetry = { refreshes.value += 1 },
        )
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace")).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
        capture("home-shell-1280x800", 1_280, 800, 1f)

        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        assertFullHeightRailAndRightBody()
        compose.onNodeWithTag("workspace-rail-Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find a module").assertIsDisplayed()
        compose.onNodeWithTag("workspace-date-time").assertIsDisplayed()
        compose.onNodeWithText("See today's operating overview").assertDoesNotExist()
        compose.onNodeWithText("Quick access").assertDoesNotExist()
        compose.onNodeWithContentDescription("Operational shortcuts").assertIsDisplayed()
        compose.onNodeWithTag("home-hero").assertIsDisplayed()
        compose.onNodeWithTag("home-hero-brand").assertDoesNotExist()
        compose.onNodeWithTag("workspace-background-ready-workspace").assertIsDisplayed()
        compose.onNodeWithTag("home-hero-status").assertIsDisplayed()
        val freshStatus = compose.onNodeWithTag("home-hero-status").getUnclippedBoundsInRoot()
        val freshStatusWidth = (freshStatus.right - freshStatus.left).value
        assertTrue(
            "fresh provenance must stay within the compact hero-edge allocation: $freshStatusWidth",
            freshStatusWidth in 432f..440f,
        )
        val heroBounds = compose.onNodeWithTag("home-hero").getUnclippedBoundsInRoot()
        assertTrue(
            "fresh provenance must remain aligned within its 22dp edge inset and 2dp content padding: $freshStatus vs $heroBounds",
            freshStatus.right <= heroBounds.right - 22.dp &&
                freshStatus.right >= heroBounds.right - 24.dp,
        )
        compose.onNodeWithContentDescription("Refresh dashboard snapshot")
            .assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText("Good evening, Nasih").assertIsDisplayed()
        val greetingBounds = compose.onNodeWithTag("home-greeting").getUnclippedBoundsInRoot()
        assertTrue(
            "greeting and provenance must retain separate hero allocations: $greetingBounds vs $freshStatus",
            greetingBounds.right + 16.dp <= freshStatus.left,
        )
        compose.onNodeWithText("Today's sales").assertIsDisplayed()
        compose.onNodeWithText("₹24,580.00").assertIsDisplayed()
        compose.onNodeWithText("Company live directory").assertIsDisplayed()
        compose.onNodeWithText("+17.05% vs previous day").assertIsDisplayed()
        compose.onNodeWithText("+14.29% vs previous day").assertIsDisplayed()
        compose.onNodeWithText("6 / 9").assertIsDisplayed()
        compose.onNodeWithText("66.7% · active and paused").assertIsDisplayed()
        compose.onNodeWithTag("home-session-capacity").assertIsDisplayed()
        compose.onNodeWithContentDescription("6 active or paused sessions out of 9 active station capacity")
            .assertIsDisplayed()
        compose.onNodeWithTag("home-activity-title-0", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals("Paid order")
        compose.onNodeWithText("POS ₹18,450.00").assertIsDisplayed()
        assertFullyInsideRoot("home-recent-activity")
        assertFullyInsideRoot("home-sales-trend")
        assertWideLowerPanelsFit(fontScale = 1f, stateLabel = "loaded", expectedHeight = 276.dp)
        val recentBounds = compose.onNodeWithTag("home-recent-activity").getUnclippedBoundsInRoot()
        val salesBounds = compose.onNodeWithTag("home-sales-trend").getUnclippedBoundsInRoot()
        assertEquals(
            "wide lower panels must retain a common baseline",
            (recentBounds.bottom - recentBounds.top).value.toDouble(),
            (salesBounds.bottom - salesBounds.top).value.toDouble(),
            1.0,
        )
        val widthRatio = (recentBounds.right - recentBounds.left).value /
            (salesBounds.right - salesBounds.left).value
        assertTrue("wide lower panels must retain the approved near-even proportion: $widthRatio", widthRatio in 0.94f..0.98f)
        assertApprovedWideGeometry()
        compose.onNodeWithTag("home-hour-profile").assertHeightIsAtLeast(100.dp)
        assertHourAxisAlignment()
        compose.onNodeWithText("Range ₹0.00 to ₹2,050.00").assertIsDisplayed()
        compose.onNodeWithTag("home-activity-4").assertIsDisplayed()
        compose.onNodeWithTag("home-activity-time-0", useUnmergedTree = true)
            .assertTextEquals("INV-1042 · 5:00 PM")
        listOf(
            "Today's sales",
            "+17.05% vs previous day",
            "Active sessions",
            "66.7% · active and paused",
            "Orders",
            "+14.29% vs previous day",
            "Customers",
            "Company live directory",
            "Paid order",
            "POS ₹18,450.00",
            "Snapshot 23 Oct 2026 · fetched ${expectedFetchedTimestamp()}",
        )
            .forEach(::assertAllHomeTextDoesNotOverflow)
        compose.onNodeWithTag("home-hero-brand").assertDoesNotExist()
        compose.onNodeWithTag("home-hero-brand-mark").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-brand-mark").assertIsDisplayed()
        assertFullyInsideRoot("workspace-rail-brand-mark")
        listOf("D COMPANY", "GAMING LOUNGE").forEach(::assertRailBrandTextDoesNotOverflow)
        recordAndAssertCompleteRailLabel("Customers", "workspace-rail-Customers", "night-font1_0")
        recordAndAssertCompleteRailLabel("Inventory", "workspace-rail-Inventory", "night-font1_0")
        compose.onNodeWithContentDescription("Refresh dashboard snapshot").performClick()
        compose.runOnIdle { assertEquals(1, refreshes.value) }
    }

    @Test
    fun masterAspectFixtureKeepsHomeCompositionInsideTheRightBody() {
        renderShell(
            widthDp = 1_280,
            heightDp = 563,
            state = populatedState(),
        )
        capture("home-shell-master-aspect-1280x563-initial", 1_280, 563, 1f)

        assertFullHeightRailAndRightBody()
        compose.onNodeWithTag("home-hero").assertIsDisplayed()
        val header = compose.onNodeWithTag("workspace-header").getUnclippedBoundsInRoot()
        val greeting = compose.onNodeWithTag("home-greeting").getUnclippedBoundsInRoot()
        val kpis = compose.onNodeWithTag("home-kpi-row").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("home-hero-brand").assertDoesNotExist()
        assertTrue("master-aspect greeting must clear the utility strip: $greeting vs $header", greeting.top >= header.bottom)
        assertTrue("master-aspect greeting must remain above the KPI band: $greeting vs $kpis", greeting.bottom <= kpis.top)
        compose.onNodeWithTag("home-kpi-row").assertIsDisplayed()
        compose.onNodeWithTag("home-shortcut-row").assertIsDisplayed()
        compose.onNodeWithTag("home-lower-row").performScrollTo().assertIsDisplayed()
        capture("home-shell-master-aspect-1280x563-lower-panels", 1_280, 563, 1f)
        assertWideLowerPanelsFit(
            fontScale = 1f,
            stateLabel = "loaded-short-wide",
            expectedHeight = 249.dp,
            scrollToLower = false,
        )
        val lower = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        assertTrue("master-aspect Home lower panels must stay right of the rail: $lower vs $rail", lower.left >= rail.right)
    }

    @Test
    fun reviewSafeHeightKeepsNewWideCompositionAndLowerPanelsInInitialViewport() {
        val reviewLabel = "Synthetic review values · disconnected from business systems"
        renderShell(
            widthDp = 1_280,
            heightDp = 746,
            state = populatedState(),
            reviewFixtureLabel = reviewLabel,
        )
        capture("home-shell-review-safe-1280x746", 1_280, 746, 1f)

        compose.onNodeWithTag("home-hero-brand").assertDoesNotExist()
        compose.onNodeWithText("Today's sales").assertIsDisplayed()
        compose.onNodeWithText("Quick access").assertDoesNotExist()
        compose.onNodeWithText(reviewLabel).assertDoesNotExist()
        compose.onNodeWithTag("home-recent-activity").assertIsDisplayed()
        compose.onNodeWithTag("home-sales-trend").assertIsDisplayed()
        assertWideLowerPanelsFit(
            fontScale = 1f,
            stateLabel = "loaded-review-safe",
            expectedHeight = 249.dp,
            scrollToLower = false,
        )
        val kpis = compose.onNodeWithTag("home-kpi-row").getUnclippedBoundsInRoot()
        val greeting = compose.onNodeWithText("Good evening, Nasih").getUnclippedBoundsInRoot()
        val location = compose.onNodeWithText("Main Shop · Verified branch workspace")
            .getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("home-hero-status").getUnclippedBoundsInRoot()
        val railSurface = compose.onNodeWithTag("workspace-navigation-rail-surface").getUnclippedBoundsInRoot()
        val railControls = compose.onNodeWithTag("workspace-navigation-rail-controls").getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithTag("workspace-rail-Settings").getUnclippedBoundsInRoot()
        val lower = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        assertTrue("greeting must clear KPI overlap: $greeting vs $kpis", greeting.bottom <= kpis.top)
        assertTrue("location must clear KPI overlap: $location vs $kpis", location.bottom <= kpis.top)
        assertTrue("state must clear KPI overlap: $status vs $kpis", status.bottom <= kpis.top)
        assertTrue("Settings must begin inside rail controls: $settings vs $railControls", settings.top >= railControls.top)
        assertTrue("Settings must end inside rail controls: $settings vs $railControls", settings.bottom <= railControls.bottom)
        assertEquals(
            "rail paint and interactive controls must share the full-height bottom boundary",
            railSurface.bottom.value.toDouble(),
            railControls.bottom.value.toDouble(),
            0.5,
        )
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        assertEquals("rail paint must reach the frame bottom", rail.bottom.value.toDouble(), railSurface.bottom.value.toDouble(), 0.5)
        assertTrue("Home lower panels must begin right of the full rail: $lower vs $rail", lower.left >= rail.right)
        compose.onNodeWithTag("workspace-rail-Settings")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText("Night mode").assertIsDisplayed()
        assertTaggedTextDoesNotOverflow("workspace-appearance-label")
        assertTaggedTextDoesNotOverflow("workspace-date-label")
        assertTaggedTextDoesNotOverflow("workspace-time-label")
        assertBounds("workspace-header", left = 192f, top = 8f, right = 1_272f, bottom = 64f, tolerance = 2f)
        assertBounds("home-kpi-row", left = 210f, top = 252.3f, right = 1_254f, bottom = 344.5f, tolerance = 2f)
        val reviewFrame = compose.onNodeWithTag("executive-home-frame").getUnclippedBoundsInRoot()
        val reviewLower = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        assertBounds(
            "home-lower-row",
            left = 208f,
            top = 473f,
            right = 1_256f,
            bottom = (reviewFrame.bottom - 16.dp).value,
            tolerance = 2f,
        )
        assertEquals("review-safe lower panels must use the remaining height", 16.0, (reviewFrame.bottom - reviewLower.bottom).value.toDouble(), 1.0)
    }

    @Test
    fun compactLargeTextScrollsWithoutClippingAndKeepsAuthorizedActionsReachable() {
        val selected = mutableStateOf<Destination?>(null)
        val allowed = listOf(Destination.Dashboard, Destination.Gaming, Destination.Pos, Destination.Reports)
        renderShell(600, 800, 1.3f, populatedState(), allowed) { selected.value = it }
        capture("home-shell-600x800-font1_3", 600, 800, 1.3f)

        compose.onNodeWithTag("workspace-navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("home-shortcut-Gaming").performScrollTo()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(Destination.Gaming, selected.value) }
        compose.onNodeWithTag("home-shortcut-Inventory").assertDoesNotExist()
        compose.onNodeWithTag("home-sales-trend").performScrollTo().assertIsDisplayed()
        listOf("Gross revenue", "Active sessions", "Orders", "Customers", "Recent activity", "POS sales by local hour")
            .forEach(::assertAllHomeTextDoesNotOverflow)
    }

    @Test
    fun capabilityRestrictedActivityStaysHonestWhileAggregateProfileRemainsVisible() {
        val restricted = populatedState().copy(
            activity = emptyList(),
            activityCapabilities = HomeActivityCapabilities("missing_permission", "missing_permission"),
        )
        renderShell(1_280, 800, state = restricted)

        compose.onNodeWithText("Your role cannot view recent operational activity").assertIsDisplayed()
        compose.onNodeWithText("Paid order").assertDoesNotExist()
        compose.onNodeWithTag("home-hour-profile").assertIsDisplayed()
        compose.onNodeWithText("POS ₹18,450.00").assertIsDisplayed()
        assertFullyInsideRoot("home-recent-activity")
        assertFullyInsideRoot("home-sales-trend")
    }

    @Test
    fun comparisonAndCapacityBoundaryStatesStayTruthfulInTheRenderedKpis() {
        val state = mutableStateOf(populatedState())
        renderShell(
            widthDp = 1_280,
            heightDp = 800,
            state = state.value,
            stateHolder = state,
        )

        compose.onNodeWithText("+17.05% vs previous day").assertIsDisplayed()
        compose.onNodeWithText("+14.29% vs previous day").assertIsDisplayed()

        compose.runOnIdle {
            state.value = populatedState().copy(
                aggregate = populatedState().aggregate!!.copy(
                    revenue = HomeAggregateMetric(2_000_000, 2_100_000, -4.25, "available"),
                    orders = HomeAggregateMetric(42, 42, 0.0, "available"),
                    activeStationCapacity = 0,
                    openSessions = 2,
                ),
                activity = populatedState().activity.take(2),
            )
        }
        compose.onNodeWithText("-4.25% vs previous day").assertIsDisplayed()
        compose.onNodeWithText("0.00% vs previous day").assertIsDisplayed()
        compose.onNodeWithText("0 active stations · active and paused").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 active or paused sessions; 0 active stations")
            .assertIsDisplayed()
        compose.onNodeWithTag("home-activity-1").assertIsDisplayed()
        compose.onNodeWithTag("home-activity-2").assertDoesNotExist()

        compose.runOnIdle {
            state.value = populatedState().copy(
                aggregate = populatedState().aggregate!!.copy(
                    revenue = HomeAggregateMetric(0, 0, null, "no_baseline"),
                    orders = HomeAggregateMetric(0, 0, null, "no_baseline"),
                    activeStationCapacity = 9,
                    openSessions = 12,
                ),
            )
        }
        assertEquals(
            "no-baseline metrics must not invent a percentage",
            0,
            compose.onAllNodes(hasText("vs previous day", substring = true)).fetchSemanticsNodes().size,
        )
        assertEquals(
            "revenue and orders must both expose the honest no-baseline state",
            2,
            compose.onAllNodes(hasText("No previous-day baseline")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("12 / 9").assertIsDisplayed()
        compose.onNodeWithText("133.3% · over capacity · active and paused").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "12 active or paused sessions; 9 active station capacity; over capacity",
        )
            .assertIsDisplayed()
    }

    @Test
    fun signedAndZeroProfilesRenderAroundTheRealZeroBaselineAndExposeEveryHour() {
        val signedHourly = (0..23).map { hour ->
            HomeHourPoint(
                hour = hour,
                revenueMinor = when (hour) {
                    0 -> -10_000L
                    23 -> 20_000L
                    else -> 0L
                },
                ordersCount = 0L,
            )
        }
        val state = mutableStateOf(
            populatedState().let { current ->
                current.copy(
                    aggregate = current.aggregate!!.copy(
                        profileRevenueMinor = 10_000L,
                        hourly = signedHourly,
                    ),
                )
            },
        )
        renderShell(1_280, 800, state = state.value, stateHolder = state)

        val profile = compose.onNodeWithTag("home-hour-profile").assertIsDisplayed()
        val description = profile.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description.contains("12am -₹100.00"))
        assertTrue(description.contains("11pm ₹200.00"))
        val signedBitmap = profile.captureToImage().asAndroidBitmap()
        try {
            val baseline = signedBitmap.height * 2f / 3f
            val goldRows = strongGoldRows(signedBitmap)
            assertTrue("positive facts must draw above zero: $goldRows", goldRows.any { it < baseline - 3f })
            assertTrue("negative facts must draw below zero: $goldRows", goldRows.any { it > baseline + 3f })
        } finally {
            signedBitmap.recycle()
        }

        compose.runOnIdle {
            val current = state.value
            state.value = current.copy(
                aggregate = current.aggregate!!.copy(
                    profileRevenueMinor = 0L,
                    hourly = (0..23).map { HomeHourPoint(it, 0L, 0L) },
                ),
            )
        }
        compose.waitForIdle()
        val zeroBitmap = compose.onNodeWithTag("home-hour-profile").captureToImage().asAndroidBitmap()
        try {
            val midpoint = zeroBitmap.height / 2f
            val goldRows = strongGoldRows(zeroBitmap)
            assertTrue("zero series must draw one flat baseline: $goldRows", goldRows.any { kotlin.math.abs(it - midpoint) <= 3f })
            assertTrue(
                "zero series must not invent positive or negative magnitude: $goldRows",
                goldRows.none { kotlin.math.abs(it - midpoint) > 5f },
            )
        } finally {
            zeroBitmap.recycle()
        }
    }

    @Test
    fun loadingBlockingErrorAndStaleSnapshotRemainTruthfulAndGeometricallyStable() {
        val state = mutableStateOf(populatedState())
        val refreshes = mutableStateOf(0)
        renderShell(
            1_280,
            800,
            state = state.value,
            stateHolder = state,
            onRetry = { refreshes.value += 1 },
        )
        val freshRefreshBounds = compose.onNodeWithTag("home-refresh").getUnclippedBoundsInRoot()
        compose.runOnIdle { state.value = populatedState().copy(loading = true) }
        capture("home-refreshing-1280x800", 1_280, 800, 1f)
        assertTaggedTextFullyVisible(
            tag = "home-refresh-label",
            text = "Refreshing verified figures…",
            maximumLines = 2,
        )
        assertTaggedTextFullyVisible(
            tag = "home-snapshot-label",
            text = "Snapshot 23 Oct 2026 · fetched ${expectedFetchedTimestamp()}",
            maximumLines = 1,
        )
        compose.onNodeWithTag("home-refresh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refreshing dashboard snapshot").assertIsNotEnabled()
        assertSameBounds("fresh to refreshing refresh action", freshRefreshBounds, "home-refresh")
        assertLoadingRefreshContrast("night-refreshing")
        compose.runOnIdle { state.value = populatedState() }
        compose.onNodeWithContentDescription("Refresh dashboard snapshot").assertIsEnabled()
        assertSameBounds("refreshing to fresh refresh action", freshRefreshBounds, "home-refresh")

        compose.runOnIdle { state.value = HomeUiState(loading = true) }
        capture("home-loading-1280x800", 1_280, 800, 1f)
        assertTaggedTextFullyVisible(
            tag = "home-refresh-label",
            text = "Loading verified figures…",
            maximumLines = 2,
        )
        assertTaggedTextFullyVisible(
            tag = "home-snapshot-label",
            text = "Verified snapshot unavailable",
            maximumLines = 1,
        )
        assertHeroStatusClearsKpis("night loading")
        compose.onNodeWithText("Revenue unavailable").assertIsDisplayed()
        compose.onNodeWithTag("home-refresh").assertIsDisplayed().assertIsNotEnabled()
        assertSameBounds("fresh to initial loading refresh action", freshRefreshBounds, "home-refresh")
        assertLoadingRefreshContrast("night-loading")
        assertWideLowerPanelsFit(fontScale = 1f, stateLabel = "unavailable", expectedHeight = 276.dp)

        compose.runOnIdle {
            state.value = HomeUiState(loading = false, error = "Couldn’t refresh the dashboard")
        }
        capture("home-error-1280x800", 1_280, 800, 1f)
        assertTaggedTextFullyVisible(
            tag = "home-state-banner",
            text = "Couldn’t refresh the dashboard",
            maximumLines = 2,
        )
        assertTaggedTextFullyVisible(
            tag = "home-snapshot-label",
            text = "Verified snapshot unavailable",
            maximumLines = 1,
        )
        assertHeroStatusClearsKpis("night error")
        compose.onNodeWithTag("home-refresh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry dashboard snapshot").assertIsDisplayed()
        assertSameBounds("fresh to error refresh action", freshRefreshBounds, "home-refresh")
        assertHeroStateContrast("night-error")
        assertWideLowerPanelsFit(fontScale = 1f, stateLabel = "error", expectedHeight = 276.dp)
        compose.onNodeWithTag("home-refresh").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, refreshes.value) }

        compose.runOnIdle {
            state.value = populatedState().copy(
                loading = false,
                error = "Live refresh unavailable",
                activity = emptyList(),
                activityCapabilities = null,
                stale = true,
            )
        }
        capture("home-stale-1280x800", 1_280, 800, 1f)
        assertTaggedTextFullyVisible(
            tag = "home-state-banner",
            text = "Live refresh unavailable",
            maximumLines = 2,
        )
        compose.onNodeWithText("₹24,580.00").assertIsDisplayed()
        compose.onNodeWithText("Live activity is unavailable offline").assertIsDisplayed()
        assertTaggedTextFullyVisible(
            tag = "home-snapshot-label",
            text = "Saved 23 Oct 2026 · fetched ${expectedFetchedTimestamp()}",
            maximumLines = 1,
        )
        assertHeroStatusClearsKpis("night stale")
        compose.onNodeWithTag("home-refresh").assertIsDisplayed()
        assertSameBounds("fresh to stale refresh action", freshRefreshBounds, "home-refresh")
        assertHeroStateContrast("night-stale")
        compose.onNodeWithTag("home-refresh").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(2, refreshes.value) }
        assertFullyInsideRoot("home-recent-activity")
    }

    @Test
    fun wideLargeTextUnavailablePanelsKeepEveryLineAndAnalyticsActionInsideTheCap() {
        val state = mutableStateOf(populatedState())
        renderShell(
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.3f,
            state = state.value,
            stateHolder = state,
        )

        assertWideLowerPanelsFit(fontScale = 1.3f, stateLabel = "loaded", expectedHeight = 312.dp)
        compose.runOnIdle { state.value = HomeUiState(loading = true) }
        assertWideLowerPanelsFit(fontScale = 1.3f, stateLabel = "unavailable", expectedHeight = 312.dp)
        compose.runOnIdle {
            state.value = HomeUiState(
                loading = false,
                error = "Couldn’t refresh the dashboard",
            )
        }
        assertWideLowerPanelsFit(fontScale = 1.3f, stateLabel = "error", expectedHeight = 312.dp)
    }

    @Test
    fun dayLargeTextReducedHeightKeepsLongGreetingAndEssentialActionsReachable() {
        val selected = mutableStateOf<Destination?>(null)
        val state = mutableStateOf(populatedState().copy(loading = true))
        val employeeName = "AlexandertheGreatOperationsLead Singh"
        renderShell(
            widthDp = 1_280,
            heightDp = 520,
            fontScale = 1.3f,
            state = state.value,
            stateHolder = state,
            employeeName = employeeName,
            appearance = AppearancePreference.Day,
            onNavigate = { selected.value = it },
        )
        capture("home-shell-1280x520-font1_3-day", 1_280, 520, 1.3f)

        val greeting = "Good evening, AlexandertheGreatOperationsLead"
        compose.onNodeWithText(greeting).assertIsDisplayed()
        val greetingBounds = compose.onNodeWithTag("home-greeting").getUnclippedBoundsInRoot()
        val kpiBounds = compose.onNodeWithTag("home-kpi-row").getUnclippedBoundsInRoot()
        assertTrue(
            "wrapped large-text greeting must clear the KPI overlap: $greetingBounds vs $kpiBounds",
            greetingBounds.bottom <= kpiBounds.top,
        )
        compose.onNodeWithTag("home-hero-brand").assertDoesNotExist()
        compose.onNodeWithText("Today's sales").assertIsDisplayed()
        compose.onNodeWithText("Quick access").assertDoesNotExist()
        assertAllHomeTextDoesNotOverflow(greeting)
        assertTaggedTextFullyVisible("home-greeting", greeting, maximumLines = 2)
        assertTaggedTextDoesNotOverflow("workspace-appearance-label")
        assertTaggedTextDoesNotOverflow("workspace-date-label")
        assertTaggedTextDoesNotOverflow("workspace-time-label")
        compose.onNodeWithTag("home-refresh").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        assertLoadingRefreshContrast("day-loading-font1_3")
        compose.runOnIdle {
            state.value = HomeUiState(
                loading = false,
                error = "Couldn’t refresh the dashboard",
            )
        }
        capture("home-error-1280x520-font1_3-day", 1_280, 520, 1.3f)
        assertTaggedTextFullyVisible(
            tag = "home-state-banner",
            text = "Couldn’t refresh the dashboard",
            maximumLines = 2,
        )
        assertTaggedTextFullyVisible(
            tag = "home-snapshot-label",
            text = "Verified snapshot unavailable",
            maximumLines = 1,
        )
        assertHeroStatusClearsKpis("day error")
        assertHeroStateContrast("day-error-font1_3")
        compose.runOnIdle {
            state.value = populatedState().copy(
                loading = false,
                error = "Live refresh unavailable",
                activity = emptyList(),
                activityCapabilities = null,
                stale = true,
            )
        }
        capture("home-stale-1280x520-font1_3-day", 1_280, 520, 1.3f)
        assertTaggedTextFullyVisible(
            tag = "home-state-banner",
            text = "Live refresh unavailable",
            maximumLines = 2,
        )
        assertTaggedTextFullyVisible(
            tag = "home-snapshot-label",
            text = "Saved 23 Oct 2026 · fetched ${expectedFetchedTimestamp()}",
            maximumLines = 2,
        )
        assertHeroStatusClearsKpis("day stale")
        assertHeroStateContrast("day-stale-font1_3")
        compose.onNodeWithTag("workspace-rail-Customers").assertHeightIsAtLeast(48.dp)
        recordAndAssertCompleteRailLabel("Customers", "workspace-rail-Customers", "day-font1_3")
        recordAndAssertCompleteRailLabel("Inventory", "workspace-rail-Inventory", "day-font1_3")
        compose.onNodeWithTag("home-shortcut-Reports").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(Destination.Reports, selected.value) }
        compose.onNodeWithTag("home-recent-activity").performScrollTo().assertIsDisplayed()
        assertFullyInsideRoot("home-recent-activity")
    }

    @Test
    fun railLargeTextNightRetainsCompleteCustomerLabelAndTarget() {
        renderShell(
            widthDp = 1_280,
            heightDp = 800,
            fontScale = 1.3f,
            state = populatedState(),
            appearance = AppearancePreference.Night,
        )
        capture("home-rail-1280x800-font1_3-night", 1_280, 800, 1.3f)

        compose.onNodeWithTag("workspace-rail-Customers")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        recordAndAssertCompleteRailLabel("Customers", "workspace-rail-Customers", "night-font1_3")
        recordAndAssertCompleteRailLabel("Inventory", "workspace-rail-Inventory", "night-font1_3")

        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("workspace-navigation-rail-controls").getUnclippedBoundsInRoot()
        val lowerBefore = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        assertEquals("Home rail controls must reach the frame bottom", rail.bottom.value.toDouble(), controls.bottom.value.toDouble(), 0.5)
        val hostDensity = compose.activity.resources.displayMetrics.density
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(x = 250f * hostDensity, y = 700f * hostDensity),
                end = Offset(x = 250f * hostDensity, y = 540f * hostDensity),
                durationMillis = 350,
            )
        }
        compose.waitForIdle()
        val lowerAfter = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        assertTrue(
            "a swipe inside the right body must reach the real Home scroller: before=$lowerBefore after=$lowerAfter",
            lowerAfter.top < lowerBefore.top - 20.dp,
        )
        assertTrue("scrolled lower panels must remain right of the rail: $lowerAfter vs $rail", lowerAfter.left >= rail.right)
    }

    @Test
    fun permissionFilteredShortcutsAndAnalyticsActionPreserveExactCallbackIdentity() {
        val selected = mutableStateOf<Destination?>(null)
        val allowed = listOf(Destination.Dashboard, Destination.Customers, Destination.Analytics)
        val destinationState = mutableStateOf(Destination.entries.toList())
        renderShell(
            1_280,
            800,
            state = populatedState(),
            destinations = destinationState.value,
            destinationsHolder = destinationState,
        ) { selected.value = it }
        val unrestrictedCard = compose.onNodeWithTag("home-shortcut-Customers").getUnclippedBoundsInRoot()
        compose.runOnIdle { destinationState.value = allowed }

        capture("home-restricted-shortcuts-1280x800", 1_280, 800, 1f)

        compose.onNodeWithTag("home-shortcut-Customers").assertHeightIsAtLeast(48.dp).performClick()
        val restrictedCard = compose.onNodeWithTag("home-shortcut-Customers").getUnclippedBoundsInRoot()
        val restrictedWidth = (restrictedCard.right - restrictedCard.left).value
        assertEquals(
            "permission filtering must retain the same six-slot card width",
            (unrestrictedCard.right - unrestrictedCard.left).value.toDouble(),
            restrictedWidth.toDouble(),
            1.0,
        )
        assertEquals(
            "permission filtering must retain the destination's slot position",
            unrestrictedCard.left.value.toDouble(),
            restrictedCard.left.value.toDouble(),
            1.0,
        )
        compose.runOnIdle { assertEquals(Destination.Customers, selected.value) }
        compose.onNodeWithTag("home-shortcut-Gaming").assertDoesNotExist()
        compose.onNodeWithTag("home-open-analytics").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(Destination.Analytics, selected.value) }
    }

    private fun populatedState() = HomeUiState(
        loading = false,
        aggregate = HomeAggregateSnapshot(
            schemaVersion = 1,
            ownerUserId = "review-user",
            ownerCompanyId = "review-company",
            branchId = "11111111-1111-1111-1111-111111111111",
            terminalId = "22222222-2222-2222-2222-222222222222",
            businessDate = "2026-10-23",
            timezone = "Asia/Kolkata",
            snapshotAtMillis = HomeFetchedAtMillis,
            revenue = HomeAggregateMetric(2_458_000, 2_100_000, 17.05, "available"),
            orders = HomeAggregateMetric(48, 42, 14.29, "available"),
            customerCount = 312,
            customerScope = "company_live_directory",
            customerComparisonStatus = "unavailable_no_immutable_history",
            activeStationCapacity = 9,
            openSessions = 6,
            dayLengthMinutes = 1_440,
            profileBasis = "pos_sales_tip_exclusive_local_hour_profile",
            profileReconcilesToDailyGross = false,
            profileRevenueMinor = 1_845_000,
            profileOrdersCount = 36,
            hourly = (0..23).map { hour ->
                HomeHourPoint(hour, if (hour in 12..20) 205_000 else 0, if (hour in 12..20) 4 else 0)
            },
        ),
        fetchedAtMillis = HomeFetchedAtMillis,
        activity = listOf(
            HomeActivity("pos_order_paid", Instant.parse("2026-10-23T11:30:00Z"), "INV-1042", "Paid order", 24_500, "terminal"),
            HomeActivity("gaming_session_started", Instant.parse("2026-10-23T11:25:00Z"), "PS5 · 03", "Gaming session started", null, "branch"),
            HomeActivity("pos_order_paid", Instant.parse("2026-10-23T11:20:00Z"), "INV-1041", "Paid order", 18_900, "terminal"),
            HomeActivity("gaming_session_started", Instant.parse("2026-10-23T11:15:00Z"), "PS5 · 07", "Gaming session started", null, "branch"),
            HomeActivity("pos_shift_opened", Instant.parse("2026-10-23T11:10:00Z"), "Till 02", "POS shift opened", null, "terminal"),
        ),
        activityCapabilities = HomeActivityCapabilities("available", "available"),
    )

    private fun renderShell(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
        state: HomeUiState,
        destinations: List<Destination> = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
            Destination.Reports,
            Destination.Analytics,
            Destination.Settings,
            Destination.Help,
        ),
        destinationsHolder: MutableState<List<Destination>>? = null,
        stateHolder: MutableState<HomeUiState>? = null,
        employeeName: String = "Nasih Mohammed with a deliberately long operator name",
        appearance: AppearancePreference = AppearancePreference.Night,
        reviewFixtureLabel: String? = null,
        onRetry: () -> Unit = {},
        onNavigate: (Destination) -> Unit = {},
    ) {
        val host = assertHost()
        compose.setContent {
            val activeDestinations = destinationsHolder?.value ?: destinations
            CompositionLocalProvider(LocalDensity provides Density(host.density, fontScale)) {
                DCompanyTheme(appearance) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(widthDp.dp).height(heightDp.dp)) {
                            WorkspaceScaffold(
                                destinations = activeDestinations,
                                currentDestination = Destination.Dashboard,
                                employeeName = employeeName,
                                locationLabel = "Main Shop · Verified branch workspace",
                                connectivityProblem = SyncAvailabilityProblem.NONE,
                                outboxWorkStatus = OutboxWorkStatus(),
                                syncing = false,
                                canChangeTill = false,
                                appearancePreference = appearance,
                                onAppearancePreferenceChanged = {},
                                onOpenSupport = {},
                                onChangeTill = {},
                                onSignOut = {},
                                onDestinationChanged = onNavigate,
                            ) { _, navigateTo ->
                                ExecutiveHomeWorkspace(
                                    employeeName = employeeName,
                                    locationLabel = "Main Shop · Verified branch workspace",
                                    state = stateHolder?.value ?: state,
                                    destinations = activeDestinations,
                                    navigateTo = navigateTo,
                                    onRetry = onRetry,
                                    hour = 18,
                                    reviewFixtureLabel = reviewFixtureLabel,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun expectedFetchedTimestamp(): String = Instant.ofEpochMilli(HomeFetchedAtMillis)
        .atZone(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a z", Locale.ENGLISH))

    private fun strongGoldRows(bitmap: Bitmap): Set<Int> = buildSet {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (
                    android.graphics.Color.red(pixel) >= 190 &&
                    android.graphics.Color.green(pixel) >= 145 &&
                    android.graphics.Color.blue(pixel) <= 145
                ) {
                    add(y)
                    break
                }
            }
        }
    }

    private fun assertHost(): HomeHostProof {
        val activity = compose.activity
        var proof: HomeHostProof? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val display = activity.display ?: error("Host activity has no display")
            val metrics = activity.resources.displayMetrics
            val bounds = activity.windowManager.currentWindowMetrics.bounds
            proof = HomeHostProof(
                displayId = display.displayId,
                modeWidthPx = display.mode.physicalWidth,
                modeHeightPx = display.mode.physicalHeight,
                densityDpi = metrics.densityDpi,
                density = metrics.density,
                bounds = Rect(bounds),
            )
        }
        return checkNotNull(proof).also {
            if (RequestedHomeDisplayId != null) {
                assertEquals(RequestedHomeDisplayId, it.displayId)
                assertEquals(2_560, it.modeWidthPx)
                assertEquals(1_600, it.modeHeightPx)
                assertEquals(320, it.densityDpi)
                assertEquals(2f, it.density, 0.001f)
                assertEquals(2_560, it.bounds.width())
                assertEquals(1_600, it.bounds.height())
            }
        }
    }

    private fun assertFullyInsideRoot(tag: String) {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val node = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("$tag must begin inside root: $node vs $root", node.left >= root.left && node.top >= root.top)
        assertTrue("$tag must end inside root: $node vs $root", node.right <= root.right && node.bottom <= root.bottom)
    }

    private fun assertApprovedWideGeometry() {
        val frameBottom = compose.onNodeWithTag("executive-home-frame").getUnclippedBoundsInRoot().bottom
        assertBounds(
            "workspace-navigation-rail",
            left = 8f,
            top = 8f,
            right = 192f,
            bottom = frameBottom.value,
            tolerance = 2f,
        )
        assertBounds("workspace-navigation-rail-surface", left = 8f, top = 8f, right = 192f, bottom = frameBottom.value, tolerance = 2f)
        assertBounds("workspace-navigation-rail-controls", left = 8f, top = 8f, right = 192f, bottom = frameBottom.value, tolerance = 2f)
        assertBounds("workspace-header", left = 192f, top = 8f, right = 1_272f, bottom = 64f, tolerance = 2f)
        assertBounds("home-hero", left = 192f, top = 8f, right = 1_272f, bottom = 318f, tolerance = 2f)
        assertBounds("home-kpi-row", left = 210f, top = 268f, right = 1_254f, bottom = 364f, tolerance = 2f)
        assertBounds("home-shortcut-row", left = 210f, top = 380f, right = 1_254f, bottom = 484f, tolerance = 2f)
        assertBounds(
            "home-lower-row",
            left = 208f,
            top = 500f,
            right = 1_256f,
            bottom = 776f,
            tolerance = 2f,
        )
    }

    private fun assertHourAxisAlignment() {
        val chart = compose.onNodeWithTag("home-hour-profile").getUnclippedBoundsInRoot()
        val plotLeft = chart.left + 2.dp
        val plotRight = chart.right - 2.dp
        listOf(0, 6, 12, 18, 23).forEach { hour ->
            val label = compose.onNodeWithTag("home-hour-label-$hour")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val expectedAnchor = plotLeft + (plotRight - plotLeft) * (hour.toFloat() / 23f)
            val actualAnchor = when (hour) {
                0 -> label.left
                23 -> label.right
                else -> label.left + (label.right - label.left) / 2f
            }
            assertEquals(
                "hour $hour label must align with its chart grid coordinate",
                expectedAnchor.value.toDouble(),
                actualAnchor.value.toDouble(),
                1.0,
            )
        }
    }

    private fun assertWideLowerPanelsFit(
        fontScale: Float,
        stateLabel: String,
        expectedHeight: androidx.compose.ui.unit.Dp,
        scrollToLower: Boolean = fontScale > 1.15f,
    ) {
        val lowerNode = compose.onNodeWithTag("home-lower-row")
        if (scrollToLower) lowerNode.performScrollTo()
        val lower = lowerNode.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(
            "$stateLabel font $fontScale lower-panel remaining height",
            expectedHeight.value.toDouble(),
            (lower.bottom - lower.top).value.toDouble(),
            1.0,
        )
        val content = if (stateLabel.startsWith("loaded")) {
            listOf(
                Triple("home-recent-activity", "Recent activity", 1),
                Triple("home-sales-trend", "POS sales by local hour", 1),
                Triple("home-sales-trend", "POS ₹18,450.00", 1),
                Triple("home-sales-trend", "Open Analytics", 1),
            )
        } else {
            listOf(
                Triple("home-recent-activity", "Recent activity", 1),
                Triple("home-recent-activity", "Live activity is unavailable offline", 3),
                Triple("home-sales-trend", "POS sales by local hour", 1),
                Triple("home-sales-trend", "Hourly POS profile unavailable", 2),
                Triple("home-sales-trend", "Open Analytics", 1),
            )
        }
        content.forEach { (panelTag, text, maximumLines) ->
            assertTextLayoutInsidePanel(panelTag, text, maximumLines, stateLabel, fontScale)
        }
        listOf("home-recent-activity", "home-sales-trend").forEach { panelTag ->
            val panelBounds = compose.onNodeWithTag(panelTag).getUnclippedBoundsInRoot()
            val icons = compose.onAllNodes(
                hasTestTag("$panelTag-header-icon") and hasAnyAncestor(hasTestTag(panelTag)),
                useUnmergedTree = true,
            )
            assertEquals(
                "$panelTag must render only its header icon",
                1,
                icons.fetchSemanticsNodes().size,
            )
            val iconBounds = icons[0].assertIsDisplayed().getUnclippedBoundsInRoot()
            assertPositiveBounds("$panelTag header icon", iconBounds)
            assertBoundsInside("$panelTag header icon", iconBounds, panelBounds)
        }
        val lowerBounds = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        val salesBounds = compose.onNodeWithTag("home-sales-trend").getUnclippedBoundsInRoot()
        val actionBounds = compose.onNodeWithTag("home-open-analytics")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val supportingText = if (stateLabel.startsWith("loaded")) "POS ₹18,450.00" else "Hourly POS profile unavailable"
        val secondaryBounds = compose.onAllNodes(
            hasText(supportingText) and
                hasAnyAncestor(hasTestTag("home-sales-trend")),
            useUnmergedTree = true,
        )[0].getUnclippedBoundsInRoot()
        assertPositiveBounds("Analytics action", actionBounds)
        assertBoundsInside("Analytics action in sales panel", actionBounds, salesBounds)
        assertBoundsInside("Analytics action in lower row", actionBounds, lowerBounds)
        assertTrue(
            "supporting copy must clear the Analytics action: $secondaryBounds vs $actionBounds",
            secondaryBounds.bottom <= actionBounds.top,
        )
        assertFullyInsideRoot("home-lower-row")
        if (stateLabel.startsWith("loaded")) {
            assertLoadedLowerContentFits(fontScale, stateLabel)
        }
    }

    private fun assertLoadedLowerContentFits(fontScale: Float, stateLabel: String) {
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        val lowerBounds = compose.onNodeWithTag("home-lower-row").getUnclippedBoundsInRoot()
        val activityBounds = compose.onNodeWithTag("home-recent-activity").getUnclippedBoundsInRoot()
        val salesBounds = compose.onNodeWithTag("home-sales-trend").getUnclippedBoundsInRoot()
        val expectedActivityTimes = listOf(
            "INV-1042 · 5:00 PM",
            "PS5 · 03 · 4:55 PM",
            "INV-1041 · 4:50 PM",
            "PS5 · 07 · 4:45 PM",
            "Till 02 · 4:40 PM",
        )
        val expectedActivityTitles = listOf(
            "Paid order",
            "Gaming session started",
            "Paid order",
            "Gaming session started",
            "POS shift opened",
        )
        expectedActivityTimes.forEachIndexed { index, expectedText ->
            val rowBounds = compose.onNodeWithTag("home-activity-$index")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            assertPositiveBounds("activity row $index", rowBounds)
            assertBoundsInside("activity row $index in activity panel", rowBounds, activityBounds)
            assertBoundsInside("activity row $index in lower row", rowBounds, lowerBounds)
            assertBoundsInside("activity row $index in root", rowBounds, rootBounds)
            assertTaggedTextInkFitsOwner(
                tag = "home-activity-title-$index",
                expectedText = expectedActivityTitles[index],
                ownerTag = "home-recent-activity",
                stateLabel = stateLabel,
                fontScale = fontScale,
            )
            assertTaggedTextInkFitsOwner(
                tag = "home-activity-time-$index",
                expectedText = expectedText,
                ownerTag = "home-recent-activity",
                stateLabel = stateLabel,
                fontScale = fontScale,
            )
        }

        val chartBounds = compose.onNodeWithTag("home-hour-profile")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertPositiveBounds("sales chart", chartBounds)
        assertBoundsInside("sales chart in sales panel", chartBounds, salesBounds)
        assertBoundsInside("sales chart in root", chartBounds, rootBounds)
        val axisBounds = compose.onNodeWithTag("home-hour-axis")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertPositiveBounds("hour axis", axisBounds)
        assertTrue("hour axis must retain at least its 16dp touch-free label band: $axisBounds", axisBounds.bottom - axisBounds.top >= 16.dp)
        assertBoundsInside("hour axis in sales panel", axisBounds, salesBounds)
        assertBoundsInside("hour axis in root", axisBounds, rootBounds)
        listOf(0 to "12am", 6 to "6am", 12 to "12pm", 18 to "6pm", 23 to "11pm")
            .forEach { (hour, label) ->
                assertTaggedTextInkFitsOwner(
                    tag = "home-hour-label-$hour",
                    expectedText = label,
                    ownerTag = "home-sales-trend",
                    stateLabel = stateLabel,
                    fontScale = fontScale,
                )
            }
        assertHourAxisAlignment()
        assertTaggedTextInkFitsOwner(
            tag = "home-profile-total",
            expectedText = "POS ₹18,450.00",
            ownerTag = "home-sales-trend",
            stateLabel = stateLabel,
            fontScale = fontScale,
        )
        assertTaggedTextInkFitsOwner(
            tag = "home-profile-range",
            expectedText = "Range ₹0.00 to ₹2,050.00",
            ownerTag = "home-sales-trend",
            stateLabel = stateLabel,
            fontScale = fontScale,
        )
        val actionLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("home-open-analytics-label", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals("Open Analytics")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(actionLayouts) }
        assertEquals("Analytics action label must expose one text layout", 1, actionLayouts.size)
        assertEquals(
            "Night Analytics action must use the Home body foreground",
            ExecutiveHomeFinish.Text,
            actionLayouts.single().layoutInput.style.color,
        )
    }

    private fun assertTaggedTextInkFitsOwner(
        tag: String,
        expectedText: String,
        ownerTag: String,
        stateLabel: String,
        fontScale: Float,
    ) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals(expectedText)
        val nodeBounds = node.getUnclippedBoundsInRoot()
        val ownerBounds = compose.onNodeWithTag(ownerTag).getUnclippedBoundsInRoot()
        val rootBounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertPositiveBounds(tag, nodeBounds)
        assertBoundsInside("$tag in $ownerTag", nodeBounds, ownerBounds)
        assertBoundsInside("$tag in root", nodeBounds, rootBounds)

        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$tag must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertFalse("$tag must not overflow vertically for $stateLabel at $fontScale", layout.didOverflowHeight)
        repeat(layout.lineCount) { line ->
            assertFalse("$tag line $line must not ellipsize", layout.isLineEllipsized(line))
            assertTrue(
                "$tag line $line must fit its integer layout width: " +
                    "left=${layout.getLineLeft(line)}, right=${layout.getLineRight(line)}, width=${layout.size.width}",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f,
            )
        }
        assertEquals(
            "$tag must retain every visible character",
            expectedText.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true),
        )
        expectedText.indices.filterNot { expectedText[it].isWhitespace() }.forEach { offset ->
            val glyph = layout.getBoundingBox(offset)
            assertTrue(
                "$tag glyph $offset must fit its integer layout: $glyph vs ${layout.size}",
                glyph.left >= -1f && glyph.top >= -1f &&
                    glyph.right <= layout.size.width + 1f && glyph.bottom <= layout.size.height + 1f,
            )
        }
    }

    private fun assertPositiveBounds(description: String, bounds: androidx.compose.ui.unit.DpRect) {
        assertTrue("$description must have positive width: $bounds", bounds.right > bounds.left)
        assertTrue("$description must have positive height: $bounds", bounds.bottom > bounds.top)
    }

    private fun assertBoundsInside(
        description: String,
        child: androidx.compose.ui.unit.DpRect,
        owner: androidx.compose.ui.unit.DpRect,
    ) {
        assertTrue("$description must start inside its owner: $child vs $owner", child.left >= owner.left && child.top >= owner.top)
        assertTrue("$description must end inside its owner: $child vs $owner", child.right <= owner.right && child.bottom <= owner.bottom)
    }

    private fun assertTextLayoutInsidePanel(
        panelTag: String,
        text: String,
        maximumLines: Int,
        stateLabel: String,
        fontScale: Float,
    ) {
        val nodes = compose.onAllNodes(
            hasText(text) and hasAnyAncestor(hasTestTag(panelTag)),
            useUnmergedTree = true,
        )
        assertEquals("$panelTag must contain exactly one '$text' node", 1, nodes.fetchSemanticsNodes().size)
        val node = nodes[0].assertIsDisplayed()
        val textBounds = node.getUnclippedBoundsInRoot()
        val panelBounds = compose.onNodeWithTag(panelTag).getUnclippedBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$text must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertTrue("$text must use at most $maximumLines lines", layout.lineCount <= maximumLines)
        assertFalse(
            "$text must not overflow vertically for $stateLabel at $fontScale: " +
                "size=${layout.size}, lines=${layout.lineCount}",
            layout.didOverflowHeight,
        )
        repeat(layout.lineCount) { line ->
            assertFalse("$text line $line must not ellipsize", layout.isLineEllipsized(line))
        }
        assertEquals(
            "$text must retain every character",
            text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = true),
        )
        val horizontalRoundingTolerancePx = 1f
        val lineLeft = (0 until layout.lineCount).minOf(layout::getLineLeft)
        val lineRight = (0 until layout.lineCount).maxOf(layout::getLineRight)
        val paragraphWidth = layout.multiParagraph.width
        val glyphBounds = text.indices.filterNot { text[it].isWhitespace() }
            .map(layout::getBoundingBox)
        val glyphLeft = glyphBounds.minOf { it.left }
        val glyphTop = glyphBounds.minOf { it.top }
        val glyphRight = glyphBounds.maxOf { it.right }
        val glyphBottom = glyphBounds.maxOf { it.bottom }
        val paragraphWidthDelta = paragraphWidth - layout.size.width
        val lineRightDelta = lineRight - layout.size.width
        val glyphRightDelta = glyphRight - layout.size.width
        val evidenceRole = when (text) {
            "Recent activity" -> "recent-title"
            "Paid order" -> "recent-detail"
            "Live activity is unavailable offline" -> "recent-offline"
            "POS sales by local hour" -> "sales-title"
            "Hourly POS profile unavailable" -> "sales-empty"
            "POS ₹18,450.00" -> "sales-support"
            "Open Analytics" -> "analytics-action"
            else -> error("Unreviewed Home lower-panel evidence text")
        }
        outputDirectory().resolve(
            "home-lower-$stateLabel-font-${String.format(Locale.US, "%.1f", fontScale)}-$evidenceRole-layout.json",
        ).writeText(
            """
            {
              "panel_tag": "$panelTag",
              "role": "$evidenceRole",
              "font_scale": ${String.format(Locale.US, "%.1f", fontScale)},
              "layout_width_px": ${layout.size.width},
              "paragraph_width_px": ${String.format(Locale.US, "%.4f", paragraphWidth)},
              "paragraph_width_delta_px": ${String.format(Locale.US, "%.4f", paragraphWidthDelta)},
              "line_left_px": ${String.format(Locale.US, "%.4f", lineLeft)},
              "max_line_right_px": ${String.format(Locale.US, "%.4f", lineRight)},
              "line_right_delta_px": ${String.format(Locale.US, "%.4f", lineRightDelta)},
              "glyph_left_px": ${String.format(Locale.US, "%.4f", glyphLeft)},
              "glyph_top_px": ${String.format(Locale.US, "%.4f", glyphTop)},
              "max_glyph_right_px": ${String.format(Locale.US, "%.4f", glyphRight)},
              "glyph_right_delta_px": ${String.format(Locale.US, "%.4f", glyphRightDelta)},
              "glyph_bottom_px": ${String.format(Locale.US, "%.4f", glyphBottom)},
              "reported_overflow_width": ${layout.didOverflowWidth},
              "reported_overflow_height": ${layout.didOverflowHeight},
              "line_count": ${layout.lineCount},
              "visible_character_end": ${layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)},
              "text_length": ${text.length}
            }
            """.trimIndent() + "\n",
        )
        assertTrue(
            "$text line advances must fit its integer layout width for $stateLabel at $fontScale: " +
                "line=[$lineLeft,$lineRight], width=${layout.size.width}, " +
                "reportedOverflow=${layout.didOverflowWidth}",
            lineLeft >= -horizontalRoundingTolerancePx &&
                lineRight <= layout.size.width + horizontalRoundingTolerancePx,
        )
        assertTrue(
            "$text glyph bounds must fit its allocated layout for $stateLabel at $fontScale: " +
                "glyph=[$glyphLeft,$glyphTop,$glyphRight,$glyphBottom], size=${layout.size}",
            glyphLeft >= -horizontalRoundingTolerancePx &&
                glyphTop >= -horizontalRoundingTolerancePx &&
                glyphRight <= layout.size.width + horizontalRoundingTolerancePx &&
                glyphBottom <= layout.size.height + horizontalRoundingTolerancePx,
        )
        assertTrue("$text must start inside $panelTag: $textBounds vs $panelBounds", textBounds.left >= panelBounds.left && textBounds.top >= panelBounds.top)
        assertTrue("$text must end inside $panelTag: $textBounds vs $panelBounds", textBounds.right <= panelBounds.right && textBounds.bottom <= panelBounds.bottom)
    }

    private fun assertFullHeightRailAndRightBody() {
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        val surface = compose.onNodeWithTag("workspace-navigation-rail-surface").getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("workspace-navigation-rail-controls").getUnclippedBoundsInRoot()
        val home = compose.onNodeWithTag("executive-home-workspace").getUnclippedBoundsInRoot()
        assertEquals("fixed rail width", 184.0, (rail.right - rail.left).value.toDouble(), 0.5)
        listOf("surface" to surface, "controls" to controls).forEach { (label, bounds) ->
            assertEquals("$label left", rail.left.value.toDouble(), bounds.left.value.toDouble(), 0.5)
            assertEquals("$label right", rail.right.value.toDouble(), bounds.right.value.toDouble(), 0.5)
            assertEquals("$label top", rail.top.value.toDouble(), bounds.top.value.toDouble(), 0.5)
            assertEquals("$label bottom", rail.bottom.value.toDouble(), bounds.bottom.value.toDouble(), 0.5)
        }
        assertTrue("Home body must begin right of the full-height rail: $home vs $rail", home.left >= rail.right)
    }

    private fun assertBounds(
        tag: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        tolerance: Float = 1.5f,
    ) {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertEquals("$tag left", left.toDouble(), bounds.left.value.toDouble(), tolerance.toDouble())
        assertEquals("$tag top", top.toDouble(), bounds.top.value.toDouble(), tolerance.toDouble())
        assertEquals("$tag right", right.toDouble(), bounds.right.value.toDouble(), tolerance.toDouble())
        assertEquals("$tag bottom", bottom.toDouble(), bounds.bottom.value.toDouble(), tolerance.toDouble())
    }

    private fun assertSameBounds(
        description: String,
        expected: androidx.compose.ui.unit.DpRect,
        actualTag: String,
    ) {
        val actual = compose.onNodeWithTag(actualTag).getUnclippedBoundsInRoot()
        assertEquals("$description left", expected.left.value.toDouble(), actual.left.value.toDouble(), 0.5)
        assertEquals("$description top", expected.top.value.toDouble(), actual.top.value.toDouble(), 0.5)
        assertEquals("$description right", expected.right.value.toDouble(), actual.right.value.toDouble(), 0.5)
        assertEquals("$description bottom", expected.bottom.value.toDouble(), actual.bottom.value.toDouble(), 0.5)
    }

    private fun assertHeroStatusClearsKpis(state: String) {
        val status = compose.onNodeWithTag("home-hero-status").getUnclippedBoundsInRoot()
        val kpis = compose.onNodeWithTag("home-kpi-row").getUnclippedBoundsInRoot()
        assertTrue("$state hero status must clear the KPI row: $status vs $kpis", status.bottom <= kpis.top)
    }

    private fun assertAllHomeTextDoesNotOverflow(text: String) {
        val nodes = compose.onAllNodes(
            hasText(text) and hasAnyAncestor(hasTestTag("executive-home-workspace")),
            useUnmergedTree = true,
        )
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("$text must expose at least one text layout", count > 0)
        val scrollableCount = compose.onAllNodes(
            hasText(text) and hasAnyAncestor(hasTestTag("executive-home-workspace")) and
                hasAnyAncestor(hasScrollAction()),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        repeat(count) { index ->
            val results = mutableListOf<TextLayoutResult>()
            val interaction = nodes[index]
            if (scrollableCount == count) interaction.performScrollTo() else interaction.assertIsDisplayed()
            interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals("$text node $index must expose one text layout", 1, results.size)
            val layout = results.single()
            assertFalse("$text node $index must not overflow vertically", layout.didOverflowHeight)
            repeat(layout.lineCount) { line ->
                assertFalse("$text node $index line $line must not ellipsize", layout.isLineEllipsized(line))
                assertTrue(
                    "$text node $index line $line must fit its integer layout width: " +
                        "left=${layout.getLineLeft(line)}, right=${layout.getLineRight(line)}, width=${layout.size.width}",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f,
                )
            }
            assertEquals(
                "$text node $index must retain every visible character",
                text.length,
                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true),
            )
            text.indices.filterNot { text[it].isWhitespace() }.forEach { offset ->
                val glyph = layout.getBoundingBox(offset)
                assertTrue(
                    "$text node $index glyph $offset must fit its integer layout: $glyph vs ${layout.size}",
                    glyph.left >= -1f && glyph.top >= -1f &&
                        glyph.right <= layout.size.width + 1f && glyph.bottom <= layout.size.height + 1f,
                )
            }
        }
    }

    private fun assertRailBrandTextDoesNotOverflow(text: String) {
        val nodes = compose.onAllNodes(
            hasText(text) and hasAnyAncestor(hasTestTag("workspace-navigation-rail-controls")),
            useUnmergedTree = true,
        )
        val count = nodes.fetchSemanticsNodes().size
        assertEquals("$text must have one shared-rail brand text node", 1, count)
        val results = mutableListOf<TextLayoutResult>()
        nodes[0].assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("$text rail brand must expose one text layout", 1, results.size)
        assertFalse("$text rail brand must not overflow", results.single().hasVisualOverflow)
    }

    private fun assertTaggedTextDoesNotOverflow(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("$tag must expose one text layout", 1, results.size)
        assertFalse("$tag must not overflow", results.single().hasVisualOverflow)
    }

    private fun assertTaggedTextFullyVisible(tag: String, text: String, maximumLines: Int) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals(text)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("$tag must expose one text layout", 1, results.size)
        val result = results.single()
        assertTrue("$tag must use no more than $maximumLines lines", result.lineCount <= maximumLines)
        assertFalse("$tag must not overflow", result.hasVisualOverflow)
        repeat(result.lineCount) { line ->
            assertFalse("$tag line $line must not ellipsize", result.isLineEllipsized(line))
        }
        assertEquals(
            "$tag must retain every visible character",
            text.length,
            result.getLineEnd(result.lineCount - 1, visibleEnd = true),
        )
    }

    private fun recordAndAssertCompleteRailLabel(text: String, parentTag: String, evidenceStem: String) {
        val nodes = compose.onAllNodes(
            hasText(text) and hasAnyAncestor(hasTestTag(parentTag)),
            useUnmergedTree = true,
        )
        assertEquals("$text must have one rail label", 1, nodes.fetchSemanticsNodes().size)
        val results = mutableListOf<TextLayoutResult>()
        nodes[0].assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val result = results.single()
        val lineEnd = result.getLineEnd(0, visibleEnd = false)
        val visibleEnd = result.getLineEnd(0, visibleEnd = true)
        val destination = parentTag.removePrefix("workspace-rail-")
        outputDirectory().resolve("home-rail-$destination-$evidenceStem-text-layout.json").writeText(
            """
            {
              "text": "$text",
              "parent_tag": "$parentTag",
              "width_px": ${result.size.width},
              "height_px": ${result.size.height},
              "did_overflow_width": ${result.didOverflowWidth},
              "did_overflow_height": ${result.didOverflowHeight},
              "has_visual_overflow": ${result.hasVisualOverflow},
              "is_line_ellipsized": ${result.isLineEllipsized(0)},
              "line_end": $lineEnd,
              "visible_end": $visibleEnd,
              "text_length": ${text.length}
            }
            """.trimIndent() + "\n",
        )
        assertFalse("$text rail label must not overflow vertically", result.didOverflowHeight)
        assertFalse("$text rail label must not ellipsize", result.isLineEllipsized(0))
        assertEquals("$text rail label must retain every character", text.length, lineEnd)
        assertEquals("$text rail label must visibly retain every character", text.length, visibleEnd)
    }

    private fun assertLoadingRefreshContrast(evidenceStem: String) {
        val button = compose.onNodeWithTag("home-refresh").captureToImage().asAndroidBitmap()
        val status = compose.onNodeWithTag("home-status-glass").captureToImage().asAndroidBitmap()
        val label = compose.onNodeWithTag("home-refresh-label", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val indicator = compose.onNodeWithTag("home-refresh-indicator", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        try {
            val buttonBackground = dominantOpaqueColor(button)
            val statusBackground = dominantOpaqueColor(status)
            val labelProof = contrastProof(label, statusBackground, 4.5)
            val indicatorProof = contrastProof(indicator, buttonBackground, 3.0)
            outputDirectory().resolve("home-refresh-$evidenceStem-contrast.json").writeText(
                """
                {
                  "source_inventory_sha256": ${jsonPin(HomeSourceInventoryArgument)},
                  "target_apk_sha256": ${jsonPin(HomeTargetApkArgument)},
                  "test_apk_sha256": ${jsonPin(HomeTestApkArgument)},
                  "button_background_argb": "${String.format(Locale.US, "%08X", buttonBackground)}",
                  "status_background_argb": "${String.format(Locale.US, "%08X", statusBackground)}",
                  "label_peak_contrast": ${String.format(Locale.US, "%.3f", labelProof.first)},
                  "label_pixels_at_4_5": ${labelProof.second},
                  "indicator_peak_contrast": ${String.format(Locale.US, "%.3f", indicatorProof.first)},
                  "indicator_pixels_at_3_0": ${indicatorProof.second}
                }
                """.trimIndent() + "\n",
            )
            assertTrue("Refresh loading label contrast must be at least 4.5:1", labelProof.first >= 4.5)
            assertTrue("Refresh loading label must retain resolved core pixels", labelProof.second >= 8)
            assertTrue("Refresh loading indicator contrast must be at least 3:1", indicatorProof.first >= 3.0)
            assertTrue("Refresh loading indicator must retain resolved stroke pixels", indicatorProof.second > 0)
        } finally {
            button.recycle()
            status.recycle()
            label.recycle()
            indicator.recycle()
        }
    }

    private fun assertHeroStateContrast(evidenceStem: String) {
        val status = compose.onNodeWithTag("home-status-glass").captureToImage().asAndroidBitmap()
        val state = compose.onNodeWithTag("home-state-banner", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(status)
            val proof = contrastProof(state, background, 4.5)
            outputDirectory().resolve("home-state-$evidenceStem-contrast.json").writeText(
                """
                {
                  "source_inventory_sha256": ${jsonPin(HomeSourceInventoryArgument)},
                  "target_apk_sha256": ${jsonPin(HomeTargetApkArgument)},
                  "test_apk_sha256": ${jsonPin(HomeTestApkArgument)},
                  "status_background_argb": "${String.format(Locale.US, "%08X", background)}",
                  "state_peak_contrast": ${String.format(Locale.US, "%.3f", proof.first)},
                  "state_pixels_at_4_5": ${proof.second}
                }
                """.trimIndent() + "\n",
            )
            assertTrue("Hero state text contrast must be at least 4.5:1", proof.first >= 4.5)
            assertTrue("Hero state text must retain resolved core pixels", proof.second >= 8)
        } finally {
            status.recycle()
            state.recycle()
        }
    }

    private fun dominantOpaqueColor(bitmap: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(color) >= 250) counts[color] = (counts[color] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: error("Refresh capture has no opaque pixels")
    }

    private fun contrastProof(bitmap: Bitmap, background: Int, threshold: Double): Pair<Double, Int> {
        var peak = 1.0
        var pixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(color) < 250) continue
            val contrast = contrastRatio(color, background)
            peak = max(peak, contrast)
            if (contrast >= threshold) pixels += 1
        }
        return peak to pixels
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (kotlin.math.min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(android.graphics.Color.red(argb)) +
            0.7152 * channel(android.graphics.Color.green(argb)) +
            0.0722 * channel(android.graphics.Color.blue(argb))
    }

    private fun capture(name: String, widthDp: Int, heightDp: Int, fontScale: Float) {
        compose.waitUntil(12_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace")).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasTestTag("workspace-glass-ready-workspace")).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        val host = assertHost()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertEquals((widthDp * host.density).roundToInt(), bitmap.width)
            assertEquals((heightDp * host.density).roundToInt(), bitmap.height)
            val directory = outputDirectory()
            val image = directory.resolve("$name.png")
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            directory.resolve("$name.provenance.json").writeText(
                """
                {
                  "phase": "C2_HOME_FIDELITY",
                  "capture_api": "ComposeTestRule.onRoot().captureToImage()",
                  "test_class": "cloud.dcompany.erp.ui.screens.home.ExecutiveHomePresentationUiTest",
                  "base_commit": "c0c2616855815541a11c3100444f1404d71abb74",
                  "source_inventory_sha256": ${jsonPin(HomeSourceInventoryArgument)},
                  "target_apk_sha256": ${jsonPin(HomeTargetApkArgument)},
                  "test_apk_sha256": ${jsonPin(HomeTestApkArgument)},
                  "application_id": "${BuildConfig.APPLICATION_ID}",
                  "device": "${Build.MANUFACTURER} ${Build.MODEL}",
                  "android_api": ${Build.VERSION.SDK_INT},
                  "workspace_background_slot": "WORKSPACE",
                  "bundled_home_asset_sha256": "ce0f12e8b46011eaa69161c031d23d10e545f9b89f2f49a6effb11f34640dcfe",
                  "bundled_home_asset_dimensions_px": "3840x2160",
                  "requested_display_id": ${RequestedHomeDisplayId ?: "null"},
                  "observed_display_id": ${host.displayId},
                  "display_mode_width_px": ${host.modeWidthPx},
                  "display_mode_height_px": ${host.modeHeightPx},
                  "activity_density_dpi": ${host.densityDpi},
                  "window_bounds": "${host.bounds.flattenToString()}",
                  "expected_width_dp": $widthDp,
                  "expected_height_dp": $heightDp,
                  "font_scale": $fontScale,
                  "actual_width_px": ${bitmap.width},
                  "actual_height_px": ${bitmap.height},
                  "png_file": "${image.name}",
                  "png_bytes": ${image.length()},
                  "png_sha256": "${sha256(image)}"
                }
                """.trimIndent() + "\n",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun outputDirectory(): File {
        val profile = RequestedHomeDisplayId?.let { "display-$it-hd" } ?: "default-display"
        val runId = InstrumentationRegistry.getArguments().getString(HomeCaptureRunArgument)
            ?: error("Missing $HomeCaptureRunArgument")
        require(runId.matches(Regex("[0-9a-f]{32}"))) { "Invalid $HomeCaptureRunArgument" }
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-c2-home-fidelity-screenshots-v7").resolve(profile)
            .resolve(runId)
            .apply { check(isDirectory || mkdirs()) }
    }

    private fun jsonPin(key: String): String {
        val value = InstrumentationRegistry.getArguments().getString(key)
        if (RequestedHomeDisplayId != null) {
            require(value?.matches(Regex("[0-9a-f]{64}")) == true) { "Missing or invalid $key" }
        }
        return value?.let { "\"$it\"" } ?: "null"
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
