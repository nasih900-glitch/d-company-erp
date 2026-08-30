package cloud.dcompany.erp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class GamingCentreNavigationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hiddenRestoredMembershipRouteNeverEntersWorkspaceContent() {
        val focused = listOf(
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Inventory,
            Destination.Help,
        )
        compose.setContent {
            DCompanyTheme {
                WorkspaceScaffold(
                    destinations = focused,
                    currentDestination = Destination.Memberships,
                    employeeName = "Rafi",
                    locationLabel = "Gaming Centre",
                    connectivityProblem = SyncAvailabilityProblem.NONE,
                    outboxWorkStatus = OutboxWorkStatus(),
                    syncing = false,
                    canChangeTill = false,
                    onOpenSupport = {},
                    onChangeTill = {},
                    onSignOut = {},
                ) { destination, _ ->
                    Text("Current: ${destination.label}")
                }
            }
        }

        compose.onNodeWithText("Current: Gaming").assertExists()
        compose.onNodeWithContentDescription(
            "Gaming. Manage stations and sessions",
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithContentDescription(
            "Help. Report a problem or ask what to do next",
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithContentDescription(
            "Memberships. Manage plans and member credit",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun connectionChangesNeverMoveOrResizeTheActiveWorkflow() {
        val problem = mutableStateOf(SyncAvailabilityProblem.NONE)
        compose.setContent {
            DCompanyTheme {
                WorkspaceScaffold(
                    destinations = listOf(Destination.Gaming, Destination.Help),
                    currentDestination = Destination.Gaming,
                    employeeName = "Rafi",
                    locationLabel = "Gaming Centre",
                    connectivityProblem = problem.value,
                    outboxWorkStatus = OutboxWorkStatus(),
                    syncing = false,
                    canChangeTill = false,
                    onOpenSupport = {},
                    onChangeTill = {},
                    onSignOut = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize().testTag("active-workflow"))
                }
            }
        }

        val original = compose.onNodeWithTag("active-workflow").fetchSemanticsNode().boundsInRoot
        repeat(30) { index ->
            val next = when (index % 5) {
                0 -> SyncAvailabilityProblem.VERIFYING
                1 -> SyncAvailabilityProblem.NO_NETWORK
                2 -> SyncAvailabilityProblem.SERVER_UNREACHABLE
                3 -> SyncAvailabilityProblem.RECOVERING
                else -> SyncAvailabilityProblem.NONE
            }
            compose.runOnIdle { problem.value = next }
            compose.waitForIdle()
            val current = compose.onNodeWithTag("active-workflow").fetchSemanticsNode().boundsInRoot
            assertEquals(original.top, current.top, 0.1f)
            assertEquals(original.height, current.height, 0.1f)
        }

        compose.runOnIdle { problem.value = SyncAvailabilityProblem.SERVER_UNREACHABLE }
        compose.onNodeWithContentDescription(
            "Connection status: Server issue. Open connection details",
        ).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        ).performClick()
        compose.onNodeWithText("ERP server unavailable").assertExists()
        // Opening details never removes the persistent status control.
        compose.onNodeWithContentDescription(
            "Connection status: Server issue. Open connection details",
        ).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription(
            "Connection status: Server issue. Open connection details",
        ).assertExists()
    }
}
