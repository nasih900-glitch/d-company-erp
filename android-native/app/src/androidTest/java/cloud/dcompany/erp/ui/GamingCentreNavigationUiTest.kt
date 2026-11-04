package cloud.dcompany.erp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.AppearancePreference
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class GamingCentreNavigationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun topRightDayNightTogglePersistsAcrossControlDeckModules() {
        val current = mutableStateOf(Destination.Gaming)
        val appearance = mutableStateOf(AppearancePreference.Day)
        compose.setContent {
            DCompanyTheme(preference = appearance.value) {
                WorkspaceScaffold(
                    destinations = listOf(
                        Destination.Gaming, Destination.Pos, Destination.Shift,
                        Destination.Customers,
                    ),
                    currentDestination = current.value,
                    employeeName = "Rafi",
                    locationLabel = "Gaming Centre",
                    connectivityProblem = SyncAvailabilityProblem.NONE,
                    outboxWorkStatus = OutboxWorkStatus(),
                    syncing = false,
                    canChangeTill = false,
                    appearancePreference = appearance.value,
                    onAppearancePreferenceChanged = { appearance.value = it },
                    onOpenSupport = {}, onChangeTill = {}, onSignOut = {},
                    onDestinationChanged = { current.value = it },
                ) { destination, _ -> Text("Current: ${destination.label}") }
            }
        }

        compose.onNodeWithTag("workspace-day-night-toggle").assertIsDisplayed()
        compose.onNodeWithText("Day mode").assertIsDisplayed()
        compose.onNodeWithTag("workspace-day-night-toggle").performClick()
        compose.onNodeWithTag("appearance-option-Night").performClick()
        compose.runOnIdle { assertEquals(AppearancePreference.Night, appearance.value) }
        compose.onNodeWithContentDescription(
            "${Destination.Pos.label}. ${Destination.Pos.description}",
            useUnmergedTree = true,
        ).performClick()
        compose.onNodeWithText("Current: POS").assertIsDisplayed()
        compose.onNodeWithText("Night mode").assertIsDisplayed()
        compose.onNodeWithTag("workspace-day-night-toggle").performClick()
        compose.onNodeWithTag("appearance-option-Day").performClick()
        compose.runOnIdle { assertEquals(AppearancePreference.Day, appearance.value) }
        compose.onNodeWithTag("workspace-day-night-toggle").performClick()
        compose.onNodeWithTag("appearance-option-System").performClick()
        compose.runOnIdle { assertEquals(AppearancePreference.System, appearance.value) }
    }

    @Test
    fun controlDeckTabsNavigateAndMoreKeepsPermittedModulesReachable() {
        val current = mutableStateOf(Destination.Gaming)
        val allowed = listOf(
            Destination.Gaming,
            Destination.Pos,
            Destination.Shift,
            Destination.Customers,
            Destination.Inventory,
        )
        compose.setContent {
            DCompanyTheme {
                // Exercise the compact dock, rather than relying on CI's tablet width.
                Box(Modifier.width(600.dp).height(800.dp)) {
                    WorkspaceScaffold(
                        destinations = allowed,
                        currentDestination = current.value,
                        employeeName = "Rafi",
                        locationLabel = "Gaming Centre",
                        connectivityProblem = SyncAvailabilityProblem.NONE,
                        outboxWorkStatus = OutboxWorkStatus(),
                        syncing = false,
                        canChangeTill = false,
                        onOpenSupport = {},
                        onChangeTill = {},
                        onSignOut = {},
                        onDestinationChanged = { current.value = it },
                    ) { destination, _ ->
                        Text("Current: ${destination.label}")
                    }
                }
            }
        }

        listOf(Destination.Pos, Destination.Shift, Destination.Customers).forEach { target ->
            compose.onNodeWithContentDescription(
                "${target.label}. ${target.description}",
                useUnmergedTree = true,
            ).performClick()
            compose.onNodeWithText("Current: ${target.label}").assertIsDisplayed()
        }
        compose.onNodeWithTag("workspace-more-modules").performClick()
        compose.onNodeWithTag("workspace-more-panel").assertIsDisplayed()
        compose.onNodeWithTag("workspace-module-search-input").assertDoesNotExist()
        compose.onNodeWithTag("workspace-destination-Inventory").performClick()
        compose.onNodeWithText("Current: Stock").assertIsDisplayed()
    }

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
        compose.onNodeWithTag("workspace-more-modules").performClick()
        compose.onNodeWithTag("workspace-destination-Help").assertIsDisplayed()
        compose.onNodeWithTag("workspace-destination-Memberships").assertDoesNotExist()
        compose.onNodeWithTag("workspace-rail-Memberships", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Current: Memberships").assertDoesNotExist()
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
    fun connectionAndSavedWorkKeepWorkflowAndEachHeaderVisibilityLayoutStable() {
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

        val workflow = compose.onNodeWithTag("active-workflow").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("workspace-header").fetchSemanticsNode().boundsInRoot
        val connection = compose.onNodeWithContentDescription(
            "Connection status: Online. Open connection details",
        ).fetchSemanticsNode().boundsInRoot
        // The reviewed header removes the saved-work pill only once work clears.
        // Keep each visibility layout stable, and retain cross-layout workflow,
        // header and connection geometry checks across every transition.
        val layouts = mutableMapOf<Boolean, List<androidx.compose.ui.geometry.Rect>>()
        layouts[false] = listOf(
            compose.onNodeWithContentDescription("Help and support"),
            compose.onNodeWithContentDescription("Account actions"),
        ).map { it.fetchSemanticsNode().boundsInRoot }
        compose.onNodeWithTag("outbox-work-status-slot").assertDoesNotExist()
        var clearTransitions = 0
        var workTransitions = 0
        fun assertBounds(original: androidx.compose.ui.geometry.Rect, current: androidx.compose.ui.geometry.Rect) {
            assertEquals(original.left, current.left, 0.1f)
            assertEquals(original.top, current.top, 0.1f)
            assertEquals(original.width, current.width, 0.1f)
            assertEquals(original.height, current.height, 0.1f)
        }
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
            val workVisible = !nextStatus.isClear || index % 3 == 0
            assertBounds(workflow, compose.onNodeWithTag("active-workflow").fetchSemanticsNode().boundsInRoot)
            assertBounds(header, compose.onNodeWithTag("workspace-header").fetchSemanticsNode().boundsInRoot)
            assertBounds(connection, compose.onNodeWithContentDescription(
                "Connection status: ${connectionLabel(next)}. Open connection details",
            ).fetchSemanticsNode().boundsInRoot)
            val controls = listOf(
                compose.onNodeWithContentDescription("Help and support"),
                compose.onNodeWithContentDescription("Account actions"),
            )
            val nodes = if (workVisible) {
                workTransitions++
                listOf(compose.onNodeWithTag("outbox-work-status-slot")
                    .assertIsDisplayed().assertHeightIsAtLeast(48.dp)) + controls
            } else {
                clearTransitions++
                compose.onNodeWithTag("outbox-work-status-slot").assertDoesNotExist()
                controls
            }
            val bounds = nodes.map { it.fetchSemanticsNode().boundsInRoot }
            val original = layouts.getOrPut(workVisible) { bounds }
            bounds.forEachIndexed { nodeIndex, current -> assertBounds(original[nodeIndex], current) }

        }

        assertTrue(clearTransitions > 1 && workTransitions > 1)
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
