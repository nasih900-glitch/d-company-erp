package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.handover.OperationDispatchGate
import cloud.dcompany.erp.core.net.ApiClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

class SharedAttendanceApiTest {
    // This standalone transport fixture has no Application; production startup is tested separately.
    private val fixtureGate = OperationDispatchGate { _ -> }
    private val b = "55555555-5555-4555-8555-555555555555"
    private val id = "66666666-6666-4666-8666-666666666666"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private val terminal = "44444444-4444-4444-8444-444444444444"
    private val key = "77777777-7777-4777-8777-777777777777"
    private val response = """{"id":"$id","user_id":"$b","branch_id":"$branch","clock_in_at":"2026-11-05T08:00:00Z","clock_out_at":null,"break_minutes":0,"break_confirmed":false,"updated_at":"2026-11-05T08:00:00Z","source":"app","staff_name":"Synthetic B"}"""
    @Test fun realRetrofitClockWireUsesAOnlyAndTargetPasswordWithoutLoginOrRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(response))
            val client = ApiClient.ephemeralAuthorityClient("synthetic-A", terminal, singleAttempt = true, operationGate = fixtureGate)
            try {
                val api = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                    .client(client).addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
                    .create(SharedAttendanceApi::class.java)
                val receipt = api.clockIn(SharedTimeClockIn(b, "synthetic-B-password"), key)
                assertEquals(b, receipt.userId)
                val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No request")
                assertEquals("/api/v1/staff/shared-attendance/clock-in", request.path)
                assertEquals("Bearer synthetic-A", request.getHeader("Authorization"))
                assertEquals(terminal, request.getHeader("X-Terminal-Id"))
                assertEquals(key, request.getHeader("Idempotency-Key"))
                val body = Json.parseToJsonElement(request.body.readUtf8()).toString()
                assertTrue(body.contains("synthetic-B-password")); assertTrue(body.contains(b))
                assertFalse(body.contains("token")); assertFalse(body.contains("branch_id"))
                assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun eligibleDirectoryCanDecodeEmptyPageAndCarriesCursorWithoutPrivateHistory() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"items":[],"next_cursor":"$b"}"""))
            val client = ApiClient.ephemeralAuthorityClient("synthetic-A", terminal, singleAttempt = true, operationGate = fixtureGate)
            try {
                val api = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                    .client(client).addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
                    .create(SharedAttendanceApi::class.java)
                val page = api.people(null)
                assertTrue(page.items.isEmpty()); assertEquals(b, page.nextCursor)
                val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("No request")
                assertEquals("/api/v1/staff/shared-attendance/people?limit=50", request.path)
                assertEquals("GET", request.method); assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun actualSharedEndpoint503IsNotSilentlyReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503).setBody("{}").addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody(response))
            val client = ApiClient.ephemeralAuthorityClient("synthetic-A", terminal, singleAttempt = true, operationGate = fixtureGate)
            try {
                val api = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                    .client(client).addConverterFactory(Json.asConverterFactory("application/json".toMediaType())).build()
                    .create(SharedAttendanceApi::class.java)
                var failed = false
                try { api.clockIn(SharedTimeClockIn(b, "synthetic-B-password"), key) } catch (_: Exception) { failed = true }
                assertTrue(failed); assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
