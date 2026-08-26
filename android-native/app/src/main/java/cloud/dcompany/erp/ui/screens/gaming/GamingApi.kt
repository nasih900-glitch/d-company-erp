package cloud.dcompany.erp.ui.screens.gaming

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Copied from StationRead / SessionRead in backend/app/api/v1/gaming/router.py. */
@Serializable
data class Station(
    val id: String,
    val code: String,
    val name: String,
    val type: String,
    @SerialName("rate_per_hour_minor") val ratePerHourMinor: Long,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class GameSession(
    val id: String,
    @SerialName("station_id") val stationId: String,
    val status: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("timer_minutes") val timerMinutes: Int? = null,
    @SerialName("timer_ends_at") val timerEndsAt: String? = null,
    @SerialName("billable_minutes") val billableMinutes: Int? = null,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    /** Local outbox metadata; absent on server DTOs. */
    val localState: String? = null,
    val lastError: String? = null,
)

@Serializable
data class SessionStartBody(
    @SerialName("station_id") val stationId: String,
    @SerialName("shift_id") val shiftId: String,
    @SerialName("customer_phone") val customerPhone: String? = null,
    @SerialName("timer_minutes") val timerMinutes: Int? = null,
)

@Serializable
data class SessionPosResult(
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_minor") val amountMinor: Long,
)

@Serializable
data class SessionCancelBody(val reason: String)

interface GamingApi {

    @GET("gaming/stations")
    suspend fun stations(): List<Station>

    @GET("gaming/sessions")
    suspend fun sessions(
        // The Android screen only renders active or stopped-unbilled sessions.
        // Asking for exactly that set prevents a busy venue's paid history
        // from pushing an older unresolved blocker out of the default page.
        @Query("unbilled_only") unbilledOnly: Boolean = true,
        @Query("limit") limit: Int = 500,
    ): List<GameSession>

    @POST("gaming/sessions/start")
    suspend fun start(
        @Body body: SessionStartBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    @POST("gaming/sessions/{id}/stop")
    suspend fun stop(
        @Path("id") id: String,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    /** Natural idempotency: cancelling an already-cancelled session returns it unchanged. */
    @POST("gaming/sessions/{id}/cancel")
    suspend fun cancel(
        @Path("id") id: String,
        @Body body: SessionCancelBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GameSession

    /** Natural idempotency: the backend locks the session and reuses gs.order_id on retry. */
    @POST("gaming/sessions/{id}/send-to-pos")
    suspend fun sendToPos(
        @Path("id") id: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): SessionPosResult
}
