package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftHistoryRow
import cloud.dcompany.erp.core.db.ShiftHistorySource
import cloud.dcompany.erp.core.db.ShiftSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftBusinessDayPresentationTest {

    @Test
    fun `two drawer lifecycles on one IST date become one business-day collection`() {
        val morning = history(
            id = "morning",
            openedAt = "2026-09-21T01:37:21Z",
            closedAt = "2026-09-21T06:30:56Z",
            grossMinor = 105_000,
            refundsMinor = 5_000,
            netMinor = 100_000,
            opener = "Sameer",
            closer = "Sameer",
        )
        val evening = history(
            id = "evening",
            openedAt = "2026-09-21T06:36:18Z",
            closedAt = "2026-09-21T16:36:59Z",
            grossMinor = 316_000,
            refundsMinor = 1_000,
            netMinor = 315_000,
            opener = "Sameer",
            closer = "Nasih",
        )

        val days = groupShiftHistoryByBusinessDay(listOf(evening, morning))

        assertEquals(1, days.size)
        val day = days.single()
        assertEquals("2026-09-21", day.businessDate.toString())
        assertEquals(listOf("morning", "evening"), day.segments.map(ShiftHistoryRow::stableId))
        assertEquals(morning.openedAtMillis, day.firstOpenedAtMillis)
        assertEquals(evening.closedAtMillis, day.finalClosedAtMillis)
        assertEquals(false, day.hasOpenSegment)
        assertEquals("Sameer", day.firstOpenerLabel)
        assertEquals("Nasih", day.finalCloserLabel)
        assertEquals(421_000L, day.grossCollectionsMinor)
        assertEquals(6_000L, day.totalRefundsMinor)
        assertEquals(415_000L, day.netCollectionsMinor)
    }

    @Test
    fun `business date and visible times always use Asia Kolkata`() {
        val afterMidnightInIndia = history(
            id = "boundary",
            openedAt = "2026-09-20T20:00:00Z",
            closedAt = "2026-09-20T21:00:00Z",
        )

        val day = groupShiftHistoryByBusinessDay(listOf(afterMidnightInIndia)).single()

        assertEquals("2026-09-21", day.businessDate.toString())
        assertEquals("1:30 AM IST", formatShiftBusinessTime(afterMidnightInIndia.openedAtMillis))
    }

    @Test
    fun `missing accounting on one immutable segment never becomes a false zero`() {
        val confirmed = history(id = "confirmed", grossMinor = 100_000, refundsMinor = 0, netMinor = 100_000)
        val awaitingHistory = history(
            id = "local",
            openedAt = "2026-09-21T06:36:18Z",
            closedAt = "2026-09-21T16:36:59Z",
            source = ShiftHistorySource.LOCAL,
            grossMinor = null,
            refundsMinor = null,
            netMinor = null,
            closer = null,
        )

        val day = groupShiftHistoryByBusinessDay(listOf(confirmed, awaitingHistory)).single()

        assertNull(day.grossCollectionsMinor)
        assertNull(day.totalRefundsMinor)
        assertNull(day.netCollectionsMinor)
        assertEquals("closer attribution pending", day.finalCloserLabel)
    }

    @Test
    fun `ui state merges a reopened current shift so the old final close is not shown`() {
        val closedMorning = history(id = "closed-morning")
        val openEvening = openShift(
            id = "open-evening",
            openedAt = "2026-09-21T10:00:00Z",
        )

        val day = ShiftUiState(
            open = openEvening,
            history = listOf(closedMorning),
        ).businessDayHistory.single()

        assertTrue(day.hasOpenSegment)
        assertNull(day.finalClosedAtMillis)
        assertEquals("close time unavailable", day.finalCloserLabel)
        assertEquals(2, day.segments.size)
        assertNull(day.segments.last().closedAtMillis)
        assertEquals(20_000L, day.grossCollectionsMinor)
        assertEquals(20_000L, day.netCollectionsMinor)
    }

    @Test
    fun `closed history wins a current-open race for the same server shift`() {
        val closed = history(id = "same-shift")
        val staleCurrent = openShift(id = "same-shift")

        val day = groupShiftHistoryByBusinessDay(listOf(closed), staleCurrent).single()

        assertFalse(day.hasOpenSegment)
        assertEquals(listOf("same-shift"), day.segments.map(ShiftHistoryRow::stableId))
        assertEquals(closed.closedAtMillis, day.finalClosedAtMillis)
        assertEquals(10_000L, day.grossCollectionsMinor)
    }

    @Test
    fun `offline local open segment remains visible but never invents collection totals`() {
        val closedMorning = history(id = "closed-morning")
        val offlineEvening = openShift(
            id = "local-open",
            openedAt = "2026-09-21T10:00:00Z",
            source = ShiftSource.LOCAL_OUTBOX,
            grossMinor = null,
            refundsMinor = null,
            netMinor = null,
        )

        val day = groupShiftHistoryByBusinessDay(listOf(closedMorning), offlineEvening).single()

        assertTrue(day.hasOpenSegment)
        assertEquals(ShiftHistorySource.LOCAL, day.segments.last().source)
        assertNull(day.grossCollectionsMinor)
        assertNull(day.totalRefundsMinor)
        assertNull(day.netCollectionsMinor)
    }

    @Test
    fun `closed row missing close time needs review without claiming the drawer is open`() {
        val incomplete = history(id = "legacy-incomplete", closedAt = null, closer = null)

        val day = groupShiftHistoryByBusinessDay(listOf(incomplete)).single()

        assertFalse(day.hasOpenSegment)
        assertNull(day.finalClosedAtMillis)
    }

    @Test
    fun `separate IST dates remain separate business-day collections`() {
        val days = groupShiftHistoryByBusinessDay(
            listOf(
                history(id = "day-one", openedAt = "2026-09-20T10:00:00Z"),
                history(id = "day-two", openedAt = "2026-09-21T10:00:00Z"),
            ),
        )

        assertEquals(listOf("2026-09-21", "2026-09-20"), days.map { it.businessDate.toString() })
        assertTrue(days.all { it.segments.size == 1 })
    }

    private fun history(
        id: String,
        openedAt: String = "2026-09-21T01:37:21Z",
        closedAt: String? = "2026-09-21T06:30:56Z",
        grossMinor: Long? = 10_000,
        refundsMinor: Long? = 0,
        netMinor: Long? = 10_000,
        opener: String? = "Sameer",
        closer: String? = "Sameer",
        source: ShiftHistorySource = ShiftHistorySource.SERVER,
    ) = ShiftHistoryRow(
        stableId = id,
        serverShiftId = id,
        source = source,
        openedAtMillis = Instant.parse(openedAt).toEpochMilli(),
        closedAtMillis = closedAt?.let { Instant.parse(it).toEpochMilli() },
        openingFloatMinor = 0,
        expectedMinor = 0,
        countedMinor = 0,
        varianceMinor = 0,
        grossCollectionsMinor = grossMinor,
        cashCollectionsMinor = grossMinor,
        cardCollectionsMinor = 0,
        upiCollectionsMinor = 0,
        otherCollectionsMinor = 0,
        totalRefundsMinor = refundsMinor,
        netCollectionsMinor = netMinor,
        openedByUserId = opener?.lowercase(),
        openedByName = opener,
        openedByEmail = null,
        closedByUserId = closer?.lowercase(),
        closedByName = closer,
        closedByEmail = null,
    )

    private fun openShift(
        id: String,
        openedAt: String = "2026-09-21T10:00:00Z",
        source: ShiftSource = ShiftSource.SERVER_CACHE,
        grossMinor: Long? = 10_000,
        refundsMinor: Long? = 0,
        netMinor: Long? = 10_000,
    ) = ResolvedOpenShift(
        shiftId = id,
        source = source,
        openedAtMillis = Instant.parse(openedAt).toEpochMilli(),
        openingFloatMinor = 0,
        expectedMinor = grossMinor,
        posCollectionsMinor = grossMinor,
        membershipCollectionsMinor = 0,
        grossCollectionsMinor = grossMinor,
        cashCollectionsMinor = grossMinor,
        cardCollectionsMinor = 0,
        upiCollectionsMinor = 0,
        otherCollectionsMinor = 0,
        settledPosRefundsMinor = refundsMinor,
        settledMembershipRefundsMinor = 0,
        totalRefundsMinor = refundsMinor,
        netCollectionsMinor = netMinor,
        openedByUserId = "sameer",
        openedByName = "Sameer",
        openedByEmail = "sameer@dcompany.local",
    )
}
