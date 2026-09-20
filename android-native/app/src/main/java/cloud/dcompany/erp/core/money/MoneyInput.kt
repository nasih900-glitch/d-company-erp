package cloud.dcompany.erp.core.money

/**
 * Parse a user-entered rupee amount into paise without ever routing money
 * through Float/Double. Returns null for malformed, negative, over-precision,
 * or overflowing input so the form can keep the value and explain the error.
 */
fun parseRupeesToMinor(input: String): Long? {
    val value = normalizeRupeeInput(input.trim()) ?: return null
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

/**
 * Accept the decimal key emitted by either dot- or comma-decimal keyboards.
 * Reject every other separator instead of deleting it: deleting the comma in
 * `12,50` would silently turn a ₹12.50 entry into ₹1,250.
 *
 * Precision remains the parser's responsibility so an over-precise pasted
 * value stays visible and can receive the existing validation message.
 */
fun normalizeRupeeInput(input: String, allowNegative: Boolean = false): String? {
    if (input.isEmpty()) return ""
    val unsigned = if (input.startsWith('-')) {
        if (!allowNegative || input.length == 1) return if (allowNegative) input else null
        input.drop(1)
    } else {
        input
    }
    if (unsigned.isEmpty()) return null
    if (unsigned.any { it !in '0'..'9' && it != '.' && it != ',' }) return null
    val separatorCount = unsigned.count { it == '.' || it == ',' }
    if (separatorCount > 1) return null

    val normalized = unsigned.replace(',', '.')
    return (if (input.startsWith('-')) "-" else "") + normalized
}

/** Exact editable rupee text from integer paise; never passes through Double. */
fun minorToRupeesInput(minor: Long): String {
    val whole = minor / 100L
    val fraction = kotlin.math.abs(minor % 100L)
    val wholeText = if (minor < 0L && whole == 0L) "-0" else whole.toString()
    return if (fraction == 0L) wholeText
    else "$wholeText.${fraction.toString().padStart(2, '0')}"
}
