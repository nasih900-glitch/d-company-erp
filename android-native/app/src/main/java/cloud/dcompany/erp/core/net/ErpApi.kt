package cloud.dcompany.erp.core.net

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
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

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenPair

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenPair

    @GET("auth/me")
    suspend fun me(): MeResponse

    @GET("menu/items")
    suspend fun menuItems(@Query("category_id") categoryId: String? = null): List<MenuItem>

    @GET("menu/categories")
    suspend fun menuCategories(): List<MenuCategory>

    @GET("pos/shifts")
    suspend fun shifts(@Query("only_open") onlyOpen: Boolean = true): List<Shift>

    @POST("pos/shifts/open")
    suspend fun openShift(@Body body: OpenShiftRequest): ShiftOpened

    @GET("pos/orders/{id}")
    suspend fun order(@Path("id") id: String): Order

    @POST("pos/orders")
    suspend fun createOrder(
        @Body body: CreateOrderRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): Order

    @POST("pos/orders/{id}/payments")
    suspend fun recordPayment(
        @Path("id") id: String,
        @Body body: PaymentRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): PaymentResult

    @GET("settings/terminals")
    suspend fun terminals(@Query("branch_id") branchId: String? = null): List<Terminal>
}
