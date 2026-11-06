package cloud.dcompany.erp.ui.screens.staff.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public attendance facts are deliberately independent of write receipts and private summaries. */
@Serializable internal data class AttendanceClockFact(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("branch_id") val branchId: String,
    @SerialName("local_date") val localDate: String,
    @SerialName("clock_in_at") val clockInAt: String,
    @SerialName("clock_out_at") val clockOutAt: String?,
    @SerialName("is_open") val isOpen: Boolean,
)
@Serializable internal data class AttendancePersonCard(
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("clock_in_day_count") val clockInDayCount: Int,
    val entries: List<AttendanceClockFact>,
    @SerialName("continued_from_previous") val continuedFromPrevious: Boolean,
    @SerialName("entries_continued") val entriesContinued: Boolean,
)
@Serializable internal data class AttendanceCardsPage(
    val period: String,
    @SerialName("period_start") val periodStart: String,
    @SerialName("period_end_exclusive") val periodEndExclusive: String,
    val timezone: String,
    @SerialName("as_of") val asOf: String,
    @SerialName("scope_branch_id") val scopeBranchId: String?,
    @SerialName("current_open_attendance") val currentOpenAttendance: AttendanceClockFact?,
    val items: List<AttendancePersonCard>,
    @SerialName("next_cursor") val nextCursor: String?,
)
internal fun attendancePeriodStart(period: String, day: LocalDate): LocalDate = when (period) {
    "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    "month" -> day.withDayOfMonth(1)
    else -> error("Choose week or month.")
}
internal fun attendancePeriodEnd(period: String, start: LocalDate): LocalDate = when (period) {
    "week" -> start.plusDays(7)
    "month" -> start.plusMonths(1)
    else -> error("Choose week or month.")
}
internal fun validateAttendancePage(p: AttendanceCardsPage, period: String, start: String,
    actorId: String, branchId: String?, more: Boolean = false) {
    val first = LocalDate.parse(start)
    require(first.year in 2000..2100 && attendancePeriodStart(period, first) == first)
    require(p.period == period && p.periodStart == start && p.periodEndExclusive == attendancePeriodEnd(period, first).toString())
    val zone = ZoneId.of(p.timezone); Instant.parse(p.asOf)
    require(p.scopeBranchId == branchId)
    p.scopeBranchId?.let(::timeUuid)
    fun fact(f: AttendanceClockFact) {
        timeUuid(f.id); timeUuid(f.userId); timeUuid(f.branchId)
        require(branchId == null || f.branchId == branchId)
        val arrived = Instant.parse(f.clockInAt)
        require(arrived.atZone(zone).toLocalDate().toString() == f.localDate)
        require(f.isOpen == (f.clockOutAt == null))
        f.clockOutAt?.let { require(!Instant.parse(it).isBefore(arrived)) }
    }
    p.currentOpenAttendance?.let { fact(it); require(it.userId == actorId && it.isOpen) }
    require(p.items.size <= 100 && p.items.sumOf { it.entries.size } <= 100)
    require(p.items.map { it.userId }.distinct().size == p.items.size)
    val ids = mutableSetOf<String>()
    p.items.forEachIndexed { index, card ->
        timeUuid(card.userId); require(card.name.length in 1..200 && card.entries.isNotEmpty())
        require(card.clockInDayCount in 1..java.time.temporal.ChronoUnit.DAYS.between(first, attendancePeriodEnd(period, first)).toInt())
        require(!card.continuedFromPrevious || (more && index == 0))
        require(!card.entriesContinued || (index == p.items.lastIndex && p.nextCursor != null))
        card.entries.forEach { f ->
            fact(f); require(f.userId == card.userId && ids.add(f.id))
            val day = LocalDate.parse(f.localDate); require(!day.isBefore(first) && day.isBefore(attendancePeriodEnd(period, first)))
        }
        require(card.entries.map { it.localDate }.distinct().size <= card.clockInDayCount)
        require(card.entries.zipWithNext().all { (a, b) ->
            Instant.parse(a.clockInAt) < Instant.parse(b.clockInAt) ||
                (Instant.parse(a.clockInAt) == Instant.parse(b.clockInAt) && a.id < b.id)
        })
    }
    require(p.items.zipWithNext().all { (a, b) -> a.userId < b.userId })
    p.nextCursor?.let { require(it.length in 1..2048 && it.matches(Regex("[A-Za-z0-9_-]+")) && p.items.isNotEmpty()) }
}
internal fun mergeAttendancePages(previous: AttendanceCardsPage, next: AttendanceCardsPage): AttendanceCardsPage {
    require(previous.period == next.period && previous.periodStart == next.periodStart &&
        previous.periodEndExclusive == next.periodEndExclusive && previous.timezone == next.timezone && previous.scopeBranchId == next.scopeBranchId)
    require(previous.nextCursor != null && previous.nextCursor != next.nextCursor)
    val existingIds = previous.items.flatMap { it.entries }.map { it.id }.toSet()
    require(next.items.flatMap { it.entries }.none { it.id in existingIds }) { "Attendance changed. Refresh this period." }
    val cards = previous.items.toMutableList()
    next.items.forEach { incoming ->
        val last = cards.lastOrNull()
        if (last?.userId == incoming.userId) {
            require(last.entriesContinued && incoming.continuedFromPrevious && last.name == incoming.name && last.clockInDayCount == incoming.clockInDayCount)
            val prior = last.entries.last(); val first = incoming.entries.first()
            val order = Instant.parse(prior.clockInAt).compareTo(Instant.parse(first.clockInAt))
            require(order < 0 || (order == 0 && prior.id < first.id))
            val entries = last.entries + incoming.entries
            require(entries.map { it.localDate }.distinct().size <= last.clockInDayCount)
            cards[cards.lastIndex] = last.copy(entries = entries, entriesContinued = incoming.entriesContinued)
        } else {
            require(!incoming.continuedFromPrevious && last?.entriesContinued != true && (last == null || last.userId < incoming.userId))
            cards += incoming
        }
    }
    cards.forEachIndexed { index, card ->
        require(!card.entriesContinued || (index == cards.lastIndex && next.nextCursor != null))
    }
    return next.copy(items = cards)
}
