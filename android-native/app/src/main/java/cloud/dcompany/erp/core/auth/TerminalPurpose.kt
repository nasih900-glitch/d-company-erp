package cloud.dcompany.erp.core.auth

/**
 * Server-enforced terminal capability. Display names are editable and must
 * never decide where a financial transaction is routed.
 */
object TerminalPurpose {
    const val HYBRID = "hybrid"
    const val CAFE_POS = "cafe_pos"
    const val GAMING = "gaming"

    val values: Set<String> = setOf(HYBRID, CAFE_POS, GAMING)

    fun isKnown(value: String?): Boolean = value in values

    fun displayLabel(value: String?): String = when (value) {
        CAFE_POS -> "Cafe POS"
        GAMING -> "Gaming Area"
        HYBRID -> "Hybrid Gaming + POS"
        else -> "Purpose needs verification"
    }
}
