package cloud.dcompany.erp.core.remote

import okhttp3.RequestBody
import retrofit2.Response
import java.io.IOException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

internal data class RemoteRequestScopeTag(val scope: RemoteAssistanceJournalScope)

/** Isolated protocol boundary so backend DTO evolution cannot leak into UI or SyncEngine. */
internal interface RemoteAssistanceApi {
    @POST("remote-assistance/device/keys/enroll")
    suspend fun enrollDeviceKey(
        @Body body: RemoteDeviceKeyEnrollmentRequest,
        @Tag requestScope: RemoteRequestScopeTag,
    ): RemoteDeviceKeyStatusResponse

    @GET("remote-assistance/device/keys/{key_id}/status")
    suspend fun deviceKeyStatus(
        @Path("key_id") keyId: String,
        @Query("installation_id") installationId: String,
        @Tag requestScope: RemoteRequestScopeTag,
    ): RemoteDeviceKeyStatusResponse

    @POST("remote-assistance/device/heartbeat")
    suspend fun heartbeat(
        @Body body: RemoteDeviceHeartbeatRequest,
        @Tag requestScope: RemoteRequestScopeTag,
    ): Response<Unit>

    @GET("remote-assistance/device/state")
    suspend fun deviceState(
        @Query("installation_id") installationId: String,
        @Query("after_sequence") afterSequence: Long,
        @Tag requestScope: RemoteRequestScopeTag,
    ): RemoteDeviceStateResponse

    @POST("remote-assistance/device/grants/{grant_id}/decision")
    suspend fun decideGrant(
        @Path("grant_id") grantId: String,
        @Body body: RemoteGrantDecisionRequest,
        @Tag requestScope: RemoteRequestScopeTag,
    ): Response<Unit>

    @POST("remote-assistance/device/grants/{grant_id}/revoke")
    suspend fun revokeGrant(
        @Path("grant_id") grantId: String,
        @Body body: RemoteGrantRevocationRequest,
        @Tag requestScope: RemoteRequestScopeTag,
    ): Response<Unit>

    @POST("remote-assistance/device/commands/{command_id}/result")
    suspend fun commandResult(
        @Path("command_id") commandId: String,
        @Body body: RemoteCommandResultRequest,
        @Tag requestScope: RemoteRequestScopeTag,
    ): Response<Unit>

    @POST("remote-assistance/device/sessions/{session_id}/end")
    suspend fun endSession(
        @Path("session_id") sessionId: String,
        @Body body: RemoteSessionEndRequest,
        @Tag requestScope: RemoteRequestScopeTag,
    ): Response<Unit>

    @Headers(
        "Content-Type: image/jpeg",
        // This mandatory marker means the client applied the ERP-only capture
        // policy. It is true for both a safe app-window frame and a generated
        // privacy placeholder; false is intentionally never representable.
        "X-ERP-Frame-Redacted: true",
    )
    @PUT("remote-assistance/device/sessions/{session_id}/frame")
    suspend fun uploadFrame(
        @Path("session_id") sessionId: String,
        @Header("X-Installation-Id") installationId: String,
        @Header("X-Frame-Id") frameId: String,
        @Header("X-Frame-Sequence") frameSequence: Long,
        @Header("X-Frame-Width") width: Int,
        @Header("X-Frame-Height") height: Int,
        @Body jpeg: RequestBody,
        @Tag requestScope: RemoteRequestScopeTag,
    ): Response<Unit>
}

internal class RemoteAssistanceHttpException(val status: Int) :
    IOException("Remote-assistance request failed with HTTP $status")

/** Retrofit Response is not success-shaped; every mutation must prove 2xx before local ack. */
internal fun Response<Unit>.requireRemoteAssistanceSuccess() {
    if (!isSuccessful) throw RemoteAssistanceHttpException(code())
}

/** 409 remains retryable/conflicting; only exact absent/terminal proofs retire a mutation. */
internal fun remoteMutationStatusIsTerminal(status: Int?): Boolean = status == 404 || status == 410
