package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.screens.shift.ShiftBlockerActions
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GamingNextActionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun perStationBookingDraftSurvivesResponsiveBranchChangesAndReturningToAStation() {
        val portrait = mutableStateOf(false)
        val selected = mutableStateOf("ps5-2")
        val stations = listOf(Station("ps5-1", "PS5-1", "PS5 Station 1", "ps5", 12000),
            Station("ps5-2", "PS5-2", "PS5 Station 2", "ps5", 12000))
        val packages = listOf(
            GamingPackage("single-30", "single-30", "ps5", variant = "single", kind = "base", name = "Single 30", durationMinutes = 30, priceMinor = 8000),
            GamingPackage("dual-60", "dual-60", "ps5", variant = "dual", includedPlayers = 2,
                maxPlayers = 4, kind = "base", name = "Dual 60", durationMinutes = 60, priceMinor = 15000))
        var observed: Map<String, GamingStartSelectionState> = emptyMap()
        compose.setContent {
            DCompanyTheme {
                val selections = rememberGamingStationStartSelections(stations, packages)
                SideEffect { observed = selections }
                val editor: @Composable () -> Unit = {
                    StartSessionEditor(station = stations.first { it.id == selected.value }, packages = packages,
                        onDismiss = {}, onConfirm = { _, _, _, _, _, _ -> }, embedded = true,
                        externallyHostedDial = true, pinActions = true, selectionState = selections.getValue(selected.value))
                }
                if (portrait.value) Column(Modifier.width(284.dp).height(620.dp)) { editor() }
                else Row(Modifier.width(380.dp).height(500.dp)) { editor() }
            }
        }
        compose.runOnIdle {
            observed.getValue("ps5-2").apply {
                selectedVariant.value = "dual"; playerCount.intValue = 4; selectedPackageId.value = "dual-60"
                customerQuery.value = "Synthetic draft"
            }
        }
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed()
        compose.onNodeWithTag("gaming-booked-player-count").assertTextEquals("4/4")
        compose.runOnIdle { portrait.value = true }
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed()
        compose.onNodeWithTag("gaming-booked-player-count").assertTextEquals("4/4")
        compose.runOnIdle {
            assertEquals("Synthetic draft", observed.getValue("ps5-2").customerQuery.value)
            selected.value = "ps5-1"
        }
        compose.onNodeWithText("Start 30 min · ₹80.00").assertIsDisplayed()
        compose.runOnIdle { selected.value = "ps5-2" }
        compose.onNodeWithText("Start 60 min · ₹210.00").assertIsDisplayed()
        compose.onNodeWithTag("gaming-booked-player-count").assertTextEquals("4/4")
    }

    @Test fun nextShiftActionsStayVisibleAndDoNotMoveThePrimaryButtonBaseline() {
        val blocked = mutableStateOf(true)
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(380.dp).height(500.dp)) {
                    StartSessionEditor(
                        station = Station("station", "PS5-1", "PS5 Station 1", "ps5", 12000),
                        packages = listOf(GamingPackage("base", "base-single-30", "ps5",
                            variant = "single", kind = "base", name = "Single 30 min", durationMinutes = 30, priceMinor = 8000)),
                        onDismiss = {}, onConfirm = { _, _, _, _, _, _ -> },
                        embedded = true, externallyHostedDial = true, pinActions = true,
                        startEnabled = !blocked.value,
                        startBlockMessage = if (blocked.value) "Open a shift before starting." else null,
                        blockerActions = {
                            if (blocked.value) ShiftBlockerActions(true, true, {}, {})
                        },
                    )
                }
            }
        }
        // No options scroll is needed to find the action beside the blocked Start.
        compose.onNodeWithTag("open-shift-here").assertIsDisplayed()
        compose.onNodeWithTag("sync-shift-here").assertIsDisplayed()
        val before = compose.onNodeWithTag("gaming-primary-action-host").getUnclippedBoundsInRoot()
        compose.runOnIdle { blocked.value = false }
        val after = compose.onNodeWithTag("gaming-primary-action-host").getUnclippedBoundsInRoot()
        assertEquals(before, after)
    }
}
