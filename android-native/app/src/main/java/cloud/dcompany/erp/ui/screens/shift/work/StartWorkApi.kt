package cloud.dcompany.erp.ui.screens.shift.work

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable internal data class OwnerStartWorkReview(
    val reference: StartWorkReference,
    val mode: String,
    @SerialName("owner_email") val ownerEmail: String,
    @SerialName("owner_password") val ownerPassword: String,
    val reason: String? = null,
) { override fun toString() = "OwnerStartWorkReview(<redacted>)" }

internal interface StartWorkApi {
    @POST("pos/shifts/start-work") suspend fun start(@Body body: StartWorkBody,
        @Header("Idempotency-Key") key: String,
        @Header("X-Installation-Id") installationId: String): StartWorkReceipt
    @GET("pos/shifts/start-work/{id}") suspend fun status(@Path("id") actionId: String): StartWorkOutcome
}
internal interface OwnerStartWorkApi {
    @POST("pos/shifts/start-work/recovery") suspend fun review(@Body body: OwnerStartWorkReview): StartWorkOutcome
}
