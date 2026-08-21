package cloud.dcompany.erp.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** Mirrors AuditUnlockRequest in backend/app/api/v1/admin/router.py — same shape, reused for pricing. */
@Serializable
data class PricingUnlockRequest(val password: String)

@Serializable
data class PricingUnlockResponse(
    @SerialName("pricing_token") val pricingToken: String,
    /** Seconds — currently 600 (10 minutes) server-side. */
    @SerialName("expires_in") val expiresIn: Int,
)

interface PricingApi {
    @POST("admin/pricing/unlock")
    suspend fun unlock(@Body body: PricingUnlockRequest): PricingUnlockResponse
}
