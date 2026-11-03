package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.StaffAccess
import cloud.dcompany.erp.core.auth.hasNormalizedOwnerRole
import cloud.dcompany.erp.core.money.parseRupeesToMinor
import cloud.dcompany.erp.core.net.MeResponse
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val STAFF_PAYROLL_WRITE = ErpPermission.StaffPayrollWrite
internal data class StaffTimeAccess(val canRead: Boolean, val canManage: Boolean)
internal fun staffIdentityAccess(profile: MeResponse, access: StaffAccess): StaffAccess =
    if (profile.protectedAccess || profile.auditAccess) access.copy(canUseAttendance = false)
    else StaffAccess(false, false, false)

internal fun staffTimeAccess(profile: MeResponse): StaffTimeAccess {
    val permission = EffectivePermissions.from(profile)
    return StaffTimeAccess(
        permission.hasAny(ErpPermission.StaffAttendanceWrite, STAFF_PAYROLL_WRITE),
        profile.roles.hasNormalizedOwnerRole() && permission.has(STAFF_PAYROLL_WRITE),
    )
}

internal val StaffTimeJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
internal fun timeUuid(value: String): String {
    require(value.length == 36 && UUID.fromString(value).toString() == value) {
        "Refresh this person's verified staff identity."
    }
    return value
}
internal fun timeMonth(value: String): String {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}"))) { "Use a month in YYYY-MM format." }
    require(YearMonth.parse(value).year in 2000..2100) { "Choose a month from 2000 through 2100." }
    return value
}
internal fun timeReason(value: String): String = value.trim().also {
    require(it.length in 1..500) { "Enter an audit reason of 1 to 500 characters." }
}
internal fun actualBreak(value: String): Int {
    require(value.matches(Regex("[0-9]{1,4}"))) { "Enter the actual break in whole minutes, including 0." }
    return requireNotNull(value.toIntOrNull()).also {
        require(it in 0..1440) { "Actual break must be 0 to 1440 minutes." }
    }
}
internal fun utcAttendance(value: String): String {
    val parsed = OffsetDateTime.parse(value.trim())
    require(parsed.offset == ZoneOffset.UTC) { "Enter an explicit UTC timestamp ending in Z or +00:00." }
    return parsed.toInstant().toString()
}
internal fun draftSalary(value: String): Long = requireNotNull(parseRupeesToMinor(value)) {
    "Enter a non-negative amount with at most two decimal places."
}.also { require(it <= 1_000_000_000_000L) { "The monthly amount exceeds the allowed limit." } }
internal fun validateTimePolicy(body: TimePolicyWrite) {
    require(body.expectedRevision in 0 until Int.MAX_VALUE) { "Refresh the latest draft revision." }
    require(LocalDate.parse(body.effectiveFrom).year in 2000..2100) { "Choose an effective date from 2000 through 2100." }
    require(body.monthlySalaryMinor in 0..1_000_000_000_000L) { "Invalid monthly draft amount." }
    require(body.dailyPresenceMinutes in 1..1440) { "Presence must be 1 to 1440 minutes." }
    require(body.dailyBreakMinutes in 0 until body.dailyPresenceMinutes) { "Planned break must be shorter than presence." }
    require(body.workingWeekdayMask in 1..127) { "Choose at least one working weekday." }
    require(body.overtimeMultiplierBasisPoints == null) { "Overtime pay is not activated." }
    timeReason(body.reason)
}
internal fun validateTimeCorrection(body: TimeAttendanceCorrection) {
    Instant.parse(body.expectedUpdatedAt)
    val start = Instant.parse(utcAttendance(body.clockInAt))
    val end = body.clockOutAt?.let { Instant.parse(utcAttendance(it)) }
    require(body.breakMinutes in 0..1440) { "Invalid actual break." }
    require(end != null || body.breakMinutes == 0) { "An open entry cannot have a completed break." }
    if (end != null) {
        require(!end.isBefore(start)) { "Departure must not be before arrival." }
        require(body.breakMinutes.toLong() * 60 <= java.time.Duration.between(start, end).seconds) {
            "Actual break cannot exceed the recorded visit."
        }
    }
    timeReason(body.reason)
}
internal fun timeDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun validateTimePayEstimate(summary: StaffTimeSummary) {
    val p = summary.payEstimate ?: return
    val safe = 9_007_199_254_740_991L
    require(p.basis == "paid_presence_clock_in_workday" && p.attendanceScope in setOf("company", "selected_branch"))
    require(p.days.size <= 31)
    if (p.status == "unavailable") {
        require(p.reason?.length in 1..500 && p.days.isEmpty())
        require(p.basePayMinor == null && p.overtime125Minor == null && p.overtime150Minor == null)
        return
    }
    require(p.status == "available" && p.reason == null && summary.policyCoversFullMonth)
    val policy = requireNotNull(summary.policy)
    val values = listOf(p.monthlySalaryMinor, p.dailyPaidSeconds, p.scheduledDays?.toLong(), p.scheduledPaidSeconds,
        p.recordedPaidSeconds, p.ordinarySeconds, p.extraSeconds, p.hourlyRateMinor, p.basePayMinor, p.overtime125Minor, p.overtime150Minor)
    require(values.all { it != null && it in 0..safe })
    require(p.monthlySalaryMinor == policy.monthlySalaryMinor && p.dailyPaidSeconds == policy.dailyPresenceMinutes.toLong() * 60)
    require(p.scheduledDays in 1..31 && p.scheduledPaidSeconds == p.scheduledDays!!.toLong() * p.dailyPaidSeconds!!)
    require(p.ordinarySeconds!! <= p.scheduledPaidSeconds!! && p.basePayMinor!! <= p.monthlySalaryMinor!!)
    require(p.overtime150Minor!! >= p.overtime125Minor!! && p.basePayMinor + p.overtime150Minor <= safe)
    require(p.ordinarySeconds + p.extraSeconds!! <= p.recordedPaidSeconds!! && p.recordedPaidSeconds - p.ordinarySeconds - p.extraSeconds <= 1)
    var previous = ""
    p.days.forEach { day ->
        require(YearMonth.from(LocalDate.parse(day.date)).toString() == summary.month && day.date > previous)
        require(listOf(day.paidSeconds, day.ordinarySeconds, day.extraSeconds).all { it in 0..2_678_400L })
        require(day.ordinarySeconds <= p.dailyPaidSeconds && day.ordinarySeconds + day.extraSeconds <= day.paidSeconds && day.paidSeconds - day.ordinarySeconds - day.extraSeconds <= 1)
        previous = day.date
    }
}

