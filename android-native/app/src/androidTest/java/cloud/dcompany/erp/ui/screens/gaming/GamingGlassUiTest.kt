package cloud.dcompany.erp.ui.screens.gaming

import android.app.ActivityOptions
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.components.SectionCardSurfaceMode
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.OperationalBanner
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundImage
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundRenderer
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.workspaceGlass
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val GamingGlassDisplayArgument = "dcompany.testDisplayId"
private const val GamingGlassSourceArgument = "dcompany.sourceInventorySha256"
private const val GamingGlassTargetArgument = "dcompany.targetApkSha256"
private const val GamingGlassTestArgument = "dcompany.testApkSha256"
private const val GamingGlassRunArgument = "dcompany.captureRunId"
private const val GamingAlarmReadyArgument = "dcompany.expectGamingAlarmReady"

private val RequestedGamingGlassDisplay: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(GamingGlassDisplayArgument)?.let {
        val requested = it.toIntOrNull()
        require(requested in setOf(0, 2, 5, 6)) { "Only verified HD displays 0, 2, 5 and 6 are supported: $it" }
        requested
    }
}

private fun <A : ComponentActivity> ActivityScenarioRule<A>.gamingGlassActivity(): A {
    var activity: A? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity).also { current ->
        if (RequestedGamingGlassDisplay == 0) {
            val display = checkNotNull(current.display)
            check(display.displayId == 0 && display.mode.physicalWidth == 2_560 &&
                display.mode.physicalHeight == 1_600 && current.resources.displayMetrics.densityDpi == 320) {
                "Display 0 Gaming proof requires the verified 2560x1600px, 320dpi host"
            }
        }
    }
}

