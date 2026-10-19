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
    @SerialName("action_code") val actionCode: String? = null,
    @SerialName("action_label") val actionLabel: String = "Other / legacy activity",
    val category: String = "other",
    val stage: String = "unknown",
    val outcome: String = "unknown",
    @SerialName("classification_source") val classificationSource: String = "unclassified",
    @SerialName("catalogue_version") val catalogueVersion: Int = 1,
    @SerialName("actor_kind") val actorKind: String = "unverified_legacy",
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("source_reference") val sourceReference: String? = null,
    @SerialName("operation_id") val operationId: String? = null,
    val summary: String = "",
    @SerialName("business_details") val businessDetails: JsonObject? = null,
    @SerialName("actor_label_source") val actorLabelSource: String = "current_account_profile",
    @SerialName("match_explanation") val matchExplanation: String? = null,
    @SerialName("linked_bill_reference") val linkedBillReference: String? = null,
    @SerialName("linked_bill_reference_basis") val linkedBillReferenceBasis: String? = null,
)

@Serializable
data class AuditActionDefinition(
    val code: String,
    val label: String,
    val category: String,
    val aliases: List<String>,
    val stages: List<String>,
    val outcomes: List<String>,
)

@Serializable
data class AuditCatalogue(
    val version: Int,
    val categories: List<String>,
    val outcomes: List<String>,
    val views: List<String>,
    val actions: List<AuditActionDefinition>,
)

@Serializable
data class AuditEventsPage(
    val entries: List<AuditEntry>,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("activity_cursor") val activityCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("snapshot_max_id") val snapshotMaxId: Long,
    @SerialName("loaded_count") val loadedCount: Int,
    @SerialName("matching_count") val matchingCount: Int,
    @SerialName("catalogue_version") val catalogueVersion: Int,
    @SerialName("shop_timezone") val shopTimezone: String,
    @SerialName("date_filter_basis") val dateFilterBasis: String,
    @SerialName("count_basis") val countBasis: String,
    @SerialName("new_entries_available") val newEntriesAvailable: Boolean,
    @SerialName("snapshot_expires_in") val snapshotExpiresIn: Int,
    val scope: String,
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

    @GET("admin/audit/catalogue")
    suspend fun catalogue(@Header("X-Audit-Token") auditToken: String): AuditCatalogue

    @GET("admin/audit/events")
    suspend fun events(
        @Header("X-Audit-Token") auditToken: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
        @Query("view") view: String = "business",
        @Query("code") code: String? = null,
        @Query("category") category: String? = null,
        @Query("outcome") outcome: String? = null,
        @Query("actor_user_id") actorUserId: String? = null,
        @Query("target_user_id") targetUserId: String? = null,
        @Query("reference") reference: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
        @Query("q") query: String? = null,
    ): AuditEventsPage

    @GET("admin/audit/facets")
    suspend fun facets(
        @Header("X-Audit-Token") auditToken: String,
    ): AuditFacets
}
