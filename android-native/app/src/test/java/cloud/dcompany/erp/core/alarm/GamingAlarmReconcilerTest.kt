package cloud.dcompany.erp.core.alarm

import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GamingAlarmReconcilerTest {
    private val station = GamingStationEntity("station-1", "PS5", "PS5 One", "ps5", 12_000, true)

    @Test
    fun `authoritative pause cancels stale local alarm and resume uses new server deadline`() {
        val cached = GamingSessionCacheEntity(
            id = "server-session", stationId = station.id, status = "paused",
            startAtMillis = 1_000, pausedAtMillis = 30_000, pausedDurationMs = 0,
            pauseVersion = 1, timerEndsAtMillis = null,
        )
        val staleLocal = LocalGamingSessionEntity(
            localId = "local-session", serverId = cached.id, stationId = station.id,
            startedAtMillis = 1_000, state = GamingSessionState.START_SYNCED,
            status = "active", timerEndsAtMillis = 61_000,
        )
        assertEquals(emptyList<GamingAlarmCandidate>(), gamingAlarmCandidates(
            listOf(cached), listOf(staleLocal), listOf(station),
        ))
        assertEquals(
            listOf(GamingAlarmCandidate(cached.id, station.id, station.name, 91_123)),
            gamingAlarmCandidates(
                listOf(cached.copy(status = "active", pausedAtMillis = null,
                    pausedDurationMs = 30_123, pauseVersion = 2, timerEndsAtMillis = 91_123)),
                listOf(staleLocal), listOf(station),
            ),
        )
        assertEquals(emptyList<GamingAlarmCandidate>(), gamingAlarmCandidates(
            emptyList(), listOf(staleLocal.copy(status = "paused")), listOf(station),
        ))
    }

    @Test
    fun `pending local stop keeps server alarm until stop is confirmed`() {
        val cache = listOf(
            GamingSessionCacheEntity(
                id = "server-session",
                stationId = station.id,
                status = "active",
                startAtMillis = 1_000,
                timerEndsAtMillis = 61_000,
            ),
        )
        val local = listOf(
            LocalGamingSessionEntity(
                localId = "local-stop",
                serverId = "server-session",
                stationId = station.id,
                startedAtMillis = 1_000,
                state = GamingSessionState.STOP_PENDING,
                status = "active",
            ),
        )

        assertEquals(
            listOf(GamingAlarmCandidate("server-session", station.id, "PS5 One", 61_000)),
            gamingAlarmCandidates(cache, local, listOf(station)),
        )
    }

    @Test
    fun `pending local start arms its captured timer before server confirmation`() {
        val local = LocalGamingSessionEntity(
            localId = "local-start",
            stationId = station.id,
            timerMinutes = 60,
            timerEndsAtMillis = 61_000,
            startedAtMillis = 1_000,
            state = GamingSessionState.START_PENDING,
            status = "starting",
        )

        assertEquals(
            listOf(GamingAlarmCandidate("local-start", station.id, "PS5 One", 61_000)),
            gamingAlarmCandidates(emptyList(), listOf(local), listOf(station)),
        )
    }

    @Test
    fun `rejected stop keeps alarm because server session is still active`() {
        val cache = GamingSessionCacheEntity(
            id = "server-session",
            stationId = station.id,
            status = "active",
            startAtMillis = 1_000,
            timerEndsAtMillis = 61_000,
        )
        val local = LocalGamingSessionEntity(
            localId = "local-stop",
            serverId = cache.id,
            stationId = station.id,
            startedAtMillis = 1_000,
            state = GamingSessionState.STOP_REJECTED,
            status = "active",
            lastError = "shift mismatch",
        )

        assertEquals(
            listOf(GamingAlarmCandidate("server-session", station.id, "PS5 One", 61_000)),
            gamingAlarmCandidates(listOf(cache), listOf(local), listOf(station)),
        )
    }

    @Test
    fun `local active session replaces cache and uses canonical server id`() {
        val local = LocalGamingSessionEntity(
            localId = "local-session",
            serverId = "server-session",
            stationId = station.id,
            startedAtMillis = 1_000,
            state = GamingSessionState.START_SYNCED,
            status = "active",
            timerEndsAtMillis = 91_000,
        )
        val cache = GamingSessionCacheEntity(
            id = "server-session",
            stationId = station.id,
            status = "active",
            startAtMillis = 1_000,
            timerEndsAtMillis = 61_000,
        )

        assertEquals(
            listOf(GamingAlarmCandidate("server-session", station.id, "PS5 One", 91_000)),
            gamingAlarmCandidates(listOf(cache), listOf(local), listOf(station)),
        )
    }
}
