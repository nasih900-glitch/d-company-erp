package cloud.dcompany.erp.ui.screens.finance

import retrofit2.http.GET

/**
 * Finance is read-only on the tablet, so every call here is a GET and none of
 * them needs an Idempotency-Key — nothing on this screen moves money.
 *
 * Recording an expense, a partner capital movement, or voiding one stays in
 * the web ERP on purpose: those entries are immutable and demand an evidence
 * reference (bank UTR / UPI id / voucher no.) or a written void reason, which
 * is not something to type one-handed on a counter tablet.
 *
 * Every endpoint below is behind the same `finance.read` permission, so a user
 * who can open this screen can load all of it. The base URL already ends in
 * /api/v1/, hence the relative paths.
 */
interface FinanceApi {

    /**
     * Month-to-date P&L: revenue (after GST), cost of goods sold, gross
     * profit, expenses, depreciation and operating profit — the same numbers
     * the web Reports screen shows, from the same aggregator.
     */
    @GET("finance/pnl")
    suspend fun profitAndLoss(): ProfitAndLoss

    /** AOV, MRR/ARR, LTV, CAC and burn rate for the current period. */
    @GET("finance/metrics")
    suspend fun metrics(): BusinessMetrics

    /** All-time: what the partners could actually take out right now. */
    @GET("finance/distributable")
    suspend fun distributable(): DistributableProfit

    /** Newest first, as ordered by the server. */
    @GET("finance/expenses")
    suspend fun expenses(): List<Expense>

    @GET("finance/partners")
    suspend fun partners(): List<Partner>

    /** Only used to put a name on each expense's category_id. */
    @GET("settings/expense-categories")
    suspend fun expenseCategories(): List<ExpenseCategory>
}
