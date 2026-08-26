package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.money.minorToRupeesInput
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.theme.Radius

/**
 * A guardrail, not an accounting limit: an on-device cafe money entry should
 * never need more than ten lakh rupees. Keeping critical editable values below
 * this boundary also prevents a paste or repeated tap from reaching a
 * database/API overflow path. Callers can supply a narrower boundary.
 */
internal const val MAX_MONEY_ENTRY_MINOR = 100_000_000L

/** Six digits matches the old denomination field while keeping the sum safe. */
internal const val MAX_DENOMINATION_COUNT = 999_999L

private fun moneyKeypadRows(allowDecimal: Boolean) = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(if (allowDecimal) "." else "clear", "0", "backspace"),
)

private fun decimalDigit(character: Char): Char? {
    val value = Character.digit(character, 10)
    return value.takeIf { it >= 0 }?.let { ('0'.code + it).toChar() }
}

private const val EXPLICIT_DECIMAL_SEPARATOR = '\u0001'

private fun canonicalMoneyCharacter(character: Char): Char? = when {
    decimalDigit(character) != null -> decimalDigit(character)
    character == '.' || character == '\uFF0E' -> '.'
    character == ',' || character == '\uFF0C' -> ','
    character == '\u066B' -> EXPLICIT_DECIMAL_SEPARATOR
    // Arabic thousands separator. Other currency/grouping characters are
    // likewise ignored by returning null below while their digits are kept.
    character == '\u066C' -> null
    else -> null
}

/**
 * Locate the one separator that represents paise. Commas are ambiguous: a
 * single comma followed by three digits (or repeated Indian/Western groups)
 * is grouping, while `12,34` is a decimal amount. When dot and comma both
 * appear, the last one is the decimal separator and earlier ones are groups.
 */
private fun decimalSeparatorIndex(value: List<Char>, allowDecimal: Boolean): Int? {
    if (!allowDecimal) return null

    val explicit = value.lastIndexOf(EXPLICIT_DECIMAL_SEPARATOR)
    if (explicit >= 0) return explicit

    val dots = value.indices.filter { value[it] == '.' }
    val commas = value.indices.filter { value[it] == ',' }
    if (dots.isNotEmpty() && commas.isNotEmpty()) {
        return maxOf(dots.last(), commas.last())
    }

    val separators = if (dots.isNotEmpty()) dots else commas
    if (separators.isEmpty()) return null
    val trailingDigits = value.drop(separators.last() + 1).count(Char::isDigit)

    // A lone dot retains the field's previous incremental/precision behaviour:
    // `12.` stays editable and `12.345` is capped to two decimal places.
    if (separators.size == 1) {
        return if (commas.isNotEmpty() && trailingDigits == 3) null else separators.single()
    }

    // Multiple equal separators ending in a three-digit group are formatted
    // whole rupees (`1,23,456` or `1,234,567`), not fractional money.
    return if (trailingDigits == 3) null else separators.last()
}

/**
 * Sanitises keyboard, paste and touch-keypad input using exact minor units.
 * OEM/localised decimal digits and separators are normalised to the ASCII
 * money contract. Partial values such as `12.` remain editable; excess
 * precision is ignored, and an amount over [maxMinor] is clamped visibly
 * rather than silently overflowing.
 */
internal fun sanitiseMoneyEntry(
    raw: String,
    maxMinor: Long = MAX_MONEY_ENTRY_MINOR,
    allowDecimal: Boolean = true,
): String {
    require(maxMinor >= 0L)
    if (raw.isBlank()) return ""

    // parseRupeesToMinor intentionally accepts only an ASCII money contract.
    // Canonicalising first prevents an Arabic, Persian, or full-width digit
    // from being mistaken for an overflow and replaced with the maximum.
    val canonical = raw.mapNotNull(::canonicalMoneyCharacter)
    val decimalIndex = decimalSeparatorIndex(canonical, allowDecimal)
    val wholeEnd = decimalIndex ?: canonical.size
    val whole = canonical.take(wholeEnd).filter(Char::isDigit).joinToString("")
    val cleaned = if (decimalIndex == null) {
        canonical.filter(Char::isDigit).joinToString("")
    } else {
        val fraction = canonical.drop(decimalIndex + 1)
            .filter(Char::isDigit)
            .take(2)
            .joinToString("")
        "${whole.ifEmpty { "0" }}.$fraction"
    }
    if (cleaned.isEmpty()) return ""
    val parsed = parseRupeesToMinor(cleaned)
    return if (parsed == null || parsed > maxMinor) minorToRupeesInput(maxMinor) else cleaned
}

