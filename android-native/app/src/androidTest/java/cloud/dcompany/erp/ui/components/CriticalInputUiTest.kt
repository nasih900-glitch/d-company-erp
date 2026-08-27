package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Rule
import org.junit.Test

class CriticalInputUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun moneyFieldAcceptsRenderedKeyboardText() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            DCompanyTheme {
                TouchMoneyEntry(
                    value = value,
                    onValueChange = { value = it },
                    label = "Opening float (₹)",
                    enabled = true,
                )
            }
        }

        compose.onNodeWithContentDescription("Opening float (₹), zero")
            .performTextReplacement("210.50")

        compose.onNodeWithContentDescription("Opening float (₹), 210.50")
            .assertTextContains("210.50")
    }

    @Test
    fun moneyCanBeEnteredUsingOnlyTouchControls() {
        compose.setContent {
            var value by remember { mutableStateOf("") }
            DCompanyTheme {
                TouchMoneyEntry(
                    value = value,
                    onValueChange = { value = it },
                    label = "Opening float (₹)",
                    enabled = true,
                )
            }
        }

        listOf("Digit 2", "Digit 1", "Digit 0", "Decimal point", "Digit 5")
            .forEach { description ->
                compose.onNodeWithContentDescription(description).performClick()
            }

        compose.onNodeWithContentDescription("Opening float (₹), 210.5")
            .assertTextContains("210.5")
    }

    @Test
    fun presetVoidReasonEnablesConfirmationWithoutKeyboard() {
        compose.setContent {
            var selectedId by remember { mutableStateOf<String?>(null) }
            var custom by remember { mutableStateOf("") }
            DCompanyTheme {
                Column {
                    VoidReasonInput(
                        selectedId = selectedId,
                        customReason = custom,
                        onPresetSelected = { selectedId = it },
                        onCustomReasonChange = { custom = it },
                    )
                    Button(
                        onClick = {},
                        enabled = resolvedVoidReason(selectedId, custom).isNotBlank(),
                        modifier = Modifier.testTag("confirm-void"),
                    ) { Text("Confirm cancellation") }
                }
            }
        }

        compose.onNodeWithTag("confirm-void").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cancellation reason: Guest changed mind")
            .performClick()
        compose.onNodeWithTag("confirm-void").assertIsEnabled()
    }

    @Test
    fun customVoidReasonAcceptsRenderedKeyboardText() {
        compose.setContent {
            var selectedId by remember { mutableStateOf<String?>(null) }
            var custom by remember { mutableStateOf("") }
            DCompanyTheme {
                VoidReasonInput(
                    selectedId = selectedId,
                    customReason = custom,
                    onPresetSelected = { selectedId = it },
                    onCustomReasonChange = { custom = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Cancellation reason: Other or add details")
            .performClick()
        compose.onNodeWithContentDescription("Custom cancellation reason")
            .performTextReplacement("Guest requested another service")

        compose.onNodeWithContentDescription("Custom cancellation reason")
            .assertTextContains("Guest requested another service")
    }

    @Test
    fun customVoidReasonRemainsReachableInCompactWindow() {
        compose.setContent {
            var selectedId by remember { mutableStateOf<String?>(null) }
            var custom by remember { mutableStateOf("") }
            DCompanyTheme {
                Box(Modifier.width(320.dp).height(220.dp)) {
                    VoidReasonInput(
                        selectedId = selectedId,
                        customReason = custom,
                        onPresetSelected = { selectedId = it },
                        onCustomReasonChange = { custom = it },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Cancellation reason: Other or add details")
            .performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription("Custom cancellation reason")
            .performScrollTo()
            .performTextReplacement("Compact window reason")
        compose.onNodeWithContentDescription("Custom cancellation reason")
            .assertTextContains("Compact window reason")
        compose.onNodeWithContentDescription("Show keyboard for custom cancellation reason")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun loadingSkeletonAnnouncesThatContentIsInProgress() {
        compose.setContent {
            DCompanyTheme { LoadingSkeleton(lines = 3) }
        }

        compose.onNodeWithContentDescription("Loading content")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "In progress",
                ),
            )
    }

    @Test
    fun busyButtonKeepsItsActionLabelAndReportsProgress() {
        compose.setContent {
            DCompanyTheme {
                ErpButton(
                    text = "Pay",
                    onClick = {},
                    busy = true,
                )
            }
        }

        compose.onNodeWithText("Pay…")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Pay in progress",
                ),
            )
    }

    @Test
    fun formDialogKeepsValidationErrorVisibleAboveScrollableContent() {
        compose.setContent {
            DCompanyTheme {
                FormDialog(
                    title = "Record payment",
                    confirmLabel = "Save",
                    busy = false,
                    error = "Enter an amount greater than zero.",
                    onDismiss = {},
                    onConfirm = {},
                ) {
                    repeat(30) { index -> Text("Form row $index") }
                }
            }
        }

        compose.onNodeWithText("Enter an amount greater than zero.")
            .assertIsDisplayed()
    }
}
