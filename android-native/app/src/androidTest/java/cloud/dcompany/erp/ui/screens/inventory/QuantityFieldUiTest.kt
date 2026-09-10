package cloud.dcompany.erp.ui.screens.inventory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cloud.dcompany.erp.core.quantity.AMBIGUOUS_QUANTITY_MESSAGE
import cloud.dcompany.erp.core.quantity.INVALID_QUANTITY_MESSAGE
import cloud.dcompany.erp.core.quantity.QUANTITY_PRECISION_MESSAGE
import cloud.dcompany.erp.core.quantity.parseQuantityInput
import cloud.dcompany.erp.core.quantity.valueOrNull
import cloud.dcompany.erp.ui.components.FormDialog
import cloud.dcompany.erp.ui.components.QuantityField
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class QuantityFieldUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recipeFormKeepsAmbiguousReplacementVisibleAndBlocksSubmissionUntilCorrected() {
        val submitted = mutableListOf<RecipeLineBody>()
        compose.setContent {
            var value by remember { mutableStateOf("7") }
            DCompanyTheme {
                FormDialog(
                    title = "Recipe quantity test",
                    confirmLabel = "Submit recipe",
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = {
                        val draft = RecipeLineDraft("milk", value, "0")
                        if (draft.error(1) == null) draft.toBody()?.let(submitted::add)
                    },
                ) {
                    QuantityField(
                        value = value,
                        onValueChange = { value = it },
                        label = "Recipe quantity",
                        modifier = Modifier.testTag("recipe-quantity"),
                    )
                }
            }
        }

        compose.onNodeWithTag("recipe-quantity").performTextReplacement("1,000.50")
        compose.onNodeWithTag("recipe-quantity").assertTextContains("1,000.50")
        compose.onNodeWithText(INVALID_QUANTITY_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Submit recipe").performClick()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }

        listOf("1,000", "01,000").forEach { raw ->
            compose.onNodeWithTag("recipe-quantity").performTextReplacement(raw)
            compose.onNodeWithTag("recipe-quantity").assertTextContains(raw)
            compose.onNodeWithText(AMBIGUOUS_QUANTITY_MESSAGE).assertIsDisplayed()
            compose.onNodeWithText("Submit recipe").performClick()
            compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        }

        val overPrecise = "0.10000000000000001"
        compose.onNodeWithTag("recipe-quantity").performTextReplacement(overPrecise)
        compose.onNodeWithTag("recipe-quantity").assertTextContains(overPrecise)
        compose.onNodeWithText(QUANTITY_PRECISION_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Submit recipe").performClick()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }

        compose.onNodeWithTag("recipe-quantity").performTextReplacement("1000")
        compose.onNodeWithText(AMBIGUOUS_QUANTITY_MESSAGE).assertDoesNotExist()
        compose.onNodeWithText("Submit recipe").performClick()
        compose.runOnIdle { assertEquals(1000.0, submitted.single().qty, 0.0) }
    }

    @Test
    fun grnFormSubmitsExplicitDotCommaDecimalAndFourPlaceValuesExactly() {
        val submitted = mutableListOf<Double>()
        compose.setContent {
            var value by remember { mutableStateOf("") }
            DCompanyTheme {
                FormDialog(
                    title = "GRN quantity test",
                    confirmLabel = "Submit GRN",
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = {
                        val draft = GrnLineDraft("milk", value, "8.00")
                        if (draft.validationError(1) == null) draft.toBody()?.qty?.let(submitted::add)
                    },
                ) {
                    QuantityField(
                        value = value,
                        onValueChange = { value = it },
                        label = "GRN quantity",
                        modifier = Modifier.testTag("grn-quantity"),
                    )
                }
            }
        }

        listOf("1.000" to 1.0, "12,50" to 12.5, "0.1250" to 0.125).forEach { (raw, expected) ->
            compose.onNodeWithTag("grn-quantity").performTextReplacement(raw)
            compose.onNodeWithTag("grn-quantity").assertTextContains(raw)
            compose.onNodeWithText("Submit GRN").performClick()
            compose.runOnIdle { assertEquals(expected, submitted.last(), 0.0) }
        }
        compose.runOnIdle { assertEquals(3, submitted.size) }
    }

    @Test
    fun signedCorrectionRejectsAmbiguousGroupingThenEmitsExactNegativeQuantity() {
        val submitted = mutableListOf<Double>()
        compose.setContent {
            var value by remember { mutableStateOf("") }
            DCompanyTheme {
                FormDialog(
                    title = "Count correction test",
                    confirmLabel = "Submit correction",
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = {
                        parseQuantityInput(value, allowNegative = true).valueOrNull()
                            ?.let { submitted += adjustmentDelta(ADJ_COUNT, it) }
                    },
                ) {
                    QuantityField(
                        value = value,
                        onValueChange = { value = it },
                        label = "Correction quantity",
                        allowNegative = true,
                        modifier = Modifier.testTag("correction-quantity"),
                    )
                }
            }
        }

        compose.onNodeWithTag("correction-quantity").performTextReplacement("-1,000")
        compose.onNodeWithTag("correction-quantity").assertTextContains("-1,000")
        compose.onNodeWithText(AMBIGUOUS_QUANTITY_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Submit correction").performClick()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }

        compose.onNodeWithTag("correction-quantity").performTextReplacement("-1000")
        compose.onNodeWithText("Submit correction").performClick()
        compose.runOnIdle { assertEquals(-1000.0, submitted.single(), 0.0) }
    }
}
