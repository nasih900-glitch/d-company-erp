package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiException
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Separate raw-body API keeps the existing JSON Reports contract unchanged. */
internal interface ReportsOverviewExportApi {
    @Streaming
    @GET("reports/overview.csv")
    suspend fun overviewCsv(
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
    ): Response<ResponseBody>
}

internal const val ReportsCsvMaximumBytes = 1_048_576

internal data class ReportsOverviewExportRequest(
    val id: String,
    val lease: CacheScopeLease,
    val selection: ReportSelection,
    val periodStart: String,
    val periodEnd: String,
    val timezone: String,
    val admittedSnapshotAtMillis: Long,
) {
    val filename: String get() = "reports-overview-$periodStart-$periodEnd.csv"
}

internal fun canExportReportsOverview(state: ReportsUiState, lease: CacheScopeLease?): Boolean {
    val overview = state.overview ?: return false
    val range = state.selection.range
    return lease != null && state.lease == lease && state.surface == ReportsSurface.OVERVIEW &&
        state.operationalTab != ReportsOperationalTab.INVENTORY &&
        state.access.canReadOverview && state.access.canExportOverview &&
        !state.overviewDenied && !state.overviewLoading && !state.overviewUsesServerDefaultDate &&
        overview.branchId == lease.scope.branchId && overview.periodStart == range.start.toString() &&
        overview.periodEnd == range.endInclusive.toString()
}

internal fun reportsExportRequest(
    state: ReportsUiState,
    lease: CacheScopeLease?,
): ReportsOverviewExportRequest? {
    if (state.overviewExportBusy || !canExportReportsOverview(state, lease)) return null
    val overview = state.overview ?: return null
    return ReportsOverviewExportRequest(
        UUID.randomUUID().toString(), requireNotNull(lease), state.selection,
        overview.periodStart, overview.periodEnd, overview.timezone, overview.snapshotAtMillis,
    )
}

internal fun reportsExportIsCurrent(
    request: ReportsOverviewExportRequest,
    pendingId: String?,
    state: ReportsUiState,
    lease: CacheScopeLease?,
): Boolean = pendingId == request.id && request.lease == lease &&
    request.selection == state.selection && canExportReportsOverview(state, lease) &&
    request.admittedSnapshotAtMillis == state.overview?.snapshotAtMillis

internal suspend fun fetchReportsOverviewCsv(
    api: ReportsOverviewExportApi,
    request: ReportsOverviewExportRequest,
): ByteArray = withContext(Dispatchers.IO) {
    val response = api.overviewCsv(request.periodStart, request.periodEnd)
    if (!response.isSuccessful) {
        response.errorBody()?.close()
        response.body()?.close()
        throw ApiException("Overview CSV is unavailable (HTTP ${response.code()}).", response.code())
    }
    val body = response.body() ?: throw ReportsContractException("CSV response is empty.")
    body.use {
        val type = it.contentType()
        if (type == null || type.type != "text" || type.subtype != "csv" ||
            type.charset(Charsets.UTF_8) != Charsets.UTF_8
        ) throw ReportsContractException("The server did not return a UTF-8 CSV report.")
        if (it.contentLength() > ReportsCsvMaximumBytes) {
            throw ReportsContractException("Overview CSV exceeds the supported size.")
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        it.byteStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size() + count > ReportsCsvMaximumBytes) {
                    throw ReportsContractException("Overview CSV exceeds the supported size.")
                }
                output.write(buffer, 0, count)
            }
        }
        output.toByteArray().also { bytes -> validateReportsOverviewCsv(bytes, request) }
    }
}

private val ReportsCsvHeader = listOf(
    "csv_schema_version", "schema_version", "branch_id", "period_start", "period_end",
    "timezone", "snapshot_at", "accounting_basis", "section", "row_key", "label",
    "metric", "value", "unit", "availability", "basis",
)

