package cloud.dcompany.erp.ui.screens.reports

import cloud.dcompany.erp.core.auth.CacheIsolationCoordinator
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeMarker
import cloud.dcompany.erp.core.auth.ReportsAccess
import cloud.dcompany.erp.core.auth.ScopeDataPurger
import cloud.dcompany.erp.core.net.ApiException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit

class ReportsOverviewExportTest {
    private val branch = "00000000-0000-0000-0000-000000000003"
    private val scope = CacheScope("user", "company", branch, "terminal")

    @Test
    fun `admission captures exact lease admitted dates and one pending nonce`(): Unit = runBlocking {
        val coordinator = coordinator()
        val state = state(coordinator)
        val request = requireNotNull(reportsExportRequest(state, coordinator.currentLease()))
        assertEquals("reports-overview-2026-10-01-2026-10-31.csv", request.filename)
        assertEquals(state.overview!!.snapshotAtMillis, request.admittedSnapshotAtMillis)
        assertNotNull(UUID.fromString(request.id))
        assertTrue(reportsExportIsCurrent(request, request.id, state, coordinator.currentLease()))
        assertFalse(reportsExportIsCurrent(request, "another picker", state, coordinator.currentLease()))
        assertFalse("A restored picker nonce without a live request has no authority",
            reportsExportIsCurrent(request, null, state, coordinator.currentLease()))
        assertNull(reportsExportRequest(state.copy(overviewExportBusy = true), coordinator.currentLease()))
        for (changed in listOf(
            state.copy(access = ReportsAccess(canReadOverview = true)),
            state.copy(access = ReportsAccess(canReadPnl = true, canExportOverview = true)),
            state.copy(surface = ReportsSurface.PNL),
            state.copy(overview = null), state.copy(overviewLoading = true),
            state.copy(overviewDenied = true), state.copy(overviewUsesServerDefaultDate = true),
            state.copy(month = YearMonth.of(2026, 9)),
            state.copy(overview = state.overview!!.copy(snapshotAtMillis = 2L)),
        )) assertFalse(reportsExportIsCurrent(request, request.id, changed, coordinator.currentLease()))
        coordinator.deactivate()
        coordinator.activateValidated(scope)
        assertFalse(reportsExportIsCurrent(request, request.id, state, coordinator.currentLease()))
        assertNull(reportsExportRequest(state, coordinator.currentLease()))
        for (changedScope in listOf(
            scope.copy(userId = "another-user"), scope.copy(companyId = "another-company"),
            scope.copy(branchId = UUID.randomUUID().toString()), scope.copy(terminalId = "another-till"),
        )) {
            coordinator.activateValidated(changedScope)
            assertFalse(reportsExportIsCurrent(request, request.id, state, coordinator.currentLease()))
            assertNull(reportsExportRequest(state, coordinator.currentLease()))
        }
    }

