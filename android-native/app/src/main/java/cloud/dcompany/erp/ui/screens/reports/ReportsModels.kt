package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.ui.WorkspacePresentationPolicy
import kotlinx.serialization.Required
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
    @SerialName("memberships_minor") val membershipsMinor: Long = 0,
    @SerialName("delivery_aggregator_minor") val deliveryAggregatorMinor: Long = 0,
    @SerialName("other_minor") val otherMinor: Long = 0,
    @SerialName("manual_collections_minor") val manualCollectionsMinor: Long = 0,
    @SerialName("discounts_and_points_redeemed_minor")
    val discountsAndPointsRedeemedMinor: Long = 0,
    @SerialName("rounding_income_minor") val roundingIncomeMinor: Long = 0,
    @SerialName("rounding_expense_minor") val roundingExpenseMinor: Long = 0,
    @SerialName("round_off_minor") val roundOffMinor: Long = 0,
    @SerialName("total_minor") val totalMinor: Long = 0,
)

internal data class PresentedRevenueSource(
    val label: String,
    val amountMinor: Long,
    val detail: String? = null,
)

/**
 * Groups dormant restaurant/event/membership revenue without changing the
 * underlying report or its totals.  A focused Gaming Centre must not advertise
 * those workflows, but historical money remains visible and reconcilable.
 */
internal fun ReportRevenue.presentedSources(
    presentation: WorkspacePresentationPolicy,
): List<PresentedRevenueSource> {
    if (presentation.showsRestaurantOperations && presentation.showsMemberships &&
        presentation.showsEvents
    ) {
        return buildList {
            add(PresentedRevenueSource("Food / drinks / desserts", foodMinor))
            add(PresentedRevenueSource("Gaming", gamingMinor))
            if (hookahMinor > 0L) add(PresentedRevenueSource("Hookah", hookahMinor))
            add(PresentedRevenueSource("Event tickets", eventTicketsMinor))
            if (membershipsMinor > 0L) add(PresentedRevenueSource("Memberships", membershipsMinor))
            add(
                PresentedRevenueSource(
                    "Delivery (Zomato/Swiggy §9(5))",
                    deliveryAggregatorMinor,
                    "aggregator pays the GST",
                ),
            )
            if (manualCollectionsMinor > 0L) {
                add(
                    PresentedRevenueSource(
                        "Manual collections (unitemized)",
                        manualCollectionsMinor,
                        "off-POS / legacy daily totals",
                    ),
                )
            }
            if (otherMinor > 0L) add(PresentedRevenueSource("Other", otherMinor))
        }
    }

    val hiddenLegacyMinor = eventTicketsMinor + membershipsMinor + deliveryAggregatorMinor + otherMinor
    return buildList {
        add(PresentedRevenueSource("Products / snacks / drinks", foodMinor))
        add(PresentedRevenueSource("Gaming", gamingMinor))
        if (hookahMinor > 0L) add(PresentedRevenueSource("Shisha", hookahMinor))
        if (manualCollectionsMinor > 0L) {
            add(
                PresentedRevenueSource(
                    "Manual collections (unitemized)",
                    manualCollectionsMinor,
                    "off-POS / legacy daily totals",
                ),
            )
        }
        if (hiddenLegacyMinor > 0L) {
            add(
                PresentedRevenueSource(
                    "Legacy/other revenue",
                    hiddenLegacyMinor,
                    "historical hidden-module sources retained for owner reconciliation",
                ),
            )
        }
    }
}

internal fun ReportRevenue.hiddenLegacySourceMinor(
    presentation: WorkspacePresentationPolicy,
): Long = if (
    presentation.showsRestaurantOperations && presentation.showsMemberships && presentation.showsEvents
) {
    0L
} else {
    eventTicketsMinor + membershipsMinor + deliveryAggregatorMinor + otherMinor
}

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
    @Required @SerialName("branch_id") val branchId: String = "",
    @SerialName("accounting_basis") val accountingBasis: String = "operational_receipt",
    val period: String = "",
    val label: String = "",
    @SerialName("period_start") val periodStart: String = "",
    @SerialName("period_end") val periodEnd: String = "",
    @SerialName("fiscal_year") val fiscalYear: String = "",

    @SerialName("orders_count") val ordersCount: Int = 0,
    @SerialName("tickets_count") val ticketsCount: Int = 0,
    @SerialName("avg_ticket_minor") val avgTicketMinor: Long = 0,
    @Required
    @SerialName("unissued_paid_orders_count")
    val unissuedPaidOrdersCount: Int = 0,

    val revenue: ReportRevenue = ReportRevenue(),
    @SerialName("tax_collected") val taxCollected: ReportTax = ReportTax(),
    @SerialName("payments_received") val paymentsReceived: ReportPayments = ReportPayments(),
    @SerialName("manual_collections_minor") val manualCollectionsMinor: Long = 0,
    @SerialName("tips_collected_minor") val tipsCollectedMinor: Long = 0,
    @SerialName("refunds_issued_minor") val refundsIssuedMinor: Long = 0,
    @SerialName("settled_refunds_issued_minor") val settledRefundsIssuedMinor: Long = 0,
    @SerialName("membership_refunds_issued_minor")
    val membershipRefundsIssuedMinor: Long = 0,
    @SerialName("refunded_tips_minor") val refundedTipsMinor: Long = 0,
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
            avgTicketMinor == 0L &&
            unissuedPaidOrdersCount == 0 &&
            revenue.foodMinor == 0L &&
            revenue.gamingMinor == 0L &&
            revenue.hookahMinor == 0L &&
            revenue.eventTicketsMinor == 0L &&
            revenue.membershipsMinor == 0L &&
            revenue.deliveryAggregatorMinor == 0L &&
            revenue.otherMinor == 0L &&
            revenue.manualCollectionsMinor == 0L &&
            revenue.discountsAndPointsRedeemedMinor == 0L &&
            revenue.roundingIncomeMinor == 0L &&
            revenue.roundingExpenseMinor == 0L &&
            revenue.totalMinor == 0L &&
            taxCollected.cgstMinor == 0L &&
            taxCollected.sgstMinor == 0L &&
            taxCollected.igstMinor == 0L &&
            taxCollected.cessMinor == 0L &&
            paymentsReceived.totalMinor == 0L &&
            manualCollectionsMinor == 0L &&
            tipsCollectedMinor == 0L &&
            refundsIssuedMinor == 0L &&
            settledRefundsIssuedMinor == 0L &&
            membershipRefundsIssuedMinor == 0L &&
            refundedTipsMinor == 0L &&
            netPaymentsReceivedMinor == 0L &&
            expenses.isEmpty() &&
            expenseTotalMinor == 0L &&
            cogsMinor == 0L &&
            depreciationMinor == 0L &&
            grossRevenueMinor == 0L &&
            netRevenueMinor == 0L &&
            grossProfitMinor == 0L &&
            netProfitMinor == 0L
}

/** The four P&L windows this screen can ask the server for. */
enum class ReportPeriod(val tab: String, val title: String) {
    DAILY("Daily", "Daily P&L"),
    MONTHLY("Monthly", "Monthly P&L"),
    QUARTERLY("Quarterly", "Quarterly P&L"),
    YEARLY("Yearly", "Annual P&L"),
}
