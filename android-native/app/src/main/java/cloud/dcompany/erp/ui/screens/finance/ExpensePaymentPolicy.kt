package cloud.dcompany.erp.ui.screens.finance

internal data class ExpensePaymentOption(
    val value: String,
    val label: String,
)

/**
 * Ordinary Finance expenses are not connected to a POS shift drawer. Keeping
 * cash out of this form prevents a paid-out from silently leaving expected
 * drawer cash unchanged. Historical cash expenses remain readable; the API
 * is the final enforcement boundary for deployed older clients.
 */
internal object ExpensePaymentPolicy {
    const val DefaultRail = "upi"

    val Options = listOf(
        ExpensePaymentOption("upi", "UPI (business account)"),
        ExpensePaymentOption("card", "Business debit card"),
        ExpensePaymentOption("bank", "Bank transfer"),
    )

    const val CashDrawerGuidance =
        "Cash is unavailable here because ordinary expenses are not linked to the open " +
            "shift drawer. Use UPI, business debit card or bank transfer. Cash paid-outs " +
            "will be available only through the future shift-linked drawer workflow."
}
