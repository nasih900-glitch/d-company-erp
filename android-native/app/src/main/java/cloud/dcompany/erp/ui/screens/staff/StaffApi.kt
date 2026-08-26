package cloud.dcompany.erp.ui.screens.staff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class StaffUser(
    val id: String,
    val email: String,
    val name: String,
    val phone: String? = null,
    val status: String,
    val roles: List<String> = emptyList(),
    @SerialName("last_login_at") val lastLoginAt: String? = null,
)

/** Only the fields being changed are non-null — `PATCH /staff/users/{id}` applies them individually. */
@Serializable
data class StaffUserUpdateBody(
    val name: String? = null,
    val phone: String? = null,
    val status: String? = null,
    @SerialName("role_code") val roleCode: String? = null,
)

@Serializable
data class StaffRole(val code: String, val name: String, val description: String? = null)

@Serializable
data class ClockInBody(@SerialName("branch_id") val branchId: String, val notes: String? = null)

@Serializable
data class ClockInResult(val id: String)

@Serializable
data class ClockOutResult(val id: String, @SerialName("clock_out_at") val clockOutAt: String)

@Serializable
data class OnShiftRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_email") val userEmail: String? = null,
    @SerialName("branch_id") val branchId: String,
    @SerialName("branch_name") val branchName: String? = null,
    @SerialName("clock_in_at") val clockInAt: String,
)

/**
 * Creating a login and resetting a password both go through this same
 * request-a-code / redeem-the-code shape, gated on a code mailed to the
 * company's central security mailbox rather than the new user's own inbox —
 * see backend/app/api/v1/auth/router.py's masked_security_email(). Neither
 * pair is specific to Staff (the sign-in screen's own "Forgot password" will
 * want the password-reset pair too, once that's built) — they live here only
 * because Staff is this app's first and so-far-only consumer; move them to a
 * shared AuthApi.kt the moment a second screen needs them, per this
 * project's own "declare your own endpoint interface" convention
 * (ApiClient.kt).
 */
@Serializable
data class OtpChallenge(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("expires_in") val expiresIn: Int,
    val destination: String,
)

@Serializable
data class RegistrationRequestBody(
    val email: String,
    val name: String,
    val phone: String? = null,
    val password: String,
)

@Serializable
data class RegistrationConfirmBody(@SerialName("challenge_id") val challengeId: String, val code: String)

@Serializable
data class AccountActionResult(val message: String)

@Serializable
data class PasswordResetRequestBody(val email: String)

@Serializable
data class PasswordResetConfirmBody(
    @SerialName("challenge_id") val challengeId: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

interface StaffApi {

    @GET("staff/users")
    suspend fun list(): List<StaffUser>

    @PATCH("staff/users/{id}")
    suspend fun update(
        @Path("id") id: String,
        @Body body: StaffUserUpdateBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): StaffUser

    @DELETE("staff/users/{id}")
    suspend fun delete(
        @Path("id") id: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    @GET("staff/roles")
    suspend fun roles(): List<StaffRole>

    @POST("staff/attendance/clock-in")
    suspend fun clockIn(@Body body: ClockInBody): ClockInResult

    @POST("staff/attendance/clock-out")
    suspend fun clockOut(): ClockOutResult

    @GET("staff/attendance/on-shift")
    suspend fun onShift(): List<OnShiftRow>

    @POST("auth/register/request")
    suspend fun requestRegistration(@Body body: RegistrationRequestBody): OtpChallenge

    @POST("auth/register/confirm")
    suspend fun confirmRegistration(@Body body: RegistrationConfirmBody): AccountActionResult

    @POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequestBody): OtpChallenge

    @POST("auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirmBody): AccountActionResult
}
