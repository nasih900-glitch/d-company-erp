package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InlineShiftOpeningUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realOpeningFormKeepsSubmissionSeparateFromObservedStateAndReturn() {
        val state = mutableStateOf(ShiftUiState(currentShiftLoaded = true, online = true))
        var amount: Long? = null
        var returned = false
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(430.dp).height(500.dp).verticalScroll(rememberScrollState())) {
                    InlineShiftOpeningContent(state.value, ShiftAccess(canOpen = true),
                        onOpen = { amount = it }, onSync = {}, onReturn = { returned = true })
                }
            }
        }
        compose.onNodeWithText("Open shift with ₹0.00").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0L, amount) }
        // The command callback did not invent a server-confirmed shift.
        compose.onAllNodesWithText("Open shift confirmed", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Return to work").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, returned) }
    }

    @Test fun unresolvedScopeAndMissingPermissionNeverExposeFinancialAction() {
        val state = mutableStateOf(ShiftUiState())
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(430.dp).height(500.dp).fillMaxSize()) {
                    InlineShiftOpeningContent(state.value, ShiftAccess(), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Checking this till's saved shift state.").assertIsDisplayed()
        compose.onAllNodesWithText("Open shift with", substring = true).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(currentShiftLoaded = true) }
        compose.onNodeWithText("Shift open permission is required.", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Open shift with", substring = true).assertCountEquals(0)
    }
}
