package cloud.dcompany.erp.ui.theme

import android.app.ActivityOptions
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.content.Context
import android.view.FrameMetrics
import android.view.ViewConfiguration
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Popup
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.MutableWorkspaceRouteAuthority
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.screens.home.ExecutiveHomeWorkspace
import cloud.dcompany.erp.ui.screens.home.HomeUiState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val GlassDisplayArgument = "dcompany.testDisplayId"
private const val GlassSourceArgument = "dcompany.sourceInventorySha256"
private const val GlassTargetArgument = "dcompany.targetApkSha256"
private const val GlassTestArgument = "dcompany.testApkSha256"
private const val GlassRunArgument = "dcompany.captureRunId"

private val RequestedGlassDisplay: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(GlassDisplayArgument)?.let {
        val requested = it.toIntOrNull()
        require(requested in setOf(0, 2, 5, 6)) { "Only verified HD displays 0, 2, 5 and 6 are supported: $it" }
        requested
    }
}

private fun <A : ComponentActivity> ActivityScenarioRule<A>.glassActivity(): A {
    var activity: A? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity).also { current ->
        if (RequestedGlassDisplay == 0) {
            val display = checkNotNull(current.display)
            check(display.displayId == 0 && display.mode.physicalWidth == 2_560 &&
                display.mode.physicalHeight == 1_600 && current.resources.displayMetrics.densityDpi == 320) {
                "Display 0 glass proof requires the verified 2560x1600px, 320dpi host"
            }
        }
    }
}

internal fun createGlassComposeRule(): AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> {
    val rule = if (RequestedGlassDisplay == null) {
        ActivityScenarioRule(ComponentActivity::class.java)
    } else {
        ActivityScenarioRule(
            ComponentActivity::class.java,
            ActivityOptions.makeBasic().setLaunchDisplayId(RequestedGlassDisplay!!).toBundle(),
        )
    }
    return AndroidComposeTestRule(rule) { it.glassActivity() }
}

internal fun syntheticBitmapScene(
    bitmap: Bitmap,
    rootCoordinates: LayoutCoordinates,
    generation: Long,
): WorkspaceGlassScene {
    val key = WorkspaceBackgroundRenderKey(
        slot = WorkspaceBackgroundSlot.WORKSPACE,
        source = WorkspaceBackgroundSource.BUNDLED,
        generation = generation,
        contentId = "synthetic-$generation",
        viewportWidth = bitmap.width,
        viewportHeight = bitmap.height,
        decoderIdentity = 0,
    )
    return WorkspaceGlassScene(
        frame = DisplayedSceneFrame(
            actualKey = key,
            requestedKey = key,
            state = DisplayedSceneState.REQUESTED_READY,
            clearBitmap = bitmap,
            epoch = generation,
        ),
        derivative = syntheticGlassDerivative(bitmap, key, generation),
        rootCoordinates = rootCoordinates,
        rootBoundsInRoot = rootCoordinates.boundsInRoot(),
        layoutEpoch = generation,
    )
}

private fun syntheticGlassDerivative(
    bitmap: Bitmap,
    key: WorkspaceBackgroundRenderKey,
    frameEpoch: Long,
): WorkspaceGlassDerivative {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return WorkspaceGlassDerivative(
        key = WorkspaceGlassDerivativeKey(
            actualKey = key,
            frameEpoch = frameEpoch,
            algorithmVersion = WorkspaceGlassAlgorithmVersion,
            width = bitmap.width,
            height = bitmap.height,
            blurRadius = WorkspaceGlassBlurRadius,
        ),
        bitmap = bitmap,
        safetyGrid = workspaceGlassSafetyGrid(pixels, bitmap.width, bitmap.height),
    )
}

private data class MotionWorkloadResult(
    val routeTapCount: Int = 0,
    val heldRailDecisionCount: Int = 0,
    val compactScrollCount: Int = 0,
    val sceneReplacementCount: Int = 0,
    val sceneResetCount: Int = 0,
    val viewportChangeCount: Int = 0,
)

private val ExpectedMotionWorkload = MotionWorkloadResult(
    routeTapCount = 3,
    heldRailDecisionCount = 1,
    compactScrollCount = 1,
    sceneReplacementCount = 1,
    sceneResetCount = 1,
    viewportChangeCount = 3,
)

private data class MotionMemory(
    val javaUsedBytes: Long,
    val nativeAllocatedBytes: Long,
    val totalPssBytes: Long,
    val naturalGcCount: Long?,
)

private data class FrameMetricSnapshot(
    val rawTotalDurationsNs: LongArray,
    val rawFirstDrawFlags: BooleanArray,
    val callbackCount: Int,
    val storedCount: Int,
    val overflowCount: Int,
    val ignoredBeforeAdmissionCount: Int,
    val ignoredAfterAdmissionCount: Int,
    val invalidDurationCount: Int,
    val listenerErrors: Int,
    val droppedMetricReportCount: Long,
) {
    val measuredDurationsNs: LongArray
        get() = rawTotalDurationsNs.filterIndexed { index, _ -> !rawFirstDrawFlags[index] }.toLongArray()
}

private class BoundedFrameMetricsCollector(private val capacity: Int) {
    private val lock = Any()
    private val durations = LongArray(capacity)
    private val firstDraw = BooleanArray(capacity)
    private var admission = 0
    private var callbacks = 0
    private var stored = 0
    private var overflow = 0
    private var ignoredBefore = 0
    private var ignoredAfter = 0
    private var invalidDurations = 0
    private var errors = 0
    private var droppedMetricReports = 0L

    fun openAdmission() = synchronized(lock) {
        check(admission == 0) { "FrameMetrics admission was already opened" }
        admission = 1
    }

    fun closeAdmission() = synchronized(lock) {
        if (admission < 2) admission = 2
    }

    fun record(frameMetrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        synchronized(lock) {
            callbacks += 1
            droppedMetricReports += dropCountSinceLastInvocation.coerceAtLeast(0).toLong()
            when (admission) {
                0 -> {
                    ignoredBefore += 1
                    return
                }
                2 -> {
                    ignoredAfter += 1
                    return
                }
            }
            try {
                val duration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (duration <= 0L) {
                    invalidDurations += 1
                    return
                }
                if (stored >= capacity) {
                    overflow += 1
                    return
                }
                durations[stored] = duration
                firstDraw[stored] = frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) > 0L
                stored += 1
            } catch (_: RuntimeException) {
                errors += 1
            }
        }
    }

    fun snapshot(): FrameMetricSnapshot = synchronized(lock) {
        FrameMetricSnapshot(
            rawTotalDurationsNs = durations.copyOf(stored),
            rawFirstDrawFlags = firstDraw.copyOf(stored),
            callbackCount = callbacks,
            storedCount = stored,
            overflowCount = overflow,
            ignoredBeforeAdmissionCount = ignoredBefore,
            ignoredAfterAdmissionCount = ignoredAfter,
            invalidDurationCount = invalidDurations,
            listenerErrors = errors,
            droppedMetricReportCount = droppedMetricReports,
        )
    }
}

private data class SegmentFrameMetricSnapshot(
    val rawTotalDurationsNs: LongArray,
    val rawFirstDrawFlags: BooleanArray,
    val rawLayoutMeasureDurationsNs: LongArray,
    val rawDrawDurationsNs: LongArray,
    val callbackCount: Int,
    val storedCount: Int,
    val overflowCount: Int,
    val invalidDurationCount: Int,
    val listenerErrors: Int,
    val droppedMetricReportCount: Long,
) {
    val measuredDurationsNs: LongArray
        get() = rawTotalDurationsNs.filterIndexed { index, _ -> !rawFirstDrawFlags[index] }.toLongArray()
}

private class SegmentBoundedFrameMetricsCollector(private val capacity: Int) {
    private val lock = Any()
    private val nonFirstDrawSample = CountDownLatch(1)
    private val durations = LongArray(capacity)
    private val firstDraw = BooleanArray(capacity)
    private val layoutMeasureDurations = LongArray(capacity)
    private val drawDurations = LongArray(capacity)
    private var admissionOpen = false
    private var callbacks = 0
    private var stored = 0
    private var overflow = 0
    private var invalidDurations = 0
    private var errors = 0
    private var droppedMetricReports = 0L

    fun openAdmission() = synchronized(lock) {
        check(!admissionOpen)
        admissionOpen = true
    }

    fun closeAdmission() = synchronized(lock) { admissionOpen = false }

    fun record(frameMetrics: FrameMetrics, dropCountSinceLastInvocation: Int) = synchronized(lock) {
        callbacks += 1
        droppedMetricReports += dropCountSinceLastInvocation.coerceAtLeast(0).toLong()
        if (!admissionOpen) return@synchronized
        try {
            val duration = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration <= 0L) {
                invalidDurations += 1
                return@synchronized
            }
            if (stored >= capacity) {
                overflow += 1
                return@synchronized
            }
            durations[stored] = duration
            firstDraw[stored] = frameMetrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) > 0L
            layoutMeasureDurations[stored] = frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                .coerceAtLeast(0L)
            drawDurations[stored] = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L)
            if (!firstDraw[stored]) nonFirstDrawSample.countDown()
            stored += 1
        } catch (_: RuntimeException) {
            errors += 1
        }
    }

    fun awaitNonFirstDrawSample(timeout: Long, unit: TimeUnit): Boolean =
        nonFirstDrawSample.await(timeout, unit)

    fun snapshot(): SegmentFrameMetricSnapshot = synchronized(lock) {
        SegmentFrameMetricSnapshot(
            rawTotalDurationsNs = durations.copyOf(stored),
            rawFirstDrawFlags = firstDraw.copyOf(stored),
            rawLayoutMeasureDurationsNs = layoutMeasureDurations.copyOf(stored),
            rawDrawDurationsNs = drawDurations.copyOf(stored),
            callbackCount = callbacks,
            storedCount = stored,
            overflowCount = overflow,
            invalidDurationCount = invalidDurations,
            listenerErrors = errors,
            droppedMetricReportCount = droppedMetricReports,
        )
    }
}

private data class SegmentedMotionResult(
    val label: String,
    val expectedWorkload: MotionWorkloadResult,
    val completedWorkload: MotionWorkloadResult,
    val wallDurationNs: Long,
    val samples: SegmentFrameMetricSnapshot,
)

@RunWith(AndroidJUnit4::class)
class WorkspaceGlassUiTest {
    @get:Rule val compose = createGlassComposeRule()
    private val renderedScene = AtomicReference<WorkspaceGlassScene?>(null)

    @Test
    fun nightWideUsesOneReadySceneForSharedRailHeaderAndHomeMaterials() {
        render(1280, 800, 1f, AppearancePreference.Night)
        capture("glass-night-wide", 1280, 800, 1f, "Night")
        assertGlassHierarchy(wide = true)
        assertStatusAndRefreshContrast()
    }

    @Test
    fun gtaAndMirageKeepTheSameNightMaterialResponsiveWithoutPersistingASelection() {
        val gta = bundledWorkspaceBackground(
            WorkspaceBackgroundSlot.WORKSPACE,
            generation = 301,
            presetId = WorkspaceBackgroundPresets.DefaultId,
        )
        val mirage = bundledWorkspaceBackground(
            WorkspaceBackgroundSlot.WORKSPACE,
            generation = 302,
            presetId = "assassins_creed",
        )
        val state = mutableStateOf(WorkspaceBackgroundUiState(workspace = gta))
        render(1280, 800, 1f, AppearancePreference.Night, backgroundState = state)

        val gtaScene = awaitExactReadyScene(gta)
        capture("glass-finish-gta-night-wide", 1280, 800, 1f, "Night")
        assertGlassHierarchy(wide = true)
        assertExactPilotViewport(gtaScene)
        assertStatusAndRefreshContrast(bodyThreshold = 4.8, iconThreshold = 3.2)
        assertDateTimeContrast(bodyThreshold = 4.8)
        assertUnavailableCaptionContrast(bodyThreshold = 4.8)
        val gtaPanel = blankPanelMaterialSample()

        compose.runOnIdle { state.value = WorkspaceBackgroundUiState(workspace = mirage) }
        val mirageScene = awaitExactReadyScene(mirage)
        capture("glass-finish-mirage-night-wide", 1280, 800, 1f, "Night")
        assertGlassHierarchy(wide = true)
        assertExactPilotViewport(mirageScene)
        assertStatusAndRefreshContrast(bodyThreshold = 4.8, iconThreshold = 3.2)
        assertDateTimeContrast(bodyThreshold = 4.8)
        assertUnavailableCaptionContrast(bodyThreshold = 4.8)
        val miragePanel = blankPanelMaterialSample()

        assertEquals(
            "The A/B pilot must keep identical workspace-root geometry",
            gtaScene.rootBoundsInRoot,
            mirageScene.rootBoundsInRoot,
        )
        assertEquals(gtaScene.derivative?.bitmap?.width, mirageScene.derivative?.bitmap?.width)
        assertEquals(gtaScene.derivative?.bitmap?.height, mirageScene.derivative?.bitmap?.height)
        assertTrue(
            "The same neutral panel must retain scene response across GTA and Mirage: " +
                "gta=${Integer.toHexString(gtaPanel)} mirage=${Integer.toHexString(miragePanel)}",
            colorDistance(gtaPanel, miragePanel) >= 12,
        )
    }

    @Test
    fun dayWideKeepsGlassControlsAndTextReadable() {
        render(1280, 800, 1f, AppearancePreference.Day)
        capture("glass-day-wide", 1280, 800, 1f, "Day")
        assertGlassHierarchy(wide = true, day = true)
        compose.onNodeWithContentDescription("Find a module").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertStatusAndRefreshContrast()
        assertDateTimeContrast()
        assertUnavailableCaptionContrast()
    }

    @Test
    fun registeredFc27PresetNightRetainsLocalContrastForVisualPilot() {
        val state = WorkspaceBackgroundUiState(
            workspace = bundledWorkspaceBackground(
                slot = WorkspaceBackgroundSlot.WORKSPACE,
                generation = 92,
                presetId = "fc27",
            ),
        )
        render(1280, 800, 1f, AppearancePreference.Night, backgrounds = state)
        capture("glass-fc27-night-wide", 1280, 800, 1f, "Night")
        assertGlassHierarchy(wide = true)
        assertStatusAndRefreshContrast()
        assertDateTimeContrast()
        assertUnavailableCaptionContrast()
    }

    @Test
    fun registeredFc27PresetDayRetainsLocalContrastForVisualPilot() {
        val state = WorkspaceBackgroundUiState(
            workspace = bundledWorkspaceBackground(
                slot = WorkspaceBackgroundSlot.WORKSPACE,
                generation = 93,
                presetId = "fc27",
            ),
        )
        render(1280, 800, 1f, AppearancePreference.Day, backgrounds = state)
        capture("glass-fc27-day-wide", 1280, 800, 1f, "Day")
        assertGlassHierarchy(wide = true, day = true)
        assertStatusAndRefreshContrast()
        assertDateTimeContrast()
        assertUnavailableCaptionContrast()
    }

