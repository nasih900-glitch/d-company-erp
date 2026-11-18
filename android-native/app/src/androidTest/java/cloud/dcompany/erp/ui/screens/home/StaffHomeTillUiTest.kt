package cloud.dcompany.erp.ui.screens.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.TillOperatorDisplay
import cloud.dcompany.erp.ui.TillOperatorRead
import cloud.dcompany.erp.ui.TillOperatorStatus
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StaffHomeTillUiTest {
    @get:Rule val compose = createComposeRule()
    private val recorded = TillOperatorRead("shift", "open", "operator-a", "Operator A", 1, "recorded")
    private val fresh = TillOperatorDisplay("Signed-in B", TillOperatorStatus.VERIFIED, recorded)

    @Test
    fun cardSeparatesRunningOperatorAttendanceAndHandoverWithoutFinancialPanels() {
        val actions = mutableListOf<String>()
        compose.setContent {
            DCompanyTheme {
                StaffHomeScreen("Signed-in B", "Test shop", listOf(Destination.Gaming, Destination.Staff), {},
                    tillOperator = fresh, online = true,
                    onAttendance = { actions += "attendance" }, onHandover = { actions += "handover" },
                    onRefreshTill = { actions += "refresh" })
            }
        }
        compose.onNodeWithText("Running till: Operator A").assertIsDisplayed()
        compose.onNodeWithText("Verified · Signed in: Signed-in B").assertIsDisplayed()
        compose.onNodeWithTag("staff-home-clock").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("attendance"), actions) }
        compose.onNodeWithTag("staff-home-handover").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("attendance", "handover"), actions) }
        compose.onNodeWithTag("staff-home-till-refresh").performClick()
        compose.runOnIdle { assertEquals(listOf("attendance", "handover", "refresh"), actions) }
        for (tag in listOf("executive-home-workspace", "home-kpi-row", "home-sales-trend")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    @Test
    fun offlineAndCheckingCannotStartHandoverButAttendanceRemainsAvailable() {
        val display = mutableStateOf(fresh.copy(status = TillOperatorStatus.STALE))
        val online = mutableStateOf(false)
        var attendance = 0
        var handovers = 0
        compose.setContent {
            DCompanyTheme {
                StaffHomeScreen("Signed-in B", "Test shop", emptyList(), {},
                    tillOperator = display.value, online = online.value,
                    onAttendance = { attendance++ }, onHandover = { handovers++ })
            }
        }
        compose.onNodeWithText("Last verified till: Operator A").assertIsDisplayed()
        compose.onNodeWithText("Connect to the internet before handing over.").assertIsDisplayed()
        compose.onNodeWithTag("staff-home-handover").assertIsNotEnabled()
        compose.onNodeWithTag("staff-home-clock").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, attendance)
            assertEquals(0, handovers)
            online.value = true
            display.value = fresh.copy(status = TillOperatorStatus.CHECKING)
        }
        compose.onNodeWithTag("staff-home-handover").assertIsNotEnabled()
        compose.onNodeWithText("Refresh the running till status before handing over.").assertIsDisplayed()
    }

    @Test
    fun untrackedAndNoShiftDoNotInventAnOperator() {
        val display = mutableStateOf(fresh.copy(verified =
            TillOperatorRead("shift", "open", revision = 0, tracking = "untracked")))
        compose.setContent {
            DCompanyTheme {
                StaffHomeScreen("Signed-in B", "Test shop", emptyList(), {},
                    tillOperator = display.value, online = true, onHandover = {})
            }
        }
        compose.onNodeWithText("Running till: not recorded").assertIsDisplayed()
        compose.onNodeWithText("Running till: Signed-in B").assertDoesNotExist()
        compose.onNodeWithTag("staff-home-handover").assertIsEnabled()
        compose.runOnIdle { display.value = TillOperatorDisplay("Signed-in B", TillOperatorStatus.NO_SHIFT) }
        compose.onNodeWithText("No open shop shift").assertIsDisplayed()
        compose.onNodeWithTag("staff-home-handover").assertIsNotEnabled()
    }

    @Test fun verifiedCompletionIsSeparateFromAttendanceAndCanBeDismissed() {
        val shown = mutableStateOf(true)
        compose.setContent { DCompanyTheme {
            StaffHomeScreen("Signed-in B", "Test shop", emptyList(), {}, tillOperator = fresh, online = true,
                completionMessage = if (shown.value) "Handover complete. Operator A is now running the till. The same shop shift remains open. Attendance is unchanged." else null,
                onDismissCompletion = { shown.value = false }, onAttendance = {})
        } }
        compose.onNodeWithTag("staff-home-handover-complete").assertIsDisplayed()
        compose.onNodeWithTag("staff-home-clock").assertIsEnabled()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithTag("staff-home-handover-complete").assertDoesNotExist()
    }

    @Test
    fun missingCapabilitiesDoNotExposeActionCallbacks() {
        compose.setContent {
            DCompanyTheme {
                StaffHomeScreen("Read-only staff", "Test shop", listOf(Destination.Gaming), {},
                    tillOperator = null, online = true)
            }
        }
        compose.onNodeWithTag("staff-home-clock").assertDoesNotExist()
        compose.onNodeWithTag("staff-home-handover").assertDoesNotExist()
        compose.onNodeWithText("Running till status unavailable").assertIsDisplayed()
    }
}
