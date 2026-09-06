package cloud.dcompany.erp.ui.screens.gaming

import java.time.Instant
import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GamingSessionTimestampTest {
    private fun session() = GameSession(
        id = "session-1",
        stationId = "station-1",
        status = "active",
        startAt = "2026-09-04T18:00:00Z",
        timerEndsAt = "2026-09-04T19:00:00Z",
        timerMinutes = 60,
    )

    @Test
    fun `authoritative start and alarm deadline survive cache projection exactly`() {
        val cached = session().toCacheEntity()
        assertEquals(Instant.parse("2026-09-04T18:00:00Z").toEpochMilli(), cached.startAtMillis)
        assertEquals(Instant.parse("2026-09-04T19:00:00Z").toEpochMilli(), cached.timerEndsAtMillis)
    }

    @Test
    fun `invalid timestamps refuse refresh instead of inventing time or dropping deadline`() {
        listOf(
            session().copy(startAt = "invalid"),
            session().copy(timerEndsAt = "invalid"),
            session().copy(status = "ended", endAt = "invalid"),
        ).forEach { row ->
            assertThrows(DateTimeParseException::class.java) { row.toCacheEntity() }
        }
    }
}
