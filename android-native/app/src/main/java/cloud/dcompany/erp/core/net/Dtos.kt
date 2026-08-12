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
    @SerialName("accessible_modules") val accessibleModules: List<String> = emptyList(),
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
)
