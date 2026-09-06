package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import retrofit2.http.Headers
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Tag

class RemoteAssistanceApiContractTest {

    @Test
    fun `device state decodes the complete backend response contract`() {
        val state = ApiClient.json.decodeFromString<RemoteDeviceStateResponse>(DEVICE_STATE_JSON)

        assertEquals("2026-08-30T10:01:00Z", state.serverTime)
        assertEquals("one_time", state.pendingGrants.single().kind)
        assertEquals("Nasih", state.pendingGrants.single().requestedByName)
        assertEquals(REQUESTED_FOR_USER_ID, state.pendingGrants.single().requestedForUserId)
        assertEquals("Staff One", state.pendingGrants.single().requestedForName)
        assertNull(state.pendingGrants.single().respondedByName)
        assertEquals(GRANT_ID, state.session?.grantId)
        assertEquals(600, state.session?.durationSeconds)
        assertEquals(2L, state.session?.nextSequence)
        assertEquals(SESSION_ID, state.commands.single().sessionId)
        assertEquals("navigate", state.commands.single().type)
        assertEquals("help", state.commands.single().module)
    }

    @Test
    fun `grant decisions serialize exact accepted and declined wire values`() {
        fun encoded(decision: RemoteGrantDecisionWire): String = ApiClient.json
            .parseToJsonElement(
                ApiClient.json.encodeToString(
                    RemoteGrantDecisionRequest(INSTALLATION_ID, decision, DECISION_ID),
                ),
            )
            .jsonObject
            .getValue("decision")
            .jsonPrimitive
            .content

        assertEquals("accepted", encoded(RemoteGrantDecisionWire.ACCEPTED))
        assertEquals("declined", encoded(RemoteGrantDecisionWire.DECLINED))
    }

    @Test
    fun `device key enrollment serializes exact proof fields without tenant authority`() {
        val encoded = ApiClient.json.parseToJsonElement(
            ApiClient.json.encodeToString(
                RemoteDeviceKeyEnrollmentRequest(
                    keyId = KEY_ID,
                    enrollmentId = ENROLLMENT_ID,
                    installationId = INSTALLATION_ID,
                    publicKeySpki = "spki",
                    signedAtEpochSeconds = 1_777_777_777L,
                    nonce = NONCE,
                    signature = "signature",
                ),
            ),
        ).jsonObject

        assertEquals(
            setOf(
                "key_id",
                "enrollment_id",
                "installation_id",
                "public_key_spki",
                "signed_at_epoch_seconds",
                "nonce",
                "signature",
            ),
            encoded.keys,
        )
        assertFalse("company_id must remain bearer-derived", "company_id" in encoded)
        assertEquals("1777777777", encoded.getValue("signed_at_epoch_seconds").jsonPrimitive.content)
    }

    @Test
    fun `device key status decodes server time pairing and approval contract`() {
        val pending = ApiClient.json.decodeFromString<RemoteDeviceKeyStatusResponse>(
            DEVICE_KEY_STATUS_JSON,
        )

        assertEquals("2026-08-30T10:01:00Z", pending.serverTime)
        assertEquals(KEY_ID, pending.keyId)
        assertEquals("pending", pending.status)
        assertEquals("01AB2CDE3FGH", pending.pairingCode)
        assertNull(pending.approvedAt)
    }

    @Test
    fun `all remote mutation endpoints match the locked prefix and frame marker`() {
        val heartbeat = RemoteAssistanceApi::class.java.methods.single { it.name == "heartbeat" }
        val enroll = RemoteAssistanceApi::class.java.methods.single { it.name == "enrollDeviceKey" }
        val keyStatus = RemoteAssistanceApi::class.java.methods.single { it.name == "deviceKeyStatus" }
        val decision = RemoteAssistanceApi::class.java.methods.single { it.name == "decideGrant" }
        val revoke = RemoteAssistanceApi::class.java.methods.single { it.name == "revokeGrant" }
        val result = RemoteAssistanceApi::class.java.methods.single { it.name == "commandResult" }
        val end = RemoteAssistanceApi::class.java.methods.single { it.name == "endSession" }
        val frame = RemoteAssistanceApi::class.java.methods.single { it.name == "uploadFrame" }

        assertEquals("remote-assistance/device/heartbeat", heartbeat.getAnnotation(POST::class.java)?.value)
        assertEquals(
            "remote-assistance/device/keys/enroll",
            enroll.getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "remote-assistance/device/keys/{key_id}/status",
            keyStatus.getAnnotation(GET::class.java)?.value,
        )
        assertEquals(
            "remote-assistance/device/grants/{grant_id}/decision",
            decision.getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "remote-assistance/device/grants/{grant_id}/revoke",
            revoke.getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "remote-assistance/device/commands/{command_id}/result",
            result.getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "remote-assistance/device/sessions/{session_id}/end",
            end.getAnnotation(POST::class.java)?.value,
        )
        assertEquals(
            "remote-assistance/device/sessions/{session_id}/frame",
            frame.getAnnotation(PUT::class.java)?.value,
        )
        val staticHeaders = frame.getAnnotation(Headers::class.java)?.value.orEmpty().toSet()
        assertTrue("Content-Type: image/jpeg" in staticHeaders)
        assertTrue("X-ERP-Frame-Redacted: true" in staticHeaders)

        RemoteAssistanceApi::class.java.methods
            .filter { it.declaringClass == RemoteAssistanceApi::class.java }
            .forEach { method ->
                assertTrue(
                    "${method.name} must carry its exact authenticated journal scope",
                    method.parameterAnnotations.flatten().any { it is Tag },
                )
            }
    }

