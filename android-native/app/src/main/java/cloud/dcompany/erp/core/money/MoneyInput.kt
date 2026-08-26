package cloud.dcompany.erp.core.money

/**
 * Parse a user-entered rupee amount into paise without ever routing money
 * through Float/Double. Returns null for malformed, negative, over-precision,
 * or overflowing input so the form can keep the value and explain the error.
 */
fun parseRupeesToMinor(input: String): Long? {
    val value = input.trim()
    if (value.isEmpty()) return null
    if (!value.matches(Regex("\\d+(?:\\.\\d{0,2})?"))) return null

    val parts = value.split('.', limit = 2)
    val rupees = parts[0].toLongOrNull() ?: return null
    val paise = when (val fraction = parts.getOrNull(1).orEmpty()) {
        "" -> 0L
        else -> fraction.padEnd(2, '0').toLongOrNull() ?: return null
    }

    return runCatching { Math.addExact(Math.multiplyExact(rupees, 100L), paise) }
        .getOrNull()
}

/** Exact editable rupee text from integer paise; never passes through Double. */
fun minorToRupeesInput(minor: Long): String {
    val whole = minor / 100L
    val fraction = kotlin.math.abs(minor % 100L)
    val wholeText = if (minor < 0L && whole == 0L) "-0" else whole.toString()
    return if (fraction == 0L) wholeText
    else "$wholeText.${fraction.toString().padStart(2, '0')}"
}
