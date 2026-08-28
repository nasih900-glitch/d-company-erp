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
 * enforces. Expense/asset/capital creates use the durable outbox; manual
 * collections and tip payouts are deliberately live-only because they alter
 * revenue or settle money owed to staff. Those live writes are protected by
 * an exact-request recovery checkpoint and the server's idempotency/audit
 * contract. The base URL already ends in /api/v1/, hence the relative paths.
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

    /** Immutable off-POS collection register. Reads may be cached, but every
     * create/void is a live, audited server operation. */
    @GET("finance/manual-collections")
    suspend fun manualCollections(
        @Query("include_voided") includeVoided: Boolean = true,
        @Query("limit") limit: Int = 500,
    ): List<ManualCollection>

    @POST("finance/manual-collections")
    suspend fun createManualCollection(
        @Body body: ManualCollectionCreate,
        @Header("Idempotency-Key") key: String,
    ): ManualCollection

    @POST("finance/manual-collections/{collection_id}/void")
    suspend fun voidManualCollection(
        @Path("collection_id") collectionId: String,
        @Body body: FinanceVoidRequest,
    ): ManualCollection

    /** The only supported way to clear money already posted to Tips Payable. */
    @GET("finance/tip-payouts")
    suspend fun tipPayouts(
        @Query("include_voided") includeVoided: Boolean = true,
        @Query("limit") limit: Int = 500,
    ): List<TipPayout>

    @POST("finance/tip-payouts")
    suspend fun createTipPayout(
        @Body body: TipPayoutCreate,
        @Header("Idempotency-Key") key: String,
    ): TipPayout

    @POST("finance/tip-payouts/{payout_id}/void")
    suspend fun voidTipPayout(
        @Path("payout_id") payoutId: String,
        @Body body: FinanceVoidRequest,
    ): TipPayout

    /** Supplies the live Tips Payable balance used to prevent over-payout. */
    @GET("accounting/trial-balance")
    suspend fun trialBalance(): TrialBalance

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
