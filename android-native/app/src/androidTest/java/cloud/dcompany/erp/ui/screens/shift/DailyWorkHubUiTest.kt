package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DailyWorkHubUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun attendanceCashierAndShiftActionsRemainIndependentAndPreserveOperatorFreshness() {
        val actions = mutableListOf<String>()
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(430.dp).height(600.dp).verticalScroll(rememberScrollState())) {
                    DailyWorkHub(
                        "Till operator: Sam", "Last verified · current status unavailable · Signed in: Jo",
                        { actions += "attendance" }, { actions += "handover" }, { actions += "review-close" }, true, false,
                    )
                }
            }
        }
        compose.onNodeWithText("Till operator: Sam").assertIsDisplayed()
        compose.onNodeWithText("Last verified · current status unavailable · Signed in: Jo").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNodeWithText("Attendance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("attendance"), actions) }
        compose.onNodeWithText("Change cashier").performScrollTo().performClick()
        compose.onNodeWithText("Close shop shift").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("attendance", "handover", "review-close"), actions) }
    }

    @Test fun offlineHandoverStaysVisibleButDisabledAndMissingCapabilitiesStayAbsent() {
        compose.setContent {
            DCompanyTheme {
                DailyWorkHub("Cashier status unavailable", "Signed in: Jo", null, {}, null, true, false, changeCashierOnline = false)
            }
        }
        compose.onNodeWithText("Change cashier").assertIsNotEnabled()
        compose.onNodeWithText("Connect to change cashier").assertIsDisplayed()
        compose.onNodeWithText("Attendance").assertDoesNotExist()
        compose.onNodeWithText("Close shop shift").assertDoesNotExist()
    }
}
