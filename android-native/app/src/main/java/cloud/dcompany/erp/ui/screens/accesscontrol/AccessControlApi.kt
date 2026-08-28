package cloud.dcompany.erp.ui.screens.accesscontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

@Serializable
data class AccessCell(
    @SerialName("role_code") val roleCode: String,
    val module: String,
    @SerialName("default_allowed") val defaultAllowed: Boolean,
    val override: Boolean? = null,
    val allowed: Boolean,
    @SerialName("default_access_level")
    val defaultAccessLevel: String = if (defaultAllowed) "partial" else "blocked",
    @SerialName("access_level")
    val accessLevel: String = if (allowed) "partial" else "blocked",
    @SerialName("effective_permissions") val effectivePermissions: List<String> = emptyList(),
    @SerialName("unavailable_permissions") val unavailablePermissions: List<String> = emptyList(),
    @SerialName("ceiling_limited_permissions") val ceilingLimitedPermissions: List<String> = emptyList(),
)

internal val AccessCell.hasExactPermissionEvidence: Boolean
    get() = effectivePermissions.isNotEmpty() || unavailablePermissions.isNotEmpty() ||
        ceilingLimitedPermissions.isNotEmpty()

@Serializable
data class AccessControlDto(
    val roles: Map<String, String>,
    val modules: List<String>,
    val cells: List<AccessCell>,
)

/**
 * `allowed` mirrors the backend's `bool | None` field exactly, but is typed
 * as [JsonElement] rather than a plain `Boolean?` deliberately: ApiClient's
 * shared `Json` has `explicitNulls = false`, which silently DROPS a Kotlin
 * `null` from a nullable property instead of encoding it — fine for every
 * other write in this app (nothing else has a real "clear this field" wire
 * meaning), wrong here, where a JSON `null` is exactly how "reset this
 * override back to the role default" is expressed server-side. A JsonElement
 * property is never itself Kotlin-null (JsonNull.INSTANCE is a real,
 * non-null value), so explicitNulls never applies to it and it always
 * serializes — as `true`, `false`, or `null` depending on what's put there.
 */
@Serializable
data class AccessControlUpdateBody(
    @SerialName("role_code") val roleCode: String,
    val module: String,
    val allowed: JsonElement,
)

interface AccessControlApi {

    @GET("admin/access-control")
    suspend fun get(): AccessControlDto

    // No Idempotency-Key: this endpoint has no check_or_reserve wiring
    // server-side (confirmed against router.py — unlike POS money writes),
    // and the web app's own client doesn't send one either.
    @PATCH("admin/access-control")
    suspend fun update(@Body body: AccessControlUpdateBody): AccessCell
}
