package cloud.dcompany.erp.ui

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.View
import android.view.WindowInsets
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.screens.home.LocalExecutiveHomeShellLayout
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundImage
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.WorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val TestDisplayIdArgument = "dcompany.testDisplayId"
private const val SourceInventoryArgument = "dcompany.sourceInventorySha256"
private const val TargetApkArgument = "dcompany.targetApkSha256"
private const val TestApkArgument = "dcompany.testApkSha256"

private data class FixtureDrawSample(
    val destination: Destination,
    val requestWaiting: Boolean,
    val remoteActive: Boolean,
    val bounds: ComposeRect,
)

private class FixtureDrawProbe {
    private var bounds: ComposeRect? = null
    private val samples = mutableListOf<FixtureDrawSample>()

    fun modifier(destination: Destination, requestWaiting: Boolean, remoteActive: Boolean): Modifier =
        Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }
            .drawWithContent {
                drawContent()
                bounds?.let { resolved ->
                    samples += FixtureDrawSample(destination, requestWaiting, remoteActive, resolved)
                }
            }

    fun clear() = samples.clear()

    fun first(destination: Destination, requestWaiting: Boolean, remoteActive: Boolean): FixtureDrawSample =
        checkNotNull(samples.firstOrNull { sample ->
            sample.destination == destination && sample.requestWaiting == requestWaiting &&
                sample.remoteActive == remoteActive
        }) {
            "No actual draw was recorded for destination=$destination request=$requestWaiting active=$remoteActive; " +
                "samples=$samples"
        }
}

private enum class DiagnosticRouteEventKind {
    REQUEST_ACCEPTED,
    REQUEST_DENIED,
    EXTERNAL_REPLACEMENT,
}

private data class DiagnosticRouteEvent(
    val sequence: Int,
    val uptimeMillis: Long,
    val kind: DiagnosticRouteEventKind,
    val requested: Destination,
    val resultingDestination: Destination,
    val resultingRevision: Long,
)

private class DiagnosticRouteOwner(
    initial: Destination,
    private val requestsAccepted: Boolean,
    private val allowed: () -> List<Destination>,
    private val onRequest: (Destination) -> Unit,
    private val onAccepted: (Destination) -> Unit = {},
) : ExecutiveRailRouteAuthority {
    private var snapshot = WorkspaceRouteSnapshot(initial, 0L)
    private val history = mutableListOf<DiagnosticRouteEvent>()

    @Synchronized
    override fun request(
        target: Destination,
        expected: WorkspaceRouteSnapshot,
        beforeCommit: () -> Unit,
        afterCommit: () -> Unit,
    ): WorkspaceRouteRequestResult {
        onRequest(target)
        val rejection = when {
            snapshot != expected -> WorkspaceRouteRejection.STALE_REVISION
            target !in allowed() || !requestsAccepted -> WorkspaceRouteRejection.OUTSIDE_CURRENT_SCOPE
            target == snapshot.destination -> WorkspaceRouteRejection.SAME_DESTINATION
            else -> null
        }
        if (rejection != null) {
            history += DiagnosticRouteEvent(
                sequence = history.size,
                uptimeMillis = SystemClock.uptimeMillis(),
                kind = DiagnosticRouteEventKind.REQUEST_DENIED,
                requested = target,
                resultingDestination = snapshot.destination,
                resultingRevision = snapshot.revision,
            )
            return WorkspaceRouteRequestResult.Rejected(rejection, snapshot)
        }
        beforeCommit()
        snapshot = WorkspaceRouteSnapshot(target, snapshot.revision + 1L)
        history += DiagnosticRouteEvent(
            sequence = history.size,
            uptimeMillis = SystemClock.uptimeMillis(),
            kind = DiagnosticRouteEventKind.REQUEST_ACCEPTED,
            requested = target,
            resultingDestination = snapshot.destination,
            resultingRevision = snapshot.revision,
        )
        onAccepted(target)
        afterCommit()
        return WorkspaceRouteRequestResult.Accepted(snapshot)
    }

    @Synchronized
    fun replaceExternally(target: Destination): WorkspaceRouteSnapshot {
        snapshot = WorkspaceRouteSnapshot(target, snapshot.revision + 1L)
        history += DiagnosticRouteEvent(
            sequence = history.size,
            uptimeMillis = SystemClock.uptimeMillis(),
            kind = DiagnosticRouteEventKind.EXTERNAL_REPLACEMENT,
            requested = target,
            resultingDestination = snapshot.destination,
            resultingRevision = snapshot.revision,
        )
        return snapshot
    }

    @Synchronized override fun snapshot(): WorkspaceRouteSnapshot = snapshot
    @Synchronized fun current(): WorkspaceRouteSnapshot = snapshot
    @Synchronized fun events(): List<DiagnosticRouteEvent> = history.toList()
}

private class BoundedRailTrace(private val maximumEvents: Int = 512) {
    private val events = mutableListOf<ExecutiveRailScrubTraceEvent>()
    private var dropped = 0

    @Synchronized
    fun record(event: ExecutiveRailScrubTraceEvent) {
        if (events.size < maximumEvents) events += event else dropped += 1
    }

    @Synchronized fun snapshot(): Pair<List<ExecutiveRailScrubTraceEvent>, Int> =
        events.toList() to dropped

    @Synchronized
    fun containsStop(): Boolean = events.any { it.kind == ExecutiveRailScrubTraceKind.STOPPED }

    @Synchronized
    fun stopCount(): Int = events.count { it.kind == ExecutiveRailScrubTraceKind.STOPPED }

    @Synchronized
    fun containsStopAfter(
        uptimeMillis: Long,
        reason: ExecutiveRailScrubStopReason,
    ): Boolean = events.any {
        it.kind == ExecutiveRailScrubTraceKind.STOPPED &&
            it.uptimeMillis >= uptimeMillis &&
            it.stopReason == reason
    }

    @Synchronized
    fun containsRejectedRequest(reason: WorkspaceRouteRejection): Boolean = events.any {
        it.kind == ExecutiveRailScrubTraceKind.REQUEST_REJECTED && it.rejection == reason
    }

    @Synchronized
    fun containsObservedOwnerAfter(
        uptimeMillis: Long,
        expected: WorkspaceRouteSnapshot,
    ): Boolean = events.any {
        it.kind == ExecutiveRailScrubTraceKind.OWNER_SNAPSHOT_OBSERVED &&
            it.uptimeMillis >= uptimeMillis &&
            it.observedDestination == expected.destination &&
            it.observedRevision == expected.revision
    }

    @Synchronized
    fun containsCommit(destination: Destination): Boolean = events.any {
        it.kind == ExecutiveRailScrubTraceKind.COMMIT_REQUESTED &&
            it.proposedTarget == destination
    }
}

private val RequestedTestDisplayId: Int? by lazy {
    val raw = InstrumentationRegistry.getArguments().getString(TestDisplayIdArgument)
    if (raw == null) {
        null
    } else {
        val requested = raw.toIntOrNull()
            ?: error("Invalid -e $TestDisplayIdArgument value: $raw")
        require(requested in setOf(0, 2, 5, 6)) {
            "Only verified HD displays 0, 2, 5 and 6 are supported; requested display was $requested"
        }
        requested
    }
}

private fun requiredSha256Argument(name: String): String {
    val value = InstrumentationRegistry.getArguments().getString(name)
        ?: error("Missing instrumentation provenance argument $name")
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid instrumentation provenance argument $name"
    }
    return value
}

private val SourceInventorySha256 by lazy { requiredSha256Argument(SourceInventoryArgument) }
private val TargetApkSha256 by lazy { requiredSha256Argument(TargetApkArgument) }
private val TestApkSha256 by lazy { requiredSha256Argument(TestApkArgument) }

private fun <A : ComponentActivity> ActivityScenarioRule<A>.currentActivity(): A {
    var current: A? = null
    scenario.onActivity { current = it }
    return checkNotNull(current) { "ActivityScenario has no resumed activity" }
}

private fun createWorkspaceComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val activityRule = if (RequestedTestDisplayId == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(RequestedTestDisplayId!!)
            .toBundle()
        ActivityScenarioRule(ComponentActivity::class.java, options)
    }
    return AndroidComposeTestRule(activityRule) { it.currentActivity() }
}

private data class HostDisplayProof(
    val requestedDisplayId: Int?,
    val observedDisplayId: Int,
    val modeWidthPx: Int,
    val modeHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val windowBounds: Rect,
    val decorWidthPx: Int,
    val decorHeightPx: Int,
    val source: String,
)

class WorkspaceScaffoldUiTest {
    @get:Rule
    val compose = createWorkspaceComposeRule()

    private fun assertRequestedDisplayHost(): HostDisplayProof {
        val proof = observeHostDisplay()
        if (RequestedTestDisplayId == null) {
            assertEquals("Default test host must remain on display 0", 0, proof.observedDisplayId)
        } else {
            assertEquals(
                "Explicit HD launch must land on its requested display",
                RequestedTestDisplayId,
                proof.observedDisplayId,
            )
            assertEquals("HD display mode width must be physical 2560px", 2_560, proof.modeWidthPx)
            assertEquals("HD display mode height must be physical 1600px", 1_600, proof.modeHeightPx)
            assertEquals("HD activity resources must use 320dpi", 320, proof.densityDpi)
        }
        assertTrue("Window width must be positive: ${proof.windowBounds}", proof.windowBounds.width() > 0)
        assertTrue("Window height must be positive: ${proof.windowBounds}", proof.windowBounds.height() > 0)
        assertTrue(
            "Window must fit the observed display mode: window=${proof.windowBounds} " +
                "mode=${proof.modeWidthPx}x${proof.modeHeightPx}",
            proof.windowBounds.width() <= proof.modeWidthPx &&
                proof.windowBounds.height() <= proof.modeHeightPx,
        )
        assertTrue(
            "Decor view must be attached and measured: ${proof.decorWidthPx}x${proof.decorHeightPx}",
            proof.decorWidthPx > 0 && proof.decorHeightPx > 0,
        )
        return proof
    }

