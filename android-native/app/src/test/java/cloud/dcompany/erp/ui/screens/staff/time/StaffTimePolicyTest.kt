package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.net.MeResponse
import java.util.Base64
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StaffTimePolicyTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private val family = "44444444-4444-4444-8444-444444444444"
    private fun me(roles: List<String> = listOf("staff"), permissions: List<String> = listOf("staff.attendance.write")) =
        MeResponse(user, "qa@example.test", "Synthetic worker", roles, companyId = company, effectivePermissions = permissions)
    private fun token(payload: String): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.toByteArray()) + ".synthetic"
    private fun access(version: String = "7", id: String = user, companyId: String = company, familyId: String = family) =
        token("""{"sub":"$id","company_id":"$companyId","type":"access","auth_version":$version,"session_family_id":"$familyId","iat":1}""")
    private fun refresh(version: String = "7", familyId: String = family, jti: String = "original") =
        token("""{"sub":"$user","type":"refresh","auth_version":$version,"family_id":"$familyId","jti":"$jti"}""")
    private fun policy() = TimePolicyWrite(0, "2026-11-02", 50_000, 600, 30, 31, reason = "Synthetic test proposal")

    @Test fun attendanceDoesNotGrantSalaryOrDirectoryAuthority() {
        assertEquals(StaffTimeAccess(true, false), staffTimeAccess(me()))
        assertEquals(StaffTimeAccess(false, false), staffTimeAccess(me(permissions = listOf("staff.read", "staff.write"))))
        assertEquals(StaffTimeAccess(true, false), staffTimeAccess(me(listOf("partner"), listOf(STAFF_PAYROLL_WRITE))))
        assertEquals(StaffTimeAccess(true, true), staffTimeAccess(me(listOf("owner"), listOf(STAFF_PAYROLL_WRITE))))
        assertEquals(StaffTimeAccess(true, false), staffTimeAccess(me(listOf("owner"))))
    }
    @Test fun actualBreakRequiresExplicitWholeMinutesIncludingZero() {
        assertEquals(0, actualBreak("0")); assertEquals(60, actualBreak("60"))
        listOf("", " ", "-1", "1.5", "1,5", "1441", "9999999999").forEach {
            assertThrows(IllegalArgumentException::class.java) { actualBreak(it) }
        }
    }
    @Test fun monthAndUuidInputsStayBoundedAndCanonical() {
        assertEquals("2024-02", timeMonth("2024-02")); assertEquals(user, timeUuid(user))
        listOf("2024-13", "2024-2", "1999-12", "2101-01").forEach {
            assertThrows(RuntimeException::class.java) { timeMonth(it) }
        }
        listOf("1-1-1-1-1", "../../staff", "", user.uppercase().replace('A', 'a') + "x").forEach {
            assertThrows(RuntimeException::class.java) { timeUuid(it) }
        }
    }
    @Test fun correctionsRequireExplicitUtcOrderedTimesAndRealBreaks() {
        val b = TimeAttendanceCorrection("2026-11-02T10:00:00Z", "2026-11-02T08:00:00Z", "2026-11-02T10:00:00Z", 15, "Correct actual arrival")
        validateTimeCorrection(b)
        assertThrows(RuntimeException::class.java) { utcAttendance("2026-11-02T10:00:00+04:00") }
        assertThrows(RuntimeException::class.java) { validateTimeCorrection(b.copy(clockOutAt = "2026-11-02T07:00:00Z")) }
        assertThrows(RuntimeException::class.java) { validateTimeCorrection(b.copy(clockOutAt = null)) }
        assertThrows(RuntimeException::class.java) { validateTimeCorrection(b.copy(breakMinutes = 121)) }
        assertThrows(RuntimeException::class.java) { validateTimeCorrection(b.copy(reason = " ")) }
    }
    @Test fun draftPolicyNeverActivatesOvertimeOrInfersActualBreak() {
        validateTimePolicy(policy())
        assertThrows(RuntimeException::class.java) { validateTimePolicy(policy().copy(overtimeMultiplierBasisPoints = 15_000)) }
        assertThrows(RuntimeException::class.java) { validateTimePolicy(policy().copy(dailyBreakMinutes = 600)) }
        assertThrows(RuntimeException::class.java) { validateTimePolicy(policy().copy(workingWeekdayMask = 0)) }
        assertThrows(RuntimeException::class.java) { validateTimePolicy(policy().copy(expectedRevision = Int.MAX_VALUE)) }
        assertThrows(RuntimeException::class.java) { validateTimePolicy(policy().copy(monthlySalaryMinor = 1_000_000_000_001)) }
        assertEquals(12345, draftSalary("123.45"))
        assertThrows(RuntimeException::class.java) { draftSalary("1.234") }
    }
    @Test fun authProofSurvivesTokenRotationWithinSameFamilyOnly() {
        val a = staffTimeAuthBinding(access(), refresh(), me())
        assertNotNull(a)
        assertEquals(a, staffTimeAuthBinding(access(), refresh(jti = "rotated"), me()))
        assertNotEquals(a, staffTimeAuthBinding(access(), refresh(familyId = company), me()))
        assertNotEquals(a, staffTimeAuthBinding(access("8"), refresh("8"), me()))
        assertNotEquals(a, staffTimeAuthBinding(access(), refresh(), me(permissions = emptyList())))
        assertNotEquals(a, staffTimeAuthBinding(access(), refresh(), me(listOf("owner"))))
    }
    @Test fun malformedOrMismatchedAuthMetadataFailsClosedWithoutExceptions() {
        assertNull(staffTimeAuthBinding("not-a-token", refresh(), me()))
        assertNull(staffTimeAuthBinding(access(id = company), refresh(), me()))
        assertNull(staffTimeAuthBinding(access(companyId = user), refresh(), me()))
        assertNull(staffTimeAuthBinding(access(), refresh("8"), me()))
        assertNull(staffTimeAuthBinding(access("\"7\""), refresh(), me()))
        assertNull(staffTimeAuthBinding(access(), refresh(familyId = "missing"), me()))
        assertNull(staffTimeAuthBinding(access("-1"), refresh("-1"), me()))
    }
    @Test fun persistedSelfClockIntentCannotTargetSomeoneElseOrChangeBranch() {
        val b = TimeSelfClockIn(branch)
        val i = StaffTimeIntent(family, user, company, branch, null, "a".repeat(64), user, "Synthetic worker", TimeWriteKind.SELF_IN, payload = StaffTimeJson.encodeToString(b))
        i.validate()
        assertThrows(RuntimeException::class.java) { i.copy(targetId = company).validate() }
        assertThrows(RuntimeException::class.java) { i.copy(branchId = company).validate() }
        assertThrows(RuntimeException::class.java) { i.copy(payload = """{"branch_id":"$branch","user_id":"$company"}""").validate() }
        assertFalse(i.toString().contains(branch))
        assertFalse(i.toString().contains(i.payload))
    }
    @Test fun exactStoredPayloadRoundTripsAndMissingActualBreakIsRejected() {
        val b = TimeSelfClockOut(company, 0)
        val i = StaffTimeIntent(family, user, company, branch, null, "a".repeat(64), user, "Synthetic worker", TimeWriteKind.SELF_OUT, company, StaffTimeJson.encodeToString(b))
        i.validate()
        val roundtrip = StaffTimeJson.decodeFromString(StaffTimeIntent.serializer(), StaffTimeJson.encodeToString(i))
        assertEquals(i, roundtrip)
        assertThrows(RuntimeException::class.java) { i.copy(payload = """{"attendance_id":"$company"}""").validate() }
        assertThrows(RuntimeException::class.java) { i.copy(attendanceId = user).validate() }
    }
    @Test fun accessAndRefreshFamilyClaimsMustMatchActualServerContract() {
        assertNull(staffTimeAuthBinding(access(familyId = company), refresh(), me()))
        val noAccessFamily = token("""{"sub":"$user","company_id":"$company","type":"access","auth_version":7}""")
        assertNull(staffTimeAuthBinding(noAccessFamily, refresh(), me()))
        val noRefreshFamily = token("""{"sub":"$user","type":"refresh","auth_version":7}""")
        assertNull(staffTimeAuthBinding(access(), noRefreshFamily, me()))
        assertNotNull(staffTimeAuthBinding(access(), refresh(), me()))
    }

}
