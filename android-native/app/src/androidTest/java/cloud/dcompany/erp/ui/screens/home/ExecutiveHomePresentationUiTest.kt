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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
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
import cloud.dcompany.erp.ui.screens.analytics.AnalyticsUiState
import cloud.dcompany.erp.ui.screens.analytics.DashboardKpis
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
private const val HomeFetchedAtMillis = 1_792_758_600_000L

private val RequestedHomeDisplayId: Int? by lazy {
    InstrumentationRegistry.getArguments().getString(HomeDisplayIdArgument)?.let { raw ->
        require(raw.toIntOrNull() == 2) { "Only verified HD display 2 is supported: $raw" }
        2
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
            ActivityOptions.makeBasic().setLaunchDisplayId(2).toBundle(),
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
        renderShell(widthDp = 1_280, heightDp = 800, state = populatedState())
        capture("home-shell-1280x800", 1_280, 800, 1f)

        compose.onNodeWithTag("workspace-navigation-rail").assertIsDisplayed()
        compose.onNodeWithTag("home-hero").assertIsDisplayed()
        compose.onNodeWithTag("home-hero-brand").assertIsDisplayed()
        compose.onNodeWithContentDescription("D Company lounge dashboard artwork").assertIsDisplayed()
        compose.onNodeWithText("Good evening, Nasih").assertIsDisplayed()
        compose.onNodeWithText("Gross revenue").assertIsDisplayed()
        compose.onNodeWithText("₹24,580.00").assertIsDisplayed()
        compose.onNodeWithText("Recent activity isn’t available in this view").assertIsDisplayed()
        compose.onNodeWithText("Open Analytics for period comparisons").assertIsDisplayed()
        assertFullyInsideRoot("home-recent-activity")
        assertFullyInsideRoot("home-sales-trend")
        val recentBounds = compose.onNodeWithTag("home-recent-activity").getUnclippedBoundsInRoot()
        val salesBounds = compose.onNodeWithTag("home-sales-trend").getUnclippedBoundsInRoot()
        assertEquals(
            "wide lower panels must balance their heights",
            (recentBounds.bottom - recentBounds.top).value.toDouble(),
            (salesBounds.bottom - salesBounds.top).value.toDouble(),
            1.0,
        )
        listOf(
            "Gross revenue",
            "Active sessions",
            "Orders",
            "Customers",
            "Quick access",
            "Recent activity isn’t available in this view",
            "Open Analytics for period comparisons",
            "D COMPANY",
            "GAMING LOUNGE",
        )
            .forEach(::assertAllHomeTextDoesNotOverflow)
        recordAndAssertCompleteRailLabel("Customers", "workspace-rail-Customers", "night-font1_0")
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
        listOf("Gross revenue", "Active sessions", "Quick access", "Recent activity", "Sales trend")
            .forEach(::assertAllHomeTextDoesNotOverflow)
    }

    @Test
    fun loadingBlockingErrorAndStaleSnapshotRemainTruthfulAndGeometricallyStable() {
        val state = mutableStateOf(AnalyticsUiState(todayLoading = true))
        val refreshes = mutableStateOf(0)
        renderShell(
            1_280,
            800,
            state = state.value,
            stateHolder = state,
            onRetry = { refreshes.value += 1 },
        )
        compose.onNodeWithText("Loading the verified dashboard snapshot…").assertIsDisplayed()
        compose.onNodeWithText("No verified dashboard snapshot").assertIsDisplayed()
        compose.onNodeWithTag("home-refresh").assertIsNotEnabled()
        assertLoadingRefreshContrast("night-loading")
        capture("home-loading-1280x800", 1_280, 800, 1f)

        compose.runOnIdle {
            state.value = AnalyticsUiState(todayLoading = false, todayError = "Couldn’t refresh the dashboard")
        }
        compose.onNodeWithText("Couldn’t refresh the dashboard").assertIsDisplayed()
        capture("home-error-1280x800", 1_280, 800, 1f)
        compose.onNodeWithTag("home-retry").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, refreshes.value) }

        compose.runOnIdle {
            state.value = populatedState().copy(todayLoading = false, todayError = "Live refresh unavailable")
        }
        compose.onNodeWithText("Saved snapshot · Live refresh unavailable").assertIsDisplayed()
        compose.onNodeWithText("₹24,580.00").assertIsDisplayed()
        compose.onNodeWithText(
            "Saved snapshot 23 Oct 2026 · fetched ${expectedFetchedTimestamp()}",
        ).assertIsDisplayed()
        capture("home-stale-1280x800", 1_280, 800, 1f)
        compose.onNodeWithTag("home-refresh").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(2, refreshes.value) }
        assertFullyInsideRoot("home-recent-activity")
    }

    @Test
    fun dayLargeTextReducedHeightKeepsLongGreetingAndEssentialActionsReachable() {
        val selected = mutableStateOf<Destination?>(null)
        val employeeName = "AlexandertheGreatOperationsLead Singh"
        renderShell(
            widthDp = 1_280,
            heightDp = 520,
            fontScale = 1.3f,
            state = populatedState().copy(todayLoading = true),
            employeeName = employeeName,
            appearance = AppearancePreference.Day,
            onNavigate = { selected.value = it },
        )
        capture("home-shell-1280x520-font1_3-day", 1_280, 520, 1.3f)

        val greeting = "Good evening, AlexandertheGreatOperationsLead"
        compose.onNodeWithText(greeting).assertIsDisplayed()
        compose.onNodeWithTag("home-hero-brand").assertDoesNotExist()
        assertAllHomeTextDoesNotOverflow(greeting)
        compose.onNodeWithTag("home-refresh").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        assertLoadingRefreshContrast("day-loading-font1_3")
        compose.onNodeWithTag("workspace-rail-Customers").assertHeightIsAtLeast(48.dp)
        recordAndAssertCompleteRailLabel("Customers", "workspace-rail-Customers", "day-font1_3")
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
    }

    @Test
    fun permissionFilteredShortcutsAndAnalyticsActionPreserveExactCallbackIdentity() {
        val selected = mutableStateOf<Destination?>(null)
        val allowed = listOf(Destination.Dashboard, Destination.Customers, Destination.Analytics)
        renderShell(1_280, 800, state = populatedState(), destinations = allowed) { selected.value = it }

        compose.onNodeWithTag("home-shortcut-Customers").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(Destination.Customers, selected.value) }
        compose.onNodeWithTag("home-shortcut-Gaming").assertDoesNotExist()
        compose.onNodeWithTag("home-open-analytics").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(Destination.Analytics, selected.value) }
    }

    private fun populatedState() = AnalyticsUiState(
        todayLoading = false,
        dashboard = DashboardKpis(
            branchId = "branch-a",
            date = "2026-10-23",
            revenueTotalMinor = 2_458_000,
            ordersCount = 48,
            openSessions = 6,
        ),
        dashboardFetchedAtMillis = HomeFetchedAtMillis,
    )

    private fun renderShell(
        widthDp: Int,
        heightDp: Int,
        fontScale: Float = 1f,
        state: AnalyticsUiState,
        destinations: List<Destination> = listOf(
            Destination.Dashboard,
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
            Destination.Reports,
            Destination.Analytics,
        ),
        stateHolder: MutableState<AnalyticsUiState>? = null,
        employeeName: String = "Nasih Mohammed with a deliberately long operator name",
        appearance: AppearancePreference = AppearancePreference.Night,
        onRetry: () -> Unit = {},
        onNavigate: (Destination) -> Unit = {},
    ) {
        val host = assertHost()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(host.density, fontScale)) {
                DCompanyTheme(appearance) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(widthDp.dp).height(heightDp.dp)) {
                            WorkspaceScaffold(
                                destinations = destinations,
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
                                    destinations = destinations,
                                    navigateTo = navigateTo,
                                    onRetry = onRetry,
                                    hour = 18,
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
        .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a 'IST'", Locale.ENGLISH))

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
                assertEquals(2, it.displayId)
                assertEquals(2_560, it.modeWidthPx)
                assertEquals(1_600, it.modeHeightPx)
                assertEquals(320, it.densityDpi)
            }
        }
    }

    private fun assertFullyInsideRoot(tag: String) {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val node = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("$tag must begin inside root: $node vs $root", node.left >= root.left && node.top >= root.top)
        assertTrue("$tag must end inside root: $node vs $root", node.right <= root.right && node.bottom <= root.bottom)
    }

    private fun assertAllHomeTextDoesNotOverflow(text: String) {
        val nodes = compose.onAllNodes(
            hasText(text) and hasAnyAncestor(hasTestTag("executive-home-workspace")),
            useUnmergedTree = true,
        )
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("$text must expose at least one text layout", count > 0)
        repeat(count) { index ->
            val results = mutableListOf<TextLayoutResult>()
            nodes[index].performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals("$text node $index must expose one text layout", 1, results.size)
            assertFalse("$text node $index must not overflow", results.single().hasVisualOverflow)
        }
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
        outputDirectory().resolve("home-rail-Customers-$evidenceStem-text-layout.json").writeText(
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
        val label = compose.onNodeWithTag("home-refresh-label", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val indicator = compose.onNodeWithTag("home-refresh-indicator", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        try {
            val background = dominantOpaqueColor(button)
            val labelProof = contrastProof(label, background, 4.5)
            val indicatorProof = contrastProof(indicator, background, 3.0)
            outputDirectory().resolve("home-refresh-$evidenceStem-contrast.json").writeText(
                """
                {
                  "source_inventory_sha256": ${jsonPin(HomeSourceInventoryArgument)},
                  "target_apk_sha256": ${jsonPin(HomeTargetApkArgument)},
                  "test_apk_sha256": ${jsonPin(HomeTestApkArgument)},
                  "button_background_argb": "${String.format(Locale.US, "%08X", background)}",
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
            label.recycle()
            indicator.recycle()
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
                  "phase": "C2_HOME",
                  "capture_api": "ComposeTestRule.onRoot().captureToImage()",
                  "test_class": "cloud.dcompany.erp.ui.screens.home.ExecutiveHomePresentationUiTest",
                  "base_commit": "d8623d93450057e943a7560d6cd2e9c5c9cb7aea",
                  "source_inventory_sha256": ${jsonPin(HomeSourceInventoryArgument)},
                  "target_apk_sha256": ${jsonPin(HomeTargetApkArgument)},
                  "test_apk_sha256": ${jsonPin(HomeTestApkArgument)},
                  "application_id": "${BuildConfig.APPLICATION_ID}",
                  "device": "${Build.MANUFACTURER} ${Build.MODEL}",
                  "android_api": ${Build.VERSION.SDK_INT},
                  "home_lounge_asset_sha256": "$HomeLoungeAssetSha256",
                  "home_lounge_asset_dimensions_px": "2172x724",
                  "home_lounge_asset_provenance": "generated reconstruction approved for C2 presentation; no baked UI",
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
        val profile = if (RequestedHomeDisplayId == null) "default-display" else "display-2-hd"
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("phase-c2-home-screenshots-v1").resolve(profile)
            .resolve(System.getProperty("dcompany.captureRunId") ?: "instrumentation")
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
