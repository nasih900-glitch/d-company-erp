package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Regression coverage for bounded compact and target-tablet Shift layouts. */
class ShiftCompactUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun closedShiftSummaryShowsCompleteBusinessDayDetail() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(560.dp)) {
                    ShiftSummaryRow(ShiftUiState(currentShiftLoaded = true))
                }
            }
        }

        compose.onNodeWithText("No open shift").assertIsDisplayed()
        compose.onNodeWithText("Business-day collections").assertIsDisplayed()
        val detail = compose.onNodeWithText("IST business days").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        detail.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }
        assertEquals(1, layouts.single().lineCount)
        assertEquals(false, layouts.single().isLineEllipsized(0))
    }

    @Test
    fun stackedOpenShiftFormFillsWideParentWithCompleteTouchControls() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(760.dp).testTag("wide-stacked-shift-parent")) {
                    OpenShiftForm(
                        online = true,
                        busy = false,
                        canOpen = true,
                        blockedByRejectedShift = false,
                        onOpenShift = {},
                    )
                }
            }
        }

        val parent = compose.onNodeWithTag("wide-stacked-shift-parent")
            .getUnclippedBoundsInRoot()
        val form = compose.onNodeWithTag("shift-open-form")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(
            "The stacked opening form should consume its caller's available width",
            (parent.right - parent.left).value,
            (form.right - form.left).value,
            1f,
        )
        listOf("Decimal point", "Digit 0", "Delete last digit", "Clear Opening float (₹)")
            .forEach { description ->
                compose.onNodeWithContentDescription(description)
                    .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
        compose.onNodeWithText("Open shift with ₹0.00")
            .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun openingFloatAndPrimaryActionRemainReachableInCompactShiftPanels() {
        var submittedMinor: Long? = null

        compose.setContent {
            DCompanyTheme {
                val density = LocalDensity.current
                // The app shell and summary row leave approximately this much
                // vertical space on the 960 x 600dp production layout.
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 1.3f),
                ) {
                    Box(Modifier.width(872.dp).height(360.dp)) {
                        CompactShiftPanels(
                            stateIdentity = "closed",
                            currentPanel = {
                                OpenShiftForm(
                                    online = true,
                                    busy = false,
                                    canOpen = true,
                                    blockedByRejectedShift = false,
                                    onOpenShift = { submittedMinor = it },
                                )
                            },
                            historyPanel = {
                                Box(Modifier.height(280.dp)) { Text("Past shifts") }
                            },
                        )
                    }
                }
            }
        }

        // Primary action must be visible before any scroll, not merely
        // technically reachable below an always-expanded touch keypad.
        compose.onNodeWithText("Open shift with ₹0.00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performScrollTo()
            .assertIsDisplayed()
        listOf("Decimal point", "Digit 0", "Delete last digit", "Clear Opening float (₹)")
            .forEach { description ->
                compose.onNodeWithContentDescription(description)
                    .performScrollTo().assertIsDisplayed()
            }
        compose.onNodeWithContentDescription("Decimal point").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Digit 0").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Delete last digit").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Clear Opening float (₹)").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performScrollTo().performTextReplacement("210.50")

        compose.onNodeWithText("Open shift with ₹210.50")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { assertEquals(21_050L, submittedMinor) }
        compose.onNodeWithContentDescription("Opening float (₹), 210.50")
            .assertIsNotFocused()
    }

    @Test
    fun newShiftIdentityStartsReplacementPanelAtItsTop() {
        compose.setContent {
            DCompanyTheme {
                var stateIdentity by remember { mutableStateOf("closed") }
                Box(Modifier.width(872.dp).height(360.dp)) {
                    CompactShiftPanels(
                        stateIdentity = stateIdentity,
                        currentPanel = {
                            if (stateIdentity == "closed") {
                                OpenShiftForm(
                                    online = true,
                                    busy = false,
                                    canOpen = true,
                                    blockedByRejectedShift = false,
                                    onOpenShift = { stateIdentity = "local:shift-1" },
                                )
                            } else {
                                // Mirrors the close panel: its heading must be
                                // visible even though the body is much taller
                                // than this compact viewport.
                                Column {
                                    Text("Close current shift")
                                    Spacer(Modifier.height(700.dp))
                                    Text("Close panel end")
                                }
                            }
                        },
                        historyPanel = {
                            Box(Modifier.height(280.dp)) { Text("Past shifts") }
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Open shift with ₹0.00")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.waitForIdle()
        compose.onNodeWithText("Close current shift").assertIsDisplayed()
    }

    @Test
    fun denominationInputsRemainReachableInsideCompactShiftScroll() {
        compose.setContent {
            DCompanyTheme {
                val counts = remember { mutableStateMapOf<Long, String>() }
                Box(Modifier.width(872.dp).height(360.dp)) {
                    CompactShiftPanels(
                        stateIdentity = "open:shift-1",
                        currentPanel = {
                            Column {
                                Text("Count the drawer")
                                DenominationCountGrid(
                                    compactLayout = true,
                                    counts = counts,
                                    onCountChange = { note, value -> counts[note] = value },
                                    enabled = true,
                                )
                                Text("Close shift")
                            }
                        },
                        historyPanel = {
                            Box(Modifier.height(280.dp)) { Text("Past shifts") }
                        },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Count of ₹500 notes")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performTextReplacement("1")

        compose.onNodeWithText("₹500.00")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Close shift")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun drawerCountDialogKeepsEveryDenominationReachableAtTabletLandscapeSize() {
        var applied: Map<Long, String>? = null

        compose.setContent {
            DCompanyTheme {
                // A 1280 x 800dp app window leaves roughly this bounded dialog
                // surface after system bars and the dialog's outer margin.
                Box(Modifier.width(960.dp).height(680.dp).testTag("drawer-host")) {
                    DrawerCountDialogContent(
                        initialCounts = emptyMap(),
                        expectedMinor = 50_200L,
                        enabled = true,
                        onDismiss = {},
                        onApply = { applied = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
                    )
                }
            }
        }

        val hostBounds = compose.onNodeWithTag("drawer-host").getUnclippedBoundsInRoot()
        val dialogBounds = compose.onNodeWithContentDescription("Drawer count dialog")
            .getUnclippedBoundsInRoot()
        val hostHeight = hostBounds.bottom - hostBounds.top
        val dialogHeight = dialogBounds.bottom - dialogBounds.top
        assertTrue("Drawer count should wrap its content on a tablet", dialogHeight < hostHeight - 48.dp)

        DENOMINATIONS.forEach { note ->
            compose.onNodeWithContentDescription("Count of ₹$note notes")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
        }

        compose.onNodeWithContentDescription("Count of ₹500 notes")
            .performScrollTo()
            .performTextReplacement("1")
        compose.onNodeWithContentDescription("Count of ₹1 notes")
            .performScrollTo()
            .performTextReplacement("2")
        compose.onNodeWithText("Use drawer count")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals("1", applied?.get(500L))
            assertEquals("2", applied?.get(1L))
            assertEquals(50_200L, drawerCountedMinor(applied.orEmpty()))
        }
    }

    @Test
    fun drawerCountDialogScrollsToFinalDenominationAndApplyActionInCompactViewport() {
        var applied: Map<Long, String>? = null

        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(360.dp).height(400.dp)) {
                    DrawerCountDialogContent(
                        initialCounts = emptyMap(),
                        expectedMinor = 100L,
                        enabled = true,
                        onDismiss = {},
                        onApply = { applied = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Count of ₹1 notes")
            .performScrollTo()
            .assertIsDisplayed()
            .performTextReplacement("1")
        compose.onNodeWithText("Use drawer count")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle { assertEquals("1", applied?.get(1L)) }
    }

    @Test
    fun actualDrawerCountDialogKeepsApplyReachableWhileLowestDenominationIsBeingEdited() {
        var applied: Map<Long, String>? = null

        compose.setContent {
            DCompanyTheme {
                DrawerCountDialog(
                    initialCounts = emptyMap(),
                    expectedMinor = 100L,
                    enabled = true,
                    onDismiss = {},
                    onApply = { applied = it },
                )
            }
        }

        // The Dialog creates its own window. Earlier tests mounted only its
        // content, so they could not catch reachability lost at that boundary.
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithContentDescription("Count of ₹1 notes")
            .performScrollTo()
            .performClick()
            .assertIsFocused()
            .performTextReplacement("1")
        compose.onNodeWithText("Use drawer count")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { assertEquals("1", applied?.get(1L)) }
    }

    @Test
    fun target1280By800WideLayoutKeepsOpeningMoneyEntryAndActionReachable() {
        var submittedMinor: Long? = null
        var submitCount = 0

        compose.setContent {
            DCompanyTheme {
                TargetTabletWideShiftFrame(stateIdentity = "closed") {
                    OpenShiftForm(
                        online = true,
                        busy = false,
                        canOpen = true,
                        blockedByRejectedShift = false,
                        onOpenShift = {
                            submittedMinor = it
                            submitCount++
                        },
                    )
                }
            }
        }

        compose.onNodeWithText("Open shift with ₹0.00").assertIsDisplayed()
        listOf("Digit 2", "Decimal point", "Digit 0", "Delete last digit", "Clear Opening float (₹)")
            .forEach { description ->
                compose.onNodeWithContentDescription(description)
                    .performScrollTo().assertIsDisplayed()
            }
        listOf("Digit 2", "Decimal point", "Digit 5", "Digit 0")
            .forEach { description ->
                compose.onNodeWithContentDescription(description).performScrollTo().performClick()
            }
        compose.onNodeWithContentDescription("Opening float (₹), 2.50").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete last digit").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Opening float (₹), 2.5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear Opening float (₹)")
            .performScrollTo().performClick()
        compose.onNodeWithContentDescription("Opening float (₹), zero").assertIsDisplayed()
        listOf("Digit 2", "Digit 1", "Digit 0", "Decimal point", "Digit 5", "Digit 0")
            .forEach { description ->
                compose.onNodeWithContentDescription(description).performScrollTo().performClick()
            }

        compose.onNodeWithText("Open shift with ₹210.50")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(21_050L, submittedMinor)
            assertEquals(1, submitCount)
        }
        compose.onNodeWithContentDescription("Opening float (₹), 210.50")
            .assertIsNotFocused()
    }

    @Test
    fun target1280By800WideLayoutKeepsCloseControlsReachableBelowCountedTotals() {
        var closeRequested = false

        compose.setContent {
            DCompanyTheme {
                TargetTabletWideShiftFrame(stateIdentity = "open:shift-1") {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Close current shift")
                        // Mirrors the operational close panel's accounting,
                        // drawer-count and recovery content above its actions.
                        Spacer(Modifier.height(620.dp))
                        Text("Counted")
                        Text("Difference")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ErpButton(
                                text = "Refresh",
                                onClick = {},
                                intent = ActionIntent.Secondary,
                            )
                            Spacer(Modifier.weight(1f))
                            ErpButton(
                                text = "Close shift",
                                onClick = { closeRequested = true },
                                intent = ActionIntent.Destructive,
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Close shift")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { assertEquals(true, closeRequested) }
    }
}

/**
 * Exercises a conservative Shift viewport inside a 1280 x 800dp window with
 * a 56dp control-deck header and a reserved 88dp rail. The live Shift page
 * uses the full width, so this remains the tighter layout for regression QA.
 */
@Composable
private fun TargetTabletWideShiftFrame(
    stateIdentity: String,
    currentPanel: @Composable () -> Unit,
) {
    Row(Modifier.width(1_280.dp).height(800.dp)) {
        Spacer(Modifier.width(88.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Spacer(Modifier.height(56.dp))
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(82.dp)) { Text("Shift summary") }
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WideCurrentShiftPanel(
                        stateIdentity = stateIdentity,
                        modifier = Modifier.weight(1f),
                        currentPanel = currentPanel,
                    )
                    Box(Modifier.width(350.dp).fillMaxHeight()) { Text("Past shifts") }
                }
            }
        }
    }
}
