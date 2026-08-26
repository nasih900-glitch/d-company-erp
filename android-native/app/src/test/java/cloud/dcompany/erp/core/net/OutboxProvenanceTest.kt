package cloud.dcompany.erp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutboxProvenanceTest {
    @Test
    fun persistedActionProducesStableTimezoneAwareHeaders() {
        val headers = outboxProvenanceHeaders(
            occurredAtMillis = 1_777_633_200_123L,
            actionId = "payment:local-123",
        )

        assertEquals("true", headers[OFFLINE_CAPTURED_HEADER])
        assertEquals("2026-05-01T11:00:00.123Z", headers[CLIENT_OCCURRED_AT_HEADER])
        assertEquals("payment:local-123", headers[CLIENT_ACTION_ID_HEADER])
    }

    @Test
    fun unavailableOccurrenceTimeIsOmittedWithoutLosingOfflineTruth() {
        val headers = outboxProvenanceHeaders(null, "gaming-stop:local-1")

        assertEquals("true", headers[OFFLINE_CAPTURED_HEADER])
        assertFalse(headers.containsKey(CLIENT_OCCURRED_AT_HEADER))
        assertEquals("gaming-stop:local-1", headers[CLIENT_ACTION_ID_HEADER])
    }
}
