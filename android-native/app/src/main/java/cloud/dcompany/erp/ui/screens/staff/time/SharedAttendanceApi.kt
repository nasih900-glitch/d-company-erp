package cloud.dcompany.erp.ui.screens.staff.time

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
internal data class SharedTimePerson(
    val id: String,
    val name: String,
    @SerialName("on_duty") val onDuty: Boolean,
    @SerialName("attendance_id") val attendanceId: String?,
)

@Serializable
internal data class SharedTimePeople(
    val items: List<SharedTimePerson>,
    @SerialName("next_cursor") val nextCursor: String?,
)

// Only these credential-free fields may enter durable storage.
@Serializable
internal data class SharedTimeFields(
    @SerialName("user_id") val userId: String,
    @SerialName("attendance_id") val attendanceId: String? = null,
    @SerialName("break_minutes") val breakMinutes: Int? = null,
)

@Serializable
internal class SharedTimeClockIn(
    @SerialName("user_id") val userId: String,
    val password: String,
) { override fun toString() = "SharedTimeClockIn(<redacted>)" }

@Serializable
internal class SharedTimeClockOut(
    @SerialName("user_id") val userId: String,
    val password: String,
    @SerialName("attendance_id") val attendanceId: String,
    @SerialName("break_minutes") val breakMinutes: Int,
) { override fun toString() = "SharedTimeClockOut(<redacted>)" }

@Serializable
internal data class SharedTimeReceipt(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("clock_in_at") val clockInAt: String,
    @SerialName("clock_out_at") val clockOutAt: String?,
    @SerialName("break_minutes") val breakMinutes: Int,
    @SerialName("break_confirmed") val breakConfirmed: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    val source: String,
    @SerialName("staff_name") val staffName: String,
)

internal interface SharedAttendanceApi {
    @GET("staff/shared-attendance/people")
    suspend fun people(@Query("after_id") afterId: String?, @Query("limit") limit: Int = 50): SharedTimePeople
    @POST("staff/shared-attendance/clock-in")
    suspend fun clockIn(@Body body: SharedTimeClockIn, @Header("Idempotency-Key") key: String): SharedTimeReceipt
    @POST("staff/shared-attendance/clock-out")
    suspend fun clockOut(@Body body: SharedTimeClockOut, @Header("Idempotency-Key") key: String): SharedTimeReceipt
}
