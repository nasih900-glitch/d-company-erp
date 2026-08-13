package cloud.dcompany.erp.ui.screens.reports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field names copied verbatim from ReportDTO in
 * backend/app/api/v1/reports/router.py (and mirrored in
 * frontend/src/lib/erp-api.ts as ReportDataDTO). A typo here fails at
 * runtime with a silently-zero P&L, not at compile time, so every name is
 * spelled out with @SerialName rather than relying on a naming strategy.
 *
 * Every money field is Long paise. Defaults are supplied on all of them so an
 * older or newer backend that omits one renders a zero row instead of
 * crashing the whole screen.
 */

@Serializable
data class ReportRevenue(
    @SerialName("food_minor") val foodMinor: Long = 0,
    @SerialName("gaming_minor") val gamingMinor: Long = 0,
    @SerialName("hookah_minor") val hookahMinor: Long = 0,
    @SerialName("event_tickets_minor") val eventTicketsMinor: Long = 0,
    @SerialName("delivery_aggregator_minor") val deliveryAggregatorMinor: Long = 0,
    @SerialName("other_minor") val otherMinor: Long = 0,
    @SerialName("manual_collections_minor") val manualCollectionsMinor: Long = 0,
    @SerialName("discounts_and_points_redeemed_minor")
    val discountsAndPointsRedeemedMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
)

@Serializable
data class ReportTax(
    @SerialName("cgst_minor") val cgstMinor: Long = 0,
    @SerialName("sgst_minor") val sgstMinor: Long = 0,
    @SerialName("igst_minor") val igstMinor: Long = 0,
    @SerialName("cess_minor") val cessMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
)

@Serializable
data class ReportPayments(
    @SerialName("cash_minor") val cashMinor: Long = 0,
    @SerialName("upi_minor") val upiMinor: Long = 0,
    @SerialName("card_minor") val cardMinor: Long = 0,
    @SerialName("bank_minor") val bankMinor: Long = 0,
    @SerialName("qr_minor") val qrMinor: Long = 0,
    @SerialName("wallet_minor") val walletMinor: Long = 0,
    @SerialName("other_minor") val otherMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
)

@Serializable
data class ReportExpenseLine(
    val category: String = "",
    @SerialName("amount_minor") val amountMinor: Long = 0,
)

@Serializable
data class ReportData(
    val period: String = "",
    val label: String = "",
    @SerialName("period_start") val periodStart: String = "",
    @SerialName("period_end") val periodEnd: String = "",
    @SerialName("fiscal_year") val fiscalYear: String = "",

    @SerialName("orders_count") val ordersCount: Int = 0,
    @SerialName("tickets_count") val ticketsCount: Int = 0,
    @SerialName("avg_ticket_minor") val avgTicketMinor: Long = 0,

    val revenue: ReportRevenue = ReportRevenue(),
    @SerialName("tax_collected") val taxCollected: ReportTax = ReportTax(),
    @SerialName("payments_received") val paymentsReceived: ReportPayments = ReportPayments(),
    @SerialName("manual_collections_minor") val manualCollectionsMinor: Long = 0,
    @SerialName("refunds_issued_minor") val refundsIssuedMinor: Long = 0,
    @SerialName("net_payments_received_minor") val netPaymentsReceivedMinor: Long = 0,
    val expenses: List<ReportExpenseLine> = emptyList(),
    @SerialName("expense_total_minor") val expenseTotalMinor: Long = 0,
    @SerialName("cogs_minor") val cogsMinor: Long = 0,
    /**
     * Straight-line equipment depreciation for this period. The backend has
     * ALREADY subtracted it from net_profit_minor (see PnLReport.net_profit_minor
     * in app/services/reports/aggregator.py) but it is NOT part of
     * expense_total_minor.
     */
    @SerialName("depreciation_minor") val depreciationMinor: Long = 0,

    @SerialName("gross_revenue_minor") val grossRevenueMinor: Long = 0,
    @SerialName("net_revenue_minor") val netRevenueMinor: Long = 0,
    @SerialName("gross_profit_minor") val grossProfitMinor: Long = 0,
    @SerialName("net_profit_minor") val netProfitMinor: Long = 0,
) {
    /**
     * Everything the period cost. Depreciation is included deliberately: it is
     * inside net_profit_minor but outside expense_total_minor, so a "costs"
     * figure of cogs + expenses alone would not reconcile with the bottom line
     * shown right beside it, and an owner would reasonably conclude one of the
     * two numbers was wrong.
     */
    val totalCostsMinor: Long get() = cogsMinor + expenseTotalMinor + depreciationMinor

    /** A period in which genuinely nothing happened, as opposed to a failed load. */
    val hasNothing: Boolean
        get() = ordersCount == 0 &&
            ticketsCount == 0 &&
            revenue.totalMinor == 0L &&
            paymentsReceived.totalMinor == 0L &&
            manualCollectionsMinor == 0L &&
            expenseTotalMinor == 0L &&
            depreciationMinor == 0L
}

/** The four P&L windows this screen can ask the server for. */
enum class ReportPeriod(val tab: String, val title: String) {
    DAILY("Daily", "Daily P&L"),
    MONTHLY("Monthly", "Monthly P&L"),
    QUARTERLY("Quarterly", "Quarterly P&L"),
    YEARLY("Yearly", "Annual P&L"),
}