    @Test
    fun `raw endpoint sends admitted dates and preserves UTF8 CSV bytes and hostile quoted names`(): Unit = runBlocking {
        val coordinator = coordinator()
        val request = requireNotNull(reportsExportRequest(state(coordinator), coordinator.currentLease()))
        val bytes = csv(request)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/csv; charset=utf-8")
                .setBody(Buffer().write(bytes)))
            val api = Retrofit.Builder().baseUrl(server.url("/api/v1/")).build()
                .create(ReportsOverviewExportApi::class.java)
            assertArrayEquals(bytes, fetchReportsOverviewCsv(api, request))
            assertEquals(
                "/api/v1/reports/overview.csv?from_date=2026-10-01&to_date=2026-10-31",
                server.takeRequest().path,
            )
        }
    }

    @Test
    fun `wrong scope version dates timezone repeated metadata and malformed CSV fail closed`(): Unit = runBlocking {
        val coordinator = coordinator()
        val request = requireNotNull(reportsExportRequest(state(coordinator), coordinator.currentLease()))
        val valid = csv(request).toString(Charsets.UTF_8)
        for (invalid in listOf(
            valid.replace(branch, UUID.randomUUID().toString()),
            valid.replace("reports.overview.csv.v1", "reports.overview.csv.v2"),
            valid.replace("reports.overview.v2", "reports.overview.v1"),
            valid.replace("2026-10-31", "2026-10-30"),
            valid.replace("'Asia/Dubai", "'Asia/Kolkata"),
            valid.replaceFirst("2026-10-28T08:00:00Z", "2026-10-28T09:00:00Z"),
            valid.replace("period_start", "unrecognized"),
            valid.replace("operational_receipt", "financial_ledger"),
            valid.dropLast(1),
            valid.replace("'food", "=FORMULA()"),
            valid.replace("device_usage", "private_finance"),
            valid.substringBefore("\"device_usage\"").substringBeforeLast("\r\n") + "\r\n",
        )) {
            assertThrows(ReportsContractException::class.java) {
                validateReportsOverviewCsv(invalid.toByteArray(), request)
            }
        }
        assertThrows(ReportsContractException::class.java) {
            validateReportsOverviewCsv(byteArrayOf(0xC3.toByte(), 0x28), request)
        }
    }

    @Test
    fun `wrong media type and known or chunked oversized bodies are closed without saving`(): Unit = runBlocking {
        val coordinator = coordinator()
        val request = requireNotNull(reportsExportRequest(state(coordinator), coordinator.currentLease()))
        var closed = 0
        for (length in listOf(-1L, ReportsCsvMaximumBytes.toLong() + 1L)) {
            val body = object : ResponseBody() {
                private val data = object : ForwardingSource(Buffer().write(ByteArray(ReportsCsvMaximumBytes + 1))) {
                    override fun close() { closed++; super.close() }
                }.buffer()
                override fun contentType() = "text/csv; charset=utf-8".toMediaType()
                override fun contentLength() = length
                override fun source() = data
            }
            assertThrows(ReportsContractException::class.java) {
                runBlocking { fetchReportsOverviewCsv(api(body), request) }
            }
        }
        assertTrue(closed >= 2)
        assertThrows(ReportsContractException::class.java) {
            runBlocking { fetchReportsOverviewCsv(api(csv(request).toResponseBody("application/json".toMediaType())), request) }
        }
    }

    @Test
    fun `scope loss during download or provider open prevents bytes and authorization`(): Unit = runBlocking {
        for (phase in listOf("before", "fetch", "open")) {
            var current = phase != "before"
            var fetches = 0
            var opens = 0
            var authorizations = 0
            val output = ByteArrayOutputStream()
            val result = exportReportsOverviewCsv(
                isCurrent = { current },
                fetch = { fetches++; if (phase == "fetch") current = false; byteArrayOf(1, 2, 3) },
                authorizeWrite = { authorizations++; true },
                openOutput = { opens++; current = false; output },
            )
            assertEquals(ReportsCsvExportResult.Stale, result)
            assertEquals(0, output.size())
            assertEquals(0, authorizations)
            assertEquals(if (phase == "before") 0 else 1, fetches)
            assertEquals(if (phase == "open") 1 else 0, opens)
        }
    }

    @Test
    fun `denied write admission closes provider with no bytes and no saved result`(): Unit = runBlocking {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        val result = exportReportsOverviewCsv({ true }, { byteArrayOf(1) }, { false }, { output })
        assertEquals(ReportsCsvExportResult.Stale, result)
        assertEquals(0, output.size())
        assertTrue(closed)
    }

    @Test
    fun `revocation after a chunk stops further writes and cannot report success`(): Unit = runBlocking {
        var current = true
        val bytes = ByteArray(20_000) { 42 }
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                super.write(bytes, offset, count)
                current = false
            }
        }
        val result = exportReportsOverviewCsv({ current }, { bytes }, { true }, { output })
        assertEquals(ReportsCsvExportResult.Stale, result)
        assertEquals(8_192, output.size())
    }

    @Test
    fun `provider open write flush and close failures never report saved`(): Unit = runBlocking {
        for (phase in listOf("open", "write", "flush", "close")) {
            val output = object : OutputStream() {
                override fun write(value: Int) { if (phase == "write") throw IOException("write failed") }
                override fun flush() { if (phase == "flush") throw IOException("flush failed") }
                override fun close() { if (phase == "close") throw IOException("close failed") }
            }
            val result = exportReportsOverviewCsv({ true }, { byteArrayOf(1) }, { true }) {
                if (phase == "open") throw IOException("open failed") else output
            }
            assertTrue(result is ReportsCsvExportResult.Failed)
            assertTrue((result as ReportsCsvExportResult.Failed).message.contains("empty or incomplete"))
        }
    }

    @Test
    fun `export denials never open a document and stale denials are ignored`(): Unit = runBlocking {
        for (status in listOf(401, 403)) {
            var current = true
            var opens = 0
            val denied = exportReportsOverviewCsv({ current }, { throw ApiException("denied", status) }, { true }) {
                opens++; ByteArrayOutputStream()
            }
            assertEquals(ReportsCsvExportResult.Denied, denied)
            val stale = exportReportsOverviewCsv({ current }, {
                current = false
                throw ApiException("old denial", status)
            }, { true }) { opens++; ByteArrayOutputStream() }
            assertEquals(ReportsCsvExportResult.Stale, stale)
            assertEquals(0, opens)
        }
    }

    @Test
    fun `cancellation propagates and successful write preserves every byte`(): Unit = runBlocking {
        var opened = false
        assertThrows(CancellationException::class.java) {
            runBlocking {
                exportReportsOverviewCsv({ true }, { throw CancellationException("cancelled") }, { true }) {
                    opened = true; ByteArrayOutputStream()
                }
            }
        }
        assertFalse(opened)
        val bytes = "'café,\"quoted\"\r\n0.125,-100".toByteArray()
        val output = ByteArrayOutputStream()
        assertEquals(ReportsCsvExportResult.Saved, exportReportsOverviewCsv({ true }, { bytes }, { true }, { output }))
        assertArrayEquals(bytes, output.toByteArray())
    }

    private fun api(body: ResponseBody) = object : ReportsOverviewExportApi {
        override suspend fun overviewCsv(fromDate: String, toDate: String): Response<ResponseBody> = Response.success(body)
    }

    private suspend fun coordinator() = CacheIsolationCoordinator(
        object : ScopeDataPurger {
            override suspend fun hasUnresolvedWork() = false
            override suspend fun purgeIfClean() = true
        },
        object : CacheScopeMarker {
            private var value: CacheScope? = scope
            override fun current() = value
            override fun remember(scope: CacheScope): Boolean { value = scope; return true }
            override fun clear(): Boolean { value = null; return true }
        },
    ).also { it.activateValidated(scope) }

    private fun state(coordinator: CacheIsolationCoordinator): ReportsUiState {
        val overview = ValidatedReportsOverview(
            branch, "2026-10-01", "2026-10-31", "Asia/Dubai", 1L, 0L, 0L, 0L, 0L, 0L, 0L,
            ReportsOverviewSources(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ReportsOverviewPayments(0, 0, 0, 0, 0, 0, 0, 0),
            ReportsOverviewHourProfile.MultiDayUnavailable, emptyList(), emptyList(),
        )
        return ReportsUiState(
            access = ReportsAccess(true, false, true), lease = coordinator.currentLease(),
            period = ReportPeriod.MONTHLY, onDate = LocalDate.of(2026, 10, 27),
            month = YearMonth.of(2026, 10), fiscalYear = "2026-27", quarter = 3,
            overview = overview, overviewUsesServerDefaultDate = false,
        )
    }

    private fun csv(request: ReportsOverviewExportRequest): ByteArray {
        val header = "csv_schema_version,schema_version,branch_id,period_start,period_end,timezone,snapshot_at,accounting_basis,section,row_key,label,metric,value,unit,availability,basis"
        val metadata = listOf("reports.overview.csv.v1", "reports.overview.v2", branch,
            request.periodStart, request.periodEnd, "'Asia/Dubai", "2026-10-28T08:00:00Z", "operational_receipt")
        fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
        fun row(section: String, key: String, label: String, metric: String, value: String,
                availability: String = "available") =
            (metadata + listOf(section, key, label, metric, value, "text", availability, "test_basis"))
                .joinToString(",", transform = ::quoted) + "\r\n"
        return (header + "\r\n" +
            row("overview", "summary", "Overview", "gross_revenue_minor", "0") +
            row("revenue_by_source", "summary", "Sources", "total_minor", "0") +
            row("payments_received", "summary", "Payments", "total_minor", "0") +
            row("linked_customers", "summary", "Customers", "linked_orders_count", "0") +
            row("local_hour_profile", "summary", "Hours", "reason", "multi_day_range") +
            row("top_products", "1", "'=SUM(1,2)\r\n\"café\"", "product_type", "'food") +
            row("top_products", "1", "'=SUM(1,2)\r\n\"café\"", "quantity_sold", "0.125") +
            row("device_usage", "summary", "Devices", "grouping", "device_type") +
            row("device_usage", "1", "'VR", "play_elapsed_ms", "", "unknown") +
            row("device_usage", "2", "'PS5", "play_elapsed_ms", "0")).toByteArray()
    }
}
