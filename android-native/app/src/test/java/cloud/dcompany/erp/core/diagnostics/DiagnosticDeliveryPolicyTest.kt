package cloud.dcompany.erp.core.diagnostics

import cloud.dcompany.erp.core.net.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DiagnosticDeliveryPolicyTest {
    @Test
    fun `accepted and duplicate event ids are both acknowledgements`() {
        val installationId = UUID.randomUUID().toString()
        val accepted = UUID.randomUUID().toString()
        val duplicate = UUID.randomUUID().toString()
        val result = validateDiagnosticResponse(
            installationId,
            setOf(accepted, duplicate),
            ClientDiagnosticBatchResponse(
                installationId = installationId,
                serverTime = Instant.EPOCH.toString(),
                acceptedEventIds = listOf(accepted),
                duplicateEventIds = listOf(duplicate),
            ),
        )

        assertTrue(result.responseValid)
        assertEquals(setOf(accepted, duplicate), result.acknowledgedIds)
    }

    @Test
    fun `foreign duplicate or malformed response cannot delete local rows`() {
        val installationId = UUID.randomUUID().toString()
        val requested = UUID.randomUUID().toString()
        val foreign = UUID.randomUUID().toString()
        val result = validateDiagnosticResponse(
            installationId,
            setOf(requested),
            ClientDiagnosticBatchResponse(
                installationId = installationId,
                serverTime = "not-a-time",
                acceptedEventIds = listOf(foreign),
            ),
        )

        assertFalse(result.responseValid)
        assertTrue(result.acknowledgedIds.isEmpty())
    }

    @Test
    fun `delivery retries concurrency but quarantines permanent idempotency mismatch`() {
        val conflictingId = UUID.randomUUID().toString()
        assertEquals(
            DiagnosticDeliveryFailureDisposition.RETRY,
            diagnosticDeliveryFailureDisposition(ApiException("offline")),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.RETRY,
            diagnosticDeliveryFailureDisposition(ApiException("auth", status = 401)),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.RETRY,
            diagnosticDeliveryFailureDisposition(ApiException("server not deployed yet", status = 404)),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.REJECT,
            diagnosticDeliveryFailureDisposition(ApiException("invalid", status = 422)),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.QUARANTINE,
            diagnosticDeliveryFailureDisposition(
                ApiException(
                    "id collision",
                    status = 409,
                    code = "diagnostic_idempotency_conflict",
                    diagnosticConflictEventId = conflictingId,
                ),
            ),
        )
        val collision = ApiException(
            "id collision",
            status = 409,
            code = "diagnostic_idempotency_conflict",
            diagnosticConflictEventId = conflictingId,
        )
        assertEquals(
            conflictingId,
            diagnosticConflictIdToQuarantine(collision, setOf(conflictingId, UUID.randomUUID().toString())),
        )
        assertEquals(
            null,
            diagnosticConflictIdToQuarantine(collision, setOf(UUID.randomUUID().toString())),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.RETRY,
            diagnosticDeliveryFailureDisposition(
                ApiException(
                    "old server omitted the conflicting row",
                    status = 409,
                    code = "diagnostic_idempotency_conflict",
                ),
            ),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.RETRY,
            diagnosticDeliveryFailureDisposition(
                ApiException(
                    "concurrent ingest",
                    status = 409,
                    code = "diagnostic_ingest_retry",
                ),
            ),
        )
        assertEquals(
            DiagnosticDeliveryFailureDisposition.RETRY,
            diagnosticDeliveryFailureDisposition(
                ApiException("unknown conflict is conservatively retried", status = 409),
            ),
        )
    }

    @Test
    fun `expired or implausibly future events are isolated before batching`() {
        val now = 100L * 24L * 60L * 60L * 1_000L
        fun event(at: Long) = DiagnosticEvent(
            eventType = DiagnosticEventType.CRASH,
            severity = DiagnosticSeverity.ERROR,
            occurredAtMillis = at,
            capturedVersionName = "3.1.4",
            capturedVersionCode = 15,
            capturedOsApiLevel = 35,
            component = DiagnosticComponent.APP,
            reasonCode = "java_crash_exit",
        )

        assertTrue(diagnosticEventIsRetainedForUpload(event(now - 89L * 86_400_000L), now))
        assertFalse(diagnosticEventIsRetainedForUpload(event(now - 90L * 86_400_000L), now))
        assertTrue(diagnosticEventIsRetainedForUpload(event(now + 23L * 3_600_000L), now))
        assertFalse(diagnosticEventIsRetainedForUpload(event(now + 24L * 3_600_000L), now))
    }

    @Test
    fun `api failure storm is bounded by an in process rate limit`() {
        val limiter = DiagnosticRateLimiter(minimumIntervalMillis = 60_000L)
        assertTrue(limiter.claim("same-fingerprint", 1_000L))
        assertFalse(limiter.claim("same-fingerprint", 60_999L))
        assertTrue(limiter.claim("same-fingerprint", 61_000L))
        assertTrue(limiter.claim("another-fingerprint", 61_001L))
    }

    @Test
    fun `wire payload contains only the allowlisted backend contract`() {
        val installationId = UUID.randomUUID().toString()
        val event = DiagnosticEvent(
            eventType = DiagnosticEventType.API_FAILURE,
            severity = DiagnosticSeverity.ERROR,
            occurredAtMillis = 1_000L,
            capturedVersionName = "3.1.4",
            capturedVersionCode = 15,
            capturedOsApiLevel = 35,
            component = DiagnosticComponent.GAMING,
            reasonCode = "server_503",
            failureFingerprint = sha256Hex("fixed-safe-evidence"),
            httpStatus = 503,
            connectivity = DiagnosticConnectivity.ONLINE,
        )
        val encoded = Json.encodeToString(
            ClientDiagnosticBatchRequest(installationId, listOf(event.toWireRequest())),
        )

        listOf(
            "client_event_id",
            "event_type",
            "occurred_at",
            "version_name",
            "version_code",
            "os_api_level",
            "reason_code",
            "failure_fingerprint",
            "http_status",
            "connectivity",
        ).forEach { assertTrue(encoded.contains("\"$it\"")) }
        listOf(
            "message",
            "stack_trace",
            "url",
            "header",
            "body",
            "token",
            "customer",
            "payment",
            "localScopeHash",
        ).forEach { assertFalse(encoded.contains(it, ignoreCase = true)) }
    }
}
