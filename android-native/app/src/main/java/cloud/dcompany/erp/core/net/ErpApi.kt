package cloud.dcompany.erp.core.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Every money-mutating call takes an Idempotency-Key. The backend stores the
 * response against that key, so a retry after a dropped connection replays the
 * original result instead of charging a second time. The key must therefore be
 * generated once per logical attempt and reused across retries — never
 * regenerated inside a retry loop.
 */
interface ErpApi {

    @GET("public/client-compatibility")
    suspend fun clientCompatibility(
        @Query("platform") platform: String,
        @Query("version_code") versionCode: Int,
    ): ClientCompatibilityResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenPair

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenPair

    @POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequest): PasswordResetChallenge

    @POST("auth/password-reset/confirm")
    suspend fun confirmPasswordReset(
        @Body body: PasswordResetConfirmRequest,
    ): AccountActionResponse

    @GET("auth/me")
    suspend fun me(): MeResponse

    @GET("menu/items")
    suspend fun menuItems(@Query("category_id") categoryId: String? = null): List<MenuItem>

    @GET("menu/categories")
    suspend fun menuCategories(): List<MenuCategory>

    @GET("pos/orders/{id}")
    suspend fun order(@Path("id") id: String): Order

    /** Retrofit repeats `status` once per list entry — matches the backend's `list[str]` param. */
    @GET("pos/orders")
    suspend fun orders(
        @Query("status") status: List<String>,
        @Query("limit") limit: Int = 500,
    ): List<OrderListItem>

    @POST("pos/orders")
    suspend fun createOrder(
        @Body body: CreateOrderRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Order

    @PATCH("pos/orders/{id}/customer")
    suspend fun updateOrderCustomer(
        @Path("id") id: String,
        @Body body: OrderCustomerUpdateRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Order

    @PATCH("pos/orders/{id}/discount")
    suspend fun updateOrderDiscount(
        @Path("id") id: String,
        @Body body: OrderDiscountUpdateRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Order

    @PATCH("pos/orders/{id}/points")
    suspend fun updateOrderPoints(
        @Path("id") id: String,
        @Body body: OrderPointsRedemptionUpdateRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Order

    @HTTP(method = "DELETE", path = "pos/orders/{id}", hasBody = true)
    suspend fun voidOrder(
        @Path("id") id: String,
        @Body body: VoidOrderRequest,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    @POST("pos/orders/{id}/checkout-claim")
    suspend fun acquireCheckoutClaim(
        @Path("id") id: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): CheckoutClaimResult

    @DELETE("pos/orders/{id}/checkout-claim")
    suspend fun releaseCheckoutClaim(
        @Path("id") id: String,
        @Header("X-Checkout-Claim") checkoutClaimToken: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    @POST("pos/orders/{id}/payments")
    suspend fun recordPayment(
        @Path("id") id: String,
        @Body body: PaymentRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Checkout-Claim") checkoutClaimToken: String? = null,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): PaymentResult

    @POST("pos/orders/{id}/finalize-zero")
    suspend fun finalizeZeroTotalOrder(
        @Path("id") id: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Checkout-Claim") checkoutClaimToken: String?,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): ZeroTotalFinalizationResult

    @GET("settings/terminals")
    suspend fun terminals(@Query("branch_id") branchId: String? = null): List<Terminal>
}
