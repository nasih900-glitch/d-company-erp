package cloud.dcompany.erp.ui.screens.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FinanceStatusViewportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactReadySummaryFitsAboveTheReportWhileTheKeyboardRailIsReserved() {
        compose.setContent {
            DCompanyTheme {
                Column {
                    FinanceStatusViewport(compact = true) { FinanceReadySummary(null, compact = true) }
                    Box(Modifier.height(200.dp).testTag("financial-report"))
                }
            }
        }
        val text = compose.onNodeWithText("Finance online · protected recovery is in saved work")
            .getUnclippedBoundsInRoot()
        val report = compose.onNodeWithTag("financial-report").getUnclippedBoundsInRoot()
        assertEquals(48f, report.top.value, 0.5f)
        assertTrue("The compact summary must not overlap the report", text.bottom <= report.top)
    }

    @Test
    fun financeReportPositionStaysFixedAcrossBackgroundStatusChanges() {
        val status = mutableStateOf("Finance records online")
        val compact = mutableStateOf(false)
        compose.setContent {
            DCompanyTheme {
                Column {
                    FinanceStatusViewport(compact = compact.value) { Text(status.value) }
                    Box(Modifier.height(200.dp).testTag("financial-report"))
                }
            }
        }
        fun top() = compose.onNodeWithTag("financial-report").getUnclippedBoundsInRoot().top.value
        assertEquals(132f, top(), 0.5f)
        for (message in listOf("Offline · showing saved financial data", "Refresh unavailable", "Saved action needs recovery", "Finance records online")) {
            compose.runOnIdle { status.value = message }
            assertEquals(132f, top(), 0.5f)
        }
        compose.runOnIdle { compact.value = true }
        assertEquals(48f, top(), 0.5f)
        compose.runOnIdle { status.value = "Another saved action needs owner review" }
        assertEquals(48f, top(), 0.5f)
    }
}
