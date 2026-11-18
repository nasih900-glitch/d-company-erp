package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.handover.OperationDispatchGate
import cloud.dcompany.erp.core.net.ApiClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class AttendanceCardsApiTest {
    @Test fun actualRetrofitUsesScopedCardsReadWithoutPrivateSummaryOrNewCredentialFlow() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val actor = "11111111-1111-4111-8111-111111111111"
            val branch = "33333333-3333-4333-8333-333333333333"
            server.enqueue(MockResponse().setBody("""{"period":"week","period_start":"2026-11-02","period_end_exclusive":"2026-11-09","timezone":"Asia/Dubai","as_of":"2026-11-05T00:00:00Z","scope_branch_id":"$branch","current_open_attendance":null,"items":[],"next_cursor":null}"""))
            val client = ApiClient.ephemeralAuthorityClient("synthetic-original-A", singleAttempt = true, operationGate = OperationDispatchGate { _ -> })
            try {
                val api = Retrofit.Builder().baseUrl(server.url("/api/v1/")).client(client)
                    .addConverterFactory(StaffTimeJson.asConverterFactory("application/json".toMediaType())).build().create(StaffTimeApi::class.java)
                val page = api.attendanceCards("week", "2026-11-02", "opaqueCursor")
                validateAttendancePage(page, "week", "2026-11-02", actor, branch, more = true)
                val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No request")
                assertEquals("/api/v1/staff/attendance-cards?period=week&start=2026-11-02&after=opaqueCursor&limit=100", request.path)
                assertEquals("GET", request.method); assertEquals("Bearer synthetic-original-A", request.getHeader("Authorization"))
                assertNull(request.getHeader("Idempotency-Key")); assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
