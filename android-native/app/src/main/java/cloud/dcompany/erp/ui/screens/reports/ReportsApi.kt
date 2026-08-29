package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.net.CostingCoverage
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Reports are read-only aggregations — nothing here mutates money, so none of
 * these calls carries an Idempotency-Key. Every endpoint requires the
 * analytics.read permission; a cashier account gets a 403 whose message the
 * screen shows verbatim.
 *
 * Query parameter names match backend/app/api/v1/reports/router.py exactly.
 * All are optional server-side and default to the current period in the
 * company's own timezone, but this screen always sends them so the picker and
 * the report on screen can never disagree.
 */
interface ReportsApi {

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
