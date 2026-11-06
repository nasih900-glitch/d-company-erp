package cloud.dcompany.erp.ui.screens.staff.time

import java.time.LocalDate
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class AttendanceCardsTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private fun fact(id: String = "44444444-4444-4444-8444-444444444444", day: String = "2026-11-02") =
        AttendanceClockFact(id, actor, branch, day, "${day}T04:00:00Z", "${day}T16:00:00Z", false)
    private fun page() = AttendanceCardsPage("week", "2026-11-02", "2026-11-09", "Asia/Dubai", "2026-11-05T00:00:00Z", branch, null,
        listOf(AttendancePersonCard(actor, "Synthetic", 3, listOf(fact()), false, true)), "cursor1")
    @Test fun partialCardUsesFullPeriodCountAndMergesWithoutDoubleCountingDays() {
        val first = page(); validateAttendancePage(first, "week", "2026-11-02", actor, branch)
        val next = first.copy(items = listOf(first.items.single().copy(entries = listOf(fact("55555555-5555-4555-8555-555555555555", "2026-11-03")),
            continuedFromPrevious = true, entriesContinued = false)), nextCursor = null)
        validateAttendancePage(next, "week", "2026-11-02", actor, branch, more = true)
        val joined = mergeAttendancePages(first, next)
        assertEquals(3, joined.items.single().clockInDayCount); assertEquals(2, joined.items.single().entries.size); assertNull(joined.nextCursor)
        assertThrows(IllegalArgumentException::class.java) { mergeAttendancePages(first, next.copy(items = first.items)) }
    }
    @Test fun wrongDatesScopeUserOpenFlagAndUnknownPrivateFieldsAreRejected() {
        val p = page()
        for (bad in listOf(p.copy(scopeBranchId = actor), p.copy(periodStart = "2026-11-03"),
            p.copy(items = listOf(p.items.single().copy(entries = listOf(fact().copy(localDate = "2026-11-01")) ))),
            p.copy(currentOpenAttendance = fact().copy(isOpen = true)))) {
            assertThrows(IllegalArgumentException::class.java) { validateAttendancePage(bad, "week", "2026-11-02", actor, branch) }
        }
        assertThrows(Exception::class.java) { StaffTimeJson.decodeFromString<AttendanceClockFact>("""{"id":"$actor","user_id":"$actor","branch_id":"$branch","local_date":"2026-11-02","clock_in_at":"2026-11-02T00:00:00Z","clock_out_at":null,"is_open":true,"break_minutes":0}""") }
    }
    @Test fun weeksAreMondayAndMonthsCalendarBoundaries() {
        assertEquals(LocalDate.parse("2026-11-02"), attendancePeriodStart("week", LocalDate.parse("2026-11-08")))
        assertEquals(LocalDate.parse("2026-12-01"), attendancePeriodStart("month", LocalDate.parse("2026-12-31")))
        assertEquals(LocalDate.parse("2027-01-01"), attendancePeriodEnd("month", LocalDate.parse("2026-12-01")))
    }
    private fun validatePair(first: AttendanceCardsPage, next: AttendanceCardsPage) {
        validateAttendancePage(first, "week", "2026-11-02", actor, branch)
        validateAttendancePage(next, "week", "2026-11-02", actor, branch, more = true)
    }
    @Test fun promisedContinuationCannotSwitchPersonAndValidPersonBoundaryRemainsAllowed() {
        val first = page()
        val other = "66666666-6666-4666-8666-666666666666"
        val next = first.copy(items = listOf(first.items.single().copy(userId = other, clockInDayCount = 1,
            entries = listOf(fact("77777777-7777-4777-8777-777777777777").copy(userId = other)),
            continuedFromPrevious = false, entriesContinued = false)), nextCursor = null)
        validatePair(first, next)
        assertThrows(IllegalArgumentException::class.java) { mergeAttendancePages(first, next) }
        val ended = first.copy(items = listOf(first.items.single().copy(entriesContinued = false)))
        validatePair(ended, next)
        assertEquals(2, mergeAttendancePages(ended, next).items.size)
    }
    @Test fun promisedContinuationCannotEndAtAnEmptyTerminalPage() {
        val first = page(); val empty = first.copy(items = emptyList(), nextCursor = null)
        validatePair(first, empty)
        assertThrows(IllegalArgumentException::class.java) { mergeAttendancePages(first, empty) }
        val ended = first.copy(items = listOf(first.items.single().copy(entriesContinued = false)))
        assertFalse(mergeAttendancePages(ended, empty).items.single().entriesContinued)
    }
    @Test fun mergedLocalDayUnionCannotExceedFullCountButTwoClocksOnOneDayAreValid() {
        val first = page().copy(items = listOf(page().items.single().copy(clockInDayCount = 1)))
        val second = first.copy(items = listOf(first.items.single().copy(entries = listOf(fact("55555555-5555-4555-8555-555555555555", "2026-11-03")),
            continuedFromPrevious = true, entriesContinued = false)), nextCursor = null)
        validatePair(first, second)
        assertThrows(IllegalArgumentException::class.java) { mergeAttendancePages(first, second) }
        val sameDay = second.copy(items = listOf(second.items.single().copy(entries = listOf(fact("55555555-5555-4555-8555-555555555555")))))
        validatePair(first, sameDay)
        assertEquals(2, mergeAttendancePages(first, sameDay).items.single().entries.size)
    }
    @Test fun crossPageEqualTimestampUsesStrictUuidTiebreakAndOnlyLastCanContinue() {
        val first = page().copy(items = listOf(page().items.single().copy(entries = listOf(fact("55555555-5555-4555-8555-555555555555")))))
        val descending = first.copy(items = listOf(first.items.single().copy(entries = listOf(fact()),
            continuedFromPrevious = true, entriesContinued = false)), nextCursor = null)
        validatePair(first, descending)
        assertThrows(IllegalArgumentException::class.java) { mergeAttendancePages(first, descending) }
        val ascending = descending.copy(items = listOf(descending.items.single().copy(entries = listOf(fact("66666666-6666-4666-8666-666666666666")))))
        validatePair(first, ascending); assertEquals(2, mergeAttendancePages(first, ascending).items.single().entries.size)
        val invalidShape = first.copy(items = first.items + first.items.single().copy(userId = "77777777-7777-4777-8777-777777777777"), nextCursor = "nextCursor")
        assertThrows(IllegalArgumentException::class.java) { mergeAttendancePages(invalidShape, first.copy(items = emptyList(), nextCursor = null)) }
    }

}
