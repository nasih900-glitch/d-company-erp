package cloud.dcompany.erp.ui.screens.customers

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response

data class CustomerDirectorySnapshot(
    val customers: List<Customer>,
    val companyId: String,
    val deletionRevision: Long,
)

fun Response<List<Customer>>.requireCustomerDirectorySnapshot(
    expectedCompanyId: String,
): CustomerDirectorySnapshot {
    check(isSuccessful) { "Customer directory refresh failed (${code()})." }
    val customers = requireNotNull(body()) { "Customer directory response was empty." }
    val companyId = headers()["X-Customer-Directory-Company-Id"]
    check(companyId == expectedCompanyId) { "Customer directory response belongs to another company." }
    val revisionText = headers()["X-Customer-Directory-Revision"]
    check(revisionText != null && revisionText.matches(Regex("0|[1-9][0-9]*"))) {
        "Customer directory response has no valid revision."
    }
    val revision = revisionText.toLongOrNull()
    check(revision != null && revision >= 0L) { "Customer directory revision is out of range." }
    return CustomerDirectorySnapshot(customers, companyId, revision)
}

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
    ): Response<List<Customer>>

    @POST("customers")
    suspend fun upsert(
        @Body body: CustomerUpsertBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Customer

    @PATCH("customers/{id}")
    suspend fun update(
        @Path("id") id: String,
        @Body body: CustomerUpdateBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Customer

    @GET("customers/{id}/history")
    suspend fun history(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
    ): List<CustomerOrderHistory>

    @GET("customers/playtime/leaderboard")
    suspend fun playtimeLeaderboard(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 5,
    ): PlaytimeLeaderboard
}
