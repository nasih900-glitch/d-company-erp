package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.net.ApiClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class DeviceHealthApiTest {
    @Test fun readOnlyExistingRoutesPreserveOccurredReceivedAndLastReportedBuilds()=runBlocking {
        MockWebServer().use { server ->
            val api=Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType())).build().create(DeviceHealthApi::class.java)
            server.enqueue(MockResponse().setBody("""{"server_time":"2026-10-19T10:00:00Z","stale_after_hours":24,"total":1,"items":[{"installation_id":"device","platform":"android","distribution_channel":"direct","version_name":"3.1.42","version_code":50,"pending_outbox_count":3,"last_successful_sync_at":null,"update_state":"installed","update_error_code":null,"last_seen_at":"2026-10-16T10:00:00Z","is_stale":true,"last_user_name":"QA Current Label","terminal_name":"QA Till"}]}"""))
            val devices=api.installations(offset=50)
            assertEquals(50,devices.items.single().versionCode);assertTrue(devices.items.single().isStale)
            assertEquals(3,devices.items.single().pendingOutboxCount)
            val first=server.takeRequest();assertEquals("GET",first.method)
            assertEquals("/api/v1/client-installations?limit=50&offset=50",first.path)
            server.enqueue(MockResponse().setBody("""{"items":[{"id":"report","installation_id":"device","event_type":"anr","severity":"critical","occurred_at":"2026-10-17T09:00:00Z","received_at":"2026-10-19T09:00:00Z","version_name":"3.1.43","version_code":51,"component":"app","reason_code":"process_anr","connectivity":"offline","pending_outbox_count":null}],"total":1,"limit":50,"offset":50,"retention_days":30}"""))
            val row=api.incidents("anr",offset=50).items.single()
            assertNotEquals(row.occurredAt,row.receivedAt);assertTrue(row.buildEvidence().contains("Actual incident build was not retained"))
            val second=server.takeRequest();assertEquals("GET",second.method)
            assertEquals("/api/v1/client-diagnostics/events?event_type=anr&limit=50&offset=50",second.path)
        }
    }
}
