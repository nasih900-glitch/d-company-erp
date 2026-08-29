package cloud.dcompany.erp.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Test

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
}
