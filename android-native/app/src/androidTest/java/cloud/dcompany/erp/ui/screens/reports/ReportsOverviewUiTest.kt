package cloud.dcompany.erp.ui.screens.reports

import android.app.ActivityOptions
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.ReportsAccess
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.ReportSnapshotEntity
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.allowedDestinations
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.presentationPolicy
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundPresets
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

private const val ReportsDisplayArg = "dcompany.testDisplayId"
private const val ReportsSourceArg = "dcompany.sourceInventorySha256"
private const val ReportsTargetArg = "dcompany.targetApkSha256"
private const val ReportsTestArg = "dcompany.testApkSha256"
private const val ReportsRunArg = "dcompany.captureRunId"
private val ReportsRequestedDisplay: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(ReportsDisplayArg)?.let { raw ->
        val id = raw.toIntOrNull()
        require(id in setOf(0, 2, 5, 6)) { "Only verified displays 0, 2, 5 and 6 are supported: $raw" }
        id
    }
}

private fun createReportsRule(): AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> {
    val rule = if (ReportsRequestedDisplay == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(ReportsRequestedDisplay!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(rule) { scenarioRule ->
        var activity: ComponentActivity? = null
        scenarioRule.scenario.onActivity {
            // Match MainActivity's production edge-to-edge contract. This keeps
            // the Compose viewport at the full physical 1280x800dp rather than
            // manufacturing a stretched capture around system-bar insets.
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            activity = it
        }
        checkNotNull(activity)
    }
}

@RunWith(AndroidJUnit4::class)
class ReportsOverviewUiTest {
    @get:Rule
    val compose = createReportsRule()

    @Test
    fun reportSnapshotPrefixesDeleteOnlyTheirAuthorizedSurface() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reportSnapshotDao()
            val rows = listOf(
                ReportSnapshotEntity("reports:overview:v2:branch-a:2026-10-27:2026-10-27", "overview", 1L),
                ReportSnapshotEntity("reports:pnl:v2:branch-a:daily:2026-10-27:2026-10-27", "pnl", 2L),
                ReportSnapshotEntity("reports:pnl:v2:branch-a:costing", "costing", 3L),
                ReportSnapshotEntity("home:v1:branch-a:2026-10-27", "home", 4L),
            )
            rows.forEach { dao.put(it) }

            assertEquals(1, dao.deleteWithPrefix("reports:overview:v2:branch-a:"))
            assertEquals("pnl", dao.get(rows[1].key)?.jsonBody)
            assertEquals("costing", dao.get(rows[2].key)?.jsonBody)
            assertEquals("home", dao.get(rows[3].key)?.jsonBody)

            dao.put(rows[0])
            assertEquals(2, dao.deleteWithPrefix("reports:pnl:v2:branch-a:"))
            assertEquals("overview", dao.get(rows[0].key)?.jsonBody)
            assertEquals("home", dao.get(rows[3].key)?.jsonBody)
        } finally {
            db.close()
        }
    }

    @Test
    fun wideLoadedArtworkRendersSourceBackedOverviewAndRealSelectors() {
        var exports = 0
        val surface = mutableStateOf<ReportsSurface?>(null)
        val period = mutableStateOf<ReportPeriod?>(null)
        val expectedDestinations = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Refunds,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
            Destination.Menu,
            Destination.Finance,
            Destination.Reports,
            Destination.Staff,
            Destination.Settings,
            Destination.AuditLog,
            Destination.SupportInbox,
            Destination.Help,
        )
        assertEquals(
            expectedDestinations,
            allowedDestinations(reportsOwnerProfile(), WorkspaceFeatureProfiles.GamingCentre),
        )
        render(1_280, 800, 1f, wideState(), realisticOwnerNavigation = true,
            onExport = { exports++ }) { selectedSurface, selectedPeriod ->
            if (selectedSurface != null) surface.value = selectedSurface
            if (selectedPeriod != null) period.value = selectedPeriod
        }
        awaitArtwork()
        capture("reports-overview-wide-1280x800", 1_280, 800, 1f)

        compose.onNodeWithTag("reports-title").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-export").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled()
            .performClick()
        assertEquals(1, exports)
        compose.onNodeWithText("Analyze your lounge performance").assertIsDisplayed()
        assertEquals(
            "the shared rail must render its original logo exactly once",
            1,
            compose.onAllNodes(hasTestTag("workspace-rail-brand-mark")).fetchSemanticsNodes().size,
        )
        listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
            Destination.Reports,
            Destination.Settings,
        ).forEach { destination ->
            compose.onNodeWithTag("workspace-rail-${destination.name}").assertIsDisplayed()
        }
        compose.onNodeWithTag("workspace-more-modules").assertIsDisplayed()
        compose.onNodeWithText("Gross revenue").assertIsDisplayed()
        compose.onNodeWithText("Linked customers").assertIsDisplayed()
        compose.onNodeWithText("Sales included in chart 15.00").assertExists()
        compose.onNodeWithText("Peak 18:00 · 14.00").assertExists()
        compose.onNodeWithText("1440 minute", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-hour-panel").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-source-panel").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-hour-chart").assertHeightIsAtLeast(100.dp)
        assertHourTickCoordinates()
        val hourlyBounds = compose.onNodeWithTag("reports-overview-hour-panel").getUnclippedBoundsInRoot()
        val sourceBounds = compose.onNodeWithTag("reports-overview-source-panel").getUnclippedBoundsInRoot()
        assertEquals("Wide charts must share their top edge", hourlyBounds.top, sourceBounds.top)
        assertEquals("Wide charts must share their bottom edge", hourlyBounds.bottom, sourceBounds.bottom)
        listOf("products" to "Top products", "payment" to "Payments received", "devices" to "Device usage").forEach { (panel, title) ->
            val panelBounds = compose.onNodeWithTag("reports-overview-$panel-panel").getUnclippedBoundsInRoot()
            val titleBounds = compose.onNodeWithText(title).getUnclippedBoundsInRoot()
            assertTrue("$title must have breathing room above its heading", titleBounds.top - panelBounds.top >= 6.dp)
        }
        val sourceAmounts = (0..7).map { index ->
            val label = compose.onNodeWithTag("reports-overview-source-label-$index").getUnclippedBoundsInRoot()
            val amount = compose.onNodeWithTag("reports-overview-source-value-$index").getUnclippedBoundsInRoot()
            assertTrue("Source $index label and amount must not overlap", label.right <= amount.left)
            amount
        }
        sourceAmounts.zipWithNext().forEach { (previous, next) ->
            assertEquals("Source amounts must share one right edge", previous.right, next.right)
            assertTrue("Every source must occupy a distinct row", previous.bottom <= next.top)
        }
        (0..6).forEach { index ->
            compose.onNodeWithTag("reports-overview-payment-track-$index").assertHeightIsAtLeast(8.dp)
            assertInside("reports-overview-payment-track-$index", "reports-overview-payment-panel")
        }
        assertNoInventedPercentages()

        listOf(
            "reports-title",
            "reports-subtitle",
            "reports-overview-export",
            "reports-period-selector",
            "reports-period-menu",
            "reports-overview-kpi-revenue",
            "reports-overview-kpi-orders",
            "reports-overview-kpi-sessions",
            "reports-overview-kpi-linked-customers",
            "reports-overview-hour-panel",
            "reports-overview-source-panel",
            "reports-overview-payment-panel",
            "reports-overview-products-panel",
            "reports-overview-devices-panel",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertFullyInsideCapture(tag)
        }
        val periodBounds = compose.onNodeWithTag("reports-period-selector").getUnclippedBoundsInRoot()
        assertTrue("Wide period navigation must leave room for the heading", (periodBounds.right - periodBounds.left) in 300.dp..420.dp)
        assertEquals("Wide navigation must not add vertical inset around 48dp controls", 48.dp, periodBounds.bottom - periodBounds.top)
        listOf("products", "payment", "devices").forEach {
            assertBottomMargin("reports-overview-$it-panel", 32.dp)
        }
        val productsBounds = compose.onNodeWithTag("reports-overview-products-panel").getUnclippedBoundsInRoot()
        val paymentsBounds = compose.onNodeWithTag("reports-overview-payment-panel").getUnclippedBoundsInRoot()
        val devicesBounds = compose.onNodeWithTag("reports-overview-devices-panel").getUnclippedBoundsInRoot()
        assertEquals(productsBounds.top, paymentsBounds.top)
        assertEquals(productsBounds.top, devicesBounds.top)
        assertEquals("Bottom cards must share a baseline", productsBounds.bottom, paymentsBounds.bottom)
        assertEquals("Bottom cards must share a baseline", productsBounds.bottom, devicesBounds.bottom)
        assertWideBottomTableAlignment()
        assertTrue(productsBounds.right < paymentsBounds.left && paymentsBounds.right < devicesBounds.left)
        assertPanelTextFits("products", listOf("reports-overview-products-caption") +
            (0..4).flatMap { listOf("reports-overview-product-name-$it", "reports-overview-product-detail-$it",
                "reports-overview-product-quantity-$it", "reports-overview-product-gross-$it") }, requireVisible = true)
        assertPanelTextFits("devices", listOf("reports-overview-devices-caption") +
            (0..2).flatMap { listOf("reports-overview-device-type-$it", "reports-overview-device-count-$it", "reports-overview-device-time-$it") },
            requireVisible = true)
        compose.onNodeWithContentDescription("FOOD · Qty 4.25").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play 1h 1m 1.123s").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play unavailable · 1 with unknown time").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play 0s").assertIsDisplayed()
        compose.onNodeWithText("View all").assertDoesNotExist()
        listOf(
            "reports-overview-hour-total" to "reports-overview-hour-panel",
            "reports-overview-hour-peak" to "reports-overview-hour-panel",
            "reports-overview-source-denominator" to "reports-overview-source-panel",
            "reports-overview-source-adjustments" to "reports-overview-source-panel",
            "reports-overview-payment-total" to "reports-overview-payment-panel",
        ).forEach { (child, parent) ->
            compose.onNodeWithTag(child, useUnmergedTree = true).assertIsDisplayed()
            assertTaggedTextFits(child)
            assertInside(child, parent)
            assertFullyInsideCapture(child)
        }
        (0..7).forEach { index ->
            listOf(
                "reports-overview-source-label-$index",
                "reports-overview-source-value-$index",
            ).forEach { tag ->
                assertTaggedTextFits(tag)
                assertInside(tag, "reports-overview-source-panel")
                assertFullyInsideCapture(tag)
            }
        }
        (0..6).forEach { index ->
            listOf(
                "reports-overview-payment-label-$index",
                "reports-overview-payment-value-$index",
            ).forEach { tag ->
                assertTaggedTextFits(tag)
                assertInside(tag, "reports-overview-payment-panel")
                assertFullyInsideCapture(tag)
            }
        }
        listOf(
            "reports-period-previous",
            "reports-period-label-action",
            "reports-period-next",
            "reports-period-menu",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("P&L").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("reports-period-menu").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("reports-period-option-MONTHLY", useUnmergedTree = true)
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(ReportsSurface.PNL, surface.value)
            assertEquals(ReportPeriod.MONTHLY, period.value)
        }
    }

    @Test
    fun emptyV2PanelsShowTheirOwnCohortWithoutInventedRows() {
        val state = wideState().let { it.copy(overview = it.overview!!.copy(
            topProducts = emptyList(), deviceUsage = emptyList(),
        )) }
        render(1_280, 800, 1f, state)
        awaitArtwork()
        compose.onNodeWithTag("reports-overview-products-empty").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-devices-empty").assertIsDisplayed()
        assertPanelTextFits("products", listOf("reports-overview-products-caption", "reports-overview-products-empty"),
            requireVisible = true)
        assertPanelTextFits("devices", listOf("reports-overview-devices-caption", "reports-overview-devices-empty"),
            requireVisible = true)
        compose.onNodeWithTag("reports-overview-product-name-0").assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-device-time-0").assertDoesNotExist()
    }

    @Test
    fun largeFontStacksWidePanelsAndRetainsLongHistoricalNamesAndExactValues() {
        val base = wideState()
        val overview = checkNotNull(base.overview)
        val product = overview.topProducts.first().copy(
            name = "Historical product with a long unchanged receipt description",
            quantitySold = "9007199254740993.125",
            grossLineSalesMinor = Long.MAX_VALUE,
        )
        render(1_280, 800, 1.3f, base.copy(overview = overview.copy(topProducts = listOf(product))))
        awaitArtwork()
        compose.onNodeWithTag("reports-overview-bottom-row").assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-products-panel").performScrollTo().assertIsDisplayed()
        assertPanelTextFits("products", listOf("reports-overview-products-caption", "reports-overview-product-name-0",
            "reports-overview-product-detail-0", "reports-overview-product-gross-0"), requireVisible = true)
        compose.onNodeWithText("FOOD · Qty 9007199254740993.125").assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-devices-panel").performScrollTo().assertIsDisplayed()
        assertPanelTextFits("devices", listOf("reports-overview-devices-caption", "reports-overview-device-time-0",
            "reports-overview-device-time-1", "reports-overview-device-time-2"), requireVisible = true)
    }

    @Test
    fun compactGrantedOwnerKeepsEnabledExportAndHeaderReadableAtLargeText() {
        var exports = 0
        render(600, 800, 1.3f, wideState(), onExport = { exports++ })
        awaitArtwork()
        capture("reports-overview-export-compact-600x800-font1_3", 600, 800, 1.3f)
        compose.onNodeWithTag("reports-overview-export").assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, exports)
        listOf("reports-title", "reports-subtitle").forEach(::assertTaggedTextFits)
        listOf("reports-title", "reports-subtitle", "reports-overview-export",
            "reports-period-selector", "reports-period-menu").forEach(::assertFullyInsideCapture)
        compose.onNodeWithTag("reports-period-menu").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reports-period-previous").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reports-period-next").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reports-overview-products-panel").performScrollTo().assertIsDisplayed()
        assertPanelTextFits("products", listOf("reports-overview-products-caption"), requireVisible = true)
    }

    @Test
    fun compactLargeTextSingleDayKeepsRealHourAxesAndPeakReadable() {
        val state = wideState().copy(
            access = ReportsAccess(canReadOverview = true),
            surface = ReportsSurface.OVERVIEW,
        )
        render(600, 800, 1.3f, state)
        awaitArtwork()

        compose.onNodeWithTag("reports-overview-hour-panel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reports-period-menu").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reports-period-previous").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reports-period-next").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reports-overview-hour-chart").assertHeightIsAtLeast(100.dp)
        listOf(
            "reports-overview-hour-axis-min",
            "reports-overview-hour-axis-max",
            "reports-overview-hour-axis-00",
            "reports-overview-hour-axis-06",
            "reports-overview-hour-axis-12",
            "reports-overview-hour-axis-18",
            "reports-overview-hour-axis-23",
            "reports-overview-hour-total",
            "reports-overview-hour-peak",
        ).forEach { tag ->
            assertTaggedTextFits(tag)
            assertInside(tag, "reports-overview-hour-panel")
        }
        assertHourTickCoordinates()
        assertFullyInsideCapture("reports-overview-hour-panel")
    }

    @Test
    fun compactLargeTextKeepsCompleteOverviewScrollableAndHidesUnauthorizedPnl() {
        val state = wideState().copy(
            access = ReportsAccess(canReadOverview = true),
            period = ReportPeriod.MONTHLY,
            surface = ReportsSurface.OVERVIEW,
            overview = wideState().overview!!.copy(
                periodStart = "2026-10-01",
                periodEnd = "2026-10-31",
                localHourProfile = ReportsOverviewHourProfile.MultiDayUnavailable,
            ),
        )
        render(600, 800, 1.3f, state)
        awaitArtwork()
        capture("reports-overview-compact-600x800-font1_3", 600, 800, 1.3f)

        compose.onNodeWithText("P&L").assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-export").assertDoesNotExist()
        listOf(
            "reports-overview-kpi-revenue",
            "reports-overview-kpi-orders",
            "reports-overview-kpi-sessions",
            "reports-overview-kpi-linked-customers",
        ).forEach { cardTag ->
            compose.onNodeWithTag(cardTag).performScrollTo().assertIsDisplayed()
            listOf("$cardTag-title", "$cardTag-value", "$cardTag-detail").forEach { textTag ->
                assertTaggedTextFits(textTag)
                assertInside(textTag, cardTag)
            }
        }
        compose.onNodeWithTag("reports-overview-hour-unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-source-denominator").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-payment-total").performScrollTo().assertIsDisplayed()
        listOf(
            "reports-title",
            "reports-subtitle",
            "reports-period-label",
            "reports-period-menu-label",
            "reports-overview-hour-unavailable",
            "reports-overview-source-denominator",
            "reports-overview-source-adjustments",
            "reports-overview-payment-total",
        ).forEach(::assertTaggedTextFits)
        assertInside("reports-period-label", "reports-period-label-action")
        assertInside("reports-period-menu-label", "reports-period-menu")
        assertInside("reports-overview-hour-unavailable", "reports-overview-hour-panel")
        assertInside("reports-overview-source-denominator", "reports-overview-source-panel")
        assertInside("reports-overview-source-adjustments", "reports-overview-source-panel")
        assertInside("reports-overview-payment-total", "reports-overview-payment-panel")
        assertFullyInsideCapture("reports-overview-payment-panel")
        compose.onNodeWithTag("reports-overview-bottom-row").assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-products-panel").performScrollTo().assertIsDisplayed()
        assertPanelTextFits("products", listOf("reports-overview-products-caption") +
            (0..4).flatMap { listOf("reports-overview-product-name-$it", "reports-overview-product-detail-$it",
                "reports-overview-product-gross-$it") }, requireVisible = true)
        compose.onNodeWithTag("reports-overview-devices-panel").performScrollTo().assertIsDisplayed()
        assertPanelTextFits("devices", listOf("reports-overview-devices-caption") +
            (0..2).flatMap { listOf("reports-overview-device-count-$it", "reports-overview-device-time-$it") },
            requireVisible = true)
        assertFullyInsideCapture("reports-overview-devices-panel")
        capture("reports-overview-compact-panels-600x800-font1_3", 600, 800, 1.3f)
        assertNoInventedPercentages()
    }

    @Test
    fun financeOnlyRetainsExistingPnlAndNeverExposesOverviewSurface() {
        val state = wideState().copy(
            access = ReportsAccess(canReadPnl = true),
            surface = ReportsSurface.PNL,
            overview = null,
            report = ReportData(
                branchId = "11111111-1111-1111-1111-111111111111",
                accountingBasis = "operational_receipt",
                period = "daily",
                label = "27 Oct 2026",
                periodStart = "2026-10-27",
                periodEnd = "2026-10-27",
                fiscalYear = "2026-27",
                ordersCount = 2,
                avgTicketMinor = 700,
                grossRevenueMinor = 1_400,
                netRevenueMinor = 1_400,
                grossProfitMinor = 1_400,
                netProfitMinor = 1_400,
                revenue = ReportRevenue(gamingMinor = 1_400, totalMinor = 1_400),
                paymentsReceived = ReportPayments(cashMinor = 1_400, totalMinor = 1_400),
            ),
            loading = false,
            costingLoading = false,
            costingCoverage = null,
        )
        render(1_280, 800, 1f, state)
        awaitArtwork()

        compose.onNodeWithText("Reports Overview").assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-export").assertDoesNotExist()
        compose.onNodeWithText("Revenue").assertIsDisplayed()
        compose.onNode(
            hasTestTag("reports-pnl-kpi-revenue") and hasAnyDescendant(hasText("14.00")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag("reports-overview-source-panel").assertDoesNotExist()
    }

    @Test
    fun operationalTabsReuseAdmittedValuesAndInventoryHasIndependentAuthority() {
        val admitted = wideState()
        val current = mutableStateOf(admitted)
        var overviewReads = 0
        var inventoryReads = 0
        render(1_280, 800, 1f, admitted, currentState = { current.value }, onOperationalTab = { tab ->
            current.value = reportsSelectOperationalTab(current.value, tab)
            when (reportsOperationalLoadNeeded(current.value)) {
                ReportsOperationalLoad.OVERVIEW -> overviewReads++
                ReportsOperationalLoad.INVENTORY -> {
                    inventoryReads++
                    current.value = current.value.copy(inventory = ReportsInventorySnapshot(
                        LocalDate.parse("2026-10-28"), admitted.lease!!.scope.branchId!!,
                        listOf(ReportsInventoryLine("22222222-2222-2222-2222-222222222222", "TEA", "Loose leaf tea", "kg", 0.1255, 333, 42, 1.0, true)), 42, 1,
                    ))
                }
                ReportsOperationalLoad.NONE -> Unit
            }
        })
        awaitArtwork()
        fun select(tab: ReportsOperationalTab) {
            compose.onNodeWithTag("premium-tab-${tab.name}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitForIdle()
        }
        select(ReportsOperationalTab.CUSTOMERS)
        compose.onNodeWithText("Distinct linked customers").assertIsDisplayed()
        compose.onNodeWithText("Orders without a customer").assertIsDisplayed()
        select(ReportsOperationalTab.SESSIONS)
        compose.onNodeWithText("Started in this range").assertIsDisplayed()
        compose.onNodeWithText("PS5 · 4 completed").performScrollTo().assertIsDisplayed()
        select(ReportsOperationalTab.PAYMENTS)
        compose.onNodeWithTag("reports-overview-payment-panel").assertIsDisplayed()
        select(ReportsOperationalTab.DEVICES)
        compose.onNodeWithText("Play 1h 1m 1.123s").assertIsDisplayed()
        compose.onNodeWithText("Play unavailable · 1 with unknown time").assertIsDisplayed()
        select(ReportsOperationalTab.SALES)
        compose.onNodeWithText("Settled orders").assertIsDisplayed()
        compose.onNodeWithText("Masala tea").performScrollTo().assertIsDisplayed()
        select(ReportsOperationalTab.INVENTORY)
        compose.onNodeWithText("Stock valuation").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("0.42")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("reports-period-menu").assertDoesNotExist()
        compose.onNodeWithTag("reports-overview-export").assertDoesNotExist()
        assertEquals(0, overviewReads)
        assertEquals(1, inventoryReads)
        compose.onNodeWithTag("reports-operational-inventory").performScrollToIndex(1)
        compose.onNodeWithText("Loose leaf tea").assertIsDisplayed()
        compose.onNodeWithText("0.1255").assertIsDisplayed()
        compose.onNodeWithText("3.33").assertIsDisplayed()
        compose.runOnIdle {
            current.value = visibleReportsState(current.value, admitted.lease, admitted.access.copy(canReadInventory = false))
        }
        compose.onNodeWithTag("premium-tab-INVENTORY").assertDoesNotExist()
        compose.onNodeWithText("Stock valuation").assertDoesNotExist()
        compose.onNodeWithText("Gross revenue").assertExists()
    }

    @Test
    fun compactOperationalTabsKeepReachableTargetsAndUntruncatedLabelsAtLargeText() {
        val admitted = wideState()
        val current = mutableStateOf(admitted)
        render(600, 800, 1.3f, admitted, currentState = { current.value },
            onOperationalTab = { current.value = reportsSelectOperationalTab(current.value, it) })
        awaitArtwork()
        ReportsOperationalTab.entries.forEach { tab ->
            compose.onNodeWithTag("premium-tab-${tab.name}").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            compose.waitForIdle()
            assertTaggedTextFits("premium-tab-label-${tab.name}")
            assertFullyInsideCapture("premium-tab-${tab.name}")
        }
        compose.onNodeWithText("Play 1h 1m 1.123s").performScrollTo().assertIsDisplayed()
        assertTaggedTextFits("reports-overview-device-time-0")
        assertTaggedTextFits("reports-overview-device-time-1")
        compose.onNodeWithTag("premium-tab-PNL").performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertTaggedTextFits("premium-tab-label-PNL")
        capture("reports-operational-compact-600x800-font1_3", 600, 800, 1.3f)
    }

    @Test
    fun successfulOperationalViewsExposeRefreshAndRetainedSnapshotsAreExplicit() {
        val current = mutableStateOf(wideState().copy(operationalTab = ReportsOperationalTab.SALES))
        var refreshes = 0
        render(600, 800, 1.3f, current.value, currentState = { current.value },
            onRetry = { refreshes++ })
        awaitArtwork()
        for (tab in listOf(ReportsOperationalTab.SALES, ReportsOperationalTab.SESSIONS,
            ReportsOperationalTab.CUSTOMERS, ReportsOperationalTab.PAYMENTS, ReportsOperationalTab.DEVICES)) {
            compose.runOnIdle { current.value = current.value.copy(operationalTab = tab) }
            compose.onNodeWithTag("reports-operational-refresh").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                .assertIsEnabled().performClick()
        }
        assertEquals(5, refreshes)
        compose.runOnIdle { current.value = current.value.copy(overviewStale = true, overviewLoading = true) }
        compose.onNodeWithText("Saved snapshot · refreshing current data.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reports-operational-refresh").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(overviewLoading = false,
            overviewError = "Offline · showing the last verified Overview for this range.") }
        compose.onNodeWithText("Offline · showing the last verified Overview for this range.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reports-operational-refresh").performScrollTo().assertIsEnabled().performClick()
        assertEquals(6, refreshes)
    }

    private fun render(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        state: ReportsUiState,
        realisticOwnerNavigation: Boolean = false,
        onExport: () -> Unit = {},
        onRetry: () -> Unit = {},
        currentState: (() -> ReportsUiState)? = null,
        onOperationalTab: (ReportsOperationalTab) -> Unit = {},
        callbacks: (ReportsSurface?, ReportPeriod?) -> Unit = { _, _ -> },
    ) {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(
                            Modifier.width(widthDp.dp).height(heightDp.dp).testTag("reports-capture-root"),
                        ) {
                            WorkspaceScaffold(
                                destinations = if (realisticOwnerNavigation) {
                                    allowedDestinations(reportsOwnerProfile(), WorkspaceFeatureProfiles.GamingCentre)
                                } else {
                                    listOf(Destination.Dashboard, Destination.Reports, Destination.Settings)
                                },
                                currentDestination = Destination.Reports,
                                employeeName = "Synthetic Reports Owner",
                                locationLabel = "Synthetic branch",
                                connectivityProblem = SyncAvailabilityProblem.NONE,
                                outboxWorkStatus = OutboxWorkStatus(),
                                syncing = false,
                                canChangeTill = false,
                                appearancePreference = AppearancePreference.Night,
                                onOpenSupport = {},
                                onChangeTill = {},
                                onSignOut = {},
                            ) { _, _ ->
                                ReportsWorkspaceContent(
                                    state = currentState?.invoke() ?: state,
                                    presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy(),
                                    onExport = onExport,
                                    onOperationalTab = onOperationalTab,
                                    onSurface = { callbacks(it, null) },
                                    onPeriod = { callbacks(null, it) },
                                    onStep = {},
                                    onPickDate = {},
                                    onPickMonth = {},
                                    onPickQuarter = {},
                                    onJumpToCurrent = {},
                                    onRetry = onRetry,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertHost()
    }

    private fun awaitArtwork() {
        compose.waitUntil(8_000L) {
            compose.onAllNodes(hasTestTag("workspace-background-ready-workspace"))
                .fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasTestTag("workspace-glass-ready-workspace"))
                    .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
    }

    private fun reportsOwnerProfile() = MeResponse(
        userId = "11111111-1111-1111-1111-111111111110",
        email = "reports-owner@example.test",
        name = "Synthetic Reports Owner",
        roles = listOf("owner"),
        protectedAccess = true,
        auditAccess = true,
        companyId = "11111111-1111-1111-1111-111111111112",
        branchId = "11111111-1111-1111-1111-111111111111",
        accessibleModules = listOf("gaming", "pos", "inventory", "menu", "finance", "reports", "staff"),
        effectivePermissions = listOf(
            ErpPermission.AnalyticsRead,
            ErpPermission.AnalyticsExport,
            ErpPermission.GamingRead,
            ErpPermission.PosRead,
            ErpPermission.PosRefund,
            ErpPermission.PosShiftOpen,
            ErpPermission.PosShiftClose,
            ErpPermission.InventoryRead,
            ErpPermission.MenuRead,
            ErpPermission.FinanceRead,
            ErpPermission.StaffRead,
            ErpPermission.AdminSystem,
        ),
    )

    /** Rendering-only authority fixture; server/export authorization is exercised separately. */
    private fun reportsFixtureLease(): CacheScopeLease = runBlocking {
        val profile = reportsOwnerProfile()
        val scope = CacheScope(profile.userId, profile.companyId, profile.branchId, "synthetic-reports-till")
        val coordinator = CacheIsolationCoordinator(
            object : ScopeDataPurger {
                override suspend fun hasUnresolvedWork() = false
                override suspend fun purgeIfClean() = true
            },
            object : CacheScopeMarker {
                private var value: CacheScope? = scope
                override fun current() = value
                override fun remember(scope: CacheScope): Boolean { value = scope; return true }
                override fun clear(): Boolean { value = null; return true }
            },
        )
        coordinator.activateValidated(scope)
        requireNotNull(coordinator.currentLease())
    }

    private fun wideState(): ReportsUiState {
        val points = (0..23).map { hour ->
            ReportsOverviewHourPoint(hour, if (hour == 18) 1_400 else 0, if (hour == 18) 2 else 0)
        }
        return ReportsUiState(
            access = EffectivePermissions.from(reportsOwnerProfile()).reportsAccess(),
            lease = reportsFixtureLease(),
            surface = ReportsSurface.OVERVIEW,
            period = ReportPeriod.DAILY,
            onDate = LocalDate.parse("2026-10-27"),
            month = YearMonth.parse("2026-10"),
            fiscalYear = "2026-27",
            quarter = 3,
            overview = ValidatedReportsOverview(
                branchId = "11111111-1111-1111-1111-111111111111",
                periodStart = "2026-10-27",
                periodEnd = "2026-10-27",
                timezone = "Asia/Dubai",
                snapshotAtMillis = 1_793_088_000_000L,
                grossRevenueMinor = 1_400,
                ordersCount = 2,
                sessionsStartedCount = 3,
                distinctLinkedCustomersCount = 1,
                linkedOrdersCount = 1,
                unlinkedOrdersCount = 1,
                sources = ReportsOverviewSources(300, 250, 200, 150, 150, 150, 150, 150, -100, 1_500, 1_400),
                payments = ReportsOverviewPayments(400, 100, 300, 200, 100, 150, 150, 1_400),
                topProducts = listOf("Masala tea", "Club sandwich", "Fresh lime", "French fries", "Espresso")
                    .mapIndexed { index, name ->
                        ReportsOverviewTopProduct("11111111-1111-1111-1111-11111111111${index + 1}",
                            name, "FOOD", if (index == 0) "4.250" else "2.000", 1_500L - index * 100L)
                    },
                deviceUsage = listOf(
                    ReportsOverviewDeviceUsage("PS5", 4, JsonPrimitive(3_661_123L), 0),
                    ReportsOverviewDeviceUsage("VR", 2, JsonNull, 1),
                    ReportsOverviewDeviceUsage("PS4", 1, JsonPrimitive(0L), 0),
                ),
                localHourProfile = ReportsOverviewHourProfile.Available(
                    "Asia/Dubai", "2026-10-27", 1_440, 1_400, 2, points,
                ),
            ),
            overviewFetchedAtMillis = 1_793_088_000_000L,
            overviewTimezone = "Asia/Dubai",
            overviewUsesServerDefaultDate = false,
        )
    }

    private fun assertWideBottomTableAlignment() {
        val tables = listOf(
            Triple("products", "product", listOf("name", "detail", "quantity", "gross")),
            Triple("payment", "payment", listOf("label", "track", "value")),
            Triple("devices", "device", listOf("type", "count", "time")),
        )
        val headerBounds = tables.map { (panel, _, _) ->
            compose.onNodeWithTag("reports-overview-$panel-columns").getUnclippedBoundsInRoot()
        }
        headerBounds.forEach {
            assertEquals("Column headings must align across the bottom row", headerBounds.first().top, it.top)
            assertEquals(headerBounds.first().bottom, it.bottom)
        }
        val firstRows = tables.map { (_, row, _) ->
            compose.onNodeWithTag("reports-overview-$row-row-0").getUnclippedBoundsInRoot()
        }
        firstRows.forEach {
            assertEquals("First data rows must align across the bottom row", firstRows.first().top, it.top)
            assertEquals(firstRows.first().bottom, it.bottom)
        }
        tables.forEach { (panel, row, columns) ->
            val count = when (panel) { "products" -> 5; "payment" -> 7; else -> 3 }
            columns.forEachIndexed { column, field ->
                val headingTag = "reports-overview-$panel-columns-$column"
                assertTaggedTextFits(headingTag)
                assertInside(headingTag, "reports-overview-$panel-panel")
                val heading = compose.onNodeWithTag(headingTag).getUnclippedBoundsInRoot()
                repeat(count) { index ->
                    val tag = "reports-overview-$row-$field-$index"
                    val cell = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                    assertEquals("$tag must align to its column's left edge", heading.left, cell.left)
                    assertEquals("$tag must align to its column's right edge", heading.right, cell.right)
                    assertInside(tag, "reports-overview-$panel-panel")
                    if (field != "track") assertTaggedTextFits(tag)
                }
            }
            val rows = (0 until count).map { index ->
                compose.onNodeWithTag("reports-overview-$row-row-$index").getUnclippedBoundsInRoot()
            }
            rows.forEach {
                assertEquals("Fixture rows must have one shared height", firstRows.first().bottom - firstRows.first().top, it.bottom - it.top)
            }
            rows.zipWithNext().forEach { (before, after) ->
                assertEquals("Table rows must follow one compact rhythm", before.bottom, after.top)
            }
        }
        compose.onNodeWithTag("reports-overview-product-detail-0").assertTextEquals("FOOD")
        compose.onNodeWithTag("reports-overview-product-quantity-0").assertTextEquals("4.25")
        compose.onNodeWithTag("reports-overview-product-gross-0").assertTextEquals("15.00")
        compose.onNodeWithTag("reports-overview-device-type-0").assertTextEquals("PS5")
        compose.onNodeWithTag("reports-overview-device-count-0").assertTextEquals("4")
        compose.onNodeWithTag("reports-overview-device-time-0").assertTextEquals("1h 1m 1.123s")
        compose.onNodeWithTag("reports-overview-device-time-1").assertTextEquals("Unavailable · 1 unknown")
        compose.onNodeWithTag("reports-overview-device-time-2").assertTextEquals("0s")
    }

    private fun assertPanelTextFits(panel: String, tags: List<String>, requireVisible: Boolean) {
        tags.forEach { tag ->
            assertTaggedTextFits(tag)
            assertInside(tag, "reports-overview-$panel-panel")
            if (requireVisible) {
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                assertFullyInsideCapture(tag)
            }
        }
    }

    private fun assertTaggedTextFits(tag: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertTrue("$tag must expose text layout", action?.action?.invoke(results) == true)
        assertEquals("$tag must expose one text layout", 1, results.size)
        val result = results.single()
        val text = result.layoutInput.text.text
        assertTrue("$tag must have text", text.isNotBlank())
        assertFalse("$tag must not overflow height", result.didOverflowHeight)
        assertTrue("$tag must expose at least one line", result.lineCount > 0)
        repeat(result.lineCount) { line ->
            assertFalse("$tag line $line must not ellipsize", result.isLineEllipsized(line))
            assertTrue(
                "$tag line $line must fit its integer layout: " +
                    "left=${result.getLineLeft(line)}, right=${result.getLineRight(line)}, width=${result.size.width}",
                result.getLineLeft(line) >= -1f && result.getLineRight(line) <= result.size.width + 1f,
            )
        }
        assertEquals(
            "$tag must retain every visible character",
            text.length,
            result.getLineEnd(result.lineCount - 1, visibleEnd = true),
        )
        text.indices.filterNot { text[it].isWhitespace() }.forEach { offset ->
            val glyph = result.getBoundingBox(offset)
            assertTrue(
                "$tag glyph $offset must fit its integer layout: $glyph vs ${result.size}",
                glyph.left >= -1f && glyph.top >= -1f &&
                    glyph.right <= result.size.width + 1f && glyph.bottom <= result.size.height + 1f,
            )
        }
    }

    private fun assertHourTickCoordinates() {
        val chart = compose.onNodeWithTag("reports-overview-hour-chart").getUnclippedBoundsInRoot()
        listOf(6, 12, 18).forEach { hour ->
            val label = compose.onNodeWithTag("reports-overview-hour-axis-${hour.toString().padStart(2, '0')}")
                .getUnclippedBoundsInRoot()
            val expected = chart.left + 8.dp + (chart.right - chart.left - 16.dp) * (hour / 23f)
            val actual = (label.left + label.right) / 2f
            assertTrue("Hour $hour must align with its plotted time: $actual versus $expected",
                kotlin.math.abs((actual - expected).value) <= 1f)
        }
        val low = compose.onNodeWithTag("reports-overview-hour-axis-min").getUnclippedBoundsInRoot()
        val high = compose.onNodeWithTag("reports-overview-hour-axis-max").getUnclippedBoundsInRoot()
        assertTrue("Revenue extrema must label the vertical axis", high.top < low.top &&
            high.right <= chart.left && low.right <= chart.left)
    }

    private fun assertFullyInsideCapture(tag: String) {
        val child = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("reports-capture-root").getUnclippedBoundsInRoot()
        assertTrue("$tag must fit root: child=$child root=$root", child.left >= root.left && child.top >= root.top && child.right <= root.right && child.bottom <= root.bottom)
    }

    private fun assertBottomMargin(tag: String, minimum: androidx.compose.ui.unit.Dp) {
        val child = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val root = compose.onNodeWithTag("reports-capture-root").getUnclippedBoundsInRoot()
        val margin = root.bottom - child.bottom
        assertTrue("$tag must retain at least $minimum below it: margin=$margin child=$child root=$root", margin >= minimum)
    }

    private fun assertInside(childTag: String, parentTag: String) {
        val child = compose.onNodeWithTag(childTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val parent = compose.onNodeWithTag(parentTag).getUnclippedBoundsInRoot()
        assertTrue(
            "$childTag must fit $parentTag: child=$child parent=$parent",
            child.left >= parent.left && child.top >= parent.top &&
                child.right <= parent.right && child.bottom <= parent.bottom,
        )
    }

    private fun assertNoInventedPercentages() {
        val percentNodes = compose.onAllNodes(hasText("%", substring = true)).fetchSemanticsNodes()
        assertTrue("Overview must not invent percentages: ${percentNodes.size}", percentNodes.isEmpty())
    }

    private fun assertHost() {
        val display = compose.activity.display ?: error("Host activity has no display")
        val metrics = compose.activity.resources.displayMetrics
        val bounds: Rect = compose.activity.windowManager.currentWindowMetrics.bounds
        ReportsRequestedDisplay?.let { requested ->
            assertEquals(requested, display.displayId)
            assertEquals(2_560, display.mode.physicalWidth)
            assertEquals(1_600, display.mode.physicalHeight)
            assertEquals(320, metrics.densityDpi)
            assertEquals(2_560, bounds.width())
            assertEquals(1_600, bounds.height())
        }
    }

    private fun capture(name: String, widthDp: Int, heightDp: Int, fontScale: Float) {
        val args = InstrumentationRegistry.getArguments()
        val runId = args.getString(ReportsRunArg) ?: return
        require(runId.matches(Regex("[0-9a-f]{32}")))
        val source = requireSha(args.getString(ReportsSourceArg), ReportsSourceArg)
        val target = requireSha(args.getString(ReportsTargetArg), ReportsTargetArg)
        val test = requireSha(args.getString(ReportsTestArg), ReportsTestArg)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.filesDir.resolve("reports-overview-captures/$runId")
        check(root.mkdirs() || root.isDirectory)
        val png = File(root, "$name.png")
        val bitmap = compose.onNodeWithTag("reports-capture-root").captureToImage().asAndroidBitmap()
        try {
            val density = compose.activity.resources.displayMetrics.density
            assertEquals((widthDp * density).roundToInt(), bitmap.width)
            assertEquals((heightDp * density).roundToInt(), bitmap.height)
            png.outputStream().use { output -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) }
            val display = compose.activity.display ?: error("Host activity has no display")
            val provenance = JSONObject()
                .put("schema", "dcompany.reports_overview_capture.v1")
                .put("capture", name)
                .put("run_id", runId)
                .put("test_class", "cloud.dcompany.erp.ui.screens.reports.ReportsOverviewUiTest")
                .put("application_id", BuildConfig.APPLICATION_ID)
                .put("source_inventory_sha256", source)
                .put("target_apk_sha256", target)
                .put("test_apk_sha256", test)
                .put("workspace_background_preset_id", WorkspaceBackgroundPresets.DefaultId)
                .put("workspace_background_content_id", WorkspaceBackgroundPresets.default.contentId)
                .put("png_file", png.name)
                .put("png_bytes", png.length())
                .put("png_sha256", sha256(png))
                .put("observed_display_id", display.displayId)
                .put("display_mode_width_px", display.mode.physicalWidth)
                .put("display_mode_height_px", display.mode.physicalHeight)
                .put("density", density.toDouble())
                .put("density_dpi", compose.activity.resources.displayMetrics.densityDpi)
                .put("width_dp", widthDp)
                .put("height_dp", heightDp)
                .put("actual_width_px", bitmap.width)
                .put("actual_height_px", bitmap.height)
                .put("font_scale", fontScale.toDouble())
            File(root, "$name.provenance.json").writeText(provenance.toString(2) + "\n")
        } finally {
            bitmap.recycle()
        }
    }

    private fun requireSha(value: String?, label: String): String = requireNotNull(value) {
        "$label is required when capture is enabled"
    }.also { require(it.matches(Regex("[0-9a-f]{64}"))) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