/** Local replay fence, not token verification or server authorization. Never retain either token. */
internal fun staffTimeAuthBinding(access: String, refresh: String, profile: MeResponse): String? = runCatching {
    fun payload(token: String) = run {
        require(token.length in 1..16_384)
        val parts = token.split('.')
        require(parts.size == 3 && parts[1].length <= 12_000)
        val bytes = Base64.getUrlDecoder().decode(parts[1])
        StaffTimeJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    }
    val a = payload(access)
    val r = payload(refresh)
    require(a["type"]?.jsonPrimitive?.contentOrNull == "access")
    require(r["type"]?.jsonPrimitive?.contentOrNull == "refresh")
    require(a["sub"]?.jsonPrimitive?.contentOrNull == timeUuid(profile.userId))
    require(r["sub"]?.jsonPrimitive?.contentOrNull == profile.userId)
    require(a["company_id"]?.jsonPrimitive?.contentOrNull == timeUuid(profile.companyId))
    val versionClaim = requireNotNull(a["auth_version"]?.jsonPrimitive)
    val refreshVersion = requireNotNull(r["auth_version"]?.jsonPrimitive)
    require(!versionClaim.isString && !refreshVersion.isString)
    val version = requireNotNull(versionClaim.longOrNull)
    require(version >= 0 && refreshVersion.longOrNull == version)
    val family = timeUuid(requireNotNull(r["family_id"]?.jsonPrimitive?.contentOrNull))
    require(timeUuid(requireNotNull(a["session_family_id"]?.jsonPrimitive?.contentOrNull)) == family)
    val roles = profile.roles.sorted().joinToString("\u0000")
    val permissions = profile.effectivePermissions?.sorted()?.joinToString("\u0000") ?: "<legacy>"
    timeDigest(listOf(profile.userId, profile.companyId, version.toString(), family, roles, permissions).joinToString("\u0001"))
}.getOrNull()

@Serializable internal enum class TimeWriteKind { SELF_IN, SELF_OUT, OWNER_IN, OWNER_OUT, CORRECTION, POLICY }
@Serializable internal data class StaffTimeIntent(
    val key: String,
    val actorId: String,
    val companyId: String,
    val branchId: String,
    val terminalId: String?,
    val authBinding: String,
    val targetId: String,
    val targetName: String,
    val kind: TimeWriteKind,
    val attendanceId: String? = null,
    val payload: String,
) {
    override fun toString() = "StaffTimeIntent(kind=$kind, key=<redacted>, payload=<redacted>)"
    fun validate() {
        timeUuid(key); timeUuid(actorId); timeUuid(companyId); timeUuid(branchId); timeUuid(targetId)
        terminalId?.let(::timeUuid)
        require(authBinding.matches(Regex("[0-9a-f]{64}"))) { "Sign in to verify the staff-time session." }
        require(targetName.length in 1..200 && payload.length in 1..16_384)
        when (kind) {
            TimeWriteKind.SELF_IN -> {
                require(targetId == actorId)
                require(StaffTimeJson.decodeFromString<TimeSelfClockIn>(payload).branchId == branchId)
            }
            TimeWriteKind.SELF_OUT -> {
                require(targetId == actorId)
                val b = StaffTimeJson.decodeFromString<TimeSelfClockOut>(payload)
                timeUuid(b.attendanceId); require(b.breakMinutes in 0..1440)
                require(attendanceId == b.attendanceId)
            }
            TimeWriteKind.OWNER_IN -> {
                val b = StaffTimeJson.decodeFromString<TimeOwnerClockIn>(payload)
                require(b.branchId == branchId); timeReason(b.reason)
            }
            TimeWriteKind.OWNER_OUT -> {
                val b = StaffTimeJson.decodeFromString<TimeOwnerClockOut>(payload)
                timeUuid(b.attendanceId); require(b.breakMinutes in 0..1440)
                require(attendanceId == b.attendanceId); timeReason(b.reason)
            }
            TimeWriteKind.CORRECTION -> { timeUuid(requireNotNull(attendanceId)); validateTimeCorrection(StaffTimeJson.decodeFromString(payload)) }
            TimeWriteKind.POLICY -> validateTimePolicy(StaffTimeJson.decodeFromString(payload))
        }
    }
    val needsOwner: Boolean get() = kind in setOf(TimeWriteKind.OWNER_IN, TimeWriteKind.OWNER_OUT, TimeWriteKind.CORRECTION, TimeWriteKind.POLICY)
}
