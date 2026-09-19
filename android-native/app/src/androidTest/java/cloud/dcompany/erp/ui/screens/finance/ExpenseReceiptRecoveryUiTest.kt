package cloud.dcompany.erp.ui.screens.finance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExpenseReceiptRecoveryUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmationExplainsServerProofAndRequiresExplicitAction() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            DCompanyTheme {
                RejectedExpenseReceiptDiscardDialog(
                    filename = "vendor-bill.pdf",
                    online = true,
                    busy = false,
                    error = null,
                    onConfirm = { confirmed += 1 },
                    onDismiss = { dismissed += 1 },
                )
            }
        }

        compose.onNodeWithText("Remove rejected receipt?").assertIsDisplayed()
        compose.onNodeWithText("only this rejected saved file is removed", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("the expense stays recorded", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Check server and remove").assertIsEnabled().performClick()
        compose.onNodeWithText("Keep saved copy").performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun offlineConfirmationCannotRemoveEvidence() {
        compose.setContent {
            DCompanyTheme {
                RejectedExpenseReceiptDiscardDialog(
                    filename = "vendor-bill.pdf",
                    online = false,
                    busy = false,
                    error = null,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Reconnect before removing saved evidence.").assertIsDisplayed()
        compose.onNodeWithText("Check server and remove").assertIsNotEnabled()
    }
}
