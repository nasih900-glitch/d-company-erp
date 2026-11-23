package cloud.dcompany.erp.ui.screens.staff.time

import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class StaffPayEstimatePolicyTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val policy = TimeWorkPolicy("44444444-4444-4444-8444-444444444444", user, 1, "2026-11-01",
        1200000, 720, 60, 127, null, "draft", "Synthetic terms", "2026-11-01T00:00:00Z")
    private fun estimate() = TimePayEstimate("available", basis = "paid_presence_clock_in_workday", attendanceScope = "selected_branch",
        monthlySalaryMinor = 1200000, dailyPaidSeconds = 43200, scheduledDays = 30, scheduledPaidSeconds = 1296000,
        recordedPaidSeconds = 50400, ordinarySeconds = 43200, extraSeconds = 7200, hourlyRateMinor = 3333,
        basePayMinor = 40000, overtime125Minor = 8333, overtime150Minor = 10000,
        days = listOf(TimePayEstimateDay("2026-11-02", 50400, 43200, 7200)))
    private fun summary() = StaffTimeSummary(user, "Synthetic worker", "2026-11", "Asia/Kolkata", "2026-11-03T00:00:00Z",
        emptyList(), 50400, 46800, 0, emptyList(), "draft", policy = policy, policyCoversFullMonth = true, payEstimate = estimate())
    @Test fun validatesSeparateSafeBaseAndOvertimeComponents() {
        validateTimePayEstimate(summary())
        assertNull(summary().payableMinor)
        assertNull(summary().overtimePayMinor)
    }
    @Test fun refusesUnsafeMoneyAndWrongMonthOrMissingPolicy() {
        val s = summary()
        listOf(s.copy(payEstimate = estimate().copy(basePayMinor = Long.MAX_VALUE)),
            s.copy(policy = null), s.copy(policyCoversFullMonth = false),
            s.copy(payEstimate = estimate().copy(days = listOf(TimePayEstimateDay("2026-10-02", 1, 1, 0)))),
            s.copy(payEstimate = estimate().copy(basis = "unapproved_basis"))).forEach {
            assertTrue(runCatching { validateTimePayEstimate(it) }.isFailure)
        }
    }
    @Test fun calendarBreakAllocationReviewDoesNotDiscardSeparatelyVerifiedPaidPresenceWorkday() {
        validateTimePayEstimate(summary().copy(reviewAttendanceIds = listOf("calendar-break-allocation")))
    }
    @Test fun unavailableCannotSmuggleAPayableAmount() {
        val unavailable = TimePayEstimate("unavailable", "Owner review needed", "paid_presence_clock_in_workday", "company")
        validateTimePayEstimate(summary().copy(payEstimate = unavailable))
        assertTrue(runCatching { validateTimePayEstimate(summary().copy(payEstimate = unavailable.copy(basePayMinor = 0))) }.isFailure)
    }
    @Test fun missingAdditiveFieldOnOlderServerRemainsCompatibleWithoutInventingPay() {
        val old = """{"user_id":"$user","name":"Synthetic worker","month":"2026-11","timezone":"UTC","as_of":"2026-11-03T00:00:00Z","entries":[],"closed_gross_seconds":0,"closed_work_seconds":0,"provisional_open_seconds":0,"review_attendance_ids":[],"pay_status":"draft"}"""
        val decoded = StaffTimeJson.decodeFromString<StaffTimeSummary>(old)
        assertNull(decoded.payEstimate)
        validateTimePayEstimate(decoded)
    }
}