    @Test
    fun `strict response gate rejects auth conflict server and frame failures`() {
        Response.success(Unit).requireRemoteAssistanceSuccess()

        for (status in listOf(401, 409, 500)) {
            val error = assertRemoteHttpFailure(status)
            assertEquals(status, error.status)
        }
        val frameError = assertRemoteHttpFailure(422)
        assertEquals(422, frameError.status)
    }

    @Test
    fun `only exact absent or terminal responses retire a durable mutation`() {
        assertTrue(remoteMutationStatusIsTerminal(404))
        assertTrue(remoteMutationStatusIsTerminal(410))
        assertFalse(remoteMutationStatusIsTerminal(401))
        assertFalse(remoteMutationStatusIsTerminal(409))
        assertFalse(remoteMutationStatusIsTerminal(500))
        assertFalse(remoteMutationStatusIsTerminal(null))
    }

    private fun assertRemoteHttpFailure(status: Int): RemoteAssistanceHttpException {
        try {
            Response.error<Unit>(status, "{}".toResponseBody())
                .requireRemoteAssistanceSuccess()
            fail("HTTP $status must never be acknowledged as success")
        } catch (error: RemoteAssistanceHttpException) {
            return error
        }
        error("unreachable")
    }

    private companion object {
        const val INSTALLATION_ID = "11111111-1111-4111-8111-111111111111"
        const val GRANT_ID = "22222222-2222-4222-8222-222222222222"
        const val SESSION_ID = "33333333-3333-4333-8333-333333333333"
        const val COMMAND_ID = "44444444-4444-4444-8444-444444444444"
        const val REQUESTER_ID = "55555555-5555-4555-8555-555555555555"
        const val REQUESTED_FOR_USER_ID = "56565656-5656-4656-8656-565656565656"
        const val DECISION_ID = "66666666-6666-4666-8666-666666666666"
        const val KEY_ID = "77777777-7777-4777-8777-777777777777"
        const val ENROLLMENT_ID = "88888888-8888-4888-8888-888888888888"
        const val NONCE = "99999999-9999-4999-8999-999999999999"
        val DEVICE_KEY_STATUS_JSON = """{
          "server_time":"2026-08-30T10:01:00Z",
          "key_id":"$KEY_ID",
          "installation_id":"$INSTALLATION_ID",
          "status":"pending",
          "fingerprint_sha256":"${"a".repeat(64)}",
          "enrolled_at":"2026-08-30T10:00:00Z",
          "pending_expires_at":"2026-08-30T10:10:00Z",
          "approved_at":null,
          "revoked_at":null,
          "pairing_code":"01AB2CDE3FGH"
        }"""
        const val DEVICE_STATE_JSON = """{
          "server_time":"2026-08-30T10:01:00Z",
          "pending_grants":[{
            "id":"$GRANT_ID",
            "installation_id":"$INSTALLATION_ID",
            "kind":"one_time",
            "status":"requested",
            "requested_by_user_id":"$REQUESTER_ID",
            "requested_by_name":"Nasih",
            "requested_for_user_id":"$REQUESTED_FOR_USER_ID",
            "requested_for_name":"Staff One",
            "responded_by_user_id":null,
            "responded_by_name":null,
            "requested_at":"2026-08-30T10:00:00Z",
            "expires_at":"2026-08-30T10:10:00Z",
            "responded_at":null,
            "revoked_at":null,
            "consumed_at":null
          }],
          "session":{
            "id":"$SESSION_ID",
            "installation_id":"$INSTALLATION_ID",
            "grant_id":"$GRANT_ID",
            "status":"active",
            "duration_seconds":600,
            "requested_by_user_id":"$REQUESTER_ID",
            "requested_by_name":"Nasih",
            "started_by_user_id":"$REQUESTER_ID",
            "started_by_name":"Nasih",
            "ended_by_user_id":null,
            "ended_by_name":null,
            "requested_at":"2026-08-30T09:59:00Z",
            "request_expires_at":"2026-08-30T10:00:30Z",
            "started_at":"2026-08-30T10:00:00Z",
            "expires_at":"2026-08-30T10:10:00Z",
            "ended_at":null,
            "end_reason":null,
            "next_sequence":2
          },
          "commands":[{
            "command_id":"$COMMAND_ID",
            "session_id":"$SESSION_ID",
            "sequence":1,
            "type":"navigate",
            "module":"help",
            "status":"pending",
            "issued_by_user_id":"$REQUESTER_ID",
            "issued_at":"2026-08-30T10:00:30Z",
            "resolved_by_user_id":null,
            "resolved_at":null,
            "rejection_reason_code":null
          }]
        }"""
    }
}
