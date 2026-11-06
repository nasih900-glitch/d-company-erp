package cloud.dcompany.erp.ui.screens.settings

import java.util.Base64
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BugReportOwnerHistoryTest {
    private val owner = BugReportOwnerScope("synthetic-company", "synthetic-A")
    private fun token(user: String = owner.userId, company: String = owner.companyId) =
        "synthetic." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"$user","company_id":"$company","branch_id":null}""".toByteArray()) + ".synthetic"

    @Test fun productionSelectedReadClientHasOriginalOwnerOnlyAndNoTerminalOrMutationAuthority() {
        var bearer = token(); var originalLogin = true
        val reader = BugReportOwnerHistory(owner, { bearer }, { originalLogin })
        MockWebServer().use { server ->
            server.start(); repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
            val client = reader.client()
            try {
                fun request(path: String, post: Boolean = false) = Request.Builder().url(server.url(path))
                    .apply { if (post) post("{}".toRequestBody()) }.build()
                client.newCall(request("/api/v1/bug-reports/mine?limit=20&offset=0")).execute().close()
                val actual = server.takeRequest()
                assertEquals("Bearer $bearer", actual.getHeader("Authorization"))
                assertNull(actual.getHeader("X-Terminal-Id"))
                for (bad in listOf(
                    request("/api/v1/bug-reports"), request("/api/v1/bug-reports/mine?limit=20&offset=0", true),
                    request("/api/v1/bug-reports/mine?limit=20&offset=0&company_id=other"),
                    request("/api/v1/bug-reports/mine?limit=20&limit=100&offset=0"),
                    request("/api/v1/auth/login", true), request("/api/v1/finance/expenses"),
                    request("/api/v1/bug-reports/%6dine?limit=20&offset=0"))) {
                    assertTrue(runCatching { client.newCall(bad).execute().close() }.isFailure)
                }
                originalLogin = false
                assertTrue(runCatching { client.newCall(request("/api/v1/bug-reports/mine?limit=20&offset=0")).execute().close() }.isFailure)
                originalLogin = true; bearer = token(user = "synthetic-B")
                assertTrue(runCatching { reader.bearer() }.isFailure)
                bearer = token(company = "another-company")
                assertTrue(runCatching { reader.bearer() }.isFailure)
                assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun frozenOrUnconfiguredOwnerReadCannotUseHistoryAsABootstrapExemption() {
        val held = BugReportOwnerHistory(owner, { token() }, { false })
        assertTrue(runCatching { held.client() }.isFailure)
        val absent = BugReportOwnerHistory(owner, { null }, { true })
        assertTrue(runCatching { absent.client() }.isFailure)
    }
}
