package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamingDaoRecoveryTest {

    private lateinit var db: ErpDatabase
    private lateinit var dao: GamingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.gamingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun legacyRejectedRowsRecoverIntoTheCorrectHumanRetryQueues() = runBlocking {
        dao.insertLocalSession(legacyRow("start", serverId = null, status = "starting"))
        dao.insertLocalSession(legacyRow("stop", serverId = "session-2", status = "stopping"))
        dao.insertLocalSession(
            legacyRow(
                "send",
                serverId = "session-3",
                status = "ended",
                endAtMillis = 2_000,
                billableMinutes = 15,
                amountMinor = 37_500,
            ),
        )
        dao.insertLocalSession(
            legacyRow(
                "sent",
                serverId = "session-4",
                status = "ended",
                endAtMillis = 2_100,
                amountMinor = 40_000,
                orderId = "order-4",
            ),
        )

        val quarantined = db.outboxSafetyDao().unresolvedGroups().single()
        assertEquals("gaming_sessions", quarantined.resource)
        assertEquals("rejected", quarantined.state)
        assertEquals(4, quarantined.count)

        assertEquals(4, dao.recoverLegacyRejectedSessions())
        assertEquals(0, dao.recoverLegacyRejectedSessions())
        assertEquals(3, dao.observeRejectedCount().first())

        val start = dao.localSessionById("start")!!
        assertEquals(GamingSessionState.START_REJECTED, start.state)
        assertEquals("start_failed", start.status)
        assertEquals("legacy failure", start.lastError)

        val stop = dao.localSessionById("stop")!!
        assertEquals(GamingSessionState.STOP_REJECTED, stop.state)
        assertEquals("active", stop.status)
        assertEquals("session-2", stop.serverId)

        val send = dao.localSessionById("send")!!
        assertEquals(GamingSessionState.SEND_REJECTED, send.state)
        assertEquals("ended", send.status)
        assertEquals(37_500L, send.amountMinor)

        val sent = dao.localSessionById("sent")!!
        assertEquals(GamingSessionState.SENT, sent.state)
        assertEquals("order-4", sent.orderId)

        assertEquals(1, dao.retryRejectedStart("start"))
        dao.requestSessionStop("stop")
        assertEquals(1, dao.requestSessionSend("send"))

        val pushable = dao.pushableSessions().associateBy { it.localId }
        assertEquals(GamingSessionState.START_PENDING, pushable.getValue("start").state)
        assertEquals("starting", pushable.getValue("start").status)
        assertNull(pushable.getValue("start").lastError)
        assertEquals(GamingSessionState.STOP_PENDING, pushable.getValue("stop").state)
        assertEquals("stopping", pushable.getValue("stop").status)
        assertEquals(GamingSessionState.SEND_PENDING, pushable.getValue("send").state)
        assertNull(pushable.getValue("send").lastError)
        assertEquals(3, pushable.size)
    }

    @Test
    fun authoritativeServerOutcomesResolveStaleLocalOverlays() = runBlocking {
        dao.insertLocalSession(localRow("stopped", "session-stop", GamingSessionState.STOP_PENDING))
        dao.insertLocalSession(localRow("sent", "session-send", GamingSessionState.SEND_REJECTED))
        dao.insertLocalSession(localRow("cancelled", "session-cancel", GamingSessionState.SEND_REJECTED))

        dao.replaceSessionCache(
            listOf(
                serverRow("session-stop", status = "ended", amountMinor = 12_000),
                serverRow("session-send", status = "ended", amountMinor = 15_000, orderId = "order-1"),
                serverRow("session-cancel", status = "cancelled", amountMinor = 0),
            ),
        )

        assertEquals(
            GamingSessionState.ENDED_UNBILLED,
            dao.localSessionById("stopped")?.state,
        )
        assertEquals(GamingSessionState.SENT, dao.localSessionById("sent")?.state)
        assertEquals("order-1", dao.localSessionById("sent")?.orderId)
        assertEquals(GamingSessionState.CANCELLED, dao.localSessionById("cancelled")?.state)

        val visibleOverlays = dao.observeActiveLocalSessions().first()
        assertEquals(listOf("stopped"), visibleOverlays.map { it.localId })
    }

    @Test
    fun rapidSecondStartIsRejectedUntilTheFirstLifecycleIsTerminal() = runBlocking {
        val first = localRow(
            localId = "first",
            serverId = null,
            state = GamingSessionState.START_PENDING,
            stationId = "station-shared",
        )
        val second = first.copy(localId = "second")

        assertTrue(dao.insertStartIfStationAvailable(first))
        assertFalse(dao.insertStartIfStationAvailable(second))

        dao.markSessionSent("first", orderId = "order-first", amountMinor = 10_000)
        assertTrue(dao.insertStartIfStationAvailable(second))
    }

    @Test
    fun confirmedStartCannotRemainStrandedInStartPendingAfterRestart() = runBlocking {
        val row = localRow(
            localId = "confirmed-start",
            serverId = null,
            state = GamingSessionState.START_PENDING,
        )
        dao.insertLocalSession(row)

        dao.setSessionStarted(
            localId = row.localId,
            serverId = "session-confirmed",
            status = "active",
            startedAtMillis = 2_000,
            timerEndsAtMillis = 62_000,
        )

        val atomicallyConfirmed = dao.localSessionById(row.localId)!!
        assertEquals(GamingSessionState.START_SYNCED, atomicallyConfirmed.state)
        assertEquals("session-confirmed", atomicallyConfirmed.serverId)
        assertEquals(1, dao.requestSessionStop(row.localId))

        dao.applyServerReconciliation(
            localId = row.localId,
            state = GamingSessionState.START_PENDING,
            status = "active",
            endAtMillis = null,
            billableMinutes = null,
            amountMinor = null,
            orderId = null,
        )
        dao.replaceSessionCache(listOf(serverRow("session-confirmed", status = "active", amountMinor = 0)))
        assertEquals(GamingSessionState.START_SYNCED, dao.localSessionById(row.localId)?.state)
    }

    private fun legacyRow(
        localId: String,
        serverId: String?,
        status: String,
        endAtMillis: Long? = null,
        billableMinutes: Int? = null,
        amountMinor: Long? = null,
        orderId: String? = null,
    ) = LocalGamingSessionEntity(
        localId = localId,
        serverId = serverId,
        stationId = "station-$localId",
        shiftId = if (serverId == null) "shift-1" else null,
        startedAtMillis = 1_000,
        state = "rejected",
        status = status,
        endAtMillis = endAtMillis,
        billableMinutes = billableMinutes,
        amountMinor = amountMinor,
        orderId = orderId,
        lastError = "legacy failure",
    )

    private fun localRow(
        localId: String,
        serverId: String?,
        state: String,
        stationId: String = "station-$localId",
    ) = LocalGamingSessionEntity(
        localId = localId,
        serverId = serverId,
        stationId = stationId,
        startedAtMillis = 1_000,
        state = state,
        status = when (state) {
            GamingSessionState.START_PENDING -> "starting"
            GamingSessionState.STOP_PENDING -> "stopping"
            else -> "ended"
        },
        endAtMillis = 2_000L.takeIf { state == GamingSessionState.SEND_REJECTED },
        amountMinor = 10_000L.takeIf { state == GamingSessionState.SEND_REJECTED },
        lastError = "stale local state",
    )

    private fun serverRow(
        id: String,
        status: String,
        amountMinor: Long,
        orderId: String? = null,
    ) = GamingSessionCacheEntity(
        id = id,
        stationId = "station-${id.removePrefix("session-")}",
        status = status,
        startAtMillis = 1_000,
        endAtMillis = 2_000,
        billableMinutes = if (status == "cancelled") 0 else 15,
        amountMinor = amountMinor,
        orderId = orderId,
    )
}
