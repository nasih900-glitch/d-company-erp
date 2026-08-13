package cloud.dcompany.erp.ui.screens.kitchen

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Kitchen Display System endpoints. Built with ApiClient.create<KitchenApi>()
 * so this feature owns its own surface instead of piling into ErpApi.
 *
 * Neither call moves money, so neither carries an Idempotency-Key: the advance
 * is naturally idempotent (see KitchenState) and the queue is a plain read.
 */
interface KitchenApi {

    /**
     * Active kitchen work. With [includeServed] the server switches to today's
     * history instead — same shape, but served lines and tickets come back too.
     */
    @GET("kitchen/queue")
    suspend fun queue(@Query("include_served") includeServed: Boolean): List<KitchenOrder>

    /**
     * Advances one ticket. The response is the whole ticket re-emitted, but its
     * `lines` include already-served lines that the queue omits — so only the
     * returned `kitchen_state` should be trusted for an in-place update.
     */
    @PATCH("kitchen/orders/{id}/state")
    suspend fun setState(
        @Path("id") orderId: String,
        @Body body: KitchenStateUpdate,
    ): KitchenOrder
}
