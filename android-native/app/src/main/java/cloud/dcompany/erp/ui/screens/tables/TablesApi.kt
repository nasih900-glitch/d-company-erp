package cloud.dcompany.erp.ui.screens.tables

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface TablesApi {

    @GET("tables/floors")
    suspend fun floors(): List<Floor>

    @GET("tables")
    suspend fun tables(): List<CafeTable>

    /**
     * Opens the first table round. Stable client line IDs are required for
     * crash-safe replay and for matching a later cancellation to the exact
     * kitchen line the waiter saw.
     */
    @POST("pos/orders")
    suspend fun createOrder(
        @Body body: TableOrderCreateBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): TableOrder

    @POST("pos/orders/{id}/lines")
    suspend fun appendRound(
        @Path("id") id: String,
        @Body body: OrderLinesAppendBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): TableOrder

    @POST("pos/orders/{orderId}/lines/{lineId}/void")
    suspend fun voidLine(
        @Path("orderId") orderId: String,
        @Path("lineId") lineId: String,
        @Body body: VoidOrderLineBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): TableOrder

    /**
     * Retrofit's @DELETE annotation intentionally has no request-body support.
     * The POS contract requires a reason in the DELETE body, so use @HTTP with
     * hasBody=true instead of silently dropping the audit reason.
     */
    @HTTP(method = "DELETE", path = "pos/orders/{id}", hasBody = true)
    suspend fun voidOrder(
        @Path("id") id: String,
        @Body body: VoidOrderBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    @PATCH("pos/orders/{id}/send-to-pos")
    suspend fun sendToPos(
        @Path("id") id: String,
        @Body body: SendToPosBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): TableOrder

    @GET("pos/table-orders/active")
    suspend fun activeOrders(): List<TableOrder>

    @GET("pos/orders/{id}")
    suspend fun order(@Path("id") id: String): TableOrder
}
