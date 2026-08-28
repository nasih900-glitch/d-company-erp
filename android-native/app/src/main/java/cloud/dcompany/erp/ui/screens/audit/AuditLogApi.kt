package cloud.dcompany.erp.ui.screens.audit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class AuditUnlockRequest(val password: String)

@Serializable
data class AuditUnlockResponse(
    @SerialName("audit_token") val auditToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class AuditFacets(
    @SerialName("entity_types") val entityTypes: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
)

/** Mirrors backend/app/api/v1/admin/router.py's AuditEntry exactly. */
@Serializable
data class AuditEntry(
    val id: Long,
    @SerialName("actor_user_id") val actorUserId: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("actor_email") val actorEmail: String? = null,
    val action: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val before: JsonObject? = null,
    val after: JsonObject? = null,
    val ip: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("terminal_id") val terminalId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("client_platform") val clientPlatform: String? = null,
    @SerialName("client_version_code") val clientVersionCode: Int? = null,
    @SerialName("client_action_id") val clientActionId: String? = null,
    @SerialName("client_reported_at") val clientReportedAt: String? = null,
    @SerialName("client_was_offline") val clientWasOffline: Boolean? = null,
    @SerialName("synced_at") val syncedAt: String? = null,
    val reason: String? = null,
    @SerialName("created_at") val createdAt: String,
)

interface AuditLogApi {
    @POST("admin/audit/unlock")
    suspend fun unlock(@Body body: AuditUnlockRequest): AuditUnlockResponse

    @GET("admin/audit")
    suspend fun list(
        @Header("X-Audit-Token") auditToken: String,
        @Query("limit") limit: Int,
        @Query("before_id") beforeId: Long? = null,
        @Query("area") area: String? = null,
        @Query("entity_type") entityType: String? = null,
        @Query("action") action: String? = null,
        @Query("q") query: String? = null,
    ): List<AuditEntry>

    @GET("admin/audit/facets")
    suspend fun facets(
        @Header("X-Audit-Token") auditToken: String,
    ): AuditFacets
}
