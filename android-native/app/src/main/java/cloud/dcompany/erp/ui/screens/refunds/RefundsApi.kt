package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.net.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Mirrors RefundCreate in backend/app/api/v1/pos/router.py. */
@Serializable
data class RefundBody(
    @SerialName("reason_code") val reasonCode: String,
    @SerialName("amount_minor") val amountMinor: Long,
    /** "cash" | "original" | "credit_note" — the backend Literal. */
    val mode: String = "cash",
    val note: String? = null,
)

@Serializable
data class RefundResult(
    val id: String,
    @SerialName("settlement_method") val settlementMethod: String? = null,
)

interface RefundsApi {

    @GET("pos/orders")
    suspend fun orders(@Query("status") status: String? = null): List<Order>

    @GET("pos/orders/{id}")
    suspend fun order(@Path("id") id: String): Order

    @POST("pos/orders/{id}/refunds")
    suspend fun refund(
        @Path("id") id: String,
        @Body body: RefundBody,
        @Header("Idempotency-Key") key: String,
    ): RefundResult
}
