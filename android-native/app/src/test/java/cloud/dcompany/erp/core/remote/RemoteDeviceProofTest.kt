package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.net.canReplayAfterBearerRefresh
import cloud.dcompany.erp.core.net.diagnosticEncodedPath
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDeviceProofTest {
    @Test
    fun `enrollment statement matches locked ASCII contract with no trailing newline`() {
        val statement = canonicalRemoteEnrollmentStatement(
            companyId = COMPANY_ID,
            installationId = INSTALLATION_ID,
            keyId = KEY_ID,
            enrollmentId = ENROLLMENT_ID,
            signedAtEpochSeconds = 1_777_777_777L,
            nonce = NONCE,
            spkiSha256 = "a".repeat(64),
        ).decodeToString()

        assertEquals(
            "D-COMPANY-ERP-REMOTE-ENROLLMENT-V1\n" +
                "$COMPANY_ID\n$INSTALLATION_ID\n$KEY_ID\n$ENROLLMENT_ID\n" +
                "1777777777\n$NONCE\n${"a".repeat(64)}",
            statement,
        )
        assertFalse(statement.endsWith("\n"))
    }

    @Test
    fun `request statement preserves exact encoded target query body hash and ordering`() {
        val emptyHash = remoteSha256Hex(ByteArray(0))
        val statement = canonicalRemoteRequestStatement(
            method = "get",
            rawTarget = "/api/v1/remote-assistance/device/keys/$KEY_ID/status" +
                "?installation_id=$INSTALLATION_ID",
            contentSha256 = emptyHash,
            signedAtEpochSeconds = 1_777_777_778L,
            nonce = NONCE,
            keyId = KEY_ID,
        ).decodeToString()

        assertEquals(
            "D-COMPANY-ERP-REMOTE-REQUEST-V1\nGET\n" +
                "/api/v1/remote-assistance/device/keys/$KEY_ID/status" +
                "?installation_id=$INSTALLATION_ID\n" +
                "$emptyHash\n1777777778\n$NONCE\n$KEY_ID",
            statement,
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyHash,
        )
        assertFalse(statement.endsWith("\n"))
    }

    @Test
    fun `device proof is pinned to configured https origin`() {
        val configured = "https://dcompany.duckdns.org/api/v1/".toHttpUrl()

        assertTrue(
            remoteDeviceProofOriginMatches(
                "https://dcompany.duckdns.org/api/v1/remote-assistance/device/state".toHttpUrl(),
                configured,
            ),
        )
        assertFalse(
            remoteDeviceProofOriginMatches(
                "https://attacker.example/api/v1/remote-assistance/device/state".toHttpUrl(),
                configured,
            ),
        )
        assertFalse(
            remoteDeviceProofOriginMatches(
                "http://dcompany.duckdns.org/api/v1/remote-assistance/device/state".toHttpUrl(),
                configured,
            ),
        )
        assertFalse(
            remoteDeviceProofOriginMatches(
                "https://dcompany.duckdns.org:444/api/v1/remote-assistance/device/state".toHttpUrl(),
                configured,
            ),
        )
    }

    @Test
    fun `pairing code groups for display without changing canonical value`() {
        assertEquals("01AB-2CDE-3FGH", groupedRemotePairingCode("01AB2CDE3FGH"))
        assertNull(groupedRemotePairingCode("01AB-2CDE-3FGH"))
        assertNull(groupedRemotePairingCode("01AB2CDE3FGI"))
    }

    @Test
    fun `wire UUIDs require canonical lowercase version four text`() {
        assertTrue(isCanonicalUuidV4(KEY_ID))
        assertFalse(
            isCanonicalUuidV4("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa".uppercase()),
        )
        assertFalse(isCanonicalUuidV4("33333333-3333-3333-8333-333333333333"))
    }

    @Test
    fun `pending pairing expiry uses server time plus monotonic elapsed time`() {
        assertEquals(
            610_000L,
            remotePendingDeviceKeyDeadline(
                serverTimeRaw = "2026-08-30T10:00:00Z",
                pendingExpiresAtRaw = "2026-08-30T10:10:00Z",
                nowElapsedMillis = 10_000L,
            ),
        )
        assertNull(
            remotePendingDeviceKeyDeadline(
                serverTimeRaw = "2026-08-30T10:10:00Z",
                pendingExpiresAtRaw = "2026-08-30T10:10:00Z",
                nowElapsedMillis = 10_000L,
            ),
        )
        assertNull(
            remotePendingDeviceKeyDeadline(
                serverTimeRaw = "2026-08-30T10:00:00Z",
                pendingExpiresAtRaw = "2026-08-30T10:20:00Z",
                nowElapsedMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `bearer refresh never replays an enrollment body proof nonce`() {
        assertFalse(
            canReplayAfterBearerRefresh(
                "/api/v1/remote-assistance/device/keys/enroll",
            ),
        )
        assertTrue(
            canReplayAfterBearerRefresh(
                "/api/v1/remote-assistance/device/keys/$KEY_ID/status",
            ),
        )
        assertTrue(
            canReplayAfterBearerRefresh(
                "/api/v1/remote-assistance/device/heartbeat",
            ),
        )
    }

    @Test
    fun `remote diagnostic paths redact device and action identifiers`() {
        assertEquals(
            "/api/v1/remote-assistance/device/keys/{id}/status",
            diagnosticEncodedPath(
                "/api/v1/remote-assistance/device/keys/$KEY_ID/status",
            ),
        )
        assertEquals(
            "/api/v1/orders/$KEY_ID",
            diagnosticEncodedPath("/api/v1/orders/$KEY_ID"),
        )
    }

    private companion object {
        const val COMPANY_ID = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION_ID = "22222222-2222-4222-8222-222222222222"
        const val KEY_ID = "33333333-3333-4333-8333-333333333333"
        const val ENROLLMENT_ID = "44444444-4444-4444-8444-444444444444"
        const val NONCE = "55555555-5555-4555-8555-555555555555"
    }
}
