package cloud.dcompany.erp.ui.screens.settings

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Settings endpoints, declared here rather than in the shared ErpApi so this
 * feature can change without touching a file every other screen compiles
 * against.
 *
 * Every write carries an Idempotency-Key. The company profile is not a ledger
 * entry, but `upi_vpa` is the account customers are told to pay into and the
 * GSTIN is what gets printed on a tax invoice, so a request that times out
 * mid-flight must be replayable rather than re-executed blind. The key is
 * minted once per attempt (see IdempotencyKeys / SettingsViewModel's Room
 * outbox for the offline paths).
 *
 * Offline scope, deliberately narrow — lowest-frequency screen in this
 * rebuild: updateCompany (Shape C, natural PATCH retry-safety, no backend
 * idempotency needed) and createBranch/createTerminal (Shape D, mandatory
 * idempotency, since a retry with no key could 409 or silently duplicate)
 * are real offline outbox writes — the plausible "no signal at the back
 * office" cases. updateBranch, updateTerminal, deleteTerminal, and the password-change
 * flow below stay online-only: editing an existing branch or deleting a
 * terminal is a rare, deliberate admin action typically done once at
 * setup, and password reset is an inherently live OTP round-trip that
 * can't be queued at all.
 */
interface SettingsApi {

    @GET("settings/company")
    suspend fun company(): CompanyDto

    @PATCH("settings/company")
    suspend fun updateCompany(
        @Body body: CompanyUpdateBody,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): CompanyDto

    @GET("settings/branches")
    suspend fun branches(): List<BranchDto>

    /** Mandatory Idempotency-Key: has a company+name uniqueness guard, but
     * a retry after a dropped response would otherwise just 409 instead of
     * replaying the original success (see backend's _require_idempotency
     * doc comment). Also this screen's one Shape D create-offline write. */
    @POST("settings/branches")
    suspend fun createBranch(
        @Body body: BranchWriteBody,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): BranchDto

    @PATCH("settings/branches/{id}")
    suspend fun updateBranch(
        @Path("id") id: String,
        @Body body: BranchWriteBody,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): BranchDto

    @GET("settings/terminals")
    suspend fun terminals(@Query("branch_id") branchId: String? = null): List<TerminalDto>

    @POST("settings/terminals")
    suspend fun createTerminal(
        @Body body: TerminalCreateBody,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): TerminalDto

    /** Natural absolute PATCH: retrying the same name/device binding is safe. */
    @PATCH("settings/terminals/{id}")
    suspend fun updateTerminal(
        @Path("id") id: String,
        @Body body: TerminalUpdateBody,
    ): TerminalDto

    /** 204 on success; 409 when the till has shift, order or audit history. */
    @DELETE("settings/terminals/{id}")
    suspend fun deleteTerminal(
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    /**
     * Password changes are not self-service: the code goes to the business
     * security mailbox, not to the staff member's own inbox, so a stolen
     * tablet cannot be turned into a stolen account.
     */
    @POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequestBody): OtpChallengeDto

    @POST("auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirmBody): AccountActionDto
}
