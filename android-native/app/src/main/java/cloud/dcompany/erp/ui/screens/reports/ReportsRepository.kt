package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.db.ReportSnapshotDao
import cloud.dcompany.erp.core.db.ReportSnapshotEntity
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CostingCoverage
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val OverviewCachePrefix = "reports:overview:v2:"
private const val PnlCachePrefix = "reports:pnl:v2:"

internal data class ReportsCacheRow(
    val key: String,
    val jsonBody: String,
    val fetchedAtMillis: Long,
)

internal interface ReportsCacheStore {
    suspend fun get(key: String): ReportsCacheRow?
    suspend fun put(row: ReportsCacheRow)
    suspend fun deletePrefix(prefix: String): Int
}

internal class RoomReportsCacheStore(private val dao: ReportSnapshotDao) : ReportsCacheStore {
    override suspend fun get(key: String): ReportsCacheRow? = dao.get(key)?.let {
        ReportsCacheRow(it.key, it.jsonBody, it.fetchedAtMillis)
    }

    override suspend fun put(row: ReportsCacheRow) {
        dao.put(ReportSnapshotEntity(row.key, row.jsonBody, row.fetchedAtMillis))
    }

    override suspend fun deletePrefix(prefix: String): Int = dao.deleteWithPrefix(prefix)
}

internal data class ReportSelection(
    val period: ReportPeriod,
    val onDate: LocalDate,
    val month: YearMonth,
    val fiscalYear: String,
    val quarter: Int,
) {
    val range: ClosedRange<LocalDate>
        get() = when (period) {
            ReportPeriod.DAILY -> onDate..onDate
            ReportPeriod.MONTHLY -> month.atDay(1)..month.atEndOfMonth()
            ReportPeriod.QUARTERLY -> {
                val firstMonth = ((quarter.coerceIn(1, 4) - 1) * 3 + 4).let {
                    if (it > 12) it - 12 else it
                }
                val year = fiscalStartYear(fiscalYear) + if (firstMonth < 4) 1 else 0
                val start = LocalDate.of(year, firstMonth, 1)
                start..start.plusMonths(3).minusDays(1)
            }
            ReportPeriod.YEARLY -> {
                val start = LocalDate.of(fiscalStartYear(fiscalYear), 4, 1)
                start..start.plusYears(1).minusDays(1)
            }
        }
}

internal data class CachedReportsValue<T>(
    val key: String,
    val value: T,
    val fetchedAtMillis: Long,
)

