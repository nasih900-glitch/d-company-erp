package cloud.dcompany.erp.ui.screens.customers

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoints from backend/app/api/v1/customers/router.py.
 *
 * No Idempotency-Key here on purpose: neither call moves money or writes to a
 * ledger, and both are already replay-safe by construction — POST /customers
 * is an upsert keyed on the phone number (a repeat returns the existing
 * customer instead of creating a twin), and PATCH targets one id with an
 * absolute set of values. Sending a key would imply a protection the
 * customers router does not actually implement.
 */
interface CustomersApi {

    @GET("customers")
    suspend fun list(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int = 200,
    ): List<Customer>

    @POST("customers")
    suspend fun upsert(@Body body: CustomerUpsertBody): Customer

    @PATCH("customers/{id}")
    suspend fun update(
        @Path("id") id: String,
        @Body body: CustomerUpdateBody,
    ): Customer
}
