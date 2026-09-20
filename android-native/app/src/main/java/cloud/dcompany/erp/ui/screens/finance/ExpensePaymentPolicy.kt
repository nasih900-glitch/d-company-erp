package cloud.dcompany.erp.ui.screens.finance

internal data class ExpensePaymentOption(
    val value: String,
    val label: String,
)

/** Cash is explicit because it moves one selected open shift drawer. */
internal object ExpensePaymentPolicy {
    const val DefaultRail = "upi"

    val Options = listOf(
        ExpensePaymentOption("cash", "Cash paid from shift drawer"),
        ExpensePaymentOption("upi", "UPI (business account)"),
        ExpensePaymentOption("card", "Business debit card"),
        ExpensePaymentOption("bank", "Bank transfer"),
    )

    const val CashDrawerGuidance =
        "Cash paid-outs require selecting the exact open shift that supplied the cash. " +
            "The server reduces that drawer atomically when the saved expense syncs."
}