internal class ReportsRepository(
    private val api: ReportsApi,
    private val cache: ReportsCacheStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fetchOverview(
        lease: CacheScopeLease,
        selection: ReportSelection,
        useServerDefaultDate: Boolean,
        requestApi: ReportsApi = api,
    ): ValidatedReportsOverview {
        val range = selection.range
        if (useServerDefaultDate && selection.period != ReportPeriod.DAILY) {
            throw ReportsContractException("Only Daily Overview can use the current business date.")
        }
        val requestedStart = range.start.takeUnless { useServerDefaultDate }
        val requestedEnd = range.endInclusive.takeUnless { useServerDefaultDate }
        return validateReportsOverview(
            wire = requestApi.overview(requestedStart?.toString(), requestedEnd?.toString()),
            lease = lease,
            requestedStart = requestedStart,
            requestedEnd = requestedEnd,
        )
    }

    suspend fun fetchPnl(lease: CacheScopeLease, selection: ReportSelection): ReportData {
        val report = when (selection.period) {
            ReportPeriod.DAILY -> api.daily(selection.onDate.toString())
            ReportPeriod.MONTHLY -> api.monthly(selection.month.toString())
            ReportPeriod.QUARTERLY -> api.quarterly(selection.fiscalYear, selection.quarter)
            ReportPeriod.YEARLY -> api.yearly(selection.fiscalYear)
        }
        validatePnl(report, lease, selection)
        return report
    }

    suspend fun fetchCosting(lease: CacheScopeLease): CostingCoverage =
        api.costingCoverage().also { coverage ->
            requireBranch(coverage.branchId, lease, "inventory costing status")
        }

    suspend fun latestOverview(
        lease: CacheScopeLease,
        selection: ReportSelection,
    ): CachedReportsValue<ValidatedReportsOverview>? = decodeCached(
        key = reportsOverviewCacheKey(lease, selection),
        validate = { cached ->
            val range = selection.range
            validateCachedReportsOverview(cached, lease, range.start, range.endInclusive)
        },
    )

    suspend fun latestPnl(
        lease: CacheScopeLease,
        selection: ReportSelection,
    ): CachedReportsValue<ReportData>? = decodeCached(
        key = reportsPnlCacheKey(lease, selection),
        validate = { cached -> validatePnl(cached, lease, selection) },
    )

    suspend fun latestCosting(lease: CacheScopeLease): CachedReportsValue<CostingCoverage>? =
        decodeCached(
            key = reportsCostingCacheKey(lease),
            validate = { cached -> requireBranch(cached.branchId, lease, "saved inventory costing status") },
        )

    suspend fun storeOverview(
        lease: CacheScopeLease,
        selection: ReportSelection,
        overview: ValidatedReportsOverview,
    ): Boolean {
        val range = selection.range
        validateCachedReportsOverview(overview, lease, range.start, range.endInclusive)
        return store(reportsOverviewCacheKey(lease, selection), overview)
    }

    suspend fun storePnl(
        lease: CacheScopeLease,
        selection: ReportSelection,
        report: ReportData,
    ): Boolean {
        validatePnl(report, lease, selection)
        return store(reportsPnlCacheKey(lease, selection), report)
    }

    suspend fun storeCosting(lease: CacheScopeLease, coverage: CostingCoverage): Boolean {
        requireBranch(coverage.branchId, lease, "inventory costing status")
        return store(reportsCostingCacheKey(lease), coverage)
    }

    suspend fun deleteOverviewScope(lease: CacheScopeLease): Boolean =
        deletePrefix(reportsOverviewCachePrefix(lease))

    suspend fun deletePnlScope(lease: CacheScopeLease): Boolean =
        deletePrefix(reportsPnlCachePrefix(lease))

    private suspend inline fun <reified T> decodeCached(
        key: String,
        crossinline validate: (T) -> Unit,
    ): CachedReportsValue<T>? {
        val row = try {
            cache.get(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        return try {
            val value = withContext(Dispatchers.Default) {
                ApiClient.json.decodeFromString<T>(row.jsonBody)
            }
            validate(value)
            CachedReportsValue(row.key, value, row.fetchedAtMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend inline fun <reified T> store(key: String, value: T): Boolean = try {
        val body = withContext(Dispatchers.Default) { ApiClient.json.encodeToString(value) }
        cache.put(ReportsCacheRow(key, body, clock.millis()))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun deletePrefix(prefix: String): Boolean = try {
        cache.deletePrefix(prefix)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

internal fun reportsOverviewCachePrefix(lease: CacheScopeLease): String =
    "$OverviewCachePrefix${reportsBranch(lease)}:"

internal fun reportsPnlCachePrefix(lease: CacheScopeLease): String =
    "$PnlCachePrefix${reportsBranch(lease)}:"

internal fun reportsOverviewCacheKey(lease: CacheScopeLease, selection: ReportSelection): String {
    val range = selection.range
    return "${reportsOverviewCachePrefix(lease)}${range.start}:${range.endInclusive}"
}

internal fun reportsPnlCacheKey(lease: CacheScopeLease, selection: ReportSelection): String =
    "${reportsPnlCachePrefix(lease)}${selection.period.name.lowercase()}:${selection.range.start}:${selection.range.endInclusive}"

internal fun reportsCostingCacheKey(lease: CacheScopeLease): String =
    "${reportsPnlCachePrefix(lease)}costing"

private fun validatePnl(report: ReportData, lease: CacheScopeLease, selection: ReportSelection) {
    requireBranch(report.branchId, lease, "P&L report")
    if (report.accountingBasis != "operational_receipt") {
        throw ReportsContractException("Unknown P&L accounting basis.")
    }
    val range = selection.range
    if (report.periodStart != range.start.toString() || report.periodEnd != range.endInclusive.toString()) {
        throw ReportsContractException("P&L range does not match the selection.")
    }
}

private fun requireBranch(actual: String, lease: CacheScopeLease, label: String) {
    val expected = reportsBranch(lease)
    val normalized = runCatching { UUID.fromString(actual.trim()).toString() }.getOrNull()
        ?: throw ReportsContractException("$label branch is invalid.")
    if (normalized != expected) throw ReportsContractException("$label belongs to another branch.")
}

private fun reportsBranch(lease: CacheScopeLease): String =
    lease.scope.branchId?.trim()?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
        ?: throw ReportsContractException("A selected branch is required for Reports.")