internal fun applyMoneyKey(
    current: String,
    key: String,
    maxMinor: Long = MAX_MONEY_ENTRY_MINOR,
    allowDecimal: Boolean = true,
): String = when (key) {
    "clear" -> ""
    "backspace" -> current.dropLast(1)
    "." -> sanitiseMoneyEntry(
        if (!allowDecimal || '.' in current) current else "$current.",
        maxMinor,
        allowDecimal,
    )
    else -> sanitiseMoneyEntry(current + key.filter(Char::isDigit), maxMinor, allowDecimal)
}

internal fun sanitiseWholeNumberEntry(
    raw: String,
    maxValue: Long = MAX_DENOMINATION_COUNT,
): String {
    require(maxValue >= 0L)
    val digits = raw.mapNotNull(::decimalDigit).joinToString(separator = "")
    if (digits.isEmpty()) return ""
    val parsed = digits.toLongOrNull() ?: return maxValue.toString()
    return parsed.coerceAtMost(maxValue).toString()
}

internal fun stepWholeNumberEntry(
    current: String,
    delta: Long,
    maxValue: Long = MAX_DENOMINATION_COUNT,
): String {
    require(maxValue >= 0L)
    val parsed = current.mapNotNull(::decimalDigit).joinToString(separator = "")
        .toLongOrNull() ?: 0L
    return (parsed + delta).coerceIn(0L, maxValue).toString()
}

/** Keyboard-editable money input with a complete touch-only fallback. */
@Composable
fun TouchMoneyEntry(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    maxMinor: Long = MAX_MONEY_ENTRY_MINOR,
    allowDecimal: Boolean = true,
    presetsMinor: List<Long> = emptyList(),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(sanitiseMoneyEntry(it, maxMinor, allowDecimal)) },
            label = { Text(label) },
            supportingText = {
                Text("Type or use the keypad · maximum ${maxMinor.asRupees()}")
            },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "$label, ${value.ifBlank { "zero" }}"
            },
            shape = Radius.shapeMd,
        )

        if (presetsMinor.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presetsMinor.forEach { preset ->
                    val safePreset = preset.coerceIn(0L, maxMinor)
                    OutlinedButton(
                        onClick = { onValueChange(minorToRupeesInput(safePreset)) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            contentDescription = "Set $label to ${safePreset.asRupees()}"
                        },
                    ) { Text(safePreset.asRupees().removeSuffix(".00")) }
                }
            }
        }

        moneyKeypadRows(allowDecimal).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    val description = when (key) {
                        "." -> "Decimal point"
                        "clear" -> "Clear $label"
                        "backspace" -> "Delete last digit"
                        else -> "Digit $key"
                    }
                    OutlinedButton(
                        onClick = {
                            onValueChange(applyMoneyKey(value, key, maxMinor, allowDecimal))
                        },
                        enabled = enabled && (
                            key !in setOf("backspace", "clear") || value.isNotEmpty()
                        ),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            contentDescription = description
                        },
                    ) {
                        Text(
                            when (key) {
                                "backspace" -> "⌫"
                                "clear" -> "C"
                                else -> key
                            },
                        )
                    }
                }
            }
        }

        TextButton(
            onClick = { onValueChange("") },
            enabled = enabled && value.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "Clear $label"
            },
        ) { Text("Clear amount") }
    }
}

/** Keyboard-editable whole number with 48dp decrement/increment touch targets. */
@Composable
fun WholeNumberStepper(
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    maxValue: Long = MAX_DENOMINATION_COUNT,
) {
    val current = value.toLongOrNull() ?: 0L
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = { onValueChange(stepWholeNumberEntry(value, -1L, maxValue)) },
            enabled = enabled && current > 0L,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = "Decrease $description by one"
            },
        ) { Text("−") }
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(sanitiseWholeNumberEntry(it, maxValue)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
            shape = Radius.shapeMd,
        )
        OutlinedButton(
            onClick = { onValueChange(stepWholeNumberEntry(value, 1L, maxValue)) },
            enabled = enabled && current < maxValue,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                contentDescription = "Increase $description by one"
            },
        ) { Text("+") }
    }
}