    @Test
    fun brightCustomSceneDrivesMaterialWithoutReplacingCrispChildren() {
        val cool = createControlledFixture(
            name = "cool-home.jpg",
            generation = 90,
            colors = intArrayOf(
                Color.rgb(14, 44, 220),
                Color.rgb(20, 82, 244),
                Color.rgb(36, 132, 236),
                Color.rgb(11, 53, 170),
            ),
        )
        val bright = createControlledFixture(
            name = "bright-home.jpg",
            generation = 91,
            colors = intArrayOf(
                Color.rgb(255, 252, 224),
                Color.rgb(246, 255, 75),
                Color.rgb(255, 190, 35),
                Color.rgb(238, 244, 255),
            ),
        )
        val sceneState = mutableStateOf(WorkspaceBackgroundUiState(workspace = cool))
        render(
            1280,
            800,
            1f,
            AppearancePreference.Night,
            backgroundState = sceneState,
        )
        val coolScene = awaitExactReadyScene(cool)
        assertExactPilotViewport(coolScene)
        val samples = JSONArray()
        val diagnostics = JSONObject()
            .put("test_class", "cloud.dcompany.erp.ui.theme.WorkspaceGlassUiTest")
            .put("capture_run_id", InstrumentationRegistry.getArguments().getString(GlassRunArgument))
            .put("source_inventory_sha256", InstrumentationRegistry.getArguments().getString(GlassSourceArgument))
            .put("target_apk_sha256", InstrumentationRegistry.getArguments().getString(GlassTargetArgument))
            .put("test_apk_sha256", InstrumentationRegistry.getArguments().getString(GlassTestArgument))
            .put("required_panel_distance", 30)
            .put("required_header_distance", 80)
            .put("latest_scene_distance", JSONObject.NULL)
            .put("maximum_scene_distance", JSONObject.NULL)
            .put("bright_panel_samples", samples)
        val coolSample = blankPanelMaterialSample { diagnostics.put("cool_panel", it) }
        val coolHeaderSample = blankHeaderHostSample { diagnostics.put("cool_header", it) }
        capture("glass-cool-custom", 1280, 800, 1f, "Night")
        writeMaterialSamples(diagnostics.put("stage", "COOL_CAPTURED"))

        compose.runOnIdle { sceneState.value = WorkspaceBackgroundUiState(workspace = bright) }
        var brightSample: Int? = null
        var maximumSceneDistance = -1
        var brightHeaderSample: Int? = null
        var collectionFailure: Throwable? = null
        try {
            val brightScene = awaitExactReadyScene(bright)
            assertExactPilotViewport(brightScene)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (System.nanoTime() < deadline) {
                compose.waitForIdle()
                if (compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1) {
                    val candidate = blankPanelMaterialSample { sample ->
                        check(samples.length() < 256) { "Material sample diagnostic capacity exceeded" }
                        samples.put(sample)
                    }
                    // Retain every latest candidate, including failures below the threshold.
                    brightSample = candidate
                    val distance = colorDistance(coolSample, candidate)
                    maximumSceneDistance = max(maximumSceneDistance, distance)
                    diagnostics.put("latest_scene_distance", distance)
                        .put("maximum_scene_distance", maximumSceneDistance)
                    if (distance >= 30) break
                }
                Thread.sleep(50)
            }
            brightHeaderSample = blankHeaderHostSample { diagnostics.put("bright_header", it) }
            diagnostics.put("header_scene_distance", colorDistance(coolHeaderSample, checkNotNull(brightHeaderSample)))
        } catch (error: Throwable) {
            collectionFailure = error
            throw error
        } finally {
            diagnostics.put("stage", "BRIGHT_COLLECTION_FINISHED")
                .put("sample_count", samples.length())
                .put("collection_failure", collectionFailure?.let { it.javaClass.simpleName + ": " + it.message } ?: JSONObject.NULL)
                .put("panel_threshold_met", brightSample?.let { colorDistance(coolSample, it) >= 30 } ?: false)
                .put("header_threshold_met", brightHeaderSample?.let { colorDistance(coolHeaderSample, it) >= 80 } ?: false)
            try {
                writeMaterialSamples(diagnostics)
                capture("glass-bright-custom", 1280, 800, 1f, "Night")
            } catch (diagnosticError: Throwable) {
                val original = collectionFailure
                if (original == null) throw diagnosticError else original.addSuppressed(diagnosticError)
            }
        }
        val latestBrightSample = checkNotNull(brightSample) { "No bright material candidate was sampled" }
        assertGlassHierarchy(wide = true)
        val sceneDistance = colorDistance(coolSample, latestBrightSample)
        assertTrue(
            "the same blank production material region must respond to its scene: " +
                "cool=${Integer.toHexString(coolSample)} bright=${Integer.toHexString(latestBrightSample)} " +
                "distance=$sceneDistance maximum=$maximumSceneDistance samples=${samples.length()}",
            sceneDistance >= 30,
        )
        val mutedText = workspaceGlassRoleStyle(
            WorkspaceGlassRole.PANEL,
            day = false,
        ).secondaryContent.toArgb()
        assertTrue("cool material must retain muted-text contrast", contrast(mutedText, coolSample) >= 4.5)
        assertTrue("bright material must retain muted-text contrast", contrast(mutedText, latestBrightSample) >= 4.5)
        val latestBrightHeaderSample = checkNotNull(brightHeaderSample)
        val headerSceneDistance = colorDistance(coolHeaderSample, latestBrightHeaderSample)
        assertTrue(
            "wide Home header host must expose the scene between floating controls: " +
                "cool=${Integer.toHexString(coolHeaderSample)} " +
                "bright=${Integer.toHexString(latestBrightHeaderSample)} distance=$headerSceneDistance",
            headerSceneDistance >= 80,
        )
        assertStatusAndRefreshContrast()
        assertDateTimeContrast()
        assertUnavailableCaptionContrast()
    }

    @Test
    fun hostileWhiteAndSaturatedBlueScenesKeepNightRailAndPanelReadableAtEveryVerticalGrade() {
        val hostileWhite = createControlledFixture(
            name = "hostile-white-night.jpg",
            generation = 94,
            colors = IntArray(4) { Color.WHITE },
        )
        val hostileBlue = createControlledFixture(
            name = "hostile-saturated-blue-night.jpg",
            generation = 95,
            colors = IntArray(4) { Color.rgb(22, 92, 245) },
        )
        val displayedImage = mutableStateOf(hostileWhite)
        val observedReadyKey = AtomicReference<WorkspaceGlassDerivativeKey?>(null)
        val density = compose.activity.resources.displayMetrics.density
        val roles = listOf(WorkspaceGlassRole.RAIL, WorkspaceGlassRole.PANEL)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface {
                        WorkspaceBackgroundRenderer(
                            image = displayedImage.value,
                            modifier = Modifier.width(1_280.dp).height(800.dp),
                        ) {
                            val scene = LocalWorkspaceGlassScene.current
                            SideEffect {
                                val frame = scene?.frame
                                observedReadyKey.set(
                                    if (frame != null &&
                                        frame.state == DisplayedSceneState.REQUESTED_READY &&
                                        scene.softenedKey?.actualKey == frame.actualKey &&
                                        scene.softenedKey?.frameEpoch == frame.epoch
                                    ) {
                                        scene.softenedKey
                                    } else {
                                        null
                                    },
                                )
                            }
                            Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                repeat(3) { index ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Row(
                                            modifier = Modifier.width(600.dp).height(88.dp)
                                                .workspaceGlass(
                                                    scene = scene,
                                                    role = WorkspaceGlassRole.RAIL,
                                                    day = false,
                                                )
                                                .testTag("hostile-rail-$index")
                                                .padding(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "Readable rail body $index",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.testTag("hostile-rail-body-$index"),
                                            )
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = "RAIL status $index",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(24.dp).height(24.dp)
                                                    .testTag("hostile-rail-icon-$index"),
                                            )
                                        }
                                        SectionCard(
                                            modifier = Modifier.width(600.dp).height(88.dp)
                                                .testTag("hostile-panel-$index"),
                                            title = "Actual panel $index",
                                            subtitle = "Readable panel body $index",
                                            action = {
                                                Icon(
                                                    imageVector = Icons.Filled.Star,
                                                    contentDescription = "PANEL status $index",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.width(24.dp).height(24.dp)
                                                        .testTag("hostile-panel-icon-$index"),
                                                )
                                            },
                                            contentPadding = PaddingValues(0.dp),
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun assertRenderedContrastFor(
            image: WorkspaceBackgroundImage,
            label: String,
        ): Map<Pair<WorkspaceGlassRole, Int>, Int> {
            compose.waitUntil(12_000) {
                val readyKey = observedReadyKey.get()
                readyKey?.actualKey?.contentId == image.contentId &&
                    readyKey.actualKey.generation == image.generation &&
                    compose.onAllNodesWithTag("workspace-background-ready-workspace")
                        .fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithTag("workspace-glass-ready-workspace")
                        .fetchSemanticsNodes().size == 1
            }
            return buildMap {
                roles.forEach { role ->
                    val roleName = role.name.lowercase()
                    repeat(3) { index ->
                        val material = compose.onNodeWithTag("hostile-$roleName-$index")
                            .assertIsDisplayed().captureToImage().asAndroidBitmap()
                        val text = if (role == WorkspaceGlassRole.PANEL) {
                            compose.onNodeWithText(
                                "Readable panel body $index",
                                useUnmergedTree = true,
                            )
                        } else {
                            compose.onNodeWithTag(
                                "hostile-$roleName-body-$index",
                                useUnmergedTree = true,
                            )
                        }.assertIsDisplayed().captureToImage().asAndroidBitmap()
                        val icon = compose.onNodeWithTag(
                            "hostile-$roleName-icon-$index",
                            useUnmergedTree = true,
                        ).assertIsDisplayed().captureToImage().asAndroidBitmap()
                        try {
                            val background = dominantOpaqueColor(material)
                            val bodyProof = contrastProof(text, background, 4.8)
                            val iconProof = contrastProof(icon, background, 3.2)
                            assertTrue(
                                "$label Night $role body at vertical grade $index must retain 4.8:1: " +
                                    "background=${Integer.toHexString(background)} proof=$bodyProof",
                                bodyProof.first >= 4.8 && bodyProof.second >= 8,
                            )
                            assertTrue(
                                "$label Night $role icon at vertical grade $index must retain 3.2:1: " +
                                    "background=${Integer.toHexString(background)} proof=$iconProof",
                                iconProof.first >= 3.2 && iconProof.second >= 4,
                            )
                            put(role to index, background)
                        } finally {
                            material.recycle()
                            text.recycle()
                            icon.recycle()
                        }
                    }
                }
            }
        }

        val whiteSamples = assertRenderedContrastFor(hostileWhite, "hostile white")
        compose.runOnIdle { displayedImage.value = hostileBlue }
        val blueSamples = assertRenderedContrastFor(hostileBlue, "hostile saturated blue")
        roles.forEach { role ->
            repeat(3) { index ->
                val white = checkNotNull(whiteSamples[role to index])
                val blue = checkNotNull(blueSamples[role to index])
                val distance = colorDistance(white, blue)
                assertTrue(
                    "Night $role at vertical grade $index must remain scene-responsive: " +
                        "white=${Integer.toHexString(white)} blue=${Integer.toHexString(blue)} " +
                        "distance=$distance",
                    distance >= 12,
                )
            }
        }
    }

    @Test
    fun reducedKernelKeepsThePhysicalSplitTransitionWithinTheGlass5Bound() {
        val density = compose.activity.resources.displayMetrics.density
        val image = testImage(generation = 961, contentId = "9".repeat(64))
        val observedScene = AtomicReference<WorkspaceGlassScene?>(null)
        val decoder = object : WorkspaceBackgroundDecoder {
            override fun decodeRequested(
                image: WorkspaceBackgroundImage,
                targetWidth: Int,
                targetHeight: Int,
                context: Context,
            ): Bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
                val dark = Color.rgb(12, 24, 46)
                val bright = Color.rgb(224, 238, 255)
                repeat(targetHeight) { y ->
                    val row = IntArray(targetWidth) { x -> if (x < targetWidth / 2) dark else bright }
                    setPixels(row, 0, targetWidth, 0, y, targetWidth, 1)
                }
            }

            override fun decodeBundled(
                slot: WorkspaceBackgroundSlot,
                targetWidth: Int,
                targetHeight: Int,
                context: Context,
            ): Bitmap? = null
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    WorkspaceBackgroundRenderer(
                        image = image,
                        decoder = decoder,
                        modifier = Modifier.width(1_280.dp).height(800.dp),
                    ) {
                        val scene = LocalWorkspaceGlassScene.current
                        SideEffect { observedScene.set(scene) }
                        Box(
                            Modifier.fillMaxSize()
                                .testTag("glass5-split-panel")
                                .workspaceGlass(scene, WorkspaceGlassRole.PANEL, day = false),
                        )
                    }
                }
            }
        }
        compose.waitUntil(12_000) {
            observedScene.get()?.let { scene ->
                scene.frame.state == DisplayedSceneState.REQUESTED_READY &&
                    scene.softenedKey?.actualKey == scene.frame.actualKey &&
                    scene.softenedKey?.frameEpoch == scene.frame.epoch
            } == true
        }
        val panel = compose.onNodeWithTag("glass5-split-panel")
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        try {
            assertEquals((1_280 * density).roundToInt(), panel.width)
            assertEquals((800 * density).roundToInt(), panel.height)
            val y = panel.height / 4
            fun luminanceAt(x: Int): Double {
                val pixel = panel.getPixel(x.coerceIn(0, panel.width - 1), y)
                return .2126 * Color.red(pixel) + .7152 * Color.green(pixel) + .0722 * Color.blue(pixel)
            }
            fun meanLuminance(from: Int, exclusiveEnd: Int): Double =
                (from until exclusiveEnd).map(::luminanceAt).average()
            val left = meanLuminance(panel.width / 4 - 12, panel.width / 4 + 12)
            val right = meanLuminance(panel.width * 3 / 4 - 12, panel.width * 3 / 4 + 12)
            assertTrue("The split fixture must retain two distinguishable plateaus: left=$left right=$right", right - left >= 24.0)
            val ten = left + (right - left) * .10
            val ninety = left + (right - left) * .90
            val scanStart = panel.width / 3
            val scanEnd = panel.width * 2 / 3
            val tenX = (scanStart until scanEnd).firstOrNull { luminanceAt(it) >= ten }
            val ninetyX = (scanStart until scanEnd).firstOrNull { luminanceAt(it) >= ninety }
            assertTrue("The 10% and 90% crossings must both exist: ten=$tenX ninety=$ninetyX", tenX != null && ninetyX != null)
            val transition = checkNotNull(ninetyX) - checkNotNull(tenX)

            fun interpolatedCrossing(threshold: Double): Double? {
                for (x in scanStart + 1 until scanEnd) {
                    val before = luminanceAt(x - 1)
                    val after = luminanceAt(x)
                    if (before < threshold && after >= threshold && after > before) {
                        return x - 1 + (threshold - before) / (after - before)
                    }
                }
                return null
            }

            val tenInterpolated = checkNotNull(interpolatedCrossing(ten))
            val ninetyInterpolated = checkNotNull(interpolatedCrossing(ninety))
            val interpolatedTransition = ninetyInterpolated - tenInterpolated
            assertTrue(
                "The rendered crossings must remain ordered: integer=$transition " +
                    "interpolated=$interpolatedTransition",
                transition > 0 && interpolatedTransition > 0.0,
            )
            assertTrue(
                "The five-tap derivative must keep the physical 10-90 transition near 20px: " +
                    "integer=$transition interpolated=$interpolatedTransition",
                transition in 18..22 && interpolatedTransition in 18.0..22.0,
            )

            val readyScene = checkNotNull(observedScene.get())
            val derivative = checkNotNull(readyScene.derivative)
            assertEquals(readyScene.frame.actualKey, derivative.key.actualKey)
            assertEquals(image.contentId, derivative.key.actualKey.contentId)
            assertEquals(image.generation, derivative.key.actualKey.generation)
            assertEquals(panel.width, derivative.key.actualKey.viewportWidth)
            assertEquals(panel.height, derivative.key.actualKey.viewportHeight)
            assertEquals(readyScene.frame.epoch, derivative.key.frameEpoch)
            assertEquals(506, derivative.bitmap.width)
            assertEquals(316, derivative.bitmap.height)
            assertEquals(2, derivative.key.blurRadius)

            val derivativeCenterX = derivative.bitmap.width / 2
            val derivativeY = derivative.bitmap.height / 4
            val dark = intArrayOf(12, 24, 46)
            val bright = intArrayOf(224, 238, 255)
            for (offset in -16..16) {
                val brightCount = (offset + 3).coerceIn(0, 5)
                val expected = Color.rgb(
                    (dark[0] * (5 - brightCount) + bright[0] * brightCount) / 5,
                    (dark[1] * (5 - brightCount) + bright[1] * brightCount) / 5,
                    (dark[2] * (5 - brightCount) + bright[2] * brightCount) / 5,
                )
                val actual = derivative.bitmap.getPixel(derivativeCenterX + offset, derivativeY)
                assertEquals(
                    "The published derivative must retain the exact integer-floor 5-tap edge at " +
                        "offset=$offset brightCount=$brightCount",
                    expected,
                    actual,
                )
            }

            fun bilinearProjectedSource(x: Int): Int {
                val sourceX = ((x + .5) * derivative.bitmap.width / panel.width) - .5
                val leftX = kotlin.math.floor(sourceX).toInt().coerceIn(0, derivative.bitmap.width - 1)
                val rightX = (leftX + 1).coerceAtMost(derivative.bitmap.width - 1)
                val fraction = (sourceX - kotlin.math.floor(sourceX)).coerceIn(0.0, 1.0)
                val leftPixel = derivative.bitmap.getPixel(leftX, derivativeY)
                val rightPixel = derivative.bitmap.getPixel(rightX, derivativeY)
                fun channel(left: Int, right: Int): Int =
                    (left + (right - left) * fraction).roundToInt().coerceIn(0, 255)
                return Color.rgb(
                    channel(Color.red(leftPixel), Color.red(rightPixel)),
                    channel(Color.green(leftPixel), Color.green(rightPixel)),
                    channel(Color.blue(leftPixel), Color.blue(rightPixel)),
                )
            }

            var maximumChannelDelta = 0
            var maximumDeltaX = -1
            var maximumActual = Color.TRANSPARENT
            var maximumExpected = Color.TRANSPARENT
            val fullBounds = Rect(0f, 0f, panel.width.toFloat(), panel.height.toFloat())
            for (x in panel.width / 2 - 48..panel.width / 2 + 48) {
                val expected = expectedAdaptivePanelPixel(
                    source = bilinearProjectedSource(x),
                    globalY = y + .5f,
                    viewportHeight = panel.height,
                    boundsInScene = fullBounds,
                    derivative = derivative,
                    independentlyPinnedTintAlpha = .58f,
                    independentlyPinnedGradeStrength = 1f,
                ).color
                val actual = panel.getPixel(x, y)
                val delta = maxOf(
                    abs(Color.red(actual) - Color.red(expected)),
                    abs(Color.green(actual) - Color.green(expected)),
                    abs(Color.blue(actual) - Color.blue(expected)),
                )
                if (delta > maximumChannelDelta) {
                    maximumChannelDelta = delta
                    maximumDeltaX = x
                    maximumActual = actual
                    maximumExpected = expected
                }
            }
            // Each later stage can contribute at most one 8-bit code level: filtered bitmap write,
            // scrim-alpha interpolation, scrim write, overlay write, tint premultiply and tint write.
            assertTrue(
                "The rendered profile must agree with exact 5-tap + pixel-center bilinear projection " +
                    "within the six-stage quantization budget: maxDelta=$maximumChannelDelta " +
                    "x=$maximumDeltaX actual=${Integer.toHexString(maximumActual)} " +
                    "expected=${Integer.toHexString(maximumExpected)} integerTransition=$transition " +
                    "interpolatedTransition=$interpolatedTransition",
                maximumChannelDelta <= 6,
            )
        } finally {
            panel.recycle()
        }
    }

