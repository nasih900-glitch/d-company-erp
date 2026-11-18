package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.handover.HandoverScope
import org.junit.Assert.*
import org.junit.Test

class RemoteEnrollmentAttemptTest {
    private val company = "11111111-1111-4111-8111-111111111111"
    private val installation = "22222222-2222-4222-8222-222222222222"
    private val key = "33333333-3333-4333-8333-333333333333"
    private val enrollment = "44444444-4444-4444-8444-444444444444"
    private val original = PersistedRemoteDeviceIdentity(company, installation, key, enrollment, "created", "a".repeat(64))
    private val scope = HandoverScope("55555555-5555-4555-8555-555555555555", company, null, null)
    private fun active() = RemoteDeviceKeyStatusResponse("2026-11-05T10:02:00Z", key, installation, "active", "a".repeat(64),
        "2026-11-05T10:00:00Z", "2026-11-05T10:10:00Z", "2026-11-05T10:01:00Z")
    @Test fun identityUsesOnlyStablePublicRetainedEnrollmentFields() {
        val id = remoteEnrollmentAttempt(scope, original)
        val pending = original.copy(status = "pending", pairingCode = "01AB2CDE3FGH", serverTime = "2026-11-05T10:00:30Z",
            enrolledAt = "2026-11-05T10:00:00Z", pendingExpiresAt = "2026-11-05T10:10:00Z")
        assertEquals(id, remoteEnrollmentAttempt(scope, pending))
        assertNotEquals(id, remoteEnrollmentAttempt(scope, original.copy(enrollmentId = "66666666-6666-4666-8666-666666666666")))
        assertEquals("KnownJournalAttempt(<redacted>)", id.toString())
    }
    @Test fun positiveActiveObjectMustMatchKeyInstallationFingerprintAndValidTimes() {
        assertNotNull(validatedRemoteDeviceStatus(original, active(), activeOnly = true))
        for (bad in listOf(active().copy(keyId = enrollment), active().copy(installationId = enrollment), active().copy(fingerprintSha256 = "b".repeat(64)),
            active().copy(status = "revoked", revokedAt = "2026-11-05T10:01:30Z"), active().copy(status = "expired"), active().copy(status = "unknown"),
            active().copy(approvedAt = null), active().copy(approvedAt = "invalid"), active().copy(serverTime = "invalid"),
            active().copy(approvedAt = "2026-11-05T09:00:00Z"), active().copy(approvedAt = "2026-11-05T11:00:00Z"),
            active().copy(revokedAt = "2026-11-05T10:01:30Z"), active().copy(pendingExpiresAt = "2026-11-05T09:00:00Z"))) {
            assertNull(validatedRemoteDeviceStatus(original, bad, activeOnly = true))
        }
    }
    @Test fun pendingStatusCanUpdateLocallyButIsNotAnActiveObservationProof() {
        val response = active().copy(status = "pending", approvedAt = null, pairingCode = "01AB2CDE3FGH")
        assertNotNull(validatedRemoteDeviceStatus(original, response))
        assertNull(validatedRemoteDeviceStatus(original, response, activeOnly = true))
    }
}
