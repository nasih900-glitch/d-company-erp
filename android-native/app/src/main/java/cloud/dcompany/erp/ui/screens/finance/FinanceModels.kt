package cloud.dcompany.erp.ui.screens.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Field names copied verbatim from backend/app/api/v1/finance/router.py
 * (ExpenseRead, PartnerRead, PLReport, DistributableProfitReport,
 * BusinessMetricsRead) and settings/router.py (ExpenseCategoryRead).
 *
 * Every `*_minor` is an integer count of paise and is therefore a Long. A
 * Double here would reintroduce, at the last mile, exactly the rounding the
 * backend keeps minor units to avoid.
 */

@Serializable
data class Expense(
    val id: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("paid_via") val paidVia: String,
    @SerialName("paid_at") val paidAt: String,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    val note: String? = null,
)

@Serializable
data class ExpenseCategory(
    val id: String,
    val name: String,
    val code: String? = null,
)

/** Only the fields this screen needs; ignoreUnknownKeys drops the rest — same
 * local-copy-per-screen convention as InventoryModels.kt's own Branch. */
@Serializable
data class Branch(
    val id: String,
    val name: String,
    val code: String? = null,
)

@Serializable
data class Partner(
    val id: String,
    val name: String,
    @SerialName("share_pct") val sharePct: Double,
    @SerialName("joined_at") val joinedAt: String,
    val notes: String? = null,
    /** Sum of investments minus capital repayments. Voided entries excluded. */
    @SerialName("capital_balance_minor") val capitalBalanceMinor: Long,
)

@Serializable
data class CapitalEntry(
    val id: String,
    @SerialName("partner_id") val partnerId: String,
    val type: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("effective_at") val effectiveAt: String,
    @SerialName("settlement_account") val settlementAccount: String,
    @SerialName("source_ref") val sourceRef: String? = null,
    val note: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("void_reason") val voidReason: String? = null,
    @SerialName("is_voided") val isVoided: Boolean,
)

@Serializable
data class Asset(
    val id: String,
    @SerialName("branch_id") val branchId: String,
    val name: String,
    val type: String,
    @SerialName("purchase_minor") val purchaseMinor: Long,
    @SerialName("purchase_date") val purchaseDate: String,
    @SerialName("useful_life_months") val usefulLifeMonths: Int,
    @SerialName("salvage_minor") val salvageMinor: Long,
    @SerialName("depreciation_method") val depreciationMethod: String,
    val notes: String? = null,
    // Recomputed server-side "as of now" on every read — never stored. See
    // backend app/services/accounting/depreciation.py.
    @SerialName("accumulated_depreciation_minor") val accumulatedDepreciationMinor: Long,
    @SerialName("book_value_minor") val bookValueMinor: Long,
)

// -------------------------------------------------------- write-path bodies

@Serializable
data class ExpenseCreate(
    @SerialName("branch_id") val branchId: String,
    @SerialName("category_id") val categoryId: String,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("paid_via") val paidVia: String,
    @SerialName("paid_at") val paidAt: String,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("invoice_no") val invoiceNo: String? = null,
    val note: String? = null,
)

@Serializable
data class AssetCreate(
    @SerialName("branch_id") val branchId: String,
    val name: String,
    val type: String,
    @SerialName("purchase_minor") val purchaseMinor: Long,
    @SerialName("purchase_date") val purchaseDate: String,
    @SerialName("useful_life_months") val usefulLifeMonths: Int,
    @SerialName("salvage_minor") val salvageMinor: Long,
    val notes: String? = null,
)

@Serializable
data class CapitalEntryCreate(
    @SerialName("partner_id") val partnerId: String,
    val type: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("effective_at") val effectiveAt: String,
    @SerialName("settlement_account") val settlementAccount: String,
    @SerialName("source_ref") val sourceRef: String,
    val note: String? = null,
)

/**
 * `revenue_minor` is revenue *after* GST is taken out — the backend fills it
 * from the same aggregator the web Reports screen uses (net_revenue_minor),
 * so this screen and the web P&L can never disagree.
 */
