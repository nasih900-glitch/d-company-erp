package cloud.dcompany.erp.ui.screens.settings

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
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
 * minted once per attempt by SettingsViewModel and reused verbatim on retry —
 * see IdempotencyKeys there.
 */
interface SettingsApi {

    @GET("settings/company")
    suspend fun company(): CompanyDto

    @PATCH("settings/company")
    suspend fun updateCompany(
        @Body body: CompanyUpdateBody,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): CompanyDto

    @GET("settings/branches")
    suspend fun branches(): List<BranchDto>

    @POST("settings/branches")
    suspend fun createBranch(
        @Body body: BranchWriteBody,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): BranchDto

    @PATCH("settings/branches/{id}")
    suspend fun updateBranch(
        @Path("id") id: String,
        @Body body: BranchWriteBody,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): BranchDto

    @GET("settings/terminals")
    suspend fun terminals(@Query("branch_id") branchId: String? = null): List<TerminalDto>

    @POST("settings/terminals")
    suspend fun createTerminal(
        @Body body: TerminalCreateBody,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): TerminalDto

    /** 204 on success; 409 when the till has shift, order or audit history. */
    @DELETE("settings/terminals/{id}")
    suspend fun deleteTerminal(
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
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
