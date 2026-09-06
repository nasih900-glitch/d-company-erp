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
import androidx.compose.ui.test.assertIsDisplayed
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
    fun pendingOwnerSupportRequestIsPassiveUntilStaffOpensHelp() {
        val current = mutableStateOf(Destination.Pos)
        compose.setContent {
            DCompanyTheme {
                WorkspaceScaffold(
                    destinations = listOf(Destination.Pos, Destination.Help),
                    currentDestination = current.value,
                    employeeName = "Rafi",
                    locationLabel = "Gaming Centre",
                    connectivityProblem = SyncAvailabilityProblem.NONE,
                    outboxWorkStatus = OutboxWorkStatus(),
                    syncing = false,
                    remoteSupportRequestWaiting = true,
                    canChangeTill = false,
                    onOpenSupport = {},
                    onChangeTill = {},
                    onSignOut = {},
                    onReviewRemoteSupportRequest = { current.value = Destination.Help },
                    onDestinationChanged = { current.value = it },
                ) { destination, _ ->
                    Text("Current: ${destination.label}")
                }
            }
        }

        compose.onNodeWithText("Current: POS").assertIsDisplayed()
        compose.onNodeWithTag("remote-assistance-request-waiting").assertIsDisplayed()
        compose.onNodeWithText(
            "Owner support request waiting — open Help to review",
        ).assertIsDisplayed()

        compose.onNodeWithTag("remote-assistance-review-request").performClick()

        compose.onNodeWithText("Current: Help").assertIsDisplayed()
        compose.onNodeWithTag("remote-assistance-request-waiting").assertDoesNotExist()
    }

    @Test
    fun connectionAndSavedWorkChangesNeverMoveHeaderControlsOrActiveWorkflow() {
        val problem = mutableStateOf(SyncAvailabilityProblem.NONE)
        val outboxStatus = mutableStateOf(OutboxWorkStatus())
        val syncing = mutableStateOf(false)
        compose.setContent {
            DCompanyTheme {
                WorkspaceScaffold(
                    destinations = listOf(Destination.Gaming, Destination.Help),
                    currentDestination = Destination.Gaming,
                    employeeName = "Rafi",
                    locationLabel = "Gaming Centre",
                    connectivityProblem = problem.value,
                    outboxWorkStatus = outboxStatus.value,
                    syncing = syncing.value,
                    canChangeTill = false,
                    onOpenSupport = {},
                    onChangeTill = {},
                    onSignOut = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize().testTag("active-workflow"))
                }
            }
        }

        val stableNodes = listOf(
            compose.onNodeWithTag("active-workflow"),
            compose.onNodeWithContentDescription("Connection status: Online. Open connection details"),
            compose.onNodeWithTag("outbox-work-status-slot"),
            compose.onNodeWithContentDescription("Help and support"),
            compose.onNodeWithContentDescription("Account actions"),
        )
        val originalBounds = stableNodes.map { it.fetchSemanticsNode().boundsInRoot }
        repeat(40) { index ->
            val next = when (index % 5) {
                0 -> SyncAvailabilityProblem.VERIFYING
                1 -> SyncAvailabilityProblem.NO_NETWORK
                2 -> SyncAvailabilityProblem.SERVER_UNREACHABLE
                3 -> SyncAvailabilityProblem.RECOVERING
                else -> SyncAvailabilityProblem.NONE
            }
            val nextStatus = when (index % 4) {
                0 -> OutboxWorkStatus()
                1 -> OutboxWorkStatus(retryableCount = 1)
                2 -> OutboxWorkStatus(actionRequiredCount = 12)
                else -> OutboxWorkStatus(savedDraftCount = 3)
            }
            compose.runOnIdle {
                problem.value = next
                outboxStatus.value = nextStatus
                syncing.value = index % 3 == 0
            }
            compose.waitForIdle()
            val currentNodes = listOf(
                compose.onNodeWithTag("active-workflow"),
                compose.onNodeWithContentDescription(
                    "Connection status: ${connectionLabel(next)}. Open connection details",
                ),
                compose.onNodeWithTag("outbox-work-status-slot"),
                compose.onNodeWithContentDescription("Help and support"),
                compose.onNodeWithContentDescription("Account actions"),
            )
            currentNodes.forEachIndexed { nodeIndex, node ->
                val original = originalBounds[nodeIndex]
                val current = node.fetchSemanticsNode().boundsInRoot
                assertEquals(original.left, current.left, 0.1f)
                assertEquals(original.top, current.top, 0.1f)
                assertEquals(original.width, current.width, 0.1f)
                assertEquals(original.height, current.height, 0.1f)
            }
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

    private fun connectionLabel(problem: SyncAvailabilityProblem): String = when (problem) {
        SyncAvailabilityProblem.NONE -> "Online"
        SyncAvailabilityProblem.VERIFYING -> "Checking"
        SyncAvailabilityProblem.NO_NETWORK -> "No internet"
        SyncAvailabilityProblem.SERVER_UNREACHABLE -> "Server issue"
        SyncAvailabilityProblem.RECOVERING -> "Restoring"
    }
}
