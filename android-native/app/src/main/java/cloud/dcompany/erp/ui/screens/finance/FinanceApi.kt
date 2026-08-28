package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.CostingCoverage
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Recording an expense, a partner capital movement, or an asset are all
 * offline-capable, insert-only writes. Corrections use separate authorised,
 * evidence-preserving workflows rather than mutating these captured rows. Each carries
 * an Idempotency-Key: none of the three has a natural key a duplicate retry
 * could collide against server-side, so a receipt sent twice because a
 * tablet lost the reply mid-request would otherwise silently double an
 * expense, a capital movement, or the asset register — same reasoning as
 * Inventory's GRN/adjustment writes.
 *
 * Every endpoint below is behind the same `finance.read`/`finance.write`/
 * `finance.partner.write`/`finance.assets.write` permissions the backend
 * enforces — this app shows every write affordance optimistically and lets a
 * 403 land as a rejected outbox row with the server's own message, rather
 * than trying to pre-derive fine-grained permission state from the
 * module-level `accessible_modules` list `/auth/me` returns (which cannot
 * distinguish "can read finance" from "can write assets" anyway). The base
 * URL already ends in /api/v1/, hence the relative paths.
 */
interface FinanceApi {

    @GET("insights/inventory/costing-coverage")
    suspend fun costingCoverage(): CostingCoverage

    /**
     * Month-to-date P&L: revenue (after GST), cost of goods sold, gross
     * profit, expenses, depreciation and operating profit — the same numbers
     * the web Reports screen shows, from the same aggregator.
     */
    @GET("finance/pnl")
    suspend fun profitAndLoss(): ProfitAndLoss

    /** AOV, active paid terms, LTV, CAC and burn rate for the current period.
     * MRR/ARR remain in the wire contract for future recurring billing but
     * are deliberately not presented while every manual term is prepaid. */
    @GET("finance/metrics")
    suspend fun metrics(): BusinessMetrics

    /** All-time: what the partners could actually take out right now. */
    @GET("finance/distributable")
    suspend fun distributable(): DistributableProfit

    /** Newest first, as ordered by the server. Unconditional full list — no
     * from_date/to_date window, matching this app's existing behavior. */
    @GET("finance/expenses")
    suspend fun expenses(): List<Expense>

    @POST("finance/expenses")
    suspend fun createExpense(
        @Body body: ExpenseCreate,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Expense

    @GET("finance/partners")
    suspend fun partners(): List<Partner>

    @GET("finance/partners/{partner_id}/capital")
    suspend fun capitalEntries(
        @Path("partner_id") partnerId: String,
        @Query("include_voided") includeVoided: Boolean = true,
    ): List<CapitalEntry>

    @POST("finance/capital-entries")
    suspend fun createCapitalEntry(
        @Body body: CapitalEntryCreate,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): CapitalEntry

    @GET("finance/assets")
    suspend fun assets(): List<Asset>

    @POST("finance/assets")
    suspend fun createAsset(
        @Body body: AssetCreate,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Asset

    /** Only used to put a name on each expense's category_id. */
    @GET("settings/expense-categories")
    suspend fun expenseCategories(): List<ExpenseCategory>

    /** Both expense and asset create need a least-privilege branch picker. */
    @GET("finance/branches")
    suspend fun branches(): List<Branch>
}
