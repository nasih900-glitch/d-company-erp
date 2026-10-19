package cloud.dcompany.erp.ui.screens.audit

import cloud.dcompany.erp.core.net.ApiClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class AuditEventsApiContractTest {
    private fun api(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/api/v1/"))
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build().create(AuditLogApi::class.java)
    private val emptySnapshot = """{"entries":[],"next_cursor":null,"activity_cursor":"signed-empty-cursor","has_more":false,"snapshot_max_id":0,"loaded_count":0,"matching_count":0,"catalogue_version":1,"shop_timezone":"Asia/Kolkata","date_filter_basis":"server_recorded_shop_date","count_basis":"events_not_revenue","new_entries_available":false,"snapshot_expires_in":600,"scope":"company_wide_protected_owner"}"""

    @Test fun catalogueAndSnapshotRequestsUseProtectedHeaderAndEntireBoundFilterTuple() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            server.enqueue(MockResponse().setBody("""{"version":1,"categories":["stock"],"outcomes":["completed"],"views":["business","all"],"actions":[{"code":"STK-MOVE","label":"Stock moved","category":"stock","aliases":["move"],"stages":["committed"],"outcomes":["completed"]}]}"""))
            server.enqueue(MockResponse().setBody(emptySnapshot))
            val catalogue = api.catalogue("memory-only-grant")
            assertEquals("Stock moved",catalogue.actions.single().label)
            val definitions = server.takeRequest()
            assertEquals("/api/v1/admin/audit/catalogue",definitions.path)
            assertEquals("memory-only-grant",definitions.getHeader("X-Audit-Token"))
            val page = api.events("memory-only-grant",50,"retained-older-cursor","all","STK-MOVE","stock","completed","actor","target","D/MN/26/10","2026-10-17","2026-10-18","drink move")
            assertEquals("signed-empty-cursor",page.activityCursor)
            val request = server.takeRequest()
            val query = requireNotNull(request.requestUrl)
            val expected = mapOf("cursor" to "retained-older-cursor","limit" to "50","view" to "all","code" to "STK-MOVE","category" to "stock","outcome" to "completed","actor_user_id" to "actor","target_user_id" to "target","reference" to "D/MN/26/10","date_from" to "2026-10-17","date_to" to "2026-10-18","q" to "drink move")
            expected.forEach { (key,value) -> assertEquals(value,query.queryParameter(key)) }
            assertEquals("memory-only-grant",request.getHeader("X-Audit-Token"))
            assertFalse(request.path.orEmpty().contains("memory-only-grant"))
        }
    }

    @Test fun emptySnapshotActivityCheckReusesItsSignedCursor() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            repeat(2) { server.enqueue(MockResponse().setBody(emptySnapshot)) }
            val loaded = api.events("grant")
            api.events("grant",cursor=loaded.activityCursor)
            assertNull(server.takeRequest().requestUrl?.queryParameter("cursor"))
            assertEquals("signed-empty-cursor",server.takeRequest().requestUrl?.queryParameter("cursor"))
        }
    }
}