@Serializable
data class ProfitAndLoss(
    @SerialName("accounting_basis") val accountingBasis: String = "operational_receipt",
    @SerialName("period_start") val periodStart: String,
    @SerialName("period_end") val periodEnd: String,
    @SerialName("revenue_minor") val revenueMinor: Long,
    @SerialName("memberships_minor") val membershipsMinor: Long = 0,
    @SerialName("cogs_minor") val cogsMinor: Long,
    @SerialName("gross_profit_minor") val grossProfitMinor: Long,
    @SerialName("expenses_minor") val expensesMinor: Long,
    @SerialName("depreciation_minor") val depreciationMinor: Long,
    @SerialName("net_profit_minor") val netProfitMinor: Long,
)

@Serializable
data class DistributablePartnerShare(
    @SerialName("partner_id") val partnerId: String,
    val name: String,
    @SerialName("share_pct") val sharePct: Double,
    @SerialName("capital_balance_minor") val capitalBalanceMinor: Long,
    @SerialName("lifetime_withdrawn_minor") val lifetimeWithdrawnMinor: Long,
    @SerialName("distributable_share_minor") val distributableShareMinor: Long,
)

@Serializable
data class DistributableProfit(
    @SerialName("as_of") val asOf: String,
    @SerialName("lifetime_net_profit_minor") val lifetimeNetProfitMinor: Long,
    @SerialName("lifetime_depreciation_minor") val lifetimeDepreciationMinor: Long,
    @SerialName("lifetime_withdrawn_minor") val lifetimeWithdrawnMinor: Long,
    @SerialName("reserve_months") val reserveMonths: Int,
    @SerialName("avg_monthly_cost_minor") val avgMonthlyCostMinor: Long,
    @SerialName("reserve_minor") val reserveMinor: Long,
    @SerialName("liquid_cash_minor") val liquidCashMinor: Long,
    @SerialName("profit_based_capacity_minor") val profitBasedCapacityMinor: Long,
    @SerialName("cash_based_capacity_minor") val cashBasedCapacityMinor: Long,
    @SerialName("safe_to_distribute_minor") val safeToDistributeMinor: Long,
    val partners: List<DistributablePartnerShare> = emptyList(),
)

@Serializable
data class BusinessMetrics(
    @SerialName("period_start") val periodStart: String,
    @SerialName("period_end") val periodEnd: String,
    @SerialName("aov_minor") val aovMinor: Long,
    @SerialName("orders_count") val ordersCount: Int,
    @SerialName("mrr_minor") val mrrMinor: Long,
    @SerialName("arr_minor") val arrMinor: Long,
    @SerialName("active_members_count") val activeMembersCount: Int,
    /** Null means no new customers this period — not zero cost. */
    @SerialName("cac_minor") val cacMinor: Long? = null,
    @SerialName("new_customers_count") val newCustomersCount: Int,
    @SerialName("marketing_spend_minor") val marketingSpendMinor: Long,
    @SerialName("ltv_minor") val ltvMinor: Long,
    @SerialName("customers_count") val customersCount: Int,
    /** Positive = money lost this period. Negative = profitable. */
    @SerialName("burn_rate_minor") val burnRateMinor: Long,
)

// ---------------------------------------------------------------- formatting

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private val DAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/**
 * The backend sends plain dates ("2026-08-01") for report periods and full
 * timestamps for ledger rows, so all three shapes are tried. An unparseable
 * value is shown as-is rather than swallowed: a raw string on screen is a
 * visible bug, a silently blank date is an invisible one.
 */
private fun localDateOrNull(iso: String?): LocalDate? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    }.recoverCatching {
        LocalDateTime.parse(iso).toLocalDate()
    }.recoverCatching {
        LocalDate.parse(iso)
    }.getOrNull()
}

/** "12 Aug 2026" */
fun String?.asDay(): String = localDateOrNull(this)?.format(DAY) ?: this.orDash()

/** "12 Aug" — for period ranges, where the year is already obvious. */
fun String?.asDayShort(): String = localDateOrNull(this)?.format(DAY_SHORT) ?: this.orDash()

private fun String?.orDash(): String = if (isNullOrBlank()) "—" else this

/** "50%", "33.33%" — trailing zeros trimmed so whole shares read cleanly. */
fun Double.asSharePct(): String {
    val text = String.format(Locale.UK, "%.2f", this).trimEnd('0').trimEnd('.')
    return "$text%"
}

fun paidViaLabel(method: String): String = when (method.lowercase(Locale.UK)) {
    "cash" -> "Cash"
    "upi" -> "UPI"
    "card" -> "Card"
    "bank" -> "Bank transfer"
    else -> method
}

fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
