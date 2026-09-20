package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import cloud.dcompany.erp.core.quantity.parseQuantityInput
import cloud.dcompany.erp.core.quantity.visibleErrorOrNull

private const val QUANTITY_HINT =
    "Use 1000 for one thousand; a comma is decimal (12,50)."

@Composable
fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    enabled: Boolean = true,
) {
    val inputError = parseQuantityInput(value, allowNegative).visibleErrorOrNull()
    Field(
        label = label,
        value = value,
        modifier = modifier,
        onChange = onValueChange,
        enabled = enabled,
        isError = inputError != null,
        supportingText = inputError ?: QUANTITY_HINT,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