    private fun observeHostDisplay(): HostDisplayProof {
        val activity = compose.activity
        var proof: HostDisplayProof? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val display = checkNotNull(activity.display) { "Test host activity has no display" }
            val mode = display.mode
            val metrics = activity.resources.displayMetrics
            val bounds = Rect(activity.windowManager.currentWindowMetrics.bounds)
            proof = HostDisplayProof(
                requestedDisplayId = RequestedTestDisplayId,
                observedDisplayId = display.displayId,
                modeWidthPx = mode.physicalWidth,
                modeHeightPx = mode.physicalHeight,
                densityDpi = metrics.densityDpi,
                density = metrics.density,
                windowBounds = bounds,
                decorWidthPx = activity.window.decorView.width,
                decorHeightPx = activity.window.decorView.height,
                source = if (RequestedTestDisplayId == null) {
                    "default-display-activity-scenario"
                } else {
                    "explicit-activity-options-launch-display"
                },
            )
        }
        return checkNotNull(proof) { "Could not inspect test host display" }
    }

    @Test
    fun landscapeRailPreservesPermissionsTargetsNavigationAndState() {
        assertRequestedDisplayHost()
        val viewportWidth = mutableStateOf(1_280.dp)
        val outbox = mutableStateOf(OutboxWorkStatus())
        val syncing = mutableStateOf(false)
        val requestWaiting = mutableStateOf(false)
        val remoteActive = mutableStateOf(false)
        val remotePrivacyProtected = mutableStateOf(true)
        val reviewRequests = mutableIntStateOf(0)
        val stopRequests = mutableIntStateOf(0)
        val drawProbe = FixtureDrawProbe()
        render(
            width = 1_280.dp,
            height = 800.dp,
            widthState = viewportWidth,
            outboxWorkStatusState = outbox,
            syncingState = syncing,
            remoteSupportRequestWaitingState = requestWaiting,
            remoteSupportActiveState = remoteActive,
            remoteSupportPrivacyProtectedState = remotePrivacyProtected,
            onReviewRemoteSupportRequest = { reviewRequests.intValue += 1 },
            onStopRemoteSupport = { stopRequests.intValue += 1 },
            drawProbe = drawProbe,
        )
        captureRoot(
            name = "workspace-landscape-1280x800-initial",
            testName = "landscapeRailPreservesPermissionsTargetsNavigationAndState",
            expectedWidthDp = 1_280,
            expectedHeightDp = 800,
            fontScale = 1.0f,
        )

        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("workspace-bottom-dock").assertDoesNotExist()
        compose.onNodeWithTag("outbox-work-status-slot").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-Dashboard")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-header")
        assertFullyInsideRoot("increment-Dashboard")
        assertFixtureCounterClearsHomeChrome()

        compose.runOnIdle {
            outbox.value = OutboxWorkStatus(protectedReviewUnknown = true)
        }
        assertPendingHeaderControls(widthRange = 86f..90f, expectsDateTime = true)
        compose.onNodeWithContentDescription(
            "Saved work status: protected recovery storage unknown; owner review required",
        ).assertIsDisplayed()

        compose.runOnIdle { viewportWidth.value = 1_104.dp }
        assertPendingHeaderControls(widthRange = 86f..90f, expectsDateTime = false)

        compose.runOnIdle {
            outbox.value = OutboxWorkStatus()
            syncing.value = true
        }
        compose.onNodeWithContentDescription("Saved work status: sync in progress").assertIsDisplayed()
        assertPendingHeaderControls(widthRange = 86f..90f, expectsDateTime = false)

        compose.runOnIdle {
            viewportWidth.value = 1_280.dp
            syncing.value = false
        }
        compose.onNodeWithTag("outbox-work-status-slot").assertDoesNotExist()
        compose.onNodeWithTag("workspace-date-time").assertIsDisplayed()
        assertTextDoesNotOverflow("workspace-date-label")
        assertTextDoesNotOverflow("workspace-time-label")

        exerciseSharedChromeContinuityAndBanners(
            requestWaiting = requestWaiting,
            remoteActive = remoteActive,
            remotePrivacyProtected = remotePrivacyProtected,
            reviewRequests = reviewRequests,
            stopRequests = stopRequests,
            drawProbe = drawProbe,
        )
        exercisePermissionFilteredNavigationAndState()
        captureRoot(
            name = "workspace-landscape-1280x800",
            testName = "landscapeRailPreservesPermissionsTargetsNavigationAndState",
            expectedWidthDp = 1_280,
            expectedHeightDp = 800,
            fontScale = 1.0f,
        )
    }

    @Test
    fun masterAspectFixtureKeepsFixedRailAndRightBodyAcrossRoutes() {
        assertRequestedDisplayHost()
        render(width = 1_280.dp, height = 563.dp)
        captureRoot(
            name = "workspace-master-aspect-1280x563-initial",
            testName = "masterAspectFixtureKeepsFixedRailAndRightBodyAcrossRoutes",
            expectedWidthDp = 1_280,
            expectedHeightDp = 563,
            fontScale = 1.0f,
        )

        val baseline = sharedChromeSnapshot()
        assertEquals("fixed rail width", 184.0, (baseline.rail.right - baseline.rail.left).value.toDouble(), 0.5)
        assertFullHeightRailAndRightBody(Destination.Dashboard)
        assertRailLogoBounds()

        compose.onNodeWithTag("workspace-rail-Gaming").performTouchInput { click() }
        assertChromeMatches(baseline, "master-aspect Gaming")
        assertFullHeightRailAndRightBody(Destination.Gaming)
        compose.onNodeWithTag("workspace-rail-Pos").performTouchInput { click() }
        assertChromeMatches(baseline, "master-aspect POS")
        assertFullHeightRailAndRightBody(Destination.Pos)

        captureRoot(
            name = "workspace-master-aspect-1280x563-pos",
            testName = "masterAspectFixtureKeepsFixedRailAndRightBodyAcrossRoutes",
            expectedWidthDp = 1_280,
            expectedHeightDp = 563,
            fontScale = 1.0f,
        )
    }

    @Test
    fun landscapeRailPhysicalSwipeAndAccessibleControlsReclaimWidth() {
        assertRequestedDisplayHost()
        render(width = 1_280.dp, height = 800.dp)
        compose.onNodeWithTag("workspace-rail-brand-company", useUnmergedTree = true)
            .assertTextEquals("D COMPANY")
        compose.onNodeWithTag("workspace-rail-brand-lounge", useUnmergedTree = true)
            .assertTextEquals("GAMING LOUNGE")
        assertSingleLineTextComplete("workspace-rail-brand-company", "D COMPANY")
        assertSingleLineTextComplete("workspace-rail-brand-lounge", "GAMING LOUNGE")
        compose.onNodeWithTag("increment-Dashboard").performClick()
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
        val routeBefore = compose.onNodeWithTag("workspace-route-content").getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density

        compose.onNodeWithTag("workspace-navigation-rail").performTouchInput {
            val start = Offset(width - (8f * density), height * 0.72f)
            down(start)
            advanceEventTime(32L)
            moveTo(Offset(start.x - (84f * density), start.y + (2f * density)))
            advanceEventTime(32L)
            up()
        }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("workspace-rail-show")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        assertRailRevealOccupiesHeaderLeadingSlot("increment-Dashboard")
        compose.waitUntil(5_000L) {
            val route = compose.onNodeWithTag("workspace-route-content").getUnclippedBoundsInRoot()
            routeBefore.left - route.left >= ExecutiveSidebarWidth - 1.dp
        }
        val routeHidden = compose.onNodeWithTag("workspace-route-content").getUnclippedBoundsInRoot()
        assertTrue(
            "Hiding the rail must reclaim its width: before=$routeBefore hidden=$routeHidden",
            routeBefore.left - routeHidden.left >= ExecutiveSidebarWidth - 1.dp &&
                routeHidden.right - routeHidden.left >=
                (routeBefore.right - routeBefore.left) + ExecutiveSidebarWidth - 1.dp,
        )
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-show").performTouchInput { click() }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-navigation-rail").performTouchInput {
            val start = Offset(width * 0.55f, height * 0.74f)
            down(start)
            advanceEventTime(24L)
            moveTo(Offset(start.x + (2f * density), start.y - (92f * density)))
            advanceEventTime(24L)
            up()
        }
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-hide")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("workspace-wide-body").performTouchInput {
            // The centered 160dp left-edge band is reserved from Android Back while hidden.
            val start = Offset(8f * density, height * 0.5f)
            down(start)
            advanceEventTime(32L)
            moveTo(Offset(start.x + (84f * density), start.y + (2f * density)))
            advanceEventTime(32L)
            up()
        }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
    }

    @Test
    fun physicalLeftEdgeRevealPreservesStateAndNormalBackKeyDispatch() {
        val host = assertRequestedDisplayHost()
        assertEquals("System gesture arbitration must be checked on physical display 0", 0, host.observedDisplayId)
        assertEquals(2_560, host.modeWidthPx)
        assertEquals(1_600, host.modeHeightPx)
        assertEquals(320, host.densityDpi)
        render(width = 1_280.dp, height = 800.dp, touchExplorationOverride = false)
        compose.onNodeWithTag("increment-Dashboard").performClick()
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = compose.activity
        val backDispatches = AtomicInteger(0)
        val backObserver = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { backDispatches.incrementAndGet() }
        }
        // Observe a real Android Back-key dispatch without destroying this test activity. This is a
        // test-only observer; the product must not install a global Back interception policy.
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.addCallback(activity, backObserver) }
        try {
            // Calibrate the Back-key observer separately from physical edge reveal. A fresh
            // AVD does not establish SystemUI's outside-band Back-gesture arbitration.
            assertTrue("Android shell Back calibration reported an error",
                boundedAndroidShellOutput("input keyevent 4", 4_096).isBlank())
            try {
                compose.waitUntil(5_000L) { backDispatches.get() == 1 }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "The test Back observer did not receive KEYCODE_BACK; gesture results are not admissible. " +
                        physicalEdgeFailureDiagnostic(activity, activity.window.decorView, backDispatches.get()),
                    failure,
                )
            }
            backDispatches.set(0)
            compose.onNodeWithTag("workspace-rail-hide").performClick()
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
            }
            val gestureNode = compose.onNodeWithTag("workspace-wide-gesture-host").fetchSemanticsNode()
            val rootView = (gestureNode.root as ViewRootForTest).view
            val rootLocation = IntArray(2)
            lateinit var gestureScreenBounds: Rect
            instrumentation.runOnMainSync {
                rootView.getLocationOnScreen(rootLocation)
                val bounds = gestureNode.boundsInRoot
                gestureScreenBounds = Rect(
                    (rootLocation[0] + bounds.left).roundToInt(),
                    (rootLocation[1] + bounds.top).roundToInt(),
                    (rootLocation[0] + bounds.right).roundToInt(),
                    (rootLocation[1] + bounds.bottom).roundToInt(),
                )
                assertEquals("Reveal host must reach the physical left edge", 0, gestureScreenBounds.left)
                assertEquals("Reveal host must span the physical display width", host.modeWidthPx, gestureScreenBounds.right)
                assertTrue(
                    "Android must report an edge-gesture inset for this geometry check",
                    checkNotNull(rootView.rootWindowInsets).getInsets(WindowInsets.Type.systemGestures()).left > 0,
                )
            }
            val startX = 5f // Physical display pixels, deliberately outside the decorative16px frame.
            val centerY = gestureScreenBounds.exactCenterY()
            val hint = compose.onNodeWithContentDescription("Swipe right to show sidebar")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("The grip must identify the center of the admitted gesture band",
                kotlin.math.abs(rootLocation[1] + hint.center.y - centerY) <= host.density)
            assertTrue("The subtle grip must stay within the central48dp", hint.height <= 48f * host.density)
            val outsideBandY = centerY - 120f * host.density
            fun registeredExclusionContains(y: Float): Boolean {
                var contains = false
                instrumentation.runOnMainSync {
                    contains = rootView.systemGestureExclusionRects.any { local ->
                        Rect(local).apply { offset(rootLocation[0], rootLocation[1]) }
                            .contains(startX.roundToInt(), y.roundToInt())
                    }
                }
                return contains
            }
            compose.waitUntil(5_000L) { registeredExclusionContains(centerY) }
            assertFalse("The requested exclusion must not cover the outside-band point", registeredExclusionContains(outsideBandY))
            captureRoot(
                name = "workspace-physical-edge-hidden",
                testName = "physicalLeftEdgeRevealPreservesStateAndNormalBackKeyDispatch",
                expectedWidthDp = 1_280, expectedHeightDp = 800, fontScale = 1.0f,
            )

            injectPhysicalDisplaySwipe(startX, centerY - 24f, startX, centerY + 80f)
            compose.waitForIdle()
            compose.onNodeWithTag("workspace-rail-show").assertIsDisplayed()
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().let {
                assertTrue("A vertical edge swipe must not reveal the rail", it.isEmpty())
            }
            assertEquals("Vertical movement inside the exclusion must not dispatch Back", 0, backDispatches.get())

            injectPhysicalDisplaySwipe(startX, centerY, 330f, centerY)
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
            compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
            assertEquals("Physical edge reveal must not dispatch Android Back", 0, backDispatches.get())
            assertFalse(activity.isFinishing || activity.isDestroyed)
            captureRoot(
                name = "workspace-physical-edge-revealed",
                testName = "physicalLeftEdgeRevealPreservesStateAndNormalBackKeyDispatch",
                expectedWidthDp = 1_280, expectedHeightDp = 800, fontScale = 1.0f,
            )

            compose.onNodeWithTag("workspace-rail-hide").performClick()
            compose.waitUntil(5_000L) {
                registeredExclusionContains(centerY) &&
                    compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
            }
            assertFalse("The requested exclusion must remain bounded after another hide",
                registeredExclusionContains(outsideBandY))
            // Normal Back-key dispatch must still work with the rail hidden. The separate
            // retained-device acceptance covers actual outside-band OS Back gestures; this
            // fresh-AVD test intentionally does not claim that environment-dependent proof.
            assertTrue("Android shell Back control reported an error",
                boundedAndroidShellOutput("input keyevent 4", 4_096).isBlank())
            try {
                compose.waitUntil(5_000L) { backDispatches.get() == 1 }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "Normal KEYCODE_BACK dispatch failed while the rail was hidden. " +
                        physicalEdgeFailureDiagnostic(activity, rootView, backDispatches.get()),
                    failure,
                )
            }
            compose.onNodeWithTag("workspace-rail-show").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
            compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
        } finally {
            instrumentation.runOnMainSync { backObserver.remove() }
        }
    }

    private fun injectPhysicalDisplaySwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        check(listOf(startX, startY, endX, endY).all { it.isFinite() && it >= 0f })
        // Use the same Shell input dispatch path as the retained-device acceptance swipe.
        // Hand-built MotionEvents can differ in display attribution and injection flags.
        val command = "input touchscreen swipe ${startX.roundToInt()} ${startY.roundToInt()} " +
            "${endX.roundToInt()} ${endY.roundToInt()} 350"
        assertTrue("Android shell swipe reported an error", boundedAndroidShellOutput(command, 4_096).isBlank())
    }

    private fun boundedAndroidShellOutput(command: String, maximumBytes: Int): String {
        check(command.length <= 4_096 && maximumBytes in 1..192_000)
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        try {
            val fd = descriptor.fileDescriptor
            val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (SystemClock.elapsedRealtime() < deadline) {
                if (Os.poll(arrayOf(poll), 200) == 0) continue
                check(poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) == 0) {
                    "Android shell output pipe failed"
                }
                val count = Os.read(fd, buffer, 0, minOf(buffer.size, maximumBytes + 1 - output.size()))
                if (count == 0) return output.toByteArray().toString(Charsets.UTF_8)
                output.write(buffer, 0, count)
                check(output.size() <= maximumBytes) { "Android shell output exceeded byte limit" }
            }
            error("Android shell command exceeded ten-second deadline")
        } finally {
            descriptor.close()
        }
    }

    private fun physicalEdgeFailureDiagnostic(activity: ComponentActivity, rootView: View, backCount: Int): String {
        val result = org.json.JSONObject().put("backDispatches", backCount)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val origin = IntArray(2).also(rootView::getLocationOnScreen)
            result.put("activityFinishing", activity.isFinishing)
                .put("activityDestroyed", activity.isDestroyed)
                .put("windowHasFocus", rootView.hasWindowFocus())
                .put("displayId", rootView.display?.displayId)
                .put("rootScreenOrigin", "${origin[0]},${origin[1]}")
                .put("registeredLocalExclusions", rootView.systemGestureExclusionRects.toString())
        }
        val markers = listOf(
            "mCurrentFocus", "mFocusedApp", "mSystemGestureExclusion", "mBackNavigation", "mTopFullscreen",
            "EdgeBackGestureHandler", "mIsEnabled", "mAllowGesture", "mThresholdCrossed", "mExcludeRegion",
            "mIsBackGestureAllowed", "mDisabledForQuickstep", "mIsGestureBlockingActivityRunning",
        )
        for ((name, command) in listOf(
            "window" to "dumpsys -t 3 window displays",
            "systemUi" to "dumpsys -t 3 activity service com.android.systemui/.SystemUIService",
        )) {
            val diagnostic = runCatching {
                boundedAndroidShellOutput(command, 192_000).lineSequence()
                    .filter { line -> markers.any { marker -> line.contains(marker) } }.take(36).joinToString("\n").take(4_000)
            }.getOrElse { "diagnostic unavailable: ${it.javaClass.simpleName}" }
            result.put(name, diagnostic)
        }
        return result.toString()
    }

    @Test
    fun hiddenRailSurvivesSavedStateRestoreAndWideCompactWideChanges() {
        assertRequestedDisplayHost()
        val width = mutableStateOf(1_280.dp)
        val restoration = StateRestorationTester(compose)
        render(
            width = 1_280.dp,
            height = 800.dp,
            widthState = width,
            restorationTester = restoration,
        )
        compose.onNodeWithTag("workspace-rail-hide").performTouchInput { click() }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
        }

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("workspace-navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-show").assertIsDisplayed()

        compose.runOnIdle { width.value = 600.dp }
        compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
        compose.onNodeWithTag("workspace-rail-show").assertDoesNotExist()
        compose.runOnIdle { width.value = 1_280.dp }
        compose.onNodeWithTag("workspace-navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-show")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun shortLargeTextRailKeepsMoreReachableAndRetainsDestinationState() {
        assertRequestedDisplayHost()
        render(width = 1_280.dp, height = 360.dp, fontScale = 1.5f)
        captureRoot(
            name = "workspace-short-1280x360-font1_5-initial",
            testName = "shortLargeTextRailKeepsMoreReachableAndRetainsDestinationState",
            expectedWidthDp = 1_280,
            expectedHeightDp = 360,
            fontScale = 1.5f,
        )

        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("workspace-bottom-dock").assertDoesNotExist()
        assertFullyInsideRoot("workspace-header")

        compose.onNodeWithTag("increment-Dashboard").performClick()
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()

        val reports = compose.onNodeWithTag("workspace-rail-Reports")
        reports.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        reports.performTouchInput { click() }
        compose.onNodeWithText("Reports count 0").assertIsDisplayed()

        val more = compose.onNodeWithTag("workspace-more-modules")
        more.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-more-modules")
        more.performTouchInput { click() }

        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Help")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(52.dp)
            .performTouchInput { click() }
        compose.onNodeWithTag("workspace-destination-Settings").assertDoesNotExist()
        compose.onNodeWithText("Help count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Dashboard")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
        assertFullyInsideRoot("increment-Dashboard")

        captureRoot(
            name = "workspace-short-1280x360-font1_5",
            testName = "shortLargeTextRailKeepsMoreReachableAndRetainsDestinationState",
            expectedWidthDp = 1_280,
            expectedHeightDp = 360,
            fontScale = 1.5f,
        )
    }

    @Test
    fun readyWorkspaceMorePanelCapturesAuthorizedModules() {
        assertRequestedDisplayHost()
        val popupRoot = AtomicReference<View>()
        render(width = 1_280.dp, height = 800.dp, commandDialogRootObserver = popupRoot::set)
        waitForWorkspaceScene()
        compose.onNodeWithTag("workspace-more-modules").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        // Wide More excludes destinations already available in the primary rail.
        compose.onNodeWithTag("workspace-destination-Settings").assertDoesNotExist()
        compose.onNodeWithTag("workspace-destination-Help")
            .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(52.dp)
        compose.onNodeWithTag("workspace-destination-Finance").assertDoesNotExist()
        captureMorePanel(
            popupRoot,
            "workspace-more-1280x800",
            "readyWorkspaceMorePanelCapturesAuthorizedModules",
            fontScale = 1.0f,
        )
        compose.onNodeWithText("Close").assertIsEnabled().performClick()
        compose.onNodeWithTag("workspace-more-panel").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-Settings")
            .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-rail-Settings")
        compose.onNodeWithTag("workspace-rail-Settings").performClick()
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()
    }

    @Test
    fun compactNavigationGlassTracksSceneAndKeepsNightContrast() {
        assertCompactNavigationGlass(AppearancePreference.Night)
    }

    @Test
    fun compactNavigationGlassTracksSceneAndKeepsDayContrast() {
        assertCompactNavigationGlass(AppearancePreference.Day)
    }

    private fun assertCompactNavigationGlass(appearance: AppearancePreference) {
        assertRequestedDisplayHost()
        val darkFile = writeSolidWorkspaceFixture("compact-nav-dark", AndroidColor.rgb(22, 48, 156))
        val brightFile = writeSolidWorkspaceFixture("compact-nav-bright", AndroidColor.WHITE)
        val background = mutableStateOf(
            importedWorkspaceState(darkFile, generation = 101L, contentId = "compact-nav-dark"),
        )
        val scene = AtomicReference<WorkspaceGlassScene?>(null)
        val current = mutableStateOf(Destination.Gaming)
        val connectivity = mutableStateOf(SyncAvailabilityProblem.NO_NETWORK)
        val outbox = mutableStateOf(OutboxWorkStatus(retryableCount = 1))
        try {
            render(
                width = 600.dp,
                height = 800.dp,
                fontScale = 1.3f,
                appearancePreference = appearance,
                currentDestinationState = current,
                connectivityProblemState = connectivity,
                outboxWorkStatusState = outbox,
                backgroundStateState = background,
                workspaceSceneObserver = scene::set,
            )
            fun awaitScene(contentId: String, generation: Long) {
                compose.waitUntil(10_000L) {
                    scene.get()?.let { observed ->
                        observed.frame.actualKey.contentId == contentId &&
                            observed.frame.actualKey.generation == generation &&
                            observed.frame.state.name == "REQUESTED_READY" &&
                            observed.softenedKey?.actualKey == observed.frame.actualKey &&
                            observed.softenedKey?.frameEpoch == observed.frame.epoch
                    } == true
                }
            }
            awaitScene("compact-nav-dark", 101L)
            val darkHeader = compactSurfaceSample("workspace-header")
            val darkDock = compactSurfaceSample("workspace-bottom-dock")
            assertCompactNavigationLabelsAndContrast(appearance, current.value)
            // Home's appearance and online controls also use translucent surfaces.
            compose.runOnIdle {
                current.value = Destination.Dashboard
                connectivity.value = SyncAvailabilityProblem.NONE
            }
            assertCompactNavigationLabelsAndContrast(appearance, current.value)
            compose.runOnIdle {
                current.value = Destination.Gaming
                connectivity.value = SyncAvailabilityProblem.NO_NETWORK
            }
            captureRoot(
                "workspace-compact-nav-${appearance.name.lowercase()}-dark-600x800-font1_3",
                "compactNavigationGlassTracksSceneAndKeeps${appearance.name}Contrast",
                600, 800, 1.3f,
            )
            compose.runOnIdle {
                background.value = importedWorkspaceState(
                    brightFile, generation = 102L, contentId = "compact-nav-bright",
                )
            }
            awaitScene("compact-nav-bright", 102L)
            val brightHeader = compactSurfaceSample("workspace-header")
            val brightDock = compactSurfaceSample("workspace-bottom-dock")
            assertTrue("Compact header must project both ready scenes", colorDistance(darkHeader, brightHeader) >= 12)
            assertTrue("Compact dock must project both ready scenes", colorDistance(darkDock, brightDock) >= 12)
            assertCompactNavigationLabelsAndContrast(appearance, current.value)
            compose.onNodeWithTag("workspace-dock-Pos").performClick()
            compose.onNodeWithText("Pos count 0").assertIsDisplayed()
            compose.onNodeWithTag("workspace-dock-Pos").assertIsSelected()
            assertCompactNavigationLabelsAndContrast(appearance, current.value)
            listOf(
                SyncAvailabilityProblem.NONE,
                SyncAvailabilityProblem.VERIFYING,
                SyncAvailabilityProblem.SERVER_UNREACHABLE,
                SyncAvailabilityProblem.RECOVERING,
            ).forEach { problem ->
                compose.runOnIdle { connectivity.value = problem }
                assertCompactNavigationLabelsAndContrast(appearance, current.value)
            }
            compose.runOnIdle { outbox.value = OutboxWorkStatus(actionRequiredCount = 1) }
            assertCompactNavigationLabelsAndContrast(appearance, current.value)
            captureRoot(
                "workspace-compact-nav-${appearance.name.lowercase()}-bright-pos-600x800-font1_3",
                "compactNavigationGlassTracksSceneAndKeeps${appearance.name}Contrast",
                600, 800, 1.3f,
            )
        } finally {
            darkFile.delete()
            brightFile.delete()
        }
    }

    private fun assertCompactNavigationLabelsAndContrast(
        appearance: AppearancePreference,
        destination: Destination,
    ) {
        assertFullyInsideRoot("workspace-header")
        assertFullyInsideRoot("workspace-bottom-dock")
        val labels = listOf("Gaming", "Pos", "Shift", "Customers", "More")
        labels.forEach { destination ->
            val control = if (destination == "More") "workspace-more-modules" else "workspace-dock-$destination"
            compose.onNodeWithTag(control).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            assertFullyInsideRoot(control)
            assertTextDoesNotOverflow("workspace-dock-label-$destination")
        }
        assertTextDoesNotOverflow("workspace-connectivity-label")
        assertTextDoesNotOverflow("workspace-outbox-label")
        val tags = listOf(
            "workspace-header-destination-label",
            "workspace-connectivity-label",
            "workspace-outbox-label",
        ) + labels.map { "workspace-dock-label-$it" }
        val nodes = tags.map { it to compose.onNodeWithTag(it, useUnmergedTree = true) } + listOf(
            "appearance-label" to if (destination == Destination.Dashboard) {
                compose.onNodeWithTag("workspace-appearance-label", useUnmergedTree = true)
            } else {
                compose.onNodeWithText(appearance.name, useUnmergedTree = true)
            },
        )
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            nodes.forEach { (tag, node) ->
                val bounds = node.fetchSemanticsNode().boundsInRoot
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.size)
                val foreground = layouts.single().layoutInput.style.color.toArgb()
                // The solid scene fixture makes this clear strip beside the text its actual backing.
                val surface = if (tag == "appearance-label") {
                    bitmap.getPixel(bounds.center.x.roundToInt(), (bounds.top - 2f).roundToInt())
                } else {
                    bitmap.getPixel((bounds.right - 2f).roundToInt(), bounds.center.y.roundToInt())
                }
                assertTrue("$tag must retain 4.8:1 body-text contrast", contrastRatio(foreground, surface) >= 4.8)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun compactSurfaceSample(tag: String): Int {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        return try {
            // Outside the child controls and below the single-contour top edge.
            bitmap.getPixel((bounds.left + 3f).roundToInt(), (bounds.top + 3f).roundToInt())
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitForWorkspaceScene() {
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("workspace-glass-ready-workspace")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
    }

    @Test
    fun readySceneMoreDialogProjectsTheCurrentBackdrop() {
        assertRequestedDisplayHost()
        val darkFile = writeSolidWorkspaceFixture("more-dark", AndroidColor.rgb(22, 48, 156))
        val brightFile = writeSolidWorkspaceFixture("more-bright", AndroidColor.WHITE)
        val background = mutableStateOf(
            importedWorkspaceState(darkFile, generation = 91L, contentId = "more-dark"),
        )
        val popupRoot = AtomicReference<View>()
        val commandScene = AtomicReference<WorkspaceGlassScene?>(null)
        try {
            render(
                width = 1_280.dp,
                height = 800.dp,
                backgroundStateState = background,
                commandDialogRootObserver = popupRoot::set,
                commandDialogSceneObserver = commandScene::set,
            )
            compose.waitUntil(10_000L) {
                compose.onAllNodesWithTag("workspace-glass-ready-workspace")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .singleOrNull()
                    ?.boundsInRoot
                    ?.let { it.width > 0f && it.height > 0f } == true
            }
            compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
            compose.onNodeWithTag("workspace-more-modules")
                .assertHeightIsAtLeast(48.dp)
                .performTouchInput { click() }
            compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
            compose.onNodeWithTag("workspace-destination-Help")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(52.dp)
            val darkPixel = popupSample(
                rootRef = popupRoot,
                tag = "workspace-more-panel",
                xFraction = .88f,
                yFraction = .18f,
            )
            val helpRowBounds = compose.onNodeWithTag("workspace-destination-Help")
                .fetchSemanticsNode().boundsInRoot
            val helpText = compose.onNodeWithText("Help", useUnmergedTree = true)
            val helpTextBounds = helpText.fetchSemanticsNode().boundsInRoot
            val helpLayouts = mutableListOf<TextLayoutResult>()
            helpText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(helpLayouts) }
            assertEquals(1, helpLayouts.size)
            val helpTextColor = helpLayouts.single().layoutInput.style.color.toArgb()
            val helpRowY = ((helpTextBounds.center.y - helpRowBounds.top) / helpRowBounds.height)
                .coerceIn(0f, 1f)
            val darkHelpSurface = popupSample(
                rootRef = popupRoot,
                tag = "workspace-destination-Help",
                xFraction = .92f,
                yFraction = helpRowY,
            )

            compose.runOnIdle {
                background.value = importedWorkspaceState(
                    brightFile,
                    generation = 92L,
                    contentId = "more-bright",
                )
            }
            compose.waitUntil(10_000L) {
                commandScene.get()?.let { observed ->
                    observed.frame.actualKey.contentId == "more-bright" &&
                        observed.frame.actualKey.generation == 92L &&
                        observed.frame.state.name == "REQUESTED_READY" &&
                        observed.softenedKey?.actualKey == observed.frame.actualKey &&
                        observed.softenedKey?.frameEpoch == observed.frame.epoch
                } == true
            }
            compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
            val brightPixel = popupSample(
                rootRef = popupRoot,
                tag = "workspace-more-panel",
                xFraction = .88f,
                yFraction = .18f,
            )
            val brightHelpSurface = popupSample(
                rootRef = popupRoot,
                tag = "workspace-destination-Help",
                xFraction = .92f,
                yFraction = helpRowY,
            )
            assertTrue(
                "The actual More modal must project the newly ready scene: " +
                    "dark=${Integer.toHexString(darkPixel)} bright=${Integer.toHexString(brightPixel)}",
                colorDistance(darkPixel, brightPixel) >= 12,
            )
            assertTrue(
                "The actual Help row must project both controlled scenes: " +
                    "dark=${Integer.toHexString(darkHelpSurface)} " +
                    "bright=${Integer.toHexString(brightHelpSurface)}",
                colorDistance(darkHelpSurface, brightHelpSurface) >= 12,
            )
            assertTrue(
                "The actual Help label must retain the 4.8:1 body-text floor over both scenes",
                contrastRatio(helpTextColor, darkHelpSurface) >= 4.8 &&
                    contrastRatio(helpTextColor, brightHelpSurface) >= 4.8,
            )
            val titleLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("workspace-command-title", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
            assertEquals(1, titleLayouts.size)
            val titleColor = titleLayouts.single().layoutInput.style.color.toArgb()
            val panelBounds = compose.onNodeWithTag("workspace-more-panel")
                .fetchSemanticsNode().boundsInRoot
            val titleBounds = compose.onNodeWithTag(
                "workspace-command-title",
                useUnmergedTree = true,
            ).fetchSemanticsNode().boundsInRoot
            val titleSurfacePixel = popupSample(
                rootRef = popupRoot,
                tag = "workspace-more-panel",
                xFraction = .88f,
                yFraction = ((titleBounds.center.y - panelBounds.top) / panelBounds.height)
                    .coerceIn(0f, 1f),
            )
            assertTrue(
                "More title must retain 4.8:1 contrast over its projected surface: " +
                    "text=${Integer.toHexString(titleColor)} " +
                    "surface=${Integer.toHexString(titleSurfacePixel)}",
                contrastRatio(titleColor, titleSurfacePixel) >= 4.8,
            )
            compose.onNodeWithTag("workspace-destination-Help").performTouchInput { click() }
            compose.onNodeWithText("Help count 0").assertIsDisplayed()
        } finally {
            darkFile.delete()
            brightFile.delete()
        }
    }

    @Test
    fun compactPortraitUsesDockAndKeepsAuthorizedRoutesReachable() {
        assertRequestedDisplayHost()
        render(width = 600.dp, height = 800.dp)
        captureRoot(
            name = "workspace-compact-portrait-600x800-initial",
            testName = "compactPortraitUsesDockAndKeepsAuthorizedRoutesReachable",
            expectedWidthDp = 600,
            expectedHeightDp = 800,
            fontScale = 1.0f,
        )

        compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
        compose.onNodeWithTag("workspace-navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("outbox-work-status-slot").assertDoesNotExist()
        compose.onNodeWithContentDescription("Gaming. Manage stations and sessions")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("workspace-more-modules")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-header")
        assertFullyInsideRoot("workspace-bottom-dock")
        assertFullyInsideRoot("increment-Dashboard")

        compose.onNodeWithTag("workspace-more-modules").performTouchInput { click() }
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("workspace-destination-Finance").assertDoesNotExist()
        compose.onNodeWithTag("workspace-destination-Settings").performTouchInput { click() }
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()

        captureRoot(
            name = "workspace-compact-portrait-600x800",
            testName = "compactPortraitUsesDockAndKeepsAuthorizedRoutesReachable",
            expectedWidthDp = 600,
            expectedHeightDp = 800,
            fontScale = 1.0f,
        )
    }

    @Test
    fun compactPortraitFontScale1_3KeepsEssentialDockLabelsComplete() {
        assertRequestedDisplayHost()
        val popupRoot = AtomicReference<View>()
        render(
            width = 600.dp,
            height = 800.dp,
            fontScale = 1.3f,
            outboxWorkStatus = OutboxWorkStatus(retryableCount = 1),
            commandDialogRootObserver = popupRoot::set,
        )

        compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
        // Capture before the layout assertions so a future regression retains
        // the exact failed pixels alongside the TextLayoutResult diagnostics.
        captureRoot(
            name = "workspace-compact-portrait-600x800-font1_3",
            testName = "compactPortraitFontScale1_3KeepsEssentialDockLabelsComplete",
            expectedWidthDp = 600,
            expectedHeightDp = 800,
            fontScale = 1.3f,
        )
        listOf(
            "Find a module",
            "Help and support",
            "Account actions",
            "Day, night and system appearance",
            "Connection status: No internet. Open connection details",
        ).forEach(::assertContentDescriptionControl)
        compose.onNodeWithTag("outbox-work-status-slot")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("outbox-work-status-slot")
        compose.onNodeWithContentDescription("Saved work status: 1 waiting to sync")
            .assertIsDisplayed()
        assertContentDescriptionInsideRoot("Saved work status: 1 waiting to sync")
        compose.onNodeWithTag("workspace-header-identity")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-header-identity")

        listOf("Gaming", "Pos", "Shift", "Customers").forEach { destination ->
            compose.onNodeWithTag("workspace-dock-$destination")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
            assertTextDoesNotOverflow("workspace-dock-label-$destination")
        }
        compose.onNodeWithTag("workspace-more-modules")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertTextDoesNotOverflow("workspace-dock-label-More")
        assertTextDoesNotOverflow("workspace-connectivity-label")

        compose.onNodeWithContentDescription("Account actions")
            .performTouchInput { click() }
        compose.onNodeWithText("Appearance · Night")
            .assertIsDisplayed()
            .performTouchInput { click() }
        compose.onNodeWithText("Appearance · Night").assertDoesNotExist()

        waitForWorkspaceScene()
        compose.onNodeWithTag("workspace-more-modules").performTouchInput { click() }
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Settings").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("workspace-destination-Finance").assertDoesNotExist()
        captureMorePanel(
            popupRoot,
            "workspace-more-600x800-font1_3",
            "compactPortraitFontScale1_3KeepsEssentialDockLabelsComplete",
            fontScale = 1.3f,
        )
        compose.onNodeWithText("Close").performClick()

        compose.onNodeWithTag("workspace-dock-Customers").performTouchInput { click() }
        compose.onNodeWithText("Customers count 0").assertIsDisplayed()
        assertFullyInsideRoot("increment-Customers")
    }

    @Test
    fun liveRailFollowsStationaryDwellsBeforeUpAndRetainsRouteState() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        val constructions = ConcurrentHashMap<Destination, Int>()
        render(
            width = 1_280.dp,
            height = 800.dp,
            onDestinationChanged = destinations::add,
            onFeatureConstructed = { destination ->
                constructions.merge(destination, 1, Int::plus)
            },
        )
        compose.onNodeWithTag("increment-Dashboard").performTouchInput { click() }
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
        val baseline = sharedChromeSnapshot()
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val gaming = nodeCenterInRoot("workspace-rail-Gaming")
        val pos = nodeCenterInRoot("workspace-rail-Pos")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(gaming)
            compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Gaming }
            assertTrue("Gaming must open while the pointer remains down", pointer.isDown)
            compose.onNodeWithText("Gaming count 0").assertIsDisplayed()
            assertChromeMatches(baseline, "live scrub Gaming before up")

            pointer.moveTo(pos)
            compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Pos }
            assertTrue("POS must open while the same pointer remains down", pointer.isDown)
            compose.onNodeWithText("Pos count 0").assertIsDisplayed()
            assertChromeMatches(baseline, "live scrub POS before up")
            captureRoot(
                name = "workspace-live-rail-before-up",
                testName = "liveRailFollowsStationaryDwellsBeforeUpAndRetainsRouteState",
                expectedWidthDp = 1_280,
                expectedHeightDp = 800,
                fontScale = 1.0f,
            )
            val callbacksBeforeUp = destinations.toList()
            pointer.up()
            compose.waitForIdle()
            assertEquals("Pointer up must not add a route", callbacksBeforeUp, destinations.toList())
        } finally {
            pointer.cancelIfDown()
        }

        assertEquals(listOf(Destination.Gaming, Destination.Pos), destinations.toList())
        assertEquals("Only dwell-committed Gaming constructs", 1, constructions[Destination.Gaming])
        assertEquals("Only dwell-committed POS constructs", 1, constructions[Destination.Pos])
        compose.onNodeWithTag("workspace-rail-Dashboard").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
    }

    @Test
    fun stationaryLongPressStillStartsTheSameDwellNavigation() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp, height = 800.dp,
            onDestinationChanged = destinations::add, railScrubTrace = trace::record,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val gaming = nodeCenterInRoot("workspace-rail-Gaming")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.activateAt(Offset(dashboard.x, dashboard.y + 1f))
            assertTrue(
                "A stationary long press must claim before any vertical slide",
                trace.snapshot().first.any { it.kind == ExecutiveRailScrubTraceKind.CLAIMED },
            )
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            assertTrue("Holding the origin must not navigate", destinations.isEmpty())
            pointer.moveTo(gaming)
            compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Gaming }
            assertTrue("Long-press compatibility must still navigate before release", pointer.isDown)
            pointer.up()
        } finally {
            pointer.cancelIfDown()
        }
        assertEquals(listOf(Destination.Gaming), destinations.toList())
    }

    @Test
    fun ambiguousDiagonalNeverScrubsAndFreshHorizontalSlideStillHidesRail() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp, height = 800.dp,
            onDestinationChanged = destinations::add, railScrubTrace = trace::record,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val density = compose.activity.resources.displayMetrics.density
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(Offset(dashboard.x + 24f * density, dashboard.y + 24f * density))
            pointer.activateAt(nodeCenterInRoot("workspace-rail-Gaming"))
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            assertFalse(
                "An ambiguous stream must never gain scrub ownership",
                trace.snapshot().first.any { it.kind == ExecutiveRailScrubTraceKind.CLAIMED },
            )
            assertTrue("An ambiguous stream must not route", destinations.isEmpty())
            pointer.up()
        } finally {
            pointer.cancelIfDown()
        }
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        assertTrue(destinations.isEmpty())
        val freshStart = nodeCenterInRoot("workspace-rail-Dashboard")
        compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
            down(freshStart)
            advanceEventTime(32L)
            moveTo(Offset(freshStart.x - 84f * density, freshStart.y))
            advanceEventTime(32L)
            up()
        }
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Horizontal hide from a route row must not navigate", destinations.isEmpty())
        assertFalse(
            "The parent must retain horizontal ownership",
            trace.snapshot().first.any { it.kind == ExecutiveRailScrubTraceKind.CLAIMED },
        )
    }

    @Test
    fun immediateSlideStopsOnSecondPointerAndCancellationWithoutFinalRoute() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp, height = 800.dp,
            onDestinationChanged = destinations::add, railScrubTrace = trace::record,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val gaming = nodeCenterInRoot("workspace-rail-Gaming")
        // Dispatch movement and its interruption together. The follower's 190 ms dwell
        // uses real uptime, so separate Compose lookups here could legitimately allow
        // navigation on a busy host before the next pointer event is injected.
        compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
            down(dashboard)
            advanceEventTime(32L)
            moveTo(gaming)
            down(1, Offset(gaming.x + 12f, gaming.y))
            up(1)
            up()
        }
        Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
        assertTrue(
            "A second pointer must stop a claimed immediate slide",
            trace.snapshot().first.any {
                it.kind == ExecutiveRailScrubTraceKind.STOPPED &&
                    it.stopReason == ExecutiveRailScrubStopReason.MULTIPLE_POINTERS
            },
        )
        assertTrue("Multitouch must not add a final route", destinations.isEmpty())
        compose.waitForIdle()
        compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
            down(dashboard)
            advanceEventTime(32L)
            moveTo(gaming)
            cancel()
        }
        assertEquals(
            "The cancelled fresh stream must also have claimed immediate ownership",
            2, trace.snapshot().first.count { it.kind == ExecutiveRailScrubTraceKind.CLAIMED },
        )
        Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
        compose.waitForIdle()
        assertTrue("Cancelled contact must not finish its pending dwell", destinations.isEmpty())
    }

    @Test
    fun diagnosticDelayedRenderedRouteMustNotCancelAcceptedTwoHopScrub() {
        assertRequestedDisplayHost()
        val presented = mutableStateOf(Destination.Dashboard)
        val requests = CopyOnWriteArrayList<Destination>()
        val owner = DiagnosticRouteOwner(
            initial = Destination.Dashboard,
            requestsAccepted = true,
            allowed = { AllowedDestinations },
            onRequest = requests::add,
        )
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp,
            height = 800.dp,
            currentDestinationState = presented,
            updatePresentedDestinationOnNavigation = false,
            railRouteAuthority = owner,
            railScrubTrace = trace::record,
        )
        val pointer = beginHeldPointer(nodeCenterInRoot("workspace-rail-Dashboard"))
        try {
            pointer.slideTo(nodeCenterInRoot("workspace-rail-Gaming"))
            compose.waitUntil(3_000) { requests.contains(Destination.Gaming) }
            pointer.moveTo(nodeCenterInRoot("workspace-rail-Pos"))
            compose.waitUntil(3_000) {
                requests.contains(Destination.Pos) || trace.containsStop()
            }
            assertTrue("The diagnostic decision must occur before pointer up", pointer.isDown)
            assertTrue(
                "Both owner-accepted hops must commit despite delayed rendered route state",
                requests.contains(Destination.Pos),
            )
            assertEquals(
                "The authoritative owner accepted both held-pointer hops",
                WorkspaceRouteSnapshot(Destination.Pos, 2L),
                owner.current(),
            )
        } finally {
            pointer.cancelIfDown()
            writeRailDiagnostic(
                name = "rail-diagnostic-delayed-rendered-route",
                expectation = "Accepted owner revisions must outlive delayed rendered route state",
                trace = trace,
                owner = owner,
                requests = requests,
            )
        }
    }

    @Test
    fun diagnosticDeniedAuthorityRequestStopsHeldStreamBeforePos() {
        assertRequestedDisplayHost()
        val presented = mutableStateOf(Destination.Dashboard)
        val requests = CopyOnWriteArrayList<Destination>()
        val owner = DiagnosticRouteOwner(
            initial = Destination.Dashboard,
            requestsAccepted = false,
            allowed = { AllowedDestinations },
            onRequest = requests::add,
        )
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp,
            height = 800.dp,
            currentDestinationState = presented,
            updatePresentedDestinationOnNavigation = false,
            railRouteAuthority = owner,
            railScrubTrace = trace::record,
        )
        val pointer = beginHeldPointer(nodeCenterInRoot("workspace-rail-Dashboard"))
        try {
            pointer.slideTo(nodeCenterInRoot("workspace-rail-Gaming"))
            compose.waitUntil(3_000) { requests.contains(Destination.Gaming) }
            compose.waitUntil(3_000) {
                trace.containsRejectedRequest(WorkspaceRouteRejection.OUTSIDE_CURRENT_SCOPE) &&
                    trace.containsStopAfter(
                        uptimeMillis = 0L,
                        reason = ExecutiveRailScrubStopReason.REQUEST_REJECTED,
                    )
            }
            assertTrue("Authority denial must stop the scrub before pointer up", pointer.isDown)
            pointer.moveTo(nodeCenterInRoot("workspace-rail-Pos"))
            Thread.sleep(350L)
            assertTrue("The negative probe must keep the pointer down", pointer.isDown)
            assertFalse("A denied Gaming request must end the stream before POS", requests.contains(Destination.Pos))
            assertEquals(WorkspaceRouteSnapshot(Destination.Dashboard, 0L), owner.current())
        } finally {
            pointer.cancelIfDown()
            writeRailDiagnostic(
                name = "rail-diagnostic-denied-authority",
                expectation = "Denied authority request cancels the held stream",
                trace = trace,
                owner = owner,
                requests = requests,
            )
        }
    }

    @Test
    fun diagnosticExternalAbaRevisionMustCancelHeldStream() {
        assertRequestedDisplayHost()
        val presented = mutableStateOf(Destination.Dashboard)
        val requests = CopyOnWriteArrayList<Destination>()
        val owner = DiagnosticRouteOwner(
            initial = Destination.Dashboard,
            requestsAccepted = true,
            allowed = { AllowedDestinations },
            onRequest = requests::add,
            onAccepted = { target ->
                if (target == Destination.Gaming) presented.value = Destination.Gaming
            },
        )
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp,
            height = 800.dp,
            currentDestinationState = presented,
            updatePresentedDestinationOnNavigation = false,
            railRouteAuthority = owner,
            railScrubTrace = trace::record,
        )
        val pointer = beginHeldPointer(nodeCenterInRoot("workspace-rail-Dashboard"))
        try {
            pointer.slideTo(nodeCenterInRoot("workspace-rail-Gaming"))
            compose.waitUntil(3_000) { requests.contains(Destination.Gaming) }
            compose.onNodeWithText("Gaming count 0").assertIsDisplayed()
            owner.replaceExternally(Destination.Dashboard)
            owner.replaceExternally(Destination.Gaming)
            val abaCompletedAt = owner.events().last().uptimeMillis
            pointer.moveTo(nodeCenterInRoot("workspace-rail-Pos"))
            compose.waitUntil(3_000) {
                requests.contains(Destination.Pos) ||
                    trace.containsStopAfter(
                        uptimeMillis = abaCompletedAt,
                        reason = ExecutiveRailScrubStopReason.OWNER_ROUTE_MISMATCH,
                    )
            }
            assertTrue("The ABA decision must occur before pointer up", pointer.isDown)
            assertFalse(
                "Dashboard-to-Gaming external ABA revisions must cancel before POS",
                requests.contains(Destination.Pos),
            )
            assertTrue(
                "The follower must observe the destination-equal owner revision before stopping",
                trace.containsObservedOwnerAfter(
                    uptimeMillis = abaCompletedAt,
                    expected = WorkspaceRouteSnapshot(Destination.Gaming, 3L),
                ),
            )
            assertEquals(
                WorkspaceRouteSnapshot(Destination.Gaming, 3L),
                owner.current(),
            )
        } finally {
            pointer.cancelIfDown()
            writeRailDiagnostic(
                name = "rail-diagnostic-external-aba",
                expectation = "Destination-equal ABA with newer owner revision cancels the held stream",
                trace = trace,
                owner = owner,
                requests = requests,
            )
        }
    }

    @Test
    fun ownerReplacementCancelsHeldStreamAndFreshGestureUsesNewOwner() {
        assertRequestedDisplayHost()
        val presented = mutableStateOf(Destination.Dashboard)
        val oldRequests = CopyOnWriteArrayList<Destination>()
        val newRequests = CopyOnWriteArrayList<Destination>()
        val oldOwner = DiagnosticRouteOwner(
            initial = Destination.Dashboard,
            requestsAccepted = true,
            allowed = { AllowedDestinations },
            onRequest = oldRequests::add,
        )
        val newOwner = DiagnosticRouteOwner(
            initial = Destination.Dashboard,
            requestsAccepted = true,
            allowed = { AllowedDestinations },
            onRequest = newRequests::add,
        )
        val ownerState = mutableStateOf<ExecutiveRailRouteAuthority?>(oldOwner)
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp,
            height = 800.dp,
            currentDestinationState = presented,
            updatePresentedDestinationOnNavigation = false,
            railRouteAuthorityState = ownerState,
            railScrubTrace = trace::record,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val gaming = nodeCenterInRoot("workspace-rail-Gaming")
        val pos = nodeCenterInRoot("workspace-rail-Pos")
        val oldPointer = beginHeldPointer(dashboard)
        try {
            oldPointer.slideTo(gaming)
            // The scrub follower deliberately uses real uptime for its 190 ms dwell. Give that
            // bounded follower interval wall-clock time before polling Compose state; advancing
            // the injected pointer clock above only establishes immediate vertical ownership.
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            compose.waitUntil(3_000) {
                oldRequests.contains(Destination.Gaming) || trace.containsStop()
            }
            assertTrue(
                "The original owner must receive Gaming before replacement; " +
                    "requests=$oldRequests trace=${trace.snapshot()}",
                oldRequests.contains(Destination.Gaming),
            )
            val ownerSwapAt = SystemClock.uptimeMillis()
            compose.runOnIdle { ownerState.value = newOwner }
            compose.waitUntil(3_000) {
                trace.containsStopAfter(
                    uptimeMillis = ownerSwapAt,
                    reason = ExecutiveRailScrubStopReason.MODEL_OR_EFFECT_DISPOSED,
                )
            }
            oldPointer.moveTo(pos)
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            assertFalse("The disposed old stream must not request POS", oldRequests.contains(Destination.Pos))
            assertTrue("The replacement owner must not inherit the held pointer", newRequests.isEmpty())
            oldPointer.up()
        } finally {
            oldPointer.cancelIfDown()
        }

        // The replacement pointer-input effect saw the tail of the old physical stream. Let its
        // awaitEachGesture cleanup observe the old UP before beginning the independent fresh DOWN;
        // otherwise Compose's synthetic dispatcher can admit the fresh slide while starving
        // the follower launched from that half-overlapped gesture.
        compose.waitForIdle()
        val stopsBeforeFreshGesture = trace.stopCount()
        val freshPointer = beginHeldPointer(dashboard)
        try {
            freshPointer.slideTo(gaming)
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            compose.waitUntil(3_000) {
                newRequests.contains(Destination.Gaming) || trace.stopCount() > stopsBeforeFreshGesture
            }
            assertTrue(
                "The replacement owner must receive a fresh Gaming gesture; " +
                    "requests=$newRequests trace=${trace.snapshot()}",
                newRequests.contains(Destination.Gaming),
            )
            assertEquals(listOf(Destination.Gaming), newRequests.toList())
            assertEquals(WorkspaceRouteSnapshot(Destination.Gaming, 1L), newOwner.current())
            captureRoot(
                name = "workspace-live-rail-owner-replacement",
                testName = "ownerReplacementCancelsHeldStreamAndFreshGestureUsesNewOwner",
                expectedWidthDp = 1_280,
                expectedHeightDp = 800,
                fontScale = 1.0f,
            )
            freshPointer.up()
        } finally {
            freshPointer.cancelIfDown()
        }
        assertEquals(listOf(Destination.Gaming), oldRequests.toList())
        assertFalse(oldRequests.contains(Destination.Pos))
    }

    @Test
    fun liveRailFastCrossSkipsConstructionAndMoreStaysTapOnly() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        val constructions = ConcurrentHashMap<Destination, Int>()
        render(
            width = 1_280.dp,
            height = 800.dp,
            onDestinationChanged = destinations::add,
            onFeatureConstructed = { destination ->
                constructions.merge(destination, 1, Int::plus)
            },
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val gaming = nodeCenterInRoot("workspace-rail-Gaming")
        val pos = nodeCenterInRoot("workspace-rail-Pos")
        val more = nodeCenterInRoot("workspace-more-modules")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(gaming)
            Thread.sleep(45L)
            pointer.moveTo(pos)
            compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Pos }
            assertFalse("A crossed row must not navigate", destinations.contains(Destination.Gaming))
            assertEquals("A crossed row must not construct", null, constructions[Destination.Gaming])
            compose.onNodeWithText("Pos count 0").assertIsDisplayed()
            compose.waitUntil(3_000) { constructions[Destination.Pos] == 1 }
            assertEquals(1, constructions[Destination.Pos])

            val beforeMore = destinations.toList()
            pointer.moveTo(more)
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            compose.waitForIdle()
            compose.onNodeWithTag("workspace-more-panel").assertDoesNotExist()
            assertEquals("More must not open or navigate under scrub contact", beforeMore, destinations.toList())
            pointer.up()
            compose.waitForIdle()
            compose.onNodeWithTag("workspace-more-panel").assertDoesNotExist()
        } finally {
            pointer.cancelIfDown()
        }

        compose.onNodeWithTag("workspace-more-modules").performTouchInput { click() }
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Help")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(52.dp)
    }

    @Test
    fun permissionModelChangeCancelsActiveRailContactWithoutStaleCommit() {
        assertRequestedDisplayHost()
        val allowed = mutableStateOf(AllowedDestinations)
        val destinations = CopyOnWriteArrayList<Destination>()
        render(
            width = 1_280.dp,
            height = 800.dp,
            destinationsState = allowed,
            onDestinationChanged = destinations::add,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val reports = nodeCenterInRoot("workspace-rail-Reports")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(reports)
            compose.runOnIdle {
                allowed.value = AllowedDestinations.filterNot { it == Destination.Reports }
            }
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            compose.waitForIdle()
            assertFalse("Removed permission must cancel the candidate", destinations.contains(Destination.Reports))
            compose.onNodeWithTag("workspace-rail-Reports").assertDoesNotExist()
            pointer.up()
        } finally {
            pointer.cancelIfDown()
        }
        assertTrue("Cancellation must not navigate anywhere", destinations.isEmpty())
        compose.onNodeWithTag("workspace-rail-Gaming").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Gaming count 0").assertIsDisplayed()
    }

    @Test
    fun largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        render(
            width = 1_280.dp,
            height = 360.dp,
            fontScale = 1.3f,
            onDestinationChanged = destinations::add,
        )
        captureRoot(
            name = "workspace-live-rail-edge-font1_3-initial",
            testName = "largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings",
            expectedWidthDp = 1_280,
            expectedHeightDp = 360,
            fontScale = 1.3f,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val viewport = nodeBoundsInRootPixels("workspace-navigation-rail-scrub-viewport")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(Offset(dashboard.x, viewport.bottom - 3f))
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithTag("workspace-rail-Settings").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            captureRoot(
                name = "workspace-live-rail-edge-font1_3-settings-exposed",
                testName = "largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings",
                expectedWidthDp = 1_280,
                expectedHeightDp = 360,
                fontScale = 1.3f,
            )
            val settings = waitForStableRailTargetOutsideEdgeZone("workspace-rail-Settings")
            captureRoot(
                name = "workspace-live-rail-edge-font1_3-settings-settled",
                testName = "largeTextEdgeScrollSuppressesTransitRoutesAndCommitsStableSettings",
                expectedWidthDp = 1_280,
                expectedHeightDp = 360,
                fontScale = 1.3f,
            )
            pointer.moveTo(settings)
            compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Settings }
            assertEquals(
                "Rows moving beneath a stationary edge-held finger must not navigate",
                listOf(Destination.Settings),
                destinations.toList(),
            )
            assertTrue(pointer.isDown)
            pointer.up()
        } finally {
            pointer.cancelIfDown()
        }
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()
    }

    @Test
    fun touchExplorationFallbackUsesSemanticsAndBodyDragNeverRoutes() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        render(
            width = 1_280.dp,
            height = 800.dp,
            touchExplorationOverride = true,
            onDestinationChanged = destinations::add,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val pos = nodeCenterInRoot("workspace-rail-Pos")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(pos)
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            pointer.up()
        } finally {
            pointer.cancelIfDown()
        }
        assertTrue("Touch exploration must disable scrub navigation", destinations.isEmpty())

        compose.onNodeWithTag("workspace-rail-Pos").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Pos count 0").assertIsDisplayed()
        assertEquals(listOf(Destination.Pos), destinations.toList())

        val bodyStart = nodeCenterInRoot("increment-Pos")
        val bodyPointer = beginHeldPointer(bodyStart)
        try {
            bodyPointer.slideTo(Offset(bodyStart.x + 90f, bodyStart.y + 80f))
            Thread.sleep(ExecutiveRailTargetDwellMillis + 60L)
            bodyPointer.up()
        } finally {
            bodyPointer.cancelIfDown()
        }
        assertEquals("Body gestures must not become route gestures", listOf(Destination.Pos), destinations.toList())

        val density = compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("workspace-navigation-rail").performTouchInput {
            val start = Offset(width - (8f * density), height * 0.72f)
            down(start)
            advanceEventTime(32L)
            moveTo(Offset(start.x - (84f * density), start.y))
            advanceEventTime(32L)
            up()
        }
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()

        compose.onNodeWithContentDescription("Hide navigation sidebar")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Show navigation sidebar")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        assertRailRevealOccupiesHeaderLeadingSlot("increment-Pos")
        compose.onNodeWithContentDescription("Show navigation sidebar")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000L) {
            compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().size == 1
        }
        assertEquals(
            "Accessible rail controls must not navigate",
            listOf(Destination.Pos),
            destinations.toList(),
        )
    }

    @Test
    fun immediateVerticalSlideClaimsWithoutDragScrollAndKeepsOrdinaryTapNavigation() {
        assertRequestedDisplayHost()
        val destinations = CopyOnWriteArrayList<Destination>()
        val constructions = ConcurrentHashMap<Destination, Int>()
        val trace = BoundedRailTrace()
        render(
            width = 1_280.dp,
            height = 360.dp,
            fontScale = 1.3f,
            onDestinationChanged = destinations::add,
            railScrubTrace = trace::record,
            onFeatureConstructed = { destination ->
                constructions.merge(destination, 1, Int::plus)
            },
        )
        compose.onNodeWithText("Dashboard count 0").assertIsDisplayed()
        compose.waitUntil(3_000) { constructions[Destination.Dashboard] == 1 }
        val before = nodeBoundsInRootPixels("workspace-rail-Shift")
        val start = Offset(
            x = (before.left + before.right) / 2f,
            y = (before.top + before.bottom) / 2f,
        )
        val density = compose.activity.resources.displayMetrics.density
        val stepPx = 24f * density
        val gestureDurationMillis = 96L
        assertTrue(
            "The slide probe must finish before the platform long-press threshold",
            gestureDurationMillis < ViewConfiguration.getLongPressTimeout(),
        )
        captureRoot(
            name = "workspace-live-rail-immediate-slide-before",
            testName = "immediateVerticalSlideClaimsWithoutDragScrollAndKeepsOrdinaryTapNavigation",
            expectedWidthDp = 1_280,
            expectedHeightDp = 360,
            fontScale = 1.3f,
        )

        compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
            down(start)
            advanceEventTime(24L)
            moveTo(Offset(start.x, start.y - stepPx))
            advanceEventTime(24L)
            moveTo(Offset(start.x, start.y - (stepPx * 2f)))
            advanceEventTime(24L)
            moveTo(Offset(start.x, start.y - (stepPx * 3f)))
            advanceEventTime(24L)
            up()
        }
        compose.waitForIdle()
        val after = nodeBoundsInRootPixels("workspace-rail-Shift")
        captureRoot(
            name = "workspace-live-rail-immediate-slide-after",
            testName = "immediateVerticalSlideClaimsWithoutDragScrollAndKeepsOrdinaryTapNavigation",
            expectedWidthDp = 1_280,
            expectedHeightDp = 360,
            fontScale = 1.3f,
        )
        val geometryDeltaPx = before.top - after.top
        assertTrue(
            "Vertical slop must claim the held stream without requiring long press: ${trace.snapshot()}",
            trace.snapshot().first.any { it.kind == ExecutiveRailScrubTraceKind.CLAIMED },
        )
        assertEquals(
            "A claimed vertical slide must suppress ordinary drag scrolling away from edge zones",
            0f, geometryDeltaPx, 1f,
        )
        assertTrue("Releasing before stable target dwell must not navigate", destinations.isEmpty())
        assertEquals(
            "A short slide must not construct another destination body",
            mapOf(Destination.Dashboard to 1),
            constructions.toMap(),
        )

        compose.onNodeWithTag("workspace-rail-Settings")
            .performScrollTo()
            .performTouchInput { click() }
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()
        assertEquals(listOf(Destination.Settings), destinations.toList())
        assertEquals(1, constructions[Destination.Settings])
    }

    @Test
    fun externalRouteReplacementCancelsClaimedRailStream() {
        assertRequestedDisplayHost()
        val current = mutableStateOf(Destination.Dashboard)
        val destinations = CopyOnWriteArrayList<Destination>()
        render(
            width = 1_280.dp,
            height = 800.dp,
            currentDestinationState = current,
            onDestinationChanged = destinations::add,
        )
        val dashboard = nodeCenterInRoot("workspace-rail-Dashboard")
        val gaming = nodeCenterInRoot("workspace-rail-Gaming")
        val pos = nodeCenterInRoot("workspace-rail-Pos")
        val pointer = beginHeldPointer(dashboard)
        try {
            pointer.slideTo(gaming)
            compose.waitUntil(3_000) { destinations.lastOrNull() == Destination.Gaming }
            compose.onNodeWithText("Gaming count 0").assertIsDisplayed()
            assertTrue(pointer.isDown)

            compose.runOnIdle { current.value = Destination.Dashboard }
            compose.onNodeWithText("Dashboard count 0").assertIsDisplayed()
            Thread.sleep(120L)
            pointer.moveTo(pos)
            Thread.sleep(ExecutiveRailTargetDwellMillis + 80L)
            captureRoot(
                name = "workspace-live-rail-external-reset-before-up",
                testName = "externalRouteReplacementCancelsClaimedRailStream",
                expectedWidthDp = 1_280,
                expectedHeightDp = 800,
                fontScale = 1.0f,
            )
            assertEquals(
                "An external return to the activation route must cancel the claimed pointer stream",
                listOf(Destination.Gaming),
                destinations.toList(),
            )
            assertTrue(pointer.isDown)
            pointer.up()
        } finally {
            pointer.cancelIfDown()
        }

        compose.onNodeWithTag("workspace-rail-Pos").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Pos count 0").assertIsDisplayed()
        assertEquals(listOf(Destination.Gaming, Destination.Pos), destinations.toList())
    }

    private inner class HeldPointer {
        var isDown: Boolean = true
            private set

        fun moveTo(next: Offset) {
            check(isDown) { "Cannot move a released test pointer" }
            compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
                moveTo(next)
            }
        }

        fun slideTo(next: Offset) {
            check(isDown) { "Cannot slide a released test pointer" }
            val elapsed = 32L
            check(elapsed < ViewConfiguration.getLongPressTimeout())
            compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
                advanceEventTime(elapsed)
                moveTo(next)
            }
        }

        fun activateAt(next: Offset) {
            check(isDown) { "Cannot activate a released test pointer" }
            compose.onNodeWithTag("phase-b-workspace-root").performTouchInput {
                advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 60L)
                moveTo(next)
            }
        }

        fun up() {
            if (!isDown) return
            compose.onNodeWithTag("phase-b-workspace-root").performTouchInput { up() }
            isDown = false
        }

        fun cancelIfDown() {
            if (!isDown) return
            try {
                // Cancellation is cleanup. Android may legitimately report it unhandled after the
                // original assertion has already ended the Compose gesture, so it must never mask
                // the primary test failure.
                runCatching {
                    compose.onNodeWithTag("phase-b-workspace-root").performTouchInput { cancel() }
                }
            } finally {
                isDown = false
            }
        }
    }

    private fun beginHeldPointer(position: Offset): HeldPointer {
        compose.onNodeWithTag("phase-b-workspace-root").performTouchInput { down(position) }
        return HeldPointer()
    }

    private fun nodeCenterInRoot(tag: String): Offset {
        val bounds = nodeBoundsInRootPixels(tag)
        return Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
    }

    private fun waitForStableRailTargetOutsideEdgeZone(
        targetTag: String,
        timeoutMillis: Long = 5_000L,
        requiredStableSamples: Int = 4,
    ): Offset {
        var previous: ComposeRect? = null
        var settledPoint: Offset? = null
        var stableSamples = 0
        val density = compose.activity.resources.displayMetrics.density
        val targetInsetPx = 5f * density
        val edgeZonePx = 30f * density
        compose.waitUntil(timeoutMillis) {
            val current = runCatching { nodeBoundsInRootPixels(targetTag) }.getOrNull()
                ?: return@waitUntil false
            val viewport = runCatching {
                nodeBoundsInRootPixels("workspace-navigation-rail-scrub-viewport")
            }.getOrNull() ?: return@waitUntil false
            val safeTop = maxOf(current.top + targetInsetPx, viewport.top + edgeZonePx)
            val safeBottom = minOf(current.bottom - targetInsetPx, viewport.bottom - edgeZonePx)
            val hasSafeIntersection = safeBottom >= safeTop
            stableSamples = if (hasSafeIntersection && previous?.approximatelyEquals(current) == true) {
                stableSamples + 1
            } else {
                0
            }
            previous = current
            if (stableSamples >= requiredStableSamples) {
                settledPoint = Offset(
                    x = (current.left + current.right) / 2f,
                    y = (safeTop + safeBottom) / 2f,
                )
                true
            } else {
                false
            }
        }
        val point = checkNotNull(settledPoint) {
            "$targetTag never settled with a point inside its 5 dp interior and outside the 30 dp edge zone"
        }
        val target = nodeBoundsInRootPixels(targetTag)
        val viewport = nodeBoundsInRootPixels("workspace-navigation-rail-scrub-viewport")
        assertTrue(point.x >= target.left + targetInsetPx && point.x <= target.right - targetInsetPx)
        assertTrue(point.y >= target.top + targetInsetPx && point.y <= target.bottom - targetInsetPx)
        assertTrue(point.y >= viewport.top + edgeZonePx && point.y <= viewport.bottom - edgeZonePx)
        return point
    }

    private fun ComposeRect.approximatelyEquals(other: ComposeRect): Boolean =
        kotlin.math.abs(left - other.left) <= 1f &&
            kotlin.math.abs(top - other.top) <= 1f &&
            kotlin.math.abs(right - other.right) <= 1f &&
            kotlin.math.abs(bottom - other.bottom) <= 1f

    private fun nodeBoundsInRootPixels(tag: String): ComposeRect {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        return ComposeRect(
            left = bounds.left.value * density,
            top = bounds.top.value * density,
            right = bounds.right.value * density,
            bottom = bounds.bottom.value * density,
        )
    }

    private fun render(
        width: Dp,
        height: Dp,
        fontScale: Float = 1.0f,
        appearancePreference: AppearancePreference = AppearancePreference.Night,
        outboxWorkStatus: OutboxWorkStatus = OutboxWorkStatus(),
        widthState: MutableState<Dp>? = null,
        outboxWorkStatusState: MutableState<OutboxWorkStatus>? = null,
        connectivityProblemState: MutableState<SyncAvailabilityProblem>? = null,
        syncingState: MutableState<Boolean>? = null,
        remoteSupportRequestWaitingState: MutableState<Boolean>? = null,
        remoteSupportActiveState: MutableState<Boolean>? = null,
        remoteSupportPrivacyProtectedState: MutableState<Boolean>? = null,
        onReviewRemoteSupportRequest: () -> Unit = {},
        onStopRemoteSupport: () -> Unit = {},
        drawProbe: FixtureDrawProbe? = null,
        destinationsState: MutableState<List<Destination>>? = null,
        currentDestinationState: MutableState<Destination>? = null,
        touchExplorationOverride: Boolean? = null,
        updatePresentedDestinationOnNavigation: Boolean = true,
        railRouteAuthority: ExecutiveRailRouteAuthority? = null,
        railRouteAuthorityState: MutableState<ExecutiveRailRouteAuthority?>? = null,
        railScrubTrace: ExecutiveRailScrubTraceSink? = null,
        backgroundStateState: MutableState<WorkspaceBackgroundUiState>? = null,
        workspaceSceneObserver: ((WorkspaceGlassScene?) -> Unit)? = null,
        commandDialogRootObserver: ((View) -> Unit)? = null,
        commandDialogSceneObserver: ((WorkspaceGlassScene?) -> Unit)? = null,
        restorationTester: StateRestorationTester? = null,
        onDestinationChanged: (Destination) -> Unit = {},
        onFeatureConstructed: (Destination) -> Unit = {},
    ) {
        val current = currentDestinationState ?: mutableStateOf(Destination.Dashboard)
        val fixture: @Composable () -> Unit = {
            val density = LocalDensity.current
            val suppliedAuthority = railRouteAuthorityState?.value ?: railRouteAuthority
            val fixtureAuthority = suppliedAuthority ?: remember(current, destinationsState) {
                MutableWorkspaceRouteAuthority(
                    readDestination = { current.value },
                    writeDestination = { target ->
                        if (updatePresentedDestinationOnNavigation) current.value = target
                    },
                    allowedDestinations = { destinationsState?.value ?: AllowedDestinations },
                )
            }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
                LocalExecutiveRailTouchExplorationOverride provides touchExplorationOverride,
            ) {
                DCompanyTheme(appearancePreference) {
                    Box(
                        Modifier.width(widthState?.value ?: width).height(height)
                            .testTag("phase-b-workspace-root"),
                    ) {
                        WorkspaceScaffold(
                            destinations = destinationsState?.value ?: AllowedDestinations,
                            currentDestination = current.value,
                            employeeName = "Long review employee name",
                            locationLabel = "Long review workspace name",
                            connectivityProblem = connectivityProblemState?.value ?: SyncAvailabilityProblem.NO_NETWORK,
                            outboxWorkStatus = outboxWorkStatusState?.value ?: outboxWorkStatus,
                            syncing = syncingState?.value ?: false,
                            remoteSupportRequestWaiting = remoteSupportRequestWaitingState?.value ?: false,
                            remoteSupportActive = remoteSupportActiveState?.value ?: false,
                            remoteSupportPrivacyProtected = remoteSupportPrivacyProtectedState?.value ?: true,
                            remoteSupportLastCommandLabel = "Inventory lookup completed",
                            canChangeTill = false,
                            appearancePreference = appearancePreference,
                            onOpenSupport = {},
                            onChangeTill = {},
                            onSignOut = {},
                            onReviewRemoteSupportRequest = onReviewRemoteSupportRequest,
                            onStopRemoteSupport = onStopRemoteSupport,
                            onDestinationChanged = { target ->
                                when (val result = (fixtureAuthority as? MutableWorkspaceRouteAuthority)
                                    ?.replace(target)
                                ) {
                                    is WorkspaceRouteRequestResult.Accepted -> onDestinationChanged(target)
                                    is WorkspaceRouteRequestResult.Rejected -> {
                                        if (result.reason == WorkspaceRouteRejection.SAME_DESTINATION) {
                                            onDestinationChanged(target)
                                        }
                                    }
                                    null -> Unit
                                }
                            },
                            railRouteAuthority = fixtureAuthority,
                            onRailDestinationBeforeCommit = {},
                            onRailDestinationAccepted = onDestinationChanged,
                            railScrubTrace = railScrubTrace,
                            backgroundState = backgroundStateState?.value ?: WorkspaceBackgroundUiState(),
                            commandDialogRootObserver = commandDialogRootObserver,
                            commandDialogSceneObserver = commandDialogSceneObserver,
                        ) { destination, _ ->
                            val displayedScene = LocalWorkspaceGlassScene.current
                            SideEffect { workspaceSceneObserver?.invoke(displayedScene) }
                            DisposableEffect(destination) {
                                onFeatureConstructed(destination)
                                onDispose { }
                            }
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            var lowerCount by rememberSaveable { mutableIntStateOf(0) }
                            val shell = LocalExecutiveHomeShellLayout.current
                            Box(
                                Modifier.fillMaxSize()
                                    .then(
                                        drawProbe?.modifier(
                                            destination = destination,
                                            requestWaiting = remoteSupportRequestWaitingState?.value ?: false,
                                            remoteActive = remoteSupportActiveState?.value ?: false,
                                        ) ?: Modifier,
                                    )
                                    .testTag("fixture-${destination.name}-root"),
                            ) {
                                ErpButton(
                                    text = "${destination.name} count $count",
                                    onClick = { count += 1 },
                                    modifier = Modifier
                                        .then(
                                            if (shell.enabled && destination == Destination.Dashboard) {
                                                Modifier.padding(top = shell.utilityHeight + shell.noticeHeight)
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .testTag("increment-${destination.name}"),
                                )
                                if (shell.enabled && destination == Destination.Dashboard) {
                                    ErpButton(
                                        text = "Lower action $lowerCount",
                                        onClick = { lowerCount += 1 },
                                        modifier = Modifier.align(Alignment.BottomStart)
                                            .width(shell.railWidth).height(48.dp)
                                            .testTag("home-body-pointer-action"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (restorationTester == null) {
            compose.setContent(fixture)
        } else {
            restorationTester.setContent(fixture)
        }
    }

    private fun writeSolidWorkspaceFixture(name: String, color: Int): File {
        val file = File.createTempFile(name, ".png", compose.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(1_280, 800, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun importedWorkspaceState(
        file: File,
        generation: Long,
        contentId: String,
    ): WorkspaceBackgroundUiState = WorkspaceBackgroundUiState(
        workspace = WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = 1_280,
            height = 800,
            generation = generation,
            contentId = contentId,
            privatePath = file.absolutePath,
        ),
    )

    private fun popupSample(
        rootRef: AtomicReference<View>,
        tag: String,
        xFraction: Float,
        yFraction: Float,
    ): Int {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val popupRoot = checkNotNull(rootRef.get()) { "$tag did not publish its dialog root" }
            check(popupRoot !== compose.activity.window.decorView) {
                "$tag must render in the dialog window"
            }
            val expectedDisplayId = InstrumentationRegistry.getArguments()
                .getString(TestDisplayIdArgument)?.toIntOrNull() ?: 0
            check(compose.activity.display?.displayId == expectedDisplayId) {
                "Activity must remain on requested display $expectedDisplayId"
            }
            check(popupRoot.display?.displayId == expectedDisplayId) {
                "$tag dialog must remain on requested display $expectedDisplayId"
            }
            check(popupRoot.isAttachedToWindow && popupRoot.width > 0 && popupRoot.height > 0)
            bitmap = Bitmap.createBitmap(popupRoot.width, popupRoot.height, Bitmap.Config.ARGB_8888)
            popupRoot.draw(Canvas(bitmap))
        }
        return try {
            val x = bounds.left + bounds.width * xFraction
            val y = bounds.top + bounds.height * yFraction
            check(x >= 0f && x < bitmap.width && y >= 0f && y < bitmap.height) {
                "$tag sample ($x, $y) must be inside ${bitmap.width}x${bitmap.height} dialog bitmap"
            }
            bitmap.getPixel(x.roundToInt(), y.roundToInt())
        } finally {
            bitmap.recycle()
        }
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(AndroidColor.red(first) - AndroidColor.red(second)) +
            kotlin.math.abs(AndroidColor.green(first) - AndroidColor.green(second)) +
            kotlin.math.abs(AndroidColor.blue(first) - AndroidColor.blue(second))

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val normalized = value / 255.0
                return if (normalized <= 0.04045) normalized / 12.92
                else Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(AndroidColor.red(color)) +
                0.7152 * channel(AndroidColor.green(color)) +
                0.0722 * channel(AndroidColor.blue(color))
        }
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun assertPendingHeaderControls(
        widthRange: ClosedFloatingPointRange<Float>,
        expectsDateTime: Boolean,
    ) {
        val status = compose.onNodeWithTag("outbox-work-status-slot")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .getUnclippedBoundsInRoot()
        val width = (status.right - status.left).value
        assertTrue("pending status width $width must follow the responsive policy $widthRange", width in widthRange)
        assertFullyInsideRoot("outbox-work-status-slot")
        assertFullyInsideRoot("workspace-header")
        assertTextDoesNotOverflow("workspace-outbox-label")
        if (expectsDateTime) {
            assertFullyInsideRoot("workspace-date-time")
            assertTextDoesNotOverflow("workspace-date-label")
            assertTextDoesNotOverflow("workspace-time-label")
        } else {
            compose.onNodeWithTag("workspace-date-time").assertDoesNotExist()
        }
        assertTextDoesNotOverflow("workspace-connectivity-label")
        listOf(
            "Find a module",
            "Help and support",
            "Account actions",
            "Day, night and system appearance",
            "Connection status: No internet. Open connection details",
        ).forEach(::assertContentDescriptionControl)
    }

    private fun assertFixtureCounterClearsHomeChrome() {
        val counter = compose.onNodeWithTag("increment-Dashboard").getUnclippedBoundsInRoot()
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("workspace-header").getUnclippedBoundsInRoot()
        assertTrue(
            "Dashboard fixture counter must begin at or beyond the rail edge: $counter vs $rail",
            counter.left >= rail.right,
        )
        assertTrue(
            "Dashboard fixture counter must begin at or below the utility header: $counter vs $header",
            counter.top >= header.bottom,
        )
        assertFullyInsideRoot("increment-Dashboard")
    }

    private data class ChromeSnapshot(
        val rail: androidx.compose.ui.unit.DpRect,
        val header: androidx.compose.ui.unit.DpRect,
        val logo: androidx.compose.ui.unit.DpRect,
        val itemCenters: Map<Destination, Pair<Float, Float>>,
    )

    private fun sharedChromeSnapshot(): ChromeSnapshot {
        assertEquals(1, compose.onAllNodesWithTag("workspace-navigation-rail").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("workspace-header").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("executive-home-frame").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithTag("workspace-rail-brand-mark").fetchSemanticsNodes().size)
        val centers = listOf(Destination.Dashboard, Destination.Gaming, Destination.Pos).associateWith { destination ->
            val bounds = compose.onNodeWithTag("workspace-rail-${destination.name}")
                .getUnclippedBoundsInRoot()
            ((bounds.left.value + bounds.right.value) / 2f) to
                ((bounds.top.value + bounds.bottom.value) / 2f)
        }
        return ChromeSnapshot(
            rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot(),
            header = compose.onNodeWithTag("workspace-header").getUnclippedBoundsInRoot(),
            logo = compose.onNodeWithTag("workspace-rail-brand-mark").getUnclippedBoundsInRoot(),
            itemCenters = centers,
        )
    }

    private fun assertChromeMatches(expected: ChromeSnapshot, label: String) {
        val actual = sharedChromeSnapshot()
        listOf(
            "rail left" to (expected.rail.left.value to actual.rail.left.value),
            "rail right" to (expected.rail.right.value to actual.rail.right.value),
            "rail top" to (expected.rail.top.value to actual.rail.top.value),
            "rail bottom" to (expected.rail.bottom.value to actual.rail.bottom.value),
            "header left" to (expected.header.left.value to actual.header.left.value),
            "header right" to (expected.header.right.value to actual.header.right.value),
            "header top" to (expected.header.top.value to actual.header.top.value),
            "header bottom" to (expected.header.bottom.value to actual.header.bottom.value),
            "logo left" to (expected.logo.left.value to actual.logo.left.value),
            "logo right" to (expected.logo.right.value to actual.logo.right.value),
            "logo top" to (expected.logo.top.value to actual.logo.top.value),
            "logo bottom" to (expected.logo.bottom.value to actual.logo.bottom.value),
        ).forEach { (metric, values) ->
            assertEquals("$label $metric", values.first.toDouble(), values.second.toDouble(), 1.0)
        }
        expected.itemCenters.forEach { (destination, center) ->
            val observed = checkNotNull(actual.itemCenters[destination])
            assertEquals("$label ${destination.name} center x", center.first.toDouble(), observed.first.toDouble(), 1.0)
            assertEquals("$label ${destination.name} center y", center.second.toDouble(), observed.second.toDouble(), 1.0)
        }
    }

    private fun assertOnlyRailDestinationSelected(selected: Destination) {
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
            val interaction = compose.onNodeWithTag("workspace-rail-${destination.name}")
            if (destination == selected) interaction.assertIsSelected() else interaction.assertIsNotSelected()
        }
        compose.onNodeWithTag("workspace-more-modules").assertIsNotSelected()
    }

    private fun assertRouteClearsSharedChrome(destination: Destination) {
        val route = compose.onNodeWithTag("fixture-${destination.name}-root").getUnclippedBoundsInRoot()
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        val topStack = compose.onNodeWithTag("workspace-wide-top-stack").getUnclippedBoundsInRoot()
        assertTrue("$destination route must start right of the rail: $route vs $rail", route.left >= rail.right)
        assertTrue("$destination route must start below the complete top stack: $route vs $topStack", route.top >= topStack.bottom)
    }

    private fun assertRailLogoBounds() {
        val logo = compose.onNodeWithTag("workspace-rail-brand-mark").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertEquals("rail logo width", 34.0, (logo.right - logo.left).value.toDouble(), 0.5)
        assertEquals("rail logo height", 34.0, (logo.bottom - logo.top).value.toDouble(), 0.5)
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        assertTrue("rail logo must remain inside the fixed rail: $logo vs $rail", logo.left >= rail.left && logo.right <= rail.right)
    }

    private fun assertFullHeightRailAndRightBody(destination: Destination) {
        val rail = compose.onNodeWithTag("workspace-navigation-rail").getUnclippedBoundsInRoot()
        val surface = compose.onNodeWithTag("workspace-navigation-rail-surface").getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("workspace-navigation-rail-controls").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("fixture-${destination.name}-root").getUnclippedBoundsInRoot()
        listOf("surface" to surface, "controls" to controls).forEach { (label, bounds) ->
            assertEquals("$label left", rail.left.value.toDouble(), bounds.left.value.toDouble(), 0.5)
            assertEquals("$label right", rail.right.value.toDouble(), bounds.right.value.toDouble(), 0.5)
            assertEquals("$label top", rail.top.value.toDouble(), bounds.top.value.toDouble(), 0.5)
            assertEquals("$label bottom", rail.bottom.value.toDouble(), bounds.bottom.value.toDouble(), 0.5)
        }
        assertTrue("$destination body must begin right of the rail: $body vs $rail", body.left >= rail.right)
    }

    private fun exerciseSharedChromeContinuityAndBanners(
        requestWaiting: MutableState<Boolean>,
        remoteActive: MutableState<Boolean>,
        remotePrivacyProtected: MutableState<Boolean>,
        reviewRequests: androidx.compose.runtime.MutableIntState,
        stopRequests: androidx.compose.runtime.MutableIntState,
        drawProbe: FixtureDrawProbe,
    ) {
        val baseline = sharedChromeSnapshot()
        assertOnlyRailDestinationSelected(Destination.Dashboard)
        compose.onNodeWithTag("home-body-pointer-action").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        compose.onNodeWithText("Lower action 1").assertIsDisplayed()
        val action = compose.onNodeWithTag("home-body-pointer-action").getUnclippedBoundsInRoot()
        assertTrue("Home pointer fixture must remain right of the full rail: $action vs ${baseline.rail}", action.left >= baseline.rail.right)
        assertFullHeightRailAndRightBody(Destination.Dashboard)
        assertRailLogoBounds()

        compose.onNodeWithTag("increment-Dashboard").performTouchInput { click() }
        compose.onNodeWithTag("workspace-rail-Gaming").performTouchInput { click() }
        compose.onNodeWithText("Gaming count 0").assertIsDisplayed()
        assertOnlyRailDestinationSelected(Destination.Gaming)
        assertChromeMatches(baseline, "Gaming")
        assertRouteClearsSharedChrome(Destination.Gaming)
        compose.onNodeWithTag("increment-Gaming").performTouchInput { click() }
        compose.onNodeWithText("Gaming count 1").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Pos").performTouchInput { click() }
        compose.onNodeWithText("Pos count 0").assertIsDisplayed()
        assertOnlyRailDestinationSelected(Destination.Pos)
        assertChromeMatches(baseline, "POS")
        assertRouteClearsSharedChrome(Destination.Pos)

        compose.runOnIdle {
            drawProbe.clear()
            requestWaiting.value = true
        }
        compose.onNodeWithTag("remote-assistance-request-waiting").assertIsDisplayed()
        assertRouteClearsSharedChrome(Destination.Pos)
        val stableRequestRoute = compose.onNodeWithTag("fixture-Pos-root").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            assertDrawBoundsMatch(
                description = "first request-banner draw",
                expected = stableRequestRoute,
                actual = drawProbe.first(Destination.Pos, requestWaiting = true, remoteActive = false).bounds,
            )
        }
        compose.onNodeWithTag("remote-assistance-review-request")
            .assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        assertEquals(1, reviewRequests.intValue)

        compose.runOnIdle {
            drawProbe.clear()
            remoteActive.value = true
        }
        compose.onNodeWithTag("remote-assistance-active").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Owner ERP remote assistance active. Owner can view Help only. This screen and other ERP screens are hidden.",
        ).assertIsDisplayed()
        assertRouteClearsSharedChrome(Destination.Pos)
        val stableActiveRoute = compose.onNodeWithTag("fixture-Pos-root").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            assertDrawBoundsMatch(
                description = "first active-banner draw",
                expected = stableActiveRoute,
                actual = drawProbe.first(Destination.Pos, requestWaiting = true, remoteActive = true).bounds,
            )
        }
        compose.runOnIdle { remotePrivacyProtected.value = false }
        compose.onNodeWithContentDescription(
            "Owner ERP remote assistance active. Inventory lookup completed · Owner can view Help only; other ERP screens are hidden.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("remote-assistance-stop")
            .assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        assertEquals(1, stopRequests.intValue)

        compose.runOnIdle {
            requestWaiting.value = false
            remoteActive.value = false
            remotePrivacyProtected.value = true
        }
        compose.onNodeWithTag("workspace-rail-Dashboard").performTouchInput { click() }
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
        compose.onNodeWithText("Lower action 1").assertIsDisplayed()
        assertOnlyRailDestinationSelected(Destination.Dashboard)
        assertChromeMatches(baseline, "Home return")
    }

    private fun assertDrawBoundsMatch(
        description: String,
        expected: androidx.compose.ui.unit.DpRect,
        actual: ComposeRect,
    ) {
        val density = compose.activity.resources.displayMetrics.density
        fun Float.toDpValue() = this / density
        assertEquals("$description left", expected.left.value.toDouble(), actual.left.toDpValue().toDouble(), 1.0)
        assertEquals("$description top", expected.top.value.toDouble(), actual.top.toDpValue().toDouble(), 1.0)
        assertEquals("$description right", expected.right.value.toDouble(), actual.right.toDpValue().toDouble(), 1.0)
        assertEquals("$description bottom", expected.bottom.value.toDouble(), actual.bottom.toDpValue().toDouble(), 1.0)
    }

    private fun exercisePermissionFilteredNavigationAndState() {
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Reports")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Reports count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Inventory-label", useUnmergedTree = true)
            .assertTextEquals("Inventory")
        compose.onNodeWithTag("workspace-rail-Inventory")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Inventory count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Settings")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-more-modules")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Help")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("workspace-destination-Settings").assertDoesNotExist()
        compose.onNodeWithTag("workspace-destination-Finance").assertDoesNotExist()
        compose.onNodeWithTag("workspace-destination-Help").performTouchInput { click() }
        compose.onNodeWithText("Help count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Dashboard")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()
    }

    private fun assertFullyInsideRoot(tag: String) {
        val viewport = compose.onNodeWithTag("phase-b-workspace-root")
            .getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "$tag must not be clipped: action=$action viewport=$viewport",
            action.left >= viewport.left && action.top >= viewport.top &&
                action.right <= viewport.right && action.bottom <= viewport.bottom,
        )
    }

    private fun assertRailRevealOccupiesHeaderLeadingSlot(routeActionTag: String) {
        val reveal = compose.onNodeWithTag("workspace-rail-show")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .getUnclippedBoundsInRoot()
        val slot = compose.onNodeWithTag("workspace-header-leading-slot")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("workspace-header")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val search = compose.onNodeWithTag("workspace-header-search")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val firstRouteAction = compose.onNodeWithTag(routeActionTag)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(
            "Header-leading slot must remain the reserved 56dp column: slot=$slot",
            (slot.right - slot.left) in 55.dp..57.dp,
        )
        assertTrue(
            "Reveal must be fully contained by its 56dp header-leading slot: reveal=$reveal slot=$slot",
            reveal.left >= slot.left && reveal.top >= slot.top &&
                reveal.right <= slot.right && reveal.bottom <= slot.bottom,
        )
        assertTrue(
            "Header-leading slot must stay inside the actual header: slot=$slot header=$header",
            slot.left >= header.left && slot.top >= header.top &&
                slot.right <= header.right && slot.bottom <= header.bottom,
        )
        assertTrue(
            "Reveal must not overlap Search: reveal=$reveal search=$search",
            reveal.right <= search.left || reveal.left >= search.right ||
                reveal.bottom <= search.top || reveal.top >= search.bottom,
        )
        assertTrue(
            "Reveal must not cover the first interactive route content: " +
                "reveal=$reveal routeAction=$firstRouteAction",
            reveal.right <= firstRouteAction.left || reveal.left >= firstRouteAction.right ||
                reveal.bottom <= firstRouteAction.top || reveal.top >= firstRouteAction.bottom,
        )
        assertFullyInsideRoot("workspace-rail-show")
    }

    private fun assertContentDescriptionControl(description: String) {
        compose.onNodeWithContentDescription(description)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        assertContentDescriptionInsideRoot(description)
    }

    private fun assertContentDescriptionInsideRoot(description: String) {
        val viewport = compose.onNodeWithTag("phase-b-workspace-root")
            .getUnclippedBoundsInRoot()
        val control = compose.onNodeWithContentDescription(description)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(
            "$description must not be clipped: control=$control viewport=$viewport",
            control.left >= viewport.left && control.top >= viewport.top &&
                control.right <= viewport.right && control.bottom <= viewport.bottom,
        )
    }

    private fun assertTextDoesNotOverflow(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$tag must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        val lineDiagnostics = (0 until layout.lineCount).joinToString(prefix = "[", postfix = "]") { line ->
            val lineHeightPx = layout.getLineBottom(line) - layout.getLineTop(line)
            "line=$line heightPx=$lineHeightPx end=${layout.getLineEnd(line)} " +
                "visibleEnd=${layout.getLineEnd(line, visibleEnd = true)} " +
                "ellipsized=${layout.isLineEllipsized(line)}"
        }
        assertFalse(
            "$tag must show its complete label: size=${layout.size} " +
                "lineCount=${layout.lineCount} didOverflowWidth=${layout.didOverflowWidth} " +
                "didOverflowHeight=${layout.didOverflowHeight} lines=$lineDiagnostics",
            layout.hasVisualOverflow,
        )
    }

    private fun assertSingleLineTextComplete(tag: String, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("$tag must expose one text layout", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("$tag must stay on one line", 1, layout.lineCount)
        assertFalse("$tag must not overflow horizontally", layout.didOverflowWidth)
        assertFalse("$tag must not overflow vertically", layout.didOverflowHeight)
        assertFalse("$tag must not be ellipsized", layout.isLineEllipsized(0))
        assertEquals(
            "$tag must render every character",
            expected.length,
            layout.getLineEnd(0, visibleEnd = true),
        )
    }

    private fun captureMorePanel(
        rootRef: AtomicReference<View>,
        name: String,
        testName: String,
        fontScale: Float,
    ) {
        compose.waitForIdle()
        val host = assertRequestedDisplayHost()
        val panel = compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        val panelBounds = panel.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val popupRoot = checkNotNull(rootRef.get()) { "More must publish its actual dialog root" }
            check(popupRoot !== compose.activity.window.decorView)
            check(popupRoot.display?.displayId == host.observedDisplayId)
            check(popupRoot.isAttachedToWindow && popupRoot.width > 0 && popupRoot.height > 0)
            check(panelBounds.left >= 0f && panelBounds.top >= 0f &&
                panelBounds.right <= popupRoot.width && panelBounds.bottom <= popupRoot.height) {
                "The complete More panel must fit its actual dialog window"
            }
        }
        val bitmap = panel.captureToImage().asAndroidBitmap()
        try {
            check(bitmap.width > 0 && bitmap.height > 0)
            check(bitmap.width <= host.windowBounds.width() && bitmap.height <= host.windowBounds.height())
            val image = outputDirectory().resolve("$name.png")
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(image.isFile && image.length() > 0L)
            writeProvenance(
                destination = outputDirectory().resolve("$name.provenance.json"),
                image = image,
                testName = testName,
                expectedWidthDp = null,
                expectedHeightDp = null,
                actualWidthPx = bitmap.width,
                actualHeightPx = bitmap.height,
                host = host,
                fontScale = fontScale,
                captureApi = "ComposeTestRule.onNodeWithTag(workspace-more-panel).captureToImage()",
                captureScope = "workspace-more-panel",
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureRoot(
        name: String,
        testName: String,
        expectedWidthDp: Int,
        expectedHeightDp: Int,
        fontScale: Float,
    ) {
        compose.waitForIdle()
        val host = assertRequestedDisplayHost()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val expectedWidthPx = (expectedWidthDp * host.density).roundToInt()
        val expectedHeightPx = (expectedHeightDp * host.density).roundToInt()
        try {
            assertEquals("Captured root width must match the requested viewport", expectedWidthPx, bitmap.width)
            assertEquals("Captured root height must match the requested viewport", expectedHeightPx, bitmap.height)
            assertTrue(
                "Captured root must fit the actual activity window: " +
                    "capture=${bitmap.width}x${bitmap.height} window=${host.windowBounds}",
                bitmap.width <= host.windowBounds.width() && bitmap.height <= host.windowBounds.height(),
            )

            val directory = outputDirectory()
            val image = directory.resolve("$name.png")
            image.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not encode $name.png"
                }
            }
            check(image.isFile && image.length() > 0L) { "Screenshot PNG was not written: $image" }
            writeProvenance(
                destination = directory.resolve("$name.provenance.json"),
                image = image,
                testName = testName,
                expectedWidthDp = expectedWidthDp,
                expectedHeightDp = expectedHeightDp,
                actualWidthPx = bitmap.width,
                actualHeightPx = bitmap.height,
                host = host,
                fontScale = fontScale,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeRailDiagnostic(
        name: String,
        expectation: String,
        trace: BoundedRailTrace,
        owner: DiagnosticRouteOwner,
        requests: List<Destination>,
    ) {
        val (traceEvents, droppedEvents) = trace.snapshot()
        val traceJson = traceEvents.joinToString(separator = ",") { event ->
            """{"sequence":${event.sequence},"uptime_millis":${event.uptimeMillis},"kind":"${event.kind.name}","phase":"${event.phase.name}","candidate":${event.candidate.jsonName()},"proposed_target":${event.proposedTarget.jsonName()},"local_committed":"${event.localCommitted.name}","expected_revision":${event.expectedRevision},"observed_destination":${event.observedDestination.jsonName()},"observed_revision":${event.observedRevision ?: "null"},"rejection":${event.rejection?.name.jsonString()},"scroll_delta_px":${event.scrollDeltaPx},"stop_reason":${event.stopReason?.name.jsonString()}}"""
        }
        val ownerEvents = owner.events()
        val ownerJson = ownerEvents.joinToString(separator = ",") { event ->
            """{"sequence":${event.sequence},"uptime_millis":${event.uptimeMillis},"kind":"${event.kind.name}","requested":"${event.requested.name}","resulting_destination":"${event.resultingDestination.name}","resulting_revision":${event.resultingRevision}}"""
        }
        outputDirectory().resolve("$name.json").writeText(
            """
            {
              "phase": "LIVE_RAIL_R10_1_AUTHORITY_IDENTITY",
              "expectation": "$expectation",
              "capture_run_id": "$CaptureRunId",
              "process_pid": ${android.os.Process.myPid()},
              "source_inventory_sha256": "$SourceInventorySha256",
              "target_apk_sha256": "$TargetApkSha256",
              "test_apk_sha256": "$TestApkSha256",
              "trace_event_limit": 512,
              "trace_events_dropped": $droppedEvents,
              "requests": [${requests.joinToString(",") { "\"${it.name}\"" }}],
              "owner_current": {"destination":"${owner.current().destination.name}","revision":${owner.current().revision}},
              "owner_events": [$ownerJson],
              "scrub_events": [$traceJson]
            }
            """.trimIndent() + "\n",
        )
    }

    private fun Destination?.jsonName(): String = this?.name.jsonString()
    private fun String?.jsonString(): String = this?.let { "\"$it\"" } ?: "null"

    private fun outputDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = RequestedTestDisplayId?.let { "display-$it-hd" } ?: "default-display"
        return context.filesDir
            .resolve("phase-b-workspace-screenshots-v5")
            .resolve(profile)
            .resolve(CaptureRunId)
            .apply {
                check(isDirectory || mkdirs()) { "Could not create screenshot output directory: $this" }
            }
    }

    private fun writeProvenance(
        destination: File,
        image: File,
        testName: String,
        expectedWidthDp: Int?,
        expectedHeightDp: Int?,
        actualWidthPx: Int,
        actualHeightPx: Int,
        host: HostDisplayProof,
        fontScale: Float,
        captureApi: String = "ComposeTestRule.onRoot().captureToImage()",
        captureScope: String = "workspace-root",
    ) {
        val requestedDisplayIdJson = host.requestedDisplayId?.toString() ?: "null"
        destination.writeText(
            """
            {
              "phase": "LIVE_RAIL_NAVIGATION_R10_1_AUTHORITY_IDENTITY",
              "capture_api": "$captureApi",
              "capture_scope": "$captureScope",
              "test_class": "cloud.dcompany.erp.ui.WorkspaceScaffoldUiTest",
              "test_name": "$testName",
              "test_source": "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/WorkspaceScaffoldUiTest.kt",
              "base_commit": "$BaseCommit",
              "application_id": "${BuildConfig.APPLICATION_ID}",
              "version_name": "${BuildConfig.VERSION_NAME}",
              "version_code": ${BuildConfig.VERSION_CODE},
              "device": "${Build.MANUFACTURER} ${Build.MODEL}",
              "android_api": ${Build.VERSION.SDK_INT},
              "capture_run_id": "$CaptureRunId",
              "process_pid": ${android.os.Process.myPid()},
              "source_inventory_sha256": "$SourceInventorySha256",
              "target_apk_sha256": "$TargetApkSha256",
              "test_apk_sha256": "$TestApkSha256",
              "launch_source": "${host.source}",
              "requested_display_id": $requestedDisplayIdJson,
              "observed_display_id": ${host.observedDisplayId},
              "display_mode_width_px": ${host.modeWidthPx},
              "display_mode_height_px": ${host.modeHeightPx},
              "activity_density_dpi": ${host.densityDpi},
              "activity_density": ${host.density},
              "window_bounds_left_px": ${host.windowBounds.left},
              "window_bounds_top_px": ${host.windowBounds.top},
              "window_bounds_right_px": ${host.windowBounds.right},
              "window_bounds_bottom_px": ${host.windowBounds.bottom},
              "window_width_px": ${host.windowBounds.width()},
              "window_height_px": ${host.windowBounds.height()},
              "decor_width_px": ${host.decorWidthPx},
              "decor_height_px": ${host.decorHeightPx},
              "expected_width_dp": $expectedWidthDp,
              "expected_height_dp": $expectedHeightDp,
              "actual_width_px": $actualWidthPx,
              "actual_height_px": $actualHeightPx,
              "density": ${host.density},
              "font_scale": $fontScale,
              "png_file": "${image.name}",
              "png_bytes": ${image.length()},
              "png_sha256": "${sha256(image)}"
            }
            """.trimIndent() + "\n",
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val BaseCommit = "c0c2616855815541a11c3100444f1404d71abb74"
        val CaptureRunId = InstrumentationRegistry.getArguments()
            .getString("dcompany.captureRunId")
            ?.also { require(it.matches(Regex("[0-9a-f]{32}"))) { "Invalid capture run id" } }
            ?: "${System.currentTimeMillis()}-${android.os.Process.myPid()}"

        val AllowedDestinations = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
            Destination.Reports,
            Destination.Settings,
            Destination.Help,
        )
    }
}
