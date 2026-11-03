package cloud.dcompany.erp.ui.screens.staff.time

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
internal data class TimePerson(val id: String, val name: String, val status: String, val departed: Boolean)

@Serializable
internal data class TimePeoplePage(
    val items: List<TimePerson>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class TimeAttendance(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("clock_in_at") val clockInAt: String,
    @SerialName("clock_out_at") val clockOutAt: String?,
    @SerialName("break_minutes") val breakMinutes: Int,
    val source: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("break_confirmed") val breakConfirmed: Boolean = false,
)

@Serializable
internal data class TimeWorkPolicy(
    val id: String,
    @SerialName("user_id") val userId: String,
    val revision: Int,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("monthly_salary_minor") val monthlySalaryMinor: Long,
    @SerialName("daily_presence_minutes") val dailyPresenceMinutes: Int,
    @SerialName("daily_break_minutes") val dailyBreakMinutes: Int,
    @SerialName("working_weekday_mask") val workingWeekdayMask: Int,
    @SerialName("overtime_multiplier_basis_points") val overtimeMultiplierBasisPoints: Int? = null,
    @SerialName("pay_status") val payStatus: String,
    val reason: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class TimeWorkSchedule(
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("daily_presence_minutes") val dailyPresenceMinutes: Int,
    @SerialName("daily_break_minutes") val dailyBreakMinutes: Int,
    @SerialName("working_weekday_mask") val workingWeekdayMask: Int,
)

@Serializable
internal data class StaffTimeSummary(
    @SerialName("user_id") val userId: String,
    val name: String,
    val month: String,
    val timezone: String,
    @SerialName("as_of") val asOf: String,
    val entries: List<TimeAttendance>,
    @SerialName("closed_gross_seconds") val closedGrossSeconds: Long,
    @SerialName("closed_work_seconds") val closedWorkSeconds: Long?,
    @SerialName("provisional_open_seconds") val provisionalOpenSeconds: Long,
    @SerialName("review_attendance_ids") val reviewAttendanceIds: List<String>,
    @SerialName("pay_status") val payStatus: String,
    @SerialName("overtime_pay_minor") val overtimePayMinor: Long? = null,
    @SerialName("payable_minor") val payableMinor: Long? = null,
    val policy: TimeWorkPolicy? = null,
    val schedule: TimeWorkSchedule? = null,
    @SerialName("scheduled_days") val scheduledDays: Int? = null,
    @SerialName("scheduled_work_minutes") val scheduledWorkMinutes: Int? = null,
    @SerialName("reference_hourly_numerator_minor") val referenceHourlyNumeratorMinor: Long? = null,
    @SerialName("reference_hourly_denominator_minutes") val referenceHourlyDenominatorMinutes: Int? = null,
    @SerialName("policy_covers_full_month") val policyCoversFullMonth: Boolean = false,
    @SerialName("pay_estimate") val payEstimate: TimePayEstimate? = null,
)

@Serializable internal data class TimePayEstimateDay(
    val date: String,
    @SerialName("paid_seconds") val paidSeconds: Long,
    @SerialName("ordinary_seconds") val ordinarySeconds: Long,
    @SerialName("extra_seconds") val extraSeconds: Long,
)
@Serializable internal data class TimePayEstimate(
    val status: String,
    val reason: String? = null,
    val basis: String,
    @SerialName("attendance_scope") val attendanceScope: String,
    @SerialName("monthly_salary_minor") val monthlySalaryMinor: Long? = null,
    @SerialName("daily_paid_seconds") val dailyPaidSeconds: Long? = null,
    @SerialName("scheduled_days") val scheduledDays: Int? = null,
    @SerialName("scheduled_paid_seconds") val scheduledPaidSeconds: Long? = null,
    @SerialName("recorded_paid_seconds") val recordedPaidSeconds: Long? = null,
    @SerialName("ordinary_seconds") val ordinarySeconds: Long? = null,
    @SerialName("extra_seconds") val extraSeconds: Long? = null,
    @SerialName("hourly_rate_minor") val hourlyRateMinor: Long? = null,
    @SerialName("base_pay_minor") val basePayMinor: Long? = null,
    @SerialName("overtime_125_minor") val overtime125Minor: Long? = null,
    @SerialName("overtime_150_minor") val overtime150Minor: Long? = null,
    val days: List<TimePayEstimateDay> = emptyList(),
)

@Serializable internal data class TimeSelfClockIn(@SerialName("branch_id") val branchId: String)
@Serializable internal data class TimeSelfClockOut(
    @SerialName("attendance_id") val attendanceId: String,
    @SerialName("break_minutes") val breakMinutes: Int,
)
@Serializable internal data class TimeOwnerClockIn(
    @SerialName("branch_id") val branchId: String,
    val reason: String,
)
@Serializable internal data class TimeOwnerClockOut(
    @SerialName("attendance_id") val attendanceId: String,
    @SerialName("break_minutes") val breakMinutes: Int,
    val reason: String,
)
@Serializable internal data class TimeAttendanceCorrection(
    @SerialName("expected_updated_at") val expectedUpdatedAt: String,
    @SerialName("clock_in_at") val clockInAt: String,
    @SerialName("clock_out_at") val clockOutAt: String?,
    @SerialName("break_minutes") val breakMinutes: Int,
    val reason: String,
)
@Serializable internal data class TimePolicyWrite(
    @SerialName("expected_revision") val expectedRevision: Int,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("monthly_salary_minor") val monthlySalaryMinor: Long,
    @SerialName("daily_presence_minutes") val dailyPresenceMinutes: Int,
    @SerialName("daily_break_minutes") val dailyBreakMinutes: Int,
    @SerialName("working_weekday_mask") val workingWeekdayMask: Int,
    // This UI can save draft terms, never activate overtime or payable money.
    @SerialName("overtime_multiplier_basis_points") val overtimeMultiplierBasisPoints: Int? = null,
    val reason: String,
)

internal interface StaffTimeApi {
    @GET("staff/time-summary") suspend fun summary(
        @Query("month") month: String, @Query("user_id") userId: String?,
    ): StaffTimeSummary
    @GET("staff/time-people") suspend fun people(
        @Query("after_id") afterId: String?, @Query("limit") limit: Int = 50,
    ): TimePeoplePage
    @GET("staff/work-policies/{user_id}") suspend fun policies(
        @Path("user_id") userId: String,
        @Query("before_revision") beforeRevision: Int?, @Query("limit") limit: Int = 50,
    ): List<TimeWorkPolicy>
    @POST("staff/my-attendance/clock-in") suspend fun selfClockIn(
        @Body body: TimeSelfClockIn, @Header("Idempotency-Key") key: String,
    ): TimeAttendance
    @POST("staff/my-attendance/clock-out") suspend fun selfClockOut(
        @Body body: TimeSelfClockOut, @Header("Idempotency-Key") key: String,
    ): TimeAttendance
    @POST("staff/attendance/{user_id}/clock-in") suspend fun ownerClockIn(
        @Path("user_id") userId: String, @Body body: TimeOwnerClockIn,
        @Header("Idempotency-Key") key: String,
    ): TimeAttendance
    @POST("staff/attendance/{user_id}/clock-out") suspend fun ownerClockOut(
        @Path("user_id") userId: String, @Body body: TimeOwnerClockOut,
        @Header("Idempotency-Key") key: String,
    ): TimeAttendance
    @PATCH("staff/attendance/{user_id}/{attendance_id}") suspend fun correct(
        @Path("user_id") userId: String, @Path("attendance_id") attendanceId: String,
        @Body body: TimeAttendanceCorrection, @Header("Idempotency-Key") key: String,
    ): TimeAttendance
    @POST("staff/work-policies/{user_id}") suspend fun savePolicy(
        @Path("user_id") userId: String, @Body body: TimePolicyWrite,
        @Header("Idempotency-Key") key: String,
    ): TimeWorkPolicy
}