    @Test
    fun broadNightPanelRendersOneFineDirectionalContourWithoutASecondRing() {
        val density = compose.activity.resources.displayMetrics.density
        val rootWidthPx = (400 * density).roundToInt()
        val rootHeightPx = (240 * density).roundToInt()
        val sceneBitmap = Bitmap.createBitmap(rootWidthPx, rootHeightPx, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(22, 38, 68))
        }
        val rootCoordinates = mutableStateOf<LayoutCoordinates?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                DCompanyTheme(AppearancePreference.Night) {
                    val scene = rootCoordinates.value?.let { syntheticBitmapScene(sceneBitmap, it, 971) }
                    Box(
                        Modifier.width(400.dp).height(240.dp)
                            .onGloballyPositioned {
                                if (rootCoordinates.value == null) rootCoordinates.value = it
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.width(240.dp).height(120.dp)
                                .testTag("glass5-single-contour-panel")
                                .workspaceGlass(scene, WorkspaceGlassRole.PANEL, day = false),
                        )
                    }
                }
            }
        }
        compose.waitUntil(3_000) { rootCoordinates.value != null }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("glass5-single-contour-panel")
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        try {
            val middleY = panel.height / 2
            val interior = panel.getPixel(panel.width / 2, middleY)
            val changed = (0 until minOf(8, panel.width / 4))
                .takeWhile { x -> colorDistance(panel.getPixel(x, middleY), interior) > 9 }
                .size
            assertTrue(
                "A .5dp Night neutral contour must rasterize as one 1-2px edge, not parallel rings: " +
                    "density=$density changed=$changed",
                changed in 1..2,
            )
            for (x in 3 until minOf(9, panel.width / 4)) {
                assertTrue(
                    "Interior below the contour must remain uniform within +/-3/channel at x=$x",
                    colorDistance(panel.getPixel(x, middleY), interior) <= 9,
                )
            }
            fun averageBrightness(pixels: List<Int>): Double = pixels.map { pixel ->
                (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3.0
            }.average()
            val upperLeft = buildList {
                for (x in panel.width / 10 until panel.width * 3 / 10) add(panel.getPixel(x, 0))
                for (y in panel.height / 10 until panel.height * 3 / 10) add(panel.getPixel(0, y))
            }
            val lowerRight = buildList {
                for (x in panel.width * 7 / 10 until panel.width * 9 / 10) add(panel.getPixel(x, panel.height - 1))
                for (y in panel.height * 7 / 10 until panel.height * 9 / 10) add(panel.getPixel(panel.width - 1, y))
            }
            val glint = averageBrightness(upperLeft) - averageBrightness(lowerRight)
            assertTrue("The single contour must retain the upper-left glint by >=6 sRGB levels: $glint", glint >= 6.0)
        } finally {
            panel.recycle()
            sceneBitmap.recycle()
        }
    }

    @Test
    fun compactLargeTextRetainsReachableActionsAndGlassPanels() {
        render(600, 800, 1.3f, AppearancePreference.Night)
        capture("glass-compact-large-text", 600, 800, 1.3f, "Night")
        assertGlassHierarchy(wide = false)
        compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
        compose.onNodeWithTag("home-refresh").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun pendingReplacementRetainsTruthfulClearFrameWithoutReusingOldGlass() {
        val releaseReplacement = CountDownLatch(1)
        val replacementStarted = AtomicBoolean(false)
        val first = testImage(generation = 701, contentId = "1".repeat(64))
        val second = testImage(generation = 702, contentId = "2".repeat(64))
        val image = mutableStateOf(first)
        val rememberedContentState = AtomicReference<MutableState<Int>?>(null)
        val decoder = object : WorkspaceBackgroundDecoder {
            override fun decodeRequested(
                image: WorkspaceBackgroundImage,
                targetWidth: Int,
                targetHeight: Int,
                context: Context,
            ): Bitmap? {
                if (image.generation == second.generation) {
                    replacementStarted.set(true)
                    check(releaseReplacement.await(8, TimeUnit.SECONDS))
                }
                return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(if (image.generation == first.generation) Color.rgb(190, 36, 28) else Color.rgb(24, 78, 218))
                }
            }

            override fun decodeBundled(
                slot: WorkspaceBackgroundSlot,
                targetWidth: Int,
                targetHeight: Int,
                context: Context,
            ): Bitmap? = null
        }
        try {
            compose.setContent {
                Box(Modifier.width(320.dp).height(180.dp)) {
                    WorkspaceBackgroundRenderer(
                        image = image.value,
                        decoder = decoder,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val retainedState = remember { mutableStateOf(0) }
                        SideEffect { rememberedContentState.set(retainedState) }
                        Box(Modifier.testTag("renderer-remembered-content"))
                    }
                }
            }
            compose.waitUntil(12_000) {
                compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1
            }
            val firstPixel = backgroundCenterPixel()
            compose.runOnIdle { checkNotNull(rememberedContentState.get()).value = 7 }

            compose.runOnIdle { image.value = second }
            compose.waitUntil(4_000) { replacementStarted.get() }
            compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTag("workspace-background-ready-workspace").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size)
            assertEquals("the valid clear frame must remain while replacement is pending", firstPixel, backgroundCenterPixel())
            assertEquals(
                "pending material state must retain renderer content state",
                7,
                compose.runOnIdle { checkNotNull(rememberedContentState.get()).value },
            )

            releaseReplacement.countDown()
            compose.waitUntil(12_000) {
                compose.onAllNodesWithTag("workspace-background-ready-workspace").fetchSemanticsNodes().size == 1 &&
                    compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1
            }
            assertTrue("replacement must publish a different clear frame", firstPixel != backgroundCenterPixel())
            assertEquals(
                "ready material state must not recreate renderer content",
                7,
                compose.runOnIdle { checkNotNull(rememberedContentState.get()).value },
            )
        } finally {
            releaseReplacement.countDown()
        }
    }

    @Test
    fun boundedNativeWindowMotionWorkloadPublishesCompletePrivateMetrics() {
        val sourceSha = requiredMotionArgument(GlassSourceArgument, 64)
        val targetSha = requiredMotionArgument(GlassTargetArgument, 64)
        val testSha = requiredMotionArgument(GlassTestArgument, 64)
        val runId = requiredMotionArgument(GlassRunArgument, 32)
        val activity = compose.activity
        val window = activity.window
        val windowToken = System.identityHashCode(window)
        val processStartTicks = processStartTicks()
        val display = activity.display ?: error("Host activity has no display")
        val density = activity.resources.displayMetrics.density
        val replacement = createControlledFixture(
            name = "motion-home.jpg",
            generation = 920,
            colors = intArrayOf(
                Color.rgb(20, 64, 168),
                Color.rgb(120, 46, 188),
                Color.rgb(230, 174, 36),
                Color.rgb(18, 104, 120),
            ),
        )
        val fixture = MotionFixture()
        renderMotionFixture(fixture)

        // Warm the actual scene, shared shell and route owner before listener admission.
        compose.onNodeWithTag("workspace-rail-Gaming").performClick()
        waitForMotionDestination(fixture, Destination.Gaming)
        compose.onNodeWithTag("workspace-rail-Dashboard").performClick()
        waitForMotionDestination(fixture, Destination.Dashboard)
        waitForMotionGlassReady(Destination.Dashboard)
        compose.waitForIdle()

        val memoryStart = readMotionMemory()
        val handlerThread = HandlerThread("b1-motion-frame-metrics").apply { start() }
        val handler = Handler(handlerThread.looper)
        val collector = BoundedFrameMetricsCollector(capacity = 2_048)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
            collector.record(metrics, droppedReports)
        }
        var workloadFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var listenerAdded = false
        var workload = MotionWorkloadResult()

        try {
            compose.runOnIdle {
                check(activity.window === window) { "Motion workload replaced its activity window before collection" }
                collector.openAdmission()
                window.addOnFrameMetricsAvailableListener(listener, handler)
                listenerAdded = true
            }
            workload = runMotionWorkload(fixture, replacement)
        } catch (failure: Throwable) {
            workloadFailure = failure
        } finally {
            fun retainCleanupFailure(failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
            collector.closeAdmission()
            if (listenerAdded) {
                runCatching {
                    compose.runOnIdle { window.removeOnFrameMetricsAvailableListener(listener) }
                }.onFailure(::retainCleanupFailure)
            }
            runCatching {
                val drained = CountDownLatch(1)
                check(handler.post { drained.countDown() }) { "FrameMetrics handler rejected its drain barrier" }
                check(drained.await(5, TimeUnit.SECONDS)) { "FrameMetrics handler drain timed out" }
            }.onFailure(::retainCleanupFailure)
            runCatching {
                check(handlerThread.quitSafely()) { "FrameMetrics handler refused quitSafely" }
                handlerThread.join(5_000L)
                check(!handlerThread.isAlive) { "FrameMetrics handler remained alive after bounded join" }
            }.onFailure(::retainCleanupFailure)
        }

        compose.waitForIdle()
        val memoryEnd = readMotionMemory()
        val samples = collector.snapshot()
        val outcomeReason = when {
            workloadFailure != null -> "WORKLOAD_FAILURE"
            cleanupFailure != null -> "CLEANUP_FAILURE"
            activity.window !== window || System.identityHashCode(activity.window) != windowToken -> "WINDOW_REPLACED"
            samples.listenerErrors != 0 -> "LISTENER_FAILURE"
            samples.measuredDurationsNs.isEmpty() -> "NO_MEASURED_SAMPLES"
            samples.overflowCount != 0 -> "SAMPLE_OVERFLOW"
            samples.droppedMetricReportCount != 0L -> "CALLBACK_DELIVERY_LOSS"
            samples.invalidDurationCount != 0 -> "INVALID_DURATION"
            workload != ExpectedMotionWorkload -> "WORKLOAD_COUNT_MISMATCH"
            else -> "NONE"
        }
        val complete = outcomeReason == "NONE"
        val record = buildMotionRecord(
            complete = complete,
            reason = outcomeReason,
            sourceSha = sourceSha,
            targetSha = targetSha,
            testSha = testSha,
            runId = runId,
            windowToken = windowToken,
            processStartTicks = processStartTicks,
            displayId = display.displayId,
            displayWidth = display.mode.physicalWidth,
            displayHeight = display.mode.physicalHeight,
            density = density,
            densityDpi = activity.resources.displayMetrics.densityDpi,
            refreshRate = display.refreshRate,
            workload = workload,
            samples = samples,
            memoryStart = memoryStart,
            memoryEnd = memoryEnd,
        )
        emitMotionRecord(record, runId, sourceSha, targetSha, testSha)

        workloadFailure?.let { failure ->
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
        cleanupFailure?.let { throw it }
        assertEquals("The bounded motion workload must complete exactly", ExpectedMotionWorkload, workload)
        assertTrue("FrameMetrics evidence must be complete: $outcomeReason", complete)
    }

    @Test
    fun unchangedPatternedSceneTracksSamePanelAcrossScrollAndRejectsSeparateRootCrop() {
        val density = compose.activity.resources.displayMetrics.density
        val rootWidthPx = (320 * density).roundToInt()
        val rootHeightPx = (480 * density).roundToInt()
        val panelWidthPx = (180 * density).roundToInt()
        val panelHeightPx = (96 * density).roundToInt()
        val scrollDeltaPx = (140 * density).roundToInt()
        val partialScrollDeltaPx = (268 * density).roundToInt()
        val pattern = createGlassCropPattern(rootWidthPx, rootHeightPx)
        val key = WorkspaceBackgroundRenderKey(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.BUNDLED,
            generation = 0,
            contentId = "diagnostic-pattern",
            viewportWidth = rootWidthPx,
            viewportHeight = rootHeightPx,
            decoderIdentity = 0,
        )
        val patternDerivative = syntheticGlassDerivative(pattern, key, 1)
        val sceneState = mutableStateOf<WorkspaceGlassScene?>(null)
        val popupCoordinates = AtomicReference<LayoutCoordinates?>(null)
        lateinit var scrollState: ScrollState
        lateinit var popupGraphicsLayer: GraphicsLayer
        compose.setContent {
            val rememberedScroll = rememberScrollState()
            val rememberedPopupLayer = rememberGraphicsLayer()
            SideEffect { scrollState = rememberedScroll }
            SideEffect { popupGraphicsLayer = rememberedPopupLayer }
            DCompanyTheme(AppearancePreference.Night) {
                Box(
                    Modifier.width(320.dp).height(480.dp)
                        .onGloballyPositioned { coordinates ->
                            sceneState.value = WorkspaceGlassScene(
                                frame = DisplayedSceneFrame(
                                    actualKey = key,
                                    requestedKey = key,
                                    state = DisplayedSceneState.REQUESTED_READY,
                                    clearBitmap = pattern,
                                    epoch = 1,
                                ),
                                derivative = patternDerivative,
                                rootCoordinates = coordinates,
                                rootBoundsInRoot = coordinates.boundsInRoot(),
                                layoutEpoch = 1,
                            )
                        }
                        .testTag("glass-crop-root"),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberedScroll),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(220.dp))
                        Box(
                            Modifier.width(180.dp).height(96.dp)
                                .testTag("glass-crop-panel")
                                .workspaceGlass(sceneState.value, WorkspaceGlassRole.PANEL, day = false),
                        )
                        Spacer(Modifier.height(560.dp))
                    }
                    Popup {
                        Box(
                            Modifier.width(180.dp).height(96.dp)
                                .testTag("glass-crop-separate-root-panel")
                                .onGloballyPositioned { popupCoordinates.set(it) }
                                .drawWithContent {
                                    rememberedPopupLayer.record {
                                        this@drawWithContent.drawContent()
                                    }
                                    drawLayer(rememberedPopupLayer)
                                }
                                .workspaceGlass(sceneState.value, WorkspaceGlassRole.PANEL, day = false),
                        )
                    }
                }
            }
        }
        compose.waitUntil(3_000) { sceneState.value != null }
        compose.waitForIdle()

        var first: Bitmap? = null
        var moved: Bitmap? = null
        var returned: Bitmap? = null
        var partiallyClipped: Bitmap? = null
        var separateRoot: Bitmap? = null
        try {
            val rootBounds = compose.onNodeWithTag("glass-crop-root").fetchSemanticsNode().boundsInRoot
            val firstBounds = compose.onNodeWithTag("glass-crop-panel").fetchSemanticsNode().boundsInRoot
            first = compose.onNodeWithTag("glass-crop-panel").captureToImage().asAndroidBitmap()
            val popupSnapshot = runBlocking { popupGraphicsLayer.toImageBitmap() }.asAndroidBitmap()
            separateRoot = if (popupSnapshot.config == Bitmap.Config.HARDWARE) {
                try {
                    checkNotNull(popupSnapshot.copy(Bitmap.Config.ARGB_8888, false)) {
                        "Popup layer snapshot could not be copied for synchronous pixel readback"
                    }
                } finally {
                    // toImageBitmap owns this snapshot independently of the recorded GraphicsLayer.
                    popupSnapshot.recycle()
                }
            } else {
                popupSnapshot
            }
            val separateRootsConfirmed = compose.runOnIdle {
                val host = checkNotNull(sceneState.value?.rootCoordinates)
                val popup = checkNotNull(popupCoordinates.get())
                host.isAttached && popup.isAttached &&
                    host.findRootCoordinates() !== popup.findRootCoordinates()
            }

            compose.runOnIdle {
                val consumed = scrollState.dispatchRawDelta(scrollDeltaPx.toFloat())
                check(abs(consumed - scrollDeltaPx) <= 1f) { "Diagnostic scroll was not fully consumed: $consumed" }
            }
            compose.waitForIdle()
            val movedBounds = compose.onNodeWithTag("glass-crop-panel").fetchSemanticsNode().boundsInRoot
            moved = compose.onNodeWithTag("glass-crop-panel").captureToImage().asAndroidBitmap()

            compose.runOnIdle {
                val consumed = scrollState.dispatchRawDelta(-scrollDeltaPx.toFloat())
                check(abs(consumed + scrollDeltaPx) <= 1f) { "Diagnostic return scroll was not fully consumed: $consumed" }
            }
            compose.waitForIdle()
            val returnedBounds = compose.onNodeWithTag("glass-crop-panel").fetchSemanticsNode().boundsInRoot
            returned = compose.onNodeWithTag("glass-crop-panel").captureToImage().asAndroidBitmap()

            compose.runOnIdle {
                val consumed = scrollState.dispatchRawDelta(partialScrollDeltaPx.toFloat())
                check(abs(consumed - partialScrollDeltaPx) <= 1f) {
                    "Diagnostic partial clip scroll was not fully consumed: $consumed"
                }
            }
            compose.waitForIdle()
            val partialBounds = compose.onNodeWithTag("glass-crop-panel").fetchSemanticsNode().boundsInRoot
            partiallyClipped = compose.onNodeWithTag("glass-crop-panel").captureToImage().asAndroidBitmap()

            compose.runOnIdle {
                val consumed = scrollState.dispatchRawDelta(-partialScrollDeltaPx.toFloat())
                check(abs(consumed + partialScrollDeltaPx) <= 1f) {
                    "Diagnostic partial clip return was not fully consumed: $consumed"
                }
            }
            compose.waitForIdle()

            val firstPixel = centerPixel(checkNotNull(first))
            val movedPixel = centerPixel(checkNotNull(moved))
            val returnedPixel = centerPixel(checkNotNull(returned))
            val partialPixel = centerPixel(checkNotNull(partiallyClipped))
            val separatePixel = centerPixel(checkNotNull(separateRoot))
            val style = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = false)
            val fallback = workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = false).fallback.toArgb()
            val firstSceneBounds = Rect(
                left = firstBounds.left - rootBounds.left,
                top = firstBounds.top - rootBounds.top,
                right = firstBounds.right - rootBounds.left,
                bottom = firstBounds.bottom - rootBounds.top,
            )
            fun shiftedSceneBounds(deltaY: Int) = Rect(
                left = firstSceneBounds.left,
                top = firstSceneBounds.top - deltaY,
                right = firstSceneBounds.right,
                bottom = firstSceneBounds.bottom - deltaY,
            )
            val firstExpectation = expectedAdaptivePanelPixel(
                source = glassCropPatternColor(firstBounds.center.y - rootBounds.top, rootHeightPx),
                globalY = firstBounds.center.y - rootBounds.top,
                viewportHeight = rootHeightPx,
                boundsInScene = firstSceneBounds,
                derivative = patternDerivative,
            )
            val movedExpectation = expectedAdaptivePanelPixel(
                source = glassCropPatternColor(movedBounds.center.y - rootBounds.top, rootHeightPx),
                globalY = movedBounds.center.y - rootBounds.top,
                viewportHeight = rootHeightPx,
                boundsInScene = shiftedSceneBounds(scrollDeltaPx),
                derivative = patternDerivative,
            )
            val partialExpectation = expectedAdaptivePanelPixel(
                source = glassCropPatternColor(partialBounds.center.y - rootBounds.top, rootHeightPx),
                globalY = partialBounds.center.y - rootBounds.top,
                viewportHeight = rootHeightPx,
                boundsInScene = shiftedSceneBounds(partialScrollDeltaPx),
                derivative = patternDerivative,
            )
            val expectedFallback = compositeColor(fallback, style.tint.toArgb())
            val sameSize = listOf(first, moved, returned).all {
                it.width == panelWidthPx && it.height == panelHeightPx
            }
            val movedByScroll = abs((firstBounds.top - movedBounds.top) - scrollDeltaPx) <= 1f
            val returnedToOrigin = abs(firstBounds.top - returnedBounds.top) <= 1f
            val firstMatches = colorDistance(firstPixel, firstExpectation.color) <= 12
            val movedMatches = colorDistance(movedPixel, movedExpectation.color) <= 12
            val returnedMatches = colorDistance(returnedPixel, firstExpectation.color) <= 12
            val respondsToCrop = colorDistance(firstPixel, movedPixel) >= 36
            val clippedHeight = abs(partialBounds.height - panelHeightPx / 2f) <= 1f
            val clippedAtHostTop = abs(partialBounds.top - rootBounds.top) <= 1f
            val partialMatchesAbsoluteScene = colorDistance(partialPixel, partialExpectation.color) <= 12
            val separateRootUsesFallback = colorDistance(separatePixel, expectedFallback) <= 12
            assertTrue(
                "Scene-relative crop diagnostic failed: sameSize=$sameSize movedByScroll=$movedByScroll " +
                    "returnedToOrigin=$returnedToOrigin firstMatches=$firstMatches movedMatches=$movedMatches " +
                    "returnedMatches=$returnedMatches respondsToCrop=$respondsToCrop " +
                    "clippedHeight=$clippedHeight clippedAtHostTop=$clippedAtHostTop " +
                    "partialMatchesAbsoluteScene=$partialMatchesAbsoluteScene " +
                    "separateRootsConfirmed=$separateRootsConfirmed " +
                    "separateRootUsesFallback=$separateRootUsesFallback first=$firstPixel moved=$movedPixel " +
                    "returned=$returnedPixel partial=$partialPixel separate=$separatePixel " +
                    "admittedAlpha(first=${firstExpectation.alpha}, moved=${movedExpectation.alpha}, " +
                    "partial=${partialExpectation.alpha})",
                sameSize && movedByScroll && returnedToOrigin && firstMatches && movedMatches &&
                    returnedMatches && respondsToCrop && clippedHeight && clippedAtHostTop &&
                    partialMatchesAbsoluteScene && separateRootsConfirmed && separateRootUsesFallback,
            )
        } finally {
            first?.recycle()
            moved?.recycle()
            returned?.recycle()
            partiallyClipped?.recycle()
            separateRoot?.recycle()
        }
    }

    @Test
    fun explicitWindowProjectionLetsPopupTrackTheControlledScene() {
        val density = compose.activity.resources.displayMetrics.density
        val rootWidthPx = (320 * density).roundToInt()
        val rootHeightPx = (240 * density).roundToInt()
        val dark = createGlassCropPattern(rootWidthPx, rootHeightPx)
        val bright = Bitmap.createBitmap(rootWidthPx, rootHeightPx, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(246, 210, 44))
        }
        val bitmap = mutableStateOf(dark)
        val rootCoordinates = mutableStateOf<LayoutCoordinates?>(null)
        val popupCoordinates = AtomicReference<LayoutCoordinates?>(null)
        val observedScene = AtomicReference<WorkspaceGlassScene?>(null)
        val drawnGeneration = AtomicLong(0L)
        lateinit var popupLayer: GraphicsLayer
        compose.setContent {
            val rememberedPopupLayer = rememberGraphicsLayer()
            SideEffect { popupLayer = rememberedPopupLayer }
            val scene = rootCoordinates.value?.let { coordinates ->
                syntheticBitmapScene(
                    bitmap = bitmap.value,
                    rootCoordinates = coordinates,
                    generation = if (bitmap.value === dark) 1 else 2,
                )
            }
            SideEffect { observedScene.set(scene) }
            DCompanyTheme(AppearancePreference.Night) {
                CompositionLocalProvider(LocalWorkspaceGlassScene provides scene) {
                    Box(
                        Modifier.width(320.dp).height(240.dp)
                            .onGloballyPositioned { coordinates ->
                                if (rootCoordinates.value == null) rootCoordinates.value = coordinates
                            }
                            .testTag("glass-window-projection-root"),
                    ) {
                        Popup {
                            Box(
                                Modifier.width(180.dp).height(96.dp)
                                    .testTag("glass-window-projection-popup")
                                    .onGloballyPositioned(popupCoordinates::set)
                                    .drawWithContent {
                                        rememberedPopupLayer.record {
                                            this@drawWithContent.drawContent()
                                        }
                                        drawLayer(rememberedPopupLayer)
                                        drawnGeneration.set(scene?.frame?.actualKey?.generation ?: 0L)
                                    }
                                    .workspaceGlass(
                                        scene = scene,
                                        role = WorkspaceGlassRole.PANEL,
                                        day = false,
                                        allowWindowProjection = true,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(3_000) {
            rootCoordinates.value != null && popupCoordinates.get() != null &&
                observedScene.get()?.derivative?.key?.actualKey?.generation == 1L &&
                drawnGeneration.get() == 1L
        }
        compose.waitForIdle()

        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            var projectedCenter = Offset.Unspecified
            var projectedBounds = Rect.Zero
            val separateRoots = compose.runOnIdle {
                val host = checkNotNull(rootCoordinates.value)
                val popup = checkNotNull(popupCoordinates.get())
                val hostOrigin = host.localToScreen(Offset.Zero)
                val corners = arrayOf(
                    popup.localToScreen(Offset.Zero) - hostOrigin,
                    popup.localToScreen(Offset(popup.size.width.toFloat(), 0f)) - hostOrigin,
                    popup.localToScreen(Offset(0f, popup.size.height.toFloat())) - hostOrigin,
                    popup.localToScreen(Offset(popup.size.width.toFloat(), popup.size.height.toFloat())) - hostOrigin,
                )
                projectedBounds = Rect(
                    left = corners.minOf { it.x },
                    top = corners.minOf { it.y },
                    right = corners.maxOf { it.x },
                    bottom = corners.maxOf { it.y },
                )
                projectedCenter = projectedBounds.center
                host.findRootCoordinates() !== popup.findRootCoordinates()
            }
            val firstDerivative = checkNotNull(observedScene.get()?.derivative)
            first = graphicsLayerSnapshot(popupLayer)
            compose.runOnIdle { bitmap.value = bright }
            compose.waitUntil(3_000) {
                observedScene.get()?.frame?.actualKey?.generation == 2L &&
                    observedScene.get()?.derivative?.key?.actualKey?.generation == 2L &&
                    drawnGeneration.get() == 2L
            }
            val secondDerivative = checkNotNull(observedScene.get()?.derivative)
            second = graphicsLayerSnapshot(popupLayer)

            val firstPixel = centerPixel(checkNotNull(first))
            val secondPixel = centerPixel(checkNotNull(second))
            val distance = colorDistance(firstPixel, secondPixel)
            val pixelTolerance = 12
            val firstExpectation = expectedAdaptivePanelPixel(
                source = glassCropPatternColor(projectedCenter.y, rootHeightPx),
                globalY = projectedCenter.y,
                viewportHeight = rootHeightPx,
                boundsInScene = projectedBounds,
                derivative = firstDerivative,
            )
            val secondExpectation = expectedAdaptivePanelPixel(
                source = bright.getPixel(
                    projectedCenter.x.roundToInt().coerceIn(0, rootWidthPx - 1),
                    projectedCenter.y.roundToInt().coerceIn(0, rootHeightPx - 1),
                ),
                globalY = projectedCenter.y,
                viewportHeight = rootHeightPx,
                boundsInScene = projectedBounds,
                derivative = secondDerivative,
            )
            val expectedFirst = firstExpectation.color
            val expectedSecond = secondExpectation.color
            val expectedDistance = colorDistance(expectedFirst, expectedSecond)
            assertTrue("Projection fixture must exercise separate window roots", separateRoots)
            assertTrue(
                "Projected popup center must remain inside the controlled host scene: $projectedCenter",
                projectedCenter.x in 0f..rootWidthPx.toFloat() &&
                    projectedCenter.y in 0f..rootHeightPx.toFloat(),
            )
            assertTrue(
                "Window projection must use the host-relative screen crop: " +
                    "actual=$firstPixel expected=$expectedFirst center=$projectedCenter",
                colorDistance(firstPixel, expectedFirst) <= pixelTolerance,
            )
            assertTrue(
                "Window projection must grade the replacement scene at the same projected center: " +
                    "actual=$secondPixel expected=$expectedSecond center=$projectedCenter",
                colorDistance(secondPixel, expectedSecond) <= pixelTolerance,
            )
            assertTrue(
                "Controlled scenes must predict distinguishable projected material: " +
                    "expectedFirst=$expectedFirst expectedSecond=$expectedSecond " +
                    "expectedDistance=$expectedDistance tolerance=$pixelTolerance",
                expectedDistance > pixelTolerance * 2,
            )
            assertTrue(
                "Explicit window projection must follow the modelled scene change: " +
                    "first=$firstPixel second=$secondPixel actualDistance=$distance " +
                    "expectedDistance=$expectedDistance admittedAlpha(first=${firstExpectation.alpha}, " +
                    "second=${secondExpectation.alpha})",
                abs(distance - expectedDistance) <= pixelTolerance * 2,
            )
        } finally {
            first?.recycle()
            second?.recycle()
        }
    }

    @Test
    fun segmentedNativeWindowMotionDiagnosticSeparatesBoundedWorkloads() {
        val sourceSha = requiredMotionArgument(GlassSourceArgument, 64)
        val targetSha = requiredMotionArgument(GlassTargetArgument, 64)
        val testSha = requiredMotionArgument(GlassTestArgument, 64)
        val runId = requiredMotionArgument(GlassRunArgument, 32)
        val activity = compose.activity
        val window = activity.window
        val windowToken = System.identityHashCode(window)
        val display = activity.display ?: error("Host activity has no display")
        val processStartTicks = processStartTicks()
        val replacement = createControlledFixture(
            name = "segmented-motion-home.jpg",
            generation = 921,
            colors = intArrayOf(
                Color.rgb(28, 78, 178),
                Color.rgb(136, 52, 194),
                Color.rgb(236, 180, 44),
                Color.rgb(24, 112, 132),
            ),
        )
        val fixture = MotionFixture()
        renderMotionFixture(fixture)

        // Warm both slot paths and the ordinary Home shell before any segment is admitted.
        compose.onNodeWithTag("workspace-rail-Gaming").performClick()
        waitForMotionDestination(fixture, Destination.Gaming)
        compose.onNodeWithTag("workspace-rail-Dashboard").performClick()
        waitForMotionDestination(fixture, Destination.Dashboard)
        compose.waitForIdle()

        val handlerThread = HandlerThread("b1-motion-segments").apply { start() }
        val handler = Handler(handlerThread.looper)
        val activeCollector = AtomicReference<SegmentBoundedFrameMetricsCollector?>(null)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
            activeCollector.get()?.record(metrics, droppedReports)
        }
        val segments = mutableListOf<SegmentedMotionResult>()
        var failure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var listenerAdded = false
        try {
            compose.runOnIdle {
                check(activity.window === window)
                window.addOnFrameMetricsAvailableListener(listener, handler)
                listenerAdded = true
            }
            val ordinaryExpected = MotionWorkloadResult(routeTapCount = 21, heldRailDecisionCount = 1)
            segments += collectMotionSegment(
                label = "ordinary_route_and_held_rail",
                expected = ordinaryExpected,
                activeCollector = activeCollector,
                handler = handler,
            ) {
                var taps = 0
                repeat(10) {
                    compose.onNodeWithTag("workspace-rail-Gaming").performClick()
                    waitForMotionDestination(fixture, Destination.Gaming)
                    taps += 1
                    compose.onNodeWithTag("workspace-rail-Dashboard").performClick()
                    waitForMotionDestination(fixture, Destination.Dashboard)
                    taps += 1
                }
                performMotionHeldRailDecision(fixture, Destination.Pos)
                compose.onNodeWithTag("workspace-rail-Dashboard").performClick()
                waitForMotionDestination(fixture, Destination.Dashboard)
                taps += 1
                MotionWorkloadResult(routeTapCount = taps, heldRailDecisionCount = 1)
            }

            segments += collectMotionSegment(
                label = "viewport_to_compact",
                expected = MotionWorkloadResult(viewportChangeCount = 1),
                activeCollector = activeCollector,
                handler = handler,
            ) {
                compose.runOnIdle {
                    fixture.widthDp.value = 600
                    fixture.heightDp.value = 800
                    fixture.fontScale.value = 1.3f
                }
                compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
                waitForMotionGlassReady(Destination.Dashboard)
                MotionWorkloadResult(viewportChangeCount = 1)
            }

            // Warm this scroll path before measuring it, independently of the compact resize.
            compose.onNodeWithTag("home-recent-activity").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("home-hero").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            segments += collectMotionSegment(
                label = "warmed_compact_scroll",
                expected = MotionWorkloadResult(compactScrollCount = 6),
                activeCollector = activeCollector,
                handler = handler,
            ) {
                repeat(3) {
                    compose.onNodeWithTag("home-recent-activity").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithTag("home-hero").performScrollTo().assertIsDisplayed()
                }
                MotionWorkloadResult(compactScrollCount = 6)
            }

            segments += collectMotionSegment(
                label = "scene_replacement",
                expected = MotionWorkloadResult(sceneReplacementCount = 1),
                activeCollector = activeCollector,
                handler = handler,
            ) {
                compose.runOnIdle { fixture.backgrounds.value = WorkspaceBackgroundUiState(workspace = replacement) }
                waitForMotionGlassReady(Destination.Dashboard)
                check(fixture.backgrounds.value.workspace?.contentId == replacement.contentId)
                MotionWorkloadResult(sceneReplacementCount = 1)
            }
            segments += collectMotionSegment(
                label = "scene_reset",
                expected = MotionWorkloadResult(sceneResetCount = 1),
                activeCollector = activeCollector,
                handler = handler,
            ) {
                compose.runOnIdle { fixture.backgrounds.value = WorkspaceBackgroundUiState() }
                waitForMotionGlassReady(Destination.Dashboard)
                MotionWorkloadResult(sceneResetCount = 1)
            }
            segments += collectMotionSegment(
                label = "viewport_to_master_aspect",
                expected = MotionWorkloadResult(viewportChangeCount = 1),
                activeCollector = activeCollector,
                handler = handler,
            ) {
                compose.runOnIdle {
                    fixture.widthDp.value = 1_280
                    fixture.heightDp.value = 563
                    fixture.fontScale.value = 1f
                }
                compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
                waitForMotionGlassReady(Destination.Dashboard)
                MotionWorkloadResult(viewportChangeCount = 1)
            }
            segments += collectMotionSegment(
                label = "viewport_to_wide",
                expected = MotionWorkloadResult(viewportChangeCount = 1),
                activeCollector = activeCollector,
                handler = handler,
            ) {
                compose.runOnIdle { fixture.heightDp.value = 800 }
                compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
                waitForMotionGlassReady(Destination.Dashboard)
                MotionWorkloadResult(viewportChangeCount = 1)
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            activeCollector.getAndSet(null)?.closeAdmission()
            fun retainCleanupFailure(caught: Throwable) {
                cleanupFailure?.addSuppressed(caught) ?: run { cleanupFailure = caught }
            }
            if (listenerAdded) {
                runCatching { compose.runOnIdle { window.removeOnFrameMetricsAvailableListener(listener) } }
                    .onFailure(::retainCleanupFailure)
            }
            runCatching { drainFrameMetricsHandler(handler) }.onFailure(::retainCleanupFailure)
            runCatching {
                check(handlerThread.quitSafely())
                handlerThread.join(5_000L)
                check(!handlerThread.isAlive) { "Segmented FrameMetrics handler did not terminate" }
            }.onFailure(::retainCleanupFailure)
        }

        val expectedLabels = listOf(
            "ordinary_route_and_held_rail",
            "viewport_to_compact",
            "warmed_compact_scroll",
            "scene_replacement",
            "scene_reset",
            "viewport_to_master_aspect",
            "viewport_to_wide",
        )
        val ordinarySamples = segments.firstOrNull { it.label == "ordinary_route_and_held_rail" }
            ?.samples?.measuredDurationsNs?.size ?: 0
        val outcomeReason = when {
            failure != null -> "WORKLOAD_FAILURE"
            cleanupFailure != null -> "CLEANUP_FAILURE"
            activity.window !== window || System.identityHashCode(activity.window) != windowToken -> "WINDOW_REPLACED"
            segments.map { it.label } != expectedLabels -> "SEGMENT_SET_MISMATCH"
            segments.any { it.expectedWorkload != it.completedWorkload } -> "WORKLOAD_COUNT_MISMATCH"
            segments.any { it.samples.listenerErrors != 0 } -> "LISTENER_FAILURE"
            segments.any { it.samples.measuredDurationsNs.isEmpty() } -> "NO_MEASURED_SAMPLES"
            segments.any { it.samples.overflowCount != 0 } -> "SAMPLE_OVERFLOW"
            segments.any { it.samples.droppedMetricReportCount != 0L } -> "CALLBACK_DELIVERY_LOSS"
            segments.any { it.samples.invalidDurationCount != 0 } -> "INVALID_DURATION"
            ordinarySamples < 20 -> "ORDINARY_SAMPLE_COUNT_TOO_SMALL"
            else -> "NONE"
        }
        val complete = outcomeReason == "NONE"
        val record = buildSegmentedMotionRecord(
            complete = complete,
            reason = outcomeReason,
            sourceSha = sourceSha,
            targetSha = targetSha,
            testSha = testSha,
            runId = runId,
            windowToken = windowToken,
            processStartTicks = processStartTicks,
            displayId = display.displayId,
            displayWidth = display.mode.physicalWidth,
            displayHeight = display.mode.physicalHeight,
            density = activity.resources.displayMetrics.density,
            densityDpi = activity.resources.displayMetrics.densityDpi,
            refreshRate = display.refreshRate,
            segments = segments,
        )
        emitSegmentedMotionRecord(record, runId, sourceSha, targetSha, testSha)
        failure?.let { caught -> cleanupFailure?.let(caught::addSuppressed); throw caught }
        cleanupFailure?.let { throw it }
        assertTrue("Segmented FrameMetrics evidence must be complete: $outcomeReason", complete)
    }

    private inner class MotionFixture {
        val widthDp = mutableStateOf(1_280)
        val heightDp = mutableStateOf(800)
        val fontScale = mutableStateOf(1f)
        val currentDestination = mutableStateOf(Destination.Dashboard)
        val backgrounds = mutableStateOf(WorkspaceBackgroundUiState())
        val allowedDestinations = listOf(
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
        )
        val authority = MutableWorkspaceRouteAuthority(
            readDestination = { currentDestination.value },
            writeDestination = { currentDestination.value = it },
            allowedDestinations = { allowedDestinations },
        )
    }

    private fun renderMotionFixture(fixture: MotionFixture) {
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fixture.fontScale.value)) {
                DCompanyTheme(AppearancePreference.Night) {
                    Surface {
                        Box(
                            Modifier.width(fixture.widthDp.value.dp).height(fixture.heightDp.value.dp)
                                .testTag("glass-motion-root"),
                        ) {
                            WorkspaceScaffold(
                                destinations = fixture.allowedDestinations,
                                currentDestination = fixture.currentDestination.value,
                                employeeName = "Motion Fixture",
                                locationLabel = "Test Workspace",
                                connectivityProblem = SyncAvailabilityProblem.NONE,
                                outboxWorkStatus = OutboxWorkStatus(),
                                syncing = false,
                                canChangeTill = false,
                                appearancePreference = AppearancePreference.Night,
                                onOpenSupport = {},
                                onChangeTill = {},
                                onSignOut = {},
                                onDestinationChanged = { fixture.authority.replace(it) },
                                railRouteAuthority = fixture.authority,
                                backgroundState = fixture.backgrounds.value,
                            ) { destination, navigateTo ->
                                if (destination == Destination.Dashboard) {
                                    ExecutiveHomeWorkspace(
                                        employeeName = "Motion Fixture",
                                        locationLabel = "Test Workspace",
                                        state = HomeUiState(loading = false),
                                        destinations = fixture.allowedDestinations,
                                        navigateTo = navigateTo,
                                        onRetry = {},
                                        hour = 18,
                                    )
                                } else {
                                    Box(
                                        Modifier.fillMaxSize()
                                            .testTag("glass-motion-route-${destination.name}"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForMotionGlassReady(Destination.Dashboard)
    }

    private fun runMotionWorkload(
        fixture: MotionFixture,
        replacement: WorkspaceBackgroundImage,
    ): MotionWorkloadResult {
        var routeTaps = 0
        var heldRailDecisions = 0
        var compactScrolls = 0
        var sceneReplacements = 0
        var sceneResets = 0
        var viewportChanges = 0

        compose.onNodeWithTag("workspace-rail-Gaming").performClick()
        waitForMotionDestination(fixture, Destination.Gaming)
        routeTaps += 1
        compose.onNodeWithTag("workspace-rail-Dashboard").performClick()
        waitForMotionDestination(fixture, Destination.Dashboard)
        waitForMotionGlassReady(Destination.Dashboard)
        routeTaps += 1

        val dashboard = motionNodeCenter("workspace-rail-Dashboard")
        val pos = motionNodeCenter("workspace-rail-Pos")
        var pointerDown = false
        try {
            compose.onNodeWithTag("glass-motion-root").performTouchInput { down(dashboard) }
            pointerDown = true
            compose.onNodeWithTag("glass-motion-root").performTouchInput {
                advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 60L)
                moveTo(pos)
            }
            compose.waitUntil(4_000) { fixture.currentDestination.value == Destination.Pos }
            compose.onNodeWithTag("glass-motion-route-Pos").assertIsDisplayed()
            waitForMotionGlassReady(Destination.Pos)
            check(pointerDown) { "Held-rail route changed only after pointer release" }
            heldRailDecisions += 1
            compose.onNodeWithTag("glass-motion-root").performTouchInput { up() }
            pointerDown = false
        } finally {
            if (pointerDown) {
                runCatching { compose.onNodeWithTag("glass-motion-root").performTouchInput { cancel() } }
            }
        }

        compose.onNodeWithTag("workspace-rail-Dashboard").performClick()
        waitForMotionDestination(fixture, Destination.Dashboard)
        waitForMotionGlassReady(Destination.Dashboard)
        routeTaps += 1

        compose.runOnIdle {
            fixture.widthDp.value = 600
            fixture.heightDp.value = 800
            fixture.fontScale.value = 1.3f
        }
        viewportChanges += 1
        compose.onNodeWithTag("workspace-bottom-dock").assertIsDisplayed()
        val beforeScroll = compose.onNodeWithTag("home-recent-activity")
            .fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("home-recent-activity").performScrollTo().assertIsDisplayed()
        val afterScroll = compose.onNodeWithTag("home-recent-activity")
            .fetchSemanticsNode().boundsInRoot.top
        check(kotlin.math.abs(beforeScroll - afterScroll) >= densityPixels(1f)) {
            "Compact Home scroll did not move its target: before=$beforeScroll after=$afterScroll"
        }
        compactScrolls += 1

        compose.runOnIdle {
            fixture.backgrounds.value = WorkspaceBackgroundUiState(workspace = replacement)
        }
        waitForMotionGlassReady(Destination.Dashboard)
        check(fixture.backgrounds.value.workspace?.contentId == replacement.contentId)
        sceneReplacements += 1

        compose.runOnIdle { fixture.backgrounds.value = WorkspaceBackgroundUiState() }
        waitForMotionGlassReady(Destination.Dashboard)
        sceneResets += 1

        compose.runOnIdle {
            fixture.widthDp.value = 1_280
            fixture.heightDp.value = 563
            fixture.fontScale.value = 1f
        }
        viewportChanges += 1
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        waitForMotionGlassReady(Destination.Dashboard)

        compose.runOnIdle { fixture.heightDp.value = 800 }
        viewportChanges += 1
        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        waitForMotionGlassReady(Destination.Dashboard)
        compose.waitForIdle()

        return MotionWorkloadResult(
            routeTapCount = routeTaps,
            heldRailDecisionCount = heldRailDecisions,
            compactScrollCount = compactScrolls,
            sceneReplacementCount = sceneReplacements,
            sceneResetCount = sceneResets,
            viewportChangeCount = viewportChanges,
        )
    }

    private fun collectMotionSegment(
        label: String,
        expected: MotionWorkloadResult,
        activeCollector: AtomicReference<SegmentBoundedFrameMetricsCollector?>,
        handler: Handler,
        workload: () -> MotionWorkloadResult,
    ): SegmentedMotionResult {
        compose.waitForIdle()
        drainFrameMetricsHandler(handler)
        val collector = SegmentBoundedFrameMetricsCollector(capacity = 256)
        collector.openAdmission()
        check(activeCollector.compareAndSet(null, collector)) { "Another FrameMetrics segment is active" }
        val started = SystemClock.elapsedRealtimeNanos()
        var completed = MotionWorkloadResult()
        var failure: Throwable? = null
        Trace.beginSection("DCompany/B1/$label")
        try {
            completed = workload()
            compose.waitForIdle()
            collector.awaitNonFirstDrawSample(5, TimeUnit.SECONDS)
            drainFrameMetricsHandler(handler)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            Trace.endSection()
            collector.closeAdmission()
            check(activeCollector.compareAndSet(collector, null)) { "FrameMetrics segment owner changed" }
            drainFrameMetricsHandler(handler)
        }
        val result = SegmentedMotionResult(
            label = label,
            expectedWorkload = expected,
            completedWorkload = completed,
            wallDurationNs = SystemClock.elapsedRealtimeNanos() - started,
            samples = collector.snapshot(),
        )
        failure?.let { throw it }
        return result
    }

    private fun drainFrameMetricsHandler(handler: Handler) {
        val drained = CountDownLatch(1)
        check(handler.post { drained.countDown() }) { "FrameMetrics handler rejected its boundary barrier" }
        check(drained.await(5, TimeUnit.SECONDS)) { "FrameMetrics handler boundary drain timed out" }
    }

    private fun performMotionHeldRailDecision(fixture: MotionFixture, target: Destination) {
        val dashboard = motionNodeCenter("workspace-rail-Dashboard")
        val targetCenter = motionNodeCenter("workspace-rail-${target.name}")
        var pointerDown = false
        try {
            compose.onNodeWithTag("glass-motion-root").performTouchInput { down(dashboard) }
            pointerDown = true
            compose.onNodeWithTag("glass-motion-root").performTouchInput {
                advanceEventTime(ViewConfiguration.getLongPressTimeout().toLong() + 60L)
                moveTo(targetCenter)
            }
            compose.waitUntil(4_000) { fixture.currentDestination.value == target }
            compose.onNodeWithTag("glass-motion-route-${target.name}").assertIsDisplayed()
            waitForMotionGlassReady(target)
            compose.onNodeWithTag("glass-motion-root").performTouchInput { up() }
            pointerDown = false
        } finally {
            if (pointerDown) {
                runCatching { compose.onNodeWithTag("glass-motion-root").performTouchInput { cancel() } }
            }
        }
    }

    private fun waitForMotionDestination(fixture: MotionFixture, expected: Destination) {
        compose.waitUntil(4_000) { fixture.currentDestination.value == expected }
        val tag = if (expected == Destination.Dashboard) {
            "executive-home-workspace"
        } else {
            "glass-motion-route-${expected.name}"
        }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        waitForMotionGlassReady(expected)
    }

    private fun waitForMotionGlassReady(destination: Destination) {
        compose.waitForIdle()
        val slot = workspaceBackgroundSlot(destination).name.lowercase()
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("workspace-background-ready-$slot").fetchSemanticsNodes().size == 1 &&
                compose.onAllNodesWithTag("workspace-glass-ready-$slot").fetchSemanticsNodes().size == 1
        }
    }

    private fun motionNodeCenter(tag: String): Offset {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Offset(bounds.center.x, bounds.center.y)
    }

    private fun densityPixels(dp: Float): Float =
        dp * compose.activity.resources.displayMetrics.density

    private fun requiredMotionArgument(name: String, length: Int): String {
        val value = InstrumentationRegistry.getArguments().getString(name)
            ?: error("Missing required motion argument: $name")
        require(value.matches(Regex("[0-9a-f]{$length}"))) { "Invalid $name" }
        return value
    }

    private fun processStartTicks(): Long {
        val stat = File("/proc/self/stat").readText()
        val commandEnd = stat.lastIndexOf(')')
        check(commandEnd > 0) { "Process start identity is unavailable" }
        val fieldsFromState = stat.substring(commandEnd + 2).split(' ')
        return fieldsFromState.getOrNull(19)?.toLongOrNull()
            ?: error("Process start identity is malformed")
    }

    private fun readMotionMemory(): MotionMemory {
        val runtime = Runtime.getRuntime()
        val activityManager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(ActivityManager::class.java)
            ?: error("ActivityManager is unavailable")
        val info = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid())).single()
        return MotionMemory(
            javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            totalPssBytes = info.totalPss.toLong() * 1_024L,
            naturalGcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull(),
        )
    }

    private fun buildMotionRecord(
        complete: Boolean,
        reason: String,
        sourceSha: String,
        targetSha: String,
        testSha: String,
        runId: String,
        windowToken: Int,
        processStartTicks: Long,
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int,
        density: Float,
        densityDpi: Int,
        refreshRate: Float,
        workload: MotionWorkloadResult,
        samples: FrameMetricSnapshot,
        memoryStart: MotionMemory,
        memoryEnd: MotionMemory,
    ): JSONObject {
        val measured = samples.measuredDurationsNs.sortedArray()
        val refreshBudgetNs = if (refreshRate > 0f) (1_000_000_000.0 / refreshRate).roundToInt().toLong()
        else 0L
        val firstDrawCount = samples.rawFirstDrawFlags.count { it }
        val percentileEvidenceComplete = complete && measured.isNotEmpty() &&
            samples.overflowCount == 0 && samples.droppedMetricReportCount == 0L
        fun percentile(percent: Double): Long {
            if (measured.isEmpty()) return 0L
            val rank = ceil(percent * measured.size).toInt().coerceIn(1, measured.size)
            return measured[rank - 1]
        }
        fun countOver(thresholdNs: Long): Int = measured.count { it > thresholdNs }
        fun workloadJson(value: MotionWorkloadResult): JSONObject = JSONObject()
            .put("route_taps", value.routeTapCount)
            .put("held_rail_decisions", value.heldRailDecisionCount)
            .put("compact_scrolls", value.compactScrollCount)
            .put("scene_replacements", value.sceneReplacementCount)
            .put("scene_resets", value.sceneResetCount)
            .put("viewport_changes", value.viewportChangeCount)
        fun memoryJson(value: MotionMemory): JSONObject = JSONObject()
            .put("java_used_bytes", value.javaUsedBytes)
            .put("native_allocated_bytes", value.nativeAllocatedBytes)
            .put("total_pss_bytes", value.totalPssBytes)
            .put("natural_gc_count", value.naturalGcCount ?: JSONObject.NULL)
        return JSONObject()
            .put("schema", "dcompany.motion.v1")
            .put("workload_version", "workspace_glass_combined_v1")
            .put("outcome", if (complete) "complete" else "incomplete")
            .put("incomplete_reason", reason)
            .put("capture_run_id", runId)
            .put("source_inventory_sha256", sourceSha)
            .put("target_apk_sha256", targetSha)
            .put("test_apk_sha256", testSha)
            .put("process_pid", Process.myPid())
            .put("process_start_ticks", processStartTicks)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("display", JSONObject()
                .put("id", displayId)
                .put("mode_width_px", displayWidth)
                .put("mode_height_px", displayHeight)
                .put("density", density.toDouble())
                .put("density_dpi", densityDpi)
                .put("device_font_scale", compose.activity.resources.configuration.fontScale.toDouble())
                .put("fixture_wide_font_scale", 1.0)
                .put("fixture_compact_font_scale", 1.3)
                .put("refresh_hz", refreshRate.toDouble())
                .put("refresh_budget_ns", refreshBudgetNs))
            .put("window_identity_token", windowToken)
            .put("warmup", JSONObject()
                .put("completed", true)
                .put("route_taps", 2)
                .put("matching_scene_ready", true)
                .put("frame_collection_started_after_warmup", true))
            .put("workload_expected", workloadJson(ExpectedMotionWorkload))
            .put("workload_completed", workloadJson(workload))
            .put("frames", JSONObject()
                .put("sample_capacity", 2_048)
                .put("callback_count", samples.callbackCount)
                .put("stored_count", samples.storedCount)
                .put("measured_count", measured.size)
                .put("first_draw_count", firstDrawCount)
                .put("overflow_count", samples.overflowCount)
                .put("ignored_before_admission_count", samples.ignoredBeforeAdmissionCount)
                .put("ignored_after_admission_count", samples.ignoredAfterAdmissionCount)
                .put("invalid_duration_count", samples.invalidDurationCount)
                .put("listener_error_count", samples.listenerErrors)
                .put("dropped_metric_report_count", samples.droppedMetricReportCount)
                .put("percentile_evidence_complete", percentileEvidenceComplete)
                .put("p50_total_duration_ns", percentile(0.50))
                .put("p95_total_duration_ns", percentile(0.95))
                .put("p99_total_duration_ns", percentile(0.99))
                .put("max_total_duration_ns", measured.lastOrNull() ?: 0L)
                .put("over_display_budget_count", if (refreshBudgetNs > 0L) countOver(refreshBudgetNs) else 0)
                .put("over_16666667ns_count", countOver(16_666_667L))
                .put("over_33333333ns_count", countOver(33_333_333L)))
            .put("memory", JSONObject()
                .put("diagnostic_only", true)
                .put("forced_gc", false)
                .put("leak_free_claim", false)
                .put("whole_process_budget_claim", false)
                .put("start", memoryJson(memoryStart))
                .put("end", memoryJson(memoryEnd))
                .put("delta_java_used_bytes", memoryEnd.javaUsedBytes - memoryStart.javaUsedBytes)
                .put("delta_native_allocated_bytes", memoryEnd.nativeAllocatedBytes - memoryStart.nativeAllocatedBytes)
                .put("delta_total_pss_bytes", memoryEnd.totalPssBytes - memoryStart.totalPssBytes))
            .put("claim_scope", "debuggable_api35_combined_shared_shell_home_diagnostic")
    }

    private fun emitMotionRecord(
        record: JSONObject,
        runId: String,
        sourceSha: String,
        targetSha: String,
        testSha: String,
    ) {
        val encoded = record.toString()
        check(encoded.toByteArray(Charsets.UTF_8).size <= 8 * 1_024) {
            "Motion status record exceeds 8 KiB"
        }
        val parsed = JSONObject(encoded)
        check(parsed.getString("schema") == "dcompany.motion.v1")
        check(parsed.getString("capture_run_id") == runId)
        check(parsed.getString("source_inventory_sha256") == sourceSha)
        check(parsed.getString("target_apk_sha256") == targetSha)
        check(parsed.getString("test_apk_sha256") == testSha)
        check(parsed.getInt("process_pid") == Process.myPid())
        check(parsed.getJSONObject("frames").getInt("sample_capacity") <= 2_048)
        check(parsed.getJSONObject("workload_expected").toString() ==
            parsed.getJSONObject("workload_completed").toString() || parsed.getString("outcome") == "incomplete")
        check(!encoded.contains("/data/") && !encoded.contains("Motion Fixture") && !encoded.contains("Test Workspace"))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("dcompany.motion.v1", encoded) },
        )
    }

    private fun buildSegmentedMotionRecord(
        complete: Boolean,
        reason: String,
        sourceSha: String,
        targetSha: String,
        testSha: String,
        runId: String,
        windowToken: Int,
        processStartTicks: Long,
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int,
        density: Float,
        densityDpi: Int,
        refreshRate: Float,
        segments: List<SegmentedMotionResult>,
    ): JSONObject {
        fun workloadJson(value: MotionWorkloadResult): JSONObject = JSONObject()
            .put("route_taps", value.routeTapCount)
            .put("held_rail_decisions", value.heldRailDecisionCount)
            .put("compact_scrolls", value.compactScrollCount)
            .put("scene_replacements", value.sceneReplacementCount)
            .put("scene_resets", value.sceneResetCount)
            .put("viewport_changes", value.viewportChangeCount)
        fun percentile(values: LongArray, percent: Double): Long {
            if (values.isEmpty()) return 0L
            val ordered = values.sortedArray()
            val rank = ceil(percent * ordered.size).toInt().coerceIn(1, ordered.size)
            return ordered[rank - 1]
        }
        val segmentJson = JSONArray()
        segments.forEach { segment ->
            val measuredIndices = segment.samples.rawFirstDrawFlags.indices
                .filter { !segment.samples.rawFirstDrawFlags[it] }
            val measured = measuredIndices.map { segment.samples.rawTotalDurationsNs[it] }.toLongArray()
            val layout = measuredIndices.map { segment.samples.rawLayoutMeasureDurationsNs[it] }.toLongArray()
            val draw = measuredIndices.map { segment.samples.rawDrawDurationsNs[it] }.toLongArray()
            segmentJson.put(JSONObject()
                .put("label", segment.label)
                .put("workload_expected", workloadJson(segment.expectedWorkload))
                .put("workload_completed", workloadJson(segment.completedWorkload))
                .put("wall_duration_ns", segment.wallDurationNs)
                .put("callback_count", segment.samples.callbackCount)
                .put("stored_count", segment.samples.storedCount)
                .put("measured_count", measured.size)
                .put("first_draw_count", segment.samples.rawFirstDrawFlags.count { it })
                .put("overflow_count", segment.samples.overflowCount)
                .put("invalid_duration_count", segment.samples.invalidDurationCount)
                .put("listener_error_count", segment.samples.listenerErrors)
                .put("dropped_metric_report_count", segment.samples.droppedMetricReportCount)
                .put("total_duration_ns", measured.sum())
                .put("p50_total_duration_ns", percentile(measured, .50))
                .put("p95_total_duration_ns", percentile(measured, .95))
                .put("max_total_duration_ns", measured.maxOrNull() ?: 0L)
                .put("layout_measure_total_ns", layout.sum())
                .put("p95_layout_measure_ns", percentile(layout, .95))
                .put("draw_total_ns", draw.sum())
                .put("p95_draw_ns", percentile(draw, .95)))
        }
        return JSONObject()
            .put("schema", "dcompany.motion.segments.v1")
            .put("version", 1)
            .put("workload_version", "workspace_glass_segmented_v1")
            .put("outcome", if (complete) "complete" else "incomplete")
            .put("incomplete_reason", reason)
            .put("capture_run_id", runId)
            .put("source_inventory_sha256", sourceSha)
            .put("target_apk_sha256", targetSha)
            .put("test_apk_sha256", testSha)
            .put("process_pid", Process.myPid())
            .put("process_start_ticks", processStartTicks)
            .put("android_api", Build.VERSION.SDK_INT)
            .put("window_identity_token", windowToken)
            .put("display", JSONObject()
                .put("id", displayId)
                .put("mode_width_px", displayWidth)
                .put("mode_height_px", displayHeight)
                .put("density", density.toDouble())
                .put("density_dpi", densityDpi)
                .put("refresh_hz", refreshRate.toDouble()))
            .put("collection", JSONObject()
                .put("segment_count_expected", 7)
                .put("segment_count_observed", segments.size)
                .put("sample_capacity_per_segment", 256)
                .put("sample_capacity_total", 1_792)
                .put("ordinary_minimum_measured_samples", 20)
                .put("first_draw_separated", true)
                .put("admission_drained_at_boundaries", true)
                .put("performance_threshold_asserted", false))
            .put("segments", segmentJson)
            .put("total_wall_duration_ns", segments.sumOf { it.wallDurationNs })
            .put("claim_scope", "debuggable_api35_segmented_shared_shell_home_diagnostic")
    }

    private fun emitSegmentedMotionRecord(
        record: JSONObject,
        runId: String,
        sourceSha: String,
        targetSha: String,
        testSha: String,
    ) {
        val encoded = record.toString()
        check(encoded.toByteArray(Charsets.UTF_8).size <= 8 * 1_024) {
            "Segmented motion status record exceeds 8 KiB"
        }
        val parsed = JSONObject(encoded)
        check(parsed.getString("schema") == "dcompany.motion.segments.v1")
        check(parsed.getInt("version") == 1)
        check(parsed.getString("capture_run_id") == runId)
        check(parsed.getString("source_inventory_sha256") == sourceSha)
        check(parsed.getString("target_apk_sha256") == targetSha)
        check(parsed.getString("test_apk_sha256") == testSha)
        check(parsed.getInt("process_pid") == Process.myPid())
        check(parsed.getJSONObject("collection").getInt("sample_capacity_total") <= 2_048)
        check(parsed.getJSONArray("segments").length() <= 7)
        check(!encoded.contains("/data/") && !encoded.contains("Motion Fixture") &&
            !encoded.contains("Test Workspace"))
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("dcompany.motion.segments.v1", encoded) },
        )
    }

    private fun render(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        appearance: AppearancePreference,
        backgrounds: WorkspaceBackgroundUiState = WorkspaceBackgroundUiState(),
        backgroundState: MutableState<WorkspaceBackgroundUiState>? = null,
    ) {
        renderedScene.set(null)
        val density = compose.activity.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                DCompanyTheme(appearance) {
                    Surface {
                        Box(Modifier.width(widthDp.dp).height(heightDp.dp)) {
                            WorkspaceScaffold(
                                destinations = listOf(
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
                                currentDestination = Destination.Dashboard,
                                employeeName = "Nasih",
                                locationLabel = "Main Shop",
                                connectivityProblem = SyncAvailabilityProblem.NONE,
                                outboxWorkStatus = OutboxWorkStatus(),
                                syncing = false,
                                canChangeTill = false,
                                appearancePreference = appearance,
                                onOpenSupport = {},
                                onChangeTill = {},
                                onSignOut = {},
                                backgroundState = backgroundState?.value ?: backgrounds,
                            ) { _, navigateTo ->
                                val scene = LocalWorkspaceGlassScene.current
                                SideEffect { renderedScene.set(scene) }
                                ExecutiveHomeWorkspace(
                                    employeeName = "Nasih",
                                    locationLabel = "Main Shop",
                                    state = HomeUiState(loading = false),
                                    destinations = listOf(
                                        Destination.Dashboard,
                                        Destination.Gaming,
                                        Destination.Pos,
                                        Destination.Shift,
                                        Destination.Customers,
                                        Destination.Inventory,
                                        Destination.Reports,
                                        Destination.Analytics,
                                    ),
                                    navigateTo = navigateTo,
                                    onRetry = {},
                                    hour = 18,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(12_000) {
            compose.onAllNodesWithTag("workspace-glass-ready-workspace").fetchSemanticsNodes().size == 1
        }
    }

    private fun awaitExactReadyScene(image: WorkspaceBackgroundImage): WorkspaceGlassScene {
        compose.waitUntil(12_000) {
            renderedScene.get()?.let { scene ->
                val frame = scene.frame
                frame.state == DisplayedSceneState.REQUESTED_READY &&
                    frame.actualKey.generation == image.generation &&
                    frame.actualKey.contentId == image.contentId &&
                    frame.actualKey.viewportWidth > 0 &&
                    frame.actualKey.viewportHeight > 0 &&
                    scene.softenedKey?.actualKey == frame.actualKey &&
                    scene.softenedKey?.frameEpoch == frame.epoch
            } == true
        }
        return checkNotNull(renderedScene.get()).also { scene ->
            assertEquals(image.generation, scene.frame.actualKey.generation)
            assertEquals(image.contentId, scene.frame.actualKey.contentId)
            assertEquals(scene.frame.actualKey, scene.derivative?.key?.actualKey)
            assertEquals(scene.frame.epoch, scene.derivative?.key?.frameEpoch)
        }
    }

    private fun assertExactPilotViewport(scene: WorkspaceGlassScene) {
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(2_560, (1_280 * density).roundToInt())
        assertEquals(1_600, (800 * density).roundToInt())
        val frameBounds = compose.onNodeWithTag("executive-home-frame")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val expectedWidth = (1_264 * density).roundToInt()
        val expectedHeight = (784 * density).roundToInt()
        assertEquals(2_528, expectedWidth)
        assertEquals(1_568, expectedHeight)
        assertEquals(expectedWidth.toFloat(), frameBounds.width, 1f)
        assertEquals(expectedHeight.toFloat(), frameBounds.height, 1f)
        assertEquals(expectedWidth, scene.frame.actualKey.viewportWidth)
        assertEquals(expectedHeight, scene.frame.actualKey.viewportHeight)
        assertEquals(frameBounds.left, scene.rootBoundsInRoot.left, 1f)
        assertEquals(frameBounds.top, scene.rootBoundsInRoot.top, 1f)
        assertEquals(frameBounds.right, scene.rootBoundsInRoot.right, 1f)
        assertEquals(frameBounds.bottom, scene.rootBoundsInRoot.bottom, 1f)
        assertEquals(scene.frame.actualKey, scene.derivative?.key?.actualKey)
        assertEquals(scene.frame.epoch, scene.derivative?.key?.frameEpoch)
    }

    private fun assertGlassHierarchy(wide: Boolean, day: Boolean = false) {
        compose.onNodeWithTag("workspace-background-bitmap-workspace").assertIsDisplayed()
        compose.onNodeWithTag("workspace-glass-ready-workspace").assertIsDisplayed()
        compose.onNodeWithTag("workspace-header").assertIsDisplayed()
        compose.onNodeWithTag("home-hero").assertIsDisplayed()
        compose.onNodeWithText("Good evening, Nasih").assertIsDisplayed()
        if (wide) {
            compose.onNodeWithText("Customer count unavailable").assertIsDisplayed()
            compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
            compose.onNodeWithTag("home-kpi-row").assertIsDisplayed()
            compose.onNodeWithTag("home-recent-activity").assertIsDisplayed()
            assertSelectedRailOutline(day)
        } else {
            compose.onNodeWithTag("home-shortcut-Customers").performScrollTo().assertIsDisplayed()
                .assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    private fun testImage(generation: Long, contentId: String) = WorkspaceBackgroundImage(
        slot = WorkspaceBackgroundSlot.WORKSPACE,
        source = WorkspaceBackgroundSource.IMPORTED,
        width = 320,
        height = 180,
        generation = generation,
        contentId = contentId,
        privatePath = "/private/test-only/$generation.jpg",
    )

    private fun backgroundCenterPixel(): Int {
        val bitmap = compose.onNodeWithTag("workspace-background-bitmap-workspace").captureToImage().asAndroidBitmap()
        return try {
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createControlledFixture(
        name: String,
        generation: Long,
        colors: IntArray,
    ): WorkspaceBackgroundImage {
        require(colors.size == 4)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir.resolve("workspace-glass-fixtures").apply { mkdirs() }
        val file = directory.resolve(name)
        val bitmap = Bitmap.createBitmap(2560, 1080, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            val row = IntArray(bitmap.width) { x ->
                colors[(x / 128 + y / 96) % colors.size]
            }
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


    /** Samples the text-free bottom padding of the real Recent activity glass panel. */
    private fun blankPanelMaterialSample(onSample: ((JSONObject) -> Unit)? = null): Int {
        val node = compose.onNodeWithTag("home-recent-activity")
        val bounds = if (onSample != null) node.fetchSemanticsNode().boundsInRoot else null
        val panel = node.captureToImage().asAndroidBitmap()
        return try {
            val density = compose.activity.resources.displayMetrics.density
            val halfWidth = max(2, (6 * density).roundToInt())
            val sampleHeight = max(2, (3 * density).roundToInt())
            val centerX = panel.width / 2
            val bottomInset = max(sampleHeight + 2, (7 * density).roundToInt())
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0
            for (y in (panel.height - bottomInset - sampleHeight) until (panel.height - bottomInset)) {
                for (x in (centerX - halfWidth) until (centerX + halfWidth)) {
                    val pixel = panel.getPixel(x, y)
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    count += 1
                }
            }
            val color = Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
            if (onSample != null) {
                onSample(materialSampleEvidence(
                    "home-recent-activity", panel, checkNotNull(bounds),
                    centerX - halfWidth, panel.height - bottomInset - sampleHeight,
                    centerX + halfWidth, panel.height - bottomInset, color,
                ))
            }
            color
        } finally {
            panel.recycle()
        }
    }

    private fun blankHeaderHostSample(onSample: ((JSONObject) -> Unit)? = null): Int {
        val node = compose.onNodeWithTag("workspace-header")
        val bounds = if (onSample != null) node.fetchSemanticsNode().boundsInRoot else null
        val header = node.captureToImage().asAndroidBitmap()
        return try {
            val centerX = (header.width * 0.34f).roundToInt()
            val centerY = header.height / 2
            val radiusX = max(2, (5 * compose.activity.resources.displayMetrics.density).roundToInt())
            val radiusY = max(2, (3 * compose.activity.resources.displayMetrics.density).roundToInt())
            val color = averageRegion(
                header,
                centerX = centerX, centerY = centerY, radiusX = radiusX, radiusY = radiusY,
            )
            if (onSample != null) {
                onSample(materialSampleEvidence(
                    "workspace-header", header, checkNotNull(bounds),
                    max(0, centerX - radiusX), max(0, centerY - radiusY),
                    minOf(header.width, centerX + radiusX + 1),
                    minOf(header.height, centerY + radiusY + 1), color,
                ))
            }
            color
        } finally {
            header.recycle()
        }
    }

    private fun materialSampleEvidence(
        tag: String,
        bitmap: Bitmap,
        bounds: Rect,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        color: Int,
    ): JSONObject {
        val scene = renderedScene.get()
        val evidence = JSONObject().put("node_tag", tag)
            .put("semantic_bounds_in_root_px", JSONObject()
                .put("left", bounds.left).put("top", bounds.top)
                .put("right", bounds.right).put("bottom", bounds.bottom))
            .put("sample_bounds_in_node_px", JSONObject()
                .put("left", left).put("top", top).put("right_exclusive", right).put("bottom_exclusive", bottom))
            .put("node_image_width_px", bitmap.width).put("node_image_height_px", bitmap.height)
            .put("sample_pixel_count", (right - left) * (bottom - top))
            .put("average_argb", Integer.toHexString(color))
            .put("workspace_generation", scene?.frame?.actualKey?.generation ?: JSONObject.NULL)
            .put("workspace_content_id", scene?.frame?.actualKey?.contentId ?: JSONObject.NULL)
            .put("workspace_frame_epoch", scene?.frame?.epoch ?: JSONObject.NULL)
            .put("workspace_derivative_epoch", scene?.derivative?.key?.frameEpoch ?: JSONObject.NULL)
        if (scene != null) {
            val sceneX = bounds.left - scene.rootBoundsInRoot.left + (left + right) / 2f
            val sceneY = bounds.top - scene.rootBoundsInRoot.top + (top + bottom) / 2f
            fun bitmapSample(source: Bitmap): String {
                val x = (sceneX / scene.frame.actualKey.viewportWidth * source.width).toInt()
                    .coerceIn(0, source.width - 1)
                val y = (sceneY / scene.frame.actualKey.viewportHeight * source.height).toInt()
                    .coerceIn(0, source.height - 1)
                return Integer.toHexString(averageRegion(source, x, y, 2, 2))
            }
            evidence.put("scene_sample_x", sceneX).put("scene_sample_y", sceneY)
                .put("diagnostic_patch_radius_px", 2)
                .put("clear_bitmap_argb", bitmapSample(scene.frame.clearBitmap))
                .put("derivative_bitmap_argb", scene.derivative?.bitmap?.let(::bitmapSample))
                .put("scene_root_left", scene.rootBoundsInRoot.left)
                .put("scene_root_top", scene.rootBoundsInRoot.top)
            val root = compose.onRoot().captureToImage().asAndroidBitmap()
            try {
                evidence.put("root_window_argb", Integer.toHexString(averageRegion(
                    root, (bounds.left + (left + right) / 2f).roundToInt(),
                    (bounds.top + (top + bottom) / 2f).roundToInt(), 2, 2,
                )))
            } finally {
                root.recycle()
            }
        }
        return evidence
    }

    private fun writeMaterialSamples(diagnostics: JSONObject) {
        // UTP uninstalls the target after a lane. Retain bounded diagnostics in its
        // per-test logcat as well as private files, including on assertion failure.
        val encoded = diagnostics.toString()
        check(encoded.length <= 256_000) { "Glass diagnostic output exceeded its bound" }
        val chunks = encoded.chunked(2_000)
        chunks.forEachIndexed { index, chunk ->
            android.util.Log.i("DCompanyGlassProof", "${index + 1}/${chunks.size}:$chunk")
        }
        val directory = outputDirectory()
        val temporary = directory.resolve("glass-bright-custom.samples.json.tmp")
        temporary.writeText(diagnostics.toString(2) + "\n")
        check(temporary.renameTo(directory.resolve("glass-bright-custom.samples.json"))) {
            "Could not publish material sample diagnostics"
        }
    }

    private fun assertSelectedRailOutline(day: Boolean) {
        val density = compose.activity.resources.displayMetrics.density
        val item = compose.onNodeWithTag("workspace-rail-Dashboard")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .captureToImage().asAndroidBitmap()
        val decoration = compose.onNodeWithTag("workspace-rail-Dashboard-decoration", useUnmergedTree = true)
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        try {
            assertTrue(
                "selected rail decoration must keep the 40dp visible finish inside its 48dp owner",
                abs(decoration.height - 40f * density) <= 2f * density &&
                    decoration.height < item.height,
            )
            val interior = decoration.getPixel(decoration.width - max(3, decoration.width / 24), decoration.height / 2)
            val band = max(1, (2 * compose.activity.resources.displayMetrics.density).roundToInt())
            val midSpan = max(1, (6 * compose.activity.resources.displayMetrics.density).roundToInt())
            val horizontalRange = max(0, decoration.width / 2 - midSpan) until
                minOf(decoration.width, decoration.width / 2 + midSpan + 1)
            val verticalRange = max(0, decoration.height / 2 - midSpan) until
                minOf(decoration.height, decoration.height / 2 + midSpan + 1)
            val edgeBands = listOf(
                horizontalRange.flatMap { x -> (0 until minOf(band, decoration.height)).map { y -> decoration.getPixel(x, y) } },
                horizontalRange.flatMap { x ->
                    (max(0, decoration.height - band) until decoration.height).map { y -> decoration.getPixel(x, y) }
                },
                verticalRange.flatMap { y -> (0 until minOf(band, decoration.width)).map { x -> decoration.getPixel(x, y) } },
                verticalRange.flatMap { y ->
                    (max(0, decoration.width - band) until decoration.width).map { x -> decoration.getPixel(x, y) }
                },
            )
            edgeBands.forEachIndexed { index, edge ->
                assertTrue(
                    "selected rail gold perimeter edge $index must remain visible",
                    edge.any { contrast(it, interior) >= 1.8 || colorDistance(it, interior) >= 36 },
                )
            }

            val radius = 8f * density
            val ringRadius = (radius - 0.5f * density).coerceAtLeast(1f)
            val sampleRadius = max(1, density.roundToInt())
            val stroke = workspaceGlassRoleStyle(
                WorkspaceGlassRole.SELECTED_OUTLINE,
                day,
            ).stroke.toArgb()
            val corners = listOf(
                Triple(radius, radius, -1f to -1f),
                Triple(decoration.width - 1f - radius, radius, 1f to -1f),
                Triple(radius, decoration.height - 1f - radius, -1f to 1f),
                Triple(decoration.width - 1f - radius, decoration.height - 1f - radius, 1f to 1f),
            )
            val arcAngles = listOf(25.0, 45.0, 65.0)
            corners.forEachIndexed { cornerIndex, (centerX, centerY, signs) ->
                arcAngles.forEach { angleDegrees ->
                    val radians = Math.toRadians(angleDegrees)
                    val angleSampleRadius = if (
                        abs(density - 1f) <= 0.01f && angleDegrees == 45.0
                    ) 2 else sampleRadius
                    val sampleX = (centerX + signs.first * ringRadius * kotlin.math.cos(radians))
                        .roundToInt().coerceIn(0, decoration.width - 1)
                    val sampleY = (centerY + signs.second * ringRadius * kotlin.math.sin(radians))
                        .roundToInt().coerceIn(0, decoration.height - 1)
                    val substrateRadius = (ringRadius - 3f * density).coerceAtLeast(0f)
                    val substrateX = (centerX + signs.first * substrateRadius * kotlin.math.cos(radians))
                        .roundToInt().coerceIn(0, decoration.width - 1)
                    val substrateY = (centerY + signs.second * substrateRadius * kotlin.math.sin(radians))
                        .roundToInt().coerceIn(0, decoration.height - 1)
                    val substrate = decoration.getPixel(substrateX, substrateY)
                    val compositedStroke = compositeColor(substrate, stroke)
                    val region = buildList {
                        for (y in max(0, sampleY - angleSampleRadius)..minOf(decoration.height - 1, sampleY + angleSampleRadius)) {
                            for (x in max(0, sampleX - angleSampleRadius)..minOf(decoration.width - 1, sampleX + angleSampleRadius)) {
                                add(decoration.getPixel(x, y))
                            }
                        }
                    }
                    assertTrue(
                        "selected rail rounded perimeter corner=$cornerIndex angle=$angleDegrees " +
                            "must retain the shape-aware gold contour",
                        region.any { candidate ->
                            colorDistance(candidate, compositedStroke) <= 64 &&
                                colorDistance(candidate, compositedStroke) < colorDistance(candidate, substrate)
                        },
                    )
                }
            }
            listOf(
                Triple(0, 0, 0 to 0),
                Triple(decoration.width - 1, 0, item.width - 1 to 0),
                Triple(0, decoration.height - 1, 0 to item.height - 1),
                Triple(
                    decoration.width - 1,
                    decoration.height - 1,
                    item.width - 1 to item.height - 1,
                ),
            ).forEachIndexed { cornerIndex, (x, y, referenceCoordinates) ->
                val corner = decoration.getPixel(x, y)
                val unpaintedReference = item.getPixel(
                    referenceCoordinates.first,
                    referenceCoordinates.second,
                )
                val paintedReference = compositeColor(unpaintedReference, stroke)
                assertTrue(
                    "selected rail rounded perimeter corner=$cornerIndex must not paint outside its arc",
                    colorDistance(corner, unpaintedReference) < colorDistance(corner, paintedReference),
                )
            }
        } finally {
            decoration.recycle()
            item.recycle()
        }
    }

    private fun assertStatusAndRefreshContrast(
        bodyThreshold: Double = 4.5,
        iconThreshold: Double = 3.0,
    ) {
        val status = compose.onNodeWithTag("home-status-glass").captureToImage().asAndroidBitmap()
        val label = compose.onNodeWithTag("home-snapshot-label", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val refresh = compose.onNodeWithTag("home-refresh").captureToImage().asAndroidBitmap()
        val glyphSize = minOf(
            refresh.width,
            refresh.height,
            max(2, (24 * compose.activity.resources.displayMetrics.density).roundToInt()),
        )
        val refreshGlyph = Bitmap.createBitmap(
            refresh,
            (refresh.width - glyphSize) / 2,
            (refresh.height - glyphSize) / 2,
            glyphSize,
            glyphSize,
        )
        try {
            val statusBackground = dominantOpaqueColor(status)
            val labelProof = contrastProof(label, statusBackground, bodyThreshold)
            val refreshBackground = dominantOpaqueColor(refresh)
            val refreshProof = contrastProof(refreshGlyph, refreshBackground, iconThreshold)
            assertTrue("status text contrast must be at least $bodyThreshold:1", labelProof.first >= bodyThreshold)
            assertTrue("status text must retain resolved core pixels", labelProof.second >= 8)
            assertTrue("refresh glyph contrast must be at least $iconThreshold:1", refreshProof.first >= iconThreshold)
            assertTrue("refresh glyph must retain resolved core pixels", refreshProof.second >= 4)
        } finally {
            status.recycle()
            label.recycle()
            refreshGlyph.recycle()
            refresh.recycle()
        }
    }

    private fun assertDateTimeContrast(bodyThreshold: Double = 4.5) {
        compose.onNodeWithTag("workspace-date-surface", useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsAtLeast(32.dp)
        compose.onNodeWithTag("workspace-time-surface", useUnmergedTree = true)
            .assertIsDisplayed().assertHeightIsAtLeast(32.dp)
        val pill = compose.onNodeWithTag("workspace-date-time").assertIsDisplayed()
            .captureToImage().asAndroidBitmap()
        val date = compose.onNodeWithTag("workspace-date-label", useUnmergedTree = true)
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(pill)
            val proof = contrastProof(date, background, bodyThreshold)
            assertTrue("date caption contrast must be at least $bodyThreshold:1", proof.first >= bodyThreshold)
            assertTrue("date caption must retain resolved core glyph pixels", proof.second >= 8)
        } finally {
            pill.recycle()
            date.recycle()
        }
    }

    private fun assertUnavailableCaptionContrast(bodyThreshold: Double = 4.5) {
        val caption = compose.onNodeWithText("Revenue unavailable", useUnmergedTree = true)
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(caption)
            val proof = contrastProof(caption, background, bodyThreshold)
            assertTrue("unavailable caption contrast must be at least $bodyThreshold:1", proof.first >= bodyThreshold)
            assertTrue("unavailable caption must retain resolved core glyph pixels", proof.second >= 8)
        } finally {
            caption.recycle()
        }
    }

    private fun dominantOpaqueColor(bitmap: Bitmap): Int {
        val counts = HashMap<Int, Int>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) >= 250) counts[color] = (counts[color] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: error("Capture has no opaque pixels")
    }

    private fun contrastProof(bitmap: Bitmap, background: Int, threshold: Double): Pair<Double, Int> {
        var peak = 1.0
        var pixels = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) < 250) continue
            val ratio = contrast(color, background)
            peak = max(peak, ratio)
            if (ratio >= threshold) pixels += 1
        }
        return peak to pixels
    }

    private fun averageRegion(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        radiusX: Int,
        radiusY: Int,
    ): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in max(0, centerY - radiusY) until minOf(bitmap.height, centerY + radiusY + 1)) {
            for (x in max(0, centerX - radiusX) until minOf(bitmap.width, centerX + radiusX + 1)) {
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count += 1
            }
        }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun contrast(first: Int, second: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        fun luminance(color: Int): Double =
            0.2126 * channel(Color.red(color)) +
                0.7152 * channel(Color.green(color)) +
                0.0722 * channel(Color.blue(color))
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (kotlin.math.min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs(Color.red(first) - Color.red(second)) +
            abs(Color.green(first) - Color.green(second)) +
            abs(Color.blue(first) - Color.blue(second))

    private fun createGlassCropPattern(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { style = Paint.Style.FILL }
        val oneThird = height / 3f
        val stripeHeight = height / 30f
        var stripeTop = 0f
        var stripeIndex = 0
        while (stripeTop < oneThird) {
            paint.color = if (stripeIndex % 2 == 0) {
                Color.rgb(236, 42, 38)
            } else {
                Color.rgb(40, 92, 236)
            }
            canvas.drawRect(
                0f,
                stripeTop,
                width.toFloat(),
                minOf(oneThird, stripeTop + stripeHeight),
                paint,
            )
            stripeTop += stripeHeight
            stripeIndex += 1
        }
        paint.color = Color.rgb(34, 214, 92)
        canvas.drawRect(0f, oneThird, width.toFloat(), oneThird * 2f, paint)
        paint.color = Color.rgb(40, 92, 236)
        canvas.drawRect(0f, oneThird * 2f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun glassCropPatternColor(y: Float, height: Int): Int = when {
        y < height / 3f -> if ((y / (height / 30f)).toInt() % 2 == 0) {
            Color.rgb(236, 42, 38)
        } else {
            Color.rgb(40, 92, 236)
        }
        y < height * 2f / 3f -> Color.rgb(34, 214, 92)
        else -> Color.rgb(40, 92, 236)
    }

    private fun expectedSceneGlassColor(
        source: Int,
        globalY: Float,
        viewportHeight: Int,
        independentlyPinnedGradeStrength: Float? = null,
    ): Int {
        val position = (globalY / viewportHeight).coerceIn(0f, 1f)
        val fullScrimAlpha = if (position <= 0.45f) {
            0.44f + (0.26f - 0.44f) * (position / 0.45f)
        } else {
            0.26f + (0.60f - 0.26f) * ((position - 0.45f) / 0.55f)
        }
        val gradeStrength = independentlyPinnedGradeStrength
            ?: workspaceGlassSceneGradeStrength(WorkspaceGlassRole.PANEL, day = false)
        val scrimAlpha = fullScrimAlpha * gradeStrength
        val withScrim = compositeColor(
            source,
            Color.argb((scrimAlpha * 255f).roundToInt(), 0, 0, 0),
        )
        return compositeColor(
            withScrim,
            Color.argb((0.10f * gradeStrength * 255f).roundToInt(), 0, 0, 0),
        )
    }

    private data class AdaptivePanelPixelExpectation(val color: Int, val alpha: Float)

    private fun expectedAdaptivePanelPixel(
        source: Int,
        globalY: Float,
        viewportHeight: Int,
        boundsInScene: Rect,
        derivative: WorkspaceGlassDerivative,
        independentlyPinnedTintAlpha: Float? = null,
        independentlyPinnedGradeStrength: Float? = null,
    ): AdaptivePanelPixelExpectation {
        val alpha = independentlyPinnedTintAlpha ?: checkNotNull(
            workspaceGlassAdmittedTintAlpha(
                grid = derivative.safetyGrid,
                derivativeKey = derivative.key,
                boundsInScene = boundsInScene,
                viewportWidth = derivative.key.actualKey.viewportWidth,
                viewportHeight = viewportHeight,
                role = WorkspaceGlassRole.PANEL,
            ),
        ) { "The exact projected panel bounds must admit a contrast-safe Night tint" }
        val tint = androidx.compose.ui.graphics.Color(0xFF05060B).copy(alpha = alpha).toArgb()
        return AdaptivePanelPixelExpectation(
            color = compositeColor(
                expectedSceneGlassColor(
                    source = source,
                    globalY = globalY,
                    viewportHeight = viewportHeight,
                    independentlyPinnedGradeStrength = independentlyPinnedGradeStrength,
                ),
                tint,
            ),
            alpha = alpha,
        )
    }

    private fun compositeColor(background: Int, foreground: Int): Int {
        val alpha = Color.alpha(foreground) / 255f
        fun channel(backgroundValue: Int, foregroundValue: Int): Int =
            (foregroundValue * alpha + backgroundValue * (1f - alpha)).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(background), Color.red(foreground)),
            channel(Color.green(background), Color.green(foreground)),
            channel(Color.blue(background), Color.blue(foreground)),
        )
    }

    private fun centerPixel(bitmap: Bitmap): Int = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)

    private fun graphicsLayerSnapshot(layer: GraphicsLayer): Bitmap {
        val snapshot = runBlocking { layer.toImageBitmap() }.asAndroidBitmap()
        if (snapshot.config != Bitmap.Config.HARDWARE) return snapshot
        return try {
            checkNotNull(snapshot.copy(Bitmap.Config.ARGB_8888, false)) {
                "Projected layer could not be copied for synchronous pixel readback"
            }
        } finally {
            snapshot.recycle()
        }
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
            val scene = renderedScene.get()
            val actualKey = scene?.frame?.actualKey
            directory.resolve("$name.provenance.json").writeText(
                """
                {
                  "capture_run_id": ${argument(GlassRunArgument)},
                  "test_class": "cloud.dcompany.erp.ui.theme.WorkspaceGlassUiTest",
                  "source_inventory_sha256": ${argument(GlassSourceArgument)},
                  "target_apk_sha256": ${argument(GlassTargetArgument)},
                  "test_apk_sha256": ${argument(GlassTestArgument)},
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
                  "workspace_generation": ${actualKey?.generation?.toString() ?: "null"},
                  "workspace_content_id": ${actualKey?.contentId?.let { JSONObject.quote(it) } ?: "null"},
                  "workspace_viewport_width_px": ${actualKey?.viewportWidth?.toString() ?: "null"},
                  "workspace_viewport_height_px": ${actualKey?.viewportHeight?.toString() ?: "null"},
                  "workspace_frame_epoch": ${scene?.frame?.epoch?.toString() ?: "null"},
                  "workspace_derivative_epoch": ${scene?.derivative?.key?.frameEpoch?.toString() ?: "null"},
                  "workspace_root_width_px": ${scene?.rootBoundsInRoot?.width?.toString() ?: "null"},
                  "workspace_root_height_px": ${scene?.rootBoundsInRoot?.height?.toString() ?: "null"},
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
        val nonce = InstrumentationRegistry.getArguments().getString(GlassRunArgument)
            ?: error("Missing $GlassRunArgument")
        require(nonce.matches(Regex("[0-9a-f]{32}")))
        val profile = RequestedGlassDisplay?.let { "display-$it-hd" } ?: "default-display"
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-b1-glass-screenshots-v1").resolve(profile).resolve(nonce)
            .apply { check(isDirectory || mkdirs()) }
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name)
            ?: error("Missing $name")
        val length = if (name == GlassRunArgument) 32 else 64
        require(value.matches(Regex("[0-9a-f]{$length}")))
        return "\"$value\""
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        @JvmStatic @AfterClass fun cleanupFixtures() {
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                .resolve("workspace-glass-fixtures").deleteRecursively()
        }
    }
}
