package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.net.CostingCoverage
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Reports are read-only aggregations — nothing here mutates money, so none of
 * these calls carries an Idempotency-Key. The reduced Overview requires
 * analytics.read; the full P&L and inventory
 * costing calls require finance.read. A caller must never infer one grant from
 * the other.
 *
 * Query parameter names match backend/app/api/v1/reports/router.py exactly.
 * The Overview dates are optional server-side. Its first Daily request omits
 * them so the company timezone, rather than the tablet timezone, chooses the
 * current business day. Once that validated day is displayed, picker requests
 * send both dates explicitly so the controls and response cannot disagree.
 */
internal interface ReportsApi {

    @GET("reports/overview?schema_version=reports.overview.v2")
    suspend fun overview(
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
    ): ReportsOverviewWire

    /** Catalogue coverage behind COGS; loaded independently so P&L stays available if it fails. */
    @GET("insights/inventory/costing-coverage")
    suspend fun costingCoverage(): CostingCoverage

    /** P&L for one calendar day. `on_date` is ISO, e.g. 2026-08-12. */
    @GET("reports/daily")
    suspend fun daily(@Query("on_date") onDate: String? = null): ReportData

    /** P&L for a calendar month. `yyyy_mm` must look like 2026-08. */
    @GET("reports/monthly")
    suspend fun monthly(@Query("yyyy_mm") yyyyMm: String? = null): ReportData

    /** P&L for one Indian fiscal quarter. `fy` looks like 2026-27, `q` is 1..4. */
    @GET("reports/quarterly")
    suspend fun quarterly(
        @Query("fy") fy: String? = null,
        @Query("q") q: Int? = null,
    ): ReportData

    /** P&L for an Indian fiscal year (April to March). `fy` looks like 2026-27. */
    @GET("reports/yearly")
    suspend fun yearly(@Query("fy") fy: String? = null): ReportData
}
