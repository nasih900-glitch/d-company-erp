package cloud.dcompany.erp.core.quantity

import java.math.BigDecimal

private const val MAX_QUANTITY_INPUT_LENGTH = 128

const val AMBIGUOUS_QUANTITY_MESSAGE =
    "Ambiguous quantity. Use 1000 for one thousand or 1.000 for a dot decimal."

const val INVALID_QUANTITY_MESSAGE =
    "Enter an ungrouped number using a dot or one decimal comma, for example 12,50."

const val QUANTITY_PRECISION_MESSAGE =
    "Quantity has more precision than this form can save. Shorten the number."

sealed interface QuantityInputResult {
    data class Valid(
        val decimal: BigDecimal,
        val normalized: String,
        val value: Double,
    ) : QuantityInputResult

    data object Empty : QuantityInputResult

    data object Incomplete : QuantityInputResult

    data class Invalid(val message: String) : QuantityInputResult
}

private val completeQuantity = Regex("""-?(?:\d+(?:[.,]\d+)?|[.,]\d+)""")
private val ambiguousGroupedQuantity = Regex("""-?0*[1-9]\d{0,2},\d{3}""")
private val incompleteQuantity = Regex("""-?\d+[.,]""")

/**
 * Parses user-entered quantities without guessing whether a three-digit comma
 * group is thousands or decimals. The normalized text is suitable for exact
 * decimal validation and calculations; [QuantityInputResult.Valid.value] is
 * the corresponding wire value.
 */
fun parseQuantityInput(raw: String, allowNegative: Boolean = false): QuantityInputResult {
    if (raw.isEmpty()) return QuantityInputResult.Empty
    if (raw.length > MAX_QUANTITY_INPUT_LENGTH) {
        return QuantityInputResult.Invalid(INVALID_QUANTITY_MESSAGE)
    }
    val isIncomplete = raw in setOf("-", ".", ",", "-.", "-,") || incompleteQuantity.matches(raw)
    if (isIncomplete && !allowNegative && raw.startsWith('-')) {
        return QuantityInputResult.Invalid("Enter a quantity without a minus sign.")
    }
    if (isIncomplete) {
        return QuantityInputResult.Incomplete
    }
    if (!completeQuantity.matches(raw)) {
        return QuantityInputResult.Invalid(INVALID_QUANTITY_MESSAGE)
    }
    if (ambiguousGroupedQuantity.matches(raw)) {
        return QuantityInputResult.Invalid(AMBIGUOUS_QUANTITY_MESSAGE)
    }
    if (!allowNegative && raw.startsWith('-')) {
        return QuantityInputResult.Invalid("Enter a quantity without a minus sign.")
    }

    val normalized = raw.replace(',', '.')
    val decimal = normalized.toBigDecimalOrNull()
        ?: return QuantityInputResult.Invalid(INVALID_QUANTITY_MESSAGE)
    val value = decimal.toDouble()
    if (!value.isFinite()) return QuantityInputResult.Invalid("Quantity is too large.")
    if (decimal.compareTo(BigDecimal.valueOf(value)) != 0) {
        return QuantityInputResult.Invalid(QUANTITY_PRECISION_MESSAGE)
    }
    return QuantityInputResult.Valid(decimal, normalized, value)
}

fun QuantityInputResult.valueOrNull(): Double? =
    (this as? QuantityInputResult.Valid)?.value

fun QuantityInputResult.normalizedOrNull(): String? =
    (this as? QuantityInputResult.Valid)?.normalized

fun QuantityInputResult.visibleErrorOrNull(): String? =
    (this as? QuantityInputResult.Invalid)?.message

/** Plain, ungrouped editable text that does not round valid stored precision. */
fun quantityInputText(value: Double, blankZero: Boolean = false): String {
    if (!value.isFinite()) return value.toString()
    if (blankZero && value == 0.0) return ""
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/** Converts a stored fraction to editable percentage text without Double multiplication noise. */
fun percentageInputText(fraction: Double): String {
    if (!fraction.isFinite()) return fraction.toString()
    return BigDecimal.valueOf(fraction).movePointRight(2).stripTrailingZeros().toPlainString()
}
