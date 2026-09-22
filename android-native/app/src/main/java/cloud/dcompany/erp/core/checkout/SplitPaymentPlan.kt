package cloud.dcompany.erp.core.checkout

/**
 * One tender rail in an atomic split settlement. Amounts are bill amounts in
 * paise; [tenderedMinor] is physical cash received and is valid only for cash.
 */
data class SplitPaymentLeg(
    val method: String,
    val amountMinor: Long,
    val tenderedMinor: Long? = null,
)

/**
 * Immutable, canonical split-tender plan. The plan is encoded into the
 * existing durable held-payment row so a Code30.3 patch can persist and replay
 * it as one unit without a Room schema migration or child payment rows.
 */
class SplitPaymentPlan internal constructor(
    val legs: List<SplitPaymentLeg>,
) {
    val totalMinor: Long = legs.fold(0L) { total, leg -> Math.addExact(total, leg.amountMinor) }

    val cashTenderedMinor: Long?
        get() = legs.firstOrNull { it.method == "cash" }?.tenderedMinor

    val cashChangeMinor: Long?
        get() = legs.firstOrNull { it.method == "cash" }?.let { cash ->
            requireNotNull(cash.tenderedMinor) - cash.amountMinor
        }

    fun displayLabel(): String = legs.joinToString(" + ") { leg ->
        "${SplitPaymentPolicy.methodLabel(leg.method)} ${leg.amountMinor.asCompactRupees()}"
    }
}

/**
 * Pure validation and versioned persistence for split tender. The supported
 * order mirrors the backend's canonical five-rail accounting breakdown.
 */
object SplitPaymentPolicy {
    private const val STORAGE_PREFIX = "split:v1|"

    val supportedMethods: List<String> = listOf("cash", "card", "upi", "qr", "wallet")

    fun create(expectedDueMinor: Long, legs: List<SplitPaymentLeg>): SplitPaymentPlan {
        require(expectedDueMinor > 0L) { "The payable bill must be positive." }
        require(legs.size in 2..5) { "Choose between two and five payment methods." }

        val normalized = legs.map { leg ->
            leg.copy(method = leg.method.trim().lowercase())
        }
        require(normalized.all { it.method in supportedMethods }) {
            "Choose only Cash, Card, UPI, QR, or Wallet."
        }
        require(normalized.map(SplitPaymentLeg::method).distinct().size == normalized.size) {
            "Each split payment method can be used only once."
        }
        normalized.forEach { leg ->
            require(leg.amountMinor > 0L) { "Every selected payment amount must be positive." }
            if (leg.method == "cash") {
                require(leg.tenderedMinor != null) { "Enter the cash received." }
                require(leg.tenderedMinor >= leg.amountMinor) {
                    "Cash received must cover the cash part of the bill."
                }
            } else {
                require(leg.tenderedMinor == null) {
                    "Cash received is valid only for the cash payment method."
                }
            }
        }

        val canonical = normalized.sortedBy { supportedMethods.indexOf(it.method) }
        val plan = SplitPaymentPlan(canonical)
        require(plan.totalMinor == expectedDueMinor) {
            "Split amounts must equal the exact amount due."
        }
        return plan
    }

    /**
     * This value is stored in LocalHeldOrderPaymentEntity.method. Single-rail
     * rows retain their historical plain method value. A split prefix that
     * cannot be decoded throws and therefore fails closed before any API call.
     */
    fun encode(plan: SplitPaymentPlan): String {
        val canonical = create(plan.totalMinor, plan.legs)
        return STORAGE_PREFIX + canonical.legs.joinToString("|") { leg ->
            listOf(
                leg.method,
                leg.amountMinor.toString(),
                leg.tenderedMinor?.toString().orEmpty(),
            ).joinToString(",")
        }
    }

    fun decodeStoredMethod(storedMethod: String): SplitPaymentPlan? {
        if (!storedMethod.startsWith("split:")) return null
        require(storedMethod.startsWith(STORAGE_PREFIX)) {
            "This saved split-payment format is not supported by this app version."
        }
        val encodedLegs = storedMethod.removePrefix(STORAGE_PREFIX).split('|')
        val legs = encodedLegs.map { encoded ->
            val fields = encoded.split(',', limit = 3)
            require(fields.size == 3) { "The saved split-payment plan is incomplete." }
            SplitPaymentLeg(
                method = fields[0],
                amountMinor = fields[1].toLongOrNull()
                    ?: error("The saved split-payment amount is invalid."),
                tenderedMinor = fields[2].takeIf(String::isNotEmpty)?.toLongOrNull()
                    ?: fields[2].takeIf(String::isNotEmpty)?.let {
                        error("The saved cash tender is invalid.")
                    },
            )
        }
        val expectedTotal = legs.fold(0L) { total, leg -> Math.addExact(total, leg.amountMinor) }
        return create(expectedTotal, legs)
    }

    fun isSplitStorageValue(storedMethod: String): Boolean = storedMethod.startsWith("split:")

    fun methodLabel(method: String): String = when (method.lowercase()) {
        "cash" -> "Cash"
        "card" -> "Card"
        "upi" -> "UPI"
        "qr" -> "QR"
        "wallet" -> "Wallet"
        else -> method.replaceFirstChar(Char::titlecase)
    }
}

private fun Long.asCompactRupees(): String {
    val whole = this / 100L
    val fraction = kotlin.math.abs(this % 100L)
    return if (fraction == 0L) "₹$whole" else "₹$whole.${fraction.toString().padStart(2, '0')}"
}
