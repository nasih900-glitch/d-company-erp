package cloud.dcompany.erp.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.WorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.createGlassComposeRule
import cloud.dcompany.erp.ui.theme.syntheticBitmapScene
import cloud.dcompany.erp.ui.theme.workspaceGlassRoleStyle
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidSharedComponentsUiTest {
    @get:Rule val compose = createGlassComposeRule()

    @Test
    fun blankSectionCardsRespondToTheSameControlledScene() {
        val dark = solidScene(600, 260, AndroidColor.rgb(18, 52, 166))
        val bright = solidScene(600, 260, AndroidColor.rgb(246, 208, 42))
        val scene = mutableStateOf(dark)
        renderScene(600, 260, scene) {
            Row(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    SectionCard(
                        modifier = Modifier.height(144.dp).testTag("shared-blank-a"),
                        contentPadding = PaddingValues(0.dp),
                    ) {}
                }
                Box(Modifier.weight(1f)) {
                    SectionCard(
                        modifier = Modifier.height(144.dp).testTag("shared-blank-b"),
                        contentPadding = PaddingValues(0.dp),
                    ) {}
                }
            }
        }
        compose.onNodeWithTag("shared-blank-a").assertIsDisplayed()
        compose.onNodeWithTag("shared-blank-b").assertIsDisplayed()
        val darkA = sample("shared-blank-a")
        val darkB = sample("shared-blank-b")

        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val brightA = sample("shared-blank-a")
        val brightB = sample("shared-blank-b")

        assertTrue("Panels at the same height must sample the same dark scene", colorDistance(darkA, darkB) <= 8)
        assertTrue("Panels at the same height must sample the same bright scene", colorDistance(brightA, brightB) <= 8)
        assertTrue("First panel must respond to scene replacement", colorDistance(darkA, brightA) >= 80)
        assertTrue("Second panel must respond to scene replacement", colorDistance(darkB, brightB) >= 80)
    }

    @Test
    fun sharedHeaderSearchAndTabsKeepNativeInteraction() {
        val scene = mutableStateOf(solidScene(600, 420, AndroidColor.rgb(44, 80, 168)))
        val query = mutableStateOf("")
        val selected = mutableStateOf("open")
        renderScene(600, 420, scene) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PageHeader(
                    title = "Shared inventory",
                    subtitle = "Native controls over the workspace scene",
                )
                SearchInput(
                    value = query.value,
                    onValueChange = { query.value = it },
                    placeholder = "Search inventory",
                    modifier = Modifier.fillMaxWidth().testTag("shared-search"),
                )
                PremiumTabBar(
                    options = listOf(
                        TabOption("open", "Open", 3),
                        TabOption("closed", "Closed", 7),
                    ),
                    selectedId = selected.value,
                    onSelect = { selected.value = it },
                    modifier = Modifier.testTag("shared-tabs"),
                )
            }
        }

        compose.onNodeWithText("Shared inventory").assertIsDisplayed()
        val search = compose.onNodeWithTag("shared-search")
        search.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        search.performTextReplacement("Arcade")
        search.assertTextEquals("Arcade")
        compose.onNodeWithText("Closed").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Search callback must retain exact native input", "Arcade", query.value)
            assertEquals("Tab callback must receive the exact selected id", "closed", selected.value)
        }
    }

    @Test
    fun premiumTabsUseGoldSelectionAndSceneResponsiveControlPills() {
        val dark = solidScene(600, 220, AndroidColor.rgb(24, 48, 154))
        val bright = solidScene(600, 220, AndroidColor.rgb(242, 198, 36))
        val scene = mutableStateOf(dark)
        val selected = mutableStateOf("open")
        val callbackCount = AtomicInteger()
        val expectedFill = AtomicInteger()
        val expectedContent = AtomicInteger()
        val drawnSelection = AtomicReference<String?>(null)
        renderScene(600, 220, scene) {
            val selectedForDraw = selected.value
            val glassAvailable = LocalWorkspaceGlassScene.current != null
            // Glass selection uses the reference gold in both appearance modes; fallback
            // surfaces retain their Material selection colors.
            val fillColor = if (glassAvailable) 0xFFFFD24A.toInt()
                else MaterialTheme.colorScheme.secondaryContainer.toArgb()
            val contentColor = if (glassAvailable) 0xFF181106.toInt()
                else MaterialTheme.colorScheme.onSurface.toArgb()
            SideEffect {
                expectedFill.set(fillColor)
                expectedContent.set(contentColor)
            }
            PremiumTabBar(
                options = listOf(
                    TabOption("open", "Open", 3),
                    TabOption("closed", "Closed", 7),
                ),
                selectedId = selectedForDraw,
                onSelect = {
                    callbackCount.incrementAndGet()
                    selected.value = it
                },
                modifier = Modifier.fillMaxWidth().padding(24.dp)
                    .drawWithContent {
                        drawContent()
                        drawnSelection.set(selectedForDraw)
                    }
                    .testTag("shared-gold-tabs"),
            )
        }

        compose.waitUntil(3_000) { drawnSelection.get() == "open" }
        val open = compose.onNodeWithTag("premium-tab-open").assertIsDisplayed().assertIsSelected()
        val closed = compose.onNodeWithTag("premium-tab-closed").assertIsDisplayed().assertIsNotSelected()
        val selectedDark = sample("premium-tab-open", xFraction = .08f, yFraction = .5f)
        val unselectedDark = sample("premium-tab-closed", xFraction = .08f, yFraction = .5f)
        assertTrue(
            "Selected scene tab must use the workspace reference gold fill",
            colorDistance(selectedDark, expectedFill.get()) <= 12,
        )
        assertEquals(
            "Selected label must use the dark content on the workspace gold fill",
            expectedContent.get(),
            textColor("premium-tab-label-open"),
        )
        assertTrue(
            "Selected gold and its content token must remain readable",
            contrast(expectedFill.get(), expectedContent.get()) >= 4.5,
        )

        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val selectedBright = sample("premium-tab-open", xFraction = .08f, yFraction = .5f)
        val unselectedBright = sample("premium-tab-closed", xFraction = .08f, yFraction = .5f)
        assertTrue(
            "Selected gold pill must remain stable across background replacement",
            colorDistance(selectedDark, selectedBright) <= 4,
        )
        assertTrue(
            "Unselected CONTROL glass pill must respond to its scene",
            colorDistance(unselectedDark, unselectedBright) >= 24,
        )

        closed.performClick()
        compose.onNodeWithTag("premium-tab-closed").assertIsSelected()
        compose.onNodeWithTag("premium-tab-open").assertIsNotSelected()
        compose.waitUntil(3_000) { drawnSelection.get() == "closed" }
        compose.runOnIdle {
            assertEquals("Tab callback must run once", 1, callbackCount.get())
            assertEquals("Tab callback must preserve the selected id", "closed", selected.value)
        }
        val movedSelection = sample("premium-tab-closed", xFraction = .08f, yFraction = .5f)
        assertTrue(
            "The newly selected tab must receive the same workspace reference gold fill: " +
                "actual=$movedSelection expected=${expectedFill.get()} " +
                "distance=${colorDistance(movedSelection, expectedFill.get())}",
            colorDistance(movedSelection, expectedFill.get()) <= 12,
        )
        assertEquals(expectedContent.get(), textColor("premium-tab-label-closed"))
        open.assertIsDisplayed()
    }

    @Test
    fun nestedSharedPanelsRemainProjectedAndReadable() {
        val dark = solidScene(600, 520, AndroidColor.rgb(26, 54, 168))
        val bright = solidScene(600, 520, AndroidColor.rgb(248, 205, 38))
        val scene = mutableStateOf(dark)
        val textColor = AtomicInteger()
        renderScene(600, 520, scene) {
            val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
            SideEffect { textColor.set(onSurface) }
            SectionCard(
                modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("shared-outer-panel"),
                title = "Nested materials",
                subtitle = "Each child projects from the scene instead of stacking an opaque fill",
            ) {
                CompactStatCard(
                    label = "Current shift",
                    value = "AED 125.00",
                    detail = "Verified total",
                    icon = Icons.Default.Info,
                    modifier = Modifier.fillMaxWidth().testTag("shared-nested-stat"),
                )
                SectionCard(
                    modifier = Modifier.height(72.dp).testTag("shared-nested-blank"),
                    contentPadding = PaddingValues(0.dp),
                ) {}
            }
        }
        compose.onNodeWithText("Nested materials").assertIsDisplayed()
        compose.onNodeWithText("AED 125.00").assertIsDisplayed()
        compose.onNodeWithTag("shared-nested-stat").assertIsDisplayed()
        val darkSample = sample("shared-nested-blank")

        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val brightSample = sample("shared-nested-blank")

        assertTrue("Nested material must respond to its scene", colorDistance(darkSample, brightSample) >= 80)
        assertTrue("Nested dark-scene text token must remain readable", contrast(textColor.get(), darkSample) >= 4.5)
        assertTrue("Nested bright-scene text token must remain readable", contrast(textColor.get(), brightSample) >= 4.5)
    }

    @Test
    fun missingSceneUsesSolidFallbackIndependentOfBackdrop() {
        val backdrop = mutableStateOf(Color(0xFF9A1D32))
        renderWithoutScene(600, 240, backdrop) {
            OperationalBanner(
                title = "Offline queue available",
                detail = "Shared status remains readable without a projected scene.",
                tone = UiTone.Warning,
                icon = Icons.Default.Warning,
                modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("shared-fallback-banner"),
            )
        }
        compose.onNodeWithText("Offline queue available").assertIsDisplayed()
        val first = sample("shared-fallback-banner", xFraction = .90f, yFraction = .72f)

        compose.runOnIdle { backdrop.value = Color(0xFF124EA0) }
        compose.waitForIdle()
        val second = sample("shared-fallback-banner", xFraction = .90f, yFraction = .72f)

        assertTrue("Scene-absent fallback must be solid across backdrop changes", colorDistance(first, second) <= 4)
        assertTrue("Fallback must not expose the first backdrop", colorDistance(first, Color(0xFF9A1D32).toArgb()) >= 80)
        assertTrue("Fallback must not expose the second backdrop", colorDistance(second, Color(0xFF124EA0).toArgb()) >= 80)
    }

    @Test
    fun liquidAlertDialogProjectsSceneAndKeepsBothCallbacksReachable() {
        val dark = solidScene(720, 600, AndroidColor.rgb(24, 54, 176))
        val bright = solidScene(720, 600, AndroidColor.rgb(246, 204, 38))
        val scene = mutableStateOf(dark)
        val showing = mutableStateOf(true)
        val confirms = AtomicInteger()
        val dismisses = AtomicInteger()
        renderScene(720, 600, scene) {
            if (showing.value) {
                LiquidAlertDialog(
                    onDismissRequest = {
                        dismisses.incrementAndGet()
                        showing.value = false
                    },
                    confirmButton = {
                        TextButton(onClick = { confirms.incrementAndGet() }) { Text("Keep") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dismisses.incrementAndGet()
                            showing.value = false
                        }) { Text("Dismiss") }
                    },
                    title = { Text("Confirm local action") },
                    text = { Text("This fixture has no business callback.") },
                    modifier = Modifier.width(440.dp).testTag("shared-liquid-alert"),
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                )
            }
        }

        compose.onNodeWithTag("shared-liquid-alert").assertIsDisplayed()
        compose.onNodeWithText("Keep").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        val darkSample = sample("shared-liquid-alert", xFraction = .88f, yFraction = .18f)
        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val brightSample = sample("shared-liquid-alert", xFraction = .88f, yFraction = .18f)
        assertTrue(
            "Opted-in modal material must respond to the controlled scene",
            colorDistance(darkSample, brightSample) >= 12,
        )

        compose.onNodeWithText("Keep").performClick()
        compose.runOnIdle { assertEquals(1, confirms.get()) }
        compose.onNodeWithText("Dismiss").performClick()
        compose.runOnIdle { assertEquals(1, dismisses.get()) }
    }

    @Test
    fun alertPopupProjectsOnlyNeutralDescendantsOwnedByItsActualRoot() {
        val dark = solidScene(720, 640, AndroidColor.rgb(24, 54, 176))
        val bright = solidScene(720, 640, AndroidColor.WHITE)
        val scene = mutableStateOf(dark)
        val rawShowing = mutableStateOf(false)
        val alertRoot = AtomicReference<View>()
        val alertOwner = AtomicReference<View>()
        val rawRoot = AtomicReference<View>()
        val rawInheritedOwner = AtomicReference<View>()
        val semanticRoles = listOf(
            "error" to WorkspaceGlassRole.ERROR,
            "warning" to WorkspaceGlassRole.WARNING,
            "selected" to WorkspaceGlassRole.SELECTED,
            "disabled" to WorkspaceGlassRole.DISABLED,
        )
        renderScene(720, 640, scene) {
            LiquidAlertDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = {}) { Text("Done") } },
                title = { Text("Popup material ownership") },
                text = {
                    val currentRoot = LocalView.current.rootView
                    val currentOwner = LocalLiquidPopupRoot.current
                    SideEffect {
                        alertRoot.set(currentRoot)
                        alertOwner.set(currentOwner)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(52.dp)
                                .liquidWorkspaceSurface(
                                    role = WorkspaceGlassRole.UTILITY,
                                )
                                .testTag("shared-popup-neutral"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            semanticRoles.forEach { (tag, role) ->
                                Box(
                                    Modifier.weight(1f).height(52.dp)
                                        .liquidWorkspaceSurface(role = role)
                                        .testTag("shared-popup-semantic-$tag"),
                                )
                            }
                        }
                        if (rawShowing.value) {
                            Dialog(onDismissRequest = { rawShowing.value = false }) {
                                val nestedRoot = LocalView.current.rootView
                                val inheritedOwner = LocalLiquidPopupRoot.current
                                SideEffect {
                                    rawRoot.set(nestedRoot)
                                    rawInheritedOwner.set(inheritedOwner)
                                }
                                Box(
                                    Modifier.width(260.dp).height(96.dp)
                                        .liquidWorkspaceSurface(
                                            role = WorkspaceGlassRole.PANEL,
                                        )
                                        .testTag("shared-raw-nested-panel"),
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.width(440.dp).testTag("shared-popup-owner-alert"),
                properties = DialogProperties(usePlatformDefaultWidth = false),
            )
        }

        compose.onNodeWithTag("shared-popup-neutral").assertIsDisplayed()
        semanticRoles.forEach { (tag, _) ->
            compose.onNodeWithTag("shared-popup-semantic-$tag").assertIsDisplayed()
        }
        compose.runOnIdle {
            assertTrue(alertRoot.get() !== compose.activity.window.decorView)
            assertTrue("The shared alert must bind its actual popup root", alertOwner.get() === alertRoot.get())
        }
        val neutralDark = popupSample(alertRoot, "shared-popup-neutral", .5f, .5f)
        val semanticDark = semanticRoles.associate { (tag, _) ->
            tag to popupSample(alertRoot, "shared-popup-semantic-$tag", .5f, .5f)
        }
        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val neutralBright = popupSample(alertRoot, "shared-popup-neutral", .5f, .5f)
        val semanticBright = semanticRoles.associate { (tag, _) ->
            tag to popupSample(alertRoot, "shared-popup-semantic-$tag", .5f, .5f)
        }
        assertTrue(
            "A neutral descendant owned by the alert root must project the scene",
            colorDistance(neutralDark, neutralBright) >= 12,
        )
        semanticRoles.forEach { (tag, _) ->
            assertTrue(
                "Semantic $tag material must keep its established fill",
                colorDistance(checkNotNull(semanticDark[tag]), checkNotNull(semanticBright[tag])) <= 4,
            )
        }

        compose.runOnIdle {
            scene.value = dark
            rawShowing.value = true
        }
        compose.onNodeWithTag("shared-raw-nested-panel").assertIsDisplayed()
        val rawDark = popupSample(rawRoot, "shared-raw-nested-panel", .5f, .5f)
        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val rawBright = popupSample(rawRoot, "shared-raw-nested-panel", .5f, .5f)
        compose.runOnIdle {
            assertTrue("A raw nested Dialog must own a distinct root", rawRoot.get() !== alertRoot.get())
            assertTrue(
                "A raw nested Dialog inherits the alert owner instead of being silently opted in",
                rawInheritedOwner.get() === alertRoot.get(),
            )
        }
        assertTrue(
            "A neutral surface in an unapproved raw nested Dialog must retain fallback material",
            colorDistance(rawDark, rawBright) <= 4,
        )
    }

    @Test
    @OptIn(ExperimentalMaterial3Api::class)
    fun datePickerDialogBindsNeutralContentToItsActualPopupRoot() {
        val dark = solidScene(720, 640, AndroidColor.rgb(24, 54, 176))
        val bright = solidScene(720, 640, AndroidColor.WHITE)
        val scene = mutableStateOf(dark)
        val root = AtomicReference<View>()
        val owner = AtomicReference<View>()
        val confirms = AtomicInteger()
        val dismisses = AtomicInteger()
        renderScene(720, 640, scene) {
            LiquidDatePickerDialog(
                onDismissRequest = { dismisses.incrementAndGet() },
                confirmButton = {
                    TextButton(onClick = { confirms.incrementAndGet() }) { Text("Choose date") }
                },
                dismissButton = {
                    TextButton(onClick = { dismisses.incrementAndGet() }) { Text("Cancel date") }
                },
                modifier = Modifier.width(440.dp).testTag("shared-date-dialog"),
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                val currentRoot = LocalView.current.rootView
                val currentOwner = LocalLiquidPopupRoot.current
                SideEffect {
                    root.set(currentRoot)
                    owner.set(currentOwner)
                }
                Box(
                    Modifier.fillMaxWidth().height(72.dp)
                        .liquidWorkspaceSurface(role = WorkspaceGlassRole.CONTROL)
                        .testTag("shared-date-neutral"),
                ) {
                    Text(
                        "Selected local date",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.Center)
                            .testTag("shared-date-neutral-label"),
                    )
                }
            }
        }

        compose.onNodeWithTag("shared-date-dialog").assertIsDisplayed()
        compose.onNodeWithTag("shared-date-neutral").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(root.get() !== compose.activity.window.decorView)
            assertTrue("DatePicker content must bind its actual popup root", owner.get() === root.get())
        }
        val labelColor = textColor("shared-date-neutral-label")
        val darkSurface = popupSample(root, "shared-date-neutral", .90f, .5f)
        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val brightSurface = popupSample(root, "shared-date-neutral", .90f, .5f)
        assertTrue(
            "DatePicker neutral content must project both controlled scenes",
            colorDistance(darkSurface, brightSurface) >= 12,
        )
        assertTrue(
            "DatePicker content must retain the 4.8:1 body-text floor over both scenes",
            contrast(labelColor, darkSurface) >= 4.8 && contrast(labelColor, brightSurface) >= 4.8,
        )
        compose.onNodeWithText("Choose date").performClick()
        compose.onNodeWithText("Cancel date").performClick()
        compose.runOnIdle {
            assertEquals(1, confirms.get())
            assertEquals(1, dismisses.get())
        }
    }

    @Test
    fun nestedSharedDropdownRebindsArtworkToItsOwnPopupRoot() {
        val dark = solidScene(720, 640, AndroidColor.rgb(24, 54, 176))
        val bright = solidScene(720, 640, AndroidColor.WHITE)
        val scene = mutableStateOf(dark)
        val showing = mutableStateOf(true)
        val alertRoot = AtomicReference<View>()
        val menuRoot = AtomicReference<View>()
        val menuOwner = AtomicReference<View>()
        val selections = AtomicInteger()
        renderScene(720, 640, scene) {
            LiquidAlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = {
                    val currentRoot = LocalView.current.rootView
                    SideEffect { alertRoot.set(currentRoot) }
                    Text("Nested shared popup")
                },
                text = {
                    Box {
                        LiquidDropdownMenu(
                            expanded = showing.value,
                            onDismissRequest = { showing.value = false },
                            modifier = Modifier.width(280.dp).testTag("shared-nested-menu"),
                            role = WorkspaceGlassRole.CONTROL,
                        ) {
                            val currentRoot = LocalView.current.rootView
                            val currentOwner = LocalLiquidPopupRoot.current
                            SideEffect {
                                menuRoot.set(currentRoot)
                                menuOwner.set(currentOwner)
                            }
                            Box(
                                Modifier.fillMaxWidth().height(56.dp)
                                    .liquidWorkspaceSurface(role = WorkspaceGlassRole.PANEL)
                                    .testTag("shared-nested-menu-panel"),
                            )
                            DropdownMenuItem(
                                text = { Text("Choose fixture") },
                                onClick = {
                                    selections.incrementAndGet()
                                    showing.value = false
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.width(440.dp),
                properties = DialogProperties(usePlatformDefaultWidth = false),
            )
        }

        compose.onNodeWithTag("shared-nested-menu-panel").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue("The nested shared popup must have a distinct root", menuRoot.get() !== alertRoot.get())
            assertTrue("The nested shared popup must rebind the decorative owner", menuOwner.get() === menuRoot.get())
        }
        val darkSample = popupSample(menuRoot, "shared-nested-menu-panel", .5f, .5f)
        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val brightSample = popupSample(menuRoot, "shared-nested-menu-panel", .5f, .5f)
        assertTrue(
            "The nested shared popup's neutral descendant must project its scene",
            colorDistance(darkSample, brightSample) >= 12,
        )
        compose.onNodeWithText("Choose fixture").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selections.get()) }
    }

    @Test
    fun liquidDropdownProjectsSceneAndPreservesNativeSelection() {
        val dark = solidScene(720, 520, AndroidColor.rgb(24, 54, 176))
        val bright = solidScene(720, 520, AndroidColor.WHITE)
        val scene = mutableStateOf(dark)
        val showing = mutableStateOf(true)
        val selected = mutableStateOf<String?>(null)
        val callbacks = AtomicInteger()
        val popupRoot = AtomicReference<View>()
        val popupScene = AtomicReference<WorkspaceGlassScene?>(null)
        renderScene(720, 520, scene) {
            Box(Modifier.fillMaxSize().padding(start = 80.dp, top = 72.dp)) {
                if (showing.value) {
                    LiquidDropdownMenu(
                        expanded = true,
                        onDismissRequest = { showing.value = false },
                        modifier = Modifier.width(260.dp).testTag("shared-liquid-dropdown"),
                        role = WorkspaceGlassRole.CONTROL,
                    ) {
                        val view = LocalView.current.rootView
                        val projectedScene = LocalWorkspaceGlassScene.current
                        SideEffect {
                            popupRoot.set(view)
                            popupScene.set(projectedScene)
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Select arcade",
                                    Modifier.testTag("shared-liquid-dropdown-label"),
                                )
                            },
                            onClick = {
                                callbacks.incrementAndGet()
                                selected.value = "arcade"
                                showing.value = false
                            },
                        )
                        Spacer(
                            Modifier.fillMaxWidth().height(32.dp)
                                .testTag("shared-liquid-dropdown-material-probe"),
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("shared-liquid-dropdown").assertIsDisplayed()
        compose.onNodeWithText("Select arcade").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val darkSample = popupSample(
            popupRoot,
            "shared-liquid-dropdown-material-probe",
            xFraction = .5f,
            yFraction = .5f,
        )
        val darkTextColor = textColor("shared-liquid-dropdown-label")
        val menuBounds = compose.onNodeWithTag("shared-liquid-dropdown").fetchSemanticsNode().boundsInRoot
        val labelBounds = compose.onNodeWithTag(
            "shared-liquid-dropdown-label",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val labelYFraction = ((labelBounds.center.y - menuBounds.top) / menuBounds.height).coerceIn(0f, 1f)
        val darkLabelSurface = popupSample(
            popupRoot,
            "shared-liquid-dropdown",
            xFraction = .90f,
            yFraction = labelYFraction,
        )
        compose.runOnIdle { scene.value = bright }
        compose.waitUntil(5_000L) {
            popupScene.get()?.let { observed ->
                observed.frame.clearBitmap === bright &&
                    observed.softenedBitmap === bright &&
                    observed.softenedKey?.actualKey == observed.frame.actualKey &&
                    observed.softenedKey?.frameEpoch == observed.frame.epoch
            } == true
        }
        val brightSample = popupSample(
            popupRoot,
            "shared-liquid-dropdown-material-probe",
            xFraction = .5f,
            yFraction = .5f,
        )
        assertTrue(
            "Projected popup material must respond to the controlled scene: " +
                "dark=${Integer.toHexString(darkSample)} " +
                "bright=${Integer.toHexString(brightSample)}",
            colorDistance(darkSample, brightSample) >= 12,
        )
        val brightLabelSurface = popupSample(
            popupRoot,
            "shared-liquid-dropdown",
            xFraction = .90f,
            yFraction = labelYFraction,
        )
        assertTrue(
            "Native popup text must retain 4.5:1 over both projected scenes: " +
                "text=${Integer.toHexString(darkTextColor)} " +
                "dark=${Integer.toHexString(darkLabelSurface)} " +
                "bright=${Integer.toHexString(brightLabelSurface)}",
            contrast(darkTextColor, darkLabelSurface) >= 4.5 &&
                contrast(darkTextColor, brightLabelSurface) >= 4.5,
        )

        compose.onNodeWithText("Select arcade").performClick()
        compose.onNodeWithTag("shared-liquid-dropdown").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("arcade", selected.value)
            assertEquals("Popup selection callback must run exactly once", 1, callbacks.get())
        }
    }

    @Test
    fun liquidDropdownWithoutSceneKeepsMaterialFallbackIndependentOfBackdrop() {
        val backdrop = mutableStateOf(Color(0xFF9A1D32))
        val popupRoot = AtomicReference<View>()
        renderWithoutScene(600, 360, backdrop) {
            Box(Modifier.fillMaxSize().padding(start = 64.dp, top = 56.dp)) {
                LiquidDropdownMenu(
                    expanded = true,
                    onDismissRequest = {},
                    modifier = Modifier.width(260.dp).testTag("shared-fallback-dropdown"),
                ) {
                    val view = LocalView.current.rootView
                    SideEffect { popupRoot.set(view) }
                    DropdownMenuItem(text = { Text("Fallback option") }, onClick = {})
                }
            }
        }
        compose.onNodeWithTag("shared-fallback-dropdown").assertIsDisplayed()
        val first = popupSample(
            popupRoot,
            "shared-fallback-dropdown",
            xFraction = .88f,
            yFraction = .72f,
        )
        compose.runOnIdle { backdrop.value = Color(0xFF124EA0) }
        compose.waitForIdle()
        val second = popupSample(
            popupRoot,
            "shared-fallback-dropdown",
            xFraction = .88f,
            yFraction = .72f,
        )

        assertTrue(
            "Scene-absent popup must retain its solid Material fallback",
            colorDistance(first, second) <= 4,
        )
        assertTrue(colorDistance(first, Color(0xFF9A1D32).toArgb()) >= 80)
        assertTrue(colorDistance(second, Color(0xFF124EA0).toArgb()) >= 80)
    }

    @Test
    fun formDialogFieldAndActionsRemainNativeAndReachable() {
        val scene = mutableStateOf(solidScene(720, 640, AndroidColor.rgb(32, 62, 164)))
        val bright = solidScene(720, 640, AndroidColor.WHITE)
        val showing = mutableStateOf(true)
        val value = mutableStateOf("")
        val selected = mutableStateOf("Counter one")
        val confirms = AtomicInteger()
        val dismisses = AtomicInteger()
        val disabledSelections = AtomicInteger()
        val formRoot = AtomicReference<View>()
        val formOwner = AtomicReference<View>()
        renderScene(720, 640, scene) {
            val readyWorkspaceColors = MaterialTheme.colorScheme.copy(
                onSurfaceVariant = workspaceGlassRoleStyle(
                    WorkspaceGlassRole.PANEL,
                    day = false,
                ).secondaryContent,
            )
            MaterialTheme(colorScheme = readyWorkspaceColors) {
                if (showing.value) {
                    FormDialog(
                        title = "Edit local label",
                        confirmLabel = "Apply local label",
                        busy = false,
                        error = null,
                        onDismiss = {
                            dismisses.incrementAndGet()
                            showing.value = false
                        },
                        onConfirm = { confirms.incrementAndGet() },
                    ) {
                        val currentRoot = LocalView.current.rootView
                        val currentOwner = LocalLiquidPopupRoot.current
                        SideEffect {
                            formRoot.set(currentRoot)
                            formOwner.set(currentOwner)
                        }
                        Field(
                            label = "Local label",
                            value = value.value,
                            onChange = { value.value = it },
                            modifier = Modifier.testTag("shared-form-field"),
                        )
                        PickerField(
                            label = "Counter",
                            selectedLabel = selected.value,
                            options = listOf("one" to "Counter one", "two" to "Counter two"),
                            modifier = Modifier.testTag("shared-form-picker"),
                            onSelect = {
                                selected.value = if (it == "two") "Counter two" else "Counter one"
                            },
                        )
                        PickerField(
                            label = "Disabled destination",
                            selectedLabel = "No destination",
                            options = listOf("disabled" to "Unavailable"),
                            modifier = Modifier.testTag("shared-disabled-picker"),
                            enabled = false,
                            onSelect = { disabledSelections.incrementAndGet() },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Edit local label").assertIsDisplayed()
        val field = compose.onNodeWithTag("shared-form-field")
        field.assertIsDisplayed()
        field.performTextReplacement("Arcade seven")
        field.assertTextContains("Arcade seven")
        compose.runOnIdle {
            assertTrue("FormDialog must bind its actual popup root", formOwner.get() === formRoot.get())
        }
        val disabledPicker = compose.onNodeWithContentDescription(
            "Disabled destination, No destination",
        )
            .assertIsDisplayed()
            .assertIsNotEnabled()
        val disabledText = compose.onNodeWithText("No destination", useUnmergedTree = true)
        val disabledTextBounds = disabledText.fetchSemanticsNode().boundsInRoot
        val disabledPickerBounds = compose.onNodeWithTag("shared-disabled-picker")
            .fetchSemanticsNode().boundsInRoot
        val disabledLayouts = mutableListOf<TextLayoutResult>()
        disabledText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(disabledLayouts) }
        assertEquals(1, disabledLayouts.size)
        val disabledTextColor = disabledLayouts.single().layoutInput.style.color.toArgb()
        assertEquals(
            "The FormDialog fixture must use the ready workspace foreground contract",
            workspaceGlassRoleStyle(WorkspaceGlassRole.PANEL, day = false).secondaryContent.toArgb(),
            disabledTextColor,
        )
        val disabledY = ((disabledTextBounds.center.y - disabledPickerBounds.top) /
            disabledPickerBounds.height).coerceIn(0f, 1f)
        val disabledDarkSurface = popupSample(
            formRoot,
            "shared-disabled-picker",
            .75f,
            disabledY,
        )
        val pickerDark = popupSample(formRoot, "shared-form-picker", .88f, .78f)
        compose.runOnIdle { scene.value = bright }
        compose.waitForIdle()
        val pickerBright = popupSample(formRoot, "shared-form-picker", .88f, .78f)
        assertTrue(
            "A real FormDialog PickerField must project the scene instead of drawing a solid slab",
            colorDistance(pickerDark, pickerBright) >= 12,
        )
        compose.onNodeWithText("Counter one").assertIsDisplayed().performClick()
        compose.onNodeWithText("Counter two").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Counter two", selected.value) }
        val disabledBrightSurface = popupSample(
            formRoot,
            "shared-disabled-picker",
            .75f,
            disabledY,
        )
        val darkContrast = contrast(disabledTextColor, disabledDarkSurface)
        val brightContrast = contrast(disabledTextColor, disabledBrightSurface)
        assertTrue(
            "A disabled real PickerField must retain the 4.8:1 body-text floor over both scenes: " +
                "text=0x%08X dark=0x%08X ratio=%s bright=0x%08X ratio=%s".format(
                    disabledTextColor,
                    disabledDarkSurface,
                    darkContrast,
                    disabledBrightSurface,
                    brightContrast,
                ),
            darkContrast >= 4.8 && brightContrast >= 4.8,
        )
        disabledPicker.performTouchInput { click() }
        compose.onNodeWithText("Unavailable").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, disabledSelections.get()) }
        compose.onNodeWithText("Apply local label").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("Arcade seven", value.value)
            assertEquals(1, confirms.get())
            assertEquals(0, dismisses.get())
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismisses.get()) }
    }

    private fun renderScene(
        widthDp: Int,
        heightDp: Int,
        bitmap: androidx.compose.runtime.State<Bitmap>,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val scene = coordinates?.let {
                syntheticBitmapScene(
                    bitmap = bitmap.value,
                    rootCoordinates = it,
                    generation = bitmap.value.getPixel(0, 0).toLong() and 0xffffffffL,
                )
            }
            DCompanyTheme(AppearancePreference.Night) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalWorkspaceGlassScene provides scene) {
                            Box(
                                Modifier.width(widthDp.dp).height(heightDp.dp)
                                    .workspaceContentCanvas()
                                    .onGloballyPositioned { if (coordinates == null) coordinates = it }
                                    .testTag("shared-liquid-root"),
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun renderWithoutScene(
        widthDp: Int,
        heightDp: Int,
        backdrop: androidx.compose.runtime.State<Color>,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                Box(
                    Modifier.width(widthDp.dp).height(heightDp.dp)
                        .background(backdrop.value)
                        .testTag("shared-fallback-root"),
                ) {
                    CompositionLocalProvider(LocalWorkspaceGlassScene provides null) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun solidScene(widthDp: Int, heightDp: Int, color: Int): Bitmap {
        val density = compose.activity.resources.displayMetrics.density
        return Bitmap.createBitmap(
            (widthDp * density).roundToInt(),
            (heightDp * density).roundToInt(),
            Bitmap.Config.ARGB_8888,
        ).apply { eraseColor(color) }
    }

    private fun sample(tag: String, xFraction: Float = .5f, yFraction: Float = .5f): Int {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return try {
            bitmap.getPixel(
                (bitmap.width * xFraction).roundToInt().coerceIn(0, bitmap.width - 1),
                (bitmap.height * yFraction).roundToInt().coerceIn(0, bitmap.height - 1),
            )
        } finally {
            bitmap.recycle()
        }
    }

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
            val popupRoot = checkNotNull(rootRef.get()) { "$tag did not publish its popup root" }
            check(popupRoot !== compose.activity.window.decorView) {
                "$tag must render in the popup window, not the activity decor"
            }
            check(popupRoot.isAttachedToWindow && popupRoot.width > 0 && popupRoot.height > 0) {
                "$tag popup root must be attached with positive bounds"
            }
            check(popupRoot.display?.displayId == compose.activity.display?.displayId) {
                "$tag popup and activity must render on the requested display"
            }
            bitmap = Bitmap.createBitmap(
                popupRoot.width,
                popupRoot.height,
                Bitmap.Config.ARGB_8888,
            )
            popupRoot.draw(Canvas(bitmap))
        }
        return try {
            val x = bounds.left + bounds.width * xFraction
            val y = bounds.top + bounds.height * yFraction
            check(x >= 0f && x < bitmap.width && y >= 0f && y < bitmap.height) {
                "$tag sample ($x, $y) must be inside popup bitmap ${bitmap.width}x${bitmap.height}"
            }
            bitmap.getPixel(x.roundToInt(), y.roundToInt())
        } finally {
            bitmap.recycle()
        }
    }

    private fun textColor(tag: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("$tag must expose one native text layout", 1, results.size)
        return results.single().layoutInput.style.color.toArgb()
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs(AndroidColor.red(first) - AndroidColor.red(second)) +
            abs(AndroidColor.green(first) - AndroidColor.green(second)) +
            abs(AndroidColor.blue(first) - AndroidColor.blue(second))

    private fun contrast(first: Int, second: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= .04045) normalized / 12.92
            else Math.pow((normalized + .055) / 1.055, 2.4)
        }
        fun luminance(color: Int): Double =
            .2126 * channel(AndroidColor.red(color)) +
                .7152 * channel(AndroidColor.green(color)) +
                .0722 * channel(AndroidColor.blue(color))
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + .05) /
            (min(firstLuminance, secondLuminance) + .05)
    }
}
