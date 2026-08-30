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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Regression coverage for bounded compact and target-tablet Shift layouts. */
class ShiftCompactUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingFloatAndPrimaryActionRemainReachableInCompactShiftPanels() {
        var submittedMinor: Long? = null

        compose.setContent {
            DCompanyTheme {
                // The app shell and summary row leave approximately this much
                // vertical space on the 960 x 600dp production layout.
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

        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performScrollTo()
            .assertIsDisplayed()
            .performTextReplacement("210.50")

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
                Box(Modifier.width(960.dp).height(680.dp)) {
                    DrawerCountDialogContent(
                        initialCounts = emptyMap(),
                        expectedMinor = 50_200L,
                        enabled = true,
                        onDismiss = {},
                        onApply = { applied = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

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
    fun target1280By800WideLayoutKeepsOpeningMoneyEntryAndActionReachable() {
        var submittedMinor: Long? = null

        compose.setContent {
            DCompanyTheme {
                TargetTabletWideShiftFrame(stateIdentity = "closed") {
                    OpenShiftForm(
                        online = true,
                        busy = false,
                        canOpen = true,
                        blockedByRejectedShift = false,
                        onOpenShift = { submittedMinor = it },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performScrollTo()
            .assertIsDisplayed()
            .performTextReplacement("210.50")

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
 * Recreates the exact 1280 x 800dp target window: 88dp compact rail, 68dp
 * header, Shift page padding, 82dp summary cards and the 350dp history panel.
 * This catches regressions that a wide-only breakpoint test misses when the
 * available height is still tablet-sized.
 */
@Composable
private fun TargetTabletWideShiftFrame(
    stateIdentity: String,
    currentPanel: @Composable () -> Unit,
) {
    Row(Modifier.width(1_280.dp).height(800.dp)) {
        Spacer(Modifier.width(88.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Spacer(Modifier.height(68.dp))
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
