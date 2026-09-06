package cloud.dcompany.erp.core.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models mirroring backend/app/api/v1/auth/router.py exactly. Field
 * names and nullability are copied from the Pydantic schemas rather than
 * guessed — a mismatch here fails at runtime, not compile time.
 */

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class PasswordResetRequest(
    val email: String,
)

@Serializable
data class PasswordResetConfirmRequest(
    @SerialName("challenge_id") val challengeId: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class PasswordResetChallenge(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("expires_in") val expiresIn: Int,
    val destination: String,
)

@Serializable
data class AccountActionResponse(
    val message: String,
)

@Serializable
data class MeResponse(
    @SerialName("user_id") val userId: String,
    val email: String,
    val name: String,
    val roles: List<String> = emptyList(),
    @SerialName("protected_access") val protectedAccess: Boolean = false,
    @SerialName("audit_access") val auditAccess: Boolean = false,
    @SerialName("company_id") val companyId: String,
    // Genuinely nullable in the backend schema (`str | None`).
    @SerialName("branch_id") val branchId: String? = null,
    // Optional for compatibility with cached profiles and older servers.
    // This is display metadata only; branchId remains the scope authority.
    @SerialName("branch_name") val branchName: String? = null,
    // Nullable distinguishes an old cached/server payload that predates this
    // field from an authoritative response that intentionally grants no modules.
    @SerialName("accessible_modules") val accessibleModules: List<String>? = null,
    @SerialName("effective_permissions") val effectivePermissions: List<String>? = null,
)

@Serializable
data class ClientCompatibilityResponse(
    val platform: String,
    @SerialName("current_version_code") val currentVersionCode: Int,
    @SerialName("minimum_supported_version_code") val minimumSupportedVersionCode: Int,
    @SerialName("latest_version_code") val latestVersionCode: Int,
    @SerialName("policy_revision") val policyRevision: Int = 0,
    val status: String,
    @SerialName("update_url") val updateUrl: String? = null,
    @SerialName("latest_version_name") val latestVersionName: String? = null,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("apk_sha256") val apkSha256: String? = null,
    @SerialName("apk_size_bytes") val apkSizeBytes: Long? = null,
    @SerialName("apk_signing_cert_sha256") val apkSigningCertSha256: String? = null,
    val message: String,
    @SerialName("checked_at") val checkedAt: String,
)

/**
 * The backend's error envelope:
 *   {"error": {"code": "...", "message": "...", "details": {}}}
 * Surfacing `message` is what lets the UI show "invalid credentials"
 * instead of a bare HTTP 401.
 */
@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: ClientCompatibilityErrorDetails? = null,
)

/**
 * Compatibility middleware is the only error producer whose details Android
 * needs to act on centrally. All fields are optional so unrelated error
 * detail objects continue to decode under the shared envelope.
 */
@Serializable
data class ClientCompatibilityErrorDetails(
    /** Exact diagnostics outbox row involved in an immutable UUID collision. */
    @SerialName("client_event_id") val clientEventId: String? = null,
    val platform: String? = null,
    @SerialName("current_version_code") val currentVersionCode: Int? = null,
    @SerialName("minimum_supported_version_code") val minimumSupportedVersionCode: Int? = null,
    @SerialName("latest_version_code") val latestVersionCode: Int? = null,
    @SerialName("policy_revision") val policyRevision: Int? = null,
    @SerialName("update_url") val updateUrl: String? = null,
    @SerialName("latest_version_name") val latestVersionName: String? = null,
    @SerialName("release_notes") val releaseNotes: String? = null,
    @SerialName("apk_sha256") val apkSha256: String? = null,
    @SerialName("apk_size_bytes") val apkSizeBytes: Long? = null,
    @SerialName("apk_signing_cert_sha256") val apkSigningCertSha256: String? = null,
)
