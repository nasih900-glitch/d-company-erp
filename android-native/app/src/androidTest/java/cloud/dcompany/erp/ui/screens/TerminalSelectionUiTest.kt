package cloud.dcompany.erp.ui.screens

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.ui.components.LiquidAlertDialog
import cloud.dcompany.erp.ui.components.liquidWorkspaceSurface
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundImage
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TerminalSelectionDisplayArgument = "dcompany.testDisplayId"
private const val TerminalSelectionSourceArgument = "dcompany.sourceInventorySha256"
private const val TerminalSelectionTargetArgument = "dcompany.targetApkSha256"
private const val TerminalSelectionTestArgument = "dcompany.testApkSha256"
private const val TerminalSelectionRunArgument = "dcompany.captureRunId"

private val RequestedTerminalSelectionDisplayId: Int by lazy {
    InstrumentationRegistry.getArguments().getString(TerminalSelectionDisplayArgument)
        ?.toIntOrNull()
        ?.takeIf { it in setOf(0, 2, 5, 6) }
        ?: error("Terminal Selection glass proof requires verified display 0, 2, 5 or 6")
}

private fun ActivityScenarioRule<ComponentActivity>.terminalSelectionActivity(): ComponentActivity {
    var activity: ComponentActivity? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity)
}

private fun createTerminalSelectionComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val activityRule = ActivityScenarioRule(
        ComponentActivity::class.java,
        ActivityOptions.makeBasic().setLaunchDisplayId(RequestedTerminalSelectionDisplayId).toBundle(),
    )
    return AndroidComposeTestRule(activityRule) { it.terminalSelectionActivity() }
}

private data class TerminalSceneEvidence(
    val contentId: String,
    val generation: Long,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val frameEpoch: Long,
    val derivativeEpoch: Long?,
    val derivativeContentId: String?,
)

@RunWith(AndroidJUnit4::class)
class TerminalSelectionUiTest {
    @get:Rule
    val compose = createTerminalSelectionComposeRule()

    private val temporaryFiles = mutableListOf<File>()

    @After
    fun deleteTemporaryArtwork() {
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun explicitChoiceIsTheOnlyConfirmationAndBusyBlocksEveryAction() {
        val choosing = mutableStateOf(false)
        val confirmed = AtomicReference<Terminal?>()
        val refreshes = AtomicInteger()
        val exits = AtomicInteger()
        val terminals = listOf(
            terminal("terminal-one", "Front till", TerminalPurpose.HYBRID),
            terminal("terminal-two", "Side till", "future_terminal_mode"),
        )
        compose.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                TerminalSelectionScreen(
                    employeeName = "Test employee",
                    selectionCompanyId = "company-one",
                    selectionBranchId = "branch-one",
                    selectionUserId = "user-one",
                    terminals = terminals,
                    choosing = choosing.value,
                    error = "Till list needs a refresh.",
                    isReassignment = true,
                    previousTerminalName = "Previous till",
                    onConfirm = confirmed::set,
                    onRefresh = { refreshes.incrementAndGet() },
                    onExit = { exits.incrementAndGet() },
                    backgroundState = WorkspaceBackgroundUiState(initializing = true),
                )
            }
        }

        compose.onNodeWithTag("terminal-selection-choice-0").assertIsNotSelected().assertIsEnabled()
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsNotSelected().assertIsEnabled()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()
        compose.onNodeWithText("Purpose needs verification").assertIsDisplayed()

        compose.onNodeWithTag("terminal-selection-choice-1").performClick().assertIsSelected()
        assertEquals(null, confirmed.get())
        compose.onNodeWithTag("terminal-selection-confirm").assertIsEnabled().performClick()
        assertEquals(terminals[1], confirmed.get())
        compose.onNodeWithTag("terminal-selection-refresh").performClick()
        compose.onNodeWithTag("terminal-selection-exit").performClick()
        assertEquals(1, refreshes.get())
        assertEquals(1, exits.get())

