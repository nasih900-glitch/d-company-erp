package cloud.dcompany.erp.core.net

/** Public signed-out recovery only; authenticated Settings uses its ordinary operation authority. */
internal interface AnonymousPasswordRecoveryApi {
    @retrofit2.http.POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@retrofit2.http.Body body: PasswordResetRequest): PasswordResetChallenge
    @retrofit2.http.POST("auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@retrofit2.http.Body body: PasswordResetConfirmRequest): AccountActionResponse
}
