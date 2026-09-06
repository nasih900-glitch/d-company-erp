package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.db.GamingSessionState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GamingPausePolicyTest {
    private val start = Instant.parse("2026-09-05T10:00:00Z").toEpochMilli()
    private fun session() = GameSession(
        id = "session", stationId = "station", shiftId = "shift", status = "active",
        startAt = Instant.ofEpochMilli(start).toString(), ratePerHourMinor = 12_000L,
        pauseVersion = 0, pausedDurationMs = 0L, pauseAvailable = true,
    )

    @Test
    fun `paused timer freezes at exact server instant across later renders and cache roundtrip`() {
        val paused = session().copy(
            status = "paused", pausedAt = Instant.ofEpochMilli(start + 90_123).toString(),
            pausedDurationMs = 12_345, pauseVersion = 1,
        )
        assertEquals(77_778L, sessionPlayElapsedMillis(paused, start + 100_000))
        assertEquals(77_778L, sessionPlayElapsedMillis(paused, start + 3_600_000))
        val restored = paused.toCacheEntity().toGameSession()
        assertEquals(paused.pausedAt, restored.pausedAt)
        assertEquals(1, restored.pauseVersion)
        assertEquals(77_778L, sessionPlayElapsedMillis(restored, start + 86_400_000))
        assertEquals(400L, estimatedCurrentAmountMinor(restored, start + 86_400_000))
    }

    @Test
    fun `resume subtracts completed milliseconds once including converted legacy minutes`() {
        val resumed = session().copy(pausedDurationMs = 90_123, pausedMinutes = 1, pauseVersion = 2)
        assertEquals(209_877L, sessionPlayElapsedMillis(resumed, start + 300_000))
        assertEquals(800L, estimatedCurrentAmountMinor(resumed, start + 300_000))
        assertEquals(240_000L, sessionPlayElapsedMillis(resumed.copy(pausedDurationMs = null), start + 300_000))
    }

    @Test
    fun `offline stop while paused freezes before captured stop and package price never changes`() {
        val stopping = session().copy(
            status = "stopping", endAt = Instant.ofEpochMilli(start + 300_000).toString(),
            pausedAt = Instant.ofEpochMilli(start + 90_000).toString(),
            pausedDurationMs = 10_000, localState = GamingSessionState.STOP_PENDING,
        )
        assertEquals(80_000L, sessionPlayElapsedMillis(stopping, start + 3_600_000))
        val ended = stopping.copy(status = "ended", pausedAt = null, pausedDurationMs = 220_000)
        assertEquals(80_000L, sessionPlayElapsedMillis(ended, start + 3_600_000))
        assertEquals(8_000L, estimatedCurrentAmountMinor(
            stopping.copy(billingMode = "package", packageId = "package", amountMinor = 8_000),
            start + 3_600_000,
        ))
    }

    @Test
    fun `invalid or legacy unclocked pause never invents a time or charge`() {
        assertNull(sessionPlayElapsedMillis(session().copy(status = "paused"), start + 60_000))
        assertNull(estimatedCurrentAmountMinor(session().copy(status = "paused"), start + 60_000))
        assertNull(sessionPlayElapsedMillis(session().copy(startAt = "bad"), start))
        assertNull(sessionPlayElapsedMillis(session().copy(pausedDurationMs = -1), start))
    }

    @Test
    fun `pause commands require verified version current operational state and a real reason`() {
        assertNull(pauseActionError(session(), true, "Controller replacement"))
        assertNotNull(pauseActionError(session().copy(pauseAvailable = false), true, "Break"))
        assertNotNull(pauseActionError(session(), true, "  "))
        assertNotNull(pauseActionError(session().copy(pauseVersion = null), true, "Break"))
        assertNotNull(pauseActionError(session().copy(localState = GamingSessionState.STOP_PENDING), true, "Break"))
        assertNotNull(pauseActionError(session(), false, "Continue session"))
        assertNotNull(pauseActionError(session().copy(status = "paused"), false, "Continue session"))
        assertNull(pauseActionError(
            session().copy(status = "paused", pausedAt = Instant.ofEpochMilli(start).toString(), pauseAvailable = false),
            false, "Continue session",
        ))
    }

    @Test
    fun `wire snapshots retain precise pause facts and old API payload stays compatible`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString<GameSession>(
            """{"id":"session","station_id":"station","status":"active","start_at":"2026-09-05T10:00:00Z","paused_minutes":2}""",
        )
        assertNull(old.pauseVersion)
        assertEquals(120_000L, old.completedPauseMillis())
        val fresh = json.decodeFromString<GameSession>(
            """{"id":"session","station_id":"station","status":"paused","start_at":"2026-09-05T10:00:00Z","paused_at":"2026-09-05T10:01:30Z","paused_duration_ms":1234,"pause_version":3,"timer_alarm_version":3,"last_pause_transition_at":"2026-09-05T10:01:30Z"}""",
        )
        val cache = fresh.toCacheEntity()
        assertEquals(1_234L, cache.pausedDurationMs)
        assertEquals(3, cache.pauseVersion)
        assertEquals(start + 90_000, cache.lastPauseTransitionAtMillis)
        assertTrue(json.encodeToString(SessionPauseBody("Break", 3)).contains("\"expected_pause_version\":3"))
    }
}