        compose.runOnIdle { choosing.value = true }
        compose.onNodeWithTag("terminal-selection-choice-0").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsNotEnabled().assertIsSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-selection-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-selection-exit").assertIsNotEnabled()
        assertEquals(terminals[1], confirmed.get())
        assertEquals(1, refreshes.get())
        assertEquals(1, exits.get())
    }

    @Test
    fun delayedSavedArtworkPreservesExplicitSelectionAndScrollWithoutDefaultFlash() {
        assertVerifiedDisplay()
        val imported = importedWorkspaceImage("terminal-selection-delayed", generation = 71L)
        val backgrounds = mutableStateOf(WorkspaceBackgroundUiState(workspace = imported, initializing = true))
        val evidence = AtomicReference<TerminalSceneEvidence?>()
        val confirmations = AtomicInteger()
        val terminals = (0 until 14).map { index ->
            terminal("terminal-$index", "Till ${index + 1}", if (index % 2 == 0) TerminalPurpose.CAFE_POS else TerminalPurpose.GAMING)
        }
        compose.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                TerminalSelectionScreen(
                    employeeName = "Test employee",
                    selectionCompanyId = "company-one",
                    selectionBranchId = "branch-one",
                    selectionUserId = "user-one",
                    terminals = terminals,
                    choosing = false,
                    error = null,
                    onConfirm = { confirmations.incrementAndGet() },
                    onRefresh = {},
                    onExit = {},
                    backgroundState = backgrounds.value,
                    trailingOverlay = { RecordTerminalScene(evidence) },
                )
            }
        }

        compose.onNodeWithTag("terminal-selection-background-initializing").assertIsDisplayed()
        compose.onNodeWithTag("terminal-selection-background-workspace").assertDoesNotExist()
        compose.onNodeWithTag("terminal-selection-choice-0").performClick().assertIsSelected()
        compose.onNodeWithTag("terminal-selection-choice-13").performScrollTo().assertIsDisplayed()
        val scrollBefore = terminalScrollValue()
        assertTrue("The delayed-artwork fixture must establish a real scroll offset", scrollBefore > 1f)
        assertEquals(null, evidence.get())
        assertEquals(0, confirmations.get())

        compose.runOnIdle { backgrounds.value = WorkspaceBackgroundUiState(workspace = imported) }
        compose.waitUntil(8_000L) { tagCount("terminal-selection-glass-ready-workspace") == 1 }
        compose.waitUntil(8_000L) {
            evidence.get()?.let { it.derivativeEpoch == it.frameEpoch } == true
        }
        compose.onNodeWithTag("terminal-selection-background-initializing").assertDoesNotExist()
        compose.onNodeWithTag("terminal-selection-choice-0").assertIsSelected()
        val scrollAfter = terminalScrollValue()
        assertTrue(
            "Artwork readiness must not reset the explicit list position: before=$scrollBefore after=$scrollAfter",
            abs(scrollAfter - scrollBefore) <= 1f,
        )
        assertEquals(0, confirmations.get())

        val actual = requireNotNull(evidence.get())
        val rootBounds = compose.onNodeWithTag("terminal-selection-background-workspace")
            .getUnclippedBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(imported.contentId, actual.contentId)
        assertEquals(imported.generation, actual.generation)
        assertEquals(((rootBounds.right - rootBounds.left).value * density).roundToInt(), actual.viewportWidth)
        assertEquals(((rootBounds.bottom - rootBounds.top).value * density).roundToInt(), actual.viewportHeight)
        assertEquals(actual.contentId, actual.derivativeContentId)
        assertEquals(actual.frameEpoch, actual.derivativeEpoch)
        capture(
            name = "terminal-selection-ready-night",
            nodeTag = "terminal-selection-root",
            expectedWidthDp = 1_280,
            expectedHeightDp = 800,
            fontScale = 1f,
            appearance = "Night",
            sceneState = "requested_ready",
        )
    }

    @Test
    fun authorityAndChoiceMembershipOwnSaveableSelectionWhileReorderDoesNot() {
        val company = mutableStateOf("company-one")
        val branch = mutableStateOf("branch-one")
        val user = mutableStateOf("user-one")
        val terminals = mutableStateOf(
            listOf(
                terminal("terminal-a", "Till A", TerminalPurpose.HYBRID),
                terminal("terminal-b", "Till B", TerminalPurpose.CAFE_POS),
            ),
        )
        compose.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                TerminalSelectionScreen(
                    employeeName = "Test employee",
                    selectionCompanyId = company.value,
                    selectionBranchId = branch.value,
                    selectionUserId = user.value,
                    terminals = terminals.value,
                    choosing = false,
                    error = null,
                    onConfirm = {},
                    onRefresh = {},
                    onExit = {},
                    backgroundState = WorkspaceBackgroundUiState(initializing = true),
                )
            }
        }

        compose.onNodeWithTag("terminal-selection-choice-0").performClick().assertIsSelected()
        compose.runOnIdle { terminals.value = terminals.value.reversed() }
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsEnabled()

        compose.runOnIdle {
            terminals.value = terminals.value + terminal("terminal-c", "Till C", TerminalPurpose.GAMING)
        }
        compose.onNodeWithTag("terminal-selection-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-choice-2").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()

        compose.onNodeWithTag("terminal-selection-choice-0").performClick().assertIsSelected()
        compose.runOnIdle { user.value = "user-two" }
        compose.onNodeWithTag("terminal-selection-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()

        compose.runOnIdle { terminals.value = emptyList() }
        compose.onNodeWithText("No till is currently available for this branch.").assertIsDisplayed()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()
    }

    @Test
    fun savedSelectionRestoresForTheSameAuthorityAndCanonicalMembership() {
        val restoration = StateRestorationTester(compose)
        val terminals = listOf(
            terminal("terminal-a", "Till A", TerminalPurpose.HYBRID),
            terminal("terminal-b", "Till B", TerminalPurpose.CAFE_POS),
        )
        restoration.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                TerminalSelectionScreen(
                    employeeName = "Test employee",
                    selectionCompanyId = "company-one",
                    selectionBranchId = null,
                    selectionUserId = "user-one",
                    terminals = terminals.reversed(),
                    choosing = false,
                    error = null,
                    onConfirm = {},
                    onRefresh = {},
                    onExit = {},
                    backgroundState = WorkspaceBackgroundUiState(initializing = true),
                )
            }
        }
        compose.onNodeWithTag("terminal-selection-choice-1").performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsEnabled()
    }

    @Test
    fun restoredSelectionIsRejectedForAnotherUserWithTheSameTerminalIds() {
        val restoration = StateRestorationTester(compose)
        var restoringUser = "user-one"
        val terminals = listOf(
            terminal("terminal-a", "Till A", TerminalPurpose.HYBRID),
            terminal("terminal-b", "Till B", TerminalPurpose.CAFE_POS),
        )
        restoration.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                TerminalSelectionScreen(
                    employeeName = "Test employee",
                    selectionCompanyId = "company-one",
                    selectionBranchId = "branch-one",
                    selectionUserId = restoringUser,
                    terminals = terminals,
                    choosing = false,
                    error = null,
                    onConfirm = {},
                    onRefresh = {},
                    onExit = {},
                    backgroundState = WorkspaceBackgroundUiState(initializing = true),
                )
            }
        }
        compose.onNodeWithTag("terminal-selection-choice-0").performClick().assertIsSelected()
        compose.runOnIdle { restoringUser = "user-two" }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("terminal-selection-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()
    }

    @Test
    fun restoredSelectionIsRejectedWhenTerminalMembershipChanged() {
        val restoration = StateRestorationTester(compose)
        var restoringTerminals = listOf(
            terminal("terminal-a", "Till A", TerminalPurpose.HYBRID),
            terminal("terminal-b", "Till B", TerminalPurpose.CAFE_POS),
        )
        restoration.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                TerminalSelectionScreen(
                    employeeName = "Test employee",
                    selectionCompanyId = "company-one",
                    selectionBranchId = "branch-one",
                    selectionUserId = "user-one",
                    terminals = restoringTerminals,
                    choosing = false,
                    error = null,
                    onConfirm = {},
                    onRefresh = {},
                    onExit = {},
                    backgroundState = WorkspaceBackgroundUiState(initializing = true),
                )
            }
        }
        compose.onNodeWithTag("terminal-selection-choice-0").performClick().assertIsSelected()
        compose.runOnIdle {
            restoringTerminals = restoringTerminals +
                terminal("terminal-c", "Till C", TerminalPurpose.GAMING)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("terminal-selection-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-choice-1").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-choice-2").assertIsNotSelected()
        compose.onNodeWithTag("terminal-selection-confirm").assertIsNotEnabled()
    }

    @Test
    fun compactLargeTextKeepsFinalActionsAndSupportPopupReachableOnFallbackScene() {
        assertVerifiedDisplay()
        val invalid = WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = 1_280,
            height = 800,
            generation = 91L,
            contentId = "terminal-selection-missing-artwork",
            privatePath = File(compose.activity.cacheDir, "missing-terminal-background.jpg").absolutePath,
        )
        val refreshed = AtomicInteger()
        val exited = AtomicInteger()
        val popupRoot = AtomicReference<View?>()
        val terminals = (0 until 7).map { index ->
            terminal("compact-$index", "Compact till ${index + 1}", TerminalPurpose.HYBRID)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DCompanyTheme(AppearancePreference.Day) {
                    Box(Modifier.size(600.dp, 520.dp)) {
                        var supportOpen by remember { mutableStateOf(false) }
                        TerminalSelectionScreen(
                            employeeName = "Test employee",
                            selectionCompanyId = "company-one",
                            selectionBranchId = "branch-one",
                            selectionUserId = "user-one",
                            terminals = terminals,
                            choosing = false,
                            error = "Could not refresh the till list. Existing choices remain unchanged.",
                            isReassignment = true,
                            previousTerminalName = "Previous till",
                            onConfirm = {},
                            onRefresh = { refreshed.incrementAndGet() },
                            onExit = { exited.incrementAndGet() },
                            backgroundState = WorkspaceBackgroundUiState(workspace = invalid),
                            trailingOverlay = {
                                TextButton(
                                    onClick = { supportOpen = true },
                                    modifier = Modifier.testTag("terminal-selection-help"),
                                ) { Text("Help") }
                                if (supportOpen) {
                                    LiquidAlertDialog(
                                        onDismissRequest = { supportOpen = false },
                                        title = {
                                            val popupView = LocalView.current
                                            SideEffect { popupRoot.set(popupView.rootView) }
                                            Text("Workspace setup help")
                                        },
                                        text = {
                                            Box(
                                                Modifier.fillMaxWidth().height(72.dp)
                                                    .liquidWorkspaceSurface(
                                                        Radius.shapeSm,
                                                        WorkspaceGlassRole.UTILITY,
                                                    )
                                                    .testTag("terminal-selection-support-panel"),
                                            ) { Text("Review the till beside this tablet before continuing.") }
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = { supportOpen = false },
                                                modifier = Modifier.testTag("terminal-selection-support-close"),
                                            ) { Text("Close") }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        compose.waitUntil(8_000L) { tagCount("terminal-selection-background-fallback-workspace") == 1 }
        compose.waitUntil(8_000L) { tagCount("terminal-selection-glass-ready-workspace") == 1 }
        compose.onNodeWithTag("terminal-selection-choice-6").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.onNodeWithTag("terminal-selection-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("terminal-selection-refresh").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("terminal-selection-confirm").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("terminal-selection-exit").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, refreshed.get())
        assertEquals(1, exited.get())

        compose.onNodeWithTag("terminal-selection-help").performClick()
        compose.onNodeWithTag("terminal-selection-support-panel").assertIsDisplayed()
        val dialogRoot = requireNotNull(popupRoot.get())
        assertNotEquals(compose.activity.window.decorView, dialogRoot)
        assertEquals(RequestedTerminalSelectionDisplayId, dialogRoot.display?.displayId)
        compose.onNodeWithTag("terminal-selection-support-close").performClick()
        compose.onNodeWithTag("terminal-selection-support-panel").assertDoesNotExist()

        val bitmap = compose.onNodeWithTag("terminal-selection-root").captureToImage().asAndroidBitmap()
        try {
            assertTrue("The fallback scene and liquid panels must render non-uniform content", sampledColorCount(bitmap) >= 12)
        } finally {
            bitmap.recycle()
        }
        capture(
            name = "terminal-selection-fallback-compact-day",
            nodeTag = "terminal-selection-root",
            expectedWidthDp = 600,
            expectedHeightDp = 520,
            fontScale = 1.3f,
            appearance = "Day",
            sceneState = "bundled_fallback",
        )
    }

    private fun terminalScrollValue(): Float = compose.onNodeWithTag("terminal-selection-panel")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun tagCount(tag: String): Int = compose.onAllNodes(
        hasTestTag(tag),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun assertVerifiedDisplay() {
        val display = requireNotNull(compose.activity.display)
        assertEquals(RequestedTerminalSelectionDisplayId, display.displayId)
        assertEquals(2_560, display.mode.physicalWidth)
        assertEquals(1_600, display.mode.physicalHeight)
        assertEquals(320, compose.activity.resources.displayMetrics.densityDpi)
    }

    private fun importedWorkspaceImage(name: String, generation: Long): WorkspaceBackgroundImage {
        val file = File(compose.activity.cacheDir, "$name.png")
        temporaryFiles += file
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(18, 28, 54))
            val paint = android.graphics.Paint().apply { color = Color.rgb(176, 128, 54) }
            canvas.drawRect(0f, 0f, 320f, 400f, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return WorkspaceBackgroundImage(
            slot = WorkspaceBackgroundSlot.WORKSPACE,
            source = WorkspaceBackgroundSource.IMPORTED,
            width = 640,
            height = 400,
            generation = generation,
            contentId = "$name-content",
            privatePath = file.absolutePath,
        )
    }

    private fun sampledColorCount(bitmap: Bitmap): Int {
        val colors = linkedSetOf<Int>()
        val xStep = (bitmap.width / 20).coerceAtLeast(1)
        val yStep = (bitmap.height / 12).coerceAtLeast(1)
        for (y in 0 until bitmap.height step yStep) {
            for (x in 0 until bitmap.width step xStep) colors += bitmap.getPixel(x, y)
        }
        return colors.size
    }

    private fun capture(
        name: String,
        nodeTag: String,
        expectedWidthDp: Int,
        expectedHeightDp: Int,
        fontScale: Float,
        appearance: String,
        sceneState: String,
    ) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(nodeTag).captureToImage().asAndroidBitmap()
        try {
            val density = compose.activity.resources.displayMetrics.density
            assertEquals((expectedWidthDp * density).roundToInt(), bitmap.width)
            assertEquals((expectedHeightDp * density).roundToInt(), bitmap.height)
            val directory = captureDirectory()
            val png = directory.resolve("$name.png")
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val display = requireNotNull(compose.activity.display)
            directory.resolve("$name.provenance.json").writeText(
                """
                {
                  "capture_run_id": ${argument(TerminalSelectionRunArgument)},
                  "test_class": "cloud.dcompany.erp.ui.screens.TerminalSelectionUiTest",
                  "source_inventory_sha256": ${argument(TerminalSelectionSourceArgument)},
                  "target_apk_sha256": ${argument(TerminalSelectionTargetArgument)},
                  "test_apk_sha256": ${argument(TerminalSelectionTestArgument)},
                  "process_pid": ${Process.myPid()},
                  "android_api": ${Build.VERSION.SDK_INT},
                  "observed_display_id": ${display.displayId},
                  "display_mode_width_px": ${display.mode.physicalWidth},
                  "display_mode_height_px": ${display.mode.physicalHeight},
                  "density": $density,
                  "density_dpi": ${compose.activity.resources.displayMetrics.densityDpi},
                  "expected_width_dp": $expectedWidthDp,
                  "expected_height_dp": $expectedHeightDp,
                  "font_scale": $fontScale,
                  "appearance": "$appearance",
                  "scene_state": "$sceneState",
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

    private fun captureDirectory(): File {
        val runId = InstrumentationRegistry.getArguments().getString(TerminalSelectionRunArgument)
            ?: error("Missing $TerminalSelectionRunArgument")
        require(runId.matches(Regex("[0-9a-f]{32}")))
        return InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("terminal-selection-glass-r1")
            .resolve("display-$RequestedTerminalSelectionDisplayId-hd")
            .resolve(runId)
            .apply { check(isDirectory || mkdirs()) }
    }

    private fun argument(name: String): String {
        val value = InstrumentationRegistry.getArguments().getString(name) ?: error("Missing $name")
        val length = if (name == TerminalSelectionRunArgument) 32 else 64
        require(value.matches(Regex("[0-9a-f]{$length}")))
        return "\"$value\""
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

@Composable
private fun RecordTerminalScene(target: AtomicReference<TerminalSceneEvidence?>) {
    val scene = LocalWorkspaceGlassScene.current
    SideEffect {
        target.set(scene?.let {
            TerminalSceneEvidence(
                contentId = it.frame.actualKey.contentId,
                generation = it.frame.actualKey.generation,
                viewportWidth = it.frame.actualKey.viewportWidth,
                viewportHeight = it.frame.actualKey.viewportHeight,
                frameEpoch = it.frame.epoch,
                derivativeEpoch = it.softenedKey?.frameEpoch,
                derivativeContentId = it.softenedKey?.actualKey?.contentId,
            )
        })
    }
}

private fun terminal(id: String, name: String, purpose: String): Terminal = Terminal(
    id = id,
    name = name,
    branchId = "branch-one",
    purpose = purpose,
)
