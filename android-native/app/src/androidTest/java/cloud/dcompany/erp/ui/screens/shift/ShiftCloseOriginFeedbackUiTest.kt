package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Test

class ShiftCloseOriginFeedbackUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeOriginRefusalExplainsAppIdentityAndWebRecovery() {
        compose.setContent {
            DCompanyTheme {
                Box(Modifier.width(600.dp)) { ShiftCloseOriginFeedback(ANDROID_SHIFT_ORIGIN_GUIDANCE) }
            }
        }
        compose.onNodeWithText("Verify the opening app before closing").assertIsDisplayed()
        compose.onNodeWithText("Recover Android shift in Web ERP", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No new drawer count will be queued", substring = true).assertIsDisplayed()
    }
}
