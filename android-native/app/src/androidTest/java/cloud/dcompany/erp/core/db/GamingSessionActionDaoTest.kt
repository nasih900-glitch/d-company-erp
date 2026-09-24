package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GamingSessionActionDaoTest {
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
    fun tearDown() = db.close()

    @Test
    fun perSessionSequenceIsMonotonicAndIndependentAcrossSessions() = runBlocking {
        val join = dao.captureSessionAction(action("join", "session-a", GamingSessionActionType.PARTICIPANT_JOIN))
        val leave = dao.captureSessionAction(action("leave", "session-a", GamingSessionActionType.PARTICIPANT_LEAVE))
        val other = dao.captureSessionAction(action("amend", "session-b", GamingSessionActionType.AMEND))

        assertEquals(1L, join.sequence)
        assertEquals(2L, leave.sequence)
        assertEquals(1L, other.sequence)
        assertEquals(listOf("join", "leave", "amend"), dao.sessionActionsForSync().map { it.actionId })
    }

    @Test
    fun actionEvidenceCannotBeReplacedAndJoinResultResolvesLaterLeave() = runBlocking {
        dao.captureSessionAction(action("join", "session-a", GamingSessionActionType.PARTICIPANT_JOIN))
        dao.captureSessionAction(
            action("leave", "session-a", GamingSessionActionType.PARTICIPANT_LEAVE)
                .copy(participantReference = "join"),
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                dao.insertSessionAction(action("join", "session-a", GamingSessionActionType.AMEND).copy(sequence = 9))
            }
        }
        assertEquals(1, dao.markSessionActionConfirmed("join", 2_000, "participant-server-1"))
        assertEquals("participant-server-1", dao.sessionAction("join")?.resultParticipantId)
        assertEquals("join", dao.sessionAction("leave")?.participantReference)
    }

    @Test
    fun definitivelyRejectedActionCanBeRetainedAsDiscardedEvidence() = runBlocking {
        dao.captureSessionAction(action("join", "session-a", GamingSessionActionType.PARTICIPANT_JOIN))
        assertEquals(1, dao.markSessionActionRejected("join", "Customer was deleted"))
        assertEquals(1, dao.discardRejectedSessionAction("join", 2_000))

        assertEquals(GamingSessionActionState.DISCARDED, dao.sessionAction("join")?.state)
        assertEquals(emptyList<LocalGamingSessionActionEntity>(), dao.sessionActionsForSync())
    }

    @Test
    fun amendmentJoinLeaveExtensionAndStopRemainInOneFifoAcrossRestart() = runBlocking {
        db.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gaming-action-restart-${System.nanoTime()}"
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, ErpDatabase::class.java, name).build()
        dao = db.gamingDao()

        listOf(
            "amend" to GamingSessionActionType.AMEND,
            "join" to GamingSessionActionType.PARTICIPANT_JOIN,
            "leave" to GamingSessionActionType.PARTICIPANT_LEAVE,
            "extend" to GamingSessionActionType.EXTEND,
            "stop" to GamingSessionActionType.STOP,
        ).forEach { (id, type) -> dao.captureSessionAction(action(id, "session-a", type)) }
        db.close()

        db = Room.databaseBuilder(context, ErpDatabase::class.java, name).build()
        dao = db.gamingDao()
        assertEquals(
            listOf("amend", "join", "leave", "extend", "stop"),
            dao.sessionActionsForSync().map { it.actionId },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), dao.sessionActionsForSync().map { it.sequence })

        assertEquals(1, dao.markSessionActionConfirmed("amend", 2_000))
        assertEquals(0, dao.markSessionActionConfirmed("amend", 2_001))
        assertNotNull(dao.sessionAction("amend"))
        assertEquals(
            listOf("join", "leave", "extend", "stop"),
            dao.sessionActionsForSync().map { it.actionId },
        )
        db.close()
        context.deleteDatabase(name)
        db = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java).build()
        dao = db.gamingDao()
    }

    private fun action(id: String, session: String, type: String) = LocalGamingSessionActionEntity(
        actionId = id,
        sessionKey = session,
        actionType = type,
        ownerCompanyId = "company-1",
        ownerUserId = "user-1",
        branchId = "branch-1",
        terminalId = "terminal-1",
        serverSessionId = session,
        shiftId = "shift-1",
        occurredAtMillis = 1_000,
        playElapsedMs = 0,
        expectedPauseVersion = 0,
        expectedParticipantRevision = 0,
        expectedBillingRevision = 0,
    )
}
