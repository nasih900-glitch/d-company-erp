package cloud.dcompany.erp.core.diagnostics

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticNormalizationTest {
    @Test
    fun `transport failure is reduced to a closed privacy safe event`() {
        val event = requireNotNull(
            ApiFailureNormalizer.normalize(
                ApiFailureObservation(
                    status = null,
                    serverCode = "customer_phone_9999999999",
                    encodedPath = "/api/v1/orders/customer/secret-value?token=do-not-store",
                    connectivity = DiagnosticConnectivity.OFFLINE,
                ),
                occurredAtMillis = 1_000L,
            ),
        )

        assertEquals(DiagnosticEventType.API_FAILURE, event.eventType)
        assertEquals(DiagnosticComponent.NETWORK, event.component)
        assertEquals("transport_unreachable", event.reasonCode)
        assertEquals(DiagnosticConnectivity.OFFLINE, event.connectivity)
        val retained = listOf(
            event.reasonCode,
            event.failureFingerprint,
            event.component.wireValue,
        ).joinToString("|")
        assertFalse(retained.contains("secret"))
        assertFalse(retained.contains("9999999999"))
        assertFalse(retained.contains("token"))
    }

    @Test
    fun `expected validation failures and diagnostics recursion are ignored`() {
        assertNull(
            ApiFailureNormalizer.normalize(
                ApiFailureObservation(422, "business_rule", "/api/v1/gaming/sessions"),
                1L,
            ),
        )
        assertNull(
            ApiFailureNormalizer.normalize(
                ApiFailureObservation(500, null, "/api/v1/client-diagnostics/events"),
                1L,
            ),
        )
        assertNull(
            ApiFailureNormalizer.normalize(
                ApiFailureObservation(null, null, "/api/v1/gaming", explicitlyCancelled = true),
                1L,
            ),
        )
    }

    @Test
    fun `server error retains only status and coarse gaming component`() {
        val event = requireNotNull(
            ApiFailureNormalizer.normalize(
                ApiFailureObservation(503, "database_password_leaked_here", "/api/v1/gaming/sessions/123"),
                2L,
            ),
        )

        assertEquals(DiagnosticComponent.GAMING, event.component)
        assertEquals("server_503", event.reasonCode)
        assertEquals(503, event.httpStatus)
        assertTrue(event.failureFingerprint?.matches(Regex("^[0-9a-f]{64}$")) == true)
    }

    @Test
    fun `crash fingerprint ignores exception message and line numbers`() {
        val first = IllegalStateException("customer 9999999999 paid 1234")
        val second = IllegalStateException("completely different secret")
        val firstFrames = arrayOf(
            StackTraceElement("cloud.dcompany.erp.ui.Game", "start", "Game.kt", 41),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
        )
        val secondFrames = arrayOf(
            StackTraceElement("cloud.dcompany.erp.ui.Game", "start", "Game.kt", 999),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 2),
        )
        first.stackTrace = firstFrames
        second.stackTrace = secondFrames

        assertEquals(crashFingerprint(first), crashFingerprint(second))
        assertFalse(crashFingerprint(first).contains("9999999999"))
        assertEquals("illegal_state", CrashNormalizer.normalize(first, 5L)?.reasonCode)
    }

    @Test
    fun `local account scope is stable but separates employees`() {
        val a1 = diagnosticScopeHash("company", "user-a", "branch")
        val a2 = diagnosticScopeHash("company", "user-a", "branch")
        val b = diagnosticScopeHash("company", "user-b", "branch")

        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertTrue(a1.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `token cannot claim another persisted employee company or branch scope`() {
        val scopeA = CacheScope("user-a", "company-a", "branch-a", "terminal-a")
        val identityA = OutboxOwnerIdentity("user-a", "company-a", "branch-a")
        val expectedA = diagnosticScopeHash("company-a", "user-a", "branch-a")

        assertEquals(expectedA, verifiedDiagnosticScopeHash(identityA, scopeA))
        assertNull(
            verifiedDiagnosticScopeHash(
                OutboxOwnerIdentity("user-b", "company-a", "branch-a"),
                scopeA,
            ),
        )
        assertNull(
            verifiedDiagnosticScopeHash(
                OutboxOwnerIdentity("user-a", "company-b", "branch-a"),
                scopeA,
            ),
        )
        assertNull(
            verifiedDiagnosticScopeHash(
                OutboxOwnerIdentity("user-a", "company-a", "branch-b"),
                scopeA,
            ),
        )
        assertNull(verifiedDiagnosticScopeHash(identityA, verifiedScope = null))
    }

    @Test
    fun `historical exit requires matching token cache and prior verified witness`() {
        val scopeA = CacheScope("user-a", "company-a", "branch-a", "terminal-a")
        val identityA = OutboxOwnerIdentity("user-a", "company-a", "branch-a")
        val hashA = diagnosticScopeHash("company-a", "user-a", "branch-a")
        val hashB = diagnosticScopeHash("company-a", "user-b", "branch-a")

        assertEquals(
            hashA,
            verifiedPersistedDiagnosticScopeHash(identityA, scopeA, hashA),
        )
        assertNull(verifiedPersistedDiagnosticScopeHash(identityA, scopeA, hashB))
        assertNull(verifiedPersistedDiagnosticScopeHash(identityA, scopeA, null))
        assertNull(
            verifiedPersistedDiagnosticScopeHash(
                OutboxOwnerIdentity("user-b", "company-a", "branch-a"),
                scopeA,
                hashA,
            ),
        )
    }
}