private fun createGamingGlassRule(): AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> {
    val rule = if (RequestedGamingGlassDisplay == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(RequestedGamingGlassDisplay!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(rule) { it.gamingGlassActivity() }
}

@RunWith(AndroidJUnit4::class)
class GamingGlassUiTest {
    @get:Rule val compose = createGamingGlassRule()

    @Test
    fun deniedPreciseAlarmLongFeedbackUsesActualBoundedScrollOwnerAtCompactFontScale() {
        assertFalse(
            "long-feedback evidence must run in the root-owned denied alarm lane",
            compose.activity.getSystemService(android.app.AlarmManager::class.java)
                .canScheduleExactAlarms(),
        )
        val registry = CancelSettingsActivityResultRegistry(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val fixture = renderGaming(
            widthDp = 600,
            heightDp = 800,
            fontScale = 1.3f,
            appearance = AppearancePreference.Night,
            activityResultRegistryOwner = owner,
        )
        awaitWorkspaceGlass()
        compose.onAllNodesWithTag("gaming-notice-rail").assertCountEquals(0)
        compose.onNodeWithText("Precise alarms")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(GamingPreciseAlarmLongFeedback, useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        assertEquals("exactly one real Settings launch is allowed", 1, registry.launchCount)
        assertFalse(
            "cancelled Settings must not alter the real denied OS authority",
            compose.activity.getSystemService(android.app.AlarmManager::class.java)
                .canScheduleExactAlarms(),
        )

        val root = bounds("gaming-glass-fixture-root")
        val scrollOwner = compose.onNodeWithTag(
            "gaming-alarm-permission-banner",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val ownerNode = scrollOwner.fetchSemanticsNode()
        val viewport = ownerNode.boundsInRoot
        val range = ownerNode.config[SemanticsProperties.VerticalScrollAxisRange]
        val density = compose.activity.resources.displayMetrics.density
        assertNonEmptyAndContained("long-feedback clipped scroll viewport", viewport, root)
        assertTrue(
            "long-feedback viewport must retain the production cap: viewport=$viewport root=$root",
            viewport.height <= minOf(144f * density, root.height * 0.19f) + 1f,
        )
        assertTrue("scroll range must remain finite", range.value().isFinite() && range.maxValue().isFinite())
        assertLongFeedbackTextComplete()

        applicablePermissionActions().forEach { action ->
            action.performScrollTo().assertIsDisplayed().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
            assertNonEmptyAndContained(
                "long-feedback recovery action",
                unclippedBoundsPx(action),
                viewport.intersect(root),
            )
        }
        assertEquals("Settings must be launched only by the deliberate Precise alarms tap", 1, registry.launchCount)
    }

    @Test
    fun nightWideKeepsStationTableSelectedExpansionAndNeighborsInOneWorkspace() {
        val fixture = renderGaming(1280, 800, 1f, AppearancePreference.Night)
        awaitWorkspaceGlass()
        assertGamingHierarchy(wide = true)
        assertSingleStableRing()
        compose.onNodeWithTag("gaming-station-ps5-2").assertIsDisplayed()
        compose.onNodeWithTag("gaming-station-action-ps5-1")
            .assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("gaming-station-ps5-2").performClick()
        compose.runOnIdle { assertEquals("ps5-2", fixture.selectedStationId.value) }
        compose.onNodeWithTag("gaming-selected-identity-zone").assertIsDisplayed()
        compose.onNodeWithTag("gaming-start-action")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        val finish = compose.onNodeWithContentDescription(
            "Expected finish if started now",
            substring = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val ring = compose.onNodeWithTag("gaming-control-ring", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("finish preview must not overlap the permanent ring: finish=$finish ring=$ring", !finish.overlaps(ring))
        capture("gaming-g2-night-selected-wide", 1280, 800, 1f, "Night")
        assertHeadlineReadabilityWashIsLocalAndContainsEveryTextNode()
        compose.onNodeWithTag("gaming-station-ps5-1").assertIsDisplayed()
        compose.onNodeWithTag("gaming-station-racing-1").assertIsDisplayed()
        assertSingleStableRing()
    }

    @Test
    fun masterAspectUsesFittedStationArtPrivateIdentityAndOneStableDial() {
        val ps5Session = activeSession(status = "active").copy(
            id = "session-ps5-2",
            stationId = "ps5-2",
            customerName = "Nasih",
            customerPhone = "0501234567",
            packageVariantSnapshot = "dual",
            extraControllers = 1,
            pauseVersion = 0,
            pauseAvailable = true,
        )
        val racingSession = activeSession(status = "active").copy(
            id = "session-racing-1",
            stationId = "racing-1",
            packageId = null,
            billingMode = "hourly",
            packagePriceMinorSnapshot = null,
            packageDurationMinutesSnapshot = null,
            packageVariantSnapshot = null,
            packageStationTypeSnapshot = null,
            packagePricingTierSnapshot = null,
        )
        val vr = Station("vr-1", "VR-1", "VR Station 1", "vr", 10_000)
        val vrSession = racingSession.copy(id = "session-vr-1", stationId = "vr-1")
        val masterStations = stations + vr
        val fixture = renderGaming(
            1280,
            563,
            1f,
            AppearancePreference.Night,
            initialState = baseState(sessions = listOf(ps5Session, racingSession, vrSession)).copy(
                stations = masterStations,
                packages = packages + GamingPackage(
                    id = "dual-extension-30",
                    code = "dual-extension-30",
                    stationType = "ps5",
                    variant = "dual",
                    kind = "extension",
                    name = "Dual extension 30",
                    durationMinutes = 30,
                    priceMinor = 4_000,
                ),
            ),
            initialSelectedStationId = "ps5-2",
        )
        awaitWorkspaceGlass()
        val board = compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
        compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        val expectAlarmReady =
            InstrumentationRegistry.getArguments().getString(GamingAlarmReadyArgument) == "true"
        if (expectAlarmReady) {
            compose.onAllNodesWithTag("gaming-alarm-permission-banner").assertCountEquals(0)
            compose.onAllNodesWithTag("gaming-notice-rail").assertCountEquals(0)
            assertInitialReadyMasterScene("ps5-2", "ps5-1")
            assertInitialReadySessionTools()
        } else {
            compose.onAllNodesWithTag("gaming-notice-rail").assertCountEquals(0)
            val banner = compose.onNodeWithTag("gaming-alarm-permission-banner").assertIsDisplayed()
            val bannerBounds = unclippedBoundsPx(banner)
            val rootBounds = bounds("gaming-glass-fixture-root")
            assertNonEmptyAndContained("degraded permission banner", bannerBounds, rootBounds)
            assertTrue(
                "single permission warning must retain the 19% height cap: " +
                    "banner=$bannerBounds root=$rootBounds",
                bannerBounds.height <= rootBounds.height * 0.19f + 1f,
            )
            assertPermissionBannerActions()
            board.performScrollToNode(hasTestTag("gaming-station-ps5-1"))
            compose.onNodeWithTag("gaming-station-ps5-1").assertIsDisplayed().assertHasClickAction()
            board.performScrollToNode(hasTestTag("gaming-station-racing-1"))
            compose.onNodeWithTag("gaming-station-racing-1").assertIsDisplayed().assertHasClickAction()
            board.performScrollToNode(hasTestTag("gaming-inline-expansion-ps5-2"))
            compose.onNodeWithTag("gaming-inline-expansion-ps5-2").assertIsDisplayed()
            revealWideFocalWorkspace(board, "ps5-2")
            assertWideFocalWorkspaceFullyVisible("ps5-2")
            compose.onNodeWithTag("gaming-station-table-header").assertIsDisplayed()
            compose.onNodeWithTag("gaming-primary-action-host")
                .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("gaming-filter-face-ps5", useUnmergedTree = true)
            .assertIsDisplayed()
            .also { face ->
                assertFalse(
                    "filter face must not duplicate the outer click owner",
                    face.fetchSemanticsNode().config.contains(SemanticsActions.OnClick),
                )
            }
        compose.onNodeWithTag("gaming-filter-ps5", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performTouchInput {
                click(Offset(center.x, center.y / 12f))
            }
        compose.runOnIdle {
            assertEquals(
                "outer filter inset must dispatch the exact id once",
                listOf("ps5"),
                fixture.filterSelections,
            )
        }
        capture("gaming-g2-master-aspect-selected-wide", 1280, 563, 1f, "Night")
        assertHeadlineReadabilityWashIsLocalAndContainsEveryTextNode()
        assertRemainingSessionToolsAndSummaryPhysicallyScrollable()

        assertSingleStableRing()
        assertFittedFocalArtwork("ps5-2", "ps5")
        compose.onNodeWithTag("gaming-selected-identity", useUnmergedTree = true)
            .assertIsDisplayed().assertTextContains("Nasih · 3 players")
        compose.onAllNodesWithText("0501234567", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("Notes", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("gaming-selected-action-group-session-tools", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("gaming-primary-action-host", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithTag("gaming-primary-action-host", useUnmergedTree = true)
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertWideFocalWorkspaceFullyVisible("ps5-2")
        fun clickDetailAction(label: String, count: () -> Int) {
            val action = compose.onNode(
                hasText(label) and hasClickAction() and
                    hasAnyAncestor(hasTestTag("gaming-detail-viewport")),
            ).performScrollTo().assertIsDisplayed().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
            val before = count()
            action.performClick()
            compose.runOnIdle { assertEquals("$label callback", before + 1, count()) }
        }
        clickDetailAction("Players") { fixture.manageParticipantsCalls }
        compose.onNodeWithTag("gaming-player-inline-close")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("gaming-selected-action-group-session-tools", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        val addDrinks = compose.onNode(
            hasText("Add drinks") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-detail-viewport")),
        ).performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        addDrinks.performClick()
        compose.onNodeWithTag("gaming-drinks-inline").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Add drinks stays owned by the inline editor", 0, fixture.addItemsCalls)
        }
        compose.onNodeWithTag("gaming-drinks-inline-close")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("gaming-selected-action-group-session-tools", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("Closing drinks must not invoke the legacy dialog callback", 0, fixture.addItemsCalls)
        }
        listOf(
            "Extend" to { fixture.extendPackageCalls },
            "Transfer" to { fixture.transferCalls },
            "Pause" to { fixture.pauseCalls },
            "Cancel" to { fixture.cancelCalls },
        ).forEach { (label, count) -> clickDetailAction(label, count) }
        compose.onAllNodesWithTag("gaming-primary-action-host").assertCountEquals(1)
        val stop = compose.onNodeWithTag("gaming-primary-action-host")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        stop.performClick()
        compose.runOnIdle { assertEquals(1, fixture.stopCalls) }
        board.performScrollToNode(hasTestTag("gaming-station-ps5-1"))
        compose.onNodeWithTag("gaming-station-ps5-1").assertIsDisplayed()
        board.performScrollToNode(hasTestTag("gaming-station-racing-1"))
        compose.onNodeWithTag("gaming-station-racing-1").assertIsDisplayed()
        board.performScrollToNode(hasTestTag("gaming-inline-expansion-ps5-2"))
        compose.onNodeWithTag("gaming-inline-expansion-ps5-2").assertIsDisplayed()
        revealWideFocalWorkspace(board, "ps5-2")
        assertFittedFocalArtwork("ps5-2", "ps5")
        assertSingleStableRing()

        listOf("racing-1" to "racing", "vr-1" to "vr").forEach { (stationId, type) ->
            compose.runOnIdle { fixture.selectedStationId.value = stationId }
            compose.onNodeWithTag("gaming-station-board")
                .performScrollToNode(hasTestTag("gaming-inline-expansion-$stationId"))
            revealWideFocalWorkspace(board, stationId)
            assertFittedFocalArtwork(stationId, type)
            assertSingleStableRing()
        }
    }

    @Test
    fun dayWideActiveSessionRetainsReadableActionsAndTruthfulTiming() {
        val now = Instant.parse("2026-10-24T10:10:00Z").toEpochMilli()
        val active = activeSession(status = "active")
        val fixture = renderGaming(
            1280, 800, 1f, AppearancePreference.Day,
            initialState = baseState(sessions = listOf(active)),
            initialClock = now,
        )
        awaitWorkspaceGlass()
        capture("gaming-g2-day-active-wide", 1280, 800, 1f, "Day")
        assertGamingHierarchy(wide = true)
        assertStationStatus("ps5-1", "ACTIVE")
        assertStationField("ps5-1", "timer", "00:20:00 remaining")
        compose.onNodeWithTag("gaming-station-action-ps5-1")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("End").performClick()
        compose.runOnIdle { assertEquals(1, fixture.stopCalls) }
    }

    @Test
    fun brightScenePausedSessionKeepsSceneDerivedMaterialsAndCrispControls() {
        val cool = controlledWorkspaceImage("gaming-cool.jpg", 810, false)
        val bright = controlledWorkspaceImage("gaming-bright.jpg", 811, true)
        val backgrounds = mutableStateOf(WorkspaceBackgroundUiState(workspace = cool))
        val paused = activeSession(status = "paused").copy(
            pausedAt = "2026-10-24T10:08:00Z",
            pausedDurationMs = 120_000,
            pauseVersion = 1,
            pauseAvailable = true,
        )
        val fixture = renderGaming(
            1280, 800, 1f, AppearancePreference.Night,
            initialState = baseState(sessions = listOf(paused)),
            backgrounds = backgrounds,
        )
        awaitWorkspaceGlass()
        val coolPixel = stationMaterialPixel("gaming-station-ps5-2")
        compose.runOnIdle { backgrounds.value = WorkspaceBackgroundUiState(workspace = bright) }
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1 &&
                colorDistance(coolPixel, stationMaterialPixel("gaming-station-ps5-2")) >= 12
        }
        capture("gaming-g2-bright-paused-wide", 1280, 800, 1f, "Night")
        val brightPixel = stationMaterialPixel("gaming-station-ps5-2")
        assertTrue("ordinary station glass must respond to the shared scene", colorDistance(coolPixel, brightPixel) >= 12)
        assertRenderedHeadlineContrast("gaming-headline-title", threshold = 3.0)
        assertRenderedHeadlineContrast("gaming-headline-subtitle", threshold = 4.5)
        assertRenderedHeadlineContrast("gaming-headline-summary", threshold = 4.5)
        assertHeadlineReadabilityWashIsLocalAndContainsEveryTextNode()
        val uniform = controlledUniformWorkspaceImage("gaming-uniform-white.jpg", 812, Color.WHITE)
        compose.runOnIdle { backgrounds.value = WorkspaceBackgroundUiState(workspace = uniform) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(uniform.generation, backgrounds.value.workspace.generation)
            assertEquals(uniform.contentId, backgrounds.value.workspace.contentId)
        }
        compose.waitUntil(12_000) {
            val observed = fixture.observedScene.get()
            compose.onAllNodesWithTag("workspace-background-ready-workspace")
                .fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("workspace-glass-ready-workspace")
                    .fetchSemanticsNodes().size == 1 &&
                observed?.state == "REQUESTED_READY" &&
                observed.contentId == uniform.contentId &&
                observed.generation == uniform.generation &&
                observed.derivativeMatchesScene
        }
        assertUniformWorkspaceScene(fixture, Color.WHITE)
        assertHeadlineWashFeathersAgainstUniformScene()
        assertStationStatus("ps5-1", "PAUSED")
        compose.onNodeWithTag("gaming-selected-status", useUnmergedTree = true)
            .assertIsDisplayed().assertTextContains("PAUSED", ignoreCase = true)
        assertSingleStableRing()
    }

    @Test
    fun compactLargeTextKeepsRowsControlsAndSelectedExpansionReachable() {
        assertFalse(
            "compact permission-banner evidence must run in the root-owned denied alarm lane",
            compose.activity.getSystemService(android.app.AlarmManager::class.java)
                .canScheduleExactAlarms(),
        )
        val fixture = renderGaming(600, 800, 1.3f, AppearancePreference.Night)
        awaitWorkspaceGlass()
        capture("gaming-g2-compact-font1_3", 600, 800, 1.3f, "Night")
        assertHeadlineReadabilityWashIsLocalAndContainsEveryTextNode()
        assertGamingHierarchy(wide = false)
        assertCompleteText("gaming-headline-title", "Gaming Stations")
        assertCompleteText("gaming-headline-subtitle", "Manage and monitor all stations")
        assertCompleteText("gaming-headline-summary", "0 active · 3 available · 0 payment due · Open")
        assertCompactHeadlineRefresh("Refresh")
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(refreshing = true) }
        compose.waitForIdle()
        assertCompactHeadlineRefresh("Refreshing")
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(refreshing = false) }
        compose.waitForIdle()
        assertCompleteText("Gaming timer alerts off")
        assertCompleteText("Enable notifications and precise alarms. Billing time stays accurate.")
        assertPermissionBannerActions()
        assertCompleteText(
            "compact-status-header",
            "Status",
            ancestorTag = "gaming-station-table-header",
        )
        assertCompleteText(
            "compact-duration-header",
            "Duration",
            ancestorTag = "gaming-station-table-header",
        )
        assertStationStatus("ps5-1", "AVAILABLE")
        compose.onNodeWithTag("gaming-filter-all").assertIsDisplayed()
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("gaming-station-board")
            .performScrollToNode(hasTestTag("gaming-inline-expansion-ps5-1"))
        val compactBoard = compose.onNodeWithTag("gaming-station-board").assertIsDisplayed()
        revealCompactTargetWithBoard(
            board = compactBoard,
            target = compose.onNodeWithTag("gaming-start-action", useUnmergedTree = true),
            diagnostic = "reveal compact Start",
            scope = CompactViewportScope.Detail,
        )
        val compactStart = compose.onNodeWithTag("gaming-start-action")
        captureGamingDiagnostic(
            "gaming-r7-6-compact-start-before-assert",
            mapOf(
                "root" to bounds("gaming-glass-fixture-root"),
                "board" to bounds("gaming-station-board"),
                "expansion" to bounds("gaming-inline-expansion-ps5-1"),
                "pane" to bounds("gaming-control-pane"),
                "detail" to bounds("gaming-detail-viewport"),
                "dock" to bounds("workspace-bottom-dock"),
                "target" to compactStart.fetchSemanticsNode().boundsInRoot,
            ),
        )
        compactStart.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertTargetInsideGamingViewports("compact Start", compactStart)
        assertAboveCompactDock("gaming-start-action")

        compose.runOnIdle {
            fixture.state.value = baseState(sessions = listOf(activeSession(status = "active")))
        }
        compose.onNodeWithTag("gaming-station-board")
            .performScrollToNode(hasTestTag("gaming-station-ps5-1"))
        compose.onNodeWithTag("gaming-station-ps5-1", useUnmergedTree = true)
            .assertIsDisplayed()
        assertStationStatus("ps5-1", "ACTIVE")
        val players = compose.onNode(
            hasText("Players") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealCompactTargetWithBoard(compactBoard, players, "reveal Players", CompactViewportScope.Detail)
        compose.runOnIdle { assertEquals(0, fixture.manageParticipantsCalls) }
        players.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("gaming-player-inline").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, fixture.manageParticipantsCalls) }
        val playerClose = compose.onNodeWithTag("gaming-player-inline-close")
        revealCompactTargetWithBoard(
            compactBoard, playerClose, "reveal Players Close", CompactViewportScope.Detail,
        )
        playerClose.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .performClick()

        val drinks = compose.onNode(
            hasText("Add drinks") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-control-pane")),
        )
        revealCompactTargetWithBoard(compactBoard, drinks, "reveal Add drinks", CompactViewportScope.Detail)
        drinks.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("gaming-drinks-inline").assertIsDisplayed()
        val drinksClose = compose.onNodeWithTag("gaming-drinks-inline-close")
        revealCompactTargetWithBoard(
            compactBoard, drinksClose, "reveal Add drinks Close", CompactViewportScope.Detail,
        )
        drinksClose.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertAboveCompactDock("gaming-drinks-inline-close")
        drinksClose.performClick()
        compose.onNodeWithTag("gaming-station-board")
            .performScrollToNode(hasTestTag("gaming-station-ps5-1"))
        val stationAction = compose.onNodeWithTag("gaming-station-action-ps5-1")
        revealCompactTargetWithBoard(
            compactBoard, stationAction, "reveal station action", CompactViewportScope.Row,
        )
        stationAction.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("gaming-station-board")
            .performScrollToNode(hasTestTag("gaming-inline-expansion-ps5-1"))
        revealCompactTargetWithBoard(
            board = compactBoard,
            target = compose.onNodeWithTag("gaming-control-ring", useUnmergedTree = true),
            diagnostic = "return to the compact ring",
            scope = CompactViewportScope.Ring,
        )
        val compactPaneValue = compose.onNodeWithTag("gaming-control-pane")
            .assertIsDisplayed().fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
        assertTrue(
            "closing the inline editor must restore the keyed compact pane to its top: $compactPaneValue",
            compactPaneValue <= 1f,
        )
        assertVisibleSquareRingInsideGamingViewports()
        assertSingleStableRing()
    }

    @Test
    fun stationKeyedRingSurvivesTicksConnectivityAndSessionTransitionsWithoutCallbackLeaks() {
        val fixture = renderGaming(1280, 800, 1f, AppearancePreference.Night)
        awaitWorkspaceGlass()
        val initialRing = compose.onNodeWithTag("gaming-control-ring").fetchSemanticsNode().boundsInRoot

        repeat(40) { compose.runOnIdle { fixture.wallClock.longValue += 1_000L } }
        assertStableRing(initialRing)
        compose.runOnIdle { fixture.state.value = fixture.state.value.copy(online = false) }
        assertStableRing(initialRing)

        val pending = activeSession(status = "starting").copy(localState = "start_pending")
        val active = activeSession(status = "active")
        val paused = active.copy(
            status = "paused", pausedAt = "2026-10-24T10:08:00Z",
            pausedDurationMs = 120_000, pauseVersion = 1, pauseAvailable = true,
        )
        val failure = activeSession(status = "start_failed").copy(lastError = "Start needs review")
        listOf(pending, active, paused, failure).forEach { session ->
            compose.runOnIdle { fixture.state.value = fixture.state.value.copy(sessions = listOf(session)) }
            assertStableRing(initialRing)
            compose.onNodeWithTag("gaming-station-ps5-2").assertIsDisplayed()
        }

        compose.runOnIdle {
            fixture.access.value = GamingAccess(canManageSessions = false)
            fixture.state.value = baseState()
        }
        compose.onNodeWithTag("gaming-station-board")
            .performScrollToNode(hasTestTag("gaming-inline-expansion-ps5-1"))
        val deniedStart = compose.onNodeWithTag("gaming-start-action").performScrollTo()
        captureGamingDiagnostic(
            "gaming-r7-6-denied-start-before-assert",
            mapOf(
                "root" to bounds("gaming-glass-fixture-root"),
                "board" to bounds("gaming-station-board"),
                "expansion" to bounds("gaming-inline-expansion-ps5-1"),
                "pane" to bounds("gaming-control-pane"),
                "detail" to bounds("gaming-detail-viewport"),
                "target" to deniedStart.fetchSemanticsNode().boundsInRoot,
            ),
        )
        deniedStart.assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        assertTargetInsideGamingViewports("denied Start", deniedStart)
        deniedStart.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(0, fixture.startCalls)
            assertEquals(0, fixture.confirmStartCalls)
            assertEquals(0, fixture.pauseCalls)
        }
        assertStableRing(initialRing)
    }

    @Test
    fun defaultSectionCardAndTransparentOptInUseSceneMaterialWhileFallbackStaysSolid() {
        val cool = controlledWorkspaceImage("section-cool.jpg", 820, false)
        val bright = controlledWorkspaceImage("section-bright.jpg", 821, true)
        val image = mutableStateOf(cool)
        renderSectionCardFixture(image)
        awaitWorkspaceGlass()
        val standardCool = centerPixel("section-standard")
        val transparentCool = centerPixel("section-transparent")
        val fallbackCool = centerPixel("section-fallback")

        compose.runOnIdle { image.value = bright }
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1 &&
                colorDistance(standardCool, centerPixel("section-standard")) >= 12 &&
                colorDistance(transparentCool, centerPixel("section-transparent")) >= 12
        }
        val standardBright = centerPixel("section-standard")
        val transparentBright = centerPixel("section-transparent")
        val fallbackBright = centerPixel("section-fallback")
        assertTrue(
            "default SectionCard must derive its material from the shared scene",
            colorDistance(standardCool, standardBright) >= 12,
        )
        assertTrue(
            "transparent SectionCard opt-in must expose scene-derived material",
            colorDistance(transparentCool, transparentBright) >= 12,
        )
        assertTrue(
            "a SectionCard without a workspace scene must retain its solid fallback",
            colorDistance(fallbackCool, fallbackBright) <= 4,
        )
    }

    @Test
    fun defaultOperationalBannerKeepsItsEstablishedHorizontalActionLayout() {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface {
                        Box(Modifier.width(720.dp).height(160.dp).padding(16.dp)) {
                            OperationalBanner(
                                title = "Existing operational notice",
                                detail = "The default banner contract keeps its action beside the notice.",
                                tone = UiTone.Information,
                                icon = Icons.Filled.Info,
                                modifier = Modifier.testTag("default-operational-banner"),
                            ) {
                                ErpButton(
                                    text = "Acknowledge",
                                    onClick = {},
                                    intent = ActionIntent.Secondary,
                                    modifier = Modifier.testTag("default-operational-action"),
                                )
                            }
                        }
                    }
                }
            }
        }
        val banner = compose.onNodeWithTag("default-operational-banner")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("default-operational-action")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithText(
            "The default banner contract keeps its action beside the notice.",
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertNonEmptyAndContained("default banner action", action, banner)
        assertTrue("default banner action must remain beside its detail", action.left >= detail.right - 1f)
    }

    private data class Fixture(
        val state: MutableState<GamingUiState>,
        val access: MutableState<GamingAccess>,
        val selectedStationId: MutableState<String>,
        val wallClock: MutableLongState,
        var startCalls: Int = 0,
        var confirmStartCalls: Int = 0,
        var stopCalls: Int = 0,
        var pauseCalls: Int = 0,
        var manageParticipantsCalls: Int = 0,
        var addItemsCalls: Int = 0,
        var extendPackageCalls: Int = 0,
        var transferCalls: Int = 0,
        var cancelCalls: Int = 0,
        val filterSelections: MutableList<String> = mutableListOf(),
        val observedScene: AtomicReference<ObservedScene?> = AtomicReference(null),
    )

    private data class ObservedScene(
        val state: String,
        val contentId: String,
        val generation: Long,
        val derivativeMatchesScene: Boolean,
        val pixels: List<Int>,
    )

    private fun renderGaming(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        appearance: AppearancePreference,
        initialState: GamingUiState = baseState(),
        initialClock: Long = Instant.parse("2026-10-24T10:10:00Z").toEpochMilli(),
        backgrounds: MutableState<WorkspaceBackgroundUiState> = mutableStateOf(WorkspaceBackgroundUiState()),
        initialSelectedStationId: String = "ps5-1",
        activityResultRegistryOwner: ActivityResultRegistryOwner? = null,
    ): Fixture {
        val fixture = Fixture(
            state = mutableStateOf(initialState),
            access = mutableStateOf(GamingAccess(canManageSessions = true)),
            selectedStationId = mutableStateOf(initialSelectedStationId),
            wallClock = mutableLongStateOf(initialClock),
        )
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            val hostConfiguration = LocalConfiguration.current
            val inheritedRegistryOwner = LocalActivityResultRegistryOwner.current
            val effectiveRegistryOwner = requireNotNull(
                activityResultRegistryOwner ?: inheritedRegistryOwner,
            ) { "Gaming fixture requires an ActivityResultRegistryOwner" }
            val fixtureConfiguration = remember(widthDp, heightDp, hostConfiguration) {
                Configuration(hostConfiguration).apply {
                    screenWidthDp = widthDp
                    screenHeightDp = heightDp
                    smallestScreenWidthDp = minOf(widthDp, heightDp)
                    this.fontScale = fontScale
                    orientation = if (widthDp >= heightDp) {
                        Configuration.ORIENTATION_LANDSCAPE
                    } else {
                        Configuration.ORIENTATION_PORTRAIT
                    }
                }
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalConfiguration provides fixtureConfiguration,
                LocalActivityResultRegistryOwner provides effectiveRegistryOwner,
            ) {
                DCompanyTheme(appearance) {
                    Surface {
                        Box(
                            Modifier.width(widthDp.dp).height(heightDp.dp)
                                .testTag("gaming-glass-fixture-root"),
                        ) {
                            WorkspaceScaffold(
                                destinations = listOf(
                                    Destination.Dashboard, Destination.Gaming, Destination.Pos,
                                    Destination.Shift, Destination.Customers, Destination.Inventory,
                                    Destination.Reports, Destination.Analytics, Destination.Settings,
                                ),
                                currentDestination = Destination.Gaming,
                                employeeName = "Nasih",
                                locationLabel = "Main Shop",
                                connectivityProblem = SyncAvailabilityProblem.NONE,
                                outboxWorkStatus = OutboxWorkStatus(),
                                syncing = false,
                                canChangeTill = false,
                                appearancePreference = appearance,
                                onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                                backgroundState = backgrounds.value,
                            ) { _, _ ->
                                val scene = LocalWorkspaceGlassScene.current
                                SideEffect {
                                    val frame = scene?.frame
                                    fixture.observedScene.set(frame?.let {
                                        ObservedScene(
                                            state = it.state.name,
                                            contentId = it.actualKey.contentId,
                                            generation = it.actualKey.generation,
                                            derivativeMatchesScene =
                                                scene.softenedKey?.actualKey == it.actualKey,
                                            pixels = it.clearBitmap.let { bitmap ->
                                                listOf(
                                                    0.12f to 0.18f,
                                                    0.50f to 0.18f,
                                                    0.88f to 0.18f,
                                                    0.12f to 0.82f,
                                                    0.50f to 0.82f,
                                                    0.88f to 0.82f,
                                                ).map { (x, y) ->
                                                    bitmap.getPixel(
                                                        (bitmap.width * x).roundToInt()
                                                            .coerceIn(0, bitmap.width - 1),
                                                        (bitmap.height * y).roundToInt()
                                                            .coerceIn(0, bitmap.height - 1),
                                                    )
                                                }
                                            },
                                        )
                                    })
                                }
                                GamingCommandWorkspace(
                                    state = fixture.state.value,
                                    access = fixture.access.value,
                                    filters = listOf(
                                        StationFilter("all", "All"), StationFilter("ps5", "PS5"),
                                        StationFilter("racing", "Racing"),
                                    ),
                                    selectedFilter = "all",
                                    visibleStations = fixture.state.value.stations,
                                    selectedStationId = fixture.selectedStationId.value,
                                    showDetail = false,
                                    focusStationId = null,
                                    focusRequested = false,
                                    startTerminalBlockMessage = null,
                                    wallClock = fixture.wallClock,
                                    attentionCount = 0,
                                    onSelectFilter = { fixture.filterSelections += it },
                                    onSelectStation = { fixture.selectedStationId.value = it },
                                    onBackToBoard = {}, onOpenAttention = {}, onRefresh = {},
                                    onStart = { fixture.startCalls += 1 },
                                    onConfirmStart = { _, _, _, _, _, _, _, _ -> fixture.confirmStartCalls += 1 },
                                    onStop = { _, _ -> fixture.stopCalls += 1 },
                                    onSend = {}, onCancelUnbilled = { fixture.cancelCalls += 1 },
                                    onExtendTimer = {},
                                    onExtendPackage = { _, _ -> fixture.extendPackageCalls += 1 },
                                    onTransfer = { fixture.transferCalls += 1 },
                                    onPauseResume = { fixture.pauseCalls += 1 }, onReconcile = {},
                                    onRepairBilling = {}, onResolveLegacyStart = {},
                                    onDiscardPackageExtension = {},
                                    onAddItems = { fixture.addItemsCalls += 1 },
                                    onVoidAddon = { _, _ -> }, onReviewRejectedAddon = {},
                                    onManageParticipants = { fixture.manageParticipantsCalls += 1 },
                                )
                            }
                        }
                    }
                }
            }
        }
        return fixture
    }

    private fun renderSectionCardFixture(image: MutableState<WorkspaceBackgroundImage>) {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    WorkspaceBackgroundRenderer(image = image.value, modifier = Modifier.width(640.dp).height(440.dp)) {
                        Column(Modifier.fillMaxSize().padding(32.dp)) {
                            SectionCard(
                                modifier = Modifier.width(300.dp).height(100.dp).testTag("section-standard"),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) { Spacer(Modifier.fillMaxSize()) }
                            Spacer(Modifier.height(24.dp))
                            SectionCard(
                                modifier = Modifier.width(300.dp).height(100.dp)
                                    .workspaceGlass(
                                        LocalWorkspaceGlassScene.current,
                                        WorkspaceGlassRole.PANEL,
                                        day = false,
                                        shape = Radius.shapeLg,
                                    )
                                    .testTag("section-transparent"),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                surfaceMode = SectionCardSurfaceMode.Transparent,
                            ) { Spacer(Modifier.fillMaxSize()) }
                            Spacer(Modifier.height(24.dp))
                            CompositionLocalProvider(LocalWorkspaceGlassScene provides null) {
                                SectionCard(
                                    modifier = Modifier.width(300.dp).height(100.dp).testTag("section-fallback"),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) { Spacer(Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertGamingHierarchy(wide: Boolean, selectedStationId: String = "ps5-1") {
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
        compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
        compose.onNodeWithTag("gaming-station-table-header").assertIsDisplayed()
        compose.onNodeWithTag("gaming-station-ps5-1").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("gaming-inline-expansion-$selectedStationId").assertIsDisplayed()
        compose.onNodeWithTag("gaming-control-pane").assertIsDisplayed()
        if (wide) compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
    }

    private fun assertSingleStableRing(expected: androidx.compose.ui.geometry.Rect? = null) {
        compose.onAllNodesWithTag("gaming-control-ring").assertCountEquals(1)
        val ring = compose.onNodeWithTag("gaming-control-ring").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        expected?.let {
            assertEquals(it.center.x, ring.center.x, 1f)
            assertEquals(it.center.y, ring.center.y, 1f)
            assertEquals(it.width, ring.width, 1f)
            assertEquals(it.height, ring.height, 1f)
        }
    }

    private fun revealWideFocalWorkspace(
        board: SemanticsNodeInteraction,
        stationId: String,
    ) {
        val art = compose.onNodeWithTag("gaming-focal-art-$stationId", useUnmergedTree = true)
        val ring = compose.onNodeWithTag("gaming-control-ring", useUnmergedTree = true)
        repeat(4) {
            val geometry = wideFocalGeometry(stationId, art, ring)
            if (geometry.targetsInsideVisible) return
            val rangeBefore = board.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            val before = rangeBefore.value()
            val moveContentUp = geometry.targets.bottom > geometry.visible.bottom + 1f
            val moveContentDown = geometry.targets.top < geometry.visible.top - 1f
            if (!moveContentUp && !moveContentDown) {
                captureWideFocalRevealFailure(stationId, geometry, rangeBefore)
                error("Focal workspace has no valid reveal direction: $geometry range=$rangeBefore")
            }
            val canMove = if (moveContentUp) rangeBefore.maxValue() > before else before > 0f
            if (!canMove) {
                captureWideFocalRevealFailure(stationId, geometry, rangeBefore)
                error("Focal workspace cannot move its station-board owner: $geometry range=$rangeBefore")
            }
            val missingPx = if (moveContentUp) {
                geometry.targets.bottom - geometry.visible.bottom
            } else {
                geometry.visible.top - geometry.targets.top
            }
            val touchSlopPx = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
            board.performTouchInput {
                // Move only the missing distance plus touch slop, then hold at
                // the end so velocity tracking cannot fling past the narrow
                // fully-visible interval. The station-label gutter keeps this
                // gesture owned by the outer board, not the detail viewport.
                val distance = (missingPx + touchSlopPx + 2f)
                    .coerceAtLeast(touchSlopPx + 2f)
                    .coerceAtMost(height * 0.68f)
                val start = Offset(
                    x = width * 0.06f,
                    y = height * if (moveContentUp) 0.84f else 0.16f,
                )
                val end = Offset(
                    x = start.x,
                    y = start.y + if (moveContentUp) -distance else distance,
                )
                down(start)
                moveTo(end, delayMillis = 240)
                advanceEventTime(160L)
                up()
            }
            compose.waitUntil(5_000) {
                val after = board.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value()
                if (moveContentUp) after > before else after < before
            }
            val after = board.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value()
            assertTrue(
                "Focal reveal must move the station-board owner in the required direction: " +
                    "up=$moveContentUp before=$before after=$after",
                if (moveContentUp) after > before else after < before,
            )
        }
        val geometry = wideFocalGeometry(stationId, art, ring)
        if (!geometry.targetsInsideVisible) {
            val range = board.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            captureWideFocalRevealFailure(stationId, geometry, range)
            error("Focal artwork and ring remained clipped after bounded owner movement: " +
                "$geometry range=$range")
        }
    }

    private fun assertWideFocalWorkspaceFullyVisible(stationId: String) {
        val geometry = wideFocalGeometry(
            stationId = stationId,
            art = compose.onNodeWithTag("gaming-focal-art-$stationId", useUnmergedTree = true),
            ring = compose.onNodeWithTag("gaming-control-ring", useUnmergedTree = true),
        )
        assertTrue(
            "the master must contain complete art, ring, identity and primary footer below " +
                "the sticky table header: $geometry",
            geometry.targetsInsideVisible,
        )
        compose.onNodeWithTag("gaming-selected-identity-zone", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithTag("gaming-primary-action-host", useUnmergedTree = true)
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }

    private fun assertInitialReadyMasterScene(stationId: String, neighbourId: String) {
        val root = bounds("gaming-glass-fixture-root")
        val board = bounds("gaming-station-board")
        listOf("workspace-header", "gaming-photo-header", "gaming-filter-chips").forEach { tag ->
            assertNonEmptyAndContained("initial ready $tag", unclippedBoundsPx(
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed(),
            ), root)
        }
        val allFilter = compose.onNodeWithTag("gaming-filter-all", useUnmergedTree = true)
            .assertIsDisplayed().assertHasClickAction().assertHeightIsEqualTo(48.dp)
        val allFilterFace = compose.onNodeWithTag("gaming-filter-face-all", useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsEqualTo(40.dp)
        assertNonEmptyAndContained(
            "40dp filter face inside 48dp click owner",
            unclippedBoundsPx(allFilterFace),
            unclippedBoundsPx(allFilter),
        )
        val boardViewport = intersection(root, board)
        val stickyHeader = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-station-table-header", useUnmergedTree = true)
                .assertIsDisplayed(),
        )
        assertNonEmptyAndContained("initial ready gaming-station-table-header", stickyHeader, boardViewport)
        val contentViewport = Rect(
            boardViewport.left,
            maxOf(boardViewport.top, stickyHeader.bottom),
            boardViewport.right,
            boardViewport.bottom,
        )
        assertTrue(
            "initial ready board must retain content below its sticky header: " +
                "board=$boardViewport header=$stickyHeader content=$contentViewport",
            contentViewport.width > 0f && contentViewport.height > 0f,
        )
        listOf(
            "gaming-station-$neighbourId",
            "gaming-station-$stationId",
            "gaming-inline-expansion-$stationId",
            "gaming-selected-identity-zone",
        ).forEach { tag ->
            assertNonEmptyAndContained("initial ready $tag", unclippedBoundsPx(
                compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed(),
            ), contentViewport)
        }
        assertWideFocalWorkspaceFullyVisible(stationId)
        assertFittedFocalArtwork(stationId, "ps5")
        val density = compose.activity.resources.displayMetrics.density
        val ring = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-control-ring", useUnmergedTree = true).assertIsDisplayed(),
        )
        assertEquals("ready master ring width", 196f * density, ring.width, 1f)
        assertEquals("ready master ring height", 196f * density, ring.height, 1f)
        val primary = compose.onNodeWithTag("gaming-primary-action-host")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertNonEmptyAndContained(
            "initial ready primary footer",
            unclippedBoundsPx(primary),
            contentViewport,
        )
    }

    private fun assertInitialReadySessionTools() {
        val viewport = listOf(
            bounds("gaming-glass-fixture-root"),
            bounds("gaming-detail-viewport"),
            bounds("gaming-selected-detail-list"),
        ).reduce(::intersection)
        compose.onNodeWithTag("gaming-selected-tools-surface", useUnmergedTree = true)
            .assertIsDisplayed()
        val extend = compose.onNode(
            hasText("Extend") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-selected-tools-surface")),
        ).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertNonEmptyAndContained("initial ready Extend", unclippedBoundsPx(extend), viewport)
        val transfer = compose.onNode(
            hasText("Transfer") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-selected-tools-surface")),
        ).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertNonEmptyAndContained("initial ready Transfer", unclippedBoundsPx(transfer), viewport)
        val pause = compose.onNode(
            hasText("Pause") and hasClickAction() and
                hasAnyAncestor(hasTestTag("gaming-selected-tools-surface")),
        )
        val cancel = compose.onNodeWithTag("gaming-cancel-active-session", useUnmergedTree = true)
        listOf("Pause" to pause, "Cancel" to cancel).forEach { (label, action) ->
            val actionBounds = unclippedBoundsPx(action.assertHasClickAction().assertHeightIsAtLeast(48.dp))
            assertTrue(
                "initial ready viewport must end in the intentional separator before $label, " +
                    "not expose a clipped control: action=$actionBounds viewport=$viewport",
                actionBounds.top >= viewport.bottom - 1f,
            )
        }
        compose.onNodeWithTag("gaming-selected-customer-context-label", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals("Customer")
        compose.onNodeWithTag("gaming-selected-identity", useUnmergedTree = true)
            .assertIsDisplayed().assertTextContains("Nasih · 3 players")
        compose.onAllNodesWithTag("gaming-primary-action-host", useUnmergedTree = true)
            .assertCountEquals(1)
        assertNonEmptyAndContained(
            "initial ready sole Stop footer",
            unclippedBoundsPx(
                compose.onNodeWithTag("gaming-primary-action-host", useUnmergedTree = true)
                    .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp),
            ),
            intersection(
                bounds("gaming-glass-fixture-root"),
                bounds("gaming-detail-viewport"),
            ),
        )
    }

    private fun assertRemainingSessionToolsAndSummaryPhysicallyScrollable() {
        val owner = compose.onNodeWithTag("gaming-selected-detail-list", useUnmergedTree = true)
            .assertIsDisplayed()
        val range = owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val initial = range.value()
        listOf("Players", "Add drinks").forEach { label ->
            revealSelectedDetailTarget(
                owner = owner,
                target = compose.onNode(
                    hasText(label) and hasClickAction() and
                        hasAnyAncestor(hasTestTag("gaming-selected-tools-surface")),
                ).assertHasClickAction().assertHeightIsAtLeast(48.dp),
                diagnostic = "remaining $label tool",
            )
        }
        revealSelectedDetailTarget(
            owner = owner,
            target = compose.onNode(
                hasTestTag("gaming-cancel-active-session") and hasClickAction() and
                    hasContentDescription("Cancel session") and
                    hasAnyAncestor(hasTestTag("gaming-selected-tools-surface")),
                useUnmergedTree = true,
            ).assertHasClickAction().assertHeightIsAtLeast(48.dp),
            diagnostic = "remaining Cancel tool",
        )
        revealSelectedDetailTarget(
            owner = owner,
            target = compose.onNodeWithTag("gaming-selected-summary-surface", useUnmergedTree = true),
            diagnostic = "booked summary",
        )
        val after = owner.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
        assertTrue(
            "remaining tools and reasons must be reachable through the one selected-detail owner: " +
                "before=$initial after=$after max=${range.maxValue()}",
            after > initial,
        )
    }

    private fun revealSelectedDetailTarget(
        owner: SemanticsNodeInteraction,
        target: SemanticsNodeInteraction,
        diagnostic: String,
    ) {
        repeat(4) {
            val viewport = listOf(
                bounds("gaming-glass-fixture-root"),
                bounds("gaming-detail-viewport"),
                bounds("gaming-selected-detail-list"),
            ).reduce(::intersection)
            val targetBounds = unclippedBoundsPx(target)
            val inside = targetBounds.width > 0f && targetBounds.height > 0f &&
                targetBounds.left >= viewport.left - 1f && targetBounds.top >= viewport.top - 1f &&
                targetBounds.right <= viewport.right + 1f && targetBounds.bottom <= viewport.bottom + 1f
            if (inside) return
            val range = owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val moveContentUp = targetBounds.bottom > viewport.bottom + 1f
            val moveContentDown = targetBounds.top < viewport.top - 1f
            check(moveContentUp || moveContentDown) {
                "$diagnostic has no physical reveal direction: target=$targetBounds viewport=$viewport"
            }
            check(if (moveContentUp) range.maxValue() > before else before > 0f) {
                "$diagnostic cannot move the selected-detail owner: target=$targetBounds " +
                    "viewport=$viewport range=$range"
            }
            val missing = if (moveContentUp) {
                targetBounds.bottom - viewport.bottom
            } else {
                viewport.top - targetBounds.top
            }
            val touchSlop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
            owner.performTouchInput {
                val distance = (missing + touchSlop + 2f)
                    .coerceAtLeast(touchSlop + 2f)
                    .coerceAtMost(height * 0.68f)
                val start = Offset(width * 0.80f, height * if (moveContentUp) 0.84f else 0.16f)
                val end = Offset(start.x, start.y + if (moveContentUp) -distance else distance)
                down(start)
                moveTo(end, delayMillis = 240)
                advanceEventTime(160L)
                up()
            }
            compose.waitUntil(5_000) {
                val after = owner.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value()
                if (moveContentUp) after > before else after < before
            }
            val after = owner.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value()
            assertTrue(
                "$diagnostic must move the selected-detail owner in the required direction: " +
                    "up=$moveContentUp before=$before after=$after",
                if (moveContentUp) after > before else after < before,
            )
        }
        val viewport = listOf(
            bounds("gaming-glass-fixture-root"),
            bounds("gaming-detail-viewport"),
            bounds("gaming-selected-detail-list"),
        ).reduce(::intersection)
        assertNonEmptyAndContained(diagnostic, unclippedBoundsPx(target), viewport)
    }

    private fun captureWideFocalRevealFailure(
        stationId: String,
        geometry: WideFocalGeometry,
        range: androidx.compose.ui.semantics.ScrollAxisRange,
    ) {
        captureGamingDiagnostic(
            "gaming-g2-$stationId-focal-reveal-failure",
            mapOf(
                "artwork_unclipped" to geometry.artwork,
                "ring_unclipped" to geometry.ring,
                "identity_unclipped" to geometry.identity,
                "primary_footer_unclipped" to geometry.primaryFooter,
                "sticky_header_unclipped" to geometry.stickyHeader,
                "combined_targets" to geometry.targets,
                "effective_content_viewport" to geometry.visible,
                "outer_scroll_range" to Rect(range.value(), 0f, range.maxValue(), 1f),
            ),
        )
    }

    private fun wideFocalGeometry(
        stationId: String,
        art: SemanticsNodeInteraction,
        ring: SemanticsNodeInteraction,
    ): WideFocalGeometry {
        val artBounds = unclippedBoundsPx(art)
        val ringBounds = unclippedBoundsPx(ring)
        val identityBounds = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-selected-identity-zone", useUnmergedTree = true),
        )
        compose.onAllNodesWithTag("gaming-primary-action-host", useUnmergedTree = true)
            .assertCountEquals(1)
        val primaryFooterBounds = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-primary-action-host", useUnmergedTree = true)
                .assertHasClickAction().assertHeightIsAtLeast(48.dp),
        )
        val stickyHeaderBounds = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-station-table-header", useUnmergedTree = true)
                .assertIsDisplayed(),
        )
        val targets = Rect(
            minOf(artBounds.left, ringBounds.left, identityBounds.left, primaryFooterBounds.left),
            minOf(artBounds.top, ringBounds.top, identityBounds.top, primaryFooterBounds.top),
            maxOf(artBounds.right, ringBounds.right, identityBounds.right, primaryFooterBounds.right),
            maxOf(artBounds.bottom, ringBounds.bottom, identityBounds.bottom, primaryFooterBounds.bottom),
        )
        val clippedViewport = listOf(
            bounds("gaming-glass-fixture-root"),
            bounds("gaming-station-board"),
            bounds("gaming-inline-expansion-$stationId"),
            bounds("gaming-control-pane"),
        ).reduce(::intersection)
        val visible = Rect(
            clippedViewport.left,
            maxOf(clippedViewport.top, stickyHeaderBounds.bottom),
            clippedViewport.right,
            clippedViewport.bottom,
        )
        fun Rect.fullyInsideVisible(): Boolean = width > 0f && height > 0f &&
            left >= visible.left - 1f && top >= visible.top - 1f &&
            right <= visible.right + 1f && bottom <= visible.bottom + 1f
        assertTrue(
            "Focal ring must remain a square: $ringBounds",
            ringBounds.width > 0f && abs(ringBounds.width - ringBounds.height) <= 1f,
        )
        return WideFocalGeometry(
            artwork = artBounds,
            ring = ringBounds,
            identity = identityBounds,
            primaryFooter = primaryFooterBounds,
            stickyHeader = stickyHeaderBounds,
            targets = targets,
            visible = visible,
            targetsInsideVisible = artBounds.fullyInsideVisible() &&
                ringBounds.fullyInsideVisible() &&
                identityBounds.fullyInsideVisible() &&
                primaryFooterBounds.fullyInsideVisible(),
        )
    }

    private fun unclippedBoundsPx(target: SemanticsNodeInteraction): Rect {
        val root = compose.onNodeWithTag("gaming-glass-fixture-root", useUnmergedTree = true)
        val rootPx = root.fetchSemanticsNode().boundsInRoot
        val rootDp = root.getUnclippedBoundsInRoot()
        val density = rootPx.width / (rootDp.right - rootDp.left).value
        val targetDp = target.getUnclippedBoundsInRoot()
        return Rect(
            targetDp.left.value * density,
            targetDp.top.value * density,
            targetDp.right.value * density,
            targetDp.bottom.value * density,
        )
    }

    private fun assertFittedFocalArtwork(stationId: String, type: String) {
        val spec = checkNotNull(gamingStationArtworkSpec(type))
        val density = compose.activity.resources.displayMetrics.density
        val artwork = compose.onNodeWithTag("gaming-focal-art-$stationId", useUnmergedTree = true)
            .assertIsDisplayed()
        val art = unclippedBoundsPx(artwork)
        assertTrue("focal artwork must have positive bounds: $art", art.width > 0f && art.height > 0f)
        assertTrue(
            "focal artwork must remain inside the 225dp render cap: $art density=$density",
            art.width <= 225f * density + 1f && art.height <= 225f * density + 1f,
        )
        assertEquals(
            "focal artwork must preserve its source aspect",
            spec.aspectRatio,
            art.width / art.height,
            0.02f,
        )
        assertTrue(
            "focal artwork must never request more pixels than its source: $art source=$spec",
            art.width <= spec.sourceWidthPx + 1f && art.height <= spec.sourceHeightPx + 1f,
        )
    }

    private fun assertStableRing(expected: androidx.compose.ui.geometry.Rect) {
        compose.waitForIdle()
        assertSingleStableRing(expected)
    }

    private fun assertStationStatus(stationId: String, expectedLabel: String) =
        assertStationField(stationId, "status", expectedLabel)

    private fun assertStationField(stationId: String, field: String, expectedLabel: String) {
        val row = compose.onNodeWithTag("gaming-station-$stationId", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val containerTag = "gaming-station-$field-$stationId"
        val container = compose.onNodeWithTag(containerTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val label = compose.onNode(
            hasText(expectedLabel) and
                hasAnyAncestor(hasTestTag(containerTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        println(
            "GAMING_FIELD_BOUNDS " +
                "station=$stationId field=$field label=$expectedLabel row=$row container=$container text=$label",
        )
        assertNonEmptyAndContained("$field container", container, row)
        assertNonEmptyAndContained("$field label", label, container)
        assertCompleteText(expectedLabel, expectedLabel, ancestorTag = containerTag)
    }

    private fun assertCompleteText(tag: String, expected: String) =
        assertCompleteText(tag, expected, ancestorTag = null, queryByTag = true)

    private fun assertCompleteText(expected: String) =
        assertCompleteText(expected, expected, ancestorTag = null, queryByTag = false)

    private fun assertCompleteText(
        diagnostic: String,
        expected: String,
        ancestorTag: String?,
        queryByTag: Boolean = false,
    ) {
        val interaction = when {
            queryByTag -> compose.onNodeWithTag(diagnostic, useUnmergedTree = true)
            ancestorTag != null -> compose.onNode(
                hasText(expected) and hasAnyAncestor(hasTestTag(ancestorTag)),
                useUnmergedTree = true,
            )
            else -> compose.onNodeWithText(expected, useUnmergedTree = true)
        }.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertEquals("$diagnostic must expose the expected text", expected, result.layoutInput.text.text)
        assertEquals(
            "$diagnostic must retain every character",
            expected.length,
            result.getLineEnd(result.lineCount - 1, visibleEnd = false),
        )
        repeat(result.lineCount) { line ->
            assertFalse("$diagnostic line $line must not ellipsize", result.isLineEllipsized(line))
        }
    }

    private fun assertCompactHeadlineRefresh(expected: String) {
        val owner = compose.onNodeWithTag("gaming-headline-refresh", useUnmergedTree = true)
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        val label = compose.onNodeWithTag("gaming-headline-refresh-label", useUnmergedTree = true)
            .assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertEquals(expected, result.layoutInput.text.text)
        assertEquals("$expected must remain on one line at compact font 1.3", 1, result.lineCount)
        assertFalse("$expected must not ellipsize", result.isLineEllipsized(0))
        assertEquals("$expected must retain every character", expected.length, result.getLineEnd(0, visibleEnd = false))
        assertNonEmptyAndContained(
            "compact Refresh label",
            label.fetchSemanticsNode().boundsInRoot,
            owner.fetchSemanticsNode().boundsInRoot,
        )
        assertNonEmptyAndContained(
            "compact Refresh owner",
            owner.fetchSemanticsNode().boundsInRoot,
            bounds("gaming-photo-header"),
        )
    }

    private fun assertAboveCompactDock(tag: String) {
        val target = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("workspace-bottom-dock")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must remain above the compact navigation dock: target=$target dock=$dock", target.bottom <= dock.top + 1f)
    }

    private fun assertTargetInsideGamingViewports(
        diagnostic: String,
        target: SemanticsNodeInteraction,
    ) {
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val rootBounds = bounds("gaming-glass-fixture-root")
        val boardBounds = bounds("gaming-station-board")
        val expansionBounds = bounds("gaming-inline-expansion-ps5-1")
        val paneBounds = bounds("gaming-control-pane")
        val detailBounds = bounds("gaming-detail-viewport")
        val visibleBounds = listOf(
            rootBounds,
            boardBounds,
            expansionBounds,
            paneBounds,
            detailBounds,
        ).reduce(::intersection)
        assertTrue("$diagnostic must retain a visible viewport: $visibleBounds", visibleBounds.width > 0f && visibleBounds.height > 0f)
        assertNonEmptyAndContained(diagnostic, targetBounds, visibleBounds)
    }

    private fun revealCompactTargetWithBoard(
        board: SemanticsNodeInteraction,
        target: SemanticsNodeInteraction,
        diagnostic: String,
        scope: CompactViewportScope,
    ) {
        repeat(4) {
            val geometry = compactTargetGeometry(target, scope)
            if (geometry.targetInsideVisible) return
            val pane = compose.onNodeWithTag("gaming-control-pane").assertIsDisplayed()
            val paneRange = runCatching {
                pane.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            }.getOrNull()
            val targetOutsidePane = scope == CompactViewportScope.Detail && (
                geometry.target.top < geometry.paneUnclipped.top - 1f ||
                    geometry.target.bottom > geometry.paneUnclipped.bottom + 1f
                )
            if (targetOutsidePane && paneRange != null) {
                val beforePane = paneRange.value()
                val beforeBoard = board.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                val moveContentUp = geometry.target.bottom > geometry.paneUnclipped.bottom + 1f
                val canMove = if (moveContentUp) paneRange.maxValue() > beforePane else beforePane > 0f
                if (canMove) {
                    val missingPx = if (moveContentUp) {
                        geometry.target.bottom - geometry.paneUnclipped.bottom
                    } else {
                        geometry.paneUnclipped.top - geometry.target.top
                    }
                    val availablePanePx = if (moveContentUp) {
                        paneRange.maxValue() - beforePane
                    } else {
                        beforePane
                    }
                    if (missingPx > availablePanePx + 1f) {
                        val boardRange = board.fetchSemanticsNode()
                            .config[SemanticsProperties.VerticalScrollAxisRange]
                        captureCompactRevealFailure(diagnostic, geometry, boardRange)
                        error(
                            "$diagnostic cannot fit inside its compact pane: " +
                                "missing=$missingPx available=$availablePanePx " +
                                "paneRange=$paneRange geometry=$geometry",
                        )
                    }
                    // Stop half a pixel before the inner range boundary. The
                    // final geometry allows one physical pixel, so this still
                    // proves full containment while preventing a calibrated
                    // drag from handing rounding residue to the parent.
                    val targetScrollPx = minOf(
                        missingPx + 2f,
                        (availablePanePx - 0.5f).coerceAtLeast(0f),
                    )
                    val touchSlopPx = ViewConfiguration.get(compose.activity)
                        .scaledTouchSlop.toFloat()
                    pane.performTouchInput {
                        // Move only the distance needed to reveal this target.
                        // A full-height swipe can exhaust the inner ScrollState
                        // and correctly hand its remainder to the outer board;
                        // that does not prove which owner moved the target.
                        // Holding before UP prevents fling from crossing the
                        // remaining inner range after the target is visible.
                        val distance = (targetScrollPx + touchSlopPx)
                            .coerceAtMost(height * 0.68f)
                        val start = Offset(
                            x = width * 0.82f,
                            y = height * if (moveContentUp) 0.84f else 0.16f,
                        )
                        val end = Offset(
                            x = start.x,
                            y = start.y + if (moveContentUp) -distance else distance,
                        )
                        down(start)
                        moveTo(end, delayMillis = 240)
                        advanceEventTime(160L)
                        up()
                    }
                    compose.waitUntil(5_000) {
                        val after = pane.fetchSemanticsNode()
                            .config[SemanticsProperties.VerticalScrollAxisRange].value()
                        if (moveContentUp) after > beforePane else after < beforePane
                    }
                    val afterPane = pane.fetchSemanticsNode()
                        .config[SemanticsProperties.VerticalScrollAxisRange].value()
                    val afterBoard = board.fetchSemanticsNode()
                        .config[SemanticsProperties.VerticalScrollAxisRange].value()
                    assertTrue(
                        "$diagnostic must move only the compact pane when its target is outside that pane: " +
                            "up=$moveContentUp before=$beforePane after=$afterPane",
                        if (moveContentUp) afterPane > beforePane else afterPane < beforePane,
                    )
                    assertTrue(
                        "$diagnostic inner-pane gesture must not move the station board: " +
                            "before=$beforeBoard after=$afterBoard",
                        abs(afterBoard - beforeBoard) <= 1f,
                    )
                    return@repeat
                }
            }
            val beforeRange = board.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            val before = beforeRange.value()
            val moveContentUp = geometry.target.bottom > geometry.visible.bottom + 1f
            val moveContentDown = geometry.target.top < geometry.visible.top - 1f
            if (!moveContentUp && !moveContentDown) {
                captureCompactRevealFailure(diagnostic, geometry, beforeRange)
                error("$diagnostic has no valid vertical reveal direction: $geometry range=$beforeRange")
            }
            val canMove = if (moveContentUp) beforeRange.maxValue() > before else before > 0f
            if (!canMove) {
                captureCompactRevealFailure(diagnostic, geometry, beforeRange)
                error("$diagnostic cannot move its outer station-board owner: $geometry range=$beforeRange")
            }
            board.performTouchInput {
                swipe(
                    // Use the narrow station-label gutter, outside the detail
                    // controls, so the outer LazyColumn owns this gesture.
                    start = Offset(width * 0.06f, height * if (moveContentUp) 0.86f else 0.14f),
                    end = Offset(width * 0.06f, height * if (moveContentUp) 0.14f else 0.86f),
                    durationMillis = 420,
                )
            }
            compose.waitUntil(5_000) {
                val after = board.fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value()
                if (moveContentUp) after > before else after < before
            }
            val after = board.fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value()
            assertTrue(
                "$diagnostic must move the intended station-board owner in the required direction: " +
                    "up=$moveContentUp before=$before after=$after",
                if (moveContentUp) after > before else after < before,
            )
        }
        val geometry = compactTargetGeometry(target, scope)
        if (!geometry.targetInsideVisible) {
            val range = board.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            captureCompactRevealFailure(diagnostic, geometry, range)
            error("$diagnostic remained clipped after bounded owner movement: $geometry range=$range")
        }
    }

    private fun compactTargetGeometry(
        target: SemanticsNodeInteraction,
        scope: CompactViewportScope,
    ): CompactTargetGeometry {
        val root = compose.onNodeWithTag("gaming-glass-fixture-root", useUnmergedTree = true)
        val rootPx = root.fetchSemanticsNode().boundsInRoot
        val rootDp = root.getUnclippedBoundsInRoot()
        val density = rootPx.width / (rootDp.right - rootDp.left).value
        val targetDp = target.getUnclippedBoundsInRoot()
        val targetPx = Rect(
            targetDp.left.value * density,
            targetDp.top.value * density,
            targetDp.right.value * density,
            targetDp.bottom.value * density,
        )
        val paneUnclippedPx = if (scope == CompactViewportScope.Row) {
            Rect(0f, 0f, 0f, 0f)
        } else {
            val paneDp = compose.onNodeWithTag("gaming-control-pane").getUnclippedBoundsInRoot()
            Rect(
                paneDp.left.value * density,
                paneDp.top.value * density,
                paneDp.right.value * density,
                paneDp.bottom.value * density,
            )
        }
        val viewports = buildList {
            add(bounds("gaming-glass-fixture-root"))
            add(bounds("gaming-station-board"))
            if (scope != CompactViewportScope.Row) {
                add(bounds("gaming-inline-expansion-ps5-1"))
                add(bounds("gaming-control-pane"))
            }
            if (scope == CompactViewportScope.Detail) add(bounds("gaming-detail-viewport"))
        }
        val visible = viewports.reduce(::intersection)
        return CompactTargetGeometry(
            target = targetPx,
            visible = visible,
            paneUnclipped = paneUnclippedPx,
            targetInsideVisible = targetPx.width > 0f && targetPx.height > 0f &&
                targetPx.left >= visible.left - 1f && targetPx.top >= visible.top - 1f &&
                targetPx.right <= visible.right + 1f && targetPx.bottom <= visible.bottom + 1f,
        )
    }

    private fun captureCompactRevealFailure(
        diagnostic: String,
        geometry: CompactTargetGeometry,
        range: androidx.compose.ui.semantics.ScrollAxisRange,
    ) {
        val safeName = diagnostic.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        captureGamingDiagnostic(
            "gaming-r7-9-$safeName-reveal-failure",
            mapOf(
                "target_unclipped" to geometry.target,
                "visible_viewport" to geometry.visible,
                "pane_unclipped" to geometry.paneUnclipped,
                "outer_scroll_range" to Rect(range.value(), 0f, range.maxValue(), 1f),
            ),
        )
    }

    private data class WideFocalGeometry(
        val artwork: Rect,
        val ring: Rect,
        val identity: Rect,
        val primaryFooter: Rect,
        val stickyHeader: Rect,
        val targets: Rect,
        val visible: Rect,
        val targetsInsideVisible: Boolean,
    )

    private data class CompactTargetGeometry(
        val target: Rect,
        val visible: Rect,
        val paneUnclipped: Rect,
        val targetInsideVisible: Boolean,
    )

    private enum class CompactViewportScope { Detail, Ring, Row }

    private fun assertVisibleSquareRingInsideGamingViewports() {
        val ring = compose.onNodeWithTag("gaming-control-ring")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val visibleBounds = listOf(
            bounds("gaming-glass-fixture-root"),
            bounds("gaming-station-board"),
            bounds("gaming-inline-expansion-ps5-1"),
            bounds("gaming-control-pane"),
        ).reduce(::intersection)
        assertTrue("compact ring must be a non-empty square: $ring", ring.width > 0f && abs(ring.width - ring.height) <= 1f)
        assertNonEmptyAndContained("compact ring", ring, visibleBounds)
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

    private fun intersection(left: Rect, right: Rect): Rect = Rect(
        left = maxOf(left.left, right.left),
        top = maxOf(left.top, right.top),
        right = minOf(left.right, right.right),
        bottom = minOf(left.bottom, right.bottom),
    )

    private fun captureGamingDiagnostic(name: String, bounds: Map<String, Rect>) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("gaming-glass-fixture-root").captureToImage().asAndroidBitmap()
        try {
            val nonce = InstrumentationRegistry.getArguments().getString(GamingGlassRunArgument)
                ?: error("Missing $GamingGlassRunArgument")
            require(nonce.matches(Regex("[0-9a-f]{32}")))
            val profile = RequestedGamingGlassDisplay?.let { "display-$it-hd" } ?: "default-display"
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                .resolve("phase-g2-gaming-fidelity-diagnostics-v1")
                .resolve(profile)
                .resolve(nonce)
                .apply { check(isDirectory || mkdirs()) }
            val png = directory.resolve("$name.png")
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val boundsJson = bounds.entries.joinToString(",\n") { (key, value) ->
                "    \"$key\": {\"left\": ${value.left}, \"top\": ${value.top}, " +
                    "\"right\": ${value.right}, \"bottom\": ${value.bottom}}"
            }
            val json = directory.resolve("$name.json")
            json.writeText(
                """
                {
                  "schema": "dcompany.gaming.glass.diagnostic.v1",
                  "capture_run_id": ${argument(GamingGlassRunArgument)},
                  "source_inventory_sha256": ${argument(GamingGlassSourceArgument)},
                  "target_apk_sha256": ${argument(GamingGlassTargetArgument)},
                  "test_apk_sha256": ${argument(GamingGlassTestArgument)},
                  "process_pid": ${Process.myPid()},
                  "png_file": "${png.name}",
                  "png_bytes": ${png.length()},
                  "png_sha256": "${sha256(png)}",
                  "bounds": {
                $boundsJson
                  }
                }
                """.trimIndent() + "\n",
            )
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("dcompany.gaming.glass.diagnostic", json.absolutePath)
                    putString("dcompany.gaming.glass.diagnostic.png", png.absolutePath)
                },
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertLongFeedbackTextComplete() {
        val interaction = compose.onNodeWithText(
            GamingPreciseAlarmLongFeedback,
            useUnmergedTree = true,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val result = layouts.single()
        assertEquals(GamingPreciseAlarmLongFeedback, result.layoutInput.text.text)
        assertFalse("long feedback must not overflow height", result.didOverflowHeight)
        assertEquals(
            "long feedback must retain every visible character",
            GamingPreciseAlarmLongFeedback.length,
            result.getLineEnd(result.lineCount - 1, visibleEnd = true),
        )
        repeat(result.lineCount) { line ->
            assertFalse("long feedback line $line must not ellipsize", result.isLineEllipsized(line))
        }
    }

    private fun applicablePermissionActions(): List<SemanticsNodeInteraction> {
        val notificationLabels = listOf(
            "Allow notifications",
            "Try notifications again",
            "Notification settings",
        )
        val notification = notificationLabels.mapNotNull { label ->
            val nodes = compose.onAllNodesWithText(label).fetchSemanticsNodes()
            when (nodes.size) {
                0 -> null
                1 -> compose.onNodeWithText(label)
                else -> error("permission notice must expose at most one $label action")
            }
        }
        assertEquals("permission notice must expose exactly one notification recovery route", 1, notification.size)
        compose.onAllNodesWithText("Precise alarms").assertCountEquals(1)
        return notification + compose.onNodeWithText("Precise alarms")
    }

    private fun assertPermissionBannerActions() {
        val bannerBounds = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-alarm-permission-banner").assertIsDisplayed(),
        )
        val notificationLabels = listOf(
            "Allow notifications",
            "Try notifications again",
            "Notification settings",
        )
        val notificationActionCount = notificationLabels.sumOf { label ->
            val matches = compose.onAllNodesWithText(label).fetchSemanticsNodes()
            if (matches.isNotEmpty()) {
                assertEquals("degraded notice must expose one $label action", 1, matches.size)
                val action = compose.onNodeWithText(label)
                    .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                assertNonEmptyAndContained(
                    "degraded notice $label action",
                    unclippedBoundsPx(action),
                    bannerBounds,
                )
            }
            matches.size
        }
        assertEquals(
            "degraded notice must expose exactly one notification recovery action",
            1,
            notificationActionCount,
        )
        val preciseAlarms = compose.onNodeWithText("Precise alarms")
            .assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertNonEmptyAndContained(
            "degraded notice Precise alarms action",
            unclippedBoundsPx(preciseAlarms),
            bannerBounds,
        )
        compose.onAllNodesWithText("Precise alarms").assertCountEquals(1)
    }

    private fun assertNonEmptyAndContained(
        name: String,
        inner: androidx.compose.ui.geometry.Rect,
        outer: androidx.compose.ui.geometry.Rect,
    ) {
        assertTrue("$name must have non-empty bounds: $inner", inner.width > 0f && inner.height > 0f)
        assertTrue(
            "$name $inner must be fully contained in $outer",
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
                inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f,
        )
    }

    private fun awaitWorkspaceGlass() {
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1
        }
    }

    private fun stationMaterialPixel(tag: String): Int {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return try {
            bitmap.getPixel((bitmap.width * 0.70f).roundToInt().coerceIn(0, bitmap.width - 1), 3.coerceAtMost(bitmap.height - 1))
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertUniformWorkspaceScene(fixture: Fixture, expected: Int) {
        compose.runOnIdle {
            val samples = requireNotNull(fixture.observedScene.get()).pixels
            assertEquals("the observed scene must expose every separated sample", 6, samples.size)
            val distances = samples.map { colorDistance(expected, it) }
            val encodedSamples = samples.map { "#%08x".format(it) }
            assertTrue(
                "the current clear scene must be uniform across separated regions: " +
                    "samples=$encodedSamples distances=$distances",
                distances.all { it <= 12 },
            )
        }
    }

    private fun centerPixel(tag: String): Int {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return try {
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertHeadlineReadabilityWashIsLocalAndContainsEveryTextNode() {
        val density = compose.activity.resources.displayMetrics.density
        val header = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-photo-header", useUnmergedTree = true).assertIsDisplayed(),
        )
        val wash = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-headline-readability-wash", useUnmergedTree = true)
                .assertIsDisplayed(),
        )
        val textRegion = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-headline-text-region", useUnmergedTree = true)
                .assertIsDisplayed(),
        )
        assertNonEmptyAndContained("local headline wash", wash, header)
        assertNonEmptyAndContained("headline text region", textRegion, wash)
        assertTrue(
            "headline wash must stay local rather than recreate a full-width rectangle: " +
                "wash=$wash header=$header",
            wash.width <= 580f * density + 1f,
        )
        assertTrue(
            "headline wash must begin after the existing 10dp content inset: wash=$wash text=$textRegion",
            textRegion.left - wash.left >= 10f * density - 1f,
        )
        assertTrue(
            "headline wash must reserve a soft fade after every glyph region: wash=$wash text=$textRegion",
            wash.right - textRegion.right >= 39f * density,
        )
        val refresh = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-headline-refresh", useUnmergedTree = true)
                .assertIsDisplayed(),
        )
        assertTrue(
            "headline wash must finish before the Refresh owner: wash=$wash refresh=$refresh",
            wash.right + 8f * density <= refresh.left + 1f,
        )
        listOf(
            "gaming-headline-title",
            "gaming-headline-subtitle",
            "gaming-headline-summary",
        ).forEach { tag ->
            assertNonEmptyAndContained(
                "$tag inside solid headline text region",
                unclippedBoundsPx(
                    compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed(),
                ),
                textRegion,
            )
        }
    }

    private fun assertHeadlineWashFeathersAgainstUniformScene() {
        val density = compose.activity.resources.displayMetrics.density
        val wash = unclippedBoundsPx(
            compose.onNodeWithTag("gaming-headline-readability-wash", useUnmergedTree = true)
                .assertIsDisplayed(),
        )
        val rootBitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val sampleTop = (wash.top + wash.height * 0.25f).roundToInt()
                .coerceIn(0, rootBitmap.height - 1)
            val sampleBottom = (wash.top + wash.height * 0.75f).roundToInt()
                .coerceIn(sampleTop + 1, rootBitmap.height)
            fun columnMedian(x: Int): Int {
                val sampleX = x.coerceIn(0, rootBitmap.width - 1)
                val red = ArrayList<Int>(sampleBottom - sampleTop)
                val green = ArrayList<Int>(sampleBottom - sampleTop)
                val blue = ArrayList<Int>(sampleBottom - sampleTop)
                for (y in sampleTop until sampleBottom) {
                    val value = rootBitmap.getPixel(sampleX, y)
                    red += Color.red(value)
                    green += Color.green(value)
                    blue += Color.blue(value)
                }
                red.sort()
                green.sort()
                blue.sort()
                val middle = red.size / 2
                return Color.rgb(red[middle], green[middle], blue[middle])
            }
            fun pixel(x: Float): Int = columnMedian(x.roundToInt())
            fun color(value: Int): String = "#%08x".format(value)
            val outsideLeft = pixel(wash.left - 2f * density)
            val firstInsideX = kotlin.math.ceil(wash.left).toInt()
            val firstInside = columnMedian(firstInsideX)
            val leftMidFeather = pixel(wash.left + 5f * density)
            val solidInterior = pixel(wash.left + 14f * density)
            val rightField = pixel(wash.left + wash.width * 0.82f)
            val rightMidFeather = pixel(wash.left + wash.width * 0.91f)
            val transparentRight = pixel(wash.right - 1f)
            val outsideRight = pixel(wash.right + 2f * density)
            val firstDistance = colorDistance(outsideLeft, firstInside)
            val leftMidDistance = colorDistance(outsideLeft, leftMidFeather)
            val leftInteriorDistance = colorDistance(outsideLeft, solidInterior)
            val rightEdgeDistance = colorDistance(outsideRight, transparentRight)
            val rightMidDistance = colorDistance(outsideRight, rightMidFeather)
            val rightFieldDistance = colorDistance(outsideRight, rightField)
            assertTrue(
                "left feather must darken monotonically from the first raster pixel through 5dp " +
                    "to the readability field, while the edge remains at most one quarter of the field: " +
                    "outside=${color(outsideLeft)} first=${color(firstInside)} " +
                    "mid=${color(leftMidFeather)} interior=${color(solidInterior)} " +
                    "x=[$firstInsideX,${wash.left + 5f * density},${wash.left + 14f * density}] " +
                    "y=[$sampleTop,$sampleBottom) distances=" +
                    "[$firstDistance,$leftMidDistance,$leftInteriorDistance]",
                firstDistance <= leftMidDistance && leftMidDistance < leftInteriorDistance &&
                    firstDistance * 4 <= leftInteriorDistance,
            )
            assertTrue(
                "right tail must visibly fade from the readability field toward the scene: " +
                    "interior=${color(solidInterior)} field=${color(rightField)} " +
                    "mid=${color(rightMidFeather)} edge=${color(transparentRight)} " +
                    "outside=${color(outsideRight)} distances=[$rightFieldDistance," +
                    "$rightMidDistance,$rightEdgeDistance] y=[$sampleTop,$sampleBottom)",
                rightEdgeDistance <= rightMidDistance && rightMidDistance < rightFieldDistance &&
                    rightEdgeDistance * 4 <= rightFieldDistance &&
                    colorDistance(solidInterior, rightField) >= 18,
            )
        } finally {
            rootBitmap.recycle()
        }
    }

    private fun assertRenderedHeadlineContrast(tag: String, threshold: Double) {
        val bitmap = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        try {
            assertTrue("$tag must expose a rendered text sample", bitmap.width >= 4 && bitmap.height >= 4)
            val luminances = ArrayList<Double>(bitmap.width * bitmap.height)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                luminances += relativeLuminance(bitmap.getPixel(x, y))
            }
            luminances.sort()
            val background = luminances[luminances.size / 2]
            val core = luminances.takeLast(maxOf(1, luminances.size / 40))
            val passingCorePixels = core.count { contrastRatio(it, background) >= threshold }
            assertTrue(
                "$tag must retain multiple rendered glyph-core pixels at $threshold:1; " +
                    "background=$background best=${core.maxOf { contrastRatio(it, background) }} " +
                    "passing=$passingCorePixels",
                passingCorePixels >= minOf(8, core.size),
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
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
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
                  "capture_run_id": ${argument(GamingGlassRunArgument)},
                  "test_class": "cloud.dcompany.erp.ui.screens.gaming.GamingGlassUiTest",
                  "source_inventory_sha256": ${argument(GamingGlassSourceArgument)},
                  "target_apk_sha256": ${argument(GamingGlassTargetArgument)},
                  "test_apk_sha256": ${argument(GamingGlassTestArgument)},
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
                  "expected_alarm_ready": ${InstrumentationRegistry.getArguments()
                      .getString(GamingAlarmReadyArgument) == "true"},
                  "observed_alarm_ready": ${compose.onAllNodesWithTag(
                      "gaming-alarm-permission-banner",
                  ).fetchSemanticsNodes().isEmpty()},
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
        val nonce = InstrumentationRegistry.getArguments().getString(GamingGlassRunArgument)
            ?: error("Missing $GamingGlassRunArgument")
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        val profile = RequestedGamingGlassDisplay?.let { "display-$it-hd" } ?: "default-display"
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-g2-gaming-fidelity-screenshots-v1").resolve(profile).resolve(nonce)
            .apply { check(isDirectory || mkdirs()) }
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name) ?: error("Missing $name")
        val length = if (name == GamingGlassRunArgument) 32 else 64
        require(value.matches(Regex("[0-9a-f]{$length}")))
        return "\"$value\""
    }

    private fun controlledWorkspaceImage(name: String, generation: Long, bright: Boolean): WorkspaceBackgroundImage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir.resolve("gaming-glass-fixtures").apply { mkdirs() }
        val file = directory.resolve(name)
        val bitmap = Bitmap.createBitmap(2560, 1080, Bitmap.Config.ARGB_8888)
        val colors = if (bright) {
            intArrayOf(Color.rgb(255, 247, 210), Color.rgb(246, 184, 34), Color.rgb(230, 248, 255))
        } else {
            intArrayOf(Color.rgb(12, 40, 128), Color.rgb(40, 18, 92), Color.rgb(9, 90, 108))
        }
        for (y in 0 until bitmap.height) {
            val row = IntArray(bitmap.width) { x -> colors[(x / 160 + y / 120) % colors.size] }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) }
        bitmap.recycle()
        return WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = 2560,
            height = 1080,
            generation = generation,
            contentId = sha256(file),
            privatePath = file.absolutePath,
        )
    }

    private fun controlledUniformWorkspaceImage(
        name: String,
        generation: Long,
        color: Int,
    ): WorkspaceBackgroundImage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir.resolve("gaming-glass-fixtures").apply { mkdirs() }
        val file = directory.resolve(name)
        val bitmap = Bitmap.createBitmap(2560, 1080, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        bitmap.recycle()
        return WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = 2560,
            height = 1080,
            generation = generation,
            contentId = sha256(file),
            privatePath = file.absolutePath,
        )
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs(Color.red(first) - Color.red(second)) +
            abs(Color.green(first) - Color.green(second)) +
            abs(Color.blue(first) - Color.blue(second))

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun baseState(sessions: List<GameSession> = emptyList()) = GamingUiState(
        catalogueLoaded = true,
        stations = stations,
        packages = packages,
        sessions = sessions,
        everSynced = true,
        refreshing = false,
        activeShiftId = "fixture-shift",
        activeShiftServerConfirmed = true,
        online = true,
    )

    private fun activeSession(status: String) = GameSession(
        id = "session-ps5-1",
        stationId = "ps5-1",
        shiftId = "fixture-shift",
        status = status,
        startAt = "2026-10-24T10:00:00Z",
        timerMinutes = 30,
        timerEndsAt = "2026-10-24T10:30:00Z",
        ratePerHourMinor = 12_000,
        packageId = "single-30",
        billingMode = "package",
        packagePriceMinorSnapshot = 8_000,
        packageDurationMinutesSnapshot = 30,
        packageVariantSnapshot = "single",
        packageStationTypeSnapshot = "ps5",
        packagePricingTierSnapshot = "standard",
        amountMinor = 8_000,
    )

    companion object {
        private val stations = listOf(
            Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12_000),
            Station("ps5-2", "PS5-2", "PS5 Station 2", "ps5", 12_000),
            Station("racing-1", "RACE-1", "Racing Simulator 1", "racing", 15_000),
        )
        private val packages = listOf(
            GamingPackage(
                id = "single-30", code = "single-30", stationType = "ps5", variant = "single",
                kind = "base", name = "Single 30", durationMinutes = 30, priceMinor = 8_000,
            ),
            GamingPackage(
                id = "single-60", code = "single-60", stationType = "ps5", variant = "single",
                kind = "base", name = "Single 60", durationMinutes = 60, priceMinor = 12_000,
            ),
            GamingPackage(
                id = "racing-30", code = "racing-30", stationType = "racing", variant = "simdrive",
                kind = "base", name = "Racing 30", durationMinutes = 30, priceMinor = 10_000,
            ),
        )

        @JvmStatic
        @AfterClass
        fun cleanupFixtures() {
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                .resolve("gaming-glass-fixtures").deleteRecursively()
        }
    }
}