/** Metadata is checked on every record; bytes are never decoded and re-encoded for saving. */
internal fun validateReportsOverviewCsv(bytes: ByteArray, request: ReportsOverviewExportRequest) {
    if (bytes.isEmpty() || bytes.size > ReportsCsvMaximumBytes) {
        throw ReportsContractException("Overview CSV is empty or exceeds the supported size.")
    }
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw ReportsContractException("Overview CSV is not valid UTF-8.")
    }
    val records = parseReportsCsv(text)
    if (records.size < 2 || records.first() != ReportsCsvHeader) {
        throw ReportsContractException("Unsupported Overview CSV columns.")
    }
    val first = records[1]
    val metadata = listOf(
        "reports.overview.csv.v1", "reports.overview.v2", request.lease.scope.branchId.orEmpty(),
        request.periodStart, request.periodEnd, "'${request.timezone}",
    )
    if (first.take(6) != metadata || first[7] != "operational_receipt" ||
        runCatching { OffsetDateTime.parse(first[6]) }.isFailure
    ) throw ReportsContractException("Overview CSV does not match this branch and date range.")
    val allowedSections = setOf(
        "overview", "revenue_by_source", "payments_received", "linked_customers",
        "local_hour_profile", "top_products", "device_usage",
    )
    val identities = mutableSetOf<List<String>>()
    val sections = mutableSetOf<String>()
    for (record in records.drop(1)) {
        if (record.take(8) != first.take(8) || record[8] !in allowedSections ||
            !identities.add(listOf(record[8], record[9], record[11]))
        ) throw ReportsContractException("Overview CSV contains inconsistent report metadata.")
        sections += record[8]
        if (record[9] != "summary" && record[8] in setOf("top_products", "device_usage") &&
            record[10].isNotEmpty() && !record[10].startsWith("'")
        ) throw ReportsContractException("Overview CSV contains unsafe editable text.")
        if (record[11] == "product_type" && record[12].isNotEmpty() && !record[12].startsWith("'")) {
            throw ReportsContractException("Overview CSV contains unsafe editable text.")
        }
    }
    if (sections != allowedSections) throw ReportsContractException("Overview CSV is incomplete.")
}

/** RFC 4180 quoting, including embedded CR/LF and doubled quotes; fixed 16-column contract. */
private fun parseReportsCsv(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closedQuote = false
    var index = 0
    fun fail(): Nothing = throw ReportsContractException("Malformed Overview CSV.")
    fun finishField() {
        fields += field.toString()
        field.setLength(0)
        closedQuote = false
        if (fields.size > ReportsCsvHeader.size) fail()
    }
    while (index < text.length) {
        val char = text[index++]
        if (quoted) {
            if (char == '"') {
                if (index < text.length && text[index] == '"') { field.append('"'); index++ }
                else { quoted = false; closedQuote = true }
            } else field.append(char)
            continue
        }
        when (char) {
            '"' -> if (field.isEmpty() && !closedQuote) quoted = true else fail()
            ',' -> finishField()
            '\r' -> {
                if (index >= text.length || text[index++] != '\n') fail()
                finishField()
                if (fields.size != ReportsCsvHeader.size) fail()
                records += fields.toList()
                fields.clear()
            }
            '\n' -> fail()
            else -> if (closedQuote) fail() else field.append(char)
        }
    }
    if (quoted || closedQuote || field.isNotEmpty() || fields.isNotEmpty()) fail()
    return records
}

internal sealed interface ReportsCsvExportResult {
    data object Saved : ReportsCsvExportResult
    data object Stale : ReportsCsvExportResult
    data object Denied : ReportsCsvExportResult
    data class Failed(val message: String) : ReportsCsvExportResult
}

/** The provider is opened only after a fresh, validated server response. No bytes are cached. */
internal suspend fun exportReportsOverviewCsv(
    isCurrent: () -> Boolean,
    fetch: suspend () -> ByteArray,
    authorizeWrite: suspend () -> Boolean,
    openOutput: () -> OutputStream?,
): ReportsCsvExportResult {
    if (!isCurrent()) return ReportsCsvExportResult.Stale
    var saving = false
    return try {
        val bytes = fetch()
        if (!isCurrent()) return ReportsCsvExportResult.Stale
        withContext(Dispatchers.IO) {
            if (!isCurrent()) return@withContext ReportsCsvExportResult.Stale
            saving = true
            val output = openOutput() ?: error("The selected document could not be opened.")
            output.use {
                // Recheck after a potentially blocking provider open. The short authorization
                // commit never holds the global workspace mutex during external provider IO.
                if (!isCurrent() || !authorizeWrite()) return@withContext ReportsCsvExportResult.Stale
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@withContext ReportsCsvExportResult.Stale
                    val count = minOf(8_192, bytes.size - offset)
                    it.write(bytes, offset, count)
                    offset += count
                }
                it.flush()
            }
            if (isCurrent()) ReportsCsvExportResult.Saved else ReportsCsvExportResult.Stale
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ApiException) {
        if (!isCurrent()) ReportsCsvExportResult.Stale
        else if (error.status == 401 || error.status == 403) ReportsCsvExportResult.Denied
        else ReportsCsvExportResult.Failed("Could not fetch the CSV. Reconnect and try again.")
    } catch (error: Exception) {
        if (!isCurrent()) ReportsCsvExportResult.Stale
        else ReportsCsvExportResult.Failed(
            if (error is ReportsContractException) error.message ?: "The CSV could not be verified."
            else if (!saving) "Could not fetch the CSV. Reconnect and try again."
            else "Could not finish saving CSV. The selected file may be empty or incomplete.",
        )
    }
}
