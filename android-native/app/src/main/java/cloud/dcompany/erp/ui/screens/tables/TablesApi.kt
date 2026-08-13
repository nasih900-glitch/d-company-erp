package cloud.dcompany.erp.ui.screens.tables

import cloud.dcompany.erp.core.net.CreateOrderRequest
import cloud.dcompany.erp.core.net.Order
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface TablesApi {

    @GET("tables/floors")
    suspend fun floors(): List<Floor>

    @GET("tables")
    suspend fun tables(): List<CafeTable>

    /**
     * Opens a bill against a table. Reuses the shared CreateOrderRequest so the
     * table order and the POS order stay one contract — a second, slightly
     * different order payload is how the two drift apart.
     */
    @POST("pos/orders")
    suspend fun createOrder(
        @Body body: CreateOrderRequest,
        @Header("Idempotency-Key") key: String,
    ): Order

    @PATCH("pos/orders/{id}/send-to-pos")
    suspend fun sendToPos(@Path("id") id: String): Order
}
