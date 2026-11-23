package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.handover.OperationDispatchGate
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** Same factory selected by FinanceViewModel; explicit synthetic HTTP authority, no Application session. */
class FinanceSingleAttemptTransportTest {
    private val fixtureGate = OperationDispatchGate { _ -> }
    private val scope = FinanceWriteScope("synthetic-owner", "synthetic-company", "synthetic-branch")
    private fun write() = pendingManualCollectionCreate(scope,
        ManualCollectionCreate("synthetic-branch", businessDate = "2026-11-05", method = "cash",
            amountMinor = 100, sourceRef = "Synthetic fixture"), idempotencyKey = "manual-collection:synthetic-exact")

    private suspend fun lostResponseThenExplicitDenial(status: Int) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"detail\":\"Synthetic denial\"}"))
            val write = write()
            val before = ApiClient.json.encodeToString(write)
            var checkpoint: String? = before
            val executor = singleAttemptFinanceWriteExecutor("synthetic-original-A", "synthetic-terminal", {},
                baseUrl = server.url("/api/v1/").toString(), operationGate = fixtureGate)
            val lost = requireNotNull(runCatching { executor.execute(write) }.exceptionOrNull())
            assertTrue(lost is ApiException); assertNull((lost as ApiException).status)
            if (!preserveFinanceWriteForRetry(lost, false)) checkpoint = null
            assertEquals(before, checkpoint)
            assertEquals("Neither transport nor authentication may replay the lost POST", 1, server.requestCount)
            val first = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", first.method)
            assertEquals("/api/v1/finance/manual-collections", first.path)
            assertEquals("Bearer synthetic-original-A", first.getHeader("Authorization"))
            assertEquals("synthetic-terminal", first.getHeader("X-Terminal-Id"))
            assertEquals(write.idempotencyKey, first.getHeader("Idempotency-Key"))

            // Only this explicit retry consumes the denial queued behind the received, lost-response POST.
            val explicitRetry = singleAttemptFinanceWriteExecutor("synthetic-original-A", "synthetic-terminal", {},
                baseUrl = server.url("/api/v1/").toString(), operationGate = fixtureGate)
            val denied = requireNotNull(runCatching { explicitRetry.execute(write) }.exceptionOrNull())
            assertTrue(denied is ApiException); assertEquals(status, (denied as ApiException).status)
            assertFalse("This status is correctable only for a known first physical refusal", preserveFinanceWriteForRetry(denied, false))
            if (!preserveFinanceWriteForRetry(denied, true)) checkpoint = null
            assertEquals(before, checkpoint)
            assertEquals(2, server.requestCount)
            val second = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(first.path, second.path)
            assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun droppedPostThenQueued401RequiresExplicitExactRetry() = runBlocking { lostResponseThenExplicitDenial(401) }
    @Test fun droppedPostThenQueued403RequiresExplicitExactRetry() = runBlocking { lostResponseThenExplicitDenial(403) }
    @Test fun droppedPostThenQueued422RequiresExplicitExactRetry() = runBlocking { lostResponseThenExplicitDenial(422) }

    @Test fun confirmedFinancePostDoesNotFollowRedirectOrRetry503() = runBlocking {
        for (status in listOf(307, 503)) MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(status).addHeader("Location", server.url("/unexpected"))
                .addHeader("Retry-After", "0").setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(401))
            val executor = singleAttemptFinanceWriteExecutor("synthetic-original-A", null, {},
                baseUrl = server.url("/api/v1/").toString(), operationGate = fixtureGate)
            assertTrue(runCatching { executor.execute(write()) }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun changedOriginalAuthorityCannotSendConfirmedFinancePost() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val executor = singleAttemptFinanceWriteExecutor("synthetic-original-A", null,
                { throw IOException("Synthetic original authority changed") },
                baseUrl = server.url("/api/v1/").toString(), operationGate = fixtureGate)
            assertTrue(runCatching { executor.execute(write()) }.exceptionOrNull() is IOException)
            assertEquals(0, server.requestCount)
        }
    }
}
