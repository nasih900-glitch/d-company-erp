package cloud.dcompany.erp.ui

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val TestDisplayIdArgument = "dcompany.testDisplayId"

private val RequestedTestDisplayId: Int? by lazy {
    val raw = InstrumentationRegistry.getArguments().getString(TestDisplayIdArgument)
    if (raw == null) {
        null
    } else {
        val requested = raw.toIntOrNull()
            ?: error("Invalid -e $TestDisplayIdArgument value: $raw")
        require(requested == 2) {
            "Only the verified HD display 2 is supported; requested display was $requested"
        }
        requested
    }
}

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
            assertEquals("Explicit HD launch must land on display 2", 2, proof.observedDisplayId)
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
        render(width = 1_280.dp, height = 800.dp)

        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("workspace-bottom-dock").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-Dashboard")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-header")
        assertFullyInsideRoot("increment-Dashboard")

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
    fun shortLargeTextRailKeepsMoreReachableAndRetainsDestinationState() {
        assertRequestedDisplayHost()
        render(width = 1_280.dp, height = 360.dp, fontScale = 1.5f)

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
        more.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertFullyInsideRoot("workspace-more-modules")
        more.performTouchInput { click() }

        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Settings")
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
    fun compactPortraitUsesDockAndKeepsAuthorizedRoutesReachable() {
        assertRequestedDisplayHost()
        render(width = 600.dp, height = 800.dp)

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
        render(
            width = 600.dp,
            height = 800.dp,
            fontScale = 1.3f,
            outboxWorkStatus = OutboxWorkStatus(retryableCount = 1),
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

        compose.onNodeWithTag("workspace-dock-Customers").performTouchInput { click() }
        compose.onNodeWithText("Customers count 0").assertIsDisplayed()
        assertFullyInsideRoot("increment-Customers")
    }

    private fun render(
        width: Dp,
        height: Dp,
        fontScale: Float = 1.0f,
        outboxWorkStatus: OutboxWorkStatus = OutboxWorkStatus(),
    ) {
        val current = mutableStateOf(Destination.Dashboard)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
                DCompanyTheme(AppearancePreference.Night) {
                    Box(
                        Modifier.width(width).height(height)
                            .testTag("phase-b-workspace-root"),
                    ) {
                        WorkspaceScaffold(
                            destinations = AllowedDestinations,
                            currentDestination = current.value,
                            employeeName = "Long review employee name",
                            locationLabel = "Long review workspace name",
                            connectivityProblem = SyncAvailabilityProblem.NO_NETWORK,
                            outboxWorkStatus = outboxWorkStatus,
                            syncing = false,
                            canChangeTill = false,
                            appearancePreference = AppearancePreference.Night,
                            onOpenSupport = {},
                            onChangeTill = {},
                            onSignOut = {},
                            onDestinationChanged = { current.value = it },
                        ) { destination, _ ->
                            var count by rememberSaveable { mutableIntStateOf(0) }
                            ErpButton(
                                text = "${destination.name} count $count",
                                onClick = { count += 1 },
                                modifier = Modifier.testTag("increment-${destination.name}"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun exercisePermissionFilteredNavigationAndState() {
        compose.onNodeWithTag("increment-Dashboard").performTouchInput { click() }
        compose.onNodeWithText("Dashboard count 1").assertIsDisplayed()

        compose.onNodeWithTag("workspace-rail-Reports")
            .performScrollTo()
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithText("Reports count 0").assertIsDisplayed()

        compose.onNodeWithTag("workspace-more-modules")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        compose.onNodeWithTag("workspace-destination-Settings")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("workspace-destination-Finance").assertDoesNotExist()
        compose.onNodeWithTag("workspace-destination-Settings").performTouchInput { click() }
        compose.onNodeWithText("Settings count 0").assertIsDisplayed()

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

    private fun outputDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = if (RequestedTestDisplayId == null) "default-display" else "display-2-hd"
        return context.filesDir
            .resolve("phase-b-workspace-screenshots-v2")
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
        expectedWidthDp: Int,
        expectedHeightDp: Int,
        actualWidthPx: Int,
        actualHeightPx: Int,
        host: HostDisplayProof,
        fontScale: Float,
    ) {
        val requestedDisplayIdJson = host.requestedDisplayId?.toString() ?: "null"
        destination.writeText(
            """
            {
              "phase": "B",
              "capture_api": "ComposeTestRule.onRoot().captureToImage()",
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
        const val BaseCommit = "1eb693127e6818261d4a236daa1da29389b8b30b"
        val CaptureRunId = "${System.currentTimeMillis()}-${android.os.Process.myPid()}"

        val AllowedDestinations = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
            Destination.Reports,
            Destination.Settings,
        )
    }
}
